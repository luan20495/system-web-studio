package com.systemwebstudio.publish

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.deploy.PointerFence
import java.time.Instant
import java.util.UUID

enum class ReleaseOperation { PUBLISH, ROLLBACK, UNPUBLISH }

/**
 * What a release operation serialises on: one tenant's app in one environment. Only PRODUCTION exists today and a project has exactly one site, so
 * the scope row is the `sites` row (C2_DEPLOY_CONTRACT.md §0.3). [tenantId] is `projects.tenant_id`.
 */
data class ReleaseScope(val tenantId: UUID, val appId: UUID, val environment: String = PRODUCTION) {
    companion object { const val PRODUCTION = "PRODUCTION" }
}

/**
 * What an operation asks the guard for.
 *  - [operationId] is the operation's identity and is stable across retries: a publish is its deployment id, a rollback / unpublish is derived from
 *    its Idempotency-Key. The same identity may take the scope again (a retry, a redelivered message, a worker that died) and then resumes.
 *  - [seq] is the intent number: a publish brings the `activation_seq` it drew when it was accepted; rollback and unpublish draw one when they
 *    take the scope (null here).
 */
data class ScopeRequest(
    val scope: ReleaseScope, val operation: ReleaseOperation, val operationId: UUID,
    val deploymentId: UUID? = null, val seq: Long? = null,
    /**
     * May the same operation take the scope again while its lease is still alive? A publish may (a redelivered message continues instead of colliding
     * with itself). A rollback / unpublish may not: a duplicate request of the same Idempotency-Key must wait for the first, not run beside it.
     * An EXPIRED lease can always be taken, by anyone, including the same operation.
     */
    val allowReentry: Boolean = operation == ReleaseOperation.PUBLISH
) {
    init { require((operation == ReleaseOperation.PUBLISH) == (deploymentId != null && seq != null)) { "a publish names its deployment and activation number; other operations do not" } }
}

/** who owns the scope right now (for the SCOPE_BUSY answer and for diagnostics) */
data class ScopeHolder(val kind: ReleaseOperation, val operationId: UUID, val deploymentId: UUID?, val since: Instant, val until: Instant)

sealed interface ScopeAcquisition {
    /** the caller owns the scope until it calls [ScopeLease.release]; [ScopeLease.resumed] = the same operation was already running or had moved the pointer */
    class Acquired(val lease: ScopeLease) : ScopeAcquisition
    /** another operation owns it (holder = null when it vanished between the attempt and the read) */
    data class Busy(val holder: ScopeHolder?) : ScopeAcquisition
    /** a newer intent already moved the pointer (or another operation holds the same number): this one must not activate */
    data class Stale(val reason: String, val activeSeq: Long, val activeDeploymentId: UUID?) : ScopeAcquisition
}

/**
 * Order of intents (C0, 2026-10-06). [seq] is the operation's activation number, [activeSeq] / [activeOperationId] describe whoever moved the
 * pointer last.
 *  - lower  -> [STALE]: a newer intent already won;
 *  - higher -> [NEWER]: may go on, provided it still holds its lease and its fencing token at every pointer write;
 *  - equal  -> [RESUME] only when it is the SAME operation (it moved the pointer itself and is resuming after a crash or a retry); equality with
 *    a different operation is a [CONFLICT]: nothing may overwrite the pointer.
 */
enum class ScopeOrder { NEWER, RESUME, STALE, CONFLICT }

object ScopeOrdering {
    fun of(seq: Long, operationId: UUID, activeSeq: Long, activeOperationId: UUID?): ScopeOrder = when {
        seq > activeSeq -> ScopeOrder.NEWER
        seq < activeSeq -> ScopeOrder.STALE
        activeOperationId == operationId -> ScopeOrder.RESUME
        else -> ScopeOrder.CONFLICT
    }
}

/**
 * Proof that an operation owns its scope. [fenceToken] is drawn from a counter that only grows: every acquisition (a takeover, or the same
 * operation taking the scope again) gets a larger one, and every pointer write is checked against the token the scope currently holds, so an
 * earlier holder is refused from then on. [pointerVersion] is the version of the pointer this holder last saw; it moves with each of its own commits.
 */
