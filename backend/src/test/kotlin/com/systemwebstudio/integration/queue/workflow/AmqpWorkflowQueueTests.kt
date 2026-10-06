package com.systemwebstudio.integration.queue.workflow

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.limits.TenantRateLimiter
import com.systemwebstudio.logic.workflow.QueueLease
import com.systemwebstudio.logic.workflow.WorkflowEngine
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowLimits
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowQueueContract
import com.systemwebstudio.logic.workflow.WorkflowRig
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowWorker
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

/**
 * [AmqpWorkflowQueue] against a REAL RabbitMQ 4 (Testcontainers, no mock): the whole [WorkflowQueueContract] (redelivery after a dead consumer, dead-lettering,
 * the delivery limit), then what only a broker can show - publisher confirms, an unroutable publish, messages that survive a broker restart, a lease that dies
 * with its channel - and finally the real engine and worker on the real broker (duplicate delivery, consumer death before the ack, a poison run to the
 * dead-letter queue).
 *
 * Needs Docker, like the other integration tests of the backend. STATUS: written against the real API but NOT YET RUN (no Docker / Gradle where it was written):
 * it is part of gate G2 and counts only once it has passed on the Mac.
 */
class AmqpWorkflowQueueTests : WorkflowQueueContract() {
    companion object {
        private const val USER = "xweb"
        private const val PASSWORD = "test-workflow-rabbit-123"
        /** Fixed host port, so a container restart keeps the address. */
        private val hostPort: Int = ServerSocket(0).use { it.localPort }

        val rabbit: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:4-management-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
            .apply { setPortBindings(listOf("$hostPort:5672")); start() }

        private val factory = ConnectionFactory().apply {
            setHost(rabbit.host); setPort(hostPort); setUsername(USER); setPassword(PASSWORD)
            setAutomaticRecoveryEnabled(false)                     // the supplier below opens a fresh connection when the old one is gone
            setConnectionTimeout(3_000)
        }
        private var shared: Connection? = null
        @Synchronized fun connection(): Connection = shared?.takeIf { it.isOpen } ?: factory.newConnection("workflow-queue-test").also { shared = it }
    }

    private val executor = Executors.newCachedThreadPool()
    private val opened = mutableListOf<AmqpWorkflowQueue>()

    @AfterEach fun closeQueues() { opened.forEach { it.close() }; opened.clear(); executor.shutdownNow() }

    // ---- the contract ------------------------------------------------------------------------------------------------

    private fun topology(id: String = UUID.randomUUID().toString().take(8), deliveryLimit: Int = 3, confirmTimeout: Duration = Duration.ofSeconds(10)) =
        WorkflowQueueTopology("test.jobs.$id", "test.jobs.$id.dlq", "test.dlx.$id", deliveryLimit, confirmTimeout)

    private fun declared(t: WorkflowQueueTopology = topology()): AmqpWorkflowQueue =
        AmqpWorkflowQueue({ connection() }, t).also { it.declareTopology(); opened += it }

    override fun newQueue(): WorkflowQueue = declared()

    override fun killConsumer(queue: WorkflowQueue) { (queue as AmqpWorkflowQueue).dropConsumer() }

    /** Redelivery and dead-lettering are asynchronous in the broker: wait a little for a message, but do not wait long for an absence. */
    override fun <T> eventually(block: () -> T?): T? {
        val end = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (true) {
            block()?.let { return it }
            if (System.nanoTime() > end) return null
            Thread.sleep(50)
        }
    }

    // ---- what only a broker shows ---------------------------------------------------------------------------------------

    @Test fun `the topology is a durable quorum queue with a dead-letter exchange and a delivery limit, and declaring it twice changes nothing`() {
        val t = topology(deliveryLimit = 7)
        val q = declared(t)
        q.declareTopology()
        val ch = connection().createChannel()
        try {
            // passive declare of the same name with the same arguments succeeds; the broker's management API would show x-queue-type=quorum, x-delivery-limit=7
            ch.queueDeclarePassive(t.queue); ch.queueDeclarePassive(t.deadLetterQueue)
        } finally { ch.close() }
        // a different delivery limit on an existing queue is refused by the broker, never silently accepted
        assertThrows(Exception::class.java) { AmqpWorkflowQueue({ connection() }, t.copy(deliveryLimit = 9)).declareTopology() }
    }

