package com.systemwebstudio.version

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import com.systemwebstudio.schema.SchemaService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class PatchSchemaRequest(
    @field:NotNull val expectedRevision: Long?,
    @field:NotEmpty @field:Size(max = 50) val operations: List<SchemaOperation>?,
    @field:Size(max = 300) val summary: String? = null
)
data class RestoreRequest(@field:NotNull val expectedRevision: Long?)

data class VersionSummary(
    val id: UUID, val versionNumber: Int, val kind: String, val summary: String, val createdBy: String?,
    val createdAt: Instant, val current: Boolean, val restorable: Boolean, val restoredFromVersionId: UUID?
)
data class VersionDetail(val version: VersionSummary, val schema: JsonNode?)
data class SchemaResponse(val schema: JsonNode, val revision: Long, val version: VersionSummary?)

fun VersionRow.toSummary(latestNumber: Int) = VersionSummary(
    id, versionNumber, kind, summary, createdByName, createdAt, versionNumber == latestNumber, versionNumber != latestNumber, restoredFromVersionId
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}")
class SchemaVersionController(
    private val access: AccessService,
    private val schemas: SchemaService,
    private val repo: SchemaRepository,
    private val patcher: SchemaPatchEngine,
    private val commits: SchemaCommitService,
    private val audit: AuditService,
    private val validator: com.systemwebstudio.schema.PageSchemaValidator
) {
    private fun response(ctx: com.systemwebstudio.access.AccessContext, schema: JsonNode, revision: Long): SchemaResponse {
        val latest = repo.latest(ctx.project!!.id)
        return SchemaResponse(schema, revision, latest?.toSummary(latest.versionNumber))
    }

    @GetMapping("/schema")
    @Transactional
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): SchemaResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        val schema = schemas.ensureInitialized(ctx.project!!, me.userId)
        return response(ctx, schema, ctx.project.revision)
    }

    @PatchMapping("/schema")
    @Transactional
    fun patch(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: PatchSchemaRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): SchemaResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        if (ctx.project!!.appType == "STATIC_APP") throw ApiException.conflict("CODE_PROJECT", "Code projects have no page schema; use /code/changes")
        val current = schemas.ensureInitialized(ctx.project!!, me.userId)
        val next = patcher.apply(current, request.operations!!)
        if (next == current) throw ApiException.badRequest("NO_CHANGE", "The operations did not change the page")
        val result = commits.commit(ctx, request.expectedRevision!!, next, "EDIT", request.summary?.ifBlank { null } ?: "Chỉnh sửa nội dung")
        return response(ctx, next, result.revision)
    }

    @GetMapping("/versions")
    @Transactional(readOnly = true)
    fun versions(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestParam(defaultValue = "100") limit: Int,
                 @AuthenticationPrincipal me: StudioUserDetails): List<VersionSummary> {
        access.forProject(me.userId, workspaceId, projectId)
        val rows = repo.versions(projectId, limit.coerceIn(1, 500))
        val latest = repo.latest(projectId)?.versionNumber ?: 0
        return rows.map { it.toSummary(latest) }
    }

    @GetMapping("/versions/{versionId}")
    @Transactional(readOnly = true)
    fun version(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable versionId: UUID, @AuthenticationPrincipal me: StudioUserDetails): VersionDetail {
        access.forProject(me.userId, workspaceId, projectId)
        val row = repo.version(projectId, versionId) ?: throw ApiException.notFound("VERSION_NOT_FOUND", "Version not found")
        val latest = repo.latest(projectId)!!.versionNumber
        return VersionDetail(row.toSummary(latest), row.schemaSnapshot)
    }

    @PostMapping("/versions/{versionId}/restore")
    @Transactional
    fun restore(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable versionId: UUID,
        @Valid @RequestBody request: RestoreRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): SchemaResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val old = repo.version(projectId, versionId) ?: throw ApiException.notFound("VERSION_NOT_FOUND", "Version not found")
        val snapshot = old.schemaSnapshot!!
        val problems = validator.validate(snapshot)
        if (problems.isNotEmpty()) {
            throw ApiException.conflict("SNAPSHOT_INCOMPATIBLE", "This version uses components that are no longer allowed",
                mapOf("violations" to problems.take(10).map { it.path + ": " + it.message }))
        }
        schemas.ensureInitialized(ctx.project!!, me.userId)
        val result = commits.commit(ctx, request.expectedRevision!!, snapshot, "RESTORE", "Khôi phục từ phiên bản v${old.versionNumber}", restoredFrom = old.id)
        audit.record("RESTORE_VERSION", "PROJECT_VERSION", old.id, workspaceId, projectId,
            oldValue = mapOf("restoredFromVersion" to old.versionNumber), newValue = mapOf("newVersion" to result.versionNumber))
        return response(ctx, snapshot, result.revision)
    }
}
