package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

/**
 * The publish pipeline end to end against the release scope: a publish that finds the scope owned waits without failing, gives up after the allowed time,
 * a publish overtaken by a newer intent fails explicitly, a worker that died mid-DEPLOYING is taken over, and publishes that race end in order.
 * Real PostgreSQL, MinIO and RabbitMQ; only the render worker is stubbed.
 */
class PublishScopeScenarioTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var releases: ReleaseService
    @Autowired lateinit var processor: DeploymentProcessor

    private val held = mutableListOf<ScopeLease>()
    @AfterEach fun tidy() { held.forEach { it.release() }; held.clear() }

    private var n = 0
    private fun Scenario.setHero(value: String) = s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"$value"}]}""")
    private fun Scenario.start() = s.body(s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":${revision()}}""", "Idempotency-Key" to "scope-${System.nanoTime()}-${n++}")).get("id").asString()
    private fun Scenario.status(id: String) = s.body(s.get("$base/deployments/$id"))
    private fun Scenario.settle(id: String, seconds: Long = 30) = await().atMost(Duration.ofSeconds(seconds)).until { status(id).get("status").asString() in setOf("RUNNING", "FAILED") }.let { status(id) }

    private fun pointer(sc: Scenario) = jdbc.queryForObject("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId)
    private fun events(id: String, status: String) = jdbc.queryForObject("SELECT count(*) FROM deployment_events WHERE deployment_id = ?::uuid AND status = ?", Int::class.java, id, status)!!
    private fun scopeOf(sc: Scenario) = releases.scopeOf(sc.projectId)
    private fun siteRow(sc: Scenario) = jdbc.queryForMap("SELECT current_deployment_id, active_seq, active_operation_id, lease_operation_id FROM sites WHERE project_id = ?", sc.projectId)

    /** an external operation owns the scope (an unpublish, here); the site row exists once the first release was made */
    private fun occupy(sc: Scenario): ScopeLease = (guard.acquire(ScopeRequest(scopeOf(sc), ReleaseOperation.UNPUBLISH, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease.also { held += it }

    @Test
    fun `a publish that finds the scope owned does not fail - it waits, writes one SCOPE_BUSY event, and completes once the scope is free`() {
        val sc = scenario(); sc.setHero("Bản một"); sc.settle(sc.start())                          // the site row exists
        val blocker = occupy(sc)
        sc.setHero("Bản hai")
        val id = sc.start()
        await().atMost(Duration.ofSeconds(10)).until { events(id, "SCOPE_BUSY") == 1 }
        Thread.sleep(1_200)
        val waiting = sc.status(id)
        assertThat(waiting.get("status").asString()).isEqualTo("DEPLOYING")                        // not failed, not running
        assertThat(events(id, "SCOPE_BUSY")).isEqualTo(1)                                         // one event, not one per attempt
        blocker.release()
        val done = sc.settle(id)
        assertThat(done.get("status").asString()).describedAs(done.toString()).isEqualTo("RUNNING")
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(id))
        assertThat(siteRow(sc)["lease_operation_id"]).isNull()
    }

    @Test
    fun `a publish that waits longer than allowed ends FAILED with SCOPE_BUSY, and the site is untouched`() {
        val sc = scenario(); sc.setHero("Bản một"); val first = sc.start(); sc.settle(first)
        occupy(sc)
        sc.setHero("Bản hai")
        val id = sc.start()
        val done = sc.settle(id, 40)
        assertThat(done.get("status").asString()).isEqualTo("FAILED")
        assertThat(done.get("error").asString()).startsWith("[SCOPE_BUSY]")
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(first))
    }

    @Test
    fun `a publish overtaken by a newer intent fails explicitly as STALE_PUBLISH and never moves the pointer`() {
        val sc = scenario(); sc.setHero("Bản một"); val first = sc.start(); sc.settle(first)
        // a rollback / unpublish with a NEWER activation number than anything this publish can draw has already moved the pointer
        jdbc.update("UPDATE sites SET active_seq = active_seq + 100000, active_operation_id = ? WHERE project_id = ?", UUID.randomUUID(), sc.projectId)
        sc.setHero("Bản hai"); val id = sc.start()
        val done = sc.settle(id)
        assertThat(done.get("status").asString()).describedAs(done.toString()).isEqualTo("FAILED")
        assertThat(done.get("error").asString()).startsWith("[STALE_PUBLISH]")                    // permanent: not retried, not "waiting"
        assertThat(events(id, "STALE_PUBLISH")).isEqualTo(1); assertThat(events(id, "SCOPE_BUSY")).isZero()
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(first))
        assertThat(siteRow(sc)["lease_operation_id"]).isNull()                                     // the loser did not keep the scope
    }

    @Test
    fun `a deployment whose number is below the pointer's fails STALE_PUBLISH through the whole pipeline`() {
        val sc = scenario(); sc.setHero("Bản một"); val first = sc.start(); sc.settle(first)
        sc.setHero("Bản hai"); val id = sc.start(); sc.settle(id)
        // craft the late arrival: a queued deployment that drew its number BEFORE the pointer moved, delivered only now
        val lateId = UUID.randomUUID()
        jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, activation_seq)
            SELECT ?, workspace_id, project_id, version_id, requested_by, visibility, 'QUEUED', provider, ? FROM deployments WHERE id = ?::uuid""", lateId, -(Math.abs(UUID.randomUUID().leastSignificantBits % 1_000_000_000L) + 1), id)
        processor.process(lateId)
        val row = jdbc.queryForMap("SELECT status, error FROM deployments WHERE id = ?", lateId)
        assertThat(row["status"]).isEqualTo("FAILED"); assertThat(row["error"].toString()).startsWith("[STALE_PUBLISH]")
        assertThat(events(lateId.toString(), "STALE_PUBLISH")).isEqualTo(1)
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(id)); assertThat(siteRow(sc)["lease_operation_id"]).isNull()
    }

    @Test
    fun `a worker that died in DEPLOYING is taken over - the redelivered deployment resumes under a new fencing token and completes once`() {
        val sc = scenario(); sc.setHero("Bản một"); val first = sc.start(); sc.settle(first)
        val deadId = UUID.randomUUID()
        jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id)
            SELECT ?, workspace_id, project_id, version_id, requested_by, visibility, 'DEPLOYING', provider, artifact_id FROM deployments WHERE id = ?::uuid""", deadId, first)
        // the dead worker held the scope for this deployment; its lease ran out
        val seq = jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, deadId)!!
        val dead = (guard.acquire(ScopeRequest(scopeOf(sc), ReleaseOperation.PUBLISH, deadId, deadId, seq)) as ScopeAcquisition.Acquired).lease.also { held += it }
        jdbc.update("UPDATE sites SET lease_until = now() - interval '1 second' WHERE project_id = ?", sc.projectId)
        processor.process(deadId)                                                                 // the sweeper redelivers it
        val row = jdbc.queryForMap("SELECT status, error FROM deployments WHERE id = ?", deadId)
        assertThat(row["status"]).describedAs(row.toString()).isEqualTo("RUNNING")
        assertThat(pointer(sc)).isEqualTo(deadId)
        assertThat(jdbc.queryForObject("SELECT fence_counter FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)!!).isGreaterThan(dead.fenceToken)
        assertThat(dead.fence.commit(deadId)).isFalse()                                          // the worker that "died" is fenced if it ever wakes up
        assertThat(siteRow(sc)["lease_operation_id"]).isNull()
    }

    @Test
    fun `publishes that race end in order - the pointer is never older than the newest activated intent, nothing is lost or doubled`() {
        repeat(3) { round ->
            val sc = scenario(); sc.setHero("Bản một"); sc.settle(sc.start())
            sc.setHero("Bản hai"); val a = sc.start()
            sc.setHero("Bản ba"); val b = sc.start()
            val ra = sc.settle(a, 60); val rb = sc.settle(b, 60)
            val seqA = jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?::uuid", Long::class.java, a)!!
            val seqB = jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?::uuid", Long::class.java, b)!!
            assertThat(seqB).isGreaterThan(seqA)
            // either both activated in order, or the older one was overtaken: never the other way round
            assertThat(rb.get("status").asString()).describedAs("round $round: ${rb}").isEqualTo("RUNNING")
            if (ra.get("status").asString() == "FAILED") assertThat(ra.get("error").asString()).startsWith("[STALE_PUBLISH]")
            assertThat(pointer(sc)).isEqualTo(UUID.fromString(b))
            assertThat(siteRow(sc)["active_seq"]).isEqualTo(seqB)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ? AND status = 'RUNNING'", Int::class.java, sc.projectId)!!).isBetween(2, 3)
            assertThat(siteRow(sc)["lease_operation_id"]).isNull()
        }
    }
}
