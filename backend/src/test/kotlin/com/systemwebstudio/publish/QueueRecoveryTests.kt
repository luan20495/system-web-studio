package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

/** Publish jobs must survive worker restarts, lost enqueues and workers that die half-way. */
@TestPropertySource(properties = ["app.deploy.recovery-interval-ms=1000", "app.deploy.recovery-queued-seconds=2", "app.deploy.recovery-progress-seconds=2"])
class QueueRecoveryTests : IntegrationTestBase() {
    @Autowired lateinit var registry: RabbitListenerEndpointRegistry

    private fun status(id: UUID) = jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, id)
    private fun events(id: UUID) = jdbc.queryForList("SELECT status FROM deployment_events WHERE deployment_id = ? ORDER BY created_at, id", String::class.java, id)

    private fun orphan(sc: Scenario, at: String, ageSeconds: Int): UUID {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id=? ORDER BY version_number DESC LIMIT 1", UUID::class.java, sc.projectId)!!
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, updated_at) VALUES (?,?,?,?,?,'PRIVATE',?,'mock', now() - make_interval(secs => ?))", id, sc.ws, sc.projectId, version, sc.user.id, at, ageSeconds)
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,?,?)", UUID.randomUUID(), id, "QUEUED", "queued")
        return id
    }

    @Test
    fun `a job published while the worker is stopped waits in the durable queue and completes when the worker returns`() {
        val sc = scenario()
        registry.stop()
        try {
            val r = sc.s.post("${sc.base}/publish", """{"visibility":"PRIVATE","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "stopped-worker-01")
            assertThat(r.response.status).isEqualTo(202)
            val id = UUID.fromString(sc.s.body(r).get("id").asString())
            Thread.sleep(1500)
            // the sweeper may re-publish duplicates; with the consumer stopped nothing may progress
            assertThat(status(id)).isEqualTo("QUEUED")
            registry.start()
            await().atMost(Duration.ofSeconds(30)).until { status(id) == "RUNNING" }
            assertThat(events(id)).containsExactly("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "RUNNING")     // duplicates did not repeat a step
        } finally { registry.start() }
    }

    @Test
    fun `a deployment whose enqueue was lost is re-published by the sweeper`() {
        val sc = scenario()
        val id = orphan(sc, "QUEUED", 60)
        await().atMost(Duration.ofSeconds(30)).until { status(id) == "RUNNING" }
        assertThat(events(id).last()).isEqualTo("RUNNING")
    }

    @Test
    fun `a worker that died mid-pipeline is resumed from where it stopped, without repeating earlier steps`() {
        val sc = scenario()
        val id = orphan(sc, "BUILDING", 60)
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status) VALUES (?,?,'POLICY_CHECK'), (?,?,'SECURITY_CHECK'), (?,?,'BUILDING')", UUID.randomUUID(), id, UUID.randomUUID(), id, UUID.randomUUID(), id)
        await().atMost(Duration.ofSeconds(30)).until { status(id) == "RUNNING" }
        assertThat(events(id).filter { it == "POLICY_CHECK" }).hasSize(1)
        assertThat(events(id).filter { it == "BUILDING" }).hasSize(1)
        assertThat(events(id).takeLast(2)).containsExactly("DEPLOYING", "RUNNING")
    }
}
