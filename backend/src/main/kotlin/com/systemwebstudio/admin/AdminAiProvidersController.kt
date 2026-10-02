package com.systemwebstudio.admin

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiProviderException
import com.systemwebstudio.integration.llm.AiProviderRegistry
import com.systemwebstudio.integration.llm.AiService
import com.systemwebstudio.integration.llm.OpenRouterClient
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PriceDto(val id: UUID, val provider: String, val modelId: String, val inputUsdPerMTok: BigDecimal, val outputUsdPerMTok: BigDecimal,
                    val effectiveFrom: Instant, val note: String, val createdBy: String?, val createdAt: Instant)
data class ModelInfo(val id: String, val name: String, val enabled: Boolean, val paid: Boolean, val price: PriceDto?)
data class ProviderInfo(val id: String, val name: String, val configured: Boolean, val paid: Boolean, val endpointHost: String?,
                        val defaultPolicy: String, val models: List<ModelInfo>, val configHint: String)
data class ProbeResult(val id: String, val ok: Boolean, val latencyMs: Long, val detail: String)
data class PolicyRequest(@field:NotBlank @field:Size(max = 200) val modelId: String, @field:NotNull val enabled: Boolean?)
data class PriceRequest(
    @field:NotBlank @field:Size(max = 200) val modelId: String,
    @field:NotNull @field:DecimalMin("0") @field:DecimalMax("10000") val inputUsdPerMTok: BigDecimal?,
    @field:NotNull @field:DecimalMin("0") @field:DecimalMax("10000") val outputUsdPerMTok: BigDecimal?,
    @field:Size(max = 200) val note: String? = null
)

