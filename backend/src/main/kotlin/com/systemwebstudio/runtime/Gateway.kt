package com.systemwebstudio.runtime

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.code.CodeProjectService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.publish.LiveSite
import com.systemwebstudio.publish.SiteService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.net.InetAddress

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
private const val MAX_BODY = 1_000_000
private const val MAX_RESPONSE = 5_000_000

/** Outbound requests the platform makes for users (connectors, TLS checks) may only reach public internet addresses. */
object PublicAddress {
    fun isPublic(host: String): Boolean {
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addrs.isNotEmpty() && addrs.none { a ->
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||                       // fc00::/7 unique local
                a.hostAddress.startsWith("100.") && a.address[1].toInt().and(0xff) in 64..127 ||                  // 100.64.0.0/10 carrier-grade NAT
                a.hostAddress == "169.254.169.254" || a.hostAddress.startsWith("0.")
        }
    }
}

/** "/api/items/{id}" matches "/api/items/42": literal segments exactly, {param} = one non-empty segment */
internal fun templateMatches(template: String, path: String): Boolean {
    val t = template.trimEnd('/').split('/'); val p = path.trimEnd('/').split('/')
    return t.size == p.size && t.zip(p).all { (a, b) -> if (a.startsWith("{") && a.endsWith("}")) b.isNotEmpty() else a == b }
}

private fun readBody(request: HttpServletRequest): ByteArray {
    val bytes = request.inputStream.readNBytes(MAX_BODY + 1)
    if (bytes.size > MAX_BODY) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", "Request body larger than 1 MB")
    return bytes
}

/**
 * Runtime gateway for server apps (ADR 0017): `<site>/<slug>/api/…` (public apps) and `<site>/_app/<token>/api/…` (private apps, the
 * member's capability path) → the API → the apps gateway → the app container. Only routes declared in the deployed openapi.json pass;
 * cookies and Authorization never reach the app; a private app receives the signed-in member in X-Factory-User.
 */
