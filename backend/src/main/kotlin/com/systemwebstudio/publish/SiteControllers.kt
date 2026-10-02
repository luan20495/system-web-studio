package com.systemwebstudio.publish

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.storage.ArtifactStore
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * What the sites gateway forwards to (`https://<sites-host>/<slug>/<file>` → `/sites/<slug>/<file>`). Never reached through the Studio
 * origin. Runs in its own stateless security chain (no Studio session, no CSRF); headers are set here per response.
 */
@RestController
class SiteServingController(
    private val sites: SiteService, private val store: ArtifactStore,
    @Value("\${app.sites.cookie-name:site_session}") private val cookieName: String,
    @Value("\${app.sites.cookie-secure:false}") private val cookieSecure: Boolean
) {
    companion object {
        /** Page-schema sites contain no scripts: nothing may run, be framed, post forms or load from elsewhere. */
        const val SITE_CSP = "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }

    private fun common(response: HttpServletResponse) {
        response.setHeader("Content-Security-Policy", SITE_CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin")
    }

    private fun page(response: HttpServletResponse, status: Int, title: String, text: String) {
        common(response)
        response.status = status
        response.contentType = "text/html; charset=utf-8"
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.writer.write("""<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
            <style>body{font:16px system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;background:#f6f7f9;color:#1d2433}main{max-width:460px;padding:24px;text-align:center}</style></head>
            <body><main><h1>$title</h1><p>$text</p></main></body></html>""")
    }

    @GetMapping("/sites/_access")
    fun access(@RequestParam(required = false) ticket: String?, response: HttpServletResponse) {
        val t = ticket?.let { sites.redeemTicket(it) } ?: return page(response, 400, "Liên kết đã hết hạn", "Hãy mở lại trang từ Studio.")
        val (userId, slug, path) = t
        val session = sites.openSession(userId)
        response.addHeader("Set-Cookie", "$cookieName=$session; Path=/; Max-Age=${sites.sessionSeconds}; HttpOnly; SameSite=Lax${if (cookieSecure) "; Secure" else ""}")
        response.setHeader("Cache-Control", "no-store")
        response.status = 302
        response.setHeader("Location", "/$slug$path")
    }

    @GetMapping("/sites/{slug}")
    fun noSlash(@PathVariable slug: String, response: HttpServletResponse) {
        response.status = 301; response.setHeader("Location", "/$slug/")
    }

    @GetMapping("/sites/{slug}/**")
    fun serve(@PathVariable slug: String, request: HttpServletRequest, response: HttpServletResponse,
              @CookieValue(name = "\${app.sites.cookie-name:site_session}", required = false) cookie: String?) {
        val site = sites.live(slug) ?: return page(response, 404, "Không tìm thấy trang", "Trang này không tồn tại hoặc đã được gỡ xuống.")
        val raw = request.requestURI.substringAfter("/sites/$slug/", "")
        val path = runCatching { URLDecoder.decode(raw, Charsets.UTF_8) }.getOrNull()?.ifEmpty { "index.html" }
            ?: return page(response, 400, "Đường dẫn không hợp lệ", "")
        if (".." in path || path.startsWith("/") || path.any { it == '\\' || it.isISOControl() }) return page(response, 400, "Đường dẫn không hợp lệ", "")
        val private = site.visibility == "PRIVATE"
        if (private) {
            val user = sites.sessionUser(cookie)
            if (user == null) {
                response.setHeader("Cache-Control", "no-store"); response.status = 302
                response.setHeader("Location", "${sites.studioOrigin.trimEnd('/')}/studio/site-access?site=$slug&path=${URLEncoder.encode("/$raw", Charsets.UTF_8)}")
                return
            }
            if (!sites.canRead(user, site)) return page(response, 403, "Bạn không có quyền xem trang này", "Trang riêng tư chỉ dành cho thành viên của ứng dụng.")
        }
        val file = site.files[path] ?: return page(response, 404, "Không tìm thấy", "Tệp này không có trong trang đã xuất bản.")
        common(response)
        response.setHeader("ETag", "\"${file.sha256}\"")
        // Revalidate every time (cheap: ETag → 304) instead of caching for a while: a site can switch to private, roll back or go offline,
        // and no gateway/CDN/browser copy may keep serving the old content after that. (Purge-based edge caching is a later optimisation.)
        // no-transform: intermediaries (e.g. Cloudflare's analytics beacon injection) must not add scripts to a script-free site
        response.setHeader("Cache-Control", if (private) "private, no-store, no-transform" else "public, no-cache, no-transform")
        if (request.getHeader("If-None-Match") == "\"${file.sha256}\"") { response.status = 304; return }
        val bytes = store.get("${site.prefix}/$path") ?: return page(response, 404, "Không tìm thấy", "")
        // integrity: serve only bytes that match the manifest recorded at build time
        if (StaticSiteBuilder.sha256(bytes) != file.sha256) return page(response, 500, "Trang bị lỗi", "Nội dung không khớp bản đã xuất bản.")
        response.contentType = file.contentType
        response.setContentLength(bytes.size)
        if (request.method != "HEAD") response.outputStream.write(bytes)
    }
}

data class SiteInfo(val slug: String?, val url: String?, val online: Boolean, val visibility: String?, val currentDeploymentId: UUID?,
                    val currentVersionNumber: Int?, val provider: String, val updatedAt: Instant?)
data class AccessTicketRequest(@field:Size(max = 512) val path: String? = null)
data class RollbackRequest(@field:NotNull val deploymentId: UUID?)

/** Studio-side site management and private-site access tickets (Studio origin, normal session + CSRF). */
@RestController
class SiteManagementController(
    private val sites: SiteService, private val access: AccessService, private val audit: AuditService, private val jdbc: JdbcTemplate,
    private val provider: com.systemwebstudio.integration.deploy.DeployProvider
) {
    private fun info(projectId: UUID): SiteInfo {
        val row = jdbc.query("""SELECT s.slug, s.current_deployment_id, d.visibility, d.version_id, s.updated_at FROM sites s
            LEFT JOIN deployments d ON d.id = s.current_deployment_id WHERE s.project_id = ?""", { rs, _ ->
            listOf(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getTimestamp(5)?.toInstant())
        }, projectId).firstOrNull()
        val slug = row?.get(0) as String?; val current = row?.get(1) as UUID?
        val version = (row?.get(3) as UUID?)?.let { jdbc.queryForObject("SELECT version_number FROM project_versions WHERE id = ?", Int::class.java, it) }
        return SiteInfo(slug, slug?.let { sites.url(it) }, current != null, row?.get(2) as String?, current, version, provider.name, row?.get(4) as Instant?)
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site")
    @Transactional(readOnly = true)
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId)
        return info(projectId)
    }

    /** Serve an earlier successful deployment again (no rebuild; its artifact is immutable). */
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site/rollback")
    @Transactional
    fun rollback(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: RollbackRequest,
                 @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_PUBLISH)
        val ok = jdbc.queryForObject("SELECT count(*) FROM deployments WHERE id = ? AND project_id = ? AND status = 'RUNNING' AND artifact_id IS NOT NULL",
            Long::class.java, request.deploymentId, projectId)!! > 0
        if (!ok) throw ApiException.badRequest("DEPLOYMENT_NOT_RESTORABLE", "Only a successful deployment with an artifact can be served again")
        if (jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ?", Long::class.java, projectId)!! == 0L) throw ApiException.notFound("SITE_NOT_FOUND", "This project has no site")
        val before = info(projectId)
        sites.point(projectId, request.deploymentId)
        jdbc.update("UPDATE projects SET site_visibility = (SELECT visibility FROM deployments WHERE id = ?) WHERE id = ?", request.deploymentId, projectId)
        audit.record("SITE_ROLLBACK", "SITE", projectId, workspaceId, projectId, oldValue = mapOf("deploymentId" to before.currentDeploymentId), newValue = mapOf("deploymentId" to request.deploymentId))
        return info(projectId)
    }

    /** Take the site offline; deployments and artifacts are kept, so it can be served again with rollback. */
    @DeleteMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site")
    @Transactional
    fun unpublish(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_PUBLISH)
        val before = info(projectId)
        sites.point(projectId, null)
        audit.record("SITE_UNPUBLISHED", "SITE", projectId, workspaceId, projectId, oldValue = mapOf("deploymentId" to before.currentDeploymentId))
        return info(projectId)
    }

    /** Signed-in member → single-use ticket → the sites origin turns it into its own session (the Studio cookie never leaves the Studio host). */
    @PostMapping("/api/v1/sites/{slug}/access-ticket")
    fun ticket(@PathVariable slug: String, @Valid @RequestBody(required = false) request: AccessTicketRequest?, @AuthenticationPrincipal me: StudioUserDetails): Map<String, String> {
        val (projectId, workspaceId) = sites.projectBySlug(slug) ?: throw ApiException.notFound("SITE_NOT_FOUND", "Site not found")
        val ctx = try { access.forProject(me.userId, workspaceId, projectId) } catch (e: ApiException) { throw ApiException.notFound("SITE_NOT_FOUND", "Site not found") }
        ctx.require(Permission.PROJECT_READ)
        val t = sites.issueTicket(me.userId, slug, request?.path)
        return mapOf("redirect" to "${sites.sitesOrigin.trimEnd('/')}/_access?ticket=$t")
    }
}
