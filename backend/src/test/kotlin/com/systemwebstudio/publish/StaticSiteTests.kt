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
    companion object {
        @Volatile var mode = "ok"
        @Volatile var lastToken: String? = null
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
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
        assertThat(page.response.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("form-action 'none'").doesNotContain("script-src 'self'")
        assertThat(page.response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(page.response.getHeader("Cache-Control")).isEqualTo("public, no-cache")          // always revalidated: visibility changes apply at once
        assertThat(page.response.getHeader("Set-Cookie")).isNull()                                   // no session for visitors
        val etag = page.response.getHeader("ETag")!!
        assertThat(v.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/sites/$slug/").header("If-None-Match", etag)).response.status).isEqualTo(304)
        val img = v.get("/sites/$slug/assets/$asset.png")
        assertThat(img.response.status).isEqualTo(200); assertThat(img.response.contentAsByteArray).isEqualTo(png)
        assertThat(img.response.contentType).isEqualTo("image/png"); assertThat(img.response.getHeader("Cache-Control")).isEqualTo("public, no-cache")
        assertThat(v.get("/sites/$slug").response.getHeader("Location")).isEqualTo("/$slug/")
        assertThat(v.get("/sites/$slug/missing.html").response.status).isEqualTo(404)
        assertThat(v.get("/sites/$slug/..%2F..%2Fetc%2Fpasswd").response.status).isIn(400, 404)
        assertThat(v.get("/sites/no-such-site-x1/").response.status).isEqualTo(404)
        assertThat(v.post("/sites/$slug/", "{}").response.status).isIn(403, 405)                    // GET/HEAD only

        val artifact = jdbc.queryForMap("SELECT a.* FROM artifacts a JOIN deployments d ON d.artifact_id = a.id WHERE d.id = ?", java.util.UUID.fromString(d.get("id").asString()))
        assertThat(artifact["file_count"]).isEqualTo(2); assertThat((artifact["sha256"] as String)).hasSize(64)
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
        assertThat(ok.response.status).isEqualTo(200); assertThat(ok.response.getHeader("Cache-Control")).isEqualTo("private, no-store")

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

    @Test
    fun `a rendered page containing a script fails the build`() {
        val sc = scenario()
        mode = "script"
        assertThat(sc.publish(expect = "FAILED").get("error").asString()).contains("script")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ? AND current_deployment_id IS NOT NULL", Long::class.java, sc.projectId)).isEqualTo(0)
    }
}