@RestController
class AppGatewayController(private val sites: SiteService, private val runtime: ServerRuntimeService, private val jdbc: JdbcTemplate,
                           private val json: JsonMapper, private val limiter: RateLimiter) {
    private fun cors(response: HttpServletResponse) {
        // the app UI runs in a sandboxed (opaque-origin) document: it can only call its API with CORS and without credentials
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE")
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept")
        response.setHeader("Access-Control-Max-Age", "600")
    }
    private fun error(response: HttpServletResponse, status: Int, message: String) {
        cors(response); response.status = status; response.contentType = "application/json"
        response.setHeader("Cache-Control", "no-store"); response.writer.write(json.writeValueAsString(mapOf("error" to message)))
    }

    private fun serverKind(site: LiveSite) = site.kind == "STATIC_APP" &&
        jdbc.queryForObject("SELECT app_kind FROM projects WHERE id = ?", String::class.java, site.projectId) in CodeProjectService.SERVER_KINDS

    @RequestMapping("/sites/{slug}/api/**")
    fun publicApi(@PathVariable slug: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (request.method == "OPTIONS") { cors(response); response.status = 204; return }
        val site = sites.live(slug)?.takeIf { serverKind(it) } ?: return error(response, 404, "not found")
        if (site.visibility != "PUBLIC") return error(response, 403, "private app: open it from its address")
        proxy(site, null, request.requestURI.substringAfter("/sites/$slug"), request, response)
    }

    @RequestMapping("/sites/_app/{token}/api/**")
    fun privateApi(@PathVariable token: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (request.method == "OPTIONS") { cors(response); response.status = 204; return }
        val (user, slug) = sites.appToken(token) ?: return error(response, 401, "session expired")
        val site = sites.live(slug)?.takeIf { it.visibility == "PRIVATE" && serverKind(it) } ?: return error(response, 404, "not found")
        if (!sites.canRead(user, site)) return error(response, 403, "no access")
        proxy(site, user, request.requestURI.substringAfter("/sites/_app/$token"), request, response)
    }

    private fun proxy(site: LiveSite, user: UUID?, path: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (!runtime.available) return error(response, 503, "server apps are disabled")
        val (deployment, routes) = runtime.liveRoutes(site.projectId) ?: return error(response, 503, "the app is not running")
        val method = request.method.uppercase()
        val decoded = runCatching { java.net.URLDecoder.decode(path, Charsets.UTF_8) }.getOrNull()
        if (decoded == null || ".." in decoded || decoded.any { it.isISOControl() }) return error(response, 400, "invalid path")
        val allowed = routes.any { r -> r.method == method && templateMatches(r.path, path) }
        if (!allowed) return error(response, 404, "route not declared in openapi.json")
        val rl = limiter.hit("app-api:${site.projectId}:${request.remoteAddr}", 300, 60)
        if (!rl.allowed) return error(response, 429, "too many requests")
        val body = if (method in setOf("POST", "PUT", "PATCH", "DELETE")) readBody(request) else ByteArray(0)
        val target = "${runtime.gatewayUrl.trimEnd('/')}/${runtime.containerName(site.projectId, deployment)}$path" + (request.queryString?.let { "?$it" } ?: "")
        val b = HttpRequest.newBuilder(URI(target)).timeout(Duration.ofSeconds(20)).header("X-Gateway-Token", runtime.gatewayToken)
            .header("Accept", request.getHeader("Accept") ?: "application/json")
        request.getHeader("Content-Type")?.let { b.header("Content-Type", it.take(200)) }
        if (user != null) {
            val u = jdbc.queryForMap("SELECT id, username, coalesce(display_name, username) AS name, email FROM users WHERE id = ?", user)
            val value = json.writeValueAsString(mapOf("id" to u["id"].toString(), "username" to u["username"], "displayName" to u["name"], "email" to u["email"]))
            // signed with the app's own APP_TOKEN so the app can reject a forged header (scaffolds verify it: t=<epoch s>,sig=HMAC-SHA256(t + "." + value))
            val t = Instant.now().epochSecond
            b.header("X-Factory-User", value).header("X-Factory-Signature", "t=$t,sig=${runtime.signUser(site.projectId, "$t.$value")}")
        }
        val res = try { http.send(b.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: Exception) { return error(response, 502, "the app did not answer") }
        val out = res.body().use { it.readNBytes(MAX_RESPONSE + 1) }
        if (out.size > MAX_RESPONSE) return error(response, 502, "response too large")
        cors(response)
        response.status = res.statusCode()
        val type = res.headers().firstValue("Content-Type").orElse("application/json")
        // the API answers data, never documents that could run in the sites origin
        response.contentType = if (type.startsWith("text/html")) "text/plain; charset=utf-8" else type.take(200)
        response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("Cache-Control", "no-store")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        if (method != "HEAD" && out.isNotEmpty()) response.outputStream.write(out)
    }
}

/**
 * CORS for server app APIs, before anything else: the app UI runs in a sandboxed (opaque-origin) document, so its calls are cross-origin
 * and credential-less. Preflights are answered here (any origin, no credentials, the methods/headers an API call needs).
 */
@org.springframework.stereotype.Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class AppApiCorsFilter : org.springframework.web.filter.OncePerRequestFilter() {
    private val paths = Regex("^/sites/(_app/[A-Za-z0-9_-]{20,100}|[a-z0-9][a-z0-9-]{1,79})/api(/.*)?$")
    override fun shouldNotFilter(request: HttpServletRequest) = !paths.matches(request.requestURI)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: jakarta.servlet.FilterChain) {
        response.setHeader("Access-Control-Allow-Origin", "*")
        if (request.method == "OPTIONS") {
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE")
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept")
            response.setHeader("Access-Control-Max-Age", "600")
            response.status = 204; return
        }
        chain.doFilter(request, response)
    }
}

// ---------------------------------------------------------------- connectors

data class ConnectorOperation(val method: String, val path: String)
data class ConnectorDto(val key: String, val name: String, val description: String, val baseUrl: String, val authHeader: String?, val hasSecret: Boolean,
                        val operations: List<ConnectorOperation>, val status: String, val grants: Int, val createdAt: Instant)
data class ConnectorRequest(@field:NotBlank @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,39}$") val key: String, @field:NotBlank @field:Size(max = 120) val name: String,
                            @field:Size(max = 500) val description: String? = null, @field:NotBlank @field:Size(max = 500) val baseUrl: String,
                            @field:Pattern(regexp = "^[A-Za-z][A-Za-z0-9-]{0,63}$") val authHeader: String? = null,
                            /** write-only; empty = keep the stored value */
                            @field:Size(max = 4000) val authValue: String? = null,
                            @field:Size(max = 50) val operations: List<ConnectorOperation> = emptyList())
data class GrantRequest(val projectId: UUID)

