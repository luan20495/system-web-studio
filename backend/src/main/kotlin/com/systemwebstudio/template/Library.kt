package com.systemwebstudio.template

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.CheckResult
import com.systemwebstudio.component.ComponentPackageService
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.PreviewResult
import com.systemwebstudio.publish.RenderClient
import com.systemwebstudio.schema.PageSchemaValidator
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CatalogRequest(@field:Pattern(regexp = "^[a-z0-9-]{1,40}$") val category: String? = null, @field:Size(max = 8) val tags: List<String>? = null)
data class TemplateReviewDto(val id: UUID, val version: Int, val actor: String?, val decision: String, val comment: String, val checks: JsonNode?, val createdAt: Instant)
data class TemplateSubmitResult(val template: TemplateDto, val passed: Boolean, val checks: List<CheckResult>)
data class TemplateDecision(@field:NotBlank @field:Pattern(regexp = "APPROVE|REJECT") val decision: String, @field:Size(max = 1000) val comment: String? = null)

/** Fixed category lists (stage F): stable filters for the library; tags are free but normalised. */
object LibraryCatalog {
    val templateCategories = linkedMapOf("general" to "Chung", "landing" to "Trang giới thiệu", "ecommerce" to "Bán hàng", "corporate" to "Doanh nghiệp",
        "event" to "Sự kiện", "portfolio" to "Hồ sơ năng lực", "internal" to "Nội bộ")
    val blockCategories = linkedMapOf("general" to "Chung", "hero" to "Banner", "content" to "Nội dung", "product" to "Sản phẩm", "social-proof" to "Đánh giá",
        "contact" to "Liên hệ", "navigation" to "Điều hướng", "footer" to "Chân trang")

    fun tags(raw: List<String>?): List<String>? = raw?.map { it.trim().lowercase().replace(Regex("\\s+"), "-") }?.filter { it.isNotEmpty() }?.distinct()?.also { list ->
        if (list.size > 8) throw ApiException.badRequest("TOO_MANY_TAGS", "At most 8 tags")
        list.firstOrNull { !Regex("^[a-z0-9\\p{L}-]{1,24}$").matches(it) }?.let { throw ApiException.badRequest("INVALID_TAG", "Tags: letters, digits and dashes, at most 24 characters") }
    }
}

/**
 * Safe-render previews: the render worker screenshots the renderer's static output with JavaScript disabled and every network request
 * blocked (workers/render/preview.ts). Images are stored in object storage and served only to viewers who may see the template/block.
 * Without a browser on the worker the status is UNAVAILABLE and the UI falls back to the schema thumbnail.
 */
@Service
class PreviewService(private val render: RenderClient, private val store: ArtifactStore, private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    private fun table(kind: String) = if (kind == "template") "templates" else "component_packages"

    fun generate(kind: String, id: UUID, schema: JsonNode): String {
        val status = when (val r = render.preview(schema)) {
            is PreviewResult.Png -> {
                val key = "previews/$kind/$id/${com.systemwebstudio.publish.StaticSiteBuilder.sha256(r.bytes).take(16)}.png"
                runCatching { store.putOnce(key, r.bytes, "image/png") }.getOrElse { return mark(kind, id, "FAILED", null) }
                return mark(kind, id, "READY", key)
            }
            is PreviewResult.Unavailable -> "UNAVAILABLE"
            is PreviewResult.Failed -> "FAILED"
        }
        return mark(kind, id, status, null)
    }

    private fun mark(kind: String, id: UUID, status: String, key: String?): String {
        jdbc.update("UPDATE ${table(kind)} SET preview_status = ?, preview_key = coalesce(?, preview_key) WHERE id = ?", status, key, id); return status
    }

    /** a one-section page for a block preview */
    fun blockPage(base: String, version: String, props: JsonNode): JsonNode = json.createObjectNode().put("page", "block-preview").also {
        it.putArray("sections").addObject().put("id", "block-preview").put("type", base).put("componentVersion", version).set("props", props.deepCopy())
    }

    fun image(kind: String, id: UUID): ResponseEntity<ByteArray> {
        val key = jdbc.query("SELECT preview_key FROM ${table(kind)} WHERE id = ? AND preview_status = 'READY'", { rs, _ -> rs.getString(1) }, id).firstOrNull()
            ?: throw ApiException.notFound("PREVIEW_NOT_FOUND", "No preview image")
        val bytes = store.get(key) ?: throw ApiException.notFound("PREVIEW_NOT_FOUND", "No preview image")
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'").body(bytes)
    }
}

