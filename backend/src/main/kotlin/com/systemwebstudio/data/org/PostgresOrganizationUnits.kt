package com.systemwebstudio.data.org

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.organization.OrgUnitTypeRules
import com.systemwebstudio.organization.OrganizationCycle
import com.systemwebstudio.organization.OrganizationUnitDto
import com.systemwebstudio.organization.OrganizationUnitInUse
import com.systemwebstudio.organization.OrganizationUnitRepository
import com.systemwebstudio.organization.OrganizationUnitTypeDto
import com.systemwebstudio.organization.OrganizationUnitTypeRepository
import com.systemwebstudio.organization.ReferencedRowInactive
import com.systemwebstudio.organization.SubtreeNode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.ResultSet
import java.util.UUID

// ======================================================================================================================================================== unit types
class PostgresOrganizationUnitTypeRepository(private val db: OrgDb) : OrganizationUnitTypeRepository {
    private val jdbc get() = db.jdbc
    private val select = "SELECT id, tenant_id, code, name, icon, active, rules::text AS rules, version, created_at, updated_at FROM organization_unit_types"

    private fun rulesOf(text: String): OrgUnitTypeRules {
        val n = db.json.readTree(text)
        fun ids(f: String): List<UUID>? = n.get(f)?.takeIf { it.isArray }?.let { arr -> (0 until arr.size()).map { UUID.fromString(arr.get(it).asString()) } }
        return OrgUnitTypeRules(ids("allowedParentTypeIds"), ids("allowedChildTypeIds"), n.get("allowRoot")?.takeIf { it.isBoolean }?.asBoolean(), n.get("maxDepth")?.takeIf { it.isNumber }?.asInt())
    }

    /** the stored document of the rules: absent (null) fields are simply not written, so `{}` = "no constraint" */
    private fun rulesJson(r: OrgUnitTypeRules): String {
        val o = db.json.createObjectNode()
        r.allowedParentTypeIds?.let { l -> val a = o.putArray("allowedParentTypeIds"); l.forEach { a.add(it.toString()) } }
        r.allowedChildTypeIds?.let { l -> val a = o.putArray("allowedChildTypeIds"); l.forEach { a.add(it.toString()) } }
        r.allowRoot?.let { o.put("allowRoot", it) }; r.maxDepth?.let { o.put("maxDepth", it) }
        return db.jsonText(o)
    }

    private fun map(rs: ResultSet) = OrganizationUnitTypeDto(
        rs.uuid("id"), rs.uuid("tenant_id"), rs.getString("name"), rs.getString("code"), rs.getString("icon"), rs.getBoolean("active"), rulesOf(rs.getString("rules")),
        rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at")
    )

    override fun list(tenantId: UUID, includeInactive: Boolean): List<OrganizationUnitTypeDto> =
        jdbc.query("$select WHERE tenant_id = ?${if (includeInactive) "" else " AND active"} ORDER BY lower(name), id", { rs, _ -> map(rs) }, tenantId)

    override fun find(tenantId: UUID, id: UUID): OrganizationUnitTypeDto? = jdbc.query("$select WHERE tenant_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    override fun findAll(tenantId: UUID, ids: Collection<UUID>): List<OrganizationUnitTypeDto> =
        if (ids.isEmpty()) emptyList() else jdbc.query("$select WHERE tenant_id = ? AND id = ANY (?) ORDER BY lower(name), id", { rs, _ -> map(rs) }, tenantId, ids.distinct().toTypedArray())

    override fun insert(type: OrganizationUnitTypeDto): OrganizationUnitTypeDto = db.write {
        try {
            jdbc.update(
                "INSERT INTO organization_unit_types (id, tenant_id, code, name, icon, rules, active, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 0, ?, ?)",
                type.id, type.tenantId, type.code, type.name, type.icon, rulesJson(type.rules), type.active, ts(type.createdAt), ts(type.updatedAt)
            )
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, mapOf("organization_unit_types_code_unique" to "code")) }
        find(type.tenantId, type.id)!!
    }

    override fun update(type: OrganizationUnitTypeDto, expectedVersion: Long): OrganizationUnitTypeDto? = db.write {
        val n = jdbc.update(
            "UPDATE organization_unit_types SET name = ?, icon = ?, rules = CAST(? AS jsonb), active = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND version = ?",
            type.name, type.icon, rulesJson(type.rules), type.active, type.tenantId, type.id, expectedVersion
        )
        if (n == 0) null else find(type.tenantId, type.id)
    }
}

