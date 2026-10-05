package com.systemwebstudio.logic.approval

import com.systemwebstudio.logic.action.PrincipalSpec
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ApprovalStatus(val terminal: Boolean) { PENDING(false), APPROVED(true), REJECTED(true), EXPIRED(true), CANCELLED(true) }

enum class DecisionKind { APPROVE, REJECT }

data class ApprovalDecision(val userId: UUID, val kind: DecisionKind, val comment: String?, val at: Instant)
data class ApprovalComment(val userId: UUID, val text: String, val at: Instant)

/** Where the approval came from (a workflow step), so the engine can resume exactly that step. */
data class ApprovalSource(val workflowRunId: UUID? = null, val stepId: String? = null)

/**
 * An approval request. [approvers] is a **snapshot** taken when the request is created (resolved by C1's membership rules at that moment):
 * later group/role changes do not silently add or remove approvers of a pending request.
 */
data class Approval(
    val id: UUID,
    val tenantId: UUID,
    val appId: UUID?,
    val title: String,
    val requestedBy: UUID,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val approverSpecs: List<PrincipalSpec>,
    val approvers: Set<UUID>,
    val requiredApprovals: Int,
    val allowSelfApproval: Boolean,
    val status: ApprovalStatus,
    val decisions: List<ApprovalDecision> = emptyList(),
    val comments: List<ApprovalComment> = emptyList(),
    val source: ApprovalSource = ApprovalSource(),
    val idempotencyKey: String? = null,
    val finishedAt: Instant? = null,
    val version: Long = 0
) {
    val approvals: Int get() = decisions.count { it.kind == DecisionKind.APPROVE }
}

/** What a caller (usually the workflow engine) asks for. [title] must be non-sensitive: it is shown in lists and audit. */
data class ApprovalRequest(
    val title: String,
    val approvers: List<PrincipalSpec>,
    val requiredApprovals: Int = 1,
    val expiresIn: Duration = Duration.ofDays(7),
    val allowSelfApproval: Boolean = false,
    /** Optional approved template; approvers (and later the requester) get an IN_APP notification. */
    val notifyTemplateRef: String? = null,
    val source: ApprovalSource = ApprovalSource(),
    val idempotencyKey: String? = null,
    val appId: UUID? = null
)

object ApprovalErrorCodes {
    const val NOT_FOUND = "APPROVAL_NOT_FOUND"
    const val FORBIDDEN = "FORBIDDEN"
    const val INVALID = "APPROVAL_INVALID"
    const val ALREADY_DECIDED = "APPROVAL_ALREADY_DECIDED"
    const val CONFLICT = "APPROVAL_CONFLICT"
    const val UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val TENANT_DISABLED = "TENANT_DISABLED"
}

sealed interface ApprovalResult<out T> {
    data class Ok<T>(val value: T) : ApprovalResult<T>
    data class Failed(val code: String, val message: String, val retryable: Boolean = false) : ApprovalResult<Nothing>
}

/** Told when an approval reaches a final status. A failure here never undoes the decision; the engine also reconciles by polling. */
fun interface ApprovalListener { fun onFinal(approval: Approval) }

/** Persistence with compare-and-set; a JDBC implementation needs a migration (BOARD.md request). */
interface ApprovalStore {
    /** Creates the approval, or returns the existing one with the same (tenant, idempotencyKey). */
    fun create(approval: Approval): Approval
    fun get(tenantId: UUID, id: UUID): Approval?
    /** Succeeds only if the stored version equals [expected].version; the stored row gets version + 1. */
    fun compareAndSet(expected: Approval, next: Approval): Boolean
    fun pendingFor(tenantId: UUID, userId: UUID, limit: Int): List<Approval>
    /** Oldest expiry first, at most [limit] in total and [perTenant] per tenant (round-robin, see FairSelection). */
    fun dueForExpiry(now: Instant, limit: Int, perTenant: Int = Int.MAX_VALUE): List<Approval>
    /**
     * Retention: deletes approvals in a **final** status (never PENDING) that finished before [olderThan], oldest first, at most [limit], except those [keep]
     * says to keep (the retention service keeps an approval whose workflow run is still active). @return rows deleted
     */
    fun purgeFinal(olderThan: Instant, limit: Int, keep: (Approval) -> Boolean = { false }): Int
}

class InMemoryApprovalStore : ApprovalStore {
    private val rows = ConcurrentHashMap<Pair<UUID, UUID>, Approval>()

    override fun create(approval: Approval): Approval {
        var result = approval
        synchronized(this) {
            val key = approval.idempotencyKey
            val existing = key?.let { k -> rows.values.firstOrNull { it.tenantId == approval.tenantId && it.idempotencyKey == k } }
            if (existing != null) result = existing else rows[approval.tenantId to approval.id] = approval
        }
        return result
    }

    override fun get(tenantId: UUID, id: UUID): Approval? = rows[tenantId to id]

    override fun compareAndSet(expected: Approval, next: Approval): Boolean {
        var ok = false
        rows.computeIfPresent(expected.tenantId to expected.id) { _, cur ->
            if (cur.version == expected.version) { ok = true; next.copy(version = cur.version + 1) } else cur
        }
        return ok
    }

    override fun pendingFor(tenantId: UUID, userId: UUID, limit: Int) =
        rows.values.filter { it.tenantId == tenantId && it.status == ApprovalStatus.PENDING && userId in it.approvers }.sortedBy { it.requestedAt }.take(limit)

    override fun purgeFinal(olderThan: Instant, limit: Int, keep: (Approval) -> Boolean): Int {
        val victims = rows.values.filter { it.status.terminal && (it.finishedAt ?: it.requestedAt).isBefore(olderThan) && !keep(it) }
            .sortedBy { it.finishedAt ?: it.requestedAt }.take(limit.coerceAtLeast(0))
        var n = 0
        for (v in victims) rows.computeIfPresent(v.tenantId to v.id) { _, cur -> if (cur.status.terminal && cur.version == v.version) { n++; null } else cur }
        return n
    }

    override fun dueForExpiry(now: Instant, limit: Int, perTenant: Int) =
        com.systemwebstudio.logic.limits.FairSelection.pick(rows.values.filter { it.status == ApprovalStatus.PENDING && !it.expiresAt.isAfter(now) }.sortedBy { it.expiresAt }, limit, perTenant) { it.tenantId }
}
