package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.AccountService
import com.systemwebstudio.identity.ActivationLink
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.identity.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class TenantResponse(val id: UUID, val slug: String, val name: String, val status: String, val createdAt: Instant)
data class CreateTenantRequest(val slug: String? = null, val name: String? = null, val firstAdminUserId: UUID? = null, val firstAdmin: FirstAdminRequest? = null)
data class TenantRenameRequest(val name: String? = null)
data class TenantStatusRequest(val status: String? = null)
data class TenantMemberRequest(val role: String? = null)
data class TenantWorkspaceRequest(val name: String? = null)
data class TenantUserProvisionRequest(
    val username: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val tenantRole: String? = "MEMBER",
    val workspaceId: UUID? = null,
    val workspaceRole: String? = null
)

private fun TenantEntity.toResponse() = TenantResponse(id, slug, name, status, createdAt)

/**
 * Tenant administration (T2). Authorization is done here by AccessService: platform operations (list/create/suspend) need a
 * SYSTEM_ADMIN; membership operations need TENANT_MEMBERS, held by the tenant's own TENANT_ADMIN and by SYSTEM_ADMIN (platform).
 * The tenant id is a path variable that is authorized against the caller before use; nothing is read from the body to pick a tenant.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
class TenantController(
    private val access: AccessService,
    private val service: TenantService,
    private val users: UserRepository,
    private val accounts: AccountService,
    private val bootstrap: CompanyBootstrapService
) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<TenantResponse> {
        access.forPlatform(me.userId)
        return service.list().map { it.toResponse() }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody r: CreateTenantRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantCreatedResponse {
        access.forPlatform(me.userId)
        // additive: `firstAdmin` creates the company AND its first Tenant Admin (pending account + activation link) atomically; `firstAdminUserId` keeps naming an existing account
        if (r.firstAdmin != null) {
            if (r.firstAdminUserId != null) throw ApiException.badRequest("VALIDATION_FAILED", "Send firstAdmin (a new account) or firstAdminUserId (an existing one), not both")
            return bootstrap.create(me.userId, r.slug.orEmpty(), r.name.orEmpty(), r.firstAdmin)
        }
        if (r.firstAdminUserId != null && !users.existsById(r.firstAdminUserId)) throw ApiException.badRequest("USER_NOT_FOUND", "First admin user does not exist")
        val t = service.create(r.slug.orEmpty(), r.name.orEmpty(), r.firstAdminUserId, me.userId)
        return TenantCreatedResponse(t.id, t.slug, t.name, t.status, t.createdAt)
    }

    @GetMapping("/{tenantId}")
    fun get(@PathVariable tenantId: UUID, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        access.forTenant(me.userId, tenantId)
        return service.get(tenantId).toResponse()
    }

    /** rename the company (the slug is immutable): TENANT_MANAGE on that tenant - its Tenant Admin or the platform operator; a stranger gets 404, a plain member 403 */
    @PatchMapping("/{tenantId}")
    fun rename(@PathVariable tenantId: UUID, @RequestBody r: TenantRenameRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        val a = access.forTenant(me.userId, tenantId); a.require(Permission.TENANT_MANAGE)
        if (!a.platformScope) access.requireTenantWritable(tenantId)               // a Tenant Admin of a SUSPENDED / DELETED company cannot rename it; the platform operator can repair a suspended one
        return service.rename(tenantId, r.name.orEmpty(), me.userId).toResponse()
    }

    @PatchMapping("/{tenantId}/status")
    fun setStatus(@PathVariable tenantId: UUID, @RequestBody r: TenantStatusRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        access.forPlatform(me.userId)
        val status = TenantStatus.entries.firstOrNull { it.name == r.status } ?: throw ApiException.badRequest("TENANT_STATUS_INVALID", "Status must be ACTIVE, SUSPENDED or DELETED")
        return service.setStatus(tenantId, status, me.userId).toResponse()
    }

    @PostMapping("/{tenantId}/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    fun createWorkspace(
        @PathVariable tenantId: UUID,
        @RequestBody r: TenantWorkspaceRequest,
        @AuthenticationPrincipal me: StudioUserDetails
    ): Map<String, Any> {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MANAGE)
        return service.createWorkspace(tenantId, r.name.orEmpty(), me.userId)
    }

    /**
     * Creates a brand-new LOCAL account inside exactly one authorized tenant. TENANT_ADMIN and SYSTEM_ADMIN both reach this
     * endpoint through TENANT_MEMBERS; WORKSPACE_ADMIN alone cannot. The optional workspace assignment is verified against the
     * same tenant before AccountService writes anything.
     */
    @PostMapping("/{tenantId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(
        @PathVariable tenantId: UUID,
        @RequestBody r: TenantUserProvisionRequest,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ActivationLink {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        val username = r.username?.trim().orEmpty()
        val displayName = r.displayName?.trim().orEmpty()
        val tenantRole = r.tenantRole ?: "MEMBER"
        return accounts.createTenantUser(
            me.userId, tenantId, username, displayName, r.email, tenantRole, r.workspaceId, r.workspaceRole
        )
    }

    @GetMapping("/{tenantId}/members")
    fun members(@PathVariable tenantId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<TenantMemberView> {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        return service.membersOf(tenantId)
    }

    /**
     * Tenant-scoped candidate directory. It never becomes a global user directory: only users already related to this tenant
     * (workspace membership or an inactive tenant membership) are returned.
     */
    @GetMapping("/{tenantId}/member-candidates")
    fun memberCandidates(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) q: String?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): List<TenantMemberCandidate> {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        return service.memberCandidates(tenantId, q)
    }

    @PutMapping("/{tenantId}/members/{userId}")
    fun setMember(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestBody r: TenantMemberRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantMemberView {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        val role = TenantRole.entries.firstOrNull { it.name == r.role } ?: throw ApiException.badRequest("TENANT_ROLE_INVALID", "Role must be TENANT_ADMIN or MEMBER")
        // self-grant is judged first (403 SELF_GRANT_FORBIDDEN in the service); any other target must be related to THIS tenant, else it is indistinguishable from "no such user"
        if (userId != me.userId) when (service.eligibility(tenantId, userId)) {
            TenantService.MemberEligibility.NOT_FOUND -> throw ApiException.notFound("USER_NOT_FOUND", "User not found")
            TenantService.MemberEligibility.DISABLED -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_DISABLED", "That account is disabled")
            TenantService.MemberEligibility.ELIGIBLE -> Unit
        }
        return service.setMember(tenantId, userId, role, me.userId)
    }

    @DeleteMapping("/{tenantId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        service.removeMember(tenantId, userId, me.userId)
    }
}