    @Test fun `a publish is confirmed by the broker and survives, and the message is persistent`() {
        val q = declared()
        val job = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "a")
        q.publish(job)
        val ch = connection().createChannel()
        try {
            val got = ch.basicGet(q.topology.queue, true)!!
            assertEquals(2, got.props.deliveryMode, "persistent")
            assertEquals(job.runId.toString(), got.props.correlationId)
            assertEquals(job.jobId.toString(), got.props.messageId)
        } finally { ch.close() }
    }

    @Test fun `a publish the broker refuses is reported as a failure and not lost silently - publisher confirm failure`() {
        val t = topology()
        val ch = connection().createChannel()
        try {   // a full queue that rejects publishes: the broker answers basic.nack to the confirm
            ch.queueDeclare(t.queue, true, false, false, mapOf<String, Any>("x-queue-type" to "quorum", "x-max-length" to 1, "x-overflow" to "reject-publish"))
        } finally { ch.close() }
        val q = AmqpWorkflowQueue({ connection() }, t).also { opened += it }
        q.publish(WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "a"))
        assertThrows(Exception::class.java) { q.publish(WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "b")) }
        val first = q.poll()!!; q.ack(first)
        // the failed publish left no half-open state behind: after room was made, publishing works again
        q.publish(WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "c"))
        assertNotNull(eventually { q.poll() })
    }

    @Test fun `a publish to a queue that does not exist is refused as unroutable instead of vanishing`() {
        val q = AmqpWorkflowQueue({ connection() }, topology()).also { opened += it }     // topology deliberately not declared
        assertThrows(IllegalStateException::class.java) { q.publish(WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "a")) }
    }

    @Test fun `a lease from a channel that died is stale - acknowledging it is a no-op and the message comes back, a reused delivery tag harms nobody`() {
        val q = declared()
        val a = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "a"); val b = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "b")
        q.publish(a)
        val stale = q.poll()!!                                  // tag 1 on channel #1
        q.dropConsumer()                                        // the consumer dies
        q.publish(b)
        var fresh: QueueLease?
        val seen = mutableListOf<QueueLease>()
        while (true) { fresh = eventually { q.poll() }; if (fresh == null) break; seen += fresh; if (seen.size == 2) break }
        assertEquals(2, seen.size)
        assertTrue(seen.any { it.body == a.encode() && it.deliveryCount >= 2 }, "a was redelivered with a higher count")
        q.ack(stale)                                            // stale: must not acknowledge whatever now carries the same tag on the new channel
        q.dropConsumer()                                        // and the two messages come back once more
        val again = generateSequence { eventually { q.poll() } }.take(2).toList()
        assertEquals(setOf(a.encode(), b.encode()), again.map { it.body }.toSet(), "nothing was acknowledged by the stale lease")
    }

    @Test fun `messages survive a broker restart and a lease from before it is stale`() {
        val q = declared()
        val jobs = (1..3).map { WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "s$it") }
        jobs.forEach { q.publish(it) }
        val inFlight = q.poll()!!
        restartBroker()
        q.ack(inFlight)                                         // its channel died with the broker: skipped, the message is redelivered
        val bodies = mutableSetOf<String>()
        val end = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (bodies.size < 3 && System.nanoTime() < end) {
            val lease = try { q.poll() } catch (e: Exception) { null }
            if (lease == null) { Thread.sleep(200); continue }
            bodies += lease.body; q.ack(lease)
        }
        assertEquals(jobs.map { it.encode() }.toSet(), bodies, "every job published before the restart is still there")
        // and the queue keeps working after the restart
        val after = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "after")
        retry { q.publish(after) }
        assertEquals(after.encode(), eventually { q.poll() }!!.body)
    }

    // ---- the real engine and worker on the real broker ------------------------------------------------------------------

    private class Harness(val rig: WorkflowRig, val queue: AmqpWorkflowQueue, val engine: WorkflowEngine, val worker: WorkflowWorker)

    private val actions = listOf("w1", "w2").map { Fx.write(it) }
    private val two = wf("two", act("a", "w1"), act("b", "w2"))

    private fun harness(queue: AmqpWorkflowQueue, maxProcessFailures: Int = 2): Harness {
        val rig = WorkflowRig(listOf(two), actions, executor, maxProcessFailures = maxProcessFailures)
        // the rig's own engine uses its in-memory queue; this one shares everything else (stores, actions, clock) but publishes to the real broker
        val engine = WorkflowEngine(
            Fx.json, rig.defs, rig.actionRig.runtime, rig.runStore, queue, rig.access, rig.tenants, rig.audit, rig.approvals, WorkflowLimits(), rig.clock,
            Duration.ofMinutes(2), TenantRateLimiter.UNLIMITED, maxProcessFailures
        )
        return Harness(rig, queue, engine, WorkflowWorker(engine, queue))
    }

    private fun Harness.start(key: String) =
        (engine.start(rig.ctx(), com.systemwebstudio.logic.workflow.WorkflowStartRequest("two", Fx.obj(), key)) as com.systemwebstudio.logic.workflow.WorkflowResult.Ok).value.runId

    private fun Harness.drainBroker() { repeat(200) { if (!worker.runOnce()) { Thread.sleep(25); if (!worker.runOnce()) return } } }

    @Test fun `a workflow runs to the end through the real broker, and duplicate deliveries repeat no effect`() {
        val h = harness(declared())
        val id = h.start("k1")
        val a = WorkflowJob.forStep(Fx.tenantA, id, "a").encode()
        h.queue.publish(WorkflowJob.decode(a)!!); h.queue.publish(WorkflowJob.decode(a)!!)         // the broker delivers 'a' three times in all
        h.drainBroker()
        assertEquals(WorkflowRunStatus.SUCCEEDED, h.rig.view(id).status)
        assertEquals(1, h.rig.writes("w1")); assertEquals(1, h.rig.writes("w2"))
    }

    @Test fun `a consumer that dies before the ack gets the message redelivered and the effect still happens once`() {
        val h = harness(declared())
        val id = h.start("k2")
        assertNotNull(eventually { h.queue.poll() })            // the job of step a is taken by a consumer that dies
        h.queue.dropConsumer()
        h.drainBroker()
        assertEquals(WorkflowRunStatus.SUCCEEDED, h.rig.view(id).status)
        assertEquals(1, h.rig.writes("w1")); assertEquals(1, h.rig.writes("w2"))
    }

    @Test fun `a poison run reaches the real dead-letter queue after its budget, healthy runs are unaffected, and nothing is requeued`() {
        val h = harness(declared(), maxProcessFailures = 2)
        val healthy = h.start("healthy")
        val poison = h.start("poison")
        h.rig.runStore.poison += poison
        repeat(4) { h.drainBroker(); h.rig.advance(Duration.ofMinutes(11)); h.engine.sweep() }
        h.drainBroker()
        assertEquals(WorkflowRunStatus.SUCCEEDED, h.rig.view(healthy).status)
        assertEquals(WorkflowRunStatus.FAILED, h.rig.view(poison).status)
        assertEquals("DEAD_LETTERED", h.rig.view(poison).errorCode)
        val dead = eventually { h.queue.pollDeadLetter() }
        assertNotNull(dead, "exactly the poison run's last message is in the dead-letter queue")
        assertTrue(dead!!.body.contains(poison.toString()))
        h.queue.ackDeadLetter(dead)
        assertNull(eventually { h.queue.pollDeadLetter() }, "only one dead letter")
        assertEquals(1, h.rig.writes("w1"), "only the healthy run wrote: the poison run never reached its action")
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------

    private fun restartBroker() {
        rabbit.dockerClient.restartContainerCmd(rabbit.containerId).exec()
        val end = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (System.nanoTime() < end) {
            try { factory.newConnection("probe").close(); return } catch (e: Exception) { Thread.sleep(500) }
        }
        error("RabbitMQ did not come back after the restart")
    }

    private fun retry(times: Int = 60, block: () -> Unit) {
        var last: Exception? = null
        repeat(times) { try { block(); return } catch (e: Exception) { last = e; Thread.sleep(500) } }
        throw last!!
    }
}