/**
 * Template review workflow (stage F), mirroring blocks: PRIVATE → submit → automated checks (synchronous, recorded) → REVIEW → a system admin
 * who is NOT the author approves (company library, visibility COMPANY) or rejects (back to PRIVATE with a reason) → ARCHIVED.
 */
@Service
class TemplateWorkflowService(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val templates: TemplateService, private val validator: PageSchemaValidator,
                              private val render: RenderClient, private val previews: PreviewService, private val audit: AuditService) {
    private val unsafeText = Regex("""<\s*/?\s*(script|iframe|object|embed|style|link|meta)\b|javascript\s*:|vbscript\s*:|data\s*:\s*text/html|\bon[a-z]+\s*=""", RegexOption.IGNORE_CASE)

    fun record(id: UUID, version: Int, actor: UUID?, decision: String, comment: String = "", checks: List<CheckResult>? = null) {
        jdbc.update("INSERT INTO template_reviews (id, template_id, version, actor_id, decision, comment, checks, created_at) VALUES (?,?,?,?,?,?,CAST(? AS jsonb), clock_timestamp())",
            UUID.randomUUID(), id, version, actor, decision, comment.take(1000), checks?.let { json.writeValueAsString(it) })
    }

    fun checks(t: TemplateDto): List<CheckResult> {
        val out = ArrayList<CheckResult>()
        val violations = validator.validate(t.schema)
        out += CheckResult("registry", violations.isEmpty(), violations.firstOrNull()?.let { "${it.path}: ${it.message}" } ?: "Mọi phần dùng component đã duyệt, thuộc tính hợp lệ")
        val refs = PageSchemaValidator.assetRefs(t.schema)
        out += CheckResult("no-files", refs.isEmpty(), if (refs.isEmpty()) "Không tham chiếu tệp của một dự án cụ thể" else "Chứa ${refs.size} tham chiếu ảnh của dự án")
        val size = json.writeValueAsString(t.schema).length
        out += CheckResult("size", size <= MAX_SCHEMA_CHARS && t.sections in 1..40, "$size / $MAX_SCHEMA_CHARS ký tự, ${t.sections} phần")
        val texts = ArrayList<String>(); fun walk(n: JsonNode) { when { n.isString -> texts += n.asString(); n.isArray -> n.forEach(::walk); n.isObject -> n.propertyNames().forEach { walk(n.get(it)) } } }
        walk(t.schema)
        val bad = texts.firstOrNull { unsafeText.containsMatchIn(it) }
        out += CheckResult("text-safety", bad == null, if (bad == null) "Không có mã nhúng (script, javascript:, on…=)" else "Nội dung trông giống mã nhúng: “${bad.take(60)}”")
        val rendered = if (violations.isEmpty()) runCatching { render.render(t.schema, emptyMap()) } else null
        val renderOk = rendered?.isSuccess == true && !Regex("<\\s*script", RegexOption.IGNORE_CASE).containsMatchIn(rendered.getOrThrow())
        out += CheckResult("render", renderOk, when { rendered == null -> "Bỏ qua vì schema chưa hợp lệ"; rendered.isFailure -> "Không render được: ${rendered.exceptionOrNull()?.message}"
            renderOk -> "Render tĩnh an toàn (không có script)"; else -> "Kết quả render chứa script" })
        val dup = jdbc.queryForObject("SELECT count(*) FROM templates WHERE lower(name) = lower(?) AND id <> ? AND review_status = 'APPROVED'", Long::class.java, t.name, t.id)!! > 0
        out += CheckResult("name", !dup, if (dup) "Đã có template khác của công ty cùng tên" else "Tên chưa trùng trong thư viện công ty")
        return out
    }

    fun submit(me: UUID, id: UUID): TemplateSubmitResult {
        val t = templates.visible(me, id)
        if (t.authorId != me) throw ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found")
        if (t.reviewStatus != "PRIVATE" || t.status != "ACTIVE") throw ApiException.conflict("NOT_SUBMITTABLE", "Only a private draft can be submitted (status ${t.reviewStatus})")
        jdbc.update("UPDATE templates SET review_status = 'SUBMITTED', submitted_at = now(), updated_at = now() WHERE id = ?", id)
        record(id, t.version, me, "SUBMITTED")
        val checks = checks(t); val passed = checks.all { it.ok }
        if (passed) {
            jdbc.update("UPDATE templates SET review_status = 'REVIEW', review_comment = NULL WHERE id = ?", id)
            record(id, t.version, null, "CHECKS_PASSED", checks = checks)
            previews.generate("template", id, t.schema)
        } else {
            jdbc.update("UPDATE templates SET review_status = 'PRIVATE', submitted_at = NULL WHERE id = ?", id)
            record(id, t.version, null, "CHECKS_FAILED", checks.filter { !it.ok }.joinToString("; ") { "${it.check}: ${it.message}" }, checks)
        }
        audit.record("SUBMIT_TEMPLATE", "TEMPLATE", id, newValue = mapOf("version" to t.version, "passed" to passed))
        return TemplateSubmitResult(templates.visible(me, id), passed, checks)
    }

    fun withdraw(me: UUID, id: UUID): TemplateDto {
        val t = templates.visible(me, id)
        if (t.authorId != me) throw ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found")
        if (t.reviewStatus != "REVIEW") throw ApiException.conflict("NOT_IN_REVIEW", "This template is not waiting for review")
        jdbc.update("UPDATE templates SET review_status = 'PRIVATE', submitted_at = NULL, updated_at = now() WHERE id = ?", id)
        record(id, t.version, me, "WITHDRAWN"); audit.record("WITHDRAW_TEMPLATE", "TEMPLATE", id)
        return templates.visible(me, id)
    }

    fun review(me: UUID, id: UUID, d: TemplateDecision): TemplateDto {
        val t = templates.visible(me, id)
        if (t.authorId == me) throw ApiException.forbidden("Authors cannot review their own template", "SELF_REVIEW")
        if (t.reviewStatus != "REVIEW") throw ApiException.conflict("NOT_IN_REVIEW", "This template is not waiting for review")
        val comment = d.comment?.trim().orEmpty()
        if (d.decision == "REJECT" && comment.isEmpty()) throw ApiException.badRequest("COMMENT_REQUIRED", "A rejection needs a reason for the author")
        if (d.decision == "APPROVE") {
            // checks again at decision time: the registry may have changed since submission
            val checks = checks(t)
            if (!checks.all { it.ok }) { record(id, t.version, null, "CHECKS_FAILED", checks.filter { !it.ok }.joinToString("; ") { "${it.check}: ${it.message}" }, checks)
                throw ApiException.conflict("CHECKS_FAILED", "The template no longer passes the automated checks", mapOf("checks" to checks)) }
            jdbc.update("""UPDATE templates SET review_status = 'APPROVED', visibility = 'COMPANY', reviewed_by = ?, reviewed_at = now(), review_comment = ?, updated_at = now() WHERE id = ?""",
                me, comment.ifEmpty { null }, id)
            if (t.previewStatus != "READY") previews.generate("template", id, t.schema)
        } else {
            jdbc.update("UPDATE templates SET review_status = 'PRIVATE', submitted_at = NULL, reviewed_by = ?, reviewed_at = now(), review_comment = ?, updated_at = now() WHERE id = ?", me, comment, id)
        }
        record(id, t.version, me, if (d.decision == "APPROVE") "APPROVED" else "REJECTED", comment)
        audit.record(if (d.decision == "APPROVE") "APPROVE_TEMPLATE" else "REJECT_TEMPLATE", "TEMPLATE", id, newValue = mapOf("version" to t.version, "comment" to comment))
        return templates.visible(me, id)
    }

    fun reviews(id: UUID): List<TemplateReviewDto> = jdbc.query("""SELECT r.id, r.version, coalesce(u.display_name, u.username), r.decision, r.comment, r.checks::text, r.created_at
        FROM template_reviews r LEFT JOIN users u ON u.id = r.actor_id WHERE r.template_id = ? ORDER BY r.created_at, r.id""", { rs, _ ->
        TemplateReviewDto(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)?.let { json.readTree(it) }, rs.getTimestamp(7).toInstant())
    }, id)

    companion object { const val MAX_SCHEMA_CHARS = 200_000 }
}

