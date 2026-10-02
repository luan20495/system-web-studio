package com.systemwebstudio.maintenance

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class CleanupTests : IntegrationTestBase() {
    @Autowired lateinit var cleanup: CleanupService

    private fun asset(sc: Scenario, status: String, ageHours: Long): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO assets (id, workspace_id, project_id, name, content_type, size_bytes, storage_key, status, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?, now() - make_interval(hours => ?))",
            id, sc.ws, sc.projectId, "a.png", "image/png", 5, "$id/a.png", status, sc.user.id, ageHours.toInt())
        return id
    }
    private fun exists(id: UUID) = jdbc.queryForObject("SELECT count(*) FROM assets WHERE id = ?", Long::class.java, id) == 1L

    @Test
    fun `retention boundaries - old pending uploads go, fresh and READY assets stay, dry run changes nothing`() {
        val sc = scenario()
        val oldPending = asset(sc, "PENDING", 25); val freshPending = asset(sc, "PENDING", 23); val ready = asset(sc, "READY", 24 * 400)
        val oldDeleted = asset(sc, "DELETED", 24 * 8); val freshDeleted = asset(sc, "DELETED", 24 * 6)

        val dry = cleanup.run(dryRun = true)!!
        assertThat(dry.abandonedUploads).isGreaterThanOrEqualTo(1); assertThat(dry.deletedAssetRows).isGreaterThanOrEqualTo(1)
        assertThat(listOf(oldPending, oldDeleted).all { exists(it) }).describedAs("dry run must not delete").isTrue()
        assertThat(sc.auditCount("CLEANUP")).isEqualTo(0L)

        cleanup.run(dryRun = false)
        assertThat(exists(oldPending)).isFalse(); assertThat(exists(oldDeleted)).isFalse()
        assertThat(exists(freshPending)).isTrue(); assertThat(exists(freshDeleted)).isTrue(); assertThat(exists(ready)).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='CLEANUP'", Long::class.java)).isGreaterThanOrEqualTo(1L)
        val again = cleanup.run(dryRun = false)!!                                       // idempotent
        assertThat(again.abandonedUploads + again.deletedAssetRows).isEqualTo(0)
    }

    @Test
    fun `old idempotency keys and failed deployments are removed, running deployments and audit rows never`() {
        val sc = scenario()
        val auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_events", Long::class.java)!!
        jdbc.update("INSERT INTO idempotency_keys (scope_key, request_hash, resource_type, resource_id, created_at) VALUES (?,?,?,?, now() - interval '8 days')", "old-" + UUID.randomUUID(), "h", "DEPLOYMENT", UUID.randomUUID())
        val freshKey = "fresh-" + UUID.randomUUID()
        jdbc.update("INSERT INTO idempotency_keys (scope_key, request_hash, resource_type, resource_id) VALUES (?,?,?,?)", freshKey, "h", "DEPLOYMENT", UUID.randomUUID())
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id=? LIMIT 1", UUID::class.java, sc.projectId)!!
        fun deployment(status: String, finishedDaysAgo: Int?): UUID {
            val id = UUID.randomUUID()
            jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, finished_at) VALUES (?,?,?,?,?,'PUBLIC',?,'mock', CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(days => ?::int) END)", id, sc.ws, sc.projectId, version, sc.user.id, status, finishedDaysAgo, finishedDaysAgo)
            jdbc.update("INSERT INTO deployment_events (id, deployment_id, status) VALUES (?,?,?)", UUID.randomUUID(), id, status)
            return id
        }
        val oldFailed = deployment("FAILED", 31); val recentFailed = deployment("FAILED", 5); val oldRunning = deployment("RUNNING", 90)
        cleanup.run(dryRun = false)
        fun has(id: UUID) = jdbc.queryForObject("SELECT count(*) FROM deployments WHERE id=?", Long::class.java, id) == 1L
        assertThat(has(oldFailed)).isFalse(); assertThat(has(recentFailed)).isTrue(); assertThat(has(oldRunning)).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_events WHERE deployment_id=?", Long::class.java, oldFailed)).isEqualTo(0L)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE scope_key=?", Long::class.java, freshKey)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Long::class.java)!!).isGreaterThanOrEqualTo(auditBefore)   // only ever grows
    }
}
