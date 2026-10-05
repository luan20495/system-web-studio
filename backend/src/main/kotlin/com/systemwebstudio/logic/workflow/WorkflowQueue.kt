package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionDefinitionValidator
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A unit of work on the queue: "run step [stepId] of run [runId]". It deliberately carries **no actor, no permissions and no payload**: the
 * worker reloads the run (and with it the actor and the pinned definition) from the store, so a forged or replayed message can never
 * widen what a run may do, and a message is just a nudge that the idempotent step claim makes safe to deliver more than once.
 */
data class WorkflowJob(val jobId: UUID, val tenantId: UUID, val runId: UUID, val stepId: String) {
    fun encode() = "v1|$jobId|$tenantId|$runId|$stepId"

    companion object {
        /** Pseudo step id of a job that continues the compensation of a finished run. */
        const val COMPENSATE = "~compensate"

        fun forStep(tenantId: UUID, runId: UUID, stepId: String) = WorkflowJob(UUID.randomUUID(), tenantId, runId, stepId)

        /** Strict parse: null for anything that is not exactly a v1 job (such a message is poison and goes to the dead-letter queue). */
        fun decode(body: String): WorkflowJob? {
            if (body.length > 400) return null
            val p = body.split('|')
            if (p.size != 5 || p[0] != "v1") return null
            return try {
                val step = p[4]
                if (step != COMPENSATE && !WorkflowDefinitionValidator.STEP_ID.matches(step)) return null
                WorkflowJob(UUID.fromString(p[1]), UUID.fromString(p[2]), UUID.fromString(p[3]), step)
            } catch (e: IllegalArgumentException) { null }
        }
    }
}

/** A delivered message. [deliveryCount] is 1 on the first delivery, incremented on every redelivery (RabbitMQ: our own header / x-death). */
data class QueueLease(val id: String, val body: String, val deliveryCount: Int)

/**
 * The broker port. A RabbitMQ adapter (C0, `integration/queue`) maps it as: durable queue `xweb.workflow.jobs` with a dead-letter exchange
 * to `xweb.workflow.jobs.dlq`; `publish` = persistent message with publisher confirms; `poll` = basic.get/consumer with manual ack;
 * `nack(requeue=false)` = dead-letter exactly this message. The workflow runtime **never requeues immediately** (a hot redelivery loop burns
 * the delivery budget in milliseconds and, in an outage, dead-letters healthy runs): a handled or outage-affected message is acked and the
 * sweeper re-publishes after a backoff; only a malformed message or a run's last poison message is nacked without requeue.
 * `nack(requeue=true)` stays on the port for other consumers (redelivery with an incremented counter, dead-letter above the limit).
 * Delayed work is **not** a broker feature here: timers live in the run store and the sweeper publishes when they are due.
 */
interface WorkflowQueue {
    /** @throws Exception when the broker did not confirm. The run stays PENDING and the sweeper publishes again. */
    fun publish(job: WorkflowJob)
    fun poll(): QueueLease?
    fun ack(lease: QueueLease)
    /** [requeue] false = reject straight to the dead-letter queue (poison message). */
    fun nack(lease: QueueLease, requeue: Boolean)
    fun pollDeadLetter(): QueueLease?
    fun ackDeadLetter(lease: QueueLease)
}

class InMemoryWorkflowQueue(private val maxDeliveries: Int = 5) : WorkflowQueue {
    private data class Msg(val id: String, val body: String, val deliveries: Int)
    private val ready = ConcurrentLinkedQueue<Msg>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Msg>()
    private val dead = ConcurrentLinkedQueue<Msg>()
    private val deadInFlight = java.util.concurrent.ConcurrentHashMap<String, Msg>()
    private val seq = AtomicLong()
    @Volatile var failPublish: Boolean = false

    /** Test knobs / introspection. */
    fun publishRaw(body: String) { ready += Msg("m${seq.incrementAndGet()}", body, 0) }
    fun readyCount() = ready.size
    fun inFlightCount() = inFlight.size
    fun deadLetterBodies(): List<String> = dead.map { it.body } + deadInFlight.values.map { it.body }
    /** Simulates a consumer that died without acking: everything in flight is redelivered. */
    fun requeueInFlight() { inFlight.values.toList().forEach { inFlight.remove(it.id); ready += it } }

    override fun publish(job: WorkflowJob) {
        if (failPublish) throw IllegalStateException("broker unavailable")
        ready += Msg("m${seq.incrementAndGet()}", job.encode(), 0)
    }

    override fun poll(): QueueLease? {
        val m = ready.poll() ?: return null
        val d = m.copy(deliveries = m.deliveries + 1)
        inFlight[d.id] = d
        return QueueLease(d.id, d.body, d.deliveries)
    }

    override fun ack(lease: QueueLease) { inFlight.remove(lease.id) }

    override fun nack(lease: QueueLease, requeue: Boolean) {
        val m = inFlight.remove(lease.id) ?: return
        if (requeue && m.deliveries < maxDeliveries) ready += m else dead += m
    }

    override fun pollDeadLetter(): QueueLease? {
        val m = dead.poll() ?: return null
        deadInFlight[m.id] = m
        return QueueLease(m.id, m.body, m.deliveries)
    }

    override fun ackDeadLetter(lease: QueueLease) { deadInFlight.remove(lease.id) }
}
