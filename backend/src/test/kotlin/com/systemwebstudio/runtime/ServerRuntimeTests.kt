package com.systemwebstudio.runtime

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.StaticSiteBuilder
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage J (ADR 0017/0018): per-app database isolation, write-only secrets, blue/green deployments with failure and rollback, the gateway
 * passing only declared routes without cookies, runner endpoints behind their token, connector proxy refusals. The apps gateway is a local stub.
 */
class ServerRuntimeTests : IntegrationTestBase() {
    companion object {
        const val GW_TOKEN = "gateway-test-token-0123456789abcdef0123"
        const val RUNNER = "runner-test-token-123456"
        class Seen(val path: String, val method: String, val token: String?, val cookie: String?, val user: String?, val body: String)
        val seen = CopyOnWriteArrayList<Seen>()
        val gateway: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                seen += Seen(ex.requestURI.toString(), ex.requestMethod, ex.requestHeaders.getFirst("X-Gateway-Token"), ex.requestHeaders.getFirst("Cookie"),
                    ex.requestHeaders.getFirst("X-Factory-User"), body)
                val out = """{"ok":true,"path":"${ex.requestURI.path}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun runtime(r: DynamicPropertyRegistry) {
            r.add("app.runtime.enabled") { "true" }
            r.add("app.secrets.master-key") { Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) }
            r.add("app.runtime.appdb-url") { postgres.jdbcUrl }
            r.add("app.runtime.appdb-admin-user") { postgres.username }
            r.add("app.runtime.appdb-admin-password") { postgres.password }
            r.add("app.runtime.gateway-url") { "http://127.0.0.1:${gateway.address.port}" }
            r.add("app.runtime.gateway-token") { GW_TOKEN }
            r.add("app.build.runner-token") { RUNNER }
        }
    }

    @Autowired lateinit var runtime: ServerRuntimeService
    @Autowired lateinit var appDb: AppDbProvisioner
    @Autowired lateinit var store: ArtifactStore
    @Autowired lateinit var crypto: SecretsCrypto
    @BeforeEach fun reset() { seen.clear() }

    /** a code project of kind SERVER_APP (the repository is not needed for these runtime tests) */
    private fun serverApp(): Scenario {
        val sc = scenario()
        jdbc.update("UPDATE projects SET app_type = 'STATIC_APP', app_kind = 'SERVER_APP' WHERE id = ?", sc.projectId)
        runtime.provision(sc.projectId)
        return sc
    }

    /** an artifact with a server bundle and its declared routes (what the build sandbox would produce) */
    private fun artifact(projectId: UUID, routes: String = """{"/api/items":{"get":{},"post":{}},"/api/items/{id}":{"delete":{}}}"""): UUID {
        val files = mapOf("index.html" to "<!doctype html><title>x</title>", "server/server.cjs" to "require('http')", "server/openapi.json" to """{"openapi":"3.1.0","paths":$routes}""")
        val id = UUID.randomUUID(); val prefix = "$projectId/${id.toString().replace("-", "")}"
        val manifest = files.map { (p, c) -> store.putOnce("$prefix/$p", c.toByteArray(), "text/plain"); mapOf("path" to p, "size" to c.length, "sha256" to StaticSiteBuilder.sha256(c.toByteArray()), "contentType" to "text/plain") }
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest, kind) VALUES (?,?,?,?,?,?,CAST(? AS jsonb), 'STATIC_APP')",
            id, projectId, StaticSiteBuilder.sha256(id.toString().toByteArray()), prefix, files.size, 100, json.writeValueAsString(manifest))
        return id
    }

    private fun runner(method: String, path: String, body: String? = null, token: String? = RUNNER) = mvc.perform(
        (if (method == "GET") B.get(path) else B.post(path).contentType(MediaType.APPLICATION_JSON).content(body ?: "{}")).also { b -> token?.let { b.header("X-Runner-Token", it) } }).andReturn()

    @Test
    fun `per-app database - own database and role, the role can connect only to its own database`() {
        val a = serverApp(); val b = serverApp()
        val ra = runtime.dbName(a.projectId); val rb = runtime.dbName(b.projectId)
        assertThat(appDb.connectableDatabases(ra)).containsExactly(ra)
        assertThat(appDb.connectableDatabases(rb)).containsExactly(rb)
        val enc = jdbc.queryForObject("SELECT db_password_enc FROM app_runtimes WHERE project_id = ?", String::class.java, a.projectId)
        assertThat(enc).startsWith("v1:")
        // the Studio API never shows the password, only the database name
        val st = a.s.body(a.s.get("${a.base}/runtime"))
        assertThat(st.get("database").asString()).isEqualTo(ra); assertThat(st.toString()).doesNotContain("password").doesNotContain("postgresql://")
    }

    @Test
    fun `secrets are write-only, encrypted at rest, reserved names refused, delivered only to the runner`() {
        val sc = serverApp()
        assertThat(sc.s.put("${sc.base}/runtime/secrets", """{"name":"PAYMENT_KEY","value":"pk_live_very_secret_value"}""").response.status).isEqualTo(200)
        assertThat(sc.s.put("${sc.base}/runtime/secrets", """{"name":"DATABASE_URL","value":"x"}""").response.status).isEqualTo(400)
        assertThat(sc.s.put("${sc.base}/runtime/secrets", """{"name":"bad name","value":"x"}""").response.status).isEqualTo(400)
        val st = sc.s.get("${sc.base}/runtime").response.contentAsString
        assertThat(st).contains("PAYMENT_KEY").doesNotContain("pk_live_very_secret_value")
        assertThat(jdbc.queryForObject("SELECT value_enc FROM project_secrets WHERE project_id = ?", String::class.java, sc.projectId)).doesNotContain("pk_live")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE new_value::text LIKE '%pk_live%'", Long::class.java)).isEqualTo(0)
        runtime.deploy(sc.projectId, artifact(sc.projectId), "abc", sc.user.id)
        assertThat(runner("GET", "/internal/runtime/desired", token = null).response.status).isEqualTo(404)
        val desired = json.readTree(runner("GET", "/internal/runtime/desired").response.contentAsString).toList().first { it.get("projectId").asString() == sc.projectId.toString() }
        val env = desired.get("containers")[0].get("env")
        assertThat(env.get("PAYMENT_KEY").asString()).isEqualTo("pk_live_very_secret_value")
        assertThat(env.get("DATABASE_URL").asString()).startsWith("postgresql://${runtime.dbName(sc.projectId)}:").endsWith("@appdb:5432/${runtime.dbName(sc.projectId)}")
        assertThat(env.get("CONNECTOR_URL").asString()).isEqualTo("http://apps-gateway:8081/_connectors")
        // a viewer cannot set secrets
        val viewer = fx.user("rtviewer"); fx.member(sc.ws, viewer, "VIEWER")
        jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role) VALUES (?,?,?, 'VIEWER')", sc.ws, sc.projectId, viewer.id)
        assertThat(sessionFor(viewer.username).put("${sc.base}/runtime/secrets", """{"name":"X_KEY","value":"y"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `blue-green - the new version serves only after its health check, a failure keeps the old one, rollback restores an earlier build`() {
        val sc = serverApp()
        val v1 = runtime.deploy(sc.projectId, artifact(sc.projectId), "c1", sc.user.id)
        assertThat(runner("POST", "/internal/runtime/$v1/report", """{"state":"RUNNING","logs":"listening DATABASE_URL=postgresql://u:pw@appdb/x token=abc"}""").response.status).isEqualTo(200)
        var st = sc.s.body(sc.s.get("${sc.base}/runtime"))
        assertThat(st.get("currentDeploymentId").asString()).isEqualTo(v1.toString())
        assertThat(st.get("logs").asString()).doesNotContain("pw@").doesNotContain("token=abc")               // logs are redacted
        val v2 = runtime.deploy(sc.projectId, artifact(sc.projectId), "c2", sc.user.id)
        val both = json.readTree(runner("GET", "/internal/runtime/desired").response.contentAsString).toList().first { it.get("projectId").asString() == sc.projectId.toString() }
        assertThat(both.get("containers").toList().map { it.get("role").asString() }).containsExactlyInAnyOrder("current", "desired")   // old keeps serving while new starts
        runner("POST", "/internal/runtime/$v2/report", """{"state":"FAILED","error":"health check did not pass"}""")
        st = sc.s.body(sc.s.get("${sc.base}/runtime"))
        assertThat(st.get("currentDeploymentId").asString()).isEqualTo(v1.toString())
        assertThat(st.get("deployments").toList().first { it.get("id").asString() == v2.toString() }.get("status").asString()).isEqualTo("FAILED")
        val v3 = runtime.deploy(sc.projectId, artifact(sc.projectId), "c3", sc.user.id)
        runner("POST", "/internal/runtime/$v3/report", """{"state":"RUNNING"}""")
        // roll back to v1 (no rebuild: same artifact), v3 becomes superseded once v1 is healthy again
        assertThat(sc.s.post("${sc.base}/runtime/rollback", """{"deploymentId":"$v2"}""").response.status).isEqualTo(409)   // v2 never ran
        val rb = sc.s.body(sc.s.post("${sc.base}/runtime/rollback", """{"deploymentId":"$v1"}""")).get("desiredDeploymentId").asString()
        runner("POST", "/internal/runtime/$rb/report", """{"state":"RUNNING"}""")
        st = sc.s.body(sc.s.get("${sc.base}/runtime"))
        val deps = st.get("deployments").toList().associateBy { it.get("id").asString() }
        assertThat(st.get("currentDeploymentId").asString()).isEqualTo(rb)
        assertThat(deps[rb]!!.get("rollbackOf").asString()).isEqualTo(v1.toString()); assertThat(deps[v3.toString()]!!.get("status").asString()).isEqualTo("SUPERSEDED")
        assertThat(sc.s.post("${sc.base}/runtime/stop").response.status).isEqualTo(200)
        assertThat(json.readTree(runner("GET", "/internal/runtime/desired").response.contentAsString).toList().none { it.get("projectId").asString() == sc.projectId.toString() }).isTrue()
    }

    @Test
    fun `gateway passes only declared routes, strips cookies, refuses when the app is not running, hides the server bundle`() {
        val sc = serverApp()
        val art = artifact(sc.projectId)
        val slug = "srv-${UUID.randomUUID().toString().take(8)}"
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val dep = UUID.randomUUID()
        jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?, 'PUBLIC', 'RUNNING', 'static', ?)""",
            dep, sc.ws, sc.projectId, version, sc.user.id, art)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?,?,?)", sc.projectId, slug, dep)
        val v = session()
        assertThat(v.get("/sites/$slug/api/items").response.status).isEqualTo(503)                           // nothing running yet
        val d = runtime.deploy(sc.projectId, art, "c", sc.user.id); runner("POST", "/internal/runtime/$d/report", """{"state":"RUNNING"}""")
        val ok = v.perform(B.get("/sites/$slug/api/items").cookie(jakarta.servlet.http.Cookie("site_session", "abc")), includeCsrf = false)
        assertThat(ok.response.status).isEqualTo(200); assertThat(ok.response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*")
        val call = seen.last()
        assertThat(call.path).isEqualTo("/${runtime.containerName(sc.projectId, d)}/api/items"); assertThat(call.token).isEqualTo(GW_TOKEN); assertThat(call.cookie).isNull()
        val post = v.perform(B.post("/sites/$slug/api/items").contentType(MediaType.APPLICATION_JSON).content("""{"title":"x"}"""), includeCsrf = false)
        assertThat(post.response.status).isEqualTo(200); assertThat(seen.last().body).isEqualTo("""{"title":"x"}""")
        assertThat(v.perform(B.delete("/sites/$slug/api/items/7"), includeCsrf = false).response.status).isEqualTo(200)
        assertThat(v.perform(B.put("/sites/$slug/api/items/7").contentType(MediaType.APPLICATION_JSON).content("{}"), includeCsrf = false).response.status).isEqualTo(404)  // not declared
        assertThat(v.get("/sites/$slug/api/admin/secrets").response.status).isEqualTo(404)
        // a real browser preflight from the sandboxed (opaque-origin) app document
        val pre = v.perform(B.options("/sites/$slug/api/items").header("Origin", "null").header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"), includeCsrf = false)
        assertThat(pre.response.status).isIn(200, 204); assertThat(pre.response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(pre.response.getHeader("Access-Control-Allow-Methods")).contains("POST")
        assertThat(v.get("/sites/$slug/server/server.cjs").response.status).isEqualTo(404)                 // the bundle is never a file
        assertThat(v.get("/sites/$slug/openapi.json").response.status).isEqualTo(404)
        // a gateway request without the API's token is refused by the real apps gateway (nginx config); here the stub only records it
        assertThat(seen.all { it.token == GW_TOKEN }).isTrue()
    }

    @Test
    fun `connector proxy - needs the gateway token, a valid app token, a grant and a declared operation`() {
        val sc = serverApp()
        val admin = sessionFor(fx.user("connadm", systemAdmin = true).username)
        val key = "crm-${UUID.randomUUID().toString().take(6)}"
        assertThat(admin.put("/api/v1/admin/connectors", """{"key":"$key","name":"CRM","baseUrl":"http://crm.example.com"}""").response.status).isEqualTo(400)      // https only
        assertThat(admin.put("/api/v1/admin/connectors", """{"key":"$key","name":"CRM","baseUrl":"https://localhost"}""").response.status).isEqualTo(400)
        val saved = admin.body(admin.put("/api/v1/admin/connectors", """{"key":"$key","name":"CRM","baseUrl":"https://crm.example.com/v1","authHeader":"Authorization",
            "authValue":"Bearer crm-secret-123","operations":[{"method":"GET","path":"/contacts"}]}"""))
        val dto = saved.toList().first { it.get("key").asString() == key }
        assertThat(dto.get("hasSecret").asBoolean()).isTrue(); assertThat(saved.toString()).doesNotContain("crm-secret-123")
        val appToken = crypto.decrypt(jdbc.queryForObject("SELECT app_token_enc FROM app_runtimes WHERE project_id = ?", String::class.java, sc.projectId)!!)
        fun call(path: String, gw: String? = GW_TOKEN, app: String? = appToken, method: String = "GET") = mvc.perform(
            (if (method == "GET") B.get(path) else B.post(path)).also { b -> gw?.let { b.header("X-Gateway-Token", it) }; app?.let { b.header("X-App-Token", it) } }).andReturn()
        assertThat(call("/internal/connectors/$key/contacts", gw = null).response.status).isEqualTo(404)
        assertThat(call("/internal/connectors/$key/contacts", app = "wrong").response.status).isEqualTo(401)
        assertThat(call("/internal/connectors/$key/contacts").response.status).isEqualTo(403)                // not granted
        assertThat(admin.post("/api/v1/admin/connectors/$key/grants", """{"projectId":"${sc.projectId}"}""").response.status).isEqualTo(200)
        assertThat(call("/internal/connectors/$key/contacts", method = "POST").response.status).isEqualTo(403)   // operation not declared
        assertThat(call("/internal/connectors/$key/../admin").response.status).isIn(400, 403, 404)
        val search = jdbc.queryForObject("SELECT count(*) FROM connectors WHERE key = ? AND auth_value_enc NOT LIKE '%crm-secret%'", Long::class.java, key)
        assertThat(search).isEqualTo(1)
    }
}
