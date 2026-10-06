package com.systemwebstudio.integration.queue.workflow

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.ReturnListener
import com.systemwebstudio.logic.workflow.QueueLease
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowQueue
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Names and limits of the workflow broker topology (C4 contract, `docs/parallel/audit/C4-V29-readiness-and-queue-contract.md` section 4).
 *
 *  - [queue]            `xweb.workflow.jobs`      durable **quorum** queue; a message that is returned to it more than [deliveryLimit] times is dead-lettered by the broker
 *  - [deadLetterExchange] `xweb.workflow.dlx`     durable direct exchange; the queue's dead-letter exchange
 *  - [deadLetterQueue]  `xweb.workflow.jobs.dlq`  durable quorum queue bound to the exchange with its own name as routing key
 */
data class WorkflowQueueTopology(
    val queue: String = "xweb.workflow.jobs",
    val deadLetterQueue: String = "xweb.workflow.jobs.dlq",
    val deadLetterExchange: String = "xweb.workflow.dlx",
    /** Quorum `x-delivery-limit`: how often the broker may give a message back (consumer died, channel closed, nack with requeue) before it dead-letters it. */
    val deliveryLimit: Int = 5,
    /** How long a publish waits for the broker's confirm (written to the quorum of replicas) before it is reported as failed. */
    val confirmTimeout: Duration = Duration.ofSeconds(5)
) {
    init {
        require(queue.isNotBlank() && deadLetterQueue.isNotBlank() && deadLetterExchange.isNotBlank()) { "names must not be blank" }
        require(queue != deadLetterQueue) { "the queue and its dead-letter queue must differ" }
        require(deliveryLimit >= 1) { "deliveryLimit must be at least 1" }
        require(!confirmTimeout.isNegative && !confirmTimeout.isZero) { "confirmTimeout must be positive" }
    }
}

/**
 * [WorkflowQueue] on RabbitMQ, written against the plain `amqp-client` API so that every guarantee is visible in this file:
 *
 *  - **durable**: a quorum queue (replicated, written to disk), persistent messages (`deliveryMode = 2`);
 *  - **publisher confirms**: [publish] returns only after the broker confirmed the message, and throws when it was nacked, timed out, or came back as
 *    unroutable (`mandatory`). The engine treats a throwing publish as "run stays PENDING, the sweeper publishes again", so a run can be late, never lost;
 *  - **manual ack**: [poll] is a `basic.get` without auto-ack; the worker acknowledges after the run's state is saved ([ack]);
 *  - **no hot requeue**: this class never requeues on its own. [nack] with `requeue = false` dead-letters exactly that message through the dead-letter exchange;
 *  - **bounded redelivery**: a message whose consumer dies (no ack) is redelivered by the broker and counted (`x-delivery-count`); past `x-delivery-limit` the
 *    broker dead-letters it. Redelivery is only a nudge: the step claim in the run store makes a duplicate harmless, and workflow retries never use it;
 *  - **correlation**: `messageId` = job id, `correlationId` = run id, so a broker-side trace can be tied to a run without opening the body (which names no payload).
 *
 * Threading: one publish channel and one consume channel, each guarded by a lock (a Channel is not safe for concurrent use). The consume channel is shared by
 * all workers of the node; a lease is `generation:deliveryTag`, so a lease from a channel that has since died is recognised as stale and ignored
 * (the broker has already given that message back) instead of acknowledging an unrelated message that reuses the tag.
 *
 * Failure of an acknowledgement is **not** an error for the caller: the work is saved, the broker redelivers, the redelivery repeats nothing.
 *
 * The [connection] supplier owns reconnection (typically one cached connection with automatic recovery, re-created if it was closed). This class never closes it.
 */