/** AI providers for system admins: what is configured, which models are enabled, the pricing catalog, live connection checks. */
@RestController
@RequestMapping("/api/v1/admin/ai")
class AdminAiProvidersController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService,
    private val ai: AiService, private val registry: AiProviderRegistry, private val openRouter: OpenRouterClient
) {
    private val priceSelect = """SELECT p.id, p.provider, p.model_id, p.input_usd_per_mtok, p.output_usd_per_mtok, p.effective_from, p.note,
        coalesce(u.display_name, u.username), p.created_at FROM ai_model_pricing p LEFT JOIN users u ON u.id = p.created_by"""
    private fun priceRow(rs: java.sql.ResultSet) = PriceDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
        rs.getBigDecimal(5), rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant())

    /** price in force now, per model */
    private fun currentPrices(): Map<String, PriceDto> =
        jdbc.query("$priceSelect WHERE p.effective_from <= now() ORDER BY p.model_id, p.effective_from DESC, p.created_at DESC") { rs, _ -> priceRow(rs) }
            .groupBy { it.modelId }.mapValues { it.value.first() }

    /** Known model ids: OpenRouter free models (when configured) and the explicit model lists of configured providers. */
    private fun known(modelId: String): String? = registry.split(modelId)?.let { (p, bare) -> if (p.configured && bare in p.models) p.id else null }
        ?: if (ai.externalEnabled && ai.freeModels().any { it.id == modelId }) "openrouter" else null

    @GetMapping("/providers")
    @Transactional(readOnly = true)
    fun providers(@AuthenticationPrincipal me: StudioUserDetails): List<ProviderInfo> {
        guard.require(me.userId)
        val pol = ai.policies(); val prices = currentPrices()
        val or = ProviderInfo("openrouter", "OpenRouter (model miễn phí)", ai.externalEnabled, false, "https://openrouter.ai", "ENABLED_UNLESS_DISABLED",
            if (ai.externalEnabled) ai.freeModels().map { ModelInfo(it.id, it.name, pol[it.id] != false, false, prices[it.id]) } else emptyList(),
            "OPENROUTER_API_KEY; chỉ model miễn phí được liệt kê")
        val others = registry.providers.values.map { p ->
            val prefix = when (p.id) { "local" -> "LOCAL_LLM"; else -> p.id.uppercase() }
            ProviderInfo(p.id, p.displayName, p.configured, p.paid, p.endpointHost, "DISABLED_UNLESS_ENABLED",
                if (p.configured) p.models.map { m -> "${p.id}:$m".let { id -> ModelInfo(id, m, pol[id] == true, p.paid, prices[id]) } } else emptyList(),
                "${prefix}_API_KEY${if (p.id == "local") " (tùy chọn)" else ""}, ${prefix}_BASE_URL, ${prefix}_MODELS (danh sách model, phân tách bằng dấu phẩy)")
        }
        return listOf(or) + others
    }

    /** Live check that lists the provider's models (spends no tokens). Not audited: it changes nothing. */
    @PostMapping("/providers/{id}/probe")
    fun probe(@PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ProbeResult {
        guard.require(me.userId)
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val detail = if (id == "openrouter") { if (!ai.externalEnabled) throw ApiException.conflict("NOT_CONFIGURED", "OpenRouter is not configured"); "${openRouter.listModels().size} models listed" }
            else { val p = registry.providers[id] ?: throw ApiException.notFound("PROVIDER_NOT_FOUND", "Unknown provider")
                if (!p.configured) throw ApiException.conflict("NOT_CONFIGURED", "${p.displayName} is not configured"); p.probe() }
            ProbeResult(id, true, ms(), detail)
        } catch (e: AiProviderException) { ProbeResult(id, false, ms(), e.message ?: "error") }
    }

    @PutMapping("/models/policy")
    @Transactional
    fun policy(@Valid @RequestBody request: PolicyRequest, @AuthenticationPrincipal me: StudioUserDetails): ModelInfo {
        guard.require(me.userId)
        val provider = known(request.modelId) ?: throw ApiException.notFound("MODEL_NOT_FOUND", "Not a model of a configured provider")
        val old = ai.policies()[request.modelId]
        jdbc.update("""INSERT INTO ai_model_policies (model_id, enabled, updated_by, updated_at) VALUES (?,?,?, now())
            ON CONFLICT (model_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by, updated_at = now()""", request.modelId, request.enabled, me.userId)
        audit.record("AI_MODEL_POLICY", "AI_MODEL", request.modelId, oldValue = mapOf("enabled" to old), newValue = mapOf("enabled" to request.enabled, "provider" to provider))
        val paid = registry.providers[provider]?.paid ?: false
        return ModelInfo(request.modelId, registry.split(request.modelId)?.second ?: request.modelId, request.enabled!!, paid, currentPrices()[request.modelId])
    }

    @GetMapping("/pricing")
    @Transactional(readOnly = true)
    fun pricing(@AuthenticationPrincipal me: StudioUserDetails): List<PriceDto> {
        guard.require(me.userId)
        return jdbc.query("$priceSelect ORDER BY p.model_id, p.effective_from DESC, p.created_at DESC LIMIT 500") { rs, _ -> priceRow(rs) }
    }

    /** Adds a price that applies from now on. Rows are never edited, so the cost of past calls stays reproducible. */
    @PostMapping("/pricing")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun addPrice(@Valid @RequestBody request: PriceRequest, @AuthenticationPrincipal me: StudioUserDetails): PriceDto {
        guard.require(me.userId)
        val provider = known(request.modelId) ?: throw ApiException.notFound("MODEL_NOT_FOUND", "Not a model of a configured provider")
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_model_pricing (id, provider, model_id, input_usd_per_mtok, output_usd_per_mtok, note, created_by) VALUES (?,?,?,?,?,?,?)",
            id, provider, request.modelId, request.inputUsdPerMTok, request.outputUsdPerMTok, request.note?.trim().orEmpty(), me.userId)
        audit.record("AI_PRICING_ADDED", "AI_MODEL", request.modelId,
            newValue = mapOf("inputUsdPerMTok" to request.inputUsdPerMTok, "outputUsdPerMTok" to request.outputUsdPerMTok, "provider" to provider))
        return jdbc.query("$priceSelect WHERE p.id = ?", { rs, _ -> priceRow(rs) }, id).first()
    }
}
