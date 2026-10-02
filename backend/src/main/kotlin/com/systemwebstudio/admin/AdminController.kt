package com.systemwebstudio.admin

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiService
import com.systemwebstudio.integration.queue.Queues
import com.systemwebstudio.integration.storage.StorageProvider
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Overview(
    val users: Long, val activeUsers: Long, val disabledUsers: Long, val usersLoggedIn30d: Long, val workspaces: Long, val projects: Long,
    val publishedProjects: Long, val aiRequestsToday: Long, val aiRequestsMonth: Long, val versionsToday: Long, val recentActivity: List<AuditRow>
)
data class WorkspaceRow(val id: UUID, val name: String, val slug: String, val createdAt: Instant, val members: Int, val projects: Int, val lastActivityAt: Instant?)
data class WorkspaceMember(val userId: UUID, val username: String, val displayName: String?, val role: String, val enabled: Boolean)
data class AppRow(
    val id: UUID, val name: String, val workspaceId: UUID, val workspaceName: String, val ownerId: UUID, val owner: String, val members: Int,
    val visibility: String, val revision: Long, val latestVersion: Int?, val createdAt: Instant, val updatedAt: Instant, val active: Boolean,
    val publishStatus: String?, val publishedAt: Instant?
)
data class WorkspaceDetail(val workspace: WorkspaceRow, val members: List<WorkspaceMember>, val projects: List<AppRow>, val recentActivity: List<AuditRow>)
data class VersionRowDto(val id: UUID, val versionNumber: Int, val kind: String, val summary: String, val createdBy: String?, val createdAt: Instant)
data class PromptRowDto(val id: UUID, val text: String, val createdAt: Instant, val user: String?, val provider: String?, val model: String?, val outcome: String?)
data class DeploymentRowDto(val id: UUID, val status: String, val visibility: String, val provider: String, val url: String?, val error: String?, val createdAt: Instant, val finishedAt: Instant?, val versionNumber: Int?)
data class AppDetail(
    val app: AppRow, val members: List<WorkspaceMember>, val versions: List<VersionRowDto>, val prompts: List<PromptRowDto>,
    val deployments: List<DeploymentRowDto>, val audit: List<AuditRow>
)
data class TransferRequest(val userId: UUID? = null)
data class ModelCount(val provider: String, val model: String?, val outcome: String, val count: Long)
data class AiAdmin(
    val provider: String, val configured: Boolean, val mode: String, val models: List<com.systemwebstudio.integration.llm.AiModel>,
    val dailyLimitPerUser: Long, val promptsPerMinute: Long, val requestsToday: Long, val requestsMonth: Long, val externalToday: Long,
    val byModelMonth: List<ModelCount>, val recent: List<PromptRowDto>, val tokenAccounting: String, val costAccounting: String
)
data class ComponentGov(val id: String, val name: String, val category: String, val description: String, val latestVersion: String, val status: String,
                        val usedInProjects: Long, val sections: Long, val propsSchema: String?)
data class HealthItem(val name: String, val status: String, val latencyMs: Long?, val detail: String?)
data class PlatformHealth(val checkedAt: Instant, val items: List<HealthItem>, val uptimeSeconds: Long, val javaVersion: String, val schemaVersion: String?,
                          val profiles: List<String>, val publishQueueDepth: Long?, val deadLetterDepth: Long?)

