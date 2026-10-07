package com.systemwebstudio.wiring

import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * H-5 in the real application: with the workflow runtime on and `app.workflow.queue=amqp`, the context has exactly one `WorkflowQueue` (C4's), it is backed by the
 * real broker (the topology exists there), and a job published through the bean can be taken and acknowledged. Run-state is `memory` here: this test is about the queue.
 */
@TestPropertySource(properties = [
    "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.run-store=memory", "app.workflow.queue=amqp",
    "app.workflow.amqp.queue=h5.wiring.jobs", "app.workflow.amqp.dead-letter-queue=h5.wiring.jobs.dlq", "app.workflow.amqp.dead-letter-exchange=h5.wiring.dlx",
    "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000"
])
class WorkflowQueueWiringTests : IntegrationTestBase() {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var queue: WorkflowQueue

    @Test
    fun `the application has exactly one WorkflowQueue and with amqp it is the broker`() {
        assertThat(context.getBeansOfType(WorkflowQueue::class.java)).hasSize(1)
        val ch = IntegrationTestBase.rabbit.let {
            com.rabbitmq.client.ConnectionFactory().apply { host = it.host; port = it.getMappedPort(5672); username = "studio"; password = "test-rabbit-123" }.newConnection("h5-probe")
        }
        try {
            ch.createChannel().use { c ->
                assertThat(c.queueDeclarePassive("h5.wiring.jobs").messageCount).isZero()
                assertThat(c.queueDeclarePassive("h5.wiring.jobs.dlq").messageCount).isZero()
            }
        } finally { ch.close() }
        val job = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), "s")
        queue.publish(job)
        var lease = queue.poll()
        for (i in 1..40) { if (lease != null) break; Thread.sleep(50); lease = queue.poll() }
        assertThat(WorkflowJob.decode(lease!!.body)!!.runId).isEqualTo(job.runId)
        queue.ack(lease)
        assertThat(queue.poll()).isNull()
    }
}
