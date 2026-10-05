package com.systemwebstudio.logic.action.handlers

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDataPort
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionDefinitionValidator
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionHandler
import com.systemwebstudio.logic.action.ActionHandlerRegistry
import com.systemwebstudio.logic.action.ActionInput
import com.systemwebstudio.logic.action.ActionNotifyPort
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRun
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.DefinitionIssue
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.DryRunOutcome
import com.systemwebstudio.logic.action.IdempotencyPolicy
import com.systemwebstudio.logic.action.InputType
import com.systemwebstudio.logic.action.NotifyChannel
import com.systemwebstudio.logic.action.NotifyRequest
import com.systemwebstudio.logic.action.OperationRequest
import com.systemwebstudio.logic.action.PrincipalSpecs
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.WriteKind
import com.systemwebstudio.logic.action.WriteRequest
import com.systemwebstudio.logic.action.configString
import com.systemwebstudio.logic.action.failed
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/*
 * Handlers are thin: validated typed action → exactly one port call → typed result. None of them contains data access,
 * connector logic, permission logic or I/O of its own. Every handler also implements preview() for TEST mode, which never causes a side
 * effect and never claims more than was actually verified.
 */

private fun requiredRef(def: ActionDefinition, key: String): DefinitionIssue? {
    val v = def.configString(key)
    return when {
        v == null -> DefinitionIssue("config.$key", "is required")
        !ActionDefinitionValidator.REF_ID.matches(v) -> DefinitionIssue("config.$key", "must be a plain id (${ActionDefinitionValidator.REF_ID.pattern})")
        else -> null
    }
}

/**
 * Canonical call identity for the data path: the app, the mode and the **derived** key. A mutating call that lacks any of them cannot be built, so
 * the handler answers a typed failure instead of sending an unkeyed or unscoped request downstream.
 */
private class DataCall(val appId: java.util.UUID, val mode: com.systemwebstudio.logic.action.ExecutionMode, val key: String)

private fun dataCall(run: ActionRun): DataCall? {
    val app = run.appId ?: return null
    val key = run.idempotencyKey ?: return null
    return DataCall(app, run.mode, key)
}

private val NO_CALL = failed(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, "A data call needs the app and a derived idempotency key")

/** What would run, derived from the definition and the bound input *names* only (values may be sensitive). */
private fun plan(json: JsonMapper, def: ActionDefinition, input: ActionInput, vararg targets: Pair<String, String?>): ObjectNode {
    val p = json.createObjectNode().put("actionId", def.id).put("type", def.type.name)
    val t = json.createObjectNode()
    targets.forEach { (k, v) -> if (v != null) t.put(k, v) }
    p.set<JsonNode>("target", t)
    val names = json.createArrayNode()
    input.names.sorted().forEach { names.add(it) }
    p.set<JsonNode>("inputNames", names)
    if (def.onSuccess.isNotEmpty()) { val a = json.createArrayNode(); def.onSuccess.forEach { a.add(it) }; p.set<JsonNode>("onSuccess", a) }
    if (def.onError.isNotEmpty()) { val a = json.createArrayNode(); def.onError.forEach { a.add(it) }; p.set<JsonNode>("onError", a) }
    return p
}

private fun notExecuted(
    json: JsonMapper, def: ActionDefinition, input: ActionInput, reason: String, output: JsonNode? = null, vararg targets: Pair<String, String?>
) = ActionResult.WouldRun(def.id, def.type, DryRunLevel.NOT_EXECUTED, plan(json, def, input, *targets), output, reason)

/** NAVIGATE — complete and dependency-free. Returns an instruction for the client; nothing runs server-side. */
class NavigateActionHandler(private val json: JsonMapper) : ActionHandler {
    override val type = ActionType.NAVIGATE

    override fun validate(definition: ActionDefinition) = listOfNotNull(requiredRef(definition, "pageId"))

    private fun instruction(definition: ActionDefinition, input: ActionInput): ObjectNode {
        val params = json.createObjectNode()
        input.values.forEach { (k, v) -> params.set<JsonNode>(k, v) }
        val out = json.createObjectNode().put("action", "NAVIGATE").put("pageId", definition.configString("pageId"))
        out.set<JsonNode>("params", params)
        return out
    }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        ActionResult.Ok(instruction(definition, input))

    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        notExecuted(json, definition, input, "Client-side instruction; the server has no side effect to simulate", instruction(definition, input), "pageId" to definition.configString("pageId"))
}

/**
 * REFRESH_QUERY — tells the client to re-run a declared READ query. The server does **not** read data here: the client's next call goes
 * through the data gateway and is authorized there under the user's own permission, so this action cannot widen what the user may read.
 */
class RefreshQueryActionHandler(private val json: JsonMapper) : ActionHandler {
    override val type = ActionType.REFRESH_QUERY

    override fun validate(definition: ActionDefinition) = listOfNotNull(requiredRef(definition, "queryRef"))

