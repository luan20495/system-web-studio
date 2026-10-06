package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Rollback and unpublish through the HTTP API: they take the same release scope as a publish (busy = 409 SCOPE_BUSY), may carry the release the client
 * expects (409 ROLLBACK_STALE) and an Idempotency-Key whose retries converge on one effect. Releases are real (artifact objects in MinIO, verified by the
 * real verifier); only the render worker is absent because nothing here builds.
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.render.url=http://127.0.0.1:9", "app.deploy.scope-duplicate-wait-seconds=3"])
class SiteOperationApiTests : IntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var releases: ReleaseService
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var store: ArtifactStore

    private val held = mutableListOf<ScopeLease>()
    @AfterEach fun tidy() { held.forEach { it.release() } ; held.clear() }

    private class Rel(val id: UUID, val artifact: UUID, val keys: List<String>)
    private fun scopeOf(sc: Scenario) = releases.scopeOf(sc.projectId)

    /** a real, verifiable release: a RUNNING deployment with an artifact whose files are in the store */
    private fun release(sc: Scenario, body: String = "<h1>x</h1>"): Rel {
        sites.ensureSlug(sc.projectId, "Ops")
        val bytes = (body + UUID.randomUUID()).toByteArray()
        val files = listOf(ManifestFile("index.html", bytes.size, StaticSiteBuilder.sha256(bytes), "text/html; charset=utf-8"))
        val sha = StaticSiteBuilder.sha256(json.writeValueAsString(files).toByteArray()); val prefix = "${sc.projectId}/$sha"
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        store.putOnce("$prefix/index.html", bytes, "text/html")
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,?,CAST(? AS jsonb))", art, sc.projectId, sha, prefix, bytes.size, json.writeValueAsString(files))
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?)", dep, sc.ws, sc.projectId, version, sc.user.id, art)
        return Rel(dep, art, listOf("$prefix/index.html"))
    }

    private fun activate(sc: Scenario, r: Rel) {
        jdbc.update("""UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = (SELECT activation_seq FROM deployments WHERE id = ?), active_operation_id = ?
            WHERE project_id = ?""", r.id, r.id, r.id, sc.projectId)
    }

    /** DELETE with an Idempotency-Key (ApiSession.delete has no headers, and the shared test support is not ours to change) */
    private fun delete(sc: Scenario, key: String) = sc.s.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("${sc.base}/site").header("Idempotency-Key", key))

    private fun pointer(sc: Scenario) = jdbc.queryForObject("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId)
    private fun version(sc: Scenario) = jdbc.queryForObject("SELECT pointer_version FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)!!
    private fun statusOf(id: UUID) = jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, id)!!
    private fun eventCount(id: UUID, status: String) = jdbc.queryForObject("SELECT count(*) FROM deployment_events WHERE deployment_id = ? AND status = ?", Int::class.java, id, status)!!
    private fun rollbackBody(to: Rel, expected: Rel? = null) = """{"deploymentId":"${to.id}"${expected?.let { ""","expectedActiveDeploymentId":"${it.id}"""" } ?: ""}}"""
    private fun hold(sc: Scenario, kind: ReleaseOperation = ReleaseOperation.PUBLISH, op: UUID = UUID.randomUUID()): ScopeLease {
        val req = if (kind == ReleaseOperation.PUBLISH) { val d = release(sc); ScopeRequest(scopeOf(sc), kind, op, d.id, jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, d.id)!!) } else ScopeRequest(scopeOf(sc), kind, op)
        return (guard.acquire(req) as ScopeAcquisition.Acquired).lease.also { held += it }
    }
    private fun expire(sc: Scenario) { jdbc.update("UPDATE sites SET lease_until = now() - interval '1 second' WHERE project_id = ?", sc.projectId) }

    // ------------------------------------------------------------------ the same guard for all three operations

    @Test
    fun `rollback while a publish owns the scope is 409 SCOPE_BUSY with Retry-After and the holder - nothing changes, and it works once the scope is free`() {
        val sc = scenario(); val older = release(sc); val newer = release(sc); activate(sc, newer)
        val publish = hold(sc, ReleaseOperation.PUBLISH)
        val r = sc.s.post("${sc.base}/site/rollback", rollbackBody(older))
        assertThat(r.response.status).isEqualTo(409)
        val body = sc.s.body(r)
        assertThat(body.get("code").asString()).isEqualTo("SCOPE_BUSY")
        assertThat(r.response.getHeader("Retry-After")).isNotBlank()
        assertThat(body.toString()).contains("PUBLISH")                                // who holds it
        assertThat(pointer(sc)).isEqualTo(newer.id); assertThat(statusOf(newer.id)).isEqualTo("RUNNING")
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("operation").get("kind").asString()).isEqualTo("PUBLISH")      // visible in SiteInfo
        publish.release()
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(older)).response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(older.id)
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("operation").isNull).isTrue()
    }

    @Test
    fun `unpublish while a rollback or a publish owns the scope is 409 SCOPE_BUSY and leaves the site online`() {
        val sc = scenario(); val r = release(sc); activate(sc, r)
        for (kind in listOf(ReleaseOperation.PUBLISH, ReleaseOperation.ROLLBACK)) {
            val lease = hold(sc, kind)
            val resp = sc.s.delete("${sc.base}/site")
            assertThat(resp.response.status).describedAs("held by $kind").isEqualTo(409)
            assertThat(sc.s.body(resp).get("code").asString()).isEqualTo("SCOPE_BUSY")
            assertThat(pointer(sc)).isEqualTo(r.id)
            lease.release()
        }
        assertThat(sc.s.delete("${sc.base}/site").response.status).isEqualTo(200); assertThat(pointer(sc)).isNull()
    }

    @Test
    fun `publish, rollback and unpublish exclude each other - whichever holds the scope, the other two are refused`() {
        val sc = scenario(); val r = release(sc); activate(sc, r)
        for (holder in ReleaseOperation.entries) {
            val lease = hold(sc, holder)
            for (other in ReleaseOperation.entries) {
                val req = if (other == ReleaseOperation.PUBLISH) { val d = release(sc); ScopeRequest(scopeOf(sc), other, d.id, d.id, jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, d.id)!!) } else ScopeRequest(scopeOf(sc), other, UUID.randomUUID())
                assertThat(guard.acquire(req)).describedAs("$holder holds, $other asks").isInstanceOf(ScopeAcquisition.Busy::class.java)
            }
            lease.release()
        }
    }

    // ------------------------------------------------------------------ the release the client expects

    @Test
    fun `a rollback that expected another active release is 409 ROLLBACK_STALE and changes nothing - the right expectation goes through`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); val c = release(sc); activate(sc, c)
        val stale = sc.s.post("${sc.base}/site/rollback", rollbackBody(a, expected = b))                    // the client thinks b is active; c is
        assertThat(stale.response.status).isEqualTo(409); assertThat(sc.s.body(stale).get("code").asString()).isEqualTo("ROLLBACK_STALE")
        assertThat(pointer(sc)).isEqualTo(c.id); assertThat(statusOf(c.id)).isEqualTo("RUNNING")
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(a, expected = c)).response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(a.id)
        // a retry whose expectation is now out of date, for a release that IS active: it already happened, so it answers 200 and changes nothing
        val v = version(sc)
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(a, expected = c)).response.status).isEqualTo(200)
        assertThat(version(sc)).isEqualTo(v)
    }

    @Test
    fun `unpublish with the wrong expected release is refused, with the right one it works, and a second unpublish is a no-op`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        assertThat(sc.s.delete("${sc.base}/site?expectedActiveDeploymentId=${a.id}").response.status).isEqualTo(409)
        assertThat(pointer(sc)).isEqualTo(b.id)
        assertThat(sc.s.delete("${sc.base}/site?expectedActiveDeploymentId=${b.id}").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isNull(); val v = version(sc)
        assertThat(sc.s.delete("${sc.base}/site?expectedActiveDeploymentId=${b.id}").response.status).isEqualTo(200)      // already offline: nothing to do, whatever was expected
        assertThat(version(sc)).isEqualTo(v); assertThat(sc.auditCount("SITE_UNPUBLISHED")).isEqualTo(1)
        assertThat(statusOf(b.id)).isEqualTo("RUNNING")                                                                   // unpublish never touches a deployment's status
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun `the same Idempotency-Key twice is one effect and the same answer - another body with the key is refused`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        val key = "rollback-key-${UUID.randomUUID()}"
        val first = sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key)
        val again = sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key)
        assertThat(first.response.status).isEqualTo(200); assertThat(again.response.status).isEqualTo(200)
        assertThat(sc.s.body(again).get("currentDeploymentId").asString()).isEqualTo(a.id.toString())
        assertThat(eventCount(a.id, "ROLLBACK_OK")).isEqualTo(1); assertThat(sc.auditCount("SITE_ROLLBACK")).isEqualTo(1)
        val other = sc.s.post("${sc.base}/site/rollback", rollbackBody(b), "Idempotency-Key" to key)
        assertThat(other.response.status).isEqualTo(409); assertThat(sc.s.body(other).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(pointer(sc)).isEqualTo(a.id)
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to "short").response.status).isEqualTo(400)
        assertThat(jdbc.queryForObject("SELECT resource_type FROM idempotency_keys WHERE scope_key LIKE ?", String::class.java, "site-op:ROLLBACK:${sc.projectId}:%")).isEqualTo("SITE_OPERATION")
    }

    @Test
    fun `an unpublish retried with its key converges`() {
        val sc = scenario(); val a = release(sc); activate(sc, a)
        val key = "unpublish-key-${UUID.randomUUID()}"
        assertThat(delete(sc, key).response.status).isEqualTo(200)
        assertThat(delete(sc, key).response.status).isEqualTo(200)
        assertThat(pointer(sc)).isNull(); assertThat(sc.auditCount("SITE_UNPUBLISHED")).isEqualTo(1)
    }

    @Test
    fun `a duplicate request of an operation that is still running waits for it, then finds the work done - no second effect`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        val key = "dup-key-${UUID.randomUUID()}"
        // the first request is in flight: it holds the scope under the operation id this key maps to
        val op = releases.operationId(ReleaseOperation.ROLLBACK, sc.projectId, sc.user.id, key, "ROLLBACK|${a.id}|null")
        val first = hold(sc, ReleaseOperation.ROLLBACK, op)
        val t0 = System.nanoTime(); val answer = java.util.concurrent.atomic.AtomicInteger()
        val duplicate = thread { answer.set(sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key).response.status) }
        Thread.sleep(800); assertThat(duplicate.isAlive).describedAs("the duplicate waits, it does not race the original").isTrue()
        // the original finishes its work and lets go
        assertThat(first.fence.commit(a.id)).isTrue(); first.release()
        duplicate.join(10_000)
        assertThat(answer.get()).isEqualTo(200)
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(700)
        assertThat(pointer(sc)).isEqualTo(a.id); assertThat(eventCount(a.id, "ROLLBACK_OK")).isZero()       // the duplicate found it done: it did nothing itself
    }

    @Test
    fun `a duplicate that waits longer than the allowed time is told to retry - 409 SCOPE_BUSY`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        val key = "slow-key-${UUID.randomUUID()}"
        val op = releases.operationId(ReleaseOperation.ROLLBACK, sc.projectId, sc.user.id, key, "ROLLBACK|${a.id}|null")
        hold(sc, ReleaseOperation.ROLLBACK, op)
        val t0 = System.nanoTime()
        val r = sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key)
        assertThat(r.response.status).isEqualTo(409); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("SCOPE_BUSY")
        assertThat((System.nanoTime() - t0) / 1_000_000_000).isBetween(2L, 6L)                               // waited about app.deploy.scope-duplicate-wait-seconds, not for ever
        assertThat(pointer(sc)).isEqualTo(b.id)
    }

    @Test
    fun `two requests with the same key at the same moment - both answer 200, one effect`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(4) { round ->
                val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
                val s2 = sessionFor(sc.user.username)
                val key = "race-key-$round-${UUID.randomUUID()}"
                val go = CountDownLatch(1)
                val results = listOf(sc.s, s2).map { s -> pool.submit<Int> { go.await(); s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key).response.status } }
                go.countDown()
                assertThat(results.map { it.get(30, TimeUnit.SECONDS) }).describedAs("round $round").containsExactly(200, 200)
                assertThat(pointer(sc)).isEqualTo(a.id)
                assertThat(eventCount(a.id, "ROLLBACK_OK")).describedAs("round $round").isEqualTo(1)
                assertThat(statusOf(b.id)).isEqualTo("ROLLED_BACK")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `a retry after the worker died - its lease expired - takes the scope over at once and completes the operation once`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        val key = "takeover-key-${UUID.randomUUID()}"
        val op = releases.operationId(ReleaseOperation.ROLLBACK, sc.projectId, sc.user.id, key, "ROLLBACK|${a.id}|null")
        val dead = hold(sc, ReleaseOperation.ROLLBACK, op)
        expire(sc)                                                                                            // it stopped heartbeating
        val t0 = System.nanoTime()
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key).response.status).isEqualTo(200)
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_500)                                  // did not wait for a dead holder
        assertThat(pointer(sc)).isEqualTo(a.id); assertThat(eventCount(a.id, "ROLLBACK_OK")).isEqualTo(1)
        assertThat(dead.fence.commit(b.id)).isFalse()                                                        // and the dead worker, if it wakes up, is fenced out
        assertThat(pointer(sc)).isEqualTo(a.id)
    }

    @Test
    fun `a retry after a terminal failure runs again and can succeed - the failure left nothing behind`() {
        val sc = scenario(); val a = release(sc); val b = release(sc); activate(sc, b)
        val key = "retry-key-${UUID.randomUUID()}"
        val bytes = store.get(a.keys.single())!!
        store.delete(a.keys.single())                                                                         // the release to go back to has lost its file
        val failed = sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key)
        assertThat(failed.response.status).isEqualTo(409); assertThat(sc.s.body(failed).get("code").asString()).isEqualTo("ROLLBACK_FAILED")
        assertThat(pointer(sc)).isEqualTo(b.id); assertThat(statusOf(b.id)).isEqualTo("RUNNING"); assertThat(statusOf(a.id)).isEqualTo("RUNNING")      // no status moved
        assertThat(jdbc.queryForObject("SELECT lease_operation_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId)).isNull()         // the scope was released
        store.putOnce(a.keys.single(), bytes, "text/html")                                                    // the file is back
        assertThat(sc.s.post("${sc.base}/site/rollback", rollbackBody(a), "Idempotency-Key" to key).response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(a.id)
    }
}
