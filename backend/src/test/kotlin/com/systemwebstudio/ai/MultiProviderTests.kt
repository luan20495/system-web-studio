package com.systemwebstudio.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Phase 6 (ADR 0007): OpenAI-compatible, Anthropic and local providers against local stubs (no network, no keys, no cost). */
class MultiProviderTests : IntegrationTestBase() {
    class Req(val path: String, val headers: Map<String, String?>, val body: tools.jackson.databind.JsonNode?)

    companion object {
        val requests = CopyOnWriteArrayList<Req>()
        /** path -> (status, body) */
        val replies = ConcurrentHashMap<String, Pair<Int, String>>()
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        private const val OPS = """{"message":"xong","operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Tiêu đề từ provider"}]}"""
        val openAiOk = mapper.writeValueAsString(mapOf("id" to "chatcmpl-1", "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to OPS))),
            "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120)))
        val anthropicOk = mapper.writeValueAsString(mapOf("id" to "msg_1", "type" to "message", "content" to listOf(mapOf("type" to "text", "text" to OPS)),
            "usage" to mapOf("input_tokens" to 100, "cache_read_input_tokens" to 10, "output_tokens" to 20)))

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val raw = ex.requestBody.readBytes()
                val path = ex.requestURI.path
                requests += Req(path, listOf("Authorization", "x-api-key", "anthropic-version").associateWith { ex.requestHeaders.getFirst(it) },
                    if (raw.isNotEmpty()) mapper.readTree(raw) else null)
                val (status, body) = replies[path] ?: when {
                    path.endsWith("/models") -> 200 to """{"data":[{"id":"a"},{"id":"b"}]}"""
                    path.startsWith("/anthropic") -> 200 to anthropicOk
                    else -> 200 to openAiOk
                }
                reply(ex, status, body)
            }
            start()
        }
        private fun reply(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic @DynamicPropertySource
        fun providers(registry: DynamicPropertyRegistry) {
            val base = "http://127.0.0.1:${server.address.port}"
            registry.add("app.ai.providers.openai.api-key") { "sk-test-openai-123456" }
            registry.add("app.ai.providers.openai.base-url") { "$base/openai" }
            registry.add("app.ai.providers.openai.models") { "gpt-test, gpt-other" }
            registry.add("app.ai.providers.anthropic.api-key") { "sk-ant-test-456789" }
            registry.add("app.ai.providers.anthropic.base-url") { "$base/anthropic" }
            registry.add("app.ai.providers.anthropic.models") { "claude-test" }
            registry.add("app.ai.providers.local.base-url") { "$base/local" }
            registry.add("app.ai.providers.local.models") { "llama-test" }
            // gemini: not configured; OpenRouter: no key → "auto" is the simulator
        }
    }

    private fun price(a: com.systemwebstudio.support.ApiSession, model: String) { a.post("/api/v1/admin/ai/pricing", """{"modelId":"$model","inputUsdPerMTok":1,"outputUsdPerMTok":1}""") }
    @BeforeEach fun reset() { requests.clear(); replies.clear(); jdbc.update("DELETE FROM ai_model_policies"); jdbc.update("DELETE FROM ai_calls"); jdbc.update("DELETE FROM ai_model_pricing"); grantPaidBudgets() }

    private fun admin() = sessionFor(fx.user("aiprov", systemAdmin = true).username)
    private fun enable(a: com.systemwebstudio.support.ApiSession, model: String, on: Boolean = true) =
        assertThat(a.put("/api/v1/admin/ai/models/policy", """{"modelId":"$model","enabled":$on}""").response.status).isEqualTo(200)
    private fun Scenario.promptWith(model: String) =
        s.post("$base/prompts", """{"prompt":"đổi tiêu đề hero","expectedRevision":${revision()},"model":"$model"}""")
    private fun chatCalls() = requests.filter { it.path.endsWith("/chat/completions") || it.path.endsWith("/v1/messages") }

    @Test
    fun `models of other providers stay disabled until a system admin enables them, unconfigured providers are refused`() {
        val sc = scenario()
        val before = sc.s.body(sc.s.get("/api/v1/ai/status"))
        assertThat(before.get("models").size()).isEqualTo(0); assertThat(before.get("provider").asString()).isEqualTo("mock")
        for (m in listOf("openai:gpt-test", "gemini:gemini-x", "openai:not-listed")) {
            val r = sc.promptWith(m); assertThat(r.response.status).describedAs(m).isEqualTo(400)
            assertThat(sc.s.body(r).get("code").asString()).isEqualTo("MODEL_NOT_ALLOWED")
        }
        // an unknown un-prefixed id is an OpenRouter id; with OpenRouter unconfigured the simulator answers, and says so
        assertThat(sc.s.body(sc.promptWith("nope:model")).get("provider").asString()).isEqualTo("mock")
        assertThat(chatCalls()).isEmpty()

        val adminUser = fx.user("aiprov", systemAdmin = true); val a = sessionFor(adminUser.username)
        val put = { s: com.systemwebstudio.support.ApiSession, body: String -> s.put("/api/v1/admin/ai/models/policy", body).response.status }
        assertThat(put(sc.s, """{"modelId":"openai:gpt-test","enabled":true}""")).isEqualTo(403)
        assertThat(put(a, """{"modelId":"openai:not-listed","enabled":true}""")).isEqualTo(404)
        assertThat(put(a, """{"modelId":"gemini:gemini-x","enabled":true}""")).isEqualTo(404)
        enable(a, "openai:gpt-test")
        val after = sc.s.body(sc.s.get("/api/v1/ai/status"))
        assertThat(after.get("models").toList().map { it.get("id").asString() }).containsExactly("openai:gpt-test")
        val group = after.get("providers").single()
        assertThat(group.get("id").asString()).isEqualTo("openai"); assertThat(group.get("paid").asBoolean()).isTrue()
        assertThat(group.get("dataNotice").asString()).contains("OpenAI")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'AI_MODEL_POLICY' AND resource_id = 'openai:gpt-test' AND actor_id = ?", Long::class.java, adminUser.id)).isEqualTo(1)
    }

    @Test
    fun `an enabled OpenAI-compatible model gets the key only in the header, usage is recorded, cost comes from the pricing catalog`() {
        val sc = scenario(); val a = admin()
        enable(a, "openai:gpt-test")
        // budgets are granted but this paid model has no price: spending cannot be controlled, so it is refused with a clear message
        val refused = sc.promptWith("openai:gpt-test"); assertThat(refused.response.status).isEqualTo(409)
        assertThat(sc.s.body(refused).get("message").asString()).contains("chưa có giá"); assertThat(chatCalls()).isEmpty()

        // pricing catalog: explicit, validated, applied from now on
        assertThat(a.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:gpt-test","inputUsdPerMTok":-1,"outputUsdPerMTok":1}""").response.status).isEqualTo(400)
        assertThat(a.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:unknown","inputUsdPerMTok":1,"outputUsdPerMTok":1}""").response.status).isEqualTo(404)
        assertThat(sc.s.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:gpt-test","inputUsdPerMTok":1,"outputUsdPerMTok":1}""").response.status).isEqualTo(403)
        val price = a.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:gpt-test","inputUsdPerMTok":2.5,"outputUsdPerMTok":10,"note":"bảng giá thử"}""")
        assertThat(price.response.status).isEqualTo(201)
        val r = sc.promptWith("openai:gpt-test"); assertThat(r.response.status).isEqualTo(200)
        val priced = sc.s.body(r)
        assertThat(priced.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(priced.get("provider").asString()).isEqualTo("openai"); assertThat(priced.get("model").asString()).isEqualTo("openai:gpt-test")
        assertThat(priced.get("usage").get("totalTokens").asLong()).isEqualTo(120)
        val call = chatCalls().single()
        assertThat(call.path).isEqualTo("/openai/chat/completions")
        assertThat(call.headers["Authorization"]).isEqualTo("Bearer sk-test-openai-123456")
        assertThat(call.body!!.get("model").asString()).isEqualTo("gpt-test")                                   // bare name to the provider
        assertThat(call.body.has("max_completion_tokens")).isTrue(); assertThat(call.body.has("temperature")).isFalse()
        assertThat(BigDecimal(priced.get("usage").get("costUsd").asString())).isEqualByComparingTo("0.00045")   // (100×2.5 + 20×10) / 1e6
        val pricedRow = jdbc.queryForMap("SELECT cost_usd, cost_source, pricing_id FROM ai_calls WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", sc.projectId)
        assertThat(pricedRow["cost_source"]).isEqualTo("CATALOG"); assertThat(pricedRow["pricing_id"].toString()).isEqualTo(a.body(price).get("id").asString())

        // a later price does not change recorded costs (rows are immutable; no edit endpoint exists)
        Thread.sleep(5)
        a.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:gpt-test","inputUsdPerMTok":100,"outputUsdPerMTok":100}""")
        assertThat((jdbc.queryForObject("SELECT cost_usd FROM ai_calls WHERE pricing_id = ?", BigDecimal::class.java, pricedRow["pricing_id"]))).isEqualByComparingTo("0.00045")
        assertThat(a.body(a.get("/api/v1/admin/ai/pricing")).toList().count { it.get("modelId").asString() == "openai:gpt-test" }).isEqualTo(2)
    }

    @Test
    fun `Anthropic - x-api-key and version headers, separate system prompt, input and cached tokens counted, a rejected key is fatal and nothing leaks`() {
        val sc = scenario(); val a = admin()
        enable(a, "anthropic:claude-test"); price(a, "anthropic:claude-test")
        val b = sc.s.body(sc.promptWith("anthropic:claude-test"))
        assertThat(b.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(b.get("provider").asString()).isEqualTo("anthropic")
        val call = chatCalls().single()
        assertThat(call.path).isEqualTo("/anthropic/v1/messages")
        assertThat(call.headers["x-api-key"]).isEqualTo("sk-ant-test-456789"); assertThat(call.headers["anthropic-version"]).isEqualTo("2023-06-01")
        assertThat(call.headers["Authorization"]).isNull()
        assertThat(call.body!!.get("system").asString()).contains("ADD_SECTION"); assertThat(call.body.get("messages").single().get("role").asString()).isEqualTo("user")
        assertThat(b.get("usage").get("promptTokens").asLong()).isEqualTo(110); assertThat(b.get("usage").get("completionTokens").asLong()).isEqualTo(20)

        replies["/anthropic/v1/messages"] = 401 to """{"type":"error","error":{"type":"authentication_error","message":"SECRET-ECHO"}}"""
        val fail = sc.s.body(sc.promptWith("anthropic:claude-test"))
        assertThat(fail.get("outcome").asString()).isEqualTo("UNSUPPORTED"); assertThat(fail.toString()).doesNotContain("SECRET-ECHO")
        val row = jdbc.queryForMap("SELECT outcome, http_status, total_tokens FROM ai_calls WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", sc.projectId)
        assertThat(row["outcome"]).isEqualTo("ERROR"); assertThat(row["http_status"]).isEqualTo(401); assertThat(row["total_tokens"]).isNull()
    }

    @Test
    fun `an explicit model is never replaced, auto never reaches a paid provider, a local model gets no key`() {
        val sc = scenario(); val a = admin()
        enable(a, "openai:gpt-test"); enable(a, "openai:gpt-other"); enable(a, "local:llama-test"); price(a, "openai:gpt-test")
        replies["/openai/chat/completions"] = 500 to "{}"
        val b = sc.s.body(sc.promptWith("openai:gpt-test"))
        assertThat(b.get("outcome").asString()).isEqualTo("UNSUPPORTED"); assertThat(chatCalls()).hasSize(1)            // no fail-over to gpt-other or others
        requests.clear()
        val auto = sc.s.body(sc.s.post("${sc.base}/prompts", """{"prompt":"bỏ phần đánh giá","expectedRevision":${sc.revision()},"model":"auto"}"""))
        assertThat(auto.get("provider").asString()).isEqualTo("mock"); assertThat(chatCalls()).isEmpty()
        val local = sc.s.body(sc.promptWith("local:llama-test"))
        assertThat(local.get("provider").asString()).isEqualTo("local")
        assertThat(chatCalls().single().path).isEqualTo("/local/chat/completions"); assertThat(chatCalls().single().headers["Authorization"]).isNull()
        assertThat(chatCalls().single().body!!.has("max_tokens")).isTrue()
        enable(a, "openai:gpt-test", false)
        assertThat(sc.promptWith("openai:gpt-test").response.status).isEqualTo(400)                                     // disabling takes effect at once
    }

    @Test
    fun `admin provider list shows configuration without secrets, the connection check is live`() {
        val a = admin()
        val r = a.get("/api/v1/admin/ai/providers"); assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.contentAsString).doesNotContain("sk-test-openai").doesNotContain("sk-ant-test")
        val list = a.body(r).toList().associateBy { it.get("id").asString() }
        assertThat(list.keys).containsExactly("openai", "anthropic", "local")
        assertThat(list["openai"]!!.get("configured").asBoolean()).isTrue(); assertThat(list["local"]!!.get("managedBySystem").asBoolean()).isTrue()
        assertThat(list["openai"]!!.get("models").toList().map { it.get("id").asString() to it.get("enabled").asBoolean() })
            .containsExactly("openai:gpt-test" to false, "openai:gpt-other" to false)
        assertThat(list["local"]!!.get("paid").asBoolean()).isFalse()
        assertThat(list["openai"]!!.get("endpointHost").asString()).startsWith("http://127.0.0.1:")

        val ok = a.body(a.post("/api/v1/admin/ai/providers/openai/probe"))
        assertThat(ok.get("ok").asBoolean()).isTrue(); assertThat(ok.get("detail").asString()).contains("2 mô hình")
        assertThat(requests.single { it.path == "/openai/models" }.headers["Authorization"]).isEqualTo("Bearer sk-test-openai-123456")
        replies["/anthropic/v1/models"] = 401 to "{}"
        assertThat(a.body(a.post("/api/v1/admin/ai/providers/anthropic/probe")).get("ok").asBoolean()).isFalse()
        assertThat(a.post("/api/v1/admin/ai/providers/gemini/probe").response.status).isEqualTo(409)
        assertThat(a.post("/api/v1/admin/ai/providers/nope/probe").response.status).isEqualTo(404)
        assertThat(scenario().s.post("/api/v1/admin/ai/providers/openai/probe").response.status).isEqualTo(403)
    }
}
