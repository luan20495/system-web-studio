package com.systemwebstudio.data.org

import com.systemwebstudio.organization.GradeDto
import com.systemwebstudio.organization.GradeRepository
import com.systemwebstudio.organization.OrganizationEmployeeCounts
import com.systemwebstudio.organization.PositionDto
import com.systemwebstudio.organization.PositionRepository
import com.systemwebstudio.organization.UnitEmployeeCounts
import org.springframework.dao.DuplicateKeyException
import java.sql.ResultSet
import java.util.UUID

/** Positions: an independent tenant taxonomy (not an organization unit, not an IAM role). Code unique per tenant, case-insensitively, even when disabled. */
class PostgresPositionRepository(private val db: OrgDb) : PositionRepository {
    private val jdbc get() = db.jdbc
    private val select = "SELECT id, tenant_id, name, code, description, active, version, created_at, updated_at FROM positions"
    private fun map(rs: ResultSet) = PositionDto(rs.uuid("id"), rs.uuid("tenant_id"), rs.getString("name"), rs.getString("code"), rs.getString("description"), rs.getBoolean("active"), rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at"))

    override fun list(tenantId: UUID, includeInactive: Boolean): List<PositionDto> = jdbc.query("$select WHERE tenant_id = ?${if (includeInactive) "" else " AND active"} ORDER BY lower(name), id", { rs, _ -> map(rs) }, tenantId)
    override fun find(tenantId: UUID, id: UUID): PositionDto? = jdbc.query("$select WHERE tenant_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    override fun insert(position: PositionDto): PositionDto = db.write {
        try {
            jdbc.update("INSERT INTO positions (id, tenant_id, code, name, description, active, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                position.id, position.tenantId, position.code, position.name, position.description, position.active, ts(position.createdAt), ts(position.updatedAt))
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, mapOf("positions_code_unique" to "code")) }
        find(position.tenantId, position.id)!!
    }

    override fun update(position: PositionDto, expectedVersion: Long): PositionDto? = db.write {
        val n = jdbc.update("UPDATE positions SET name = ?, description = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND version = ?", position.name, position.description, position.tenantId, position.id, expectedVersion)
        if (n == 0) null else find(position.tenantId, position.id)
    }

    override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): PositionDto? = db.write {
        val n = jdbc.update("UPDATE positions SET active = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND version = ?", active, tenantId, id, expectedVersion)
        if (n == 0) null else find(tenantId, id)
    }
}

/** Grades: an independent tenant taxonomy. The grade BELONGS TO the employee position (an attribute of the assignment), never to a unit or a permission. `rank` is nullable and not unique. */
class PostgresGradeRepository(private val db: OrgDb) : GradeRepository {
    private val jdbc get() = db.jdbc
    private val select = "SELECT id, tenant_id, name, code, \"rank\", description, active, version, created_at, updated_at FROM grades"
    private fun map(rs: ResultSet) = GradeDto(rs.uuid("id"), rs.uuid("tenant_id"), rs.getString("name"), rs.getString("code"), rs.getObject("rank") as Int?, rs.getString("description"), rs.getBoolean("active"), rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at"))

    override fun list(tenantId: UUID, includeInactive: Boolean): List<GradeDto> = jdbc.query("$select WHERE tenant_id = ?${if (includeInactive) "" else " AND active"} ORDER BY \"rank\" NULLS LAST, lower(name), id", { rs, _ -> map(rs) }, tenantId)
    override fun find(tenantId: UUID, id: UUID): GradeDto? = jdbc.query("$select WHERE tenant_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    override fun insert(grade: GradeDto): GradeDto = db.write {
        try {
            jdbc.update("INSERT INTO grades (id, tenant_id, code, name, \"rank\", description, active, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                grade.id, grade.tenantId, grade.code, grade.name, grade.rank, grade.description, grade.active, ts(grade.createdAt), ts(grade.updatedAt))
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, mapOf("grades_code_unique" to "code")) }
        find(grade.tenantId, grade.id)!!
    }

    override fun update(grade: GradeDto, expectedVersion: Long): GradeDto? = db.write {
        val n = jdbc.update("UPDATE grades SET name = ?, \"rank\" = ?, description = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND version = ?", grade.name, grade.rank, grade.description, grade.tenantId, grade.id, expectedVersion)
        if (n == 0) null else find(grade.tenantId, grade.id)
    }

    override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): GradeDto? = db.write {
        val n = jdbc.update("UPDATE grades SET active = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND version = ?", active, tenantId, id, expectedVersion)
        if (n == 0) null else find(tenantId, id)
    }
}


