package com.systemwebstudio.publish

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Phase 7.1 (ADR 0009): real static sites — artifact build, serving, private access, rollback, unpublish. Render worker stubbed. */
@TestPropertySource(properties = [
    "app.deploy.provider=static", "app.sites.origin=https://sites.example.test", "app.sites.studio-origin=https://studio.example.test",
    "app.render.token=render-test-token"
])
class StaticSiteTests : IntegrationTestBase() {
    @org.springframework.test.context.bean.override.mockito.MockitoBean lateinit var dnsLookup: DnsLookup
    @org.springframework.test.context.bean.override.mockito.MockitoBean lateinit var tlsProbe: TlsProbe
    @org.springframework.beans.factory.annotation.Autowired lateinit var settingsSvc: com.systemwebstudio.settings.SettingsService
    @org.springframework.beans.factory.annotation.Autowired lateinit var domainService: SiteDomainService
    companion object {
        @Volatile var mode = "ok"
        @Volatile var lastToken: String? = null
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            // multi-page renderer (stage G): one HTML per page + 404.html, as JSON
            createContext("/render-site") { ex ->
                lastToken = ex.requestHeaders.getFirst("X-Render-Token")
                val body = mapper.readTree(ex.requestBody.readBytes())
                fun page(secs: tools.jackson.databind.JsonNode?): String {
                    val title = secs?.firstOrNull { it.get("type").asString() == "Hero" }?.get("props")?.get("title")?.asString() ?: ""
                    val imgs = body.get("assets").propertyNames().joinToString("") { "<img src=\"${body.get("assets").get(it).asString()}\">" }
                    val forms = secs?.filter { it.get("type").asString() == "ContactForm" }?.joinToString("") { "<form method=\"post\" action=\"./_forms/${it.get("id").asString()}\"></form>" } ?: ""
                    return when (mode) { "script" -> "<html><body><script>alert(1)</script></body></html>"; else -> "<!doctype html><html><body><h1>$title</h1>$imgs$forms</body></html>" }
                }
                val files = linkedMapOf("index.html" to page(body.get("schema").get("sections")))
                body.get("schema").get("pages")?.forEach { files["${it.get("slug").asString()}/index.html"] = page(it.get("sections")) }
                files["404.html"] = "<!doctype html><html><body><h1>404</h1><a href=\"__SITE_ROOT__\">home</a></body></html>"
                val bytes = mapper.writeValueAsBytes(mapOf("files" to files)); val status = if (mode == "down") 503 else 200
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            createContext("/render") { ex ->
                lastToken = ex.requestHeaders.getFirst("X-Render-Token")
                val body = mapper.readTree(ex.requestBody.readBytes())
                val title = body.get("schema").get("sections").firstOrNull { it.get("type").asString() == "Hero" }?.get("props")?.get("title")?.asString() ?: ""
                val imgs = body.get("assets").propertyNames().joinToString("") { "<img src=\"${body.get("assets").get(it).asString()}\">" }
                val html = when (mode) { "script" -> "<html><body><script>alert(1)</script></body></html>"; else -> "<!doctype html><html><body><h1>$title</h1>$imgs</body></html>" }
                val bytes = html.toByteArray(); val status = if (mode == "down") 503 else 200
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${server.address.port}" } }
    }

    @BeforeEach fun reset() { mode = "ok" }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7, 6)
    private fun upload(sc: Scenario): String {
        val t = sc.s.body(sc.s.post("${sc.base}/assets/upload-url", """{"fileName":"hero.png","contentType":"image/png","size":${png.size}}"""))
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(t.get("uploadUrl").asString())).header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.discarding())
        val id = t.get("assetId").asString()
        sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""")
        return id
    }
    private fun Scenario.setHero(path: String, value: String) =
        s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"$path","value":"$value"}]}""")
    private var key = 0
    private fun Scenario.publish(visibility: String = "PUBLIC", expect: String = "RUNNING"): tools.jackson.databind.JsonNode {
        val r = s.post("$base/publish", """{"visibility":"$visibility","expectedRevision":${revision()}}""", "Idempotency-Key" to "site-test-${System.nanoTime()}-${key++}")
        val id = s.body(r).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { s.body(s.get("$base/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }
        val d = s.body(s.get("$base/deployments/$id"))
        assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo(expect)
        return d
    }
    private fun slugOf(url: String) = url.removePrefix("https://sites.example.test/").trimEnd('/')
    private fun visitor() = session()

    @Test
    fun `publishing builds an immutable artifact and the site serves it with strict headers`() {
        val sc = scenario()
        val asset = upload(sc); sc.setHero("image", "asset://$asset"); sc.setHero("title", "Trang thật")
        val d = sc.publish()
        assertThat(d.get("provider").asString()).isEqualTo("static"); assertThat(d.get("mock").asBoolean()).isFalse()
        val url = d.get("url").asString(); assertThat(url).startsWith("https://sites.example.test/").endsWith("/")
        assertThat(lastToken).isEqualTo("render-test-token")
        val slug = slugOf(url)
        val v = visitor()
        val page = v.get("/sites/$slug/")
        assertThat(page.response.status).isEqualTo(200)
        assertThat(page.response.contentAsString).contains("<h1>Trang thật</h1>").contains("assets/$asset.png")
        assertThat(page.response.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("form-action 'self'").doesNotContain("script-src 'self'")
        assertThat(page.response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(page.response.getHeader("Cache-Control")).isEqualTo("public, no-cache, no-transform")          // always revalidated: visibility changes apply at once
        assertThat(page.response.getHeader("Set-Cookie")).isNull()                                   // no session for visitors
        val etag = page.response.getHeader("ETag")!!
        assertThat(v.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/$slug/").header("If-None-Match", etag)).response.status).isEqualTo(304)
        val img = v.get("/sites/$slug/assets/$asset.png")
        assertThat(img.response.status).isEqualTo(200); assertThat(img.response.contentAsByteArray).isEqualTo(png)
        assertThat(img.response.contentType).isEqualTo("image/png")
        assertThat(img.response.getHeader("Cache-Control")).isEqualTo("public, max-age=31536000, immutable, no-transform")   // CDN: asset ids never change content
        assertThat(v.get("/sites/$slug").response.getHeader("Location")).isEqualTo("/$slug/")
        val missing = v.get("/sites/$slug/missing.html")
        assertThat(missing.response.status).isEqualTo(404); assertThat(missing.response.contentAsString).contains("href=\"/$slug/\"")   // the site's own 404 page
        assertThat(v.get("/sites/$slug/..%2F..%2Fetc%2Fpasswd").response.status).isIn(400, 404)
        assertThat(v.get("/sites/no-such-site-x1/").response.status).isEqualTo(404)
        assertThat(v.post("/sites/$slug/", "{}").response.status).isIn(403, 405)                    // GET/HEAD only

        val artifact = jdbc.queryForMap("SELECT a.* FROM artifacts a JOIN deployments d ON d.artifact_id = a.id WHERE d.id = ?", java.util.UUID.fromString(d.get("id").asString()))
        assertThat(artifact["file_count"]).isEqualTo(3);                                              // index.html, 404.html, the image assertThat((artifact["sha256"] as String)).hasSize(64)
        // republishing the same content reuses the same artifact (content-addressed)
        val again = sc.publish()
        assertThat(jdbc.queryForObject("SELECT artifact_id FROM deployments WHERE id = ?", java.util.UUID::class.java, java.util.UUID.fromString(again.get("id").asString())))
            .isEqualTo(artifact["id"])
    }

    @Test
    fun `a private site needs a member's single-use ticket, then its own session, and access is checked on every request`() {
        val sc = scenario()
        val slug = slugOf(sc.publish("PRIVATE").get("url").asString())
        val v = visitor()
        val anon = v.get("/sites/$slug/")
        assertThat(anon.response.status).isEqualTo(302)
        assertThat(anon.response.getHeader("Location")).startsWith("https://studio.example.test/studio/site-access?site=$slug&path=")

        val outsider = scenario()
        assertThat(outsider.s.post("/api/v1/sites/$slug/access-ticket", """{"path":"/"}""").response.status).isEqualTo(404)
        assertThat(session().post("/api/v1/sites/$slug/access-ticket", "{}").response.status).isIn(401, 403)
        val redirect = sc.s.body(sc.s.post("/api/v1/sites/$slug/access-ticket", """{"path":"/"}""")).get("redirect").asString()
        assertThat(redirect).startsWith("https://sites.example.test/_access?ticket=")
        val ticket = redirect.substringAfter("ticket=")
        val redeem = v.get("/sites/_access?ticket=$ticket")
        assertThat(redeem.response.status).isEqualTo(302); assertThat(redeem.response.getHeader("Location")).isEqualTo("/$slug/")
        assertThat(redeem.response.getHeader("Set-Cookie")).startsWith("site_session=").contains("HttpOnly").contains("SameSite=Lax")
        assertThat(v.get("/sites/_access?ticket=$ticket").response.status).isEqualTo(400)              // single use
        val ok = v.get("/sites/$slug/")
        assertThat(ok.response.status).isEqualTo(200); assertThat(ok.response.getHeader("Cache-Control")).isEqualTo("private, no-store, no-transform")

        fx.disable(sc.user.id)                                                                           // access ends on the next request
        assertThat(v.get("/sites/$slug/").response.status).isEqualTo(403)
        val forged = ApiSession(mvc, json)
        assertThat(forged.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/$slug/")
            .cookie(jakarta.servlet.http.Cookie("site_session", "forged-session-value-1234567890"))).response.status).isEqualTo(302)
    }

    @Test
    fun `rollback serves an earlier artifact without a rebuild, unpublish takes the site offline, only publishers may do either`() {
        val sc = scenario()
        sc.setHero("title", "Phiên bản một")
        val first = sc.publish()
        val slug = slugOf(first.get("url").asString())
        sc.setHero("title", "Phiên bản hai")
        sc.publish()
        assertThat(visitor().get("/sites/$slug/").response.contentAsString).contains("Phiên bản hai")
        val site = sc.s.body(sc.s.get("${sc.base}/site"))
        assertThat(site.get("online").asBoolean()).isTrue(); assertThat(site.get("provider").asString()).isEqualTo("static")

        val viewer = fx.user("siteviewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        val vs = sessionFor(viewer.username)
        assertThat(vs.post("${sc.base}/site/rollback", """{"deploymentId":"${first.get("id").asString()}"}""").response.status).isEqualTo(403)
        assertThat(vs.delete("${sc.base}/site").response.status).isEqualTo(403)

        val builds = jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Long::class.java, sc.projectId)
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${first.get("id").asString()}"}""").response.status).isEqualTo(200)
        assertThat(visitor().get("/sites/$slug/").response.contentAsString).contains("Phiên bản một")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(builds)
        assertThat(sc.auditCount("SITE_ROLLBACK")).isEqualTo(1)

        mode = "down"
        val failed = sc.publish(expect = "FAILED")
        assertThat(failed.get("error").asString()).contains("Render worker")
        assertThat(visitor().get("/sites/$slug/").response.contentAsString).contains("Phiên bản một")   // a failed build never replaces the live site
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${failed.get("id").asString()}"}""").response.status).isEqualTo(400)

        assertThat(sc.s.delete("${sc.base}/site").response.status).isEqualTo(200)
        assertThat(visitor().get("/sites/$slug/").response.status).isEqualTo(404)
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isFalse()
    }

    private fun Scenario.ops(vararg ops: String) = s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[${ops.joinToString(",")}]}""")

    @Test
    fun `multi-page site - pages, navigation and SEO are validated, every page is in one atomic artifact, directory URLs and 404 work`() {
        val sc = scenario()
        assertThat(sc.ops("""{"type":"ADD_PAGE","pageId":"about","props":{"slug":"gioi-thieu","title":"Giới thiệu","seo":{"title":"Về chúng tôi","description":"Công ty"}}}""").response.status).isEqualTo(200)
        assertThat(sc.ops("""{"type":"ADD_SECTION","pageId":"about","sectionType":"Hero","sectionId":"hero-about","props":{"title":"Trang giới thiệu"}}""").response.status).isEqualTo(200)
        // section ids are unique across the whole site; slugs are checked
        assertThat(sc.ops("""{"type":"ADD_SECTION","pageId":"about","sectionType":"Hero","sectionId":"hero-1","props":{"title":"x"}}""").response.status).isEqualTo(400)
        assertThat(sc.ops("""{"type":"ADD_PAGE","pageId":"bad","props":{"slug":"Bad Slug","title":"x"}}""").response.status).isEqualTo(422)
        assertThat(sc.ops("""{"type":"ADD_PAGE","pageId":"res","props":{"slug":"assets","title":"x"}}""").response.status).isEqualTo(422)
        // navigation: page links and anchors are fine; javascript: and unapproved hosts are not
        assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"n1","label":"Trang chủ","pageId":"home"},{"id":"n2","label":"Giới thiệu","pageId":"about"},{"id":"n3","label":"Liên hệ","anchor":"#contact"}]}""").response.status).isEqualTo(200)
        assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"x","label":"X","url":"javascript:alert(1)"}]}""").response.status).isEqualTo(422)
        assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"x","label":"X","url":"https://evil.example/"}]}""").response.status).isEqualTo(422)
        assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"x","label":"X","pageId":"nope"}]}""").response.status).isEqualTo(422)
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('site.external-link-domains', 'example.com') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")
        settingsSvc.invalidate()
        try { assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"n1","label":"Đối tác","url":"https://docs.example.com/a"},{"id":"n2","label":"Giới thiệu","pageId":"about"}]}""").response.status).isEqualTo(200) }
        finally { jdbc.update("DELETE FROM system_settings WHERE key = 'site.external-link-domains'"); settingsSvc.invalidate() }
        // the host is no longer approved: editing still works (the existing link stays), publishing refuses until the link is fixed
        assertThat(sc.ops("""{"type":"UPDATE_SITE","props":{"title":"Shop"}}""").response.status).isEqualTo(200)
        assertThat(sc.publish(expect = "FAILED").get("error").asString()).contains("not approved")
        assertThat(sc.ops("""{"type":"SET_NAVIGATION","value":[{"id":"n1","label":"Trang chủ","pageId":"home"},{"id":"n2","label":"Giới thiệu","pageId":"about"},{"id":"n3","label":"Liên hệ","anchor":"#contact"}]}""").response.status).isEqualTo(200)
        assertThat(sc.ops("""{"type":"UPDATE_SITE","props":{"notFound":{"title":"Lạc đường rồi","message":"Trang không có"}}}""").response.status).isEqualTo(200)
        assertThat(sc.ops("""{"type":"UPDATE_PAGE","pageId":"home","props":{"seo":{"title":"Trang chủ","description":"Mô tả","noindex":false}}}""").response.status).isEqualTo(200)
        assertThat(sc.ops("""{"type":"UPDATE_PAGE","pageId":"home","props":{"slug":"x"}}""").response.status).isEqualTo(400)

        val d = sc.publish(); val slug = slugOf(d.get("url").asString()); val v = visitor()
        val manifest = jdbc.queryForObject("SELECT a.manifest::text FROM artifacts a JOIN deployments d ON d.artifact_id = a.id WHERE d.id = ?", String::class.java, java.util.UUID.fromString(d.get("id").asString()))
        assertThat(manifest).contains("\"index.html\"").contains("\"gioi-thieu/index.html\"").contains("\"404.html\"")
        assertThat(v.get("/sites/$slug/gioi-thieu/").response.contentAsString).contains("<h1>Trang giới thiệu</h1>")
        assertThat(v.get("/sites/$slug/gioi-thieu").response.getHeader("Location")).isEqualTo("gioi-thieu/")
        assertThat(v.get("/sites/$slug/khong-co/").response.status).isEqualTo(404)
        // removing a page also removes navigation links to it (the schema stays valid)
        assertThat(sc.ops("""{"type":"REMOVE_PAGE","pageId":"about"}""").response.status).isEqualTo(200)
        assertThat(sc.schema().get("site").get("navigation").toList().map { it.get("id").asString() }).containsExactly("n1", "n3")
    }

    @Test
    fun `website forms - validated, spam dropped silently, rate limited, origin checked, editors read and export, viewers cannot`() {
        val sc = scenario()
        sc.ops("""{"type":"ADD_SECTION","sectionType":"ContactForm","sectionId":"lien-he","props":{"heading":"Liên hệ"}}""")
        val d = sc.publish(); val slug = slugOf(d.get("url").asString()); val v = visitor()
        fun post(form: String, origin: String? = "https://sites.example.test", id: String = "lien-he", ip: String = "10.1.2.${(1..250).random()}") = v.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/sites/$slug/_forms/$id").contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .content(form).also { b -> origin?.let { b.header("Origin", it) } }.with { it.remoteAddr = ip; it }, includeCsrf = false)
        val ok = post("name=Lan&email=lan%40example.com&phone=0901&message=Xin+t%C6%B0+v%E1%BA%A5n&_page=home")
        assertThat(ok.response.status).isEqualTo(200); assertThat(ok.response.contentAsString).contains("Cảm ơn")
        assertThat(ok.response.getHeader("Content-Security-Policy")).contains("default-src 'none'")
        assertThat(post("name=&email=lan%40example.com&message=hi").response.status).isEqualTo(400)
        assertThat(post("name=A&email=not-an-email&message=hi").response.status).isEqualTo(400)
        assertThat(post("name=Bot&email=b%40example.com&message=hi&website=http%3A%2F%2Fspam").response.status).isEqualTo(200)     // honeypot: dropped, same page
        assertThat(post("name=A&email=a%40example.com&message=hi", origin = "https://evil.example").response.status).isEqualTo(403)
        assertThat(post("name=A&email=a%40example.com&message=hi", id = "hero-1").response.status).isEqualTo(400)                 // not a ContactForm
        val same = "10.9.9.9"; repeat(5) { post("name=R&email=r%40example.com&message=m", ip = same) }
        assertThat(post("name=R&email=r%40example.com&message=m", ip = same).response.status).isEqualTo(429)

        val list = sc.s.body(sc.s.get("${sc.base}/form-submissions"))
        assertThat(list.get("items").toList().map { it.get("data").get("name").asString() }).contains("Lan").doesNotContain("Bot")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM form_submissions WHERE project_id = ? AND ip_hash LIKE '10.%'", Long::class.java, sc.projectId)).isEqualTo(0)  // IPs hashed
        val csv = sc.s.get("${sc.base}/form-submissions/export")
        assertThat(csv.response.status).isEqualTo(200); assertThat(csv.response.getContentAsString(Charsets.UTF_8)).contains("\"Lan\"").contains("lan@example.com")
        val viewer = fx.user("formviewer"); fx.member(sc.ws, viewer, "VIEWER")
        val p = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", java.util.UUID::class.java, sc.projectId)
        jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role) VALUES (?,?,?, 'VIEWER')", p, sc.projectId, viewer.id)
        assertThat(sessionFor(viewer.username).get("${sc.base}/form-submissions").response.status).isEqualTo(403)
        val id = list.get("items")[0].get("id").asString()
        assertThat(sc.s.delete("${sc.base}/form-submissions/$id").response.status).isEqualTo(204)
        assertThat(sc.auditCount("FORM_SUBMISSIONS_EXPORTED")).isEqualTo(1); assertThat(sc.auditCount("FORM_SUBMISSION_DELETED")).isEqualTo(1)
    }

    @Test
    fun `custom domains - TXT verification, real TLS probe result, served only for verified public sites, no reserved hosts`() {
        val sc = scenario()
        val d = sc.publish(); val v = visitor()
        assertThat(sc.s.post("${sc.base}/domains", """{"hostname":"x.sites.example.test"}""").response.status).isEqualTo(400)       // platform host
        assertThat(sc.s.post("${sc.base}/domains", """{"hostname":"not a host"}""").response.status).isEqualTo(400)
        val host = "www.shop-${java.util.UUID.randomUUID().toString().take(6)}.example"
        val dom = sc.s.body(sc.s.post("${sc.base}/domains", """{"hostname":"$host"}"""))
        assertThat(dom.get("status").asString()).isEqualTo("PENDING"); assertThat(dom.get("txtName").asString()).isEqualTo("_hbl-verify.$host")
        val id = dom.get("id").asString(); val token = dom.get("txtValue").asString()
        // not verified yet: the host serves nothing
        assertThat(v.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/_host/").header("Host", host)).response.status).isEqualTo(404)
        org.mockito.Mockito.`when`(dnsLookup.txt("_hbl-verify.$host")).thenReturn(listOf("other-value"))
        assertThat(sc.s.body(sc.s.post("${sc.base}/domains/$id/verify")).get("status").asString()).isEqualTo("FAILED")
        org.mockito.Mockito.`when`(dnsLookup.txt("_hbl-verify.$host")).thenReturn(listOf(token))
        org.mockito.Mockito.`when`(tlsProbe.check(host)).thenReturn("PENDING" to "not reachable")
        val verified = sc.s.body(sc.s.post("${sc.base}/domains/$id/verify"))
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED"); assertThat(verified.get("tlsStatus").asString()).isEqualTo("PENDING")
        org.mockito.Mockito.`when`(tlsProbe.check(host)).thenReturn("ACTIVE" to null)
        assertThat(sc.s.body(sc.s.post("${sc.base}/domains/$id/check-tls")).get("tlsStatus").asString()).isEqualTo("ACTIVE")
        val page = v.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/_host/").header("Host", host))
        assertThat(page.response.status).isEqualTo(200); assertThat(page.response.contentAsString).contains("<h1>")
        // a verified host cannot be claimed again; a private site is not served on a custom domain
        assertThat(scenario().let { o -> o.s.post("${o.base}/domains", """{"hostname":"$host"}""").response.status }).isEqualTo(409)
        // a pending claim never blocks the real owner: two websites may claim; the first to prove ownership wins, other claims are void
        val h2 = "shop2-${java.util.UUID.randomUUID().toString().take(6)}.example"
        val squatter = scenario(); squatter.publish()
        val s1 = squatter.s.body(squatter.s.post("${squatter.base}/domains", """{"hostname":"$h2"}"""))
        val o2 = sc.s.body(sc.s.post("${sc.base}/domains", """{"hostname":"$h2"}"""))
        org.mockito.Mockito.`when`(dnsLookup.txt("_hbl-verify.$h2")).thenReturn(listOf(o2.get("txtValue").asString()))
        org.mockito.Mockito.`when`(tlsProbe.check(h2)).thenReturn("PENDING" to "not reachable")
        assertThat(sc.s.body(sc.s.post("${sc.base}/domains/${o2.get("id").asString()}/verify")).get("status").asString()).isEqualTo("VERIFIED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_domains WHERE id = ?::uuid", Long::class.java, s1.get("id").asString())).isEqualTo(0)
        // the daily re-check unverifies a domain whose TXT record is gone
        org.mockito.Mockito.`when`(dnsLookup.txt("_hbl-verify.$h2")).thenReturn(emptyList())
        domainService.recheck()
        assertThat(jdbc.queryForObject("SELECT status FROM site_domains WHERE id = ?::uuid", String::class.java, o2.get("id").asString())).isEqualTo("FAILED")
        sc.publish("PRIVATE")
        assertThat(v.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/_host/").header("Host", host)).response.status).isEqualTo(404)
        assertThat(sc.s.delete("${sc.base}/domains/$id").response.status).isEqualTo(204)
        assertThat(sc.auditCount("DOMAIN_VERIFIED")).isEqualTo(2)
    }

    @Test
    fun `a rendered page containing a script fails the build`() {
        val sc = scenario()
        mode = "script"
        assertThat(sc.publish(expect = "FAILED").get("error").asString()).contains("script")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ? AND current_deployment_id IS NOT NULL", Long::class.java, sc.projectId)).isEqualTo(0)
    }
}
