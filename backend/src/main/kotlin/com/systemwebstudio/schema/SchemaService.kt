package com.systemwebstudio.schema

import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.version.SchemaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode

@Service
class SchemaService(
    private val repo: SchemaRepository,
    private val defaults: DefaultPageSchema,
    private val validator: PageSchemaValidator,
    private val jdbc: JdbcTemplate
) {
    /** Creates the page and version 1 for a project that has none yet (new project or one created before schemas existed). */
    @Transactional
    fun ensureInitialized(project: ProjectEntity, actorId: java.util.UUID, initial: JsonNode? = null, summary: String = "Phiên bản khởi tạo"): JsonNode {
        repo.currentSchema(project.id)?.let { return it }
        val schema = initial ?: defaults.create(project.name.take(60))
        validator.requireValid(schema)
        repo.upsertSchema(project.id, project.workspaceId, schema)
        val versionId = repo.insertVersion(project.workspaceId, project.id, repo.nextVersionNumber(project.id), schema, "INITIAL", summary, null, null, null, actorId)
        repo.insertUsage(versionId, project.id, schema)
        return schema
    }
}
