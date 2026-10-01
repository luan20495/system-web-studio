package com.systemwebstudio.version

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.schema.PageSchemaValidator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class CommitResult(val versionId: UUID, val versionNumber: Int, val revision: Long, val createdAt: Instant)

/**
 * The single place that changes the current page schema. One transaction:
 * validate -> compare-and-swap project revision -> save schema -> immutable version -> usage -> audit.
 * The UPDATE ... WHERE revision = ? statement takes the row lock, so concurrent writers serialize
 * and exactly one wins; the loser sees 0 rows and gets 409.
 */
@Service
class SchemaCommitService(
    private val jdbc: JdbcTemplate,
    private val repo: SchemaRepository,
    private val validator: PageSchemaValidator,
    private val audit: AuditService
) {
    @Transactional
    fun commit(
        ctx: AccessContext, expectedRevision: Long, schema: JsonNode, kind: String, summary: String,
        promptId: UUID? = null, restoredFrom: UUID? = null
    ): CommitResult {
        validator.requireValid(schema)
        val project = ctx.project!!
        val updated = jdbc.update(
            "UPDATE projects SET revision = revision + 1, updated_at = now() WHERE id = ? AND revision = ? AND active",
            project.id, expectedRevision
        )
        if (updated == 0) {
            val current = jdbc.queryForObject("SELECT revision FROM projects WHERE id = ?", Long::class.java, project.id)
            throw ApiException.conflict("REVISION_CONFLICT", "Project changed elsewhere; reload and retry.", mapOf("currentRevision" to current))
        }
        val newRevision = expectedRevision + 1
        repo.upsertSchema(project.id, ctx.workspaceId, schema)
        val number = repo.nextVersionNumber(project.id)
        val versionId = repo.insertVersion(ctx.workspaceId, project.id, number, schema, kind, summary, promptId, restoredFrom, expectedRevision, ctx.userId)
        repo.insertUsage(versionId, project.id, schema)
        audit.record("CREATE_VERSION", "PROJECT_VERSION", versionId, ctx.workspaceId, project.id,
            newValue = mapOf("versionNumber" to number, "kind" to kind, "revision" to newRevision))
        return CommitResult(versionId, number, newRevision, Instant.now())
    }
}
