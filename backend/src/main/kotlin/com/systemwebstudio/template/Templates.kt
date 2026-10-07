package com.systemwebstudio.template

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.schema.PageSchemaValidator
import com.systemwebstudio.version.SchemaRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class TemplateDto(
    val id: UUID, val name: String, val description: String, val visibility: String, val status: String, val version: Int,
    val authorId: UUID, val author: String?, val sourceProjectId: UUID?, val createdAt: Instant, val updatedAt: Instant,
    val sections: Int, val componentTypes: List<String>, val schema: JsonNode, val canEdit: Boolean = false,
    /** catalog (stage F) */
    val category: String = "general", val tags: List<String> = emptyList(), val reviewStatus: String = "PRIVATE", val usageCount: Int = 0,
    /** NONE | READY | FAILED | UNAVAILABLE; when READY the image is served by GET /api/v1/templates/{id}/preview */
    val previewStatus: String = "NONE", val submittedAt: Instant? = null, val reviewedBy: String? = null, val reviewedAt: Instant? = null,
    val reviewComment: String? = null, val canReview: Boolean = false,
    /** Template V2 (D-C2-09): PRIVATE | TENANT | SYSTEM, the data the template expects the project to bind, and its sample rows (view model id -> rows) */
    val scope: String = "PRIVATE", val dataSlots: List<DataSlot> = emptyList(), val sampleData: JsonNode? = null, val builtIn: Boolean = false
)
data class SaveTemplateRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Size(max = 500) val description: String? = null,
    /** update this template (new version) instead of creating one */
    val templateId: UUID? = null
)
data class SaveTemplateResult(val template: TemplateDto, val removedImages: Int, /** tenant / project data left out of the template (connector ids, publish draft, ...) */ val removedPrivateData: List<String> = emptyList())
data class UpdateTemplateRequest(@field:Size(min = 1, max = 120) val name: String? = null, @field:Size(max = 500) val description: String? = null)

/**
 * Template library. A template is a validated PAGE SCHEMA copied from a project, never source code.
 * Visibility: PRIVATE (author only) or COMPANY (everyone; only a system admin can make a template COMPANY, and from then on only a
 * system admin can change it, so company templates cannot be altered without an admin).
 */
@Service
class TemplateService(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val validator: PageSchemaValidator, private val guard: AdminGuard) {
    val select = """SELECT t.id, t.name, t.description, t.visibility, t.status, t.version, t.author_id, coalesce(u.display_name, u.username),
        t.source_project_id, t.created_at, t.updated_at, t.schema::text, t.category, array_to_string(t.tags, ','), t.review_status, t.usage_count,
        t.preview_status, t.submitted_at, coalesce(r.display_name, r.username), t.reviewed_at, t.review_comment
        FROM templates t LEFT JOIN users u ON u.id = t.author_id LEFT JOIN users r ON r.id = t.reviewed_by"""

    private val sanitizer = TemplateSanitizer(json)

