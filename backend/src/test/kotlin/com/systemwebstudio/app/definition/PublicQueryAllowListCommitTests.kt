package com.systemwebstudio.app.definition

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The public flag through the real HTTP + commit path. */
class PublicQueryAllowListCommitTests : IntegrationTestBase() {
    private fun patch(sc: Scenario, vararg operation: String) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":${operation.joinToString(",", "[", "]")}}""")

    @Test
    fun `a public query is stored in the version, and a version restored later has exactly the allow-list it had`() {
        val sc = scenario()
        patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"orders","type":"postgres"}}""", """{"type":"ADD_QUERY","definition":{"id":"q","dataSourceRef":"orders","operationKey":"a","public":true}}""")
        assertThat(sc.schema().get("queries").get(0).get("public").asBoolean()).isTrue()
        assertThat(patch(sc, """{"type":"ADD_QUERY","definition":{"id":"w","dataSourceRef":"orders","mode":"WRITE","operationKey":"b","public":true}}""").response.status).isEqualTo(422)
        assertThat(sc.schema().get("queries")).hasSize(1)
    }
}
