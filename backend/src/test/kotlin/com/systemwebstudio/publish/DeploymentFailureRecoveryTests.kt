package com.systemwebstudio.publish

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * C2 production-readiness: failure, retry, verification and rollback of the real static pipeline (real Postgres, MinIO and RabbitMQ
 * through Testcontainers; only the render worker is stubbed because it is a separate service).
 * Pure decision logic lives in DeploymentFailureTests / ReleaseDeployerTests; this class proves the same behaviour end to end.
 */
@TestPropertySource(properties = [
    "app.deploy.provider=static", "app.sites.origin=https://sites.example.test", "app.sites.studio-origin=https://studio.example.test",
    "app.render.token=render-test-token", "app.deploy.step-max-attempts=3", "app.deploy.retry-backoff-ms=10",\n    "app.deploy.recovery-interval-ms=600000"
])
class DeploymentFailureRecoveryTests : IntegrationTestBase() {
    @MockitoSpyBean lateinit var store: ArtifactStore
    @org.springframework.beans.factory.annotation.Autowired lateinit var processor: DeploymentProcessor
    @org.springframework.beans.factory.annotation.Autowired lateinit var repo: DeploymentRepository
    /** the real probe needs a public host; here it is a test double that is not configured unless a test says so */
    @MockitoBean lateinit var probe: ReleaseHealthProbe

    companion object {
        /** ok | down (503, transient) | bad (400, permanent) | flaky (503 for the first [flakyFailures] calls, then ok) */
        @Volatile var mode = "ok"
        @Volatile var flakyFailures = 0
        val renderCalls = AtomicInteger()
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/render-site") { ex ->
                val n = renderCalls.incrementAndGet()
                val body = mapper.readTree(ex.requestBody.readBytes())
                val title = body.get("schema").get("sections").firstOrNull { it.get("type").asString() == "Hero" }?.get("props")?.get("title")?.asString() ?: ""
                val status = when (mode) { "down" -> 503; "bad" -> 400; "flaky" -> if (n <= flakyFailures) 503 else 200; else -> 200 }
                val files = linkedMapOf("index.html" to "<!doctype html><html><body><h1>$title</h1></body></html>",
                    "404.html" to "<!doctype html><html><body><h1>404</h1></body></html>")
                val bytes = mapper.writeValueAsBytes(mapOf("files" to files))
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${server.address.port}" } }
    }

    @BeforeEach fun reset() { mode = "ok"; flakyFailures = 0; renderCalls.set(0) }

