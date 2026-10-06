package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.FakeDataConnector
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * REAL LIVE data path, no in-memory port anywhere in the chain (D-C0-21, B-C0-W-01):
 * HTTP -> access checks -> published AppDefinition (RUNNING deployment) -> V28 bindings -> `DefaultDataGateway` -> V28 data sources / queries /
 * mutations / credentials / idempotency (all JDBC) -> a writable test connector (the production connectors are read-only by design, B-C0-W-04).
 *
 * Flags: data platform and workflow on; `allow-volatile-stores=true` only because C4's run stores stay in memory until V29 (this is the dev/E2E switch of D-C0-20).
 * The management API for data sources / queries / bindings does not exist yet (B-C0-W-03): fixtures write the rows through the adapters.
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="        // base64 of 32 test bytes, only for this test context
    ]
)
class DataRuntimeLiveApiTests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg {
        @Bean fun fakeDataConnector() = FakeDataConnector()
    }

    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var connector: FakeDataConnector
    @Autowired lateinit var repository: DataSourceRepository
    @Autowired lateinit var vault: CredentialVault
    @Autowired lateinit var gateway: DataGateway

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")
    private val bindings get() = DataSourceBindingWriter(jdbc)
    private val queries get() = JdbcQueryCatalog(jdbc)
    private val mutations get() = JdbcMutationCatalog(jdbc)

    @BeforeEach
    fun resetConnector() {
        connector.queryCalls.set(0); connector.mutationCalls.set(0); connector.credentialsSeen.clear(); connector.lastMutationParams.clear()
        connector.mutationHook = { _, _, _ -> MutationOutcome(1, com.systemwebstudio.data.query.DataJson.toNode(mapOf("id" to "rec-1"))) }
    }

    private class App(val sc: Scenario, val tenant: UUID, val source: DataSource)

    private fun rt(app: App, path: String) = "${app.sc.base}/app-runtime/$path"
    private fun admin(sc: Scenario) = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)

    /** an application whose AppDefinition is both the working draft (TEST) and the published version (LIVE), with one registered source and approved operations */
    private fun app(credentialRef: String? = null, bindLive: Boolean = true): App {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        schemas.upsertSchema(sc.projectId, sc.ws, sample)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, 1, sample, "INITIAL", "published", null, null, null, sc.user.id)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')",
            UUID.randomUUID(), sc.ws, sc.projectId, versionId, sc.user.id)
        val ds = source(tenant, sc.ws, credentialRef)
        if (bindLive) bindings.bind(tenant, sc.ws, sc.projectId, ExecutionMode.LIVE, "erp-db", ds.id, sc.user.id)
        return App(sc, tenant, ds)
    }

    private fun source(tenant: UUID, ws: UUID, credentialRef: String? = null): DataSource {
        val now = Instant.now()
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), "erp-" + UUID.randomUUID().toString().take(8), credentialRef = credentialRef, workspaceId = ws, createdAt = now, updatedAt = now)
        repository.save(ds)
        queries.save(SqlQueryDefinition("orders.list", tenant, ds.id, "SELECT 1", listOf(QueryParamSpec("status", ParamType.STRING, false), QueryParamSpec("since", ParamType.TIMESTAMP, false)), 200))
        mutations.save(MutationDefinition("orders.create", tenant, ds.id, MutationKind.CREATE, "orders", listOf(QueryParamSpec("customer", ParamType.STRING), QueryParamSpec("amount", ParamType.NUMBER, false)), listOf("orders.list"), "orders"))
        return ds
    }

    private fun order(no: String, customer: String) = mapOf("order_no" to no, "customer" to mapOf("name" to customer))

    private fun create(app: App, key: String, customer: String = "ACME") =
        admin(app.sc).let { a -> a to a.post(rt(app, "actions/create-order/execute"), """{"idempotencyKey":"$key","inputs":{"customer":"$customer"},"trigger":{"eventName":"contact-1.onSubmit"}}""") }

    private fun idempotencyRows(app: App) = jdbc.queryForList("SELECT state, idem_key, fingerprint, affected, output_json FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ?", app.tenant, app.source.id)

    // ------------------------------------------------------------------------------------------------ the gateway bean is really there

    @Test
    fun `the data gateway bean exists and is the real one`() {
        assertThat(gateway).isNotNull()
        assertThat(gateway.javaClass.simpleName).isEqualTo("DefaultDataGateway")
    }

    // ------------------------------------------------------------------------------------------------ LIVE query

    @Test
    fun `a LIVE query reads rows through the persistent source, query and binding and returns the mapped view model`() {
        val app = app()
        connector.rowsFor[app.source.id] = listOf(order("SO-1001", "ACME Co"), order("SO-1002", "Globex"))
        val a = admin(app.sc)
        val r = a.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200)
        val b = a.body(r)
        assertThat(b.get("queryId").asString()).isEqualTo("orders-list")
        assertThat(b.get("mode").asString()).isEqualTo("LIVE")
        val result = b.get("result").toString()
        assertThat(result).contains("SO-1001").contains("ACME Co").contains("SO-1002").contains("Globex")
        assertThat(connector.queryCalls.get()).isEqualTo(1)
    }

    @Test
    fun `LIVE needs a LIVE binding: a TEST-only binding leaves LIVE unbound`() {
        val app = app(bindLive = false)
        bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.TEST, "erp-db", app.source.id, null)
        connector.rowsFor[app.source.id] = listOf(order("SO-1", "X"))
        val a = admin(app.sc)
        val r = a.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(a.body(r).get("code").asString()).isEqualTo("DATA_SOURCE_UNBOUND")
        assertThat(connector.queryCalls.get()).isZero()
    }

    @Test
    fun `a TEST run reads the TEST binding only and never writes a binding`() {
        val app = app()                                                                 // LIVE -> app.source
        val testSource = source(app.tenant, app.sc.ws)
        bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.TEST, "erp-db", testSource.id, null)
        connector.rowsFor[app.source.id] = listOf(order("LIVE-ROW", "Live Customer"))
        connector.rowsFor[testSource.id] = listOf(order("TEST-ROW", "Test Customer"))
        val before = jdbc.queryForList("SELECT mode, slot_id, data_source_id, updated_at FROM data_source_bindings WHERE project_id = ? ORDER BY mode", app.sc.projectId)

        val r = app.sc.s.post(rt(app, "queries/orders-list/run"), """{"mode":"TEST"}""")
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200)
        val result = app.sc.s.body(r).get("result").toString()
        assertThat(result).contains("TEST-ROW").doesNotContain("LIVE-ROW")
        assertThat(jdbc.queryForList("SELECT mode, slot_id, data_source_id, updated_at FROM data_source_bindings WHERE project_id = ? ORDER BY mode", app.sc.projectId)).isEqualTo(before)

        val live = admin(app.sc).post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(admin(app.sc).body(live).get("result").toString()).contains("LIVE-ROW").doesNotContain("TEST-ROW")
    }

    @Test
    fun `a disabled query is not found and a disabled data source is refused`() {
        val app = app()
        connector.rowsFor[app.source.id] = listOf(order("SO-1", "X"))
        val a = admin(app.sc)
        assertThat(queries.disable(app.tenant, app.source.id, "orders.list")).isTrue()
        val r = a.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(r.response.status).isEqualTo(404)
        assertThat(a.body(r).get("code").asString()).isEqualTo("QUERY_NOT_FOUND")

        queries.save(SqlQueryDefinition("orders.list", app.tenant, app.source.id, "SELECT 1", maxRows = 200, version = 2))
        assertThat(a.post(rt(app, "queries/orders-list/run"), "{}").response.status).isEqualTo(200)
        repository.save(app.source.revised(Instant.now(), status = DataSourceStatus.DISABLED))
        val off = a.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(off.response.status).isEqualTo(409)
        assertThat(a.body(off).get("code").asString()).isEqualTo(FailureCodes.DISABLED)
    }

    @Test
    fun `a stored credential reaches the connector, and appears in no response, audit row or table other than as ciphertext`() {
        val secret = "tok-LIVE-SECRET-9f31"
        val app = app()
        repository.save(app.source.revised(Instant.now(), credentialRef = vault.store(app.tenant, mapOf("token" to secret))))
        connector.rowsFor[app.source.id] = listOf(order("SO-9", "Z"))
        val a = admin(app.sc)
        val r = a.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200)
        assertThat(connector.credentialsSeen).isNotEmpty()
        assertThat(connector.credentialsSeen.last().require("token")).isEqualTo(secret)
        assertThat(r.response.contentAsString).doesNotContain(secret)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE new_value::text LIKE ?", Long::class.java, "%$secret%")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_credentials WHERE ciphertext LIKE ?", Long::class.java, "%$secret%")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_sources WHERE config_nonsecret::text LIKE ? OR credential_ref LIKE ?", Long::class.java, "%$secret%", "%$secret%")).isZero()
    }

    // ------------------------------------------------------------------------------------------------ LIVE mutation

    @Test
    fun `a LIVE action writes through the gateway, stores only a derived key, and a retry with the same key writes nothing again`() {
        val app = app()
        val (a, first) = create(app, "order-0001")
        assertThat(first.response.status).describedAs(first.response.contentAsString).isEqualTo(200)
        assertThat(a.body(first).get("status").asString()).isEqualTo("OK")
        assertThat(connector.mutationCalls.get()).isEqualTo(1)
        assertThat(connector.lastMutationParams.single()["customer"]).isEqualTo("ACME")

        val rows = idempotencyRows(app)
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["state"]).isEqualTo("DONE")
        val key = rows.single()["idem_key"] as String
        assertThat(key).hasSize(43).isNotEqualTo("order-0001").matches("[A-Za-z0-9_-]{43}")                       // C4's derived key, never the client's
        assertThat(rows.single()["affected"]).isEqualTo(1L)
        val everything = jdbc.queryForList("SELECT t::text FROM data_idempotency t WHERE tenant_id = ?", String::class.java, app.tenant).joinToString()
        assertThat(everything).doesNotContain("order-0001").doesNotContain("ACME")

        val (_, second) = create(app, "order-0001")
        assertThat(second.response.status).isIn(200, 409)
        assertThat(connector.mutationCalls.get()).describedAs("the same key must never write twice").isEqualTo(1)
        assertThat(idempotencyRows(app)).hasSize(1)

        val (_, third) = create(app, "order-0002", "Globex")
        assertThat(third.response.status).isEqualTo(200)
        assertThat(connector.mutationCalls.get()).isEqualTo(2)
        assertThat(idempotencyRows(app)).hasSize(2)
    }

    @Test
    fun `an ambiguous failure keeps the key reserved as UNKNOWN and a retry never writes again`() {
        val app = app()
        connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "the source did not answer in time") }
        val (_, first) = create(app, "order-timeout")
        assertThat(first.response.status).isNotEqualTo(200)
        assertThat(connector.mutationCalls.get()).isEqualTo(1)
        assertThat(idempotencyRows(app).single()["state"]).isEqualTo("UNKNOWN")

        connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }                  // the source recovered: still must not run
        val (_, retry) = create(app, "order-timeout")
        assertThat(retry.response.status).isNotEqualTo(200)
        assertThat(connector.mutationCalls.get()).describedAs("an unknown outcome is never handed out for a second run").isEqualTo(1)
        assertThat(idempotencyRows(app).single()["state"]).isEqualTo("UNKNOWN")
    }

    @Test
    fun `a TEST action is only previewed: no connector call, no idempotency row`() {
        val app = app()
        val a = admin(app.sc)
        val r = a.post(rt(app, "actions/create-order/execute"), """{"mode":"TEST","inputs":{"customer":"ACME"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(a.body(r).get("status").asString()).isEqualTo("WOULD_RUN")
        assertThat(connector.mutationCalls.get()).isZero()
        assertThat(idempotencyRows(app)).isEmpty()
    }

    @Test
    fun `a LIVE action with no LIVE binding changes nothing`() {
        val app = app(bindLive = false)
        val (_, r) = create(app, "order-unbound")
        assertThat(r.response.status).isNotEqualTo(200)
        assertThat(connector.mutationCalls.get()).isZero()
        assertThat(idempotencyRows(app)).isEmpty()
    }

    @Test
    fun `another workspace cannot reach the application's data`() {
        val app = app()
        connector.rowsFor[app.source.id] = listOf(order("SECRET-ORDER", "Hidden"))
        val stranger = sessionFor(fx.user("stranger").also { fx.member(fx.workspace(), it, "WORKSPACE_ADMIN") }.username)
        val r = stranger.post(rt(app, "queries/orders-list/run"), "{}")
        assertThat(r.response.status).isEqualTo(404)
        assertThat(r.response.contentAsString).doesNotContain("SECRET-ORDER")
        assertThat(stranger.post(rt(app, "actions/create-order/execute"), """{"idempotencyKey":"order-stranger","inputs":{"customer":"X"},"trigger":{"eventName":"contact-1.onSubmit"}}""").response.status).isEqualTo(404)
        assertThat(connector.queryCalls.get()).isZero()
        assertThat(connector.mutationCalls.get()).isZero()
    }
}
