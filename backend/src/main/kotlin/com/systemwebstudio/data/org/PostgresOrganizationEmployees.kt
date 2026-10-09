package com.systemwebstudio.data.org

import com.systemwebstudio.organization.DuplicateOrganizationKey
import com.systemwebstudio.organization.EmployeeDirectoryRepository
import com.systemwebstudio.organization.EmployeeOrganizationMembershipRepository
import com.systemwebstudio.organization.EmployeePositionDto
import com.systemwebstudio.organization.EmployeePositionRepository
import com.systemwebstudio.organization.EmployeeSearch
import com.systemwebstudio.organization.MembershipHasPositions
import com.systemwebstudio.organization.OrganizationMembershipDto
import com.systemwebstudio.organization.ReferencedRowInactive
import com.systemwebstudio.organization.Slice
import com.systemwebstudio.organization.TenantIdentityDirectory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.ResultSet
import java.util.UUID

// ======================================================================================================================================================== memberships
/**
 * Employee <-> unit memberships. The employee is `tenant_members(tenant_id, user_id)` (composite FK). Nothing here takes the tenant structural lock: a membership insert takes
 * `FOR SHARE` on the unit row (so it cannot slip under a concurrent archive), `end` takes `FOR UPDATE` on the membership row (so it cannot race a concurrent position insert), and
 * `setPrimary` locks the employee's active memberships in id order (two racing `setPrimary` serialise there; the partial unique index is the backstop).
 */
class PostgresEmployeeOrganizationMembershipRepository(private val db: OrgDb) : EmployeeOrganizationMembershipRepository {
    private val jdbc get() = db.jdbc
    private val select = "SELECT id, tenant_id, user_id, organization_unit_id, relation_type, is_primary, active, version, created_at, updated_at FROM employee_organization_units"
    private val keys = mapOf("employee_organization_units_active_unique" to "membership", "employee_organization_units_one_primary_idx" to "primary")

    private fun map(rs: ResultSet) = OrganizationMembershipDto(
        rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("user_id"), rs.uuid("organization_unit_id"), rs.getString("relation_type"), rs.getBoolean("is_primary"), rs.getBoolean("active"),
        rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at")
    )

    override fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<OrganizationMembershipDto> =
        jdbc.query("$select WHERE tenant_id = ? AND user_id = ?${if (includeInactive) "" else " AND active"} ORDER BY created_at, id", { rs, _ -> map(rs) }, tenantId, userId)

