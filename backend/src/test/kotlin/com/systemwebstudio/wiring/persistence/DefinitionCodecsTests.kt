package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredRelation
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.RestQueryDefinition
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pure tests (no Spring, no database): what the V28 JSON columns hold decodes back to exactly what was stored, and anything odd decodes to null. */
class DefinitionCodecsTests {
    private val tenant = UUID.randomUUID()
    private val source = UUID.randomUUID()
    private val params = listOf(
        QueryParamSpec("status", ParamType.STRING, required = false, default = DataJson.toNode("open")),
        QueryParamSpec("since", ParamType.TIMESTAMP, required = false),
        QueryParamSpec("limitRows", ParamType.INTEGER, required = true)
    )

    @Test
    fun `a SQL query survives the round trip`() {
        val def = SqlQueryDefinition("orders.list", tenant, source, "SELECT id FROM orders WHERE status = :status", params, 200, 60, 7)
        val back = DefinitionCodecs.decodeQuery(DefinitionCodecs.kindOf(def), def.id, tenant, source, 7, DefinitionCodecs.encodeQuery(def))
        assertThat(back).isEqualTo(def)
    }

    @Test
    fun `a REST query survives the round trip`() {
        val def = RestQueryDefinition(
            "orders.rest", tenant, source, "/v1/orders/{status}", listOf(QueryParamSpec("status", ParamType.STRING)), mapOf("status" to "st"), "/data/items", 50,
            "limit", "offset", true, 30, 3
        )
        val back = DefinitionCodecs.decodeQuery(DefinitionCodecs.kindOf(def), def.id, tenant, source, 3, DefinitionCodecs.encodeQuery(def))
        assertThat(back).isEqualTo(def)
    }

    @Test
    fun `a mutation survives the round trip for every kind`() {
        for (kind in MutationKind.values()) {
            val def = MutationDefinition("orders.$kind".lowercase(), tenant, source, kind, "orders", listOf(QueryParamSpec("customer", ParamType.STRING)), listOf("orders.list"), "orders", 4)
            val back = DefinitionCodecs.decodeMutation(kind.name, def.id, tenant, source, 4, DefinitionCodecs.encodeMutation(def))
            assertThat(back).describedAs(kind.name).isEqualTo(def)
        }
    }

    @Test
    fun `identity never comes from the document`() {
        val def = SqlQueryDefinition("q1", tenant, source, "SELECT 1")
        val json = DefinitionCodecs.encodeQuery(def)
        assertThat(json).doesNotContain(tenant.toString()).doesNotContain(source.toString())
        val other = UUID.randomUUID()
        assertThat(DefinitionCodecs.decodeQuery("SQL", "q1", other, source, 1, json)!!.tenantId).isEqualTo(other)
    }

    @Test
    fun `an unknown kind, a missing field or an invalid definition decodes to null`() {
        val ok = DefinitionCodecs.encodeQuery(SqlQueryDefinition("q1", tenant, source, "SELECT 1"))
        assertThat(DefinitionCodecs.decodeQuery("GRAPHQL", "q1", tenant, source, 1, ok)).isNull()
        assertThat(DefinitionCodecs.decodeQuery("SQL", "q1", tenant, source, 1, """{"sql":"SELECT 1"}""")).isNull()               // no maxRows
        assertThat(DefinitionCodecs.decodeQuery("SQL", "q1", tenant, source, 1, """{"sql":"SELECT 1","maxRows":0,"params":[]}""")).isNull()   // refused by the type
        assertThat(DefinitionCodecs.decodeQuery("SQL", "bad id", tenant, source, 1, ok)).isNull()
        assertThat(DefinitionCodecs.decodeQuery("SQL", "q1", tenant, source, 1, "[]")).isNull()
        assertThat(DefinitionCodecs.decodeMutation("TRUNCATE", "m1", tenant, source, 1, """{"target":"t","params":[]}""")).isNull()
        assertThat(DefinitionCodecs.decodeMutation("CREATE", "m1", tenant, source, 1, """{"params":[]}""")).isNull()
        assertThat(DefinitionCodecs.decodeMutation("CREATE", "m1", tenant, source, 1, """{"target":"t","params":[{"name":"x","type":"BLOB"}]}""")).isNull()
    }

    @Test
    fun `a discovered schema survives the round trip including masked samples`() {
        val schema = DiscoveredSchema(
            listOf(
                DiscoveredEntity(
                    "orders", "public", EntityKind.TABLE,
                    listOf(DiscoveredField("id", NormalizedType.INTEGER, false, "int4", true), DiscoveredField("note", NormalizedType.STRING, true)),
                    listOf("id"), listOf(DiscoveredRelation("fk_customer", listOf("customer_id"), "customers", "public", listOf("id"))),
                    mapOf("kind" to "table"), listOf(mapOf("id" to DataJson.toNode(1), "note" to DataJson.toNode("a***")))
                ),
                DiscoveredEntity("ping", null, EntityKind.ENDPOINT, emptyList())
            ),
            truncated = true, warnings = listOf("table list truncated")
        )
        assertThat(SchemaCodec.decode(SchemaCodec.encode(schema))).isEqualTo(schema)
    }

    @Test
    fun `a schema document that is not a schema is rejected`() {
        for (bad in listOf("{}", "[]", """{"entities":[{"name":"x"}]}""", """{"entities":[{"name":"x","schema":null,"kind":"CUBE","fields":[]}]}""")) {
            val failed = try { SchemaCodec.decode(bad); false } catch (e: RuntimeException) { true }
            assertThat(failed).describedAs(bad).isTrue()
        }
    }
}
