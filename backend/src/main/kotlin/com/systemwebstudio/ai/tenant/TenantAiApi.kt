package com.systemwebstudio.ai.tenant

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.ai.ModelAccessService
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiModel
import com.systemwebstudio.integration.llm.AiService
import com.systemwebstudio.runtime.SecretsCrypto
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The ONE place that says what "tenant" means for AI. Today a tenant is the workspace (tenant-context.md, C1); when C1's tenant model
 * lands (T2) only this function changes.
 */
object TenantRef {
    fun of(ctx: AccessContext): UUID = ctx.workspaceId
}

/**
 * Table `tenant_ai_providers` (migration requested in BOARD.md; the table does not exist until C0 issues the version and merges it).
 * The key is encrypted with [SecretsCrypto] exactly like `ai_providers.api_key_enc` and is read back only inside the server process.
 */
class JdbcTenantAiConfigs(private val jdbc: JdbcTemplate, private val crypto: SecretsCrypto, private val json: JsonMapper) : TenantAiConfigs {
    override fun find(tenantId: UUID): TenantAiConfig? = jdbc.query(
        "SELECT kind, name, base_url, api_key_enc, models::text, default_model, enabled, fallback_to_platform FROM tenant_ai_providers WHERE tenant_id = ?",
        { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            TenantAiConfig(tenantId, TenantProviderKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3),
                rs.getString(4)?.let { enc -> runCatching { crypto.decrypt(enc) }.getOrNull() },
                json.readValue(rs.getString(5), List::class.java) as List<String>, rs.getString(6), rs.getBoolean(7), rs.getBoolean(8))
        }, tenantId).firstOrNull()

    override fun save(config: TenantAiConfig) {
        val enc = config.apiKey?.let { crypto.encrypt(it) }
        jdbc.update(
            """INSERT INTO tenant_ai_providers (tenant_id, kind, name, base_url, api_key_enc, models, default_model, enabled, fallback_to_platform, updated_at)
               VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?, now())
               ON CONFLICT (tenant_id) DO UPDATE SET kind = EXCLUDED.kind, name = EXCLUDED.name, base_url = EXCLUDED.base_url, api_key_enc = EXCLUDED.api_key_enc,
                   models = EXCLUDED.models, default_model = EXCLUDED.default_model, enabled = EXCLUDED.enabled,
                   fallback_to_platform = EXCLUDED.fallback_to_platform, updated_at = now()""",
            config.tenantId, config.kind.name, config.name, config.baseUrl, enc, json.writeValueAsString(config.models), config.defaultModel,
            config.enabled, config.fallbackToPlatform)
    }

    override fun delete(tenantId: UUID): Boolean = jdbc.update("DELETE FROM tenant_ai_providers WHERE tenant_id = ?", tenantId) > 0
}

/**
 * The tenant AI API is OFF until the table exists: `app.tenant-ai.enabled=true` (application.yml belongs to C0) turns these beans on together
 * with the migration. `app.tenant-ai.allowed-hosts` (comma separated) is the operator's allow-list for custom OpenAI-compatible endpoints.
 */
@Configuration
@ConditionalOnProperty("app.tenant-ai.enabled", havingValue = "true")
class TenantAiConfiguration {
    @Bean
    fun tenantAiConfigs(jdbc: JdbcTemplate, crypto: SecretsCrypto, json: JsonMapper): TenantAiConfigs = JdbcTenantAiConfigs(jdbc, crypto, json)

    @Bean
    fun tenantAiPolicy(@Value("\${app.tenant-ai.allowed-hosts:}") hosts: String) =
        TenantAiPolicy(hosts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())

    @Bean
    fun tenantAiService(configs: TenantAiConfigs, policy: TenantAiPolicy, audit: AuditService) =
        TenantAiService(configs, policy, TenantAiAudit { action, tenantId, details ->
            audit.record(action, "TENANT_AI", tenantId, tenantId, newValue = details)       // details never contain the key (TenantAiService)
        })
}