class AmqpWorkflowQueue(
    private val connection: () -> Connection,
    val topology: WorkflowQueueTopology = WorkflowQueueTopology()
) : WorkflowQueue, AutoCloseable {
    private val log = System.getLogger(AmqpWorkflowQueue::class.java.name)

    private val publishLock = Any()
    private var publishChannel: Channel? = null
    private val unroutable = AtomicReference<String?>(null)

    private val consumeLock = Any()
    private var consumeChannel: Channel? = null
    private var generation = 0L

    /**
     * Declares the topology (idempotent). Call once at start-up. Declaring a queue with different arguments than the existing one is refused by the broker
     * (PRECONDITION_FAILED), which is what we want: a drifted topology must be fixed by an operator, not silently used.
     */
    fun declareTopology() {
        val ch = connection().createChannel()
        try {
            ch.exchangeDeclare(topology.deadLetterExchange, "direct", true)
            ch.queueDeclare(topology.deadLetterQueue, true, false, false, mapOf<String, Any>("x-queue-type" to "quorum"))
            ch.queueBind(topology.deadLetterQueue, topology.deadLetterExchange, topology.deadLetterQueue)
            ch.queueDeclare(
                topology.queue, true, false, false,
                mapOf<String, Any>(
                    "x-queue-type" to "quorum",
                    "x-dead-letter-exchange" to topology.deadLetterExchange,
                    "x-dead-letter-routing-key" to topology.deadLetterQueue,
                    "x-delivery-limit" to topology.deliveryLimit,
                    // a dead letter is only removed from the main queue once the dead-letter queue has stored it; needs reject-publish when the queue is full
                    "x-dead-letter-strategy" to "at-least-once",
                    "x-overflow" to "reject-publish"
                )
            )
        } finally {
            closeQuietly(ch)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- publish

    override fun publish(job: WorkflowJob) {
        val props = AMQP.BasicProperties.Builder()
            .deliveryMode(2)
            .contentType("text/plain")
            .contentEncoding("UTF-8")
            .type("workflow.job.v1")
            .messageId(job.jobId.toString())
            .correlationId(job.runId.toString())
            .build()
        val body = job.encode().toByteArray(Charsets.UTF_8)
        synchronized(publishLock) {
            val ch = publishChannelOrOpen()
            try {
                unroutable.set(null)
                ch.basicPublish("", topology.queue, true, props, body)      // default exchange, routing key = queue name, mandatory
                ch.waitForConfirmsOrDie(topology.confirmTimeout.toMillis())  // throws on nack and on timeout
                unroutable.get()?.let { throw IllegalStateException("The broker could not route the workflow job: $it") }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                discardPublishChannel()                                       // its confirm state is unknown now; the next publish opens a clean one
                throw e
            }
        }
    }

    private fun publishChannelOrOpen(): Channel {
        publishChannel?.takeIf { it.isOpen }?.let { return it }
        val ch = connection().createChannel()
        try {
            ch.confirmSelect()
            ch.addReturnListener(ReturnListener { code, text, _, routingKey, _, _ -> unroutable.set("$code $text (routing key $routingKey)") })
        } catch (e: Exception) {
            closeQuietly(ch); throw e
        }
        publishChannel = ch
        return ch
    }

    private fun discardPublishChannel() { publishChannel?.let { closeQuietly(it) }; publishChannel = null }

    // ---------------------------------------------------------------------------------------------------------------- consume

    override fun poll(): QueueLease? = get(topology.queue)

    override fun pollDeadLetter(): QueueLease? = get(topology.deadLetterQueue)

    private fun get(queue: String): QueueLease? = synchronized(consumeLock) {
        val ch = consumeChannelOrOpen()
        val fetched: GetResponse? = try {
            ch.basicGet(queue, false)
        } catch (e: Exception) {
            discardConsumeChannel(); throw e
        }
        val response = fetched ?: return null
        QueueLease("$generation:${response.envelope.deliveryTag}", String(response.body, Charsets.UTF_8), deliveryCount(response))
    }

    /** `x-delivery-count` is absent on the first delivery of a quorum-queue message and counts the returns after that. */
    private fun deliveryCount(r: GetResponse): Int {
        val returns = (r.props?.headers?.get("x-delivery-count") as? Number)?.toInt() ?: 0
        return returns + 1
    }

    override fun ack(lease: QueueLease) = settle(lease, "ack") { ch, tag -> ch.basicAck(tag, false) }

    override fun nack(lease: QueueLease, requeue: Boolean) = settle(lease, if (requeue) "nack(requeue)" else "nack(dead-letter)") { ch, tag -> ch.basicNack(tag, false, requeue) }

    override fun ackDeadLetter(lease: QueueLease) = settle(lease, "ack(dead letter)") { ch, tag -> ch.basicAck(tag, false) }

    private fun settle(lease: QueueLease, what: String, op: (Channel, Long) -> Unit) {
        val parts = lease.id.split(':')
        val gen = parts.getOrNull(0)?.toLongOrNull()
        val tag = parts.getOrNull(1)?.toLongOrNull()
        require(parts.size == 2 && gen != null && tag != null) { "Not a lease of this queue" }
        synchronized(consumeLock) {
            val ch = consumeChannel
            if (gen != generation || ch == null || !ch.isOpen) {
                // the channel the message was delivered on is gone: the broker has already given it back, and a redelivery is harmless
                log.log(System.Logger.Level.WARNING, "Workflow queue: $what skipped, the lease belongs to a closed channel (the message will be redelivered)")
                return
            }
            try {
                op(ch, tag)
            } catch (e: Exception) {
                // every other lease of this channel is now invalid too; the broker redelivers them all
                log.log(System.Logger.Level.WARNING, "Workflow queue: $what failed (${e.javaClass.simpleName}); the message will be redelivered")
                discardConsumeChannel()
            }
        }
    }

    private fun consumeChannelOrOpen(): Channel {
        consumeChannel?.takeIf { it.isOpen }?.let { return it }
        generation++
        val ch = connection().createChannel()
        consumeChannel = ch
        return ch
    }

    private fun discardConsumeChannel() { consumeChannel?.let { closeQuietly(it) }; consumeChannel = null }

    override fun close() {
        synchronized(publishLock) { discardPublishChannel() }
        synchronized(consumeLock) { discardConsumeChannel() }
    }

    /**
     * Test and operations hook: closes the consume channel as a crashed consumer would. Everything delivered and not acknowledged is given back by the broker
     * (and counted against the delivery limit).
     */
    fun dropConsumer() = synchronized(consumeLock) { discardConsumeChannel() }

    private fun closeQuietly(ch: Channel) {
        try { if (ch.isOpen) ch.close() } catch (e: Exception) { /* already gone */ }
    }
}
