package com.systemwebstudio.template

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Phase 5: template library and component contribution (blocks), with their authorization and review rules. */
class TemplateAndBlockTests : IntegrationTestBase() {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private fun upload(sc: Scenario): String {
        val t = sc.s.body(sc.s.post("${sc.base}/assets/upload-url", """{"fileName":"hero.png","contentType":"image/png","size":${png.size}}"""))
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(t.get("uploadUrl").asString())).header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.discarding())
        val id = t.get("assetId").asString()
        assertThat(sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""").response.status).isEqualTo(200)
        return id
    }
    private fun Scenario.setHero(path: String, value: String) =
        s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"$path","value":${json.writeValueAsString(value)}}]}""")
            .also { assertThat(it.response.status).isEqualTo(200) }
    private fun admin() = sessionFor(fx.user("gov", systemAdmin = true).username)
    private fun Scenario.saveTemplate(name: String, extra: String = "") = s.post("$base/templates", """{"name":"$name","description":"d"$extra}""")
    private fun Scenario.saveBlock(name: String, sectionId: String = "hero-1", extra: String = "") =
        s.post("$base/component-packages", """{"sectionId":"$sectionId","name":"$name"$extra}""")
    private fun ids(n: tools.jackson.databind.JsonNode) = n.toList().map { it.get("id").asString() }

    // ------------------------------------------------------------------ templates
    @Test
    fun `save a page as a private template - images cleared, author-only, and a new project starts from it`() {
        val sc = scenario()
        sc.setHero("image", "asset://${upload(sc)}"); sc.setHero("title", "Tiêu đề mẫu riêng")
        val r = sc.saveTemplate("Mẫu của tôi")
        assertThat(r.response.status).isEqualTo(201)
        val saved = sc.s.body(r); val id = saved.get("template").get("id").asString()
        assertThat(saved.get("removedImages").asInt()).isEqualTo(1)
        val t = saved.get("template")
        assertThat(t.get("visibility").asString()).isEqualTo("PRIVATE"); assertThat(t.get("canEdit").asBoolean()).isTrue()
        assertThat(t.get("schema").toString()).doesNotContain("asset://").contains("Tiêu đề mẫu riêng")
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/templates?scope=mine")))).contains(id)
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/templates?scope=company")))).doesNotContain(id)

        val other = scenario()                                                      // another user: private template is invisible
        assertThat(other.s.get("/api/v1/templates/$id").response.status).isEqualTo(404)
        assertThat(other.s.post(api(other.ws), """{"name":"X","templateId":"$id"}""").response.status).isEqualTo(404)

        val created = sc.s.post(api(sc.ws), """{"name":"Từ mẫu","templateId":"$id"}""")
        assertThat(created.response.status).isEqualTo(201)
        val pid = sc.s.body(created).get("id").asString()
        val page = sc.s.body(sc.s.get("${api(sc.ws, UUID.fromString(pid))}/schema")).get("schema")
        assertThat(page.toString()).contains("Tiêu đề mẫu riêng")
        assertThat(sc.s.body(sc.s.get("${api(sc.ws, UUID.fromString(pid))}/versions")).get(0).get("summary").asString()).contains("Mẫu của tôi")

        // new version from the same project; rename; archive
        sc.setHero("title", "Bản 2")
        val v2 = sc.s.body(sc.saveTemplate("Mẫu của tôi v2", ""","templateId":"$id"""")).get("template")
        assertThat(v2.get("version").asInt()).isEqualTo(2); assertThat(v2.get("schema").toString()).contains("Bản 2")
        assertThat(sc.s.patch("/api/v1/templates/$id", """{"name":"Đổi tên"}""").response.status).isEqualTo(200)
        assertThat(other.s.patch("/api/v1/templates/$id", """{"name":"hack"}""").response.status).isEqualTo(404)
        assertThat(sc.s.delete("/api/v1/templates/$id").response.status).isEqualTo(204)
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/templates?scope=mine")))).doesNotContain(id)
        assertThat(sc.auditCount("CREATE_TEMPLATE")).isEqualTo(1)
    }

    @Test
    fun `company templates - only a system admin shares them, then the author can no longer change them, everyone can use them`() {
        val sc = scenario()
        val id = sc.s.body(sc.saveTemplate("Mẫu công ty")).get("template").get("id").asString()
        // the author cannot make it company-wide (admin API), and an ordinary user gets 403
        assertThat(sc.s.post("/api/v1/admin/templates/$id/visibility", """{"visibility":"COMPANY"}""").response.status).isEqualTo(403)
        val a = admin()
        val shared = a.body(a.post("/api/v1/admin/templates/$id/visibility", """{"visibility":"COMPANY"}"""))
        assertThat(shared.get("visibility").asString()).isEqualTo("COMPANY")
        assertThat(sc.s.body(sc.s.get("/api/v1/templates/$id")).get("canEdit").asBoolean()).isFalse()
        assertThat(sc.s.patch("/api/v1/templates/$id", """{"name":"author edit"}""").response.status).isEqualTo(403)
        assertThat(sc.saveTemplate("overwrite", ""","templateId":"$id"""").response.status).isEqualTo(403)
        assertThat(sc.s.delete("/api/v1/templates/$id").response.status).isEqualTo(403)

        val other = scenario()
        assertThat(ids(other.s.body(other.s.get("/api/v1/templates?scope=company")))).contains(id)
        assertThat(other.s.post(api(other.ws), """{"name":"Dùng mẫu công ty","templateId":"$id"}""").response.status).isEqualTo(201)

        assertThat(a.post("/api/v1/admin/templates/$id/status", """{"status":"ARCHIVED"}""").response.status).isEqualTo(200)
        assertThat(ids(other.s.body(other.s.get("/api/v1/templates?scope=company")))).doesNotContain(id)
        assertThat(other.s.post(api(other.ws), """{"name":"X","templateId":"$id"}""").response.status).isEqualTo(404)
        val list = a.body(a.get("/api/v1/admin/templates?visibility=COMPANY&status=ARCHIVED&size=100"))
        assertThat(ids(list.get("items"))).contains(id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'TEMPLATE_VISIBILITY'", Long::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `templates - foreign project is 404, viewers cannot save, outdated templates create nothing`() {
        val sc = scenario(); val other = scenario()
        assertThat(other.s.post("${sc.base}/templates", """{"name":"steal"}""").response.status).isEqualTo(404)
        val viewer = fx.user("tviewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        assertThat(sessionFor(viewer.username).post("${sc.base}/templates", """{"name":"v"}""").response.status).isEqualTo(403)

        val bad = UUID.randomUUID()
        jdbc.update("""INSERT INTO templates (id, name, schema, visibility, author_id) VALUES (?, 'Cũ', CAST(? AS jsonb), 'COMPANY', ?)""", bad,
            """{"page":"old","sections":[{"id":"x-1","type":"RetiredWidget","componentVersion":"1.0.0","props":{}}]}""", sc.user.id)
        val before = jdbc.queryForObject("SELECT count(*) FROM projects WHERE workspace_id = ?", Long::class.java, sc.ws)
        val r = sc.s.post(api(sc.ws), """{"name":"Từ mẫu cũ","templateId":"$bad"}""")
        assertThat(r.response.status).isEqualTo(422); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("TEMPLATE_OUTDATED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE workspace_id = ?", Long::class.java, sc.ws)).isEqualTo(before)
    }

    // ------------------------------------------------------------------ blocks
    @Test
    fun `save a section as a private block - props come from the stored page, images cleared, owner-only`() {
        val sc = scenario()
        sc.setHero("image", "asset://${upload(sc)}"); sc.setHero("title", "Khối Hero của tôi")
        val r = sc.saveBlock("Hero khuyến mãi")
        assertThat(r.response.status).isEqualTo(201)
        val b = sc.s.body(r)
        assertThat(b.get("removedImages").asInt()).isEqualTo(1)
        val blk = b.get("block"); val id = blk.get("id").asString()
        assertThat(blk.get("baseComponent").asString()).isEqualTo("Hero"); assertThat(blk.get("status").asString()).isEqualTo("PRIVATE")
        assertThat(blk.get("current").get("props").get("title").asString()).isEqualTo("Khối Hero của tôi")
        assertThat(blk.get("current").get("props").get("image").asString()).isEmpty()
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/component-packages?scope=mine")))).contains(id)
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/component-packages?scope=company")))).doesNotContain(id)
        val other = scenario()
        assertThat(other.s.get("/api/v1/component-packages/$id").response.status).isEqualTo(404)
        assertThat(other.s.post("/api/v1/component-packages/$id/submit").response.status).isEqualTo(404)
        assertThat(other.s.post("${sc.base}/component-packages", """{"sectionId":"hero-1","name":"x"}""").response.status).isEqualTo(404)
        assertThat(sc.saveBlock("x", "no-such-section").response.status).isEqualTo(404)
        // a client-supplied props/html field is not part of the contract: only the stored section is used
        val sneaky = sc.saveBlock("sneaky", extra = ""","props":{"title":"<script>x</script>"},"html":"<script>alert(1)</script>"""")
        assertThat(sneaky.response.status).isIn(201, 400)                                                            // ignored or rejected, never used
        if (sneaky.response.status == 201) assertThat(sc.s.body(sneaky).get("block").get("current").get("props").get("title").asString()).isEqualTo("Khối Hero của tôi")
    }

    @Test
    fun `submit runs automated checks - unsafe text fails and stays private with a report`() {
        val sc = scenario()
        sc.setHero("title", "javascript:alert(1)")
        val id = sc.s.body(sc.saveBlock("Không an toàn")).get("block").get("id").asString()
        val r = sc.s.body(sc.s.post("/api/v1/component-packages/$id/submit"))
        assertThat(r.get("passed").asBoolean()).isFalse()
        assertThat(r.get("checks").toList().single { it.get("check").asString() == "text-safety" }.get("ok").asBoolean()).isFalse()
        assertThat(r.get("block").get("status").asString()).isEqualTo("PRIVATE")
        assertThat(r.get("block").get("reviews").toList().map { it.get("decision").asString() }).containsExactly("SUBMIT", "VALIDATION_FAILED")
        assertThat(r.get("block").get("current").get("validation")).isNotNull()
    }

    @Test
    fun `review - no self-approval even for admins, ordinary users cannot decide, reject needs a reason, approve publishes to everyone`() {
        val sc = scenario()
        sc.setHero("title", "Hero bán hàng")
        val id = sc.s.body(sc.saveBlock("Hero bán hàng ${UUID.randomUUID().toString().take(6)}")).get("block").get("id").asString()
        val sub = sc.s.body(sc.s.post("/api/v1/component-packages/$id/submit"))
        assertThat(sub.get("passed").asBoolean()).isTrue(); assertThat(sub.get("block").get("status").asString()).isEqualTo("REVIEW")
        assertThat(sc.saveBlock("change", extra = ""","packageId":"$id"""").response.status).isEqualTo(409)                      // in review: frozen

        assertThat(sc.s.post("/api/v1/admin/component-packages/$id/review", """{"decision":"APPROVE"}""").response.status).isEqualTo(403)
        val a = admin()
        val queue = a.body(a.get("/api/v1/admin/component-packages?status=REVIEW&size=100"))
        assertThat(ids(queue.get("page").get("items"))).contains(id)
        assertThat(a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"REJECT"}""").response.status).isEqualTo(400)   // reason required
        val rejected = a.body(a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"REJECT","comment":"Tiêu đề quá chung chung"}"""))
        assertThat(rejected.get("status").asString()).isEqualTo("PRIVATE")
        val mine = sc.s.body(sc.s.get("/api/v1/component-packages/$id"))
        assertThat(mine.get("reviews").toList().last().get("comment").asString()).isEqualTo("Tiêu đề quá chung chung")

        // fix, resubmit, approve
        sc.setHero("title", "Hero bán hàng mùa hè")
        assertThat(sc.saveBlock(mine.get("name").asString(), extra = ""","packageId":"$id"""").response.status).isEqualTo(201)
        sc.s.post("/api/v1/component-packages/$id/submit")
        val ok = a.body(a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"APPROVE","version":1}"""))
        assertThat(ok.get("status").asString()).isEqualTo("APPROVED"); assertThat(ok.get("approvedVersion").asInt()).isEqualTo(1)
        val other = scenario()
        val lib = other.s.body(other.s.get("/api/v1/component-packages?scope=company")).toList().single { it.get("id").asString() == id }
        assertThat(lib.get("current").get("props").get("title").asString()).isEqualTo("Hero bán hàng mùa hè")
        assertThat(lib.get("reviews").size()).isEqualTo(0)                                                          // no internal history for others

        // a new draft does not replace the live version until it is approved
        sc.setHero("title", "Hero v2")
        val v2 = sc.s.body(sc.saveBlock(mine.get("name").asString(), extra = ""","packageId":"$id"""")).get("block")
        assertThat(v2.get("latestVersion").asInt()).isEqualTo(2); assertThat(v2.get("approvedVersion").asInt()).isEqualTo(1)
        val stillV1 = other.s.body(other.s.get("/api/v1/component-packages?scope=company")).toList().single { it.get("id").asString() == id }
        assertThat(stillV1.get("current").get("props").get("title").asString()).isEqualTo("Hero bán hàng mùa hè")
        assertThat(sc.s.delete("/api/v1/component-packages/$id").response.status).isEqualTo(409)                   // was approved: deprecate instead

        // an admin's own block cannot be approved by that admin
        val adminUser = fx.user("selfadm", systemAdmin = true); fx.member(sc.ws, adminUser, "EDITOR")
        fx.projectRole(fx.projects.findById(sc.projectId).get(), adminUser, "EDITOR")
        val s2 = sessionFor(adminUser.username)
        val own = s2.body(s2.post("${sc.base}/component-packages", """{"sectionId":"hero-1","name":"Admin ${UUID.randomUUID().toString().take(6)}"}""")).get("block").get("id").asString()
        s2.post("/api/v1/component-packages/$own/submit")
        val self = s2.post("/api/v1/admin/component-packages/$own/review", """{"decision":"APPROVE"}""")
        assertThat(self.response.status).isEqualTo(403); assertThat(s2.body(self).get("code").asString()).isEqualTo("SELF_REVIEW")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN ('APPROVE_BLOCK','REJECT_BLOCK')", Long::class.java, id)).isEqualTo(2)
    }

    @Test
    fun `deprecate hides a block from the library without touching pages, restore brings it back, withdraw and delete rules`() {
        val sc = scenario()
        val id = sc.s.body(sc.saveBlock("Dep ${UUID.randomUUID().toString().take(6)}")).get("block").get("id").asString()
        sc.s.post("/api/v1/component-packages/$id/submit")
        val a = admin()
        a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"APPROVE"}""")
        val pageBefore = sc.schema().toString()
        assertThat(a.body(a.post("/api/v1/admin/component-packages/$id/deprecate", """{"comment":"thay bằng khối mới"}""")).get("status").asString()).isEqualTo("DEPRECATED")
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/component-packages?scope=company")))).doesNotContain(id)
        assertThat(sc.schema().toString()).isEqualTo(pageBefore)
        assertThat(sc.saveBlock("x", extra = ""","packageId":"$id"""").response.status).isEqualTo(409)
        assertThat(a.body(a.post("/api/v1/admin/component-packages/$id/restore")).get("status").asString()).isEqualTo("APPROVED")
        assertThat(ids(sc.s.body(sc.s.get("/api/v1/component-packages?scope=company")))).contains(id)

        val draft = sc.s.body(sc.saveBlock("Nháp ${UUID.randomUUID().toString().take(6)}")).get("block").get("id").asString()
        sc.s.post("/api/v1/component-packages/$draft/submit")
        assertThat(sc.s.delete("/api/v1/component-packages/$draft").response.status).isEqualTo(409)                 // in review
        assertThat(sc.s.body(sc.s.post("/api/v1/component-packages/$draft/withdraw")).get("status").asString()).isEqualTo("PRIVATE")
        assertThat(sc.s.delete("/api/v1/component-packages/$draft").response.status).isEqualTo(204)
        assertThat(sc.s.get("/api/v1/component-packages/$draft").response.status).isEqualTo(404)
    }

    @Test
    fun `a block keeps to its base component and approval re-validates against today's registry`() {
        val sc = scenario()
        val id = sc.s.body(sc.saveBlock("Base ${UUID.randomUUID().toString().take(6)}")).get("block").get("id").asString()
        val navbar = sc.schema().get("sections").first { it.get("type").asString() == "Navbar" }.get("id").asString()
        val mismatch = sc.saveBlock("x", navbar, ""","packageId":"$id"""")
        assertThat(mismatch.response.status).isEqualTo(422); assertThat(sc.s.body(mismatch).get("code").asString()).isEqualTo("BASE_MISMATCH")
        sc.s.post("/api/v1/component-packages/$id/submit")
        jdbc.update("UPDATE components SET status = 'DEPRECATED' WHERE id = 'Hero'")
        try {
            val a = admin()
            val r = a.post("/api/v1/admin/component-packages/$id/review", """{"decision":"APPROVE"}""")
            assertThat(r.response.status).isEqualTo(409); assertThat(a.body(r).get("code").asString()).isEqualTo("VALIDATION_FAILED")
            assertThat(sc.saveBlock("y").response.status).isEqualTo(422)                                               // COMPONENT_NOT_ELIGIBLE
        } finally { jdbc.update("UPDATE components SET status = 'ACTIVE' WHERE id = 'Hero'") }
    }
}