    private fun instruction(definition: ActionDefinition) =
        json.createObjectNode().put("action", "REFRESH_QUERY").put("queryRef", definition.configString("queryRef"))

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        ActionResult.Ok(instruction(definition))

    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        notExecuted(json, definition, input, "Client-side instruction; the server reads no data here", instruction(definition), "queryRef" to definition.configString("queryRef"))
}

/**
 * NOTIFY — config `channel` (IN_APP|EMAIL|WEBHOOK|SMS), `templateRef` (approved template), `recipients` (principal specs fixed in the
 * definition; empty = the acting user) and, for WEBHOOK only, `endpointRef` (a pre-registered endpoint — never a URL).
 */
class NotifyActionHandler(private val json: JsonMapper, private val notify: ActionNotifyPort) : ActionHandler {
    override val type = ActionType.NOTIFY

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "templateRef")?.let { issues += it }
        val ch = channel(definition)
        if (ch == null) issues += DefinitionIssue("config.channel", "must be one of ${NotifyChannel.entries.joinToString()}")
        if (ch == NotifyChannel.WEBHOOK) requiredRef(definition, "endpointRef")?.let { issues += it }
        else if (definition.config.containsKey("endpointRef")) issues += DefinitionIssue("config.endpointRef", "only valid for WEBHOOK")
        PrincipalSpecs.parseAll(definition.config["recipients"], "config.recipients", issues)
        return issues
    }

    private fun channel(d: ActionDefinition) = d.configString("channel")?.let { c -> NotifyChannel.entries.firstOrNull { it.name == c } }

    private fun request(definition: ActionDefinition, input: ActionInput, run: ActionRun): NotifyRequest {
        val issues = mutableListOf<DefinitionIssue>()
        val recipients = PrincipalSpecs.parseAll(definition.config["recipients"], "config.recipients", issues)
        return NotifyRequest(
            channel(definition)!!, definition.configString("templateRef")!!, recipients, input.values, run.idempotencyKey,
            definition.configString("endpointRef")
        )
    }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        notify.send(ctx, request(definition, input, run)).toResult()

    /** Never sends — not even to the acting user. */
    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        notExecuted(
            json, definition, input, "Notifications are never sent in TEST mode", null,
            "channel" to channel(definition)?.name, "templateRef" to definition.configString("templateRef"), "endpointRef" to definition.configString("endpointRef")
        )
}

/** CREATE_RECORD / UPDATE_RECORD / DELETE_RECORD / SUBMIT_FORM — config `queryRef` (a WRITE query of the app); UPDATE and DELETE need a required string input `recordId`. */
class MutationActionHandler(override val type: ActionType, private val json: JsonMapper, private val data: ActionDataPort) : ActionHandler {
    private val kind = when (type) {
        ActionType.CREATE_RECORD -> WriteKind.CREATE
        ActionType.UPDATE_RECORD -> WriteKind.UPDATE
        ActionType.DELETE_RECORD -> WriteKind.DELETE
        ActionType.SUBMIT_FORM -> WriteKind.SUBMIT
        else -> throw IllegalArgumentException("MutationActionHandler does not handle $type")
    }

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "queryRef")?.let { issues += it }
        if (kind == WriteKind.UPDATE || kind == WriteKind.DELETE) {
            val rid = definition.inputs.firstOrNull { it.name == "recordId" }
            if (rid == null || !rid.required || rid.type != InputType.STRING) {
                issues += DefinitionIssue("inputs.recordId", "$type requires a required STRING input 'recordId'")
            }
        }
        return issues
    }

    private fun request(definition: ActionDefinition, input: ActionInput, run: ActionRun): WriteRequest? =
        dataCall(run)?.let { WriteRequest(it.appId, it.mode, definition.configString("queryRef")!!, kind, input.values, it.key) }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val req = request(definition, input, run) ?: return NO_CALL
        return data.write(ctx, req).toResult()
    }

    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val req = request(definition, input, run) ?: return NO_CALL
        val p = plan(json, definition, input, "queryRef" to req.queryRef, "writeKind" to kind.name)
        return when (val d = data.dryRunWrite(ctx, req)) {
            DryRunOutcome.Unsupported -> ActionResult.WouldRun(
                definition.id, type, DryRunLevel.NOT_EXECUTED, p, null,
                "The data platform offers no dry-run for this write; nothing was sent and nothing was validated downstream"
            )
            is DryRunOutcome.Validated -> ActionResult.WouldRun(definition.id, type, DryRunLevel.VALIDATED, p, d.output, "Validated by the data platform; nothing was written")
            is DryRunOutcome.Sandboxed -> ActionResult.WouldRun(definition.id, type, DryRunLevel.SANDBOX, p, d.output, "Executed in the data platform's sandbox; no real data changed")
            is DryRunOutcome.Failure -> ActionResult.Failed(d.code, d.retryable, d.message)
        }
    }
}

