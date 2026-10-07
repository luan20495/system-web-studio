package com.systemwebstudio.wiring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourcePatch
import com.systemwebstudio.data.datasource.DataSourceAdminService
import com.systemwebstudio.data.datasource.DataSourceSpec
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.query.DefinitionAdminService
import com.systemwebstudio.data.query.DefinitionPatch
import com.systemwebstudio.data.query.DefinitionStatus
import com.systemwebstudio.data.query.MutationDefinitionCreate
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.QueryDefinitionCreate
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.tenancy.ActorKind
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The negative security suite of the Management API (contract §1, §2, §7): what must NOT work, against the real stack — the route table is exactly the frozen
 * one, authority and unknown keys are refused in every body, only a USER actor reaches the services, and a recognisable secret fed through every route that
 * can carry one appears in no response, audit row, stored row or log line.
 */
@Import(ManagementTestBeans::class)
class DataManagementNegativeSecurityTests : ManagementApiTestBase() {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping
    @Autowired lateinit var dataSources: DataSourceAdminService
    @Autowired lateinit var definitions: DefinitionAdminService
    @Autowired lateinit var discovery: DiscoveryService
    @Autowired lateinit var bindings: DataBindingService

    /** our own packages at TRACE (every C3 log statement), frameworks left at the level the application is configured with */
    private class OurLogs : AutoCloseable {
        private val ours = LoggerFactory.getLogger("com.systemwebstudio") as Logger
        private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        private val before: Level? = ours.level
        init { ours.level = Level.TRACE }
        val text: String get() = appender.list.toList().joinToString("\n") { e -> e.formattedMessage + (e.throwableProxy?.let { " | " + it.className + ": " + it.message } ?: "") }
        override fun close() { root.detachAppender(appender); ours.level = before }
    }

    private fun body(json: String): JsonNode = com.systemwebstudio.data.query.DataJson.parse(json.toByteArray())

    // ------------------------------------------------------------------------------------------------ the route table is the frozen one

    @Test
    fun `the mounted routes are exactly the frozen list - no project-scoped definitions, no browser mutation route`() {
        val base = "/api/v1/workspaces/{workspaceId}"; val ds = "$base/data-sources"; val b = "$base/projects/{projectId}/data-bindings"
        val frozen = setOf(
            "GET $ds/connectors", "GET $ds", "POST $ds", "GET $ds/{id}", "PATCH $ds/{id}", "DELETE $ds/{id}",
            "GET $ds/{id}/credential", "PUT $ds/{id}/credential", "DELETE $ds/{id}/credential", "POST $ds/{id}/test",
            "POST $ds/{id}/schema/discover", "GET $ds/{id}/schema",
            "GET $ds/{id}/queries", "POST $ds/{id}/queries", "GET $ds/{id}/queries/{queryId}", "PATCH $ds/{id}/queries/{queryId}", "DELETE $ds/{id}/queries/{queryId}",
            "GET $ds/{id}/mutations", "POST $ds/{id}/mutations", "GET $ds/{id}/mutations/{mutationId}", "PATCH $ds/{id}/mutations/{mutationId}", "DELETE $ds/{id}/mutations/{mutationId}",
            "GET $b", "PUT $b/{mode}/{slotId}", "DELETE $b/{mode}/{slotId}"
        )
        val mounted = mappings.handlerMethods.keys.flatMap { info ->
            val patterns = info.pathPatternsCondition?.patternValues ?: emptySet()
            val methods = info.methodsCondition.methods.map { it.name }
            patterns.flatMap { p -> methods.map { m -> "$m $p" } }
        }.filter { it.contains("data-sources") || it.contains("data-bindings") || it.contains("data-queries") || it.contains("data-mutations") || it.contains("/data/") }.toSet()
        assertThat(mounted.filter { it.contains("/workspaces/") && (it.contains("data-sources") || it.contains("data-bindings")) }.toSet())
            .describedAs("every management route is in the contract and every contract route is mounted").isEqualTo(frozen)
        assertThat(mounted.filter { it.contains("data-queries") || it.contains("data-mutations") }).describedAs("project-scoped definition routes are not approved").isEmpty()
        assertThat(mounted.filter { it.contains("/data/mutate") }).describedAs("a browser cannot execute a raw mutation").isEmpty()
    }

