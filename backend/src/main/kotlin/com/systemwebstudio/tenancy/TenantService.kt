package com.systemwebstudio.tenancy

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class TenantMemberView(val tenantId: UUID, val userId: UUID, val role: String, val active: Boolean)

/**
 * Tenant lifecycle and tenant membership. Callers MUST authorize first (AccessService.forPlatform / forTenant): this service
 * enforces data invariants (unique slug, DEFAULT tenant protected, never remove the last TENANT_ADMIN), not who may call it.
 */
@Service
class TenantService(
    private val tenants: TenantRepository,
    private val members: TenantMemberRepository,
    private val audit: AuditService
) {
    private val slugRegex = Regex("^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$")

    fun get(id: UUID): TenantEntity = tenants.findById(id).orElseThrow { notFound() }
    fun list(): List<TenantEntity> = tenants.findAll().sortedBy { it.createdAt }

    @Transactional
    fun create(slug: String, name: String, firstAdmin: UUID? = null, actorId: UUID? = null): TenantEntity {
        val s = slug.trim().lowercase()
        if (!slugRegex.matches(s)) throw ApiException.badRequest("TENANT_SLUG_INVALID", "Slug must be 2-120 chars of a-z, 0-9 and '-', starting and ending with a letter or digit")
        if (name.isBlank() || name.length > 160) throw ApiException.badRequest("TENANT_NAME_INVALID", "Name is required (max 160 characters)")
        if (tenants.findBySlug(s) != null) throw ApiException.conflict("TENANT_SLUG_TAKEN", "A tenant with this slug already exists")
        val t = tenants.save(TenantEntity(slug = s, name = name.trim()))
        if (firstAdmin != null) members.save(TenantMemberEntity(t.id, firstAdmin, TenantRole.TENANT_ADMIN.name, true, Instant.now(), actorId))
        audit.record("TENANT_CREATED", "TENANT", t.id, actorId = actorId, newValue = mapOf("slug" to t.slug, "name" to t.name, "firstAdmin" to firstAdmin))
        return t
    }

    @Transactional
    fun setStatus(id: UUID, status: TenantStatus, actorId: UUID? = null): TenantEntity {
        val t = get(id)
        if (id == TenantIds.DEFAULT && status != TenantStatus.ACTIVE) throw ApiException.conflict("DEFAULT_TENANT_PROTECTED", "The DEFAULT tenant cannot be suspended or deleted")
        val old = t.status
        t.status = status.name; t.updatedAt = Instant.now()
        tenants.save(t)
        audit.record("TENANT_STATUS_CHANGED", "TENANT", id, actorId = actorId, oldValue = mapOf("status" to old), newValue = mapOf("status" to status.name))
        return t
    }

    fun membersOf(tenantId: UUID): List<TenantMemberView> {
        get(tenantId)
        return members.findByTenantIdAndActiveTrue(tenantId).map { it.view() }
    }

    fun membershipsOf(userId: UUID): List<TenantMemberView> = members.findByUserIdAndActiveTrue(userId).map { it.view() }

    fun roleOf(tenantId: UUID, userId: UUID): TenantRole? =
        members.findByTenantIdAndUserId(tenantId, userId)?.takeIf { it.active }?.let { TenantRole.valueOf(it.role) }

    /** Adds the user to the tenant or changes the role / re-activates; refuses to demote the last active TENANT_ADMIN. */
    @Transactional
    fun setMember(tenantId: UUID, userId: UUID, role: TenantRole, actorId: UUID? = null): TenantMemberView {
        // separation of duties: nobody (TENANT_ADMIN or SYSTEM_ADMIN) adds themselves to a tenant or changes their own tenant role
        if (actorId != null && actorId == userId) throw ApiException.forbidden("You cannot grant yourself access or change your own role", "SELF_GRANT_FORBIDDEN")
        get(tenantId)
        val existing = members.findByTenantIdAndUserId(tenantId, userId)
        val before = existing?.let { mapOf("role" to it.role, "active" to it.active) }
        if (existing != null && existing.active && existing.role == TenantRole.TENANT_ADMIN.name && role != TenantRole.TENANT_ADMIN) assertNotLastAdmin(tenantId)
        val saved = if (existing == null) members.save(TenantMemberEntity(tenantId, userId, role.name, true, Instant.now(), actorId))
        else { existing.role = role.name; existing.active = true; members.save(existing) }
        audit.record("TENANT_MEMBER_SET", "TENANT", tenantId, actorId = actorId, oldValue = before, newValue = mapOf("userId" to userId, "role" to role.name))
        return saved.view()
    }

    /** Deactivates (never deletes) the membership; the last active TENANT_ADMIN cannot be removed. */
    @Transactional
    fun removeMember(tenantId: UUID, userId: UUID, actorId: UUID? = null) {
        val m = members.findByTenantIdAndUserId(tenantId, userId)?.takeIf { it.active } ?: throw ApiException.notFound("TENANT_MEMBER_NOT_FOUND", "Tenant member not found")
        if (m.role == TenantRole.TENANT_ADMIN.name) assertNotLastAdmin(tenantId)
        m.active = false
        members.save(m)
        audit.record("TENANT_MEMBER_REMOVED", "TENANT", tenantId, actorId = actorId, oldValue = mapOf("userId" to userId, "role" to m.role))
    }

    private fun assertNotLastAdmin(tenantId: UUID) {
        if (members.countByTenantIdAndRoleAndActiveTrue(tenantId, TenantRole.TENANT_ADMIN.name) <= 1)
            throw ApiException.conflict("LAST_TENANT_ADMIN", "A tenant must keep at least one TENANT_ADMIN")
    }

    private fun notFound() = ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found")
    private fun TenantMemberEntity.view() = TenantMemberView(tenantId, userId, role, active)
}
