package com.systemwebstudio.data.org

import com.systemwebstudio.organization.EmployeePositionDto
import com.systemwebstudio.organization.EmployeeSearch
import com.systemwebstudio.organization.OrganizationUnitDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import java.util.UUID

/**
 * FINAL-SCHEMA BENCHMARK of the PRODUCTION repositories (not the prototype): the real organization tables, the C3 repositories, the deterministic 2,000-unit / 10,000-employee dataset (a depth-60
 * chain, a 300-children node, 27% multi-membership). Every statement is the exact SQL the repositories run, through EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) and timed from the JVM.
 * Environment: Testcontainers PostgreSQL 17.6 on a developer Mac, default configuration; the schema is applied from the PENDING SQL (not yet a numbered Flyway migration). Reports: build/reports/org-benchmark.md,
 * org-concurrency.md.
 */
class OrgBenchmarkTests {
    private val s = OrgTestDb.stack; private val jdbc = OrgTestDb.jdbc
    private val banner = "**FINAL-SCHEMA BENCHMARK of the PRODUCTION repositories** (schema source: `${OrgTestDb.schemaSource}`; not yet a numbered Flyway migration). PostgreSQL 17.6 in Testcontainers on a developer Mac, default configuration. " +
        "\"first\" = first execution after seeding + ANALYZE (plan-cold, NOT disk-cold: the data was just written). Numbers show the shape of the curve, they are not an SLA."
    private fun report(file: String, text: String) { val dir = Path.of("build", "reports"); Files.createDirectories(dir); Files.writeString(dir.resolve(file), text) }
    private fun search(t: UUID, text: String? = null, unitIds: Set<UUID>? = null, position: UUID? = null, grade: UUID? = null, page: Int = 0, size: Int = 100, active: Boolean? = true) =
        EmployeeSearch(text, unitIds, position, grade, active, null, "name", true, page, size)

