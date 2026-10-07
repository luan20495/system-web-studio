package com.systemwebstudio.ai.planner

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.ai.AiGate
import com.systemwebstudio.ai.AiUsageService
import com.systemwebstudio.ai.ModelAccessService
import com.systemwebstudio.ai.PromptUsage
import com.systemwebstudio.ai.tenant.AiSource
import com.systemwebstudio.ai.tenant.AiSourceException
import com.systemwebstudio.ai.tenant.AiSourceKind
import com.systemwebstudio.ai.tenant.AiSourceResolver
import com.systemwebstudio.ai.tenant.TenantAiConfig
import com.systemwebstudio.ai.tenant.TenantAiConfigs
import com.systemwebstudio.ai.tenant.TenantRef
import com.systemwebstudio.ai.tenant.tenantModelDenied
import com.systemwebstudio.app.definition.AppDefinitionValidator
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.component.ComponentRegistry
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiProviderRegistry
import com.systemwebstudio.integration.llm.ExternalLLMProvider
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import com.systemwebstudio.schema.SchemaService
import com.systemwebstudio.template.BusinessTemplates
import com.systemwebstudio.version.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * What the data platform (C3) offers to a user: the masked schema-discovery metadata of the data sources and operations the user is
 * granted (names of operations, parameters and fields; never SQL, URLs, credentials or sample data). PROVISIONAL (B-C2-01): until C3
 * provides an implementation, the default says "nothing is granted", so the planner can only work with what the document already holds.
 */
fun interface AiDataCatalog { fun granted(ctx: AccessContext): List<GrantedDataSource> }

/**
 * The AI planner API is OFF until switched on: `app.ai-planner.enabled=true` (application.yml belongs to C0). It needs no table of its own:
 * prompts and runs are recorded in the existing `prompts` / `prompt_runs` (same history as the page editor).
 */
@Configuration
@ConditionalOnProperty("app.ai-planner.enabled", havingValue = "true")
class AiPlannerConfiguration {
    @Bean
    fun planGuard(json: JsonMapper) = PlanGuard({ json.treeToValue(it, SchemaOperation::class.java) }, json)

    @Bean
    fun appPlanner(patcher: SchemaPatchEngine, definitions: AppDefinitionValidator, guard: PlanGuard, json: JsonMapper,
                   @Value("\${app.ai-planner.max-document-chars:24000}") maxChars: Int) = AppPlanner(patcher, definitions, guard, json, maxChars)

    @Bean
    @ConditionalOnMissingBean(AiDataCatalog::class)
    fun noDataCatalog() = AiDataCatalog { emptyList() }
}

data class PlanRequest(
    @field:NotBlank @field:Size(max = 2000) val prompt: String, @field:NotNull val expectedRevision: Long?,
    /** a platform model id, `tenant:<model>` for the tenant's own provider, or "auto" / omitted */
    @field:Pattern(regexp = "^[A-Za-z0-9._:/@-]{1,120}$") val model: String? = null,
    /** true = propose only: nothing is stored, no version is created */
    val dryRun: Boolean = false
)

data class PlanResponse(
    val promptId: UUID?, val status: String, val applied: Boolean, val message: String, val operations: List<SchemaOperation>,
    /** the document the operations produce (the preview); null when nothing is proposed */
    val document: JsonNode?, val violations: List<PlanViolation>, val revision: Long, val version: VersionSummary?,
    /** PLATFORM | TENANT: whose AI answered */
    val source: String, val provider: String?, val model: String?, val usage: PromptUsage?, val stopped: String? = null
)

/**
 * POST /ai/plans: prompt → AI source (tenant / platform) → App Planner → typed operations → validators → [immutable version] → preview.
 * Platform AI keeps going through the unchanged AI Gateway (access, limits, budgets, accounting); a tenant's own provider is paid by the
 * tenant, so the platform's model policies and money budgets do not apply to it, but the per-user rate limit and the accounting do.
 * The planner never publishes: SHARE and PUBLISH are separate (D-C2-06) and publishConfig cannot be changed by AI.
 */
