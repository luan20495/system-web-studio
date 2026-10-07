package com.systemwebstudio.wiring.persistence

import com.rabbitmq.client.AMQP
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.IdempotencyPolicy
import com.systemwebstudio.logic.action.InputSpec
import com.systemwebstudio.logic.action.InputType
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.RetryPolicy
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * G3-09 .. G3-12 on real PostgreSQL (V29 stores) and RabbitMQ 4: the ordering between the workflow's retry timer, the action-run sweeper and duplicate deliveries;
 * a race of several workers for one expired lease; dead-lettering joined to the durable workflow state; cancel after a worker death.
 */
class WorkflowG3SweeperRaceTests : G3TestBase() {
    private val retryingSaga = { attempts: Int ->
        wf("rs", act("s1", "w1", comp = "c1"), act("s2", "w2", retry = RetryPolicy(maxAttempts = attempts, initialBackoff = Duration.ofSeconds(30), multiplier = 1.0, maxBackoff = Duration.ofSeconds(30))))
    }

    private fun stepOf(p: G3Process, id: UUID, step: String) = p.stored(id).steps[step]!!

    /** The first process takes the saga to the point "the write of w2 was sent and never answered" and is then left behind. */
    private fun dieAfterSend(workflowDef: com.systemwebstudio.logic.workflow.WorkflowDefinition, b: G3Broker, key: String): Triple<G3Process, UUID, java.util.concurrent.CountDownLatch> {
        val dying = process(listOf(workflowDef), amqp(b))
        val g = hangOnWrite(dying, "w2")
        val id = dying.start(workflowDef.id, key)
        inBackground { dying.drain(300) }
        await("the write of w2 was dispatched") { if (dying.writes("w2") == 1 && stepOf(dying, id, "s2").status == StepStatus.RUNNING) true else null }
        return Triple(dying, id, g)
    }

    // ---- G3-09: ACTION_IN_PROGRESS and the action-run sweeper -------------------------------------------------------------

