package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.CreateOutcome
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.StepState
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.ValueRef
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowRun
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowStep
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The durable `WorkflowRunStore` (V29): same contract as `InMemoryWorkflowRunStore` - idempotent create, compare-and-set with the steps in the same transaction,
 * the fair sweeper claim, failure bookkeeping, retention - plus persistence across a "restart" (a new store instance over the same database) and isolation.
 */
class JdbcWorkflowRunStoreTests : DataRuntimeJdbcTestBase() {
    private lateinit var tenant: UUID
    private lateinit var ws: UUID
    private lateinit var app: UUID
    private lateinit var store: JdbcWorkflowRunStore
    private val user = UUID.randomUUID()
    private val every = Duration.ofSeconds(5)

    @BeforeEach
    fun setUp() {
        tenant = newTenant(); ws = workspaceOf(tenant); app = projectIn(ws, tenant)
        store = newStore()
    }

    private fun newStore() = JdbcWorkflowRunStore(jdbc, json)
    private fun text(s: String): JsonNode = json.createObjectNode().put("v", s).get("v")
    private fun obj(k: String, v: String): JsonNode = json.createObjectNode().put(k, v)

    private fun definition(appId: UUID = app, tenantId: UUID = tenant) = WorkflowDefinition(
        "wf", tenantId, appId, steps = listOf(
            WorkflowStep("a", StepKind.ACTION, actionRef = "w1", inputs = mapOf("title" to ValueRef.Literal(text("T"))), next = "b", compensationActionRef = "c1"),
            WorkflowStep("b", StepKind.ACTION, actionRef = "w2", compensationActionRef = "c2"),
            WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofMinutes(5))
        )
    )

    private fun run(
        key: String = "k-" + UUID.randomUUID(), appId: UUID = app, tenantId: UUID = tenant, workspaceId: UUID? = ws, mode: ExecutionMode = ExecutionMode.LIVE,
        userId: UUID = user, fingerprint: String = "fp", createdAt: Instant = at, status: WorkflowRunStatus = WorkflowRunStatus.PENDING
    ) = WorkflowRun(
        runId = UUID.randomUUID(), tenantId = tenantId, appId = appId, workflowId = "wf", definition = definition(appId, tenantId), mode = mode, status = status,
        createdBy = ActionActor(userId), workspaceId = workspaceId, idempotencyKey = key, fingerprint = fingerprint, input = obj("who", "me"), currentStepId = "a",
        steps = mapOf("a" to StepState("a", StepStatus.PENDING)), createdAt = createdAt, updatedAt = createdAt
    )

    private fun created(r: WorkflowRun = run()): WorkflowRun { assertThat(store.create(r)).isEqualTo(CreateOutcome.Created(r)); return r }
    private fun reload(r: WorkflowRun, s: JdbcWorkflowRunStore = store) = s.get(r.tenantId, r.runId)!!

    /** one successful compare-and-set from the stored state; returns the stored result */
    private fun move(r: WorkflowRun, s: JdbcWorkflowRunStore = store, f: (WorkflowRun) -> WorkflowRun): WorkflowRun {
        val cur = reload(r, s)
        assertThat(s.compareAndSet(cur, f(cur))).isTrue()
        return reload(r, s)
    }

    private fun finish(r: WorkflowRun, status: WorkflowRunStatus, at: Instant, compensation: CompensationState = CompensationState.NONE, errorCode: String? = null) = move(r) {
        it.copy(status = status, finishedAt = at, updatedAt = at, compensation = compensation, errorCode = errorCode, errorMessage = errorCode?.let { c -> "msg $c" }, currentStepId = null)
    }

    private fun claim(now: Instant, staleAfter: Duration = Duration.ofMinutes(2), minInterval: Duration = every, approvalInterval: Duration = Duration.ofSeconds(60), perTenant: Int = 1000) =
        store.claimForSweep(now, 1000, perTenant, minInterval, now.minus(staleAfter), approvalInterval).filter { it.tenantId == tenant }

    // ---- create / get / list ---------------------------------------------------------------------------------------

    @Test
    fun `a created run is stored with its definition, input and steps and read back identical`() {
        val r = created(run().copy(
            steps = mapOf("a" to StepState("a", StepStatus.SUCCEEDED, attempt = 1, input = obj("t", "T"), output = obj("id", "o1"), startedAt = at, finishedAt = at.plusSeconds(1), visit = 2)),
            compensable = listOf("a"), stepExecutions = 1, depth = 1
        ))
        assertThat(store.get(tenant, r.runId)).isEqualTo(r)
    }

    @Test
    fun `create is idempotent per scope, a different fingerprint is KeyReused, TEST and LIVE and other users do not share a key`() {
        val first = created(run(key = "same"))
        assertThat(store.create(run(key = "same"))).isEqualTo(CreateOutcome.Existing(first))
        assertThat(store.create(run(key = "same", fingerprint = "other"))).isEqualTo(CreateOutcome.KeyReused)
        assertThat(store.create(run(key = "same", mode = ExecutionMode.TEST))).isInstanceOf(CreateOutcome.Created::class.java)
        assertThat(store.create(run(key = "same", userId = UUID.randomUUID()))).isInstanceOf(CreateOutcome.Created::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE tenant_id = ? AND idempotency_key = 'same'", Long::class.java, tenant)).isEqualTo(3L)
    }

    @Test
    fun `concurrent creates of one key produce exactly one run`() {
        val n = 6
        val pool = Executors.newFixedThreadPool(n)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..n).map { pool.submit<CreateOutcome> { gate.await(); newStore().create(run(key = "race")) } }
            gate.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(outcomes.filterIsInstance<CreateOutcome.Created>()).hasSize(1)
            assertThat(outcomes.filterIsInstance<CreateOutcome.Existing>()).hasSize(n - 1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE tenant_id = ? AND idempotency_key = 'race'", Long::class.java, tenant)).isEqualTo(1L)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a run for an application of another tenant, or with a foreign workspace, is refused and stores nothing`() {
        val other = newTenant(); val otherWs = workspaceOf(other); val otherApp = projectIn(otherWs, other)
        assertThatThrownBy { store.create(run(appId = otherApp)) }.isInstanceOf(RuntimeException::class.java)                       // this tenant, that tenant's app
        assertThatThrownBy { store.create(run(workspaceId = otherWs)) }.isInstanceOf(RuntimeException::class.java)                  // this app, another tenant's workspace
        assertThatThrownBy { store.create(run(workspaceId = workspaceOf(tenant))) }.isInstanceOf(RuntimeException::class.java)      // this tenant's workspace, but not the app's
        assertThatThrownBy { store.create(run(appId = UUID.randomUUID())) }.isInstanceOf(RuntimeException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE tenant_id IN (?, ?)", Long::class.java, tenant, other)).isZero()
        created(run(workspaceId = null))                                                                                              // the workspace is optional
    }

    @Test
    fun `get and list never cross tenants, list filters by app and returns newest first`() {
        val older = created(run(createdAt = at)); val newer = created(run(createdAt = at.plusSeconds(10)))
        val secondApp = projectIn(ws, tenant)
        val elsewhere = created(run(appId = secondApp, createdAt = at.plusSeconds(20)))
        val other = newTenant(); val otherApp = projectIn(workspaceOf(other), other)
        val foreign = created(run(tenantId = other, appId = otherApp, workspaceId = null))
        assertThat(store.get(other, older.runId)).isNull()                                           // right run id, wrong tenant
        assertThat(store.get(tenant, foreign.runId)).isNull()
        assertThat(store.list(tenant, null, 10).map { it.runId }).containsExactly(elsewhere.runId, newer.runId, older.runId)
        assertThat(store.list(tenant, app, 10).map { it.runId }).containsExactly(newer.runId, older.runId)
        assertThat(store.list(tenant, null, 2).map { it.runId }).containsExactly(elsewhere.runId, newer.runId)
        assertThat(store.list(other, null, 10).map { it.runId }).containsExactly(foreign.runId)
        assertThat(store.list(tenant, otherApp, 10)).isEmpty()
    }

    // ---- compare-and-set -------------------------------------------------------------------------------------------

    @Test
    fun `a compare-and-set writes the run and the changed steps together and bumps the version`() {
        val r = created()
        val running = move(r) {
            it.copy(
                status = WorkflowRunStatus.RUNNING, updatedAt = at.plusSeconds(1), stepExecutions = 1,
                steps = it.steps + ("a" to StepState("a", StepStatus.RUNNING, attempt = 1, input = obj("t", "T"), startedAt = at.plusSeconds(1)))
            )
        }
        assertThat(running.version).isEqualTo(1L)
        assertThat(running.status).isEqualTo(WorkflowRunStatus.RUNNING)
        assertThat(running.steps["a"]!!.status).isEqualTo(StepStatus.RUNNING)
        assertThat(running.steps["a"]!!.attempt).isEqualTo(1)
        val done = move(r) {
            it.copy(
                updatedAt = at.plusSeconds(2), compensable = listOf("a"), currentStepId = "b",
                steps = it.steps + ("a" to it.steps["a"]!!.copy(status = StepStatus.SUCCEEDED, output = obj("id", "o1"), finishedAt = at.plusSeconds(2))) + ("b" to StepState("b", StepStatus.PENDING))
            )
        }
        assertThat(done.version).isEqualTo(2L)
        assertThat(done.compensable).containsExactly("a")
        assertThat(done.steps.keys).containsExactly("a", "b")
        assertThat(done.steps["a"]!!.output).isEqualTo(obj("id", "o1"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run_steps WHERE run_id = ?", Long::class.java, r.runId)).isEqualTo(2L)
    }

    @Test
    fun `a compare-and-set from a stale version fails and writes nothing, not even the steps`() {
        val r = created()
        val before = reload(r)
        move(r) { it.copy(status = WorkflowRunStatus.RUNNING, updatedAt = at.plusSeconds(1)) }
        val loser = before.copy(status = WorkflowRunStatus.FAILED, updatedAt = at.plusSeconds(2), steps = before.steps + ("b" to StepState("b", StepStatus.FAILED)))
        assertThat(store.compareAndSet(before, loser)).isFalse()
        val now = reload(r)
        assertThat(now.status).isEqualTo(WorkflowRunStatus.RUNNING)
        assertThat(now.steps.keys).containsExactly("a")
    }

    @Test
    fun `a compare-and-set cannot reach a run of another tenant`() {
        val r = created()
        val other = newTenant()
        val hijack = reload(r).copy(tenantId = other)
        assertThat(store.compareAndSet(hijack, hijack.copy(status = WorkflowRunStatus.CANCELLED))).isFalse()
        assertThat(reload(r).status).isEqualTo(WorkflowRunStatus.PENDING)
        assertThatThrownBy { store.compareAndSet(reload(r), reload(r).copy(runId = UUID.randomUUID())) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `of several concurrent compare-and-sets from one version exactly one wins`() {
        val r = created()
        val base = reload(r)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..5).map { i -> pool.submit<Boolean> { gate.await(); newStore().compareAndSet(base, base.copy(status = WorkflowRunStatus.RUNNING, updatedAt = at.plusSeconds(i.toLong()), stepExecutions = i)) } }
            gate.countDown()
            assertThat(futures.map { it.get(30, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
            assertThat(reload(r).version).isEqualTo(1L)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- restart ---------------------------------------------------------------------------------------------------

    @Test
    fun `completed, failed and cancelled runs survive a restart with their outcome`() {
        val ok = finish(created(), WorkflowRunStatus.SUCCEEDED, at.plusSeconds(5))
        val failed = finish(created(), WorkflowRunStatus.FAILED, at.plusSeconds(6), CompensationState.DONE, ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        val cancelled = finish(created(), WorkflowRunStatus.CANCELLED, at.plusSeconds(7))
        val restarted = newStore()
        for ((before, status) in listOf(ok to WorkflowRunStatus.SUCCEEDED, failed to WorkflowRunStatus.FAILED, cancelled to WorkflowRunStatus.CANCELLED)) {
            val after = reload(before, restarted)
            assertThat(after).isEqualTo(before)
            assertThat(after.status).isEqualTo(status)
            assertThat(after.finishedAt).isNotNull()
        }
        assertThat(reload(failed, restarted).errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(reload(failed, restarted).compensation).isEqualTo(CompensationState.DONE)
        // nothing of a finished run is ever offered to the sweeper again
        assertThat(claim(at.plus(Duration.ofDays(2))).map { it.runId }).doesNotContain(ok.runId, failed.runId, cancelled.runId)
    }

    @Test
    fun `an in-flight run, its step attempts and its compensation progress are reconstructed exactly after a restart`() {
        val r = created()
        move(r) {
            it.copy(
                status = WorkflowRunStatus.RUNNING, updatedAt = at.plusSeconds(1), stepExecutions = 3, compensable = listOf("a", "b"), currentStepId = "b",
                steps = mapOf(
                    "a" to StepState("a", StepStatus.SUCCEEDED, attempt = 1, output = obj("id", "o1"), startedAt = at, finishedAt = at.plusSeconds(1), compensated = true),
                    "b" to StepState("b", StepStatus.RUNNING, attempt = 2, input = obj("t", "T"), startedAt = at.plusSeconds(1), visit = 2)
                )
            )
        }
        val before = reload(r)
        val after = reload(r, newStore())
        assertThat(after).isEqualTo(before)
        assertThat(after.steps["a"]!!.compensated).isTrue()                  // a compensation that already ran is remembered, so it is not run again
        assertThat(after.steps["b"]!!.attempt).isEqualTo(2)                  // the attempt counter is kept (a recovered step continues, it does not restart from 0)
        assertThat(after.compensable).containsExactly("a", "b")
    }

    // ---- sweeper claim ---------------------------------------------------------------------------------------------

    @Test
    fun `a stale run is claimed once and stamped, a second sweeper gets nothing until the interval has passed`() {
        val stale = created(run())
        val now = at.plus(Duration.ofMinutes(10))
        val first = claim(now)
        assertThat(first.map { it.runId }).contains(stale.runId)
        val claimed = first.single { it.runId == stale.runId }
        assertThat(claimed.lastSweptAt).isEqualTo(now)
        assertThat(claimed.version).isEqualTo(stale.version + 1)
        assertThat(claim(now).map { it.runId }).doesNotContain(stale.runId)                         // another sweeper node
        assertThat(claim(now.plusSeconds(1)).map { it.runId }).doesNotContain(stale.runId)         // inside minInterval
        assertThat(claim(now.plusSeconds(6)).map { it.runId }).contains(stale.runId)               // after it
    }

    @Test
    fun `a run that was just updated is not stale and is left alone`() {
        val fresh = created(run(createdAt = at.plus(Duration.ofMinutes(9))))
        assertThat(claim(at.plus(Duration.ofMinutes(10))).map { it.runId }).doesNotContain(fresh.runId)
    }

    @Test
    fun `a due timer or retry is claimed before the run is stale, a future one is not`() {
        val due = created(); val later = created(); val retry = created()
        fun waiting(r: WorkflowRun, status: StepStatus, wake: Instant) = move(r) { it.copy(status = WorkflowRunStatus.WAITING, updatedAt = at.plus(Duration.ofMinutes(9)), steps = it.steps + ("a" to it.steps["a"]!!.copy(status = status, wakeAt = wake))) }
        waiting(due, StepStatus.WAITING, at.plus(Duration.ofMinutes(9)))
        waiting(later, StepStatus.WAITING, at.plus(Duration.ofHours(1)))
        waiting(retry, StepStatus.RETRY_WAIT, at.plus(Duration.ofMinutes(9)))
        val claimed = claim(at.plus(Duration.ofMinutes(10))).map { it.runId }
        assertThat(claimed).contains(due.runId, retry.runId).doesNotContain(later.runId)
    }

    @Test
    fun `a run awaiting an approval is re-examined at the approval interval, not at the short one`() {
        val r = created()
        val approval = UUID.randomUUID()
        move(r) { it.copy(status = WorkflowRunStatus.WAITING, updatedAt = at.plus(Duration.ofMinutes(9)), steps = it.steps + ("a" to it.steps["a"]!!.copy(status = StepStatus.WAITING, approvalId = approval))) }
        val t0 = at.plus(Duration.ofMinutes(10))
        assertThat(claim(t0).map { it.runId }).contains(r.runId)
        assertThat(claim(t0.plusSeconds(10)).map { it.runId }).doesNotContain(r.runId)       // past minInterval (5s) but inside approvalInterval (60s)
        assertThat(claim(t0.plusSeconds(61)).map { it.runId }).contains(r.runId)
    }

    @Test
    fun `a run in backoff is not claimed until its not-before has passed`() {
        val r = created()
        assertThat(store.recordProcessFailure(tenant, r.runId, at.plus(Duration.ofMinutes(20)))).isEqualTo(1)
        assertThat(claim(at.plus(Duration.ofMinutes(10))).map { it.runId }).doesNotContain(r.runId)
        assertThat(claim(at.plus(Duration.ofMinutes(21))).map { it.runId }).contains(r.runId)
    }

    @Test
    fun `a stuck compensation of a finished run is claimed, a finished run without one never is`() {
        val stuck = finish(created(), WorkflowRunStatus.FAILED, at.plusSeconds(1), CompensationState.IN_PROGRESS, "BOOM")
        val done = finish(created(), WorkflowRunStatus.FAILED, at.plusSeconds(1), CompensationState.DONE, "BOOM")
        val claimed = claim(at.plus(Duration.ofMinutes(10))).map { it.runId }
        assertThat(claimed).contains(stuck.runId).doesNotContain(done.runId)
    }

    @Test
    fun `the claim is bounded per tenant`() {
        val a = created(); val b = created(); val c = created()
        val claimed = claim(at.plus(Duration.ofMinutes(10)), perTenant = 2).map { it.runId }
        assertThat(claimed.count { it in setOf(a.runId, b.runId, c.runId) }).isEqualTo(2)
    }

    // ---- failure bookkeeping ---------------------------------------------------------------------------------------

    @Test
    fun `process failures are counted per run and only for the right tenant`() {
        val r = created()
        assertThat(store.recordProcessFailure(tenant, r.runId, at.plusSeconds(10))).isEqualTo(1)
        assertThat(store.recordProcessFailure(tenant, r.runId, at.plusSeconds(20))).isEqualTo(2)
        assertThat(reload(r).processFailures).isEqualTo(2)
        assertThat(reload(r).notBefore).isEqualTo(at.plusSeconds(20))
        assertThat(store.recordProcessFailure(newTenant(), r.runId, at.plusSeconds(30))).isEqualTo(-1)
        assertThat(store.recordProcessFailure(tenant, UUID.randomUUID(), at.plusSeconds(30))).isEqualTo(-1)
        assertThat(reload(r).processFailures).isEqualTo(2)
    }

    @Test
    fun `a failed sweep backs the run off and a successful one lifts only its own backoff`() {
        val r = created()
        store.recordSweepResult(tenant, r.runId, failed = true, notBefore = at.plusSeconds(30))
        assertThat(reload(r).sweepFailures).isEqualTo(1)
        assertThat(reload(r).notBefore).isEqualTo(at.plusSeconds(30))
        store.recordSweepResult(tenant, r.runId, failed = false, notBefore = null)
        assertThat(reload(r).sweepFailures).isZero()
        assertThat(reload(r).notBefore).isNull()
        store.recordProcessFailure(tenant, r.runId, at.plusSeconds(90))
        store.recordSweepResult(tenant, r.runId, failed = true, notBefore = at.plusSeconds(40))
        store.recordSweepResult(tenant, r.runId, failed = false, notBefore = null)
        assertThat(reload(r).notBefore).isEqualTo(at.plusSeconds(40))                                // a process-failure backoff is not the sweeper's to lift
    }

    // ---- retention -------------------------------------------------------------------------------------------------

    @Test
    fun `redaction and purge only ever touch finished runs that are not compensating`() {
        val old = at.minus(Duration.ofDays(100))
        val finished = finish(created(run(createdAt = old).copy(steps = mapOf("a" to StepState("a", StepStatus.SUCCEEDED, input = obj("secret", "s"), output = obj("secret", "o"))))), WorkflowRunStatus.SUCCEEDED, old.plusSeconds(1))
        val compensating = finish(created(run(createdAt = old)), WorkflowRunStatus.FAILED, old.plusSeconds(1), CompensationState.IN_PROGRESS, "BOOM")
        val active = created(run(createdAt = old).copy(status = WorkflowRunStatus.RUNNING))
        val recent = finish(created(), WorkflowRunStatus.SUCCEEDED, at)
        val placeholder = obj("redacted", "yes")

        assertThat(store.redactFinished(at.minus(Duration.ofDays(30)), 1000, placeholder, at)).isGreaterThanOrEqualTo(1)
        val redacted = reload(finished)
        assertThat(redacted.redactedAt).isEqualTo(at)
        assertThat(redacted.input).isEqualTo(placeholder)
        assertThat(redacted.steps["a"]!!.input).isNull()
        assertThat(redacted.steps["a"]!!.output).isNull()
        assertThat(redacted.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)                          // the row and its outcome stay
        for (untouched in listOf(compensating, active, recent)) assertThat(reload(untouched).redactedAt).describedAs(untouched.runId.toString()).isNull()
        assertThat(store.redactFinished(at.minus(Duration.ofDays(30)), 1000, placeholder, at.plusSeconds(5))).isGreaterThanOrEqualTo(0)
        assertThat(reload(finished).redactedAt).isEqualTo(at)                                        // redacting twice does not move the stamp

        assertThat(store.purgeFinished(at.minus(Duration.ofDays(30)), 1000)).isGreaterThanOrEqualTo(1)
        assertThat(store.get(tenant, finished.runId)).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run_steps WHERE run_id = ?", Long::class.java, finished.runId)).isZero()   // the steps go with their run
        for (kept in listOf(compensating, active, recent)) assertThat(store.get(tenant, kept.runId)).describedAs(kept.runId.toString()).isNotNull()
        assertThat(store.purgeFinished(at.plus(Duration.ofDays(1)), 0)).isZero()
    }
}
