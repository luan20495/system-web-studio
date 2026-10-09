package com.systemwebstudio.data.org

import org.springframework.jdbc.core.JdbcTemplate
import java.util.Random
import java.util.UUID

/**
 * Deterministic benchmark seed (fixed seed => the same structure on every run). The generator produces PURE DATA ([SeedData]) that does not know any schema, so after V32 exists
 * the same generator is reused with a loader for the real tables; [ScratchSeedLoader] is only the loader of the prototype schema.
 *
 * Default shape (the C0 benchmark dataset): 2,000 units, 5 roots, a spine of depth 12, ONE depth-60 chain, ONE node with 300 children, the rest attached at random with a bias to
 * recent nodes (so branches get deep); 10,000 employees, 25% with a second membership (3% a third), ~50 positions, ~10 grades, a position (and mostly a grade) on ~80% of the
 * primary memberships; sibling codes repeat across branches on purpose (the scope of a code is its siblings).
 */
data class SeedSpec(
    val units: Int = 2000, val roots: Int = 5, val spineDepth: Int = 12, val chainDepth: Int = 60, val broadChildren: Int = 300, val employees: Int = 10_000,
    val multiMembershipPct: Int = 25, val thirdMembershipPct: Int = 3, val positions: Int = 50, val grades: Int = 10, val seed: Long = 20_261_008L, val tag: String = "a"
)

class SeedUnit(val index: Int, val parentIndex: Int, val typeIndex: Int, val code: String, val name: String, val sortOrder: Int, val depth: Int)
class SeedEmployee(val index: Int, val username: String, val displayName: String, val email: String)
class SeedMembership(val index: Int, val employeeIndex: Int, val unitIndex: Int, val primary: Boolean, val relation: String)
class SeedPositionAssignment(val membershipIndex: Int, val positionIndex: Int, val gradeIndex: Int?, val primary: Boolean)

class SeedData(
    val spec: SeedSpec, val units: List<SeedUnit>, val employees: List<SeedEmployee>, val memberships: List<SeedMembership>, val assignments: List<SeedPositionAssignment>,
    val typeCodes: List<String>, val positionCodes: List<String>, val gradeCodes: List<String>
) {
    val maxDepth: Int = units.maxOf { it.depth } + 1                                  // root = level 1
    val children: Map<Int, List<Int>> by lazy { units.filter { it.parentIndex >= 0 }.groupBy({ it.parentIndex }, { it.index }) }
    val subtreeSize: IntArray by lazy { val s = IntArray(units.size) { 1 }; for (i in units.indices.reversed()) { val p = units[i].parentIndex; if (p >= 0) s[p] += s[i] }; s }
    /** index of the unit with the most children (the "~300 children" node) */
    val broadNode: Int by lazy { children.maxByOrNull { it.value.size }!!.key }
    /** the deepest unit (end of the depth-60 chain) */
    val deepestUnit: Int by lazy { units.maxByOrNull { it.depth }!!.index }
    val multiMembershipEmployees: Int by lazy { memberships.groupBy { it.employeeIndex }.count { it.value.size > 1 } }
}

object OrgSeedGenerator {
    private val firsts = listOf("Anh", "Binh", "Chi", "Dung", "Giang", "Hanh", "Hoa", "Khanh", "Lan", "Linh", "Minh", "Nam", "Ngoc", "Phuc", "Quang", "Son", "Thao", "Trang", "Tuan", "Yen")
    private val lasts = listOf("Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu", "Dang", "Bui", "Do", "Ngo", "Duong", "Ly", "Dinh", "Truong", "Phan")
    private val words = listOf("HR", "FIN", "OPS", "ENG", "SALES", "LEGAL", "SUPPORT", "QA", "DATA", "SEC", "PLANT", "REGION")
    private val types = listOf("COMPANY", "DIVISION", "DEPARTMENT", "TEAM", "GROUP")