data class TenantAiRequest(
    val kind: TenantProviderKind, @field:NotBlank @field:Size(max = 80) val name: String,
    @field:Size(max = 300) val baseUrl: String? = null,
    /** write-only: omitted or empty keeps the stored key */
    @field:Size(max = 512) val apiKey: String? = null,
    @field:Size(min = 1, max = 30) val models: List<String>, @field:Size(max = 100) val defaultModel: String? = null,
    val enabled: Boolean = true, val fallbackToPlatform: Boolean = false
)
data class TenantAiStatus(val configured: Boolean, val provider: TenantAiConfigView?)
data class AvailableModels(val models: List<ModelOption>, val tenantProviderActive: Boolean)

/**
 * Tenant AI for the tenant's admins; every member may read which models they can use. Authorization: the workspace's MEMBER_MANAGE
 * permission stands in for "Tenant Admin" until C1 defines a tenant-level permission (B-C2-03). A key is never returned.
 */
@RestController
@ConditionalOnProperty("app.tenant-ai.enabled", havingValue = "true")
@RequestMapping("/api/v1/workspaces/{workspaceId}/ai")
class TenantAiController(
    private val access: AccessService, private val service: TenantAiService, private val configs: TenantAiConfigs,
    private val ai: AiService, private val modelAccess: ModelAccessService
) {
    @GetMapping("/tenant-provider")
    @Transactional(readOnly = true)
    fun status(@PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails): TenantAiStatus {
        val view = service.view(TenantRef.of(access.forWorkspace(me.userId, workspaceId)))
        return TenantAiStatus(view != null, view)
    }

    @PutMapping("/tenant-provider")
    @Transactional
    fun set(@PathVariable workspaceId: UUID, @Valid @RequestBody request: TenantAiRequest, @AuthenticationPrincipal me: StudioUserDetails): TenantAiStatus {
        val ctx = access.forWorkspace(me.userId, workspaceId)
        ctx.require(Permission.MEMBER_MANAGE)
        val view = try {
            service.set(TenantRef.of(ctx), TenantAiConfigUpdate(request.kind, request.name, request.baseUrl, request.apiKey, request.models, request.defaultModel,
                request.enabled, request.fallbackToPlatform))
        } catch (e: TenantAiException) { throw ApiException.badRequest(e.code, e.message ?: "Invalid AI configuration") }
        return TenantAiStatus(true, view)
    }

    @DeleteMapping("/tenant-provider")
    @Transactional
    fun remove(@PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails): TenantAiStatus {
        val ctx = access.forWorkspace(me.userId, workspaceId)
        ctx.require(Permission.MEMBER_MANAGE)
        service.remove(TenantRef.of(ctx))
        return TenantAiStatus(false, null)
    }

    /** the models this user may pick: the tenant's (when it has its own provider) narrowed by the model access rules, plus the platform's only with the tenant's fallback */
    @GetMapping("/models")
    @Transactional(readOnly = true)
    fun models(@PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails): AvailableModels {
        val ctx = access.forWorkspace(me.userId, workspaceId)
        val tenant = configs.find(TenantRef.of(ctx))
        val platform = modelAccess.filter(me.userId, workspaceId, ai.catalog()).map { it.id }
        val allowedOwn = tenant?.let { tenantModelDenied(modelAccess, ctx, it) }
        return AvailableModels(AiSourceResolver.visibleModels(tenant, platform, allowedOwn ?: { false }), tenant != null && tenant.enabled && tenant.models.isNotEmpty())
    }

}

/** the model access rules (org / workspace / role / user deny rules) applied to the tenant's models, addressed as `tenant:<model>` */
fun tenantModelDenied(modelAccess: ModelAccessService, ctx: AccessContext, tenant: TenantAiConfig): (String) -> Boolean {
    val own = tenant.models.map { AiModel(AiSourceResolver.TENANT_PREFIX + it, it, 0, "tenant", true) }
    val allowed = modelAccess.filter(ctx.userId, ctx.workspaceId, own).map { it.id }.toSet()
    return { id -> id.startsWith(AiSourceResolver.TENANT_PREFIX) && id !in allowed }
}
