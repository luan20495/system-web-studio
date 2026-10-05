package com.systemwebstudio.component

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.schema.PageSchemaValidator
import com.systemwebstudio.template.TemplateService
import com.systemwebstudio.version.SchemaRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class PackageVersionDto(
    val version: Int, val baseComponentVersion: String, val props: JsonNode, val status: String, val validation: JsonNode?,
    val sourceProjectId: UUID?, val createdAt: Instant, val submittedAt: Instant?, val decidedAt: Instant?
)
data class PackageReviewDto(val id: UUID, val version: Int, val actorId: UUID?, val actor: String?, val decision: String, val comment: String, val createdAt: Instant)
data class PackageDto(
    val id: UUID, val name: String, val description: String, val baseComponent: String, val ownerId: UUID, val owner: String?,
    val status: String, val latestVersion: Int, val approvedVersion: Int?, val createdAt: Instant, val updatedAt: Instant,
    /** the props to insert: the approved version in the company library, the latest version for the owner and reviewers */
    val current: PackageVersionDto?,
    val versions: List<PackageVersionDto> = emptyList(), val reviews: List<PackageReviewDto> = emptyList(),
    val canEdit: Boolean = false, val canReview: Boolean = false,
    /** catalog (stage F); usageCount = inserts through the Studio (counted server-side on a successful page edit) */
    val category: String = "general", val tags: List<String> = emptyList(), val usageCount: Int = 0, val previewStatus: String = "NONE"
)
data class CheckResult(val check: String, val ok: Boolean, val message: String)
data class SubmitResult(val block: PackageDto, val passed: Boolean, val checks: List<CheckResult>)
data class SaveBlockRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$") val sectionId: String,
    @field:NotBlank @field:Size(max = 80) val name: String,
    @field:Size(max = 500) val description: String? = null,
    /** save as a new version of this block (same base component) instead of creating a block */
    val packageId: UUID? = null
)
data class SaveBlockResult(val block: PackageDto, val removedImages: Int)
data class UpdateBlockRequest(@field:Size(min = 1, max = 80) val name: String? = null, @field:Size(max = 500) val description: String? = null)
data class ReviewRequest(@field:NotBlank @field:Pattern(regexp = "APPROVE|REJECT") val decision: String, val version: Int? = null, @field:Size(max = 1000) val comment: String? = null)
data class CommentRequest(@field:Size(max = 1000) val comment: String? = null)

/**
 * Contributed components ("blocks"): a reviewed preset of an APPROVED registry component. Workflow:
 *   PRIVATE (draft, owner only) → submit → automated validation (synchronous; SUBMITTED/VALIDATING are recorded as review
 *   events) → REVIEW → a system admin who is NOT the owner approves (company library) or rejects (back to PRIVATE with a reason)
 *   → DEPRECATED (hidden from the library; pages that already use it are not touched).
 * A block stores only props data; inserting one creates an ordinary section of the base component that goes through the same
 * validator and renderer as any edit. Nothing executable is stored, so the registry's security guarantee is unchanged.
 */
