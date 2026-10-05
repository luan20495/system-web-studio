package com.systemwebstudio.integration.llm

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Any provider's failure. `fatal` = another attempt cannot help (bad key, no credit); `usage` is set when the provider answered (and so may have billed) although the answer was unusable. */
open class AiProviderException(message: String, val status: Int = 0, val fatal: Boolean = false, val usage: ChatUsage? = null) : RuntimeException(message)
typealias OpenRouterException = AiProviderException

/** Exactly what the provider reported in `usage`; a null field means "not reported", never an estimate. */
data class ChatUsage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?, val costUsd: java.math.BigDecimal?, val generationId: String?)
data class ChatResult(val content: String, val usage: ChatUsage?)

data class OrModel(val id: String, val name: String, val contextLength: Int, val promptPrice: String?, val completionPrice: String?, val outputText: Boolean)

/**
 * Thin HTTP client for OpenRouter's OpenAI-compatible API (https://openrouter.ai/docs). The API key comes only from
 * configuration (OPENROUTER_API_KEY), is sent only in the Authorization header and is never logged or returned to clients.
 */
@Component
class OpenRouterClient(
    private val json: JsonMapper,
    @Value("\${app.openrouter.api-key:}") private val envKey: String,
    private val store: ProviderStore,
    @Value("\${app.openrouter.base-url:https://openrouter.ai/api/v1}") private val baseUrl: String,
    @Value("\${app.openrouter.referer:}") private val referer: String,
    @Value("\${app.openrouter.title:System Web Studio}") private val title: String,
    @Value("\${app.openrouter.timeout-seconds:45}") private val timeoutSeconds: Long
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    /** the environment (managed by the operator) wins; otherwise the key an admin saved on the web */
    private val apiKey: String get() = envKey.ifBlank { store.openRouterKey().orEmpty() }
    val configured: Boolean get() = apiKey.isNotBlank()
    val managedByEnvironment: Boolean get() = envKey.isNotBlank()

    /** Public endpoint, no key needed. */
    fun listModels(): List<OrModel> {
        val request = HttpRequest.newBuilder(URI("$baseUrl/models")).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw OpenRouterException("model list unavailable: ${e.javaClass.simpleName}") }
        if (response.statusCode() != 200) throw OpenRouterException("model list returned ${response.statusCode()}", response.statusCode())
        val data = json.readTree(response.body()).get("data") ?: return emptyList()
        return data.toList().mapNotNull { m ->
            val id = m.get("id")?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val outputs = m.get("architecture")?.get("output_modalities")?.toList()?.map { it.asString() }
                ?: listOfNotNull(m.get("architecture")?.get("modality")?.asString()?.substringAfter("->"))
            OrModel(id, m.get("name")?.asString() ?: id, m.get("context_length")?.asInt(0) ?: 0,
                m.get("pricing")?.get("prompt")?.asString(), m.get("pricing")?.get("completion")?.asString(), outputs == listOf("text"))
        }
    }

    /** Free check of the key (no tokens): OpenRouter's key endpoint answers 200 for a valid key and 401 otherwise. */
    fun checkKey() {
        if (!configured) throw OpenRouterException("OpenRouter API key is not configured", fatal = true)
        val request = HttpRequest.newBuilder(URI("$baseUrl/key")).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $apiKey").header("Accept", "application/json").GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.discarding()) }
            catch (e: java.net.http.HttpTimeoutException) { throw OpenRouterException("timeout") } catch (e: Exception) { throw OpenRouterException("network error: ${e.javaClass.simpleName}") }
        if (response.statusCode() in setOf(401, 403)) throw OpenRouterException("OpenRouter rejected the API key (HTTP ${response.statusCode()})", response.statusCode(), fatal = true)
        if (response.statusCode() != 200) throw OpenRouterException("HTTP ${response.statusCode()}", response.statusCode())
    }

    /** Returns the assistant message text and the reported usage. Throws [OpenRouterException]; `fatal` means retrying another model cannot help (bad key, no credit). */
    fun chat(model: String, system: String, user: String, maxTokens: Int = 2500): ChatResult {
        if (!configured) throw OpenRouterException("OpenRouter API key is not configured", fatal = true)
        val body = json.writeValueAsString(mapOf(
            "model" to model, "temperature" to 0.2, "max_tokens" to maxTokens, "stream" to false,
            "usage" to mapOf("include" to true),                       // OpenRouter usage accounting: tokens + cost in the response
            "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))
        ))
        val builder = HttpRequest.newBuilder(URI("$baseUrl/chat/completions")).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").header("X-Title", title)
        if (referer.isNotBlank()) builder.header("HTTP-Referer", referer)
        val response = try { http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()) }
        catch (e: java.net.http.HttpTimeoutException) { throw OpenRouterException("timeout") }
        catch (e: Exception) { throw OpenRouterException("network error: ${e.javaClass.simpleName}") }
        val status = response.statusCode()
        // Error bodies are never copied into messages or logs: they can echo request content.
        if (status == 401 || status == 403) throw OpenRouterException("OpenRouter rejected the API key (HTTP $status)", status, fatal = true)
        if (status == 402) throw OpenRouterException("OpenRouter reports insufficient credit (HTTP 402)", status, fatal = true)
        if (status != 200) throw OpenRouterException("HTTP $status", status)
        val root: JsonNode = try { json.readTree(response.body()) } catch (e: Exception) { throw OpenRouterException("unreadable response") }
        val usage = usage(root)
        if (root.has("error")) throw OpenRouterException("provider error", root.get("error")?.get("code")?.asInt(0) ?: 0, usage = usage)
        val content = root.get("choices")?.get(0)?.get("message")?.get("content")?.asString()?.takeIf { it.isNotBlank() }
            ?: throw OpenRouterException("empty completion", usage = usage)
        return ChatResult(content, usage)
    }

    /** Streamed chat; OpenRouter sends usage (tokens + cost) in the last chunk when usage.include is set. */
    fun chatStream(model: String, system: String, user: String, maxTokens: Int, sink: StreamSink): ChatResult {
        if (!configured) throw OpenRouterException("OpenRouter API key is not configured", fatal = true)
        val body = json.writeValueAsString(mapOf(
            "model" to model, "temperature" to 0.2, "max_tokens" to maxTokens, "stream" to true, "usage" to mapOf("include" to true),
            "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))
        ))
        val builder = HttpRequest.newBuilder(URI("$baseUrl/chat/completions")).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").header("Accept", "text/event-stream").header("X-Title", title)
        if (referer.isNotBlank()) builder.header("HTTP-Referer", referer)
        return streamSse(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), "OpenRouter", sink, json) { node, setUsage -> openAiChunk("OpenRouter", node, setUsage) }
    }

    internal fun usage(root: JsonNode): ChatUsage? = openAiUsage(root)
}

/** `usage` of an OpenAI-style chat completion (OpenRouter adds `cost`). Only reported values; nothing is estimated. */
fun openAiUsage(root: JsonNode): ChatUsage? {
    val u = root.get("usage")?.takeIf { it.isObject } ?: return null
    fun tokens(name: String) = u.get(name)?.takeIf { it.isIntegralNumber }?.asLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    val cost = u.get("cost")?.takeIf { it.isNumber }?.let { runCatching { java.math.BigDecimal(it.asString()) }.getOrNull() }?.takeIf { it.signum() >= 0 }
    val prompt = tokens("prompt_tokens"); val completion = tokens("completion_tokens")
    val total = tokens("total_tokens") ?: if (prompt != null && completion != null) prompt + completion else null
    val id = root.get("id")?.takeIf { it.isString }?.asString()?.take(160)
    return ChatUsage(prompt, completion, total, cost, id)
}
