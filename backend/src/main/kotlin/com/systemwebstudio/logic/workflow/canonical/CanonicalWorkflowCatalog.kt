package com.systemwebstudio.logic.workflow.canonical

import com.systemwebstudio.logic.action.DefinitionIssue
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.PrincipalSpecs
import com.systemwebstudio.logic.action.canonical.AppDefinitionSource
import com.systemwebstudio.logic.action.canonical.CanonicalActionReader
import com.systemwebstudio.logic.action.canonical.array
import com.systemwebstudio.logic.action.canonical.text
import com.systemwebstudio.logic.scheduler.CronExpression
import com.systemwebstudio.logic.workflow.ApprovalSpec
import com.systemwebstudio.logic.workflow.Branch
import com.systemwebstudio.logic.workflow.CompareOp
import com.systemwebstudio.logic.workflow.Condition
import com.systemwebstudio.logic.workflow.RetryPolicy
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.ValueRef
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowDefinitionProvider
import com.systemwebstudio.logic.workflow.WorkflowDefinitionValidator
import com.systemwebstudio.logic.workflow.WorkflowLimits
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.WorkflowTrigger
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/** A `trigger: SCHEDULE` workflow of the AppDefinition: the platform registers a Schedule row from it when the app is published. */
data class DeclaredSchedule(val workflowId: String, val cron: String, val timezone: String)

class ParsedWorkflows(val workflows: Map<String, WorkflowDefinition>, val issues: Map<String, List<DefinitionIssue>>, val schedules: List<DeclaredSchedule>)

/**
 * Reads `workflows[]` of the canonical AppDefinition. C2's `WorkflowDef` (id, trigger, schedule, steps[{id, actionRef, next, onError}])
 * maps to a chain of ACTION steps; the richer step kinds (`kind`: WAIT / APPROVAL / BRANCH / END, `inputs`, `retry`, `timeoutMillis`,
 * `compensationActionRef`, workflow-level `limits` / `compensateOnCancel`) are additive fields proposed to C2 (BLOCKERS B-C4-03) and are
 * accepted when present. A workflow whose references do not resolve inside the same document is **not offered**.
 */
object CanonicalWorkflowReader {