/**
 * `directMemberCount` and `subtreeEmployeeCount` (C0 frozen EMPLOYEE_COUNT; NOT part of the C1 seams today - C1 only exposes `activeCountByUnit`, which counts memberships and is what blocks an
 * archive). The subtree count is `COUNT(DISTINCT user_id)`: an employee with memberships in several units of one subtree counts once. ONE statement for any set of units (no N+1).
 */
class PostgresOrganizationCounts(private val db: OrgDb) : OrganizationEmployeeCounts {
    private val jdbc get() = db.jdbc

    /** [unitIds] null = every non-archived unit of the tenant; ids of another tenant simply do not appear */
    internal fun countsSql(tenantId: UUID, unitIds: Collection<UUID>?): Q {
        val seed = if (unitIds == null) "" else " AND id = ANY (?)"
        val sql = """WITH RECURSIVE roots AS (SELECT id FROM organization_units WHERE tenant_id = ? AND deleted_at IS NULL$seed),
           pairs AS (SELECT r.id AS root_id, r.id AS node_id, 0 AS depth FROM roots r
                     UNION ALL SELECT p.root_id, c.id, p.depth + 1 FROM pairs p JOIN organization_units c ON c.tenant_id = ? AND c.parent_id = p.node_id AND c.deleted_at IS NULL WHERE p.depth < ${OrgDb.MAX_DEPTH}),
           emp AS (SELECT m.organization_unit_id AS unit_id, m.user_id FROM employee_organization_units m JOIN tenant_members tm ON tm.tenant_id = m.tenant_id AND tm.user_id = m.user_id
                    WHERE m.tenant_id = ? AND m.active AND tm.active AND m.organization_unit_id IN (SELECT node_id FROM pairs)),
           direct AS (SELECT unit_id, count(*) AS n FROM emp GROUP BY unit_id),
           agg AS (SELECT p.root_id, count(DISTINCT e.user_id) AS n FROM pairs p JOIN emp e ON e.unit_id = p.node_id GROUP BY p.root_id)
           SELECT r.id AS unit_id, coalesce(d.n, 0) AS direct, coalesce(a.n, 0) AS subtree FROM roots r LEFT JOIN direct d ON d.unit_id = r.id LEFT JOIN agg a ON a.root_id = r.id"""
        val args = mutableListOf<Any>(tenantId); if (unitIds != null) args.add(unitIds.toTypedArray()); args.add(tenantId); args.add(tenantId)
        return Q(sql, args)
    }

    private fun run(q: Q) = jdbc.query(q.sql, { rs, _ -> UnitEmployeeCounts(rs.uuid("unit_id"), rs.getLong("direct"), rs.getLong("subtree")) }, *q.args.toTypedArray())

    /** the counts of the given units (the per-level read of a lazy tree: pass the children of the node being expanded) */
    override fun countsFor(tenantId: UUID, unitIds: Collection<UUID>): List<UnitEmployeeCounts> = if (unitIds.isEmpty()) emptyList() else run(countsSql(tenantId, unitIds))
    /** every non-archived unit of the tenant at once (bulk; the per-level form is cheaper for a UI) */
    override fun countsForAll(tenantId: UUID): List<UnitEmployeeCounts> = run(countsSql(tenantId, null))
    fun directMemberCount(tenantId: UUID, unitId: UUID): Long = countsFor(tenantId, listOf(unitId)).firstOrNull()?.direct ?: 0L
    fun subtreeEmployeeCount(tenantId: UUID, unitId: UUID): Long = countsFor(tenantId, listOf(unitId)).firstOrNull()?.subtreeDistinct ?: 0L
}