// ======================================================================================================================================================== units
/**
 * The hierarchy on PostgreSQL: ADJACENCY LIST (`parent_id`) + recursive CTE. Every statement carries `tenant_id` (another tenant's id is "not found"); the composite foreign keys of the
 * schema are the second wall. Versioned writes are ONE `UPDATE ... WHERE tenant_id AND id AND version = ?`. Row locks make the invariants race-safe without any tenant-wide lock:
 *  - whoever makes a unit the PARENT / holder of something (child insert, move destination, membership insert, restore) takes `FOR SHARE` on that unit row and refuses when it is archived;
 *  - archive takes `FOR UPDATE` on the unit row, then counts ACTIVE children and ACTIVE memberships: the two sides conflict, so an active record can never appear under an archived unit.
 * `move` additionally takes the (re-entrant) tenant structural advisory lock and re-checks the cycle itself.
 */
class PostgresOrganizationUnitRepository(private val db: OrgDb) : OrganizationUnitRepository {
    private val jdbc get() = db.jdbc
    private val cols = "u.id, u.tenant_id, u.type_id, u.parent_id, u.name, u.code, u.sort_order, u.metadata::text AS metadata, u.active, u.version, u.created_at, u.updated_at, u.deleted_at"
    private val from = "FROM organization_units u"

    private fun map(rs: ResultSet) = OrganizationUnitDto(
        rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("type_id"), rs.uuidOrNull("parent_id"), rs.getString("name"), rs.getString("code"), rs.getInt("sort_order"),
        db.json.readTree(rs.getString("metadata")), rs.getBoolean("active"), rs.getLong("version"), rs.instant("created_at"), rs.instant("updated_at"), rs.instantOrNull("deleted_at")
    )

    private val codeKey = mapOf("organization_units_sibling_code_unique" to "code")

    override fun find(tenantId: UUID, id: UUID): OrganizationUnitDto? = jdbc.query("SELECT $cols $from WHERE u.tenant_id = ? AND u.id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    override fun listAll(tenantId: UUID, includeArchived: Boolean): List<OrganizationUnitDto> =
        jdbc.query("SELECT $cols $from WHERE u.tenant_id = ?${if (includeArchived) "" else " AND u.deleted_at IS NULL"} ORDER BY u.sort_order, u.name, u.id", { rs, _ -> map(rs) }, tenantId)

    /** the row of [id] locked FOR SHARE: (active, archived?) or null when it does not exist in the tenant */
    private fun sharedHead(tenantId: UUID, id: UUID): Pair<Boolean, Boolean>? =
        jdbc.query("SELECT active, deleted_at IS NOT NULL AS archived FROM organization_units WHERE tenant_id = ? AND id = ? FOR SHARE", { rs, _ -> rs.getBoolean("active") to rs.getBoolean("archived") }, tenantId, id).firstOrNull()

    private fun requireLiveParent(tenantId: UUID, parentId: UUID) {
        val h = sharedHead(tenantId, parentId) ?: throw IllegalStateException("the parent unit does not exist in this tenant")
        if (!h.first || h.second) throw ReferencedRowInactive("unit")
    }

    override fun insert(unit: OrganizationUnitDto): OrganizationUnitDto = db.write {
        unit.parentId?.let { requireLiveParent(unit.tenantId, it) }
        try {
            jdbc.update(
                "INSERT INTO organization_units (id, tenant_id, type_id, parent_id, name, code, sort_order, metadata, active, deleted_at, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 0, ?, ?)",
                unit.id, unit.tenantId, unit.typeId, unit.parentId, unit.name, unit.code, unit.sortOrder, db.jsonText(unit.metadata), unit.active, unit.archivedAt?.let { ts(it) }, ts(unit.createdAt), ts(unit.updatedAt)
            )
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, codeKey)
        } catch (e: DataIntegrityViolationException) { throw IllegalStateException("the unit refers to a type or a parent of another tenant", e) }
        recheckMaxDepth(unit.tenantId, unit.id)
        find(unit.tenantId, unit.id)!!
    }