/**
 * Connector proxy (ADR 0017): an app container calls `http://apps-gateway:8081/_connectors/<key>/<path>` with its APP_TOKEN; the apps gateway
 * adds its own token and forwards here. The API checks the app is granted that connector and the operation is declared, adds the
 * connector's credential (decrypted only here) and calls the approved HTTPS base URL. The app never sees the credential.
 */
@RestController
class ConnectorProxyController(private val jdbc: JdbcTemplate, private val crypto: SecretsCrypto, private val runtime: ServerRuntimeService, private val json: JsonMapper,
                               private val limiter: RateLimiter) {
    @RequestMapping("/internal/connectors/{key}/**")
    fun call(@PathVariable key: String, request: HttpServletRequest, response: HttpServletResponse) {
        fun fail(status: Int, msg: String) { response.status = status; response.contentType = "application/json"; response.writer.write(json.writeValueAsString(mapOf("error" to msg))) }
        val gw = request.getHeader("X-Gateway-Token") ?: ""
        if (runtime.gatewayToken.isBlank() || !MessageDigest.isEqual(gw.toByteArray(), runtime.gatewayToken.toByteArray())) return fail(404, "not found")
        val appToken = request.getHeader("X-App-Token") ?: return fail(401, "missing app token")
        val hash = MessageDigest.getInstance("SHA-256").digest(appToken.toByteArray()).joinToString("") { "%02x".format(it) }
        val project = jdbc.query("SELECT project_id FROM app_runtimes WHERE app_token_hash = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, hash).firstOrNull() ?: return fail(401, "invalid app token")
        val c = jdbc.queryForList("""SELECT c.base_url, c.auth_header, c.auth_value_enc, c.operations::text FROM connectors c JOIN project_connectors g ON g.connector_key = c.key
            WHERE c.key = ? AND g.project_id = ? AND c.status = 'APPROVED'""", key, project).firstOrNull() ?: return fail(403, "connector not granted to this app")
        if (!limiter.hit("connector:$project:$key", 600, 60).allowed) return fail(429, "too many connector calls")
        val rest = request.requestURI.substringAfter("/internal/connectors/$key", "")
        if (".." in rest || rest.any { it.isISOControl() }) return fail(400, "invalid path")
        val method = request.method.uppercase()
        val ops = json.readValue(c["operations"] as String, Array<ConnectorOperation>::class.java).toList()
        val ok = ops.any { o -> o.method.uppercase() == method && templateMatches(o.path, rest) }
        if (!ok) return fail(403, "operation not allowed for this connector")
        val base = URI(c["base_url"] as String)
        val target = URI((c["base_url"] as String).trimEnd('/') + rest + (request.queryString?.let { "?$it" } ?: ""))
        if (target.scheme != "https" || target.host != base.host) return fail(400, "target outside the connector")
        if (!PublicAddress.isPublic(target.host)) return fail(403, "connector host resolves to a non-public address")
        val body = if (method in setOf("POST", "PUT", "PATCH", "DELETE")) readBody(request) else ByteArray(0)
        val b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20)).header("Accept", request.getHeader("Accept") ?: "application/json")
        request.getHeader("Content-Type")?.let { b.header("Content-Type", it.take(200)) }
        val header = c["auth_header"] as String?; val secret = (c["auth_value_enc"] as String?)?.let { crypto.decrypt(it) }
        if (header != null && secret != null) b.header(header, secret)
        val res = try { http.send(b.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: Exception) { return fail(502, "connector did not answer") }
        val out = res.body().use { it.readNBytes(MAX_RESPONSE + 1) }
        if (out.size > MAX_RESPONSE) return fail(502, "response too large")
        response.status = res.statusCode(); response.contentType = res.headers().firstValue("Content-Type").orElse("application/json").take(200)
        response.outputStream.write(out)
    }
}

