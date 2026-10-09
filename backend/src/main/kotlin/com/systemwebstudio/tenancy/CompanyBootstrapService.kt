package com.systemwebstudio.tenancy

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.AccountService
import com.systemwebstudio.identity.ActivationLink
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class FirstAdminRequest(val username: String? = null, val displayName: String? = null, val email: String? = null)
data class TenantCreatedResponse(
    val id: UUID, val slug: String, val name: String, val status: String, val createdAt: java.time.Instant,
    /** the one-time activation link of the first Tenant Admin when the company was created together with it (never a password) */
    val firstAdmin: ActivationLink? = null
)

/**
 * Company bootstrap in ONE transaction: the tenant, the first Tenant Admin's pending account, its TENANT_ADMIN membership and its activation link are created together or not at
 * all. Any failure (a taken slug or username, an invalid e-mail, ...) rolls the whole thing back: no tenant without its first admin, no orphan account without its tenant.
 * The account is the existing provisioning ([AccountService.createTenantUser]); no password is accepted, stored or logged here. The caller must already be an authorized SYSTEM_ADMIN.
 */
@Service
class CompanyBootstrapService(private val tenants: TenantService, private val accounts: AccountService) {
    @Transactional
    fun create(actorId: UUID, slug: String, name: String, firstAdmin: FirstAdminRequest): TenantCreatedResponse {
        val username = firstAdmin.username?.trim().orEmpty()
        if (username.isEmpty()) throw ApiException.badRequest("VALIDATION_FAILED", "firstAdmin.username is required")
        val tenant = tenants.create(slug, name, null, actorId)
        val link = accounts.createTenantUser(actorId, tenant.id, username, firstAdmin.displayName?.trim().orEmpty(), firstAdmin.email, TenantRole.TENANT_ADMIN.name, null, null)
        return TenantCreatedResponse(tenant.id, tenant.slug, tenant.name, tenant.status, tenant.createdAt, link)
    }
}
