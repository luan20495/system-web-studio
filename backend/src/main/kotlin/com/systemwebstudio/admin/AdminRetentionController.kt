package com.systemwebstudio.admin

import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.maintenance.ArtifactRetentionService
import com.systemwebstudio.maintenance.CleanupResult
import com.systemwebstudio.maintenance.CleanupService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class RepoRow(val projectId: UUID, val project: String?, val name: String, val state: String, val sizeBytes: Long?, val archivedAt: Instant?, val deleteAfter: Instant?)

/** Retention preview/run and code repository lifecycle for system admins. */
@RestController
@RequestMapping("/api/v1/admin/retention")
class AdminRetentionController(private val guard: AdminGuard, private val cleanup: CleanupService, private val retention: ArtifactRetentionService, private val jdbc: JdbcTemplate) {
    /** What the cleanup job would delete right now (nothing is deleted). */
    @GetMapping("/preview")
    fun preview(@AuthenticationPrincipal me: StudioUserDetails): CleanupResult? { guard.require(me.userId); return cleanup.run(dryRun = true) }

    @PostMapping("/run")
    fun run(@AuthenticationPrincipal me: StudioUserDetails): CleanupResult? { guard.require(me.userId); return cleanup.run(dryRun = false) }

    @GetMapping("/repositories")
    fun repositories(@AuthenticationPrincipal me: StudioUserDetails): List<RepoRow> {
        guard.require(me.userId)
        return jdbc.query("""SELECT r.project_id, p.name, r.name, r.state, r.size_bytes, r.archived_at, r.delete_after FROM repositories r LEFT JOIN projects p ON p.id = r.project_id
            ORDER BY (r.state = 'ACTIVE'), r.updated_at DESC LIMIT 200""") { rs, _ ->
            RepoRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5)?.let { (it as Number).toLong() },
                rs.getTimestamp(6)?.toInstant(), rs.getTimestamp(7)?.toInstant()) }
    }

    /** Hard delete of a repository whose retention period has passed (PENDING_DELETE). Audited. */
    @PostMapping("/repositories/{projectId}/delete")
    fun delete(@PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        guard.require(me.userId); retention.hardDeleteRepository(projectId); return mapOf("state" to "DELETED")
    }
}
