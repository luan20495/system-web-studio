package com.systemwebstudio.wiring.persistence

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.action.Fx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration

/**
 * G3-07: the broker is restarted while the run state stays in PostgreSQL. The broker here is a container of this class only (fixed host port, so a restart keeps
 * the address): the broker shared by the other test classes is never restarted. No sweeper is ever called in these tests, so that what finishes the run is the
 * message that survived (or was redelivered), not the lost-job recovery.
 */
class WorkflowG3BrokerRestartTests : G3TestBase() {
    private companion object {
        const val USER = "xweb"
        const val PASSWORD = "g3-restart-rabbit-123"
        val hostPort: Int = ServerSocket(0).use { it.localPort }
        val rabbit: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:4-management-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
            .apply { setPortBindings(listOf("$hostPort:5672")); start() }
        val factory = ConnectionFactory().apply {
            host = rabbit.host; port = hostPort; username = USER; password = PASSWORD; isAutomaticRecoveryEnabled = false; connectionTimeout = 3_000
        }
        @Volatile var shared: Connection? = null
        @Synchronized fun open(): Connection = shared?.takeIf { it.isOpen } ?: factory.newConnection("g3-restart").also { shared = it }
    }

    override fun connection(): Connection = open()

    private fun restartBroker() {
        rabbit.dockerClient.restartContainerCmd(rabbit.containerId).exec()
        await("the broker accepts connections again", Duration.ofSeconds(90)) { runCatching { factory.newConnection("probe").close(); true }.getOrNull() }
    }

    /** Works on after the restart with the SAME queue object: its channels and connection died with the broker and are re-opened by the adapter. */
    private fun drainThroughRestart(p: G3Process, until: () -> Boolean) {
        await("the worker finishes the run after the broker is back", Duration.ofSeconds(90)) {
            try { p.worker.runOnce() } catch (e: Exception) { Thread.sleep(200) }        // the adapter throws while the broker is unreachable; the worker loop retries
            if (until()) true else null
        }
    }

    @Test
    fun `G3-07A a job that sat in the broker across a restart is still there, the same worker reconnects and finishes the run with one effect per step`() {
        val b = broker()
        val p = process(listOf(two), amqp(b))
        val id = p.start("two", "g3-07a")                                             // the confirm means: written to disk by the quorum queue
        assertThat(ready(b.topology.queue)).isEqualTo(1)

        restartBroker()
        await("the persisted job is back in the durable quorum queue") { if (ready(b.topology.queue) == 1) true else null }
        assertThat(p.writes("w1")).isZero()

        drainThroughRestart(p) { p.stored(id).status == WorkflowRunStatus.SUCCEEDED }
        val run = p.stored(id)
        assertThat(run.steps["a"]!!.attempt).isEqualTo(1)
        assertThat(run.steps["b"]!!.attempt).isEqualTo(1)
        assertThat(p.writes("w1")).isEqualTo(1)
        assertThat(p.writes("w2")).isEqualTo(1)
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-07B a step persisted as SUCCEEDED whose ack was never sent, followed by a broker restart, is redelivered and its effect is not repeated`() {
        val b = broker()
        val crashing = CrashableQueue(amqp(b))
        val first = process(listOf(two), crashing)
        val id = first.start("two", "g3-07b")
        crashing.skipNextAck = true
        assertThat(first.worker.runOnce()).isTrue()                                   // a runs, SUCCEEDED is persisted, job b is published, the ack never leaves
        assertThat(crashing.crashes.get()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run_steps WHERE tenant_id = ? AND run_id = ? AND step_id = 'a'", String::class.java, Fx.tenantA, id)).isEqualTo("SUCCEEDED")
        assertThat(first.writes("w1")).isEqualTo(1)

        restartBroker()                                                               // the consumer's channel dies with the broker: the unacked job a is returned
        await("job a (returned) and job b (published) are both in the queue") { if (ready(b.topology.queue) == 2) true else null }

        val secondQueue = CrashableQueue(amqp(b))
        val second = process(listOf(two), secondQueue)
        drainThroughRestart(second) { second.stored(id).status == WorkflowRunStatus.SUCCEEDED }

        val redelivered = secondQueue.polled.mapNotNull { l -> WorkflowJob.decode(l.body)?.let { it to l.deliveryCount } }
        assertThat(redelivered.filter { it.first.stepId == "a" }.map { it.second }).describedAs("job a was delivered again, counted by the broker").allMatch { it >= 2 }
        assertThat(redelivered.filter { it.first.stepId == "a" }).isNotEmpty()
        val run = second.stored(id)
        assertThat(run.steps["a"]!!.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(run.steps["a"]!!.attempt).describedAs("the database stayed authoritative: step a was not claimed again").isEqualTo(1)
        assertThat(total("w1", first, second)).isEqualTo(1)
        assertThat(total("w2", first, second)).isEqualTo(1)
        eventuallyEmpty(b, "queue after the restart")
    }
}
