package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.doc
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.registry
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.schema.OperationTypes
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * B-C0-W-07: ADD_DATA_SOURCE / UPDATE_DATA_SOURCE / REMOVE_DATA_SOURCE declare the LOGICAL slot that queries, bindings and the Public Runtime hang on.
 * A slot is `{id, name, type, description}` and nothing else: no sourceRef, no credential, no connection detail; the binding to a real source is the
 * data platform's (Management API). Pure unit tests (no Spring, no database), then the same operations through the real commit path.
 */
class DataSourceSlotOperationsTests {
    private val engine = SchemaPatchEngine(registry, json)
    private fun op(type: String, definition: String? = null, id: String? = null) = SchemaOperation(type = type, definitionId = id, definition = definition?.let { json.readTree(it) })
    private fun apply(d: JsonNode, vararg ops: SchemaOperation): JsonNode = engine.apply(d, ops.toList())
    private fun violations(d: JsonNode) = validator.validateDocument(d).violations
    private fun fails(d: JsonNode, vararg ops: SchemaOperation, containing: String) {
        val e = assertThrows(ApiException::class.java) { apply(d, *ops) }
        assertThat(e.status.value()).isEqualTo(400); assertThat(e.code).isEqualTo("INVALID_OPERATION")
        assertThat(e.message).describedAs("message").contains(containing)
    }
    private val legacy = doc("")
    private val slot = """{"id":"orders","name":"Orders","type":"postgres","description":"the order table"}"""

    @Test
    fun `add declares a logical slot, the document stays valid and round-trips through the codec`() {
        val added = apply(legacy, op("ADD_DATA_SOURCE", slot))
        assertThat(violations(added)).isEmpty()
        assertThat(added.get("dataSources")).hasSize(1)
        val def = AppDefinitionTestSupport.codec.fromJson(added)
        assertThat(def.dataSources).containsExactly(DataSourceDef("orders", "Orders", "postgres", null, "the order table"))
        assertThat(def.dataSources.single().sourceRef).isNull()                                       // a slot is never born bound
        assertThat(AppDefinitionTestSupport.codec.toJson(def).get("dataSources")).isEqualTo(added.get("dataSources"))      // serialization and deserialization agree
        assertThat(added.has("schemaVersion")).isFalse()                                               // an operation never turns a legacy document into a strict V2 one
        // only the type is required; name and description are optional
        assertThat(violations(apply(legacy, op("ADD_DATA_SOURCE", """{"id":"min","type":"rest"}""")))).isEmpty()
    }

