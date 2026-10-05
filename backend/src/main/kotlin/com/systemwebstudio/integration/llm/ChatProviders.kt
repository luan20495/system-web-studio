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
    /** streamed variant; providers without streaming answer in one piece */
    fun chatStream(model: String, system: String, user: String, maxTokens: Int, sink: StreamSink): ChatResult =
        chat(model, system, user, maxTokens).also { sink.delta(it.content) }
    /** lists models on the provider (no tokens spent); throws [AiProviderException] when unreachable or the key is rejected */
    fun probe(): String
    /** address and key (when required) are present, so a connection test makes sense even before any model is listed */
    val connectable: Boolean get() = configured
    /** model ids the provider reports (no tokens spent); empty when the provider cannot list them */
    fun listModels(): List<String> = emptyList()
    /** per-model price class; most providers are all-or-nothing */
    fun isPaid(model: String): Boolean = paid
}

data class ProviderSlot(
    val id: String, val displayName: String, val apiKey: String, val baseUrl: String, val models: List<String>, val keyRequired: Boolean,
    val paid: Boolean, val timeoutSeconds: Long,
    /** OpenAI's current API wants max_completion_tokens; most compatible servers accept max_tokens */
    val tokenParam: String = "max_tokens", val sendTemperature: Boolean = true,
    /** added on the web (true) or configured in the environment (false) */
    val fromWeb: Boolean = false, val enabled: Boolean = true
) {
    val configured get() = enabled && connectable && models.isNotEmpty()
    val connectable get() = baseUrl.isNotBlank() && (!keyRequired || apiKey.isNotBlank())
    val host: String? get() = runCatching { URI(baseUrl).let { "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}" } }.getOrNull()
}

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private fun send(request: HttpRequest, name: String): HttpResponse<String> = try { http.send(request, HttpResponse.BodyHandlers.ofString()) }
    catch (e: java.net.http.HttpTimeoutException) { throw AiProviderException("$name: timeout") }
    catch (e: Exception) { throw AiProviderException("$name: network error ${e.javaClass.simpleName}") }

/**
 * Reads a server-sent-events body line by line. [onData] gets each `data:` payload (parsed JSON) and returns text to append (or null).
 * Cancel/deadline: checked per line; a read blocked between chunks is woken by the controller interrupting the worker thread.
 * The error body of a failed request is never read into messages (it can echo the prompt).
 */
internal fun streamSse(request: HttpRequest, name: String, sink: StreamSink, json: JsonMapper, whole: ((JsonNode) -> ChatResult)? = null,
                       onData: (JsonNode, (ChatUsage?) -> Unit) -> String?): ChatResult {
    val text = StringBuilder(); var usage: ChatUsage? = null
    sink.stopReason()?.let { throw AiStopped(it, "", null) }
    sink.inModelCall = true
    try {
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: java.net.http.HttpTimeoutException) { throw AiProviderException("$name: timeout") }
            catch (e: Exception) { sink.stopReason()?.let { throw AiStopped(it, "", null) }; throw AiProviderException("$name: network error ${e.javaClass.simpleName}") }
        response.body().use { body ->
            if (response.statusCode() !in 200..299) failOn(response.statusCode(), name)
            // a server that ignores "stream": true answers one JSON document: use it as a normal (non-streamed) answer
            if (whole != null && response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
                val root = try { json.readTree(body.readNBytes(5_000_000)) } catch (e: Exception) { throw AiProviderException("$name: unreadable response") }
                return whole(root).also { sink.delta(it.content) }
            }
            val reader = body.bufferedReader(Charsets.UTF_8)
            while (true) {
                sink.stopReason()?.let { throw AiStopped(it, text.toString(), usage) }
                val line = try { reader.readLine() } catch (e: java.io.IOException) {
                    sink.stopReason()?.let { throw AiStopped(it, text.toString(), usage) }; throw AiProviderException("$name: stream interrupted", usage = usage)
                } ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val node = runCatching { json.readTree(data) }.getOrNull() ?: continue
                val piece = onData(node) { u -> if (u != null) usage = u }
                if (!piece.isNullOrEmpty()) { text.append(piece); sink.delta(piece) }
            }
        }
    } finally { sink.inModelCall = false; Thread.interrupted() }
    sink.stopReason()?.let { throw AiStopped(it, text.toString(), usage) }
    if (text.isBlank()) throw AiProviderException("$name: empty completion", usage = usage)
    return ChatResult(text.toString(), usage)
}