    override fun update(unit: OrganizationUnitDto, expectedVersion: Long): OrganizationUnitDto? = db.write {
        try {
            jdbc.query(
                "UPDATE organization_units u SET name = ?, code = ?, sort_order = ?, metadata = CAST(? AS jsonb), version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE u.tenant_id = ? AND u.id = ? AND u.version = ? RETURNING $cols",
                { rs, _ -> map(rs) }, unit.name, unit.code, unit.sortOrder, db.jsonText(unit.metadata), unit.tenantId, unit.id, expectedVersion
            ).firstOrNull()
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, codeKey) }
    }

    override fun move(tenantId: UUID, id: UUID, newParentId: UUID?, sortOrder: Int?, expectedVersion: Long): OrganizationUnitDto? = db.write {
        db.lockTenantStructure(tenantId)                                                                                    // re-entrant: C1's service already holds it
        jdbc.query("SELECT id FROM organization_units WHERE tenant_id = ? AND id = ? AND version = ? FOR UPDATE", { rs, _ -> rs.uuid("id") }, tenantId, id, expectedVersion).firstOrNull() ?: return@write null
        if (newParentId != null) {
            val h = sharedHead(tenantId, newParentId) ?: return@write null                                                   // a destination of another tenant / unknown: nothing applies
            if (!h.first || h.second) throw ReferencedRowInactive("unit")
            if (newParentId == id || isInChainOf(tenantId, newParentId, id)) throw OrganizationCycle()                       // the store refuses the cycle itself
        }
        try {
            jdbc.query(
                "UPDATE organization_units u SET parent_id = ?, sort_order = COALESCE(?, sort_order), version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE u.tenant_id = ? AND u.id = ? AND u.version = ? RETURNING $cols",
                { rs, _ -> map(rs) }, newParentId, sortOrder, tenantId, id, expectedVersion
            ).firstOrNull()
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, codeKey) }
    }

    override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): OrganizationUnitDto? = db.write {
        val row = jdbc.query("SELECT parent_id FROM organization_units WHERE tenant_id = ? AND id = ? AND version = ? FOR UPDATE", { rs, _ -> rs.uuidOrNull("parent_id") to true }, tenantId, id, expectedVersion).firstOrNull() ?: return@write null
        if (!active) {
            val children = jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ? AND parent_id = ? AND active", Int::class.java, tenantId, id)!!
            val members = jdbc.queryForObject("SELECT count(*) FROM employee_organization_units WHERE tenant_id = ? AND organization_unit_id = ? AND active", Int::class.java, tenantId, id)!!
            if (children > 0 || members > 0) throw OrganizationUnitInUse(children, members)
        } else row.first?.let { requireLiveParent(tenantId, it) }
        val updated = try {
            jdbc.query(
                "UPDATE organization_units u SET active = ?, deleted_at = ${if (active) "NULL" else "CURRENT_TIMESTAMP"}, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE u.tenant_id = ? AND u.id = ? AND u.version = ? RETURNING $cols",
                { rs, _ -> map(rs) }, active, tenantId, id, expectedVersion
            ).firstOrNull()
        } catch (e: DuplicateKeyException) { throw db.duplicate(e, codeKey) } ?: return@write null
        if (active) recheckMaxDepth(tenantId, id)
        updated
    }

    override fun subtree(tenantId: UUID, id: UUID): List<SubtreeNode> = subtreeSql(tenantId, id).let { q ->
        jdbc.query(q.sql, { rs, _ -> SubtreeNode(rs.uuid("id"), rs.uuid("type_id"), rs.getInt("depth"), rs.getBoolean("active")) }, *q.args.toTypedArray())
    }

    internal fun subtreeSql(tenantId: UUID, id: UUID) = Q(
        """WITH RECURSIVE sub AS (
               SELECT id, type_id, active, 0 AS depth FROM organization_units WHERE tenant_id = ? AND id = ?
               UNION ALL
               SELECT c.id, c.type_id, c.active, s.depth + 1 FROM sub s JOIN organization_units c ON c.tenant_id = ? AND c.parent_id = s.id WHERE s.depth < ${OrgDb.MAX_DEPTH}
           ) SELECT id, type_id, active, depth FROM sub ORDER BY depth, id""", listOf(tenantId, id, tenantId)
    )

    override fun depthOf(tenantId: UUID, id: UUID): Int? = depthSql(tenantId, id).let { q -> jdbc.queryForObject(q.sql, Int::class.java, *q.args.toTypedArray()) }

    internal fun depthSql(tenantId: UUID, id: UUID) = Q(
        """WITH RECURSIVE up AS (
               SELECT id, parent_id, 1 AS lvl FROM organization_units WHERE tenant_id = ? AND id = ?
               UNION ALL
               SELECT p.id, p.parent_id, up.lvl + 1 FROM up JOIN organization_units p ON p.tenant_id = ? AND p.id = up.parent_id WHERE up.lvl < ${OrgDb.MAX_DEPTH}
           ) SELECT max(lvl) FROM up""", listOf(tenantId, id, tenantId)
    )

    override fun activeChildCount(tenantId: UUID, id: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ? AND parent_id = ? AND active", Int::class.java, tenantId, id)!!

    /** true when [searchId] is [startId] itself or one of its ancestors (the chain from [startId] up to its root) */
    private fun isInChainOf(tenantId: UUID, startId: UUID, searchId: UUID): Boolean = jdbc.queryForObject(
        """WITH RECURSIVE up AS (
               SELECT id, parent_id, 0 AS dist FROM organization_units WHERE tenant_id = ? AND id = ?
               UNION ALL
               SELECT p.id, p.parent_id, up.dist + 1 FROM up JOIN organization_units p ON p.tenant_id = ? AND p.id = up.parent_id WHERE up.dist < ${OrgDb.MAX_DEPTH}
           ) SELECT EXISTS (SELECT 1 FROM up WHERE id = ?)""", Boolean::class.java, tenantId, startId, tenantId, searchId
    ) == true

    /**
     * C1 contract (CF-4 residual race): create / restore validate the type's `maxDepth` WITHOUT the structural lock. Re-checked here, inside the write transaction and under the parent row
     * `FOR SHARE` taken before it, which closes the window left by the service's own pre-check (a concurrent move of an ANCESTOR is the one case that can still slip through).
     * Same refusal as C1's service: 409 ORG_TYPE_RULE_VIOLATION, reason MAX_DEPTH.
     */
    private fun recheckMaxDepth(tenantId: UUID, id: UUID) {
        val max = jdbc.query(
            "SELECT (t.rules ->> 'maxDepth')::int AS max_depth FROM organization_units u JOIN organization_unit_types t ON t.id = u.type_id AND t.tenant_id = u.tenant_id WHERE u.tenant_id = ? AND u.id = ?",
            { rs, _ -> rs.getObject("max_depth") as Int? }, tenantId, id
        ).firstOrNull() ?: return
        val depth = depthOf(tenantId, id) ?: return
        if (depth > max) throw ApiException.conflict("ORG_TYPE_RULE_VIOLATION", "Units of this type cannot sit deeper than level $max", mapOf("reason" to "MAX_DEPTH"))
    }

    // ---------------------------------------------------------------------------------------------------------------------------------- extra tree reads (not part of the C1 seam)
    internal fun rootsSql(tenantId: UUID) = Q("SELECT $cols $from WHERE u.tenant_id = ? AND u.parent_id IS NULL AND u.deleted_at IS NULL ORDER BY u.sort_order, u.name, u.id", listOf(tenantId))
    internal fun childrenSql(tenantId: UUID, parentId: UUID) = Q("SELECT $cols $from WHERE u.tenant_id = ? AND u.parent_id = ? AND u.deleted_at IS NULL ORDER BY u.sort_order, u.name, u.id", listOf(tenantId, parentId))
    /** roots of the tenant, non-archived, `sort_order, name, id` */
    fun listRoots(tenantId: UUID): List<OrganizationUnitDto> = rootsSql(tenantId).let { q -> jdbc.query(q.sql, { rs, _ -> map(rs) }, *q.args.toTypedArray()) }
    /** direct non-archived children of [parentId], `sort_order, name, id` (served by organization_units_children_idx) */
    fun listChildren(tenantId: UUID, parentId: UUID): List<OrganizationUnitDto> = childrenSql(tenantId, parentId).let { q -> jdbc.query(q.sql, { rs, _ -> map(rs) }, *q.args.toTypedArray()) }

    /** one node of a tree read: `depth` 0 = the seed (a root of the tenant, or the node a subtree starts at); the list is depth-first in display order */
    data class TreeRow(val id: UUID, val parentId: UUID?, val typeId: UUID, val code: String, val name: String, val sortOrder: Int, val active: Boolean, val version: Long, val depth: Int)

    internal fun treeSql(tenantId: UUID, seedWhere: String, seedArgs: List<Any>, limit: Int): Q = Q(
        """WITH RECURSIVE sub AS (
               SELECT u.id, u.parent_id, u.type_id, u.code, u.name, u.sort_order, u.active, u.version, 0 AS depth FROM organization_units u WHERE u.tenant_id = ? AND $seedWhere AND u.deleted_at IS NULL
               UNION ALL
               SELECT c.id, c.parent_id, c.type_id, c.code, c.name, c.sort_order, c.active, c.version, s.depth + 1 FROM sub s
                 JOIN organization_units c ON c.tenant_id = ? AND c.parent_id = s.id AND c.deleted_at IS NULL WHERE s.depth < ${OrgDb.MAX_DEPTH}
           ), ranked AS (SELECT sub.*, row_number() OVER (PARTITION BY parent_id ORDER BY sort_order, name, id) AS rn FROM sub),
           ordered AS (SELECT r.*, ARRAY[r.rn] AS path FROM ranked r WHERE r.depth = 0
                       UNION ALL SELECT r.*, o.path || r.rn FROM ordered o JOIN ranked r ON r.parent_id = o.id AND r.depth = o.depth + 1)
           SELECT id, parent_id, type_id, code, name, sort_order, active, version, depth FROM ordered ORDER BY path LIMIT ?""", listOf<Any>(tenantId) + seedArgs + tenantId + limit
    )
    private fun treeRows(q: Q) = jdbc.query(q.sql, { rs, _ -> TreeRow(rs.uuid("id"), rs.uuidOrNull("parent_id"), rs.uuid("type_id"), rs.getString("code"), rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"), rs.getLong("version"), rs.getInt("depth")) }, *q.args.toTypedArray())
    /** the whole non-archived tree, depth-first in display order, BOUNDED to [limit] rows (the V1 expectation is ~2,000 units); no employee data is joined */
    fun fullTree(tenantId: UUID, limit: Int = DEFAULT_TREE_LIMIT): List<TreeRow> = treeRows(treeSql(tenantId, "u.parent_id IS NULL", emptyList(), limit.coerceIn(1, MAX_TREE_LIMIT)))
    /** [rootId] and every non-archived descendant, depth-first in display order (depth 0 = the node); empty when the node is not in the tenant */
    fun descendants(tenantId: UUID, rootId: UUID, limit: Int = DEFAULT_TREE_LIMIT): List<TreeRow> = treeRows(treeSql(tenantId, "u.id = ?", listOf(rootId), limit.coerceIn(1, MAX_TREE_LIMIT)))

    internal fun ancestorsSql(tenantId: UUID, id: UUID) = Q(
        """WITH RECURSIVE up AS (
               SELECT u.id, u.parent_id, u.type_id, u.code, u.name, u.sort_order, u.active, u.version, 0 AS dist FROM organization_units u WHERE u.tenant_id = ? AND u.id = ?
               UNION ALL
               SELECT p.id, p.parent_id, p.type_id, p.code, p.name, p.sort_order, p.active, p.version, up.dist + 1 FROM up JOIN organization_units p ON p.tenant_id = ? AND p.id = up.parent_id WHERE up.dist < ${OrgDb.MAX_DEPTH}
           ) SELECT id, parent_id, type_id, code, name, sort_order, active, version, dist AS depth FROM up ORDER BY dist DESC""", listOf(tenantId, id, tenantId)
    )
    /** the chain ABOVE [id], root first (`depth` 0 = the root); [includeSelf] adds the unit as the last element. Empty when the unit is not in the tenant. */
    fun ancestors(tenantId: UUID, id: UUID, includeSelf: Boolean = false): List<TreeRow> {
        val rows = treeRows(ancestorsSql(tenantId, id)).let { if (includeSelf) it else it.dropLast(1) }
        return rows.mapIndexed { i, r -> r.copy(depth = i) }
    }

    companion object { const val DEFAULT_TREE_LIMIT = 5_000; const val MAX_TREE_LIMIT = 20_000 }
}
