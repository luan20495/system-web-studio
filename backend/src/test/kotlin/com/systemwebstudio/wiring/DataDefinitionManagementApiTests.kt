package com.systemwebstudio.wiring

import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID

/**
 * Management API contract §3.5: approved query and mutation definitions, scoped by data source, over the real stack (Spring, PostgreSQL, C1 access, the real
 * audit table). The runtime catalogs (`JdbcQueryCatalog`, `JdbcMutationCatalog`) are asked afterwards, so what the API stores is proven to be what the gateway loads.
 */
@Import(ManagementTestBeans::class)
class DataDefinitionManagementApiTests : ManagementApiTestBase() {
    private val sql = """{"sql":"SELECT id, name FROM customers WHERE plan = :plan","params":[{"name":"plan","type":"STRING"}],"maxRows":50}"""
    private fun queryBody(id: String = "customers.byPlan", kind: String = "SQL", definition: String = sql, extra: String = "") = """{"queryId":"$id","kind":"$kind","definition":$definition$extra}"""
    private val mutation = """{"target":"shop.orders returning=id","params":[{"name":"customer","type":"STRING"},{"name":"amount","type":"NUMBER","required":false}],"invalidates":["orders.list"],"entity":"orders"}"""
    private fun mutationBody(id: String = "orders.create", kind: String = "CREATE", definition: String = mutation, extra: String = "") = """{"mutationId":"$id","kind":"$kind","definition":$definition$extra}"""

    private fun W.q(id: String) = "$base/$id/queries"
    private fun W.m(id: String) = "$base/$id/mutations"

    // ------------------------------------------------------------------------------------------------ queries

    @Test
    fun `query definition lifecycle - create, list summary, full read, patch with expectedVersion, disable, delete`() {
        val w = admin(); val id = w.created(); val ds = UUID.fromString(id)
        val c = w.s.post(w.q(id), queryBody())
        assertThat(status(c)).describedAs(c.response.contentAsString).isEqualTo(201)
        val b = w.s.body(c)
        assertThat(b.get("queryId").asString()).isEqualTo("customers.byPlan"); assertThat(b.get("kind").asString()).isEqualTo("SQL")
        assertThat(b.get("status").asString()).isEqualTo("ACTIVE"); assertThat(b.get("version").asLong()).isEqualTo(1L)
        assertThat(b.get("definition").get("sql").asString()).contains("customers"); assertThat(b.get("definition").get("maxRows").asInt()).isEqualTo(50)
        assertThat(c.response.contentAsString).doesNotContain(w.tenant.toString()).doesNotContain(id)

        val list = w.s.get(w.q(id)); assertThat(status(list)).isEqualTo(200)
        val item = DataJson.elements(w.s.body(list).get("items")).single()
        assertThat(DataJson.keys(item)).describedAs("summary: no SQL, no template").containsExactlyInAnyOrder("queryId", "kind", "status", "version", "params")
        assertThat(item.get("params").get(0).asString()).isEqualTo("plan")
        assertThat(list.response.contentAsString).doesNotContain("SELECT")

        val full = w.s.get("${w.q(id)}/customers.byPlan"); assertThat(status(full)).isEqualTo(200)
        assertThat(w.s.body(full).get("definition").get("params").get(0).get("type").asString()).isEqualTo("STRING")
        assertThat(JdbcQueryCatalog(jdbc).find(w.tenant, ds, "customers.byPlan")).describedAs("the gateway loads exactly what the API stored").isNotNull()

        val patched = w.s.patch("${w.q(id)}/customers.byPlan", """{"definition":{"sql":"SELECT id FROM customers WHERE plan = :plan","params":[{"name":"plan","type":"STRING"}]},"expectedVersion":1}""")
        assertThat(status(patched)).describedAs(patched.response.contentAsString).isEqualTo(200); assertThat(w.s.body(patched).get("version").asLong()).isEqualTo(2L)
        assertThat(w.s.body(patched).get("definition").get("sql").asString()).isEqualTo("SELECT id FROM customers WHERE plan = :plan")
        val stale = w.s.patch("${w.q(id)}/customers.byPlan", """{"status":"DISABLED","expectedVersion":1}""")
        assertThat(status(stale)).isEqualTo(409); assertThat(code(w.s, stale)).isEqualTo("CONFLICT")
        assertThat(w.s.body(w.s.get("${w.q(id)}/customers.byPlan")).get("status").asString()).isEqualTo("ACTIVE")

        val off = w.s.patch("${w.q(id)}/customers.byPlan", """{"status":"DISABLED"}""")
        assertThat(status(off)).isEqualTo(200); assertThat(w.s.body(off).get("version").asLong()).isEqualTo(3L)
        assertThat(JdbcQueryCatalog(jdbc).find(w.tenant, ds, "customers.byPlan")).describedAs("a DISABLED definition cannot run").isNull()

        assertThat(status(w.s.delete("${w.q(id)}/customers.byPlan"))).isEqualTo(204)
        assertThat(status(w.s.get("${w.q(id)}/customers.byPlan"))).isEqualTo(404); assertThat(status(w.s.delete("${w.q(id)}/customers.byPlan"))).isEqualTo(404)
        assertThat(DataJson.elements(w.s.body(w.s.get(w.q(id))).get("items"))).isEmpty()
    }