    fun generate(spec: SeedSpec = SeedSpec()): SeedData {
        val rnd = Random(spec.seed); val n = spec.units
        val parent = IntArray(n) { -1 }; var next = spec.roots
        // spine of depth `spineDepth` under root 0; the depth-`chainDepth` chain under root 2; the broad node under root 1
        var prev = 0; repeat(spec.spineDepth - 1) { parent[next] = prev; prev = next++ }
        prev = if (spec.roots > 2) 2 else 0; repeat(spec.chainDepth - 1) { parent[next] = prev; prev = next++ }
        val broad = next; parent[next++] = if (spec.roots > 1) 1 else 0
        repeat(spec.broadChildren) { parent[next++] = broad }
        while (next < n) { parent[next] = if (rnd.nextBoolean()) maxOf(0, next - 1 - Math.abs(rnd.nextGaussian() * 60).toInt()) else rnd.nextInt(next); next++ }
        val depth = IntArray(n); for (i in 0 until n) depth[i] = if (parent[i] < 0) 0 else depth[parent[i]] + 1
        val used = HashMap<Int, HashSet<String>>()                                       // sibling scope: the codes already taken under one parent (-1 = the roots)
        val units = (0 until n).map { i ->
            val base = words[rnd.nextInt(words.size)]; val taken = used.getOrPut(parent[i]) { HashSet() }
            var code = base; var k = 1; while (!taken.add(code)) code = base + "-" + (k++)
            SeedUnit(i, parent[i], depth[i] % types.size, code, "Unit $i $base", rnd.nextInt(10), depth[i])
        }
        val employees = (0 until spec.employees).map { e ->
            val f = firsts[rnd.nextInt(firsts.size)]; val l = lasts[rnd.nextInt(lasts.size)]
            SeedEmployee(e, "emp${spec.tag}%05d".format(e), "$l $f", "${f.lowercase()}.${l.lowercase()}$e.${spec.tag}@corp.example")
        }
        fun unitFor() = (n * Math.pow(rnd.nextDouble(), 2.5)).toInt().coerceIn(0, n - 1)    // skew: early units (roots, spine, broad node) are crowded
        val memberships = ArrayList<SeedMembership>()
        for (e in 0 until spec.employees) {
            val first = unitFor(); val mine = mutableSetOf(first)
            memberships += SeedMembership(memberships.size, e, first, true, if (rnd.nextInt(20) == 0) "MANAGER" else "MEMBER")
            if (rnd.nextInt(100) < spec.multiMembershipPct) { val u = unitFor(); if (mine.add(u)) memberships += SeedMembership(memberships.size, e, u, false, "SECONDARY") }
            if (rnd.nextInt(100) < spec.thirdMembershipPct) { val u = unitFor(); if (mine.add(u)) memberships += SeedMembership(memberships.size, e, u, false, "MATRIX") }
        }
        val assignments = ArrayList<SeedPositionAssignment>(); val havePrimaryPosition = HashSet<Int>()
        for (m in memberships) {
            val chance = if (m.primary) 80 else 30
            if (rnd.nextInt(100) >= chance) continue
            val primary = havePrimaryPosition.add(m.employeeIndex)
            assignments += SeedPositionAssignment(m.index, rnd.nextInt(spec.positions), if (rnd.nextInt(100) < 70) rnd.nextInt(spec.grades) else null, primary)
        }
        return SeedData(spec, units, employees, memberships, assignments, types, (1..spec.positions).map { "P%03d".format(it) }, (1..spec.grades).map { "G%02d".format(it) })
    }
}


/** What a loaded dataset looks like to the tests: the row ids by seed index. */
class BenchDataset(val tenantId: UUID, val data: SeedData, val typeIds: List<UUID>, val unitIds: List<UUID>, val userIds: List<UUID>, val membershipIds: List<UUID>, val positionIds: List<UUID>, val gradeIds: List<UUID>)

/**
 * The loader of the REAL organization tables (JDBC batches, parents before children): ids are derived from (tenant, kind, index), so the same seed always lands with the same
 * ids for a given tenant. It writes through SQL, not through the repositories, so a benchmark of the repositories is not slowed by its own setup.
 */
object OrgSeedLoader {
    private fun id(tenant: UUID, kind: String, index: Int) = UUID.nameUUIDFromBytes("$tenant/$kind/$index".toByteArray())

