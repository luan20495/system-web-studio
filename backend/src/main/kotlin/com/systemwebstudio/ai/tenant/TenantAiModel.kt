package com.systemwebstudio.ai.tenant

import java.net.URI
import java.util.UUID

/*
 * Platform AI + Tenant AI (D-C2-11).
 *
 *   PLATFORM AI  configured by the Super Admin: the existing `ai_providers`, environment providers, `ai_model_policies`, default model,
 *                budgets and limits (ai/AiGovernance.kt) — unchanged.
 *   TENANT AI    configured by a Tenant Admin: ONE provider of the tenant's own (kind, endpoint, key, models). Stored server-side only
 *                (migration request: tenant_ai_providers), key encrypted with SecretsCrypto like every other provider key.
 *
 * Resolution (AiSourceResolver): the tenant's provider when the tenant selected one; the platform otherwise; the platform as a fallback
 * ONLY when the tenant explicitly enabled it. Keys never leave the server: not in a view, an audit record, a log line (toString) or a
 * prompt. A tenant user only ever sees the models they are allowed to use.
 */

enum class TenantProviderKind(val defaultBaseUrl: String, val keyRequired: Boolean) {
    OPENAI("https://api.openai.com/v1", true),
    ANTHROPIC("https://api.anthropic.com", true),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/openai", true),
    /** any server that speaks the OpenAI chat API; its host must be allow-listed by the platform operator */
    OPENAI_COMPATIBLE("", false)
}

/** What the server holds for a tenant. [apiKey] is the decrypted key and exists only inside the server process. */
data class TenantAiConfig(
    val tenantId: UUID, val kind: TenantProviderKind, val name: String, val baseUrl: String, val apiKey: String?,
    val models: List<String>, val defaultModel: String?, val enabled: Boolean = true,
    /** the tenant explicitly allows the platform's models when its own provider is unavailable (default: no) */
    val fallbackToPlatform: Boolean = false
) {
    /** safe for logs and exception messages: never prints the key */
    override fun toString() = "TenantAiConfig(tenantId=$tenantId, kind=$kind, name=$name, endpoint=${hostOf(baseUrl)}, models=${models.size}, keySet=${!apiKey.isNullOrEmpty()}, enabled=$enabled)"
    val keySet: Boolean get() = !apiKey.isNullOrEmpty()
    fun view() = TenantAiConfigView(kind, name, hostOf(baseUrl), models, defaultModel, enabled, fallbackToPlatform, keySet)
    companion object { fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull() }
}

/** What an API may return: no key (only whether one is set) and no path of the endpoint. */
data class TenantAiConfigView(
    val kind: TenantProviderKind, val name: String, val endpointHost: String?, val models: List<String>, val defaultModel: String?,
    val enabled: Boolean, val fallbackToPlatform: Boolean, val keySet: Boolean
)

/** A Tenant Admin's change. [apiKey] null = keep the stored key; [baseUrl] blank = the default endpoint of the kind. */
data class TenantAiConfigUpdate(
    val kind: TenantProviderKind, val name: String, val baseUrl: String? = null, val apiKey: String? = null,
    val models: List<String>, val defaultModel: String? = null, val enabled: Boolean = true, val fallbackToPlatform: Boolean = false
)

class TenantAiException(val code: String, message: String) : RuntimeException(message)

/** Persistence port (JDBC adapter in TenantAiApi.kt; migration request in BOARD). The adapter encrypts / decrypts the key. */
interface TenantAiConfigs {
    fun find(tenantId: UUID): TenantAiConfig?
    fun save(config: TenantAiConfig)
    fun delete(tenantId: UUID): Boolean
}

/** Rules for what a tenant may configure. Pure: the endpoint is judged by its text and by the operator's allow-list, never by calling it. */
class TenantAiPolicy(
    /** hosts the operator allows for OPENAI_COMPATIBLE endpoints (app.tenant-ai.allowed-hosts); the well-known providers need no entry */
    private val allowedHosts: Set<String> = emptySet()
) {
    fun apply(tenantId: UUID, update: TenantAiConfigUpdate, existing: TenantAiConfig?): TenantAiConfig {
        val name = update.name.trim()
        if (name.isEmpty() || name.length > 80) bad("INVALID_NAME", "Name must be 1-80 characters")
        val baseUrl = endpoint(update.kind, update.baseUrl?.trim().orEmpty())
        val models = update.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (models.isEmpty() || models.size > MAX_MODELS) bad("INVALID_MODELS", "Between 1 and $MAX_MODELS models are required")
        models.firstOrNull { !MODEL.matches(it) }?.let { bad("INVALID_MODELS", "Invalid model id") }
        val default = update.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
        if (default != null && default !in models) bad("INVALID_DEFAULT_MODEL", "The default model must be one of the models")
        val key = update.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.apiKey
        if (update.apiKey != null && update.apiKey.isNotBlank()) {
            if (key!!.length !in 8..512 || key.any { it.isWhitespace() || it.isISOControl() }) bad("INVALID_API_KEY", "The API key looks invalid")
        }
        if (key == null && update.kind.keyRequired) bad("API_KEY_REQUIRED", "${update.kind} needs an API key")
        return TenantAiConfig(tenantId, update.kind, name, baseUrl, key, models, default, update.enabled, update.fallbackToPlatform)
    }

    /** https only, no credentials / query / fragment in the URL, a hostname (never an IP literal or localhost), default port; the host must be the provider's own or allow-listed */
    private fun endpoint(kind: TenantProviderKind, requested: String): String {
        if (requested.isEmpty()) {
            if (kind.defaultBaseUrl.isEmpty()) bad("ENDPOINT_REQUIRED", "An endpoint is required for $kind")
            return kind.defaultBaseUrl
        }
        val uri = try { URI(requested) } catch (e: Exception) { bad("INVALID_ENDPOINT", "The endpoint is not a valid URL") }
        val host = uri.host?.lowercase() ?: bad("INVALID_ENDPOINT", "The endpoint is not a valid URL")
        if (uri.scheme != "https") bad("INVALID_ENDPOINT", "Only https endpoints are allowed")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) bad("INVALID_ENDPOINT", "The endpoint must not contain credentials, a query or a fragment")
        if (uri.port != -1 && uri.port != 443) bad("INVALID_ENDPOINT", "Only the default https port is allowed")
        if (isIpLiteral(host) || host == "localhost" || !host.contains('.') || LOCAL_SUFFIXES.any { host.endsWith(it) }) bad("INVALID_ENDPOINT", "The endpoint must be a public host name")
        val own = TenantProviderKind.entries.mapNotNull { TenantAiConfig.hostOf(it.defaultBaseUrl)?.lowercase() }.toSet()
        if (kind != TenantProviderKind.OPENAI_COMPATIBLE) {
            if (host != TenantAiConfig.hostOf(kind.defaultBaseUrl)?.lowercase() && host !in allowedHosts) bad("ENDPOINT_NOT_ALLOWED", "This endpoint is not allowed for $kind")
        } else if (host !in allowedHosts && host !in own) bad("ENDPOINT_NOT_ALLOWED", "This host is not on the platform's allow-list; ask the platform administrator")
        return requested.trimEnd('/')
    }

    private fun isIpLiteral(host: String) = host.startsWith("[") || host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun bad(code: String, message: String): Nothing = throw TenantAiException(code, message)

    companion object {
        const val MAX_MODELS = 30
        val MODEL = Regex("^[A-Za-z0-9._/:@-]{1,100}$")
        private val LOCAL_SUFFIXES = listOf(".local", ".internal", ".localhost", ".lan", ".intranet", ".corp", ".home.arpa")
    }
}