    @Test
    fun `G3-09 mutating - while the dead worker's action run is RUNNING the retry waits, a duplicate delivery changes nothing, and after the sweep the step ends UNKNOWN without a second send`() {
        val b = broker()
        val (dying, id, _) = dieAfterSend(retryingSaga(6), b, "g3-09m")
        val survivor = process(listOf(retryingSaga(6)), amqp(b))

        // 1. the workflow lease ran out (3 min) but the action run is far from stale (10 min): the next attempt meets ACTION_IN_PROGRESS and waits in backoff
        clock.advance(Duration.ofMinutes(3))
        assertThat(survivor.engine.sweep().republished).isEqualTo(1)
        survivor.drain(1500)
        val waiting = stepOf(survivor, id, "s2")
        assertThat(waiting.status).isEqualTo(StepStatus.RETRY_WAIT)
        assertThat(waiting.errorCode).isEqualTo(ActionErrorCodes.ACTION_IN_PROGRESS)
        assertThat(waiting.attempt).isEqualTo(2)
        assertThat(survivor.writes("w2")).describedAs("not sent again while the first one may still be running").isZero()

        // 2. a duplicate delivery of the same job arrives while it waits: no claim, no write to the run
        val v = version(id)
        survivor.queue.publish(WorkflowJob.forStep(Fx.tenantA, id, "s2"))
        survivor.drain(600)
        assertThat(version(id)).isEqualTo(v)
        assertThat(survivor.writes("w2")).isZero()

        // 3. the retry timer fires BEFORE the action-run sweeper ran: still in progress, still waiting, the attempt budget (6) is what bounds this
        clock.advance(Duration.ofMinutes(8))
        survivor.engine.sweep(); survivor.drain(1500)
        assertThat(stepOf(survivor, id, "s2").status).isEqualTo(StepStatus.RETRY_WAIT)
        assertThat(stepOf(survivor, id, "s2").attempt).isEqualTo(3)
        assertThat(survivor.writes("w2")).isZero()

        // 4. the action-run recovery tick: the abandoned MUTATING run becomes UNKNOWN, not retryable (exactly one row: ours)
        clock.advance(Duration.ofMinutes(5))
        assertThat(survivor.actionRuns.sweepStale(clock.instant().minus(Duration.ofMinutes(10)), clock.instant())).isEqualTo(1)

        // 5. the next retry replays the recorded UNKNOWN: the run ends there - no further attempt, no send, no onError, the ambiguous step is not compensated
        survivor.engine.sweep(); survivor.drain(1500)
        val run = survivor.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(run.errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(run.steps["s2"]!!.status).isEqualTo(StepStatus.FAILED)
        assertThat(run.steps["s2"]!!.attempt).describedAs("terminal at the attempt that replayed UNKNOWN, far below the budget of 6").isEqualTo(4)
        assertThat(run.steps["s2"]!!.compensated).isFalse()
        assertThat(run.steps["s1"]!!.compensated).isTrue()
        assertThat(run.compensation).isEqualTo(CompensationState.DONE)
        assertThat(total("w2", dying, survivor)).describedAs("the mutation was sent exactly once, by the dead worker").isEqualTo(1)
        assertThat(survivor.data.writes.map { it.second.queryRef }).containsExactly("c1")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ? AND action_id = 'w2' AND status = 'RUNNING'", Long::class.java, Fx.tenantA)).isZero()
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-09 mutating - characterization - when the retry budget runs out before the action sweeper ran, the run fails with ACTION_IN_PROGRESS, and nothing is resent`() {
        val b = broker()
        val (dying, id, _) = dieAfterSend(retryingSaga(2), b, "g3-09x")
        val survivor = process(listOf(retryingSaga(2)), amqp(b))
        clock.advance(Duration.ofMinutes(3))
        survivor.engine.sweep(); survivor.drain(1500)
        // attempt 2 of 2 meets ACTION_IN_PROGRESS: there is no attempt left to wait for
        val run = survivor.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(run.errorCode).describedAs("the code is ACTION_IN_PROGRESS, not UNKNOWN: pinned here as the current behaviour, see the readiness doc").isEqualTo(ActionErrorCodes.ACTION_IN_PROGRESS)
        assertThat(total("w2", dying, survivor)).isEqualTo(1)
        assertThat(run.steps["s2"]!!.compensated).isFalse()
        assertThat(run.steps["s1"]!!.compensated).isTrue()
        // the action run is still RUNNING and the action-run recovery later turns it into UNKNOWN, as for every abandoned mutating run
        clock.advance(Duration.ofMinutes(10))
        assertThat(survivor.actionRuns.sweepStale(clock.instant().minus(Duration.ofMinutes(10)), clock.instant())).isEqualTo(1)
        survivor.engine.sweep(); survivor.drain(600)
        assertThat(total("w2", dying, survivor)).isEqualTo(1)
        assertThat(survivor.stored(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        eventuallyEmpty(b)
    }

    private val nav = Fx.navigate("nav1", inputs = listOf(InputSpec("title", InputType.STRING))).copy(idempotency = IdempotencyPolicy.OPTIONAL)

    @Test
    fun `G3-09 non-mutating - an abandoned run becomes a retryable TIMEOUT and the step is simply run again within its budget`() {
        assertThat(nav.type.mutatesState).isFalse()
        val b = broker()
        val def = wf("nv", act("s1", "nav1", retry = RetryPolicy(maxAttempts = 6, initialBackoff = Duration.ofSeconds(30), multiplier = 1.0, maxBackoff = Duration.ofSeconds(30))))
        val dying = process(listOf(def), amqp(b), extraActions = listOf(nav))
        val g = gate(); dying.navigateHook = { g.await(60, TimeUnit.SECONDS) }
        val id = dying.start("nv", "g3-09n")
        inBackground { dying.drain(300) }
        await("the navigate action started") {
            jdbc.queryForList("SELECT mutating FROM action_runs WHERE tenant_id = ? AND action_id = 'nav1' AND status = 'RUNNING'", Boolean::class.java, Fx.tenantA).firstOrNull()
        }.also { assertThat(it).describedAs("stored as non-mutating").isFalse() }

        val survivor = process(listOf(def), amqp(b), extraActions = listOf(nav))
        clock.advance(Duration.ofMinutes(3))
        survivor.engine.sweep(); survivor.drain(1500)
        assertThat(stepOf(survivor, id, "s1").status).describedAs("ACTION_IN_PROGRESS while the first run is RUNNING").isEqualTo(StepStatus.RETRY_WAIT)
        assertThat(stepOf(survivor, id, "s1").errorCode).isEqualTo(ActionErrorCodes.ACTION_IN_PROGRESS)

        clock.advance(Duration.ofMinutes(11))
        assertThat(survivor.actionRuns.sweepStale(clock.instant().minus(Duration.ofMinutes(10)), clock.instant())).isEqualTo(1)
        val swept = jdbc.queryForMap("SELECT status, result::text AS result FROM action_runs WHERE tenant_id = ? AND action_id = 'nav1'", Fx.tenantA)
        assertThat(swept["status"]).isEqualTo("FAILED")
        assertThat(swept["result"].toString()).contains("TIMEOUT").containsPattern("\"retryable\"\\s*:\\s*true")

        survivor.engine.sweep(); survivor.drain(1500)
        val run = survivor.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(run.steps["s1"]!!.attempt).describedAs("attempt 1 died, 2 waited, 3 ran").isEqualTo(3)
        assertThat(jdbc.queryForMap("SELECT status, attempt FROM action_runs WHERE tenant_id = ? AND action_id = 'nav1'", Fx.tenantA)).containsEntry("status", "SUCCEEDED").containsEntry("attempt", 2)
        eventuallyEmpty(b)
    }

    // ---- G3-10: several workers race for one expired lease ---------------------------------------------------------------

    @Test
    fun `G3-10 of several workers racing for an expired lease exactly one takes it, one attempt is added, one effect happens and the old owner commits nothing`() {
        val b = broker()
        repeat(5) { round ->
            val old = process(listOf(one), amqp(b))
            val g = hangBeforeExecute(old)
            val id = old.start("one", "g3-10-$round")
            val stuck = inBackground { old.drain(300) }
            await("round $round: the old worker owns the step") { if (stepOf(old, id, "a").status == StepStatus.RUNNING) true else null }
            assertThat(old.stored(id).leaseOwner).isEqualTo(old.workerId)

            clock.advance(Duration.ofMinutes(3))                                   // the old owner's lease ran out
            val racers = listOf(process(listOf(one), amqp(b)), process(listOf(one), amqp(b)), process(listOf(one), amqp(b)))
            val start = CyclicBarrier(racers.size)
            val pool = Executors.newFixedThreadPool(racers.size)
            val republished = try {
                racers.map { r -> pool.submit<Int> { start.await(10, TimeUnit.SECONDS); val n = r.engine.sweep().republished; r.drain(1500); n } }.map { it.get(60, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }

            val run = racers[0].stored(id)
            assertThat(republished.sum()).describedAs("round $round: the lost run is republished exactly once").isEqualTo(1)
            assertThat(run.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
            assertThat(run.steps["a"]!!.attempt).describedAs("round $round: one winner, one attempt added").isEqualTo(2)
            assertThat(total("w1", *racers.toTypedArray())).describedAs("round $round: effect of the winner").isEqualTo(1)
            assertThat(run.leaseOwner).isNull()

            val settled = version(id)
            g.countDown(); stuck.get(30, TimeUnit.SECONDS)                         // the old owner returns from its stall
            assertThat(version(id)).describedAs("round $round: the old owner commits nothing").isEqualTo(settled)
            assertThat(total("w1", old, *racers.toTypedArray())).describedAs("round $round: replayed by the derived key, never a second effect").isEqualTo(1)
        }
        eventuallyEmpty(b)
    }

    // ---- G3-11: dead-lettering joined to the durable workflow state -----------------------------------------------------------

    @Test
    fun `G3-11 a malformed message goes to the dead-letter queue without touching any run or effect, and is never requeued`() {
        val b = broker()
        val p = process(listOf(two), amqp(b))
        val id = p.start("two", "g3-11a")                                           // a healthy run is also queued behind the poison
        val before = version(id)
        connection().createChannel().let { ch ->
            try { ch.basicPublish("", b.topology.queue, AMQP.BasicProperties.Builder().deliveryMode(2).build(), "not a workflow job".toByteArray()) } finally { ch.close() }
        }
        val healthyJob = p.queue.poll()!!                                           // the healthy job was first: handle it by hand so that the poison is next
        p.queue.ack(healthyJob)
        await("the poison message is the next delivery") { if (ready(b.topology.queue) == 1) true else null }
        assertThat(p.worker.runOnce()).isTrue()                                     // rejected with requeue=false
        await("the poison message is in the dead-letter queue, and only there") { if (ready(b.topology.deadLetterQueue) == 1 && ready(b.topology.queue) == 0) true else null }
        assertThat(version(id)).describedAs("no run was touched").isEqualTo(before)
        assertThat(p.data.writes).isEmpty()
        val dead = await("the dead letter") { p.queue.pollDeadLetter() }
        assertThat(dead.body).isEqualTo("not a workflow job")
        p.queue.ackDeadLetter(dead)
        eventuallyEmpty(b)
        assertThat(p.stored(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    @Test
    fun `G3-11 a job whose consumers keep dying is dead-lettered by the broker without becoming a workflow retry, and the DLQ consumer then fails the run durably`() {
        val b = broker(deliveryLimit = 2)
        val consumer = amqp(b)
        val crashing = CrashableQueue(consumer)
        val p = process(listOf(two), crashing)
        val id = p.start("two", "g3-11b")
        var rounds = 0
        await("the broker dead-letters the message after its delivery limit") {
            crashing.poll()?.also { rounds++ }                                      // delivered ...
            consumer.dropConsumer()                                                 // ... and the consumer dies without acking: the broker takes it back
            if (ready(b.topology.deadLetterQueue) == 1) true else null
        }
        assertThat(rounds).describedAs("delivered, dropped unacked, redelivered ... until the limit of 2").isGreaterThanOrEqualTo(2)
        // the broker gave up; the durable workflow state did not move: no attempt, no effect, no retry created by redeliveries
        val untouched = p.stored(id)
        assertThat(untouched.status).isEqualTo(WorkflowRunStatus.PENDING)
        assertThat(untouched.steps["a"]!!.attempt).isZero()
        assertThat(untouched.processFailures).isZero()
        assertThat(p.data.writes).isEmpty()
        // the DLQ consumer attributes the dead letter to its run: ONE process failure (a message the broker gave up on says nothing against a healthy run), recorded durably
        assertThat(p.worker.drainDeadLetters()).isEqualTo(1)
        val counted = p.stored(id)
        assertThat(counted.status).isEqualTo(WorkflowRunStatus.PENDING)
        assertThat(counted.processFailures).isEqualTo(1)
        assertThat(counted.notBefore).describedAs("backed off, not hot-republished").isNotNull()
        assertThat(ready(b.topology.deadLetterQueue)).describedAs("the dead letter was acknowledged").isZero()
        assertThat(p.data.writes).isEmpty()
        // after the backoff the sweeper republishes the run and it completes: nothing was lost, nothing ran twice
        assertThat(p.engine.sweep().republished).describedAs("not before its backoff").isZero()
        clock.advance(Duration.ofMinutes(11))
        assertThat(p.engine.sweep().republished).isEqualTo(1)
        p.drain(1500)
        assertThat(p.stored(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(p.writes("w1")).isEqualTo(1); assertThat(p.writes("w2")).isEqualTo(1)
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-11 a run that really is poison is failed durably as DEAD_LETTERED after its budget, healthy runs are unaffected, and exactly its last message is in the DLQ`() {
        val b = broker()
        val p = process(listOf(two), amqp(b), maxProcessFailures = 2)
        val healthy = p.start("two", "g3-11-healthy")
        val poison = p.start("two", "g3-11-poison")
        p.runStore.poison += poison                                                  // every claim of this run throws: the worker fails on it, as opposed to an outage
        repeat(4) { p.drain(800); clock.advance(Duration.ofMinutes(11)); p.engine.sweep() }
        p.drain(800)
        assertThat(p.stored(healthy).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        val failed = p.stored(poison)
        assertThat(failed.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(failed.errorCode).isEqualTo("DEAD_LETTERED")
        assertThat(jdbc.queryForObject("SELECT error_code FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", String::class.java, Fx.tenantA, poison)).isEqualTo("DEAD_LETTERED")
        assertThat(p.writes("w1")).describedAs("only the healthy run wrote").isEqualTo(1)
        val dead = await("the last message of the poison run is in the dead-letter queue") { p.queue.pollDeadLetter() }
        assertThat(dead.body).contains(poison.toString())
        p.queue.ackDeadLetter(dead)
        assertThat(ready(b.topology.deadLetterQueue)).describedAs("only one dead letter").isZero()
        eventuallyEmpty(b)
    }

    // ---- G3-12: cancel and a dead worker ---------------------------------------------------------------------------------------

    private val cancellable = wf("cc", act("a", "w1", comp = "c1"), act("b", "w2"), compensateOnCancel = true)

    @Test
    fun `G3-12 a run cancelled while a worker holds a step, and that worker never comes back, is compensated by another worker and never resumed`() {
        val b = broker()
        val dying = process(listOf(cancellable), amqp(b))
        val g = gate()
        dying.beforeExecute = { dying.beforeExecute = { g.await(60, TimeUnit.SECONDS) } }          // step a runs, step b is taken and then the worker is stuck for good
        val id = dying.start("cc", "g3-12")
        inBackground { dying.drain(300) }
        await("step a done, step b held by the worker") { if (stepOf(dying, id, "a").status == StepStatus.SUCCEEDED && stepOf(dying, id, "b").status == StepStatus.RUNNING) true else null }

        val operator = process(listOf(cancellable), amqp(b))
        assertThat((operator.engine.cancel(ctx, id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(operator.stored(id).leaseOwner).isNull()
        assertThat(operator.stored(id).compensation).isEqualTo(CompensationState.IN_PROGRESS)

        operator.drain(1500)                                                         // the compensation job
        clock.advance(Duration.ofDays(1))
        operator.engine.sweep(); operator.drain(1500)                                // a day later: nothing resumes the cancelled work
        val run = operator.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(run.compensation).isEqualTo(CompensationState.DONE)
        assertThat(run.steps["a"]!!.compensated).isTrue()
        assertThat(total("c1", dying, operator)).describedAs("step a compensated once").isEqualTo(1)
        assertThat(total("w2", dying, operator)).describedAs("step b never executed: its worker is gone and nobody resumes it").isZero()
        assertThat(total("w1", dying, operator)).isEqualTo(1)
        eventuallyEmpty(b)
    }
}
