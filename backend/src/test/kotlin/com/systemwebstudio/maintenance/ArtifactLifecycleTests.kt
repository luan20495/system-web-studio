package com.systemwebstudio.maintenance

import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.StaticSiteBuilder
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.io.IOException
import java.time.Duration
import java.util.UUID

/**
 * Artifact lifecycle against real Postgres and MinIO: which artifacts retention may delete and which it never touches (the served one, the
 * rollback target, anything a deployment or the server runtime still uses, anything inside the retention window), what happens when the store
 * is missing objects or fails halfway, and how objects that no record owns are found and reclaimed.
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.render.url=http://127.0.0.1:9"])
class ArtifactLifecycleTests : IntegrationTestBase() {
    @Autowired lateinit var retention: ArtifactRetentionService
    @MockitoSpyBean lateinit var store: ArtifactStore

    @AfterEach fun reset() { jdbc.update("DELETE FROM system_settings WHERE key LIKE 'retention.%'"); settingsService.invalidate() }

    private class Art(val id: UUID, val prefix: String, val sha: String) { val key get() = "$prefix/index.html" }

    private fun sha() = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    /** a recorded artifact; [objects] = its index.html is really in the store; [ageHours] = how long ago it was created */
    private fun artifact(sc: Scenario, ageHours: Long = 3, objects: Boolean = true, deleted: Boolean = false): Art {
        val sha = sha(); val id = UUID.randomUUID(); val prefix = "${sc.projectId}/$sha"
        jdbc.update("""INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest, created_at, deleted_at)
            VALUES (?,?,?,?,1,5,CAST(? AS jsonb), now() - make_interval(hours => ?), CASE WHEN ? THEN now() ELSE NULL END)""",
            id, sc.projectId, sha, prefix, """[{"path":"index.html","size":5,"sha256":"x","contentType":"text/html"}]""", ageHours.toInt(), deleted)
        if (objects) store.putOnce("$prefix/index.html", "hello".toByteArray(), "text/html")
        return Art(id, prefix, sha)
    }

    private fun deployment(sc: Scenario, art: Art, status: String, minutesAgo: Long): UUID {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id, created_at)
            VALUES (?,?,?,?,?,'PUBLIC',?,'static',?, now() - make_interval(mins => ?))""", id, sc.ws, sc.projectId, version, sc.user.id, status, art.id, minutesAgo.toInt())
        return id
    }

    private fun serve(sc: Scenario, deployment: UUID) {
        jdbc.update("INSERT INTO sites (project_id, slug) VALUES (?, ?) ON CONFLICT (project_id) DO NOTHING", sc.projectId, "lc-${sc.projectId.toString().take(8)}")
        jdbc.update("UPDATE sites SET current_deployment_id = ? WHERE project_id = ?", deployment, sc.projectId)
    }

    private fun candidates(sc: Scenario) = retention.candidates().filter { it["project_id"] == sc.projectId }.map { it["id"] as UUID }.toSet()
    private fun alive(sc: Scenario) = jdbc.queryForList("SELECT id FROM artifacts WHERE project_id = ? AND deleted_at IS NULL", UUID::class.java, sc.projectId).toSet()
    private fun rollbackWindow(n: Int) { jdbc.update("INSERT INTO system_settings (key, value) VALUES ('retention.rollback-deployments', ?)", n.toString()); settingsService.invalidate() }

    @Test
    fun `the served artifact and its rollback target are protected even when the rollback window is one`() {
        val sc = scenario(); rollbackWindow(1)
        val oldest = artifact(sc); val previous = artifact(sc); val served = artifact(sc)
        deployment(sc, oldest, "RUNNING", 90); deployment(sc, previous, "RUNNING", 60); serve(sc, deployment(sc, served, "RUNNING", 30))
        // window 1 alone would keep only the newest; the release right before the served one is the rollback target and stays too
        assertThat(candidates(sc)).containsExactly(oldest.id)
        retention.run(dryRun = false)
        assertThat(alive(sc)).containsExactlyInAnyOrder(previous.id, served.id)
        assertThat(store.exists(served.key)).isTrue(); assertThat(store.exists(previous.key)).isTrue(); assertThat(store.exists(oldest.key)).isFalse()
    }

    @Test
    fun `after a manual rollback the served release, the one before it and the newest are all kept`() {
        val sc = scenario(); rollbackWindow(1)
        val first = artifact(sc); val second = artifact(sc); val third = artifact(sc)
        deployment(sc, first, "RUNNING", 90); val d2 = deployment(sc, second, "RUNNING", 60); deployment(sc, third, "RUNNING", 30)
        serve(sc, d2)                                                      // manual rollback: release 2 is served, 3 is the newest
        assertThat(candidates(sc)).isEmpty()      // 2 is served, 1 is its rollback target, 3 is the newest (window 1)
        retention.run(dryRun = false)
        assertThat(alive(sc)).containsExactlyInAnyOrder(first.id, second.id, third.id)
    }

    @Test
    fun `an artifact used by a deployment that is still being built or published, or by the server runtime, is never deleted`() {
        val sc = scenario(); rollbackWindow(1)
        val building = artifact(sc); val deploying = artifact(sc); val runtimeNow = artifact(sc); val runtimeNext = artifact(sc)
        deployment(sc, building, "BUILDING", 600); deployment(sc, deploying, "DEPLOYING", 600)
        val sdNow = UUID.randomUUID(); val sdNext = UUID.randomUUID()
        jdbc.update("""INSERT INTO app_runtimes (project_id, db_name, db_role, db_password_enc, app_token_enc, app_token_hash)
            VALUES (?,?,?,'x','x',?)""", sc.projectId, "db_${sha().take(20)}", "role_${sha().take(20)}", sha())
        jdbc.update("INSERT INTO server_deployments (id, project_id, version, artifact_id, status) VALUES (?,?,1,?,'RUNNING'), (?,?,2,?,'STARTING')",
            sdNow, sc.projectId, runtimeNow.id, sdNext, sc.projectId, runtimeNext.id)
        jdbc.update("UPDATE app_runtimes SET current_deployment_id = ?, desired_deployment_id = ? WHERE project_id = ?", sdNow, sdNext, sc.projectId)
        assertThat(candidates(sc)).isEmpty()
        retention.run(dryRun = false)
        assertThat(alive(sc)).hasSize(4)
        listOf(building, deploying, runtimeNow, runtimeNext).forEach { assertThat(store.exists(it.key)).isTrue() }
    }

    @Test
    fun `an artifact inside the retention window is kept, one of a failed publish is not kept for ever`() {
        val sc = scenario(); rollbackWindow(1)
        val fresh = artifact(sc, ageHours = 0)                                  // built a moment ago, nothing references it yet
        val failed = artifact(sc, ageHours = 3); deployment(sc, failed, "FAILED", 180)
        assertThat(candidates(sc)).containsExactly(failed.id)
        retention.run(dryRun = false)
        assertThat(alive(sc)).containsExactly(fresh.id)
        assertThat(store.exists(fresh.key)).isTrue(); assertThat(store.exists(failed.key)).isFalse()
    }

    @Test
    fun `repeating the cleanup is idempotent - one audit record per deleted artifact, nothing else changes`() {
        val sc = scenario(); rollbackWindow(1)
        val keep = artifact(sc); val drop = artifact(sc)
        serve(sc, deployment(sc, keep, "RUNNING", 30)); deployment(sc, drop, "FAILED", 90)
        retention.run(dryRun = true)
        assertThat(alive(sc)).hasSize(2); assertThat(store.exists(drop.key)).isTrue()      // a dry run changes nothing
        repeat(3) { retention.run(dryRun = false) }
        assertThat(alive(sc)).containsExactly(keep.id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ARTIFACT_DELETED' AND resource_id = ?", Long::class.java, drop.id.toString())).isEqualTo(1)
        assertThat(store.exists(keep.key)).isTrue()
    }

    @Test
    fun `an artifact whose objects are already gone does not crash retention and is still removed`() {
        val sc = scenario(); rollbackWindow(1)
        val keep = artifact(sc); val ghost = artifact(sc, objects = false)
        serve(sc, deployment(sc, keep, "RUNNING", 30)); deployment(sc, ghost, "FAILED", 90)
        retention.run(dryRun = false)
        assertThat(alive(sc)).containsExactly(keep.id)
        retention.run(dryRun = false)                                                       // and again, with nothing left to do
        assertThat(alive(sc)).containsExactly(keep.id)
    }

    @Test
    fun `a store outage while deleting leaves objects without a live record, never a live record without objects, and the orphan sweep reclaims them`() {
        val sc = scenario(); rollbackWindow(1)
        val keep = artifact(sc); val drop = artifact(sc)
        serve(sc, deployment(sc, keep, "RUNNING", 30)); deployment(sc, drop, "FAILED", 90)
        Mockito.doAnswer { throw IOException("storage offline") }.`when`(store).delete(Mockito.anyString())
        retention.run(dryRun = false)
        assertThat(alive(sc)).containsExactly(keep.id)                                      // the record is gone, so nothing can serve the half-deleted artifact
        assertThat(store.exists(drop.key)).isTrue()                                         // the objects are still there

        Mockito.doCallRealMethod().`when`(store).delete(Mockito.anyString())
        val sweep = retention.cleanOrphans(dryRun = false, grace = Duration.ZERO, projectId = sc.projectId)
        assertThat(sweep.deleted).isEqualTo(1); assertThat(sweep.keys).containsExactly(drop.key)
        assertThat(store.exists(drop.key)).isFalse(); assertThat(store.exists(keep.key)).isTrue()
    }

    @Test
    fun `orphan objects are found and reclaimed - what a live record owns and what other features store is left alone`() {
        val sc = scenario()
        val live = artifact(sc)
        val removed = artifact(sc, deleted = true)                                          // record deleted, objects left behind
        val orphanSha = sha(); val orphanKey = "${sc.projectId}/$orphanSha/index.html"      // a build that died before recording its artifact
        store.putOnce(orphanKey, "x".toByteArray(), "text/html"); store.putOnce("${sc.projectId}/$orphanSha/about/index.html", "y".toByteArray(), "text/html")
        val preview = "previews/template/${UUID.randomUUID()}/abc.png"; store.putOnce(preview, "png".toByteArray(), "image/png")
        val stray = "not-an-artifact/${sha()}/file"; store.putOnce(stray, "z".toByteArray(), "text/plain")

        // inside the grace period nothing is an orphan, whatever its record says: a build that is writing right now is safe
        assertThat(retention.cleanOrphans(dryRun = true, projectId = sc.projectId).found).isZero()

        val preview1 = retention.cleanOrphans(dryRun = true, grace = Duration.ZERO, projectId = sc.projectId)
        assertThat(preview1.keys).containsExactlyInAnyOrder(orphanKey, "${sc.projectId}/$orphanSha/about/index.html", removed.key)
        assertThat(store.exists(orphanKey)).isTrue()                                        // a dry run deletes nothing

        val swept = retention.cleanOrphans(dryRun = false, grace = Duration.ZERO, projectId = sc.projectId)
        assertThat(swept.deleted).isEqualTo(3); assertThat(swept.failed).isZero()
        assertThat(store.exists(orphanKey)).isFalse(); assertThat(store.exists(removed.key)).isFalse()
        assertThat(store.exists(live.key)).isTrue()
        assertThat(store.exists(preview)).isTrue(); assertThat(store.exists(stray)).isTrue()

        val again = retention.cleanOrphans(dryRun = false, grace = Duration.ZERO, projectId = sc.projectId)       // repeating finds nothing
        assertThat(again.found).isZero(); assertThat(again.deleted).isZero()
        // a whole-bucket scan never touches keys that are not artifact objects either
        retention.cleanOrphans(dryRun = false, grace = Duration.ZERO)
        assertThat(store.exists(preview)).isTrue(); assertThat(store.exists(stray)).isTrue(); assertThat(store.exists(live.key)).isTrue()
    }

    @Test
    fun `one object that cannot be deleted does not stop the sweep and is retried by the next one`() {
        val sc = scenario()
        val sha = sha(); val a = "${sc.projectId}/$sha/a.html"; val b = "${sc.projectId}/$sha/b.html"
        store.putOnce(a, "a".toByteArray(), "text/html"); store.putOnce(b, "b".toByteArray(), "text/html")
        Mockito.doAnswer { throw IOException("boom") }.`when`(store).delete(a)
        val first = retention.cleanOrphans(dryRun = false, grace = Duration.ZERO, projectId = sc.projectId)
        assertThat(first.deleted).isEqualTo(1); assertThat(first.failed).isEqualTo(1)
        assertThat(store.exists(a)).isTrue(); assertThat(store.exists(b)).isFalse()
        Mockito.doCallRealMethod().`when`(store).delete(a)
        assertThat(retention.cleanOrphans(dryRun = false, grace = Duration.ZERO, projectId = sc.projectId).deleted).isEqualTo(1)
        assertThat(store.exists(a)).isFalse()
    }

    @Test
    fun `building the same content again after retention removed it brings the artifact back with its files, and retention leaves it alone`() {
        val sc = scenario(); rollbackWindow(1)
        val removed = artifact(sc, deleted = true, objects = false)
        val bytes = "hello".toByteArray()
        val back = StaticSiteBuilder.reuseOrRevive(jdbc, store, sc.projectId, removed.sha, mapOf(removed.key to (bytes to "text/html")))
        assertThat(back).isEqualTo(removed.id)
        assertThat(alive(sc)).containsExactly(removed.id)
        assertThat(store.exists(removed.key)).isTrue()                                      // the files were written again, not just the flag cleared
        assertThat(jdbc.queryForObject("SELECT created_at > now() - interval '5 minutes' FROM artifacts WHERE id = ?", Boolean::class.java, removed.id)).isTrue()
        assertThat(candidates(sc)).isEmpty()                                                // back inside the retention window
        // a live record is reused untouched, an unknown content is the caller's to build
        assertThat(StaticSiteBuilder.reuseOrRevive(jdbc, store, sc.projectId, removed.sha, emptyMap())).isEqualTo(removed.id)
        assertThat(StaticSiteBuilder.reuseOrRevive(jdbc, store, sc.projectId, sha(), emptyMap())).isNull()
    }
}