    @Test
    fun `a REST query definition is stored and loaded too, and an id is unique per data source`() {
        val w = admin(); val id = w.created(); val ds = UUID.fromString(id)
        val rest = """{"pathTemplate":"/customers/{id}","params":[{"name":"id","type":"STRING"}],"rowsPointer":"","maxRows":10,"discoverable":true}"""
        val c = w.s.post(w.q(id), queryBody("customer.get", "REST", rest))
        assertThat(status(c)).describedAs(c.response.contentAsString).isEqualTo(201); assertThat(w.s.body(c).get("kind").asString()).isEqualTo("REST")
        assertThat(JdbcQueryCatalog(jdbc).find(w.tenant, ds, "customer.get")).isNotNull()
        val dup = w.s.post(w.q(id), queryBody("customer.get", "REST", rest))
        assertThat(status(dup)).isEqualTo(409); assertThat(code(w.s, dup)).isEqualTo("CONFLICT")
        val other = w.created()
        assertThat(status(w.s.post(w.q(other), queryBody("customer.get", "REST", rest)))).describedAs("same id on another data source is fine").isEqualTo(201)
    }

    @Test
    fun `an invalid query definition is INVALID_QUERY (or INVALID_PARAMS for a bad id or kind) and nothing is stored`() {
        val w = admin(); val id = w.created()
        val invalid = listOf(
            """{"sql":"SELECT 1","unknown":1}""", """{"sql":1}""", """{"params":[]}""", """{"sql":"SELECT 1","maxRows":0}""", """{"sql":"SELECT 1","maxRows":"10"}""",
            """{"sql":"SELECT 1","cacheTtlSeconds":99999999}""", """{"sql":"SELECT 1","params":[{"name":"Bad Name","type":"STRING"}]}""", """{"sql":"SELECT 1","params":[{"name":"a","type":"NOPE"}]}""",
            """{"sql":"SELECT 1","params":[{"name":"a","type":"STRING","extra":1}]}""", """{"sql":"SELECT 1","params":"x"}""", """{"sql":"SELECT 1","params":[{"name":"a","type":"STRING","default":{"x":1}}]}""")
        for (d in invalid) { val r = w.s.post(w.q(id), queryBody(definition = d)); assertThat(status(r)).describedAs(d).isEqualTo(400); assertThat(code(w.s, r)).describedAs(d).isEqualTo("INVALID_QUERY") }
        for (bad in listOf(queryBody(kind = "GRAPHQL"), queryBody(kind = "sql"), queryBody(id = "-bad"), queryBody(id = "has space"), queryBody(id = "x".repeat(65)), """{"queryId":"a","kind":"SQL"}""",
            queryBody(extra = ""","tenantId":"${w.tenant}""""), queryBody(extra = ""","dataSourceId":"$id""""), queryBody(extra = ""","version":1"""), queryBody(extra = ""","createdBy":"x""""),
            queryBody(extra = ""","status":"DELETED""""), "[]", "{}"))
            assertThat(status(w.s.post(w.q(id), bad))).describedAs(bad).isEqualTo(400)
        val rest = w.s.post(w.q(id), queryBody("r.bad", "REST", """{"pathTemplate":"//host/x"}"""))
        assertThat(status(rest)).isEqualTo(400); assertThat(code(w.s, rest)).isEqualTo("INVALID_QUERY")
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", UUID.fromString(id))).isZero()
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'DATA_QUERY_DEFINITION_CHANGED'", id)).isZero()
        for (bad in listOf("{}", """{"status":"NOPE"}""", """{"expectedVersion":1}""", """{"definition":"x"}""", """{"tenantId":"${w.tenant}","status":"DISABLED"}""", """{"queryId":"other","status":"DISABLED"}"""))
            assertThat(status(w.s.patch("${w.q(id)}/nothing", bad))).describedAs(bad).isEqualTo(400)
    }

    @Test
    fun `a PostgreSQL data source takes only SQL the guard accepts, with every parameter declared`() {
        val w = admin(); val id = w.pg(writable = false)
        assertThat(status(w.s.post(w.q(id), queryBody("ok", definition = sql)))).describedAs("a read-only source still takes queries").isEqualTo(201)
        for (d in listOf("""{"sql":"DELETE FROM customers"}""", """{"sql":"SELECT 1; DROP TABLE x"}""", """{"sql":"SELECT * FROM customers FOR UPDATE"}""", """{"sql":"SELECT :undeclared"}""",
            """{"sql":"SELECT set_config('a','b',false)"}""", """{"sql":"SELECT * FROM pg_authid"}""")) {
            val r = w.s.post(w.q(id), queryBody("bad", definition = d))
            assertThat(status(r)).describedAs(d).isEqualTo(400); assertThat(code(w.s, r)).describedAs(d).isEqualTo("INVALID_QUERY")
            assertThat(r.response.contentAsString).describedAs("the offending text is never echoed").doesNotContain("pg_authid").doesNotContain("DROP TABLE")
        }
        val rest = w.s.post(w.q(id), queryBody("rest", "REST", """{"pathTemplate":"/x"}"""))
        assertThat(status(rest)).isEqualTo(400); assertThat(code(w.s, rest)).isEqualTo("INVALID_QUERY")
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
    }

    // ------------------------------------------------------------------------------------------------ mutations

    @Test
    fun `mutation definition lifecycle on a writable PostgreSQL source`() {
        val w = admin(); val id = w.pg(); val ds = UUID.fromString(id)
        val c = w.s.post(w.m(id), mutationBody())
        assertThat(status(c)).describedAs(c.response.contentAsString).isEqualTo(201)
        val b = w.s.body(c)
        assertThat(b.get("mutationId").asString()).isEqualTo("orders.create"); assertThat(b.get("kind").asString()).isEqualTo("CREATE"); assertThat(b.get("version").asLong()).isEqualTo(1L)
        assertThat(b.get("definition").get("target").asString()).isEqualTo("shop.orders returning=id"); assertThat(b.get("definition").get("entity").asString()).isEqualTo("orders")
        val m = JdbcMutationCatalog(jdbc).find(w.tenant, ds, "orders.create")
        assertThat(m).describedAs("the gateway loads exactly what the API stored").isNotNull(); assertThat(m!!.params.map { it.name }).containsExactly("customer", "amount")

        val item = DataJson.elements(w.s.body(w.s.get(w.m(id))).get("items")).single()
        assertThat(DataJson.keys(item)).containsExactlyInAnyOrder("mutationId", "kind", "status", "version", "params")
        assertThat(w.s.get(w.m(id)).response.contentAsString).describedAs("a summary carries no target").doesNotContain("shop.orders")
        assertThat(status(w.s.get("${w.m(id)}/orders.create"))).isEqualTo(200)

        val patched = w.s.patch("${w.m(id)}/orders.create", """{"definition":{"target":"shop.orders","params":[{"name":"customer","type":"STRING"}]},"status":"DISABLED","expectedVersion":1}""")
        assertThat(status(patched)).describedAs(patched.response.contentAsString).isEqualTo(200)
        assertThat(w.s.body(patched).get("version").asLong()).isEqualTo(2L); assertThat(w.s.body(patched).get("status").asString()).isEqualTo("DISABLED")
        assertThat(JdbcMutationCatalog(jdbc).find(w.tenant, ds, "orders.create")).describedAs("DISABLED cannot run").isNull()
        val stale = w.s.patch("${w.m(id)}/orders.create", """{"status":"ACTIVE","expectedVersion":1}""")
        assertThat(status(stale)).isEqualTo(409)
        assertThat(status(w.s.delete("${w.m(id)}/orders.create"))).isEqualTo(204)
        assertThat(status(w.s.get("${w.m(id)}/orders.create"))).isEqualTo(404)
    }

    @Test
    fun `a mutation definition needs a data source that may write, and a statement the connector can express`() {
        val w = admin()
        val readOnly = w.pg(writable = false)
        val ro = w.s.post(w.m(readOnly), mutationBody())
        assertThat(status(ro)).isEqualTo(422); assertThat(code(w.s, ro)).isEqualTo("READ_ONLY_VIOLATION")
        val rest = w.created(type = "rest", config = mapOf("baseUrl" to "https://api.example.com"))
        val roRest = w.s.post(w.m(rest), mutationBody())
        assertThat(status(roRest)).isEqualTo(422); assertThat(code(w.s, roRest)).isEqualTo("READ_ONLY_VIOLATION")
        connector.readOnly = true
        val fake = w.created()
        assertThat(status(w.s.post(w.m(fake), mutationBody()))).describedAs("a connector with no mutator").isEqualTo(422)
        connector.readOnly = false
        val id = w.pg()
        for ((label, body) in listOf(
            "UPDATE without a key" to mutationBody("u1", "UPDATE", """{"target":"shop.orders","params":[{"name":"customer","type":"STRING"}]}"""),
            "schema not configured" to mutationBody("u2", definition = """{"target":"other.orders","params":[{"name":"customer","type":"STRING"}]}"""),
            "an injection in the target" to mutationBody("u3", definition = """{"target":"shop.orders; DROP TABLE x","params":[{"name":"customer","type":"STRING"}]}"""),
            "DELETE with a non-key param" to mutationBody("u4", "DELETE", """{"target":"shop.orders key=id","params":[{"name":"id","type":"INTEGER"},{"name":"x","type":"STRING"}]}"""),
            "no parameters at all" to mutationBody("u5", definition = """{"target":"shop.orders"}"""),
            "SUBMIT is not a database operation" to mutationBody("u6", "SUBMIT", """{"target":"shop.orders","params":[{"name":"a","type":"STRING"}]}"""))) {
            val r = w.s.post(w.m(id), body)
            assertThat(status(r)).describedAs(label).isEqualTo(400)
            assertThat(code(w.s, r)).describedAs(label).isIn("INVALID_CONFIG", "INVALID_QUERY")
            assertThat(r.response.contentAsString).describedAs("$label: nothing of the request is echoed").doesNotContain("DROP TABLE").doesNotContain("other.orders")
        }
        assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", UUID.fromString(id))).isZero()
        for (bad in listOf(mutationBody(kind = "MERGE"), mutationBody(kind = "create"), mutationBody(extra = ""","tenantId":"${w.tenant}""""), mutationBody(extra = ""","dataSourceId":"$id""""),
            mutationBody(definition = """{"target":"x","sql":"DELETE FROM t"}"""), mutationBody(definition = """{"target":1}"""), mutationBody(definition = """{"target":"shop.orders","invalidates":"all"}""")))
            assertThat(status(w.s.post(w.m(id), bad))).describedAs(bad).isEqualTo(400)
    }

    @Test
    fun `a mutation cannot be deleted while a write through it is in flight or of unknown outcome`() {
        val w = admin(); val id = w.pg(); val ds = UUID.fromString(id)
        assertThat(status(w.s.post(w.m(id), mutationBody()))).isEqualTo(201)
        for (state in listOf("RESERVED", "UNKNOWN")) {
            jdbc.update("""INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at)
                VALUES (?, ?, 'orders.create', ?, 'fp', ?, now() + interval '1 hour', now() + interval '30 days')""", w.tenant, ds, "key-def-$state-01", state)
            val r = w.s.delete("${w.m(id)}/orders.create")
            assertThat(status(r)).describedAs(state).isEqualTo(409); assertThat(code(w.s, r)).isEqualTo("CONFLICT")
            assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", ds)).isEqualTo(1L)
            jdbc.update("DELETE FROM data_idempotency WHERE data_source_id = ?", ds)
        }
        jdbc.update("""INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at)
            VALUES (?, ?, 'orders.create', 'key-def-done-01', 'fp', 'DONE', now(), now() + interval '30 days')""", w.tenant, ds)
        assertThat(status(w.s.delete("${w.m(id)}/orders.create"))).describedAs("a finished write does not block").isEqualTo(204)
    }

    // ------------------------------------------------------------------------------------------------ audit: fixed fields only, fail closed

    @Test
    fun `every definition change is audited with ids, version, status and a hash - never the definition`() {
        val w = admin(); val id = w.pg(); val marker = "MARKER-9d41"
        val sqlWithMarker = """{"sql":"SELECT id FROM customers WHERE note = '$marker' AND plan = :plan","params":[{"name":"plan","type":"STRING"}]}"""
        assertThat(status(w.s.post(w.q(id), queryBody("a.q", definition = sqlWithMarker)))).isEqualTo(201)
        assertThat(status(w.s.patch("${w.q(id)}/a.q", """{"status":"DISABLED"}"""))).isEqualTo(200)
        assertThat(status(w.s.delete("${w.q(id)}/a.q"))).isEqualTo(204)
        assertThat(status(w.s.post(w.m(id), mutationBody("a.m", definition = """{"target":"shop.orders","params":[{"name":"customer","type":"STRING"}],"entity":"$marker"}""")))).isEqualTo(201)
        assertThat(status(w.s.patch("${w.m(id)}/a.m", """{"status":"DISABLED"}"""))).isEqualTo(200)
        assertThat(status(w.s.delete("${w.m(id)}/a.m"))).isEqualTo(204)
        val rows = jdbc.queryForList("SELECT action, new_value::text AS v FROM audit_events WHERE resource_id = ? AND action IN ('DATA_QUERY_DEFINITION_CHANGED','DATA_MUTATION_DEFINITION_CHANGED') ORDER BY created_at", id)
        assertThat(rows.map { it["action"] }).containsExactly("DATA_QUERY_DEFINITION_CHANGED", "DATA_QUERY_DEFINITION_CHANGED", "DATA_QUERY_DEFINITION_CHANGED",
            "DATA_MUTATION_DEFINITION_CHANGED", "DATA_MUTATION_DEFINITION_CHANGED", "DATA_MUTATION_DEFINITION_CHANGED")
        val first = DataJson.parse((rows[0]["v"] as String).toByteArray())
        assertThat(DataJson.keys(first)).contains("change", "queryId", "version", "status", "hash", "tenantId", "actor", "workspace")
        assertThat(first.get("change").asString()).isEqualTo("create"); assertThat(first.get("hash").asString()).matches("[0-9a-f]{64}")
        assertThat(rows.map { DataJson.parse((it["v"] as String).toByteArray()).get("change").asString() }).containsExactly("create", "update", "delete", "create", "update", "delete")
        assertThat(rows.joinToString { it["v"] as String }).describedAs("no definition text, no table, no marker in any audit row").doesNotContain(marker).doesNotContain("customers").doesNotContain("shop.orders")
    }

    @Test
    fun `a definition change that cannot be audited is not applied`() {
        val w = admin(); val id = w.pg(); val ds = UUID.fromString(id)
        assertThat(status(w.s.post(w.q(id), queryBody("keep.q")))).isEqualTo(201); assertThat(status(w.s.post(w.m(id), mutationBody("keep.m")))).isEqualTo(201)
        faultAudit.failOn = emptySet()
        assertThat(status(w.s.post(w.q(id), queryBody("new.q")))).isEqualTo(500); assertThat(status(w.s.post(w.m(id), mutationBody("new.m")))).isEqualTo(500)
        assertThat(status(w.s.patch("${w.q(id)}/keep.q", """{"status":"DISABLED"}"""))).isEqualTo(500); assertThat(status(w.s.patch("${w.m(id)}/keep.m", """{"status":"DISABLED"}"""))).isEqualTo(500)
        assertThat(status(w.s.delete("${w.q(id)}/keep.q"))).isEqualTo(500); assertThat(status(w.s.delete("${w.m(id)}/keep.m"))).isEqualTo(500)
        faultAudit.failOn = null
        assertThat(jdbc.queryForList("SELECT query_id, status, version FROM data_queries WHERE data_source_id = ?", ds)).hasSize(1).first().satisfies({ r -> assertThat(r["query_id"]).isEqualTo("keep.q"); assertThat(r["status"]).isEqualTo("ACTIVE"); assertThat(r["version"]).isEqualTo(1L) })
        assertThat(jdbc.queryForList("SELECT mutation_id, status, version FROM data_mutations WHERE data_source_id = ?", ds)).hasSize(1).first().satisfies({ r -> assertThat(r["mutation_id"]).isEqualTo("keep.m"); assertThat(r["status"]).isEqualTo("ACTIVE"); assertThat(r["version"]).isEqualTo(1L) })
        assertThat(status(w.s.delete("${w.q(id)}/keep.q"))).describedAs("and nothing is stuck").isEqualTo(204)
    }

    // ------------------------------------------------------------------------------------------------ permissions and isolation

    private fun routes(a: W, id: String): List<(ApiSession) -> MvcResult> = listOf(
        { it.get(a.q(id)) }, { it.get("${a.q(id)}/qq") }, { it.post(a.q(id), queryBody("n.q")) }, { it.patch("${a.q(id)}/qq", """{"status":"DISABLED"}""") }, { it.delete("${a.q(id)}/qq") },
        { it.get(a.m(id)) }, { it.get("${a.m(id)}/mm") }, { it.post(a.m(id), mutationBody("n.m")) }, { it.patch("${a.m(id)}/mm", """{"status":"DISABLED"}""") }, { it.delete("${a.m(id)}/mm") })

    @Test
    fun `without DATA_SOURCE_VIEW or MANAGE every route is 403, strangers get 404 and anonymous callers are refused`() {
        val a = admin(); val id = a.pg()
        assertThat(status(a.s.post(a.q(id), queryBody("qq")))).isEqualTo(201); assertThat(status(a.s.post(a.m(id), mutationBody("mm")))).isEqualTo(201)
        for (role in listOf("EDITOR", "PUBLISHER", "VIEWER")) {
            val (_, s) = member(a.ws, role)
            for (call in routes(a, id)) { val r = call(s); assertThat(status(r)).describedAs("$role ${r.request.method} ${r.request.requestURI}").isEqualTo(403); assertThat(code(s, r)).isEqualTo("PERMISSION_DENIED") }
        }
        val stranger = sessionFor(fx.user("stranger").username)
        for (call in routes(a, id)) { val r = call(stranger); assertThat(status(r)).describedAs("stranger ${r.request.method} ${r.request.requestURI}").isEqualTo(404) }
        for (call in routes(a, id)) assertThat(status(call(session()))).describedAs("anonymous").isIn(401, 403)
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT version FROM data_queries WHERE data_source_id = ?", Long::class.java, UUID.fromString(id))).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'DATA_ACCESS_DENIED'", id)).isGreaterThan(0L)
    }

    @Test
    fun `another workspace or tenant cannot list, read, create, change or delete a definition, and cannot tell the data source exists`() {
        val tenant = newTenant(); val a = admin(tenant); val b = admin(tenant); val c = admin()
        val id = a.pg(); assertThat(status(a.s.post(a.q(id), queryBody("qq")))).isEqualTo(201); assertThat(status(a.s.post(a.m(id), mutationBody("mm")))).isEqualTo(201)
        val missing = b.s.get(b.q(UUID.randomUUID().toString()))
        for (x in listOf(b, c)) for (call in routes(x, id)) {                       // the same routes, addressed under the foreigner's OWN workspace path with A's data source id
            val r = call(x.s)
            assertThat(status(r)).describedAs("${r.request.method} ${r.request.requestURI}").isEqualTo(404)
            assertThat(code(x.s, r)).isEqualTo(code(b.s, missing)); assertThat(x.s.body(r).get("message").asString()).isEqualTo(b.s.body(missing).get("message").asString())
        }
        for (call in routes(a, id)) assertThat(status(call(b.s))).describedAs("A's path through B's session").isEqualTo(404)
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT version FROM data_mutations WHERE data_source_id = ?", Long::class.java, UUID.fromString(id))).isEqualTo(1L)
    }

    @Test
    fun `deleting the data source takes its definitions with it`() {
        val w = admin(); val id = w.pg(); val ds = UUID.fromString(id)
        assertThat(status(w.s.post(w.q(id), queryBody("qq")))).isEqualTo(201); assertThat(status(w.s.post(w.m(id), mutationBody("mm")))).isEqualTo(201)
        assertThat(status(w.s.delete("${w.base}/$id"))).isEqualTo(204)
        assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds)).isZero(); assertThat(count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", ds)).isZero()
        assertThat(status(w.s.get("${w.q(id)}/qq"))).isEqualTo(404)
    }
}