@RestController
class LibraryController(
    private val templates: TemplateService, private val workflow: TemplateWorkflowService, private val previews: PreviewService,
    private val blocks: ComponentPackageService, private val guard: AdminGuard, private val audit: AuditService, private val jdbc: JdbcTemplate
) {
    @GetMapping("/api/v1/library/categories")
    fun categories() = mapOf("templates" to LibraryCatalog.templateCategories, "blocks" to LibraryCatalog.blockCategories)

    @PatchMapping("/api/v1/templates/{id}/catalog")
    @Transactional
    fun templateCatalog(@PathVariable id: UUID, @Valid @RequestBody r: CatalogRequest, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto {
        val t = templates.visible(me.userId, id)
        if (!t.canEdit) throw ApiException.forbidden("Only the author (while private) or a system admin can change this template")
        r.category?.let { if (it !in LibraryCatalog.templateCategories) throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category") }
        val tags = LibraryCatalog.tags(r.tags)
        jdbc.update("UPDATE templates SET category = coalesce(?, category), tags = coalesce(string_to_array(?, ','), tags), updated_at = now() WHERE id = ?",
            r.category, tags?.joinToString(","), id)
        audit.record("UPDATE_TEMPLATE", "TEMPLATE", id, oldValue = mapOf("category" to t.category, "tags" to t.tags), newValue = mapOf("category" to (r.category ?: t.category), "tags" to (tags ?: t.tags)))
        return templates.visible(me.userId, id)
    }

    @PostMapping("/api/v1/templates/{id}/submit")
    @Transactional
    fun submit(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) = workflow.submit(me.userId, id)

    @PostMapping("/api/v1/templates/{id}/withdraw")
    @Transactional
    fun withdraw(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) = workflow.withdraw(me.userId, id)

    @GetMapping("/api/v1/templates/{id}/reviews")
    @Transactional(readOnly = true)
    fun reviews(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<TemplateReviewDto> {
        val t = templates.visible(me.userId, id)
        if (t.authorId != me.userId && !guard.isAdmin(me.userId)) throw ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found")
        return workflow.reviews(id)
    }

    @GetMapping("/api/v1/templates/{id}/preview")
    fun templatePreview(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<ByteArray> {
        templates.visible(me.userId, id); return previews.image("template", id)
    }

    @PostMapping("/api/v1/admin/templates/{id}/review")
    @Transactional
    fun review(@PathVariable id: UUID, @Valid @RequestBody d: TemplateDecision, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto {
        guard.require(me.userId); return workflow.review(me.userId, id, d)
    }

    @PostMapping("/api/v1/admin/templates/{id}/preview")
    fun regenerateTemplate(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        guard.require(me.userId)
        val t = templates.visible(me.userId, id)
        return mapOf("previewStatus" to previews.generate("template", id, t.schema))
    }

    @PatchMapping("/api/v1/component-packages/{id}/catalog")
    @Transactional
    fun blockCatalog(@PathVariable id: UUID, @Valid @RequestBody r: CatalogRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        val b = blocks.visible(me.userId, id); val admin = guard.isAdmin(me.userId)
        if (!(admin || (b.ownerId == me.userId && b.status != "REVIEW" && b.status != "DEPRECATED"))) throw ApiException.forbidden("Only the owner (outside review) or a system admin can change this block")
        r.category?.let { if (it !in LibraryCatalog.blockCategories) throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category") }
        val tags = LibraryCatalog.tags(r.tags)
        jdbc.update("UPDATE component_packages SET category = coalesce(?, category), tags = coalesce(string_to_array(?, ','), tags), updated_at = now() WHERE id = ?",
            r.category, tags?.joinToString(","), id)
        audit.record("UPDATE_BLOCK", "COMPONENT_PACKAGE", id, newValue = mapOf("category" to r.category, "tags" to tags))
        return meta(id)
    }

    fun meta(id: UUID): Map<String, Any> = jdbc.queryForMap("SELECT category, array_to_string(tags, ',') AS tags, usage_count, preview_status FROM component_packages WHERE id = ?", id)
        .let { mapOf("category" to it["category"]!!, "tags" to (it["tags"] as String).split(',').filter { s -> s.isNotEmpty() }, "usageCount" to it["usage_count"]!!, "previewStatus" to it["preview_status"]!!) }

    @GetMapping("/api/v1/component-packages/{id}/preview")
    fun blockPreview(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<ByteArray> {
        blocks.visible(me.userId, id); return previews.image("block", id)
    }

    @PostMapping("/api/v1/admin/component-packages/{id}/preview")
    fun regenerateBlock(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        guard.require(me.userId)
        val b = blocks.visible(me.userId, id)
        val v = blocks.versions(id).firstOrNull { it.version == (b.approved ?: b.latest) } ?: throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        return mapOf("previewStatus" to previews.generate("block", id, previews.blockPage(b.base, v.baseComponentVersion, v.props)))
    }
}
