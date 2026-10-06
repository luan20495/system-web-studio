package com.systemwebstudio.integration.queue.workflow

import com.rabbitmq.client.Connection
import java.util.Locale

/** Which [com.systemwebstudio.logic.workflow.WorkflowQueue] adapter the runtime uses (`app.workflow.queue`). */
enum class WorkflowQueueMode { MEMORY, AMQP }

/**
 * H-5: the adapter is chosen once, at start-up, from configuration. The domain code never sees this switch (no flag inside `logic.*`).
 *
 *  - `app.workflow.queue=amqp`   RabbitMQ ([AmqpWorkflowQueue]); the only mode allowed in production.
 *  - `app.workflow.queue=memory` in-process queue; for development and tests. Jobs do not survive a restart, so it is **refused** in production.
 *  - not set                     `amqp` in production, `memory` otherwise. Production = the `prod` or `production` Spring profile.
 *
 * Anything else is a start-up error: a typo must not silently pick the in-memory queue.
 */
object WorkflowQueueSelection {
    private val PRODUCTION_PROFILES = setOf("prod", "production")

    fun isProduction(activeProfiles: Collection<String>): Boolean = activeProfiles.any { it.trim().lowercase(Locale.ROOT) in PRODUCTION_PROFILES }

    fun resolve(configured: String?, activeProfiles: Collection<String>): WorkflowQueueMode {
        val production = isProduction(activeProfiles)
        val text = configured?.trim().orEmpty()
        if (text.isEmpty()) return if (production) WorkflowQueueMode.AMQP else WorkflowQueueMode.MEMORY
        val mode = when (text.lowercase(Locale.ROOT)) {
            "amqp" -> WorkflowQueueMode.AMQP
            "memory" -> WorkflowQueueMode.MEMORY
            else -> throw IllegalArgumentException("app.workflow.queue must be 'memory' or 'amqp', not '$text'")
        }
        check(!(production && mode == WorkflowQueueMode.MEMORY)) {
            "app.workflow.queue=memory is not allowed in production: queued workflow jobs would be lost on restart. Use 'amqp'."
        }
        return mode
    }
}

/**
 * One shared broker connection for the workflow queue, re-created when it was closed (the amqp-client's automatic recovery handles network drops of an
 * open connection; this covers a connection that was closed for good). Not closed by the queue; [close] is called by the owner at shutdown.
 */
class CachedBrokerConnection(private val open: () -> Connection) : () -> Connection, AutoCloseable {
    private val lock = Any()
    private var current: Connection? = null

    override fun invoke(): Connection = synchronized(lock) {
        val c = current
        if (c != null && c.isOpen) return c
        open().also { current = it }
    }

    override fun close() {
        val c = synchronized(lock) { current.also { current = null } } ?: return
        try { c.close() } catch (e: Exception) { System.getLogger(CachedBrokerConnection::class.java.name).log(System.Logger.Level.WARNING, "Closing the broker connection failed: ${e.javaClass.simpleName}") }
    }
}
