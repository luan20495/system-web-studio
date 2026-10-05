package com.systemwebstudio.publish

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class FormSubmissionDto(val id: UUID, val formId: String, val pageId: String, val data: Map<String, String>, val createdAt: Instant)

/**
 * Website forms (stage G). A published ContactForm is a plain HTML form (no script) that posts to `<site>/_forms/<sectionId>`. The server
 * accepts it only for a live website whose CURRENT deployment contains that ContactForm; validates every field; drops spam silently
 * (honeypot field, link flood) and rate-limits per visitor and per site; checks the Origin; stores the submission for the project's editors.
 */
@Service
class FormService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val limiter: RateLimiter,
    @Value("\${app.forms.ip-salt:}") salt: String,
    @Value("\${app.forms.max-per-visitor:5}") private val perVisitor: Long,
    @Value("\${app.forms.max-per-site-per-hour:200}") private val perSite: Long
) {
    /** without a configured salt a random one per process is used (hashes are then comparable only within one run) */
    private val ipSalt = salt.ifBlank { ByteArray(24).also(SecureRandom()::nextBytes).let { Base64.getEncoder().encodeToString(it) } }
    private val email = Regex("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}\\.[A-Za-z]{2,24}$")
    private val phone = Regex("^[0-9+().\\- ]{0,30}$")
    private val link = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

    fun ipHash(ip: String): String = MessageDigest.getInstance("SHA-256").digest("$ipSalt|$ip".toByteArray()).joinToString("") { "%02x".format(it) }

    /** the sections of every page of the deployed schema version */
    fun deployedForm(deploymentId: UUID, formId: String): String? {
        val schema = jdbc.query("""SELECT v.schema_snapshot::text FROM deployments d JOIN project_versions v ON v.id = d.version_id WHERE d.id = ?""",
            { rs, _ -> rs.getString(1) }, deploymentId).firstOrNull()?.let { json.readTree(it) } ?: return null
        fun find(secs: JsonNode?) = secs?.firstOrNull { it.get("id")?.asString() == formId && it.get("type")?.asString() == "ContactForm" }
        if (find(schema.get("sections")) != null) return "home"
        return schema.get("pages")?.firstOrNull { find(it.get("sections")) != null }?.get("id")?.asString()
    }

    sealed interface Outcome { object Stored : Outcome; object Dropped : Outcome; class Invalid(val message: String) : Outcome }

    fun submit(site: LiveSite, formId: String, fields: Map<String, String>, ip: String, userAgent: String?): Outcome {
        if (!Regex("^[a-z0-9][a-z0-9-]{0,63}$").matches(formId)) return Outcome.Invalid("Biểu mẫu không tồn tại.")
        val page = deployedForm(site.deploymentId, formId) ?: return Outcome.Invalid("Biểu mẫu không tồn tại.")
        val hash = ipHash(ip)
        val visitor = limiter.hit("form:${site.projectId}:$hash", perVisitor, 600)
        val all = limiter.hit("form:${site.projectId}", perSite, 3600)
        if (!visitor.allowed || !all.allowed) throw ApiException.tooManyRequests("Too many form submissions; retry later.", maxOf(visitor.retryAfterSeconds, all.retryAfterSeconds))
        // spam: the hidden honeypot is filled by bots; a flood of links is not a contact request. The visitor sees the normal thank-you page.
        if (!fields["website"].isNullOrBlank()) return Outcome.Dropped
        val name = fields["name"]?.trim().orEmpty(); val mail = fields["email"]?.trim().orEmpty()
        val tel = fields["phone"]?.trim().orEmpty(); val message = fields["message"]?.trim().orEmpty()
        when {
            name.isEmpty() || name.length > 100 -> return Outcome.Invalid("Vui lòng nhập họ tên (tối đa 100 ký tự).")
            !email.matches(mail) || mail.length > 200 -> return Outcome.Invalid("Email không hợp lệ.")
            !phone.matches(tel) -> return Outcome.Invalid("Số điện thoại không hợp lệ.")
            message.isEmpty() || message.length > 2000 -> return Outcome.Invalid("Vui lòng nhập nội dung (tối đa 2000 ký tự).")
        }
        if (link.findAll(message).count() > 3 || link.containsMatchIn(name)) return Outcome.Dropped
        jdbc.update("""INSERT INTO form_submissions (id, project_id, deployment_id, form_id, page_id, data, ip_hash, user_agent) VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?)""",
            UUID.randomUUID(), site.projectId, site.deploymentId, formId, page,
            json.writeValueAsString(linkedMapOf("name" to name, "email" to mail, "phone" to tel, "message" to message)), hash, userAgent?.take(300))
        return Outcome.Stored
    }

    fun list(projectId: UUID, limit: Int, offset: Int): Pair<List<FormSubmissionDto>, Long> {
        val total = jdbc.queryForObject("SELECT count(*) FROM form_submissions WHERE project_id = ?", Long::class.java, projectId) ?: 0
        @Suppress("UNCHECKED_CAST")
        val rows = jdbc.query("SELECT id, form_id, page_id, data::text, created_at FROM form_submissions WHERE project_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?", { rs, _ ->
            FormSubmissionDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), json.readValue(rs.getString(4), Map::class.java) as Map<String, String>, rs.getTimestamp(5).toInstant())
        }, projectId, limit, offset)
        return rows to total
    }
}