/** Admin Console read models and actions over the existing data. Nothing here is synthesised: metrics come straight from the tables. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService, private val ai: AiService,
    private val storage: StorageProvider, private val redis: RedisConnectionFactory, private val rabbit: ConnectionFactory, private val env: Environment,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args) ?: 0L

    // ------------------------------------------------------------------ overview
    @GetMapping("/overview")
    @Transactional(readOnly = true)
    fun overview(@AuthenticationPrincipal me: StudioUserDetails): Overview {
        guard.require(me.userId)
        return Overview(
            users = count("SELECT count(*) FROM users"),
            activeUsers = count("SELECT count(*) FROM users WHERE enabled"),
            disabledUsers = count("SELECT count(*) FROM users WHERE NOT enabled"),
            usersLoggedIn30d = count("SELECT count(DISTINCT actor_id) FROM audit_events WHERE action = 'LOGIN_SUCCESS' AND created_at > now() - interval '30 days'"),
            workspaces = count("SELECT count(*) FROM workspaces"),
            projects = count("SELECT count(*) FROM projects WHERE active"),
            publishedProjects = count("SELECT count(DISTINCT d.project_id) FROM deployments d JOIN projects p ON p.id = d.project_id AND p.active WHERE d.status = 'RUNNING'"),
            aiRequestsToday = count("SELECT count(*) FROM prompt_runs WHERE created_at >= date_trunc('day', now())"),
            aiRequestsMonth = count("SELECT count(*) FROM prompt_runs WHERE created_at >= date_trunc('month', now())"),
            versionsToday = count("SELECT count(*) FROM project_versions WHERE created_at >= date_trunc('day', now())"),
            recentActivity = jdbc.query("$AUDIT_SELECT ORDER BY a.created_at DESC LIMIT 12", { rs, _ -> auditRow(rs) })
        )
    }

    // ------------------------------------------------------------------ workspaces
    private val wsSelect = """SELECT w.id, w.name, w.slug, w.created_at,
        (SELECT count(*) FROM workspace_members m WHERE m.workspace_id = w.id AND m.active),
        (SELECT count(*) FROM projects p WHERE p.workspace_id = w.id AND p.active),
        (SELECT max(a.created_at) FROM audit_events a WHERE a.workspace_id = w.id) FROM workspaces w"""
    private fun wsRow(rs: java.sql.ResultSet) = WorkspaceRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
        rs.getInt(5), rs.getInt(6), rs.getTimestamp(7)?.toInstant())

    @GetMapping("/workspaces")
    @Transactional(readOnly = true)
    fun workspaces(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) q: String?,
                   @AuthenticationPrincipal me: StudioUserDetails): PageDto<WorkspaceRow> {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val l = like(q)
        val where = if (l != null) " WHERE w.name ILIKE ? OR w.slug ILIKE ?" else ""
        val args = if (l != null) arrayOf<Any>(l, l) else emptyArray()
        return PageDto(jdbc.query("$wsSelect$where ORDER BY w.created_at DESC, w.id LIMIT $s OFFSET ${p.toLong() * s}", { rs, _ -> wsRow(rs) }, *args),
            count("SELECT count(*) FROM workspaces w$where", *args), p, s)
    }

    @GetMapping("/workspaces/{id}")
    @Transactional(readOnly = true)
    fun workspace(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): WorkspaceDetail {
        guard.require(me.userId)
        val ws = jdbc.query("$wsSelect WHERE w.id = ?", { rs, _ -> wsRow(rs) }, id).firstOrNull() ?: throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        val members = jdbc.query("""SELECT u.id, u.username, u.display_name, m.role, u.enabled FROM workspace_members m JOIN users u ON u.id = m.user_id
            WHERE m.workspace_id = ? AND m.active ORDER BY u.username""", { rs, _ -> WorkspaceMember(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)) }, id)
        return WorkspaceDetail(ws, members, apps(" AND p.workspace_id = ?", arrayOf(id), 0, 100).items,
            jdbc.query("$AUDIT_SELECT WHERE a.workspace_id = ? ORDER BY a.created_at DESC LIMIT 20", { rs, _ -> auditRow(rs) }, id))
    }

    // ------------------------------------------------------------------ application inventory
    private val appSelect = """SELECT p.id, p.name, p.workspace_id, w.name, p.owner_user_id, coalesce(u.display_name, u.username),
        (SELECT count(*) FROM project_members pm WHERE pm.project_id = p.id AND pm.active), p.site_visibility, p.revision,
        (SELECT max(v.version_number) FROM project_versions v WHERE v.project_id = p.id), p.created_at, p.updated_at, p.active,
        d.status, d.created_at
        FROM projects p JOIN workspaces w ON w.id = p.workspace_id JOIN users u ON u.id = p.owner_user_id
        LEFT JOIN LATERAL (SELECT status, created_at FROM deployments d WHERE d.project_id = p.id ORDER BY d.created_at DESC LIMIT 1) d ON TRUE"""
    private fun appRow(rs: java.sql.ResultSet) = AppRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getString(4),
        rs.getObject(5, UUID::class.java), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getLong(9), rs.getObject(10) as Int?, rs.getTimestamp(11).toInstant(),
        rs.getTimestamp(12).toInstant(), rs.getBoolean(13), rs.getString(14), rs.getTimestamp(15)?.toInstant())

    private fun apps(extra: String, args: Array<Any>, page: Int, size: Int): PageDto<AppRow> {
        val (p, s) = pageArgs(page, size)
        val where = " WHERE TRUE$extra"
        val total = count("SELECT count(*) FROM projects p JOIN users u ON u.id = p.owner_user_id$where", *args)
        return PageDto(jdbc.query("$appSelect$where ORDER BY p.updated_at DESC, p.id LIMIT $s OFFSET ${p.toLong() * s}", { rs, _ -> appRow(rs) }, *args), total, p, s)
    }

    @GetMapping("/applications")
    @Transactional(readOnly = true)
    fun applications(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) q: String?,
                     @RequestParam(required = false) workspaceId: UUID?, @RequestParam(required = false) visibility: String?,
                     @RequestParam(defaultValue = "active") status: String, @AuthenticationPrincipal me: StudioUserDetails): PageDto<AppRow> {
        guard.require(me.userId)
        val extra = StringBuilder(); val args = mutableListOf<Any>()
        when (status) { "active" -> extra.append(" AND p.active"); "deleted" -> extra.append(" AND NOT p.active") }
        like(q)?.let { extra.append(" AND (p.name ILIKE ? OR u.username ILIKE ?)"); args += it; args += it }
        workspaceId?.let { extra.append(" AND p.workspace_id = ?"); args += it }
        visibility?.takeIf { it == "PUBLIC" || it == "PRIVATE" }?.let { extra.append(" AND p.site_visibility = ?"); args += it }
        return apps(extra.toString(), args.toTypedArray(), page, size)
    }

    @GetMapping("/applications/{id}")
    @Transactional(readOnly = true)
    fun application(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): AppDetail {
        guard.require(me.userId)
        val app = jdbc.query("$appSelect WHERE p.id = ?", { rs, _ -> appRow(rs) }, id).firstOrNull() ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Application not found")
        val members = jdbc.query("""SELECT u.id, u.username, u.display_name, pm.role, u.enabled FROM project_members pm JOIN users u ON u.id = pm.user_id
            WHERE pm.project_id = ? AND pm.active ORDER BY pm.role, u.username""", { rs, _ -> WorkspaceMember(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)) }, id)
        val versions = jdbc.query("""SELECT v.id, v.version_number, v.kind, v.summary, coalesce(u.display_name, u.username), v.created_at FROM project_versions v
            LEFT JOIN users u ON u.id = v.created_by WHERE v.project_id = ? ORDER BY v.version_number DESC LIMIT 20""",
            { rs, _ -> VersionRowDto(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()) }, id)
        val prompts = jdbc.query("""SELECT p.id, left(p.text, 300), p.created_at, coalesce(u.display_name, u.username), r.provider, r.model, r.status FROM prompts p
            LEFT JOIN prompt_runs r ON r.prompt_id = p.id LEFT JOIN users u ON u.id = p.created_by WHERE p.project_id = ? ORDER BY p.created_at DESC LIMIT 20""",
            { rs, _ -> PromptRowDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) }, id)
        val deployments = jdbc.query("""SELECT d.id, d.status, d.visibility, d.provider, d.url, d.error, d.created_at, d.finished_at, v.version_number FROM deployments d
            LEFT JOIN project_versions v ON v.id = d.version_id WHERE d.project_id = ? ORDER BY d.created_at DESC LIMIT 10""",
            { rs, _ -> DeploymentRowDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8)?.toInstant(), rs.getObject(9) as Int?) }, id)
        return AppDetail(app, members, versions, prompts, deployments,
            jdbc.query("$AUDIT_SELECT WHERE a.project_id = ? ORDER BY a.created_at DESC LIMIT 20", { rs, _ -> auditRow(rs) }, id))
    }

    /** New owner must be an enabled, active member of the project's workspace. The previous owner stays on the project as EDITOR. */
    @PostMapping("/applications/{id}/transfer-ownership")
    @Transactional
    fun transfer(@PathVariable id: UUID, @RequestBody body: TransferRequest, @AuthenticationPrincipal me: StudioUserDetails): AppRow {
        guard.require(me.userId)
        val target = body.userId ?: throw ApiException.badRequest("VALIDATION_FAILED", "userId is required")
        val row = jdbc.queryForList("SELECT workspace_id, owner_user_id FROM projects WHERE id = ? AND active FOR UPDATE", id).firstOrNull()
            ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Application not found")
        val ws = row["workspace_id"] as UUID; val previous = row["owner_user_id"] as UUID
        if (previous == target) return jdbc.query("$appSelect WHERE p.id = ?", { rs, _ -> appRow(rs) }, id).first()
        val eligible = count("SELECT count(*) FROM workspace_members m JOIN users u ON u.id = m.user_id WHERE m.workspace_id = ? AND m.user_id = ? AND m.active AND u.enabled", ws, target)
        if (eligible == 0L) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_WORKSPACE_MEMBER", "The new owner must be an enabled member of the workspace")
        jdbc.update("UPDATE projects SET owner_user_id = ?, updated_at = now() WHERE id = ?", target, id)
        jdbc.update("""INSERT INTO project_members (workspace_id, project_id, user_id, role, active) VALUES (?,?,?,'OWNER',TRUE)
            ON CONFLICT (project_id, user_id) DO UPDATE SET role = 'OWNER', active = TRUE""", ws, id, target)
        jdbc.update("UPDATE project_members SET role = 'EDITOR' WHERE project_id = ? AND user_id = ? AND role = 'OWNER'", id, previous)
        audit.record("TRANSFER_OWNERSHIP", "PROJECT", id, ws, id, oldValue = mapOf("owner" to previous), newValue = mapOf("owner" to target))
        return jdbc.query("$appSelect WHERE p.id = ?", { rs, _ -> appRow(rs) }, id).first()
    }

    // ------------------------------------------------------------------ audit (organisation-wide)
    @GetMapping("/audit")
    @Transactional(readOnly = true)
    fun audit(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int,
              @RequestParam(required = false) actor: String?, @RequestParam(required = false) workspaceId: UUID?, @RequestParam(required = false) projectId: UUID?,
              @RequestParam(required = false) action: String?, @RequestParam(required = false) requestId: String?,
              @RequestParam(required = false) from: Instant?, @RequestParam(required = false) to: Instant?,
              @AuthenticationPrincipal me: StudioUserDetails): PageDto<AuditRow> {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val where = StringBuilder(" WHERE TRUE"); val args = mutableListOf<Any>()
        actor?.trim()?.takeIf { it.isNotEmpty() }?.let { a -> runCatching { UUID.fromString(a) }.getOrNull()?.let { where.append(" AND a.actor_id = ?"); args += it }
            ?: run { where.append(" AND (u.username ILIKE ? OR coalesce(u.display_name,'') ILIKE ?)"); args += like(a)!!; args += like(a)!! } }
        workspaceId?.let { where.append(" AND a.workspace_id = ?"); args += it }
        projectId?.let { where.append(" AND a.project_id = ?"); args += it }
        action?.trim()?.takeIf { it.isNotEmpty() }?.let { where.append(" AND a.action = ?"); args += it.uppercase() }
        requestId?.trim()?.takeIf { it.isNotEmpty() }?.let { where.append(" AND a.request_id = ?"); args += it }
        from?.let { where.append(" AND a.created_at >= ?"); args += java.sql.Timestamp.from(it) }
        to?.let { where.append(" AND a.created_at < ?"); args += java.sql.Timestamp.from(it) }
        val total = count("SELECT count(*) FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id$where", *args.toTypedArray())
        return PageDto(jdbc.query("$AUDIT_SELECT$where ORDER BY a.created_at DESC, a.id LIMIT $s OFFSET ${p.toLong() * s}", { rs, _ -> auditRow(rs) }, *args.toTypedArray()), total, p, s)
    }

    @GetMapping("/audit/actions")
    fun auditActions(@AuthenticationPrincipal me: StudioUserDetails): List<String> {
        guard.require(me.userId)
        return jdbc.queryForList("SELECT DISTINCT action FROM audit_events ORDER BY action", String::class.java)
    }

    // ------------------------------------------------------------------ AI control (current: OpenRouter + simulator)
    @GetMapping("/ai")
    @Transactional(readOnly = true)
    fun ai(@AuthenticationPrincipal me: StudioUserDetails): AiAdmin {
        guard.require(me.userId)
        val byModel = jdbc.query("""SELECT provider, model, status, count(*) FROM prompt_runs WHERE created_at >= date_trunc('month', now())
            GROUP BY provider, model, status ORDER BY count(*) DESC LIMIT 50""", { rs, _ -> ModelCount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) })
        val recent = jdbc.query("""SELECT p.id, left(p.text, 160), r.created_at, coalesce(u.display_name, u.username), r.provider, r.model, r.status FROM prompt_runs r
            JOIN prompts p ON p.id = r.prompt_id LEFT JOIN users u ON u.id = p.created_by ORDER BY r.created_at DESC LIMIT 25""",
            { rs, _ -> PromptRowDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) })
        return AiAdmin(
            provider = if (ai.externalEnabled) "openrouter" else "mock", configured = ai.externalEnabled, mode = if (ai.externalEnabled) "auto" else "mock",
            models = if (ai.externalEnabled) ai.freeModels() else emptyList(), dailyLimitPerUser = ai.dailyLimitPerUser, promptsPerMinute = promptMax,
            requestsToday = count("SELECT count(*) FROM prompt_runs WHERE created_at >= date_trunc('day', now())"),
            requestsMonth = count("SELECT count(*) FROM prompt_runs WHERE created_at >= date_trunc('month', now())"),
            externalToday = count("SELECT count(*) FROM prompt_runs WHERE provider <> 'mock' AND created_at >= date_trunc('day', now())"),
            byModelMonth = byModel, recent = recent,
            tokenAccounting = "NOT_IMPLEMENTED", costAccounting = "NOT_IMPLEMENTED"
        )
    }

    // ------------------------------------------------------------------ component governance (current: fixed approved registry)
    @GetMapping("/components")
    @Transactional(readOnly = true)
    fun components(@AuthenticationPrincipal me: StudioUserDetails): List<ComponentGov> {
        guard.require(me.userId)
        return jdbc.query("""SELECT c.id, c.name, c.category, c.description, c.latest_version, c.status,
            coalesce(u.projects, 0), coalesce(u.sections, 0), v.props_schema::text
            FROM components c LEFT JOIN component_versions v ON v.component_id = c.id AND v.version = c.latest_version
            LEFT JOIN (SELECT e->>'type' AS type, count(DISTINCT s.project_id) AS projects, count(*) AS sections FROM page_schemas s
                       JOIN projects p ON p.id = s.project_id AND p.active, jsonb_array_elements(s.schema->'sections') e GROUP BY 1) u ON u.type = c.id
            ORDER BY c.category, c.id""", { rs, _ -> ComponentGov(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getLong(7), rs.getLong(8), rs.getString(9)) })
    }

    // ------------------------------------------------------------------ platform health (real probes, no invented percentages)
    @GetMapping("/system/health")
    fun health(@AuthenticationPrincipal me: StudioUserDetails): PlatformHealth {
        guard.require(me.userId)
        fun probe(name: String, block: () -> String?): HealthItem {
            val t0 = System.nanoTime()
            return try { val detail = block(); HealthItem(name, "HEALTHY", (System.nanoTime() - t0) / 1_000_000, detail) }
            catch (e: Exception) { HealthItem(name, "UNAVAILABLE", (System.nanoTime() - t0) / 1_000_000, e.javaClass.simpleName) }
        }
        val items = mutableListOf(
            HealthItem("API", "HEALTHY", 0, "this response"),
            probe("PostgreSQL") { jdbc.queryForObject("SELECT version()", String::class.java)?.substringBefore(" on ") },
            probe("Redis") { redis.connection.use { c -> c.ping() } },
            probe("RabbitMQ") { rabbit.createConnection().use { if (!it.isOpen) error("closed"); null } },
            probe("MinIO") { if (!storage.isHealthy()) error("unhealthy"); null }
        )
        items += if (!ai.externalEnabled) HealthItem("OpenRouter", "NOT_CONFIGURED", null, "No OPENROUTER_API_KEY: simulator in use")
        else probe("OpenRouter") { "${ai.freeModels().size} free models listed" }.let { if (it.status == "HEALTHY" && it.detail?.startsWith("0 ") == true) it.copy(status = "DEGRADED") else it }
        val oidcIssuer = env.getProperty("app.oidc.issuer-uri").orEmpty()
        items += if (env.getProperty("app.oidc.enabled") != "true") HealthItem("OIDC provider", "NOT_CONFIGURED", null, "SSO disabled")
        else probe("OIDC provider") {
            val r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI("${oidcIssuer.trimEnd('/')}/.well-known/openid-configuration")).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.discarding())
            if (r.statusCode() != 200) error("HTTP ${r.statusCode()}"); null
        }
        val admin = runCatching { RabbitAdmin(rabbit) }.getOrNull()
        return PlatformHealth(Instant.now(), items, ManagementFactory.getRuntimeMXBean().uptime / 1000, System.getProperty("java.version"),
            runCatching { jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success", String::class.java) }.getOrNull(),
            env.activeProfiles.toList(), admin?.getQueueInfo(Queues.PUBLISH)?.messageCount?.toLong(), admin?.getQueueInfo(Queues.PUBLISH_DLQ)?.messageCount?.toLong())
    }

    // ------------------------------------------------------------------ settings (read-only view of the effective configuration; never secrets)
    @GetMapping("/settings")
    fun settings(@AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId)
        fun p(k: String) = env.getProperty(k)
        return mapOf(
            "authentication" to mapOf("localLogin" to p("app.local-login.enabled"), "oidc" to p("app.oidc.enabled"), "oidcIssuer" to p("app.oidc.issuer-uri")?.takeIf { it.isNotBlank() },
                "publicSignup" to p("app.signup.enabled"), "signupInviteRequired" to !p("app.signup.invite-code").isNullOrBlank(), "sessionTimeout" to p("server.servlet.session.timeout")),
            "limits" to mapOf("projectsPerWorkspace" to p("app.limits.max-projects-per-workspace"), "assetsPerProject" to p("app.limits.max-assets-per-project"),
                "promptsPerMinute" to p("app.rate-limit.prompt-max"), "publishPerMinute" to p("app.rate-limit.publish-max"), "aiRequestsPerUserPerDay" to p("app.openrouter.daily-limit-per-user"),
                "loginFailuresPerUser" to p("app.rate-limit.login-user-max"), "maxRequestBytes" to p("app.limits.max-request-bytes")),
            "ai" to mapOf("provider" to (if (ai.externalEnabled) "openrouter" else "mock"), "keyConfigured" to ai.externalEnabled, "modelAllowlist" to p("app.openrouter.model-allowlist")?.takeIf { it.isNotBlank() }),
            "retention" to mapOf("cleanupEnabled" to p("app.cleanup.enabled"), "abandonedUploadHours" to p("app.cleanup.abandoned-upload-hours"),
                "failedDeploymentDays" to p("app.cleanup.failed-deployment-days"), "auditRetention" to "never deleted by the application"),
            "deployment" to mapOf("provider" to p("app.deploy.provider"), "real" to false),
            "network" to mapOf("trustProxy" to p("app.proxy.trust"), "corsOrigins" to p("app.cors.allowed-origins"))
        )
    }
}
