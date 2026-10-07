package com.systemwebstudio.ai.tenant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

private val TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555")
private const val KEY = "sk-tenant-SECRET-1234567890"

private fun config(
    models: List<String> = listOf("gpt-a", "gpt-b"), default: String? = "gpt-b", enabled: Boolean = true, fallback: Boolean = false, key: String? = KEY
) = TenantAiConfig(TENANT, TenantProviderKind.OPENAI, "Tenant OpenAI", "https://api.openai.com/v1", key, models, default, enabled, fallback)

class AiSourceResolverTests {
    @Test
    fun `without a tenant provider the platform is used`() {
        assertThat(AiSourceResolver.resolve(null, null)).isEqualTo(AiSource(AiSourceKind.PLATFORM, null))
        assertThat(AiSourceResolver.resolve(null, "openai:gpt-x")).isEqualTo(AiSource(AiSourceKind.PLATFORM, "openai:gpt-x"))
        assertThat(AiSourceResolver.resolve(config(enabled = false), "auto")).isEqualTo(AiSource(AiSourceKind.PLATFORM, "auto"))
        assertThat(AiSourceResolver.resolve(config(models = emptyList(), default = null), null).kind).isEqualTo(AiSourceKind.PLATFORM)
        assertThrows(AiSourceException::class.java) { AiSourceResolver.resolve(null, "tenant:gpt-a") }
    }

    @Test
    fun `a tenant that selected a provider uses it, with its default model`() {
        assertThat(AiSourceResolver.resolve(config(), null)).isEqualTo(AiSource(AiSourceKind.TENANT, "gpt-b", false))
        assertThat(AiSourceResolver.resolve(config(), "auto")).isEqualTo(AiSource(AiSourceKind.TENANT, "gpt-b", false))
        assertThat(AiSourceResolver.resolve(config(default = null), "")).isEqualTo(AiSource(AiSourceKind.TENANT, "gpt-a", false))
        assertThat(AiSourceResolver.resolve(config(), "tenant:gpt-a")).isEqualTo(AiSource(AiSourceKind.TENANT, "gpt-a", false))
    }

    @Test
    fun `platform models are refused unless the tenant explicitly enabled the fallback`() {
        val e = assertThrows(AiSourceException::class.java) { AiSourceResolver.resolve(config(), "openai:gpt-x") }
        assertThat(e.code).isEqualTo("MODEL_NOT_ALLOWED")
        assertThat(AiSourceResolver.resolve(config(fallback = true), "openai:gpt-x")).isEqualTo(AiSource(AiSourceKind.PLATFORM, "openai:gpt-x"))
    }

    @Test
    fun `a tenant model that is not the tenant's is refused`() {
        assertThat(assertThrows(AiSourceException::class.java) { AiSourceResolver.resolve(config(), "tenant:other") }.code).isEqualTo("MODEL_NOT_ALLOWED")
    }

    @Test
    fun `models the user is denied are not used, and without any model left the call is refused or falls back only when enabled`() {
        val denyB = { id: String -> id == "tenant:gpt-b" }
        assertThat(AiSourceResolver.resolve(config(), "auto", denyB)).isEqualTo(AiSource(AiSourceKind.TENANT, "gpt-a", false))      // default denied → next allowed
        assertThat(assertThrows(AiSourceException::class.java) { AiSourceResolver.resolve(config(), "tenant:gpt-b", denyB) }.code).isEqualTo("MODEL_ACCESS_DENIED")
        val denyAll = { id: String -> id.startsWith("tenant:") }
        assertThat(assertThrows(AiSourceException::class.java) { AiSourceResolver.resolve(config(), null, denyAll) }.code).isEqualTo("NO_MODEL_AVAILABLE")
        assertThat(AiSourceResolver.resolve(config(fallback = true), null, denyAll)).isEqualTo(AiSource(AiSourceKind.PLATFORM, null))
    }

    @Test
    fun `fallback to the platform exists only for a failed tenant call of a tenant that enabled it`() {
        assertThat(AiSourceResolver.fallback(AiSource(AiSourceKind.TENANT, "gpt-a", true))).isEqualTo(AiSource(AiSourceKind.PLATFORM, null))
        assertThat(AiSourceResolver.fallback(AiSource(AiSourceKind.TENANT, "gpt-a", false))).isNull()
        assertThat(AiSourceResolver.fallback(AiSource(AiSourceKind.PLATFORM, null, true))).isNull()
    }

