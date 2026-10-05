package com.systemwebstudio.code

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.settings.SettingsService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Build quotas (organization / workspace / user / project, daily counts and concurrency) and size limits. All limits are settings
 * (Admin → Cài đặt), defaults from configuration. A refusal is recorded in build_rejections and audited, then reported as 429/413.
 */
@Service
class BuildPolicyService(private val jdbc: JdbcTemplate, private val settings: SettingsService, private val audit: AuditService,
                         txManager: org.springframework.transaction.PlatformTransactionManager) {
    // own transaction: the refusal must be recorded even though the caller's transaction is rolled back by the exception
    private val newTx = org.springframework.transaction.support.TransactionTemplate(txManager).apply { propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private fun count(where: String, vararg args: Any?): Long = jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE $where", Long::class.java, *args) ?: 0

    /** Called before anything is committed or queued. */
    fun requireCapacity(projectId: UUID, workspaceId: UUID, userId: UUID?) {
        if (!settings.bool("source-apps.build-enabled")) reject(projectId, workspaceId, userId, "BUILDS_DISABLED", "Sandbox builds are disabled by the administrator", HttpStatus.CONFLICT)
        val today = "queued_at >= date_trunc('day', now())"
        val checks = listOf(
            Triple("ORG_DAILY", settings.long("build.max-per-org-per-day")) { count(today) },
            Triple("WORKSPACE_DAILY", settings.long("build.max-per-workspace-per-day")) { count("$today AND workspace_id = ?", workspaceId) },
            Triple("PROJECT_DAILY", settings.long("build.max-per-project-per-day")) { count("$today AND project_id = ?", projectId) }
        ) + (if (userId != null) listOf(
            Triple("USER_DAILY", settings.long("build.max-per-user-per-day")) { count("$today AND requested_by = ?", userId) },
            Triple("USER_CONCURRENT", settings.long("build.max-concurrent-per-user")) { count("status IN ('QUEUED','RUNNING') AND requested_by = ?", userId) }
        ) else emptyList()) + listOf(
            Triple("WORKSPACE_CONCURRENT", settings.long("build.max-concurrent-per-workspace")) { count("status IN ('QUEUED','RUNNING') AND workspace_id = ?", workspaceId) }
        )
        for ((code, limit, used) in checks) {
            val n = used()
            if (n >= limit) reject(projectId, workspaceId, userId, "BUILD_QUOTA_$code", "Build quota reached ($code: $n / $limit)", HttpStatus.TOO_MANY_REQUESTS, mapOf("scope" to code, "used" to n, "limit" to limit))
        }
    }

    fun requireRepoSize(projectId: UUID, workspaceId: UUID, userId: UUID?, bytes: Long) {
        val max = settings.long("build.max-repo-mib") * 1024 * 1024
        if (bytes > max) reject(projectId, workspaceId, userId, "REPO_TOO_LARGE", "Source would be ${bytes / 1024} KiB; limit ${max / 1024} KiB", HttpStatus.PAYLOAD_TOO_LARGE,
            mapOf("bytes" to bytes, "limit" to max))
    }

    fun maxArtifactBytes() = settings.long("build.max-artifact-mib") * 1024 * 1024
    fun maxDurationSeconds() = settings.long("build.max-duration-seconds")

    fun recordRejection(projectId: UUID?, workspaceId: UUID?, userId: UUID?, code: String, detail: String) = newTx.executeWithoutResult {
        jdbc.update("INSERT INTO build_rejections (id, project_id, workspace_id, user_id, reason_code, detail) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), projectId, workspaceId, userId, code.take(48), detail.take(500))
        audit.record("BUILD_REJECTED", "PROJECT", projectId, workspaceId, projectId, actorId = userId, newValue = mapOf("reason" to code, "detail" to detail))
    }

    private fun reject(projectId: UUID, workspaceId: UUID, userId: UUID?, code: String, detail: String, status: HttpStatus, details: Map<String, Any?> = emptyMap()): Nothing {
        recordRejection(projectId, workspaceId, userId, code, detail)
        throw ApiException(status, code, detail, details)
    }
}

data class BuildUsageRow(val key: String, val label: String?, val builds: Long, val succeeded: Long, val failed: Long, val cpuMs: Long, val durationMs: Long, val artifactBytes: Long)
data class RejectionRow(val createdAt: Instant, val user: String?, val project: String?, val reason: String, val detail: String)
data class BuildPolicyReport(val days: Int, val totals: BuildUsageRow, val byWorkspace: List<BuildUsageRow>, val byUser: List<BuildUsageRow>, val byProject: List<BuildUsageRow>,
                             val running: Long, val queued: Long, val rejections: List<RejectionRow>, val storage: Map<String, Long>)

@RestController
@RequestMapping("/api/v1/admin/builds")
class AdminBuildController(private val guard: AdminGuard, private val jdbc: JdbcTemplate) {
    private val cols = """count(*), count(*) FILTER (WHERE b.status = 'SUCCEEDED'), count(*) FILTER (WHERE b.status = 'FAILED'),
        coalesce(sum(b.cpu_ms), 0), coalesce(sum(b.duration_ms), 0), coalesce(sum(b.artifact_bytes), 0)"""
    private fun row(rs: java.sql.ResultSet, key: String, label: String?) = BuildUsageRow(key, label, rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8))

    /** Measured build usage (from the runner's cgroup CPU counter and wall clock) and refusals over the last 30 days. */
    @GetMapping
    fun report(@AuthenticationPrincipal me: StudioUserDetails): BuildPolicyReport {
        guard.require(me.userId)
        val since = "b.queued_at >= now() - interval '30 days'"
        fun grouped(sql: String) = jdbc.query(sql) { rs, _ -> row(rs, rs.getString(1) ?: "-", rs.getString(2)) }
        val totals = jdbc.queryForObject("SELECT 'all', NULL, $cols FROM build_jobs b WHERE $since") { rs, _ -> row(rs, "all", null) }!!
        val storage = mapOf(
            "artifactsBytes" to (jdbc.queryForObject("SELECT coalesce(sum(total_bytes),0) FROM artifacts WHERE deleted_at IS NULL", Long::class.java) ?: 0),
            "artifactsCount" to (jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE deleted_at IS NULL", Long::class.java) ?: 0),
            "repositoriesBytes" to (jdbc.queryForObject("SELECT coalesce(sum(size_bytes),0) FROM repositories WHERE state <> 'DELETED'", Long::class.java) ?: 0),
            "assetsBytes" to (jdbc.queryForObject("SELECT coalesce(sum(size_bytes),0) FROM assets WHERE status = 'READY'", Long::class.java) ?: 0))
        return BuildPolicyReport(30, totals,
            grouped("SELECT b.workspace_id::text, max(w.name), $cols FROM build_jobs b LEFT JOIN workspaces w ON w.id = b.workspace_id WHERE $since GROUP BY 1 ORDER BY 3 DESC LIMIT 20"),
            grouped("SELECT b.requested_by::text, max(coalesce(u.display_name, u.username)), $cols FROM build_jobs b LEFT JOIN users u ON u.id = b.requested_by WHERE $since GROUP BY 1 ORDER BY 3 DESC LIMIT 20"),
            grouped("SELECT b.project_id::text, max(p.name), $cols FROM build_jobs b LEFT JOIN projects p ON p.id = b.project_id WHERE $since GROUP BY 1 ORDER BY 3 DESC LIMIT 20"),
            jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'RUNNING'", Long::class.java) ?: 0,
            jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'QUEUED'", Long::class.java) ?: 0,
            jdbc.query("""SELECT r.created_at, coalesce(u.display_name, u.username), p.name, r.reason_code, r.detail FROM build_rejections r
                LEFT JOIN users u ON u.id = r.user_id LEFT JOIN projects p ON p.id = r.project_id ORDER BY r.created_at DESC LIMIT 50""") { rs, _ ->
                RejectionRow(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) },
            storage)
    }
}
