package com.systemwebstudio.data.org

import com.systemwebstudio.organization.EmployeeSearch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** directMemberCount / subtreeEmployeeCount (DISTINCT by employee) and the 10,000-employee directory on the production repositories, against an independent in-memory oracle of the seed. */
class OrgCountsDirectoryTests {
    private val fx = OrgFx(); private val s = OrgTestDb.stack

    private fun search(t: UUID, text: String? = null, unitIds: Set<UUID>? = null, position: UUID? = null, grade: UUID? = null, active: Boolean? = null, sort: String = "name", asc: Boolean = true, page: Int = 0, size: Int = 100, user: UUID? = null) =
        s.directory.search(t, EmployeeSearch(text, unitIds, position, grade, active, user, sort, asc, page, size), s.identities)

    // ------------------------------------------------------------------------------------------------------------------------------ counts
    @Test
    fun `direct count and DISTINCT subtree count - a person in two units of one subtree counts once, inactive members, ended memberships and archived units do not count`() {
        val t = fx.tenant(); val ty = fx.type(t)
        val r = fx.unit(t, ty, "R"); val c = fx.unit(t, ty, "C", r.id); val g = fx.unit(t, ty, "G", c.id); val x = fx.unit(t, ty, "X", r.id); val other = fx.unit(t, ty, "OTHER")
        val u1 = fx.member(t); val u2 = fx.member(t); val u3 = fx.member(t); val u4 = fx.member(t, active = false); val u5 = fx.member(t); val u6 = fx.member(t)
        fx.membership(t, u1, r.id); fx.membership(t, u2, r.id); fx.membership(t, u2, c.id); fx.membership(t, u3, c.id); fx.membership(t, u4, c.id)
        val ended = fx.membership(t, u5, g.id); s.memberships.end(t, u5, ended.id, 0)
        val mx = fx.membership(t, u6, x.id); s.memberships.end(t, u6, mx.id, 0); s.units.setActive(t, x.id, false, 0)                             // a unit archived after its member left
        fx.membership(t, u1, other.id)
        val by = s.counts.countsForAll(t).associateBy { it.unitId }
        assertThat(by.keys).containsExactlyInAnyOrder(r.id, c.id, g.id, other.id)                                                                 // the archived unit is not a unit of the live tree
        assertThat(by[r.id]!!.direct).isEqualTo(2); assertThat(by[c.id]!!.direct).isEqualTo(2); assertThat(by[g.id]!!.direct).isZero()
        assertThat(by[r.id]!!.subtreeDistinct).describedAs("u1, u2, u3 - u2 once although in two units").isEqualTo(3); assertThat(by[c.id]!!.subtreeDistinct).isEqualTo(2)
        assertThat(by[other.id]!!.subtreeDistinct).isEqualTo(1)
        assertThat(by[r.id]!!.direct + by[c.id]!!.direct).describedAs("the plain sum would double count u2").isEqualTo(4)
        assertThat(s.counts.directMemberCount(t, r.id)).isEqualTo(2); assertThat(s.counts.subtreeEmployeeCount(t, r.id)).isEqualTo(3)
        assertThat(s.counts.countsFor(t, listOf(c.id, UUID.randomUUID())).map { it.unitId }).containsExactly(c.id)
        assertThat(s.counts.countsFor(fx.tenant(), listOf(c.id))).isEmpty(); assertThat(s.counts.countsFor(t, emptyList())).isEmpty()
        assertThat(s.memberships.activeCountByUnit(t, c.id)).describedAs("the C1 seam counts MEMBERSHIPS (it blocks an archive): the disabled member counts there").isEqualTo(3)
    }

    @Test
    fun `counts of the 2000-unit dataset equal an independent in-memory oracle for every unit`() {
        val d = OrgBenchData.main; val data = d.data
        fun subtree(root: Int): Set<Int> { val out = HashSet<Int>(); val stack = ArrayDeque(listOf(root)); while (stack.isNotEmpty()) { val u = stack.removeLast(); out += u; data.children[u]?.let { stack.addAll(it) } }; return out }
        val byUnit = data.memberships.groupBy({ it.unitIndex }, { it.employeeIndex }); val got = s.counts.countsForAll(d.tenantId).associateBy { it.unitId }
        assertThat(got).hasSize(2000); var multi = 0
        for (u in data.units.indices) {
            val distinct = subtree(u).flatMap { byUnit[it].orEmpty() }.toSet().size.toLong(); val sum = subtree(u).sumOf { byUnit[it].orEmpty().size }.toLong()
            assertThat(got[d.unitIds[u]]!!.direct).describedAs("direct of unit $u").isEqualTo(byUnit[u].orEmpty().size.toLong())
            assertThat(got[d.unitIds[u]]!!.subtreeDistinct).describedAs("distinct subtree of unit $u").isEqualTo(distinct)
            if (sum != distinct) multi++
        }
        assertThat(multi).describedAs("units whose subtree holds somebody twice (DISTINCT really exercised)").isGreaterThan(50); println("EVIDENCE counts: distinct differs from the plain sum in $multi of 2000 units")
        val kids = data.children[data.broadNode]!!.map { d.unitIds[it] }
        assertThat(s.counts.countsFor(d.tenantId, kids).associate { it.unitId to it.subtreeDistinct }).isEqualTo(kids.associateWith { got[it]!!.subtreeDistinct })       // the per-level read agrees with the bulk read
    }

