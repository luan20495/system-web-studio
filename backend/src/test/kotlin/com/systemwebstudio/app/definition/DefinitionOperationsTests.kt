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
 * The typed AppDefinition V2 operations: the same SchemaOperation / SchemaPatchEngine path as the section operations, followed by the same
 * validators. Pure unit tests (no Spring, no database).
 */
class DefinitionOperationsTests {
    private val engine = SchemaPatchEngine(registry, json)

    /** a document that already has what operations may refer to but cannot create: a data source and a read and a write query */
    private val base: JsonNode = doc(""""dataSources":[{"id":"d","type":"rest"}],
        "queries":[{"id":"q","dataSourceRef":"d","operationKey":"orders.list"},{"id":"qw","dataSourceRef":"d","mode":"WRITE","operationKey":"orders.create"}]""")

    private fun op(type: String, definition: String? = null, id: String? = null) =
        SchemaOperation(type = type, definitionId = id, definition = definition?.let { json.readTree(it) })

    private fun apply(doc: JsonNode, vararg ops: SchemaOperation): JsonNode = engine.apply(doc, ops.toList())
    private fun violations(doc: JsonNode) = validator.validateDocument(doc).violations

    private val addAll = arrayOf(
        op("ADD_QUERY", """{"id":"q2","dataSourceRef":"d","operationKey":"orders.list","params":[{"name":"status","type":"STRING"}],"maxRows":50}"""),
        op("ADD_MAPPING", """{"id":"m","queryRef":"q2","fields":[{"from":"order_no","to":"name"}]}"""),
        op("ADD_VIEW_MODEL", """{"id":"v","queryRef":"q2","mappingRef":"m","fields":[{"name":"name"}]}"""),
        op("ADD_DATA_BINDING", """{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v"}"""),
        op("ADD_ACTION", """{"id":"notify","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}"""),
        op("ADD_ACTION", """{"id":"create","type":"CREATE_RECORD","queryRef":"qw","trigger":{"sectionId":"contact-1","event":"onSubmit"},"permissionRef":"p"}"""),
        op("ADD_WORKFLOW_REF", """{"id":"w","steps":[{"id":"s1","actionRef":"notify","next":"s2"},{"id":"s2","actionRef":"create"}]}"""),
        op("ADD_PERMISSION_REF", """{"id":"p","permission":"ACTION_EXECUTE","resourceType":"ACTION","resourceRef":"create"}""")
    )

    @Test
    fun `every typed collection can be added updated and removed and the result stays valid`() {
        val added = apply(base, *addAll)
        assertThat(violations(added)).isEmpty()
        val def = AppDefinitionTestSupport.codec.fromJson(added)
        assertThat(def.viewModels.map { it.id }).containsExactly("v")
        assertThat(def.dataBindings.map { it.id }).containsExactly("b")
        assertThat(def.actions.map { it.id }).containsExactly("notify", "create")
        assertThat(def.workflows.map { it.id }).containsExactly("w")
        assertThat(def.permissions.map { it.id }).containsExactly("p")

        val updated = apply(added,
            op("UPDATE_QUERY", """{"maxRows":75}""", "q2"),
            op("UPDATE_VIEW_MODEL", """{"name":"Orders","fields":[{"name":"name"},{"name":"extra"}]}""", "v"),
            op("UPDATE_DATA_BINDING", """{"viewModelRef":"v"}""", "b"),
            op("UPDATE_ACTION", """{"name":"Create order"}""", "create"),
            op("UPDATE_WORKFLOW_REF", """{"name":"Flow"}""", "w"),
            op("UPDATE_PERMISSION_REF", """{"permission":"QUERY_EXECUTE","resourceType":"QUERY","resourceRef":"q2"}""", "p"),
            op("UPDATE_MAPPING", """{"name":"Orders map"}""", "m"))
        assertThat(violations(updated)).isEmpty()
        val after = AppDefinitionTestSupport.codec.fromJson(updated)
        assertThat(after.queries.first { it.id == "q2" }.maxRows).isEqualTo(75)
        assertThat(after.viewModels[0].fields.map { it.name }).containsExactly("name", "extra")
        assertThat(after.permissions[0].resourceRef).isEqualTo("q2")

        // remove in dependency order: the document is exactly what it was before (no empty collections left behind)
        val removed = apply(updated,
            op("REMOVE_DATA_BINDING", id = "b"), op("REMOVE_WORKFLOW_REF", id = "w"),
            op("REMOVE_PERMISSION_REF", id = "p"), op("REMOVE_ACTION", id = "create"), op("REMOVE_ACTION", id = "notify"),
            op("REMOVE_VIEW_MODEL", id = "v"), op("REMOVE_MAPPING", id = "m"), op("REMOVE_QUERY", id = "q2"))
        assertThat(removed).isEqualTo(base)
        assertThat(violations(removed)).isEmpty()
    }

