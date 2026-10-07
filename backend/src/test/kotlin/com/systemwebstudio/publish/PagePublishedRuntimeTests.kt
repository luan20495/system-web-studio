package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

/**
 * V1 option (b), the server side: a PAGE_SCHEMA site with data bindings is published directly through the renderer path (no code app, no Git, no build
 * runner), ships the client runtime, is served with the CSP that allows exactly that script and its own origin, has a runtime config, and its public query
 * allow-list is the one frozen in the release - draft edits cannot reach it, rollback restores it, unpublish removes it. Real PostgreSQL, MinIO, RabbitMQ;
 * the render worker is a double that follows its contract (the real worker is covered by tests/page-runtime and tests/browser).
 * NOT an end-to-end test: the public data controller (C0 + C3 + C1) does not exist yet, so no query is executed here.
 */
class PagePublishedRuntimeTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var released: PublishedRelease

    private var n = 0
    private fun Scenario.patch(vararg ops: String) = s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":${ops.joinToString(",", "[", "]")}}""")
    private fun Scenario.setHero(v: String) = patch("""{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"$v"}""")
    private fun Scenario.publish(): JsonNode {
        val id = s.body(s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":${revision()}}""", "Idempotency-Key" to "page-rt-${System.nanoTime()}-${n++}")).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { s.body(s.get("$base/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }
        return s.body(s.get("$base/deployments/$id"))
    }
    private fun Scenario.slug() = s.body(s.get("$base/site")).get("slug").asString()
    private fun Scenario.declareData(vararg publicQueries: String, privateQueries: List<String> = emptyList()) = patch(
        """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","name":"Orders","type":"postgres"}}""",
        *(publicQueries.map { """{"type":"ADD_QUERY","definition":{"id":"$it","dataSourceRef":"orders","operationKey":"k.$it","public":true}}""" } +
            privateQueries.map { """{"type":"ADD_QUERY","definition":{"id":"$it","dataSourceRef":"orders","operationKey":"k.$it"}}""" }).toTypedArray(),
        """{"type":"ADD_DATA_BINDING","definition":{"id":"b-title","sectionId":"hero-1","prop":"title","queryRef":"${publicQueries.first()}"}}""")
    private fun get(path: String) = mvc.perform(B.get(path)).andReturn().response
    private fun events(id: String, status: String) = jdbc.queryForList("SELECT message FROM deployment_events WHERE deployment_id = ?::uuid AND status = ?", String::class.java, id, status)

    @Test
    fun `a page with data bindings is published directly and served with its runtime, under a CSP that allows that one script and its own origin`() {
        val sc = scenario(); sc.setHero("Bound page"); assertThat(sc.declareData("q-title", "q-items").response.status).isEqualTo(200)
        val d = sc.publish(); assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo("RUNNING")
        val slug = sc.slug()
        val page = get("/sites/$slug/")
        assertThat(page.status).isEqualTo(200)
        assertThat(page.contentAsString).contains("""<script src="./_runtime/page-runtime.js" defer></script>""")
        assertThat(page.getHeader("Content-Security-Policy")).isEqualTo(SiteController_DATA_BOUND_CSP)
        assertThat(page.getHeader("Content-Security-Policy")).doesNotContain("unsafe-eval").doesNotContain("'unsafe-inline'; script").contains("script-src 'self'").contains("connect-src 'self'")
        val js = get("/sites/$slug/_runtime/page-runtime.js")
        assertThat(js.status).isEqualTo(200); assertThat(js.contentType).startsWith("application/javascript")
        assertThat(js.getHeader("Cache-Control")).isEqualTo("public, no-cache, no-transform"); assertThat(js.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(js.getHeader("ETag")).isNotBlank()
        // the runtime file is part of the immutable artifact: it is in the manifest with its own checksum
        val manifest = jdbc.queryForObject("SELECT a.manifest::text FROM artifacts a JOIN deployments d ON d.artifact_id = a.id WHERE d.id = ?::uuid", String::class.java, d.get("id").asString())!!
        assertThat(manifest).contains("_runtime/page-runtime.js").contains("application/javascript")
        // a page WITHOUT bindings is exactly what it always was: no runtime, no script, the strict CSP
        val plain = scenario(); plain.setHero("Plain page"); plain.publish()
        val plainPage = get("/sites/${plain.slug()}/")
        assertThat(plainPage.getHeader("Content-Security-Policy")).isEqualTo(SiteController_CSP)
        assertThat(plainPage.contentAsString).doesNotContain("<script")
        assertThat(get("/sites/${plain.slug()}/_runtime/page-runtime.js").status).isEqualTo(404)
        assertThat(get("/sites/${plain.slug()}/__factory/config.json").status).describedAs("a page without a runtime has no runtime config").isEqualTo(404)
    }

    @Test
    fun `the runtime config of a page site - exact JSON, same-origin apiBase for this slug, the served release, never cached, nothing sensitive`() {
        val sc = scenario(); sc.setHero("Config page"); sc.declareData("q-title"); val d = sc.publish(); val slug = sc.slug()
        val r = get("/sites/$slug/__factory/config.json")
        assertThat(r.status).isEqualTo(200); assertThat(r.contentType).isEqualTo("application/json")
        assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store, no-transform")
        val cfg = json.readTree(r.contentAsString)
        assertThat(cfg.propertyNames().toSet()).containsExactlyInAnyOrder("appId", "appName", "environment", "visibility", "version", "user", "flags", "apiBase", "releaseId", "generatedAt")
        assertThat(cfg.get("apiBase").asString()).isEqualTo("https://sites.example.test/$slug/_data")                       // browser-callable, same origin as the site, this site's slug
        assertThat(cfg.get("apiBase").asString()).startsWith(sitesOrigin()).doesNotContain("localhost").doesNotContain("127.0.0.1").doesNotContain("host.docker.internal")
        assertThat(cfg.get("releaseId").asString()).isEqualTo(d.get("id").asString())
        assertThat(cfg.get("appId").asString()).isEqualTo(sc.projectId.toString())
        assertThat(cfg.get("environment").asString()).isEqualTo("production"); assertThat(cfg.get("visibility").asString()).isEqualTo("PUBLIC")
        assertThat(cfg.get("version").asString()).isEqualTo(d.get("versionNumber").asInt().toString())
        assertThat(cfg.get("user").isNull).isTrue()
        val text = r.contentAsString
        assertThat(text).doesNotContain(sc.ws.toString()).doesNotContain(tenantOf(sc))                                      // no workspace id, no tenant id
        assertThat(text.lowercase()).doesNotContain("token", "secret", "password", "credential", "fence", "sha256", "artifact", "leaseuntil", "lease_")
        assertThat(Regex("\\blease\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)).isFalse()               // (the word "release" is fine)
        // a private page site never advertises a data address: how a member reaches it is not frozen (the data route is anonymous)
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("visibility").asString()).isEqualTo("PUBLIC")
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PRIVATE", null, UUID.randomUUID(), slug = slug, pageSite = true)["apiBase"]).isNull()
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, UUID.randomUUID(), slug = slug, pageSite = true)["apiBase"]).isEqualTo("https://sites.example.test/$slug/_data")
    }
    @Autowired lateinit var sites: SiteService
    private fun sitesOrigin() = "https://sites.example.test"
    private fun tenantOf(sc: Scenario) = jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, sc.projectId)!!.toString()

    @Test
    fun `the active release's allow-list is the one frozen at publish - a draft edit cannot widen it, narrow it or add to it`() {
        val sc = scenario(); sc.setHero("Release A"); sc.declareData("q-title", "q-items", privateQueries = listOf("q-private"))
        val a = sc.publish(); val slug = sc.slug()
        val active = released.active(slug)!!
        assertThat(active.publicQueries).containsExactly("q-title", "q-items")
        assertThat(active.deploymentId.toString()).isEqualTo(a.get("id").asString()); assertThat(active.projectId).isEqualTo(sc.projectId); assertThat(active.workspaceId).isEqualTo(sc.ws)
        assertThat(released.resolve(slug, "q-title")).isNotNull(); assertThat(released.resolve(slug, "q-private")).describedAs("exists in the project, but is not public").isNull()
        assertThat(released.resolve(slug, "q-ghost")).isNull(); assertThat(released.resolve("no-such-site", "q-title")).isNull(); assertThat(released.resolve("Bad Slug", "q-title")).isNull()
        assertThat(events(a.get("id").asString(), "PUBLIC_QUERIES").single()).contains("q-title, q-items").doesNotContain("q-private")      // the publish confirmation says what became public

        // the draft moves on: another title, q-title no longer public, q-private becomes public, a new public query
        sc.setHero("Draft after A")
        sc.patch("""{"type":"UPDATE_QUERY","definitionId":"q-title","definition":{"public":false}}""", """{"type":"UPDATE_QUERY","definitionId":"q-private","definition":{"public":true}}""",
            """{"type":"ADD_QUERY","definition":{"id":"q-new","dataSourceRef":"orders","operationKey":"k.new","public":true}}""")
        assertThat(sc.s.body(sc.s.get("${sc.base}/schema")).get("schema").get("queries").toList().filter { it.has("public") && it.get("public").asBoolean() }.map { it.get("id").asString() })
            .containsExactlyInAnyOrder("q-items", "q-private", "q-new")
        val after = released.active(slug)!!
        assertThat(after.publicQueries).describedAs("the active release did not change").containsExactly("q-title", "q-items")
        assertThat(after.versionId).isEqualTo(active.versionId)
        assertThat(released.resolve(slug, "q-new")).isNull(); assertThat(released.resolve(slug, "q-private")).isNull(); assertThat(released.resolve(slug, "q-title")).isNotNull()
        assertThat(get("/sites/$slug/").contentAsString).contains("Release A").doesNotContain("Draft after A")                   // and the page is still A
    }

    @Test
    fun `a new release brings its own allow-list, rollback gives the previous one back, unpublish leaves no usable query`() {
        val sc = scenario(); sc.setHero("Release A"); sc.declareData("q-title", "q-items")
        val a = sc.publish(); val slug = sc.slug()
        sc.setHero("Release B")
        sc.patch("""{"type":"UPDATE_QUERY","definitionId":"q-items","definition":{"public":false}}""", """{"type":"ADD_QUERY","definition":{"id":"q-b","dataSourceRef":"orders","operationKey":"k.b","public":true}}""")
        val b = sc.publish()
        assertThat(b.get("status").asString()).isEqualTo("RUNNING")
        assertThat(released.active(slug)!!.publicQueries).containsExactly("q-title", "q-b")                                      // B's list is active
        assertThat(released.resolve(slug, "q-items")).describedAs("A's query is not in B's list").isNull()
        assertThat(get("/sites/$slug/").contentAsString).contains("Release B")
        val cfgB = json.readTree(get("/sites/$slug/__factory/config.json").contentAsString)
        assertThat(cfgB.get("releaseId").asString()).isEqualTo(b.get("id").asString())

        // rollback to A: page, config, version and allow-list all go back, with no rebuild
        val artifacts = jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Int::class.java, sc.projectId)
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${a.get("id").asString()}"}""").response.status).isEqualTo(200)
        assertThat(released.active(slug)!!.publicQueries).containsExactly("q-title", "q-items")
        assertThat(released.resolve(slug, "q-b")).isNull(); assertThat(released.resolve(slug, "q-items")).isNotNull()
        assertThat(get("/sites/$slug/").contentAsString).contains("Release A")
        val cfgA = json.readTree(get("/sites/$slug/__factory/config.json").contentAsString)
        assertThat(cfgA.get("releaseId").asString()).isEqualTo(a.get("id").asString()); assertThat(cfgA.get("version").asString()).isEqualTo(a.get("versionNumber").asInt().toString())
        assertThat(cfgA.get("apiBase")).isEqualTo(cfgB.get("apiBase")); assertThat(cfgA.get("appId")).isEqualTo(cfgB.get("appId"))             // environment-level values do not move
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Int::class.java, sc.projectId)).isEqualTo(artifacts)       // nothing was rebuilt

        // unpublish: no page, no config, no active release, so no query is usable
        assertThat(sc.s.delete("${sc.base}/site").response.status).isEqualTo(200)
        assertThat(released.active(slug)).isNull()
        for (q in listOf("q-title", "q-items", "q-b")) assertThat(released.resolve(slug, q)).describedAs(q).isNull()
        assertThat(get("/sites/$slug/").status).isEqualTo(404); assertThat(get("/sites/$slug/__factory/config.json").status).isEqualTo(404); assertThat(get("/sites/$slug/_runtime/page-runtime.js").status).isEqualTo(404)
        // and a rollback brings the whole release back
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${a.get("id").asString()}"}""").response.status).isEqualTo(200)
        assertThat(released.resolve(slug, "q-title")).isNotNull()
    }

    @Test
    fun `an archived or deleted project has no active release either`() {
        val sc = scenario(); sc.setHero("Soon gone"); sc.declareData("q-title"); sc.publish(); val slug = sc.slug()
        assertThat(released.resolve(slug, "q-title")).isNotNull()
        jdbc.update("UPDATE projects SET active = false WHERE id = ?", sc.projectId)
        assertThat(released.active(slug)).isNull(); assertThat(released.resolve(slug, "q-title")).isNull()
    }

    @Test
    fun `the builder refuses every script except the one runtime reference, and refuses a half-shipped runtime`() {
        fun failedWith(title: String): String {
            val sc = scenario(); sc.setHero(title); val d = sc.publish()
            assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo("FAILED")
            return d.get("error").asString()
        }
        assertThat(failedWith("__SCRIPT__")).startsWith("[BUILD_FAILED]").contains("contains a script")                          // an inline script, with no bindings
        val bound = scenario(); bound.setHero("__SCRIPT__"); bound.declareData("q-title")
        assertThat(bound.publish().get("error").asString()).contains("contains a script")                                         // an inline script next to the allowed tag
        val orphan = scenario(); orphan.setHero("__ORPHAN_TAG__"); orphan.declareData("q-title")
        assertThat(orphan.publish().get("error").asString()).contains("references the page runtime but the renderer did not ship it")
        val noTag = scenario(); noTag.setHero("__NO_TAG__"); noTag.declareData("q-title")
        assertThat(noTag.publish().get("error").asString()).contains("shipped a page runtime that no page uses")
        val unused = scenario(); unused.setHero("__UNUSED_RUNTIME__")
        assertThat(unused.publish().get("error").asString()).contains("shipped a page runtime that no page uses")
    }

    @Test
    fun `a binding the renderer refuses fails the publish with the reason, once, and serves nothing`() {
        val sc = scenario(); sc.setHero("__REFUSE__"); sc.declareData("q-title")
        val d = sc.publish()
        assertThat(d.get("status").asString()).isEqualTo("FAILED")
        assertThat(d.get("error").asString()).startsWith("[BUILD_FAILED] The page cannot be published: binding 'b1': query 'q-title' is not public")
        assertThat(d.get("events").toList().count { it.get("message").asString().startsWith("Retry") }).describedAs("a refusal is permanent: never retried").isZero()
        assertThat(jdbc.queryForList("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId).firstOrNull()).isNull()
    }

    private companion object {
        const val SiteController_CSP = SiteServingController.SITE_CSP
        const val SiteController_DATA_BOUND_CSP = SiteServingController.DATA_BOUND_SITE_CSP
    }
}
