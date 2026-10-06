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
    private val sites: SiteService, private val store: ArtifactStore, private val json: tools.jackson.databind.json.JsonMapper,
    private val domains: SiteDomainService,
    @Value("\${app.sites.cookie-name:site_session}") private val cookieName: String,
    @Value("\${app.sites.cookie-secure:false}") private val cookieSecure: Boolean
) {
    companion object {
        /** Page-schema sites contain no scripts: nothing may run, be framed or load from elsewhere; forms may only post to the site itself. */
        const val SITE_CSP = "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
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

    @GetMapping("/sites/_preview/{token}")
    fun previewNoSlash(@PathVariable token: String, response: HttpServletResponse) { response.status = 301; response.setHeader("Location", "/_preview/$token/") }

    /**
     * Preview of a code change (generated JavaScript). Capability URL (unguessable, expiring) and CSP `sandbox allow-scripts`:
     * the browser runs the app in an opaque origin, so it cannot read cookies, storage or pages of the sites host or the Studio.
     */
    @GetMapping("/sites/_preview/{token}/**")
    fun preview(@PathVariable token: String, request: HttpServletRequest, response: HttpServletResponse) {
        val (prefix, files) = sites.preview(token) ?: return page(response, 404, "Bản xem trước không còn", "Liên kết đã hết hạn hoặc thay đổi đã bị huỷ.")
        val raw = request.requestURI.substringAfter("/sites/_preview/$token/", "")
        if (raw == "__factory/config.json") return runtimeConfig(response, sites.runtimeConfig(sites.previewProject(token)!!, "preview", "PUBLIC", null))
        serveFile(prefix, files, raw, private = false, app = true, frameAncestors = sites.studioOrigin, preview = true, request, response)
    }

    /** Private code app content (see SiteService.openAppToken): token → user, membership re-checked, never cached. */
    @GetMapping("/sites/_app/{token}/**")
    fun privateApp(@PathVariable token: String, request: HttpServletRequest, response: HttpServletResponse) {
        val (user, slug) = sites.appToken(token) ?: return page(response, 404, "Phiên đã hết hạn", "Hãy mở lại ứng dụng từ địa chỉ của nó.")
        val site = sites.live(slug)?.takeIf { it.visibility == "PRIVATE" && it.kind == "STATIC_APP" } ?: return page(response, 404, "Không tìm thấy", "")
        if (!sites.canRead(user, site)) return page(response, 403, "Bạn không có quyền xem ứng dụng này", "")
        val raw = request.requestURI.substringAfter("/sites/_app/$token/", "")
        if (raw == "__factory/config.json") return runtimeConfig(response, sites.runtimeConfig(site.projectId, "production", "PRIVATE", user))
        serveFile(site.prefix, site.files, raw, private = true, app = true, frameAncestors = null, preview = false, request, response)
    }

    /** A verified custom domain (gateway: any Host other than the sites host → /sites/_host/<path>). Public websites only. */
    @GetMapping("/sites/_host/**")
    fun serveHost(request: HttpServletRequest, response: HttpServletResponse) {
        val host = domains.requestHost(request)
        val site = host?.let { domains.liveFor(it) }
        if (site == null || site.kind == "STATIC_APP" || site.visibility != "PUBLIC") return page(response, 404, "Không tìm thấy trang", "Tên miền này chưa được kết nối với website nào.")
        serveFile(site.prefix, site.files, request.requestURI.substringAfter("/sites/_host/", ""), false, false, null, preview = false, request, response, root = "/")
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
        val private = site.visibility == "PRIVATE"
        val app = site.kind == "STATIC_APP"
        if (private) {
            val user = sites.sessionUser(cookie)
            if (user == null) {
                response.setHeader("Cache-Control", "no-store"); response.status = 302
                response.setHeader("Location", "${sites.studioOrigin.trimEnd('/')}/studio/site-access?site=$slug&path=${URLEncoder.encode("/$raw", Charsets.UTF_8)}")
                return
            }
            if (!sites.canRead(user, site)) return page(response, 403, "Bạn không có quyền xem trang này", "Trang riêng tư chỉ dành cho thành viên của ứng dụng.")
            if (app) {
                response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer"); response.status = 302
                response.setHeader("Location", "/_app/${sites.openAppToken(user, slug)}/$raw"); return
            }
        }
        if (app && raw == "__factory/config.json") return runtimeConfig(response, sites.runtimeConfig(site.projectId, "production", site.visibility, null))
        serveFile(site.prefix, site.files, raw, private, app, null, preview = false, request, response, root = "/$slug/")
    }

    /** The site's own 404 page when it has one (website, not app), with its link to the home page pointed at this site's root. */
    private fun notFound(prefix: String, files: Map<String, ManifestFile>, app: Boolean, root: String?, request: HttpServletRequest, response: HttpServletResponse) {
        val nf = files["404.html"]
        val bytes = if (!app && nf != null && root != null) store.get("$prefix/404.html")?.takeIf { StaticSiteBuilder.sha256(it) == nf.sha256 } else null
        if (bytes == null) return page(response, 404, "Không tìm thấy", "Tệp này không có trong bản đã xuất bản.")
        common(response)
        response.status = 404; response.contentType = "text/html; charset=utf-8"; response.setHeader("Cache-Control", "no-store")
        // verified bytes; the only change is the fixed token → the site's root path (computed here, never from the request)
        val html = bytes.toString(Charsets.UTF_8).replace("__SITE_ROOT__", root!!.replace("\"", ""))
        if (request.method != "HEAD") response.writer.write(html)
    }

    private fun runtimeConfig(response: HttpServletResponse, cfg: Map<String, Any?>) {
        common(response)
        response.setHeader("Access-Control-Allow-Origin", "*"); response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "application/json"; response.writer.write(json.writeValueAsString(cfg))
    }

    private fun serveFile(prefix: String, files: Map<String, ManifestFile>, raw: String, private: Boolean, app: Boolean, frameAncestors: String?, preview: Boolean,
                          request: HttpServletRequest, response: HttpServletResponse, root: String? = null) {
        val decoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8) }.getOrNull() ?: return page(response, 400, "Đường dẫn không hợp lệ", "")
        if (".." in decoded || decoded.startsWith("/") || decoded.any { it == '\\' || it.isISOControl() }) return page(response, 400, "Đường dẫn không hợp lệ", "")
        // directory URLs of a multi-page site: "" → index.html, "about/" → about/index.html, "about" → redirect to "about/"
        val path = when {
            decoded.isEmpty() -> "index.html"
            decoded.endsWith("/") -> "${decoded}index.html"
            files[decoded] == null && files["$decoded/index.html"] != null -> { response.status = 301; response.setHeader("Location", "${decoded.substringAfterLast('/')}/"); return }
            else -> decoded
        }
        // the server part of a server app (bundle, routes) is for the runtime only, never served as a file
        if (app && (path.startsWith("server/") || path == "openapi.json")) return page(response, 404, "Không tìm thấy", "")
        val file = files[path] ?: return notFound(prefix, files, app, root, request, response)
        common(response)
        if (app) {
            val o = sites.sitesOrigin.trimEnd('/')
            response.setHeader("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; script-src 'self' $o; style-src 'self' $o 'unsafe-inline'; " +
                "img-src 'self' $o data: blob:; font-src 'self' $o; connect-src 'self' $o; base-uri 'none'; form-action 'none'; frame-ancestors ${frameAncestors ?: "'none'"}")
            // the sandboxed (opaque-origin) document loads its scripts/styles with CORS and without credentials
            response.setHeader("Access-Control-Allow-Origin", "*")
            response.setHeader("Cross-Origin-Resource-Policy", "cross-origin")
            response.setHeader("Cross-Origin-Opener-Policy", "unsafe-none")
        }
        if (preview) response.setHeader("X-Robots-Tag", "noindex, nofollow")
        response.setHeader("ETag", "\"${file.sha256}\"")
        // Revalidate every time (cheap: ETag → 304) instead of caching for a while: a site can switch to private, roll back or go offline,
        // and no gateway/CDN/browser copy may keep serving the old content after that. (Purge-based edge caching is a later optimisation.)
        // no-transform: intermediaries (e.g. Cloudflare's analytics beacon injection) must not add scripts to a script-free site
        // CDN: files under assets/ are addressed by an asset id whose bytes never change → cacheable for a year at every layer (still
        // only for public sites); pages keep revalidating so unpublish / rollback / visibility changes apply at once
        response.setHeader("Cache-Control", when {
            private || preview -> "private, no-store, no-transform"
            path.startsWith("assets/") && !app -> "public, max-age=31536000, immutable, no-transform"
            else -> "public, no-cache, no-transform"
        })
        if (request.getHeader("If-None-Match") == "\"${file.sha256}\"") { response.status = 304; return }
        val bytes = store.get("$prefix/$path") ?: return page(response, 404, "Không tìm thấy", "")
        // integrity: serve only bytes that match the manifest recorded at build time
        if (StaticSiteBuilder.sha256(bytes) != file.sha256) return page(response, 500, "Trang bị lỗi", "Nội dung không khớp bản đã xuất bản.")
        response.contentType = file.contentType
        response.setContentLength(bytes.size)
        if (request.method != "HEAD") response.outputStream.write(bytes)
    }
}

