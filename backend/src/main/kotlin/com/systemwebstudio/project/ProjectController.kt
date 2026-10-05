package com.systemwebstudio.project

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

private const val HOSTNAME = "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"

data class CreateProjectRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Size(max = 1000) val description: String? = null,
    @field:Pattern(regexp = "nextjs|react|static") val framework: String? = null,
    /** start from this template (company, or one of the caller's own) instead of the default page */
    val templateId: UUID? = null,
    /** PAGE_SCHEMA (default) or STATIC_APP (code project, needs the Git server) */
    @field:Pattern(regexp = "PAGE_SCHEMA|STATIC_APP") val appType: String? = null,
    /** stage J/K: SOURCE_WEB_APP | DASHBOARD | INTERNAL_TOOL | WORKFLOW | SERVER_APP (code projects); websites are WEBSITE_STATIC */
    @field:Pattern(regexp = "WEBSITE_STATIC|SOURCE_WEB_APP|DASHBOARD|INTERNAL_TOOL|WORKFLOW|SERVER_APP") val appKind: String? = null
)

data class UpdateProjectRequest(
    @field:NotNull val expectedRevision: Long?,
    @field:Size(min = 1, max = 160) val name: String? = null,
    @field:Size(max = 1000) val description: String? = null,
    @field:Pattern(regexp = "nextjs|react|static") val framework: String? = null,
    @field:Pattern(regexp = "PRIVATE|PUBLIC") val siteVisibility: String? = null,
    @field:Pattern(regexp = "NONE|LOCAL|OIDC") val authMode: String? = null,
    @field:Pattern(regexp = HOSTNAME) val domain: String? = null,
    @field:Pattern(regexp = HOSTNAME) val customDomain: String? = null,
    @field:Pattern(regexp = "MOCK|SELF_HOSTED|CLOUD") val deploymentMode: String? = null,
    @field:Size(max = 120) val deploymentTarget: String? = null
)

data class ProjectResponse(
    val id: UUID, val workspaceId: UUID, val name: String, val description: String?, val ownerUserId: UUID,
    val framework: String, val projectAccessPolicy: String, val siteVisibility: String, val authMode: String,
    val domain: String?, val customDomain: String?, val deploymentMode: String, val deploymentTarget: String?,
    val status: String, val revision: Long, val createdAt: Instant, val updatedAt: Instant,
    val permissions: List<String> = emptyList(), val appType: String = "PAGE_SCHEMA", val appKind: String = "WEBSITE_STATIC"
)