    fun parse(tenantId: UUID, appId: UUID, doc: JsonNode): ParsedWorkflows {
        val actionIds = CanonicalActionReader.parse(tenantId, appId, doc).actions.keys
        val defs = linkedMapOf<String, WorkflowDefinition>()
        val issues = linkedMapOf<String, List<DefinitionIssue>>()
        val schedules = mutableListOf<DeclaredSchedule>()

        for (node in doc.array("workflows")) {
            val id = node.text("id") ?: continue
            val problems = mutableListOf<DefinitionIssue>()
            val trigger = node.text("trigger")?.let { t -> WorkflowTrigger.entries.firstOrNull { it.name == t } } ?: WorkflowTrigger.MANUAL
            if (node.text("trigger") != null && WorkflowTrigger.entries.none { it.name == node.text("trigger") }) problems += DefinitionIssue("trigger", "unknown trigger")

            val steps = node.array("steps").mapIndexedNotNull { i, s -> step(s, "steps[$i]", problems) }
            for (s in steps) {
                if (s.kind == StepKind.ACTION && s.actionRef !in actionIds) problems += DefinitionIssue("steps.${s.id}.actionRef", "does not reference a server-side action of this app")
                s.compensationActionRef?.let { if (it !in actionIds) problems += DefinitionIssue("steps.${s.id}.compensationActionRef", "does not reference an action of this app") }
            }

            var tz = "UTC"
            if (trigger == WorkflowTrigger.SCHEDULE) {
                val cron = node.text("schedule")
                tz = node.text("timezone") ?: "UTC"
                if (cron == null) problems += DefinitionIssue("schedule", "a cron expression is required for SCHEDULE")
                else {
                    try { CronExpression.parse(cron) } catch (e: IllegalArgumentException) { problems += DefinitionIssue("schedule", e.message ?: "invalid cron") }
                    try { ZoneId.of(tz) } catch (e: Exception) { problems += DefinitionIssue("timezone", "unknown timezone") }
                    if (problems.none { it.path == "schedule" || it.path == "timezone" }) schedules += DeclaredSchedule(id, cron, tz)
                }
            } else if (node.text("schedule") != null) problems += DefinitionIssue("schedule", "only valid for SCHEDULE workflows")

            val limits = node.get("limits")?.takeIf { it.isObject }?.let { l ->
                val d = WorkflowLimits()
                WorkflowLimits(
                    maxSteps = l.int("maxSteps") ?: d.maxSteps, maxStepExecutions = l.int("maxStepExecutions") ?: d.maxStepExecutions,
                    maxRetries = l.int("maxRetries") ?: d.maxRetries, maxDuration = l.int("maxDurationSeconds")?.let { Duration.ofSeconds(it.toLong()) } ?: d.maxDuration,
                    maxWait = d.maxWait, maxPayloadBytes = l.int("maxPayloadBytes") ?: d.maxPayloadBytes, maxDepth = l.int("maxDepth") ?: d.maxDepth,
                    maxStepTimeout = l.int("maxStepTimeoutMillis")?.let { Duration.ofMillis(it.toLong()) } ?: d.maxStepTimeout
                )
            } ?: WorkflowLimits()

            val def = WorkflowDefinition(
                id = id, tenantId = tenantId, appId = appId, name = node.text("name") ?: id, trigger = trigger, steps = steps,
                startStepId = node.text("startStepId"), limits = limits, enabled = node.get("enabled")?.asBoolean(true) ?: true,
                compensateOnCancel = node.get("compensateOnCancel")?.asBoolean(false) ?: false
            )
            if (problems.isEmpty()) problems += WorkflowDefinitionValidator.validate(def)
            if (problems.isNotEmpty()) { issues[id] = problems; schedules.removeAll { it.workflowId == id }; continue }
            defs[id] = def
        }
        return ParsedWorkflows(defs, issues, schedules)
    }

    private fun JsonNode.int(name: String): Int? = get(name)?.takeIf { it.isNumber }?.asInt()

    private fun step(n: JsonNode, path: String, problems: MutableList<DefinitionIssue>): WorkflowStep? {
        val id = n.text("id") ?: run { problems += DefinitionIssue("$path.id", "is required"); return null }
        val kind = n.text("kind")?.let { k -> StepKind.entries.firstOrNull { it.name == k } ?: run { problems += DefinitionIssue("$path.kind", "unknown kind"); null } }
            ?: if (n.text("actionRef") != null) StepKind.ACTION else StepKind.END
        val inputs = linkedMapOf<String, ValueRef>()
        n.get("inputs")?.takeIf { it.isObject }?.let { m ->
            for (name in m.propertyNames()) valueRef(m.get(name))?.let { inputs[name] = it } ?: run { problems += DefinitionIssue("$path.inputs.$name", "invalid value reference") }
        }
        val retry = n.get("retry")?.takeIf { it.isObject }?.let { r ->
            try {
                RetryPolicy(
                    maxAttempts = r.int("maxAttempts") ?: 1, initialBackoff = Duration.ofMillis((r.int("initialBackoffMillis") ?: 1000).toLong()),
                    multiplier = r.get("multiplier")?.takeIf { it.isNumber }?.asDouble() ?: 2.0, maxBackoff = Duration.ofMillis((r.int("maxBackoffMillis") ?: 300_000).toLong())
                )
            } catch (e: IllegalArgumentException) { problems += DefinitionIssue("$path.retry", e.message ?: "invalid"); null }
        } ?: RetryPolicy()
        val approval = n.get("approval")?.takeIf { it.isObject }?.let { a ->
            val issues = mutableListOf<DefinitionIssue>()
            val approvers = PrincipalSpecs.parseAll(a.get("approvers"), "$path.approval.approvers", issues)
            problems += issues
            ApprovalSpec(
                title = a.text("title") ?: "", approvers = approvers, requiredApprovals = a.int("requiredApprovals") ?: 1,
                expiresIn = Duration.ofSeconds((a.int("expiresInSeconds") ?: 86_400).toLong()), allowSelfApproval = a.get("allowSelfApproval")?.asBoolean(false) ?: false,
                notifyTemplateRef = a.text("notifyTemplateRef"), onReject = a.text("onReject"), onExpire = a.text("onExpire")
            )
        }
        val branches = n.array("branches").mapIndexedNotNull { i, b ->
            val c = b.get("condition")?.let { condition(it, 0) }
            val next = b.text("next")
            if (c == null || next == null) { problems += DefinitionIssue("$path.branches[$i]", "needs a valid condition and next"); null } else Branch(c, next)
        }
        return WorkflowStep(
            id = id, kind = kind, actionRef = n.text("actionRef"), inputs = inputs, next = n.text("next"), onError = n.text("onError"), retry = retry,
            timeout = n.int("timeoutMillis")?.let { Duration.ofMillis(it.toLong()) }, wait = n.int("waitSeconds")?.let { Duration.ofSeconds(it.toLong()) },
            approval = approval, branches = branches, defaultNext = n.text("defaultNext"), compensationActionRef = n.text("compensationActionRef")
        )
    }