/** one chunk of an OpenAI-style stream: delta text; usage arrives in the last chunk (stream_options.include_usage / OpenRouter usage.include) */
internal fun openAiChunk(name: String, node: JsonNode, setUsage: (ChatUsage?) -> Unit): String? {
    val u = openAiUsage(node); if (u != null && (u.totalTokens != null || u.costUsd != null)) setUsage(u)
    if (node.has("error")) throw AiProviderException("$name: provider error", usage = u)
    return node.get("choices")?.get(0)?.get("delta")?.get("content")?.takeIf { it.isString }?.asString()
}

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
    override val connectable get() = slot.connectable
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

    override fun chatStream(model: String, system: String, user: String, maxTokens: Int, sink: StreamSink): ChatResult {
        if (!configured) throw AiProviderException("$displayName is not configured", fatal = true)
        val body = linkedMapOf<String, Any>("model" to model, "stream" to true, "stream_options" to mapOf("include_usage" to true), slot.tokenParam to maxTokens,
            "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user)))
        if (slot.sendTemperature) body["temperature"] = 0.2
        val request = builder("/chat/completions", slot.timeoutSeconds).header("Content-Type", "application/json").header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()
        return streamSse(request, displayName, sink, json, whole = { root ->
            val usage = openAiUsage(root)
            if (root.has("error")) throw AiProviderException("$displayName: provider error", usage = usage)
            ChatResult(root.get("choices")?.get(0)?.get("message")?.get("content")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
                ?: throw AiProviderException("$displayName: empty completion", usage = usage), usage)
        }) { node, setUsage -> openAiChunk(displayName, node, setUsage) }
    }

    override fun probe(): String {
        val response = send(builder("/models", 15).GET().build(), displayName)
        failOn(response.statusCode(), displayName)
        val n = runCatching { json.readTree(response.body()).get("data")?.size() }.getOrNull()
        return if (n != null) "$n models listed" else "reachable"
    }

    override fun listModels(): List<String> {
        val response = send(builder("/models", 15).GET().build(), displayName)
        failOn(response.statusCode(), displayName)
        return parseModelIds(json, response.body())
    }
}

/** model ids of an OpenAI/Anthropic-style `{"data":[{"id":..}]}` list (Gemini prefixes ids with "models/"), only ids the registry accepts */
internal fun parseModelIds(json: JsonMapper, body: String): List<String> =
    runCatching { json.readTree(body).get("data")?.toList().orEmpty().mapNotNull { it.get("id")?.asString()?.removePrefix("models/") } }
        .getOrDefault(emptyList()).filter { AiProviderRegistry.MODEL.matches(it) }.distinct().sorted().take(500)

/** Anthropic Messages API (https://docs.anthropic.com/en/api/messages). */
class AnthropicProvider(private val slot: ProviderSlot, private val json: JsonMapper) : ChatProvider {
    override val id = slot.id
    override val displayName = slot.displayName
    override val configured get() = slot.configured
    override val connectable get() = slot.connectable
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

    /** Messages API stream: message_start carries input usage, content_block_delta the text, message_delta the output usage. */
    override fun chatStream(model: String, system: String, user: String, maxTokens: Int, sink: StreamSink): ChatResult {
        if (!configured) throw AiProviderException("$displayName is not configured", fatal = true)
        val body = mapOf("model" to model, "max_tokens" to maxTokens, "temperature" to 0.2, "system" to system, "stream" to true,
            "messages" to listOf(mapOf("role" to "user", "content" to user)))
        val request = builder("/v1/messages", slot.timeoutSeconds).header("Content-Type", "application/json").header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()
        var start: ChatUsage? = null; var id: String? = null
        return streamSse(request, displayName, sink, json) { node, setUsage ->
            when (node.get("type")?.asString()) {
                "message_start" -> { val m = node.get("message"); id = m?.get("id")?.asString(); start = m?.let { usage(it) }; setUsage(start); null }
                "content_block_delta" -> node.get("delta")?.takeIf { it.get("type")?.asString() == "text_delta" }?.get("text")?.asString()
                "message_delta" -> {
                    val out = node.get("usage")?.get("output_tokens")?.takeIf { it.isIntegralNumber }?.asInt()
                    val p = start?.promptTokens
                    setUsage(ChatUsage(p, out, if (p != null && out != null) p + out else null, null, id?.take(160))); null
                }
                "error" -> throw AiProviderException("$displayName: provider error", usage = start)
                else -> null
            }
        }
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

    override fun listModels(): List<String> {
        val response = send(builder("/v1/models?limit=200".let { if (this is OpenAiCompatibleProvider) "/models" else it }, 15).GET().build(), displayName)
        failOn(response.statusCode(), displayName)
        return parseModelIds(json, response.body())
    }
}

/**
 * The configured non-OpenRouter providers. Model ids users see are namespaced "provider:model" (e.g. "openai:gpt-…"); bare ids
 * (e.g. "vendor/x:free", "openrouter/free") stay OpenRouter's, so earlier choices and stored data keep working.
 * Configuration (environment only): <PREFIX>_API_KEY, <PREFIX>_BASE_URL, <PREFIX>_MODELS (comma list) for OPENAI, ANTHROPIC, GEMINI, LOCAL_LLM.
 */
@Component
class AiProviderRegistry(private val env: Environment, private val json: JsonMapper, private val store: ProviderStore) {
    private fun slot(id: String, name: String, defaultBase: String, keyRequired: Boolean, paid: Boolean, tokenParam: String = "max_tokens", temperature: Boolean = true) =
        ProviderSlot(id, name,
            env.getProperty("app.ai.providers.$id.api-key", "").trim(),
            env.getProperty("app.ai.providers.$id.base-url", defaultBase).trim(),
            env.getProperty("app.ai.providers.$id.models", "").split(",").map { it.trim() }.filter { MODEL.matches(it) }.distinct(),
            keyRequired, paid, env.getProperty("app.ai.providers.$id.timeout-seconds", Long::class.java, 60L), tokenParam, temperature)

