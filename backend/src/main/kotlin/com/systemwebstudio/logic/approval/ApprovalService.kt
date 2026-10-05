package com.systemwebstudio.logic.approval

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.LogicAuditRecord
import com.systemwebstudio.logic.action.NotifyChannel
import com.systemwebstudio.logic.action.ActionNotifyPort
import com.systemwebstudio.logic.action.NotifyRequest
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.PrincipalResolution
import com.systemwebstudio.logic.action.PrincipalResolver
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.TenantGate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Cross-tenant approvals are **denied unless C1's policy says otherwise**. [allows] answers: may a user acting in [ctx]'s tenant decide an
 * approval that belongs to [approvalTenantId]? The default denies everything.
 */
fun interface CrossTenantApprovalPolicy {
    fun allows(ctx: ActionContext, approvalTenantId: UUID): Boolean

    companion object { val DENY_ALL = CrossTenantApprovalPolicy { _, _ -> false } }
}

/**
 * Approval requests with approvers (USER, GROUP, DEPARTMENT_MANAGER, ROLE), approve/reject/expire/cancel, comments and audit.
 * No business rule lives in the UI: who may decide is the approver snapshot, nothing the client sends.
 */
class ApprovalService(
    private val json: JsonMapper,
    private val store: ApprovalStore,
    private val principals: PrincipalResolver,
    private val tenants: TenantGate,
    private val audit: LogicAuditPort,
    private val notify: ActionNotifyPort? = null,
    private val listener: ApprovalListener? = null,
    private val crossTenant: CrossTenantApprovalPolicy = CrossTenantApprovalPolicy.DENY_ALL,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = System.getLogger(ApprovalService::class.java.name)

    companion object {
        const val MAX_APPROVERS = 50
        const val MAX_COMMENT = 1000
        const val MAX_TITLE = 200
        val MIN_EXPIRY: Duration = Duration.ofMinutes(1)
        val MAX_EXPIRY: Duration = Duration.ofDays(30)
        private const val CAS_RETRIES = 5
    }

    fun request(ctx: ActionContext, req: ApprovalRequest): ApprovalResult<Approval> {
        if (tenants.isEnabledSafe(ctx.tenantId) != true) return ApprovalResult.Failed(ApprovalErrorCodes.TENANT_DISABLED, "Tenant is disabled")
        if (req.title.isBlank() || req.title.length > MAX_TITLE) return invalid("title must be 1-$MAX_TITLE characters")
        if (req.requiredApprovals < 1) return invalid("requiredApprovals must be at least 1")
        if (req.expiresIn < MIN_EXPIRY || req.expiresIn > MAX_EXPIRY) return invalid("expiresIn must be between ${MIN_EXPIRY.toMinutes()} minutes and ${MAX_EXPIRY.toDays()} days")
        if (req.approvers.isEmpty()) return invalid("at least one approver is required")

        val approvers = linkedSetOf<UUID>()
        for (spec in req.approvers) {
            val r = try { principals.resolve(ctx, spec) } catch (e: Exception) {
                return ApprovalResult.Failed(ApprovalErrorCodes.UNAVAILABLE, "Approvers could not be resolved", retryable = true)
            }
            when (r) {
                is PrincipalResolution.Resolved -> approvers += r.userIds
                // cross-tenant (or otherwise non-addressable) principals are refused by C1's policy inside the resolver
                is PrincipalResolution.Denied -> {
                    record(ctx, "REQUEST_DENIED", null, mapOf("reason" to "approver not addressable"))
                    return ApprovalResult.Failed(ApprovalErrorCodes.FORBIDDEN, "An approver is not addressable")
                }
            }
        }
        if (!req.allowSelfApproval) approvers.remove(ctx.actor.userId)
        if (approvers.size > MAX_APPROVERS) return invalid("at most $MAX_APPROVERS approvers")
        if (approvers.size < req.requiredApprovals) return invalid("fewer eligible approvers (${approvers.size}) than required approvals (${req.requiredApprovals})")

        val now = clock.instant()
        val fresh = Approval(
            id = UUID.randomUUID(), tenantId = ctx.tenantId, appId = req.appId ?: ctx.projectId, title = req.title.trim(),
            requestedBy = ctx.actor.userId, requestedAt = now, expiresAt = now.plus(req.expiresIn), approverSpecs = req.approvers,
            approvers = approvers, requiredApprovals = req.requiredApprovals, allowSelfApproval = req.allowSelfApproval,
            status = ApprovalStatus.PENDING, source = req.source, idempotencyKey = req.idempotencyKey
        )
        val stored = try { store.create(fresh) } catch (e: Exception) {
            return ApprovalResult.Failed(ApprovalErrorCodes.UNAVAILABLE, "Approval store is unavailable", retryable = true)
        }
        if (stored.id != fresh.id) return ApprovalResult.Ok(stored) // idempotent replay: no second request, no second notification
        record(ctx, "REQUESTED", stored, mapOf("approvers" to approvers.size.toString(), "required" to req.requiredApprovals.toString()))
        req.notifyTemplateRef?.let { tpl -> notifyBestEffort(ctx, tpl, approvers.map { PrincipalSpec.User(it) }, stored, "requested") }
        return ApprovalResult.Ok(stored)
    }

    /** [approvalTenantId] null = the caller's own tenant. Anything else needs [CrossTenantApprovalPolicy]. */
    fun decide(ctx: ActionContext, approvalId: UUID, kind: DecisionKind, comment: String?, approvalTenantId: UUID? = null): ApprovalResult<Approval> {
        if (comment != null && comment.length > MAX_COMMENT) return invalid("comment must be at most $MAX_COMMENT characters")
        val tenant = approvalTenantId ?: ctx.tenantId
        if (tenants.isEnabledSafe(ctx.tenantId) != true) return ApprovalResult.Failed(ApprovalErrorCodes.TENANT_DISABLED, "Tenant is disabled")
        if (tenant != ctx.tenantId && !crossTenant.allows(ctx, tenant)) {
            record(ctx, "DECISION_DENIED", null, mapOf("reason" to "cross-tenant"), tenantOverride = ctx.tenantId)
            return notFound()
        }
        repeat(CAS_RETRIES) {
            val cur = store.get(tenant, approvalId) ?: return notFound()
            val me = ctx.actor.userId
            if (me !in cur.approvers) {
                record(ctx, "DECISION_DENIED", cur, mapOf("reason" to "not an approver"))
                return ApprovalResult.Failed(ApprovalErrorCodes.FORBIDDEN, "You are not an approver of this request")
            }
            if (!cur.allowSelfApproval && me == cur.requestedBy) {
                record(ctx, "DECISION_DENIED", cur, mapOf("reason" to "self approval"))
                return ApprovalResult.Failed(ApprovalErrorCodes.FORBIDDEN, "You cannot decide your own request")
            }
            val prior = cur.decisions.firstOrNull { it.userId == me }
            if (prior != null) {
                // same user, same decision: idempotent; a different decision is a conflict
                return if (prior.kind == kind) ApprovalResult.Ok(cur)
                else ApprovalResult.Failed(ApprovalErrorCodes.CONFLICT, "You already decided differently")
            }
            if (cur.status.terminal) return ApprovalResult.Failed(ApprovalErrorCodes.ALREADY_DECIDED, "The request is already ${cur.status}")
            val now = clock.instant()
            if (!cur.expiresAt.isAfter(now)) {
                // lazily expire so a late decision can never revive an expired request
                if (finish(cur, ApprovalStatus.EXPIRED, now)) return ApprovalResult.Failed(ApprovalErrorCodes.ALREADY_DECIDED, "The request has expired")
                return@repeat
            }
            val decisions = cur.decisions + ApprovalDecision(me, kind, comment?.trim()?.takeIf { it.isNotEmpty() }, now)
            val approvals = decisions.count { it.kind == DecisionKind.APPROVE }
            val status = when {
                kind == DecisionKind.REJECT -> ApprovalStatus.REJECTED // any rejection rejects
                approvals >= cur.requiredApprovals -> ApprovalStatus.APPROVED
                else -> ApprovalStatus.PENDING
            }
            val next = cur.copy(decisions = decisions, status = status, finishedAt = if (status.terminal) now else null)
            if (store.compareAndSet(cur, next)) {
                val saved = next.copy(version = cur.version + 1)
                record(ctx, if (kind == DecisionKind.APPROVE) "APPROVED_BY" else "REJECTED_BY", saved, mapOf("status" to status.name))
                if (status.terminal) afterFinal(ctx, saved)
                return ApprovalResult.Ok(saved)
            }
        }
        return ApprovalResult.Failed(ApprovalErrorCodes.CONFLICT, "Concurrent update, please retry", retryable = true)
    }

    fun comment(ctx: ActionContext, approvalId: UUID, text: String): ApprovalResult<Approval> {
        if (text.isBlank() || text.length > MAX_COMMENT) return invalid("comment must be 1-$MAX_COMMENT characters")
        repeat(CAS_RETRIES) {
            val cur = store.get(ctx.tenantId, approvalId) ?: return notFound()
            val me = ctx.actor.userId
            if (me !in cur.approvers && me != cur.requestedBy) return ApprovalResult.Failed(ApprovalErrorCodes.FORBIDDEN, "Only the requester and approvers can comment")
            val next = cur.copy(comments = cur.comments + ApprovalComment(me, text.trim(), clock.instant()))
            if (store.compareAndSet(cur, next)) { record(ctx, "COMMENTED", cur, emptyMap()); return ApprovalResult.Ok(next.copy(version = cur.version + 1)) }
        }
        return ApprovalResult.Failed(ApprovalErrorCodes.CONFLICT, "Concurrent update, please retry", retryable = true)
    }

    /** Used by the workflow engine when a run is cancelled. Only pending requests of that tenant can be cancelled. */
    fun cancel(ctx: ActionContext, approvalId: UUID): ApprovalResult<Approval> {
        repeat(CAS_RETRIES) {
            val cur = store.get(ctx.tenantId, approvalId) ?: return notFound()
            if (cur.status.terminal) return ApprovalResult.Ok(cur)
            val next = cur.copy(status = ApprovalStatus.CANCELLED, finishedAt = clock.instant())
            if (store.compareAndSet(cur, next)) { record(ctx, "CANCELLED", cur, emptyMap()); return ApprovalResult.Ok(next.copy(version = cur.version + 1)) }
        }
        return ApprovalResult.Failed(ApprovalErrorCodes.CONFLICT, "Concurrent update, please retry", retryable = true)
    }

    /** Own tenant only; requester and approvers (and nobody else) can read an approval. */
    fun get(ctx: ActionContext, approvalId: UUID): ApprovalResult<Approval> {
        val a = store.get(ctx.tenantId, approvalId) ?: return notFound()
        return if (ctx.actor.userId == a.requestedBy || ctx.actor.userId in a.approvers) ApprovalResult.Ok(a) else notFound()
    }

    /** System accessor for the workflow engine's reconciliation (no membership check — callers are platform code, never request handlers). */
    fun find(tenantId: UUID, approvalId: UUID): Approval? = store.get(tenantId, approvalId)

    fun inbox(ctx: ActionContext, limit: Int = 50): List<Approval> = store.pendingFor(ctx.tenantId, ctx.actor.userId, limit.coerceIn(1, 200))

    /** Sweeper entry point (called by a scheduled job of the platform): expires everything due. */
    fun expireDue(limit: Int = 100, perTenant: Int = 25): Int {
        var n = 0
        for (a in store.dueForExpiry(clock.instant(), limit, perTenant)) {
            // One approval that cannot be finished (store/audit hiccup) must not stop the others, nor be retried in a hot loop: it stays due and is tried again next pass.
            try { if (finish(a, ApprovalStatus.EXPIRED, clock.instant())) n++ } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Expiring an approval failed: ${e.javaClass.simpleName}") }
        }
        return n
    }

    private fun finish(cur: Approval, status: ApprovalStatus, now: Instant): Boolean {
        val next = cur.copy(status = status, finishedAt = now)
        if (!store.compareAndSet(cur, next)) return false
        val saved = next.copy(version = cur.version + 1)
        recordSystem(cur.tenantId, if (status == ApprovalStatus.EXPIRED) "EXPIRED" else status.name, saved)
        afterFinal(null, saved)
        return true
    }

    private fun afterFinal(ctx: ActionContext?, a: Approval) {
        try { listener?.onFinal(a) } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Approval listener failed: ${e.javaClass.simpleName}") }
        if (ctx != null && notify != null) {
            // tell the requester the outcome (best effort); the template ref is a platform template, not workflow input
            notifyBestEffort(ctx, "approval.${a.status.name.lowercase()}", listOf(PrincipalSpec.User(a.requestedBy)), a, "final")
        }
    }

    private fun notifyBestEffort(ctx: ActionContext, template: String, to: List<PrincipalSpec>, a: Approval, phase: String) {
        val port = notify ?: return
        try {
            val params = mapOf<String, JsonNode>("approvalId" to str(a.id.toString()), "title" to str(a.title), "status" to str(a.status.name))
            val out = port.send(ctx, NotifyRequest(NotifyChannel.IN_APP, template, to, params, "approval:${a.id}:$phase:${a.status}"))
            if (out is PortOutcome.Failure) log.log(System.Logger.Level.WARNING, "Approval notification failed: ${out.code}")
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Approval notification threw ${e.javaClass.simpleName}")
        }
    }

    private fun str(s: String): JsonNode = json.createObjectNode().put("v", s).get("v")

    private fun record(ctx: ActionContext, event: String, a: Approval?, attrs: Map<String, String>, tenantOverride: UUID? = null) {
        try {
            audit.record(
                LogicAuditRecord(
                    AuditDomains.APPROVAL, event, tenantOverride ?: ctx.tenantId, ctx.actor, a?.appId ?: ctx.projectId, "approval", a?.id?.toString(),
                    runId = a?.source?.workflowRunId?.toString(), attributes = attrs, at = clock.instant()
                )
            )
        } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Approval audit failed: ${e.javaClass.simpleName}") }
    }

    private fun recordSystem(tenantId: UUID, event: String, a: Approval) {
        try {
            audit.record(LogicAuditRecord(AuditDomains.APPROVAL, event, tenantId, null, a.appId, "approval", a.id.toString(), runId = a.source.workflowRunId?.toString(), at = clock.instant()))
        } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Approval audit failed: ${e.javaClass.simpleName}") }
    }

    private fun TenantGate.isEnabledSafe(t: UUID): Boolean? = try { isEnabled(t) } catch (e: Exception) { null }
    private fun invalid(msg: String) = ApprovalResult.Failed(ApprovalErrorCodes.INVALID, msg)
    private fun notFound() = ApprovalResult.Failed(ApprovalErrorCodes.NOT_FOUND, "Approval not found")
}
