package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.datasource.ConnectorCapability
import com.systemwebstudio.data.datasource.ConnectorDescriptor
import com.systemwebstudio.data.datasource.ConnectorStatus
import com.systemwebstudio.data.datasource.CredentialInfo
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.DataSourceView
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The strict request parsers and safe response projections of the Data Source Management API. */
class ManagementHttpTests {
    private val secret = "sk-live-HTTP-SECRET-4242"
    private fun json(s: String) = DataJson.parse(s.toByteArray())
    private fun code(block: () -> Unit): String { try { block() } catch (e: com.systemwebstudio.data.datasource.ConnectorFailure) { assertThat(e.toString() + e.message).doesNotContain(secret); return e.code }; throw AssertionError("expected a ConnectorFailure") }

    @Test fun `create takes name type config and an optional credential and nothing else`() {
        val spec = ManagementRequests.create(json("""{"name":"billing","type":"postgres","config":{"host":"db.example.com","port":5432,"sslmode":"verify-full","writable":true},"credential":{"user":"u","password":"$secret"}}"""))
        assertThat(spec.name).isEqualTo("billing"); assertThat(spec.type).isEqualTo("postgres")
        assertThat(spec.config["port"]).isEqualTo("5432"); assertThat(spec.config["writable"]).isEqualTo("true"); assertThat(spec.config["host"]).isEqualTo("db.example.com")
        assertThat(spec.credential!!["password"]).isEqualTo(secret)
        assertThat(spec.toString()).doesNotContain(secret)
        assertThat(ManagementRequests.create(json("""{"name":"n","type":"postgres"}""")).config).isEmpty()
        for (body in listOf(
            """{"name":"n","type":"t","tenantId":"$secret"}""", """{"name":"n","type":"t","workspaceId":"x"}""", """{"name":"n","type":"t","credentialRef":"x"}""", """{"name":"n","type":"t","status":"ACTIVE"}""",
            """{"type":"t"}""", """{"name":"n"}""", """{"name":1,"type":"t"}""", """{"name":"n","type":"t","config":[]}""", """{"name":"n","type":"t","config":{"a":{"b":1}}}""",
            """{"name":"n","type":"t","config":{"a":1.5}}""", """{"name":"n","type":"t","config":{"a":null}}""", """{"name":"n","type":"t","credential":"$secret"}""", """{"name":"n","type":"t","credential":{}}""",
            """{"name":"n","type":"t","credential":{"a":1}}""", """{"name":"n","type":"t","credential":{"a":{"b":"$secret"}}}""", """[]""", """"text""""))
            assertThat(code { ManagementRequests.create(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(code { ManagementRequests.create(null) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(code { ManagementRequests.create(json("""{"name":"n","type":"t","credential":{${(1..9).joinToString(",") { "\"k$it\":\"v\"" }}}}""")) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `update accepts name config and status and needs at least one`() {
        val c = ManagementRequests.update(json("""{"name":"x","config":{"host":"h"},"status":"DISABLED"}"""))
        assertThat(c.name).isEqualTo("x"); assertThat(c.config!!["host"]).isEqualTo("h"); assertThat(c.status).isEqualTo(DataSourceStatus.DISABLED)
        assertThat(ManagementRequests.update(json("""{"status":"ACTIVE"}""")).status).isEqualTo(DataSourceStatus.ACTIVE)
        for (body in listOf("""{}""", """{"status":"DELETED"}""", """{"status":"active"}""", """{"credential":{"a":"$secret"}}""", """{"name":null}""", """{"config":null}""", """{"type":"mysql"}""", """{"tenantId":"x"}"""))
            assertThat(code { ManagementRequests.update(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `the credential body is write only and strict`() {
        assertThat(ManagementRequests.credential(json("""{"credential":{"user":"u","password":"$secret"}}"""))["password"]).isEqualTo(secret)
        for (body in listOf("""{}""", """{"credential":{}}""", """{"credential":"$secret"}""", """{"credential":{"a":"b"},"extra":1}""", """{"password":"$secret"}"""))
            assertThat(code { ManagementRequests.credential(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `binding targets slots and ids are validated and a bad id looks like a missing one`() {
        val id = UUID.randomUUID()
        assertThat(ManagementRequests.bindingTarget(json("""{"dataSourceId":"$id"}"""))).isEqualTo(id)
        for (body in listOf("""{}""", """{"dataSourceId":"nope"}""", """{"dataSourceId":1}""", """{"dataSourceId":"$id","mode":"LIVE"}"""))
            assertThat(code { ManagementRequests.bindingTarget(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(ManagementRequests.id(id.toString())).isEqualTo(id)
        assertThat(code { ManagementRequests.id("../etc/passwd") }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(ManagementRequests.slot("main")).isEqualTo("main"); assertThat(ManagementRequests.slot("crm.v2-a_b")).isEqualTo("crm.v2-a_b")
        for (bad in listOf("", " ", "-x", "a/b", "a b", "a;b", "x".repeat(129), "é")) assertThat(code { ManagementRequests.slot(bad) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `responses carry only the safe projection`() {
        val id = UUID.randomUUID(); val ws = UUID.randomUUID(); val now = Instant.parse("2026-10-05T10:00:00Z")
        val view = DataSourceView(id, UUID.randomUUID(), ws, "billing", "postgres", mapOf("host" to "db.example.com"), true, DataSourceStatus.ACTIVE, UUID.randomUUID(), now, now, 3)
        val node = ManagementResponses.dataSource(view)
        assertThat(DataJson.keys(node)).containsExactlyInAnyOrder("id", "workspaceId", "name", "type", "config", "hasCredential", "status", "version", "createdBy", "createdAt", "updatedAt")
        assertThat(node.get("hasCredential").asBoolean()).isTrue(); assertThat(node.get("version").asLong()).isEqualTo(3L)
        assertThat(node.toString()).doesNotContain("tenantId").doesNotContain("credentialRef")
        assertThat(DataJson.elements(ManagementResponses.dataSources(listOf(view)).get("items"))).hasSize(1)

        val by = UUID.randomUUID()
        val cred = ManagementResponses.credential(CredentialInfo(true, "postgres", listOf("user", "password"), now, by))
        assertThat(DataJson.keys(cred)).containsExactlyInAnyOrder("configured", "type", "keys", "updatedAt", "updatedBy")
        assertThat(cred.get("configured").asBoolean()).isTrue(); assertThat(cred.get("type").asString()).isEqualTo("postgres")
        assertThat(cred.get("updatedBy").asString()).isEqualTo(by.toString()); assertThat(cred.get("updatedAt").asString()).isEqualTo(now.toString())
        assertThat(DataJson.elements(cred.get("keys")).map { it.asString() }).containsExactly("user", "password")
        val none = ManagementResponses.credential(CredentialInfo(false, "postgres", listOf("user", "password"), null, null))
        assertThat(none.get("configured").asBoolean()).isFalse(); assertThat(none.get("updatedAt").isNull).isTrue(); assertThat(none.get("updatedBy").isNull).isTrue()

        val b = ManagementResponses.binding("LIVE", "main", id, now)
        assertThat(DataJson.keys(b)).containsExactlyInAnyOrder("mode", "slotId", "dataSourceId", "updatedAt")
        val tested = GatewayResponses.connection(com.systemwebstudio.data.datasource.ConnectionTestResult.Ok(7, listOf("writable is enabled but the database role has no write privilege on the configured schemas")))
        assertThat(DataJson.keys(tested)).containsExactlyInAnyOrder("ok", "latencyMs", "warnings")
        assertThat(DataJson.elements(tested.get("warnings")).map { it.asString() }).containsExactly("writable is enabled but the database role has no write privilege on the configured schemas")
        assertThat(DataJson.elements(GatewayResponses.connection(com.systemwebstudio.data.datasource.ConnectionTestResult.Ok(1)).get("warnings"))).isEmpty()
        val catalog = ManagementResponses.connectors(listOf(ConnectorDescriptor("postgres", "PostgreSQL", ConnectorStatus.AVAILABLE, setOf(ConnectorCapability.QUERY, ConnectorCapability.MUTATION), credentialKeys = listOf("user", "password"))))
        assertThat(DataJson.elements(catalog.get("items"))[0].get("capabilities").toString()).contains("MUTATION")
    }

    @Test fun `update takes expectedVersion as a positive whole number and it never counts as a change`() {
        val c = ManagementRequests.update(json("""{"name":"x","expectedVersion":3}"""))
        assertThat(c.expectedVersion).isEqualTo(3L); assertThat(c.name).isEqualTo("x")
        assertThat(ManagementRequests.update(json("""{"name":"x"}""")).expectedVersion).isNull()
        for (body in listOf("""{"expectedVersion":3}""", """{"name":"x","expectedVersion":0}""", """{"name":"x","expectedVersion":-1}""", """{"name":"x","expectedVersion":"3"}""", """{"name":"x","expectedVersion":1.5}""", """{"name":"x","expectedVersion":null}""", """{"name":"x","version":3}"""))
            assertThat(code { ManagementRequests.update(json(body)) }).describedAs(body).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `the binding mode is exactly TEST or LIVE`() {
        assertThat(ManagementRequests.bindingMode("TEST")).isEqualTo("TEST"); assertThat(ManagementRequests.bindingMode("LIVE")).isEqualTo("LIVE")
        for (bad in listOf("test", "live", "Test", "Live", "tEST", "", " TEST", "LIVE ", "STAGING", "PROD", "TEST,LIVE", "LIVEE")) assertThat(code { ManagementRequests.bindingMode(bad) }).describedAs(bad).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `the discover body is optional and has one boolean key`() {
        assertThat(ManagementRequests.discover(null)).isFalse(); assertThat(ManagementRequests.discover(json("{}"))).isFalse()
        assertThat(ManagementRequests.discover(json("""{"includeSamples":false}"""))).isFalse(); assertThat(ManagementRequests.discover(json("""{"includeSamples":true}"""))).isTrue()
        for (body in listOf("""{"includeSamples":"true"}""", """{"includeSamples":1}""", """{"includeSamples":null,"x":1}""", """{"sql":"x"}""", """{"tenantId":"x"}""", "[]"))
            assertThat(code { ManagementRequests.discover(json(body)) }).describedAs(body).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `definition requests are strict - ids, kinds, statuses, documents and authority keys`() {
        val q = ManagementRequests.queryCreate(json("""{"queryId":"a.b-c_1","kind":"SQL","definition":{"sql":"SELECT 1"},"status":"DISABLED"}"""))
        assertThat(q.queryId).isEqualTo("a.b-c_1"); assertThat(q.kind).isEqualTo("SQL"); assertThat(q.status).isEqualTo(com.systemwebstudio.data.query.DefinitionStatus.DISABLED)
        assertThat(ManagementRequests.queryCreate(json("""{"queryId":"x","kind":"REST","definition":{}}""")).status).isEqualTo(com.systemwebstudio.data.query.DefinitionStatus.ACTIVE)
        val m = ManagementRequests.mutationCreate(json("""{"mutationId":"m1","kind":"DELETE","definition":{"target":"t"}}"""))
        assertThat(m.mutationId).isEqualTo("m1"); assertThat(m.kind).isEqualTo(com.systemwebstudio.data.query.MutationKind.DELETE)
        for (body in listOf("""{"kind":"SQL","definition":{}}""", """{"queryId":"x","definition":{}}""", """{"queryId":"x","kind":"SQL"}""", """{"queryId":"x","kind":"sql","definition":{}}""", """{"queryId":"x","kind":"GRAPHQL","definition":{}}""",
            """{"queryId":"-x","kind":"SQL","definition":{}}""", """{"queryId":"a b","kind":"SQL","definition":{}}""", """{"queryId":"${"x".repeat(65)}","kind":"SQL","definition":{}}""", """{"queryId":"x","kind":"SQL","definition":"select"}""",
            """{"queryId":"x","kind":"SQL","definition":[]}""", """{"queryId":"x","kind":"SQL","definition":{},"status":"NOPE"}""", """{"queryId":"x","kind":"SQL","definition":{},"tenantId":"t"}""",
            """{"queryId":"x","kind":"SQL","definition":{},"dataSourceId":"d"}""", """{"queryId":"x","kind":"SQL","definition":{},"version":1}"""))
            assertThat(code { ManagementRequests.queryCreate(json(body)) }).describedAs(body).isEqualTo(FailureCodes.INVALID_PARAMS)
        for (body in listOf("""{"mutationId":"x","kind":"MERGE","definition":{}}""", """{"mutationId":"x","kind":"create","definition":{}}""", """{"mutationId":"x","kind":"CREATE","definition":{},"createdBy":"u"}""", """{"kind":"CREATE","definition":{}}"""))
            assertThat(code { ManagementRequests.mutationCreate(json(body)) }).describedAs(body).isEqualTo(FailureCodes.INVALID_PARAMS)
        val p = ManagementRequests.definitionPatch(json("""{"definition":{"sql":"SELECT 2"},"status":"ACTIVE","expectedVersion":4}"""))
        assertThat(p.definition).isNotNull(); assertThat(p.status).isEqualTo(com.systemwebstudio.data.query.DefinitionStatus.ACTIVE); assertThat(p.expectedVersion).isEqualTo(4L)
        assertThat(ManagementRequests.definitionPatch(json("""{"status":"DISABLED"}""")).definition).isNull()
        for (body in listOf("{}", """{"expectedVersion":1}""", """{"definition":"x"}""", """{"status":"x"}""", """{"status":"ACTIVE","tenantId":"t"}""", """{"status":"ACTIVE","queryId":"other"}""", """{"status":"ACTIVE","expectedVersion":0}"""))
            assertThat(code { ManagementRequests.definitionPatch(json(body)) }).describedAs(body).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(ManagementRequests.definitionId("ok.id-1")).isEqualTo("ok.id-1")
        for (bad in listOf("", "-x", "a b", "a/b", "x".repeat(65), "é")) assertThat(code { ManagementRequests.definitionId(bad) }).describedAs(bad).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `definition summaries carry no SQL and no template, the full read carries the document and neither carries identity`() {
        val t = UUID.randomUUID(); val d = UUID.randomUUID(); val now = Instant.parse("2026-10-05T10:00:00Z")
        val q = com.systemwebstudio.data.query.StoredQueryDefinition(com.systemwebstudio.data.query.SqlQueryDefinition("q", t, d, "SELECT secret_looking_column FROM t WHERE a = :a",
            listOf(com.systemwebstudio.data.query.QueryParamSpec("a", com.systemwebstudio.data.query.ParamType.STRING)), 10), com.systemwebstudio.data.query.DefinitionStatus.ACTIVE, now, now)
        val m = com.systemwebstudio.data.query.StoredMutationDefinition(com.systemwebstudio.data.query.MutationDefinition("m", t, d, com.systemwebstudio.data.query.MutationKind.CREATE, "shop.orders",
            listOf(com.systemwebstudio.data.query.QueryParamSpec("customer", com.systemwebstudio.data.query.ParamType.STRING))), com.systemwebstudio.data.query.DefinitionStatus.DISABLED, now, now)
        val qs = ManagementResponses.querySummaries(listOf(q)).toString(); val ms = ManagementResponses.mutationSummaries(listOf(m)).toString()
        assertThat(qs).doesNotContain("SELECT").doesNotContain("secret_looking_column").contains("\"params\":[\"a\"]"); assertThat(ms).doesNotContain("shop.orders").contains("\"params\":[\"customer\"]")
        val qf = ManagementResponses.queryDefinition(q); val mf = ManagementResponses.mutationDefinition(m)
        assertThat(DataJson.keys(qf)).containsExactlyInAnyOrder("queryId", "kind", "status", "version", "definition", "createdAt", "updatedAt")
        assertThat(DataJson.keys(mf)).containsExactlyInAnyOrder("mutationId", "kind", "status", "version", "definition", "createdAt", "updatedAt")
        assertThat(qf.get("definition").get("sql").asString()).contains("secret_looking_column"); assertThat(mf.get("definition").get("target").asString()).isEqualTo("shop.orders"); assertThat(mf.get("status").asString()).isEqualTo("DISABLED")
        assertThat(qf.toString() + mf.toString() + qs + ms).doesNotContain(t.toString()).doesNotContain(d.toString())
    }
}
