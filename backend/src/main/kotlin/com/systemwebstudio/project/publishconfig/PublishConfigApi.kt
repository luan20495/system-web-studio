package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.app.definition.AppDefinitionCodec
import com.systemwebstudio.app.definition.PublishConfigDef
import com.systemwebstudio.app.definition.PublishMode
import com.systemwebstudio.app.definition.PublishVisibility
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.settings.SettingsService
import com.systemwebstudio.version.SchemaRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/** Table `publish_configs` (migration requested in BOARD.md; the table does not exist until C0 issues the version and merges it). */
class JdbcPublishConfigRepository(private val jdbc: JdbcTemplate) : PublishConfigRepository {
    override fun find(projectId: UUID): PublishConfig? = jdbc.query(
        """SELECT project_id, workspace_id, mode, visibility, requires_auth, cache_seconds, public_data_approved, (link_token_hash IS NOT NULL),
                  revision, updated_by, updated_at FROM publish_configs WHERE project_id = ?""",
        { rs, _ ->
            PublishConfig(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), PublishMode.valueOf(rs.getString(3)),
                PublishVisibility.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getObject(6) as Int?, rs.getBoolean(7), rs.getBoolean(8),
                rs.getLong(9), rs.getObject(10, UUID::class.java), rs.getTimestamp(11)?.toInstant())
        }, projectId).firstOrNull()

    override fun save(config: PublishConfig, linkTokenHash: String?, expectedRevision: Long?): Boolean =
        if (expectedRevision == null) jdbc.update(
            """INSERT INTO publish_configs (project_id, workspace_id, mode, visibility, requires_auth, cache_seconds, public_data_approved,
                   link_token_hash, revision, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?, now()) ON CONFLICT (project_id) DO NOTHING""",
            config.projectId, config.workspaceId, config.mode.name, config.visibility.name, config.requiresAuth, config.cacheSeconds,
            config.publicDataApproved, linkTokenHash, config.revision, config.updatedBy) == 1
        else jdbc.update(
            """UPDATE publish_configs SET mode = ?, visibility = ?, requires_auth = ?, cache_seconds = ?, public_data_approved = ?,
                   link_token_hash = ?, revision = ?, updated_by = ?, updated_at = now() WHERE project_id = ? AND revision = ?""",
            config.mode.name, config.visibility.name, config.requiresAuth, config.cacheSeconds, config.publicDataApproved,
            linkTokenHash, config.revision, config.updatedBy, config.projectId, expectedRevision) == 1

    override fun linkTokenHash(projectId: UUID): String? =
        jdbc.query("SELECT link_token_hash FROM publish_configs WHERE project_id = ?", { rs, _ -> rs.getString(1) }, projectId).firstOrNull()
}

/**
 * The publish configuration API is OFF until the table exists: `app.publish-configs.enabled=true` (application.yml belongs to C0) turns on
 * these beans together with the migration. Nothing else in the application depends on them, so the existing publish flow is untouched.
 */
@Configuration
@ConditionalOnProperty("app.publish-configs.enabled", havingValue = "true")
class PublishConfigConfiguration {
    @Bean
    fun publishConfigRepository(jdbc: JdbcTemplate): PublishConfigRepository = JdbcPublishConfigRepository(jdbc)

    @Bean
    fun publishConfigService(repo: PublishConfigRepository, audit: AuditService): PublishConfigService = PublishConfigService(repo, PublishConfigAudit { action, workspaceId, projectId, actorId, details ->
        audit.record(action, "PUBLISH_CONFIG", projectId, workspaceId, projectId, actorId = actorId, newValue = details)
    })
}

data class PublishConfigDto(
    val mode: PublishMode, val visibility: PublishVisibility, val requiresAuth: Boolean, val cacheSeconds: Int?, val publicDataApproved: Boolean,
    val linkTokenSet: Boolean, val revision: Long, val updatedAt: Instant?
)

/**
 * [config] = the authoritative policy (null until one is set; the app then publishes as it always did), [draft] = the intention stored in the
 * document (informational), [linkToken] = only in the response that creates or rotates a PRIVATE_LINK token, and never again.
 */
data class PublishConfigResponse(val config: PublishConfigDto?, val draft: PublishConfigDef?, val linkToken: String? = null)