    @Test
    fun `adding then removing on a legacy document returns exactly the legacy document`() {
        val legacy = AppDefinitionTestSupport.doc("")
        val withVm = apply(legacy, op("ADD_VIEW_MODEL", """{"id":"v","fields":[{"name":"a"}]}"""))
        assertThat(withVm.has("viewModels")).isTrue()
        assertThat(withVm.has("schemaVersion")).isFalse()           // an operation never turns a legacy document into a strict V2 one
        assertThat(apply(withVm, op("REMOVE_VIEW_MODEL", id = "v"))).isEqualTo(legacy)
    }

    @Test
    fun `update merges the given fields and null clears a field`() {
        val doc = apply(base, op("UPDATE_QUERY", """{"name":"Orders","maxRows":10}""", "q"))
        assertThat(doc.get("queries").get(0).get("name").asString()).isEqualTo("Orders")
        val cleared = apply(doc, op("UPDATE_QUERY", """{"name":null}""", "q"))
        assertThat(cleared.get("queries").get(0).has("name")).isFalse()
        assertThat(cleared.get("queries").get(0).get("maxRows").asInt()).isEqualTo(10)       // untouched fields stay
        assertThat(cleared.get("queries").get(0).get("operationKey").asString()).isEqualTo("orders.list")
    }

    @Test
    fun `ids are unique and immutable and the target must exist`() {
        fun fails(vararg ops: SchemaOperation, containing: String) {
            val e = assertThrows(ApiException::class.java) { apply(base, *ops) }
            assertThat(e.status.value()).isEqualTo(400)
            assertThat(e.code).isEqualTo("INVALID_OPERATION")
            assertThat(e.message.contains(containing)).describedAs(e.message).isTrue()
        }
        fails(op("ADD_QUERY", """{"id":"q","dataSourceRef":"d"}"""), containing = "'q' already exists in queries")
        fails(op("ADD_QUERY", """{"dataSourceRef":"d"}"""), containing = "definition.id is required")
        fails(op("ADD_QUERY"), containing = "definition is required")
        fails(op("ADD_QUERY", """[1]"""), containing = "definition must be an object")
        fails(op("ADD_QUERY", """{"id":"x","dataSourceRef":"d"}""", "y"), containing = "does not match")
        fails(op("UPDATE_QUERY", """{"id":"other"}""", "q"), containing = "immutable")
        fails(op("UPDATE_QUERY", """{"name":"x"}""", "ghost"), containing = "'ghost' not found in queries")
        fails(op("UPDATE_QUERY", """{"name":"x"}"""), containing = "definitionId is required")
        fails(op("REMOVE_QUERY", id = "ghost"), containing = "not found in queries")
        fails(op("REMOVE_VIEW_MODEL", id = "v"), containing = "not found in viewModels")                // collection does not exist yet
        fails(op("ADD_QUERY", """{"id":"x","__proto__":{}}"""), containing = "illegal field name")
    }

    @Test
    fun `a failing operation names its index and the input document is never changed`() {
        val before = base.deepCopy() as JsonNode
        val e = assertThrows(ApiException::class.java) {
            apply(base, op("ADD_VIEW_MODEL", """{"id":"v"}"""), op("ADD_VIEW_MODEL", """{"id":"v"}"""))
        }
        assertThat(e.message.startsWith("operation[1] ADD_VIEW_MODEL:")).isTrue()
        assertThat(e.details["operationIndex"]).isEqualTo(1)
        assertThat(base).isEqualTo(before)
    }

    @Test
    fun `an operation that leaves a dangling reference is rejected by the validator with the path`() {
        val added = apply(base, *addAll)
        val brokenQuery = apply(added, op("REMOVE_QUERY", id = "q2"))
        assertThat(violations(brokenQuery).any { it.path == "mappings[0].queryRef" && it.message.contains("unknown query 'q2'") }).isTrue()
        val brokenSection = apply(added, op("UPDATE_DATA_BINDING", """{"sectionId":"ghost-1"}""", "b"))
        assertThat(violations(brokenSection).any { it.path == "dataBindings[0].sectionId" }).isTrue()
        val readAsWrite = apply(added, op("UPDATE_VIEW_MODEL", """{"queryRef":"qw"}""", "v"))
        assertThat(violations(readAsWrite).any { it.message.contains("is a WRITE query") }).isTrue()
    }