@RestController
@RequestMapping("/api/v1/admin/connectors")
class AdminConnectorController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val crypto: SecretsCrypto, private val json: JsonMapper, private val audit: AuditService) {
    private fun list() = jdbc.query("""SELECT c.key, c.name, c.description, c.base_url, c.auth_header, c.auth_value_enc IS NOT NULL, c.operations::text, c.status,
        (SELECT count(*) FROM project_connectors g WHERE g.connector_key = c.key), c.created_at FROM connectors c ORDER BY c.key""") { rs, _ ->
        ConnectorDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
            json.readValue(rs.getString(7), Array<ConnectorOperation>::class.java).toList(), rs.getString(8), rs.getInt(9), rs.getTimestamp(10).toInstant())
    }

    @GetMapping
    fun get(@AuthenticationPrincipal me: StudioUserDetails): List<ConnectorDto> { guard.require(me.userId); return list() }

    @PutMapping
    @Transactional
    fun upsert(@Valid @RequestBody r: ConnectorRequest, @AuthenticationPrincipal me: StudioUserDetails): List<ConnectorDto> {
        guard.require(me.userId)
        val uri = runCatching { URI(r.baseUrl.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null) throw ApiException.badRequest("INVALID_BASE_URL", "Base URL: https://host[/path], no credentials or query")
        val host = uri.host.lowercase()
        if (host == "localhost" || Regex("^[0-9.]+$|^\\[|:").containsMatchIn(host) || host.endsWith(".internal") || host.endsWith(".local"))
            throw ApiException.badRequest("INVALID_BASE_URL", "Connectors must target a public host name")
        r.operations.forEach { o ->
            if (o.method.uppercase() !in setOf("GET", "POST", "PUT", "PATCH", "DELETE") || !Regex("^(/[A-Za-z0-9._{}-]+)*/?$").matches(o.path))
                throw ApiException.badRequest("INVALID_OPERATION", "Operation: GET|POST|PUT|PATCH|DELETE and a path like /items/{id}")
        }
        if (!r.authValue.isNullOrEmpty() && !crypto.available) throw ApiException.conflict("SECRETS_UNAVAILABLE", "Connector credentials need SECRETS_MASTER_KEY")
        val enc = r.authValue?.takeIf { it.isNotEmpty() }?.let { crypto.encrypt(it) }
        jdbc.update("""INSERT INTO connectors (key, name, description, base_url, auth_header, auth_value_enc, operations, created_by) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)
            ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, base_url = EXCLUDED.base_url, auth_header = EXCLUDED.auth_header,
            auth_value_enc = coalesce(EXCLUDED.auth_value_enc, connectors.auth_value_enc), operations = EXCLUDED.operations""",
            r.key, r.name.trim(), r.description?.trim().orEmpty(), r.baseUrl.trim().trimEnd('/'), r.authHeader, enc, json.writeValueAsString(r.operations.map { ConnectorOperation(it.method.uppercase(), it.path) }), me.userId)
        audit.record("CONNECTOR_SAVED", "CONNECTOR", r.key, newValue = mapOf("baseUrl" to r.baseUrl, "operations" to r.operations.size, "credentialChanged" to (enc != null)))
        return list()
    }

    @PostMapping("/{key}/status")
    @Transactional
    fun status(@PathVariable key: String, @RequestParam status: String, @AuthenticationPrincipal me: StudioUserDetails): List<ConnectorDto> {
        guard.require(me.userId)
        if (status !in setOf("APPROVED", "DISABLED")) throw ApiException.badRequest("INVALID_STATUS", "APPROVED | DISABLED")
        if (jdbc.update("UPDATE connectors SET status = ? WHERE key = ?", status, key) == 0) throw ApiException.notFound("CONNECTOR_NOT_FOUND", "Not found")
        audit.record("CONNECTOR_STATUS", "CONNECTOR", key, newValue = mapOf("status" to status)); return list()
    }

    @PostMapping("/{key}/grants")
    @Transactional
    fun grant(@PathVariable key: String, @RequestBody r: GrantRequest, @AuthenticationPrincipal me: StudioUserDetails): List<ConnectorDto> {
        guard.require(me.userId)
        val kind = jdbc.query("SELECT app_kind FROM projects WHERE id = ? AND active", { rs, _ -> rs.getString(1) }, r.projectId).firstOrNull() ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Application not found")
        if (kind !in CodeProjectService.SERVER_KINDS) throw ApiException.conflict("NOT_A_SERVER_APP", "Only server apps can call connectors")
        try { jdbc.update("INSERT INTO project_connectors (project_id, connector_key, granted_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", r.projectId, key, me.userId) }
        catch (e: org.springframework.dao.DataIntegrityViolationException) { throw ApiException.notFound("CONNECTOR_NOT_FOUND", "Connector not found") }
        audit.record("CONNECTOR_GRANTED", "CONNECTOR", key, projectId = r.projectId); return list()
    }

    @DeleteMapping("/{key}/grants/{projectId}")
    @Transactional
    fun revoke(@PathVariable key: String, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<ConnectorDto> {
        guard.require(me.userId)
        jdbc.update("DELETE FROM project_connectors WHERE project_id = ? AND connector_key = ?", projectId, key)
        audit.record("CONNECTOR_REVOKED", "CONNECTOR", key, projectId = projectId); return list()
    }
}
