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
        private val forgejo: GenericContainer<*> = GenericContainer(DockerImageName.parse("codeberg.org/forgejo/forgejo:11-rootless"))
            .withEnv(mapOf("FORGEJO__database__DB_TYPE" to "sqlite3", "FORGEJO__security__INSTALL_LOCK" to "true", "FORGEJO__server__HTTP_PORT" to "3000",
                "FORGEJO__server__DISABLE_SSH" to "true", "FORGEJO__service__DISABLE_REGISTRATION" to "true", "FORGEJO__repository__DEFAULT_BRANCH" to "main",
                "FORGEJO__actions__ENABLED" to "false", "FORGEJO__security__SECRET_KEY" to "test-secret-key-0123456789",
                "FORGEJO__security__INTERNAL_TOKEN" to "eyJhbGciOiJIUzI1NiJ9.test-internal-token-0123456789"))
            .withExposedPorts(3000).waitingFor(Wait.forHttp("/api/healthz").forPort(3000).withStartupTimeout(Duration.ofMinutes(2)))
            .apply { start() }
        private val token: String by lazy {
            fun fx(vararg a: String) = forgejo.execInContainer("forgejo", *a).also { check(it.exitCode == 0) { it.stderr } }.stdout.trim()
            fx("admin", "user", "create", "--username", "factory-bot", "--password", "bot-password-123456", "--email", "bot@test.local", "--must-change-password=false")
            val t = fx("admin", "user", "generate-access-token", "--username", "factory-bot", "--token-name", "t", "--scopes", "write:repository,write:organization,read:user", "--raw")
            val url = "http://${forgejo.host}:${forgejo.getMappedPort(3000)}/api/v1/orgs"
            java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI(url)).header("Authorization", "token $t")
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"username":"factory","visibility":"private"}""")).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding())
            t
        }
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
            registry.add("app.git.url") { "http://${forgejo.host}:${forgejo.getMappedPort(3000)}" }
            registry.add("app.git.token") { token }
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
        assertThat(sc.s.post("$base/publish", """{"visibility":"PRIVATE","expectedRevision":$rev}""", "Idempotency-Key" to "code-priv-1").response.status).isEqualTo(400)
        val dep = sc.s.body(sc.s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":$rev}""", "Idempotency-Key" to "code-pub-1")).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE deployment_id = ?::uuid", Long::class.java, dep)!! > 0 }
        var pj = claim()!!
        while (pj.get("purpose").asString() != "PUBLISH") { finish(pj.get("id").asString(), "FAILED", "other"); pj = claim()!! }
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
        assertThat(diff.get("path").asString()).isEqualTo("src/App.tsx"); assertThat(diff.get("after").asString()).contains("<h1>Xin chào AI</h1>")
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
}

