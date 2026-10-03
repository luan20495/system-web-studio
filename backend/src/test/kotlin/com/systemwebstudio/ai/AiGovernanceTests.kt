package com.systemwebstudio.ai

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage E (ADR 0014): model access rules, money budgets + alerts, SSE streaming with cancel/timeout, controlled tool calling and reuse tracking.
 * Providers are a local OpenAI-compatible stub (no network, no keys, no cost): "openai:gpt-test" (paid) and "local:llama-test" (free).
 */
class AiGovernanceTests : IntegrationTestBase() {
    companion object {
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        /** bare model -> queued answers (the last one repeats) */
        val answers = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
        val lastAnswer = ConcurrentHashMap<String, String>()
        val userMessages = CopyOnWriteArrayList<String>()
        @Volatile var chunkDelayMs = 0L
        @Volatile var chunks = 0

        const val OPS = """{"message":"xong","operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Tiêu đề mới"}]}"""

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            createContext("/") { ex ->
                val body = mapper.readTree(ex.requestBody.readBytes())
                if (!ex.requestURI.path.endsWith("/chat/completions")) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
                val model = body.get("model").asString()
                userMessages += body.get("messages")[1].get("content").asString()
                val q = answers[model]
                val content = q?.poll()?.also { lastAnswer[model] = it } ?: lastAnswer[model] ?: OPS
                val usage = mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120)
                if (body.get("stream")?.asBoolean() == true) {
                    ex.responseHeaders.add("Content-Type", "text/event-stream"); ex.sendResponseHeaders(200, 0)
                    try {
                        ex.responseBody.use { out ->
                            val pieces = content.chunked(maxOf(1, content.length / maxOf(1, if (chunks > 0) chunks else 6)))
                            for (p in pieces) {
                                out.write("data: ${mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to p)))))}\n\n".toByteArray()); out.flush()
                                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                            }
                            out.write("data: ${mapper.writeValueAsString(mapOf("choices" to emptyList<Any>(), "usage" to usage))}\n\ndata: [DONE]\n\n".toByteArray())
                        }
                    } catch (_: Exception) { /* client went away (cancel / timeout) */ }
                } else {
                    val bytes = mapper.writeValueAsBytes(mapOf("id" to "c1", "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))), "usage" to usage))
                    ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }

        @JvmStatic @DynamicPropertySource
        fun providers(registry: DynamicPropertyRegistry) {
            val base = "http://127.0.0.1:${server.address.port}"
            registry.add("app.ai.providers.openai.api-key") { "sk-test-openai-123456" }
            registry.add("app.ai.providers.openai.base-url") { "$base/openai" }
            registry.add("app.ai.providers.openai.models") { "gpt-test" }
            registry.add("app.ai.providers.local.base-url") { "$base/local" }
            registry.add("app.ai.providers.local.models") { "llama-test" }
        }
    }

    @BeforeEach fun reset() {
        answers.clear(); lastAnswer.clear(); userMessages.clear(); chunkDelayMs = 0; chunks = 0
        listOf("ai_calls", "ai_model_policies", "ai_model_access", "ai_budgets", "admin_alerts", "ai_model_pricing", "system_settings").forEach { jdbc.update("DELETE FROM $it") }
    }
    @AfterEach fun clean() { jdbc.update("DELETE FROM system_settings") }

    private fun admin() = sessionFor(fx.user("aigov", systemAdmin = true).username)
    private fun enable(a: ApiSession, vararg models: String) = models.forEach {
        assertThat(a.put("/api/v1/admin/ai/models/policy", """{"modelId":"$it","enabled":true}""").response.status).isEqualTo(200)
    }
    private fun Scenario.ask(model: String, text: String = "đổi tiêu đề") =
        s.post("$base/prompts", """{"prompt":${json.writeValueAsString(text)},"expectedRevision":${revision()},"model":"$model"}""")
    private fun script(model: String, vararg contents: String) { answers[model] = ConcurrentLinkedQueue(contents.toList()) }

    @Test
    fun `model access - deny rules at user, workspace and role level restrict models (most restrictive wins), audited, admin only`() {
        val a = admin(); enable(a, "openai:gpt-test", "local:llama-test")
        val sc = scenario()
        assertThat(sc.ask("openai:gpt-test").response.status).isEqualTo(200)

        // non-admins cannot manage rules
        assertThat(sc.s.post("/api/v1/admin/ai/access", """{"scopeType":"ORG","modelId":"*"}""").response.status).isEqualTo(403)

        // user-level deny of one model
        val rule = a.body(a.post("/api/v1/admin/ai/access", """{"scopeType":"USER","scopeId":"${sc.user.id}","modelId":"openai:gpt-test"}""")).get("id").asString()
        val denied = sc.ask("openai:gpt-test")
        assertThat(denied.response.status).isEqualTo(403); assertThat(sc.s.body(denied).get("code").asString()).isEqualTo("MODEL_ACCESS_DENIED")
        assertThat(sc.ask("local:llama-test").response.status).isEqualTo(200)
        assertThat(a.post("/api/v1/admin/ai/access", """{"scopeType":"USER","scopeId":"${sc.user.id}","modelId":"openai:gpt-test"}""").response.status).isEqualTo(409)
        // the picker hides it in this workspace
        val status = sc.s.body(sc.s.get("/api/v1/ai/status?workspaceId=${sc.ws}")).get("models").toList().map { it.get("id").asString() }
        assertThat(status).contains("local:llama-test").doesNotContain("openai:gpt-test")
        assertThat(a.delete("/api/v1/admin/ai/access/$rule").response.status).isEqualTo(204)
        assertThat(sc.ask("openai:gpt-test").response.status).isEqualTo(200)

        // workspace-level deny of every paid model: the free local model still works
        a.post("/api/v1/admin/ai/access", """{"scopeType":"WORKSPACE","scopeId":"${sc.ws}","modelId":"paid:*"}""")
        assertThat(sc.ask("openai:gpt-test").response.status).isEqualTo(403)
        assertThat(sc.ask("local:llama-test").response.status).isEqualTo(200)
        val eff = a.body(a.get("/api/v1/admin/ai/access/effective?userId=${sc.user.id}&workspaceId=${sc.ws}")).toList().associateBy { it.get("id").asString() }
        assertThat(eff["openai:gpt-test"]!!.get("allowed").asBoolean()).isFalse()
        assertThat(eff["openai:gpt-test"]!!.get("reason").asString()).contains("workspace")
        assertThat(eff["local:llama-test"]!!.get("allowed").asBoolean()).isTrue()
        jdbc.update("DELETE FROM ai_model_access")

        // role-level deny (the scenario user is an EDITOR of the workspace)
        a.post("/api/v1/admin/ai/access", """{"scopeType":"ROLE","scopeId":"${sc.ws}:EDITOR","modelId":"*"}""")
        assertThat(sc.ask("local:llama-test").response.status).isEqualTo(403)
        assertThat(a.post("/api/v1/admin/ai/access", """{"scopeType":"ROLE","scopeId":"${sc.ws}:BOSS","modelId":"*"}""").response.status).isEqualTo(400)
        // the simulator is never restricted
        assertThat(sc.ask("mock").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action LIKE 'AI_MODEL_ACCESS_DENY_%'", Long::class.java)).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `money budgets - unknown price refused, soft and hard thresholds raise alerts, hard budget blocks paid models only`() {
        val a = admin(); enable(a, "openai:gpt-test", "local:llama-test")
        val sc = scenario()
        val budget = """{"scopeType":"USER","scopeId":"${sc.user.id}","period":"DAILY","amount":0.002,"currency":"USD","softPercent":50,"hard":true}"""
        assertThat(a.put("/api/v1/admin/ai/budgets", budget).response.status).isEqualTo(200)
        // no price row: the cost of a call could not be counted, so a paid model is refused while a hard budget applies
        val unknown = sc.ask("openai:gpt-test")
        assertThat(unknown.response.status).isEqualTo(409); assertThat(sc.s.body(unknown).get("code").asString()).isEqualTo("AI_COST_UNKNOWN")

        // 10 USD / Mtok in, 30 USD / Mtok out → 100 in + 20 out = 0.0016 USD per call
        a.post("/api/v1/admin/ai/pricing", """{"modelId":"openai:gpt-test","inputUsdPerMTok":10,"outputUsdPerMTok":30}""")
        assertThat(sc.ask("openai:gpt-test").response.status).isEqualTo(200)
        val b1 = a.body(a.get("/api/v1/admin/ai/budgets")).first()
        assertThat(BigDecimal(b1.get("spentUsd").asString())).isEqualByComparingTo("0.0016"); assertThat(b1.get("percent").asInt()).isEqualTo(80)
        val alerts1 = a.body(a.get("/api/v1/admin/alerts")).get("items").toList().map { it.get("kind").asString() }
        assertThat(alerts1).containsExactly("AI_BUDGET_SOFT")

        assertThat(sc.ask("openai:gpt-test").response.status).isEqualTo(200)          // checked before: 0.0016 < 0.002, may overshoot
        val blocked = sc.ask("openai:gpt-test")
        assertThat(blocked.response.status).isEqualTo(429); assertThat(sc.s.body(blocked).get("code").asString()).isEqualTo("AI_BUDGET_EXCEEDED")
        assertThat(sc.ask("local:llama-test").response.status).isEqualTo(200)          // free model: no money spent
        val open = a.body(a.get("/api/v1/admin/alerts"))
        assertThat(open.get("items").toList().map { it.get("kind").asString() }).contains("AI_BUDGET_SOFT", "AI_BUDGET_EXCEEDED")
        assertThat(open.get("items").toList().count { it.get("kind").asString() == "AI_BUDGET_EXCEEDED" }).isEqualTo(1)   // deduplicated

        val id = open.get("items")[0].get("id").asString()
        assertThat(sc.s.post("/api/v1/admin/alerts/$id/acknowledge").response.status).isEqualTo(403)
        assertThat(a.post("/api/v1/admin/alerts/$id/acknowledge").response.status).isEqualTo(200)
        assertThat(a.body(a.get("/api/v1/admin/alerts")).get("open").asLong()).isEqualTo(open.get("open").asLong() - 1)

        // a non-USD budget needs an explicit rate; none is assumed
        val vnd = a.put("/api/v1/admin/ai/budgets", """{"scopeType":"ORG","period":"MONTHLY","amount":500000,"currency":"VND"}""")
        assertThat(vnd.response.status).isEqualTo(400); assertThat(a.body(vnd).get("code").asString()).isEqualTo("EXCHANGE_RATE_REQUIRED")
        val withRate = a.body(a.put("/api/v1/admin/ai/budgets", """{"scopeType":"ORG","period":"MONTHLY","amount":500000,"currency":"VND","usdPerUnit":0.00004,"hard":false}"""))
        val org = withRate.toList().first { it.get("scopeType").asString() == "ORG" }
        assertThat(BigDecimal(org.get("spentUsd").asString())).isEqualByComparingTo("0.0032")
        assertThat(BigDecimal(org.get("spent").asString())).isEqualByComparingTo("80")          // 0.0032 USD / 0.00004 USD per VND
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'AI_BUDGET_SET'", Long::class.java)).isGreaterThanOrEqualTo(2)
    }

    private fun stream(sc: Scenario, model: String, text: String = "đổi tiêu đề"): MvcResult = sc.s.perform(
        MockMvcRequestBuilders.post("${sc.base}/prompts/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content("""{"prompt":${json.writeValueAsString(text)},"expectedRevision":${sc.revision()},"model":"$model"}"""))

    /** parsed SSE events once the stream has finished */
    private fun events(r: MvcResult, timeout: Duration = Duration.ofSeconds(30)): List<Pair<String, tools.jackson.databind.JsonNode>> {
        await().atMost(timeout).until { r.response.getContentAsString(Charsets.UTF_8).let { "event:result" in it || "event:error" in it } }
        return r.response.getContentAsString(Charsets.UTF_8).split("\n\n").mapNotNull { block ->
            val ev = block.lines().firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim() ?: return@mapNotNull null
            val data = block.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:") }
            ev to json.readTree(data)
        }
    }

    @Test
    fun `SSE streaming - partial output arrives as deltas, final result carries provider-reported usage`() {
        val a = admin(); enable(a, "local:llama-test")
        val sc = scenario(); chunks = 8
        val r = stream(sc, "local:llama-test")
        assertThat(r.request.isAsyncStarted).isTrue()
        val ev = events(r)
        assertThat(ev.first().first).isEqualTo("start")
        val deltas = ev.filter { it.first == "delta" }.joinToString("") { it.second.get("text").asString() }
        assertThat(ev.count { it.first == "delta" }).isGreaterThan(3); assertThat(deltas).isEqualTo(OPS)
        val result = ev.last().second
        assertThat(ev.last().first).isEqualTo("result"); assertThat(result.get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(result.get("usage").get("totalTokens").asLong()).isEqualTo(120)
        assertThat(sc.section("Hero")!!.get("props").get("title").asString()).isEqualTo("Tiêu đề mới")
        // refusals happen before the stream starts, as plain HTTP errors
        val bad = stream(sc, "openai:gpt-test")
        assertThat(bad.response.status).isEqualTo(400)
    }

    @Test
    fun `SSE streaming - cancel keeps the partial output and records the call as CANCELLED, nothing is saved`() {
        val a = admin(); enable(a, "local:llama-test")
        val sc = scenario(); chunks = 40; chunkDelayMs = 150
        val before = sc.revision()
        val r = stream(sc, "local:llama-test")
        await().atMost(Duration.ofSeconds(10)).until { "event:delta" in r.response.getContentAsString(Charsets.UTF_8) }
        val id = Regex("\"streamId\":\"([0-9a-f-]{36})\"").find(r.response.getContentAsString(Charsets.UTF_8))!!.groupValues[1]
        // only the owner can cancel
        assertThat(sessionFor(fx.user("other").username).post("/api/v1/ai/streams/$id/cancel").response.status).isEqualTo(404)
        assertThat(sc.s.post("/api/v1/ai/streams/$id/cancel").response.status).isEqualTo(200)
        val result = events(r).last().second
        assertThat(result.get("outcome").asString()).isEqualTo("CANCELLED"); assertThat(result.get("stopped").asString()).isEqualTo("CANCELLED")
        assertThat(result.get("partial").asString()).isNotEmpty(); assertThat(OPS).startsWith(result.get("partial").asString())
        assertThat(sc.revision()).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT outcome FROM ai_calls WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", String::class.java, sc.projectId)).isEqualTo("CANCELLED")
        assertThat(jdbc.queryForObject("SELECT status FROM prompt_runs WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", String::class.java, sc.projectId)).isEqualTo("CANCELLED")
    }

    @Test
    fun `SSE streaming - the deadline stops a slow model with TIMEOUT`() {
        val a = admin(); enable(a, "local:llama-test")
        assertThat(a.put("/api/v1/admin/settings/policies/ai.stream-timeout-seconds", """{"value":"10"}""").response.status).isEqualTo(200)
        val sc = scenario(); chunks = 30; chunkDelayMs = 1000
        val result = events(stream(sc, "local:llama-test"), Duration.ofSeconds(40)).last().second
        assertThat(result.get("outcome").asString()).isEqualTo("TIMEOUT"); assertThat(result.get("partial").asString()).isNotEmpty()
        assertThat(jdbc.queryForObject("SELECT outcome FROM ai_calls WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", String::class.java, sc.projectId)).isEqualTo("TIMEOUT")
    }

    @Test
    fun `tool calling - server runs allowed tools with the user's permissions, refuses unknown ones, audits all, verifies reuse claims`() {
        val a = admin(); enable(a, "local:llama-test")
        val sc = scenario()
        // an approved company block (preset of the Testimonials component)
        val block = UUID.randomUUID(); val owner = fx.user("blockowner")
        val ver = jdbc.queryForObject("SELECT latest_version FROM components WHERE id = 'Testimonials'", String::class.java)
        jdbc.update("INSERT INTO component_packages (id, name, base_component, owner_id, status, approved_version) VALUES (?, 'Đánh giá chuẩn', 'Testimonials', ?, 'APPROVED', 1)", block, owner.id)
        jdbc.update("""INSERT INTO component_package_versions (package_id, version, base_component, base_component_version, props, status, created_by, decided_at)
            VALUES (?, 1, 'Testimonials', ?, '{"heading":"Khách hàng nói gì về chúng tôi","visible":true,"items":[]}'::jsonb, 'APPROVED', ?, now())""", block, ver, owner.id)
        val final = """{"message":"Đã thêm đánh giá","sources":{"blocks":["$block"],"templates":["00000000-0000-0000-0000-000000000001"]},"operations":[
            {"type":"ADD_SECTION","sectionType":"Testimonials","sectionId":"testimonials-9","props":{"heading":"Khách hàng nói gì về chúng tôi","visible":true,"items":[]}}]}"""
        script("llama-test", """{"tool":"search_component","arguments":{"query":"testimonials"}}""", """{"tool":"read_project_schema","arguments":{}}""",
            """{"tool":"drop_tables","arguments":{}}""", final)
        val r = sc.ask("local:llama-test", "thêm phần đánh giá khách hàng")
        assertThat(r.response.status).isEqualTo(200)
        val body = sc.s.body(r)
        assertThat(body.get("outcome").asString()).isEqualTo("UPDATED")
        val tools = jdbc.queryForList("SELECT tool, outcome FROM ai_tool_calls WHERE project_id = ? ORDER BY created_at", sc.projectId).map { "${it["tool"]}:${it["outcome"]}" }
        assertThat(tools).containsExactly("search_component:OK", "read_project_schema:OK", "drop_tables:DENIED")
        assertThat(userMessages[1]).contains("<tool_result name=\"search_component\">").contains(block.toString())
        assertThat(userMessages[2]).contains("<tool_result name=\"read_project_schema\">").contains("hero-1")
        assertThat(userMessages[3]).contains("ERROR: tool 'drop_tables' is not available here")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_calls WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(4)
        // reuse: the block is verified by its props; the template claim is dropped (never retrieved by a tool)
        val reuse = body.get("reuseSources")
        assertThat(reuse.get("blocks").toList().map { it.asString() }).containsExactly(block.toString())
        assertThat(reuse.get("templates").size()).isEqualTo(0); assertThat(reuse.get("generated").asInt()).isEqualTo(0)
        assertThat(jdbc.queryForObject("SELECT reuse_sources->'blocks'->>0 FROM prompt_runs WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", String::class.java, sc.projectId))
            .isEqualTo(block.toString())
    }
}