    private var key = 0
    private fun Scenario.setHero(value: String) =
        s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"$value"}]}""")

    private fun Scenario.publishRaw(idempotencyKey: String = "c2-failure-${System.nanoTime()}-${key++}") =
        s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":${revision()}}""", "Idempotency-Key" to idempotencyKey)

    private fun Scenario.settle(id: String): tools.jackson.databind.JsonNode {
        await().atMost(Duration.ofSeconds(30)).until { s.body(s.get("$base/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }
        return s.body(s.get("$base/deployments/$id"))
    }
    private fun Scenario.publish(expect: String = "RUNNING"): tools.jackson.databind.JsonNode {
        val d = settle(s.body(publishRaw()).get("id").asString())
        assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo(expect)
        return d
    }

    private fun id(d: tools.jackson.databind.JsonNode) = UUID.fromString(d.get("id").asString())
    private fun events(deployment: UUID, status: String) =
        jdbc.queryForObject("SELECT count(*) FROM deployment_events WHERE deployment_id = ? AND status = ?", Int::class.java, deployment, status)!!
    private fun eventMessages(deployment: UUID, status: String): List<String> =
        jdbc.queryForList("SELECT message FROM deployment_events WHERE deployment_id = ? AND status = ? ORDER BY created_at", String::class.java, deployment, status)
    private fun pointer(sc: Scenario): UUID? =
        jdbc.queryForList("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId).firstOrNull()
    private fun artifacts(sc: Scenario) = jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Int::class.java, sc.projectId)!!
    private fun sha(deployment: UUID) = jdbc.queryForObject("SELECT a.sha256 FROM artifacts a JOIN deployments d ON d.artifact_id = a.id WHERE d.id = ?", String::class.java, deployment)!!
    private fun slugOf(d: tools.jackson.databind.JsonNode) = d.get("url").asString().removePrefix("https://sites.example.test/").trimEnd('/')
    private fun served(slug: String) = session().get("/sites/$slug/")

    /** After this call [store] can only "see" objects of the given artifact hashes: everything else looks missing to size(). */
    private fun onlyVisible(vararg hashes: String) {
        Mockito.doAnswer { inv ->
            val k = inv.getArgument<String>(0)
            if (hashes.any { k.contains("/$it/") }) inv.callRealMethod() else null
        }.`when`(store).size(Mockito.anyString())
    }

    @Test
    fun `a permanent render failure fails once with its reason, is not retried and publishes nothing`() {
        val sc = scenario(); mode = "bad"
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[BUILD_FAILED]")
        assertThat(renderCalls.get()).isEqualTo(1)
        assertThat(eventMessages(id(d), "BUILDING").filter { it.startsWith("Retry") }).isEmpty()
        assertThat(pointer(sc)).isNull()
        assertThat(artifacts(sc)).isZero()
    }

    @Test
    fun `a transient render outage is retried a bounded number of times, then fails with the reason and the attempt count`() {
        val sc = scenario(); mode = "down"
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[RENDER_UNAVAILABLE]").contains("3 attempts")
        assertThat(renderCalls.get()).isEqualTo(3)
        assertThat(eventMessages(id(d), "BUILDING").filter { it.startsWith("Retry") }).hasSize(2)
        assertThat(pointer(sc)).isNull()
    }

    @Test
    fun `a transient failure that goes away is retried to success with exactly one artifact and one active release`() {
        val sc = scenario(); mode = "flaky"; flakyFailures = 1
        val d = sc.publish()
        assertThat(renderCalls.get()).isEqualTo(2)
        assertThat(eventMessages(id(d), "BUILDING").filter { it.startsWith("Retry") }).hasSize(1)
        assertThat(artifacts(sc)).isEqualTo(1)
        assertThat(pointer(sc)).isEqualTo(id(d))
        assertThat(served(slugOf(d)).response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ? AND status = 'RUNNING'", Int::class.java, sc.projectId)).isEqualTo(1)
    }

    @Test
    fun `a failed artifact store write fails the build with ARTIFACT_STORE_UNAVAILABLE and leaves the previous release serving`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai")
        // Kotlin declares no checked exceptions, so doThrow(IOException) is rejected by Mockito; the real MinIO client does throw it at runtime.
        Mockito.doAnswer { throw IOException("storage offline") }.`when`(store).putOnce(Mockito.anyString(), Mockito.any(ByteArray::class.java) ?: ByteArray(0), Mockito.anyString())
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[ARTIFACT_STORE_UNAVAILABLE]")
        assertThat(pointer(sc)).isEqualTo(id(first))
        assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, id(first))).isEqualTo("RUNNING")
    }

    private fun siteUpdatedAt(sc: Scenario) = jdbc.queryForObject("SELECT updated_at FROM sites WHERE project_id = ?", java.sql.Timestamp::class.java, sc.projectId)

    @Test
    fun `a release whose artifact cannot be verified is never switched in - the previous release keeps serving and the pointer is never touched`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai")
        onlyVisible(sha(id(first)))                    // the new artifact is written, but cannot be read back: verification must fail BEFORE the switch
        val before = artifacts(sc); val touched = siteUpdatedAt(sc)
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[VERIFICATION_FAILED]").doesNotContain("rollback:")      // nothing was switched, so nothing was rolled back
        assertThat(pointer(sc)).isEqualTo(id(first))
        assertThat(siteUpdatedAt(sc)).isEqualTo(touched)                               // the site row was not written at all: the new release never served a request
        assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(artifacts(sc)).isEqualTo(before + 1)                                // N+1 was built once, nothing was rebuilt
        assertThat(events(id(d), "SWITCH")).isZero(); assertThat(events(id(d), "ROLLBACK_OK")).isZero(); assertThat(events(id(d), "ROLLBACK_FAILED")).isZero()
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, id(first))).isEqualTo("RUNNING")
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("currentDeploymentId").asString()).isEqualTo(id(first).toString())
    }

    @Test
    fun `a first release that fails verification never serves anything - the site stays without a pointer`() {
        val sc = scenario()
        onlyVisible("nothing-is-visible")
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[VERIFICATION_FAILED]")
        assertThat(events(id(d), "SWITCH")).isZero(); assertThat(events(id(d), "ROLLBACK_OFFLINE")).isZero()      // never switched, so nothing to take offline
        assertThat(pointer(sc)).isNull()
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isFalse()
    }

    @Test
    fun `manual rollback to a release whose artifact is gone is refused, recorded, and changes nothing`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai")
        val second = sc.publish()
        onlyVisible(sha(id(second)))                   // release one's files are gone from the store

        val refused = sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${id(first)}"}""")
        assertThat(refused.response.status).isEqualTo(409)
        assertThat(refused.response.contentAsString).contains("ROLLBACK_FAILED")
        assertThat(pointer(sc)).isEqualTo(id(second))
        assertThat(served(slug).response.contentAsString).contains("Bản hai")
        assertThat(events(id(first), "ROLLBACK_FAILED")).isEqualTo(1)                  // the failure has its own recorded state
        assertThat(sc.auditCount("SITE_ROLLBACK")).isZero()
    }

    @Test
    fun `manual rollback is idempotent - repeating it neither rebuilds nor duplicates history nor audit`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai")
        sc.publish()
        val builds = artifacts(sc)
        repeat(3) { assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${id(first)}"}""").response.status).isEqualTo(200) }
        assertThat(pointer(sc)).isEqualTo(id(first))
        assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(artifacts(sc)).isEqualTo(builds)
        assertThat(events(id(first), "ROLLBACK_OK")).isEqualTo(1)
        assertThat(sc.auditCount("SITE_ROLLBACK")).isEqualTo(1)
    }

    @Test
    fun `publishing again with the same Idempotency-Key creates no second deployment and no second build`() {
        val sc = scenario()
        val k = "c2-duplicate-${System.nanoTime()}"
        val a = sc.s.body(sc.publishRaw(k)).get("id").asString()
        val b = sc.s.body(sc.publishRaw(k)).get("id").asString()
        assertThat(b).isEqualTo(a)
        sc.settle(a)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Int::class.java, sc.projectId)).isEqualTo(1)
        assertThat(renderCalls.get()).isEqualTo(1)
    }

    @Test
    fun `an active pointer at a failed deployment is reported offline, never online`() {
        val sc = scenario()
        val ok = sc.publish()
        val failed = jdbc.queryForObject("SELECT id FROM deployments WHERE id = ?", UUID::class.java, id(ok))!!
        jdbc.update("UPDATE deployments SET status = 'FAILED', error = '[DEPLOY_FAILED] test' WHERE id = ?", failed)
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isFalse()
    }

    // ------------------------------------------------------------------ health probe of the public address (test double)

    private fun probeAnswers(answer: com.systemwebstudio.integration.deploy.DeployVerification) {
        Mockito.`when`(probe.configured).thenReturn(true)
        Mockito.`when`(probe.probe(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(answer)
    }

    @Test
    fun `without a public host to probe the release is verified by its pointer and artifact and the detail says the probe did not run`() {
        val sc = scenario(); val d = sc.publish()
        Mockito.verify(probe, Mockito.never()).probe(Mockito.anyString(), Mockito.anyBoolean())
        assertThat(pointer(sc)).isEqualTo(id(d))
    }

    @Test
    fun `an address that answers 5xx after the switch is not RUNNING - the previous release is restored`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai")
        probeAnswers(com.systemwebstudio.integration.deploy.DeployVerification.unhealthy("the address answers HTTP 503"))
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[VERIFICATION_FAILED]").contains("HTTP 503").contains("rollback:")
        assertThat(pointer(sc)).isEqualTo(id(first))
        assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(events(id(d), "ROLLBACK_OK")).isEqualTo(1)
    }

    @Test
    fun `an address that cannot be reached is UNKNOWN, which is not success`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish()
        sc.setHero("Bản hai")
        probeAnswers(com.systemwebstudio.integration.deploy.DeployVerification.unknown("the address did not answer: ConnectException"))
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[DEPLOY_STATE_UNKNOWN]")
        assertThat(pointer(sc)).isEqualTo(id(first))
    }

    // ------------------------------------------------------------------ ROLLING_BACK, fail-closed, ROLLED_BACK (C2_DEPLOY_CONTRACT.md §1.1 / §3)

    private fun statuses(deployment: UUID) = jdbc.queryForList("SELECT status FROM deployment_events WHERE deployment_id = ? ORDER BY created_at, id", String::class.java, deployment)
    private fun statusOf(deployment: UUID) = jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, deployment)!!

    /** every file of the artifact with this hash looks missing to the store; everything else is real */
    private fun hide(sha: String) {
        Mockito.doAnswer { inv -> if (inv.getArgument<String>(0).contains("/$sha/")) null else inv.callRealMethod() }.`when`(store).size(Mockito.anyString())
    }

    @Test
    fun `an unhealthy release is ROLLING_BACK before it is FAILED and never RUNNING - the previous release is back`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); sc.setHero("Bản hai")
        probeAnswers(com.systemwebstudio.integration.deploy.DeployVerification.unhealthy("the address answers HTTP 503"))
        val d = sc.publish(expect = "FAILED")
        assertThat(statuses(id(d))).containsSubsequence("DEPLOYING", "SWITCH", "ROLLING_BACK", "ROLLBACK_OK", "FAILED")
        assertThat(statuses(id(d))).doesNotContain("RUNNING")
        assertThat(jdbc.queryForObject("SELECT previous_deployment_id FROM deployments WHERE id = ?", UUID::class.java, id(d))).isEqualTo(id(first))     // typed state
        assertThat(pointer(sc)).isEqualTo(id(first)); assertThat(statusOf(id(first))).isEqualTo("RUNNING")
    }

    @Test
    fun `automatic rollback that cannot restore fails closed - pointer NULL, deployment FAILED, ROLLBACK_FAILED then ROLLBACK_OFFLINE, nothing served`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first); val firstSha = sha(id(first))
        sc.setHero("Bản hai")
        probeAnswers(com.systemwebstudio.integration.deploy.DeployVerification.unhealthy("the address answers HTTP 503"))
        hide(firstSha)                                                                  // the release to go back to has lost its files
        val d = sc.publish(expect = "FAILED")
        assertThat(d.get("error").asString()).startsWith("[VERIFICATION_FAILED]").contains("rollback:").contains("taken offline")
        assertThat(pointer(sc)).isNull()
        assertThat(statuses(id(d))).containsSubsequence("ROLLING_BACK", "ROLLBACK_FAILED", "ROLLBACK_OFFLINE", "FAILED")
        assertThat(statusOf(id(d))).isEqualTo("FAILED")
        assertThat(served(slug).response.status).isEqualTo(404)                         // not the failed release, not the one that cannot be served
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isFalse()
    }

    /** a deployment that was ROLLING_BACK when its worker died: the pointer is still on it */
    private fun crashedRollingBack(sc: Scenario, previous: UUID?): UUID {
        val template = previous ?: jdbc.queryForObject("SELECT id FROM deployments WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)!!
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id, previous_deployment_id)
            SELECT ?, workspace_id, project_id, version_id, requested_by, visibility, 'ROLLING_BACK', provider, artifact_id, ? FROM deployments WHERE id = ?""", id, previous, template)
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,'ROLLING_BACK','Rolling back: [VERIFICATION_FAILED] The new release is unhealthy: HTTP 503')", UUID.randomUUID(), id)
        jdbc.update("UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = (SELECT activation_seq FROM deployments WHERE id = ?), active_operation_id = ? WHERE project_id = ?", id, id, id, sc.projectId)
        return id
    }

    @Test
    fun `a crash in ROLLING_BACK is resumed - the previous release comes back, the deployment ends FAILED, it never rolls forward`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        val crashed = crashedRollingBack(sc, id(first))
        assertThat(pointer(sc)).isEqualTo(crashed)
        processor.process(crashed)                                                       // the sweeper re-delivers it
        assertThat(statusOf(crashed)).isEqualTo("FAILED")
        assertThat(jdbc.queryForObject("SELECT error FROM deployments WHERE id = ?", String::class.java, crashed)).startsWith("[VERIFICATION_FAILED]").contains("rollback: restored")
        assertThat(pointer(sc)).isEqualTo(id(first))
        assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(statuses(crashed)).containsSubsequence("ROLLING_BACK", "ROLLBACK_OK", "FAILED").doesNotContain("RUNNING", "DEPLOYING")
        processor.process(crashed)                                                       // a second delivery of the same message: nothing changes
        assertThat(statusOf(crashed)).isEqualTo("FAILED"); assertThat(statuses(crashed).count { it == "ROLLBACK_OK" }).isEqualTo(1)
    }

    @Test
    fun `a crash in ROLLING_BACK of a first release ends with the site offline`() {
        val sc = scenario()
        val first = sc.publish()
        jdbc.update("UPDATE deployments SET status = 'FAILED' WHERE id = ?", id(first))      // keep it as a source of artifact and version only
        jdbc.update("UPDATE sites SET current_deployment_id = NULL WHERE project_id = ?", sc.projectId)
        val crashed = crashedRollingBack(sc, null)
        processor.process(crashed)
        assertThat(statusOf(crashed)).isEqualTo("FAILED"); assertThat(pointer(sc)).isNull()
        assertThat(statuses(crashed)).containsSubsequence("ROLLING_BACK", "ROLLBACK_OFFLINE", "FAILED")
    }

    @Test
    fun `the recovery sweeper picks up a deployment that stopped in ROLLING_BACK`() {
        val sc = scenario(); val first = sc.publish()
        val crashed = crashedRollingBack(sc, id(first))
        jdbc.update("UPDATE deployments SET updated_at = now() - interval '10 minutes' WHERE id = ?", crashed)
        assertThat(repo.staleIds(30, 120)).contains(crashed)
        processor.process(crashed)
    }

    @Test
    fun `a manual rollback marks the newer release ROLLED_BACK, which is terminal and no longer restorable - roll forward means publishing again`() {
        val sc = scenario(); sc.setHero("Bản một")
        val first = sc.publish(); val slug = slugOf(first)
        sc.setHero("Bản hai"); val second = sc.publish()
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${id(first)}"}""").response.status).isEqualTo(200)
        assertThat(statusOf(id(second))).isEqualTo("ROLLED_BACK"); assertThat(statusOf(id(first))).isEqualTo("RUNNING")
        assertThat(pointer(sc)).isEqualTo(id(first)); assertThat(served(slug).response.contentAsString).contains("Bản một")
        assertThat(statuses(id(second))).contains("ROLLED_BACK")
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${id(second)}"}""").response.status).isEqualTo(400)      // not restorable
        assertThat(sc.s.body(sc.s.get("${sc.base}/deployments/${id(second)}")).get("status").asString()).isEqualTo("ROLLED_BACK")
        // pollers treat ROLLED_BACK as terminal: the list endpoint still serves it
        assertThat(sc.s.body(sc.s.get("${sc.base}/deployments")).toList().map { it.get("status").asString() }).contains("ROLLED_BACK")
    }

    @Test
    fun `unpublish moves the pointer through the scope, bumps its version and leaves every deployment status alone`() {
        val sc = scenario(); val first = sc.publish()
        val before = jdbc.queryForObject("SELECT pointer_version FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)!!
        assertThat(sc.s.delete("${sc.base}/site").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isNull()
        assertThat(jdbc.queryForObject("SELECT pointer_version FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(before + 1)
        assertThat(statusOf(id(first))).isEqualTo("RUNNING")
        assertThat(jdbc.queryForObject("SELECT lease_operation_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId)).isNull()      // the scope is free again
    }
}
