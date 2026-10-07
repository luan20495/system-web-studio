package com.systemwebstudio.wiring

import com.systemwebstudio.data.FakeDataConnector
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.tenancy.TenantService
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * B-C0-W-03 · the Data Source Management API over real HTTP (MockMvc + the real Spring context, Flyway/V28 on a real PostgreSQL, real Redis, real C1 access
 * checks and audit): create / list / get / update / delete, credential configure / replace / remove with metadata-only answers, test connection,
 * TEST / LIVE bindings, tenant and workspace isolation, permission denial per the C1 contract, secret leakage.
 *
 * The connector here is the writable test connector (`FakeDataConnector`, type `fake`): this class is about the API and its isolation, not about a database.
 * The real PostgreSQL connector behind the same API is DataWritableE2ETests.
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class DataManagementApiTests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg {
        @Bean fun fakeDataConnector() = FakeDataConnector()
    }

    @Autowired lateinit var connector: FakeDataConnector
    @Autowired lateinit var repository: DataSourceRepository
    @Autowired lateinit var tenants: TenantService

    private val secret = "pw-MGMT-SECRET-5b1f"
    private fun uniq(p: String) = p + "-" + UUID.randomUUID().toString().take(8)

    @BeforeEach fun reset() { connector.credentialsSeen.clear() }

    // ------------------------------------------------------------------------------------------------ fixtures

    /** an administrator of one workspace; the workspace lives in its own tenant, so the per-tenant management rate limit of one test never touches another */
    private class W(val tenant: UUID, val ws: UUID, val user: UserEntity, val s: ApiSession) { val base = "/api/v1/workspaces/$ws/data-sources" }

    private fun newTenant(): UUID = tenants.create(uniq("dsm"), "T").id
    private fun workspaceIn(tenant: UUID): UUID = fx.workspace().also { jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", tenant, it) }
    private fun member(ws: UUID, role: String): Pair<UserEntity, ApiSession> = fx.user("dsm").let { u -> fx.member(ws, u, role); u to sessionFor(u.username) }
    private fun admin(tenant: UUID = newTenant(), ws: UUID = workspaceIn(tenant)): W = member(ws, "WORKSPACE_ADMIN").let { (u, s) -> W(tenant, ws, u, s) }

    private fun W.create(name: String = uniq("src"), type: String = "fake", config: Map<String, Any> = mapOf("host" to "api.example.com"), credential: Map<String, String>? = null): MvcResult =
        s.post(base, json.writeValueAsString(buildMap<String, Any> { put("name", name); put("type", type); put("config", config); if (credential != null) put("credential", credential) }))
    private fun W.created(credential: Map<String, String>? = null): String = s.body(create(credential = credential).also { assertThat(it.response.status).describedAs(it.response.contentAsString).isEqualTo(201) }).get("id").asString()
    private fun status(r: MvcResult) = r.response.status
    private fun code(s: ApiSession, r: MvcResult) = s.body(r).get("code")?.asString()
    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private fun assertNoSecretAnywhere(secretValue: String, vararg responses: MvcResult) {
        for (r in responses) assertThat(r.response.contentAsString).doesNotContain(secretValue)
        assertThat(count("SELECT count(*) FROM audit_events WHERE new_value::text LIKE ?", "%$secretValue%")).describedAs("audit events").isZero()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE ciphertext LIKE ?", "%$secretValue%")).describedAs("credential rows hold ciphertext only").isZero()
        assertThat(count("SELECT count(*) FROM data_sources t WHERE t::text LIKE ?", "%$secretValue%")).describedAs("data source rows").isZero()
        assertThat(count("SELECT count(*) FROM data_idempotency t WHERE t::text LIKE ?", "%$secretValue%")).describedAs("idempotency rows").isZero()
    }

    // ------------------------------------------------------------------------------------------------ CRUD

    @Test
    fun `create, list, get and the safe shape of a data source`() {
        val w = admin()
        val r = w.create(name = "billing", config = mapOf("host" to "api.example.com", "port" to 8443, "writable" to false))
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(201)
        val b = w.s.body(r)
        assertThat(b.get("name").asString()).isEqualTo("billing"); assertThat(b.get("type").asString()).isEqualTo("fake")
        assertThat(b.get("workspaceId").asString()).isEqualTo(w.ws.toString())
        assertThat(b.get("status").asString()).isEqualTo("ACTIVE"); assertThat(b.get("version").asLong()).isEqualTo(1L)
        assertThat(b.get("hasCredential").asBoolean()).isFalse(); assertThat(b.get("createdBy").asString()).isEqualTo(w.user.id.toString())
        assertThat(b.get("config").get("port").asString()).isEqualTo("8443")                              // whole numbers and booleans are kept as plain text
        assertThat(r.response.contentAsString).doesNotContain(w.tenant.toString()).doesNotContain("credentialRef").doesNotContain("tenantId")
        val id = b.get("id").asString()

        val list = w.s.get(w.base)
        assertThat(status(list)).isEqualTo(200)
        assertThat(DataJson.elements(w.s.body(list).get("items")).map { it.get("id").asString() }).containsExactly(id)
        val one = w.s.get("${w.base}/$id")
        assertThat(status(one)).isEqualTo(200); assertThat(w.s.body(one).get("name").asString()).isEqualTo("billing")

        val unknown = w.s.get("${w.base}/${UUID.randomUUID()}")
        assertThat(status(unknown)).isEqualTo(404); assertThat(code(w.s, unknown)).isEqualTo("NOT_FOUND")
        assertThat(status(w.s.get("${w.base}/not-a-uuid"))).isEqualTo(404)
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'DATASOURCE_CREATED' AND resource_id = ?", id)).isEqualTo(1L)
    }

    @Test
    fun `update changes name, config and status and each change is a new version`() {
        val w = admin(); val id = w.created()
        val renamed = w.s.patch("${w.base}/$id", """{"name":"renamed"}""")
        assertThat(status(renamed)).describedAs(renamed.response.contentAsString).isEqualTo(200)
        assertThat(w.s.body(renamed).get("name").asString()).isEqualTo("renamed"); assertThat(w.s.body(renamed).get("version").asLong()).isEqualTo(2L)
        val config = w.s.patch("${w.base}/$id", """{"config":{"host":"other.example.com"}}""")
        assertThat(w.s.body(config).get("config").get("host").asString()).isEqualTo("other.example.com"); assertThat(w.s.body(config).get("version").asLong()).isEqualTo(3L)
        val off = w.s.patch("${w.base}/$id", """{"status":"DISABLED"}""")
        assertThat(w.s.body(off).get("status").asString()).isEqualTo("DISABLED")
        assertThat(w.s.body(w.s.get("${w.base}/$id")).get("status").asString()).isEqualTo("DISABLED")      // a disabled source can still be read and managed ...
        assertThat(status(w.s.post("${w.base}/$id/test", "{}"))).isEqualTo(409)                           // ... but is not used
        val on = w.s.patch("${w.base}/$id", """{"status":"ACTIVE"}""")
        assertThat(w.s.body(on).get("status").asString()).isEqualTo("ACTIVE")
        for (bad in listOf("{}", """{"nope":1}""", """{"status":"DELETED"}""", """{"tenantId":"${w.tenant}"}""", """{"name":""}""", """{"config":{"password":"x"}}""", "[]"))
            assertThat(status(w.s.patch("${w.base}/$id", bad))).describedAs(bad).isEqualTo(400)
        assertThat(count("SELECT version FROM data_sources WHERE id = ?", UUID.fromString(id))).isEqualTo(5L)  // refused requests changed nothing
    }

    @Test
    fun `creation is validated - strict body, unique name, supported type, no secrets in the configuration`() {
        val w = admin(); w.created().let { }
        val dup = uniq("dup"); assertThat(status(w.create(name = dup))).isEqualTo(201)
        val conflict = w.create(name = dup.uppercase()); assertThat(status(conflict)).isEqualTo(409); assertThat(code(w.s, conflict)).isEqualTo("CONFLICT")
        assertThat(status(w.create(type = "nope"))).isEqualTo(422)                                         // UNSUPPORTED_TYPE
        assertThat(status(w.create(type = "mysql", config = mapOf("host" to "db.example.com")))).isEqualTo(501)   // NOT_IMPLEMENTED: a planned connector
        assertThat(status(w.create(config = mapOf("password" to "x")))).isEqualTo(400)                       // INVALID_CONFIG
        assertThat(status(w.create(name = "bad;name"))).isEqualTo(400)
        for (extra in listOf("tenantId" to w.tenant.toString(), "workspaceId" to UUID.randomUUID().toString(), "credentialRef" to "v1:x", "status" to "DISABLED", "id" to UUID.randomUUID().toString(), "createdBy" to "x"))
            assertThat(status(w.s.post(w.base, """{"name":"${uniq("x")}","type":"fake","config":{},"${extra.first}":"${extra.second}"}"""))).describedAs(extra.first).isEqualTo(400)
        assertThat(status(w.s.post(w.base, "[]"))).isEqualTo(400)
        assertThat(status(w.s.post(w.base, ""))).isIn(400, 415)
        assertThat(count("SELECT count(*) FROM data_sources WHERE tenant_id = ?", w.tenant)).isEqualTo(2L)
    }

    @Test
    fun `delete removes the data source with its operations and its credential`() {
        val w = admin(); val id = w.created(credential = mapOf("authValue" to secret)); val uuid = UUID.fromString(id)
        JdbcQueryCatalog(jdbc).save(SqlQueryDefinition("q.list", w.tenant, uuid, "SELECT 1", emptyList(), 10))
        JdbcMutationCatalog(jdbc).save(MutationDefinition("m.create", w.tenant, uuid, MutationKind.CREATE, "t", listOf(QueryParamSpec("a", ParamType.STRING))))
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).isEqualTo(1L)
        val del = w.s.delete("${w.base}/$id")
        assertThat(status(del)).describedAs(del.response.contentAsString).isEqualTo(204)
        assertThat(status(w.s.get("${w.base}/$id"))).isEqualTo(404)
        assertThat(status(w.s.delete("${w.base}/$id"))).isEqualTo(404)
        for (table in listOf("data_sources", "data_queries", "data_mutations", "source_schemas", "data_idempotency"))
            assertThat(count("SELECT count(*) FROM $table WHERE tenant_id = ? AND ${if (table == "data_sources") "id" else "data_source_id"} = ?", w.tenant, uuid)).describedAs(table).isZero()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).describedAs("the credential is destroyed with its source").isZero()
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'DATASOURCE_DELETED' AND resource_id = ?", id)).isEqualTo(1L)
        assertThat(DataJson.elements(w.s.body(w.s.get(w.base)).get("items"))).isEmpty()
        assertNoSecretAnywhere(secret, del)
    }

    // ------------------------------------------------------------------------------------------------ credential

    @Test
    fun `a credential is write only - the answers carry metadata and never a secret`() {
        val w = admin()
        val r = w.create(credential = mapOf("authValue" to secret))
        assertThat(status(r)).isEqualTo(201); assertThat(w.s.body(r).get("hasCredential").asBoolean()).isTrue()
        val id = w.s.body(r).get("id").asString()
        val meta = w.s.get("${w.base}/$id/credential")
        assertThat(status(meta)).isEqualTo(200)
        val m = w.s.body(meta)
        assertThat(DataJson.keys(m)).containsExactlyInAnyOrder("configured", "type", "keys", "updatedAt", "updatedBy")
        assertThat(m.get("configured").asBoolean()).isTrue(); assertThat(m.get("type").asString()).isEqualTo("fake")
        assertThat(Instant.parse(m.get("updatedAt").asString())).isBeforeOrEqualTo(Instant.now())
        assertThat(m.get("updatedBy").asString()).describedAs("the user who configured it, from the audit trail").isEqualTo(w.user.id.toString())
        val stored = jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ?", String::class.java, w.tenant)!!
        assertThat(stored).startsWith("v1:").doesNotContain(secret)
        val everything = listOf(r, meta, w.s.get(w.base), w.s.get("${w.base}/$id"), w.s.get("${w.base}/connectors"))
        assertNoSecretAnywhere(secret, *everything.toTypedArray())
        // the stored secret is what the connector gets (so "configured" is real), while no API answer can return it
        assertThat(status(w.s.post("${w.base}/$id/test", "{}"))).isEqualTo(200)
        assertThat(connector.credentialsSeen.last().require("authValue")).isEqualTo(secret)
    }

    @Test
    fun `configure, replace and remove a credential`() {
        val w = admin(); val id = w.created()
        assertThat(w.s.body(w.s.get("${w.base}/$id/credential")).get("configured").asBoolean()).isFalse()
        val put = w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"$secret"}}""")
        assertThat(status(put)).describedAs(put.response.contentAsString).isEqualTo(200)
        assertThat(w.s.body(put).get("configured").asBoolean()).isTrue()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).isEqualTo(1L)
        val first = jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ?", String::class.java, w.tenant)!!
        val replace = w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"second-$secret"}}""")
        assertThat(status(replace)).isEqualTo(200)
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).describedAs("the old credential is destroyed, not accumulated").isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ?", String::class.java, w.tenant)).isNotEqualTo(first)
        assertThat(status(w.s.post("${w.base}/$id/test", "{}"))).isEqualTo(200)
        assertThat(connector.credentialsSeen.last().require("authValue")).isEqualTo("second-$secret")
        for (bad in listOf("{}", """{"credential":{}}""", """{"credential":"x"}""", """{"credential":{"a":1}}""", """{"credential":{"a":"b"},"extra":1}""", """{"authValue":"x"}"""))
            assertThat(status(w.s.put("${w.base}/$id/credential", bad))).describedAs(bad).isEqualTo(400)
        assertThat(connector.credentialsSeen.size).describedAs("the refused bodies never reached the connector: only the one test connection did").isEqualTo(1)
        val del = w.s.delete("${w.base}/$id/credential")
        assertThat(status(del)).isEqualTo(204)
        assertThat(w.s.body(w.s.get("${w.base}/$id/credential")).get("configured").asBoolean()).isFalse()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).isZero()
        assertThat(w.s.body(w.s.get("${w.base}/$id")).get("hasCredential").asBoolean()).isFalse()
        assertThat(status(w.s.delete("${w.base}/$id/credential"))).describedAs("removing nothing is not an error").isEqualTo(204)
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN ('DATASOURCE_CREDENTIAL_ROTATED','DATASOURCE_CREDENTIAL_REMOVED')", id)).isEqualTo(3L)
        assertNoSecretAnywhere(secret, put, replace, del)
        assertNoSecretAnywhere("second-$secret", put, replace, del)
    }

    // ------------------------------------------------------------------------------------------------ test connection

    @Test
    fun `test connection runs the connector with the stored credential and answers ok with a latency`() {
        val w = admin(); val id = w.created(credential = mapOf("authValue" to secret))
        val r = w.s.post("${w.base}/$id/test", "{}")
        assertThat(status(r)).isEqualTo(200)
        val b = w.s.body(r)
        assertThat(b.get("ok").asBoolean()).isTrue(); assertThat(b.get("latencyMs").isNumber).isTrue()
        assertThat(connector.credentialsSeen).hasSize(1)
        assertNoSecretAnywhere(secret, r)
        assertThat(status(w.s.post("${w.base}/${UUID.randomUUID()}/test", "{}"))).isEqualTo(404)
    }

    // ------------------------------------------------------------------------------------------------ isolation: workspace and tenant

    private fun sameTenantTwoWorkspaces(): Pair<W, W> {
        val tenant = newTenant()
        return admin(tenant) to admin(tenant)
    }

    @Test
    fun `workspace B of the same tenant cannot see or change what workspace A registered, and cannot tell it exists`() {
        val (a, b) = sameTenantTwoWorkspaces()
        val id = a.created(credential = mapOf("authValue" to secret))
        val missing = b.s.get("${b.base}/${UUID.randomUUID()}")
        assertThat(DataJson.elements(b.s.body(b.s.get(b.base)).get("items"))).isEmpty()                                // the list of B is empty
        val attempts = listOf(
            b.s.get("${b.base}/$id"), b.s.patch("${b.base}/$id", """{"name":"hijacked"}"""), b.s.patch("${b.base}/$id", """{"status":"DISABLED"}"""), b.s.delete("${b.base}/$id"),
            b.s.get("${b.base}/$id/credential"), b.s.put("${b.base}/$id/credential", """{"credential":{"authValue":"attacker"}}"""), b.s.delete("${b.base}/$id/credential"),
            b.s.post("${b.base}/$id/test", "{}"))
        for (r in attempts) {
            assertThat(status(r)).describedAs(r.request.requestURI + " " + r.request.method).isEqualTo(404)
            assertThat(code(b.s, r)).isEqualTo(code(b.s, missing)); assertThat(b.s.body(r).get("message").asString()).isEqualTo(b.s.body(missing).get("message").asString())
        }
        // path trickery: A's source id under B's workspace is the same 404; B's workspace id with A's path is a 404 for a non-member
        assertThat(status(b.s.get("${a.base}/$id"))).isEqualTo(404)
        assertThat(status(b.s.delete("${a.base}/$id"))).isEqualTo(404)
        // nothing changed
        val row = jdbc.queryForMap("SELECT name, status, version, credential_ref FROM data_sources WHERE id = ?", UUID.fromString(id))
        assertThat(row["status"]).isEqualTo("ACTIVE"); assertThat(row["version"]).isEqualTo(1L); assertThat(row["name"]).isNotEqualTo("hijacked"); assertThat(row["credential_ref"]).isNotNull()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", a.tenant)).isEqualTo(1L)
        assertThat(connector.credentialsSeen).isEmpty()                                                       // B's test connection never reached the connector
        assertThat(a.s.body(a.s.get("${a.base}/$id")).get("name").asString()).isNotEqualTo("hijacked")
        assertNoSecretAnywhere(secret, *attempts.toTypedArray())
    }

    @Test
    fun `another tenant cannot read, update or delete it either`() {
        val a = admin()
        val c = admin()                                                                                      // its own tenant, its own workspace
        val id = a.created()
        val missing = c.s.get("${c.base}/${UUID.randomUUID()}")
        for (r in listOf(c.s.get("${c.base}/$id"), c.s.patch("${c.base}/$id", """{"name":"x"}"""), c.s.delete("${c.base}/$id"), c.s.put("${c.base}/$id/credential", """{"credential":{"authValue":"x"}}"""), c.s.post("${c.base}/$id/test", "{}"))) {
            assertThat(status(r)).isEqualTo(404); assertThat(code(c.s, r)).isEqualTo(code(c.s, missing))
        }
        // A's workspace through C's session: C is not a member, so the workspace itself does not exist for C
        for (r in listOf(c.s.get(a.base), c.s.get("${a.base}/$id"), c.s.delete("${a.base}/$id"), c.s.post(a.base, """{"name":"x","type":"fake","config":{}}"""))) assertThat(status(r)).isEqualTo(404)
        assertThat(count("SELECT count(*) FROM data_sources WHERE id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(DataJson.elements(c.s.body(c.s.get(c.base)).get("items"))).isEmpty()
        assertThat(count("SELECT count(*) FROM data_sources WHERE workspace_id = ?", c.ws)).isZero()          // nothing was created in C's workspace by those calls
    }

    @Test
    fun `the tenant and workspace of a new data source come from the path, never from the body`() {
        val (a, b) = sameTenantTwoWorkspaces()
        val forged = a.s.post(a.base, """{"name":"${uniq("x")}","type":"fake","config":{},"workspaceId":"${b.ws}"}""")
        assertThat(status(forged)).isEqualTo(400)
        val id = a.created()
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM data_sources WHERE id = ?", UUID::class.java, UUID.fromString(id))).isEqualTo(a.ws)
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM data_sources WHERE id = ?", UUID::class.java, UUID.fromString(id))).isEqualTo(a.tenant)
    }

    // ------------------------------------------------------------------------------------------------ permissions (C1 contract, unchanged)

    @Test
    fun `without DATA_SOURCE_MANAGE or VIEW every route is a 403 PERMISSION_DENIED, strangers get 404 and anonymous callers are refused`() {
        val a = admin(); val id = a.created(credential = mapOf("authValue" to secret))
        val routes: List<(ApiSession) -> MvcResult> = listOf(
            { it.get(a.base) }, { it.get("${a.base}/connectors") }, { it.get("${a.base}/$id") }, { it.post(a.base, """{"name":"${uniq("x")}","type":"fake","config":{}}""") },
            { it.patch("${a.base}/$id", """{"name":"x"}""") }, { it.delete("${a.base}/$id") }, { it.get("${a.base}/$id/credential") },
            { it.put("${a.base}/$id/credential", """{"credential":{"authValue":"x"}}""") }, { it.delete("${a.base}/$id/credential") }, { it.post("${a.base}/$id/test", "{}") })
        for (role in listOf("EDITOR", "PUBLISHER", "VIEWER")) {                                              // workspace members without WORKSPACE_ADMIN hold no data-source permission at workspace level
            val (_, s) = member(a.ws, role)
            for (call in routes) { val r = call(s); assertThat(status(r)).describedAs("$role ${r.request.method} ${r.request.requestURI}").isEqualTo(403); assertThat(code(s, r)).isEqualTo("PERMISSION_DENIED") }
        }
        val stranger = sessionFor(fx.user("stranger").username)
        for (call in routes) { val r = call(stranger); assertThat(status(r)).describedAs("stranger ${r.request.method} ${r.request.requestURI}").isEqualTo(404) }
        for (call in routes) assertThat(status(call(session()))).describedAs("anonymous").isIn(401, 403)
        assertThat(count("SELECT count(*) FROM data_sources WHERE tenant_id = ?", a.tenant)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT version FROM data_sources WHERE id = ?", Long::class.java, UUID.fromString(id))).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", a.tenant)).isEqualTo(1L)
        assertThat(connector.credentialsSeen).isEmpty()
    }

    @Test
    fun `the connector catalogue lists available and planned types with key names only`() {
        val a = admin()
        val r = a.s.get("${a.base}/connectors")
        assertThat(status(r)).isEqualTo(200)
        val items = DataJson.elements(a.s.body(r).get("items"))
        val byType = items.associateBy { it.get("type").asString() }
        assertThat(byType.keys).contains("postgres", "rest", "fake", "mysql")
        assertThat(byType.getValue("postgres").get("status").asString()).isEqualTo("AVAILABLE")
        assertThat(DataJson.elements(byType.getValue("postgres").get("capabilities")).map { it.asString() }).contains("QUERY", "DISCOVERY", "MUTATION")
        assertThat(DataJson.elements(byType.getValue("postgres").get("credentialKeys")).map { it.asString() }).containsExactly("username", "password")
        assertThat(DataJson.elements(byType.getValue("postgres").get("configKeys")).map { it.get("name").asString() }).contains("host", "database", "writable")
        assertThat(byType.getValue("mysql").get("status").asString()).isEqualTo("PLANNED")
    }

    // ------------------------------------------------------------------------------------------------ bindings (TEST / LIVE)

    private class P(val sc: Scenario, val tenant: UUID, val admin: ApiSession, val adminUser: UserEntity) {
        val bindings = "${sc.base}/data-bindings"
    }

    /** one application (default tenant) with a WORKSPACE_ADMIN; data sources are written through the repository here (the API itself is covered above) */
    private fun app(): P {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val adminUser = fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        return P(sc, tenant, sessionFor(adminUser.username), adminUser)
    }

    private fun source(tenant: UUID, ws: UUID?): DataSource {
        val now = Instant.now()
        return DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), uniq("src"), workspaceId = ws, createdAt = now, updatedAt = now).also { repository.save(it) }
    }
    private fun bindingRows(p: P) = jdbc.queryForList("SELECT mode, slot_id, data_source_id FROM data_source_bindings WHERE project_id = ? ORDER BY mode, slot_id", p.sc.projectId)
    private fun put(p: P, mode: String, slot: String, ds: UUID) = p.admin.put("${p.bindings}/$mode/$slot", """{"dataSourceId":"$ds"}""")

    @Test
    fun `TEST and LIVE are bound, listed, re-pointed and removed independently`() {
        val p = app(); val live = source(p.tenant, p.sc.ws); val test = source(p.tenant, p.sc.ws); val other = source(p.tenant, p.sc.ws)
        val l = put(p, "LIVE", "erp-db", live.id)
        assertThat(status(l)).describedAs(l.response.contentAsString).isEqualTo(200)
        val lb = p.admin.body(l)
        assertThat(lb.get("mode").asString()).isEqualTo("LIVE"); assertThat(lb.get("slotId").asString()).isEqualTo("erp-db"); assertThat(lb.get("dataSourceId").asString()).isEqualTo(live.id.toString())
        assertThat(status(put(p, "test", "erp-db", test.id))).describedAs("the mode is exactly TEST or LIVE: no normalisation (contract §3.6)").isEqualTo(400)
        assertThat(status(put(p, "TEST", "erp-db", test.id))).isEqualTo(200)
        assertThat(bindingRows(p).map { it["mode"] to it["data_source_id"] }).containsExactlyInAnyOrder("LIVE" to live.id, "TEST" to test.id)

        val list = p.admin.get(p.bindings)
        assertThat(status(list)).isEqualTo(200)
        assertThat(DataJson.elements(p.admin.body(list).get("items")).map { it.get("mode").asString() + ":" + it.get("slotId").asString() + ":" + it.get("dataSourceId").asString() })
            .containsExactlyInAnyOrder("LIVE:erp-db:${live.id}", "TEST:erp-db:${test.id}")

        assertThat(status(put(p, "LIVE", "erp-db", other.id))).isEqualTo(200)                                   // re-point LIVE, TEST untouched
        assertThat(bindingRows(p).map { it["mode"] to it["data_source_id"] }).containsExactlyInAnyOrder("LIVE" to other.id, "TEST" to test.id)
        assertThat(status(p.admin.delete("${p.bindings}/TEST/erp-db"))).isEqualTo(204)
        assertThat(bindingRows(p).map { it["mode"] }).containsExactly("LIVE")
        assertThat(status(p.admin.delete("${p.bindings}/TEST/erp-db"))).isEqualTo(404)
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'DATASOURCE_BINDING_CHANGED' AND resource_id IN (?, ?)", test.id.toString(), live.id.toString())).isGreaterThanOrEqualTo(3L)
    }

    @Test
    fun `a binding refuses a bad mode, a bad slot, a bad body and any data source that is not this workspace's`() {
        val p = app(); val own = source(p.tenant, p.sc.ws)
        assertThat(status(put(p, "STAGING", "erp-db", own.id))).isEqualTo(400)
        assertThat(status(p.admin.put("${p.bindings}/LIVE/-bad", """{"dataSourceId":"${own.id}"}"""))).isEqualTo(400)
        assertThat(status(p.admin.put("${p.bindings}/LIVE/erp-db", """{"dataSourceId":"nope"}"""))).isEqualTo(400)
        assertThat(status(p.admin.put("${p.bindings}/LIVE/erp-db", """{"dataSourceId":"${own.id}","tenantId":"${p.tenant}"}"""))).isEqualTo(400)
        assertThat(status(p.admin.put("${p.bindings}/LIVE/erp-db", "{}"))).isEqualTo(400)

        val elsewhere = app()                                                                                    // another workspace, same tenant
        val foreignWorkspace = source(elsewhere.tenant, elsewhere.sc.ws)
        val tenantLevel = source(p.tenant, null)
        val otherTenant = newTenant(); val otherWs = workspaceIn(otherTenant)
        val foreignTenant = source(otherTenant, otherWs)
        val ghost = put(p, "LIVE", "erp-db", UUID.randomUUID())
        assertThat(status(ghost)).isEqualTo(404)
        for (ds in listOf(foreignWorkspace, tenantLevel, foreignTenant)) {
            val r = put(p, "LIVE", "erp-db", ds.id)
            assertThat(status(r)).describedAs("binding a source of another workspace/tenant").isEqualTo(404)
            assertThat(code(p.admin, r)).isEqualTo(code(p.admin, ghost)); assertThat(p.admin.body(r).get("message").asString()).isEqualTo(p.admin.body(ghost).get("message").asString())
        }
        assertThat(bindingRows(p)).isEmpty()
    }

    @Test
    fun `changing a binding needs DATA_SOURCE_MANAGE, reading needs DATA_SOURCE_VIEW, and other workspaces' projects do not exist`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws)
        assertThat(status(put(p, "LIVE", "erp-db", ds.id))).isEqualTo(200)
        // the application's own editor/owner (project OWNER, workspace EDITOR): may read the bindings, may not change them
        val owner = p.sc.s
        assertThat(status(owner.get(p.bindings))).isEqualTo(200)
        for (r in listOf(owner.put("${p.bindings}/LIVE/erp-db", """{"dataSourceId":"${ds.id}"}"""), owner.delete("${p.bindings}/LIVE/erp-db"))) {
            assertThat(status(r)).isEqualTo(403); assertThat(code(owner, r)).isEqualTo("PERMISSION_DENIED")
        }
        assertThat(bindingRows(p)).hasSize(1)
        // a project VIEWER may not even edit the project: the binding routes need PROJECT_EDIT first
        val viewer = fx.user("viewer").also { fx.member(p.sc.ws, it, "VIEWER"); fx.projectRole(fx.projects.findById(p.sc.projectId).get(), it, "VIEWER") }
        assertThat(status(sessionFor(viewer.username).put("${p.bindings}/LIVE/erp-db", """{"dataSourceId":"${ds.id}"}"""))).isEqualTo(403)
        // strangers, and the right project under the wrong workspace
        val stranger = sessionFor(fx.user("stranger").username)
        assertThat(status(stranger.get(p.bindings))).isEqualTo(404)
        val other = app()
        assertThat(status(p.admin.get("/api/v1/workspaces/${other.sc.ws}/projects/${p.sc.projectId}/data-bindings"))).isEqualTo(404)
        assertThat(status(p.admin.get("/api/v1/workspaces/${p.sc.ws}/projects/${other.sc.projectId}/data-bindings"))).isEqualTo(404)
        assertThat(status(other.admin.get(p.bindings))).isEqualTo(404)
        assertThat(status(other.admin.put("${p.bindings}/LIVE/erp-db", """{"dataSourceId":"${ds.id}"}"""))).isEqualTo(404)
        assertThat(bindingRows(p).map { it["data_source_id"] }).containsExactly(ds.id)
    }

    @Test
    fun `a data source that an application still uses cannot be deleted until it is unbound`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws)
        JdbcQueryCatalog(jdbc).save(SqlQueryDefinition("q.list", p.tenant, ds.id, "SELECT 1", emptyList(), 10))
        assertThat(status(put(p, "LIVE", "erp-db", ds.id))).isEqualTo(200)
        val refused = p.admin.delete("/api/v1/workspaces/${p.sc.ws}/data-sources/${ds.id}")
        assertThat(status(refused)).isEqualTo(409); assertThat(code(p.admin, refused)).isEqualTo("CONFLICT")
        assertThat(repository.find(p.tenant, ds.id)).isNotNull()
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds.id)).describedAs("a refused delete removes nothing").isEqualTo(1L)
        assertThat(status(p.admin.delete("${p.bindings}/LIVE/erp-db"))).isEqualTo(204)
        assertThat(status(p.admin.delete("/api/v1/workspaces/${p.sc.ws}/data-sources/${ds.id}"))).isEqualTo(204)
        assertThat(repository.find(p.tenant, ds.id)).isNull()
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds.id)).isZero()
    }
}
