package com.systemwebstudio.integration.llm

import com.systemwebstudio.runtime.SecretsCrypto
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** An AI provider an admin added on the web. `apiKey` is the decrypted key and exists only inside the server process. */
data class StoredProvider(
    val id: UUID, val slug: String, val kind: String, val name: String, val baseUrl: String, val apiKey: String?, val keySet: Boolean,
    val models: List<String>, val defaultModel: String?, val paid: Boolean, val enabled: Boolean
)

/** Reads/writes `ai_providers`. Keys are encrypted with [SecretsCrypto]; reading them back out through any API is impossible by design. */
@Service
class ProviderStore(private val jdbc: JdbcTemplate, private val crypto: SecretsCrypto, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var cache: Pair<Long, List<StoredProvider>>? = null

    val encryptionAvailable get() = crypto.available

    fun invalidate() { cache = null }

    fun all(): List<StoredProvider> {
        cache?.takeIf { System.currentTimeMillis() - it.first < 5000 }?.let { return it.second }
        val rows = runCatching {
            jdbc.query("SELECT id, slug, kind, name, base_url, api_key_enc, models::text, default_model, paid, enabled FROM ai_providers ORDER BY created_at") { rs, _ ->
                val enc = rs.getString(6)
                val key = enc?.let { runCatching { crypto.decrypt(it) }.onFailure { e -> log.warn("AI provider key of {} cannot be decrypted: {}", rs.getString(2), e.message) }.getOrNull() }
                @Suppress("UNCHECKED_CAST")
                StoredProvider(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), key, key != null,
                    (json.readValue(rs.getString(7), List::class.java) as List<String>), rs.getString(8), rs.getBoolean(9), rs.getBoolean(10))
            }
        }.getOrElse { cache?.second ?: emptyList() }
        cache = System.currentTimeMillis() to rows
        return rows
    }

    fun bySlug(slug: String) = all().firstOrNull { it.slug == slug }

    /** key of the enabled OpenRouter provider added on the web (used when OPENROUTER_API_KEY is not set in the environment) */
    fun openRouterKey(): String? = all().firstOrNull { it.kind == "OPENROUTER" && it.enabled && it.keySet }?.apiKey
}
