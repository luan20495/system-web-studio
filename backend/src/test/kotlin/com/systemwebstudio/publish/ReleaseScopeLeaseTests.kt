package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The release scope lease against a real PostgreSQL (no mocks): who may own (tenant, app, PRODUCTION), the fencing token that refuses an earlier holder,
 * the order of intents, the heartbeat and the maximum lifetime. Expiry is simulated by moving `lease_until` in the database, so nothing here sleeps for 90 s.
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.render.url=http://127.0.0.1:9"])
class ReleaseScopeLeaseTests : IntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var txManager: PlatformTransactionManager

    private val held = mutableListOf<ScopeLease>()

    @AfterEach fun releaseEverything() { held.forEach { it.release() }; held.clear() }

    private class Fx(val sc: Scenario, val scope: ReleaseScope)
    private fun fx(): Fx {
        val sc = scenario(); sites.ensureSlug(sc.projectId, "Lease")
        return Fx(sc, ReleaseScope(jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, sc.projectId)!!, sc.projectId))
    }

    private fun deployment(f: Fx): Pair<UUID, Long> {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, f.sc.projectId)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PUBLIC','DEPLOYING','static')", id, f.sc.ws, f.sc.projectId, version, f.sc.user.id)
        return id to jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, id)!!
    }

    private fun publish(f: Fx, d: Pair<UUID, Long>, g: ReleaseScopeGuard = guard) = g.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, d.first, d.first, d.second))
    private fun rollback(f: Fx, op: UUID = UUID.randomUUID(), g: ReleaseScopeGuard = guard) = g.acquire(ScopeRequest(f.scope, ReleaseOperation.ROLLBACK, op))
    /** asserts the scope was taken, and gives it straight back */
    private fun ScopeAcquisition.takenAndReleased() { assertThat(this).isInstanceOf(ScopeAcquisition.Acquired::class.java); (this as ScopeAcquisition.Acquired).lease.release() }
    private fun ScopeAcquisition.lease(): ScopeLease = (this as ScopeAcquisition.Acquired).lease.also { held += it }
    private fun expire(f: Fx) { jdbc.update("UPDATE sites SET lease_until = now() - interval '1 second' WHERE project_id = ?", f.sc.projectId) }
    private fun row(f: Fx) = jdbc.queryForMap("SELECT current_deployment_id, pointer_version, active_seq, active_operation_id, lease_operation_id, fence_counter FROM sites WHERE project_id = ?", f.sc.projectId)
    private fun pointer(f: Fx) = row(f)["current_deployment_id"] as UUID?
    private fun shortLived(ttlMs: Long, maxMs: Long, beatMs: Long) = JdbcScopeGuard(jdbc, txManager, Duration.ofMillis(ttlMs), Duration.ofMillis(maxMs), Duration.ofMillis(beatMs), "test-worker")

    @Test
    fun `one operation owns the scope at a time - the second is told who holds it, and gets it after the release`() {
        val f = fx(); val a = deployment(f); val b = deployment(f)
        val la = publish(f, a).lease()
        val busy = publish(f, b) as ScopeAcquisition.Busy
        assertThat(busy.holder!!.kind).isEqualTo(ReleaseOperation.PUBLISH); assertThat(busy.holder!!.deploymentId).isEqualTo(a.first)
        assertThat(rollback(f)).isInstanceOf(ScopeAcquisition.Busy::class.java)
        la.release()
        assertThat(row(f)["lease_operation_id"]).isNull()
        publish(f, b).takenAndReleased()
    }

    @Test
    fun `an operation of another tenant or another app cannot take the scope`() {
        val f = fx(); val other = fx(); val d = deployment(f)
        // the project's tenant is its own: a request that names another tenant for this app is refused and writes nothing
        val foreign = ReleaseScope(UUID.randomUUID(), f.sc.projectId)
        assertThat(guard.acquire(ScopeRequest(foreign, ReleaseOperation.PUBLISH, d.first, d.first, d.second))).isNotInstanceOf(ScopeAcquisition.Acquired::class.java)
        assertThat(guard.acquire(ScopeRequest(foreign, ReleaseOperation.ROLLBACK, UUID.randomUUID()))).isNotInstanceOf(ScopeAcquisition.Acquired::class.java)
        assertThat(row(f)["lease_operation_id"]).isNull(); assertThat(row(f)["fence_counter"]).isEqualTo(0L)
        val lo = rollback(other).lease()                                             // different apps never block each other
        rollback(f).takenAndReleased()
        lo.release()
    }

    @Test
    fun `two workers racing for one scope - exactly one wins, every time`() {
        val f = fx(); val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(15) {
                val go = CountDownLatch(1)
                val results = (1..2).map { pool.submit<ScopeAcquisition> { go.await(); rollback(f) } }
                go.countDown()
                val got = results.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(got.count { it is ScopeAcquisition.Acquired }).describedAs("round $it").isEqualTo(1)
                assertThat(got.count { it is ScopeAcquisition.Busy }).isEqualTo(1)
                got.filterIsInstance<ScopeAcquisition.Acquired>().single().lease.release()
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `taking the scope inside a caller's open transaction neither blocks others nor is blocked - every statement is its own transaction`() {
        val f = fx()
        val outer = TransactionTemplate(txManager)
        val pool = Executors.newSingleThreadExecutor()
        try {
            outer.execute {
                val l = rollback(f).lease()                                           // the caller's transaction is still open here
                val answer = pool.submit<ScopeAcquisition> { rollback(f) }.get(5, TimeUnit.SECONDS)    // would hang on a row lock if the acquire were part of the caller's transaction
                assertThat(answer).isInstanceOf(ScopeAcquisition.Busy::class.java)
                l.release()
            }
        } finally { pool.shutdownNow() }
    }

    // ------------------------------------------------------------------ fencing

    @Test
    fun `lease expiry - another operation takes over, the dead worker's pointer write is refused, the new owner's is not`() {
        val f = fx(); val a = deployment(f); val b = deployment(f)
        val la = publish(f, a).lease()
        expire(f)                                                                     // A stopped heartbeating
        val lb = publish(f, b).lease()
        assertThat(lb.fenceToken).isGreaterThan(la.fenceToken)
        assertThat(la.fence.commit(a.first)).isFalse(); assertThat(la.fence.fencedOut).isTrue(); assertThat(la.lost).isTrue()
        assertThat(pointer(f)).isNull()                                               // nothing of A was written
        assertThat(lb.fence.commit(b.first)).isTrue()
        assertThat(pointer(f)).isEqualTo(b.first)
    }

    @Test
    fun `publish A is slow, its lease expires, B takes over and publishes, A finishes late - A is fenced out and B stays active`() {
        val f = fx(); val a = deployment(f); val b = deployment(f)
        val la = publish(f, a).lease()                                                // A acquires and is slow
        expire(f)
        val lb = publish(f, b).lease(); assertThat(lb.fence.commit(b.first)).isTrue(); lb.release()
        val before = row(f)
        assertThat(la.fence.commit(a.first)).isFalse()                                // A completes late: CAS refused
        assertThat(la.fence.fencedOut).isTrue()
        assertThat(row(f)).isEqualTo(before)                                          // not a single column changed
        assertThat(pointer(f)).isEqualTo(b.first)
        la.release()                                                                  // the late release is harmless too
        assertThat(pointer(f)).isEqualTo(b.first)
    }

    @Test
    fun `the old holder releasing late does not release the new holder's scope`() {
        val f = fx(); val a = deployment(f); val b = deployment(f)
        val la = publish(f, a).lease(); expire(f)
        val lb = publish(f, b).lease()
        la.release()
        assertThat(row(f)["lease_operation_id"]).isEqualTo(b.first)
        assertThat(rollback(f)).isInstanceOf(ScopeAcquisition.Busy::class.java)
        lb.release()
    }

    @Test
    fun `higher activation number with a stale fencing token is refused - the number alone grants nothing`() {
        val f = fx(); val x = deployment(f); val y = deployment(f)
        val lx = publish(f, x).lease()                                                // X: token 1
        expire(f)
        val ly = rollback(f, y.first).lease()                                         // Y took over: token 2
        assertThat(lx.fence.commit(x.first)).isFalse()
        assertThat(ly.fenceToken).isGreaterThan(lx.fenceToken)
        assertThat(pointer(f)).isNull()
    }

    @Test
    fun `the same operation takes the scope again after a crash - it gets a NEW fencing token, resumes, and the earlier thread of it is fenced out`() {
        val f = fx(); val a = deployment(f)
        val first = publish(f, a).lease()
        expire(f)                                                                     // the worker died; the message is redelivered
        val second = publish(f, a).lease()
        assertThat(second.fenceToken).isGreaterThan(first.fenceToken)
        assertThat(second.resumed).isTrue(); assertThat(second.seq).isEqualTo(a.second)             // same operation, same activation number
        assertThat(first.fence.commit(a.first)).isFalse()                                          // the earlier holder is fenced
        assertThat(second.fence.commit(a.first)).isTrue()                                          // the resumed operation succeeds
        assertThat(pointer(f)).isEqualTo(a.first)
    }

    // ------------------------------------------------------------------ order of intents

    @Test
    fun `a lower activation number is stale - it never activates and the scope is given straight back`() {
        val f = fx(); val older = deployment(f); val newer = deployment(f)
        val ln = publish(f, newer).lease(); assertThat(ln.fence.commit(newer.first)).isTrue(); ln.release()
        val stale = publish(f, older) as ScopeAcquisition.Stale
        assertThat(stale.activeDeploymentId).isEqualTo(newer.first); assertThat(stale.activeSeq).isEqualTo(newer.second)
        assertThat(row(f)["lease_operation_id"]).isNull()                              // not held by the loser
        assertThat(pointer(f)).isEqualTo(newer.first)
    }

    @Test
    fun `a higher activation number goes on, and a rollback after it is newer still`() {
        val f = fx(); val first = deployment(f); val second = deployment(f)
        publish(f, first).lease().also { assertThat(it.fence.commit(first.first)).isTrue(); it.release() }
        val l = publish(f, second).lease(); assertThat(l.seq).isGreaterThan(first.second); l.release()
        val r = rollback(f).lease(); assertThat(r.seq).isGreaterThan(second.second); r.release()
    }

    @Test
    fun `the same activation number resumes only for the SAME operation`() {
        val f = fx(); val a = deployment(f)
        publish(f, a).lease().also { assertThat(it.fence.commit(a.first)).isTrue(); it.release() }       // A moved the pointer, then the worker died before finishing
        val again = publish(f, a).lease()                                                                  // redelivery of the same deployment: equality + same operation
        assertThat(again.resumed).isTrue(); again.release()
        // a different operation that claims the same number (only possible by a bug or a forged request) must not overwrite the pointer
        val intruder = UUID.randomUUID()
        val conflict = guard.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, intruder, a.first, a.second))
        assertThat(conflict).isInstanceOf(ScopeAcquisition.Stale::class.java)
        assertThat((conflict as ScopeAcquisition.Stale).reason).contains("same activation number")
        assertThat(pointer(f)).isEqualTo(a.first); assertThat(row(f)["lease_operation_id"]).isNull()
    }

    @Test
    fun `the pointer commit is a compare-and-set - the version moves by one, an outdated version is refused`() {
        val f = fx(); val a = deployment(f); val b = deployment(f)
        val l = publish(f, a).lease()
        val v0 = row(f)["pointer_version"] as Long
        assertThat(l.fence.commit(a.first)).isTrue(); assertThat(row(f)["pointer_version"]).isEqualTo(v0 + 1)
        assertThat(l.pointerVersion).isEqualTo(v0 + 1)
        assertThat(l.fence.commit(null)).isTrue(); assertThat(pointer(f)).isNull(); assertThat(row(f)["pointer_version"]).isEqualTo(v0 + 2)    // the same owner's next commit
        jdbc.update("UPDATE sites SET pointer_version = pointer_version + 1 WHERE project_id = ?", f.sc.projectId)                                 // somebody else moved the pointer
        assertThat(l.fence.commit(b.first)).isFalse(); assertThat(l.fence.fencedOut).isTrue()
        assertThat(pointer(f)).isNull()
    }

    @Test
    fun `a commit that carries a step writes both or neither`() {
        val f = fx(); val a = deployment(f)
        val l = publish(f, a).lease()
        l.fence.withNextCommit { jdbc.update("UPDATE deployments SET error = 'with the commit' WHERE id = ?", a.first) }
        assertThat(l.fence.commit(a.first)).isTrue()
        assertThat(jdbc.queryForObject("SELECT error FROM deployments WHERE id = ?", String::class.java, a.first)).isEqualTo("with the commit")
        expire(f); val taker = rollback(f).lease()                                                    // someone else took over: l is fenced
        l.fence.withNextCommit { jdbc.update("UPDATE deployments SET error = 'must not stay' WHERE id = ?", a.first) }
        assertThat(l.fence.commit(null)).isFalse()
        assertThat(jdbc.queryForObject("SELECT error FROM deployments WHERE id = ?", String::class.java, a.first)).isEqualTo("with the commit")
        assertThat(pointer(f)).isEqualTo(a.first)
        taker.release()
    }

    // ------------------------------------------------------------------ heartbeat and maximum lifetime

    @Test
    fun `the heartbeat keeps a working operation's lease alive beyond its TTL`() {
        val f = fx(); val g = shortLived(ttlMs = 2_000, maxMs = 60_000, beatMs = 300); val a = deployment(f)
        val l = g.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, a.first, a.first, a.second)).lease()
        Thread.sleep(4_500)                                                                           // more than twice the TTL
        assertThat(rollback(f, g = g)).isInstanceOf(ScopeAcquisition.Busy::class.java)               // still owned
        assertThat(l.lost).isFalse(); assertThat(l.fence.commit(a.first)).isTrue()
    }

    @Test
    fun `without a heartbeat the lease expires after its TTL and can be taken`() {
        val f = fx(); val a = deployment(f)
        val l = guard.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, a.first, a.first, a.second)).lease()
        jdbc.update("UPDATE sites SET lease_until = now() + interval '1 second' WHERE project_id = ?", f.sc.projectId)
        assertThat(rollback(f)).isInstanceOf(ScopeAcquisition.Busy::class.java)
        Thread.sleep(1_300)
        rollback(f).takenAndReleased()
        assertThat(l.fence.commit(a.first)).isFalse()
    }

    @Test
    fun `the maximum lifetime is respected - a heartbeat cannot keep a runaway operation alive for ever`() {
        val f = fx(); val g = shortLived(ttlMs = 1_500, maxMs = 3_000, beatMs = 200); val a = deployment(f)
        val l = g.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, a.first, a.first, a.second)).lease()
        Thread.sleep(5_500)                                                                           // past the maximum: the last extension was refused, then the TTL ran out
        assertThat(l.lost).isTrue()
        assertThat(l.fence.commit(a.first)).isFalse()
        rollback(f, g = g).takenAndReleased()
    }
}
