package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.identity.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class TenantResponse(val id: UUID, val slug: String, val name: String, val status: String, val createdAt: Instant)
data class CreateTenantRequest(val slug: String? = null, val name: String? = null, val firstAdminUserId: UUID? = null)
data class TenantStatusRequest(val status: String? = null)
data class TenantMemberRequest(val role: String? = null)

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
    private val users: UserRepository
) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<TenantResponse> {
        access.forPlatform(me.userId)
        return service.list().map { it.toResponse() }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody r: CreateTenantRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        access.forPlatform(me.userId)
        if (r.firstAdminUserId != null && !users.existsById(r.firstAdminUserId)) throw ApiException.badRequest("USER_NOT_FOUND", "First admin user does not exist")
        return service.create(r.slug.orEmpty(), r.name.orEmpty(), r.firstAdminUserId, me.userId).toResponse()
    }

    @GetMapping("/{tenantId}")
    fun get(@PathVariable tenantId: UUID, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        access.forTenant(me.userId, tenantId)
        return service.get(tenantId).toResponse()
    }

    @PatchMapping("/{tenantId}/status")
    fun setStatus(@PathVariable tenantId: UUID, @RequestBody r: TenantStatusRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantResponse {
        access.forPlatform(me.userId)
        val status = TenantStatus.entries.firstOrNull { it.name == r.status } ?: throw ApiException.badRequest("TENANT_STATUS_INVALID", "Status must be ACTIVE, SUSPENDED or DELETED")
        return service.setStatus(tenantId, status, me.userId).toResponse()
    }

    @GetMapping("/{tenantId}/members")
    fun members(@PathVariable tenantId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<TenantMemberView> {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        return service.membersOf(tenantId)
    }

    @PutMapping("/{tenantId}/members/{userId}")
    fun setMember(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestBody r: TenantMemberRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantMemberView {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        val role = TenantRole.entries.firstOrNull { it.name == r.role } ?: throw ApiException.badRequest("TENANT_ROLE_INVALID", "Role must be TENANT_ADMIN or MEMBER")
        if (!users.existsById(userId)) throw ApiException.notFound("USER_NOT_FOUND", "User not found")
        return service.setMember(tenantId, userId, role, me.userId)
    }

    @DeleteMapping("/{tenantId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        access.forTenant(me.userId, tenantId).require(Permission.TENANT_MEMBERS)
        service.removeMember(tenantId, userId, me.userId)
    }
}
