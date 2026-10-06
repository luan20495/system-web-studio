package com.systemwebstudio.tenancy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Fixed ids shared with the V26 migration. */
object TenantIds {
    /** Tenant that owns every workspace that existed before tenancy (and every workspace created without an explicit tenant). */
    val DEFAULT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
}

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }

/** Role of a user inside a tenant. `tenant_members` is the only source of truth for it (never workspace_members). */
enum class TenantRole { TENANT_ADMIN, MEMBER }

@Entity
@Table(name = "tenants")
class TenantEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 120)
    var slug: String = "",
    @Column(nullable = false, length = 160)
    var name: String = "",
    @Column(nullable = false, length = 16)
    var status: String = TenantStatus.ACTIVE.name,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

interface TenantRepository : JpaRepository<TenantEntity, UUID> {
    fun findBySlug(slug: String): TenantEntity?
}

data class TenantMemberKey(var tenantId: UUID = UUID.randomUUID(), var userId: UUID = UUID.randomUUID()) : Serializable

@Entity
@Table(name = "tenant_members")
@IdClass(TenantMemberKey::class)
class TenantMemberEntity(
    @Id
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 16)
    var role: String = TenantRole.MEMBER.name,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "created_by")
    var createdBy: UUID? = null
)

interface TenantMemberRepository : JpaRepository<TenantMemberEntity, TenantMemberKey> {
    fun findByTenantIdAndUserId(tenantId: UUID, userId: UUID): TenantMemberEntity?
    fun findByTenantIdAndActiveTrue(tenantId: UUID): List<TenantMemberEntity>
    fun findByUserIdAndActiveTrue(userId: UUID): List<TenantMemberEntity>
    fun countByTenantIdAndRoleAndActiveTrue(tenantId: UUID, role: String): Long
}