/** CALL_API — config `dataSourceRef` + `operationKey`: an approved operation of a registered data source. Never a URL, header or credential. */
class CallApiActionHandler(private val json: JsonMapper, private val data: ActionDataPort) : ActionHandler {
    override val type = ActionType.CALL_API

    override fun validate(definition: ActionDefinition) = listOfNotNull(requiredRef(definition, "dataSourceRef"), requiredRef(definition, "operationKey"))

    private fun request(definition: ActionDefinition, input: ActionInput, run: ActionRun): OperationRequest? =
        dataCall(run)?.let { OperationRequest(it.appId, it.mode, definition.configString("dataSourceRef")!!, definition.configString("operationKey")!!, input.values, it.key) }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val req = request(definition, input, run) ?: return NO_CALL
        return data.callOperation(ctx, req).toResult()
    }

    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val req = request(definition, input, run) ?: return NO_CALL
        val p = plan(json, definition, input, "dataSourceRef" to req.dataSourceRef, "operationKey" to req.operationKey)
        return when (val d = data.dryRunOperation(ctx, req)) {
            DryRunOutcome.Unsupported -> ActionResult.WouldRun(
                definition.id, type, DryRunLevel.NOT_EXECUTED, p, null,
                "The connector does not support a dry-run for this operation; no request was sent"
            )
            is DryRunOutcome.Validated -> ActionResult.WouldRun(definition.id, type, DryRunLevel.VALIDATED, p, d.output, "Validated by the connector; no request was sent")
            is DryRunOutcome.Sandboxed -> ActionResult.WouldRun(definition.id, type, DryRunLevel.SANDBOX, p, d.output, "Executed in the connector's sandbox")
            is DryRunOutcome.Failure -> ActionResult.Failed(d.code, d.retryable, d.message)
        }
    }
}

/** START_WORKFLOW — config `workflowRef`; the definition must use `IdempotencyPolicy.REQUIRED` (the workflow contract demands a key). */
class StartWorkflowActionHandler(private val json: JsonMapper, private val workflows: WorkflowStarterPort) : ActionHandler {
    override val type = ActionType.START_WORKFLOW

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "workflowRef")?.let { issues += it }
        if (definition.idempotency != IdempotencyPolicy.REQUIRED) {
            issues += DefinitionIssue("idempotency", "START_WORKFLOW requires IdempotencyPolicy.REQUIRED")
        }
        return issues
    }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val key = run.idempotencyKey ?: return failed(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, "START_WORKFLOW needs an idempotency key")
        val body = json.createObjectNode()
        input.values.forEach { (k, v) -> body.set<JsonNode>(k, v) }
        return workflows.start(ctx, StartWorkflowRequest(definition.configString("workflowRef")!!, body, key, run.callDepth)).toResult()
    }

    /** Starting a workflow is a side effect; TEST mode only reports it. A TEST *run* of a workflow is started through the workflow runtime itself. */
    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        notExecuted(json, definition, input, "No workflow run is started in TEST mode", null, "workflowRef" to definition.configString("workflowRef"))
}

/** Typed placeholder for a type whose port is not wired yet. Fails closed with a non-retryable NOT_IMPLEMENTED — in TEST mode too. */
class NotImplementedActionHandler(override val type: ActionType, private val missingPort: String) : ActionHandler {
    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        failed(ActionErrorCodes.NOT_IMPLEMENTED, "$type is not available: $missingPort is not wired")

    override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult = execute(ctx, definition, input, run)
}

object DefaultActionHandlers {
    /** All nine types are always registered: a missing port becomes [NotImplementedActionHandler], never a hole. */
    fun registry(json: JsonMapper, ports: ActionPorts = ActionPorts()): ActionHandlerRegistry {
        val b = ActionHandlerRegistry.builder().register(NavigateActionHandler(json)).register(RefreshQueryActionHandler(json))
        fun data(type: ActionType, make: (ActionDataPort) -> ActionHandler) =
            b.register(ports.data?.let(make) ?: NotImplementedActionHandler(type, "ActionDataPort"))
        data(ActionType.SUBMIT_FORM) { MutationActionHandler(ActionType.SUBMIT_FORM, json, it) }
        data(ActionType.CREATE_RECORD) { MutationActionHandler(ActionType.CREATE_RECORD, json, it) }
        data(ActionType.UPDATE_RECORD) { MutationActionHandler(ActionType.UPDATE_RECORD, json, it) }
        data(ActionType.DELETE_RECORD) { MutationActionHandler(ActionType.DELETE_RECORD, json, it) }
        data(ActionType.CALL_API) { CallApiActionHandler(json, it) }
        b.register(ports.notify?.let { NotifyActionHandler(json, it) } ?: NotImplementedActionHandler(ActionType.NOTIFY, "ActionNotifyPort"))
        b.register(ports.workflow?.let { StartWorkflowActionHandler(json, it) } ?: NotImplementedActionHandler(ActionType.START_WORKFLOW, "WorkflowStarterPort"))
        return b.build()
    }
}
