package com.systemwebstudio.code

import com.systemwebstudio.integration.git.GitFileChange
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Phase 7.2–7.3: code projects against a REAL Forgejo (container) — repository + scaffold commit, changes on branches, policy,
 * runner protocol (a fake runner uploads a prepared build output), preview capability URL with sandbox CSP, fast-forward merge,
 * publish of a code app. The real sandbox runner is covered by the browser E2E.
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.build.runner-token=runner-test-token", "app.sites.origin=https://sites.example.test",
    "app.sites.studio-origin=https://studio.example.test"])
class CodeProjectTests : IntegrationTestBase() {
    companion object {
        @Volatile var aiReply: String = ""
        private val aiStub: com.sun.net.httpserver.HttpServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val body = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(mapOf("id" to "c1",
                    "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to aiReply))),
                    "usage" to mapOf("prompt_tokens" to 900, "completion_tokens" to 300, "total_tokens" to 1200))).toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun git(registry: DynamicPropertyRegistry) {
            registry.add("app.git.url") { ForgejoFixture.url }
            registry.add("app.git.token") { ForgejoFixture.botToken }
            registry.add("app.git.admin-token") { ForgejoFixture.adminToken }
            registry.add("app.ai.providers.local.base-url") { "http://127.0.0.1:${aiStub.address.port}/v1" }
            registry.add("app.ai.providers.local.models") { "stub-code" }
        }

    }

    private fun Scenario.codeProject(): Pair<String, String> {
        val r = s.post(api(ws), """{"name":"Ứng dụng mã nguồn","appType":"STATIC_APP"}""")
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(201)
        val id = s.body(r).get("id").asString()
        return id to "${api(ws, UUID.fromString(id))}/code"
    }
    private fun runner() = session()
    private fun claim(): tools.jackson.databind.JsonNode? {
        val r = runner().perform(MockMvcRequestBuilders.post("/internal/build-jobs/claim").header("X-Runner-Token", "runner-test-token"), includeCsrf = false)
        return if (r.response.status == 200) json.readTree(r.response.contentAsString) else null
    }
    /** Minimal ustar archive: (name, bytes, typeflag). */
    private fun tar(entries: List<Triple<String, ByteArray, Char>>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for ((name, data, type) in entries) {
                val h = ByteArray(512)
                name.toByteArray().copyInto(h, 0)
                "0000644\u0000".toByteArray().copyInto(h, 100); "0000000\u0000".toByteArray().copyInto(h, 108); "0000000\u0000".toByteArray().copyInto(h, 116)
                String.format("%011o\u0000", data.size).toByteArray().copyInto(h, 124); "00000000000\u0000".toByteArray().copyInto(h, 136)
                h[156] = type.code.toByte(); "ustar\u000000".toByteArray().copyInto(h, 257)
                "        ".toByteArray().copyInto(h, 148); val sum = h.sumOf { it.toInt() and 0xff }; String.format("%06o\u0000 ", sum).toByteArray().copyInto(h, 148)
                gz.write(h); gz.write(data); gz.write(ByteArray(((data.size + 511) / 512) * 512 - data.size))
            }
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }
    private fun upload(jobId: String, body: ByteArray) = runner().perform(MockMvcRequestBuilders.put("/internal/build-jobs/$jobId/artifact")
        .header("X-Runner-Token", "runner-test-token").contentType("application/gzip").content(body), includeCsrf = false)
    private fun finish(jobId: String, status: String, error: String? = null) = runner().perform(MockMvcRequestBuilders.post("/internal/build-jobs/$jobId/finish")
        .header("X-Runner-Token", "runner-test-token").contentType("application/json")
        .content("""{"status":"$status","stage":"DONE","log":"ok","error":${error?.let { "\"$it\"" } ?: "null"},"scans":{"dependencies":{"findings":[]}}}"""), includeCsrf = false)
    private val dist = listOf(Triple("./index.html", """<!doctype html><script type="module" crossorigin src="./assets/app.js"></script><div id=root></div>""".toByteArray(), '0'),
        Triple("./assets/app.js", "document.getElementById('root').textContent='built'".toByteArray(), '0'))

