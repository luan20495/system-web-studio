package com.systemwebstudio.integration.queue

import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

object Queues {
    const val PUBLISH = "studio.publish"
    const val PUBLISH_DLQ = "studio.publish.dlq"
}

/** Port for background work. The core only knows "publish this payload to this named queue". */
interface JobQueue {
    fun publish(queue: String, payload: String)
}

@Component
class AmqpJobQueue(private val rabbit: RabbitTemplate) : JobQueue {
    override fun publish(queue: String, payload: String) = rabbit.convertAndSend(queue, payload)
}

@Configuration
class QueueConfiguration {
    @Bean fun publishQueue(): Queue = QueueBuilder.durable(Queues.PUBLISH)
        .deadLetterExchange("").deadLetterRoutingKey(Queues.PUBLISH_DLQ).build()
    @Bean fun publishDeadLetterQueue(): Queue = QueueBuilder.durable(Queues.PUBLISH_DLQ).build()
}
