package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

/**
 * The HTTP contract of publish / site / rollback / unpublish and of the published app's runtime config, pinned as the code really behaves (real
 * controllers, PostgreSQL, MinIO). A change here is a change of a contract that the Studio (C5) and the data runtime consumers (C3) rely on:
 * docs/parallel/c2/PUBLISH_API_CONTRACT.md and docs/parallel/c2/PUBLISHED_RUNTIME_TOPOLOGY.md describe it in prose.
 */
class PublishApiContractTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var releases: ReleaseService
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var store: ArtifactStore

    private val held = mutableListOf<ScopeLease>()
    @AfterEach fun tidy() { held.forEach { it.release() }; held.clear() }

    private fun keys(n: JsonNode) = n.propertyNames().toSet()
    private var counter = 0
    private fun Scenario.setHero(v: String) = s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"$v"}]}""")
    private fun Scenario.publish(key: String = "contract-${System.nanoTime()}-${counter++}", vis: String = "PUBLIC", rev: Long = revision()) =
        s.post("$base/publish", """{"visibility":"$vis","expectedRevision":$rev}""", "Idempotency-Key" to key)
    private fun Scenario.settle(id: String) = await().atMost(Duration.ofSeconds(30)).until { s.body(s.get("$base/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }.let { s.body(s.get("$base/deployments/$id")) }
    private fun site(sc: Scenario) = sc.s.body(sc.s.get("${sc.base}/site"))
    private fun scopeOf(sc: Scenario) = releases.scopeOf(sc.projectId)

    /** a served CODE app release (kind STATIC_APP): the only kind that has a runtime config */
    private class AppRel(val id: UUID, val artifact: UUID)
    private fun appRelease(sc: Scenario): AppRel {
        sites.ensureSlug(sc.projectId, "Contract")
        val bytes = ("<!doctype html><title>app</title>" + UUID.randomUUID()).toByteArray()
        val files = listOf(ManifestFile("index.html", bytes.size, StaticSiteBuilder.sha256(bytes), "text/html; charset=utf-8"))
        val sha = StaticSiteBuilder.sha256(json.writeValueAsString(files).toByteArray()); val prefix = "${sc.projectId}/$sha"
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        store.putOnce("$prefix/index.html", bytes, "text/html")
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, kind, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,'STATIC_APP',?,1,?,CAST(? AS jsonb))", art, sc.projectId, sha, prefix, bytes.size, json.writeValueAsString(files))
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?)", dep, sc.ws, sc.projectId, version, sc.user.id, art)
        return AppRel(dep, art)
    }
    private fun activate(sc: Scenario, r: AppRel) {
        jdbc.update("""UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = (SELECT activation_seq FROM deployments WHERE id = ?), active_operation_id = ? WHERE project_id = ?""", r.id, r.id, r.id, sc.projectId)
    }
    private fun slug(sc: Scenario) = jdbc.queryForObject("SELECT slug FROM sites WHERE project_id = ?", String::class.java, sc.projectId)!!
    private fun get(path: String) = mvc.perform(B.get(path)).andReturn().response

    // ------------------------------------------------------------------ PUBLISH

    @Test
    fun `publish - 202 with the deployment, Idempotency-Key is a required header, a replay is flagged, another body is refused`() {
        val sc = scenario(); sc.setHero("Hello")
        val key = "contract-key-${UUID.randomUUID()}"
        val first = sc.publish(key)
        assertThat(first.response.status).isEqualTo(202)                                                            // accepted: the work is asynchronous
        val d = sc.s.body(first)
        assertThat(keys(d)).containsExactlyInAnyOrder("id", "projectId", "versionId", "versionNumber", "visibility", "status", "url", "error", "provider", "createdAt", "updatedAt", "finishedAt", "events", "mock")
        assertThat(d.get("status").asString()).isIn("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "RUNNING")
        assertThat(d.get("visibility").asString()).isEqualTo("PUBLIC"); assertThat(d.get("versionNumber").isInt).isTrue(); assertThat(d.get("mock").isBoolean).isTrue()
        assertThat(keys(d.get("events").first())).containsExactlyInAnyOrder("status", "message", "createdAt")
        assertThat(first.response.getHeader("Idempotent-Replay")).isNull()

        val replay = sc.publish(key)                                                                                // same key, same payload
        assertThat(replay.response.status).isEqualTo(202); assertThat(replay.response.getHeader("Idempotent-Replay")).isEqualTo("true")
        assertThat(sc.s.body(replay).get("id").asString()).isEqualTo(d.get("id").asString())
        val other = sc.publish(key, vis = "PRIVATE")                                                                // same key, another payload
        assertThat(other.response.status).isEqualTo(409)
        val err = sc.s.body(other)
        assertThat(keys(err)).containsExactlyInAnyOrder("code", "message", "requestId", "details")                  // the standard envelope: no `retryable` field
        assertThat(err.get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(err.get("message").asString()).isEqualTo("This Idempotency-Key was already used with a different request")

        val noKey = sc.s.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":${sc.revision()}}""")
        assertThat(noKey.response.status).isEqualTo(400); assertThat(sc.s.body(noKey).get("code").asString()).isEqualTo("MISSING_HEADER")
        assertThat(sc.s.body(sc.publish("short")).get("code").asString()).isEqualTo("INVALID_IDEMPOTENCY_KEY")
        assertThat(sc.s.body(sc.s.post("${sc.base}/publish", """{"visibility":"WORLD"}""", "Idempotency-Key" to "contract-key-validation-1")).get("code").asString()).isEqualTo("VALIDATION_FAILED")
        assertThat(sc.s.body(sc.publish("contract-key-stale-rev-1", rev = sc.revision() + 7)).get("code").asString()).isEqualTo("REVISION_CONFLICT")

        val done = sc.settle(d.get("id").asString())                                                                // poll the same resource
        assertThat(done.get("status").asString()).isEqualTo("RUNNING"); assertThat(done.get("url").asString()).startsWith("https://sites.example.test/")
    }

    @Test
    fun `the deployment status values, and which of them are terminal, busy and served`() {
        val all = jdbc.queryForList("SELECT unnest(ARRAY['QUEUED','POLICY_CHECK','SECURITY_CHECK','BUILDING','DEPLOYING','ROLLING_BACK','RUNNING','FAILED','ROLLED_BACK'])", String::class.java)
        assertThat(DeploymentStatus.terminal).containsExactlyInAnyOrder("RUNNING", "FAILED", "ROLLED_BACK")
        assertThat(DeploymentStatus.inProgress).containsExactlyInAnyOrder("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "ROLLING_BACK")
        val sc = scenario(); sites.ensureSlug(sc.projectId, "Served"); val slug = slug(sc)
        val servable = all.filter { st ->
            val r = appRelease(sc); jdbc.update("UPDATE deployments SET status = ? WHERE id = ?", st, r.id); activate(sc, r); sites.live(slug) != null
        }
        assertThat(servable).containsExactlyInAnyOrder("DEPLOYING", "RUNNING")                                      // a pointer at anything else serves nothing
        // the CHECK constraint is the closed list: ROLLBACK_FAILED / ROLLBACK_OFFLINE are events, never statuses
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'deployments_status_check'", String::class.java)).contains("ROLLING_BACK").doesNotContain("ROLLBACK_")
        assertThat(ReleaseOperation.entries.map { it.name }).containsExactly("PUBLISH", "ROLLBACK", "UNPUBLISH")
    }

    // ------------------------------------------------------------------ SITE INFO

    @Test
    fun `GET site - the exact shape, pointerVersion is an integer that moves with the pointer, operation is null when idle and an object while a scope is held`() {
        val sc = scenario(); val r = appRelease(sc); activate(sc, r)
        val idle = site(sc)
        assertThat(keys(idle)).containsExactlyInAnyOrder("slug", "url", "online", "visibility", "currentDeploymentId", "currentVersionNumber", "provider", "updatedAt", "pointerVersion", "operation")
        assertThat(idle.get("pointerVersion").isIntegralNumber).isTrue(); assertThat(idle.get("pointerVersion").asLong()).isGreaterThanOrEqualTo(1)
        assertThat(idle.has("operation")).isTrue(); assertThat(idle.get("operation").isNull).isTrue()               // idle = explicit null, the field is present
        assertThat(idle.get("online").asBoolean()).isTrue(); assertThat(idle.get("currentDeploymentId").asString()).isEqualTo(r.id.toString())

        val lease = (guard.acquire(ScopeRequest(scopeOf(sc), ReleaseOperation.ROLLBACK, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease.also { held += it }
        val busy = site(sc).get("operation")
        assertThat(keys(busy)).containsExactlyInAnyOrder("kind", "deploymentId", "since", "leaseUntil")
        assertThat(busy.get("kind").asString()).isEqualTo("ROLLBACK"); assertThat(busy.get("deploymentId").isNull).isTrue()      // only a PUBLISH names its deployment
        jdbc.update("UPDATE sites SET lease_until = now() - interval '1 second' WHERE project_id = ?", sc.projectId)
        assertThat(site(sc).get("operation").isNull).describedAs("an expired lease is not an operation").isTrue()
        lease.release()
        val none = scenario(); assertThat(keys(none.s.body(none.s.get("${none.base}/site")))).contains("pointerVersion", "operation")      // a project that was never published
    }

    // ------------------------------------------------------------------ ERRORS OF ROLLBACK / UNPUBLISH

    @Test
    fun `SCOPE_BUSY ROLLBACK_STALE and the rollback errors - HTTP status, envelope, details and Retry-After`() {
        val sc = scenario(); val a = appRelease(sc); val b = appRelease(sc); val c = appRelease(sc); activate(sc, c)
        val body = { to: AppRel, expected: AppRel? -> """{"deploymentId":"${to.id}"${expected?.let { ""","expectedActiveDeploymentId":"${it.id}"""" } ?: ""}}""" }

        val lease = (guard.acquire(ScopeRequest(scopeOf(sc), ReleaseOperation.UNPUBLISH, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease.also { held += it }
        val busy = sc.s.post("${sc.base}/site/rollback", body(a, null))
        assertThat(busy.response.status).isEqualTo(409); assertThat(busy.response.getHeader("Retry-After")).isEqualTo("5")
        val e = sc.s.body(busy)
        assertThat(keys(e)).containsExactlyInAnyOrder("code", "message", "requestId", "details"); assertThat(e.get("code").asString()).isEqualTo("SCOPE_BUSY")
        assertThat(e.get("message").asString()).startsWith("Another release operation (UNPUBLISH) is running for this app")
        assertThat(keys(e.get("details"))).containsExactlyInAnyOrder("appId", "environment", "operation")
        assertThat(e.get("details").get("environment").asString()).isEqualTo("PRODUCTION"); assertThat(e.get("details").get("appId").asString()).isEqualTo(sc.projectId.toString())
        assertThat(keys(e.get("details").get("operation"))).containsExactlyInAnyOrder("kind", "deploymentId", "since", "leaseUntil")
        val busyDelete = sc.s.delete("${sc.base}/site")
        assertThat(busyDelete.response.status).isEqualTo(409); assertThat(sc.s.body(busyDelete).get("code").asString()).isEqualTo("SCOPE_BUSY")
        lease.release()

        val stale = sc.s.post("${sc.base}/site/rollback", body(a, b))                                                  // expects b, c is active
        assertThat(stale.response.status).isEqualTo(409); assertThat(stale.response.getHeader("Retry-After")).isNull()
        val s = sc.s.body(stale)
        assertThat(s.get("code").asString()).isEqualTo("ROLLBACK_STALE")
        assertThat(s.get("message").asString()).isEqualTo("The active release is not the one this request expected; reload and decide again")
        assertThat(keys(s.get("details"))).containsExactlyInAnyOrder("activeDeploymentId", "expectedActiveDeploymentId")
        assertThat(s.get("details").get("activeDeploymentId").asString()).isEqualTo(c.id.toString())

        val unpublishStale = sc.s.delete("${sc.base}/site?expectedActiveDeploymentId=${a.id}")
        assertThat(unpublishStale.response.status).isEqualTo(409); assertThat(sc.s.body(unpublishStale).get("code").asString()).isEqualTo("ROLLBACK_STALE")

        val notRestorable = sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${UUID.randomUUID()}"}""")
        assertThat(notRestorable.response.status).isEqualTo(400); assertThat(sc.s.body(notRestorable).get("code").asString()).isEqualTo("DEPLOYMENT_NOT_RESTORABLE")
        assertThat(sc.s.body(sc.s.post("${sc.base}/site/rollback", "{}")).get("code").asString()).isEqualTo("VALIDATION_FAILED")

        store.delete(a.let { jdbc.queryForObject("SELECT storage_prefix FROM artifacts WHERE id = ?", String::class.java, it.artifact) }.let { "$it/index.html" })
        val failed = sc.s.post("${sc.base}/site/rollback", body(a, null))
        assertThat(failed.response.status).isEqualTo(409); assertThat(sc.s.body(failed).get("code").asString()).isEqualTo("ROLLBACK_FAILED")
        assertThat(sc.s.body(failed).get("message").asString()).startsWith("The release could not be restored: ")
    }

    // ------------------------------------------------------------------ ROLLBACK / UNPUBLISH AND THE PUBLISHED APP

    @Test
    fun `rollback and unpublish seen by the published code app - runtime config, headers, which fields move`() {
        val sc = scenario(); val older = appRelease(sc); val newer = appRelease(sc); activate(sc, newer); val slug = slug(sc)
        val configPath = "/sites/$slug/__factory/config.json"

        val before = get(configPath)
        assertThat(before.status).isEqualTo(200)
        assertThat(before.contentType).isEqualTo("application/json")
        assertThat(before.getHeader("Cache-Control")).isEqualTo("no-store, no-transform")                            // the runtime config is never cached
        assertThat(before.getHeader("Access-Control-Allow-Origin")).isEqualTo("*")
        val cfg = json.readTree(before.contentAsString)
        assertThat(keys(cfg)).containsExactlyInAnyOrder("appId", "appName", "environment", "visibility", "version", "user", "flags", "apiBase", "releaseId", "generatedAt")
        assertThat(cfg.get("appId").asString()).isEqualTo(sc.projectId.toString()); assertThat(cfg.get("releaseId").asString()).isEqualTo(newer.id.toString())
        assertThat(cfg.get("environment").asString()).isEqualTo("production"); assertThat(cfg.get("visibility").asString()).isEqualTo("PUBLIC")
        assertThat(cfg.get("user").isNull).isTrue(); assertThat(cfg.get("flags").isObject).isTrue()
        assertThat(cfg.get("apiBase").asString()).isEqualTo("https://data.dev.example.test/api/v1")                  // the value of app.sites.data-api-base of THIS test context
        assertThat(before.contentAsString).doesNotContain("token", "secret", "password", "Authorization")            // nothing credential-like is ever in the config

        // the app page is served in a sandbox that can only talk to its own origin
        val page = get("/sites/$slug/")
        assertThat(page.status).isEqualTo(200)
        val csp = page.getHeader("Content-Security-Policy")!!
        assertThat(csp).contains("sandbox allow-scripts").doesNotContain("allow-same-origin").contains("connect-src 'self' https://sites.example.test")

        // rollback: only the release moves
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${older.id}"}""", "Idempotency-Key" to "contract-rollback-${UUID.randomUUID()}").response.status).isEqualTo(200)
        val after = json.readTree(get(configPath).contentAsString)
        assertThat(after.get("releaseId").asString()).isEqualTo(older.id.toString())
        listOf("appId", "appName", "environment", "visibility", "apiBase").forEach { assertThat(after.get(it)).describedAs(it).isEqualTo(cfg.get(it)) }      // unchanged
        assertThat(site(sc).get("currentDeploymentId").asString()).isEqualTo(older.id.toString())

        // unpublish: nothing is served, config included; the answer is the site's own 404 page
        val unpublished = sc.s.delete("${sc.base}/site")
        assertThat(unpublished.response.status).isEqualTo(200)
        val info = sc.s.body(unpublished)
        assertThat(info.get("online").asBoolean()).isFalse(); assertThat(info.get("currentDeploymentId").isNull).isTrue(); assertThat(info.get("slug").asString()).isEqualTo(slug)
        val gone = get(configPath); assertThat(gone.status).isEqualTo(404); assertThat(gone.contentType).startsWith("text/html")
        assertThat(get("/sites/$slug/").status).isEqualTo(404)
        val v = info.get("pointerVersion").asLong()
        assertThat(sc.s.body(sc.s.delete("${sc.base}/site")).get("pointerVersion").asLong()).describedAs("already offline: nothing written").isEqualTo(v)
        // and a rollback brings the older release back with no rebuild
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${older.id}"}""").response.status).isEqualTo(200)
        assertThat(json.readTree(get(configPath).contentAsString).get("releaseId").asString()).isEqualTo(older.id.toString())
    }
}