    @Test
    fun `final benchmark - tree, counts, directory, page enrichment, move`() {
        val d = OrgBenchData.main; OrgBenchData.noise; val data = d.data; val t = d.tenantId; val bench = BenchRunner(jdbc); val u = d.unitIds
        val nonRoot = data.units.indices.filter { data.units[it].parentIndex >= 0 }
        fun nearest(size: Int) = nonRoot.minByOrNull { Math.abs(data.subtreeSize[it] - size) }!!
        fun subtreeIds(root: Int): Set<UUID> { val out = HashSet<UUID>(); val st = ArrayDeque(listOf(root)); while (st.isNotEmpty()) { val x = st.removeLast(); out += u[x]; data.children[x]?.let { st.addAll(it) } }; return out }
        val small = nearest(6); val medium = nearest(100); val large = (0 until 5).maxByOrNull { data.subtreeSize[it] }!!; val broad = data.broadNode; val deepest = data.deepestUnit; val spineNode = 6; val e123 = data.employees[123]
        val head = StringBuilder("# Dynamic organization - FINAL-SCHEMA benchmark (production repositories)\n\n$banner\n\n")
        head.append("- engine: ${jdbc.queryForObject("SELECT version()", String::class.java)}; shared_buffers=${jdbc.queryForObject("SHOW shared_buffers", String::class.java)}; work_mem=${jdbc.queryForObject("SHOW work_mem", String::class.java)}\n")
        head.append("- dataset: ${data.units.size} units, ${data.units.count { it.parentIndex < 0 }} roots, max depth ${data.maxDepth} levels (a depth-${data.spec.chainDepth} chain + a spine of ${data.spec.spineDepth}), broadest node ${data.children[broad]!!.size} children; subtree sizes small=${data.subtreeSize[small]} medium=${data.subtreeSize[medium]} large=${data.subtreeSize[large]}\n")
        head.append("- ${data.employees.size} employees (tenant members), ${data.memberships.size} memberships, ${data.multiMembershipEmployees} employees with several memberships (${100 * data.multiMembershipEmployees / data.employees.size}%), ${data.assignments.size} employee positions, ${data.positionCodes.size} positions, ${data.gradeCodes.size} grades; a second tenant of ${OrgBenchData.noise.data.units.size} units / ${OrgBenchData.noise.data.employees.size} employees; seed ${data.spec.seed}\n\n")

        bench.measure("roots", s.units.rootsSql(t)) { s.units.listRoots(t).size }
        val broadChildren = bench.measure("direct children of the broad node (${data.children[broad]!!.size})", s.units.childrenSql(t, u[broad])) { s.units.listChildren(t, u[broad]).size }
        val spineChildren = bench.measure("direct children of a spine node", s.units.childrenSql(t, u[spineNode])) { s.units.listChildren(t, u[spineNode]).size }
        bench.measure("ancestors of the deepest unit (depth ${data.maxDepth})", s.units.ancestorsSql(t, u[deepest])) { s.units.ancestors(t, u[deepest], true).size }
        bench.measure("depthOf the deepest unit (C1 seam)", s.units.depthSql(t, u[deepest])) { s.units.depthOf(t, u[deepest]) ?: 0 }
        bench.measure("small subtree (${data.subtreeSize[small]}) - ordered descendants", s.units.treeSql(t, "u.id = ?", listOf(u[small]), 5000)) { s.units.descendants(t, u[small]).size }
        bench.measure("medium subtree (${data.subtreeSize[medium]}) - ordered descendants", s.units.treeSql(t, "u.id = ?", listOf(u[medium]), 5000)) { s.units.descendants(t, u[medium]).size }
        bench.measure("large subtree (${data.subtreeSize[large]}) - ordered descendants", s.units.treeSql(t, "u.id = ?", listOf(u[large]), 5000)) { s.units.descendants(t, u[large]).size }
        bench.measure("large subtree (${data.subtreeSize[large]}) - C1 seam subtree()", s.units.subtreeSql(t, u[large])) { s.units.subtree(t, u[large]).size }
        val full = bench.measure("full tree (2,000 units), bounded", s.units.treeSql(t, "u.parent_id IS NULL", emptyList(), 5000)) { s.units.fullTree(t).size }
        bench.measure("direct + subtree DISTINCT counts, small unit", s.counts.countsSql(t, listOf(u[small]))) { s.counts.countsFor(t, listOf(u[small])).size }
        bench.measure("direct + subtree DISTINCT counts, medium unit", s.counts.countsSql(t, listOf(u[medium]))) { s.counts.countsFor(t, listOf(u[medium])).size }
        bench.measure("direct + subtree DISTINCT counts, large unit", s.counts.countsSql(t, listOf(u[large]))) { s.counts.countsFor(t, listOf(u[large])).size }
        val kids = data.children[broad]!!.map { u[it] }
        val level = bench.measure("counts for the ${kids.size} children of the broad node (one tree level, lazy UI)", s.counts.countsSql(t, kids)) { s.counts.countsFor(t, kids).size }
        val bulk = bench.measure("counts for EVERY unit (whole tree, bulk)", s.counts.countsSql(t, null)) { s.counts.countsForAll(t).size }
        bench.measure("activeCountByUnit (C1 seam: the archive blocker)", Q("SELECT count(*) FROM employee_organization_units WHERE tenant_id = ? AND organization_unit_id = ? AND active", listOf(t, u[broad]))) { s.memberships.activeCountByUnit(t, u[broad]) }

        fun dir(label: String, c: EmployeeSearch) = bench.measure("$label [page query EXPLAIN; wall = page + total]", s.directory.searchSql(t, c).first) { s.directory.search(t, c, s.identities).items.size }
        val first = dir("employee directory, first page (100)", search(t))
        dir("employee directory, offset 1,000", search(t, page = 10)); dir("employee directory, offset 5,000", search(t, page = 50)); val deep = dir("employee directory, offset 9,900 (deepest full page)", search(t, page = 99))
        val nameSearch = dir("employee search, name contains 'nguyen anh'", search(t, text = "nguyen anh")); val emailSearch = dir("employee search, e-mail (one hit)", search(t, text = e123.email))
        dir("org filter, medium subtree (${data.subtreeSize[medium]} units)", search(t, unitIds = subtreeIds(medium))); dir("org filter, large subtree (${data.subtreeSize[large]} units)", search(t, unitIds = subtreeIds(large)))
        dir("position filter", search(t, position = d.positionIds[7])); dir("grade filter", search(t, grade = d.gradeIds[4]))
        dir("combined: org (medium) + name 'tran' + active", search(t, text = "tran", unitIds = subtreeIds(medium)))
        val pageIds = s.directory.search(t, search(t, page = 3), s.identities).items
        bench.measure("page enrichment: memberships of the 100 ids of a page (listForUsers)", Q("SELECT count(*) FROM employee_organization_units WHERE tenant_id = ? AND user_id = ANY (?) AND active", listOf(t, pageIds.toTypedArray()))) { s.memberships.listForUsers(t, pageIds, false).size }
        bench.measure("page enrichment: positions of the 100 ids of a page (listForUsers)", Q("SELECT count(*) FROM employee_positions WHERE tenant_id = ? AND user_id = ANY (?) AND active", listOf(t, pageIds.toTypedArray()))) { s.employeePositions.listForUsers(t, pageIds, false).size }

        // ---- the move: the whole service-shaped transaction (acquire the structural lock + the store's atomic move), alternating a ~150-unit subtree between two roots
        val movedIdx = nearest(150); val targets = listOf(3, 4).map { u[it] }; val originalParent = u[data.units[movedIdx].parentIndex]
        var version = s.units.find(t, u[movedIdx])!!.version; var flip = 0
        val move: Measurement
        try {
            move = bench.measure("MOVE subtree of ${data.subtreeSize[movedIdx]} units (transaction: structural lock + cycle check + CAS + parent update)", null, runs = 41) {
                version = s.inTx { s.lock.acquire(t); s.units.move(t, u[movedIdx], targets[flip++ % 2], null, version)!! }.version; 1
            }
            assertThat(s.units.subtree(t, u[movedIdx]).size).isEqualTo(data.subtreeSize[movedIdx])                    // the whole subtree travelled with one row
        } finally { s.inTx { s.lock.acquire(t); s.units.move(t, u[movedIdx], originalParent, null, s.units.find(t, u[movedIdx])!!.version) } }       // the dataset is shared: put it back
        assertThat(OrgGraph.verify(jdbc, t).ok).isTrue()

        val md = StringBuilder(head).append(bench.table()).append("\n")
        md.append("- C0 / C7 initial targets: direct children p95 < 200 ms -> measured p95 ${f2(spineChildren.wallP95Ms)} ms (spine node) / ${f2(broadChildren.wallP95Ms)} ms (300 children); employee search page p95 < 300 ms -> measured p95 ${f2(nameSearch.wallP95Ms)} ms (name) / ${f2(emailSearch.wallP95Ms)} ms (e-mail), first page ${f2(first.wallP95Ms)} ms, deepest page ${f2(deep.wallP95Ms)} ms; full 2,000-unit tree median ${f2(full.wallMedianMs)} ms / p95 ${f2(full.wallP95Ms)}; move median ${f2(move.wallMedianMs)} ms / p95 ${f2(move.wallP95Ms)}; counts per tree level median ${f2(level.wallMedianMs)} ms vs whole tree ${f2(bulk.wallMedianMs)} ms.\n")
        report("org-benchmark.md", md.toString()); println(md)
        assertThat(spineChildren.wallP95Ms).describedAs("children p95").isLessThan(200.0); assertThat(broadChildren.wallP95Ms).isLessThan(200.0)
        assertThat(nameSearch.wallP95Ms).describedAs("employee search page p95").isLessThan(300.0); assertThat(emailSearch.wallP95Ms).isLessThan(300.0); assertThat(first.wallP95Ms).isLessThan(300.0); assertThat(deep.wallP95Ms).isLessThan(300.0)
    }

