package com.systemwebstudio.wiring

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.TriggerInfo
import com.systemwebstudio.logic.action.TriggerKind
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunView
import com.systemwebstudio.logic.workflow.WorkflowStartRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * C0 · R2 and R3 of `docs/contracts/v2/runtime-api.md`: execute an action, start / read / cancel a workflow run. Mounted only with
 * `app.workflow.enabled=true`. The controller does three things and nothing else: (1) `AccessService.forProject` — membership, tenant, archive state, 404 for
 * strangers; (2) strict body parsing; (3) hand a server-built [com.systemwebstudio.logic.action.ActionContext] (ActorKind.USER, tenant from step 1) to C4.
 * All permission decisions of an action/workflow are C4's (C1 `AccessPort` behind `C4AccessPortAdapter`), default deny.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime")
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class AppRuntimeActionController(
    private val access: AccessService,
    private val runtime: AppRuntime,
    private val json: JsonMapper
) {
    private val responses = RuntimeResponses(json)

    @PostMapping("/actions/{actionId}/execute")
    fun execute(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable actionId: String,
        @RequestBody(required = false) body: JsonNode?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        val requestId = RequestIdFilter.current()
        val req = try { RuntimeRequests.actionRun(body) } catch (e: BadRuntimeRequest) { return bad(e, requestId) }
        if (req.mode == ExecutionMode.TEST) ctx.require(Permission.PROJECT_EDIT)
        val request = ActionRequest(
            actionId = actionId,
            inputs = req.inputs,
            idempotencyKey = req.idempotencyKey,
            trigger = TriggerInfo(TriggerKind.UI_EVENT, req.eventName),
            mode = req.mode
        )
        return try {
            responses.action(actionId, req.mode, runtime.actions.run(RuntimeContexts.action(ctx, projectId, requestId), request)).toResponse()
        } catch (e: Exception) {
            responses.error(500, "INTERNAL", "Unexpected error", requestId = requestId).toResponse()
        }
    }

    @PostMapping("/workflows/{workflowId}/runs")
    fun start(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: JsonNode?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        val requestId = RequestIdFilter.current()
        val req = try { RuntimeRequests.workflowStart(body) } catch (e: BadRuntimeRequest) { return bad(e, requestId) }
        if (req.mode == ExecutionMode.TEST) ctx.require(Permission.PROJECT_EDIT)
        val input: JsonNode = req.input ?: json.createObjectNode()
        return workflow(ctx, projectId, requestId, 202) { actx -> runtime.workflows.start(actx, WorkflowStartRequest(workflowId, input, req.idempotencyKey, req.mode)) }
    }

    @GetMapping("/workflow-runs/{runId}")
    fun status(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        return workflow(ctx, projectId, RequestIdFilter.current(), 200) { actx -> runtime.workflows.status(actx, runId) }
    }

    @PostMapping("/workflow-runs/{runId}/cancel")
    fun cancel(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        return workflow(ctx, projectId, RequestIdFilter.current(), 200) { actx -> runtime.workflows.cancel(actx, runId) }
    }

    /**
     * FQ-WF-02: the canonical decision of an APPROVAL step. The approver is the session user (never the body); C4 checks scope -> WORKFLOW_MANAGE -> the approval belongs to this
     * run -> the user is one of the approvers snapshotted when the step started. Idempotent for the same decision, 409 for the opposite one or a final approval.
     */
    @PostMapping("/workflow-runs/{runId}/approvals/{approvalId}/decision")
    fun decide(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable runId: UUID,
        @PathVariable approvalId: UUID,
        @RequestBody(required = false) body: JsonNode?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        val requestId = RequestIdFilter.current()
        val req = try { RuntimeRequests.approvalDecision(body) } catch (e: BadRuntimeRequest) { return bad(e, requestId) }
        return try {
            when (val r = runtime.workflows.decideApproval(RuntimeContexts.action(ctx, projectId, requestId), runId, approvalId, req.decision, req.comment)) {
                is WorkflowResult.Ok -> responses.approvalDecision(r.value).toResponse()
                is WorkflowResult.Failed -> responses.workflowFailure(r).toResponse()
            }
        } catch (e: Exception) {
            responses.error(500, "INTERNAL", "Unexpected error", requestId = requestId).toResponse()
        }
    }

    private fun workflow(
        ctx: AccessContext,
        projectId: UUID,
        requestId: String?,
        okStatus: Int,
        call: (com.systemwebstudio.logic.action.ActionContext) -> WorkflowResult<WorkflowRunView>
    ): ResponseEntity<JsonNode> = try {
        when (val r = call(RuntimeContexts.action(ctx, projectId, requestId))) {
            is WorkflowResult.Ok -> responses.workflowRun(r.value, okStatus).toResponse()
            is WorkflowResult.Failed -> responses.workflowFailure(r).toResponse()
        }
    } catch (e: Exception) {
        responses.error(500, "INTERNAL", "Unexpected error", requestId = requestId).toResponse()
    }

    private fun bad(e: BadRuntimeRequest, requestId: String?): ResponseEntity<JsonNode> =
        responses.error(responses.status(e.code), e.code, e.message ?: "invalid request", requestId = requestId).toResponse()
}
