package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.check
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.codec
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.doc
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** Local document ids → runtime ids (data-runtime.md §3). The document never carries a runtime id except the data source slot's `sourceRef`. */
class AppDataBindingResolverTests {
    private val crmId = UUID.fromString("0b8f2a3e-5c1d-4b7a-9e2f-3d4c5b6a7e80")
    private val erpId = UUID.fromString("22222222-3333-4444-8555-666666666666")

    private fun resolver(slots: Map<String, UUID> = mapOf("erp-db" to erpId)) = AppDataBindingResolver(codec.fromJson(resource("valid-v2-sample.json")), slots)

    private fun code(block: () -> Unit): String = assertThrows(AppResolutionException::class.java) { block() }.code

    @Test
    fun `a data source with a sourceRef resolves to that registration`() {
        val ds = resolver().dataSource("crm")
        assertThat(ds.sourceId).isEqualTo(crmId)
        assertThat(ds.localId).isEqualTo("crm")
        assertThat(ds.type).isEqualTo("rest")
    }

    @Test
    fun `an unbound slot resolves through the runtime slot bindings and fails without them`() {
        assertThat(resolver().dataSource("erp-db").sourceId).isEqualTo(erpId)
        assertThat(code { resolver(emptyMap()).dataSource("erp-db") }).isEqualTo(ResolutionCodes.DATA_SOURCE_UNBOUND)
    }

    @Test
    fun `a sourceRef in the document wins over a slot binding`() {
        val other = UUID.randomUUID()
        assertThat(resolver(mapOf("crm" to other)).dataSource("crm").sourceId).isEqualTo(crmId)
    }

    @Test
    fun `an invalid sourceRef is reported by code, not by a parse exception`() {
        // the reader already rejects such a document; the resolver stays defensive for definitions built in code
        val def = AppDefinitionV2(dataSources = listOf(DataSourceDef("d", type = "postgres", sourceRef = "not-a-uuid")))
        assertThat(assertThrows(AppResolutionException::class.java) { AppDataBindingResolver(def).dataSource("d") }.code).isEqualTo(ResolutionCodes.INVALID_SOURCE_ID)
    }