    // ============================================================================================================================ concurrency
    private fun loadTenant(spec: SeedSpec): BenchDataset = OrgSeedLoader.load(jdbc, UUID.randomUUID(), OrgSeedGenerator.generate(spec))

    @Test
    fun `concurrency - 100 admin operations over 10 tenants, 100 structural operations in one hot tenant, bounded lock timeout, A-B conflict storm`() {
        val md = StringBuilder("# Dynamic organization - FINAL-SCHEMA concurrency (production repositories)\n\n$banner\n\n").append(ConcurrencyReport.HEADER)
        val tenants = (0 until 10).map { loadTenant(SeedSpec(units = 300, roots = 3, spineDepth = 8, chainDepth = 25, broadChildren = 40, employees = 400, positions = 10, grades = 4, seed = 100L + it, tag = "d$it")) }
        val hot = loadTenant(SeedSpec(units = 500, roots = 4, spineDepth = 8, chainDepth = 30, broadChildren = 60, employees = 500, positions = 10, grades = 4, seed = 999L, tag = "h"))
        fun withStack(poolSize: Int, lockTimeoutMs: Int = OrgDb.DEFAULT_LOCK_TIMEOUT_MS, block: (ConcurrencyHarness, OrgTestDb.Stack) -> ConcurrencyReport): ConcurrencyReport {
            val pool = OrgTestDb.pool(poolSize, "conc-$poolSize"); try { return block(ConcurrencyHarness(pool, OrgConcurrencyClassify::classify), OrgTestDb.Stack(pool, lockTimeoutMs)) } finally { pool.close() }
        }
        fun moveOp(st: OrgTestDb.Stack, d: BenchDataset, rnd: Random): () -> OpResult = {
            val node = 5 + rnd.nextInt(d.unitIds.size - 5); val target = if (rnd.nextInt(12) == 0) null else d.unitIds[rnd.nextInt(d.unitIds.size)]
            val v = st.units.find(d.tenantId, d.unitIds[node])!!.version
            val t0 = System.nanoTime(); st.inTx { st.lock.acquire(d.tenantId); st.units.move(d.tenantId, d.unitIds[node], target, null, v) }; OpResult("OK", 0 * (System.nanoTime() - t0))
        }

        val distributed = withStack(10) { h, st ->
            val rnd = Random(5); val work = ArrayList<Pair<String, () -> OpResult>>(); val now = Instant.now()
            tenants.forEachIndexed { i, d ->
                repeat(4) { work += "move-$i" to moveOp(st, d, rnd) }
                repeat(3) { n -> val idx = 5 + rnd.nextInt(d.unitIds.size - 5); work += "rename-$i" to { val cur = st.units.find(d.tenantId, d.unitIds[idx])!!; st.units.update(cur.copy(name = "renamed-$i-$n"), cur.version); OpResult("OK") } }
                repeat(3) { n -> val e = rnd.nextInt(d.userIds.size); val unit = d.unitIds[rnd.nextInt(d.unitIds.size)]
                    work += "membership-$i" to { try { st.memberships.insert(com.systemwebstudio.organization.OrganizationMembershipDto(UUID.randomUUID(), d.tenantId, d.userIds[e], unit, "MEMBER", false, true, 0, now, now)); OpResult("OK") } catch (x: com.systemwebstudio.organization.DuplicateOrganizationKey) { OpResult("DUPLICATE_MEMBERSHIP") } } }
            }
            work.shuffle(rnd); h.run("distributed: 100 ops over 10 tenants (mixed), pool 10", work, threads = 100)
        }
        md.append(distributed.markdown()); println(ConcurrencyReport.HEADER + distributed.markdown())
        assertThat(distributed.samples.filter { it.code.startsWith("ERROR") }.map { it.label + " -> " + it.code }).describedAs("unexpected errors").isEmpty()
        assertThat(distributed.deadlocks).isZero(); assertThat(distributed.lockTimeouts).isZero()
        tenants.forEach { assertThat(OrgGraph.verify(jdbc, it.tenantId).ok).isTrue() }

        val hot10 = withStack(10) { h, st -> val rnd = Random(6); h.run("hot tenant: 100 structural moves, pool 10, lock_timeout 5 s", (1..100).map { "move" to moveOp(st, hot, rnd) }, threads = 100) }
        val hot32 = withStack(32) { h, st -> val rnd = Random(7); h.run("hot tenant: 100 structural moves, pool 32, lock_timeout 5 s", (1..100).map { "move" to moveOp(st, hot, rnd) }, threads = 100) }
        md.append(hot10.markdown()).append(hot32.markdown()); println(hot10.markdown() + hot32.markdown())
        listOf(hot10, hot32).forEach { r ->
            assertThat(r.samples.filter { it.code.startsWith("ERROR") }.map { it.label + " -> " + it.code }).describedAs("unexpected errors in ${r.name}").isEmpty(); assertThat(r.deadlocks).isZero()
            assertThat(r.outcomes.keys).allMatch { it in setOf("OK", "CYCLE", "VERSION_CONFLICT", ConcurrencyReport.LOCK_TIMEOUT, "DUPLICATE:code", "INACTIVE:unit") }
        }
        val tight = withStack(32, lockTimeoutMs = 20) { h, st -> val rnd = Random(8); h.run("hot tenant: 100 structural moves, pool 32, lock_timeout 20 ms", (1..100).map { "move" to moveOp(st, hot, rnd) }, threads = 100) }
        md.append(tight.markdown()); println(tight.markdown())
        assertThat(tight.deadlocks).isZero(); assertThat(tight.samples.filter { it.code.startsWith("ERROR") }.map { it.code }).isEmpty(); assertThat(tight.samples.maxOf { it.latencyMs }).describedAs("every wait is bounded").isLessThan(30_000.0)

        val storm = withStack(32) { h, st ->
            val ty = OrgFx(st).type(hot.tenantId, "storm" + UUID.randomUUID().toString().take(6)); val fx = OrgFx(st)
            val pairs = (1..50).map { i -> fx.unit(hot.tenantId, ty, "SA$i") to fx.unit(hot.tenantId, ty, "SB$i") }
            val work = pairs.flatMap { (a, b) -> listOf("A-under-B" to { st.inTx { st.lock.acquire(hot.tenantId); st.units.move(hot.tenantId, a.id, b.id, null, a.version) }; OpResult("OK") },
                "B-under-A" to { st.inTx { st.lock.acquire(hot.tenantId); st.units.move(hot.tenantId, b.id, a.id, null, b.version) }; OpResult("OK") }) }
            h.run("hot tenant: 50 A<->B pairs = 100 conflicting moves, pool 32", work.shuffled(Random(9)), threads = 100)
        }
        md.append(storm.markdown()); println(storm.markdown())
        assertThat(storm.outcomes["OK"]).describedAs("exactly one winner per pair").isEqualTo(50); assertThat(storm.outcomes["CYCLE"]).isEqualTo(50)
        val g = OrgGraph.verify(jdbc, hot.tenantId); println(g); assertThat(g.ok).describedAs(g.toString()).isTrue()

        md.append("\nLegend: lock wait is not measured per operation here (the move transaction includes it: see latency); max active / max awaiting = Hikari connections in use / threads waiting for a connection (sampled every 2 ms): with pool 10 and 100 simultaneous threads the DB pool, not the lock, is the first queue.\n")
        md.append("Graph integrity after every scenario: ${(tenants.map { it.tenantId } + hot.tenantId).count { OrgGraph.verify(jdbc, it).ok }} of ${tenants.size + 1} tenants acyclic, every unit reachable from a root, no cross-tenant parent.\n")
        report("org-concurrency.md", md.toString())
    }
}