    @Test
    fun `an operation cannot smuggle SQL a URL a credential or code`() {
        fun paths(vararg ops: SchemaOperation) = violations(apply(base, *ops)).map { it.path }
        assertThat(paths(op("ADD_QUERY", """{"id":"x","dataSourceRef":"d","sql":"select * from users"}"""))).contains("queries[2].sql")
        assertThat(paths(op("ADD_QUERY", """{"id":"x","dataSourceRef":"d","operationKey":"https://evil.example/x"}"""))).contains("queries[2].operationKey")
        assertThat(paths(op("ADD_QUERY", """{"id":"x","dataSourceRef":"d","name":"jdbc:postgresql://h/db"}"""))).contains("queries[2].name")
        assertThat(paths(op("ADD_ACTION", """{"id":"x","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","script":"fetch('http://x')"}"""))).contains("actions[0].script")
        assertThat(paths(op("ADD_ACTION", """{"id":"x","type":"RUN_SCRIPT"}"""))).contains("actions[0].type")
        assertThat(paths(op("ADD_ACTION", """{"id":"x","type":"CALL_API","dataSourceRef":"d","operationKey":"a","headers":{"Authorization":"Bearer x"}}"""))).contains("actions[0].headers")
        assertThat(paths(op("UPDATE_QUERY", """{"password":"x"}""", "q"))).contains("queries[0].password")
        assertThat(paths(op("ADD_PERMISSION_REF", """{"id":"p","permission":"lower case","resourceType":"QUERY","resourceRef":"q"}"""))).contains("permissions[0].permission")
    }

    @Test
    fun `theme and the publish draft are updated through operations too`() {
        val themed = apply(base, op("UPDATE_THEME", """{"colors":{"primary":"#112233"},"radius":"SM"}"""),
            op("UPDATE_PUBLISH_CONFIG", """{"mode":"DYNAMIC","visibility":"TENANT"}"""))
        assertThat(violations(themed)).isEmpty()
        val def = AppDefinitionTestSupport.codec.fromJson(themed)
        assertThat(def.theme).isEqualTo(ThemeDef(mapOf("primary" to "#112233"), null, ThemeRadius.SM))
        assertThat(def.publishConfig).isEqualTo(PublishConfigDef(PublishMode.DYNAMIC, PublishVisibility.TENANT))
        val again = apply(themed, op("UPDATE_THEME", """{"radius":null,"fontFamily":"MONO"}"""), op("UPDATE_PUBLISH_CONFIG", """{"visibility":null}"""))
        val def2 = AppDefinitionTestSupport.codec.fromJson(again)
        assertThat(def2.theme).isEqualTo(ThemeDef(mapOf("primary" to "#112233"), ThemeFont.MONO, null))
        assertThat(def2.publishConfig).isEqualTo(PublishConfigDef(PublishMode.DYNAMIC))
        assertThat(violations(apply(base, op("UPDATE_PUBLISH_CONFIG", """{"mode":"SERVER_APP"}"""))).map { it.path }).contains("publishConfig.mode")
        assertThat(violations(apply(base, op("UPDATE_THEME", """{"css":"body{}"}"""))).map { it.path }).contains("theme.css")
    }

    @Test
    fun `section and site operations are unchanged and mix with V2 operations in one request`() {
        val result = apply(base,
            op("ADD_QUERY", """{"id":"q2","dataSourceRef":"d"}"""),
            SchemaOperation("UPDATE_PROP", sectionId = "hero-1", path = "title", value = json.readTree("\"Mixed\"")),
            SchemaOperation("ADD_PAGE", pageId = "about", props = json.readTree("""{"slug":"about","title":"About"}""")),
            op("ADD_ACTION", """{"id":"go","type":"NAVIGATE","pageRef":"about","trigger":{"sectionId":"hero-1","event":"onClick"}}"""))
        assertThat(violations(result)).isEmpty()
        assertThat(result.get("sections").get(0).get("props").get("title").asString()).isEqualTo("Mixed")
    }

    @Test
    fun `the operation vocabulary keeps the legacy set and adds the typed V2 set next to it`() {
        assertThat(OperationTypes.all.size).isEqualTo(12)
        assertThat(OperationTypes.definitions).hasSize(26)                                  // 23 + the three data source SLOT operations (B-C0-W-07)
        assertThat(OperationTypes.definitions).contains("UPDATE_PERMISSION_REF", "ADD_WORKFLOW_REF", "REMOVE_DATA_BINDING", "ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE")
        assertThat(OperationTypes.all.intersect(OperationTypes.definitions)).isEmpty()
        assertThat(OperationTypes.allV2.size).isEqualTo(38)
        // an operation declares a data source SLOT (DataSourceSlotOperationsTests); none can create or change a credential or a secret, or bind a slot to a source
        assertThat(OperationTypes.definitions.none { it.contains("CREDENTIAL") || it.contains("SECRET") }).isTrue()
    }

    @Test
    fun `at most 50 operations per request still applies`() {
        val many = (1..51).map { op("ADD_VIEW_MODEL", """{"id":"v$it"}""") }
        assertThrows(ApiException::class.java) { engine.apply(base, many) }
    }
}