    override fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean): List<OrganizationMembershipDto> =
        if (userIds.isEmpty()) emptyList() else jdbc.query("$select WHERE tenant_id = ? AND user_id = ANY (?)${if (includeInactive) "" else " AND active"} ORDER BY user_id, created_at, id", { rs, _ -> map(rs) }, tenantId, userIds.distinct().toTypedArray())

    override fun find(tenantId: UUID, userId: UUID, membershipId: UUID): OrganizationMembershipDto? =
        jdbc.query("$select WHERE tenant_id = ? AND user_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, userId, membershipId).firstOrNull()

    override fun insert(membership: OrganizationMembershipDto): OrganizationMembershipDto = db.write {
        val unit = jdbc.query("SELECT active, deleted_at IS NOT NULL AS archived FROM organization_units WHERE tenant_id = ? AND id = ? FOR SHARE", { rs, _ -> rs.getBoolean("active") && !rs.getBoolean("archived") }, membership.tenantId, membership.organizationUnitId).firstOrNull()
            ?: throw IllegalStateException("the unit does not exist in this tenant")
        if (!unit) throw ReferencedRowInactive("unit")
        try {
            jdbc.update(
                "INSERT INTO employee_organization_units (id, tenant_id, user_id, organization_unit_id, relation_type, is_primary, active, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, FALSE, TRUE, 0, ?, ?)",
                membership.id, membership.tenantId, membership.userId, membership.organizationUnitId, membership.relationType, ts(membership.createdAt), ts(membership.updatedAt)
            )
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, keys)
        } catch (e: DataIntegrityViolationException) { throw IllegalStateException("the user is not a member of this tenant", e) }
        find(membership.tenantId, membership.userId, membership.id)!!
    }

    override fun update(membership: OrganizationMembershipDto, expectedVersion: Long): OrganizationMembershipDto? = db.write {
        val n = jdbc.update(
            "UPDATE employee_organization_units SET relation_type = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ? AND version = ?",
            membership.relationType, membership.tenantId, membership.userId, membership.id, expectedVersion
        )
        if (n == 0) null else find(membership.tenantId, membership.userId, membership.id)
    }

    override fun setPrimary(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long): OrganizationMembershipDto? = db.write {
        data class Row(val id: UUID, val version: Long)
        val rows = jdbc.query("SELECT id, version FROM employee_organization_units WHERE tenant_id = ? AND user_id = ? AND active ORDER BY id FOR UPDATE", { rs, _ -> Row(rs.uuid("id"), rs.getLong("version")) }, tenantId, userId)
        rows.firstOrNull { it.id == membershipId && it.version == expectedVersion } ?: return@write null
        jdbc.update("UPDATE employee_organization_units SET is_primary = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND is_primary AND id <> ?", tenantId, userId, membershipId)
        try {
            jdbc.update("UPDATE employee_organization_units SET is_primary = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ?", tenantId, userId, membershipId)
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, keys) }
        find(tenantId, userId, membershipId)
    }

    override fun end(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long): OrganizationMembershipDto? = db.write {
        jdbc.query("SELECT id FROM employee_organization_units WHERE tenant_id = ? AND user_id = ? AND id = ? AND active AND version = ? FOR UPDATE", { rs, _ -> rs.uuid("id") }, tenantId, userId, membershipId, expectedVersion).firstOrNull() ?: return@write null
        val held = jdbc.queryForObject("SELECT count(*) FROM employee_positions WHERE tenant_id = ? AND membership_id = ? AND active", Int::class.java, tenantId, membershipId)!!
        if (held > 0) throw MembershipHasPositions(held)
        jdbc.update("UPDATE employee_organization_units SET active = FALSE, is_primary = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ? AND version = ?", tenantId, userId, membershipId, expectedVersion)
        find(tenantId, userId, membershipId)
    }

    override fun activeCountByUnit(tenantId: UUID, unitId: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM employee_organization_units WHERE tenant_id = ? AND organization_unit_id = ? AND active", Int::class.java, tenantId, unitId)!!
}

// ======================================================================================================================================================== employee positions
/** A position held WITHIN one membership (composite FK to the membership and its employee); the GRADE is an attribute of the assignment; the unit is derived from the membership. */
class PostgresEmployeePositionRepository(private val db: OrgDb) : EmployeePositionRepository {
    private val jdbc get() = db.jdbc
    private val select = """SELECT ep.id, ep.tenant_id, ep.user_id, ep.membership_id, m.organization_unit_id, ep.position_id, ep.grade_id, ep.is_primary, ep.active, ep.version, ep.created_at, ep.updated_at
        FROM employee_positions ep JOIN employee_organization_units m ON m.id = ep.membership_id AND m.tenant_id = ep.tenant_id"""
    private val keys = mapOf("employee_positions_active_unique" to "assignment", "employee_positions_one_primary_idx" to "primary")

    private fun map(rs: ResultSet) = EmployeePositionDto(
        rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("user_id"), rs.uuid("membership_id"), rs.uuid("organization_unit_id"), rs.uuid("position_id"), rs.uuidOrNull("grade_id"),
        rs.getBoolean("is_primary"), rs.getBoolean("active"), rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at")
    )

