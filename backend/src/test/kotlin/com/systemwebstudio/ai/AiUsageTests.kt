package com.systemwebstudio.ai

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.util.UUID

/** AI usage accounting: every upstream call is recorded with the provider-reported usage; budgets; admin reporting. */
@TestPropertySource(properties = [
    "app.openrouter.daily-limit-per-user=50", "app.openrouter.max-attempts=3", "app.llm.provider=auto",
    "app.ai.daily-token-limit-per-user=300", "app.ai.monthly-token-limit-per-workspace=5000"
])
class AiUsageTests : IntegrationTestBase() {
    companion object {
        @JvmStatic @DynamicPropertySource
        fun openRouter(registry: DynamicPropertyRegistry) = OpenRouterStub.register(registry)
    }

    @org.springframework.beans.factory.annotation.Autowired lateinit var ai: com.systemwebstudio.integration.llm.AiService

    // the 5-minute "skip a model that just failed" memory is per JVM; clear it so each test sees the full fail-over order
    @BeforeEach fun reset() { OpenRouterStub.reset(); ai.freeModels().forEach { ai.markOk(it.id) } }

    private fun ok(op: String) = 200 to "{\"message\":\"xong\",\"operations\":[$op]}"
    private val setTitle = """{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Tiêu đề do AI"}"""
    private fun Scenario.promptWith(text: String, model: String? = null) =
        s.post("$base/prompts", """{"prompt":${json.writeValueAsString(text)},"expectedRevision":${revision()}${model?.let { ""","model":"$it"""" } ?: ""}}""")
    private fun dec(n: tools.jackson.databind.JsonNode) = BigDecimal(n.asString())
    private fun rows(projectId: UUID) = jdbc.queryForList("SELECT * FROM ai_calls WHERE project_id = ? ORDER BY created_at, model", projectId)

    @Test
    fun `every upstream call is accounted with provider-reported tokens and cost, failed fail-over attempts included`() {
        val sc = scenario()
        OpenRouterStub.script["openrouter/free"] = listOf(429 to "")
        OpenRouterStub.script["vendor/beta:free"] = listOf(200 to "I cannot do that, sorry")
        OpenRouterStub.usage["vendor/beta:free"] = mapOf("prompt_tokens" to 50, "completion_tokens" to 10, "total_tokens" to 60, "cost" to 0.0001)
        OpenRouterStub.script["vendor/gamma:free"] = listOf(ok(setTitle))
        OpenRouterStub.usage["vendor/gamma:free"] = mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120, "cost" to 0.0002)

        val r = sc.promptWith("đổi tiêu đề hero"); assertThat(r.response.status).isEqualTo(200)
        val b = sc.s.body(r)
        assertThat(OpenRouterStub.calls.first().body.get("usage").get("include").asBoolean()).isTrue()      // usage accounting requested
        val u = b.get("usage")
        assertThat(u.get("attempts").asInt()).isEqualTo(3)
        assertThat(u.get("promptTokens").asLong()).isEqualTo(150); assertThat(u.get("completionTokens").asLong()).isEqualTo(30)
        assertThat(u.get("totalTokens").asLong()).isEqualTo(180)
        assertThat(dec(u.get("costUsd"))).isEqualByComparingTo("0.0003")

        val rows = rows(sc.projectId)
        assertThat(rows.map { it["model"] to it["outcome"] }).containsExactlyInAnyOrder(
            "openrouter/free" to "ERROR", "vendor/beta:free" to "BAD_OUTPUT", "vendor/gamma:free" to "OK")
        val failed = rows.single { it["outcome"] == "ERROR" }
        assertThat(failed["http_status"]).isEqualTo(429); assertThat(failed["total_tokens"]).isNull(); assertThat(failed["cost_usd"]).isNull()   // unknown, not 0
        assertThat(rows.single { it["outcome"] == "OK" }["generation_id"]).isEqualTo("gen-vendor/gamma:free-1")
        assertThat(rows.map { it["user_id"] }.toSet()).containsExactly(sc.user.id)
        assertThat(rows.map { it["prompt_id"] }.toSet()).hasSize(1)