@Service
class ComponentPackageService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val registry: ComponentRegistry,
    private val validator: PageSchemaValidator, private val guard: AdminGuard, private val audit: AuditService
) {
    class Row(val id: UUID, val name: String, val description: String, val base: String, val ownerId: UUID, val owner: String?, val status: String,
              val latest: Int, val approved: Int?, val createdAt: Instant, val updatedAt: Instant,
              val category: String = "general", val tags: List<String> = emptyList(), val usage: Int = 0, val preview: String = "NONE")

    private val select = """SELECT p.id, p.name, p.description, p.base_component, p.owner_id, coalesce(u.display_name, u.username), p.status,
        p.latest_version, p.approved_version, p.created_at, p.updated_at, p.category, array_to_string(p.tags, ','), p.usage_count, p.preview_status
        FROM component_packages p LEFT JOIN users u ON u.id = p.owner_id"""
    private fun row(rs: java.sql.ResultSet) = Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java),
        rs.getString(6), rs.getString(7), rs.getInt(8), rs.getObject(9)?.let { (it as Number).toInt() }, rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant(),
        rs.getString(12), rs.getString(13).split(',').filter { it.isNotEmpty() }, rs.getInt(14), rs.getString(15))

    fun rows(where: String, vararg args: Any?): List<Row> = jdbc.query("$select $where", { rs, _ -> row(rs) }, *args)
    fun load(id: UUID): Row? = rows("WHERE p.id = ? ", id).firstOrNull()
    fun loadForUpdate(id: UUID): Row? = rows("WHERE p.id = ? FOR UPDATE OF p", id).firstOrNull()

    fun versions(id: UUID): List<PackageVersionDto> = jdbc.query(
        """SELECT version, base_component_version, props::text, status, validation::text, source_project_id, created_at, submitted_at, decided_at
           FROM component_package_versions WHERE package_id = ? ORDER BY version DESC""", { rs, _ ->
            PackageVersionDto(rs.getInt(1), rs.getString(2), json.readTree(rs.getString(3)), rs.getString(4), rs.getString(5)?.let { json.readTree(it) },
                rs.getObject(6, UUID::class.java), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8)?.toInstant(), rs.getTimestamp(9)?.toInstant())
        }, id)

    fun reviews(id: UUID): List<PackageReviewDto> = jdbc.query(
        """SELECT r.id, r.version, r.actor_id, coalesce(u.display_name, u.username), r.decision, r.comment, r.created_at
           FROM component_reviews r LEFT JOIN users u ON u.id = r.actor_id WHERE r.package_id = ? ORDER BY r.created_at, r.id""", { rs, _ ->
            PackageReviewDto(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant())
        }, id)

    fun listed(r: Row) = r.approved != null && r.status != "DEPRECATED"

    fun dto(r: Row, viewer: UUID, admin: Boolean, detailed: Boolean, libraryView: Boolean): PackageDto {
        val owner = r.ownerId == viewer
        val all = versions(r.id)
        val current = if (libraryView || !(owner || admin)) all.firstOrNull { it.version == r.approved } else all.firstOrNull { it.version == r.latest }
        val full = detailed && (owner || admin)
        return PackageDto(r.id, r.name, r.description, r.base, r.ownerId, r.owner, r.status, r.latest, r.approved, r.createdAt, r.updatedAt, current,
            if (full) all else emptyList(), if (full) reviews(r.id) else emptyList(),
            canEdit = owner && r.status != "REVIEW" && r.status != "DEPRECATED", canReview = admin && !owner && r.status == "REVIEW",
            category = r.category, tags = r.tags, usageCount = r.usage, previewStatus = r.preview)
    }

    /** Owner, admin, or anyone once it is in the company library; otherwise 404 so private block ids are not confirmed. */
    fun visible(viewer: UUID, id: UUID): Row {
        val r = load(id)
        if (r == null || !(r.ownerId == viewer || listed(r) || guard.isAdmin(viewer))) throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        return r
    }

    fun record(id: UUID, version: Int, actor: UUID?, decision: String, comment: String = "") {
        // clock_timestamp(): events of one transaction (submit + validation) keep their real order; now() would tie
        jdbc.update("INSERT INTO component_reviews (id, package_id, version, actor_id, decision, comment, created_at) VALUES (?,?,?,?,?,?, clock_timestamp())",
            UUID.randomUUID(), id, version, actor, decision, comment.take(1000))
    }

    /** status of the package from the state of its latest version */
    fun statusFor(versionStatus: String) = when (versionStatus) { "REVIEW" -> "REVIEW"; "APPROVED" -> "APPROVED"; else -> "PRIVATE" }

    // -------------------------------------------------------------------------------------------- automated validation
    private val unsafeText = Regex("""<\s*/?\s*(script|iframe|object|embed|style|link|meta)\b|javascript\s*:|vbscript\s*:|data\s*:\s*text/html|\bon[a-z]+\s*=""", RegexOption.IGNORE_CASE)

    fun checks(r: Row, version: Int): List<CheckResult> {
        val v = versions(r.id).firstOrNull { it.version == version } ?: return listOf(CheckResult("version", false, "Phiên bản không tồn tại"))
        val out = ArrayList<CheckResult>()
        val comp = registry.list().firstOrNull { it.id == r.base }
        val entry = registry.versions()["${r.base}@${v.baseComponentVersion}"]
        val registryOk = comp != null && comp.status == "ACTIVE" && entry != null && entry.dto.status == "ACTIVE"
        out += CheckResult("registry", registryOk, if (registryOk) "Dựa trên component đã duyệt ${r.base}@${v.baseComponentVersion}" else "Component gốc ${r.base}@${v.baseComponentVersion} không còn được duyệt")
        val page = json.createObjectNode().put("page", "block-check")
        page.putArray("sections").addObject().put("id", "block-check").put("type", r.base).put("componentVersion", v.baseComponentVersion).set("props", v.props.deepCopy())
        val violations = if (registryOk) validator.validate(page) else emptyList()
        out += CheckResult("props", registryOk && violations.isEmpty(),
            if (!registryOk) "Không kiểm tra được thuộc tính" else violations.firstOrNull()?.let { "${it.path.removePrefix("sections[0].props.")}: ${it.message}" } ?: "Thuộc tính hợp lệ theo schema của component")
        val refs = PageSchemaValidator.assetRefs(v.props)
        out += CheckResult("no-files", refs.isEmpty(), if (refs.isEmpty()) "Không tham chiếu tệp của một dự án cụ thể" else "Chứa ${refs.size} tham chiếu ảnh của dự án")
        val size = json.writeValueAsString(v.props).length
        out += CheckResult("size", size <= MAX_PROPS_CHARS, "$size / $MAX_PROPS_CHARS ký tự")
        val texts = ArrayList<String>(); fun walk(n: JsonNode) { when { n.isString -> texts += n.asString(); n.isArray -> n.forEach(::walk); n.isObject -> n.propertyNames().forEach { walk(n.get(it)) } } }
        walk(v.props)
        val bad = texts.firstOrNull { unsafeText.containsMatchIn(it) }
        out += CheckResult("text-safety", bad == null, if (bad == null) "Không có mã nhúng (script, javascript:, on…=)" else "Nội dung trông giống mã nhúng: “${bad.take(60)}”")
        val dup = jdbc.queryForObject("SELECT count(*) FROM component_packages WHERE lower(name) = lower(?) AND id <> ? AND approved_version IS NOT NULL AND status <> 'DEPRECATED'",
            Long::class.java, r.name, r.id)!! > 0
        out += CheckResult("name", !dup, if (dup) "Đã có khối khác của công ty cùng tên" else "Tên chưa trùng trong thư viện công ty")
        return out
    }

    companion object { const val MAX_PROPS_CHARS = 16_000 }
}

