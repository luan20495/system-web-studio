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
import com.systemwebstudio.data.query.DataJson
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
import tools.jackson.databind.node.ObjectNode
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
    private fun adminUser(sc: Scenario) = fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
    private fun admin(sc: Scenario) = sessionFor(adminUser(sc).username)

    /** the AppDefinition with `erp-db` pointing straight at a registered source (a `sourceRef`), instead of being resolved through a binding */
    private fun definitionWithSourceRef(sourceId: UUID): JsonNode {
        val copy: JsonNode = sample.deepCopy()
        val erp = DataJson.elements(copy.get("dataSources")).first { it.get("id").asString() == "erp-db" } as ObjectNode
        erp.put("sourceRef", sourceId.toString())
        return copy
    }

    /**
     * An application whose AppDefinition is both the working draft (TEST) and the published version (LIVE), with one registered source and approved operations.
     * [sourceRef] = the AppDefinition names that source directly (no binding is made); otherwise `erp-db` is bound for LIVE to a source of the application's workspace.
     */
    private fun app(credentialRef: String? = null, bindLive: Boolean = true, sourceRef: ((Scenario, UUID) -> DataSource)? = null): App {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val ds = sourceRef?.invoke(sc, tenant) ?: source(tenant, sc.ws, credentialRef)
        val document = if (sourceRef != null) definitionWithSourceRef(ds.id) else sample
        schemas.upsertSchema(sc.projectId, sc.ws, document)
        // scenario() creates the project through the API, which already wrote version 1 (INITIAL); a second row with number 1 would violate
        // project_versions_unique (project_id, version_number) - the published document is the NEXT version of the project
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), document, "EDIT", "published", null, null, null, sc.user.id)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')",
            UUID.randomUUID(), sc.ws, sc.projectId, versionId, sc.user.id)
        if (sourceRef == null && bindLive) bindings.bind(tenant, sc.ws, sc.projectId, ExecutionMode.LIVE, "erp-db", ds.id, sc.user.id)
        return App(sc, tenant, ds)
    }

    private fun source(tenant: UUID, ws: UUID?, credentialRef: String? = null): DataSource {
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
    fun `LIVE needs a LIVE binding - a TEST-only binding leaves LIVE unbound`() {
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
    fun `a TEST action is only previewed - no connector call, no idempotency row`() {
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

    // ------------------------------------------------------------------------------------------------ B-C0-W-05: tenant + workspace + project ownership

    private fun otherTenantWorkspace(): Pair<UUID, UUID> {
        val tenant = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", tenant, "t-" + tenant.toString().take(8))
        val ws = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, ?)", ws, "w-" + ws.toString().take(8), tenant)
        return tenant to ws
    }

    /** a source that was never registered: the reference answer for "does not exist" */
    private fun nowhere(tenant: UUID, ws: UUID) = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), "ghost", workspaceId = ws)

    private fun gatewayContext(app: App, workspaceId: UUID, user: com.systemwebstudio.identity.UserEntity) = com.systemwebstudio.data.gateway.GatewayContext(
        tenant = com.systemwebstudio.tenancy.TenantContext(app.tenant, null, com.systemwebstudio.tenancy.TenantStatus.ACTIVE, false),
        actorUserId = user.id, workspaceId = workspaceId, projectId = app.sc.projectId
    )

    private fun runList(app: App) = admin(app.sc).let { a -> a.post(rt(app, "queries/orders-list/run"), "{}").let { Triple(it.response.status, a.body(it).get("code")?.asString(), it.response.contentAsString) } }

    @Test
    fun `same tenant, same workspace, a directly referenced source of that workspace is allowed`() {
        val app = app(sourceRef = { sc, tenant -> source(tenant, sc.ws) })
        connector.rowsFor[app.source.id] = listOf(order("SO-OWN", "Own Co"))
        val (status, _, body) = runList(app)
        assertThat(status).describedAs(body).isEqualTo(200)
        assertThat(body).contains("SO-OWN")
        assertThat(connector.queryCalls.get()).isEqualTo(1)
    }

    @Test
    fun `same tenant, another workspace, a direct sourceRef is refused exactly like a source that does not exist`() {
        val foreign = app()                                                                  // workspace B of the same tenant, with its own source and data
        connector.rowsFor[foreign.source.id] = listOf(order("SECRET-B", "Workspace B Customer"))
        val intruder = app(sourceRef = { _, _ -> foreign.source })
        val ghost = app(sourceRef = { sc, tenant -> nowhere(tenant, sc.ws) })
        assertThat(intruder.tenant).isEqualTo(foreign.tenant)

        val denied = runList(intruder); val missing = runList(ghost)
        assertThat(denied.first).isEqualTo(404)
        assertThat(denied.second).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(denied.first).describedAs("no existence oracle: status").isEqualTo(missing.first)
        assertThat(denied.second).describedAs("no existence oracle: code").isEqualTo(missing.second)
        assertThat(denied.third).doesNotContain("SECRET-B").doesNotContain("Workspace B Customer")
        assertThat(connector.queryCalls.get()).isZero()
    }

    @Test
    fun `same tenant, another workspace, a direct sourceRef cannot write either, and nothing is reserved`() {
        val foreign = app()
        val intruder = app(sourceRef = { _, _ -> foreign.source })
        val ghost = app(sourceRef = { sc, tenant -> nowhere(tenant, sc.ws) })
        val (_, denied) = create(intruder, "order-intrusion")
        val (_, missing) = create(ghost, "order-ghost-key")
        assertThat(denied.response.status).isNotEqualTo(200)
        assertThat(denied.response.status).isEqualTo(missing.response.status)
        assertThat(connector.mutationCalls.get()).isZero()
        assertThat(idempotencyRows(foreign)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ?", Long::class.java, foreign.tenant, foreign.source.id)).isZero()
    }

    @Test
    fun `a tenant-level source without a workspace is not reachable at run time, by sourceRef or by binding`() {
        val app = app(bindLive = false)
        val tenantLevel = source(app.tenant, null)
        assertThat(bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.LIVE, "erp-db", tenantLevel.id, null)).isFalse()
        val direct = app(sourceRef = { _, _ -> tenantLevel })
        assertThat(runList(direct).first).isEqualTo(404)
        assertThat(runList(app).first).isEqualTo(422)
        assertThat(connector.queryCalls.get()).isZero()
    }

    @Test
    fun `same tenant, another workspace, a binding is refused by the writer and by the database, and the slot stays unbound`() {
        val foreign = app()
        val app = app(bindLive = false)
        assertThat(bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.LIVE, "erp-db", foreign.source.id, null)).isFalse()
        assertThat(bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.TEST, "erp-db", foreign.source.id, null)).isFalse()
        val failed = try {
            jdbc.update("INSERT INTO data_source_bindings (tenant_id, workspace_id, project_id, mode, slot_id, data_source_id) VALUES (?, ?, ?, 'LIVE', 'erp-db', ?)", app.tenant, app.sc.ws, app.sc.projectId, foreign.source.id)
            false
        } catch (e: org.springframework.dao.DataIntegrityViolationException) { true }
        assertThat(failed).describedAs("the database itself refuses a cross-workspace binding").isTrue()
        val (status, code, _) = runList(app)
        assertThat(status).isEqualTo(422)
        assertThat(code).isEqualTo("DATA_SOURCE_UNBOUND")
        assertThat(connector.queryCalls.get()).isZero()
    }

    @Test
    fun `a different tenant is refused by sourceRef and by binding`() {
        val (otherTenant, otherWs) = otherTenantWorkspace()
        val foreignSource = source(otherTenant, otherWs)
        connector.rowsFor[foreignSource.id] = listOf(order("SECRET-T2", "Other Tenant"))
        val intruder = app(sourceRef = { _, _ -> foreignSource })
        val denied = runList(intruder)
        assertThat(denied.first).isEqualTo(404)
        assertThat(denied.second).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(denied.third).doesNotContain("SECRET-T2")
        val app = app(bindLive = false)
        assertThat(bindings.bind(app.tenant, app.sc.ws, app.sc.projectId, ExecutionMode.LIVE, "erp-db", foreignSource.id, null)).isFalse()
        assertThat(connector.queryCalls.get()).isZero()
    }

    @Test
    fun `a forged workspace and project combination is denied, over HTTP and at the gateway`() {
        val a = app(); val b = app()
        connector.rowsFor[b.source.id] = listOf(order("SECRET-B", "B"))
        val adminA = adminUser(a.sc); val sessionA = sessionFor(adminA.username)
        // HTTP: workspace B in the path with the project of A (and the reverse); the caller is only a member of A
        val forgedPath = "/api/v1/workspaces/${b.sc.ws}/projects/${a.sc.projectId}/app-runtime/queries/orders-list/run"
        assertThat(sessionA.post(forgedPath, "{}").response.status).isEqualTo(404)
        val forgedPath2 = "/api/v1/workspaces/${a.sc.ws}/projects/${b.sc.projectId}/app-runtime/queries/orders-list/run"
        assertThat(sessionA.post(forgedPath2, "{}").response.status).isEqualTo(404)
        // gateway: a context that claims workspace B for the project of A and a source of B
        val forged = gatewayContext(a, b.sc.ws, adminA)
        val query = com.systemwebstudio.data.gateway.GatewayQuery(b.source.id, "orders.list", emptyMap(), null, "orders-map")
        assertThat(failureCode { gateway.runQuery(forged, query) }).isEqualTo(FailureCodes.PERMISSION_DENIED)
        // an honest context of A asking for the source of B: ownership, not permission, refuses (and says nothing about B)
        val honest = gatewayContext(a, a.sc.ws, adminA)
        assertThat(failureCode { gateway.runQuery(honest, query) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failureCode { gateway.mutate(honest, com.systemwebstudio.data.gateway.GatewayMutation(b.source.id, "orders.create", emptyMap(), "k".repeat(43))) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(connector.queryCalls.get()).isZero()
        assertThat(connector.mutationCalls.get()).isZero()
    }

    private fun failureCode(block: () -> Unit): String? = try { block(); null } catch (e: ConnectorFailure) { e.code }
}
