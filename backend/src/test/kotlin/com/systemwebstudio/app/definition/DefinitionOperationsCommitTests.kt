package com.systemwebstudio.app.definition

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * V2 operations through the real HTTP + commit path (PostgreSQL via Testcontainers): validate -> revision CAS -> immutable version -> audit,
 * the same path as every section edit; a restore of a version made before V2 members existed keeps working.
 */
class DefinitionOperationsCommitTests : IntegrationTestBase() {
    private fun ops(vararg operation: String) = operation.joinToString(",", "[", "]")
    private fun patch(sc: Scenario, vararg operation: String) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":${ops(*operation)}}""")

    @Test
    fun `typed V2 operations create immutable versions and are audited`() {
        val sc = scenario()
        val versionsBefore = sc.versionCount()
        val auditBefore = sc.auditCount("CREATE_VERSION")

        val r = patch(sc, """{"type":"ADD_VIEW_MODEL","definition":{"id":"vm","fields":[{"name":"name"}]}}""",
            """{"type":"ADD_DATA_BINDING","definition":{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"vm"}}""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(sc.schema().get("viewModels").get(0).get("id").asString()).isEqualTo("vm")
        assertThat(sc.schema().get("dataBindings").get(0).get("sectionId").asString()).isEqualTo("products-1")
        assertThat(sc.versionCount()).isEqualTo(versionsBefore + 1)
        assertThat(sc.auditCount("CREATE_VERSION")).isEqualTo(auditBefore + 1)

        val u = patch(sc, """{"type":"UPDATE_VIEW_MODEL","definitionId":"vm","definition":{"name":"Products"}}""")
        assertThat(u.response.status).isEqualTo(200)
        assertThat(sc.versionCount()).isEqualTo(versionsBefore + 2)
    }

    @Test
    fun `an invalid V2 operation is rejected and nothing is stored`() {
        val sc = scenario()
        val rev = sc.revision(); val versions = sc.versionCount()
        val dangling = patch(sc, """{"type":"ADD_DATA_BINDING","definition":{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"nope"}}""")
        assertThat(dangling.response.status).isEqualTo(422)
        val unknownTarget = patch(sc, """{"type":"UPDATE_QUERY","definitionId":"ghost","definition":{"name":"x"}}""")
        assertThat(unknownTarget.response.status).isEqualTo(400)
        val smuggled = patch(sc, """{"type":"ADD_ACTION","definition":{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","url":"https://evil.example"}}""")
        assertThat(smuggled.response.status).isEqualTo(422)
        assertThat(sc.revision()).isEqualTo(rev)
        assertThat(sc.versionCount()).isEqualTo(versions)
        assertThat(sc.schema().has("actions")).isFalse()
    }

    @Test
    fun `an older version can still be restored after V2 members were added`() {
        val sc = scenario()
        val original = sc.schema()
        patch(sc, """{"type":"ADD_VIEW_MODEL","definition":{"id":"vm","fields":[{"name":"name"}]}}""")
        assertThat(sc.schema().has("viewModels")).isTrue()
        val oldest = sc.s.body(sc.s.get("${sc.base}/versions")).minByOrNull { it.get("versionNumber").asInt() }!!
        val restored = sc.s.post("${sc.base}/versions/${oldest.get("id").asString()}/restore", """{"expectedRevision":${sc.revision()}}""")
        assertThat(restored.response.status).isEqualTo(200)
        assertThat(sc.schema()).isEqualTo(original)                      // exactly the legacy document again
        assertThat(sc.schema().has("viewModels")).isFalse()
    }

    @Test
    fun `old section operations keep working unchanged`() {
        val sc = scenario()
        val r = patch(sc, """{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Still works"}""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(sc.schema().has("viewModels")).isFalse()
    }
}
