package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.pageValidator
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.schema.DefaultPageSchema
import com.systemwebstudio.schema.SchemaService
import com.systemwebstudio.version.SchemaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * `SchemaService.ensureInitialized` is a way to put a document into page_schemas / project_versions (a new project, a template, a restored
 * document). It must run the SAME checks as the commit path: page schema validator first, then the AppDefinitionV2 validator. No bypass.
 * Unit test with a mocked repository: nothing may be written when the initial document is invalid.
 */
class SchemaServiceInitializationTests {
    private val repo: SchemaRepository = mock(SchemaRepository::class.java)
    private val service = SchemaService(repo, DefaultPageSchema(json), pageValidator, JdbcTemplate(), validator)
    private val actor = UUID.randomUUID()
    private val project = ProjectEntity(workspaceId = UUID.randomUUID(), name = "Demo", ownerUserId = actor, createdAt = Instant.now(), updatedAt = Instant.now())

    /**
     * Mockito's any(Class) registers the matcher and returns null. Kotlin checks the value of a platform-type call when it is passed to a NON-null
     * parameter (`any(...) must not be null`), so it never reaches the mock, and the matcher it already registered leaks into the next test on the
     * same thread (InvalidUseOfMatchers / UnfinishedVerification). Register the matcher, then hand back a real placeholder value of the right type.
     */
    private fun <T : Any> anyNonNull(type: Class<T>, placeholder: T): T = any(type) ?: placeholder
    private fun anyId(): UUID = anyNonNull(UUID::class.java, UUID(0L, 0L))
    private fun anySchema(): JsonNode = anyNonNull(JsonNode::class.java, json.createObjectNode())

    private fun neverWritten() {
        verify(repo, never()).upsertSchema(anyId(), anyId(), anySchema())
        verify(repo, never()).insertVersion(anyId(), anyId(), anyInt(), anySchema(), anyString(), anyString(), any(), any(), any(), any())
    }

    @Test
    fun `an initial document with a broken V2 reference is rejected and nothing is written`() {
        `when`(repo.currentSchema(project.id)).thenReturn(null)
        val broken = AppDefinitionTestSupport.doc(""""queries":[{"id":"q","dataSourceRef":"missing"}]""")
        val e = assertThrows(ApiException::class.java) { service.ensureInitialized(project, actor, broken, "from template") }
        assertThat(e.status.value()).isEqualTo(422)
        assertThat(e.code).isEqualTo("SCHEMA_INVALID")
        neverWritten()
    }

    @Test
    fun `an initial document with a non canonical permission code is rejected`() {
        `when`(repo.currentSchema(project.id)).thenReturn(null)
        val bad = AppDefinitionTestSupport.doc(
            """"dataSources":[{"id":"d","type":"postgres"}],"queries":[{"id":"q","dataSourceRef":"d","operationKey":"op"}],
               "permissions":[{"id":"p","permission":"PROJECT_EDIT","resourceType":"QUERY","resourceRef":"q"}]""")
        assertThrows(ApiException::class.java) { service.ensureInitialized(project, actor, bad) }
        neverWritten()
    }

    @Test
    fun `an initial document that breaks the page schema is still rejected first`() {
        `when`(repo.currentSchema(project.id)).thenReturn(null)
        assertThrows(ApiException::class.java) { service.ensureInitialized(project, actor, json.readTree("""{"page":"p","sections":[{"id":"x","type":"NoSuchComponent","componentVersion":"1.0.0","props":{}}]}""")) }
        neverWritten()
    }

    @Test
    fun `a project that already has a schema is returned unchanged without validating or writing`() {
        val existing = AppDefinitionTestSupport.doc("")
        `when`(repo.currentSchema(project.id)).thenReturn(existing)
        assertThat(service.ensureInitialized(project, actor, AppDefinitionTestSupport.doc(""""queries":[{"id":"q","dataSourceRef":"missing"}]"""))).isEqualTo(existing)
        neverWritten()
    }

    @Test
    fun `a valid V2 initial document and the default page schema are written as version 1`() {
        `when`(repo.currentSchema(project.id)).thenReturn(null)
        `when`(repo.nextVersionNumber(project.id)).thenReturn(1)
        `when`(repo.insertVersion(anyId(), anyId(), anyInt(), anySchema(), anyString(), anyString(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID())
        val v2 = AppDefinitionTestSupport.resource("valid-v2-sample.json")
        assertThat(service.ensureInitialized(project, actor, v2, "from template")).isEqualTo(v2)
        verify(repo).upsertSchema(project.id, project.workspaceId, v2)
        val schema = service.ensureInitialized(project, actor)
        assertThat(schema.get("sections")).isNotNull()
    }
}
