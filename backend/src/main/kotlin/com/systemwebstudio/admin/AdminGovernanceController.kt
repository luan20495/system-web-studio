package com.systemwebstudio.admin

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.CommentRequest
import com.systemwebstudio.component.ComponentPackageService
import com.systemwebstudio.component.PackageDto
import com.systemwebstudio.component.ReviewRequest
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.template.TemplateDto
import com.systemwebstudio.template.TemplateService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class VisibilityRequest(@field:NotBlank @field:Pattern(regexp = "PRIVATE|COMPANY") val visibility: String)
data class TemplateStatusRequest(@field:NotBlank @field:Pattern(regexp = "ACTIVE|ARCHIVED") val status: String)
data class PackageQueue(val page: PageDto<PackageDto>, val counts: Map<String, Long>)

/** Component and template governance for system admins. Every decision is audited and, for blocks, kept in component_reviews. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminGovernanceController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService,
    private val packages: ComponentPackageService, private val templates: TemplateService
) {
    // ------------------------------------------------------------------ contributed blocks
    @GetMapping("/component-packages")
    @Transactional(readOnly = true)
    fun packages(@RequestParam(defaultValue = "REVIEW") status: String, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
                 @AuthenticationPrincipal me: StudioUserDetails): PackageQueue {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val filter = status.uppercase().takeIf { it in setOf("PRIVATE", "REVIEW", "APPROVED", "DEPRECATED") }
        val where = if (filter != null) "WHERE p.status = ?" else "WHERE TRUE"
        val args = listOfNotNull(filter).toTypedArray()
        val total = jdbc.queryForObject("SELECT count(*) FROM component_packages p $where", Long::class.java, *args) ?: 0
        val rows = packages.rows("$where ORDER BY p.updated_at DESC LIMIT $s OFFSET ${p.toLong() * s}", *args)
        val counts = jdbc.query("SELECT status, count(*) FROM component_packages GROUP BY status", { rs, _ -> rs.getString(1) to rs.getLong(2) }).toMap()
        return PackageQueue(PageDto(rows.map { packages.dto(it, me.userId, true, false, false) }, total, p, s), counts)
    }

    @GetMapping("/component-packages/{id}")
    @Transactional(readOnly = true)
    fun pkg(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        guard.require(me.userId)
        return packages.dto(packages.load(id) ?: throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found"), me.userId, true, true, false)
    }

    @PostMapping("/component-packages/{id}/review")
    @Transactional
    fun review(@PathVariable id: UUID, @Valid @RequestBody request: ReviewRequest, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        guard.require(me.userId)
        val r = packages.loadForUpdate(id) ?: throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        // separation of duties: nobody approves their own contribution, admins included
        if (r.ownerId == me.userId) throw ApiException(HttpStatus.FORBIDDEN, "SELF_REVIEW", "You cannot review your own block")
        if (r.status != "REVIEW") throw ApiException.conflict("NOT_IN_REVIEW", "This block is not waiting for review")
        if (request.version != null && request.version != r.latest) throw ApiException.conflict("STALE_REVIEW", "The block changed; reload before deciding", mapOf("latestVersion" to r.latest))
        val comment = request.comment?.trim().orEmpty()
        if (request.decision == "APPROVE") {
            // the registry may have changed since submission: validate again at decision time
            val checks = packages.checks(r, r.latest)
            if (!checks.all { it.ok }) throw ApiException.conflict("VALIDATION_FAILED", "The block no longer passes validation",
                mapOf("checks" to checks.filter { !it.ok }.map { mapOf("check" to it.check, "message" to it.message) }))
            jdbc.update("UPDATE component_package_versions SET status = 'SUPERSEDED' WHERE package_id = ? AND status = 'APPROVED'", id)
            jdbc.update("UPDATE component_package_versions SET status = 'APPROVED', decided_at = now() WHERE package_id = ? AND version = ?", id, r.latest)
            jdbc.update("UPDATE component_packages SET status = 'APPROVED', approved_version = ?, updated_at = now() WHERE id = ?", r.latest, id)
        } else {
            if (comment.isEmpty()) throw ApiException.badRequest("COMMENT_REQUIRED", "Say why the block is rejected so the author can fix it")
            jdbc.update("UPDATE component_package_versions SET status = 'REJECTED', decided_at = now() WHERE package_id = ? AND version = ?", id, r.latest)
            jdbc.update("UPDATE component_packages SET status = 'PRIVATE', updated_at = now() WHERE id = ?", id)
        }
        packages.record(id, r.latest, me.userId, request.decision, comment)
        audit.record(if (request.decision == "APPROVE") "APPROVE_BLOCK" else "REJECT_BLOCK", "COMPONENT_PACKAGE", id,
            newValue = mapOf("version" to r.latest, "owner" to r.ownerId, "comment" to comment.take(200)))
        return packages.dto(packages.load(id)!!, me.userId, true, true, false)
    }

    @PostMapping("/component-packages/{id}/deprecate")
    @Transactional
    fun deprecate(@PathVariable id: UUID, @Valid @RequestBody request: CommentRequest, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        guard.require(me.userId)
        val r = packages.loadForUpdate(id) ?: throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        if (r.status == "DEPRECATED") throw ApiException.conflict("ALREADY_DEPRECATED", "Already deprecated")
        jdbc.update("UPDATE component_packages SET status = 'DEPRECATED', updated_at = now() WHERE id = ?", id)
        packages.record(id, r.latest, me.userId, "DEPRECATE", request.comment?.trim().orEmpty())
        audit.record("DEPRECATE_BLOCK", "COMPONENT_PACKAGE", id, oldValue = mapOf("status" to r.status))
        return packages.dto(packages.load(id)!!, me.userId, true, true, false)
    }

    @PostMapping("/component-packages/{id}/restore")
    @Transactional
    fun restore(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): PackageDto {
        guard.require(me.userId)
        val r = packages.loadForUpdate(id) ?: throw ApiException.notFound("BLOCK_NOT_FOUND", "Block not found")
        if (r.status != "DEPRECATED") throw ApiException.conflict("NOT_DEPRECATED", "Only a deprecated block can be restored")
        val latestStatus = jdbc.queryForObject("SELECT status FROM component_package_versions WHERE package_id = ? AND version = ?", String::class.java, id, r.latest)!!
        jdbc.update("UPDATE component_packages SET status = ?, updated_at = now() WHERE id = ?", packages.statusFor(latestStatus), id)
        packages.record(id, r.latest, me.userId, "RESTORE")
        audit.record("RESTORE_BLOCK", "COMPONENT_PACKAGE", id)
        return packages.dto(packages.load(id)!!, me.userId, true, true, false)
    }

    // ------------------------------------------------------------------ templates
    @GetMapping("/templates")
    @Transactional(readOnly = true)
    fun templates(@RequestParam(required = false) visibility: String?, @RequestParam(required = false) status: String?, @RequestParam(required = false) q: String?,
                  @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
                  @AuthenticationPrincipal me: StudioUserDetails): PageDto<TemplateDto> {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val where = StringBuilder(" WHERE TRUE"); val args = mutableListOf<Any>()
        visibility?.uppercase()?.takeIf { it in setOf("PRIVATE", "COMPANY") }?.let { where.append(" AND t.visibility = ?"); args += it }
        status?.uppercase()?.takeIf { it in setOf("ACTIVE", "ARCHIVED") }?.let { where.append(" AND t.status = ?"); args += it }
        status?.uppercase()?.takeIf { it in setOf("PRIVATE", "SUBMITTED", "REVIEW", "APPROVED") }?.let { where.append(" AND t.review_status = ?"); args += it }
        like(q)?.let { where.append(" AND t.name ILIKE ?"); args += it }
        val total = jdbc.queryForObject("SELECT count(*) FROM templates t$where", Long::class.java, *args.toTypedArray()) ?: 0
        val items = jdbc.query("${templates.select}$where ORDER BY t.updated_at DESC LIMIT $s OFFSET ${p.toLong() * s}", { rs, _ -> templates.row(rs, me.userId, true) }, *args.toTypedArray())
        return PageDto(items, total, p, s)
    }

    @PostMapping("/templates/{id}/visibility")
    @Transactional
    fun visibility(@PathVariable id: UUID, @Valid @RequestBody request: VisibilityRequest, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto {
        guard.require(me.userId)
        val t = templates.visible(me.userId, id)
        if (request.visibility == "COMPANY" && t.status != "ACTIVE") throw ApiException.conflict("TEMPLATE_ARCHIVED", "Restore the template before sharing it")
        // the review lifecycle follows: sharing directly = approval by this admin, unsharing = back to a private draft
        jdbc.update("""UPDATE templates SET visibility = ?, review_status = CASE WHEN ? = 'COMPANY' THEN 'APPROVED' ELSE 'PRIVATE' END,
            reviewed_by = CASE WHEN ? = 'COMPANY' THEN ? ELSE reviewed_by END, reviewed_at = CASE WHEN ? = 'COMPANY' THEN now() ELSE reviewed_at END, updated_at = now() WHERE id = ?""",
            request.visibility, request.visibility, request.visibility, me.userId, request.visibility, id)
        audit.record("TEMPLATE_VISIBILITY", "TEMPLATE", id, oldValue = mapOf("visibility" to t.visibility), newValue = mapOf("visibility" to request.visibility))
        return templates.visible(me.userId, id)
    }

    @PostMapping("/templates/{id}/status")
    @Transactional
    fun templateStatus(@PathVariable id: UUID, @Valid @RequestBody request: TemplateStatusRequest, @AuthenticationPrincipal me: StudioUserDetails): TemplateDto {
        guard.require(me.userId)
        val t = templates.visible(me.userId, id)
        jdbc.update("""UPDATE templates SET status = ?, review_status = CASE WHEN ? = 'ARCHIVED' THEN 'ARCHIVED' WHEN visibility = 'COMPANY' THEN 'APPROVED' ELSE 'PRIVATE' END,
            updated_at = now() WHERE id = ?""", request.status, request.status, id)
        audit.record(if (request.status == "ARCHIVED") "ARCHIVE_TEMPLATE" else "RESTORE_TEMPLATE", "TEMPLATE", id, oldValue = mapOf("status" to t.status))
        return templates.visible(me.userId, id)
    }
}
