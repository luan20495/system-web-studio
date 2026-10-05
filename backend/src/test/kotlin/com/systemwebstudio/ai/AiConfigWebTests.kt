package com.systemwebstudio.ai

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Core product: an administrator configures AI entirely on the web (provider, write-only key, models, defaults, limits, per-user override). */
class AiConfigWebTests : IntegrationTestBase() {
    companion object {
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        const val KEY = "sk-web-secret-ABCDEF123456"
        val auth = CopyOnWriteArrayList<String?>()
        const val OPS = """{"message":"xong","operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Tiêu đề mới"}]}"""
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { ex ->
                auth += ex.requestHeaders.getFirst("Authorization")
                val bad = ex.requestHeaders.getFirst("Authorization") == "Bearer wrong-key-123456"
                val b = (if (bad) "{}" else """{"data":[{"id":"gpt-small"},{"id":"gpt-big"}]}""").toByteArray()
                ex.sendResponseHeaders(if (bad) 401 else 200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes(); auth += ex.requestHeaders.getFirst("Authorization")
                val b = mapper.writeValueAsBytes(mapOf("id" to "c1", "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to OPS))),
                    "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120)))
                ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("app.secrets.master-key") { Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() }) }
            r.add("app.ai.providers.openai.api-key") { "sk-env-managed-123456" }
            r.add("app.ai.providers.openai.models") { "env-model" }
        }
    }
    private val base get() = "http://127.0.0.1:${server.address.port}/v1"

    @BeforeEach fun reset() {
        auth.clear()
        listOf("ai_calls", "ai_model_policies", "ai_model_pricing", "ai_limit_overrides", "ai_providers", "system_settings").forEach { jdbc.update("DELETE FROM $it") }
        settingsService.invalidate()
    }

    @org.junit.jupiter.api.AfterEach fun cleanup() {
        listOf("ai_calls", "ai_model_policies", "ai_model_pricing", "ai_limit_overrides", "ai_providers", "system_settings").forEach { jdbc.update("DELETE FROM $it") }
        settingsService.invalidate()
    }

    private fun admin() = sessionFor(fx.user("aicfg", systemAdmin = true).username)
    private fun addLocal(a: ApiSession, name: String = "Máy chủ thử", paid: Boolean = false): String {
        val r = a.post("/api/v1/admin/ai/providers", """{"name":"$name","kind":"LOCAL","baseUrl":"$base","apiKey":"$KEY","models":["gpt-small"],"paid":$paid}""")
        assertThat(r.response.status).isEqualTo(201)
        return a.body(r).get("id").asString()
    }

    @Test
    fun `a provider is added on the web, the key is encrypted and never returned, logged or audited, the connection test works without spending tokens`() {
        val a = admin(); val id = addLocal(a)
        val listed = a.get("/api/v1/admin/ai/providers").response.contentAsString
        assertThat(listed).doesNotContain(KEY).contains("\"keySet\":true")
        assertThat(jdbc.queryForObject("SELECT api_key_enc FROM ai_providers WHERE slug = ?", String::class.java, id)).startsWith("v1:").doesNotContain(KEY)
        assertThat(jdbc.queryForList("SELECT new_value::text FROM audit_events WHERE action = 'AI_PROVIDER_ADDED'").toString()).doesNotContain(KEY)

        val ok = a.body(a.post("/api/v1/admin/ai/providers/$id/probe"))
        assertThat(ok.get("ok").asBoolean()).isTrue(); assertThat(ok.get("detail").asString()).contains("Kết nối thành công")
        assertThat(auth).containsOnly("Bearer $KEY")
        assertThat(a.body(a.post("/api/v1/admin/ai/providers/$id/discover")).get("models").toList().map { it.asString() }).containsExactly("gpt-big", "gpt-small")

        // replacing the key: blank keeps the old one; a wrong key is reported in plain Vietnamese
        a.put("/api/v1/admin/ai/providers/$id", """{"name":"Đổi tên","apiKey":""}""")
        assertThat(a.body(a.post("/api/v1/admin/ai/providers/$id/probe")).get("ok").asBoolean()).isTrue()
        a.put("/api/v1/admin/ai/providers/$id", """{"apiKey":"wrong-key-123456"}""")
        val bad = a.body(a.post("/api/v1/admin/ai/providers/$id/probe"))
        assertThat(bad.get("ok").asBoolean()).isFalse(); assertThat(bad.get("detail").asString()).isEqualTo("Khóa kết nối bị từ chối. Hãy kiểm tra lại khóa.")
        assertThat(jdbc.queryForList("SELECT new_value::text FROM audit_events WHERE action = 'AI_PROVIDER_UPDATED'").toString()).doesNotContain("wrong-key").contains("keyChanged")

        // the environment-managed provider is shown as such and cannot be overwritten
        val env = a.body(a.get("/api/v1/admin/ai/providers")).toList().first { it.get("id").asString() == "openai" }
        assertThat(env.get("managedBySystem").asBoolean()).isTrue()
        assertThat(a.put("/api/v1/admin/ai/providers/openai", """{"apiKey":"sk-attempt-123456"}""").response.status).isEqualTo(409)
        assertThat(a.delete("/api/v1/admin/ai/providers/openai").response.status).isEqualTo(404)
        // validation and permissions
        assertThat(a.post("/api/v1/admin/ai/providers", """{"name":"x","kind":"OPENAI"}""").response.status).isEqualTo(400)                    // key required
        assertThat(a.post("/api/v1/admin/ai/providers", """{"name":"x","kind":"LOCAL","baseUrl":"ftp://x"}""").response.status).isEqualTo(400)
        assertThat(scenario().s.post("/api/v1/admin/ai/providers", """{"name":"x","kind":"LOCAL","baseUrl":"$base"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `models are enabled by the admin, only enabled models reach employees, the default model applies to requests without a choice`() {
        val a = admin(); val id = addLocal(a); val sc = scenario()
        assertThat(sc.s.body(sc.s.get("/api/v1/ai/status")).get("models")).isEmpty()                                  // nothing enabled yet
        assertThat(a.put("/api/v1/admin/ai/limits/defaults", """{"defaultModel":"$id:gpt-small"}""").response.status).isEqualTo(400)   // not enabled → cannot be the default
        a.put("/api/v1/admin/ai/models/policy", """{"modelId":"$id:gpt-small","enabled":true}""")
        assertThat(a.put("/api/v1/admin/ai/limits/defaults", """{"defaultModel":"$id:gpt-small"}""").response.status).isEqualTo(200)
        val status = sc.s.body(sc.s.get("/api/v1/ai/status"))
        assertThat(status.get("models").toList().map { it.get("id").asString() }).containsExactly("$id:gpt-small")
        assertThat(status.get("defaultModel").asString()).isEqualTo("$id:gpt-small")
        assertThat(sc.s.get("/api/v1/ai/status").response.contentAsString).doesNotContain(KEY).doesNotContain("127.0.0.1")   // employees never see addresses or keys
        // no model in the request → the company default is used
        val b = sc.s.body(sc.s.post("${sc.base}/prompts", """{"prompt":"đổi tiêu đề","expectedRevision":${sc.revision()}}"""))
        assertThat(b.get("provider").asString()).isEqualTo(id); assertThat(auth).contains("Bearer $KEY")
    }

    @Test
    fun `defaults - paid models have no budget, requests per day are enforced with a Vietnamese message, a per-user override changes the effective limits`() {
        val a = admin(); val paid = addLocal(a, "Trả phí", paid = true); val sc = scenario()
        a.put("/api/v1/admin/ai/models/policy", """{"modelId":"$paid:gpt-small","enabled":true}""")
        a.post("/api/v1/admin/ai/pricing", """{"modelId":"$paid:gpt-small","inputUsdPerMTok":1,"outputUsdPerMTok":1}""")
        fun ask(model: String? = null) = sc.s.post("${sc.base}/prompts", """{"prompt":"đổi tiêu đề","expectedRevision":${sc.revision()}${model?.let { ""","model":"$it"""" } ?: ""}}""")
        val defaults = a.body(a.get("/api/v1/admin/ai/limits")).get("defaults")
        assertThat(defaults.get("defaultModel").asString()).isEqualTo("auto"); assertThat(defaults.get("requestsPerUserDay").asInt()).isEqualTo(50)
        assertThat(defaults.get("paidBudgetPerUserMonth").asDouble()).isZero()
        val blocked = ask("$paid:gpt-small")
        assertThat(blocked.response.status).isEqualTo(402)
        assertThat(sc.s.body(blocked).get("message").asString()).isEqualTo("Mô hình trả phí này chưa được cấp ngân sách. Hãy chọn mô hình miễn phí hoặc nhờ quản trị viên cấp ngân sách AI trả phí.")
        assertThat(auth).isEmpty()                                                                                    // nothing was spent

        // the free model is limited to one request per day by default → the second is refused politely
        val free = addLocal(a, "Miễn phí", paid = false)
        a.put("/api/v1/admin/ai/models/policy", """{"modelId":"$free:gpt-small","enabled":true}""")
        a.put("/api/v1/admin/ai/limits/defaults", """{"requestsPerUserDay":1}""")
        assertThat(ask("$free:gpt-small").response.status).isEqualTo(200)
        val over = ask("$free:gpt-small")
        assertThat(over.response.status).isEqualTo(429)
        assertThat(sc.s.body(over).get("message").asString()).startsWith("Bạn đã dùng hết 1 lượt AI hôm nay")
        // a per-user override: five requests for this person; the user detail shows where each limit comes from
        assertThat(a.put("/api/v1/admin/ai/limits/overrides", """{"scopeType":"USER","scopeId":"${sc.user.id}","requestsPerDay":5}""").response.status).isEqualTo(200)
        assertThat(a.put("/api/v1/admin/ai/limits/overrides", """{"scopeType":"USER","scopeId":"${sc.user.id}","tokensPerMonth":5}""").response.status).isEqualTo(400)   // not a per-user field
        jdbc.update("DELETE FROM ai_calls")
        sc.s.get("/api/v1/me/usage")
        assertThat(ask("$free:gpt-small").response.status).isEqualTo(200)
        val v = a.body(a.get("/api/v1/admin/ai/limits/users/${sc.user.id}"))
        assertThat(v.get("limits").get("requestsPerDay").get("value").asInt()).isEqualTo(5); assertThat(v.get("limits").get("requestsPerDay").get("source").asString()).isEqualTo("USER")
        assertThat(v.get("limits").get("paidBudgetUserMonth").get("source").asString()).isEqualTo("DEFAULT")
        assertThat(v.get("allowedModels").toList().map { it.get("id").asString() }).contains("$free:gpt-small")
        assertThat(v.get("requestsToday").asInt()).isGreaterThanOrEqualTo(2)
        // removing the override returns to the default
        a.delete("/api/v1/admin/ai/limits/overrides/${v.get("override").get("id").asString()}")
        assertThat(a.body(a.get("/api/v1/admin/ai/limits/users/${sc.user.id}")).get("limits").get("requestsPerDay").get("source").asString()).isEqualTo("DEFAULT")
        // granting a paid budget lets the paid model work
        a.put("/api/v1/admin/ai/limits/defaults", """{"requestsPerUserDay":50,"paidBudgetPerUserMonth":5,"paidBudgetPerWorkspaceMonth":50}""")
        assertThat(ask("$paid:gpt-small").response.status).isEqualTo(200)
    }
}
