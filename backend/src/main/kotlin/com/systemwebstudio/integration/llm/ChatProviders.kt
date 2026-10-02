package com.systemwebstudio.integration.llm

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One chat backend (ADR 0007). Implementations receive the BARE model name (without the "provider:" prefix), send the key only in
 * their auth header, never copy error bodies into messages or logs (they can echo the prompt), and report only provider-returned usage.
 */
interface ChatProvider {
    val id: String
    val displayName: String
    /** key (if the provider needs one) and an explicit model list are present. Says nothing about reachability. */
    val configured: Boolean
    /** bare model names an admin may enable; nothing outside this list can be called */
    val models: List<String>
    /** for display only: scheme + host of the endpoint, never a key */
    val endpointHost: String?
    /** true when calls may be billed (shown to users; models of such providers start disabled) */
    val paid: Boolean
    fun chat(model: String, system: String, user: String, maxTokens: Int = 2500): ChatResult
    /** lists models on the provider (no tokens spent); throws [AiProviderException] when unreachable or the key is rejected */
    fun probe(): String
}

data class ProviderSlot(
    val id: String, val displayName: String, val apiKey: String, val baseUrl: String, val models: List<String>, val keyRequired: Boolean,
    val paid: Boolean, val timeoutSeconds: Long,
    /** OpenAI's current API wants max_completion_tokens; most compatible servers accept max_tokens */
    val tokenParam: String = "max_tokens", val sendTemperature: Boolean = true
) {
    val configured get() = baseUrl.isNotBlank() && models.isNotEmpty() && (!keyRequired || apiKey.isNotBlank())
    val host: String? get() = runCatching { URI(baseUrl).let { "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}" } }.getOrNull()
}

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private fun send(request: HttpRequest, name: String): HttpResponse<String> = try { http.send(request, HttpResponse.BodyHandlers.ofString()) }
    catch (e: java.net.http.HttpTimeoutException) { throw AiProviderException("$name: timeout") }
    catch (e: Exception) { throw AiProviderException("$name: network error ${e.javaClass.simpleName}") }

private fun failOn(status: Int, name: String) {
    if (status == 401 || status == 403) throw AiProviderException("$name rejected the API key (HTTP $status)", status, fatal = true)
    if (status == 402) throw AiProviderException("$name reports insufficient credit (HTTP 402)", status, fatal = true)
    if (status !in 200..299) throw AiProviderException("$name: HTTP $status", status)
}

/** Any OpenAI-compatible chat API: OpenAI, Gemini's OpenAI-compatible endpoint, Ollama/vLLM/LM Studio, or a gateway such as LiteLLM. */
class OpenAiCompatibleProvider(private val slot: ProviderSlot, private val json: JsonMapper) : ChatProvider {
    override val id = slot.id
    override val displayName = slot.displayName
    override val configured get() = slot.configured
    override val models get() = slot.models
    override val endpointHost get() = slot.host
    override val paid get() = slot.paid
    private val base get() = slot.baseUrl.trimEnd('/')

    private fun builder(path: String, timeout: Long) = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(timeout)).header("Accept", "application/json")
        .also { if (slot.apiKey.isNotBlank()) it.header("Authorization", "Bearer ${slot.apiKey}") }

    override fun chat(model: String, system: String, user: String, maxTokens: Int): ChatResult {
        if (!configured) throw AiProviderException("$displayName is not configured", fatal = true)
        val body = linkedMapOf<String, Any>("model" to model, "stream" to false, slot.tokenParam to maxTokens,
            "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user)))
        if (slot.sendTemperature) body["temperature"] = 0.2
        val response = send(builder("/chat/completions", slot.timeoutSeconds).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), displayName)
        failOn(response.statusCode(), displayName)
        val root: JsonNode = try { json.readTree(response.body()) } catch (e: Exception) { throw AiProviderException("$displayName: unreadable response") }
        val usage = openAiUsage(root)
        if (root.has("error")) throw AiProviderException("$displayName: provider error", usage = usage)
        val content = root.get("choices")?.get(0)?.get("message")?.get("content")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException("$displayName: empty completion", usage = usage)
        return ChatResult(content, usage)
    }

    override fun probe(): String {
        val response = send(builder("/models", 15).GET().build(), displayName)
        failOn(response.statusCode(), displayName)
        val n = runCatching { json.readTree(response.body()).get("data")?.size() }.getOrNull()
        return if (n != null) "$n models listed" else "reachable"
    }
}

/** Anthropic Messages API (https://docs.anthropic.com/en/api/messages). */
class AnthropicProvider(private val slot: ProviderSlot, private val json: JsonMapper) : ChatProvider {
    override val id = slot.id
    override val displayName = slot.displayName
    override val configured get() = slot.configured
    override val models get() = slot.models
    override val endpointHost get() = slot.host
    override val paid get() = slot.paid
    private val base get() = slot.baseUrl.trimEnd('/')