    @Test
    fun `a user sees only the models they may use`() {
        val platform = listOf("openai:gpt-x", "anthropic:claude-y")
        assertThat(AiSourceResolver.visibleModels(null, platform).map { it.id }).containsExactly("openai:gpt-x", "anthropic:claude-y")
        assertThat(AiSourceResolver.visibleModels(config(), platform).map { it.id }).containsExactly("tenant:gpt-a", "tenant:gpt-b")
        assertThat(AiSourceResolver.visibleModels(config(fallback = true), platform).map { it.id }).containsExactly("tenant:gpt-a", "tenant:gpt-b", "openai:gpt-x", "anthropic:claude-y")
        assertThat(AiSourceResolver.visibleModels(config(), platform) { it == "tenant:gpt-a" }.map { it.id }).containsExactly("tenant:gpt-b")
        assertThat(AiSourceResolver.visibleModels(config(enabled = false), platform).map { it.source }.toSet()).containsExactly(AiSourceKind.PLATFORM)
    }
}

class TenantAiPolicyTests {
    private val policy = TenantAiPolicy(setOf("llm.partner.example"))
    private fun update(kind: TenantProviderKind = TenantProviderKind.OPENAI, baseUrl: String? = null, apiKey: String? = KEY, models: List<String> = listOf("gpt-a"),
                       default: String? = null, name: String = "Tenant AI") = TenantAiConfigUpdate(kind, name, baseUrl, apiKey, models, default)

    private fun code(block: () -> Unit) = assertThrows(TenantAiException::class.java) { block() }.code

    @Test
    fun `a provider with its default endpoint is accepted`() {
        val c = policy.apply(TENANT, update(), null)
        assertThat(c.baseUrl).isEqualTo("https://api.openai.com/v1")
        assertThat(c.apiKey).isEqualTo(KEY)
        assertThat(c.models).containsExactly("gpt-a")
    }

    @Test
    fun `endpoints that could reach internal systems are refused`() {
        val bad = listOf(
            "http://api.openai.com/v1", "https://localhost/v1", "https://127.0.0.1/v1", "https://169.254.169.254/latest", "https://[::1]/v1", "https://10.0.0.5/v1",
            "https://internal-llm/v1", "https://llm.corp/v1", "https://api.openai.com:8443/v1", "https://user:pass@api.openai.com/v1", "https://api.openai.com/v1?x=1",
            "https://api.openai.com/v1#f", "ftp://api.openai.com", "not a url", "https://metadata.google.internal/x"
        )
        for (u in bad) assertThat(code { policy.apply(TENANT, update(baseUrl = u), null) } in setOf("INVALID_ENDPOINT", "ENDPOINT_NOT_ALLOWED")).describedAs(u).isTrue()
    }

    @Test
    fun `a custom host needs the operator's allow-list`() {
        assertThat(code { policy.apply(TENANT, update(TenantProviderKind.OPENAI_COMPATIBLE, "https://llm.unknown.example/v1", apiKey = null), null) }).isEqualTo("ENDPOINT_NOT_ALLOWED")
        assertThat(policy.apply(TENANT, update(TenantProviderKind.OPENAI, "https://llm.partner.example/v1"), null).baseUrl).isEqualTo("https://llm.partner.example/v1")   // allow-listed by the operator
        val ok = policy.apply(TENANT, update(TenantProviderKind.OPENAI_COMPATIBLE, "https://llm.partner.example/v1/", apiKey = null), null)
        assertThat(ok.baseUrl).isEqualTo("https://llm.partner.example/v1")
        assertThat(code { policy.apply(TENANT, update(TenantProviderKind.OPENAI_COMPATIBLE, null, apiKey = null), null) }).isEqualTo("ENDPOINT_REQUIRED")
        // a well-known provider kind cannot be pointed at another well-known provider's host
        assertThat(code { policy.apply(TENANT, update(TenantProviderKind.ANTHROPIC, "https://api.openai.com/v1"), null) }).isEqualTo("ENDPOINT_NOT_ALLOWED")
    }

