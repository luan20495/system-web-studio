package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.tenancy.ActorKind
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantStatus
import com.systemwebstudio.version.SchemaRepository
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * D-C0-36 · the Public Runtime route, end to end on real PostgreSQL / Redis, with C1's real `PublicSiteAuthorizer`, the real release allow-list provider and the real
 * C3 `DataGateway` (only the connector is the test double): `POST /sites/{slug}/_data/queries/{queryId}/run`, anonymous, read-only LIVE query of the ACTIVE release.
 * Each test builds its own published application (site, release, snapshot, approval, LIVE binding) directly in the database: that is what a publish leaves behind.
 */
@Import(ManagementTestBeans::class)
@TestPropertySource(properties = [
    "app.sites.public-data.enabled=true", "app.sites.public-data.rate.ip-burst=6", "app.sites.public-data.rate.site-ip-per-minute=10", "app.sites.public-data.rate.site-per-minute=100000",
    "app.sites.origin=https://sites.example.test", "app.sites.studio-origin=https://studio.example.test"
])
class PublicDataEndpointTests : ManagementApiTestBase() {
    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var limiter: com.systemwebstudio.common.RateLimiter
    @Autowired lateinit var gateway: DataGateway
    @Autowired lateinit var codec: com.systemwebstudio.app.definition.AppDefinitionCodec
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")
    private val queries get() = JdbcQueryCatalog(jdbc)
    private val bindings get() = DataSourceBindingWriter(jdbc)

    private inner class Pub(val sc: Scenario, val tenant: UUID, val slug: String, val ds: DataSource, var release: UUID)

    private fun docWith(public: Boolean = true, publicOther: Boolean = false): JsonNode {
        val d: JsonNode = sample.deepCopy()
        val qs = d.get("queries")
        for (i in 0 until qs.size()) { val q = qs.get(i) as ObjectNode; when (q.get("id").asString()) { "orders-list" -> if (public) q.put("public", true) else q.remove("public"); "order-create" -> if (publicOther) q.put("public", true) } }
        return d
    }

