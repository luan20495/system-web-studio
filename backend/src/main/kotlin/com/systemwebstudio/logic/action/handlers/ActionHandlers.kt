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
import com.systemwebstudio.logic.action.ApiCallRequest
import com.systemwebstudio.logic.action.DefinitionIssue
import com.systemwebstudio.logic.action.InputType
import com.systemwebstudio.logic.action.MutationKind
import com.systemwebstudio.logic.action.MutationRequest
import com.systemwebstudio.logic.action.NotifyChannel
import com.systemwebstudio.logic.action.NotifyRequest
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.configString
import com.systemwebstudio.logic.action.failed
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/*
 * Handlers are thin: validated typed action → exactly one port call → typed result. None of them contains data access,
 * connector logic, permission logic or I/O of its own.
 */

private fun requiredRef(def: ActionDefinition, key: String): DefinitionIssue? {
    val v = def.configString(key)
    return when {
        v == null -> DefinitionIssue("config.$key", "is required")
        !ActionDefinitionValidator.REF_ID.matches(v) -> DefinitionIssue("config.$key", "must be a plain id (${ActionDefinitionValidator.REF_ID.pattern})")
        else -> null
    }
}

/** NAVIGATE — complete and dependency-free. Returns an instruction for the client; nothing runs server-side. */
class NavigateActionHandler(private val json: JsonMapper) : ActionHandler {
    override val type = ActionType.NAVIGATE

    override fun validate(definition: ActionDefinition) = listOfNotNull(requiredRef(definition, "pageId"))

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val params = json.createObjectNode()
        input.values.forEach { (k, v) -> params.set<JsonNode>(k, v) }
        val out = json.createObjectNode().put("action", "NAVIGATE").put("pageId", definition.configString("pageId"))
        out.set<JsonNode>("params", params)
        return ActionResult.Ok(out)
    }
}

/** NOTIFY — config `channel` (IN_APP|EMAIL), `templateId`, optional `recipientUserIds` (fixed in the definition). */
class NotifyActionHandler(private val json: JsonMapper, private val notify: ActionNotifyPort) : ActionHandler {
    override val type = ActionType.NOTIFY

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "templateId")?.let { issues += it }
        if (channel(definition) == null) issues += DefinitionIssue("config.channel", "must be one of ${NotifyChannel.entries.joinToString()}")
        val raw = definition.config["recipientUserIds"]
        if (raw != null && (!raw.isArray || raw.any { !it.isString || runCatching { UUID.fromString(it.asString()) }.isFailure })) {
            issues += DefinitionIssue("config.recipientUserIds", "must be an array of user UUIDs")
        }
        return issues
    }

    private fun channel(d: ActionDefinition) = d.configString("channel")?.let { c -> NotifyChannel.entries.firstOrNull { it.name == c } }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val recipients = definition.config["recipientUserIds"]?.map { UUID.fromString(it.asString()) }.orEmpty()
        return notify.send(
            ctx, NotifyRequest(channel(definition)!!, definition.configString("templateId")!!, recipients, input.values, run.idempotencyKey)
        ).toResult()
    }
}

/** CREATE_RECORD / UPDATE_RECORD / DELETE_RECORD / SUBMIT_FORM — config `mutationId`; UPDATE and DELETE need a required string input `recordId`. */
class MutationActionHandler(override val type: ActionType, private val data: ActionDataPort) : ActionHandler {
    private val kind = when (type) {
        ActionType.CREATE_RECORD -> MutationKind.CREATE
        ActionType.UPDATE_RECORD -> MutationKind.UPDATE
        ActionType.DELETE_RECORD -> MutationKind.DELETE
        ActionType.SUBMIT_FORM -> MutationKind.SUBMIT
        else -> throw IllegalArgumentException("MutationActionHandler does not handle $type")
    }

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "mutationId")?.let { issues += it }
        if (kind == MutationKind.UPDATE || kind == MutationKind.DELETE) {
            val rid = definition.inputs.firstOrNull { it.name == "recordId" }
            if (rid == null || !rid.required || rid.type != InputType.STRING) {
                issues += DefinitionIssue("inputs.recordId", "$type requires a required STRING input 'recordId'")
            }
        }
        return issues
    }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        data.mutate(ctx, MutationRequest(definition.configString("mutationId")!!, kind, input.values, run.idempotencyKey)).toResult()
}

/** CALL_API — config `operationId` of an already declared connector operation. */
class CallApiActionHandler(private val data: ActionDataPort) : ActionHandler {
    override val type = ActionType.CALL_API

    override fun validate(definition: ActionDefinition) = listOfNotNull(requiredRef(definition, "operationId"))

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        data.callApi(ctx, ApiCallRequest(definition.configString("operationId")!!, input.values, run.idempotencyKey)).toResult()
}

/** START_WORKFLOW — config `workflowId`; the definition must use `IdempotencyPolicy.REQUIRED` (the workflow contract demands a key). */
class StartWorkflowActionHandler(private val json: JsonMapper, private val workflows: WorkflowStarterPort) : ActionHandler {
    override val type = ActionType.START_WORKFLOW

    override fun validate(definition: ActionDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        requiredRef(definition, "workflowId")?.let { issues += it }
        if (definition.idempotency != com.systemwebstudio.logic.action.IdempotencyPolicy.REQUIRED) {
            issues += DefinitionIssue("idempotency", "START_WORKFLOW requires IdempotencyPolicy.REQUIRED")
        }
        return issues
    }

    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
        val key = run.idempotencyKey ?: return failed(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, "START_WORKFLOW needs an idempotency key")
        val body = json.createObjectNode()
        input.values.forEach { (k, v) -> body.set<JsonNode>(k, v) }
        return workflows.start(ctx, StartWorkflowRequest(definition.configString("workflowId")!!, body, key)).toResult()
    }
}

/** Typed placeholder for a type whose port is not wired yet. Fails closed with a non-retryable NOT_IMPLEMENTED. */
class NotImplementedActionHandler(override val type: ActionType, private val missingPort: String) : ActionHandler {
    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
        failed(ActionErrorCodes.NOT_IMPLEMENTED, "$type is not available: $missingPort is not wired")
}

object DefaultActionHandlers {
    /** All eight types are always registered: a missing port becomes [NotImplementedActionHandler], never a hole. */
    fun registry(json: JsonMapper, ports: ActionPorts = ActionPorts()): ActionHandlerRegistry {
        val b = ActionHandlerRegistry.builder().register(NavigateActionHandler(json))
        fun data(type: ActionType, make: (ActionDataPort) -> ActionHandler) =
            b.register(ports.data?.let(make) ?: NotImplementedActionHandler(type, "ActionDataPort"))
        data(ActionType.SUBMIT_FORM) { MutationActionHandler(ActionType.SUBMIT_FORM, it) }
        data(ActionType.CREATE_RECORD) { MutationActionHandler(ActionType.CREATE_RECORD, it) }
        data(ActionType.UPDATE_RECORD) { MutationActionHandler(ActionType.UPDATE_RECORD, it) }
        data(ActionType.DELETE_RECORD) { MutationActionHandler(ActionType.DELETE_RECORD, it) }
        data(ActionType.CALL_API) { CallApiActionHandler(it) }
        b.register(ports.notify?.let { NotifyActionHandler(json, it) } ?: NotImplementedActionHandler(ActionType.NOTIFY, "ActionNotifyPort"))
        b.register(ports.workflow?.let { StartWorkflowActionHandler(json, it) } ?: NotImplementedActionHandler(ActionType.START_WORKFLOW, "WorkflowStarterPort"))
        return b.build()
    }
}
