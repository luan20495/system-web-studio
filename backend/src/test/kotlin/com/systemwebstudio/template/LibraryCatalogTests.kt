package com.systemwebstudio.template

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.UUID

/** Stage F: template review workflow, catalog metadata, safe-render previews (stub render worker), usage counts for templates and blocks. */
class LibraryCatalogTests : IntegrationTestBase() {
    companion object {
        @Volatile var previewMode = 200
        /** a minimal valid PNG header + padding (the API checks the signature and size) */
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { 7 }
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/render") { ex -> ex.requestBody.readBytes(); val b = "<!doctype html><html><body><h1>ok</h1></body></html>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            createContext("/preview") { ex -> ex.requestBody.readBytes()
                if (previewMode != 200) { ex.sendResponseHeaders(previewMode, -1); ex.close() }
                else { ex.responseHeaders.add("Content-Type", "image/png"); ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } } }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${server.address.port}" } }
    }

    @BeforeEach fun reset() { previewMode = 200 }
    private fun admin() = sessionFor(fx.user("libadm", systemAdmin = true).username)
    private fun Scenario.saveTemplate(name: String) = s.body(s.post("$base/templates", """{"name":"$name","description":"mô tả"}""")).get("template").get("id").asString()

    @Test
    fun `template workflow - catalog, submit with checks and preview, reject needs reason, approve publishes, usage counted, history kept`() {
        val sc = scenario(); val a = admin(); val other = scenario()
        val name = "Mẫu ${UUID.randomUUID().toString().take(6)}"
        val id = sc.saveTemplate(name)
        val cat = sc.s.patch("/api/v1/templates/$id/catalog", """{"category":"landing","tags":["Khuyến mãi","sale"]}""")
        assertThat(cat.response.status).isEqualTo(200)
        assertThat(sc.s.body(cat).get("tags").toList().map { it.asString() }).containsExactly("khuyến-mãi", "sale")
        assertThat(sc.s.patch("/api/v1/templates/$id/catalog", """{"category":"nope"}""").response.status).isEqualTo(400)

        val sub = sc.s.body(sc.s.post("/api/v1/templates/$id/submit"))
        assertThat(sub.get("passed").asBoolean()).isTrue()
        assertThat(sub.get("checks").toList().map { it.get("check").asString() }).contains("registry", "no-files", "text-safety", "render", "name")
        val t = sub.get("template")
        assertThat(t.get("reviewStatus").asString()).isEqualTo("REVIEW"); assertThat(t.get("previewStatus").asString()).isEqualTo("READY")
        assertThat(t.get("canEdit").asBoolean()).isFalse()                                    // locked during review
        assertThat(sc.s.patch("/api/v1/templates/$id/catalog", """{"category":"event"}""").response.status).isEqualTo(403)
        val img = sc.s.get("/api/v1/templates/$id/preview")
        assertThat(img.response.status).isEqualTo(200); assertThat(img.response.contentType).isEqualTo("image/png")
        assertThat(img.response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(other.s.get("/api/v1/templates/$id/preview").response.status).isEqualTo(404)     // not visible yet
        assertThat(sc.s.post("/api/v1/admin/templates/$id/review", """{"decision":"APPROVE"}""").response.status).isEqualTo(403)

        assertThat(a.post("/api/v1/admin/templates/$id/review", """{"decision":"REJECT"}""").response.status).isEqualTo(400)
        val rejected = a.body(a.post("/api/v1/admin/templates/$id/review", """{"decision":"REJECT","comment":"Thêm phần liên hệ"}"""))
        assertThat(rejected.get("reviewStatus").asString()).isEqualTo("PRIVATE"); assertThat(rejected.get("reviewComment").asString()).isEqualTo("Thêm phần liên hệ")
        assertThat(sc.s.body(sc.s.post("/api/v1/templates/$id/submit")).get("passed").asBoolean()).isTrue()
        val approved = a.body(a.post("/api/v1/admin/templates/$id/review", """{"decision":"APPROVE","comment":"OK"}"""))
        assertThat(approved.get("reviewStatus").asString()).isEqualTo("APPROVED"); assertThat(approved.get("visibility").asString()).isEqualTo("COMPANY")

        val listed = other.s.body(other.s.get("/api/v1/templates?scope=company&category=landing&tag=sale")).toList().map { it.get("id").asString() }
        assertThat(listed).contains(id)
        assertThat(other.s.body(other.s.get("/api/v1/templates?scope=company&category=event")).toList().map { it.get("id").asString() }).doesNotContain(id)
        assertThat(other.s.get("/api/v1/templates/$id/preview").response.status).isEqualTo(200)
        assertThat(other.s.post(api(other.ws), """{"name":"Từ mẫu","templateId":"$id"}""").response.status).isEqualTo(201)
        assertThat(other.s.post(api(other.ws), """{"name":"Từ mẫu 2","templateId":"$id"}""").response.status).isEqualTo(201)
        assertThat(other.s.body(other.s.get("/api/v1/templates/$id")).get("usageCount").asInt()).isEqualTo(2)
        assertThat(other.s.body(other.s.get("/api/v1/templates?scope=company&sort=popular")).toList().first().get("usageCount").asInt()).isGreaterThanOrEqualTo(2)

        val history = sc.s.body(sc.s.get("/api/v1/templates/$id/reviews")).toList().map { it.get("decision").asString() }
        assertThat(history).containsExactly("SUBMITTED", "CHECKS_PASSED", "REJECTED", "SUBMITTED", "CHECKS_PASSED", "APPROVED")
        assertThat(other.s.get("/api/v1/templates/$id/reviews").response.status).isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN ('SUBMIT_TEMPLATE','APPROVE_TEMPLATE','REJECT_TEMPLATE')", Long::class.java, id)).isEqualTo(4)
    }

    @Test
    fun `template checks fail on unsafe text and keep it private, without a browser the preview is UNAVAILABLE, not faked`() {
        val sc = scenario()
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Bấm vào onclick=alert(1)"}]}""")
        val bad = sc.saveTemplate("Mẫu xấu ${UUID.randomUUID().toString().take(5)}")
        val r = sc.s.body(sc.s.post("/api/v1/templates/$bad/submit"))
        assertThat(r.get("passed").asBoolean()).isFalse()
        assertThat(r.get("checks").toList().first { it.get("check").asString() == "text-safety" }.get("ok").asBoolean()).isFalse()
        assertThat(r.get("template").get("reviewStatus").asString()).isEqualTo("PRIVATE")

        previewMode = 501
        val sc2 = scenario()
        val ok = sc2.saveTemplate("Mẫu tốt ${UUID.randomUUID().toString().take(5)}")
        val t = sc2.s.body(sc2.s.post("/api/v1/templates/$ok/submit")).get("template")
        assertThat(t.get("reviewStatus").asString()).isEqualTo("REVIEW"); assertThat(t.get("previewStatus").asString()).isEqualTo("UNAVAILABLE")
        assertThat(sc2.s.get("/api/v1/templates/$ok/preview").response.status).isEqualTo(404)
        // an admin can regenerate once a browser is available
        previewMode = 200
        val a = admin()
        assertThat(a.body(a.post("/api/v1/admin/templates/$ok/preview")).get("previewStatus").asString()).isEqualTo("READY")
    }

    @Test
    fun `blocks - catalog metadata, preview on submit, usage counted only for a real insert`() {
        val sc = scenario(); val a = admin()
        val block = sc.s.body(sc.s.post("${sc.base}/component-packages", """{"sectionId":"hero-1","name":"Banner ${UUID.randomUUID().toString().take(6)}"}""")).get("block")
        val id = block.get("id").asString(); val base = block.get("baseComponent").asString()
        assertThat(sc.s.patch("/api/v1/component-packages/$id/catalog", """{"category":"hero","tags":["banner"]}""").response.status).isEqualTo(200)
        assertThat(sc.s.patch("/api/v1/component-packages/$id/catalog", """{"category":"footerx"}""").response.status).isEqualTo(400)
        val sub = sc.s.body(sc.s.post("/api/v1/component-packages/$id/submit"))
        assertThat(sub.get("passed").asBoolean()).isTrue(); assertThat(sub.get("block").get("previewStatus").asString()).isEqualTo("READY")
        assertThat(sc.s.get("/api/v1/component-packages/$id/preview").response.status).isEqualTo(200)
        a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"APPROVE"}""")

        val user = scenario()
        val props = user.s.body(user.s.get("/api/v1/component-packages/$id")).get("current").get("props")
        fun insert(blockId: String?, type: String) = user.s.patch("${user.base}/schema", """{"expectedRevision":${user.revision()},"operations":[{"type":"ADD_SECTION","sectionType":"$type",
            "sectionId":"s-${UUID.randomUUID().toString().take(6)}","props":${json.writeValueAsString(props)}}]${if (blockId != null) ""","blockId":"$blockId"""" else ""}}""")
        assertThat(insert(id, base).response.status).isEqualTo(200)
        assertThat(insert(null, base).response.status).isEqualTo(200)                          // no blockId: not counted
        val after = user.s.body(user.s.get("/api/v1/component-packages/$id"))
        assertThat(after.get("usageCount").asInt()).isEqualTo(1)
        assertThat(after.get("category").asString()).isEqualTo("hero"); assertThat(after.get("tags").toList().map { it.asString() }).containsExactly("banner")
        assertThat(user.s.body(user.s.get("/api/v1/library/categories")).get("blocks").has("hero")).isTrue()
    }
}