    /** artifact + deployment + the version it pins; the active pointer is NOT touched */
    private fun release(sc: Scenario, doc: JsonNode, status: String = "RUNNING", visibility: String = "PUBLIC"): UUID {
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), doc, "EDIT", "published", null, null, null, sc.user.id)
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,5,'[]'::jsonb)", art, sc.projectId, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), "${sc.projectId}/x")
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,?,?,'static',?)", dep, sc.ws, sc.projectId, versionId, sc.user.id, visibility, status, art)
        return dep
    }

    private fun pointTo(app: Pub, release: UUID?) { jdbc.update("UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1 WHERE project_id = ?", release, app.sc.projectId); if (release != null) app.release = release }

    private fun approve(sc: Scenario, tenant: UUID, approved: Boolean = true) {
        jdbc.update("""INSERT INTO publish_configs (project_id, workspace_id, tenant_id, visibility, public_data_approved) VALUES (?,?,?,'PUBLIC',?)
            ON CONFLICT (project_id) DO UPDATE SET public_data_approved = EXCLUDED.public_data_approved""", sc.projectId, sc.ws, tenant, approved)
    }

    private fun order(no: String, customer: String) = mapOf("order_no" to no, "customer" to mapOf("name" to customer))

    private fun pub(doc: JsonNode = docWith(), approved: Boolean = true, status: String = "RUNNING", visibility: String = "PUBLIC", bindLive: Boolean = true): Pub {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val now = Instant.now()
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), "pub-" + UUID.randomUUID().toString().take(8), workspaceId = sc.ws, createdAt = now, updatedAt = now).also { repository.save(it) }
        queries.save(SqlQueryDefinition("orders.list", tenant, ds.id, "SELECT 1", listOf(QueryParamSpec("status", ParamType.STRING, false), QueryParamSpec("since", ParamType.TIMESTAMP, false)), 200))
        connector.rowsFor[ds.id] = listOf(order("SO-1001", "ACME Co"), order("SO-1002", "Globex"))
        schemas.upsertSchema(sc.projectId, sc.ws, doc)
        val dep = release(sc, doc, status, visibility)
        val slug = "pub-" + UUID.randomUUID().toString().replace("-", "").take(12)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?,?,?)", sc.projectId, slug, dep)
        approve(sc, tenant, approved)
        if (bindLive) bindings.bind(tenant, sc.ws, sc.projectId, ExecutionMode.LIVE, "erp-db", ds.id, sc.user.id)
        return Pub(sc, tenant, slug, ds, dep)
    }

    private var n = 0
    /** a client address block per test instance: the Redis counters outlive a test, so two tests must not share addresses */
    private val ipA = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 250); private val ipB = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 250)
    /** anonymous POST, from its own client address unless one is given */
    private fun call(slug: String, query: String = "orders-list", body: String? = """{"params":{"status":"open"}}""", ip: String? = null, path: String = "/sites/$slug/_data/queries/$query/run",
                     customize: (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) -> Unit = {}): MvcResult =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).with { r -> r.remoteAddr = ip ?: "10.$ipA.$ipB.${++n}"; r }.also { b -> if (body != null) b.content(body) }.also(customize)).andReturn()

    private fun body(r: MvcResult): JsonNode = json.readTree(r.response.contentAsString)
    private val notFoundBody = """"code":"QUERY_NOT_FOUND""""
    private val envelopeKeys = setOf("code", "message", "requestId", "retryable", "details")
    private fun assertNotFound(r: MvcResult) {
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(404)
        val b = body(r); assertThat(b.propertyNames().asSequence().toSet()).isEqualTo(envelopeKeys)
        assertThat(b.get("code").asString()).isEqualTo("QUERY_NOT_FOUND"); assertThat(b.get("message").asString()).isEqualTo("Query not found")
        assertThat(b.get("retryable").asBoolean()).isFalse()
    }

    // ------------------------------------------------------------------------------------------------ the happy path

    @Test
    fun `1 to 3 - an anonymous visitor runs a public LIVE query - no user, no session, no cookie, nothing about tenants in the answer`() {
        val a = pub()
        val r = call(a.slug)
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(200)
        val text = r.response.contentAsString
        assertThat(text).contains("SO-1001").contains("ACME Co")
        assertThat(r.response.getHeader("Cache-Control")).isEqualTo("no-store")
        for (secret in listOf(a.tenant.toString(), a.sc.ws.toString(), a.sc.projectId.toString(), a.ds.id.toString(), a.ds.name, a.release.toString())) assertThat(text).doesNotContain(secret)
        assertThat(r.request.cookies).isNullOrEmpty(); assertThat(r.request.getHeader("Authorization")).isNull()
        // the audit row names the actor kind and the release, no visitor identity
        val audit = jdbc.queryForList("SELECT new_value::text AS v FROM audit_events WHERE action = 'DATA_PUBLIC_QUERY_SERVED' AND new_value::text LIKE ?", "%${a.slug}%")
        assertThat(audit).hasSize(1); assertThat(audit.single()["v"] as String).contains("PUBLIC_SITE").contains(a.release.toString()).contains("orders-list")
    }

    @Test
    fun `a Studio session cookie or a bearer header changes nothing - the visitor is still PUBLIC_SITE`() {
        val a = pub()
        val r = call(a.slug) { b -> b.header("Authorization", "Bearer forged"); b.header("X-XSRF-TOKEN", "x"); b.header("Cookie", "STUDIO_SESSION=forged; XSRF-TOKEN=x") }
        assertThat(status(r)).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DATA_PUBLIC_QUERY_SERVED' AND new_value::text LIKE '%PUBLIC_SITE%' AND new_value::text LIKE ?", Long::class.java, "%${a.slug}%")).isEqualTo(1L)
    }

    // ------------------------------------------------------------------------------------------------ LIVE only, release scope

    @Test
    fun `4 and 5 - LIVE only - the body cannot ask for TEST, the TEST binding is never used, and a draft-only public query is not there`() {
        val a = pub()
        assertThat(status(call(a.slug, body = """{"mode":"TEST","params":{}}"""))).isEqualTo(400)
        assertThat(status(call(a.slug, body = """{"params":{},"mappingRef":"x"}"""))).isEqualTo(400)
        // a TEST binding to another source with other rows exists, but the public call reads the LIVE one
        val other = DataSource(DataSourceRef(UUID.randomUUID(), a.tenant, "fake", emptyMap()), "t-" + UUID.randomUUID().toString().take(6), workspaceId = a.sc.ws, createdAt = Instant.now(), updatedAt = Instant.now()).also { repository.save(it) }
        queries.save(SqlQueryDefinition("orders.list", a.tenant, other.id, "SELECT 1", emptyList(), 10)); connector.rowsFor[other.id] = listOf(order("TEST-ROW", "T"))
        bindings.bind(a.tenant, a.sc.ws, a.sc.projectId, ExecutionMode.TEST, "erp-db", other.id, a.sc.user.id)
        val r = call(a.slug); assertThat(r.response.contentAsString).contains("SO-1001").doesNotContain("TEST-ROW")
        // draft: the draft document adds a public query that no release has
        val d: JsonNode = docWith(); (d.get("queries") as tools.jackson.databind.node.ArrayNode).add((d.get("queries").get(0).deepCopy() as ObjectNode).also { it.put("id", "draft-only"); it.put("public", true) })
        schemas.upsertSchema(a.sc.projectId, a.sc.ws, d)
        assertNotFound(call(a.slug, "draft-only"))
    }

    @Test
    fun `6 7 - a query the author did not mark public, an unknown one and a malformed id are the same 404`() {
        val a = pub(docWith(public = false))
        val unlisted = call(a.slug); assertNotFound(unlisted)
        val unknown = call(a.slug, "no-such-query"); assertNotFound(unknown)
        assertThat(body(unlisted).get("message")).isEqualTo(body(unknown).get("message"))
        assertNotFound(call(a.slug, "bad id!"))
        val write = pub(docWith(publicOther = true)); assertNotFound(call(write.slug, "order-create"))          // a WRITE query is never public (and the validator refuses the document: see the model tests)
    }

    @Test
    fun `8 - a query of another release is a 404, and a rollback changes the available set with the pointer`() {
        val a = pub()                                                                                          // release A: orders-list public
        assertThat(status(call(a.slug))).isEqualTo(200)
        val b = release(a.sc, docWith(public = false))                                                         // release B: nothing public
        pointTo(a, b); assertNotFound(call(a.slug))
        pointTo(a, a.release.let { jdbc.queryForObject("SELECT id FROM deployments WHERE project_id = ? ORDER BY created_at LIMIT 1", UUID::class.java, a.sc.projectId)!! })
        assertThat(status(call(a.slug))).describedAs("the pointer is back on release A").isEqualTo(200)
    }

    @Test
    fun `9 - draft changes do not touch an active release`() {
        val a = pub()
        schemas.upsertSchema(a.sc.projectId, a.sc.ws, docWith(public = false))
        assertThat(status(call(a.slug))).isEqualTo(200)
        jdbc.update("UPDATE page_schemas SET schema = '{}'::jsonb WHERE project_id = ?", a.sc.projectId)
        assertThat(status(call(a.slug))).isEqualTo(200)
    }

    @Test
    fun `10 to 14 - unknown, offline, archived, private and not-running sites are all the same 404`() {
        assertNotFound(call("no-such-site-123"))
        val offline = pub(); pointTo(offline, null); assertNotFound(call(offline.slug))
        val archived = pub(); jdbc.update("UPDATE projects SET lifecycle = 'ARCHIVED' WHERE id = ?", archived.sc.projectId); assertNotFound(call(archived.slug))
        val deleted = pub(); jdbc.update("UPDATE projects SET active = false WHERE id = ?", deleted.sc.projectId); assertNotFound(call(deleted.slug))
        val private_ = pub(visibility = "PRIVATE"); assertNotFound(call(private_.slug))
        val deploying = pub(status = "DEPLOYING"); assertNotFound(call(deploying.slug))
        val failed = pub(); jdbc.update("UPDATE deployments SET status = 'ROLLED_BACK' WHERE id = ?", failed.release); assertNotFound(call(failed.slug))
        val foreignTenant = pub(); val mine = pub(); assertNotFound(call(foreignTenant.slug, "orders-list-of-" + mine.slug.take(3)))
    }

    @Test
    fun `15 - the public-data approval is a live switch - not approved, no policy row, or revoked means 404`() {
        val none = pub(approved = false); assertNotFound(call(none.slug))
        val gone = pub(); jdbc.update("DELETE FROM publish_configs WHERE project_id = ?", gone.sc.projectId); assertNotFound(call(gone.slug))
        val a = pub(); assertThat(status(call(a.slug))).isEqualTo(200)
        approve(a.sc, a.tenant, false); assertNotFound(call(a.slug))
        approve(a.sc, a.tenant, true); assertThat(status(call(a.slug))).isEqualTo(200)
    }

    // ------------------------------------------------------------------------------------------------ authority, parsing, other routes

    @Test
    fun `16 17 - forged tenant, workspace, project, release or role fields are refused in the body and ignored everywhere else`() {
        val a = pub(); val other = pub()
        for (field in listOf("tenantId", "workspaceId", "projectId", "deploymentId", "releaseId", "role", "permissions", "actorKind", "userId", "dataSourceId", "sql", "mode", "mappingRef", "page"))
            assertThat(status(call(a.slug, body = """{"params":{},"$field":"${other.sc.projectId}"}"""))).describedAs(field).isEqualTo(400)
        assertThat(status(call(a.slug, body = "[]"))).isEqualTo(400)
        assertThat(status(call(a.slug, body = "{not json"))).isEqualTo(400)
        val r = call(a.slug, path = "/sites/${a.slug}/_data/queries/orders-list/run?tenantId=${other.tenant}&projectId=${other.sc.projectId}&mode=TEST") { b -> b.header("X-Tenant-Id", other.tenant.toString()); b.header("X-Project-Id", other.sc.projectId.toString()) }
        assertThat(status(r)).isEqualTo(200); assertThat(r.response.contentAsString).contains("SO-1001")
        // an empty body is a call with no parameters
        assertThat(status(call(a.slug, body = null))).isEqualTo(200)
    }

    @Test
    fun `18 - no public mutation, action, workflow, discovery or raw gateway route exists, and CSRF is unchanged elsewhere`() {
        val a = pub()
        for (path in listOf("/sites/${a.slug}/_data/mutations/orders.create/run", "/sites/${a.slug}/_data/actions/create-order/execute", "/sites/${a.slug}/_data/workflows/notify-flow/runs",
            "/sites/${a.slug}/_data/schema/discover", "/sites/${a.slug}/_data/sources", "/sites/${a.slug}/_data/mutate", "/sites/${a.slug}/_data/queries/orders-list", "/sites/${a.slug}/other"))
            assertThat(status(call(a.slug, path = path))).describedAs(path).isIn(401, 403, 404, 405)
        assertThat(status(mvc.perform(post("/api/v1/workspaces/${a.sc.ws}/projects/${a.sc.projectId}/app-runtime/queries/orders-list/run").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()))
            .describedAs("the authenticated runtime still needs a session and CSRF").isIn(401, 403)
        val mounted = mappings.handlerMethods.keys.flatMap { i -> i.pathPatternsCondition?.patternValues.orEmpty().flatMap { p -> i.methodsCondition.methods.map { "${it.name} $p" } } }.filter { "/sites/{slug}/_data" in it }
        assertThat(mounted).containsExactly("POST /sites/{slug}/_data/queries/{queryId}/run")
        // the data gateway refuses everything but a query for a PUBLIC_SITE: a mutation, even with a valid idempotency key
        val ctx = GatewayContext(TenantContext(a.tenant, null, TenantStatus.ACTIVE, false), null, ActorKind.PUBLIC_SITE, "r", a.sc.ws, a.sc.projectId, "00000000-0000-0000-0000-000000000000")
        val e = org.junit.jupiter.api.Assertions.assertThrows(ConnectorFailure::class.java) { gateway.mutate(ctx, GatewayMutation(a.ds.id, "orders.create", emptyMap(), "k-1")) }
        assertThat(e.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
    }

    // ------------------------------------------------------------------------------------------------ abuse control and disclosure

    @Test
    fun `19 - rate limits - a burst per client address, a sustained limit per site and address, other addresses unaffected, and a safe envelope`() {
        val a = pub()
        val results = (1..8).map { call(a.slug, ip = "198.51.${ipA}.${ipB}") }
        assertThat(results.take(6).map(::status)).containsOnly(200)
        val limited = results[6]; assertThat(status(limited)).isEqualTo(429)
        assertThat(limited.response.getHeader("Retry-After")).isNotBlank()
        val b = body(limited); assertThat(b.propertyNames().asSequence().toSet()).isEqualTo(envelopeKeys); assertThat(b.get("code").asString()).isEqualTo("RATE_LIMITED"); assertThat(b.get("retryable").asBoolean()).isTrue()
        assertThat(status(call(a.slug, ip = "198.52.${ipA}.${ipB}"))).describedAs("another address").isEqualTo(200)
    }

    @Test
    fun `20 - errors disclose nothing - a failing source, an unbound slot and an oversized body are generic and carry the envelope`() {
        val a = pub()
        connector.queryHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.CONNECT_FAILED, "db-host-secret.internal:5432 refused") }
        val failing = call(a.slug); connector.queryHook = { _, _, _ -> }
        assertThat(status(failing)).isEqualTo(502)
        assertThat(failing.response.contentAsString).doesNotContain("db-host-secret").doesNotContain("5432").doesNotContain("CONNECT_FAILED").contains("DATA_UNAVAILABLE")
        assertThat(body(failing).propertyNames().asSequence().toSet()).isEqualTo(envelopeKeys)
        val unbound = pub(bindLive = false); val u = call(unbound.slug)
        assertThat(status(u)).isEqualTo(422); assertThat(body(u).get("code").asString()).isEqualTo("DATA_SOURCE_UNBOUND"); assertThat(u.response.contentAsString).doesNotContain(unbound.ds.id.toString()).doesNotContain(unbound.ds.name)
        val big = call(a.slug, body = """{"params":{"status":"${"x".repeat(17_000)}"}}""")
        assertThat(status(big)).isEqualTo(413); assertThat(body(big).get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE")
        connector.rowsFor[a.ds.id] = listOf(mapOf("order_no" to "SO-BIG", "customer" to mapOf("name" to "y".repeat(300_000))))
        val large = call(a.slug); assertThat(status(large)).isEqualTo(502); assertThat(body(large).get("code").asString()).isEqualTo("RESPONSE_TOO_LARGE")
        for (r in listOf(failing, u, big, large)) assertThat(r.response.getHeader("Cache-Control")).isEqualTo("no-store")
    }

    // ------------------------------------------------------------------------------------------------ the allow-list provider on its own

    @Test
    fun `the provider is C2's PublicQueries over the release's own snapshot, gated by the approval, and fails closed`() {
        val p = ReleaseSnapshotPublicQueryAllowList(jdbc, json, codec)
        assertThat(p.publicQueryIds(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).describedAs("unknown release").isEmpty()
        val a = pub(); assertThat(p.publicQueryIds(a.tenant, a.sc.projectId, a.release)).containsExactly("orders-list")
        assertThat(p.publicQueryIds(UUID.randomUUID(), a.sc.projectId, a.release)).describedAs("another tenant").isEmpty()
        assertThat(p.publicQueryIds(a.tenant, UUID.randomUUID(), a.release)).describedAs("another project").isEmpty()
    }

    @Test
    fun `19b - the sustained budget per site and address (and per site) refuses with 429 whatever the burst budget says`() {
        val a = pub(); val ip = "192.0.${ipA}.${ipB}"
        repeat(10) { limiter.hit("pubdata:site:${a.slug}:$ip", 10, 60) }
        val r = call(a.slug, ip = ip); assertThat(status(r)).isEqualTo(429); assertThat(body(r).get("code").asString()).isEqualTo("RATE_LIMITED")
        assertThat(status(call(a.slug, ip = "192.1.${ipA}.${ipB}"))).describedAs("another address of the same site").isEqualTo(200)
    }
}
