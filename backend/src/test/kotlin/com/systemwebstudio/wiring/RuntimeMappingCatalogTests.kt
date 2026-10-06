package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.logic.action.ExecutionMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The document stores the LOCAL query id in `queryRef`; C3's gateway compares it with the catalog id of the approved query (the `operationKey`).
 * `RuntimeMappingCatalog` translates, on a copy, so the real gateway accepts a mapping of the query it runs and still refuses the mapping of another one.
 */
class RuntimeMappingCatalogTests {
    private val tenant = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val sample get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")

    private fun catalog(document: JsonNode) = RuntimeMappingCatalog(object : DefinitionLoader {
        override fun load(tenantId: UUID, projectId: UUID, mode: ExecutionMode) = LoadedDefinition(document, null)
        override fun loadVersion(tenantId: UUID, projectId: UUID, versionId: String) = LoadedDefinition(document, versionId)
    })

    @Test
    fun `a mapping and a view model name the operation key of their local query`() {
        val c = catalog(sample)
        assertThat(c.findMapping(AppScope(tenant, project), "orders-map")!!.queryRef).isEqualTo("orders.list")
        assertThat(c.findViewModel(AppScope(tenant, project, UUID.randomUUID().toString()), "orders-vm")!!.queryRef).isEqualTo("orders.list")
    }

    @Test
    fun `the stored document is not modified by the translation`() {
        val doc = sample
        val before = doc.toString()
        catalog(doc).findMapping(AppScope(tenant, project), "orders-map")
        assertThat(doc.toString()).isEqualTo(before)
        assertThat(doc.get("mappings").get(0).get("queryRef").asString()).isEqualTo("orders-list")
    }

    @Test
    fun `a queryRef that names no local query is left as it is, and an unknown mapping is not found`() {
        val doc: JsonNode = sample.deepCopy()
        (doc.get("mappings").get(0) as ObjectNode).put("queryRef", "ghost")
        val c = catalog(doc)
        assertThat(c.findMapping(AppScope(tenant, project), "orders-map")!!.queryRef).isEqualTo("ghost")
        assertThat(c.findMapping(AppScope(tenant, project), "nope")).isNull()
    }
}