        val h = sc.s.body(sc.s.get("${sc.base}/prompts")).get(0)
        assertThat(h.get("aiCalls").asInt()).isEqualTo(3); assertThat(h.get("totalTokens").asLong()).isEqualTo(180); assertThat(h.get("model").asString()).isEqualTo("vendor/gamma:free")

        val me = sc.s.body(sc.s.get("/api/v1/me/usage"))
        assertThat(me.get("tokensLast24h").asLong()).isEqualTo(180); assertThat(me.get("tokensLimitPerDay").asLong()).isEqualTo(300)
        assertThat(me.get("usageLast30Days").get("calls").asLong()).isEqualTo(3)
        assertThat(me.get("usageLast30Days").get("callsWithoutUsage").asLong()).isEqualTo(1)
        assertThat(me.get("usageLast30Days").get("failedCalls").asLong()).isEqualTo(2)
    }

    @Test
    fun `a provider answer without usage is stored as unknown, never as zero`() {
        val sc = scenario()
        OpenRouterStub.script["openrouter/free"] = listOf(ok(setTitle))
        OpenRouterStub.usage["openrouter/free"] = emptyMap()
        val b = sc.s.body(sc.promptWith("đổi tiêu đề hero"))
        assertThat(b.get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(b.get("usage").get("attempts").asInt()).isEqualTo(1); assertThat(b.get("usage").get("totalTokens").isNull).isTrue()
        val row = rows(sc.projectId).single()
        assertThat(row["outcome"]).isEqualTo("OK"); assertThat(row["total_tokens"]).isNull(); assertThat(row["cost_usd"]).isNull()
    }

    @Test
    fun `the simulator makes no upstream call, records nothing and reports no usage`() {
        val sc = scenario()
        val b = sc.s.body(sc.promptWith("bỏ phần đánh giá", "mock"))
        assertThat(b.get("provider").asString()).isEqualTo("mock"); assertThat(b.get("usage").isNull).isTrue()
        assertThat(rows(sc.projectId)).isEmpty(); assertThat(OpenRouterStub.calls).isEmpty()
        assertThat(sc.s.body(sc.s.get("${sc.base}/prompts")).get(0).get("aiCalls").asInt()).isEqualTo(0)
    }

    @Test
    fun `spent tokens stay recorded when the edit is rolled back by a concurrent change during the model call`() {
        val sc = scenario()
        OpenRouterStub.script["openrouter/free"] = listOf(ok(setTitle))
        val before = sc.revision()
        // someone else saves while the model is "thinking": the model call holds no transaction, the commit's revision CAS catches it
        OpenRouterStub.duringCall = { jdbc.update("UPDATE projects SET revision = revision + 1 WHERE id = ?", sc.projectId) }
        val r = sc.s.post("${sc.base}/prompts", """{"prompt":"đổi tiêu đề hero","expectedRevision":$before}""")
        assertThat(r.response.status).isEqualTo(409); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("REVISION_CONFLICT")
        assertThat(sc.versionCount()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prompt_runs WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(0)
        val row = rows(sc.projectId).single()
        assertThat(row["total_tokens"]).isEqualTo(120); assertThat(row["outcome"]).isEqualTo("OK")
    }

    @Test
    fun `token budgets - per user per day and per workspace per month - block real AI but not the simulator`() {
        val sc = scenario()
        OpenRouterStub.script["openrouter/free"] = listOf(ok(setTitle))
        OpenRouterStub.usage["openrouter/free"] = mapOf("prompt_tokens" to 140, "completion_tokens" to 20, "total_tokens" to 160)
        assertThat(sc.promptWith("đổi tiêu đề hero").response.status).isEqualTo(200)            // 160 used
        assertThat(sc.promptWith("đổi tiêu đề hero").response.status).isEqualTo(200)            // 160 < 300 before the call -> 320 used
        val blocked = sc.promptWith("đổi tiêu đề hero")
        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(sc.s.body(blocked).get("code").asString()).isEqualTo("AI_TOKEN_LIMIT")
        assertThat(OpenRouterStub.calls).hasSize(2)                                              // checked before calling the provider
        assertThat(sc.promptWith("bỏ phần đánh giá", "mock").response.status).isEqualTo(200)

        // workspace budget: earlier usage by others in the same workspace reached the monthly budget; a member with no personal usage is blocked
        val other = fx.user("ws-member"); fx.member(sc.ws, other, "EDITOR"); fx.projectRole(fx.projects.findById(sc.projectId).get(), other, "EDITOR")
        jdbc.update("""INSERT INTO ai_calls (id, workspace_id, project_id, user_id, provider, model, outcome, total_tokens, latency_ms)
            VALUES (?,?,?,?,'openrouter','openrouter/free','OK',5000,10)""", UUID.randomUUID(), sc.ws, sc.projectId, fx.user("ws-heavy").id)
        val fresh = scenario()
        assertThat(fresh.promptWith("đổi tiêu đề hero").response.status).isEqualTo(200)          // a different workspace is unaffected
        val s2 = sessionFor(other.username)
        val rev = s2.body(s2.get(sc.base)).get("revision").asLong()
        val ws = s2.post("${sc.base}/prompts", """{"prompt":"đổi tiêu đề hero","expectedRevision":$rev}""")
        assertThat(ws.response.status).isEqualTo(429)
        assertThat(s2.body(ws).get("details").get("scope").asString()).isEqualTo("workspace")
    }

    @Test
    fun `admin usage report and call log are aggregated from ai_calls and admin-only`() {
        val sc = scenario()
        OpenRouterStub.script["openrouter/free"] = listOf(429 to "")
        OpenRouterStub.script["vendor/beta:free"] = listOf(ok(setTitle))
        sc.promptWith("đổi tiêu đề hero")

        assertThat(sc.s.get("/api/v1/admin/ai/usage").response.status).isEqualTo(403)
        assertThat(sc.s.get("/api/v1/admin/ai/calls").response.status).isEqualTo(403)
        val a = sessionFor(fx.user("aiadm", systemAdmin = true).username)
        val rep = a.body(a.get("/api/v1/admin/ai/usage?days=7"))
        assertThat(rep.get("days").asInt()).isEqualTo(7); assertThat(rep.get("daily").size()).isEqualTo(7)
        assertThat(rep.get("tokenSource").asString()).isEqualTo("PROVIDER_REPORTED")
        assertThat(rep.get("limits").get("dailyTokensPerUser").asLong()).isEqualTo(300)
        assertThat(rep.get("totals").get("totalTokens").asLong()).isGreaterThanOrEqualTo(120)
        val mine = rep.get("byUser").toList().single { it.get("key").asString() == sc.user.id.toString() }
        assertThat(mine.get("totals").get("calls").asLong()).isEqualTo(2); assertThat(mine.get("totals").get("totalTokens").asLong()).isEqualTo(120)
        assertThat(mine.get("totals").get("callsWithoutUsage").asLong()).isEqualTo(1)
        assertThat(rep.get("byModel").toList().map { it.get("key").asString() }).contains("vendor/beta:free", "openrouter/free")
        assertThat(rep.get("daily").last().get("calls").asLong()).isGreaterThanOrEqualTo(2)

        val log = a.body(a.get("/api/v1/admin/ai/calls?userId=${sc.user.id}&size=10"))
        assertThat(log.get("total").asLong()).isEqualTo(2)
        assertThat(log.get("items").toList().map { it.get("outcome").asString() }).containsExactlyInAnyOrder("OK", "ERROR")
        assertThat(log.get("items").get(0).get("project").asString()).isNotBlank()
        val errors = a.body(a.get("/api/v1/admin/ai/calls?userId=${sc.user.id}&outcome=error"))
        assertThat(errors.get("total").asLong()).isEqualTo(1); assertThat(errors.get("items").get(0).get("httpStatus").asInt()).isEqualTo(429)
        assertThat(a.body(a.get("/api/v1/admin/ai")).get("tokenAccounting").asString()).isEqualTo("PROVIDER_REPORTED")
    }
}