/** The public endpoint the published form posts to (through the sites gateway). Answers with small static pages, never JSON or scripts. */
@RestController
class SiteFormController(private val sites: SiteService, private val forms: FormService, private val domains: SiteDomainService) {
    private fun page(response: HttpServletResponse, status: Int, title: String, text: String, back: String) {
        response.status = status
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("Referrer-Policy", "no-referrer"); response.setHeader("Cache-Control", "no-store")
        response.contentType = "text/html; charset=utf-8"
        fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        response.writer.write("""<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
            <title>${e(title)}</title><style>body{font:16px system-ui,sans-serif;max-width:560px;margin:12vh auto;padding:0 20px;color:#173b49}a{color:#0b6f63}</style></head>
            <body><h1>${e(title)}</h1><p>${e(text)}</p><p><a href="${e(back)}">Quay lại trang</a></p></body></html>""")
    }

    private fun handle(site: LiveSite?, formId: String, root: String, request: HttpServletRequest, response: HttpServletResponse, allowedOrigins: Set<String>) {
        if (site == null || site.kind == "STATIC_APP") return page(response, 404, "Không tìm thấy", "Trang này không tồn tại.", root)
        // forms of private sites are not offered; their pages are for signed-in members and have no public form endpoint
        if (site.visibility != "PUBLIC") return page(response, 403, "Không gửi được", "Biểu mẫu chỉ hoạt động trên website công khai.", root)
        val origin = request.getHeader("Origin")
        if (origin != null && origin != "null" && origin.trimEnd('/') !in allowedOrigins) return page(response, 403, "Không gửi được", "Yêu cầu không đến từ website này.", root)
        val fields = listOf("name", "email", "phone", "message", "website", "_page").associateWith { request.getParameter(it).orEmpty().take(4000) }
        val pageSlug = fields["_page"]?.takeIf { it != "home" }?.let { pid -> sites.pageSlug(site.deploymentId, pid) }
        val back = root + (pageSlug?.let { "$it/" } ?: "") + "#contact"
        when (val r = forms.submit(site, formId, fields, request.remoteAddr, request.getHeader("User-Agent"))) {
            is FormService.Outcome.Invalid -> page(response, 400, "Chưa gửi được", r.message, back)
            else -> page(response, 200, "Cảm ơn bạn!", "Chúng tôi đã nhận được thông tin và sẽ liên hệ lại sớm.", back)
        }
    }

    @PostMapping("/sites/{slug}/_forms/{formId}", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun submit(@PathVariable slug: String, @PathVariable formId: String, request: HttpServletRequest, response: HttpServletResponse) =
        handle(sites.live(slug), formId, "/$slug/", request, response, setOf(sites.sitesOrigin.trimEnd('/')))

    @PostMapping("/sites/_host/_forms/{formId}", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun submitOnDomain(@PathVariable formId: String, request: HttpServletRequest, response: HttpServletResponse) {
        val host = domains.requestHost(request)
        handle(host?.let { domains.liveFor(it) }, formId, "/", request, response, setOfNotNull(host?.let { "https://$it" }))
    }
}

/** Studio: the project's form submissions (personal data: editors only; export and deletion are audited). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/form-submissions")
class FormSubmissionController(private val access: AccessService, private val forms: FormService, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    @GetMapping
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestParam(defaultValue = "0") page: Int,
             @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_EDIT)
        val size = 50; val (rows, total) = forms.list(projectId, size, page.coerceAtLeast(0) * size)
        return mapOf("items" to rows, "total" to total, "page" to page, "size" to size)
    }

    @GetMapping("/export")
    fun export(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<ByteArray> {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_EDIT)
        val (rows, total) = forms.list(projectId, 10_000, 0)
        // CSV: every cell quoted; cells that a spreadsheet would treat as formulas are prefixed with '
        fun cell(s: String) = "\"" + (if (s.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$s" else s).replace("\"", "\"\"") + "\""
        val csv = buildString {
            append("﻿\"created_at\",\"form\",\"page\",\"name\",\"email\",\"phone\",\"message\"\r\n")
            rows.forEach { r -> append(listOf(r.createdAt.toString(), r.formId, r.pageId, r.data["name"].orEmpty(), r.data["email"].orEmpty(), r.data["phone"].orEmpty(), r.data["message"].orEmpty())
                .joinToString(",") { cell(it) }).append("\r\n") }
        }
        audit.record("FORM_SUBMISSIONS_EXPORTED", "PROJECT", projectId, workspaceId, projectId, newValue = mapOf("rows" to rows.size, "total" to total))
        return ResponseEntity.ok().contentType(MediaType("text", "csv", Charsets.UTF_8)).header("Content-Disposition", "attachment; filename=\"form-submissions.csv\"")
            .header("Cache-Control", "no-store").body(csv.toByteArray(Charsets.UTF_8))
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_EDIT)
        if (jdbc.update("DELETE FROM form_submissions WHERE id = ? AND project_id = ?", id, projectId) == 0) throw ApiException.notFound("SUBMISSION_NOT_FOUND", "Submission not found")
        audit.record("FORM_SUBMISSION_DELETED", "FORM_SUBMISSION", id, workspaceId, projectId)
    }
}
