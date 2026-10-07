package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.doc
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.registry
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * The V1 public query allow-list (D-C0-35): a query is public when its definition says so (`public: true`, READ only). The allow-list of a release is derived
 * from the version snapshot the release names, so it is exactly as immutable as that version. Pure unit tests.
 */
class PublicQueryAllowListTests {
    private val engine = SchemaPatchEngine(registry, json)
    private fun op(type: String, definition: String? = null, id: String? = null) = SchemaOperation(type = type, definitionId = id, definition = definition?.let { json.readTree(it) })
    private fun apply(d: JsonNode, vararg ops: SchemaOperation): JsonNode = engine.apply(d, ops.toList())
    private fun violations(d: JsonNode) = validator.validateDocument(d).violations
    private val legacy = doc("")
    private val slot = """{"id":"orders","name":"Orders","type":"postgres","description":"the order table"}"""

    @Test
    fun `the public flag of a query is part of the document, only a READ query can have it, and the allow-list is derived from it`() {
        val base = apply(legacy, op("ADD_DATA_SOURCE", slot),
            op("ADD_QUERY", """{"id":"read-pub","dataSourceRef":"orders","operationKey":"a","public":true}"""),
            op("ADD_QUERY", """{"id":"read-priv","dataSourceRef":"orders","operationKey":"b"}"""))
        assertThat(violations(base)).isEmpty()
        val def = AppDefinitionTestSupport.codec.fromJson(base)
        assertThat(PublicQueries.of(def)).containsExactly("read-pub")
        assertThat(base.get("queries").get(0).get("public").asBoolean()).isTrue()
        assertThat(base.get("queries").get(1).has("public")).describedAs("the default is not written").isFalse()
        val explicitOff = apply(base, op("ADD_QUERY", """{"id":"read-off","dataSourceRef":"orders","operationKey":"c","public":false}"""))
        assertThat(PublicQueries.of(AppDefinitionTestSupport.codec.fromJson(explicitOff))).containsExactly("read-pub")
        assertThat(AppDefinitionTestSupport.codec.toJson(AppDefinitionTestSupport.codec.fromJson(explicitOff)).get("queries").get(2).has("public")).describedAs("canonical form omits false").isFalse()
        assertThat(AppDefinitionTestSupport.codec.toJson(def)).isEqualTo(base)                                                // round trip
        val write = apply(base, op("ADD_QUERY", """{"id":"w","dataSourceRef":"orders","mode":"WRITE","operationKey":"d","public":true}"""))
        assertThat(violations(write).map { it.path }).contains("queries[2].public")
        assertThat(PublicQueries.of(AppDefinitionTestSupport.codec.fromJson(write))).describedAs("a WRITE query is never public, even if marked").containsExactly("read-pub")
        assertThat(violations(apply(base, op("UPDATE_QUERY", """{"public":"yes"}""", "read-priv"))).map { it.path }).contains("queries[1].public")      // must be a boolean
        assertThat(PublicQueries.of(AppDefinitionTestSupport.codec.fromJson(apply(base, op("UPDATE_QUERY", """{"public":true}""", "read-priv"))))).containsExactly("read-pub", "read-priv")
        assertThat(PublicQueries.of(AppDefinitionTestSupport.codec.fromJson(apply(base, op("UPDATE_QUERY", """{"public":null}""", "read-pub"))))).isEmpty()
    }
}