    fun load(jdbc: JdbcTemplate, tenantId: UUID, data: SeedData, createTenant: Boolean = true): BenchDataset {
        if (createTenant) jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)", tenantId, "bench-" + tenantId.toString().replace("-", ""), "Bench")
        val typeIds = data.typeCodes.indices.map { id(tenantId, "type", it) }
        jdbc.batchUpdate("INSERT INTO organization_unit_types (id, tenant_id, code, name) VALUES (?, ?, ?, ?)", data.typeCodes.indices.toList(), 1000) { ps, i ->
            ps.setObject(1, typeIds[i]); ps.setObject(2, tenantId); ps.setString(3, data.typeCodes[i]); ps.setString(4, data.typeCodes[i])
        }
        val unitIds = data.units.map { id(tenantId, "unit", it.index) }
        jdbc.batchUpdate("INSERT INTO organization_units (id, tenant_id, type_id, parent_id, code, name, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", data.units, 500) { ps, u ->
            ps.setObject(1, unitIds[u.index]); ps.setObject(2, tenantId); ps.setObject(3, typeIds[u.typeIndex]); ps.setObject(4, if (u.parentIndex < 0) null else unitIds[u.parentIndex])
            ps.setString(5, u.code); ps.setString(6, u.name); ps.setInt(7, u.sortOrder)
        }
        val userIds = data.employees.map { id(tenantId, "user", it.index) }
        jdbc.batchUpdate("INSERT INTO users (id, username, password_hash, display_name, email) VALUES (?, ?, 'x', ?, ?)", data.employees, 1000) { ps, e ->
            ps.setObject(1, userIds[e.index]); ps.setString(2, e.username); ps.setString(3, e.displayName); ps.setString(4, e.email)
        }
        jdbc.batchUpdate("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES (?, ?, 'MEMBER')", data.employees, 1000) { ps, e -> ps.setObject(1, tenantId); ps.setObject(2, userIds[e.index]) }
        val membershipIds = data.memberships.map { id(tenantId, "membership", it.index) }
        jdbc.batchUpdate("INSERT INTO employee_organization_units (id, tenant_id, user_id, organization_unit_id, relation_type, is_primary) VALUES (?, ?, ?, ?, ?, ?)", data.memberships, 1000) { ps, m ->
            ps.setObject(1, membershipIds[m.index]); ps.setObject(2, tenantId); ps.setObject(3, userIds[m.employeeIndex]); ps.setObject(4, unitIds[m.unitIndex]); ps.setString(5, m.relation); ps.setBoolean(6, m.primary)
        }
        val positionIds = data.positionCodes.indices.map { id(tenantId, "position", it) }
        jdbc.batchUpdate("INSERT INTO positions (id, tenant_id, code, name) VALUES (?, ?, ?, ?)", data.positionCodes.indices.toList(), 1000) { ps, i -> ps.setObject(1, positionIds[i]); ps.setObject(2, tenantId); ps.setString(3, data.positionCodes[i]); ps.setString(4, "Position " + data.positionCodes[i]) }
        val gradeIds = data.gradeCodes.indices.map { id(tenantId, "grade", it) }
        jdbc.batchUpdate("INSERT INTO grades (id, tenant_id, code, name, \"rank\") VALUES (?, ?, ?, ?, ?)", data.gradeCodes.indices.toList(), 1000) { ps, i -> ps.setObject(1, gradeIds[i]); ps.setObject(2, tenantId); ps.setString(3, data.gradeCodes[i]); ps.setString(4, "Grade " + data.gradeCodes[i]); ps.setInt(5, i + 1) }
        jdbc.batchUpdate("INSERT INTO employee_positions (id, tenant_id, user_id, membership_id, position_id, grade_id, is_primary) VALUES (?, ?, ?, ?, ?, ?, ?)", data.assignments, 1000) { ps, a ->
            val m = data.memberships[a.membershipIndex]
            ps.setObject(1, id(tenantId, "assignment", a.membershipIndex * 100 + a.positionIndex)); ps.setObject(2, tenantId); ps.setObject(3, userIds[m.employeeIndex]); ps.setObject(4, membershipIds[a.membershipIndex])
            ps.setObject(5, positionIds[a.positionIndex]); ps.setObject(6, a.gradeIndex?.let { gradeIds[it] }); ps.setBoolean(7, a.primary)
        }
        jdbc.execute("ANALYZE")
        return BenchDataset(tenantId, data, typeIds, unitIds, userIds, membershipIds, positionIds, gradeIds)
    }
}

/** the benchmark dataset (+ a second, smaller tenant so tenant filters have something to filter), loaded once per JVM into the REAL tables */
object OrgBenchData {
    val main: BenchDataset by lazy { OrgSeedLoader.load(OrgTestDb.jdbc, UUID.fromString("c3000000-0000-4000-8000-000000000001"), OrgSeedGenerator.generate()) }
    val noise: BenchDataset by lazy { OrgSeedLoader.load(OrgTestDb.jdbc, UUID.fromString("c3000000-0000-4000-8000-000000000002"), OrgSeedGenerator.generate(SeedSpec(units = 500, roots = 3, spineDepth = 8, chainDepth = 20, broadChildren = 40, employees = 2000, positions = 20, grades = 5, seed = 7L, tag = "b"))) }
}