    /** configured by the operator (environment): always wins over a provider with the same id added on the web */
    private val fromEnvironment: Map<String, ChatProvider> = listOf(
        OpenAiCompatibleProvider(slot("openai", "OpenAI", "https://api.openai.com/v1", true, true, "max_completion_tokens", false), json),
        AnthropicProvider(slot("anthropic", "Anthropic", "https://api.anthropic.com", true, true), json),
        OpenAiCompatibleProvider(slot("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", true, true), json),
        OpenAiCompatibleProvider(slot("local", "Model nội bộ (OpenAI-compatible)", "", false, false), json)
    ).associateBy { it.id }

    @Volatile private var cache: Pair<Long, Map<String, ChatProvider>>? = null
    fun invalidate() { cache = null; store.invalidate() }

    /** environment providers plus the ones an admin added on the web (OpenRouter's key is handled by [OpenRouterClient]) */
    val providers: Map<String, ChatProvider> get() {
        cache?.takeIf { System.currentTimeMillis() - it.first < 5000 }?.let { return it.second }
        val fromWeb = store.all().filter { it.kind != "OPENROUTER" && fromEnvironment[it.slug]?.configured != true }.associate { it.slug to build(it) }
        val all = fromEnvironment.filterKeys { it !in fromWeb } + fromWeb
        cache = System.currentTimeMillis() to all
        return all
    }

    private fun build(p: StoredProvider): ChatProvider {
        val s = ProviderSlot(p.slug, p.name, p.apiKey.orEmpty(), p.baseUrl.ifBlank { defaultBase(p.kind) }, p.models.filter { MODEL.matches(it) },
            keyRequired = p.kind in setOf("OPENAI", "ANTHROPIC", "GEMINI"), paid = p.paid, timeoutSeconds = 60,
            tokenParam = if (p.kind == "OPENAI") "max_completion_tokens" else "max_tokens", sendTemperature = p.kind != "OPENAI", fromWeb = true, enabled = p.enabled)
        return if (p.kind == "ANTHROPIC") AnthropicProvider(s, json) else OpenAiCompatibleProvider(s, json)
    }

    /** true when this provider's settings come from the environment (shown to admins as managed by the system) */
    fun managedBySystem(id: String) = fromEnvironment[id]?.let { it.configured && providers[id] === it } ?: false

    fun configured(): List<ChatProvider> = providers.values.filter { it.configured }

    /** "openai:gpt-x" → (provider, "gpt-x"); null for OpenRouter/bare ids, "auto" and "mock" */
    fun split(modelId: String?): Pair<ChatProvider, String>? {
        if (modelId == null) return null
        val i = modelId.indexOf(':'); if (i <= 0) return null
        val p = providers[modelId.substring(0, i)] ?: return null
        return p to modelId.substring(i + 1)
    }

    companion object {
        val MODEL = Regex("^[A-Za-z0-9._/:@-]{1,100}$")
        fun defaultBase(kind: String) = when (kind) {
            "OPENAI" -> "https://api.openai.com/v1"; "ANTHROPIC" -> "https://api.anthropic.com"
            "GEMINI" -> "https://generativelanguage.googleapis.com/v1beta/openai"; "OPENROUTER" -> "https://openrouter.ai/api/v1"; else -> ""
        }
    }
}
