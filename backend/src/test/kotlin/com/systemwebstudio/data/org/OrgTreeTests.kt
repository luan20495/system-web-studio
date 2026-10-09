package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The production tree reads (adjacency list + recursive CTE): roots, children, ancestors, descendants, subtree, depth, bounded full tree; multiple roots, depth 10 and 60, stable order, no employee data. */
class OrgTreeTests {
    private val fx = OrgFx(); private val s = OrgTestDb.stack

    @Test
    fun `multiple roots and children come in stable display order sort_order, name, id`() {
        val t = fx.tenant(); val ty = fx.type(t)
        val r2 = fx.unit(t, ty, "R2", sort = 2, name = "Beta"); val r1 = fx.unit(t, ty, "R1", sort = 1, name = "Zulu"); val r3 = fx.unit(t, ty, "R3", sort = 2, name = "Alpha")
        assertThat(s.units.listRoots(t).map { it.id }).containsExactly(r1.id, r3.id, r2.id)
        val ids = (1..6).map { fx.unit(t, ty, "C$it", r1.id, sort = 0, name = "same").id }                         // equal sort_order and name: the id decides, the order is total
        assertThat(s.units.listChildren(t, r1.id).map { it.id }).containsExactlyElementsOf(ids.sortedBy { it.toString() })
        assertThat(s.units.listChildren(t, r2.id)).isEmpty()
    }

    @Test
    fun `full tree is depth first with depth, ancestors root first, descendants seeded at a node`() {
        val t = fx.tenant(); val ty = fx.type(t)
        val r1 = fx.unit(t, ty, "R1", sort = 1); val a = fx.unit(t, ty, "A", r1.id, 2); val b = fx.unit(t, ty, "B", r1.id, 1)
        val b1 = fx.unit(t, ty, "B1", b.id); val b2 = fx.unit(t, ty, "B2", b.id, 1); val r0 = fx.unit(t, ty, "R0", sort = 0); val a1 = fx.unit(t, ty, "A1", a.id)
        val tree = s.units.fullTree(t)
        assertThat(tree.map { it.id }).containsExactly(r0.id, r1.id, b.id, b1.id, b2.id, a.id, a1.id); assertThat(tree.map { it.depth }).containsExactly(0, 0, 1, 2, 2, 1, 2)
        assertThat(s.units.descendants(t, b.id).map { it.id }).containsExactly(b.id, b1.id, b2.id)
        assertThat(s.units.descendants(t, a1.id).map { it.id }).containsExactly(a1.id)
        assertThat(s.units.ancestors(t, a1.id).map { it.id }).containsExactly(r1.id, a.id)
        assertThat(s.units.ancestors(t, a1.id, includeSelf = true).map { it.id }).containsExactly(r1.id, a.id, a1.id)
        assertThat(s.units.ancestors(t, r1.id)).isEmpty()
        assertThat(s.units.fullTree(t, limit = 3)).hasSize(3)                                                       // BOUNDED
    }

    @Test
    fun `depth 10 and depth 60 chains - depthOf, subtree with relative depths, ancestors and the full tree all see the whole chain`() {
        val t = fx.tenant(); val ty = fx.type(t)
        val ten = fx.chain(t, ty, null, 10, "T"); val sixty = fx.chain(t, ty, null, 60, "S")
        assertThat(s.units.depthOf(t, ten.last().id)).isEqualTo(10); assertThat(s.units.depthOf(t, sixty.last().id)).isEqualTo(60); assertThat(s.units.depthOf(t, sixty.first().id)).isEqualTo(1)
        assertThat(s.units.subtree(t, sixty.first().id).map { it.relativeDepth }).containsExactlyElementsOf((0..59).toList())
        assertThat(s.units.descendants(t, sixty.first().id).map { it.depth }).containsExactlyElementsOf((0..59).toList())
        assertThat(s.units.ancestors(t, sixty.last().id).map { it.id }).containsExactlyElementsOf(sixty.dropLast(1).map { it.id })
        assertThat(s.units.fullTree(t)).hasSize(70)
        assertThat(OrgGraph.verify(OrgTestDb.jdbc, t).also { println(it) }.ok).isTrue()
    }

    @Test
    fun `the tree statements touch no employee table, archived units leave the tree, another tenant sees nothing`() {
        val t = fx.tenant(); val other = fx.tenant(); val ty = fx.type(t); val root = fx.unit(t, ty, "R"); val child = fx.unit(t, ty, "C", root.id)
        val sql = listOf(s.units.rootsSql(t), s.units.childrenSql(t, root.id), s.units.treeSql(t, "u.parent_id IS NULL", emptyList(), 10), s.units.ancestorsSql(t, child.id), s.units.subtreeSql(t, root.id), s.units.depthSql(t, root.id))
            .joinToString(" ") { it.sql.lowercase() }
        assertThat(sql).doesNotContain("employee").doesNotContain("tenant_members").doesNotContain("users")
        s.units.setActive(t, child.id, false, 0)
        assertThat(s.units.fullTree(t).map { it.id }).containsExactly(root.id); assertThat(s.units.listChildren(t, root.id)).isEmpty()
        assertThat(s.units.subtree(t, root.id).map { it.id }).containsExactlyInAnyOrder(root.id, child.id)       // the seam's subtree is "every descendant, whatever its state"
        assertThat(s.units.fullTree(other)).isEmpty(); assertThat(s.units.descendants(other, root.id)).isEmpty(); assertThat(s.units.ancestors(other, child.id, true)).isEmpty(); assertThat(s.units.depthOf(other, root.id)).isNull()
    }

    @Test
    fun `the benchmark dataset has the required shape - 2000 units, 5 roots, a depth 60 chain, one node with 300 children, 27 percent multi-membership`() {
        val d = OrgBenchData.main; OrgBenchData.noise
        assertThat(d.data.units).hasSize(2000); assertThat(d.data.units.count { it.parentIndex < 0 }).isEqualTo(5)
        assertThat(d.data.maxDepth).isGreaterThanOrEqualTo(60); assertThat(d.data.children[d.data.broadNode]!!.size).isGreaterThanOrEqualTo(300)
        assertThat(s.units.fullTree(d.tenantId)).hasSize(2000)
        assertThat(s.units.depthOf(d.tenantId, d.unitIds[d.data.deepestUnit])).isEqualTo(d.data.maxDepth)
        assertThat(d.data.multiMembershipEmployees * 100 / d.data.employees.size).isBetween(20, 30)
        assertThat(OrgGraph.verify(OrgTestDb.jdbc, d.tenantId).also { println(it) }.ok).isTrue()
        assertThat(OrgSeedGenerator.generate().units.map { it.parentIndex }).isEqualTo(d.data.units.map { it.parentIndex })           // fixed seed: identical structure
    }
}
