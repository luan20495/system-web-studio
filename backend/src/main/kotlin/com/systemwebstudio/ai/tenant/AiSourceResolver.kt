package com.systemwebstudio.ai.tenant

import java.util.UUID

enum class AiSourceKind { PLATFORM, TENANT }

/**
 * Where one AI call goes. For TENANT, [model] is the bare model id of the tenant's provider; for PLATFORM it is what the platform's gate
 * understands (null / "auto", "mock", "provider:model"). [fallbackToPlatform] is true only when the tenant explicitly enabled it.
 */
data class AiSource(val kind: AiSourceKind, val model: String?, val fallbackToPlatform: Boolean = false)

/** A model a user may pick. Tenant models are addressed as `tenant:<model>` so they can never be confused with a platform model id. */
data class ModelOption(val id: String, val label: String, val source: AiSourceKind)

class AiSourceException(val code: String, message: String) : RuntimeException(message)

/**
 * Decides which AI the call uses, from the tenant's configuration and what the user asked for. Pure: the platform's own rules
 * (model policies, budgets, limits) are applied afterwards by the unchanged AI Gateway, and only to PLATFORM calls.
 *
 *   - no usable tenant provider  → PLATFORM (the platform default when no model was asked for)
 *   - tenant provider, "auto"    → TENANT with the tenant's default model (or its first allowed one)
 *   - tenant provider, tenant:x  → TENANT, if x is the tenant's and the user may use it
 *   - tenant provider, a platform model → PLATFORM only when the tenant explicitly enabled the fallback; refused otherwise
 *   - tenant provider with no model left for this user → PLATFORM only with the fallback; refused otherwise
 */
object AiSourceResolver {
    const val TENANT_PREFIX = "tenant:"

    /** [denied] tells whether the user is not allowed a model id (the model access rules, evaluated by the caller) */
    fun resolve(tenant: TenantAiConfig?, requested: String?, denied: (String) -> Boolean = { false }): AiSource {
        val usable = tenant != null && tenant.enabled && tenant.models.isNotEmpty()
        if (!usable) {
            if (requested != null && requested.startsWith(TENANT_PREFIX)) throw AiSourceException("MODEL_NOT_ALLOWED", "This tenant has no AI provider of its own")
            return AiSource(AiSourceKind.PLATFORM, requested)
        }
        tenant!!
        val allowed = tenant.models.filter { !denied(TENANT_PREFIX + it) }
        return when {
            requested.isNullOrBlank() || requested == "auto" -> {
                val pick = tenant.defaultModel?.takeIf { it in allowed } ?: allowed.firstOrNull()
                when {
                    pick != null -> AiSource(AiSourceKind.TENANT, pick, tenant.fallbackToPlatform)
                    tenant.fallbackToPlatform -> AiSource(AiSourceKind.PLATFORM, null)
                    else -> throw AiSourceException("NO_MODEL_AVAILABLE", "No AI model is available to you in this tenant")
                }
            }
            requested.startsWith(TENANT_PREFIX) -> {
                val bare = requested.removePrefix(TENANT_PREFIX)
                if (bare !in tenant.models) throw AiSourceException("MODEL_NOT_ALLOWED", "This model is not offered by the tenant's AI provider")
                if (bare !in allowed) throw AiSourceException("MODEL_ACCESS_DENIED", "This model is restricted for you")
                AiSource(AiSourceKind.TENANT, bare, tenant.fallbackToPlatform)
            }
            tenant.fallbackToPlatform -> AiSource(AiSourceKind.PLATFORM, requested)
            else -> throw AiSourceException("MODEL_NOT_ALLOWED", "Platform models are not enabled for this tenant")
        }
    }

    /** the source to retry on when a TENANT call produced no answer; null unless the tenant explicitly enabled the fallback */
    fun fallback(failed: AiSource): AiSource? =
        if (failed.kind == AiSourceKind.TENANT && failed.fallbackToPlatform) AiSource(AiSourceKind.PLATFORM, null) else null

    /**
     * The models the user may choose from. [platformModels] are the platform ids the user may already use (the platform's policies and
     * access rules were applied by the caller). With a usable tenant provider the user sees the tenant's models they are allowed, plus the
     * platform's only if the tenant enabled the fallback.
     */
    fun visibleModels(tenant: TenantAiConfig?, platformModels: List<String>, denied: (String) -> Boolean = { false }): List<ModelOption> {
        val platform = platformModels.map { ModelOption(it, it, AiSourceKind.PLATFORM) }
        if (tenant == null || !tenant.enabled || tenant.models.isEmpty()) return platform
        val own = tenant.models.filter { !denied(TENANT_PREFIX + it) }.map { ModelOption(TENANT_PREFIX + it, it, AiSourceKind.TENANT) }
        return if (tenant.fallbackToPlatform) own + platform else own
    }
}

/** What an admin did to the tenant's AI configuration, for the audit trail: never carries the key. */
fun interface TenantAiAudit { fun record(action: String, tenantId: UUID, details: Map<String, Any?>) }

/** Tenant admin operations over the port. Authorization (who is a tenant admin) is the caller's, through C1's access layer. */
class TenantAiService(
    private val configs: TenantAiConfigs, private val policy: TenantAiPolicy, private val audit: TenantAiAudit
) {
    fun view(tenantId: UUID): TenantAiConfigView? = configs.find(tenantId)?.view()

    fun set(tenantId: UUID, update: TenantAiConfigUpdate): TenantAiConfigView {
        val existing = configs.find(tenantId)
        val config = policy.apply(tenantId, update, existing)
        configs.save(config)
        audit.record(if (existing == null) "TENANT_AI_CREATE" else "TENANT_AI_UPDATE", tenantId, mapOf(
            "kind" to config.kind.name, "endpointHost" to TenantAiConfig.hostOf(config.baseUrl), "models" to config.models.size,
            "enabled" to config.enabled, "fallbackToPlatform" to config.fallbackToPlatform, "keyChanged" to (update.apiKey?.isNotBlank() == true)))
        return config.view()
    }

    fun remove(tenantId: UUID): Boolean =
        configs.delete(tenantId).also { if (it) audit.record("TENANT_AI_DELETE", tenantId, emptyMap()) }
}
