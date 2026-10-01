package com.systemwebstudio.prompt

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.component.ComponentRegistry
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.ComponentInfo
import com.systemwebstudio.integration.llm.LLMProvider
import com.systemwebstudio.integration.llm.LLMRequest
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import com.systemwebstudio.schema.SchemaService
import com.systemwebstudio.version.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class PromptRequest(@field:NotBlank @field:Size(max = 2000) val prompt: String, @field:NotNull val expectedRevision: Long?)
data class AssistantMessage(val role: String, val content: String)
data class PromptResponse(
    val promptId: UUID, val outcome: String, val message: AssistantMessage, val schemaPatch: List<SchemaOperation>,
    val pageSchema: JsonNode, val revision: Long, val version: VersionSummary?, val registryReuse: Int
)
data class PromptHistoryItem(
    val id: UUID, val text: String, val createdAt: Instant, val outcome: String, val assistantMessage: String,
    val versionId: UUID?, val registryReuse: Int?
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/prompts")
class PromptController(
    private val access: AccessService,
    private val llm: LLMProvider,
    private val registry: ComponentRegistry,
    private val schemas: SchemaService,
    private val patcher: SchemaPatchEngine,
    private val commits: SchemaCommitService,
    private val repo: SchemaRepository,
    private val limiter: RateLimiter,
    private val audit: AuditService,
    private val jdbc: JdbcTemplate,
    private val json: JsonMapper,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    /**
     * registryReuse = share (0-100) of sections whose component is an ACTIVE registry component.
     * Custom/unregistered components cannot pass validation in V1, so the value is 100 for every saved page;
     * the metric becomes informative once non-registry components are allowed.
     */
    private fun reuse(schema: JsonNode): Int {
        val sections = schema.get("sections").toList()
        if (sections.isEmpty()) return 100
        val active = registry.list().filter { it.status == "ACTIVE" }.map { it.id }.toSet()
        return Math.round(100.0 * sections.count { it.get("type").asString() in active } / sections.size).toInt()
    }

    @PostMapping
    @Transactional
    fun run(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: PromptRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): PromptResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        limiter.require("prompt:${me.userId}", promptMax, 60, "prompt")
        val project = ctx.project!!
        if (project.revision != request.expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT", "Project changed elsewhere; reload and retry.", mapOf("currentRevision" to project.revision))
        }
        val current = schemas.ensureInitialized(project, me.userId)
        val promptId = UUID.randomUUID()
        jdbc.update("INSERT INTO prompts (id, workspace_id, project_id, created_by, text) VALUES (?,?,?,?,?)",
            promptId, workspaceId, projectId, me.userId, request.prompt.trim())

        val plan = llm.plan(LLMRequest(request.prompt.trim(), current, registry.list().filter { it.status == "ACTIVE" }.map { ComponentInfo(it.id, it.category, it.latestVersion) }))
        var next = current
        var version: VersionSummary? = null
        var revision = project.revision
        var outcome = if (plan.intent == "UNSUPPORTED") "UNSUPPORTED" else "NO_CHANGE"
        var versionId: UUID? = null
        if (plan.operations.isNotEmpty()) {
            next = patcher.apply(current, plan.operations)
            if (next != current) {
                val result = commits.commit(ctx, request.expectedRevision!!, next, "PROMPT", request.prompt.trim().take(120), promptId = promptId)
                outcome = "UPDATED"; revision = result.revision; versionId = result.versionId
                version = repo.latest(projectId)?.let { it.toSummary(it.versionNumber) }
            }
        }
        val reuse = reuse(next)
        jdbc.update(
            """INSERT INTO prompt_runs (id, prompt_id, workspace_id, project_id, provider, intent, status, schema_patch, assistant_message,
               version_id, registry_reuse, expected_revision) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?)""",
            UUID.randomUUID(), promptId, workspaceId, projectId, llm.name, plan.intent, outcome, json.writeValueAsString(plan.operations),
            plan.message.take(2000), versionId, reuse, request.expectedRevision
        )
        audit.record("RUN_PROMPT", "PROMPT", promptId, workspaceId, projectId,
            newValue = mapOf("outcome" to outcome, "intent" to plan.intent, "provider" to llm.name, "operations" to plan.operations.size))
        return PromptResponse(promptId, outcome, AssistantMessage("assistant", plan.message), plan.operations, next, revision, version, reuse)
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun history(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<PromptHistoryItem> {
        access.forProject(me.userId, workspaceId, projectId)
        return jdbc.query(
            """SELECT p.id, p.text, p.created_at, r.status, r.assistant_message, r.version_id, r.registry_reuse
               FROM prompts p JOIN prompt_runs r ON r.prompt_id = p.id WHERE p.project_id = ? ORDER BY p.created_at ASC LIMIT 200""",
            { rs, _ -> PromptHistoryItem(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID::class.java), rs.getObject(7) as Int?) }, projectId)
    }
}
