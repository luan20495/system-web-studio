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

class OpenRouterException(message: String, val status: Int = 0, val fatal: Boolean = false) : RuntimeException(message)

data class OrModel(val id: String, val name: String, val contextLength: Int, val promptPrice: String?, val completionPrice: String?, val outputText: Boolean)

/**
 * Thin HTTP client for OpenRouter's OpenAI-compatible API (https://openrouter.ai/docs). The API key comes only from
 * configuration (OPENROUTER_API_KEY), is sent only in the Authorization header and is never logged or returned to clients.
 */
@Component
class OpenRouterClient(
    private val json: JsonMapper,
    @Value("\${app.openrouter.api-key:}") private val apiKey: String,
    @Value("\${app.openrouter.base-url:https://openrouter.ai/api/v1}") private val baseUrl: String,
    @Value("\${app.openrouter.referer:}") private val referer: String,
    @Value("\${app.openrouter.title:System Web Studio}") private val title: String,
    @Value("\${app.openrouter.timeout-seconds:45}") private val timeoutSeconds: Long
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val configured: Boolean get() = apiKey.isNotBlank()

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

    /** Returns the assistant message text. Throws [OpenRouterException]; `fatal` means retrying another model cannot help (bad key, no credit). */
    fun chat(model: String, system: String, user: String, maxTokens: Int = 2500): String {
        if (!configured) throw OpenRouterException("OpenRouter API key is not configured", fatal = true)
        val body = json.writeValueAsString(mapOf(
            "model" to model, "temperature" to 0.2, "max_tokens" to maxTokens, "stream" to false,
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
        if (root.has("error")) throw OpenRouterException("provider error", root.get("error")?.get("code")?.asInt(0) ?: 0)
        return root.get("choices")?.get(0)?.get("message")?.get("content")?.asString()?.takeIf { it.isNotBlank() } ?: throw OpenRouterException("empty completion")
    }
}