    // ------------------------------------------------------------------------------------------------------------------------------ directory
    @Test
    fun `10k directory - pages are complete, duplicate free, equal to the unpaged order, total is exact, bounded offset`() {
        val d = OrgBenchData.main; OrgBenchData.noise
        val expected = OrgTestDb.jdbc.queryForList("SELECT u.id FROM tenant_members tm JOIN users u ON u.id = tm.user_id WHERE tm.tenant_id = ? ORDER BY lower(coalesce(u.display_name, u.username)), u.id", UUID::class.java, d.tenantId)
        assertThat(expected).hasSize(10_000)
        val all = ArrayList<UUID>(); var page = 0
        while (true) { val p = search(d.tenantId, page = page, size = 100); all += p.items; assertThat(p.total).isEqualTo(10_000); if (p.items.size < 100) break; page++ }
        assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf(expected)                                                                // multi-membership never duplicates a person; the order is deterministic
        assertThat(search(d.tenantId, page = 100, size = 100).items).describedAs("offset 10000 is the last allowed page").isEmpty()
        val beyond = search(d.tenantId, page = 101, size = 100); assertThat(beyond.items).describedAs("offset beyond 10,000: empty page, true total").isEmpty(); assertThat(beyond.total).isEqualTo(10_000)
        assertThat(search(d.tenantId, sort = "name", asc = false, size = 5).items).containsExactlyElementsOf(OrgTestDb.jdbc.queryForList(
            "SELECT u.id FROM tenant_members tm JOIN users u ON u.id = tm.user_id WHERE tm.tenant_id = ? ORDER BY lower(coalesce(u.display_name, u.username)) DESC, u.id ASC LIMIT 5", UUID::class.java, d.tenantId))
        assertThat(search(fx.tenant()).total).isZero(); assertThat(search(OrgBenchData.noise.tenantId).total).isEqualTo(2000)
    }

    @Test
    fun `10k directory - unit, position, grade, name, e-mail, status and combined filters equal the in-memory oracle`() {
        val d = OrgBenchData.main; val data = d.data; val idx = d.userIds.withIndex().associate { it.value to it.index }
        fun subtree(root: Int): Set<Int> { val out = HashSet<Int>(); val stack = ArrayDeque(listOf(root)); while (stack.isNotEmpty()) { val u = stack.removeLast(); out += u; data.children[u]?.let { stack.addAll(it) } }; return out }
        fun all(q: (Int) -> com.systemwebstudio.organization.Slice<UUID>): List<Int> { val out = ArrayList<Int>(); var p = 0; while (true) { val r = q(p); out += r.items.map { idx[it]!! }; if (r.items.size < 100) return out; p++ } }
        val medium = data.units.indices.filter { data.units[it].parentIndex >= 0 }.minByOrNull { Math.abs(data.subtreeSize[it] - 100) }!!
        for (root in listOf(medium, 0, 1, 2)) {
            val units = subtree(root); val oracle = data.memberships.filter { it.unitIndex in units }.map { it.employeeIndex }.toSet()
            val ids = units.map { d.unitIds[it] }.toSet()
            assertThat(all { search(d.tenantId, unitIds = ids, page = it).also { r -> assertThat(r.total).isEqualTo(oracle.size.toLong()) } }).describedAs("unit filter, root $root").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(oracle)
        }
        for (p in listOf(0, 7, 49)) {
            val oracle = data.assignments.filter { it.positionIndex == p }.map { data.memberships[it.membershipIndex].employeeIndex }.toSet()
            assertThat(all { search(d.tenantId, position = d.positionIds[p], page = it) }).describedAs("position $p").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(oracle)
        }
        for (g in listOf(0, 4)) {
            val oracle = data.assignments.filter { it.gradeIndex == g }.map { data.memberships[it.membershipIndex].employeeIndex }.toSet()
            assertThat(all { search(d.tenantId, grade = d.gradeIds[g], page = it) }).describedAs("grade $g").containsExactlyInAnyOrderElementsOf(oracle)
        }
        val nameOracle = data.employees.filter { it.displayName.lowercase().contains("nguyen anh") }.map { it.index }.toSet(); assertThat(nameOracle).isNotEmpty()
        assertThat(all { search(d.tenantId, text = "NGUYEN Anh", page = it) }).containsExactlyInAnyOrderElementsOf(nameOracle)
        assertThat(all { search(d.tenantId, text = data.employees[123].email, page = it) }).containsExactly(123)
        val units1 = subtree(1); val inOrg = data.memberships.filter { it.unitIndex in units1 }.map { it.employeeIndex }.toSet()
        val combined = inOrg.filter { data.employees[it].displayName.lowercase().contains("tran") }.toSet()
        assertThat(all { search(d.tenantId, text = "tran", unitIds = units1.map { d.unitIds[it] }.toSet(), active = true, page = it) }).containsExactlyInAnyOrderElementsOf(combined)
        assertThat(search(d.tenantId, active = false).total).isZero(); assertThat(search(d.tenantId, user = d.userIds[5]).items).containsExactly(d.userIds[5])
    }
}
