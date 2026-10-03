package com.systemwebstudio.maintenance

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.integration.git.ForgejoClient
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class RetentionResult(val previewsExpired: Int, val artifactsDeleted: Int, val artifactBytesFreed: Long, val failedBuildLogsCleared: Int, val repositoriesPendingDelete: Int)

/**
 * Retention for build/publish artifacts and code repositories (all values are settings). An artifact is KEPT when it is served by a site,
 * is one of the last N successful deployments of its project (rollback), backs a live preview, belongs to a job in progress, or is younger
 * than one hour. Projects deleted longer than the retention period keep nothing. Every deleted artifact is audited.
 */
@Service
class ArtifactRetentionService(
    private val gitAccess: org.springframework.beans.factory.ObjectProvider<com.systemwebstudio.code.GitAccessService>,
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val store: ArtifactStore, private val git: ForgejoClient,
    private val settings: SettingsService, private val audit: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun candidates(): List<Map<String, Any?>> = jdbc.queryForList("""
        WITH ranked AS (
            SELECT d.artifact_id, row_number() OVER (PARTITION BY d.project_id ORDER BY d.created_at DESC) AS rn
            FROM deployments d WHERE d.status = 'RUNNING' AND d.artifact_id IS NOT NULL),
        keep AS (
            SELECT d.artifact_id AS id FROM sites s JOIN deployments d ON d.id = s.current_deployment_id
            UNION SELECT r.artifact_id FROM ranked r JOIN artifacts a ON a.id = r.artifact_id JOIN projects p ON p.id = a.project_id
                   WHERE r.rn <= ? AND (p.active OR p.updated_at > now() - make_interval(days => CAST(? AS int)))
            UNION SELECT preview_artifact_id FROM code_changes WHERE preview_artifact_id IS NOT NULL AND status IN ('READY','MERGED') AND preview_expires_at > now()
            UNION SELECT artifact_id FROM build_jobs WHERE artifact_id IS NOT NULL AND status IN ('QUEUED','RUNNING'))
        SELECT a.id, a.project_id, a.storage_prefix, a.total_bytes, a.manifest::text AS manifest, a.kind FROM artifacts a
        WHERE a.deleted_at IS NULL AND a.created_at < now() - interval '1 hour' AND a.id NOT IN (SELECT id FROM keep WHERE id IS NOT NULL)""",
        settings.long("retention.rollback-deployments"), settings.long("retention.deleted-project-days"))

    fun run(dryRun: Boolean): RetentionResult {
        val expired = if (dryRun) jdbc.queryForObject("SELECT count(*) FROM code_changes WHERE preview_token IS NOT NULL AND preview_expires_at <= now()", Int::class.java)!!
            else jdbc.update("UPDATE code_changes SET preview_token = NULL WHERE preview_token IS NOT NULL AND preview_expires_at <= now()")
        val drop = candidates()
        var freed = 0L; var deleted = 0
        if (!dryRun) for (a in drop) {
            val id = a["id"] as UUID
            try {
                json.readTree(a["manifest"] as String).toList().forEach { f -> store.delete("${a["storage_prefix"]}/${f.get("path").asString()}") }
                jdbc.update("UPDATE artifacts SET deleted_at = now() WHERE id = ?", id)
                audit.record("ARTIFACT_DELETED", "ARTIFACT", id, projectId = a["project_id"] as UUID, actorId = null,
                    oldValue = mapOf("kind" to a["kind"], "bytes" to a["total_bytes"], "prefix" to a["storage_prefix"]))
                freed += (a["total_bytes"] as Number).toLong(); deleted++
            } catch (e: Exception) { log.warn("Could not delete artifact {}: {}", id, e.message) }
        }
        val logs = if (dryRun) jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'FAILED' AND log IS NOT NULL AND finished_at < now() - make_interval(days => ?)", Int::class.java, settings.int("retention.failed-build-days"))!!
            else jdbc.update("UPDATE build_jobs SET log = NULL, scans = NULL WHERE status = 'FAILED' AND log IS NOT NULL AND finished_at < now() - make_interval(days => ?)", settings.int("retention.failed-build-days"))
        // archived repositories past their retention become PENDING_DELETE; deleting them is an explicit admin action
        val due = jdbc.queryForList("SELECT project_id, name FROM repositories WHERE state = 'ARCHIVED' AND delete_after < now()")
        if (!dryRun) due.forEach {
            jdbc.update("UPDATE repositories SET state = 'PENDING_DELETE', updated_at = now() WHERE project_id = ? AND state = 'ARCHIVED'", it["project_id"])
            audit.record("REPOSITORY_PENDING_DELETE", "REPOSITORY", it["project_id"], projectId = it["project_id"] as UUID, actorId = null, newValue = mapOf("name" to it["name"]))
        }
        if (!dryRun) runCatching { gitAccess.ifAvailable?.syncCollaborators() }.onFailure { log.warn("Git access sync failed: {}", it.message) }
        return RetentionResult(expired, if (dryRun) drop.size else deleted, if (dryRun) drop.sumOf { (it["total_bytes"] as Number).toLong() } else freed, logs, due.size)
    }

    /** Project deleted: take its site offline and archive its repository (read-only), to be deleted after the retention period. */
    fun onProjectDeleted(projectId: UUID) {
        jdbc.update("UPDATE sites SET current_deployment_id = NULL, updated_at = now() WHERE project_id = ?", projectId)
        jdbc.query("SELECT name FROM repositories WHERE project_id = ? AND state = 'ACTIVE'", { rs, _ -> rs.getString(1) }, projectId).firstOrNull()?.let { name ->
            runCatching { git.setArchived(name, true) }.onFailure { log.warn("Could not archive repository {}: {}", name, it.message) }
            jdbc.update("""UPDATE repositories SET state = 'ARCHIVED', archived_at = now(), delete_after = now() + make_interval(days => ?), updated_at = now()
                WHERE project_id = ?""", settings.int("retention.deleted-project-days"), projectId)
            audit.record("REPOSITORY_ARCHIVED", "REPOSITORY", projectId, projectId = projectId, newValue = mapOf("name" to name))
        }
    }

    fun hardDeleteRepository(projectId: UUID) {
        val name = jdbc.query("SELECT name FROM repositories WHERE project_id = ? AND state = 'PENDING_DELETE'", { rs, _ -> rs.getString(1) }, projectId).firstOrNull()
            ?: throw com.systemwebstudio.common.ApiException.conflict("NOT_PENDING_DELETE", "Only repositories past their retention period (PENDING_DELETE) can be deleted")
        git.deleteRepository(name)
        jdbc.update("UPDATE repositories SET state = 'DELETED', updated_at = now() WHERE project_id = ?", projectId)
        audit.record("REPOSITORY_DELETED", "REPOSITORY", projectId, projectId = projectId, oldValue = mapOf("name" to name))
    }
}
