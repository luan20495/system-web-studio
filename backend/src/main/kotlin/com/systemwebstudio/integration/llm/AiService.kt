package com.systemwebstudio.integration.llm

import com.systemwebstudio.common.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class AiModel(val id: String, val name: String, val contextLength: Int, val provider: String = "openrouter", val paid: Boolean = false)

/**
 * Which models users may pick. Only models OpenRouter lists with zero prompt AND completion price and text-only output
 * are ever offered or accepted, so a user (or a crafted request) cannot spend credits on a paid model even if the key has some.
 */
@Service
class AiService(
    private val client: OpenRouterClient,
    private val registry: AiProviderRegistry,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    @Value("\${app.llm.provider:auto}") private val providerSetting: String,
    @Value("\${app.openrouter.models-cache-minutes:30}") private val cacheMinutes: Long,
    @Value("\${app.openrouter.max-attempts:4}") val maxAttempts: Int,
    @Value("\${app.openrouter.daily-limit-per-user:50}") private val configuredDailyLimit: Long,
    @Value("\${app.openrouter.model-allowlist:}") allowlist: String,
    private val settings: com.systemwebstudio.settings.SettingsService? = null
) {
    /** admin-editable (Settings → AI), default from configuration */
    val dailyLimitPerUser: Long get() = settings?.long("ai.daily-requests-per-user") ?: configuredDailyLimit
    private val log = LoggerFactory.getLogger(javaClass)
    private val allow = allowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    @Volatile private var cache: Pair<Instant, List<AiModel>>? = null
    private val failedUntil = ConcurrentHashMap<String, Instant>()

    /** OpenRouter is configured (and not forced to the simulator): "auto" uses its free models. */
    val externalEnabled: Boolean get() = providerSetting != "mock" && client.configured

    /**
     * "auto" (or null) means "let the server try OpenRouter free models in turn" and never reaches a paid provider; "mock" is the
     * built-in simulator; "provider:model" is an explicitly chosen model of another provider (ADR 0007).
     */
    fun isExternal(model: String?): Boolean {
        if (providerSetting == "mock" || model == "mock") return false
        registry.split(model)?.let { (p, _) -> return p.configured }
        return externalEnabled
    }

    /** Admin decisions per model id. Paid-provider models are OFF unless enabled; OpenRouter free models are ON unless disabled. */
    fun policies(): Map<String, Boolean> = jdbc.query("SELECT model_id, enabled FROM ai_model_policies") { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.toMap()

    /** Every model of every configured non-OpenRouter provider, with the admin's enabled flag. */
    fun providerModels(policies: Map<String, Boolean> = policies()): List<Pair<AiModel, Boolean>> =
        if (providerSetting == "mock") emptyList()
        else registry.configured().flatMap { p -> p.models.map { m -> AiModel("${p.id}:$m", m, 0, p.id, p.paid) to (policies["${p.id}:$m"] ?: false) } }

    /** What users may pick: enabled OpenRouter free models, then enabled models of other providers. */
    fun catalog(): List<AiModel> {
        val pol = policies()
        val free = if (externalEnabled) freeModels().filter { pol[it.id] != false } else emptyList()
        return free + providerModels(pol).filter { it.second }.map { it.first }
    }

    fun freeModels(): List<AiModel> {
        cache?.takeIf { Duration.between(it.first, Instant.now()) < Duration.ofMinutes(cacheMinutes) }?.let { return it.second }
        val fresh = try {
            client.listModels().filter(::isFreeText).map { AiModel(it.id, it.name, it.contextLength) }
                .filter { allow.isEmpty() || it.id in allow }
                .sortedWith(compareBy<AiModel> { it.id != ROUTER_ID }.thenByDescending { it.contextLength }.thenBy { it.id })
        } catch (e: Exception) {
            log.warn("Could not refresh the OpenRouter model list: {}", e.message)
            cache?.second ?: listOf(AiModel(ROUTER_ID, "OpenRouter free router", 200_000))
        }
        cache = Instant.now() to fresh
        return fresh
    }

    private fun isFreeText(m: OrModel): Boolean =
        m.promptPrice == "0" && m.completionPrice == "0" && m.outputText && m.contextLength >= 16_000 &&
            (m.id.endsWith(":free") || m.id == ROUTER_ID) && BLOCKED.none { m.id.contains(it, ignoreCase = true) }

    /** 400 unless the model is a currently listed free model, "auto" or "mock". */
    fun requireAllowed(model: String?) {
        if (model == null || model == "auto" || model == "mock") return
        registry.split(model)?.let { (p, bare) ->
            val ok = providerSetting != "mock" && p.configured && bare in p.models && policies()[model] == true
            if (!ok) throw ApiException.badRequest("MODEL_NOT_ALLOWED", "This model is not configured or not enabled by an administrator")
            return
        }
        if (!externalEnabled) return                                  // choice is irrelevant while only the simulator runs
        if (freeModels().none { it.id == model } || policies()[model] == false) throw ApiException.badRequest("MODEL_NOT_ALLOWED", "Only enabled free OpenRouter models can be selected")
    }

    /** Models to try for "auto": the free router first, then the roomiest free models, skipping ones that failed in the last 5 minutes. */
    fun autoCandidates(): List<String> {
        val now = Instant.now()
        val disabled = policies().filterValues { !it }.keys
        val all = freeModels().map { it.id }.filter { it !in disabled }
        val healthy = all.filter { (failedUntil[it] ?: Instant.MIN).isBefore(now) }
        return (healthy.ifEmpty { all }).take(maxAttempts.coerceIn(1, 8))
    }

    fun markFailed(model: String) { failedUntil[model] = Instant.now().plusSeconds(300) }
    fun markOk(model: String) { failedUntil.remove(model) }

    companion object {
        const val ROUTER_ID = "openrouter/free"
        private val BLOCKED = listOf("safety", "guard", "moderation", "embed", "lyria", "tts", "whisper", "image-gen")
    }
}
