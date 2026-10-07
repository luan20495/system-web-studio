package com.systemwebstudio.wiring

import com.systemwebstudio.integration.queue.workflow.OwnedWorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource

/**
 * H-5 / D-C0-32 · the runtime wiring no longer builds a queue of its own: exactly ONE `WorkflowQueue` per context, chosen by `app.workflow.queue`
 * (the selection rules themselves are C4's `WorkflowQueueSelectionTests`). Here: the real C0 configuration classes together.
 */
@TestPropertySource(properties = ["app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.queue=memory", "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000"])
class WorkflowQueueSelectionWiringTests : IntegrationTestBase() {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `dev and test with memory - one queue, the in-process one, and the runtime is wired to it`() {
        val beans = context.getBeansOfType(WorkflowQueue::class.java)
        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(OwnedWorkflowQueue::class.java)
        assertThat(context.getBeanNamesForType(WorkflowQueue::class.java)).containsExactly("workflowQueue")
    }

    @Test
    fun `AppRuntimeConfiguration declares no queue bean of its own and a second definition of the name is refused`() {
        val src = java.io.File("src/main/kotlin/com/systemwebstudio/wiring/AppRuntimeConfiguration.kt").readText()
        assertThat(src).doesNotContain("InMemoryWorkflowQueue").doesNotContain("fun workflowQueue(")
        // production-shaped refusals, with the real configuration class
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(com.systemwebstudio.integration.queue.workflow.WorkflowQueueConfiguration::class.java))
            .withPropertyValues("app.workflow.enabled=true")
        runner.withPropertyValues("spring.profiles.active=prod", "app.workflow.queue=memory").run { assertThat(it.startupFailure).isNotNull() }
        runner.withPropertyValues("app.workflow.queue=typo").run { assertThat(it.startupFailure).isNotNull() }
        runner.withPropertyValues("app.workflow.queue=memory").run { assertThat(it).hasSingleBean(WorkflowQueue::class.java) }
        // with the runtime off nothing is built at all (no broker connection)
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(com.systemwebstudio.integration.queue.workflow.WorkflowQueueConfiguration::class.java))
            .run { assertThat(it).doesNotHaveBean(WorkflowQueue::class.java) }
    }

    @Test
    fun `amqp that cannot reach its broker stops the start - there is no memory fallback`() {
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(com.systemwebstudio.integration.queue.workflow.WorkflowQueueConfiguration::class.java))
            .withPropertyValues("app.workflow.enabled=true", "app.workflow.queue=amqp", "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=1")
        runner.run {
            assertThat(it.startupFailure).isNotNull()
            assertThat(generateSequence(it.startupFailure as Throwable?) { t -> t.cause }.any { t -> t.message?.contains("topology could not be declared") == true }).isTrue()
        }
    }
}