    /** `{ "from": "INPUT", "path": "a.b" }`, `{ "from": "STEP", "stepId": "s1", "path": "x" }`, `{ "from": "LITERAL", "value": … }`. */
    internal fun valueRef(n: JsonNode?): ValueRef? {
        if (n == null || !n.isObject) return null
        return when (n.text("from")) {
            "INPUT" -> ValueRef.Input(n.text("path") ?: "")
            "STEP" -> n.text("stepId")?.let { ValueRef.Step(it, n.text("path") ?: "") }
            "LITERAL" -> n.get("value")?.let { ValueRef.Literal(it) }
            else -> null
        }
    }

    /** `{ "op": "EQ", "left": ref, "right": ref }`, `{ "all": [..] }`, `{ "any": [..] }`, `{ "not": cond }`, `{ "exists": ref }`. Depth-limited while parsing. */
    internal fun condition(n: JsonNode, depth: Int): Condition? {
        if (depth > WorkflowDefinitionValidator.MAX_CONDITION_DEPTH || !n.isObject) return null
        n.get("all")?.takeIf { it.isArray }?.let { a -> val items = a.map { condition(it, depth + 1) ?: return null }; return Condition.AllOf(items) }
        n.get("any")?.takeIf { it.isArray }?.let { a -> val items = a.map { condition(it, depth + 1) ?: return null }; return Condition.AnyOf(items) }
        n.get("not")?.let { return condition(it, depth + 1)?.let { c -> Condition.Not(c) } }
        n.get("exists")?.let { return valueRef(it)?.let { r -> Condition.Exists(r) } }
        val op = n.text("op")?.let { o -> CompareOp.entries.firstOrNull { it.name == o } } ?: return null
        val l = valueRef(n.get("left")) ?: return null
        val r = valueRef(n.get("right")) ?: return null
        return Condition.Compare(l, op, r)
    }
}

class CanonicalWorkflowCatalog(private val source: AppDefinitionSource) : WorkflowDefinitionProvider {
    override fun find(tenantId: UUID, appId: UUID, workflowId: String, mode: ExecutionMode): WorkflowDefinition? =
        source.load(tenantId, appId, mode)?.let { CanonicalWorkflowReader.parse(tenantId, appId, it).workflows[workflowId] }

    fun diagnose(tenantId: UUID, appId: UUID, mode: ExecutionMode): Map<String, List<DefinitionIssue>> =
        source.load(tenantId, appId, mode)?.let { CanonicalWorkflowReader.parse(tenantId, appId, it).issues }.orEmpty()

    fun declaredSchedules(tenantId: UUID, appId: UUID, mode: ExecutionMode): List<DeclaredSchedule> =
        source.load(tenantId, appId, mode)?.let { CanonicalWorkflowReader.parse(tenantId, appId, it).schedules }.orEmpty()
}
