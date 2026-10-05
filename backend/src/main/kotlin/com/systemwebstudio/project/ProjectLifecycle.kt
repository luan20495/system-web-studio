package com.systemwebstudio.project

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.publish.SiteService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Application lifecycle (stage H): ARCHIVED applications are read-only for everyone (AccessService drops every permission but read) and
 * offline (the site pointer is cleared and the sites gateway never serves an archived project). RESTORE makes them editable again; the
 * site stays offline until someone publishes. Data, versions and repositories are kept.
 */
@Service
class ProjectLifecycleService(private val jdbc: JdbcTemplate, private val sites: SiteService, private val audit: AuditService,
                              private val runtime: com.systemwebstudio.runtime.ServerRuntimeService) {
    fun archive(projectId: UUID, workspaceId: UUID, actor: UUID) {
        val n = jdbc.update("UPDATE projects SET lifecycle = 'ARCHIVED', archived_at = now(), archived_by = ?, updated_at = now() WHERE id = ? AND active AND lifecycle = 'ACTIVE'", actor, projectId)
        if (n == 0) throw ApiException.conflict("NOT_ARCHIVABLE", "Only an active application can be archived")
        val wasOnline = jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ? AND current_deployment_id IS NOT NULL", Long::class.java, projectId)!! > 0
        if (wasOnline) sites.point(projectId, null)
        runtime.stop(projectId, actor)          // a server app's containers are removed by the runner
        audit.record("APPLICATION_ARCHIVED", "PROJECT", projectId, workspaceId, projectId, newValue = mapOf("siteTakenOffline" to wasOnline))
    }

    fun restore(projectId: UUID, workspaceId: UUID) {
        val n = jdbc.update("UPDATE projects SET lifecycle = 'ACTIVE', archived_at = NULL, archived_by = NULL, updated_at = now() WHERE id = ? AND active AND lifecycle = 'ARCHIVED'", projectId)
        if (n == 0) throw ApiException.conflict("NOT_ARCHIVED", "This application is not archived")
        audit.record("APPLICATION_RESTORED", "PROJECT", projectId, workspaceId, projectId)
    }
}

@RestController
class ProjectLifecycleController(private val access: AccessService, private val lifecycle: ProjectLifecycleService, private val guard: AdminGuard, private val jdbc: JdbcTemplate) {
    /** owners, workspace admins and system admins (whoever may delete the application) */
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/archive")
    @Transactional
    fun archive(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        access.forProject(me.userId, workspaceId, projectId, ignoreArchive = true).require(Permission.PROJECT_DELETE)
        lifecycle.archive(projectId, workspaceId, me.userId); return mapOf("lifecycle" to "ARCHIVED")
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/restore")
    @Transactional
    fun restore(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        access.forProject(me.userId, workspaceId, projectId, ignoreArchive = true).require(Permission.PROJECT_DELETE)
        lifecycle.restore(projectId, workspaceId); return mapOf("lifecycle" to "ACTIVE")
    }

    private fun workspaceOf(id: UUID) = jdbc.query("SELECT workspace_id FROM projects WHERE id = ? AND active", { rs, _ -> rs.getObject(1, UUID::class.java) }, id).firstOrNull()
        ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Application not found")

    @PostMapping("/api/v1/admin/applications/{id}/archive")
    @Transactional
    fun adminArchive(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        guard.require(me.userId); lifecycle.archive(id, workspaceOf(id), me.userId); return mapOf("lifecycle" to "ARCHIVED")
    }

    @PostMapping("/api/v1/admin/applications/{id}/restore")
    @Transactional
    fun adminRestore(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        guard.require(me.userId); lifecycle.restore(id, workspaceOf(id)); return mapOf("lifecycle" to "ACTIVE")
    }
}
