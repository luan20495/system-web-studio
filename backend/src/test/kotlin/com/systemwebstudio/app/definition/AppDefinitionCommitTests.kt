package com.systemwebstudio.app.definition

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaCommitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * End to end through the real commit path (PostgreSQL via Testcontainers): V2 members are validated by SchemaCommitService, stored in
 * the same page_schemas row / immutable project version as before, and the existing PATCH operations keep working on such a document.
 */
class AppDefinitionCommitTests : IntegrationTestBase() {
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var commits: SchemaCommitService

    private val v2Members = """{
        "dataSources":[{"id":"d","type":"rest"}],
        "queries":[{"id":"q","dataSourceRef":"d"}],
        "viewModels":[{"id":"v","queryRef":"q","fields":[{"name":"name"}]}],
        "dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v"}]}"""

    private fun withMembers(base: JsonNode, members: String): JsonNode {
        val copy = base.deepCopy() as ObjectNode
        val extra = json.readTree(members)
        extra.propertyNames().forEach { copy.set(it, extra.get(it)) }
        return copy
    }

    @Test
    fun `a valid V2 document is committed as an immutable version and survives later edits`() {
        val sc = scenario()
        val current = sc.schema()
        val rev = sc.revision()
        val versionsBefore = sc.versionCount()
        val ctx = access.forProject(sc.user.id, sc.ws, sc.projectId)

        val result = commits.commit(ctx, rev, withMembers(current, v2Members), "EDIT", "Add data binding")
        assertThat(result.revision).isEqualTo(rev + 1)
        assertThat(sc.versionCount()).isEqualTo(versionsBefore + 1)
        assertThat(sc.schema().get("dataBindings").get(0).get("viewModelRef").asString()).isEqualTo("v")

        // the existing operation API still works and carries the V2 members along
        val patched = sc.s.patch("${sc.base}/schema",
            """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"New title"}]}""")
        assertThat(patched.response.status).isEqualTo(200)
        assertThat(sc.schema().get("queries").get(0).get("id").asString()).isEqualTo("q")
        assertThat(sc.versionCount()).isEqualTo(versionsBefore + 2)
    }

    @Test
    fun `an invalid V2 document is rejected with 422 and nothing is stored`() {
        val sc = scenario()
        val current = sc.schema()
        val rev = sc.revision()
        val versionsBefore = sc.versionCount()
        val ctx = access.forProject(sc.user.id, sc.ws, sc.projectId)

        val dangling = withMembers(current, """{"dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"q","dataSourceRef":"missing"}]}""")
        val e = assertThrows(ApiException::class.java) { commits.commit(ctx, rev, dangling, "EDIT", "bad") }
        assertThat(e.status.value()).isEqualTo(422)
        assertThat(e.code).isEqualTo("SCHEMA_INVALID")
        assertThat(sc.revision()).isEqualTo(rev)
        assertThat(sc.versionCount()).isEqualTo(versionsBefore)
        assertThat(sc.schema().has("queries")).isFalse()
    }

    @Test
    fun `a plain page schema commits exactly as before`() {
        val sc = scenario()
        val r = sc.s.patch("${sc.base}/schema",
            """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Hello"}]}""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(sc.schema().has("schemaVersion")).isFalse()
        assertThat(sc.schema().has("dataSources")).isFalse()
    }
}
