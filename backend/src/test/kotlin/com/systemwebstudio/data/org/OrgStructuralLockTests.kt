package com.systemwebstudio.data.org

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.organization.OrganizationCycle
import com.systemwebstudio.organization.OrganizationUnitDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The tenant STRUCTURAL lock and the move, on real PostgreSQL: the service-shaped boundary of C1 (ONE READ COMMITTED transaction: lock -> move) and the properties C1 section 10 leaves to C3:
 * serialisation of two moves of one tenant, release on commit AND rollback, re-entrancy, `IllegalStateException` without a transaction, another tenant not blocked, non-move writes never wait,
 * bounded wait, the cycle race of two opposite moves, and a negative control proving the lock is what prevents the cycle.
 */
class OrgStructuralLockTests {
    private val fx = OrgFx(); private val s = OrgTestDb.stack

    /** exactly what C1's `OrganizationUnitService.move` does around the seam: acquire, then the store's atomic move, in one transaction */
    private fun moveLikeService(stack: OrgTestDb.Stack, t: UUID, id: UUID, parent: UUID?, version: Long): OrganizationUnitDto? = stack.inTx { stack.lock.acquire(t); stack.units.move(t, id, parent, null, version) }

    @Test
    fun `the lock needs an active transaction, is re-entrant, is released by commit and by rollback`() {
        val t = fx.tenant()
        assertThatThrownBy { s.lock.acquire(t) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("active transaction")
        s.inTx { s.lock.acquire(t); s.lock.acquire(t) }                                                              // re-entrant inside one transaction
        // held while the transaction is open ...
        val holding = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val h = pool.submit(Callable { s.inTx { s.lock.acquire(t); holding.countDown(); release.await(30, TimeUnit.SECONDS) } })
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue()
        val tight = OrgTestDb.Stack(OrgTestDb.dataSource, lockTimeoutMs = 250)
        val t0 = System.nanoTime()
        assertThatThrownBy { tight.inTx { tight.lock.acquire(t) } }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(OrgDb.BUSY_CODE)   // bounded wait, retryable 503
        val waited = (System.nanoTime() - t0) / 1e6
        assertThat(waited).isBetween(200.0, 3000.0)
        release.countDown(); h.get(20, TimeUnit.SECONDS)                                                             // ... released at COMMIT
        s.inTx { s.lock.acquire(t) }
        // ... and at ROLLBACK
        val holding2 = CountDownLatch(1); val release2 = CountDownLatch(1)
        val h2 = pool.submit(Callable { runCatching { s.inTx { s.lock.acquire(t); holding2.countDown(); release2.await(30, TimeUnit.SECONDS); throw IllegalStateException("late failure") } } })
        assertThat(holding2.await(20, TimeUnit.SECONDS)).isTrue(); release2.countDown(); h2.get(20, TimeUnit.SECONDS)
        s.inTx { s.lock.acquire(t) }
        pool.shutdownNow()
        println("EVIDENCE lock: IllegalStateException without a transaction; re-entrant; bounded wait gave up after ${"%.0f".format(waited)} ms with 503 ${OrgDb.BUSY_CODE}; released by commit and by rollback")
    }

    @Test
    fun `another tenant is not blocked, and no ordinary organization write waits on the structural lock`() {
        val a = fx.tenant(); val b = fx.tenant(); val ta = fx.type(a); val tb = fx.type(b)
        val a1 = fx.unit(a, ta, "A1"); val a2 = fx.unit(a, ta, "A2"); val b1 = fx.unit(b, tb, "B1"); val b2 = fx.unit(b, tb, "B2")
        val u = fx.member(a); val holding = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val holder = pool.submit(Callable { s.inTx { s.lock.acquire(a); holding.countDown(); release.await(60, TimeUnit.SECONDS) } })
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue()
        val quick = OrgTestDb.Stack(OrgTestDb.dataSource, lockTimeoutMs = 400)                                      // any wait on the structural lock would fail within 400 ms
        val t0 = System.nanoTime()
        assertThat(moveLikeService(quick, b, b1.id, b2.id, 0)!!.parentId).isEqualTo(b2.id)                           // tenant B: its own lock, free
        val tenantB = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()                                                                                   // tenant A, everything that is NOT a move: must not wait on A's structural lock
        val ty = quick.types.insert(fx.s.types.find(a, ta.id)!!.copy(id = UUID.randomUUID(), code = "late" + fx.tag(), name = "late"))
        val child = quick.units.insert(a1.copy(id = UUID.randomUUID(), parentId = a1.id, code = "KID", name = "kid", typeId = ty.id))
        quick.units.update(child.copy(name = "renamed"), 0)!!
        val m = quick.memberships.insert(fx.membership(a, u, a2.id).copy(id = UUID.randomUUID(), organizationUnitId = a1.id).let { it.copy(userId = u) }.let { it })   // membership add (a2 already has u: use a1)
        quick.memberships.setPrimary(a, u, m.id, 0)!!
        val pos = fx.position(a); val asg = quick.employeePositions.insert(com.systemwebstudio.organization.EmployeePositionDto(UUID.randomUUID(), a, u, m.id, a1.id, pos.id, null, false, true, 0, java.time.Instant.now(), java.time.Instant.now()))
        quick.employeePositions.setPrimary(a, u, asg.id, 0)!!; quick.employeePositions.end(a, u, asg.id, 1)!!
        quick.memberships.end(a, u, m.id, 1)!!
        quick.units.setActive(a, child.id, false, 1)!!; quick.units.setActive(a, child.id, true, 2)!!                 // archive / restore of a unit: row locks only
        quick.positions.setActive(a, pos.id, false, 0)!!; quick.directory.search(a, com.systemwebstudio.organization.EmployeeSearch(null, null, null, null, null, null, "name", true, 0, 10), quick.identities)
        val nonStructural = (System.nanoTime() - t1) / 1e6
        println("EVIDENCE lock independence: tenant B move ${"%.1f".format(tenantB)} ms; tenant A non-move writes (type, unit create / update / archive / restore, membership add / primary / end, position assign / primary / end, catalog, directory) ${"%.1f".format(nonStructural)} ms while A's structural lock was held")
        val tm = System.nanoTime()
        assertThatThrownBy { moveLikeService(quick, a, a1.id, a2.id, 0) }.isInstanceOf(ApiException::class.java)    // the MOVE of tenant A does wait, and gives up at the bounded timeout
        assertThat((System.nanoTime() - tm) / 1e6).isBetween(300.0, 4000.0)
        release.countDown(); holder.get(20, TimeUnit.SECONDS); pool.shutdownNow()
        assertThat(moveLikeService(s, a, a1.id, a2.id, 0)!!.parentId).isEqualTo(a2.id)
    }

    @Test
    fun `A under B and B under A at once - the lock serialises them, exactly one wins, the other sees the committed topology and is refused as a cycle`() {
        val t = fx.tenant(); val ty = fx.type(t)
        repeat(25) { round ->
            val a = fx.unit(t, ty, "A$round"); val b = fx.unit(t, ty, "B$round"); val pool = Executors.newFixedThreadPool(2); val gate = CyclicBarrier(2)
            val results = listOf(a to b, b to a).map { (node, parent) -> pool.submit(Callable { gate.await(30, TimeUnit.SECONDS); runCatching { moveLikeService(s, t, node.id, parent.id, 0) } }) }.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdownNow()
            assertThat(results.count { it.isSuccess && it.getOrNull() != null }).describedAs("round $round: exactly one move wins").isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(OrganizationCycle::class.java)
        }
        val g = OrgGraph.verify(OrgTestDb.jdbc, t); println("A<->B final: $g"); assertThat(g.ok).isTrue()
    }

    @Test
    fun `negative control - the same cycle check WITHOUT the structural lock builds a cycle (the race is real, the lock is what prevents it)`() {
        val t = fx.tenant(); val ty = fx.type(t); val a = fx.unit(t, ty, "A"); val b = fx.unit(t, ty, "B"); val gate = CyclicBarrier(2)
        fun racer(node: UUID, newParent: UUID) = Callable {
            OrgTestDb.dataSource.connection.use { c ->
                c.autoCommit = false
                val inChain = c.prepareStatement(
                    """WITH RECURSIVE up AS (SELECT id, parent_id FROM organization_units WHERE tenant_id = ? AND id = ? UNION ALL SELECT p.id, p.parent_id FROM up JOIN organization_units p ON p.tenant_id = ? AND p.id = up.parent_id)
                       SELECT EXISTS (SELECT 1 FROM up WHERE id = ?)"""
                ).use { st -> st.setObject(1, t); st.setObject(2, newParent); st.setObject(3, t); st.setObject(4, node); st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) } }
                assertThat(inChain).isFalse(); gate.await(20, TimeUnit.SECONDS)                                      // both pass the check, nobody has written yet
                c.prepareStatement("UPDATE organization_units SET parent_id = ? WHERE tenant_id = ? AND id = ?").use { st -> st.setObject(1, newParent); st.setObject(2, t); st.setObject(3, node); st.executeUpdate() }
                c.commit()
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try { listOf(pool.submit(racer(a.id, b.id)), pool.submit(racer(b.id, a.id))).forEach { it.get(60, TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
        val g = OrgGraph.verify(OrgTestDb.jdbc, t); assertThat(g.ok).describedAs("without the lock the topology is broken: $g").isFalse(); assertThat(g.unreachable).isEqualTo(2)
        OrgTestDb.jdbc.update("UPDATE organization_units SET parent_id = NULL WHERE tenant_id = ?", t)
    }

    @Test
    fun `concurrent moves of an ancestor and of its descendant, and 240 random moves in one tenant, never produce a cycle, a lost unit or a deadlock`() {
        val t = fx.tenant(); val ty = fx.type(t); val nodes = ArrayList<OrganizationUnitDto>()
        repeat(4) { r -> val root = fx.unit(t, ty, "R$r"); nodes += root; var p = root; repeat(10) { d -> p = fx.unit(t, ty, "N$r-$d", p.id); nodes += p } }
        val pool = OrgTestDb.pool(12, "move-stress"); val stack = OrgTestDb.Stack(pool); val rnd = java.util.Random(11)
        val harness = ConcurrencyHarness(pool, OrgConcurrencyClassify::classify)
        val work = (1..240).map { i ->
            val node = nodes[rnd.nextInt(nodes.size)]; val target = if (rnd.nextInt(10) == 0) null else nodes[rnd.nextInt(nodes.size)]
            "move-$i" to { val v = stack.units.find(t, node.id)!!.version; moveLikeService(stack, t, node.id, target?.id, v); OpResult("OK") }
        }
        val report = harness.run("240 random moves, one tenant, pool 12", work, threads = 12); pool.close()
        println(ConcurrencyReport.HEADER + report.markdown())
        assertThat(report.deadlocks).isZero(); assertThat(report.outcomes.keys).allMatch { it in setOf("OK", "CYCLE", "VERSION_CONFLICT", "DUPLICATE:code", "NOT_FOUND") }
        assertThat(report.outcomes["OK"]).isGreaterThan(20)
        val g = OrgGraph.verify(OrgTestDb.jdbc, t); assertThat(g.ok).describedAs(g.toString()).isTrue(); assertThat(g.totalUnits).isEqualTo(nodes.size.toLong())
    }

    @Test
    fun `edit against edit and move against edit on one version - exactly one wins and the version moves once`() {
        val t = fx.tenant(); val ty = fx.type(t); val u = fx.unit(t, ty, "U"); val p = fx.unit(t, ty, "P")
        val pool = Executors.newFixedThreadPool(8); val gate = CyclicBarrier(8)
        val edits = (0 until 8).map { i -> pool.submit(Callable { gate.await(30, TimeUnit.SECONDS); s.units.update(u.copy(name = "N$i"), 0) }) }.map { it.get(60, TimeUnit.SECONDS) }
        assertThat(edits.count { it != null }).isEqualTo(1); assertThat(s.units.find(t, u.id)!!.version).isEqualTo(1)
        val gate2 = CyclicBarrier(2); val cur = s.units.find(t, u.id)!!
        val results = listOf<() -> Any?>({ moveLikeService(s, t, u.id, p.id, cur.version) }, { s.units.update(cur.copy(name = "after"), cur.version) }).map { op -> pool.submit(Callable { gate2.await(30, TimeUnit.SECONDS); op() }) }.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertThat(results.count { it != null }).describedAs("move vs edit on the same version: one of them").isEqualTo(1); assertThat(s.units.find(t, u.id)!!.version).isEqualTo(cur.version + 1)
    }
}

/** classification of repository / lock exceptions for the concurrency harness reports */
object OrgConcurrencyClassify {
    fun classify(e: Throwable): String = when {
        e is com.systemwebstudio.organization.OrganizationCycle -> "CYCLE"
        e is com.systemwebstudio.organization.DuplicateOrganizationKey -> "DUPLICATE:" + e.key
        e is com.systemwebstudio.organization.ReferencedRowInactive -> "INACTIVE:" + e.kind
        e is com.systemwebstudio.organization.OrganizationUnitInUse -> "IN_USE"
        e is com.systemwebstudio.organization.MembershipHasPositions -> "HAS_POSITIONS"
        e is ApiException && e.code == OrgDb.BUSY_CODE -> ConcurrencyReport.LOCK_TIMEOUT
        sqlState(e) == "40P01" -> ConcurrencyReport.DEADLOCK
        else -> "ERROR:" + e.javaClass.simpleName + ":" + sqlState(e) + ":" + (e.message ?: "").take(60)
    }
    fun sqlState(e: Throwable): String? { var c: Throwable? = e; while (c != null) { if (c is java.sql.SQLException) return c.sqlState; c = c.cause }; return null }
}
