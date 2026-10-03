package com.systemwebstudio.prompt

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.ai.AiUsageService
import com.systemwebstudio.ai.PromptUsage
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class PromptRequest(
    @field:NotBlank @field:Size(max = 2000) val prompt: String, @field:NotNull val expectedRevision: Long?,
    @field:jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9._:/-]{1,120}$") val model: String? = null
)
data class AssistantMessage(val role: String, val content: String)
data class PromptResponse(
    val promptId: UUID, val outcome: String, val message: AssistantMessage, val schemaPatch: List<SchemaOperation>,
    val pageSchema: JsonNode, val revision: Long, val version: VersionSummary?, val registryReuse: Int,
    val provider: String? = null, val model: String? = null, val usage: PromptUsage? = null
)
data class PromptHistoryItem(
    val id: UUID, val text: String, val createdAt: Instant, val outcome: String, val assistantMessage: String,
    val versionId: UUID?, val registryReuse: Int?, val provider: String? = null, val model: String? = null,
    /** upstream calls for this prompt (0 = simulator); tokens/cost as reported by the provider, null when none were reported */
    val aiCalls: Int = 0, val totalTokens: Long? = null, val costUsd: java.math.BigDecimal? = null
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/prompts")
class PromptController(
    private val access: AccessService,
    private val llm: LLMProvider,
    private val ai: com.systemwebstudio.integration.llm.AiService,
    private val registry: ComponentRegistry,
    private val schemas: SchemaService,
    private val patcher: SchemaPatchEngine,
    private val validator: com.systemwebstudio.schema.PageSchemaValidator,
    private val commits: SchemaCommitService,
    private val repo: SchemaRepository,
    private val limiter: RateLimiter,
    private val audit: AuditService,
    private val jdbc: JdbcTemplate,
    private val json: JsonMapper,
    private val usage: AiUsageService,
    txManager: PlatformTransactionManager,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    /**
     * registryReuse = share (0-100) of sections whose component is an ACTIVE registry component.
     * Custom/unregistered components cannot pass validation in V1, so the value is 100 for every saved page;
     * the metric becomes informative once non-registry components are allowed.
     */
    private val tx = TransactionTemplate(txManager)

    private fun reuse(schema: JsonNode): Int {
        val sections = schema.get("sections").toList()
        if (sections.isEmpty()) return 100
        val active = registry.list().filter { it.status == "ACTIVE" }.map { it.id }.toSet()
        return Math.round(100.0 * sections.count { it.get("type").asString() in active } / sections.size).toInt()
    }

    /**
     * Three steps, deliberately NOT one transaction: (1) a short transaction makes sure the page exists and records the prompt;
     * (2) the model call runs with no transaction or DB connection held (it can take tens of seconds per attempt) and every
     * upstream call is recorded at once, so spent tokens are accounted for even if (3) fails; (3) validate + commit + prompt run
     * in one transaction. A concurrent edit during (2) is caught by the revision compare-and-swap in SchemaCommitService.
     */
    @PostMapping
    fun run(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: PromptRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): PromptResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        if (ctx.project!!.appType == "STATIC_APP") throw ApiException.conflict("CODE_PROJECT", "Code projects are edited through /code/ai and /code/changes")
        limiter.require("prompt:${me.userId}", promptMax, 60, "prompt")
        ai.requireAllowed(request.model)
        if (ai.isExternal(request.model)) {
            usage.requireBudget(me.userId, workspaceId)
            // Free-tier quotas are shared by everyone using the key, so each user gets a daily allowance of real-AI prompts.
            limiter.require("ai:${me.userId}", ai.dailyLimitPerUser, 86_400, "ai-daily")
        }
        val project = ctx.project!!
        if (project.revision != request.expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT", "Project changed elsewhere; reload and retry.", mapOf("currentRevision" to project.revision))
        }
        val text = request.prompt.trim()
        val promptId = UUID.randomUUID()
        val current = tx.execute {
            schemas.ensureInitialized(project, me.userId).also {
                jdbc.update("INSERT INTO prompts (id, workspace_id, project_id, created_by, text) VALUES (?,?,?,?,?)", promptId, workspaceId, projectId, me.userId, text)
            }
        }!!

        val versions = registry.versions()
        val plan = llm.plan(LLMRequest(text, current,
            registry.list().filter { it.status == "ACTIVE" }.map { ComponentInfo(it.id, it.category, it.latestVersion, versions["${it.id}@${it.latestVersion}"]?.dto?.propsSchema) }, request.model))
        val calls = usage.price(plan.calls)
        usage.record(calls, promptId, workspaceId, projectId, me.userId)
        val promptUsage = usage.summarize(calls)

        return tx.execute { apply(ctx, request, text, promptId, current, plan, promptUsage) }!!
    }

    private fun apply(
        ctx: com.systemwebstudio.access.AccessContext, request: PromptRequest, text: String, promptId: UUID, current: JsonNode,
        plan: com.systemwebstudio.integration.llm.LLMResponse, promptUsage: PromptUsage?
    ): PromptResponse {
        val project = ctx.project!!
        val workspaceId = ctx.workspaceId; val projectId = project.id
        var next = current
        var version: VersionSummary? = null
        var revision = project.revision
        var outcome = if (plan.intent == "UNSUPPORTED") "UNSUPPORTED" else "NO_CHANGE"
        var versionId: UUID? = null
        var planMessage = plan.message
        if (plan.operations.isNotEmpty()) {
            // An external model can propose rubbish. That is "the AI failed", not a client error: nothing is stored and the user
            // is told so. (For the built-in simulator an invalid plan is a server bug and still surfaces as 400.)
            val external = (plan.provider ?: llm.name) != "mock"
            next = try { patcher.apply(current, plan.operations) } catch (e: ApiException) {
                if (!external) throw e
                planMessage = "AI đề xuất thay đổi không hợp lệ (${e.message}); nội dung không đổi."; outcome = "UNSUPPORTED"; current
            }
            val violations = if (next != current) validator.validate(next) else emptyList()
            if (external && violations.isNotEmpty()) {
                planMessage = "AI đề xuất nội dung không hợp lệ (${violations.first().path}: ${violations.first().message}); nội dung không đổi."; outcome = "UNSUPPORTED"; next = current
            }
            if (next != current) {
                val result = commits.commit(ctx, request.expectedRevision!!, next, "PROMPT", text.take(120), promptId = promptId)
                outcome = "UPDATED"; revision = result.revision; versionId = result.versionId
                version = repo.latest(projectId)?.let { it.toSummary(it.versionNumber) }
            }
        }
        val reuse = reuse(next)
        jdbc.update(
            """INSERT INTO prompt_runs (id, prompt_id, workspace_id, project_id, provider, intent, status, schema_patch, assistant_message,
               version_id, registry_reuse, expected_revision, model) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?)""",
            UUID.randomUUID(), promptId, workspaceId, projectId, plan.provider ?: llm.name, plan.intent, outcome, json.writeValueAsString(plan.operations),
            planMessage.take(2000), versionId, reuse, request.expectedRevision, plan.model
        )
        audit.record("RUN_PROMPT", "PROMPT", promptId, workspaceId, projectId,
            newValue = mapOf("outcome" to outcome, "intent" to plan.intent, "provider" to (plan.provider ?: llm.name), "model" to plan.model,
                "operations" to plan.operations.size, "aiCalls" to plan.calls.size, "totalTokens" to promptUsage?.totalTokens))
        return PromptResponse(promptId, outcome, AssistantMessage("assistant", planMessage), plan.operations, next, revision, version, reuse,
            plan.provider ?: llm.name, plan.model, promptUsage)
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun history(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestParam(defaultValue = "100") limit: Int,
                @AuthenticationPrincipal me: StudioUserDetails): List<PromptHistoryItem> {
        access.forProject(me.userId, workspaceId, projectId)
        // newest first (the previous ASC LIMIT returned the OLDEST 200 and hid recent prompts on long projects)
        return jdbc.query(
            """SELECT p.id, p.text, p.created_at, r.status, r.assistant_message, r.version_id, r.registry_reuse, r.provider, r.model,
                      coalesce(c.calls, 0), c.tokens, c.cost
               FROM prompts p JOIN prompt_runs r ON r.prompt_id = p.id
               LEFT JOIN LATERAL (SELECT count(*) AS calls, sum(total_tokens) AS tokens, sum(cost_usd) AS cost FROM ai_calls WHERE prompt_id = p.id) c ON true
               WHERE p.project_id = ? ORDER BY p.created_at DESC, p.id DESC LIMIT ?""",
            { rs, _ -> PromptHistoryItem(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID::class.java), rs.getObject(7) as Int?, rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getObject(11)?.let { (it as Number).toLong() }, rs.getBigDecimal(12)) }, projectId, limit.coerceIn(1, 500))
    }
}
