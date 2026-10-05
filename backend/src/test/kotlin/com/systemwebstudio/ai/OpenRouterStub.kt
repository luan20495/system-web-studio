package com.systemwebstudio.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Local stand-in for OpenRouter (no network, no key, no cost), shared by the AI test classes. */
object OpenRouterStub {
    class Call(val model: String, val auth: String?, val system: String, val user: String, val body: tools.jackson.databind.JsonNode)

    val calls = CopyOnWriteArrayList<Call>()
    /** model id -> successive (status, content) answers; the last one repeats */
    val script = ConcurrentHashMap<String, List<Pair<Int, String>>>()
    val served = ConcurrentHashMap<String, Int>()
    /** model id -> the `usage` object to return with a 200 answer; absent = DEFAULT_USAGE, an empty map = no usage field at all */
    val usage = ConcurrentHashMap<String, Map<String, Any>>()
    /** runs inside the upstream call, before answering (to simulate things happening while the model "thinks") */
    @Volatile var duringCall: (() -> Unit)? = null
    val DEFAULT_USAGE = mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120, "cost" to 0)
    private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

    private val modelsJson = """{"data":[
      {"id":"vendor/alpha:free","name":"Alpha","context_length":32000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/beta:free","name":"Beta","context_length":128000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/gamma:free","name":"Gamma","context_length":64000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
      {"id":"openrouter/free","name":"Free router","context_length":200000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/paid","name":"Paid","context_length":128000,"pricing":{"prompt":"0.000001","completion":"0.000002"},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/audio:free","name":"Audio","context_length":128000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text","audio"]}},
      {"id":"vendor/guard-safety:free","name":"Safety classifier","context_length":128000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/tiny:free","name":"Tiny","context_length":4000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}}]}"""

    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/models") { ex -> reply(ex, 200, modelsJson) }
        createContext("/chat/completions") { ex ->
            val body = mapper.readTree(ex.requestBody.readBytes())
            val model = body.get("model").asString()
            val messages = body.get("messages").toList()
            calls += Call(model, ex.requestHeaders.getFirst("Authorization"), messages[0].get("content").asString(), messages[1].get("content").asString(), body)
            duringCall?.invoke()
            val answers = script[model] ?: listOf(500 to "")
            val n = served.merge(model, 1) { a, b -> a + b }!!
            val (status, content) = answers[minOf(n - 1, answers.size - 1)]
            val u = usage[model] ?: DEFAULT_USAGE
            val ok = mutableMapOf<String, Any>("id" to "gen-$model-$n", "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))))
            if (u.isNotEmpty()) ok["usage"] = u
            reply(ex, status, if (status == 200) mapper.writeValueAsString(ok) else """{"error":{"code":$status,"message":"SECRET-ECHO"}}""")
        }
        start()
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("app.openrouter.base-url") { "http://127.0.0.1:${server.address.port}" }
        registry.add("app.openrouter.api-key") { "sk-or-test-key-123456" }
    }

    fun reset() { calls.clear(); script.clear(); served.clear(); usage.clear(); duringCall = null }
}
