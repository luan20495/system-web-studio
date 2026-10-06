package com.systemwebstudio.maintenance

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.integration.git.ForgejoClient
import com.systemwebstudio.integration.storage.ArtifactObject
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class RetentionResult(val previewsExpired: Int, val artifactsDeleted: Int, val artifactBytesFreed: Long, val failedBuildLogsCleared: Int, val repositoriesPendingDelete: Int,
                           val orphanObjectsDeleted: Int = 0)

/** Objects in the artifact store that no live artifact record owns. [failed] = objects that could not be deleted now (the next sweep tries again). */
data class OrphanSweep(val found: Int, val bytes: Long, val deleted: Int, val failed: Int, val keys: List<String>)

/**
 * Retention for build/publish artifacts and code repositories (all values are settings). An artifact is KEPT when it is served by a site,
 * is one of the last N successful deployments of its project (rollback) or the release right before the served one (the rollback target, whatever
 * N is), is used by a deployment that is still being built or published, is the current / desired server runtime release, backs a live preview,
 * belongs to a job in progress, or is younger than one hour. Projects deleted longer than the retention period keep nothing. Every deleted
 * artifact is audited.
 *
 * Deleting is two steps, in this order: the record is marked deleted (and stops being servable), then its objects are removed. A crash or a store
 * outage in between leaves objects without a live record, never a live record without objects; [cleanOrphans] reclaims those objects.
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
            UNION SELECT artifact_id FROM build_jobs WHERE artifact_id IS NOT NULL AND status IN ('QUEUED','RUNNING')
            UNION SELECT artifact_id FROM deployments WHERE artifact_id IS NOT NULL AND status IN ('QUEUED','POLICY_CHECK','SECURITY_CHECK','BUILDING','DEPLOYING')
            UNION SELECT sd.artifact_id FROM server_deployments sd JOIN app_runtimes r ON sd.id IN (r.current_deployment_id, r.desired_deployment_id)
            UNION SELECT (SELECT p.artifact_id FROM deployments p WHERE p.project_id = d.project_id AND p.status = 'RUNNING' AND p.artifact_id IS NOT NULL
                          AND p.created_at < d.created_at ORDER BY p.created_at DESC LIMIT 1)
                   FROM sites s JOIN deployments d ON d.id = s.current_deployment_id)
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
                // 1. the record stops being servable. Guarded again here: a build that reused the row (fresh created_at) or a site switch since
                //    the candidates were read wins, and then nothing is deleted
                val claimed = jdbc.update("""UPDATE artifacts SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL AND created_at < now() - interval '1 hour'
                    AND NOT EXISTS (SELECT 1 FROM sites s JOIN deployments d ON d.id = s.current_deployment_id WHERE d.artifact_id = artifacts.id)""", id)
                if (claimed == 0) continue
                deleted++
                audit.record("ARTIFACT_DELETED", "ARTIFACT", id, projectId = a["project_id"] as UUID, actorId = null,
                    oldValue = mapOf("kind" to a["kind"], "bytes" to a["total_bytes"], "prefix" to a["storage_prefix"]))
                // 2. its objects. A missing object is not an error; a store outage leaves them for cleanOrphans
                json.readTree(a["manifest"] as String).toList().forEach { f -> store.delete("${a["storage_prefix"]}/${f.get("path").asString()}") }
                freed += (a["total_bytes"] as Number).toLong()
            } catch (e: Exception) { log.warn("Could not delete the objects of artifact {} (they are reclaimed by the orphan sweep): {}", id, e.message) }
        }
        val orphans = if (dryRun) 0 else runCatching { cleanOrphans(dryRun = false).deleted }.onFailure { log.warn("Orphan sweep failed: {}", it.message) }.getOrDefault(0)
        val logs = if (dryRun) jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'FAILED' AND log IS NOT NULL AND finished_at < now() - make_interval(days => ?)", Int::class.java, settings.int("retention.failed-build-days"))!!
            else jdbc.update("UPDATE build_jobs SET log = NULL, scans = NULL WHERE status = 'FAILED' AND log IS NOT NULL AND finished_at < now() - make_interval(days => ?)", settings.int("retention.failed-build-days"))
        // archived repositories past their retention become PENDING_DELETE; deleting them is an explicit admin action
        val due = jdbc.queryForList("SELECT project_id, name FROM repositories WHERE state = 'ARCHIVED' AND delete_after < now()")
        if (!dryRun) due.forEach {
            jdbc.update("UPDATE repositories SET state = 'PENDING_DELETE', updated_at = now() WHERE project_id = ? AND state = 'ARCHIVED'", it["project_id"])
            audit.record("REPOSITORY_PENDING_DELETE", "REPOSITORY", it["project_id"], projectId = it["project_id"] as UUID, actorId = null, newValue = mapOf("name" to it["name"]))
        }
        if (!dryRun) runCatching { gitAccess.ifAvailable?.syncCollaborators() }.onFailure { log.warn("Git access sync failed: {}", it.message) }
        return RetentionResult(expired, if (dryRun) drop.size else deleted, if (dryRun) drop.sumOf { (it["total_bytes"] as Number).toLong() } else freed, logs, due.size, orphans)
    }

    /**
     * Objects of the artifact store that no live artifact record owns: left by a build that failed between writing its files and recording the
     * artifact, by a deletion whose second step failed, or written by something that is gone. Only keys shaped like an artifact object
     * (`<projectId>/<sha256>/<file>`) are ever considered, so other users of the bucket (template previews) are untouched. A group
     * (project + sha) is only an orphan when its newest object is older than [grace], which covers a build that is writing right now, and
     * is checked against the records again right before its objects are deleted. [projectId] limits the scan to one project.
     * Safe to repeat: a second run finds nothing. A store error on one object does not stop the others.
     */
    fun cleanOrphans(dryRun: Boolean, grace: Duration = ORPHAN_GRACE, projectId: UUID? = null): OrphanSweep {
        val cutoff = Instant.now().minus(grace)
        val groups = LinkedHashMap<String, MutableList<ArtifactObject>>()
        store.list(projectId?.let { "$it/" } ?: "").forEach { o -> ARTIFACT_KEY.find(o.key)?.let { groups.getOrPut(it.groupValues[1] + "/" + it.groupValues[2]) { ArrayList() }.add(o) } }
        var found = 0; var bytes = 0L; var deleted = 0; var failed = 0
        val keys = ArrayList<String>()
        for ((group, objects) in groups) {
            val newest = objects.mapNotNull { it.lastModified }.maxOrNull()
            if (newest == null || objects.any { it.lastModified == null } || newest.isAfter(cutoff)) continue
            val project = UUID.fromString(group.substringBefore('/')); val sha = group.substringAfter('/')
            if (liveRecord(project, sha)) continue
            found += objects.size; bytes += objects.sumOf { it.size }; keys += objects.map { it.key }
            if (dryRun) continue
            if (liveRecord(project, sha)) { found -= objects.size; bytes -= objects.sumOf { it.size }; keys.removeAll(objects.map { it.key }.toSet()); continue }
            var groupDeleted = 0
            objects.forEach { o -> try { store.delete(o.key); groupDeleted++ } catch (e: Exception) { failed++; log.warn("Could not delete orphan object {}: {}", o.key, e.message) } }
            deleted += groupDeleted
            if (groupDeleted > 0) runCatching {
                audit.record("ARTIFACT_ORPHAN_DELETED", "ARTIFACT_PREFIX", group, projectId = project, actorId = null, oldValue = mapOf("objects" to groupDeleted, "bytes" to objects.sumOf { it.size }))
            }.onFailure { log.warn("Could not audit the orphan cleanup of {}: {}", group, it.message) }
        }
        return OrphanSweep(found, bytes, deleted, failed, keys)
    }

    private fun liveRecord(projectId: UUID, sha: String) =
        jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ? AND sha256 = ? AND deleted_at IS NULL", Long::class.java, projectId, sha)!! > 0

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

    companion object {
        /** same grace as the artifact age rule in [candidates]: nothing younger than this is ever reclaimed */
        val ORPHAN_GRACE: Duration = Duration.ofHours(1)
        private val ARTIFACT_KEY = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{64})/.+")
    }
}
