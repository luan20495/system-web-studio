package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.arrayItems
import com.systemwebstudio.logic.workflow.ApprovalSpec
import com.systemwebstudio.logic.workflow.Branch
import com.systemwebstudio.logic.workflow.CompareOp
import com.systemwebstudio.logic.workflow.Condition
import com.systemwebstudio.logic.workflow.RetryPolicy
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.ValueRef
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowLimits
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.WorkflowTrigger
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.UUID

/**
 * `workflow_runs.definition`: the SNAPSHOT of the definition a run was started with (C4: editing or republishing a workflow never changes a run in flight).
 * A lossless, explicit JSON form of [WorkflowDefinition] - every durable and every condition node is spelled out, durations are ISO-8601, so
 * `decode(encode(d)) == d` for every definition the engine can start (proved by `WorkflowDefinitionCodecTests`). Not the AppDefinition JSON: that one is
 * the editable source, this one is what a run pinned.
 */
class WorkflowDefinitionCodec(private val json: JsonMapper) {

    fun encode(def: WorkflowDefinition): String = json.writeValueAsString(definition(def))

    fun decode(text: String): WorkflowDefinition = definition(json.readTree(text))

    // ------------------------------------------------------------------------------------------------ encode

    private fun definition(d: WorkflowDefinition): ObjectNode {
        val o = json.createObjectNode()
        o.put("id", d.id); o.put("tenantId", d.tenantId.toString()); o.put("appId", d.appId.toString()); o.put("name", d.name)
        o.put("trigger", d.trigger.name)
        val steps = json.createArrayNode()
        d.steps.forEach { steps.add(step(it)) }
        o.set("steps", steps)
        d.startStepId?.let { o.put("startStepId", it) }
        o.set("limits", limits(d.limits))
        o.put("enabled", d.enabled); o.put("compensateOnCancel", d.compensateOnCancel)
        return o
    }

    private fun limits(l: WorkflowLimits): ObjectNode = json.createObjectNode().also {
        it.put("maxSteps", l.maxSteps); it.put("maxStepExecutions", l.maxStepExecutions); it.put("maxRetries", l.maxRetries)
        it.put("maxDuration", l.maxDuration.toString()); it.put("maxWait", l.maxWait.toString()); it.put("maxPayloadBytes", l.maxPayloadBytes)
        it.put("maxDepth", l.maxDepth); it.put("maxStepTimeout", l.maxStepTimeout.toString())
    }

    private fun step(s: WorkflowStep): ObjectNode {
        val o = json.createObjectNode()
        o.put("id", s.id); o.put("kind", s.kind.name)
        s.actionRef?.let { o.put("actionRef", it) }
        val inputs = json.createObjectNode()
        s.inputs.forEach { (k, v) -> inputs.set(k, valueRef(v)) }
        o.set("inputs", inputs)
        s.next?.let { o.put("next", it) }
        s.onError?.let { o.put("onError", it) }
        o.set("retry", json.createObjectNode().also {
            it.put("maxAttempts", s.retry.maxAttempts); it.put("initialBackoff", s.retry.initialBackoff.toString())
            it.put("multiplier", s.retry.multiplier); it.put("maxBackoff", s.retry.maxBackoff.toString())
        })
        s.timeout?.let { o.put("timeout", it.toString()) }
        s.wait?.let { o.put("wait", it.toString()) }
        s.approval?.let { o.set("approval", approval(it)) }
        val branches = json.createArrayNode()
        s.branches.forEach { b -> branches.add(json.createObjectNode().also { it.set("condition", condition(b.condition)); it.put("next", b.next) }) }
        o.set("branches", branches)
        s.defaultNext?.let { o.put("defaultNext", it) }
        s.compensationActionRef?.let { o.put("compensationActionRef", it) }
        return o
    }

    private fun approval(a: ApprovalSpec): ObjectNode {
        val o = json.createObjectNode()
        o.put("title", a.title)
        val approvers = json.createArrayNode()
        a.approvers.forEach { approvers.add(principal(it)) }
        o.set("approvers", approvers)
        o.put("requiredApprovals", a.requiredApprovals); o.put("expiresIn", a.expiresIn.toString()); o.put("allowSelfApproval", a.allowSelfApproval)
        a.notifyTemplateRef?.let { o.put("notifyTemplateRef", it) }
        a.onReject?.let { o.put("onReject", it) }
        a.onExpire?.let { o.put("onExpire", it) }
        return o
    }

    private fun principal(p: PrincipalSpec): ObjectNode {
        val o = json.createObjectNode()
        when (p) {
            is PrincipalSpec.User -> { o.put("k", "user"); o.put("userId", p.userId.toString()); p.tenantId?.let { o.put("tenantId", it.toString()) } }
            is PrincipalSpec.Group -> { o.put("k", "group"); o.put("groupId", p.groupId) }
            is PrincipalSpec.DepartmentManager -> { o.put("k", "departmentManager"); p.departmentId?.let { o.put("departmentId", it) } }
            is PrincipalSpec.Role -> { o.put("k", "role"); o.put("role", p.role) }
        }
        return o
    }

    private fun valueRef(v: ValueRef): ObjectNode {
        val o = json.createObjectNode()
        when (v) {
            is ValueRef.Input -> { o.put("k", "input"); o.put("path", v.path) }
            is ValueRef.Step -> { o.put("k", "step"); o.put("stepId", v.stepId); o.put("path", v.path) }
            is ValueRef.Literal -> { o.put("k", "literal"); o.set("value", v.value) }
        }
        return o
    }