    @Test
    fun `a code project gets a platform-owned repository with the scaffold on a protected main, and real files in Code mode`() {
        val sc = scenario()
        val (pid, code) = sc.codeProject()
        val project = sc.s.body(sc.s.get(api(sc.ws, UUID.fromString(pid))))
        assertThat(project.get("appType").asString()).isEqualTo("STATIC_APP")
        val tree = sc.s.body(sc.s.get("$code/tree")).toList().map { it.get("path").asString() }
        assertThat(tree).contains("package.json", "package-lock.json", "src/App.tsx", "vite.config.ts", "index.html")
        val app = sc.s.body(sc.s.get("$code/file?path=src/App.tsx"))
        assertThat(app.get("text").asString()).contains("export function App"); assertThat(app.get("editable").asBoolean()).isTrue()
        assertThat(sc.s.body(sc.s.get("$code/file?path=package.json")).get("editable").asBoolean()).isFalse()
        val commits = sc.s.body(sc.s.get("$code/commits")).toList()
        assertThat(commits).hasSize(1); assertThat(commits[0].get("committer").asString()).isEqualTo("factory-bot")
        assertThat(jdbc.queryForObject("SELECT commit_sha FROM project_versions WHERE project_id = ?::uuid", String::class.java, pid)).isEqualTo(commits[0].get("sha").asString())
        // page-schema endpoints and foreign users are refused
        assertThat(scenario().s.get("$code/tree").response.status).isEqualTo(404)
        val page = scenario(); assertThat(page.s.get("${page.base}/code/tree").response.status).isEqualTo(409)
    }

    @Test
    fun `the change policy refuses configuration, dependencies, binaries, traversal and credentials before anything is committed`() {
        fun bad(path: String, content: String = "x") = assertThatThrownBy { CodeChangePolicy.check(listOf(GitFileChange(path, content.toByteArray()))) }
        bad("package.json"); bad("package-lock.json"); bad("vite.config.ts"); bad("../etc/passwd"); bad("/src/a.ts"); bad("src/../package.json")
        bad("src/logo.png"); bad("src/a.ts", "const k = 'AKIAABCDEFGHIJKLMNOP'"); bad("src/a.ts", "-----BEGIN RSA PRIVATE KEY-----")
        CodeChangePolicy.check(listOf(GitFileChange("src/App.tsx", "export const x = 1".toByteArray()), GitFileChange("public/robots.txt", "ok".toByteArray())))
        val sc = scenario(); val (_, code) = sc.codeProject()
        val r = sc.s.post("$code/changes", """{"summary":"x","files":[{"path":"package.json","content":"{}"}]}""")
        assertThat(r.response.status).isEqualTo(422); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("PATH_NOT_ALLOWED")
        assertThat(sc.s.body(sc.s.get("$code/commits")).size()).isEqualTo(1)                                   // nothing was committed
    }

    @Test
    fun `safe tar refuses links and traversal in build output`() {
        assertThat(SafeTar.read(tar(dist)).keys).containsExactly("index.html", "assets/app.js")
        assertThatThrownBy { SafeTar.read(tar(listOf(Triple("../evil.js", "x".toByteArray(), '0')))) }.hasMessageContaining("unsafe path")
        assertThatThrownBy { SafeTar.read(tar(listOf(Triple("link", ByteArray(0), '2')))) }.hasMessageContaining("links")
    }