class ScopeLease(
    val scope: ReleaseScope, val operation: ReleaseOperation, val operationId: UUID, val deploymentId: UUID?,
    val seq: Long, val fenceToken: Long, @Volatile var pointerVersion: Long,
    /** the pointer, read in the same statement that took the scope */
    val activeDeploymentId: UUID?, val activeSeq: Long, val activeOperationId: UUID?,
    val resumed: Boolean,
    val fence: PointerFence,
    private val onRelease: () -> Unit = {}
) {
    @Volatile private var released = false
    /** true once the lease is known to be lost (heartbeat found it gone, or a commit was refused) */
    val lost: Boolean get() = fence.fencedOut
    /** idempotent; never throws */
    fun release() { if (!released) { released = true; runCatching(onRelease) } }

    companion object {
        const val NO_FENCE = 0L
        /** no exclusion, no fencing: unit tests and the mock path */
        fun unguarded(scope: ReleaseScope = ReleaseScope(UUID(0, 0), UUID(0, 0)), operation: ReleaseOperation = ReleaseOperation.PUBLISH, operationId: UUID = UUID.randomUUID()) =
            ScopeLease(scope, operation, operationId, null, 0, NO_FENCE, 0, null, 0, null, false, NoFence(operationId))
    }
}

/** a fence that never refuses (see [ScopeLease.unguarded]) */
class NoFence(override val operationId: UUID) : PointerFence {
    override val fencedOut = false
    private var step: (() -> Unit)? = null
    override fun commit(deploymentId: UUID?, inSameTransaction: (() -> Unit)?): Boolean { step?.invoke(); step = null; inSameTransaction?.invoke(); return true }
    override fun withNextCommit(step: () -> Unit) { this.step = step }
}

/**
 * The single door every mutation of a site's release state goes through: publish (deploy), rollback and unpublish. The contract decided by C0:
 *  - PUBLISH that finds the scope owned is not a failure: it is given back to the queue and waits (at most 300 s, then FAILED / SCOPE_BUSY);
 *  - ROLLBACK and UNPUBLISH never wait: a busy scope is `409 SCOPE_BUSY` ([busy]);
 *  - one owner at a time per [ReleaseScope], held by a lease (TTL 90 s, heartbeat 30 s, at most 900 s) so a dead worker's scope can be taken over;
 *  - a publish that a newer intent has overtaken ends FAILED / STALE_PUBLISH;
 *  - the same operation identity converges: it resumes, it does not compete with itself.
 * Only the entry points acquire the scope. What runs inside (including the automatic rollback of a failed publish) already holds it.
 */
interface ReleaseScopeGuard {
    fun acquire(request: ScopeRequest): ScopeAcquisition
    /** who owns the scope now (a live lease), null = free */
    fun holder(scope: ReleaseScope): ScopeHolder? = null

    companion object {
        fun busy(scope: ReleaseScope, holder: ScopeHolder? = null) = ApiException.conflict("SCOPE_BUSY",
            "Another release operation" + (holder?.let { " (${it.kind})" } ?: "") + " is running for this app; try again when it has finished",
            mapOf("appId" to scope.appId, "environment" to scope.environment,
                "operation" to holder?.let { mapOf("kind" to it.kind.name, "deploymentId" to it.deploymentId, "since" to it.since.toString(), "leaseUntil" to it.until.toString()) }),
        ).let { ApiException(it.status, it.code, it.message, it.details, mapOf("Retry-After" to "5")) }
    }
}

/** No exclusion (unit tests of the deployer): every acquisition succeeds with a lease that never fences. */
class PassThroughScopeGuard : ReleaseScopeGuard {
    override fun acquire(request: ScopeRequest): ScopeAcquisition =
        ScopeAcquisition.Acquired(ScopeLease.unguarded(request.scope, request.operation, request.operationId))
}
