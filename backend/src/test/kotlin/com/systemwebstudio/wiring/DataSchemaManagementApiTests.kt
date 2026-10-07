package com.systemwebstudio.wiring

import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

/** Management API contract §3.4: `POST …/schema/discover` and `GET …/schema` over the real stack. */
@Import(ManagementTestBeans::class)
class DataSchemaManagementApiTests : ManagementApiTestBase() {
    private val customers = DiscoveredEntity(
        "customers", "shop", EntityKind.TABLE,
        listOf(DiscoveredField("id", NormalizedType.INTEGER, false, "int4", true), DiscoveredField("email", NormalizedType.STRING, true, "text"), DiscoveredField("plan", NormalizedType.STRING, true, "text")),
        primaryKey = listOf("id"), metadata = mapOf("rows" to "estimated")
    )

    private fun shape() { connector.discovered = DiscoveredSchema(listOf(customers), warnings = listOf("table list is partial")) }
    private fun W.discover(id: String, body: String = "{}") = s.post("$base/$id/schema/discover", body)

    @Test
    fun `discover stores a snapshot and GET returns it - structure only, no samples unless asked`() {
        shape(); val w = admin(); val id = w.created(credential = mapOf("authValue" to secret))
        assertThat(status(w.s.get("${w.base}/$id/schema"))).describedAs("nothing discovered yet").isEqualTo(404)
        val d = w.discover(id)
        assertThat(status(d)).describedAs(d.response.contentAsString).isEqualTo(200)
        val b = w.s.body(d)
        assertThat(DataJson.keys(b)).containsExactlyInAnyOrder("version", "discoveredAt", "changed", "fingerprint", "includesSamples", "truncated", "entityCount", "fieldCount", "warnings")
        assertThat(b.get("version").asInt()).isEqualTo(1); assertThat(b.get("changed").asBoolean()).isTrue(); assertThat(b.get("includesSamples").asBoolean()).isFalse()
        assertThat(b.get("entityCount").asInt()).isEqualTo(1); assertThat(b.get("fieldCount").asInt()).isEqualTo(3)
        assertThat(connector.discoveries.get()).isEqualTo(1)

        val g = w.s.get("${w.base}/$id/schema")
        assertThat(status(g)).isEqualTo(200)
        val snap = w.s.body(g)
        assertThat(snap.get("version").asInt()).isEqualTo(1); assertThat(snap.get("fingerprint").asString()).isEqualTo(b.get("fingerprint").asString())
        val entity = DataJson.elements(snap.get("entities")).single()
        assertThat(entity.get("name").asString()).isEqualTo("customers"); assertThat(entity.get("schema").asString()).isEqualTo("shop")
        assertThat(DataJson.elements(entity.get("fields")).map { it.get("name").asString() }).containsExactly("id", "email", "plan")
        assertThat(entity.get("sample").size()).describedAs("no samples were asked for").isZero()
        assertThat(count("SELECT count(*) FROM source_schemas WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(auditCount(id, "DATASOURCE_SCHEMA_REFRESHED")).isEqualTo(1L)
        assertNoSecretAnywhere(secret, d, g)
    }

    @Test
    fun `a second discovery inside the cooldown is REFRESH_TOO_SOON and stores nothing`() {
        shape(); val w = admin(); val id = w.created()
        assertThat(status(w.discover(id))).isEqualTo(200)
        val again = w.discover(id)
        assertThat(status(again)).isEqualTo(429); assertThat(code(w.s, again)).isEqualTo("REFRESH_TOO_SOON")
        assertThat(count("SELECT count(*) FROM source_schemas WHERE data_source_id = ?", UUID.fromString(id))).isEqualTo(1L)
        assertThat(connector.discoveries.get()).describedAs("the connector was not asked a second time").isEqualTo(1)
    }

    @Test
    fun `samples are opt-in, masked, and need QUERY_EXECUTE as well as DATA_SOURCE_MANAGE`() {
        shape(); connector.discoveredSample = listOf(mapOf("id" to 7, "email" to "alice.private@example.com", "plan" to "gold"))
        val w = admin(); val id = w.created()
        val r = w.discover(id, """{"includeSamples":true}""")
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(200); assertThat(w.s.body(r).get("includesSamples").asBoolean()).isTrue()
        val g = w.s.get("${w.base}/$id/schema")
        assertThat(g.response.contentAsString).describedAs("the e-mail address never appears, masked or not stored raw").doesNotContain("alice.private@example.com")
        val sample = DataJson.elements(DataJson.elements(w.s.body(g).get("entities")).single().get("sample"))
        assertThat(sample).hasSize(1); assertThat(sample.single().get("plan").asString()).isEqualTo("gold")
        assertThat(count("SELECT count(*) FROM source_schemas WHERE snapshot::text LIKE ?", "%alice.private%")).describedAs("stored snapshot").isZero()
        assertThat(auditCount(id, "DATASOURCE_SCHEMA_REFRESHED")).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND new_value::text LIKE ?", id, "%alice%")).isZero()
    }

    @Test
    fun `strict body - an unknown or authority key and a non-boolean includeSamples are refused`() {
        shape(); val w = admin(); val id = w.created()
        for (bad in listOf("""{"includeSamples":"yes"}""", """{"includeSamples":1}""", """{"tenantId":"${w.tenant}"}""", """{"dataSourceId":"${UUID.randomUUID()}"}""", """{"sql":"select 1"}""", "[]", "\"x\""))
            assertThat(status(w.discover(id, bad))).describedAs(bad).isEqualTo(400)
        assertThat(status(w.s.post("${w.base}/$id/schema/discover", ""))).describedAs("an empty body is the default request").isIn(200, 415)
        assertThat(connector.discoveries.get()).describedAs("nothing was discovered for a refused body").isLessThanOrEqualTo(1)
    }

    @Test
    fun `permissions - only DATA_SOURCE_MANAGE may discover or read the stored schema, a stranger gets 404`() {
        shape(); val a = admin(); val id = a.created(); assertThat(status(a.discover(id))).isEqualTo(200)
        for (role in listOf("EDITOR", "PUBLISHER", "VIEWER")) {
            val (_, s) = member(a.ws, role)
            for (r in listOf(s.post("${a.base}/$id/schema/discover", "{}"), s.post("${a.base}/$id/schema/discover", """{"includeSamples":true}"""), s.get("${a.base}/$id/schema"))) {
                assertThat(status(r)).describedAs("$role ${r.request.method} ${r.request.requestURI}").isEqualTo(403); assertThat(code(s, r)).isEqualTo("PERMISSION_DENIED")
            }
        }
        val stranger = sessionFor(fx.user("stranger").username)
        assertThat(status(stranger.get("${a.base}/$id/schema"))).isEqualTo(404); assertThat(status(stranger.post("${a.base}/$id/schema/discover", "{}"))).isEqualTo(404)
        assertThat(connector.discoveries.get()).isEqualTo(1)
    }

    @Test
    fun `another workspace or tenant cannot discover or read it, and cannot tell it exists`() {
        shape(); val tenant = newTenant(); val a = admin(tenant); val b = admin(tenant); val c = admin()
        val id = a.created(); assertThat(status(a.discover(id))).isEqualTo(200)
        val missing = b.s.get("${b.base}/${UUID.randomUUID()}/schema")
        for (x in listOf(b, c)) {
            for (r in listOf(x.s.get("${x.base}/$id/schema"), x.discover(id), x.discover(id, """{"includeSamples":true}"""))) {
                assertThat(status(r)).isEqualTo(404); assertThat(code(x.s, r)).isEqualTo(code(b.s, missing)); assertThat(x.s.body(r).get("message").asString()).isEqualTo(b.s.body(missing).get("message").asString())
            }
        }
        assertThat(status(b.s.get("${a.base}/$id/schema"))).describedAs("A's path through B's session: B is no member").isEqualTo(404)
        assertThat(connector.discoveries.get()).describedAs("only A's own discovery reached the connector").isEqualTo(1)
    }

    @Test
    fun `a disabled source cannot be discovered, and a discovery that cannot be audited stores nothing`() {
        shape(); val w = admin(); val id = w.created()
        assertThat(status(w.s.patch("${w.base}/$id", """{"status":"DISABLED"}"""))).isEqualTo(200)
        val off = w.discover(id); assertThat(status(off)).isEqualTo(409); assertThat(code(w.s, off)).isEqualTo("DISABLED")
        assertThat(status(w.s.patch("${w.base}/$id", """{"status":"ACTIVE"}"""))).isEqualTo(200)
        faultAudit.failOn = setOf("DATASOURCE_SCHEMA_REFRESHED")
        val r = w.discover(id)
        assertThat(status(r)).isEqualTo(500)
        assertThat(count("SELECT count(*) FROM source_schemas WHERE data_source_id = ?", UUID.fromString(id))).describedAs("no snapshot without its audit row").isZero()
        faultAudit.failOn = null
        assertThat(status(w.s.get("${w.base}/$id/schema"))).isEqualTo(404)
    }
}
