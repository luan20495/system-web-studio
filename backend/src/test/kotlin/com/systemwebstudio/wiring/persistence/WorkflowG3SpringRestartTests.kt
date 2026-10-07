package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.integration.queue.workflow.WorkflowQueueConfiguration
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.FakeDataPort
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.wiring.AppRuntime
import com.systemwebstudio.wiring.RunStoreConfiguration
import com.systemwebstudio.wiring.WorkflowWorkerRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a node needs that is not a bean of the production configuration: the workflows, the effect sink shared by all nodes, the crash boundaries. */
class G3NodeDeps(
    val workflows: List<WorkflowDefinition>, val actionDefs: List<ActionDefinition>, val ctx: ActionContext, val data: FakeDataPort, val workerId: String,
    val staleAfter: Duration, val beforeExecute: (() -> Unit)? = null, val dieOnNextAck: Boolean = false
)

/**
 * The node as the application assembles it, minus the HTTP side: the REAL `RunStoreConfiguration` (PostgreSQL stores), the REAL `WorkflowQueueConfiguration`
 * (`app.workflow.queue=amqp`, topology declared on the broker at start-up, channels and connection closed at shutdown), the REAL `WorkflowWorkerRunner`
 * (`@Scheduled`, polls the queue and sweeps) over an [AppRuntime] built from the same engine / worker / action runtime the other G3 tests use.
 */
// Deliberately NOT @Configuration: the main application scans the test classpath, and these classes are only meant to be registered by hand into the node contexts below.
@EnableScheduling
@Import(RunStoreConfiguration::class, WorkflowQueueConfiguration::class)
class G3NodeConfig {
    @Bean
    fun g3Process(queue: WorkflowQueue, actionRuns: ActionRunStore, runs: WorkflowRunStore, deps: G3NodeDeps): G3Process {
        val crashable = CrashableQueue(queue).also { it.dieOnNextAck = deps.dieOnNextAck }
        return G3Process(
            deps.workflows, deps.actionDefs, crashable, actionRuns, runs, Clock.systemUTC(), deps.ctx, deps.staleAfter, workerId = deps.workerId, data = deps.data,
            sweepMinInterval = Duration.ofSeconds(1)
        ).also { it.beforeExecute = deps.beforeExecute }
    }

    @Bean
    fun appRuntime(p: G3Process) = AppRuntime(p.runtime, p.engine, p.definitions, p.engine, p.worker)
}

@Import(G3NodeConfig::class, WorkflowWorkerRunner::class)
class G3NodeWithWorkerConfig

/**
 * G3-08 and G3-12 (restart part): a node is a Spring application context that is really started and really closed; the next node is a new context over the same
 * PostgreSQL rows and the same broker queue. "Killed" = the worker thread ends in a [ProcessDeath] (heartbeat stopped, step RUNNING, message unacked), then the
 * context is closed, which is what releases the channels of the dead consumer. Real clock: leases are seconds long here.
 */
class WorkflowG3SpringRestartTests : G3TestBase() {
    private val staleAfter = Duration.ofSeconds(4)

    private inner class Node(val b: G3Broker, val deps: G3NodeDeps, withWorker: Boolean = true, workerDelayMillis: Long = 100) : AutoCloseable {
        val spring = AnnotationConfigApplicationContext()
        init {
            spring.environment.propertySources.addFirst(MapPropertySource("g3", mapOf(
                "app.workflow.enabled" to "true", "app.workflow.run-store" to "jdbc", "app.workflow.queue" to "amqp",
                "spring.rabbitmq.host" to IntegrationTestBase.rabbit.host, "spring.rabbitmq.port" to IntegrationTestBase.rabbit.getMappedPort(5672).toString(),
                "spring.rabbitmq.username" to "studio", "spring.rabbitmq.password" to "test-rabbit-123",
                "app.workflow.amqp.queue" to b.topology.queue, "app.workflow.amqp.dead-letter-queue" to b.topology.deadLetterQueue,
                "app.workflow.amqp.dead-letter-exchange" to b.topology.deadLetterExchange, "app.workflow.amqp.delivery-limit" to b.topology.deliveryLimit.toString(),
                "app.workflow.worker-delay-ms" to workerDelayMillis.toString()
            )))
            spring.beanFactory.registerSingleton("jdbcTemplate", jdbc)
            spring.beanFactory.registerSingleton("jsonMapper", json)
            spring.beanFactory.registerSingleton("g3NodeDeps", deps)
            spring.register(if (withWorker) G3NodeWithWorkerConfig::class.java else G3NodeConfig::class.java)
            spring.refresh()
        }
        val process: G3Process get() = spring.getBean(G3Process::class.java)
        val queue: CrashableQueue get() = process.queue as CrashableQueue
        override fun close() { runCatching { spring.close() }; runCatching { process.executor.shutdownNow() } }
    }

    private fun deps(workflows: List<WorkflowDefinition>, data: FakeDataPort, beforeExecute: (() -> Unit)? = null, dieOnNextAck: Boolean = false) =
        G3NodeDeps(workflows, actionDefs, ctx, data, "node-" + UUID.randomUUID().toString().take(8), staleAfter, beforeExecute, dieOnNextAck)

