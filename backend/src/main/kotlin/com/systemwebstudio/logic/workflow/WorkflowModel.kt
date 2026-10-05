package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionDefinitionValidator
import com.systemwebstudio.logic.action.DefinitionIssue
import com.systemwebstudio.logic.action.InputResolver
import com.systemwebstudio.logic.action.PrincipalSpec
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/**
 * Declarative workflow definition (owner C4). Like actions, a workflow is data: steps reference actions by id, conditions are typed
 * comparisons over workflow input / earlier step outputs / literals — there is **no expression language, no script, no URL**.
 *
 * C2's `WorkflowDef` (id, trigger, schedule, steps[{id, actionRef, next, onError}]) is the subset every workflow can express; the richer
 * step kinds below are additive fields proposed to C2 (BLOCKERS B-C4-03). `logic.workflow.canonical` reads both.
 */
enum class StepKind { ACTION, WAIT, APPROVAL, BRANCH, END }

enum class WorkflowTrigger { MANUAL, SCHEDULE, ACTION }

data class RetryPolicy(
    val maxAttempts: Int = 1,
    val initialBackoff: Duration = Duration.ofSeconds(1),
    val multiplier: Double = 2.0,
    val maxBackoff: Duration = Duration.ofMinutes(5)
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(!initialBackoff.isNegative && !maxBackoff.isNegative) { "backoff must not be negative" }
        require(multiplier >= 1.0 && multiplier <= 10.0) { "multiplier must be between 1 and 10" }
    }

    /**
     * Delay before attempt `failedAttempt + 1` (exponential, capped). [failedAttempt] is 1-based. **Never below [MIN_BACKOFF]**: a definition that
     * says 0 ms (or a cap below the floor) still waits the floor, so a failing step cannot spin against its dependency or the queue.
     */
    fun backoffAfter(failedAttempt: Int): Duration {
        var millis = initialBackoff.toMillis().toDouble()
        repeat((failedAttempt - 1).coerceAtLeast(0)) { millis = minOf(millis * multiplier, maxBackoff.toMillis().toDouble()) }
        return maxOf(MIN_BACKOFF, Duration.ofMillis(minOf(millis.toLong(), maxBackoff.toMillis())))
    }

    companion object {
        /** The floor of every retry/backoff delay in the runtime. */
        val MIN_BACKOFF: Duration = Duration.ofSeconds(1)
    }
}

sealed interface ValueRef {
    /** Workflow input; empty path = the whole input. */
    data class Input(val path: String = "") : ValueRef
    /** Output of an already finished step. */
    data class Step(val stepId: String, val path: String = "") : ValueRef
    data class Literal(val value: JsonNode) : ValueRef
}

enum class CompareOp { EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS }

sealed interface Condition {
    data class Compare(val left: ValueRef, val op: CompareOp, val right: ValueRef) : Condition
    data class Exists(val ref: ValueRef) : Condition
    data class AllOf(val items: List<Condition>) : Condition
    data class AnyOf(val items: List<Condition>) : Condition
    data class Not(val item: Condition) : Condition
}

data class Branch(val condition: Condition, val next: String)

data class ApprovalSpec(
    val title: String,
    val approvers: List<PrincipalSpec>,
    val requiredApprovals: Int = 1,
    val expiresIn: Duration = Duration.ofDays(1),
    val allowSelfApproval: Boolean = false,
    val notifyTemplateRef: String? = null,
    /** Step to continue with after a rejection / expiry; null = the workflow fails. */
    val onReject: String? = null,
    val onExpire: String? = null
)

data class WorkflowStep(
    val id: String,
    val kind: StepKind,
    /** ACTION */
    val actionRef: String? = null,
    /** ACTION: input name → where its value comes from at the time the step runs. */
    val inputs: Map<String, ValueRef> = emptyMap(),
    /** Next step; null = the following step in the list, or the end of the workflow when this is the last one. */
    val next: String? = null,
    /** Step to continue with when this step fails for good (after retries); null = the workflow fails. */
    val onError: String? = null,
    val retry: RetryPolicy = RetryPolicy(),
    val timeout: Duration? = null,
    /** WAIT */
    val wait: Duration? = null,
    /** APPROVAL */
    val approval: ApprovalSpec? = null,
    /** BRANCH: first matching branch wins; [defaultNext] otherwise. */
    val branches: List<Branch> = emptyList(),
    val defaultNext: String? = null,
    /** Optional compensation: an action that undoes this ACTION step, run in reverse order when the workflow fails or is cancelled. */
    val compensationActionRef: String? = null
)