    @Test
    fun `keys, models and names are checked`() {
        assertThat(code { policy.apply(TENANT, update(apiKey = null), null) }).isEqualTo("API_KEY_REQUIRED")
        assertThat(code { policy.apply(TENANT, update(apiKey = "short"), null) }).isEqualTo("INVALID_API_KEY")
        assertThat(code { policy.apply(TENANT, update(apiKey = "has space in it 1234"), null) }).isEqualTo("INVALID_API_KEY")
        assertThat(code { policy.apply(TENANT, update(models = emptyList()), null) }).isEqualTo("INVALID_MODELS")
        assertThat(code { policy.apply(TENANT, update(models = listOf("bad model!")), null) }).isEqualTo("INVALID_MODELS")
        assertThat(code { policy.apply(TENANT, update(models = (1..31).map { "m$it" }), null) }).isEqualTo("INVALID_MODELS")
        assertThat(code { policy.apply(TENANT, update(default = "other"), null) }).isEqualTo("INVALID_DEFAULT_MODEL")
        assertThat(code { policy.apply(TENANT, update(name = " "), null) }).isEqualTo("INVALID_NAME")
        assertThat(policy.apply(TENANT, update(models = listOf("a", "a", " b ")), null).models).containsExactly("a", "b")
    }

    @Test
    fun `a missing key keeps the stored one`() {
        val existing = policy.apply(TENANT, update(), null)
        val changed = policy.apply(TENANT, update(apiKey = null, models = listOf("gpt-a", "gpt-c")), existing)
        assertThat(changed.apiKey).isEqualTo(KEY)
        assertThat(policy.apply(TENANT, update(apiKey = "sk-new-key-0987654321"), existing).apiKey).isEqualTo("sk-new-key-0987654321")
    }
}

class TenantAiServiceTests {
    private class MemoryConfigs : TenantAiConfigs {
        val rows = HashMap<UUID, TenantAiConfig>()
        override fun find(tenantId: UUID) = rows[tenantId]
        override fun save(config: TenantAiConfig) { rows[config.tenantId] = config }
        override fun delete(tenantId: UUID) = rows.remove(tenantId) != null
    }

    private val configs = MemoryConfigs()
    private val audits = ArrayList<Triple<String, UUID, Map<String, Any?>>>()
    private val service = TenantAiService(configs, TenantAiPolicy(), TenantAiAudit { a, t, d -> audits += Triple(a, t, d) })
    private val update = TenantAiConfigUpdate(TenantProviderKind.OPENAI, "Tenant AI", null, KEY, listOf("gpt-a"), "gpt-a", true, false)

    @Test
    fun `the key never leaves the server in a view, an audit record or a log line`() {
        val view = service.set(TENANT, update)
        assertThat(view.keySet).isTrue()
        assertThat(view.toString().contains(KEY)).isFalse()
        assertThat(view.endpointHost).isEqualTo("api.openai.com")
        assertThat(service.view(TENANT)!!.toString().contains("SECRET")).isFalse()
        assertThat(configs.rows.getValue(TENANT).toString().contains("SECRET")).isFalse()      // toString of the stored config is safe for logs
        assertThat(audits.map { it.third.toString() }.any { it.contains("SECRET") || it.contains(KEY) }).isFalse()
        assertThat(audits.single().first).isEqualTo("TENANT_AI_CREATE")
        assertThat(audits.single().third["keyChanged"]).isEqualTo(true)
    }

    @Test
    fun `update, keep the key, and remove are audited`() {
        service.set(TENANT, update)
        service.set(TENANT, update.copy(apiKey = null, models = listOf("gpt-a", "gpt-b")))
        assertThat(configs.rows.getValue(TENANT).apiKey).isEqualTo(KEY)
        assertThat(audits.map { it.first }).containsExactly("TENANT_AI_CREATE", "TENANT_AI_UPDATE")
        assertThat(audits[1].third["keyChanged"]).isEqualTo(false)
        assertThat(service.remove(TENANT)).isTrue()
        assertThat(service.remove(TENANT)).isFalse()
        assertThat(service.view(TENANT)).isNull()
        assertThat(audits.last().first).isEqualTo("TENANT_AI_DELETE")
    }

    @Test
    fun `an invalid change stores and audits nothing`() {
        assertThrows(TenantAiException::class.java) { service.set(TENANT, update.copy(baseUrl = "http://localhost/v1")) }
        assertThat(configs.rows.isEmpty()).isTrue()
        assertThat(audits).isEmpty()
    }

    @Test
    fun `tenants are separate`() {
        val other = UUID.randomUUID()
        service.set(TENANT, update)
        assertThat(service.view(other)).isNull()
        assertThat(AiSourceResolver.resolve(configs.find(other), null).kind).isEqualTo(AiSourceKind.PLATFORM)
        assertThat(AiSourceResolver.resolve(configs.find(TENANT), null).kind).isEqualTo(AiSourceKind.TENANT)
    }
}