    private fun node(b: G3Broker, d: G3NodeDeps, withWorker: Boolean = true, delay: Long = 100) = Node(b, d, withWorker, delay).also { own(it) }

    @Test
    fun `G3-08A the application is restarted while a step is RUNNING - the new context takes the run over when the lease ran out, as a new attempt, and the old owner can no longer renew or write`() {
        val b = broker()
        val data = FakeDataPort()
        val died = CountDownLatch(1)
        val nodeA = node(b, deps(listOf(two), data, beforeExecute = { died.countDown(); throw ProcessDeath("killed after the claim") }))
        val id = nodeA.process.start("two", "g3-08a")                                  // the real WorkflowWorkerRunner of context A takes the job and the step is claimed
        assertThat(died.await(30, TimeUnit.SECONDS)).describedAs("worker of context A was killed holding the step").isTrue()
        val claimed = nodeA.process.stored(id)
        assertThat(claimed.steps["a"]!!.status).isEqualTo(StepStatus.RUNNING)
        assertThat(claimed.leaseOwner).isEqualTo(nodeA.deps.workerId)
        assertThat(data.writes).isEmpty()
        val engineA = nodeA.process.engine
        nodeA.close()                                                                   // the application stops: channels of the dead consumer go, the message is given back

        val nodeB = node(b, deps(listOf(two), data))                                    // the application starts again: new beans, new worker id, same database and queue
        await("context B recovers the run (lease expiry, sweep, republish, new attempt)", Duration.ofSeconds(90)) { if (nodeB.process.stored(id).status == WorkflowRunStatus.SUCCEEDED) true else null }
        val run = nodeB.process.stored(id)
        assertThat(nodeB.deps.workerId).isNotEqualTo(nodeA.deps.workerId)
        assertThat(run.steps["a"]!!.attempt).describedAs("claimed by A (1), recovered by B (2)").isEqualTo(2)
        assertThat(run.steps["b"]!!.attempt).isEqualTo(1)
        assertThat(run.leaseOwner).isNull()
        assertThat(data.writes.count { it.second.queryRef == "w1" }).isEqualTo(1)
        assertThat(data.writes.count { it.second.queryRef == "w2" }).isEqualTo(1)

        val settled = version(id)
        assertThat(engineA.renewLease(Fx.tenantA, id, "a", 1)).describedAs("the old owner can no longer renew").isFalse()
        assertThat(version(id)).describedAs("and its attempt wrote nothing").isEqualTo(settled)
        await("queue and dead-letter queue are empty") { if (ready(b.topology.queue) == 0 && ready(b.topology.deadLetterQueue) == 0) true else null }
    }

    @Test
    fun `G3-08B the application is restarted after a step was persisted but before the ack - the new context reads the database, repeats no effect and acknowledges`() {
        val b = broker()
        val data = FakeDataPort()
        val nodeA = node(b, deps(listOf(two), data, dieOnNextAck = true))
        val id = nodeA.process.start("two", "g3-08b")
        await("context A persisted step a and died before the ack") { if (nodeA.queue.crashes.get() == 1) true else null }
        assertThat(nodeA.process.stored(id).steps["a"]!!.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(data.writes.count { it.second.queryRef == "w1" }).isEqualTo(1)
        nodeA.close()

        val nodeB = node(b, deps(listOf(two), data))
        await("context B finishes the run", Duration.ofSeconds(60)) { if (nodeB.process.stored(id).status == WorkflowRunStatus.SUCCEEDED) true else null }
        val seen = nodeB.queue.polled.mapNotNull { l -> WorkflowJob.decode(l.body)?.let { it.stepId to l.deliveryCount } }
        assertThat(seen.filter { it.first == "a" }).describedAs("job a came back").isNotEmpty().allMatch { it.second >= 2 }
        assertThat(nodeB.process.stored(id).steps["a"]!!.attempt).isEqualTo(1)
        assertThat(data.writes.count { it.second.queryRef == "w1" }).describedAs("effect of step a, in both contexts").isEqualTo(1)
        assertThat(data.writes.count { it.second.queryRef == "w2" }).isEqualTo(1)
        await("everything acknowledged, nothing dead-lettered") { if (ready(b.topology.queue) == 0 && ready(b.topology.deadLetterQueue) == 0) true else null }
    }

    @Test
    fun `G3-12 a run cancelled while queued stays cancelled across an application restart, and the job that arrives afterwards executes nothing`() {
        val b = broker()
        val data = FakeDataPort()
        val nodeA = node(b, deps(listOf(two), data), withWorker = false)                // no worker in this context: the job stays queued
        val id = nodeA.process.start("two", "g3-12q")
        assertThat((nodeA.process.engine.cancel(ctx, id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(ready(b.topology.queue)).isEqualTo(1)
        nodeA.close()                                                                   // the application stops with the job in the broker

        val nodeB = node(b, deps(listOf(two), data))
        await("the stale job is consumed and acknowledged") { if (ready(b.topology.queue) == 0 && nodeB.queue.polled.isNotEmpty()) true else null }
        assertThat(nodeB.process.stored(id).status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(data.writes).isEmpty()
        assertThat(ready(b.topology.deadLetterQueue)).isZero()
    }
}
