package com.systemwebstudio.integration.queue.workflow

import com.rabbitmq.client.ConnectionFactory
import com.systemwebstudio.logic.workflow.InMemoryWorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowQueue
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Provides the one [WorkflowQueue] bean (H-5). The runtime wiring injects `WorkflowQueue` and must not construct a queue itself.
 *
 * Properties (all optional except where the broker needs them): `app.workflow.queue` (see [WorkflowQueueSelection]),
 * `spring.rabbitmq.host|port|username|password|virtual-host` (the same settings the rest of the application uses),
 * `app.workflow.amqp.queue|dead-letter-queue|dead-letter-exchange|delivery-limit|confirm-timeout`.
 *
 * `amqp` declares the topology at start-up and so fails fast when the broker is unreachable or when an existing queue was declared with other arguments.
 *
 * Gated by `app.workflow.enabled=true`, the same switch as the rest of the workflow runtime (C0 wiring): with the runtime off nothing consumes the queue, so the
 * application must not connect to the broker or declare a topology for it.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class WorkflowQueueConfiguration {

    @Bean(destroyMethod = "close")
    fun workflowQueue(
        env: Environment,
        @Value("\${app.workflow.queue:}") configured: String,
        @Value("\${spring.rabbitmq.host:127.0.0.1}") host: String,
        @Value("\${spring.rabbitmq.port:5672}") port: Int,
        @Value("\${spring.rabbitmq.username:guest}") username: String,
        @Value("\${spring.rabbitmq.password:guest}") password: String,
        @Value("\${spring.rabbitmq.virtual-host:/}") virtualHost: String,
        @Value("\${app.workflow.amqp.queue:xweb.workflow.jobs}") queue: String,
        @Value("\${app.workflow.amqp.dead-letter-queue:xweb.workflow.jobs.dlq}") deadLetterQueue: String,
        @Value("\${app.workflow.amqp.dead-letter-exchange:xweb.workflow.dlx}") deadLetterExchange: String,
        @Value("\${app.workflow.amqp.delivery-limit:5}") deliveryLimit: Int,
        @Value("\${app.workflow.amqp.confirm-timeout:PT5S}") confirmTimeout: String
    ): WorkflowQueue = when (WorkflowQueueSelection.resolve(configured, env.activeProfiles.toList())) {
        WorkflowQueueMode.MEMORY -> OwnedWorkflowQueue(InMemoryWorkflowQueue(deliveryLimit)) { }
        WorkflowQueueMode.AMQP -> {
            val factory = ConnectionFactory().apply {
                setHost(host); setPort(port); setUsername(username); setPassword(password); setVirtualHost(virtualHost)
                setAutomaticRecoveryEnabled(true); setConnectionTimeout(5_000)
            }
            val connection = CachedBrokerConnection { factory.newConnection("xweb-workflow") }
            val amqp = AmqpWorkflowQueue(connection, WorkflowQueueTopology(queue, deadLetterQueue, deadLetterExchange, deliveryLimit, DurationStyle.detectAndParse(confirmTimeout)))
            try {
                amqp.declareTopology()
            } catch (e: Exception) {
                connection.close()
                throw IllegalStateException("The workflow queue topology could not be declared on the broker", e)
            }
            OwnedWorkflowQueue(amqp) { amqp.close(); connection.close() }
        }
    }
}

/** The queue handed to the application, plus the cleanup of whatever it owns (channels, connection); Spring calls [close] at shutdown. */
class OwnedWorkflowQueue(private val delegate: WorkflowQueue, private val onClose: () -> Unit) : WorkflowQueue by delegate, AutoCloseable {
    override fun close() = onClose()
}