    @Test
    fun `no raw mutation route answers, with the platform on`() {
        val w = admin()
        for (path in listOf("/api/v1/data/mutate", "/api/v1/data/mutations", "/api/v1/workspaces/${w.ws}/data/mutate"))
            assertThat(status(w.s.post(path, """{"dataSourceId":"${UUID.randomUUID()}","operation":"x","params":{},"idempotencyKey":"k-12345678"}"""))).describedAs(path).isIn(404, 405)
        val p = app()
        for (suffix in listOf("data-queries", "data-mutations")) {
            assertThat(status(p.admin.get("${p.sc.base}/$suffix"))).describedAs(suffix).isIn(404, 405)
            assertThat(status(p.admin.post("${p.sc.base}/$suffix", "{}"))).describedAs(suffix).isIn(404, 405)
        }
    }

    // ------------------------------------------------------------------------------------------------ authority and unknown keys

    @Test
    fun `an authority or unknown key is refused in every body of every route`() {
        val w = admin(); val id = w.pg(); val ds = UUID.fromString(id); val p = app(); val src = source(p.tenant, p.sc.ws)
        assertThat(status(w.s.post("${w.base}/$id/queries", """{"queryId":"qq","kind":"SQL","definition":{"sql":"SELECT 1"}}"""))).isEqualTo(201)
        assertThat(status(w.s.post("${w.base}/$id/mutations", """{"mutationId":"mm","kind":"CREATE","definition":{"target":"shop.orders","params":[{"name":"a","type":"STRING"}]}}"""))).isEqualTo(201)
        val authority = listOf("tenantId" to "\"${w.tenant}\"", "workspaceId" to "\"${w.ws}\"", "projectId" to "\"${UUID.randomUUID()}\"", "credentialRef" to "\"v1:x\"", "id" to "\"${UUID.randomUUID()}\"",
            "createdBy" to "\"${w.user.id}\"", "updatedBy" to "\"${w.user.id}\"", "userId" to "\"${w.user.id}\"", "version" to "7", "dataSourceId" to "\"$id\"", "actor" to "\"x\"", "role" to "\"ADMIN\"", "unknown" to "1")
        val routes: List<Triple<String, String, (String) -> org.springframework.test.web.servlet.MvcResult>> = listOf(
            Triple("POST ds", """{"name":"${uniq("x")}","type":"fake","config":{}""", { b -> w.s.post(w.base, b) }),
            Triple("PATCH ds", """{"name":"renamed.ok"""", { b -> w.s.patch("${w.base}/$id", b) }),
            Triple("PUT credential", """{"credential":{"username":"u","password":"p"}""", { b -> w.s.put("${w.base}/$id/credential", b) }),
            Triple("POST discover", """{"includeSamples":false""", { b -> w.s.post("${w.base}/$id/schema/discover", b) }),
            Triple("POST query", """{"queryId":"n.q","kind":"SQL","definition":{"sql":"SELECT 1"}""", { b -> w.s.post("${w.base}/$id/queries", b) }),
            Triple("PATCH query", """{"status":"DISABLED"""", { b -> w.s.patch("${w.base}/$id/queries/qq", b) }),
            Triple("POST mutation", """{"mutationId":"n.m","kind":"CREATE","definition":{"target":"shop.orders","params":[{"name":"a","type":"STRING"}]}""", { b -> w.s.post("${w.base}/$id/mutations", b) }),
            Triple("PATCH mutation", """{"status":"DISABLED"""", { b -> w.s.patch("${w.base}/$id/mutations/mm", b) }),
            Triple("PUT binding", """{"dataSourceId":"${src.id}"""", { b -> p.admin.put("${p.bindings}/LIVE/erp-db", b) }))
        for ((label, prefix, call) in routes) for ((key, value) in authority) {
            if (label == "PUT binding" && key == "dataSourceId") continue                    // the one key a binding body carries
            val r = call("$prefix,\"$key\":$value}")
            assertThat(status(r)).describedAs("$label + $key").isEqualTo(400)
            assertThat(code(w.s, r)).describedAs("$label + $key").isEqualTo("INVALID_PARAMS")
            assertThat(r.response.contentAsString).describedAs("$label + $key: the value is never echoed").doesNotContain(w.tenant.toString())
        }
        // nested: an unknown key inside a definition document is refused too
        for (nested in listOf("""{"sql":"SELECT 1","tenantId":"x"}""", """{"sql":"SELECT 1","dataSourceId":"$id"}""", """{"sql":"SELECT 1","credential":{"a":"b"}}"""))
            assertThat(status(w.s.post("${w.base}/$id/queries", """{"queryId":"n.q","kind":"SQL","definition":$nested}"""))).describedAs(nested).isEqualTo(400)
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", ds)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM data_source_bindings WHERE project_id = ?", p.sc.projectId)).isZero()
        assertThat(jdbc.queryForObject("SELECT version FROM data_sources WHERE id = ?", Long::class.java, ds)).describedAs("nothing was applied").isEqualTo(1L)
    }

    @Test
    fun `a body that is not a JSON object is refused 400 on every body route`() {
        val w = admin(); val id = w.created()
        for (bad in listOf("[]", "\"text\"", "123", "null")) {
            for (call in listOf<() -> org.springframework.test.web.servlet.MvcResult>({ w.s.post(w.base, bad) }, { w.s.patch("${w.base}/$id", bad) }, { w.s.put("${w.base}/$id/credential", bad) },
                { w.s.post("${w.base}/$id/queries", bad) }, { w.s.post("${w.base}/$id/mutations", bad) }, { w.s.patch("${w.base}/$id/queries/x", bad) }))
                assertThat(status(call())).describedAs(bad).isEqualTo(400)
        }
    }

    // ------------------------------------------------------------------------------------------------ only a USER actor

    @Test
    fun `an actor that is not a USER is denied by every service, whatever role its user id holds`() {
        val w = admin(); val id = UUID.fromString(w.created(type = "postgres", config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop", "writable" to true)))
        val p = app(); val src = source(p.tenant, p.sc.ws)
        val before = jdbc.queryForObject("SELECT version FROM data_sources WHERE id = ?", Long::class.java, id)
        fun ctx(kind: ActorKind, ws: UUID, project: UUID? = null) = GatewayContext(TenantContext(w.tenant, null, TenantStatus.ACTIVE, false), w.user.id, kind, "req-1", ws, project)
        for (kind in listOf(ActorKind.SYSTEM, ActorKind.APP_TOKEN, ActorKind.SERVICE)) {
            val c = ctx(kind, w.ws)
            val calls = listOf<Pair<String, () -> Any?>>(
                "list" to { dataSources.list(c) }, "get" to { dataSources.get(c, id) }, "catalogue" to { dataSources.connectorCatalog(c) },
                "create" to { dataSources.create(c, DataSourceSpec("x.$kind", "fake", emptyMap())) }, "patch" to { dataSources.patch(c, id, DataSourcePatch(name = "x.patch")) },
                "disable" to { dataSources.setStatus(c, id, DataSourceStatus.DISABLED) }, "credential" to { dataSources.rotateCredential(c, id, mapOf("username" to "u", "password" to "p")) },
                "credential info" to { dataSources.credentialInfo(c, id) }, "remove credential" to { dataSources.removeCredential(c, id) }, "delete" to { dataSources.delete(c, id) },
                "discover" to { discovery.refresh(c, id) }, "stored schema" to { discovery.stored(c, id) },
                "list queries" to { definitions.listQueries(c, id) }, "create query" to { definitions.createQuery(c, id, QueryDefinitionCreate("q", "SQL", body("""{"sql":"SELECT 1"}"""), DefinitionStatus.ACTIVE)) },
                "get query" to { definitions.getQuery(c, id, "q") }, "patch query" to { definitions.updateQuery(c, id, "q", DefinitionPatch(status = DefinitionStatus.DISABLED)) }, "delete query" to { definitions.deleteQuery(c, id, "q") },
                "list mutations" to { definitions.listMutations(c, id) }, "create mutation" to { definitions.createMutation(c, id, MutationDefinitionCreate("m", MutationKind.CREATE, body("""{"target":"shop.orders","params":[{"name":"a","type":"STRING"}]}"""))) },
                "patch mutation" to { definitions.updateMutation(c, id, "m", DefinitionPatch(status = DefinitionStatus.DISABLED)) }, "delete mutation" to { definitions.deleteMutation(c, id, "m") },
                "bind" to { bindings.bind(ctx(kind, p.sc.ws, p.sc.projectId).copy(tenant = TenantContext(p.tenant, null, TenantStatus.ACTIVE, false), actorUserId = p.adminUser.id), ExecutionMode.LIVE, "erp-db", src.id) },
                "list bindings" to { bindings.list(ctx(kind, p.sc.ws, p.sc.projectId).copy(tenant = TenantContext(p.tenant, null, TenantStatus.ACTIVE, false), actorUserId = p.adminUser.id)) })
            for ((label, call) in calls) {
                val e = try { call(); null } catch (e: ConnectorFailure) { e }
                assertThat(e).describedAs("$kind $label").isNotNull(); assertThat(e!!.code).describedAs("$kind $label").isEqualTo(FailureCodes.PERMISSION_DENIED)
            }
        }
        assertThat(jdbc.queryForObject("SELECT version FROM data_sources WHERE id = ?", Long::class.java, id)).isEqualTo(before)
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", id)).isZero(); assertThat(count("SELECT count(*) FROM data_source_bindings WHERE project_id = ?", p.sc.projectId)).isZero()
        // the same user id as a USER is allowed: it is the actor kind that was refused
        assertThat(dataSources.list(ctx(ActorKind.USER, w.ws)).map { it.id }).contains(id)
    }

    // ------------------------------------------------------------------------------------------------ no secret anywhere

    @Test
    fun `a recognisable secret fed through every route that can carry one appears nowhere`() {
        connector.discovered = DiscoveredSchema(listOf(DiscoveredEntity("t", "public", EntityKind.TABLE, listOf(DiscoveredField("id", NormalizedType.INTEGER, false, "int4", true)))))
        val s1 = "pw-S1-ONLY-IN-CREATE-71c2"; val s2 = "pw-S2-ONLY-IN-PUT-8e3d"; val s3 = "pw-S3-ONLY-IN-ROTATE-90ab"; val s4 = "pw-S4-WRONG-KEYS-1f77"
        OurLogs().use { logs ->
            val w = admin(); val rest = mutableListOf<org.springframework.test.web.servlet.MvcResult>()
            val id = w.s.body(w.create(credential = mapOf("authValue" to s1)).also { rest += it }).get("id").asString()
            rest += w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"$s2"}}""")
            rest += w.s.get("${w.base}/$id/credential"); rest += w.s.get("${w.base}/$id"); rest += w.s.get(w.base); rest += w.s.post("${w.base}/$id/test", "{}")
            rest += w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"$s3"}}""")
            rest += w.s.post("${w.base}/$id/schema/discover", "{}"); rest += w.s.get("${w.base}/$id/schema")
            // refused requests that carry a secret: bad shape, bad keys (postgres), authority key next to a secret, wrong content type
            val pg = w.created(type = "postgres", config = mapOf("host" to "db.example.com", "database" to "shop"))
            rest += w.s.put("${w.base}/$pg/credential", """{"credential":{"token":"$s4"}}"""); rest += w.s.put("${w.base}/$pg/credential", """{"credential":{"password":"$s4"},"tenantId":"x"}""")
            rest += w.s.put("${w.base}/$pg/credential", """{"credential":{"a":1,"password":"$s4"}}"""); rest += w.s.post(w.base, """{"name":"n","type":"fake","config":{"password":"$s4"}}""")
            rest += w.s.post(w.base, """{"name":"n","type":"fake","config":{"url":"https://user:$s4@host.example.com/x"}}""")
            for (secretValue in listOf(s1, s2, s3, s4)) assertNoSecretAnywhere(secretValue, *rest.toTypedArray())
            for (secretValue in listOf(s1, s2, s3, s4)) assertThat(logs.text).describedAs("log lines").doesNotContain(secretValue)
            assertThat(connector.credentialsSeen.map { it.get("authValue") }).describedAs("the connector DID receive the stored credential (the secrets are real)").contains(s2)
        }
    }

    @Test
    fun `the application is not configured to log request bodies - the web framework is not at DEBUG or TRACE`() {
        // Spring logs "Read application/json to [<body>]" and the handler arguments at DEBUG/TRACE: with a credential in the body that would be a leak.
        // The configured levels (application.yml / application-prod.yml) must never enable it; MANAGEMENT_API.md tells operators the same.
        for (name in listOf("org.springframework.web", "org.springframework.web.servlet.DispatcherServlet", "org.springframework.web.servlet.mvc.method.annotation", "org.springframework.http.converter"))
            assertThat(LoggerFactory.getLogger(name).isDebugEnabled).describedAs(name).isFalse()
        for (file in listOf("application.yml", "application-prod.yml"))
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/$file"))).describedAs(file).doesNotContain("org.springframework.web: DEBUG").doesNotContain("org.springframework.web: TRACE")
    }

    @Test
    fun `toString of every request and domain object leaves out secrets and bodies`() {
        val s = "pw-TOSTRING-4d9b"
        val spec = DataSourceSpec("n", "postgres", mapOf("host" to "h"), mapOf("password" to s))
        assertThat(spec.toString()).doesNotContain(s)
        assertThat(DataSourcePatch("n", mapOf("k" to s), DataSourceStatus.ACTIVE, 3).toString()).doesNotContain(s)
        assertThat(QueryDefinitionCreate("q", "SQL", body("""{"sql":"SELECT '$s'"}""")).toString()).doesNotContain(s)
        assertThat(MutationDefinitionCreate("m", MutationKind.CREATE, body("""{"target":"t","entity":"$s"}""")).toString()).doesNotContain(s)
        assertThat(DefinitionPatch(body("""{"sql":"$s"}"""), DefinitionStatus.ACTIVE, 1).toString()).doesNotContain(s)
        val failure = ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "credential keys do not fit this connector")
        assertThat(failure.toString() + failure.message).doesNotContain(s)
    }
}
