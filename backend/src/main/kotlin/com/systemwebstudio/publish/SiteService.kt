package com.systemwebstudio.publish

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Duration
import java.util.Base64
import java.util.UUID

data class LiveSite(
    val projectId: UUID, val workspaceId: UUID, val slug: String, val deploymentId: UUID, val visibility: String,
    val prefix: String, val files: Map<String, ManifestFile>, val kind: String = "STATIC_SITE"
)

/**
 * Sites of page-schema projects (ADR 0009): one slug per project, a pointer to the deployment being served, and the access rules for
 * private sites. Sites live on their own origin (`app.sites.origin`), so Studio cookies never reach them; a private site gets its own
 * host-only session through a single-use ticket issued by the Studio API to a signed-in member.
 */
@Service
class SiteService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val access: AccessService, private val redis: StringRedisTemplate,
    @Value("\${app.sites.origin:http://127.0.0.1:18088}") val sitesOrigin: String,
    @Value("\${app.sites.studio-origin:http://localhost:3100}") val studioOrigin: String,
    @Value("\${app.sites.session-hours:8}") private val sessionHours: Long
) {
    private val random = SecureRandom()

    fun url(slug: String) = "${sitesOrigin.trimEnd('/')}/$slug/"

    /** The project's slug, created on first publish: readable name + a short id (unique, stable, ASCII). */
    fun ensureSlug(projectId: UUID, name: String): String {
        jdbc.query("SELECT slug FROM sites WHERE project_id = ?", { rs, _ -> rs.getString(1) }, projectId).firstOrNull()?.let { return it }
        val base = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "site" }
        val slug = "$base-${projectId.toString().replace("-", "").take(6)}"
        jdbc.update("INSERT INTO sites (project_id, slug) VALUES (?, ?) ON CONFLICT (project_id) DO NOTHING", projectId, slug)
        return jdbc.queryForObject("SELECT slug FROM sites WHERE project_id = ?", String::class.java, projectId)!!
    }

    fun point(projectId: UUID, deploymentId: UUID?) {
        jdbc.update("UPDATE sites SET current_deployment_id = ?, updated_at = now() WHERE project_id = ?", deploymentId, projectId)
    }

    /** The artifact currently served for a slug, or null (unknown slug, offline, deleted project). */
    fun live(slug: String): LiveSite? = jdbc.query(
        """SELECT s.project_id, p.workspace_id, s.slug, d.id, d.visibility, a.storage_prefix, a.manifest::text, a.kind
           FROM sites s JOIN projects p ON p.id = s.project_id AND p.active
           JOIN deployments d ON d.id = s.current_deployment_id AND d.status IN ('DEPLOYING', 'RUNNING')
           JOIN artifacts a ON a.id = d.artifact_id AND a.deleted_at IS NULL WHERE s.slug = ?""", { rs, _ ->
            val files = json.readTree(rs.getString(7)).toList().map { json.treeToValue(it, ManifestFile::class.java) }.associateBy { it.path }
            LiveSite(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java),
                rs.getString(5), rs.getString(6), files, rs.getString(8))
        }, slug).firstOrNull()

    /** A code change preview by its unguessable token (expires; revoked on discard). */
    fun previewProject(token: String): UUID? = jdbc.query("SELECT project_id FROM code_changes WHERE preview_token = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, token).firstOrNull()

    /** `__factory/config.json` for @company/app-sdk: identity of the app, environment, visibility, and the viewer of a PRIVATE app. No secrets. */
    fun runtimeConfig(projectId: UUID, environment: String, visibility: String, userId: UUID?): Map<String, Any?> {
        val p = jdbc.queryForMap("SELECT name FROM projects WHERE id = ?", projectId)
        val version = jdbc.query("SELECT max(version_number) FROM project_versions WHERE project_id = ?", { rs, _ -> rs.getObject(1)?.toString() }, projectId).firstOrNull()
        val user = userId?.let { jdbc.query("SELECT coalesce(display_name, username) FROM users WHERE id = ?", { rs, _ -> rs.getString(1) }, it).firstOrNull() }
        return mapOf("appId" to projectId.toString(), "appName" to p["name"], "environment" to environment, "visibility" to visibility, "version" to version,
            "user" to user?.let { mapOf("displayName" to it) }, "flags" to emptyMap<String, Boolean>(), "apiBase" to null, "generatedAt" to java.time.Instant.now().toString())
    }

    fun preview(token: String): Pair<String, Map<String, ManifestFile>>? {
        if (!Regex("^[A-Za-z0-9_-]{20,64}$").matches(token)) return null
        return jdbc.query("""SELECT a.storage_prefix, a.manifest::text FROM code_changes c JOIN artifacts a ON a.id = c.preview_artifact_id AND a.deleted_at IS NULL
            JOIN projects p ON p.id = c.project_id AND p.active WHERE c.preview_token = ? AND c.preview_expires_at > now() AND c.status IN ('READY', 'MERGED')""", { rs, _ ->
            rs.getString(1) to json.readTree(rs.getString(2)).toList().map { json.treeToValue(it, ManifestFile::class.java) }.associateBy { it.path }
        }, token).firstOrNull()
    }

    fun projectBySlug(slug: String): Pair<UUID, UUID>? = jdbc.query(
        "SELECT s.project_id, p.workspace_id FROM sites s JOIN projects p ON p.id = s.project_id AND p.active WHERE s.slug = ?",
        { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, slug).firstOrNull()

    /** Live permission check (disabled users and removed members lose access on the next request). */
    fun canRead(userId: UUID, site: LiveSite): Boolean = try {
        access.forProject(userId, site.workspaceId, site.projectId).permissions.contains(Permission.PROJECT_READ)
    } catch (e: ApiException) { false }

    // ---------------------------------------------------------------- private-site access: ticket (Studio origin) → session (sites origin)
    private fun token() = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun safePath(path: String?): String {
        val p = path?.takeIf { it.isNotBlank() } ?: "/"
        if (!p.startsWith("/") || p.startsWith("//") || p.length > 512 || p.any { it == '\\' || it.isISOControl() } || ".." in p) return "/"
        return p
    }

    fun issueTicket(userId: UUID, slug: String, path: String?): String {
        val t = token()
        redis.opsForValue().set("site-ticket:$t", json.writeValueAsString(mapOf("u" to userId.toString(), "s" to slug, "p" to safePath(path))), Duration.ofSeconds(60))
        return t
    }

    /** Single use: the ticket is deleted as it is read. Returns (userId, slug, path). */
    fun redeemTicket(ticket: String): Triple<UUID, String, String>? {
        if (!Regex("^[A-Za-z0-9_-]{20,100}$").matches(ticket)) return null
        val raw = redis.opsForValue().getAndDelete("site-ticket:$ticket") ?: return null
        val n = json.readTree(raw)
        return Triple(UUID.fromString(n.get("u").asString()), n.get("s").asString(), safePath(n.get("p").asString()))
    }

    fun openSession(userId: UUID): String = token().also { redis.opsForValue().set("site-session:$it", userId.toString(), Duration.ofHours(sessionHours)) }
    val sessionSeconds get() = sessionHours * 3600

    /**
     * Private code apps run in an opaque (sandboxed) origin that cannot send cookies, so after the cookie check at the entry URL the
     * member gets a per-session capability path `/_app/<token>/`; membership is still re-checked on every request for that token.
     */
    fun openAppToken(userId: UUID, slug: String): String =
        token().also { redis.opsForValue().set("site-app:$it", "$userId|$slug", Duration.ofHours(sessionHours)) }

    fun appToken(value: String): Pair<UUID, String>? {
        if (!Regex("^[A-Za-z0-9_-]{20,100}$").matches(value)) return null
        val v = redis.opsForValue().get("site-app:$value") ?: return null
        return runCatching { UUID.fromString(v.substringBefore('|')) to v.substringAfter('|') }.getOrNull()
    }

    fun sessionUser(value: String?): UUID? {
        if (value == null || !Regex("^[A-Za-z0-9_-]{20,100}$").matches(value)) return null
        return redis.opsForValue().get("site-session:$value")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

/** Real static hosting (ADR 0009): the artifact built in BUILDING becomes what the site's address serves. */
@Component
@ConditionalOnProperty(name = ["app.deploy.provider"], havingValue = "static")
class StaticSiteDeployProvider(private val sites: SiteService) : DeployProvider {
    override val name = "static"
    override val buildsArtifacts = true
    override fun deploy(request: DeployRequest): DeployResult {
        val projectId = request.projectId ?: return DeployResult(null, "Missing project")
        if (request.artifactId == null) return DeployResult(null, "No artifact was built")
        val slug = sites.ensureSlug(projectId, request.projectName)
        sites.point(projectId, request.deploymentId)
        return DeployResult(sites.url(slug), null)
    }
}
