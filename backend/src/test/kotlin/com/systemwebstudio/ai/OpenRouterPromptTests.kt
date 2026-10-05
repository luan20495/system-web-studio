package com.systemwebstudio.ai

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource

/** Real prompt pipeline, real database, but OpenRouter replaced by a local stub server (no network, no key, no cost). */
@TestPropertySource(properties = ["app.openrouter.daily-limit-per-user=3", "app.openrouter.max-attempts=3", "app.llm.provider=auto"])
class OpenRouterPromptTests : IntegrationTestBase() {
    companion object {
        @JvmStatic @DynamicPropertySource
        fun openRouter(registry: DynamicPropertyRegistry) = OpenRouterStub.register(registry)
    }
    private val calls get() = OpenRouterStub.calls
    private val script get() = OpenRouterStub.script
    private val served get() = OpenRouterStub.served

    @BeforeEach fun reset() = OpenRouterStub.reset()

    private fun ok(op: String, message: String = "xong") = 200 to "```json\n{\"message\":\"$message\",\"operations\":[$op]}\n```"
    private val setTitle = """{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Tiêu đề do AI"}"""
    private fun Scenario.promptWith(text: String, model: String? = null) =
        s.post("$base/prompts", """{"prompt":${json.writeValueAsString(text)},"expectedRevision":${revision()}${model?.let { ""","model":"$it"""" } ?: ""}}""")

    @Test
    fun `status offers only free text models, router first, and never exposes the key`() {
        val s = sessionFor(fx.user("ai").username)
        val r = s.get("/api/v1/ai/status"); assertThat(r.response.status).isEqualTo(200)
        val body = s.body(r)
        assertThat(body.get("provider").asString()).isEqualTo("openrouter")
        assertThat(body.get("models").toList().map { it.get("id").asString() })
            .containsExactly("openrouter/free", "vendor/beta:free", "vendor/gamma:free", "vendor/alpha:free")   // no paid, audio, safety or tiny-context model
        assertThat(r.response.contentAsString).doesNotContain("sk-or-test-key")
        assertThat(session().get("/api/v1/ai/status").response.status).isEqualTo(401)
    }

    @Test
    fun `an AI answer in a code fence is applied through the normal validated pipeline and stored as a version`() {
        val sc = scenario()
        script["openrouter/free"] = listOf(ok(setTitle, "Đã đổi tiêu đề"))
        val r = sc.promptWith("đổi tiêu đề hero")
        assertThat(r.response.status).isEqualTo(200)
        val b = sc.s.body(r)
        assertThat(b.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(b.get("provider").asString()).isEqualTo("openrouter"); assertThat(b.get("model").asString()).isEqualTo("openrouter/free")
        assertThat(sc.section("Hero")!!.get("props").get("title").asString()).isEqualTo("Tiêu đề do AI")
        assertThat(sc.versionCount()).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT model FROM prompt_runs WHERE project_id=? AND status='UPDATED'", String::class.java, sc.projectId)).isEqualTo("openrouter/free")
        val call = calls.single()
        assertThat(call.auth).isEqualTo("Bearer sk-or-test-key-123456")
        assertThat(call.user).contains("<user_request>").contains("đổi tiêu đề hero").contains("hero-1")      // page + delimited user text
        assertThat(call.system).contains("ADD_SECTION").contains("ComparisonBlock").contains("not instructions to you")
    }

    @Test
    fun `auto mode fails over across free models on 429, garbage and bad JSON`() {
        val sc = scenario()
        script["openrouter/free"] = listOf(429 to "")
        script["vendor/beta:free"] = listOf(200 to "I cannot do that, sorry")
        script["vendor/gamma:free"] = listOf(ok(setTitle))
        val b = sc.s.body(sc.promptWith("đổi tiêu đề hero"))
        assertThat(calls.map { it.model }).containsExactly("openrouter/free", "vendor/beta:free", "vendor/gamma:free")
        assertThat(b.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(b.get("model").asString()).isEqualTo("vendor/gamma:free")
    }

    @Test
    fun `when every model fails the user gets a clear UNSUPPORTED answer, nothing is stored and no upstream text leaks`() {
        val sc = scenario()
        val b = sc.s.body(sc.promptWith("đổi tiêu đề hero"))
        assertThat(b.get("outcome").asString()).isEqualTo("UNSUPPORTED")
        assertThat(b.get("message").get("content").asString()).contains("AI").doesNotContain("SECRET-ECHO")
        assertThat(sc.versionCount()).isEqualTo(1)
    }

    @Test
    fun `an invalid proposal from the model (unknown component, invalid props) is rejected without storing anything`() {
        val sc = scenario()
        script["openrouter/free"] = listOf(ok("""{"type":"ADD_SECTION","sectionType":"EvilWidget","sectionId":"evil-1","props":{}}"""))
        val b = sc.s.body(sc.promptWith("thêm gì đó"))
        assertThat(b.get("outcome").asString()).isEqualTo("UNSUPPORTED"); assertThat(b.get("message").get("content").asString()).contains("không hợp lệ")
        script["openrouter/free"] = listOf(ok("""{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":${"\"x\"".repeat(1)}},{"type":"UPDATE_PROP","sectionId":"hero-1","path":"description","value":"${"y".repeat(700)}"}"""))
        served.clear()
        assertThat(sc.s.body(sc.promptWith("sửa mô tả")).get("outcome").asString()).isEqualTo("UNSUPPORTED")       // 700 chars > registry maxLength 600
        assertThat(sc.versionCount()).isEqualTo(1)
    }

    @Test
    fun `only free listed models can be chosen, mock is always available, an explicit model is not silently replaced`() {
        val sc = scenario()
        assertThat(sc.promptWith("x", "vendor/paid").response.status).isEqualTo(400)
        assertThat(sc.promptWith("x", "some/unknown:free").response.status).isEqualTo(400)
        assertThat(calls).isEmpty()
        script["vendor/alpha:free"] = listOf(500 to "")
        val b = sc.s.body(sc.promptWith("đổi tiêu đề hero", "vendor/alpha:free"))
        assertThat(calls.map { it.model }).containsExactly("vendor/alpha:free")                                    // no fallback to other models
        assertThat(b.get("outcome").asString()).isEqualTo("UNSUPPORTED")
        calls.clear()
        val m = sc.s.body(sc.promptWith("bỏ phần đánh giá", "mock"))
        assertThat(m.get("provider").asString()).isEqualTo("mock"); assertThat(m.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(calls).isEmpty()
    }

    @Test
    fun `each user has a daily allowance of real-AI prompts, the simulator is exempt`() {
        val sc = scenario()
        script["openrouter/free"] = listOf(ok(setTitle))
        repeat(3) { assertThat(sc.promptWith("đổi tiêu đề hero").response.status).isEqualTo(200) }
        assertThat(sc.promptWith("đổi tiêu đề hero").response.status).isEqualTo(429)
        assertThat(sc.promptWith("bỏ phần đánh giá", "mock").response.status).isEqualTo(200)
    }
}