    @Test
    fun `change - branch commit, runner builds it, preview is a sandboxed capability URL, merge fast-forwards with the user as author, publish serves it`() {
        val sc = scenario(); val (pid, code) = sc.codeProject()
        val app = sc.s.body(sc.s.get("$code/file?path=src/App.tsx")).get("text").asString()
        val c = sc.s.body(sc.s.post("$code/changes", """{"summary":"Đổi tiêu đề","files":[{"path":"src/App.tsx","content":${json.writeValueAsString(app.replace("Ứng dụng mới của bạn", "Tiêu đề mới"))}}]}"""))
        assertThat(c.get("status").asString()).isEqualTo("BUILDING"); assertThat(c.get("branch").asString()).startsWith("edit/")
        val changeId = c.get("id").asString()

        // runner protocol: token required, source = the exact commit
        assertThat(runner().perform(MockMvcRequestBuilders.post("/internal/build-jobs/claim"), includeCsrf = false).response.status).isEqualTo(404)
        var job = claim()!!
        while (job.get("projectId").asString() != pid) { finish(job.get("id").asString(), "FAILED", "other test"); job = claim()!! }
        assertThat(job.get("commitSha").asString()).isEqualTo(c.get("headSha").asString())
        val src = runner().perform(MockMvcRequestBuilders.get("/internal/build-jobs/${job.get("id").asString()}/source").header("X-Runner-Token", "runner-test-token"), includeCsrf = false)
        assertThat(src.response.status).isEqualTo(200); assertThat(SafeTar.read(src.response.contentAsByteArray).keys.any { it.endsWith("src/App.tsx") }).isTrue()
        // a credential in the output is refused even if the runner missed it
        assertThat(upload(job.get("id").asString(), tar(listOf(Triple("index.html", "<p>ghp_abcdefghijklmnopqrstuvwxyz0123456789</p>".toByteArray(), '0')))).response.status).isEqualTo(400)
        assertThat(upload(job.get("id").asString(), tar(dist)).response.status).isEqualTo(200)
        assertThat(finish(job.get("id").asString(), "SUCCEEDED").response.status).isEqualTo(200)

        val ready = sc.s.body(sc.s.get("$code/changes/$changeId"))
        assertThat(ready.get("status").asString()).isEqualTo("READY")
        val preview = ready.get("previewUrl").asString(); assertThat(preview).startsWith("https://sites.example.test/_preview/")
        val token = preview.removePrefix("https://sites.example.test/_preview/").trimEnd('/')
        val page = session().get("/sites/_preview/$token/")
        assertThat(page.response.status).isEqualTo(200)
        assertThat(page.response.getHeader("Content-Security-Policy")).startsWith("sandbox allow-scripts").contains("frame-ancestors https://studio.example.test")
        val js = session().perform(MockMvcRequestBuilders.get("/sites/_preview/$token/assets/app.js").header("Origin", "null"), includeCsrf = false)
        assertThat(js.response.status).isEqualTo(200); assertThat(js.response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(session().get("/sites/_preview/notavalidtoken000000000/").response.status).isEqualTo(404)
        val diff = sc.s.body(sc.s.get("$code/changes/$changeId/diff")).toList().single()
        assertThat(diff.get("before").asString()).contains("Ứng dụng mới của bạn"); assertThat(diff.get("after").asString()).contains("Tiêu đề mới")

        // a viewer cannot merge; the editor merges (fast-forward keeps the author)
        val viewer = fx.user("codeviewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(fx.projects.findById(UUID.fromString(pid)).get(), viewer, "VIEWER")
        assertThat(sessionFor(viewer.username).post("$code/changes/$changeId/merge").response.status).isEqualTo(403)
        assertThat(sc.s.body(sc.s.post("$code/changes/$changeId/merge")).get("status").asString()).isEqualTo("MERGED")
        val head = sc.s.body(sc.s.get("$code/commits")).toList().first()
        assertThat(head.get("sha").asString()).isEqualTo(c.get("headSha").asString()); assertThat(head.get("committer").asString()).isEqualTo("factory-bot")
        assertThat(head.get("message").asString()).contains("Code-Change-Id: $changeId")

        // publish: private is refused for code apps; public builds the merged commit and serves it with the sandbox policy
        val base = api(sc.ws, UUID.fromString(pid))
        val rev = sc.s.body(sc.s.get(base)).get("revision").asLong()
        val dep = sc.s.body(sc.s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":$rev}""", "Idempotency-Key" to "code-pub-1")).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE deployment_id = ?::uuid", Long::class.java, dep)!! > 0 }
        var pj = claim()!!
        while (pj.get("purpose").asString() != "PUBLISH" || pj.get("projectId").asString() != pid) { finish(pj.get("id").asString(), "FAILED", "other"); pj = claim()!! }
        assertThat(pj.get("commitSha").asString()).isEqualTo(head.get("sha").asString())
        upload(pj.get("id").asString(), tar(dist)); finish(pj.get("id").asString(), "SUCCEEDED")
        await().atMost(Duration.ofSeconds(30)).until { sc.s.body(sc.s.get("$base/deployments/$dep")).get("status").asString() in setOf("RUNNING", "FAILED") }
        val d = sc.s.body(sc.s.get("$base/deployments/$dep"))
        assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo("RUNNING")
        val slug = d.get("url").asString().removePrefix("https://sites.example.test/").trimEnd('/')
        val live = session().get("/sites/$slug/")
        assertThat(live.response.status).isEqualTo(200); assertThat(live.response.getHeader("Content-Security-Policy")).startsWith("sandbox allow-scripts").contains("frame-ancestors 'none'")
    }

    @Test
    fun `a failed build marks the change FAILED and it cannot be merged, discard closes it and revokes the preview`() {
        val sc = scenario(); val (pid, code) = sc.codeProject()
        val c = sc.s.body(sc.s.post("$code/changes", """{"summary":"Lỗi","files":[{"path":"src/broken.ts","content":"export const x: number = 'nope'"}]}"""))
        var job = claim()!!
        while (job.get("projectId").asString() != pid) { finish(job.get("id").asString(), "FAILED", "other"); job = claim()!! }
        finish(job.get("id").asString(), "FAILED", "BUILD: typecheck failed")
        val failed = sc.s.body(sc.s.get("$code/changes/${c.get("id").asString()}"))
        assertThat(failed.get("status").asString()).isEqualTo("FAILED"); assertThat(failed.get("error").asString()).contains("typecheck")
        assertThat(failed.get("build").get("status").asString()).isEqualTo("FAILED")
        assertThat(sc.s.post("$code/changes/${c.get("id").asString()}/merge").response.status).isEqualTo(409)
        assertThat(sc.s.body(sc.s.post("$code/changes/${c.get("id").asString()}/discard")).get("status").asString()).isEqualTo("DISCARDED")
        assertThat(sc.s.body(sc.s.get("$code/commits")).size()).isEqualTo(1)                                      // main untouched
    }

    @Test
    fun `AI for code - the simulator makes a real change on an ai branch, unknown requests change nothing`() {
        val sc = scenario(); val (_, code) = sc.codeProject()
        val r = sc.s.body(sc.s.post("$code/ai", """{"prompt":"Đổi tiêu đề thành \"Xin chào AI\""}"""))
        assertThat(r.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(r.get("provider").asString()).isEqualTo("mock")
        val change = r.get("change"); assertThat(change.get("kind").asString()).isEqualTo("AI"); assertThat(change.get("branch").asString()).startsWith("ai/")
        val diff = sc.s.body(sc.s.get("$code/changes/${change.get("id").asString()}/diff")).toList().single()
        assertThat(diff.get("path").asString()).isEqualTo("src/App.tsx"); assertThat(diff.get("after").asString()).contains("<Heading level={1}>Xin chào AI</Heading>")
        val none = sc.s.body(sc.s.post("$code/ai", """{"prompt":"làm một trò chơi 3D"}"""))
        assertThat(none.get("outcome").asString()).isEqualTo("NO_CHANGE"); assertThat(none.get("change").isNull).isTrue()
        val history = sc.s.body(sc.s.get("$code/ai")).toList()
        assertThat(history).hasSize(2); assertThat(history.last().get("changeId").asString()).isEqualTo(change.get("id").asString())
    }

    @Test
    fun `AI for code - a model answer is data - valid files become an ai change with usage recorded, forbidden files are refused`() {
        val a = sessionFor(fx.user("codeai-admin", systemAdmin = true).username)
        assertThat(a.put("/api/v1/admin/ai/models/policy", """{"modelId":"local:stub-code","enabled":true}""").response.status).isEqualTo(200)
        val sc = scenario(); val (pid, code) = sc.codeProject()
        aiReply = json.writeValueAsString(mapOf("message" to "Đã thêm phần giới thiệu", "files" to listOf(mapOf("path" to "src/Intro.tsx", "content" to "export const Intro = () => <p>Giới thiệu</p>;"))))
        val ok = sc.s.body(sc.s.post("$code/ai", """{"prompt":"thêm phần giới thiệu","model":"local:stub-code"}"""))
        assertThat(ok.get("outcome").asString()).isEqualTo("UPDATED"); assertThat(ok.get("provider").asString()).isEqualTo("local")
        assertThat(ok.get("usage").get("totalTokens").asLong()).isEqualTo(1200)
        assertThat(ok.get("change").get("files").toList().map { it.asString() }).containsExactly("src/Intro.tsx")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_calls WHERE project_id = ?::uuid", Long::class.java, pid)).isEqualTo(1)
        val head = ok.get("change").get("headSha").asString()
        aiReply = json.writeValueAsString(mapOf("message" to "x", "files" to listOf(mapOf("path" to "package.json", "content" to "{\"dependencies\":{\"evil\":\"1\"}}"))))
        val refused = sc.s.body(sc.s.post("$code/ai", """{"prompt":"thêm thư viện","model":"local:stub-code"}"""))
        assertThat(refused.get("outcome").asString()).isEqualTo("UNSUPPORTED"); assertThat(refused.get("change").isNull).isTrue()
        aiReply = "I cannot help with that"
        assertThat(sc.s.body(sc.s.post("$code/ai", """{"prompt":"x","model":"local:stub-code"}""")).get("outcome").asString()).isEqualTo("NO_CHANGE")
        assertThat(sc.s.body(sc.s.get("$code/changes")).toList().map { it.get("headSha").asString() }).containsExactly(head)   // only the valid change exists
        // page-schema prompt endpoint refuses code projects
        assertThat(sc.s.post("${api(sc.ws, UUID.fromString(pid))}/prompts", """{"prompt":"x","expectedRevision":0}""").response.status).isEqualTo(409)
    }

    private fun setting(key: String, value: String) { jdbc.update("INSERT INTO system_settings (key, value) VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value); settingsSvc.invalidate() }
    @org.springframework.beans.factory.annotation.Autowired lateinit var settingsSvc: com.systemwebstudio.settings.SettingsService
    @org.junit.jupiter.api.AfterEach fun resetSettings() { jdbc.update("DELETE FROM system_settings"); settingsSvc.invalidate() }

    @Test
    fun `policy - source apps can be disabled, builds can be disabled, daily build quota and repo size are enforced and recorded`() {
        setting("source-apps.enabled", "false")
        val sc = scenario()
        assertThat(sc.s.post(api(sc.ws), """{"name":"x","appType":"STATIC_APP"}""").response.status).isEqualTo(409)
        assertThat(sc.s.body(sc.s.get("/api/v1/auth/config")).get("codeProjects").asBoolean()).isFalse()
        setting("source-apps.enabled", "true")
        val (pid, code) = sc.codeProject()
        val body = """{"summary":"q","files":[{"path":"src/q.ts","content":"export const q = 1"}]}"""
        setting("source-apps.build-enabled", "false")
        assertThat(sc.s.body(sc.s.post("$code/changes", body)).get("code").asString()).isEqualTo("BUILDS_DISABLED")
        setting("source-apps.build-enabled", "true")
        setting("build.max-per-user-per-day", "1")
        assertThat(sc.s.post("$code/changes", body).response.status).isEqualTo(201)
        val r = sc.s.post("$code/changes", body)
        assertThat(r.response.status).isEqualTo(429); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("BUILD_QUOTA_USER_DAILY")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM build_rejections WHERE project_id = ?::uuid AND reason_code = 'BUILD_QUOTA_USER_DAILY'", Long::class.java, pid)).isEqualTo(1)
        assertThat(sc.s.body(sc.s.get("$code/commits")).size()).isEqualTo(1)        // refused before anything was committed
        setting("build.max-per-user-per-day", "100"); setting("build.max-concurrent-per-user", "10"); setting("build.max-repo-mib", "1")
        val big = sc.s.post("$code/changes", """{"summary":"big","files":[{"path":"src/big.ts","content":"${"x".repeat(190_000)}"},{"path":"src/big2.ts","content":"${"y".repeat(190_000)}"},{"path":"src/big3.ts","content":"${"z".repeat(190_000)}"},{"path":"src/big4.ts","content":"${"w".repeat(190_000)}"},{"path":"src/big5.ts","content":"${"v".repeat(190_000)}"},{"path":"src/big6.ts","content":"${"u".repeat(190_000)}"}]}""")
        assertThat(big.response.status).isIn(413, 422)
        val a = sessionFor(fx.user("bp-admin", systemAdmin = true).username)
        val rep = a.body(a.get("/api/v1/admin/builds"))
        assertThat(rep.get("rejections").toList().map { it.get("reason").asString() }).contains("BUILD_QUOTA_USER_DAILY", "BUILDS_DISABLED")
        assertThat(sc.s.get("/api/v1/admin/builds").response.status).isEqualTo(403)
    }

    @Test
    fun `private code app - entry needs the site session, content is served from a per-session capability path, membership re-checked`() {
        setting("source-apps.public-publish-enabled", "false")
        val sc = scenario(); val (pid, _) = sc.codeProject()
        val base = api(sc.ws, UUID.fromString(pid))
        val rev = sc.s.body(sc.s.get(base)).get("revision").asLong()
        val pub = sc.s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":$rev}""", "Idempotency-Key" to "code-pub-off")
        assertThat(pub.response.status).isEqualTo(403); assertThat(sc.s.body(pub).get("code").asString()).isEqualTo("CODE_APP_PUBLIC_DISABLED")
        val dep = sc.s.body(sc.s.post("$base/publish", """{"visibility":"PRIVATE","expectedRevision":$rev}""", "Idempotency-Key" to "code-priv-ok")).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE deployment_id = ?::uuid", Long::class.java, dep)!! > 0 }
        var pj = claim()!!
        while (pj.get("purpose").asString() != "PUBLISH" || pj.get("projectId").asString() != pid) { finish(pj.get("id").asString(), "FAILED", "other"); pj = claim()!! }
        upload(pj.get("id").asString(), tar(dist)); finish(pj.get("id").asString(), "SUCCEEDED")
        await().atMost(Duration.ofSeconds(30)).until { sc.s.body(sc.s.get("$base/deployments/$dep")).get("status").asString() == "RUNNING" }
        val slug = sc.s.body(sc.s.get("$base/deployments/$dep")).get("url").asString().removePrefix("https://sites.example.test/").trimEnd('/')
        assertThat(session().get("/sites/$slug/").response.status).isEqualTo(302)                       // → Studio sign-in
        val ticket = sc.s.body(sc.s.post("/api/v1/sites/$slug/access-ticket", """{"path":"/"}""")).get("redirect").asString().substringAfter("ticket=")
        val v = session()
        val cookie = v.get("/sites/_access?ticket=$ticket").response.getHeader("Set-Cookie")!!.substringBefore(';').substringAfter('=')
        fun withCookie(path: String) = v.perform(MockMvcRequestBuilders.get(path).cookie(jakarta.servlet.http.Cookie("site_session", cookie)), includeCsrf = false)
        val entry = withCookie("/sites/$slug/")
        assertThat(entry.response.status).isEqualTo(302)
        val appPath = entry.response.getHeader("Location")!!; assertThat(appPath).startsWith("/_app/")
        val token = appPath.removePrefix("/_app/").substringBefore('/')
        val html = session().get("/sites/_app/$token/")                                                   // no cookie needed (opaque origin)
        assertThat(html.response.status).isEqualTo(200); assertThat(html.response.getHeader("Content-Security-Policy")).startsWith("sandbox allow-scripts")
        assertThat(html.response.getHeader("Cache-Control")).contains("no-store")
        fx.disable(sc.user.id)
        assertThat(session().get("/sites/_app/$token/").response.status).isEqualTo(403)
        assertThat(session().get("/sites/_app/notarealtoken00000000000/").response.status).isEqualTo(404)
    }

    @Test
    fun `deleting a code project archives its repository, hard delete only after retention by an admin`() {
        val sc = scenario(); val (pid, _) = sc.codeProject()
        val base = api(sc.ws, UUID.fromString(pid))
        val rev = sc.s.body(sc.s.get(base)).get("revision").asLong()
        assertThat(sc.s.delete("$base?expectedRevision=$rev").response.status).isEqualTo(204)
        assertThat(jdbc.queryForObject("SELECT state FROM repositories WHERE project_id = ?::uuid", String::class.java, pid)).isEqualTo("ARCHIVED")
        val a = sessionFor(fx.user("repo-admin", systemAdmin = true).username)
        assertThat(a.post("/api/v1/admin/retention/repositories/$pid/delete").response.status).isEqualTo(409)   // still within retention
        jdbc.update("UPDATE repositories SET delete_after = now() - interval '1 minute' WHERE project_id = ?::uuid", pid)
        a.post("/api/v1/admin/retention/run")
        assertThat(jdbc.queryForObject("SELECT state FROM repositories WHERE project_id = ?::uuid", String::class.java, pid)).isEqualTo("PENDING_DELETE")
        assertThat(a.post("/api/v1/admin/retention/repositories/$pid/delete").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT state FROM repositories WHERE project_id = ?::uuid", String::class.java, pid)).isEqualTo("DELETED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN ('REPOSITORY_ARCHIVED','REPOSITORY_PENDING_DELETE','REPOSITORY_DELETED')", Long::class.java, pid)).isEqualTo(3)
    }

    private fun finishWith(jobId: String, status: String, result: Any?, error: String? = null) = runner().perform(MockMvcRequestBuilders.post("/internal/build-jobs/$jobId/finish")
        .header("X-Runner-Token", "runner-test-token").contentType("application/json")
        .content(json.writeValueAsString(mapOf("status" to status, "stage" to "DONE", "result" to result, "error" to error))), includeCsrf = false)
    private fun claimPurpose(purpose: String, match: (tools.jackson.databind.JsonNode) -> Boolean = { true }): tools.jackson.databind.JsonNode {
        var j = claim()!!
        while (j.get("purpose").asString() != purpose || !match(j)) { finish(j.get("id").asString(), "FAILED", "other"); j = claim()!! }
        return j
    }

    @Test
    fun `package catalog - approval resolves the closure, HIGH findings are denied by default, explicit risk acceptance is recorded`() {
        val a = sessionFor(fx.user("pkg-admin", systemAdmin = true).username)
        assertThat(scenario().s.post("/api/v1/admin/packages", """{"name":"left-pad"}""").response.status).isEqualTo(403)
        assertThat(a.body(a.post("/api/v1/admin/packages", """{"name":"left-pad","versionRange":"^1.3.0"}""")).get("status").asString()).isEqualTo("RESOLVING")
        val job = claimPurpose("RESOLVE") { it.get("input").get("name").asString() == "left-pad" }
        assertThat(job.get("input").get("spec").asString()).isEqualTo("^1.3.0"); assertThat(job.get("sourceUrl").isNull).isTrue()
        finishWith(job.get("id").asString(), "SUCCEEDED", mapOf("packages" to listOf(mapOf("name" to "left-pad", "version" to "1.3.0")), "findings" to emptyList<Any>(), "blocking" to emptyList<Any>()))
        val ok = a.body(a.get("/api/v1/admin/packages")).toList().single { it.get("name").asString() == "left-pad" }
        assertThat(ok.get("status").asString()).isEqualTo("ALLOWED"); assertThat(ok.get("dependencies").asInt()).isEqualTo(1)
        assertThat(claimPurposeAllowlist()).contains("left-pad")

        a.post("/api/v1/admin/packages", """{"name":"bad-pkg"}""")
        val j2 = claimPurpose("RESOLVE") { it.get("input").get("name").asString() == "bad-pkg" }
        val vuln = mapOf("package" to "bad-pkg@1.0.0", "id" to "GHSA-test", "severity" to "HIGH")
        finishWith(j2.get("id").asString(), "SUCCEEDED", mapOf("packages" to listOf(mapOf("name" to "bad-pkg", "version" to "1.0.0")), "findings" to listOf(vuln), "blocking" to listOf(vuln)))
        assertThat(a.body(a.get("/api/v1/admin/packages")).toList().single { it.get("name").asString() == "bad-pkg" }.get("status").asString()).isEqualTo("DENIED")
        assertThat(a.put("/api/v1/admin/packages/bad-pkg/decision", """{"status":"ALLOWED"}""").response.status).isEqualTo(428)
        val accepted = a.body(a.put("/api/v1/admin/packages/bad-pkg/decision", """{"status":"ALLOWED","acceptRisk":true,"note":"needed for X, fix pending"}"""))
        assertThat(accepted.get("status").asString()).isEqualTo("ALLOWED"); assertThat(accepted.get("riskAccepted").asBoolean()).isTrue()
        a.put("/api/v1/admin/packages/bad-pkg/decision", """{"status":"DENIED"}""")
    }
    private fun claimPurposeAllowlist(): List<String> {
        val sc = scenario(); val (_, code) = sc.codeProject()
        sc.s.post("$code/changes", """{"summary":"x","files":[{"path":"src/x.ts","content":"export const x = 1"}]}""")
        val j = claimPurpose("PREVIEW"); finish(j.get("id").asString(), "FAILED", "not needed")
        return j.get("allowlist").toList().map { it.asString() }
    }

    @Test
    fun `dependency request - only catalog packages, lockfile from the sandbox validated before a DEPENDENCY change is committed`() {
        val sc = scenario(); val (pid, code) = sc.codeProject()
        val unknown = sc.s.post("$code/dependencies", """{"name":"some-unknown-lib"}""")
        assertThat(unknown.response.status).isEqualTo(403); assertThat(sc.s.body(unknown).get("code").asString()).isEqualTo("PACKAGE_NOT_APPROVED")
        assertThat(jdbc.queryForObject("SELECT status FROM approved_packages WHERE name = 'some-unknown-lib'", String::class.java)).isEqualTo("PENDING")
        jdbc.update("""INSERT INTO approved_packages (name, status, version_range, resolved) VALUES ('tiny-lib', 'ALLOWED', '^2.0.0', '[{"name":"tiny-lib","version":"2.1.0"}]'::jsonb)
            ON CONFLICT (name) DO UPDATE SET status = 'ALLOWED', resolved = EXCLUDED.resolved""")
        val pkgJson = sc.s.body(sc.s.get("$code/file?path=package.json")).get("text").asString()
        val lock = sc.s.body(sc.s.get("$code/file?path=package-lock.json")).get("text").asString()
        fun request(): String { val r = sc.s.post("$code/dependencies", """{"name":"tiny-lib"}"""); assertThat(r.response.status).isEqualTo(202); return sc.s.body(r).get("id").asString() }
        fun lockJob() = claimPurpose("LOCK") { it.get("projectId").asString() == pid }
        val newPkg = (json.readTree(pkgJson) as tools.jackson.databind.node.ObjectNode).also { (it.get("dependencies") as tools.jackson.databind.node.ObjectNode).put("tiny-lib", "^2.1.0") }
        val newLock = (json.readTree(lock) as tools.jackson.databind.node.ObjectNode).also { (it.get("packages") as tools.jackson.databind.node.ObjectNode).putObject("node_modules/tiny-lib").put("version", "2.1.0") }

        // tampered package.json (extra script) is refused
        val r1 = request(); val j1 = lockJob(); assertThat(j1.get("input").get("spec").asString()).isEqualTo("^2.0.0")
        val evil = newPkg.deepCopy().also { it.putObject("scripts").put("postinstall", "curl evil") }
        finishWith(j1.get("id").asString(), "SUCCEEDED", mapOf("packageJson" to json.writeValueAsString(evil), "packageLock" to json.writeValueAsString(newLock)))
        assertThat(sc.s.body(sc.s.get("$code/dependencies")).get("requests").toList().single { it.get("id").asString() == r1 }.get("error").asString()).contains("beyond")
        // lockfile with a package outside the catalog is refused
        val r2 = request(); val j2 = lockJob()
        val sneaky = newLock.deepCopy().also { (it.get("packages") as tools.jackson.databind.node.ObjectNode).putObject("node_modules/evil-dep").put("version", "6.6.6") }
        finishWith(j2.get("id").asString(), "SUCCEEDED", mapOf("packageJson" to json.writeValueAsString(newPkg), "packageLock" to json.writeValueAsString(sneaky)))
        assertThat(sc.s.body(sc.s.get("$code/dependencies")).get("requests").toList().single { it.get("id").asString() == r2 }.get("error").asString()).contains("evil-dep")
        // valid → DEPENDENCY change with exactly package.json + package-lock.json, then a normal build
        val r3 = request(); val j3 = lockJob()
        finishWith(j3.get("id").asString(), "SUCCEEDED", mapOf("packageJson" to json.writeValueAsString(newPkg), "packageLock" to json.writeValueAsString(newLock)))
        val req = sc.s.body(sc.s.get("$code/dependencies")).get("requests").toList().single { it.get("id").asString() == r3 }
        assertThat(req.get("status").asString()).describedAs(req.toString()).isEqualTo("COMMITTED")
        val change = sc.s.body(sc.s.get("$code/changes/${req.get("changeId").asString()}"))
        assertThat(change.get("kind").asString()).isEqualTo("DEPENDENCY"); assertThat(change.get("branch").asString()).startsWith("dep/")
        assertThat(change.get("files").toList().map { it.asString() }).containsExactlyInAnyOrder("package.json", "package-lock.json")
        assertThat(change.get("status").asString()).isEqualTo("BUILDING")
    }

    @Test
    fun `review before merge - required by workspace policy, no self-approval, a publisher approves`() {
        val sc = scenario(); val (pid, code) = sc.codeProject()
        val wsAdmin = fx.user("ws-reviewadm"); fx.member(sc.ws, wsAdmin, "WORKSPACE_ADMIN")
        assertThat(sc.s.put("/api/v1/workspaces/${sc.ws}/merge-policy", """{"policy":"REVIEW_REQUIRED"}""").response.status).isEqualTo(403)
        val wa = sessionFor(wsAdmin.username)
        assertThat(wa.put("/api/v1/workspaces/${sc.ws}/merge-policy", """{"policy":"REVIEW_REQUIRED"}""").response.status).isEqualTo(200)
        val c = sc.s.body(sc.s.post("$code/changes", """{"summary":"needs review","files":[{"path":"src/r.ts","content":"export const r = 1"}]}"""))
        val job = claimPurpose("PREVIEW") { it.get("projectId").asString() == pid }; upload(job.get("id").asString(), tar(dist)); finish(job.get("id").asString(), "SUCCEEDED")
        val id = c.get("id").asString()
        assertThat(sc.s.body(sc.s.get("$code/changes/$id")).get("reviewRequired").asBoolean()).isTrue()
        val m = sc.s.post("$code/changes/$id/merge"); assertThat(m.response.status).isEqualTo(409); assertThat(sc.s.body(m).get("code").asString()).isEqualTo("REVIEW_REQUIRED")
        // the author is OWNER of the project (has publish rights) but cannot approve their own change
        assertThat(sc.s.body(sc.s.post("$code/changes/$id/approve", """{"comment":"lgtm"}""")).get("code").asString()).isEqualTo("SELF_REVIEW")
        val approved = wa.body(wa.post("$code/changes/$id/approve", """{"comment":"Đã xem, ổn"}"""))
        assertThat(approved.get("approvedBy").asString()).isNotBlank()
        val merged = sc.s.body(sc.s.post("$code/changes/$id/merge"))
        assertThat(merged.get("status")?.asString()).describedAs(merged.toString()).isEqualTo("MERGED")
        wa.put("/api/v1/workspaces/${sc.ws}/merge-policy", """{"policy":"AUTO_MERGE_ALLOWED"}""")
    }

    @Test
    fun `IDE access - per-user read-only token that can read the repository but not write, revocable`() {
        val sc = scenario(); val (pid, _) = sc.codeProject()
        val access = sc.s.body(sc.s.post("${api(sc.ws, UUID.fromString(pid))}/code/clone-access"))
        val url = access.get("cloneUrl").asString(); val user = access.get("username").asString(); val token = access.get("token").asString()
        assertThat(url).startsWith(ForgejoFixture.url).endsWith(".git")
        val repoApi = url.removeSuffix(".git").replace(ForgejoFixture.url, "${ForgejoFixture.url}/api/v1/repos")
        fun get(t: String) = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI("$repoApi/contents/src/App.tsx")).header("Authorization", "token $t").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()
        assertThat(get(token)).isEqualTo(200)
        val write = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI("$repoApi/contents/src/evil.ts")).header("Authorization", "token $token")
            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"content":"eA==","message":"x","branch":"main"}""")).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        assertThat(write.statusCode()).isIn(401, 403, 404)
        assertThat(scenario().s.post("${api(sc.ws, UUID.fromString(pid))}/code/clone-access").response.status).isEqualTo(404)   // non-member
        assertThat(sc.s.delete("/api/v1/me/clone-access").response.status).isEqualTo(204)
        assertThat(get(token)).isEqualTo(401)
        assertThat(user).startsWith("s-")
    }

    @Test
    fun `runtime config for app-sdk is served next to the preview, without secrets`() {
        val sc = scenario(); val (pid, code) = sc.codeProject()
        val c = sc.s.body(sc.s.post("$code/changes", """{"summary":"cfg","files":[{"path":"src/c.ts","content":"export const c = 1"}]}"""))
        val job = claimPurpose("PREVIEW") { it.get("projectId").asString() == pid }; upload(job.get("id").asString(), tar(dist)); finish(job.get("id").asString(), "SUCCEEDED")
        val token = sc.s.body(sc.s.get("$code/changes/${c.get("id").asString()}")).get("previewUrl").asString().substringAfter("/_preview/").trimEnd('/')
        val r = session().get("/sites/_preview/$token/__factory/config.json")
        assertThat(r.response.status).isEqualTo(200)
        val cfg = session().body(r)
        assertThat(cfg.get("appId").asString()).isEqualTo(pid); assertThat(cfg.get("environment").asString()).isEqualTo("preview"); assertThat(cfg.get("user").isNull).isTrue()
        assertThat(r.response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*")
    }
}