data class WorkflowLimits(
    val maxSteps: Int = 50,
    /** Total step executions per run (loop guard): a definition with a cycle still ends. */
    val maxStepExecutions: Int = 200,
    val maxRetries: Int = 10,
    val maxDuration: Duration = Duration.ofDays(7),
    val maxWait: Duration = Duration.ofDays(30),
    val maxPayloadBytes: Int = 64 * 1024,
    /** Workflow → action → workflow nesting. */
    val maxDepth: Int = 3,
    val maxStepTimeout: Duration = Duration.ofMinutes(5)
) {
    fun coerceAtMost(c: WorkflowLimits) = WorkflowLimits(
        minOf(maxSteps, c.maxSteps), minOf(maxStepExecutions, c.maxStepExecutions), minOf(maxRetries, c.maxRetries),
        if (maxDuration < c.maxDuration) maxDuration else c.maxDuration, if (maxWait < c.maxWait) maxWait else c.maxWait,
        minOf(maxPayloadBytes, c.maxPayloadBytes), minOf(maxDepth, c.maxDepth),
        if (maxStepTimeout < c.maxStepTimeout) maxStepTimeout else c.maxStepTimeout
    )
}

data class WorkflowDefinition(
    val id: String,
    val tenantId: UUID,
    val appId: UUID,
    val name: String = id,
    val trigger: WorkflowTrigger = WorkflowTrigger.MANUAL,
    val steps: List<WorkflowStep>,
    val startStepId: String? = null,
    val limits: WorkflowLimits = WorkflowLimits(),
    val enabled: Boolean = true,
    val compensateOnCancel: Boolean = false
) {
    val start: String get() = startStepId ?: steps.first().id
    fun step(id: String): WorkflowStep? = steps.firstOrNull { it.id == id }
    /** The step that follows [s] in the list (the implicit `next`), or null at the end. */
    fun following(s: WorkflowStep): WorkflowStep? = steps.indexOfFirst { it.id == s.id }.let { i -> if (i in 0 until steps.size - 1) steps[i + 1] else null }
}

object WorkflowDefinitionValidator {
    val STEP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    const val MAX_BRANCHES = 10
    const val MAX_CONDITION_NODES = 20
    const val MAX_CONDITION_DEPTH = 5
    const val MAX_INPUTS_PER_STEP = 32

    fun validate(def: WorkflowDefinition, ceiling: WorkflowLimits = WorkflowLimits()): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        if (!ActionDefinitionValidator.REF_ID.matches(def.id)) issues += DefinitionIssue("id", "must be a plain id")
        val limits = def.limits
        if (limits.maxSteps > ceiling.maxSteps) issues += DefinitionIssue("limits.maxSteps", "exceeds the platform ceiling ${ceiling.maxSteps}")
        if (limits.maxRetries > ceiling.maxRetries) issues += DefinitionIssue("limits.maxRetries", "exceeds the platform ceiling ${ceiling.maxRetries}")
        if (limits.maxDuration > ceiling.maxDuration) issues += DefinitionIssue("limits.maxDuration", "exceeds the platform ceiling ${ceiling.maxDuration}")
        if (def.steps.isEmpty()) { issues += DefinitionIssue("steps", "at least one step is required"); return issues }
        if (def.steps.size > minOf(limits.maxSteps, ceiling.maxSteps)) issues += DefinitionIssue("steps", "at most ${minOf(limits.maxSteps, ceiling.maxSteps)} steps")

        val ids = def.steps.map { it.id }
        val idSet = ids.toSet()
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { issues += DefinitionIssue("steps", "duplicate step id '$it'") }
        if (def.startStepId != null && def.startStepId !in idSet) issues += DefinitionIssue("startStepId", "unknown step")
        fun ref(path: String, target: String?) { if (target != null && target !in idSet) issues += DefinitionIssue(path, "unknown step '$target'") }