    private fun condition(c: Condition): ObjectNode {
        val o = json.createObjectNode()
        when (c) {
            is Condition.Compare -> { o.put("k", "compare"); o.set("left", valueRef(c.left)); o.put("op", c.op.name); o.set("right", valueRef(c.right)) }
            is Condition.Exists -> { o.put("k", "exists"); o.set("ref", valueRef(c.ref)) }
            is Condition.AllOf -> { o.put("k", "allOf"); o.set("items", conditions(c.items)) }
            is Condition.AnyOf -> { o.put("k", "anyOf"); o.set("items", conditions(c.items)) }
            is Condition.Not -> { o.put("k", "not"); o.set("item", condition(c.item)) }
        }
        return o
    }

    private fun conditions(items: List<Condition>): ArrayNode = json.createArrayNode().also { a -> items.forEach { a.add(condition(it)) } }

    // ------------------------------------------------------------------------------------------------ decode

    private fun definition(n: JsonNode) = WorkflowDefinition(
        id = text(n, "id"), tenantId = UUID.fromString(text(n, "tenantId")), appId = UUID.fromString(text(n, "appId")), name = text(n, "name"),
        trigger = WorkflowTrigger.valueOf(text(n, "trigger")), steps = n.get("steps").arrayItems().map { step(it) },
        startStepId = optText(n, "startStepId"), limits = limits(n.get("limits")), enabled = n.get("enabled").asBoolean(), compensateOnCancel = n.get("compensateOnCancel").asBoolean()
    )

    private fun limits(n: JsonNode) = WorkflowLimits(
        maxSteps = n.get("maxSteps").asInt(), maxStepExecutions = n.get("maxStepExecutions").asInt(), maxRetries = n.get("maxRetries").asInt(),
        maxDuration = Duration.parse(text(n, "maxDuration")), maxWait = Duration.parse(text(n, "maxWait")), maxPayloadBytes = n.get("maxPayloadBytes").asInt(),
        maxDepth = n.get("maxDepth").asInt(), maxStepTimeout = Duration.parse(text(n, "maxStepTimeout"))
    )

    private fun step(n: JsonNode): WorkflowStep {
        val inputs = LinkedHashMap<String, ValueRef>()
        n.get("inputs")?.let { i -> for (k in i.propertyNames()) inputs[k] = valueRef(i.get(k)) }
        val r = n.get("retry")
        return WorkflowStep(
            id = text(n, "id"), kind = StepKind.valueOf(text(n, "kind")), actionRef = optText(n, "actionRef"), inputs = inputs,
            next = optText(n, "next"), onError = optText(n, "onError"),
            retry = RetryPolicy(r.get("maxAttempts").asInt(), Duration.parse(text(r, "initialBackoff")), r.get("multiplier").asDouble(), Duration.parse(text(r, "maxBackoff"))),
            timeout = optText(n, "timeout")?.let { Duration.parse(it) }, wait = optText(n, "wait")?.let { Duration.parse(it) },
            approval = n.get("approval")?.takeIf { !it.isNull }?.let { approval(it) },
            branches = n.get("branches")?.arrayItems().orEmpty().map { Branch(condition(it.get("condition")), text(it, "next")) },
            defaultNext = optText(n, "defaultNext"), compensationActionRef = optText(n, "compensationActionRef")
        )
    }

    private fun approval(n: JsonNode) = ApprovalSpec(
        title = text(n, "title"), approvers = n.get("approvers").arrayItems().map { principal(it) }, requiredApprovals = n.get("requiredApprovals").asInt(),
        expiresIn = Duration.parse(text(n, "expiresIn")), allowSelfApproval = n.get("allowSelfApproval").asBoolean(), notifyTemplateRef = optText(n, "notifyTemplateRef"),
        onReject = optText(n, "onReject"), onExpire = optText(n, "onExpire")
    )

    private fun principal(n: JsonNode): PrincipalSpec = when (val k = text(n, "k")) {
        "user" -> PrincipalSpec.User(UUID.fromString(text(n, "userId")), optText(n, "tenantId")?.let { UUID.fromString(it) })
        "group" -> PrincipalSpec.Group(text(n, "groupId"))
        "departmentManager" -> PrincipalSpec.DepartmentManager(optText(n, "departmentId"))
        "role" -> PrincipalSpec.Role(text(n, "role"))
        else -> throw IllegalStateException("unknown stored principal kind: $k")
    }

    private fun valueRef(n: JsonNode): ValueRef = when (val k = text(n, "k")) {
        "input" -> ValueRef.Input(text(n, "path"))
        "step" -> ValueRef.Step(text(n, "stepId"), text(n, "path"))
        "literal" -> ValueRef.Literal(n.get("value") ?: throw IllegalStateException("stored literal has no value"))
        else -> throw IllegalStateException("unknown stored value reference kind: $k")
    }

    private fun condition(n: JsonNode): Condition = when (val k = text(n, "k")) {
        "compare" -> Condition.Compare(valueRef(n.get("left")), CompareOp.valueOf(text(n, "op")), valueRef(n.get("right")))
        "exists" -> Condition.Exists(valueRef(n.get("ref")))
        "allOf" -> Condition.AllOf(n.get("items").arrayItems().map { condition(it) })
        "anyOf" -> Condition.AnyOf(n.get("items").arrayItems().map { condition(it) })
        "not" -> Condition.Not(condition(n.get("item")))
        else -> throw IllegalStateException("unknown stored condition kind: $k")
    }

    private fun text(n: JsonNode, field: String): String = n.get(field)?.takeIf { !it.isNull }?.asString() ?: throw IllegalStateException("stored definition lacks $field")
    private fun optText(n: JsonNode, field: String): String? = n.get(field)?.takeIf { !it.isNull }?.asString()
}