    fun row(rs: ResultSet, viewer: UUID?, admin: Boolean): TemplateDto {
        val schema = json.readTree(rs.getString(12))
        val sections = schema.get("sections")?.toList().orEmpty()
        val t = TemplateDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
            rs.getObject(7, UUID::class.java), rs.getString(8), rs.getObject(9, UUID::class.java), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant(),
            sections.size, sections.mapNotNull { it.get("type")?.asString() }.distinct(), schema,
            category = rs.getString(13), tags = rs.getString(14).split(',').filter { it.isNotEmpty() }, reviewStatus = rs.getString(15), usageCount = rs.getInt(16),
            previewStatus = rs.getString(17), submittedAt = rs.getTimestamp(18)?.toInstant(), reviewedBy = rs.getString(19), reviewedAt = rs.getTimestamp(20)?.toInstant(),
            reviewComment = rs.getString(21), scope = TemplateScope.of(rs.getString(4)).name, dataSlots = sanitizer.dataSlots(schema),
            sampleData = sanitizer.sampleData(schema))
        // the source project of a template is not for everyone who may use it (C1 B-009): only the author and a system admin see it
        val shown = if (viewer != null && (admin || t.authorId == viewer)) t else t.copy(sourceProjectId = null)
        return shown.copy(canEdit = viewer != null && canEdit(viewer, admin, t), canReview = viewer != null && admin && t.authorId != viewer && t.reviewStatus == "REVIEW")
    }

    /** a built-in business template (scope SYSTEM, defined in code, never edited) as the same DTO the library uses */
    fun builtIn(b: BuiltInTemplate): TemplateDto {
        val sections = b.schema.get("sections")?.toList().orEmpty()
        return TemplateDto(b.id, b.name, b.description, "COMPANY", "ACTIVE", 1, SYSTEM_AUTHOR, "XWEB", null, BUILT_IN_DATE, BUILT_IN_DATE,
            sections.size, sections.mapNotNull { it.get("type")?.asString() }.distinct(), b.schema, canEdit = false,
            category = b.category, tags = b.tags, reviewStatus = "APPROVED", scope = TemplateScope.SYSTEM.name,
            dataSlots = sanitizer.dataSlots(b.schema), sampleData = sanitizer.sampleData(b.schema), builtIn = true)
    }

    /** built-ins matching the library filters, in the fixed catalogue order (they come before the company templates) */
    fun builtIns(q: String?, category: String?, tag: String?): List<TemplateDto> = BusinessTemplates.all(json).map { builtIn(it) }.filter { t ->
        (q.isNullOrBlank() || t.name.contains(q.trim(), ignoreCase = true) || t.description.contains(q.trim(), ignoreCase = true)) &&
            (category == null || t.category == category) && (tag == null || tag in t.tags)
    }

    /** the author only while the template is a private draft (not during review, not once approved); a system admin always */
    fun canEdit(userId: UUID, admin: Boolean, t: TemplateDto) = admin || (t.authorId == userId && t.reviewStatus == "PRIVATE" && t.status == "ACTIVE")

    /** 404 (not 403) for templates the caller may not see, so ids of private templates are not confirmed. */
    fun visible(userId: UUID, id: UUID): TemplateDto {
        BusinessTemplates.find(json, id)?.let { return builtIn(it) }
        val admin = guard.isAdmin(userId)
        val t = jdbc.query("$select WHERE t.id = ?", { rs, _ -> row(rs, userId, admin) }, id).firstOrNull()
        if (t == null || !(admin || (t.status == "ACTIVE" && (t.visibility == "COMPANY" || t.authorId == userId)))) throw ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found")
        return t
    }

    /** The schema a new project starts from: visible, active, and still valid against today's registry. */
    fun forUse(userId: UUID, id: UUID): Pair<TemplateDto, JsonNode> {
        val t = visible(userId, id)
        if (t.status != "ACTIVE") throw ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found")
        val violations = validator.validate(t.schema)
        if (violations.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_OUTDATED",
            "This template no longer matches the approved components", mapOf("violations" to violations.take(10).map { mapOf("path" to it.path, "message" to it.message) }))
        // counted when a project is created from it (same transaction as the project, so a failed creation does not count); built-ins have no row
        if (!t.builtIn) jdbc.update("UPDATE templates SET usage_count = usage_count + 1 WHERE id = ?", id)
        return t to t.schema
    }

    companion object {
        /** author of the built-in templates: not a user */
        val SYSTEM_AUTHOR: UUID = UUID(0L, 0L)
        private val BUILT_IN_DATE: Instant = Instant.parse("2026-10-01T00:00:00Z")

        /**
         * Images (asset://) belong to the project they were uploaded to; a copy elsewhere would point at files the new project does not own
         * (and commit would reject it). They are cleared, and the caller is told how many.
         */
        fun stripAssets(schema: JsonNode): Pair<JsonNode, Int> {
            val copy = schema.deepCopy()
            var removed = 0
            fun walk(n: JsonNode) {
                when (n) {
                    is ObjectNode -> n.propertyNames().toList().forEach { k ->
                        val v = n.get(k)
                        if (v.isString && PageSchemaValidator.ASSET_REF.matches(v.asString())) { n.put(k, ""); removed++ } else walk(v)
                    }
                    is ArrayNode -> n.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(copy)
            return copy to removed
        }
    }
}

@RestController
class TemplateController(
    private val templates: TemplateService, private val access: AccessService, private val repo: SchemaRepository, private val validator: PageSchemaValidator,
    private val guard: AdminGuard, private val audit: AuditService, private val jdbc: JdbcTemplate, private val json: JsonMapper
) {
    @GetMapping("/api/v1/templates")
    @Transactional(readOnly = true)
    fun list(@RequestParam(defaultValue = "company") scope: String, @RequestParam(required = false) q: String?,
             @RequestParam(required = false) category: String?, @RequestParam(required = false) tag: String?, @RequestParam(defaultValue = "recent") sort: String,
             @AuthenticationPrincipal me: StudioUserDetails): List<TemplateDto> {
        val admin = guard.isAdmin(me.userId)
        val where = StringBuilder(" WHERE t.status = 'ACTIVE'"); val args = mutableListOf<Any>()
        if (scope == "mine") { where.append(" AND t.author_id = ?"); args += me.userId } else where.append(" AND t.visibility = 'COMPANY'")
        com.systemwebstudio.admin.like(q)?.let { where.append(" AND (t.name ILIKE ? OR t.description ILIKE ?)"); args += it; args += it }
        category?.takeIf { Regex("^[a-z0-9-]{1,40}$").matches(it) }?.let { where.append(" AND t.category = ?"); args += it }
        tag?.lowercase()?.takeIf { Regex("^[a-z0-9-]{1,24}$").matches(it) }?.let { where.append(" AND ? = ANY(t.tags)"); args += it }
        val order = if (sort == "popular") "t.usage_count DESC, t.updated_at DESC" else "t.updated_at DESC"
        val stored = jdbc.query("${templates.select}$where ORDER BY $order LIMIT 100", { rs, _ -> templates.row(rs, me.userId, admin) }, *args.toTypedArray())
        if (scope == "mine") return stored
        val builtIns = templates.builtIns(q, category?.takeIf { Regex("^[a-z0-9-]{1,40}$").matches(it) }, tag?.lowercase())
        // built-ins have no usage count: by popularity they follow the stored templates, otherwise they come first
        return if (sort == "popular") stored + builtIns else builtIns + stored
    }

    @GetMapping("/api/v1/templates/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto = templates.visible(me.userId, id)

    /** Save the project's CURRENT stored page (never a client-supplied schema) as a new template, or as a new version of one the caller may edit. */
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/templates")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun saveFromProject(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: SaveTemplateRequest,
                        @AuthenticationPrincipal me: StudioUserDetails): SaveTemplateResult {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val current = repo.currentSchema(projectId) ?: throw ApiException.conflict("NO_PAGE", "This project has no page yet")
        val (stripped, removed) = TemplateService.stripAssets(current)
        // a template is portable: no connector id, no publish draft, no credential-like extension (D-C2-09)
        val portable = TemplateSanitizer(json).sanitize(stripped, TemplateScope.PRIVATE)
        val schema = portable.schema
        validator.requireValid(schema)
        val name = request.name.trim(); val description = request.description?.trim().orEmpty()
        val id = if (request.templateId != null) {
            val t = templates.visible(me.userId, request.templateId)
            if (!t.canEdit) throw ApiException.forbidden("Only the author (while private) or a system admin can change this template")
            jdbc.update("UPDATE templates SET name = ?, description = ?, schema = CAST(? AS jsonb), version = version + 1, source_project_id = ?, updated_at = now() WHERE id = ?",
                name, description, json.writeValueAsString(schema), projectId, t.id)
            audit.record("UPDATE_TEMPLATE", "TEMPLATE", t.id, workspaceId, projectId, newValue = mapOf("version" to t.version + 1, "removedImages" to removed, "removedPrivateData" to portable.removed.size))
            t.id
        } else {
            val newId = UUID.randomUUID()
            jdbc.update("INSERT INTO templates (id, name, description, schema, author_id, source_project_id) VALUES (?,?,?,CAST(? AS jsonb),?,?)",
                newId, name, description, json.writeValueAsString(schema), me.userId, projectId)
            audit.record("CREATE_TEMPLATE", "TEMPLATE", newId, workspaceId, projectId, newValue = mapOf("name" to name, "removedImages" to removed, "removedPrivateData" to portable.removed.size))
            newId
        }
        return SaveTemplateResult(templates.visible(me.userId, id), removed, portable.removed)
    }

    @PatchMapping("/api/v1/templates/{id}")
    @Transactional
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateTemplateRequest, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto {
        val t = templates.visible(me.userId, id)
        if (!t.canEdit) throw ApiException.forbidden("Only the author (while private) or a system admin can change this template")
        jdbc.update("UPDATE templates SET name = ?, description = ?, updated_at = now() WHERE id = ?",
            request.name?.trim() ?: t.name, request.description?.trim() ?: t.description, id)
        audit.record("UPDATE_TEMPLATE", "TEMPLATE", id, oldValue = mapOf("name" to t.name), newValue = mapOf("name" to (request.name?.trim() ?: t.name)))
        return templates.visible(me.userId, id)
    }

    @DeleteMapping("/api/v1/templates/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun archive(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        val t = templates.visible(me.userId, id)
        if (!t.canEdit) throw ApiException.forbidden("Only the author (while private) or a system admin can archive this template")
        jdbc.update("UPDATE templates SET status = 'ARCHIVED', review_status = 'ARCHIVED', updated_at = now() WHERE id = ?", id)
        audit.record("ARCHIVE_TEMPLATE", "TEMPLATE", id, newValue = mapOf("name" to t.name))
    }
}