data class SetPublishConfigRequest(
    @field:NotNull val mode: PublishMode?, @field:NotNull val visibility: PublishVisibility?,
    val requiresAuth: Boolean = false, @field:Min(0) @field:Max(86400) val cacheSeconds: Int? = null,
    val acknowledgePublicData: Boolean = false, val expectedRevision: Long? = null
)

data class AdoptDraftRequest(val acknowledgePublicData: Boolean = false)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/publish-config")
@ConditionalOnProperty("app.publish-configs.enabled", havingValue = "true")
class PublishConfigController(
    private val access: AccessService, private val configs: PublishConfigService, private val schemas: SchemaRepository,
    private val codec: AppDefinitionCodec, private val settings: SettingsService
) {
    private fun toDto(c: PublishConfig) = PublishConfigDto(c.mode, c.visibility, c.requiresAuth, c.cacheSeconds, c.publicDataApproved, c.linkTokenSet, c.revision, c.updatedAt)

    /** the draft of the current document, or null when it has none or the document cannot be read as an app definition */
    private fun draftOf(projectId: UUID): PublishConfigDef? = schemas.currentSchema(projectId)?.let { runCatching { codec.fromJson(it).publishConfig }.getOrNull() }

    private fun facts(ctx: AccessContext): ProjectFacts {
        val project = ctx.project!!
        val bindings = schemas.currentSchema(project.id)?.get("dataBindings")
        return ProjectFacts(project.appKind, bindings != null && bindings.isArray && bindings.size() > 0)
    }

    private fun limits() = PublishLimits(settings.bool("publish.public-enabled"), settings.bool("source-apps.public-publish-enabled"))

    /** the three outcomes of the service as the API's errors */
    private fun <T> guarded(block: () -> T): T = try { block() } catch (e: PublishConfigException) {
        throw ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_CONFIG_INVALID", "The publish configuration is not allowed",
            mapOf("issues" to e.issues.map { mapOf("field" to it.field, "code" to it.code, "message" to it.message) }))
    } catch (e: PublishConfigConflict) {
        throw ApiException.conflict("REVISION_CONFLICT", "The publish configuration changed elsewhere; reload and retry.", mapOf("currentRevision" to e.currentRevision))
    }

    /** reading needs only to see the project; the link token is never returned here */
    @GetMapping
    @Transactional(readOnly = true)
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): PublishConfigResponse {
        access.forProject(me.userId, workspaceId, projectId)
        return PublishConfigResponse(configs.get(projectId)?.let { toDto(it) }, draftOf(projectId))
    }

    @PutMapping
    @Transactional
    fun put(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: SetPublishConfigRequest,
            @AuthenticationPrincipal me: StudioUserDetails): PublishConfigResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_PUBLISH)
        val update = PublishConfigUpdate(request.mode!!, request.visibility!!, request.requiresAuth, request.cacheSeconds, request.acknowledgePublicData, request.expectedRevision)
        val result = guarded { configs.set(workspaceId, projectId, me.userId, update, facts(ctx), limits()) }
        return PublishConfigResponse(toDto(result.config), draftOf(projectId), result.linkToken)
    }

    /** the draft of the current document becomes the policy, through the same rules as PUT; an explicit action, never automatic */
    @PostMapping("/adopt-draft")
    @Transactional
    fun adoptDraft(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestBody(required = false) request: AdoptDraftRequest?,
                   @AuthenticationPrincipal me: StudioUserDetails): PublishConfigResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_PUBLISH)
        val draft = draftOf(projectId) ?: throw ApiException.badRequest("NO_DRAFT", "The app definition has no publishConfig draft")
        val update = configs.fromDraft(draft, configs.get(projectId), request?.acknowledgePublicData ?: false)!!
        val result = guarded { configs.set(workspaceId, projectId, me.userId, update, facts(ctx), limits()) }
        return PublishConfigResponse(toDto(result.config), draft, result.linkToken)
    }

    @PostMapping("/link/rotate")
    @Transactional
    fun rotate(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): PublishConfigResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_PUBLISH)
        val result = guarded { configs.rotateLink(workspaceId, projectId, me.userId) }
        return PublishConfigResponse(toDto(result.config), draftOf(projectId), result.linkToken)
    }
}