        def.steps.forEachIndexed { i, s ->
            val p = "steps[$i]"
            if (!STEP_ID.matches(s.id)) issues += DefinitionIssue("$p.id", "must match ${STEP_ID.pattern}")
            ref("$p.next", s.next); ref("$p.onError", s.onError); ref("$p.defaultNext", s.defaultNext)
            if (s.retry.maxAttempts - 1 > minOf(limits.maxRetries, ceiling.maxRetries)) issues += DefinitionIssue("$p.retry.maxAttempts", "more than ${minOf(limits.maxRetries, ceiling.maxRetries)} retries")
            if (s.timeout != null && (s.timeout.isNegative || s.timeout.isZero || s.timeout > minOf(limits.maxStepTimeout, ceiling.maxStepTimeout))) {
                issues += DefinitionIssue("$p.timeout", "must be positive and at most ${minOf(limits.maxStepTimeout, ceiling.maxStepTimeout)}")
            }
            when (s.kind) {
                StepKind.ACTION -> {
                    if (s.actionRef == null || !ActionDefinitionValidator.REF_ID.matches(s.actionRef)) issues += DefinitionIssue("$p.actionRef", "an action id is required")
                    if (s.inputs.size > MAX_INPUTS_PER_STEP) issues += DefinitionIssue("$p.inputs", "at most $MAX_INPUTS_PER_STEP inputs")
                    s.inputs.forEach { (name, v) -> valueRef(v, "$p.inputs.$name", s.id, idSet, issues) }
                    s.compensationActionRef?.let { if (!ActionDefinitionValidator.REF_ID.matches(it)) issues += DefinitionIssue("$p.compensationActionRef", "must be a plain action id") }
                }
                StepKind.WAIT -> {
                    val w = s.wait
                    if (w == null || w.isNegative || w.isZero) issues += DefinitionIssue("$p.wait", "a positive duration is required")
                    else if (w > minOf(limits.maxWait, ceiling.maxWait)) issues += DefinitionIssue("$p.wait", "at most ${minOf(limits.maxWait, ceiling.maxWait)}")
                }
                StepKind.APPROVAL -> {
                    val a = s.approval
                    if (a == null) issues += DefinitionIssue("$p.approval", "is required for an APPROVAL step")
                    else {
                        if (a.title.isBlank() || a.title.length > 200) issues += DefinitionIssue("$p.approval.title", "must be 1-200 characters")
                        if (a.approvers.isEmpty()) issues += DefinitionIssue("$p.approval.approvers", "at least one approver")
                        if (a.requiredApprovals < 1) issues += DefinitionIssue("$p.approval.requiredApprovals", "must be >= 1")
                        ref("$p.approval.onReject", a.onReject); ref("$p.approval.onExpire", a.onExpire)
                        a.notifyTemplateRef?.let { if (!ActionDefinitionValidator.REF_ID.matches(it)) issues += DefinitionIssue("$p.approval.notifyTemplateRef", "must be a plain id") }
                    }
                }
                StepKind.BRANCH -> {
                    if (s.branches.isEmpty()) issues += DefinitionIssue("$p.branches", "at least one branch")
                    if (s.branches.size > MAX_BRANCHES) issues += DefinitionIssue("$p.branches", "at most $MAX_BRANCHES branches")
                    s.branches.forEachIndexed { bi, b ->
                        ref("$p.branches[$bi].next", b.next)
                        val nodes = intArrayOf(0)
                        condition(b.condition, "$p.branches[$bi].condition", s.id, idSet, 1, nodes, issues)
                    }
                }
                StepKind.END -> Unit
            }
        }
        if (issues.isEmpty()) unreachable(def, issues)
        return issues
    }

    private fun valueRef(v: ValueRef, path: String, ownerId: String, ids: Set<String>, out: MutableList<DefinitionIssue>) {
        when (v) {
            is ValueRef.Input -> if (!InputResolver.isValidPath(v.path, true)) out += DefinitionIssue(path, "invalid input path")
            is ValueRef.Step -> {
                if (v.stepId !in ids) out += DefinitionIssue(path, "unknown step '${v.stepId}'")
                if (v.stepId == ownerId) out += DefinitionIssue(path, "a step cannot read its own output")
                if (!InputResolver.isValidPath(v.path, true)) out += DefinitionIssue(path, "invalid output path")
            }
            is ValueRef.Literal -> Unit
        }
    }

    private fun condition(c: Condition, path: String, owner: String, ids: Set<String>, depth: Int, nodes: IntArray, out: MutableList<DefinitionIssue>) {
        if (++nodes[0] > MAX_CONDITION_NODES) { if (nodes[0] == MAX_CONDITION_NODES + 1) out += DefinitionIssue(path, "condition has more than $MAX_CONDITION_NODES nodes"); return }
        if (depth > MAX_CONDITION_DEPTH) { out += DefinitionIssue(path, "condition nesting deeper than $MAX_CONDITION_DEPTH"); return }
        when (c) {
            is Condition.Compare -> { valueRef(c.left, "$path.left", owner, ids, out); valueRef(c.right, "$path.right", owner, ids, out) }
            is Condition.Exists -> valueRef(c.ref, "$path.ref", owner, ids, out)
            is Condition.AllOf -> { if (c.items.isEmpty()) out += DefinitionIssue(path, "AllOf needs items"); c.items.forEachIndexed { i, x -> condition(x, "$path.items[$i]", owner, ids, depth + 1, nodes, out) } }
            is Condition.AnyOf -> { if (c.items.isEmpty()) out += DefinitionIssue(path, "AnyOf needs items"); c.items.forEachIndexed { i, x -> condition(x, "$path.items[$i]", owner, ids, depth + 1, nodes, out) } }
            is Condition.Not -> condition(c.item, "$path.item", owner, ids, depth + 1, nodes, out)
        }
    }

    /** Steps no path can reach are an authoring mistake (usually a typo in a `next`). */
    private fun unreachable(def: WorkflowDefinition, out: MutableList<DefinitionIssue>) {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { add(def.start) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val s = def.step(id) ?: continue
            val out2 = mutableListOf<String?>(s.onError, s.defaultNext, s.approval?.onReject, s.approval?.onExpire)
            s.branches.forEach { out2 += it.next }
            if (s.kind != StepKind.END && s.kind != StepKind.BRANCH) out2 += (s.next ?: def.following(s)?.id)
            out2.filterNotNull().forEach { stack.add(it) }
        }
        def.steps.filter { it.id !in seen }.forEach { out += DefinitionIssue("steps.${it.id}", "is not reachable from '${def.start}'") }
    }
}