    @Test
    fun `a duplicate logical id is refused, and so is an add without an id or a type`() {
        val one = apply(legacy, op("ADD_DATA_SOURCE", slot))
        fails(one, op("ADD_DATA_SOURCE", """{"id":"orders","type":"rest"}"""), containing = "'orders' already exists in dataSources")
        fails(legacy, op("ADD_DATA_SOURCE", """{"type":"rest"}"""), containing = "definition.id is required")
        fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x"}"""), containing = "definition.type is required")
        fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":5}"""), containing = "definition.type is required")
        fails(legacy, op("ADD_DATA_SOURCE"), containing = "definition is required")
        fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":"rest"}""", "y"), containing = "does not match")
        // the reader still judges the shape: an id or type that is not allowed is a violation with its path
        assertThat(violations(apply(legacy, op("ADD_DATA_SOURCE", """{"id":"Bad Id","type":"rest"}""")))).isNotEmpty()
        assertThat(violations(apply(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":"Not A Type"}"""))).map { it.path }).contains("dataSources[0].type")
    }

    @Test
    fun `no sourceRef, no credential and no connection detail is accepted on add or update`() {
        val one = apply(legacy, op("ADD_DATA_SOURCE", slot))
        fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":"rest","sourceRef":"11111111-1111-1111-1111-111111111111"}"""), containing = "sourceRef is granted by the data platform")
        fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":"rest","sourceRef":null}"""), containing = "sourceRef is granted by the data platform")
        for (field in listOf("password", "secret", "token", "apiKey", "credential", "credentials", "url", "baseUrl", "host", "port", "connectionString", "jdbcUrl", "sql", "headers", "authHeader", "script", "dataSourceId"))
            fails(legacy, op("ADD_DATA_SOURCE", """{"id":"x","type":"rest","$field":"v"}"""), containing = "'$field' is not a field of a data source slot")
        fails(one, op("UPDATE_DATA_SOURCE", """{"sourceRef":"11111111-1111-1111-1111-111111111111"}""", "orders"), containing = "sourceRef is granted by the data platform")
        fails(one, op("UPDATE_DATA_SOURCE", """{"sourceRef":null}""", "orders"), containing = "cannot be set, changed or cleared")       // not even to unbind
        fails(one, op("UPDATE_DATA_SOURCE", """{"password":"x"}""", "orders"), containing = "'password' is not a field of a data source slot")
        assertThat(one.get("dataSources").get(0).has("sourceRef")).isFalse()
    }

    @Test
    fun `update changes the declaration, never the physical source`() {
        val one = apply(legacy, op("ADD_DATA_SOURCE", slot))
        val updated = apply(one, op("UPDATE_DATA_SOURCE", """{"name":"Order table","type":"mysql"}""", "orders"))
        assertThat(violations(updated)).isEmpty()
        assertThat(updated.get("dataSources").get(0).get("name").asString()).isEqualTo("Order table")
        assertThat(updated.get("dataSources").get(0).get("type").asString()).isEqualTo("mysql")           // an UNBOUND slot may be re-typed
        assertThat(updated.get("dataSources").get(0).get("description").asString()).isEqualTo("the order table")          // untouched fields stay
        val cleared = apply(updated, op("UPDATE_DATA_SOURCE", """{"description":null}""", "orders"))
        assertThat(cleared.get("dataSources").get(0).has("description")).isFalse()
        fails(one, op("UPDATE_DATA_SOURCE", """{"id":"other"}""", "orders"), containing = "immutable")
        fails(one, op("UPDATE_DATA_SOURCE", """{"name":"x"}"""), containing = "definitionId is required")
        // a slot the data platform has already registered keeps its physical source: the edit can rename it but cannot silently re-type it
        val registered = doc(""""dataSources":[{"id":"orders","type":"postgres","sourceRef":"11111111-1111-1111-1111-111111111111"}]""")
        val renamed = apply(registered, op("UPDATE_DATA_SOURCE", """{"name":"Renamed"}""", "orders"))
        assertThat(renamed.get("dataSources").get(0).get("sourceRef").asString()).isEqualTo("11111111-1111-1111-1111-111111111111")      // preserved by the edit
        fails(registered, op("UPDATE_DATA_SOURCE", """{"type":"rest"}""", "orders"), containing = "cannot change")
        assertThat(violations(apply(registered, op("UPDATE_DATA_SOURCE", """{"type":"postgres"}""", "orders")))).isEmpty()               // the same type is not a change
    }

    @Test
    fun `update and remove of a slot that does not exist are refused`() {
        fails(legacy, op("UPDATE_DATA_SOURCE", """{"name":"x"}""", "ghost"), containing = "'ghost' not found in dataSources")
        fails(legacy, op("REMOVE_DATA_SOURCE", id = "ghost"), containing = "'ghost' not found in dataSources")
        fails(legacy, op("REMOVE_DATA_SOURCE"), containing = "definitionId is required")
        val one = apply(legacy, op("ADD_DATA_SOURCE", slot))
        fails(one, op("REMOVE_DATA_SOURCE", id = "ghost"), containing = "'ghost' not found")
    }

    @Test
    fun `remove takes an unreferenced slot away and leaves exactly the document it was before`() {
        val one = apply(legacy, op("ADD_DATA_SOURCE", slot))
        assertThat(apply(one, op("REMOVE_DATA_SOURCE", id = "orders"))).isEqualTo(legacy)             // no empty collection left behind
        val two = apply(one, op("ADD_DATA_SOURCE", """{"id":"crm","type":"rest"}"""))
        val removed = apply(two, op("REMOVE_DATA_SOURCE", id = "orders"))
        assertThat(removed.get("dataSources").toList().map { it.get("id").asString() }).containsExactly("crm")
        assertThat(violations(removed)).isEmpty()
    }

    @Test
    fun `removing a slot that something still references is reported by the validator with the path - no cascade, no silent fix`() {
        val wired = apply(legacy,
            op("ADD_DATA_SOURCE", slot),
            op("ADD_QUERY", """{"id":"q","dataSourceRef":"orders","operationKey":"orders.list","public":true}"""),
            op("ADD_ACTION", """{"id":"call","type":"CALL_API","dataSourceRef":"orders","operationKey":"orders.sync"}"""),
            op("ADD_PERMISSION_REF", """{"id":"p","permission":"QUERY_EXECUTE","resourceType":"DATA_SOURCE","resourceRef":"orders"}"""))
        assertThat(violations(wired)).isEmpty()
        val broken = apply(wired, op("REMOVE_DATA_SOURCE", id = "orders"))                              // the operation itself applies: structure only
        assertThat(broken.has("dataSources")).isFalse()
        val paths = violations(broken).map { it.path }
        assertThat(paths).contains("queries[0].dataSourceRef", "actions[0].dataSourceRef", "permissions[0].resourceRef")
        assertThat(violations(broken).first { it.path == "queries[0].dataSourceRef" }.message).contains("unknown data source 'orders'")
        assertThat(broken.get("queries")).hasSize(1)                                                    // nothing was cascaded away
        assertThat(broken.get("actions")).hasSize(1)
    }

    @Test
    fun `a slot can be used by a query, a mapping and a view model, and the whole chain is valid`() {
        val chain = apply(legacy,
            op("ADD_DATA_SOURCE", slot),
            op("ADD_QUERY", """{"id":"q","dataSourceRef":"orders","operationKey":"orders.list"}"""),
            op("ADD_MAPPING", """{"id":"m","queryRef":"q","fields":[{"from":"no","to":"name"}]}"""),
            op("ADD_VIEW_MODEL", """{"id":"v","queryRef":"q","mappingRef":"m","fields":[{"name":"name"}]}"""),
            op("ADD_DATA_BINDING", """{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v"}"""))
        assertThat(violations(chain)).isEmpty()
    }

    @Test
    fun `the vocabulary now has the three slot operations - and still no operation can touch a credential or a secret`() {
        assertThat(OperationTypes.definitions).contains("ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE")
        assertThat(OperationTypes.definitions).hasSize(26); assertThat(OperationTypes.allV2).hasSize(38)
        assertThat(OperationTypes.definitions.none { it.contains("CREDENTIAL") || it.contains("SECRET") || it.contains("BINDING_SOURCE") }).isTrue()
        assertThat(OperationTypes.all).doesNotContain("ADD_DATA_SOURCE")                                    // the legacy page operations are unchanged
        assertThat(OperationTypes.planner).describedAs("the AI planner never proposes a slot").doesNotContain("ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE")
        assertThat(OperationTypes.planner).hasSize(35)
    }
}
