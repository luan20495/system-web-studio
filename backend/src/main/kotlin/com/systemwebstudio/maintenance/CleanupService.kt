package com.systemwebstudio.maintenance

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.integration.storage.StorageProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class CleanupResult(val dryRun: Boolean, val abandonedUploads: Int, val deletedAssetRows: Int, val idempotencyKeys: Int, val failedDeployments: Int, val aiCalls: Int = 0,
                         val retention: RetentionResult? = null) {
    val total get() = abandonedUploads + deletedAssetRows + idempotencyKeys + failedDeployments + aiCalls +
        (retention?.let { it.previewsExpired + it.artifactsDeleted + it.failedBuildLogsCleared + it.repositoriesPendingDelete } ?: 0)
}

/**
 * Retention for operational data. Never touches audit_events (append-only by design), sessions (Redis TTL) or any
 * RUNNING/in-progress deployment. Idempotent, safe to run on several instances (Postgres advisory lock), and
 * every non-dry run that deleted something leaves one CLEANUP audit event with the counts.
 */
@Service
class CleanupService(
    private val jdbc: JdbcTemplate,
    private val storage: StorageProvider,
    private val audit: AuditService,
    private val tx: TransactionTemplate,
    private val retention: ArtifactRetentionService,
    @Value("\${app.cleanup.enabled:true}") private val enabled: Boolean,
    @Value("\${app.cleanup.dry-run:false}") private val defaultDryRun: Boolean,
    @Value("\${app.cleanup.abandoned-upload-hours:24}") private val abandonedHours: Long,
    @Value("\${app.cleanup.deleted-asset-days:7}") private val deletedAssetDays: Long,
    @Value("\${app.cleanup.idempotency-key-days:7}") private val idempotencyDays: Long,
    @Value("\${app.cleanup.failed-deployment-days:30}") private val failedDeploymentDays: Long,
    /** AI usage rows (accounting): kept for more than a year by default so monthly and yearly totals stay complete */
    @Value("\${app.cleanup.ai-call-days:400}") private val aiCallDays: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.cleanup.interval-ms:3600000}", initialDelayString = "\${app.cleanup.interval-ms:3600000}")
    fun scheduled() { if (enabled) runCatching { run() }.onFailure { log.warn("Cleanup run failed: {}", it.message) } }

    fun run(dryRun: Boolean = defaultDryRun): CleanupResult? {
        // Session-level advisory lock on one connection; another instance running at the same moment simply skips.
        return jdbc.execute { con: java.sql.Connection ->
            val locked = con.prepareStatement("SELECT pg_try_advisory_lock(727001)").use { st -> st.executeQuery().use { it.next() && it.getBoolean(1) } }
            if (!locked) { log.info("Cleanup skipped: another instance holds the lock"); return@execute null }
            try { execute(dryRun) } finally { con.prepareStatement("SELECT pg_advisory_unlock(727001)").use { it.execute() } }
        }
    }

    private fun execute(dryRun: Boolean): CleanupResult {
        // 1. uploads that were never completed: object (if any) + row
        val abandoned = jdbc.queryForList("SELECT id, storage_key FROM assets WHERE status = 'PENDING' AND created_at < now() - make_interval(hours => ?)", abandonedHours.toInt())
        // 2. soft-deleted assets past their grace period
        val deleted = jdbc.queryForList("SELECT id, storage_key FROM assets WHERE status = 'DELETED' AND created_at < now() - make_interval(days => ?)", deletedAssetDays.toInt())
        val keys = jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE created_at < now() - make_interval(days => ?)", Int::class.java, idempotencyDays.toInt())!!
        val failed = jdbc.queryForList("SELECT id FROM deployments WHERE status = 'FAILED' AND finished_at < now() - make_interval(days => ?)", failedDeploymentDays.toInt())
        val aiCalls = jdbc.queryForObject("SELECT count(*) FROM ai_calls WHERE created_at < now() - make_interval(days => ?)", Int::class.java, aiCallDays.toInt())!!

        if (!dryRun) {
            (abandoned + deleted).forEach { runCatching { storage.delete(it["storage_key"] as String) }.onFailure { e -> log.warn("Could not delete object {}: {}", it["storage_key"], e.message) } }
            tx.executeWithoutResult {
                (abandoned + deleted).forEach { jdbc.update("DELETE FROM assets WHERE id = ? AND status IN ('PENDING','DELETED')", it["id"]) }
                jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(days => ?)", idempotencyDays.toInt())
                jdbc.update("DELETE FROM ai_calls WHERE created_at < now() - make_interval(days => ?)", aiCallDays.toInt())
                failed.forEach {
                    jdbc.update("UPDATE build_jobs SET deployment_id = NULL WHERE deployment_id = ?", it["id"])
                    jdbc.update("DELETE FROM deployment_events WHERE deployment_id = ?", it["id"])
                    jdbc.update("DELETE FROM deployments WHERE id = ? AND status = 'FAILED'", it["id"])
                }
            }
        }
        val ret = runCatching { retention.run(dryRun) }.onFailure { log.warn("Artifact retention failed: {}", it.message) }.getOrNull()
        val result = CleanupResult(dryRun, abandoned.size, deleted.size, keys, failed.size, aiCalls, ret)
        log.info("cleanup dry_run={} abandoned_uploads={} deleted_assets={} idempotency_keys={} failed_deployments={} ai_calls={}", dryRun, result.abandonedUploads, result.deletedAssetRows, result.idempotencyKeys, result.failedDeployments, result.aiCalls)
        if (!dryRun && result.total > 0) audit.record("CLEANUP", "SYSTEM", null, actorId = null, newValue = result)
        return result
    }
}
