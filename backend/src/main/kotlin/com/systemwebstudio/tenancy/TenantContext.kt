package com.systemwebstudio.tenancy

import java.util.UUID

/**
 * Kind of principal behind a call. ONE definition for the whole backend (contract v2 §2): C3/C4 convert at their boundary, they do not
 * keep their own enum. Authorization policy for each kind lives in `access.adapters` (default deny: only USER is authorised, plus
 * PUBLIC_SITE for exactly one operation, see `PublicSiteAuthorizer`).
 *
 * [PUBLIC_SITE] (D-C0-35, docs/contracts/v2/published-runtime.md §4) is the anonymous visitor of a published site, seen through the same-origin sites
 * gateway. It is NOT a USER and carries no user id, role or membership; its tenant / workspace / project / release are derived by the server from the
 * site slug, never from anything the browser sent. It is never SYSTEM / SERVICE / APP_TOKEN, and `logic.action.ActorKind` (C4) deliberately has no such
 * value: converting a PUBLIC_SITE for the action / workflow runtime is a denial (`wiring.ActorKinds.toLogic` throws), so a public call can never reach it.
 */
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE, PUBLIC_SITE }

/**
 * The tenant a request operates in, resolved SERVER-SIDE from the resource being accessed (see [TenantResolver]).
 * It is never built from a query/body parameter. [tenantRole] is the caller's role in that tenant (null = not a member, e.g. a
 * platform operator). [platformScope] is true when the caller acts as SYSTEM_ADMIN on the platform rather than as a tenant member.
 */
data class TenantContext(
    val tenantId: UUID,
    val tenantRole: TenantRole?,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val platformScope: Boolean = false
) {
    val isTenantAdmin: Boolean get() = tenantRole == TenantRole.TENANT_ADMIN
    val isDefaultTenant: Boolean get() = tenantId == TenantIds.DEFAULT

    companion object {
        /** Used only by legacy code paths that construct an AccessContext without resolving tenancy. */
        val DEFAULT = TenantContext(TenantIds.DEFAULT, null)
    }
}