fun ProjectEntity.toResponse(permissions: Collection<Permission> = emptyList()) = ProjectResponse(
    id, workspaceId, name, description, ownerUserId, framework, projectAccessPolicy, siteVisibility, authMode,
    domain, customDomain, deploymentMode, deploymentTarget, if (!active) "DELETED" else lifecycle,
    revision, createdAt, updatedAt, permissions.map { it.name }.sorted(), appType, appKind
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects")
class ProjectController(
    private val access: AccessService,
    private val projects: ProjectRepository,
    private val projectMembers: ProjectMemberRepository,
    private val audit: AuditService,
    private val schemas: com.systemwebstudio.schema.SchemaService,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val templates: com.systemwebstudio.template.TemplateService,
    private val codeProjects: com.systemwebstudio.code.CodeProjectService,
    private val retention: com.systemwebstudio.maintenance.ArtifactRetentionService,
    private val versionRepo: com.systemwebstudio.version.SchemaRepository,
    private val json: tools.jackson.databind.json.JsonMapper,
    private val runtime: com.systemwebstudio.runtime.ServerRuntimeService,
    @org.springframework.beans.factory.annotation.Value("\${app.limits.max-projects-per-workspace:1000}") private val maxProjects: Long
) {
    /**
     * Projects the caller can see. Optional server-side paging: `page` (0-based), `size` (≤100), `q` (name search),
     * `scope` = all | owned | shared. The total is returned in `X-Total-Count`. Without `page` the full list is returned
     * (backwards compatible).
     */
    @GetMapping
    @Transactional(readOnly = true)
    fun list(
        @PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails,
        @RequestParam(required = false) page: Int?, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) q: String?, @RequestParam(defaultValue = "all") scope: String,
        response: jakarta.servlet.http.HttpServletResponse
    ): List<ProjectResponse> {
        val ctx = access.forWorkspace(me.userId, workspaceId)
        val where = StringBuilder("p.workspace_id = ? AND p.active")
        val args = mutableListOf<Any>(workspaceId)
        if (!ctx.seesAllProjects) { where.append(" AND EXISTS (SELECT 1 FROM project_members m WHERE m.project_id = p.id AND m.user_id = ? AND m.active)"); args += me.userId }
        when (scope) { "owned" -> { where.append(" AND p.owner_user_id = ?"); args += me.userId }; "shared" -> { where.append(" AND p.owner_user_id <> ?"); args += me.userId } }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { where.append(" AND p.name ILIKE ?"); args += "%" + it.replace("%", "\\%").replace("_", "\\_") + "%" }
        val total = jdbc.queryForObject("SELECT count(*) FROM projects p WHERE $where", Long::class.java, *args.toTypedArray())!!
        response.setHeader("X-Total-Count", total.toString())
        val paging = if (page != null) " LIMIT ${size.coerceIn(1, 100)} OFFSET ${page.coerceAtLeast(0).toLong() * size.coerceIn(1, 100)}" else ""
        val ids = jdbc.query("SELECT p.id FROM projects p WHERE $where ORDER BY p.updated_at DESC, p.id$paging", { rs, _ -> rs.getObject(1, UUID::class.java) }, *args.toTypedArray())
        val byId = projects.findAllById(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toResponse() }
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable workspaceId: UUID, @Valid @RequestBody request: CreateProjectRequest,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ProjectResponse {
        val ctx = access.forWorkspace(me.userId, workspaceId)
        ctx.require(Permission.PROJECT_CREATE)
        // the creator becomes the project OWNER, which requires workspace membership; a system admin who is not a member gets a clear
        // answer instead of a constraint violation (membership is never added silently)
        if (ctx.workspaceRole == null) throw ApiException.conflict("ADMIN_NOT_MEMBER", "Join this workspace as a member before creating applications in it")
        val existing = jdbc.queryForObject("SELECT count(*) FROM projects WHERE workspace_id = ? AND active", Long::class.java, workspaceId)!!
        if (existing >= maxProjects) throw ApiException.conflict("PROJECT_LIMIT", "This workspace reached its limit of $maxProjects projects", mapOf("limit" to maxProjects))
        val kind = request.appKind ?: if (request.appType == "STATIC_APP") "SOURCE_WEB_APP" else "WEBSITE_STATIC"
        val code = kind != "WEBSITE_STATIC"
        // server apps run generated server code: only when the policy allows it AND the isolated runtime is configured (ADR 0017)
        if (kind in com.systemwebstudio.code.CodeProjectService.SERVER_KINDS && !runtime.available)
            throw ApiException.conflict("SERVER_APPS_UNAVAILABLE", "Server apps are disabled by policy or the isolated runtime is not configured on this server")
        if (code && request.templateId != null) throw ApiException.badRequest("TEMPLATE_NOT_FOR_CODE", "Templates are page schemas; code projects start from the approved scaffold")
        if (code && !codeProjects.available) throw ApiException.conflict("CODE_PROJECTS_UNAVAILABLE", "Code projects need the Git server")
        // resolved before anything is stored: an invisible or outdated template leaves no half-created project behind
        val template = request.templateId?.let { templates.forUse(me.userId, it) }
        val now = Instant.now()
        val project = projects.saveAndFlush(
            ProjectEntity(
                workspaceId = workspaceId, name = request.name.trim(), ownerUserId = me.userId,
                description = request.description?.trim()?.ifEmpty { null },
                framework = if (code) "react" else request.framework ?: "nextjs", createdAt = now, updatedAt = now,
                appType = if (code) "STATIC_APP" else "PAGE_SCHEMA", appKind = kind
            )
        )
        projectMembers.save(ProjectMemberEntity(workspaceId = workspaceId, projectId = project.id, userId = me.userId, role = "OWNER"))
        if (code) {
            // platform-owned repository with the scaffold as first commit; version 1 points at that commit (ADR 0011)
            val sha = codeProjects.initialize(project, me.userId, kind)
            if (kind in com.systemwebstudio.code.CodeProjectService.SERVER_KINDS) runtime.provision(project.id)
            val versionId = versionRepo.insertVersion(workspaceId, project.id, 1, json.createObjectNode().put("appType", "STATIC_APP").put("commit", sha),
                "INITIAL", "Khởi tạo từ khung React + Vite", null, null, null, me.userId)
            jdbc.update("UPDATE project_versions SET commit_sha = ? WHERE id = ?", sha, versionId)
        } else if (template == null) schemas.ensureInitialized(project, me.userId)
        else schemas.ensureInitialized(project, me.userId, template.second, "Tạo từ mẫu “${template.first.name.take(80)}” (v${template.first.version})")
        audit.record("CREATE_PROJECT", "PROJECT", project.id, workspaceId, project.id,
            newValue = mapOf("name" to project.name, "appType" to project.appType, "templateId" to template?.first?.id, "templateVersion" to template?.first?.version))
        return project.toResponse()
    }

    @GetMapping("/{projectId}")
    @Transactional(readOnly = true)
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ProjectResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        return ctx.project!!.toResponse(ctx.permissions)
    }

    @PatchMapping("/{projectId}")
    @Transactional
    fun update(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: UpdateProjectRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): ProjectResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_SETTINGS)
        val project = ctx.project!!
        if (project.revision != request.expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT", "Project revision changed; reload before saving.", mapOf("currentRevision" to project.revision))
        }
        val before = snapshot(project)
        request.name?.let { project.name = it.trim().ifEmpty { throw ApiException.badRequest("VALIDATION_FAILED", "name must not be blank") } }
        request.description?.let { project.description = it.trim().ifEmpty { null } }
        request.framework?.let { project.framework = it }
        request.siteVisibility?.let { project.siteVisibility = it }
        request.authMode?.let { project.authMode = it }
        request.domain?.let { project.domain = it.lowercase() }
        request.customDomain?.let { project.customDomain = it.lowercase() }
        request.deploymentMode?.let { project.deploymentMode = it }
        request.deploymentTarget?.let { project.deploymentTarget = it.trim().ifEmpty { null } }
        project.updatedAt = Instant.now()
        val saved = projects.saveAndFlush(project)
        val after = snapshot(saved)
        val changed = after.filter { (k, v) -> before[k] != v }
        audit.record("UPDATE_PROJECT", "PROJECT", saved.id, workspaceId, saved.id,
            oldValue = before.filterKeys { it in changed }, newValue = changed)
        return saved.toResponse(ctx.permissions)
    }

    @DeleteMapping("/{projectId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @RequestParam expectedRevision: Long, @AuthenticationPrincipal me: StudioUserDetails
    ) {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_DELETE)
        val project = ctx.project!!
        if (project.revision != expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT", "Project revision changed; reload before deleting.", mapOf("currentRevision" to project.revision))
        }
        project.active = false
        project.updatedAt = Instant.now()
        projects.saveAndFlush(project)
        retention.onProjectDeleted(project.id)
        runtime.stop(project.id, me.userId)
        audit.record("DELETE_PROJECT", "PROJECT", project.id, workspaceId, project.id, oldValue = mapOf("name" to project.name))
    }

    private fun snapshot(p: ProjectEntity): Map<String, Any?> = linkedMapOf(
        "name" to p.name, "description" to p.description, "framework" to p.framework,
        "siteVisibility" to p.siteVisibility, "authMode" to p.authMode, "domain" to p.domain,
        "customDomain" to p.customDomain, "deploymentMode" to p.deploymentMode, "deploymentTarget" to p.deploymentTarget
    )
}