/** Typed, side-effect-free evaluation of [Condition]s. Missing values never throw: a comparison with a missing operand is false (NE: true). */
object ConditionEvaluator {
    fun eval(c: Condition, resolve: (ValueRef) -> JsonNode?): Boolean = when (c) {
        is Condition.Exists -> resolve(c.ref).let { it != null && !it.isNull }
        is Condition.AllOf -> c.items.all { eval(it, resolve) }
        is Condition.AnyOf -> c.items.any { eval(it, resolve) }
        is Condition.Not -> !eval(c.item, resolve)
        is Condition.Compare -> {
            val l = resolve(c.left)?.takeIf { !it.isNull }
            val r = resolve(c.right)?.takeIf { !it.isNull }
            if (l == null || r == null) c.op == CompareOp.NE else compare(l, c.op, r)
        }
    }

    private fun compare(l: JsonNode, op: CompareOp, r: JsonNode): Boolean = when (op) {
        CompareOp.EQ -> equal(l, r)
        CompareOp.NE -> !equal(l, r)
        CompareOp.GT -> order(l, r)?.let { it > 0 } ?: false
        CompareOp.GTE -> order(l, r)?.let { it >= 0 } ?: false
        CompareOp.LT -> order(l, r)?.let { it < 0 } ?: false
        CompareOp.LTE -> order(l, r)?.let { it <= 0 } ?: false
        CompareOp.IN -> r.isArray && r.any { equal(l, it) }
        CompareOp.CONTAINS -> when {
            l.isArray -> l.any { equal(it, r) }
            l.isString && r.isString -> l.asString().contains(r.asString())
            else -> false
        }
    }

    private fun equal(l: JsonNode, r: JsonNode): Boolean =
        if (l.isNumber && r.isNumber) number(l).compareTo(number(r)) == 0 else l == r

    private fun order(l: JsonNode, r: JsonNode): Int? = when {
        l.isNumber && r.isNumber -> number(l).compareTo(number(r))
        l.isString && r.isString -> l.asString().compareTo(r.asString())
        else -> null
    }

    private fun number(n: JsonNode) = BigDecimal(n.toString())
}
