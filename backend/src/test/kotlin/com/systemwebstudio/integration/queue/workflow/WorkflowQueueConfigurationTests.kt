package com.systemwebstudio.integration.queue.workflow

import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

/**
 * H-5 with a real Spring context (no broker needed): which `WorkflowQueue` bean the application gets from `app.workflow.queue` and the active profile.
 * The AMQP bean against a live broker is covered by the Testcontainers tests; here the AMQP branch is proven by its start-up behaviour (it tries the
 * broker, which is unreachable on purpose, and refuses to start).
 */
class WorkflowQueueConfigurationTests {
    private val runner = ApplicationContextRunner().withUserConfiguration(WorkflowQueueConfiguration::class.java).withPropertyValues("app.workflow.enabled=true")
    private val noBroker = arrayOf("spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=1")

    @Test fun `memory selects exactly one working in-memory queue`() {
        runner.withPropertyValues("app.workflow.queue=memory").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            val queues = ctx.getBeansOfType(WorkflowQueue::class.java)
            assertEquals(1, queues.size)
            val queue = queues.values.single()
            val job = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "s")
            queue.publish(job)
            assertNotNull(queue.poll(), "an in-memory queue hands the job back without any broker")
        }
    }

    @Test fun `unset outside production is memory`() {
        runner.run { ctx ->
            assertEquals(null, ctx.startupFailure)
            assertEquals(1, ctx.getBeansOfType(WorkflowQueue::class.java).size)
        }
    }

    @Test fun `amqp selects the broker adapter and fails fast when the broker cannot be reached`() {
        runner.withPropertyValues("app.workflow.queue=amqp", *noBroker).run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure, "amqp mode must try the broker at start-up")
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("topology could not be declared") == true }, failure.toString())
        }
    }

    @Test fun `production defaults to amqp`() {
        runner.withPropertyValues("spring.profiles.active=prod", *noBroker).run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("topology could not be declared") == true }, failure.toString())
        }
    }

    @Test fun `memory is refused in production`() {
        runner.withPropertyValues("spring.profiles.active=prod", "app.workflow.queue=memory").run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("not allowed in production") == true }, failure.toString())
        }
    }

    @Test fun `a typo stops the application`() {
        runner.withPropertyValues("app.workflow.queue=rabbit").run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("rabbit") == true }, failure.toString())
        }
    }

    @Test fun `with the workflow runtime off there is no queue bean and no broker connection, even in production`() {
        ApplicationContextRunner().withUserConfiguration(WorkflowQueueConfiguration::class.java)
            .withPropertyValues("spring.profiles.active=prod", *noBroker).run { ctx ->
                assertEquals(null, ctx.startupFailure)
                assertEquals(0, ctx.getBeansOfType(WorkflowQueue::class.java).size)
            }
    }
}
