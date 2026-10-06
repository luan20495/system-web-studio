package com.systemwebstudio.publish

import com.systemwebstudio.common.ApiException
import org.springframework.stereotype.Component
import java.util.UUID

enum class ReleaseOperation { PUBLISH, ROLLBACK, UNPUBLISH }

/**
 * What a release operation serialises on: one tenant's app in one environment. Only PRODUCTION exists today; the field is here so the
 * guard does not change shape when another environment is added.
 */
data class ReleaseScope(val tenantId: UUID, val appId: UUID, val environment: String = PRODUCTION) {
    companion object { const val PRODUCTION = "PRODUCTION" }
}

/**
 * The proof that an operation holds its scope. [fencingToken] is what a compare-and-set on the active pointer will carry once leases exist, so a
 * holder that lost its lease (a stalled worker) cannot move the pointer after a newer holder took over. [NO_FENCE] = no exclusion is enforced.
 */
class ScopeLease(val scope: ReleaseScope, val operation: ReleaseOperation, val fencingToken: Long = NO_FENCE) {
    companion object { const val NO_FENCE = 0L }
}

/**
 * The single door every mutation of a site's release state goes through: publish (deploy), rollback and unpublish. The contract decided by C0:
 *  - PUBLISH waits for the scope (bounded, 300 s) and a publish that lost the scope meanwhile ends FAILED / STALE_PUBLISH;
 *  - ROLLBACK and UNPUBLISH never wait: a busy scope is `409 SCOPE_BUSY` ([busy]);
 *  - one holder at a time per [ReleaseScope], held with a lease (TTL 90 s, heartbeat 30 s, at most 900 s) so a dead worker's scope can be taken over;
 *  - the same Idempotency-Key converges on one result.
 * Only the entry points acquire the scope. What runs inside [body] (including the automatic rollback of a failed publish) already holds it and
 * never asks again.
 *
 * Shape only: the lease and fencing storage needs a migration C0 has not reserved. [PassThroughScopeGuard] is what runs until then.
 */
interface ReleaseScopeGuard {
    fun <T> run(scope: ReleaseScope, operation: ReleaseOperation, body: (ScopeLease) -> T): T

    companion object {
        fun busy(scope: ReleaseScope, held: ReleaseOperation? = null) = ApiException.conflict("SCOPE_BUSY",
            "Another release operation" + (held?.let { " ($it)" } ?: "") + " is running for this app; try again when it has finished",
            mapOf("appId" to scope.appId, "environment" to scope.environment))
    }
}

/** No exclusion yet (see [ReleaseScopeGuard]): runs the operation with a lease that carries [ScopeLease.NO_FENCE]. Behaviour is exactly what it was before the guard existed. */
@Component
class PassThroughScopeGuard : ReleaseScopeGuard {
    override fun <T> run(scope: ReleaseScope, operation: ReleaseOperation, body: (ScopeLease) -> T): T = body(ScopeLease(scope, operation))
}
