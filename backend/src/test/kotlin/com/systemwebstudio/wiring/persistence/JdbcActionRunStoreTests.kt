package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.RunBegin
import com.systemwebstudio.logic.action.RunKey
import com.systemwebstudio.logic.action.RunStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The durable `ActionRunStore` (V29): the decision table of `InMemoryActionRunStore`, plus persistence across a "restart" (a new store instance over the same
 * database), isolation, the sweeper hook, retention and real concurrency.
 */
class JdbcActionRunStoreTests : DataRuntimeJdbcTestBase() {
    private lateinit var tenant: UUID
    private lateinit var ws: UUID
    private lateinit var app: UUID
    private lateinit var store: JdbcActionRunStore
    private val user = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tenant = newTenant(); ws = workspaceOf(tenant); app = projectIn(ws, tenant)
        store = newStore()
    }

    private fun newStore() = JdbcActionRunStore(jdbc, json)
    private fun key(k: String = "k-" + UUID.randomUUID(), appId: UUID? = app, actionId: String = "create", userId: UUID = user, tenantId: UUID = tenant) =
        RunKey(tenantId, appId, actionId, userId, k)

    private fun ok(v: String = "x") = ActionResult.Ok(json.createObjectNode().put("id", v))
    private fun started(b: RunBegin) = (b as RunBegin.Started)

    @Test
    fun `the first call starts, a second one is in progress, completing records the result and later calls replay it`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        assertThat(s.attempt).isEqualTo(1)
        assertThat(store.begin(k, "fp", at)).isEqualTo(RunBegin.InProgress(s.runId))
        assertThat(store.complete(k, s.runId, ok(), at.plusSeconds(1))).isTrue()
        assertThat(store.begin(k, "fp", at.plusSeconds(2))).isEqualTo(RunBegin.Replay(ok()))
        val rec = store.find(k)!!
        assertThat(rec.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(rec.runId).isEqualTo(s.runId)
        assertThat(rec.startedAt).isEqualTo(at)
        assertThat(rec.finishedAt).isEqualTo(at.plusSeconds(1))
        assertThat(rec.result).isEqualTo(ok())
    }

    @Test
    fun `the same key with other inputs is KeyReused and changes nothing`() {
        val k = key()
        val s = started(store.begin(k, "fp-1", at))
        assertThat(store.begin(k, "fp-2", at)).isEqualTo(RunBegin.KeyReused)
        assertThat(store.find(k)!!.runId).isEqualTo(s.runId)
        store.complete(k, s.runId, ok(), at)
        assertThat(store.begin(k, "fp-2", at)).isEqualTo(RunBegin.KeyReused)
    }

    @Test
    fun `a retryable failure restarts with a new run id and attempt + 1, a stale owner cannot complete the new run`() {
        val k = key()
        val first = started(store.begin(k, "fp", at))
        assertThat(store.complete(k, first.runId, ActionResult.Failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, retryable = true, message = "down"), at)).isTrue()
        val second = started(store.begin(k, "fp", at.plusSeconds(5)))
        assertThat(second.attempt).isEqualTo(2)
        assertThat(second.runId).isNotEqualTo(first.runId)
        assertThat(store.complete(k, first.runId, ok("late"), at.plusSeconds(6))).isFalse()          // the old owner lost the run
        assertThat(store.find(k)!!.status).isEqualTo(RunStatus.RUNNING)
        assertThat(store.complete(k, second.runId, ok("done"), at.plusSeconds(7))).isTrue()
        assertThat(store.begin(k, "fp", at.plusSeconds(8))).isEqualTo(RunBegin.Replay(ok("done")))
    }

    @Test
    fun `a non-retryable failure is replayed as is`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        val failure = ActionResult.Failed(ActionErrorCodes.MUTATION_REJECTED, retryable = false, message = "no", details = mapOf("title" to "required"))
        store.complete(k, s.runId, failure, at)
        assertThat(store.begin(k, "fp", at)).isEqualTo(RunBegin.Replay(failure))
        assertThat(store.find(k)!!.status).isEqualTo(RunStatus.FAILED)
    }

    @Test
    fun `completing twice or with a foreign run id changes nothing`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        assertThat(store.complete(k, UUID.randomUUID().toString(), ok("foreign"), at)).isFalse()
        assertThat(store.complete(k, s.runId, ok("first"), at)).isTrue()
        assertThat(store.complete(k, s.runId, ok("second"), at)).isFalse()
        assertThat(store.find(k)!!.result).isEqualTo(ok("first"))
        assertThat(store.complete(key(), s.runId, ok(), at)).isFalse()                                // an unknown key
    }

    // ---- restart ---------------------------------------------------------------------------------------------------

    @Test
    fun `a finished run survives a restart and is replayed by a new store instance`() {
        val k = key()
        store.complete(k, started(store.begin(k, "fp", at)).runId, ok("kept"), at)
        val restarted = newStore()
        assertThat(restarted.begin(k, "fp", at.plusSeconds(60))).isEqualTo(RunBegin.Replay(ok("kept")))
        assertThat(restarted.find(k)!!.status).isEqualTo(RunStatus.SUCCEEDED)
    }

    @Test
    fun `a RUNNING run survives a restart as in progress, it is not started a second time`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        val restarted = newStore()
        assertThat(restarted.begin(k, "fp", at.plusSeconds(1))).isEqualTo(RunBegin.InProgress(s.runId))
        assertThat(restarted.find(k)!!.status).isEqualTo(RunStatus.RUNNING)
        assertThat(restarted.complete(k, s.runId, ok(), at.plusSeconds(2))).isTrue()                  // the original owner can still finish it
    }

    @Test
    fun `an ambiguous write stays unknown and never retryable across a restart, however often it is asked again`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        val unknown = ActionResult.Failed(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, retryable = false, message = "outcome unknown")
        store.complete(k, s.runId, unknown, at)
        repeat(3) {
            val after = newStore().begin(k, "fp", at.plusSeconds(600L * (it + 1)))
            assertThat(after).isEqualTo(RunBegin.Replay(unknown))                                    // never Started: the write is never re-run
        }
        assertThat((newStore().find(k)!!.result as ActionResult.Failed).retryable).isFalse()
    }

    // ---- recovery of abandoned runs -------------------------------------------------------------------------------

    @Test
    fun `an abandoned RUNNING run is turned into a retryable timeout, a fresh one is left alone`() {
        val abandoned = key(); val fresh = key()
        val old = started(store.begin(abandoned, "fp", at))
        val young = started(store.begin(fresh, "fp", at.plus(Duration.ofHours(1))))
        assertThat(store.sweepStale(at.plus(Duration.ofMinutes(30)), at.plus(Duration.ofHours(1)))).isGreaterThanOrEqualTo(1)
        val swept = store.find(abandoned)!!
        assertThat(swept.status).isEqualTo(RunStatus.FAILED)
        val failure = swept.result as ActionResult.Failed
        assertThat(failure.code).isEqualTo(ActionErrorCodes.TIMEOUT)
        assertThat(failure.retryable).isTrue()
        assertThat(store.find(fresh)!!.status).isEqualTo(RunStatus.RUNNING)
        assertThat(store.complete(abandoned, old.runId, ok("zombie"), at.plus(Duration.ofHours(2)))).isFalse()   // the crashed worker cannot overwrite the sweep
        val retry = started(store.begin(abandoned, "fp", at.plus(Duration.ofHours(2))))              // a retry is a new attempt of the SAME key (the data layer dedupes the write)
        assertThat(retry.attempt).isEqualTo(2)
        assertThat(young.attempt).isEqualTo(1)
    }

    @Test
    fun `sweeping twice does not touch a run that is already finished`() {
        val k = key()
        started(store.begin(k, "fp", at))
        store.sweepStale(at.plusSeconds(1), at.plusSeconds(2))
        val first = store.find(k)!!
        store.sweepStale(at.plusSeconds(10), at.plusSeconds(20))
        assertThat(store.find(k)).isEqualTo(first)
    }

    // ---- retention -------------------------------------------------------------------------------------------------

    @Test
    fun `purge deletes finished records older than the horizon and never a RUNNING one`() {
        val oldDone = key(); val newDone = key(); val running = key()
        store.complete(oldDone, started(store.begin(oldDone, "fp", at)).runId, ok(), at)
        store.complete(newDone, started(store.begin(newDone, "fp", at.plus(Duration.ofDays(10)))).runId, ok(), at.plus(Duration.ofDays(10)))
        started(store.begin(running, "fp", at.minus(Duration.ofDays(400))))
        assertThat(store.purgeFinished(at.plus(Duration.ofDays(5)), 1000)).isGreaterThanOrEqualTo(1)
        assertThat(store.find(oldDone)).isNull()
        assertThat(store.find(newDone)).isNotNull()
        assertThat(store.find(running)!!.status).isEqualTo(RunStatus.RUNNING)
        assertThat(store.purgeFinished(at.plus(Duration.ofDays(5)), 0)).isZero()
    }

    // ---- isolation -------------------------------------------------------------------------------------------------

    @Test
    fun `tenants, users, actions and apps are separate key scopes`() {
        val k = "shared-key"
        val otherTenant = newTenant(); val otherWs = workspaceOf(otherTenant); val otherApp = projectIn(otherWs, otherTenant)
        val secondApp = projectIn(ws, tenant)
        val scopes = listOf(
            key(k), key(k, userId = UUID.randomUUID()), key(k, actionId = "other"), key(k, appId = secondApp),
            key(k, appId = otherApp, tenantId = otherTenant), key(k, appId = null)
        )
        val ids = scopes.map { started(store.begin(it, "fp", at)).runId }
        assertThat(ids.toSet()).hasSize(scopes.size)
        for ((i, s) in scopes.withIndex()) assertThat(store.find(s)!!.runId).isEqualTo(ids[i])
    }

    @Test
    fun `an application of another tenant is refused and leaves no row`() {
        val otherTenant = newTenant(); val otherApp = projectIn(workspaceOf(otherTenant), otherTenant)
        assertThatThrownBy { store.begin(key(appId = otherApp), "fp", at) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { store.begin(key(appId = UUID.randomUUID()), "fp", at) }.isInstanceOf(RuntimeException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ?", Long::class.java, tenant)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ?", Long::class.java, otherTenant)).isZero()
        assertThat(store.find(key(appId = otherApp))).isNull()
    }

    @Test
    fun `another tenant cannot see or finish a run even with the same key and run id`() {
        val k = key("same")
        val s = started(store.begin(k, "fp", at))
        val otherTenant = newTenant(); val otherApp = projectIn(workspaceOf(otherTenant), otherTenant)
        val foreign = RunKey(otherTenant, otherApp, k.actionId, user, k.idempotencyKey)
        assertThat(store.find(foreign)).isNull()
        assertThat(store.complete(foreign, s.runId, ok("steal"), at)).isFalse()
        assertThat(store.find(k)!!.status).isEqualTo(RunStatus.RUNNING)
    }

    @Test
    fun `the workspace of a run is derived from its project and a run without an app has none`() {
        val withApp = key(); val noApp = key(appId = null)
        store.begin(withApp, "fp", at); store.begin(noApp, "fp", at)
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM action_runs WHERE tenant_id = ? AND idempotency_key = ? AND app_id = ?", UUID::class.java, tenant, withApp.idempotencyKey, app)).isEqualTo(ws)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ? AND idempotency_key = ? AND app_id IS NULL AND workspace_id IS NULL", Long::class.java, tenant, noApp.idempotencyKey)).isEqualTo(1L)
        assertThat(store.begin(noApp, "fp", at)).isInstanceOf(RunBegin.InProgress::class.java)       // a NULL app is ONE scope
        assertThat(store.find(noApp)!!.key.appId).isNull()
    }

    @Test
    fun `the stored result is the replayable outcome and carries no extra data`() {
        val k = key()
        store.complete(k, started(store.begin(k, "fp", at)).runId, ActionResult.Failed("X", retryable = false, message = "m"), at)
        val text = jdbc.queryForObject("SELECT result::text FROM action_runs WHERE tenant_id = ? AND idempotency_key = ?", String::class.java, tenant, k.idempotencyKey)!!
        val node = json.readTree(text)
        assertThat(node.get("t").asString()).isEqualTo("FAILED")
        assertThat(node.get("code").asString()).isEqualTo("X")
        assertThat(node.get("retryable").asBoolean()).isFalse()
        assertThat(jdbc.queryForObject("SELECT worker_id IS NOT NULL FROM action_runs WHERE tenant_id = ? AND idempotency_key = ?", Boolean::class.java, tenant, k.idempotencyKey)).isTrue()
    }

    // ---- concurrency -----------------------------------------------------------------------------------------------

    @Test
    fun `concurrent begins of one key produce exactly one owner`() {
        val k = key()
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..n).map { pool.submit<RunBegin> { gate.await(); newStore().begin(k, "fp", at) } }
            gate.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(outcomes.filterIsInstance<RunBegin.Started>()).hasSize(1)
            assertThat(outcomes.filterIsInstance<RunBegin.InProgress>()).hasSize(n - 1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ? AND idempotency_key = ?", Long::class.java, tenant, k.idempotencyKey)).isEqualTo(1L)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent completes of one run let exactly one win`() {
        val k = key()
        val s = started(store.begin(k, "fp", at))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..4).map { i -> pool.submit<Boolean> { gate.await(); newStore().complete(k, s.runId, ok("w$i"), at.plusSeconds(1)) } }
            gate.countDown()
            assertThat(futures.map { it.get(30, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }
}