    private fun builder(path: String, timeout: Long) = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(timeout))
        .header("x-api-key", slot.apiKey).header("anthropic-version", "2023-06-01").header("Accept", "application/json")

    override fun chat(model: String, system: String, user: String, maxTokens: Int): ChatResult {
        if (!configured) throw AiProviderException("$displayName is not configured", fatal = true)
        val body = mapOf("model" to model, "max_tokens" to maxTokens, "temperature" to 0.2, "system" to system,
            "messages" to listOf(mapOf("role" to "user", "content" to user)))
        val response = send(builder("/v1/messages", slot.timeoutSeconds).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), displayName)
        failOn(response.statusCode(), displayName)
        val root: JsonNode = try { json.readTree(response.body()) } catch (e: Exception) { throw AiProviderException("$displayName: unreadable response") }
        val usage = usage(root)
        if (root.get("type")?.asString() == "error") throw AiProviderException("$displayName: provider error", usage = usage)
        val text = root.get("content")?.toList().orEmpty().filter { it.get("type")?.asString() == "text" }.joinToString("") { it.get("text")?.asString().orEmpty() }
        if (text.isBlank()) throw AiProviderException("$displayName: empty completion", usage = usage)
        return ChatResult(text, usage)
    }

    internal fun usage(root: JsonNode): ChatUsage? {
        val u = root.get("usage")?.takeIf { it.isObject } ?: return null
        fun tokens(name: String) = u.get(name)?.takeIf { it.isIntegralNumber }?.asLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        val input = tokens("input_tokens")
        // prompt caching reports cached input separately; all of it is input the request consumed
        val prompt = input?.let { it + (tokens("cache_creation_input_tokens") ?: 0) + (tokens("cache_read_input_tokens") ?: 0) }
        val completion = tokens("output_tokens")
        return ChatUsage(prompt, completion, if (prompt != null && completion != null) prompt + completion else null, null,
            root.get("id")?.takeIf { it.isString }?.asString()?.take(160))
    }

    override fun probe(): String {
        val response = send(builder("/v1/models", 15).GET().build(), displayName)
        failOn(response.statusCode(), displayName)
        val n = runCatching { json.readTree(response.body()).get("data")?.size() }.getOrNull()
        return if (n != null) "$n models listed" else "reachable"
    }
}

/**
 * The configured non-OpenRouter providers. Model ids users see are namespaced "provider:model" (e.g. "openai:gpt-…"); bare ids
 * (e.g. "vendor/x:free", "openrouter/free") stay OpenRouter's, so earlier choices and stored data keep working.
 * Configuration (environment only): <PREFIX>_API_KEY, <PREFIX>_BASE_URL, <PREFIX>_MODELS (comma list) for OPENAI, ANTHROPIC, GEMINI, LOCAL_LLM.
 */
@Component
class AiProviderRegistry(private val env: Environment, json: JsonMapper) {
    private fun slot(id: String, name: String, defaultBase: String, keyRequired: Boolean, paid: Boolean, tokenParam: String = "max_tokens", temperature: Boolean = true) =
        ProviderSlot(id, name,
            env.getProperty("app.ai.providers.$id.api-key", "").trim(),
            env.getProperty("app.ai.providers.$id.base-url", defaultBase).trim(),
            env.getProperty("app.ai.providers.$id.models", "").split(",").map { it.trim() }.filter { MODEL.matches(it) }.distinct(),
            keyRequired, paid, env.getProperty("app.ai.providers.$id.timeout-seconds", Long::class.java, 60L), tokenParam, temperature)

    val providers: Map<String, ChatProvider> = listOf(
        OpenAiCompatibleProvider(slot("openai", "OpenAI", "https://api.openai.com/v1", true, true, "max_completion_tokens", false), json),
        AnthropicProvider(slot("anthropic", "Anthropic", "https://api.anthropic.com", true, true), json),
        OpenAiCompatibleProvider(slot("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", true, true), json),
        OpenAiCompatibleProvider(slot("local", "Model nội bộ (OpenAI-compatible)", "", false, false), json)
    ).associateBy { it.id }

    fun configured(): List<ChatProvider> = providers.values.filter { it.configured }

    /** "openai:gpt-x" → (provider, "gpt-x"); null for OpenRouter/bare ids, "auto" and "mock" */
    fun split(modelId: String?): Pair<ChatProvider, String>? {
        if (modelId == null) return null
        val i = modelId.indexOf(':'); if (i <= 0) return null
        val p = providers[modelId.substring(0, i)] ?: return null
        return p to modelId.substring(i + 1)
    }

    companion object { val MODEL = Regex("^[A-Za-z0-9._/:@-]{1,100}$") }
}