/** deployment states the sites gateway serves (same rule as SiteService.live): a pointer at a FAILED deployment is not online */
private val SERVED_STATUSES = setOf("DEPLOYING", "RUNNING")

data class SiteInfo(val slug: String?, val url: String?, val online: Boolean, val visibility: String?, val currentDeploymentId: UUID?,
                    val currentVersionNumber: Int?, val provider: String, val updatedAt: Instant?)
data class AccessTicketRequest(@field:Size(max = 512) val path: String? = null)
data class RollbackRequest(@field:NotNull val deploymentId: UUID?)

/** Studio-side site management and private-site access tickets (Studio origin, normal session + CSRF). */
@RestController
class SiteManagementController(
    private val sites: SiteService, private val access: AccessService, private val audit: AuditService, private val jdbc: JdbcTemplate,
    private val provider: com.systemwebstudio.integration.deploy.DeployProvider, private val releases: ReleaseService
) {
    private fun info(projectId: UUID): SiteInfo {
        val row = jdbc.query("""SELECT s.slug, s.current_deployment_id, d.visibility, d.version_id, s.updated_at, d.status FROM sites s
            LEFT JOIN deployments d ON d.id = s.current_deployment_id WHERE s.project_id = ?""", { rs, _ ->
            listOf(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getTimestamp(5)?.toInstant(), rs.getString(6))
        }, projectId).firstOrNull()
        val slug = row?.get(0) as String?; val current = row?.get(1) as UUID?
        val version = (row?.get(3) as UUID?)?.let { jdbc.queryForObject("SELECT version_number FROM project_versions WHERE id = ?", Int::class.java, it) }
        return SiteInfo(slug, slug?.let { sites.url(it) }, current != null && (row?.get(5) as String?) in SERVED_STATUSES, row?.get(2) as String?, current, version, provider.name, row?.get(4) as Instant?)
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site")
    @Transactional(readOnly = true)
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId)
        return info(projectId)
    }

    /**
     * Serve an earlier successful deployment again (no rebuild; its artifact is immutable). The artifact is verified first (record, checksum,
     * every file in the store); if it cannot be served the site is left untouched and the answer is 409 ROLLBACK_FAILED with the reason, which is
     * also kept in the deployment's history. Repeating a rollback to the release that is already active changes nothing.
     * noRollbackFor: the failure record must survive the error response.
     */
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site/rollback")
    @Transactional(noRollbackFor = [ApiException::class])
    fun rollback(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: RollbackRequest,
                 @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_PUBLISH)
        val ok = jdbc.queryForObject("SELECT count(*) FROM deployments d JOIN artifacts a ON a.id = d.artifact_id AND a.deleted_at IS NULL WHERE d.id = ? AND d.project_id = ? AND d.status = 'RUNNING'",
            Long::class.java, request.deploymentId, projectId)!! > 0
        if (!ok) throw ApiException.badRequest("DEPLOYMENT_NOT_RESTORABLE", "Only a successful deployment with an artifact can be served again")
        if (jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ?", Long::class.java, projectId)!! == 0L) throw ApiException.notFound("SITE_NOT_FOUND", "This project has no site")
        val before = info(projectId)
        when (val result = releases.rollback(ReleaseScope(workspaceId, projectId), request.deploymentId!!)) {
            is RollbackResult.AlreadyActive -> return before
            is RollbackResult.Failed -> throw ApiException.conflict("ROLLBACK_FAILED", "The release could not be restored: ${result.reason}")
            else -> audit.record("SITE_ROLLBACK", "SITE", projectId, workspaceId, projectId, oldValue = mapOf("deploymentId" to before.currentDeploymentId), newValue = mapOf("deploymentId" to request.deploymentId))
        }
        return info(projectId)
    }

    /** Take the site offline; deployments and artifacts are kept, so it can be served again with rollback. */
    @DeleteMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/site")
    @Transactional
    fun unpublish(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): SiteInfo {
        access.forProject(me.userId, workspaceId, projectId).require(Permission.PROJECT_PUBLISH)
        val before = info(projectId)
        releases.unpublish(ReleaseScope(workspaceId, projectId)) { sites.point(projectId, null) }
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
