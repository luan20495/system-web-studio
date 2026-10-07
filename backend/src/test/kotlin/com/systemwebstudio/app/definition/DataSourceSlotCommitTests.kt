package com.systemwebstudio.app.definition

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The slot operations through the real HTTP + commit path: validate -> revision CAS -> immutable version -> audit; a project can declare its slot without any SQL seed. */
class DataSourceSlotCommitTests : IntegrationTestBase() {
    private fun patch(sc: Scenario, vararg operation: String) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":${operation.joinToString(",", "[", "]")}}""")

    @Test
    fun `a project declares, renames and removes a slot through PATCH schema - each edit is an immutable audited version`() {
        val sc = scenario(); val versions = sc.versionCount(); val audit = sc.auditCount("CREATE_VERSION")
        assertThat(patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","name":"Orders","type":"postgres"}}""").response.status).isEqualTo(200)
        val slot = sc.schema().get("dataSources").get(0)
        assertThat(slot.get("id").asString()).isEqualTo("orders"); assertThat(slot.has("sourceRef")).isFalse()
        assertThat(sc.versionCount()).isEqualTo(versions + 1); assertThat(sc.auditCount("CREATE_VERSION")).isEqualTo(audit + 1)
        assertThat(patch(sc, """{"type":"UPDATE_DATA_SOURCE","definitionId":"orders","definition":{"name":"Order table"}}""").response.status).isEqualTo(200)
        assertThat(sc.schema().get("dataSources").get(0).get("name").asString()).isEqualTo("Order table")
        assertThat(patch(sc, """{"type":"REMOVE_DATA_SOURCE","definitionId":"orders"}""").response.status).isEqualTo(200)
        assertThat(sc.schema().has("dataSources")).isFalse()
        assertThat(sc.versionCount()).isEqualTo(versions + 3)
    }

    @Test
    fun `a slot with a sourceRef or a credential is refused with 400 and nothing is stored`() {
        val sc = scenario(); val rev = sc.revision(); val versions = sc.versionCount()
        for (def in listOf("""{"id":"orders","type":"postgres","sourceRef":"11111111-1111-1111-1111-111111111111"}""", """{"id":"orders","type":"postgres","password":"x"}""",
            """{"id":"orders","type":"postgres","url":"postgres://h/db"}""")) {
            val r = patch(sc, """{"type":"ADD_DATA_SOURCE","definition":$def}""")
            assertThat(r.response.status).describedAs(def).isEqualTo(400); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("INVALID_OPERATION")
        }
        assertThat(sc.revision()).isEqualTo(rev); assertThat(sc.versionCount()).isEqualTo(versions); assertThat(sc.schema().has("dataSources")).isFalse()
    }

    @Test
    fun `removing a slot that a query still uses is rejected with 422 and the path of the dangling reference`() {
        val sc = scenario()
        patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","type":"postgres"}}""",
            """{"type":"ADD_QUERY","definition":{"id":"q","dataSourceRef":"orders","operationKey":"orders.list"}}""")
        val rev = sc.revision()
        val r = patch(sc, """{"type":"REMOVE_DATA_SOURCE","definitionId":"orders"}""")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.contentAsString).contains("queries[0].dataSourceRef")
        assertThat(sc.revision()).isEqualTo(rev); assertThat(sc.schema().get("dataSources")).hasSize(1)      // nothing changed
        assertThat(patch(sc, """{"type":"REMOVE_QUERY","definitionId":"q"}""", """{"type":"REMOVE_DATA_SOURCE","definitionId":"orders"}""").response.status).isEqualTo(200)     // in dependency order it works
    }

    @Test
    fun `a duplicate slot and an unknown slot are refused over HTTP`() {
        val sc = scenario()
        patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","type":"postgres"}}""")
        assertThat(patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","type":"rest"}}""").response.status).isEqualTo(400)
        assertThat(patch(sc, """{"type":"UPDATE_DATA_SOURCE","definitionId":"ghost","definition":{"name":"x"}}""").response.status).isEqualTo(400)
        assertThat(patch(sc, """{"type":"REMOVE_DATA_SOURCE","definitionId":"ghost"}""").response.status).isEqualTo(400)
    }
}