@RestController
@ConditionalOnProperty("app.ai-planner.enabled", havingValue = "true")
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/ai/plans")
class AppPlannerController(
    private val access: AccessService, private val planner: AppPlanner, private val registry: ComponentRegistry, private val schemas: SchemaService,
    private val commits: SchemaCommitService, private val repo: SchemaRepository, private val gate: AiGate, private val usage: AiUsageService,
    private val modelAccess: ModelAccessService, private val external: ExternalLLMProvider, private val providers: AiProviderRegistry,
    private val tenantConfigs: ObjectProvider<TenantAiConfigs>, private val catalog: AiDataCatalog, private val limiter: RateLimiter,
    private val audit: AuditService, private val jdbc: JdbcTemplate, private val json: JsonMapper, txManager: PlatformTransactionManager,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    private val tx = TransactionTemplate(txManager)

    @PostMapping
    fun plan(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: PlanRequest,
             @AuthenticationPrincipal me: StudioUserDetails): PlanResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val project = ctx.project!!
        if (project.appType == "STATIC_APP") throw ApiException.conflict("CODE_PROJECT", "Code projects are edited through /code/ai and /code/changes")
        limiter.require("plan:${me.userId}", promptMax, 60, "plan")
        if (project.revision != request.expectedRevision)
            throw ApiException.conflict("REVISION_CONFLICT", "Project changed elsewhere; reload and retry.", mapOf("currentRevision" to project.revision))

        // 1. whose AI: the tenant's own when the tenant selected one, the platform otherwise; the user's model rules apply either way
        val tenant = tenantConfigs.getIfAvailable()?.find(TenantRef.of(ctx))
        val denied: (String) -> Boolean = tenant?.let { tenantModelDenied(modelAccess, ctx, it) } ?: { false }
        val source = try { AiSourceResolver.resolve(tenant, request.model, denied) } catch (e: AiSourceException) { throw ApiException.forbidden(e.message ?: "Model not allowed", e.code) }
        val decision = if (source.kind == AiSourceKind.PLATFORM) gate.authorize(ctx, source.model, projectId) else null

        // 2. the page exists; a real prompt (not a dry run) is recorded like every other prompt
        val text = request.prompt.trim()
        val promptId = if (request.dryRun) null else UUID.randomUUID()
        val current = tx.execute {
            schemas.ensureInitialized(project, me.userId).also {
                if (promptId != null) jdbc.update("INSERT INTO prompts (id, workspace_id, project_id, created_by, text) VALUES (?,?,?,?,?)", promptId, workspaceId, projectId, me.userId, text)
            }
        }!!
        val versions = registry.versions()
        val context = PlannerContext(current,
            registry.list().filter { it.status == "ACTIVE" }.map { PlannerComponent(it.id, it.category, it.latestVersion, versions["${it.id}@${it.latestVersion}"]?.dto?.propsSchema) },
            templateHints(), catalog.granted(ctx))

        // 3. the model call runs with no transaction or connection held; every upstream call is accounted for even if a later step fails
        var used = source
        var outcome = planner.plan(text, context, llm(source, tenant, decision))
        if (outcome.status == PlanStatus.FAILED && outcome.stopped == null) {
            // fall back to the platform only when the tenant explicitly enabled it, through the platform's own gate
            val fallback = AiSourceResolver.fallback(source)
            val platform = fallback?.let { runCatching { gate.authorize(ctx, null, projectId) }.getOrNull() }
            if (fallback != null && platform != null) {
                used = fallback
                val second = planner.plan(text, context, llm(fallback, tenant, platform))
                outcome = second.copy(calls = outcome.calls + second.calls)
            }
        }
        val calls = gate.after(outcome.calls, promptId, ctx, projectId)
        val promptUsage = usage.summarize(calls)

        // 4. apply = the one commit path (validate → CAS → immutable version → audit); a dry run stops here
        var revision = project.revision
        var version: VersionSummary? = null
        var applied = false
        var versionId: UUID? = null
        if (outcome.status == PlanStatus.PROPOSED && !request.dryRun) {
            val result = tx.execute { commits.commit(ctx, request.expectedRevision!!, outcome.document!!, "PROMPT", ("AI plan: $text").take(120), promptId = promptId) }!!
            applied = true; revision = result.revision; versionId = result.versionId
            version = repo.latest(projectId)?.let { it.toSummary(it.versionNumber) }
        }
        if (promptId != null) recordRun(ctx, promptId, outcome, used, applied, versionId, request.expectedRevision!!)
        audit.record(if (request.dryRun) "PLAN_APP_PREVIEW" else "PLAN_APP", "PROJECT", projectId, workspaceId, projectId,
            newValue = mapOf("status" to outcome.status.name, "applied" to applied, "source" to used.kind.name, "provider" to outcome.provider, "model" to outcome.model,
                "operations" to outcome.operations.size, "violations" to outcome.violations.size, "aiCalls" to outcome.calls.size, "totalTokens" to promptUsage?.totalTokens))
        return PlanResponse(promptId, outcome.status.name, applied, outcome.message, outcome.operations, outcome.document, outcome.violations, revision, version,
            used.kind.name, outcome.provider, outcome.model, promptUsage, outcome.stopped)
    }

    /** the model backend for one source; a PlannerLlm never sees a key, a connector id or the database */
    private fun llm(source: AiSource, tenant: TenantAiConfig?, decision: AiGate.Decision?) = object : PlannerLlm {
        override fun <T> complete(system: String, user: String, parse: (String) -> T): PlannerCompletion<T> {
            // an unusable answer is BAD_OUTPUT (try the next model), exactly like the page editor
            val safe: (String) -> T = { try { parse(it) } catch (e: BadPlanOutput) { throw ExternalLLMProvider.BadModelOutput(e.message ?: "bad plan") } }
            val c = when {
                source.kind == AiSourceKind.TENANT -> {
                    val cfg = tenant ?: throw ApiException.conflict("TENANT_AI_MISSING", "The tenant's AI provider is no longer configured")
                    val provider = providers.buildDetached(cfg.kind.name, "tenant", cfg.name, cfg.baseUrl, cfg.apiKey.orEmpty(), cfg.models)
                    external.complete(null, system, user, MAX_TOKENS, emptySet(), null, null, listOf(Triple(provider, source.model!!, AiSourceResolver.TENANT_PREFIX + source.model)), safe)
                }
                decision?.external == true -> external.complete(source.model, system, user, MAX_TOKENS, decision.exclude, null, null, null, safe)
                else -> return PlannerCompletion(null, "mock", "mock", emptyList(),
                    "Trình mô phỏng chỉ chỉnh sửa trang; lập kế hoạch ứng dụng cần một model AI thật")
            }
            return PlannerCompletion(c.result, c.provider, c.model, c.calls, c.error, c.stopped, c.partial)
        }
    }

    /** approved templates the model may suggest (names only): the built-in business templates and the company's approved ones */
    private fun templateHints(): List<TemplateHint> =
        BusinessTemplates.all(json).map { TemplateHint(it.id.toString(), it.name, it.category) } +
            jdbc.query("SELECT id, name, category FROM templates WHERE status = 'ACTIVE' AND review_status = 'APPROVED' AND visibility = 'COMPANY' ORDER BY usage_count DESC, updated_at DESC LIMIT 20",
                { rs, _ -> TemplateHint(rs.getString(1), rs.getString(2), rs.getString(3)) })

    private fun recordRun(ctx: AccessContext, promptId: UUID, outcome: PlanOutcome, source: AiSource, applied: Boolean, versionId: UUID?, expectedRevision: Long) {
        val status = when {
            applied -> "UPDATED"
            outcome.status == PlanStatus.NO_CHANGE -> "NO_CHANGE"
            outcome.status == PlanStatus.REJECTED -> "UNSUPPORTED"
            outcome.stopped == "CANCELLED" -> "CANCELLED"
            outcome.stopped == "TIMEOUT" -> "TIMEOUT"
            else -> "FAILED"
        }
        jdbc.update(
            """INSERT INTO prompt_runs (id, prompt_id, workspace_id, project_id, provider, intent, status, schema_patch, assistant_message,
               version_id, registry_reuse, expected_revision, model, reuse_sources) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,CAST(? AS jsonb))""",
            UUID.randomUUID(), promptId, ctx.workspaceId, ctx.project!!.id, outcome.provider ?: "none", "PLAN_APP", status, json.writeValueAsString(outcome.operations),
            outcome.message.take(2000), versionId, 100, expectedRevision, outcome.model,
            json.writeValueAsString(mapOf("source" to source.kind.name, "planStatus" to outcome.status.name, "violations" to outcome.violations.take(10).map { "${it.path}: ${it.message}" })))
    }

    private companion object { const val MAX_TOKENS = 3000 }
}