    override fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<EmployeePositionDto> =
        jdbc.query("$select WHERE ep.tenant_id = ? AND ep.user_id = ?${if (includeInactive) "" else " AND ep.active"} ORDER BY ep.created_at, ep.id", { rs, _ -> map(rs) }, tenantId, userId)

    override fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean): List<EmployeePositionDto> =
        if (userIds.isEmpty()) emptyList() else jdbc.query("$select WHERE ep.tenant_id = ? AND ep.user_id = ANY (?)${if (includeInactive) "" else " AND ep.active"} ORDER BY ep.user_id, ep.created_at, ep.id", { rs, _ -> map(rs) }, tenantId, userIds.distinct().toTypedArray())

    override fun find(tenantId: UUID, userId: UUID, id: UUID): EmployeePositionDto? = jdbc.query("$select WHERE ep.tenant_id = ? AND ep.user_id = ? AND ep.id = ?", { rs, _ -> map(rs) }, tenantId, userId, id).firstOrNull()

    override fun insert(assignment: EmployeePositionDto): EmployeePositionDto = db.write {
        val m = jdbc.query(
            "SELECT organization_unit_id, active FROM employee_organization_units WHERE id = ? AND tenant_id = ? AND user_id = ? FOR SHARE", { rs, _ -> rs.uuid("organization_unit_id") to rs.getBoolean("active") },
            assignment.membershipId, assignment.tenantId, assignment.userId
        ).firstOrNull()
        require(m != null && m.first == assignment.organizationUnitId) { "the membership must belong to the same tenant and user, and name the unit" }
        if (!m.second) throw ReferencedRowInactive("membership")
        try {
            jdbc.update(
                "INSERT INTO employee_positions (id, tenant_id, user_id, membership_id, position_id, grade_id, is_primary, active, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, FALSE, TRUE, 0, ?, ?)",
                assignment.id, assignment.tenantId, assignment.userId, assignment.membershipId, assignment.positionId, assignment.gradeId, ts(assignment.createdAt), ts(assignment.updatedAt)
            )
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, keys)
        } catch (e: DataIntegrityViolationException) { throw IllegalArgumentException("the position or the grade does not exist in this tenant", e) }
        find(assignment.tenantId, assignment.userId, assignment.id)!!
    }

    override fun update(assignment: EmployeePositionDto, expectedVersion: Long): EmployeePositionDto? = db.write {
        val n = try {
            jdbc.update(
                "UPDATE employee_positions SET grade_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ? AND active AND version = ?",
                assignment.gradeId, assignment.tenantId, assignment.userId, assignment.id, expectedVersion
            )
        } catch (e: DataIntegrityViolationException) { throw IllegalArgumentException("the grade does not exist in this tenant", e) }
        if (n == 0) null else find(assignment.tenantId, assignment.userId, assignment.id)
    }

    override fun setPrimary(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long): EmployeePositionDto? = db.write {
        data class Row(val id: UUID, val version: Long)
        val rows = jdbc.query("SELECT id, version FROM employee_positions WHERE tenant_id = ? AND user_id = ? AND active ORDER BY id FOR UPDATE", { rs, _ -> Row(rs.uuid("id"), rs.getLong("version")) }, tenantId, userId)
        rows.firstOrNull { it.id == id && it.version == expectedVersion } ?: return@write null
        jdbc.update("UPDATE employee_positions SET is_primary = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND is_primary AND id <> ?", tenantId, userId, id)
        try {
            jdbc.update("UPDATE employee_positions SET is_primary = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ?", tenantId, userId, id)
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, keys) }
        find(tenantId, userId, id)
    }

    override fun end(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long): EmployeePositionDto? = db.write {
        val n = jdbc.update("UPDATE employee_positions SET active = FALSE, is_primary = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND user_id = ? AND id = ? AND active AND version = ?", tenantId, userId, id, expectedVersion)
        if (n == 0) null else find(tenantId, userId, id)
    }
}