@RestController
class ComponentPackageController(
    private val svc: ComponentPackageService, private val access: AccessService, private val repo: SchemaRepository,
    private val registry: ComponentRegistry, private val guard: AdminGuard, private val audit: AuditService,
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val previews: com.systemwebstudio.template.PreviewService
) {
    @GetMapping("/api/v1/component-packages")
    @Transactional(readOnly = true)
    fun list(@RequestParam(defaultValue = "company") scope: String, @AuthenticationPrincipal me: StudioUserDetails): List<PackageDto> {
        val admin = guard.isAdmin(me.userId)
        return if (scope == "mine") svc.rows("WHERE p.owner_id = ? ORDER BY p.updated_at DESC LIMIT 200", me.userId).map { svc.dto(it, me.userId, admin, true, false) }
        else svc.rows("WHERE p.approved_version IS NOT NULL AND p.status <> 'DEPRECATED' ORDER BY p.name LIMIT 200").map { svc.dto(it, me.userId, admin, false, true) }
    }

    @GetMapping("/api/v1/component-packages/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): PackageDto =
        svc.dto(svc.visible(me.userId, id), me.userId, guard.isAdmin(me.userId), true, false)

    /** Saves a section of the project's STORED page (the client sends only its id) as a private block or a new draft version. */
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/component-packages")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun saveFromSection(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: SaveBlockRequest,
                        @AuthenticationPrincipal me: StudioUserDetails): SaveBlockResult {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val section = repo.currentSchema(projectId)?.get("sections")?.firstOrNull { it.get("id")?.asString() == request.sectionId }
            ?: throw ApiException.notFound("SECTION_NOT_FOUND", "Section not found on the saved page")
        val type = section.get("type").asString(); val baseVersion = section.get("componentVersion").asString()
        val comp = registry.list().firstOrNull { it.id == type }
        if (comp == null || comp.status != "ACTIVE") throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPONENT_NOT_ELIGIBLE", "Only sections of approved components can become blocks")
        val (props, removed) = TemplateService.stripAssets(section.get("props"))
        val propsJson = json.writeValueAsString(props)
        val name = request.name.trim(); val description = request.description?.trim().orEmpty()
        val id: UUID
        if (request.packageId != null) {
            val r = svc.loadForUpdate(request.packageId)
            if (r == null || r.ownerId != me.userId) throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
            if (r.base != type) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BASE_MISMATCH", "A new version must use the same component (${r.base})")
            if (r.status == "REVIEW") throw ApiException.conflict("IN_REVIEW", "Withdraw the submission before changing the block")
            if (r.status == "DEPRECATED") throw ApiException.conflict("DEPRECATED", "This block is deprecated")
            val latestStatus = jdbc.queryForObject("SELECT status FROM component_package_versions WHERE package_id = ? AND version = ?", String::class.java, r.id, r.latest)
            val version = if (latestStatus == "DRAFT" || latestStatus == "REJECTED") {
                jdbc.update("""UPDATE component_package_versions SET props = CAST(? AS jsonb), base_component_version = ?, status = 'DRAFT', validation = NULL,
                    source_project_id = ?, created_by = ?, created_at = now(), submitted_at = NULL, decided_at = NULL WHERE package_id = ? AND version = ?""",
                    propsJson, baseVersion, projectId, me.userId, r.id, r.latest)
                r.latest
            } else {
                jdbc.update("""INSERT INTO component_package_versions (package_id, version, base_component, base_component_version, props, source_project_id, created_by)
                    VALUES (?,?,?,?,CAST(? AS jsonb),?,?)""", r.id, r.latest + 1, type, baseVersion, propsJson, projectId, me.userId)
                r.latest + 1
            }
            jdbc.update("UPDATE component_packages SET name = ?, description = ?, latest_version = ?, status = 'PRIVATE', updated_at = now() WHERE id = ?",
                name, description, version, r.id)
            audit.record("UPDATE_BLOCK", "COMPONENT_PACKAGE", r.id, workspaceId, projectId, newValue = mapOf("version" to version, "removedImages" to removed))
            id = r.id
        } else {
            id = UUID.randomUUID()
            jdbc.update("INSERT INTO component_packages (id, name, description, base_component, owner_id) VALUES (?,?,?,?,?)", id, name, description, type, me.userId)
            jdbc.update("""INSERT INTO component_package_versions (package_id, version, base_component, base_component_version, props, source_project_id, created_by)
                VALUES (?,1,?,?,CAST(? AS jsonb),?,?)""", id, type, baseVersion, propsJson, projectId, me.userId)
            audit.record("CREATE_BLOCK", "COMPONENT_PACKAGE", id, workspaceId, projectId, newValue = mapOf("name" to name, "base" to type, "removedImages" to removed))
        }
        return SaveBlockResult(svc.dto(svc.load(id)!!, me.userId, guard.isAdmin(me.userId), true, false), removed)
    }

    @PatchMapping("/api/v1/component-packages/{id}")
    @Transactional
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateBlockRequest, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        val admin = guard.isAdmin(me.userId)
        val r = svc.visible(me.userId, id)
        val ownerMayEdit = r.ownerId == me.userId && r.status != "REVIEW" && r.status != "DEPRECATED"
        if (!ownerMayEdit && !admin) throw ApiException.forbidden("Only the owner (outside review) or a system admin can rename this block")
        jdbc.update("UPDATE component_packages SET name = ?, description = ?, updated_at = now() WHERE id = ?", request.name?.trim() ?: r.name, request.description?.trim() ?: r.description, id)
        audit.record("UPDATE_BLOCK", "COMPONENT_PACKAGE", id, oldValue = mapOf("name" to r.name), newValue = mapOf("name" to (request.name?.trim() ?: r.name)))
        return svc.dto(svc.load(id)!!, me.userId, admin, true, false)
    }

    @PostMapping("/api/v1/component-packages/{id}/submit")
    @Transactional
    fun submit(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): SubmitResult {
        val r = svc.loadForUpdate(id)
        if (r == null || r.ownerId != me.userId) throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        if (r.status != "PRIVATE") throw ApiException.conflict("NOT_SUBMITTABLE", "Only a private draft can be submitted (status ${r.status})")
        svc.record(id, r.latest, me.userId, "SUBMIT")
        val checks = svc.checks(r, r.latest)
        val passed = checks.all { it.ok }
        val report = json.writeValueAsString(checks)
        if (passed) {
            jdbc.update("UPDATE component_package_versions SET status = 'REVIEW', validation = CAST(? AS jsonb), submitted_at = now() WHERE package_id = ? AND version = ?", report, id, r.latest)
            jdbc.update("UPDATE component_packages SET status = 'REVIEW', updated_at = now() WHERE id = ?", id)
            svc.record(id, r.latest, null, "VALIDATION_PASSED")
            svc.versions(id).firstOrNull { it.version == r.latest }?.let { v -> previews.generate("block", id, previews.blockPage(r.base, v.baseComponentVersion, v.props)) }
        } else {
            jdbc.update("UPDATE component_package_versions SET status = 'DRAFT', validation = CAST(? AS jsonb) WHERE package_id = ? AND version = ?", report, id, r.latest)
            svc.record(id, r.latest, null, "VALIDATION_FAILED", checks.filter { !it.ok }.joinToString("; ") { "${it.check}: ${it.message}" })
        }
        audit.record("SUBMIT_BLOCK", "COMPONENT_PACKAGE", id, newValue = mapOf("version" to r.latest, "passed" to passed))
        return SubmitResult(svc.dto(svc.load(id)!!, me.userId, guard.isAdmin(me.userId), true, false), passed, checks)
    }

    @PostMapping("/api/v1/component-packages/{id}/withdraw")
    @Transactional
    fun withdraw(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        val r = svc.loadForUpdate(id)
        if (r == null || r.ownerId != me.userId) throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        if (r.status != "REVIEW") throw ApiException.conflict("NOT_IN_REVIEW", "This block is not waiting for review")
        jdbc.update("UPDATE component_package_versions SET status = 'DRAFT', submitted_at = NULL WHERE package_id = ? AND version = ?", id, r.latest)
        jdbc.update("UPDATE component_packages SET status = 'PRIVATE', updated_at = now() WHERE id = ?", id)
        svc.record(id, r.latest, me.userId, "WITHDRAW")
        audit.record("WITHDRAW_BLOCK", "COMPONENT_PACKAGE", id, newValue = mapOf("version" to r.latest))
        return svc.dto(svc.load(id)!!, me.userId, guard.isAdmin(me.userId), true, false)
    }

    /** Only blocks that were never in the company library can be deleted (approved ones are deprecated by an admin instead). */
    @DeleteMapping("/api/v1/component-packages/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        val r = svc.loadForUpdate(id)
        if (r == null || r.ownerId != me.userId) throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        if (r.approved != null || r.status == "REVIEW") throw ApiException.conflict("NOT_DELETABLE", "Blocks that are in review or were approved cannot be deleted")
        jdbc.update("DELETE FROM component_packages WHERE id = ?", id)
        audit.record("DELETE_BLOCK", "COMPONENT_PACKAGE", id, oldValue = mapOf("name" to r.name))
    }
}
