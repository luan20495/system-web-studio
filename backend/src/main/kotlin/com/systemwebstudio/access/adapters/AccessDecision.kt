package com.systemwebstudio.access.adapters

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.ActorKind
import java.util.UUID

/**
 * C1 policy cores behind the ports of C3 (`GatewayAuthorizer`) and C4 (`AccessPort`, `TenantGate`, `PrincipalResolver`).
 * Layering (contract integration §1): `access` imports base + `tenancy` only, so these classes speak plain types; C0's `wiring.*Adapter`
 * classes convert `GatewayContext` / `ActionContext` into [Principal] + ids. Nothing here trusts a value the client chose: the tenant is
 * always re-derived from the workspace and compared with the one the caller claims. Default deny; any failure denies.
 */
sealed interface AccessDecision {
    data object Allowed : AccessDecision
    /** [reason] is for audit only; callers must never return it to end users. */
    data class Denied(val reason: String) : AccessDecision
}

val AccessDecision.allowed: Boolean get() = this is AccessDecision.Allowed

/** Who acts. [userId] is required for [ActorKind.USER] and ignored otherwise. */
data class Principal(val kind: ActorKind, val userId: UUID? = null)

/**
 * TEMPORARY V2 POLICY (not a permanent platform rule): the frozen v2 contract defines runtime permissions for end users only, so every C1
 * adapter fails closed for [ActorKind.SYSTEM], [ActorKind.SERVICE] and [ActorKind.APP_TOKEN]. Lifting this needs a contract change
 * (what may a system/service/app-token actor do, per tenant) approved by C0; until then the single switch is [userOrNull].
 */
internal object ActorPolicy {
    const val DENIED_REASON = "actor kind not authorised"
    /** The user id of an authorised actor, or null (=> deny). Only [ActorKind.USER] with a user id passes. */
    fun userOrNull(p: Principal): UUID? = if (p.kind == ActorKind.USER) p.userId else null
}

internal inline fun decide(block: () -> AccessDecision): AccessDecision = try {
    block()
} catch (e: ApiException) {
    AccessDecision.Denied(e.code)                 // 404/403/401 from AccessService: denial reason = its code
} catch (e: Exception) {
    AccessDecision.Denied("authorization failed") // fail closed
}
