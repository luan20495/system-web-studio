package com.systemwebstudio.publish

import com.systemwebstudio.integration.queue.JobQueue
import com.systemwebstudio.integration.queue.Queues
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/** Consumes publish jobs. Failures after the configured retries end up in the dead-letter queue. */
@Component
class PublishWorker(private val processor: DeploymentProcessor) {
    @RabbitListener(queues = [Queues.PUBLISH])
    fun onMessage(deploymentId: String) = processor.process(UUID.fromString(deploymentId))
}

/**
 * The job is published after the database commit. If the broker was unreachable at that moment (or a worker died
 * mid-run), this sweeper re-publishes stale deployments; the CAS transitions make re-delivery harmless.
 */
@Component
class DeploymentRecovery(
    private val deployments: DeploymentRepository,
    private val queue: JobQueue,
    @Value("\${app.deploy.recovery-queued-seconds:30}") private val queuedSeconds: Long,
    @Value("\${app.deploy.recovery-progress-seconds:120}") private val progressSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.deploy.recovery-interval-ms:30000}", initialDelayString = "\${app.deploy.recovery-interval-ms:30000}")
    fun republishStale() {
        runCatching {
            deployments.staleIds(queuedSeconds, progressSeconds).forEach {
                log.warn("Re-publishing stale deployment {}", it)
                queue.publish(Queues.PUBLISH, it.toString())
            }
        }.onFailure { log.warn("Deployment recovery skipped: {}", it.message) }
    }
}