// ======================================================================================================================================================== employee directory
/**
 * The employee directory: `tenant_members JOIN users` (identity is C1's, nothing is copied; there is no profile table). PAGE FIRST: filter, stable sort and LIMIT / OFFSET run on the
 * members of the tenant only, and the filters on memberships / positions are `EXISTS` (a person matching through several units is still ONE row, nothing is joined per assignment);
 * C1 then enriches the <= 100 ids of the page with `listForUsers` (one query per kind, no N+1). Bounded offset: a page beyond offset 10,000 answers an empty page and the true total.
 */
class PostgresEmployeeDirectoryRepository(private val db: OrgDb) : EmployeeDirectoryRepository {
    private val jdbc get() = db.jdbc

    private fun where(tenantId: UUID, c: EmployeeSearch): Pair<String, List<Any>> {
        val sb = StringBuilder(" WHERE tm.tenant_id = ?"); val args = mutableListOf<Any>(tenantId)
        c.active?.let { sb.append(" AND tm.active = ?"); args += it }
        c.userId?.let { sb.append(" AND tm.user_id = ?"); args += it }
        val text = c.text?.trim()?.lowercase().orEmpty()
        if (text.isNotEmpty()) {
            val p = "%" + OrgDb.likeLiteral(text) + "%"
            sb.append(" AND (lower(u.username) LIKE ? ESCAPE '\\' OR lower(coalesce(u.display_name, '')) LIKE ? ESCAPE '\\' OR lower(coalesce(u.email, '')) LIKE ? ESCAPE '\\')"); repeat(3) { args += p }
        }
        c.unitIds?.let {
            sb.append(" AND EXISTS (SELECT 1 FROM employee_organization_units m WHERE m.tenant_id = tm.tenant_id AND m.user_id = tm.user_id AND m.active AND m.organization_unit_id = ANY (?))"); args.add(it.toTypedArray())
        }
        c.positionId?.let { sb.append(" AND EXISTS (SELECT 1 FROM employee_positions ep WHERE ep.tenant_id = tm.tenant_id AND ep.user_id = tm.user_id AND ep.active AND ep.position_id = ?)"); args += it }
        c.gradeId?.let { sb.append(" AND EXISTS (SELECT 1 FROM employee_positions ep WHERE ep.tenant_id = tm.tenant_id AND ep.user_id = tm.user_id AND ep.active AND ep.grade_id = ?)"); args += it }
        return sb.toString() to args
    }

    internal fun searchSql(tenantId: UUID, c: EmployeeSearch): Pair<Q, Q> {
        val size = c.size.coerceIn(1, OrgDb.MAX_PAGE_SIZE); val offset = c.page.coerceAtLeast(0).toLong() * size
        val (w, args) = where(tenantId, c)
        val key = if (c.sort == "username") "lower(u.username)" else "lower(coalesce(u.display_name, u.username))"
        val dir = if (c.ascending) "ASC" else "DESC"
        val page = Q("SELECT u.id AS user_id FROM tenant_members tm JOIN users u ON u.id = tm.user_id$w ORDER BY $key $dir, u.id LIMIT ? OFFSET ?", args + size + offset)
        return page to Q("SELECT count(*) FROM tenant_members tm JOIN users u ON u.id = tm.user_id$w", args)
    }

    override fun search(tenantId: UUID, criteria: EmployeeSearch, identities: TenantIdentityDirectory): Slice<UUID> {
        val (page, count) = searchSql(tenantId, criteria)
        val total = jdbc.queryForObject(count.sql, Long::class.java, *count.args.toTypedArray()) ?: 0L
        val size = criteria.size.coerceIn(1, OrgDb.MAX_PAGE_SIZE)
        if (criteria.page.coerceAtLeast(0).toLong() * size > OrgDb.MAX_OFFSET) return Slice(emptyList(), total)
        return Slice(jdbc.query(page.sql, { rs, _ -> rs.uuid("user_id") }, *page.args.toTypedArray()), total)
    }
}
