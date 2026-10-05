package com.systemwebstudio.prompt

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PromptApiTests : IntegrationTestBase() {
    @Test
    fun `thêm bảng so sánh adds ComparisonBlock before testimonials with version audit and reuse metric`() {
        val sc = scenario()
        val r = sc.prompt("Thêm bảng so sánh sản phẩm trước phần đánh giá")
        assertThat(r.response.status).isEqualTo(200)
        val body = sc.s.body(r)
        assertThat(body.get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(body.get("schemaPatch").get(0).get("type").asString()).isEqualTo("ADD_SECTION")
        assertThat(body.get("registryReuse").asInt()).isEqualTo(100)
        assertThat(body.get("version").get("versionNumber").asInt()).isEqualTo(2)
        assertThat(body.get("message").get("role").asString()).isEqualTo("assistant")
        val types = sc.schema().get("sections").toList().map { it.get("type").asString() }
        assertThat(types.indexOf("ComparisonBlock")).isEqualTo(types.indexOf("Testimonials") - 1)
        assertThat(sc.versionCount()).isEqualTo(2)
        assertThat(sc.auditCount("RUN_PROMPT")).isEqualTo(1L)
        assertThat(sc.auditCount("CREATE_VERSION")).isEqualTo(1L)
        // asking again is a no-op: no new version
        assertThat(sc.s.body(sc.prompt("thêm bảng so sánh")).get("outcome").asString()).isEqualTo("NO_CHANGE")
        assertThat(sc.versionCount()).isEqualTo(2)
    }

    @Test
    fun `testimonial intents hide show remove and re-add`() {
        val sc = scenario()
        fun visible() = sc.section("Testimonials")?.get("props")?.get("visible")?.asBoolean()
        assertThat(sc.s.body(sc.prompt("bỏ đánh giá")).get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(visible()).isFalse()
        assertThat(sc.s.body(sc.prompt("hiện đánh giá")).get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(visible()).isTrue()
        assertThat(sc.s.body(sc.prompt("xóa đánh giá")).get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(sc.section("Testimonials")).isNull()
        assertThat(sc.s.body(sc.prompt("hiện đánh giá")).get("outcome").asString()).isEqualTo("UPDATED")
        assertThat(sc.section("Testimonials")).isNotNull()
        assertThat(sc.versionCount()).isEqualTo(5)
    }

    @Test
    fun `thêm sản phẩm and rút gọn hero patch the right props`() {
        val sc = scenario()
        assertThat(sc.s.body(sc.prompt("thêm sản phẩm mới")).get("schemaPatch").get(0).get("type").asString()).isEqualTo("ADD_ITEM")
        assertThat(sc.section("ProductGrid")!!.get("props").get("items").size()).isEqualTo(4)
        sc.prompt("rút gọn hero")
        assertThat(sc.section("Hero")!!.get("props").get("title").asString()).isEqualTo("Nước sạch. Sống khỏe.")
        assertThat(sc.s.body(sc.prompt("rút gọn hero")).get("outcome").asString()).isEqualTo("NO_CHANGE")
        assertThat(sc.versionCount()).isEqualTo(3)
        assertThat(sc.auditCount("RUN_PROMPT")).isEqualTo(3L)
    }

    @Test
    fun `unsupported prompt changes nothing`() {
        val sc = scenario()
        val rev = sc.revision()
        val r = sc.prompt("viết cho tôi một bài thơ")
        assertThat(sc.s.body(r).get("outcome").asString()).isEqualTo("UNSUPPORTED")
        assertThat(sc.versionCount()).isEqualTo(1)
        assertThat(sc.revision()).isEqualTo(rev)
        assertThat(sc.s.body(sc.s.get("${sc.base}/prompts")).size()).isEqualTo(1)
    }

    @Test
    fun `stale revision, viewer and blank prompt are rejected`() {
        val sc = scenario()
        assertThat(sc.prompt("bỏ đánh giá", 42).response.status).isEqualTo(409)
        assertThat(sc.prompt("   ").response.status).isEqualTo(400)
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER")
        fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        val v = sessionFor(viewer.username)
        assertThat(v.post("${sc.base}/prompts", """{"prompt":"bỏ đánh giá","expectedRevision":0}""").response.status).isEqualTo(403)
        assertThat(sc.versionCount()).isEqualTo(1)
    }

    @Test
    fun `prompt history survives and is project scoped`() {
        val sc = scenario()
        sc.prompt("bỏ đánh giá")
        val history = sc.s.body(sc.s.get("${sc.base}/prompts"))
        assertThat(history.size()).isEqualTo(1)
        assertThat(history.get(0).get("text").asString()).isEqualTo("bỏ đánh giá")
        assertThat(history.get(0).get("outcome").asString()).isEqualTo("UPDATED")
    }
}
