package com.systemwebstudio.integration.llm

import com.systemwebstudio.common.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class AiModel(val id: String, val name: String, val contextLength: Int)

/**
 * Which models users may pick. Only models OpenRouter lists with zero prompt AND completion price and text-only output
 * are ever offered or accepted, so a user (or a crafted request) cannot spend credits on a paid model even if the key has some.
 */
@Service
class AiService(
    private val client: OpenRouterClient,
    @Value("\${app.llm.provider:auto}") private val providerSetting: String,
    @Value("\${app.openrouter.models-cache-minutes:30}") private val cacheMinutes: Long,
    @Value("\${app.openrouter.max-attempts:4}") val maxAttempts: Int,
    @Value("\${app.openrouter.daily-limit-per-user:50}") val dailyLimitPerUser: Long,
    @Value("\${app.openrouter.model-allowlist:}") allowlist: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val allow = allowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    @Volatile private var cache: Pair<Instant, List<AiModel>>? = null
    private val failedUntil = ConcurrentHashMap<String, Instant>()

    val externalEnabled: Boolean get() = providerSetting != "mock" && client.configured

    /** "auto" (or null) means "let the server try free models in turn"; "mock" is the built-in simulator. */
    fun isExternal(model: String?): Boolean = externalEnabled && model != "mock"

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
        if (!externalEnabled) return                                  // choice is irrelevant while only the simulator runs
        if (freeModels().none { it.id == model }) throw ApiException.badRequest("MODEL_NOT_ALLOWED", "Only free OpenRouter models can be selected")
    }

    /** Models to try for "auto": the free router first, then the roomiest free models, skipping ones that failed in the last 5 minutes. */
    fun autoCandidates(): List<String> {
        val now = Instant.now()
        val all = freeModels().map { it.id }
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
