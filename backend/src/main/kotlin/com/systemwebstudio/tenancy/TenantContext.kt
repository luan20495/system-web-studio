package com.systemwebstudio.tenancy

import java.util.UUID

/**
 * Kind of principal behind a call. ONE definition for the whole backend (contract v2 §2): C3/C4 convert at their boundary, they do not
 * keep their own enum. Authorization policy for each kind lives in `access.adapters` (default deny: only USER is authorised today).
 */
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }

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