    @Test
    fun `a query resolves to its source and approved operation, never to a stored runtime id`() {
        val q = resolver().query("orders-list")
        assertThat(q.queryId).isEqualTo("orders-list")
        assertThat(q.mode).isEqualTo(QueryMode.READ)
        assertThat(q.operationKey).isEqualTo("orders.list")
        assertThat(q.dataSource.sourceId).isEqualTo(erpId)
        assertThat(q.maxRows).isEqualTo(200)
        assertThat(q.params.map { it.name }).containsExactly("status", "since")
        assertThat(code { resolver().query("nope") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
    }

    @Test
    fun `a mapping is translated from the local query id to the operation key C3 knows`() {
        val m = resolver().mapping("orders-map")
        assertThat(m.queryOperationKey).isEqualTo("orders.list")
        assertThat(m.definition.queryRef).isEqualTo("orders-list")   // the document keeps the local id
        assertThat(code { resolver().mapping("nope") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
    }

    @Test
    fun `a data binding reads through its view model, query and mapping`() {
        val b = resolver().binding("bind-orders")
        assertThat(b.sectionId).isEqualTo("products-1")
        assertThat(b.prop).isEqualTo("items")
        assertThat(b.viewModel?.id).isEqualTo("orders-vm")
        assertThat(b.query.operationKey).isEqualTo("orders.list")
        assertThat(b.mapping?.mappingId).isEqualTo("orders-map")
        assertThat(b.mapping?.queryOperationKey).isEqualTo("orders.list")
        assertThat(resolver().bindingsOf("products-1").map { it.bindingId }).containsExactly("bind-orders")
        assertThat(resolver().bindingsOf("hero-1")).isEmpty()
    }

    @Test
    fun `a binding that reaches no query or a write query fails with a stable code`() {
        val noQuery = AppDataBindingResolver(codec.fromJson(doc(""""viewModels":[{"id":"vm","fields":[{"name":"a"}]}],"dataBindings":[{"id":"b","sectionId":"hero-1","prop":"title","viewModelRef":"vm"}]""")))
        assertThat(code { noQuery.binding("b") }).isEqualTo(ResolutionCodes.NO_QUERY)
        val write = AppDataBindingResolver(codec.fromJson(doc(
            """"dataSources":[{"id":"d","type":"postgres","sourceRef":"$erpId"}],
               "queries":[{"id":"w","dataSourceRef":"d","mode":"WRITE","operationKey":"orders.create"}],
               "dataBindings":[{"id":"b","sectionId":"hero-1","prop":"title","queryRef":"w"}]""")))
        assertThat(code { write.binding("b") }).isEqualTo(ResolutionCodes.WRONG_MODE)
        assertThat(code { resolver().binding("missing") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
    }

    @Test
    fun `a query without an operation key cannot be resolved`() {
        val r = AppDataBindingResolver(codec.fromJson(doc(""""dataSources":[{"id":"d","type":"postgres","sourceRef":"$erpId"}],"queries":[{"id":"q","dataSourceRef":"d"}]""")))
        assertThat(code { r.query("q") }).isEqualTo(ResolutionCodes.NO_OPERATION)
    }

    @Test
    fun `REFRESH_QUERY resolves a read query and record actions resolve a write query`() {
        val def = codec.fromJson(resource("conformance/valid-action-def-full.json"))
        val r = AppDataBindingResolver(def, mapOf("erp-db" to erpId))
        val refresh = r.action("refresh")
        assertThat(refresh.type).isEqualTo(ActionType.REFRESH_QUERY)
        assertThat(refresh.query?.operationKey).isEqualTo("orders.list")
        assertThat(refresh.viewModel?.id).isEqualTo("orders-vm")
        val edit = r.action("edit-order")
        assertThat(edit.query?.mode).isEqualTo(QueryMode.WRITE)
        assertThat(edit.query?.operationKey).isEqualTo("orders.update")
    }

    @Test
    fun `CALL_API resolves an approved operation of a registered source, not a URL`() {
        val r = AppDataBindingResolver(codec.fromJson(resource("conformance/valid-action-def-full.json")), mapOf("erp-db" to erpId))
        val call = r.action("call-erp")
        assertThat(call.operation?.operationKey).isEqualTo("orders.sync")
        assertThat(call.operation?.dataSource?.sourceId).isEqualTo(erpId)
        assertThat(call.query).isNull()
    }

    @Test
    fun `actions that touch no data say so`() {
        val r = AppDataBindingResolver(codec.fromJson(resource("conformance/valid-action-def-full.json")), mapOf("erp-db" to erpId))
        for (id in listOf("go-about", "notify-all", "start-review")) assertThat(code { r.action(id) }).describedAs(id).isEqualTo(ResolutionCodes.NOT_A_DATA_ACTION)
        assertThat(code { r.action("ghost") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
    }

    @Test
    fun `resolving never changes the definition and no runtime id appears in it`() {
        val doc = resource("valid-v2-sample.json")
        val def = codec.fromJson(doc)
        AppDataBindingResolver(def, mapOf("erp-db" to erpId)).also { it.query("orders-list"); it.binding("bind-orders") }
        assertThat(codec.toJson(def)).isEqualTo(doc)
        assertThat(doc.toString()).doesNotContain(erpId.toString())
    }

    // ---- wrong type, missing reference, runtime-id injection ----

    private fun resolverOf(v2: String) = AppDataBindingResolver(codec.fromJson(doc(v2)), mapOf("d" to erpId))
    private val sources = """"dataSources":[{"id":"d","type":"postgres"}],
        "queries":[{"id":"r","dataSourceRef":"d","operationKey":"op.read"},{"id":"w","dataSourceRef":"d","mode":"WRITE","operationKey":"op.write"}]"""

    @Test
    fun `an action of the wrong query mode fails with WRONG_MODE`() {
        val r = resolverOf("""$sources,"actions":[
            {"id":"refresh-w","type":"REFRESH_QUERY","queryRef":"w"},{"id":"create-r","type":"CREATE_RECORD","queryRef":"r"},
            {"id":"submit-r","type":"SUBMIT_FORM","queryRef":"r"},{"id":"delete-r","type":"DELETE_RECORD","queryRef":"r"},{"id":"update-r","type":"UPDATE_RECORD","queryRef":"r"}]""")
        for (id in listOf("refresh-w", "create-r", "submit-r", "delete-r", "update-r")) assertThat(code { r.action(id) }).describedAs(id).isEqualTo(ResolutionCodes.WRONG_MODE)
    }

    @Test
    fun `every record action resolves its write query`() {
        val r = resolverOf("""$sources,"actions":[
            {"id":"a1","type":"SUBMIT_FORM","queryRef":"w"},{"id":"a2","type":"CREATE_RECORD","queryRef":"w"},
            {"id":"a3","type":"UPDATE_RECORD","queryRef":"w"},{"id":"a4","type":"DELETE_RECORD","queryRef":"w"}]""")
        for (id in listOf("a1", "a2", "a3", "a4")) {
            val a = r.action(id)
            assertThat(a.query?.operationKey).describedAs(id).isEqualTo("op.write")
            assertThat(a.query?.dataSource?.sourceId).describedAs(id).isEqualTo(erpId)
        }
    }

    @Test
    fun `missing references are reported with UNKNOWN_REFERENCE or NO_QUERY`() {
        val r = resolverOf("""$sources,"actions":[{"id":"ghost-q","type":"REFRESH_QUERY","queryRef":"nope"},{"id":"no-q","type":"REFRESH_QUERY"},
            {"id":"ghost-vm","type":"REFRESH_QUERY","queryRef":"r","viewModelRef":"nope"},{"id":"ghost-ds","type":"CALL_API","dataSourceRef":"nope","operationKey":"x"},
            {"id":"no-ds","type":"CALL_API"},{"id":"no-op","type":"CALL_API","dataSourceRef":"d"}]""")
        assertThat(code { r.action("ghost-q") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        assertThat(code { r.action("no-q") }).isEqualTo(ResolutionCodes.NO_QUERY)
        assertThat(code { r.action("ghost-vm") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        assertThat(code { r.action("ghost-ds") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        assertThat(code { r.action("no-ds") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        assertThat(code { r.action("no-op") }).isEqualTo(ResolutionCodes.NO_OPERATION)
    }

    @Test
    fun `a runtime id used where a local id belongs is never resolved and never accepted`() {
        val uuid = erpId.toString()
        val r = resolverOf(sources)
        // the resolver only knows local ids: a UUID is just an unknown reference
        assertThat(code { r.query(uuid) }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        assertThat(code { r.dataSource(uuid) }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
        // the validator rejects a document that tries to point at / name things by runtime id
        assertThat(check(""""dataSources":[{"id":"d","type":"postgres"}],"queries":[{"id":"q","dataSourceRef":"$uuid","operationKey":"op"}]""").map { it.path })
            .contains("queries[0].dataSourceRef")
        assertThat(check(""""dataSources":[{"id":"$uuid","type":"postgres"}]""").map { it.path }.any { it.startsWith("dataSources") }).isTrue()
        // and no member carries a runtime id: query / action / mapping do not accept a sourceId or queryId of their own
        assertThat(check(""""dataSources":[{"id":"d","type":"postgres"}],"queries":[{"id":"q","dataSourceRef":"d","sourceId":"$uuid"}]""").map { it.path }).contains("queries[0].sourceId")
        assertThat(check(""""actions":[{"id":"a","type":"REFRESH_QUERY","queryId":"$uuid"}]""").map { it.path }).contains("actions[0].queryId")
        // sourceRef is the only registration and must be a UUID, nothing else
        assertThat(check(""""dataSources":[{"id":"d","type":"postgres","sourceRef":"orders-db"}]""").map { it.path }).contains("dataSources[0].sourceRef")
    }

    @Test
    fun `a mapping is resolved with the operation key of its own query`() {
        val r = resolverOf("""$sources,"mappings":[{"id":"m","queryRef":"r","fields":[{"from":"a","to":"b"}]}]""")
        assertThat(r.mapping("m").queryOperationKey).isEqualTo("op.read")
        val unknownQuery = AppDataBindingResolver(AppDefinitionV2(mappings = listOf(MappingDef("m", queryRef = "nope", fields = listOf(FieldMappingDef("a", "b"))))))
        assertThat(code { unknownQuery.mapping("m") }).isEqualTo(ResolutionCodes.UNKNOWN_REFERENCE)
    }
}
