package com.systemwebstudio.data.org

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.organization.DuplicateOrganizationKey
import com.systemwebstudio.organization.EmployeePositionDto
import com.systemwebstudio.organization.MembershipHasPositions
import com.systemwebstudio.organization.OrgUnitTypeRules
import com.systemwebstudio.organization.OrganizationUnitDto
import com.systemwebstudio.organization.OrganizationUnitInUse
import com.systemwebstudio.organization.ReferencedRowInactive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The races C1 section 10 leaves to C3's own PostgreSQL tests, WITHOUT any tenant-wide lock: archive against a concurrent child / membership insert, ending a membership against a position
 * insert, the primary flags (membership and position), the sibling code under concurrent inserts, duplicate memberships; transaction participation; the bounded row-lock wait; the maxDepth re-check.
 */
class OrgRaceTests {
    private val fx = OrgFx(); private val s = OrgTestDb.stack
    private val now get() = Instant.now()

    private fun <T> burst(n: Int, op: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n); val gate = CyclicBarrier(n)
        try { return (0 until n).map { i -> pool.submit(Callable { gate.await(30, TimeUnit.SECONDS); runCatching { op(i) } }) }.map { it.get(120, TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
    }

    @Test
    fun `archive against a concurrent child insert - an active child can never appear under an archived parent`() {
        val t = fx.tenant(); val ty = fx.type(t); var archived = 0; var inserted = 0
        repeat(60) { round ->
            val p = fx.unit(t, ty, "P$round")
            val r = burst(2) { i -> if (i == 0) s.units.setActive(t, p.id, false, 0) else fx.unit(t, ty, "K$round", p.id) }
            val archiveOk = r[0].isSuccess; val childOk = r[1].isSuccess
            assertThat(archiveOk && childOk).describedAs("round $round: archived parent AND an active child").isFalse()
            if (!archiveOk) assertThat(r[0].exceptionOrNull()).isInstanceOf(OrganizationUnitInUse::class.java)
            if (!childOk) assertThat(r[1].exceptionOrNull()).isInstanceOf(ReferencedRowInactive::class.java)
            if (archiveOk) archived++ else inserted++
            val live = s.units.find(t, p.id)!!
            assertThat(live.active == (s.units.activeChildCount(t, p.id) >= 0 && !(live.archivedAt != null))).isTrue()
            if (live.archivedAt != null) assertThat(s.units.activeChildCount(t, p.id)).isZero()
        }
        println("EVIDENCE race archive vs child insert: 60 rounds, archive won $archived, insert won $inserted, never both")
    }

    @Test
    fun `archive against a concurrent membership insert, and ending a membership against a concurrent position insert`() {
        val t = fx.tenant(); val ty = fx.type(t); val pos = fx.position(t); var a1 = 0; var b1 = 0
        repeat(40) { round ->
            val unit = fx.unit(t, ty, "U$round"); val user = fx.member(t)
            val r = burst(2) { i -> if (i == 0) s.units.setActive(t, unit.id, false, 0) else fx.membership(t, user, unit.id) }
            assertThat(r[0].isSuccess && r[1].isSuccess).describedAs("round $round: archived unit AND an active membership").isFalse()
            if (r[0].isSuccess) a1++
            val u = s.units.find(t, unit.id)!!; if (u.archivedAt != null) assertThat(s.memberships.activeCountByUnit(t, unit.id)).isZero()
        }
        repeat(40) { round ->
            val unit = fx.unit(t, ty, "V$round"); val user = fx.member(t); val m = fx.membership(t, user, unit.id)
            val r = burst(2) { i ->
                if (i == 0) s.memberships.end(t, user, m.id, 0)
                else s.employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), t, user, m.id, unit.id, pos.id, null, false, true, 0, now, now))
            }
            assertThat(r[0].isSuccess && r[0].getOrNull() != null && r[1].isSuccess).describedAs("round $round: ended membership AND an active position in it").isFalse()
            val ended = s.memberships.find(t, user, m.id)!!.active.not()
            if (ended) assertThat(s.employeePositions.list(t, user, false)).isEmpty() else b1++
            r.forEach { it.exceptionOrNull()?.let { e -> assertThat(e).isInstanceOfAny(MembershipHasPositions::class.java, ReferencedRowInactive::class.java) } }
        }
        println("EVIDENCE race: archive vs membership insert (archive won $a1/40); end membership vs position insert (membership stayed active $b1/40); never contradictory")
    }

    @Test
    fun `racing setPrimary leaves exactly ONE primary - memberships and position assignments - with no deadlock`() {
        val t = fx.tenant(); val ty = fx.type(t); val user = fx.member(t); val pos = fx.position(t)
        val ms = (1..6).map { fx.membership(t, user, fx.unit(t, ty, "PU$it").id) }
        val asg = ms.map { m -> fx.hold(m, fx.position(t)) }
        val r = burst(6) { i ->
            var done = false; var tries = 0
            while (!done && tries++ < 8) { val cur = s.memberships.find(t, user, ms[i].id)!!; done = s.memberships.setPrimary(t, user, ms[i].id, cur.version) != null }
            done
        }
        assertThat(r.mapNotNull { it.exceptionOrNull() }).describedAs("no deadlock, no unique violation leaks").isEmpty()
        assertThat(s.memberships.list(t, user, false).count { it.primary }).isEqualTo(1)
        val r2 = burst(6) { i ->
            var done = false; var tries = 0
            while (!done && tries++ < 8) { val cur = s.employeePositions.find(t, user, asg[i].id)!!; done = s.employeePositions.setPrimary(t, user, asg[i].id, cur.version) != null }
            done
        }
        assertThat(r2.mapNotNull { it.exceptionOrNull() }).isEmpty(); assertThat(s.employeePositions.list(t, user, false).count { it.primary }).isEqualTo(1)
        assertThat(pos).isNotNull()
    }

    @Test
    fun `concurrent inserts of one sibling code, one membership and one assignment - exactly one wins, the others get the typed duplicate`() {
        val t = fx.tenant(); val ty = fx.type(t); val parent = fx.unit(t, ty, "P")
        val codes = burst(10) { fx.unit(t, ty, "SAME", parent.id) }
        assertThat(codes.count { it.isSuccess }).isEqualTo(1); assertThat(codes.mapNotNull { it.exceptionOrNull() }).allMatch { it is DuplicateOrganizationKey && it.key == "code" }
        val roots = burst(10) { fx.unit(t, ty, "ROOTSAME") }
        assertThat(roots.count { it.isSuccess }).describedAs("roots are siblings: one wins").isEqualTo(1)
        val user = fx.member(t); val unit = fx.unit(t, ty, "MU"); val members = burst(8) { fx.membership(t, user, unit.id) }
        assertThat(members.count { it.isSuccess }).isEqualTo(1); assertThat(members.mapNotNull { it.exceptionOrNull() }).allMatch { it is DuplicateOrganizationKey && it.key == "membership" }
        val m = s.memberships.list(t, user, false).single(); val pos = fx.position(t)
        val assignments = burst(8) { s.employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), t, user, m.id, unit.id, pos.id, null, false, true, 0, now, now)) }
        assertThat(assignments.count { it.isSuccess }).isEqualTo(1); assertThat(assignments.mapNotNull { it.exceptionOrNull() }).allMatch { it is DuplicateOrganizationKey && it.key == "assignment" }
        val types = burst(8) { fx.type(t, "dup-type") }; assertThat(types.count { it.isSuccess }).isEqualTo(1)
    }

    @Test
    fun `transaction participation - one transaction, no REQUIRES_NEW, a failure after the write rolls the write back, uncommitted rows are invisible to other connections`() {
        val t = fx.tenant(); val ty = fx.type(t); val seen = mutableListOf<Long>()
        val outsiderPool = OrgTestDb.pool(1, "outsider"); val outsider = org.springframework.jdbc.core.JdbcTemplate(outsiderPool)                    // a DIFFERENT DataSource = a different connection
        val created = s.inTx {
            val u = fx.unit(t, ty, "INTX"); val m = fx.member(t)
            seen += outsider.queryForObject("SELECT count(*) FROM organization_units WHERE id = ?", Long::class.java, u.id)!!                     // ANOTHER connection: nothing is committed yet
            seen += s.jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE id = ?", Long::class.java, u.id)!!                        // the same transaction sees it
            u.also { assertThat(m).isNotNull() }
        }
        assertThat(seen).containsExactly(0L, 1L)
        assertThat(outsider.queryForObject("SELECT count(*) FROM organization_units WHERE id = ?", Long::class.java, created.id)).isEqualTo(1L)             // committed together at the end
        outsiderPool.close()
        // a failure AFTER the repository write (an audit failure in C1's flow) rolls the write back
        var written: UUID? = null
        assertThatThrownBy { s.inTx { written = fx.unit(t, ty, "ROLLED").id; s.jdbc.update("INSERT INTO audit_events (id, action, resource_type) VALUES (?, 'X', 'ORG_UNIT')", UUID.randomUUID()); throw IllegalStateException("the audit write failed") } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("audit")                                                       // an audit failure after the write: the unit AND the audit row go together
        assertThat(OrgTestDb.jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE id = ?", Long::class.java, written!!)).isZero()
        assertThat(OrgTestDb.jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'X' AND resource_type = 'ORG_UNIT' AND created_at > now() - interval '1 minute'", Long::class.java)).isZero()
        // a typed refusal inside an ambient transaction is the caller's to roll back (C1 maps it to 409 and the transaction ends)
        assertThatThrownBy { s.inTx { fx.unit(t, ty, "INTX") } }.isInstanceOf(DuplicateOrganizationKey::class.java)
    }

    @Test
    fun `a writer waits a bounded time for a row lock and then fails with the retryable 503 instead of holding the connection`() {
        val t = fx.tenant(); val ty = fx.type(t); val u = fx.unit(t, ty, "LOCKED")
        val holding = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1); val pool = Executors.newFixedThreadPool(1)
        val h = pool.submit(Callable { OrgTestDb.stack.inTx { OrgTestDb.jdbc.queryForObject("SELECT id FROM organization_units WHERE id = ? FOR UPDATE", UUID::class.java, u.id); holding.countDown(); release.await(30, TimeUnit.SECONDS) } })
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue()
        val tight = OrgTestDb.Stack(OrgTestDb.dataSource, lockTimeoutMs = 300); val t0 = System.nanoTime()
        assertThatThrownBy { tight.units.setActive(t, u.id, false, 0) }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(OrgDb.BUSY_CODE)
        assertThat((System.nanoTime() - t0) / 1e6).isBetween(250.0, 3000.0)
        release.countDown(); h.get(20, TimeUnit.SECONDS); pool.shutdownNow()
        assertThat(tight.units.setActive(t, u.id, false, 0)!!.archivedAt).isNotNull()
    }

    @Test
    fun `maxDepth is re-checked inside create and restore (the contract's documented race window), the refusal is the same 409 ORG_TYPE_RULE_VIOLATION`() {
        val t = fx.tenant(); val shallow = fx.type(t, "shallow", OrgUnitTypeRules(maxDepth = 3)); val free = fx.type(t, "free")
        val l1 = fx.unit(t, free, "A"); val l2 = fx.unit(t, free, "B", l1.id); val l3 = fx.unit(t, shallow, "C", l2.id)                    // level 3: allowed
        assertThatThrownBy { fx.unit(t, shallow, "D", l3.id) }.isInstanceOf(ApiException::class.java).satisfies({ e -> assertThat((e as ApiException).code).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(e.details["reason"]).isEqualTo("MAX_DEPTH") })
        assertThat(s.units.listChildren(t, l3.id)).isEmpty()                                                                                // refused: rolled back, nothing written
        val deep = fx.unit(t, free, "E", l3.id)                                                                                             // a unit of a type WITHOUT the rule may sit at level 4
        s.units.setActive(t, deep.id, false, 0)
        OrgTestDb.jdbc.update("UPDATE organization_units SET type_id = ? WHERE id = ?", shallow.id, deep.id)                                 // (the type of the archived unit now carries the rule: simulates a rule added later)
        assertThatThrownBy { s.units.setActive(t, deep.id, true, 1) }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo("ORG_TYPE_RULE_VIOLATION")
        assertThat(s.units.find(t, deep.id)!!.archivedAt).isNotNull()
        val ok: OrganizationUnitDto = fx.unit(t, shallow, "F", l2.id); assertThat(ok.parentId).isEqualTo(l2.id)                             // level 3: fine
    }
}
