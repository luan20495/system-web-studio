package com.systemwebstudio.runtime

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.code.CodeProjectService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.ManifestFile
import com.systemwebstudio.settings.SettingsService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream

data class ServerDeploymentDto(val id: UUID, val version: Int, val status: String, val error: String?, val commitSha: String?, val routes: Int,
                               val createdAt: Instant, val startedAt: Instant?, val requestedBy: String?, val rollbackOf: UUID?, val current: Boolean)
data class RuntimeDto(val available: Boolean, val provisioned: Boolean, val database: String?, val currentDeploymentId: UUID?, val desiredDeploymentId: UUID?,
                      val deployments: List<ServerDeploymentDto>, val secrets: List<SecretDto>, val connectors: List<String>, val logs: String?, val logsAt: Instant?,
                      val notice: String)
data class SecretDto(val name: String, val updatedAt: Instant, val updatedBy: String?)
data class SecretRequest(@field:NotBlank @field:Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") val name: String, @field:NotNull @field:Size(min = 1, max = 4000) val value: String?)
data class RollbackServerRequest(@field:NotNull val deploymentId: UUID?)
/** one route the gateway lets through: HTTP method + path template from the app's openapi.json */
data class AppRoute(val method: String, val path: String)
data class DesiredContainer(val deploymentId: UUID, val container: String, val artifactUrl: String, val env: Map<String, String>, val limits: Map<String, Any>, val role: String)
data class DesiredApp(val projectId: UUID, val containers: List<DesiredContainer>)
data class RuntimeReport(@field:NotBlank val state: String, val error: String? = null, val logs: String? = null)

/**
 * Server runtime (stage J, ADR 0017). The server part of a server app is built in the build sandbox like any code app; deploying creates a
 * server deployment the runner starts in an isolated container (non-root, read-only rootfs, all capabilities dropped, CPU/memory/pid limits,
 * network "apps": no internet, no host, no platform service — only the app's own database and the connector gateway). Blue/green: the new
 * container must pass its health check before the gateway switches to it; otherwise the previous one keeps serving. Rollback = deploying an
 * earlier build again (no rebuild).
 */
@Service
class ServerRuntimeService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val crypto: SecretsCrypto, private val appDb: AppDbProvisioner,
    private val settings: SettingsService, private val store: ArtifactStore, private val audit: AuditService,
    @Value("\${app.build.runner-token:}") private val runnerToken: String,
    @Value("\${app.runtime.gateway-url:}") val gatewayUrl: String,
    @Value("\${app.runtime.gateway-token:}") val gatewayToken: String,
    @Value("\${app.build.api-base:http://127.0.0.1:8080}") private val apiBase: String
) {
    /** policy (Admin → Settings, default off) AND every runtime component configured */
    val available: Boolean get() = settings.bool("server-apps.enabled") && configured
    val configured: Boolean get() = crypto.available && appDb.configured && gatewayUrl.isNotBlank() && gatewayToken.length >= 32 && runnerToken.isNotBlank()

    fun dbName(projectId: UUID) = "app_" + projectId.toString().replace("-", "").take(12)
    fun containerName(projectId: UUID, deploymentId: UUID) = "app-${projectId.toString().take(8)}-${deploymentId.toString().take(8)}"

    /** database + role on the apps DB server, app token for connector calls (at creation of a server app) */
    fun provision(projectId: UUID) {
        if (!configured) throw ApiException.conflict("SERVER_APPS_UNAVAILABLE", "The isolated runtime is not configured")
        val name = dbName(projectId); val pw = crypto.token(24); val appToken = crypto.token(32)
        appDb.create(name, pw)
        jdbc.update("""INSERT INTO app_runtimes (project_id, db_name, db_role, db_password_enc, app_token_enc, app_token_hash) VALUES (?,?,?,?,?,?)
            ON CONFLICT (project_id) DO NOTHING""", projectId, name, name, crypto.encrypt(pw), crypto.encrypt(appToken), sha256(appToken))
        audit.record("SERVER_APP_PROVISIONED", "PROJECT", projectId, projectId = projectId, newValue = mapOf("database" to name))
    }

    /** HMAC-SHA256 with the app's APP_TOKEN (hex), for headers the app must be able to trust */
    fun signUser(projectId: UUID, payload: String): String {
        val token = crypto.decrypt(jdbc.queryForObject("SELECT app_token_enc FROM app_runtimes WHERE project_id = ?", String::class.java, projectId)!!)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun files(artifactId: UUID): Pair<String, Map<String, ManifestFile>> {
        val row = jdbc.queryForMap("SELECT storage_prefix, manifest::text FROM artifacts WHERE id = ? AND deleted_at IS NULL", artifactId)
        val files = json.readTree(row["manifest"] as String).toList().map { json.treeToValue(it, ManifestFile::class.java) }.associateBy { it.path }
        return row["storage_prefix"] as String to files
    }

    /** the routes the app declares (openapi.json): only these reach it through the gateway */
    fun routesOf(artifactId: UUID): List<AppRoute> {
        val (prefix, files) = files(artifactId)
        if ("server/server.cjs" !in files) throw ApiException.conflict("NO_SERVER_BUILD", "The build has no server part (server/server.cjs)")
        val spec = files["server/openapi.json"]?.let { store.get("$prefix/server/openapi.json") }?.let { runCatching { json.readTree(it) }.getOrNull() }
            ?: throw ApiException.conflict("NO_ROUTES", "server/openapi.json is missing or not JSON")
        val methods = setOf("get", "post", "put", "patch", "delete")
        val routes = spec.get("paths")?.propertyNames()?.flatMap { path ->
            if (!Regex("^/api(/[A-Za-z0-9._{}-]+)*$").matches(path)) throw ApiException.conflict("BAD_ROUTE", "Routes must live under /api: $path")
            spec.get("paths").get(path).propertyNames().filter { it in methods }.map { AppRoute(it.uppercase(), path) }
        }.orEmpty()
        if (routes.size > 200) throw ApiException.conflict("TOO_MANY_ROUTES", "At most 200 routes")
        return routes
    }

    /** called after a successful publish build of a server app: creates the next server deployment and asks the runner to start it */
    @Transactional
    fun deploy(projectId: UUID, artifactId: UUID, commitSha: String?, userId: UUID?, rollbackOf: UUID? = null): UUID {
        if (!available) throw ApiException.conflict("SERVER_APPS_UNAVAILABLE", "Server apps are disabled by policy or the runtime is not configured")
        if (jdbc.queryForObject("SELECT count(*) FROM app_runtimes WHERE project_id = ?", Long::class.java, projectId)!! == 0L) provision(projectId)
        // one deploy at a time per app (versions stay unique, pointers consistent)
        jdbc.queryForObject("SELECT project_id FROM app_runtimes WHERE project_id = ? FOR UPDATE", UUID::class.java, projectId)
        val routes = routesOf(artifactId)
        val id = UUID.randomUUID()
        val version = (jdbc.queryForObject("SELECT coalesce(max(version), 0) FROM server_deployments WHERE project_id = ?", Int::class.java, projectId) ?: 0) + 1
        jdbc.update("""INSERT INTO server_deployments (id, project_id, version, artifact_id, commit_sha, status, routes, requested_by, rollback_of) VALUES (?,?,?,?,?, 'PENDING', CAST(? AS jsonb),?,?)""",
            id, projectId, version, artifactId, commitSha, json.writeValueAsString(routes), userId, rollbackOf)
        jdbc.update("UPDATE app_runtimes SET desired_deployment_id = ? WHERE project_id = ?", id, projectId)
        audit.record(if (rollbackOf != null) "SERVER_APP_ROLLBACK" else "SERVER_APP_DEPLOY", "SERVER_DEPLOYMENT", id, projectId = projectId, actorId = userId,
            newValue = mapOf("version" to version, "routes" to routes.size, "rollbackOf" to rollbackOf))
        return id
    }

    @Transactional
    fun rollback(projectId: UUID, to: UUID, userId: UUID): UUID {
        val row = jdbc.queryForList("SELECT artifact_id, commit_sha, status FROM server_deployments WHERE id = ? AND project_id = ?", to, projectId).firstOrNull()
            ?: throw ApiException.notFound("DEPLOYMENT_NOT_FOUND", "Deployment not found")
        if (row["status"] !in setOf("RUNNING", "SUPERSEDED", "STOPPED")) throw ApiException.conflict("NOT_ROLLBACKABLE", "Only a deployment that ran before can be restored")
        return deploy(projectId, row["artifact_id"] as UUID, row["commit_sha"] as String?, userId, rollbackOf = to)
    }

    /** stop serving (archive, delete, or by request): nothing desired, the runner removes the containers */
    fun stop(projectId: UUID, userId: UUID?) {
        jdbc.update("UPDATE server_deployments SET status = 'STOPPED', stopped_at = now() WHERE project_id = ? AND status IN ('PENDING', 'STARTING', 'RUNNING')", projectId)
        if (jdbc.update("UPDATE app_runtimes SET desired_deployment_id = NULL, current_deployment_id = NULL WHERE project_id = ?", projectId) > 0)
            audit.record("SERVER_APP_STOPPED", "PROJECT", projectId, projectId = projectId, actorId = userId)
    }

    /** what the runner must keep running: the current deployment and, while it starts, the desired one */
    fun desired(): List<DesiredApp> = jdbc.queryForList("""SELECT r.project_id, r.current_deployment_id, r.desired_deployment_id, r.db_name, r.db_role, r.db_password_enc, r.app_token_enc
        FROM app_runtimes r JOIN projects p ON p.id = r.project_id AND p.active AND p.lifecycle = 'ACTIVE'
        WHERE r.current_deployment_id IS NOT NULL OR r.desired_deployment_id IS NOT NULL""").map { r ->
        val pid = r["project_id"] as UUID
        val secrets = jdbc.query("SELECT name, value_enc FROM project_secrets WHERE project_id = ?", { rs, _ -> rs.getString(1) to crypto.decrypt(rs.getString(2)) }, pid).toMap()
        // platform variables win over user secrets with the same name
        val env = secrets + mapOf(
            "DATABASE_URL" to "postgresql://${r["db_role"]}:${crypto.decrypt(r["db_password_enc"] as String)}@${appDb.hostForApps}/${r["db_name"]}",
            "APP_TOKEN" to crypto.decrypt(r["app_token_enc"] as String), "CONNECTOR_URL" to "http://apps-gateway:8081/_connectors", "NODE_ENV" to "production", "PORT" to "8080")
        val ids = listOfNotNull(r["current_deployment_id"] as UUID?, (r["desired_deployment_id"] as UUID?)).distinct()
        DesiredApp(pid, ids.map { id ->
            val artifact = jdbc.queryForObject("SELECT artifact_id FROM server_deployments WHERE id = ?", UUID::class.java, id)!!
            DesiredContainer(id, containerName(pid, id), "${apiBase.trimEnd('/')}/internal/runtime/artifacts/$artifact", env,
                mapOf("memory" to "256m", "cpus" to 0.5, "pids" to 128), if (id == r["current_deployment_id"]) "current" else "desired")
        })
    }

    @Transactional
    fun report(deploymentId: UUID, r: RuntimeReport) {
        val row = jdbc.queryForList("SELECT project_id, status FROM server_deployments WHERE id = ?", deploymentId).firstOrNull() ?: throw ApiException.notFound("NOT_FOUND", "Unknown deployment")
        val pid = row["project_id"] as UUID
        val logs = r.logs?.let(::redact)?.takeLast(20_000)
        if (logs != null) jdbc.update("UPDATE app_runtimes SET last_logs = ?, logs_at = now() WHERE project_id = ?", logs, pid)
        val desired = jdbc.queryForObject("SELECT desired_deployment_id FROM app_runtimes WHERE project_id = ?", UUID::class.java, pid)
        when (r.state) {
            "STARTING" -> jdbc.update("UPDATE server_deployments SET status = 'STARTING' WHERE id = ? AND status = 'PENDING'", deploymentId)
            "RUNNING" -> if (desired == deploymentId) {
                val previous = jdbc.queryForObject("SELECT current_deployment_id FROM app_runtimes WHERE project_id = ?", UUID::class.java, pid)
                jdbc.update("UPDATE server_deployments SET status = 'RUNNING', started_at = coalesce(started_at, now()), error = NULL WHERE id = ?", deploymentId)
                if (previous != null && previous != deploymentId) jdbc.update("UPDATE server_deployments SET status = 'SUPERSEDED', stopped_at = now() WHERE id = ?", previous)
                jdbc.update("UPDATE app_runtimes SET current_deployment_id = ?, desired_deployment_id = NULL WHERE project_id = ?", deploymentId, pid)
                audit.record("SERVER_APP_RUNNING", "SERVER_DEPLOYMENT", deploymentId, projectId = pid, actorId = null)
            }
            "FAILED" -> {
                jdbc.update("UPDATE server_deployments SET status = 'FAILED', error = ? WHERE id = ?", r.error?.take(1000) ?: "failed", deploymentId)
                // the previous deployment (if any) keeps serving
                if (desired == deploymentId) jdbc.update("UPDATE app_runtimes SET desired_deployment_id = NULL WHERE project_id = ?", pid)
                val current = jdbc.queryForObject("SELECT current_deployment_id FROM app_runtimes WHERE project_id = ?", UUID::class.java, pid)
                if (current == deploymentId) jdbc.update("UPDATE app_runtimes SET current_deployment_id = NULL WHERE project_id = ?", pid)
                audit.record("SERVER_APP_FAILED", "SERVER_DEPLOYMENT", deploymentId, projectId = pid, actorId = null, newValue = mapOf("error" to r.error?.take(300)))
            }
            else -> throw ApiException.badRequest("INVALID_STATE", "state: STARTING | RUNNING | FAILED")
        }
    }

    private fun redact(s: String) = s.replace(Regex("postgres(ql)?://[^\\s@]+@"), "postgresql://***@").replace(Regex("(?i)(token|password|secret)([=:\\s\"]+)[^\\s\"]+"), "$1$2***")

    /** a tar.gz with only the server part of the build (server/), for the runner */
    fun serverBundle(artifactId: UUID): ByteArray {
        val (prefix, files) = files(artifactId)
        val out = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(GZIPOutputStream(out)).use { tar ->
            files.values.filter { it.path.startsWith("server/") }.forEach { f ->
                val bytes = store.get("$prefix/${f.path}") ?: error("missing ${f.path}")
                if (com.systemwebstudio.publish.StaticSiteBuilder.sha256(bytes) != f.sha256) error("integrity check failed for ${f.path}")
                val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(f.path.removePrefix("server/")); e.size = bytes.size.toLong(); e.mode = "644".toInt(8)
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /** routes of the deployment being served, null when the app is not running */
    fun liveRoutes(projectId: UUID): Pair<UUID, List<AppRoute>>? = jdbc.query("""SELECT d.id, d.routes::text FROM app_runtimes r JOIN server_deployments d ON d.id = r.current_deployment_id
        WHERE r.project_id = ? AND d.status = 'RUNNING'""", { rs, _ -> rs.getObject(1, UUID::class.java) to json.readValue(rs.getString(2), Array<AppRoute>::class.java).toList() }, projectId).firstOrNull()

    fun status(projectId: UUID, canSeeLogs: Boolean = true): RuntimeDto {
        val r = jdbc.queryForList("SELECT db_name, current_deployment_id, desired_deployment_id, last_logs, logs_at FROM app_runtimes WHERE project_id = ?", projectId).firstOrNull()
        val deps = jdbc.query("""SELECT d.id, d.version, d.status, d.error, d.commit_sha, jsonb_array_length(d.routes), d.created_at, d.started_at, coalesce(u.display_name, u.username), d.rollback_of
            FROM server_deployments d LEFT JOIN users u ON u.id = d.requested_by WHERE d.project_id = ? ORDER BY d.version DESC LIMIT 30""", { rs, _ ->
            ServerDeploymentDto(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8)?.toInstant(), rs.getString(9), rs.getObject(10, UUID::class.java), rs.getObject(1, UUID::class.java) == r?.get("current_deployment_id"))
        }, projectId)
        val secrets = jdbc.query("SELECT s.name, s.updated_at, coalesce(u.display_name, u.username) FROM project_secrets s LEFT JOIN users u ON u.id = s.updated_by WHERE s.project_id = ? ORDER BY s.name",
            { rs, _ -> SecretDto(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)) }, projectId)
        val connectors = jdbc.queryForList("SELECT connector_key FROM project_connectors WHERE project_id = ? ORDER BY 1", String::class.java, projectId)
        return RuntimeDto(available, r != null, r?.get("db_name") as String?, r?.get("current_deployment_id") as UUID?, r?.get("desired_deployment_id") as UUID?, deps, secrets, connectors,
            if (canSeeLogs) r?.get("last_logs") as String? else null, (r?.get("logs_at") as java.sql.Timestamp?)?.toInstant(),
            if (available) "Máy chủ của ứng dụng chạy trong container cô lập (không Internet, CSDL riêng)." else "Ứng dụng có máy chủ đang tắt theo chính sách hoặc máy chủ này chưa có runtime cô lập.")
    }

    @Transactional
    fun setSecret(projectId: UUID, name: String, value: String, userId: UUID) {
        if (!crypto.available) throw ApiException.conflict("SECRETS_UNAVAILABLE", "Secrets need SECRETS_MASTER_KEY on the server")
        if (name in RESERVED) throw ApiException.badRequest("RESERVED_NAME", "$name is set by the platform")
        if (jdbc.queryForObject("SELECT count(*) FROM project_secrets WHERE project_id = ?", Long::class.java, projectId)!! >= 50 &&
            jdbc.queryForObject("SELECT count(*) FROM project_secrets WHERE project_id = ? AND name = ?", Long::class.java, projectId, name)!! == 0L) throw ApiException.conflict("TOO_MANY_SECRETS", "At most 50 secrets")
        jdbc.update("""INSERT INTO project_secrets (project_id, name, value_enc, updated_by) VALUES (?,?,?,?)
            ON CONFLICT (project_id, name) DO UPDATE SET value_enc = EXCLUDED.value_enc, updated_by = EXCLUDED.updated_by, updated_at = now()""", projectId, name, crypto.encrypt(value), userId)
        audit.record("PROJECT_SECRET_SET", "PROJECT_SECRET", "$projectId:$name", projectId = projectId, newValue = mapOf("name" to name))   // never the value
    }

    companion object { val RESERVED = setOf("DATABASE_URL", "APP_TOKEN", "CONNECTOR_URL", "NODE_ENV", "PORT", "HOME", "PATH") }
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime")
class ServerRuntimeController(private val access: AccessService, private val runtime: ServerRuntimeService, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    private fun ctx(me: StudioUserDetails, w: UUID, p: UUID, perm: Permission? = null) = access.forProject(me.userId, w, p).also { c ->
        perm?.let { c.require(it) }
        if (c.project!!.appKind !in CodeProjectService.SERVER_KINDS) throw ApiException.conflict("NOT_A_SERVER_APP", "This application has no server part")
    }

    @GetMapping
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): RuntimeDto {
        // container logs can contain data the app printed: only people who deploy or configure the app see them
        val c = ctx(me, workspaceId, projectId)
        return runtime.status(projectId, Permission.PROJECT_PUBLISH in c.permissions || Permission.PROJECT_SETTINGS in c.permissions)
    }

    @PostMapping("/rollback")
    fun rollback(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody r: RollbackServerRequest, @AuthenticationPrincipal me: StudioUserDetails): RuntimeDto {
        ctx(me, workspaceId, projectId, Permission.PROJECT_PUBLISH); runtime.rollback(projectId, r.deploymentId!!, me.userId); return runtime.status(projectId)
    }

    @PostMapping("/stop")
    fun stop(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): RuntimeDto {
        ctx(me, workspaceId, projectId, Permission.PROJECT_PUBLISH); runtime.stop(projectId, me.userId); return runtime.status(projectId)
    }

    /** write-only: the value is stored encrypted and never returned */
    @PutMapping("/secrets")
    fun setSecret(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody r: SecretRequest, @AuthenticationPrincipal me: StudioUserDetails): RuntimeDto {
        ctx(me, workspaceId, projectId, Permission.PROJECT_SETTINGS); runtime.setSecret(projectId, r.name, r.value!!, me.userId); return runtime.status(projectId)
    }

    @DeleteMapping("/secrets/{name}")
    @Transactional
    fun deleteSecret(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable name: String, @AuthenticationPrincipal me: StudioUserDetails): RuntimeDto {
        ctx(me, workspaceId, projectId, Permission.PROJECT_SETTINGS)
        if (jdbc.update("DELETE FROM project_secrets WHERE project_id = ? AND name = ?", projectId, name) == 0) throw ApiException.notFound("SECRET_NOT_FOUND", "Secret not found")
        audit.record("PROJECT_SECRET_DELETED", "PROJECT_SECRET", "$projectId:$name", workspaceId, projectId)
        return runtime.status(projectId)
    }
}

/** Runner side (same token as the build runner); never routed by the UI or the sites gateway. */
@RestController
@RequestMapping("/internal/runtime")
class RuntimeInternalController(private val runtime: ServerRuntimeService, @Value("\${app.build.runner-token:}") private val token: String) {
    private fun auth(request: HttpServletRequest) {
        val got = request.getHeader("X-Runner-Token") ?: ""
        if (token.isBlank() || !MessageDigest.isEqual(got.toByteArray(), token.toByteArray())) throw ApiException.notFound("NOT_FOUND", "Not found")
    }

    @GetMapping("/desired")
    fun desired(request: HttpServletRequest): List<DesiredApp> { auth(request); return if (runtime.configured) runtime.desired() else emptyList() }

    @GetMapping("/artifacts/{artifactId}", produces = ["application/gzip"])
    fun artifact(@PathVariable artifactId: UUID, request: HttpServletRequest): ByteArray { auth(request); return runtime.serverBundle(artifactId) }

    @PostMapping("/{deploymentId}/report")
    fun report(@PathVariable deploymentId: UUID, @Valid @RequestBody r: RuntimeReport, request: HttpServletRequest) { auth(request); runtime.report(deploymentId, r) }
}
