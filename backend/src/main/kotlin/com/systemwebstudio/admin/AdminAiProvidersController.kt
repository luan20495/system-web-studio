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
import jakarta.validation.constraints.Pattern
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
data class ModelInfo(val id: String, val name: String, val enabled: Boolean, val paid: Boolean, val price: PriceDto?, val default: Boolean = false)
data class ProviderInfo(val id: String, val name: String, val configured: Boolean, val paid: Boolean, val endpointHost: String?,
                        val defaultPolicy: String, val models: List<ModelInfo>, val configHint: String,
                        /** Loại: OPENROUTER, OPENAI, ANTHROPIC, GEMINI, OPENAI_COMPATIBLE, LOCAL */
                        val kind: String = "", val enabled: Boolean = true,
                        /** settings come from the operator's configuration: shown as "Được quản lý bởi hệ thống", not editable on the web */
                        val managedBySystem: Boolean = false, val keySet: Boolean = false, val baseUrl: String? = null,
                        val defaultModel: String? = null, val savedModels: List<String> = emptyList())
data class ProbeResult(val id: String, val ok: Boolean, val latencyMs: Long, val detail: String)
data class DiscoverResult(val id: String, val ok: Boolean, val models: List<String>, val detail: String)
data class ProviderRequest(
    @field:Size(max = 80) val name: String? = null,
    @field:Pattern(regexp = "OPENROUTER|OPENAI|ANTHROPIC|GEMINI|OPENAI_COMPATIBLE|LOCAL") val kind: String? = null,
    @field:Size(max = 300) val baseUrl: String? = null,
    /** write-only; blank or missing keeps the current key */
    @field:Size(max = 600) val apiKey: String? = null,
    @field:Size(max = 100) val models: List<String>? = null,
    @field:Size(max = 100) val defaultModel: String? = null,
    val paid: Boolean? = null, val enabled: Boolean? = null, val removeKey: Boolean? = null
)
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
    private val ai: AiService, private val registry: AiProviderRegistry, private val openRouter: OpenRouterClient,
    private val store: com.systemwebstudio.integration.llm.ProviderStore, private val crypto: com.systemwebstudio.runtime.SecretsCrypto,
    private val settings: com.systemwebstudio.settings.SettingsService
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

    private fun defaultModelId() = settings.raw("ai.default-model")

    @GetMapping("/providers")
    @Transactional(readOnly = true)
    fun providers(@AuthenticationPrincipal me: StudioUserDetails): List<ProviderInfo> { guard.require(me.userId); return providersInternal() }

    // ---- write side (system admins): providers added on the web, keys encrypted and write-only

    private val slugBases = mapOf("OPENROUTER" to "openrouter", "OPENAI" to "openai", "ANTHROPIC" to "anthropic", "GEMINI" to "gemini", "OPENAI_COMPATIBLE" to "openai-compatible", "LOCAL" to "local")
    private val bareKinds = setOf("OPENAI_COMPATIBLE", "LOCAL")

    private fun cleanUrl(kind: String, raw: String?): String {
        if (kind !in bareKinds) return ""                                   // the fixed, well-known address of the provider is used
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) throw ApiException.badRequest("ADDRESS_REQUIRED", "Hãy nhập địa chỉ dịch vụ (ví dụ http://localhost:11434/v1).")
        val uri = runCatching { java.net.URI(v) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || uri.scheme !in setOf("http", "https") || uri.userInfo != null || v.any { it.isWhitespace() })
            throw ApiException.badRequest("ADDRESS_INVALID", "Địa chỉ dịch vụ không hợp lệ. Hãy nhập dạng https://máy-chủ/v1 hoặc http://localhost:11434/v1.")
        return v.trimEnd('/')
    }

    private fun cleanKey(raw: String?): String? {
        val k = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (k.length < 8 || k.any { it.isWhitespace() || it.isISOControl() }) throw ApiException.badRequest("KEY_INVALID", "Khóa kết nối không hợp lệ (không chứa khoảng trắng, tối thiểu 8 ký tự).")
        if (!crypto.available) throw ApiException.conflict("ENCRYPTION_UNAVAILABLE", "Hệ thống chưa sẵn sàng để lưu khóa kết nối an toàn. Vui lòng liên hệ người vận hành hệ thống.")
        return crypto.encrypt(k)
    }

    private fun cleanModels(list: List<String>?): List<String> {
        val m = list.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        m.firstOrNull { !AiProviderRegistry.MODEL.matches(it) }?.let { throw ApiException.badRequest("MODEL_INVALID", "Mã mô hình không hợp lệ: $it") }
        return m
    }

    private fun slugFor(kind: String): String {
        if (kind == "OPENROUTER") {
            if (openRouter.managedByEnvironment) throw ApiException.conflict("MANAGED_BY_SYSTEM", "OpenRouter đang được quản lý bởi hệ thống.")
            if (store.all().any { it.slug == "openrouter" }) throw ApiException.conflict("PROVIDER_EXISTS", "OpenRouter đã được thêm. Hãy sửa nhà cung cấp hiện có.")
            return "openrouter"
        }
        val base = slugBases.getValue(kind); val taken = store.all().map { it.slug }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken && !(registry.managedBySystem(it)) }
    }

    private fun view(slug: String) = providersInternal().firstOrNull { it.id == slug } ?: throw ApiException.notFound("PROVIDER_NOT_FOUND", "Không tìm thấy nhà cung cấp")
    private fun providersInternal(): List<ProviderInfo> {
        val pol = ai.policies(); val prices = currentPrices(); val def = defaultModelId(); val stored = store.all().associateBy { it.slug }
        val out = mutableListOf<ProviderInfo>()
        if (openRouter.configured || stored["openrouter"] != null) {
            val s = stored["openrouter"]
            out += ProviderInfo("openrouter", s?.name?.takeIf { !openRouter.managedByEnvironment } ?: "OpenRouter", openRouter.configured, false, "https://openrouter.ai", "ENABLED_UNLESS_DISABLED",
                if (ai.externalEnabled) ai.freeModels().map { ModelInfo(it.id, it.name, pol[it.id] != false, false, prices[it.id], def == it.id) } else emptyList(), "", "OPENROUTER", s?.enabled ?: true,
                openRouter.managedByEnvironment, openRouter.configured, null, null, emptyList())
        }
        for (p in registry.providers.values) {
            val s = stored[p.id]; val managed = registry.managedBySystem(p.id)
            if (!managed && s == null) continue
            val models = if (p.configured) p.models.map { m -> "${p.id}:$m".let { id -> ModelInfo(id, m, pol[id] == true, p.isPaid(m), prices[id], def == id) } } else emptyList()
            out += ProviderInfo(p.id, p.displayName, p.configured, p.paid, p.endpointHost, "DISABLED_UNLESS_ENABLED", models, "", s?.kind ?: when (p.id) { "local" -> "LOCAL"; else -> p.id.uppercase() },
                s?.enabled ?: true, managed, s?.keySet ?: managed, s?.baseUrl, s?.defaultModel, s?.models ?: p.models)
        }
        return out
    }

    @PostMapping("/providers")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: ProviderRequest, @AuthenticationPrincipal me: StudioUserDetails): ProviderInfo {
        guard.require(me.userId)
        val kind = r.kind ?: throw ApiException.badRequest("KIND_REQUIRED", "Hãy chọn loại nhà cung cấp.")
        val name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiException.badRequest("NAME_REQUIRED", "Hãy nhập tên nhà cung cấp.")
        val base = cleanUrl(kind, r.baseUrl); val key = cleanKey(r.apiKey)
        if (key == null && kind !in setOf("LOCAL", "OPENAI_COMPATIBLE")) throw ApiException.badRequest("KEY_REQUIRED", "Hãy nhập khóa kết nối của nhà cung cấp.")
        val models = if (kind == "OPENROUTER") emptyList() else cleanModels(r.models)
        val def = r.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
        if (def != null && def !in models) throw ApiException.badRequest("DEFAULT_MODEL_INVALID", "Mô hình mặc định phải nằm trong danh sách mô hình.")
        val slug = slugFor(kind)
        jdbc.update("""INSERT INTO ai_providers (id, slug, kind, name, base_url, api_key_enc, models, default_model, paid, enabled, created_by) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?)""",
            UUID.randomUUID(), slug, kind, name, base, key, jsonList(models), def, r.paid ?: (kind !in setOf("LOCAL", "OPENROUTER")), r.enabled ?: true, me.userId)
        registry.invalidate()
        audit.record("AI_PROVIDER_ADDED", "AI_PROVIDER", slug, newValue = mapOf("name" to name, "kind" to kind, "baseUrl" to base, "models" to models, "keySet" to (key != null)))
        return view(slug)
    }

    @PutMapping("/providers/{id}")
    @Transactional
    fun update(@PathVariable id: String, @Valid @RequestBody r: ProviderRequest, @AuthenticationPrincipal me: StudioUserDetails): ProviderInfo {
        guard.require(me.userId)
        val s = store.bySlug(id) ?: if (registry.managedBySystem(id) || (id == "openrouter" && openRouter.managedByEnvironment))
            throw ApiException.conflict("MANAGED_BY_SYSTEM", "Nhà cung cấp này được quản lý bởi hệ thống, không sửa được trên web.") else throw ApiException.notFound("PROVIDER_NOT_FOUND", "Không tìm thấy nhà cung cấp")
        if (registry.managedBySystem(id) || (id == "openrouter" && openRouter.managedByEnvironment)) throw ApiException.conflict("MANAGED_BY_SYSTEM", "Nhà cung cấp này được quản lý bởi hệ thống, không sửa được trên web.")
        val name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: s.name
        val base = if (r.baseUrl != null) cleanUrl(s.kind, r.baseUrl) else s.baseUrl
        val newKey = cleanKey(r.apiKey)
        val models = if (r.models != null && s.kind != "OPENROUTER") cleanModels(r.models) else s.models
        val def = if (r.defaultModel != null) r.defaultModel.trim().takeIf { it.isNotEmpty() } else s.defaultModel?.takeIf { it in models }
        if (def != null && def !in models) throw ApiException.badRequest("DEFAULT_MODEL_INVALID", "Mô hình mặc định phải nằm trong danh sách mô hình.")
        val enabled = r.enabled ?: s.enabled; val paid = r.paid ?: s.paid
        if (r.removeKey == true && s.kind !in setOf("LOCAL", "OPENAI_COMPATIBLE")) throw ApiException.badRequest("KEY_REQUIRED", "Nhà cung cấp này cần khóa kết nối; hãy thay khóa thay vì xóa.")
        jdbc.update("""UPDATE ai_providers SET name = ?, base_url = ?, models = CAST(? AS jsonb), default_model = ?, paid = ?, enabled = ?, updated_at = now(),
            api_key_enc = CASE WHEN ? THEN NULL WHEN ?::text IS NOT NULL THEN ?::text ELSE api_key_enc END WHERE slug = ?""",
            name, base, jsonList(models), def, paid, enabled, r.removeKey == true, newKey, newKey, id)
        registry.invalidate()
        audit.record("AI_PROVIDER_UPDATED", "AI_PROVIDER", id, oldValue = mapOf("name" to s.name, "enabled" to s.enabled, "models" to s.models),
            newValue = mapOf("name" to name, "enabled" to enabled, "baseUrl" to base, "models" to models, "keyChanged" to (newKey != null), "keyRemoved" to (r.removeKey == true)))
        return view(id)
    }

    @DeleteMapping("/providers/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails) {
        guard.require(me.userId)
        val s = store.bySlug(id) ?: throw ApiException.notFound("PROVIDER_NOT_FOUND", "Không tìm thấy nhà cung cấp")
        jdbc.update("DELETE FROM ai_providers WHERE slug = ?", id)
        registry.invalidate()
        audit.record("AI_PROVIDER_REMOVED", "AI_PROVIDER", id, oldValue = mapOf("name" to s.name, "kind" to s.kind))
    }

    private fun jsonList(l: List<String>) = "[" + l.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "]"

    /** plain-language reason for a provider failure; never the provider's own message (it can echo request content) */
    private fun friendly(e: AiProviderException): String = when {
        e.status in setOf(401, 403) -> "Khóa kết nối bị từ chối. Hãy kiểm tra lại khóa."
        e.status == 402 -> "Tài khoản nhà cung cấp đã hết số dư."
        e.status == 404 -> "Không tìm thấy dịch vụ tại địa chỉ này. Hãy kiểm tra lại địa chỉ dịch vụ."
        e.status == 429 -> "Nhà cung cấp đang giới hạn tốc độ. Hãy thử lại sau ít phút."
        e.message?.contains("timeout") == true -> "Nhà cung cấp không phản hồi kịp (quá thời gian chờ)."
        e.message?.contains("network error") == true || e.message?.contains("unavailable") == true -> "Không kết nối được tới địa chỉ dịch vụ. Hãy kiểm tra địa chỉ và kết nối mạng."
        e.status > 0 -> "Nhà cung cấp trả về lỗi (mã HTTP ${e.status})."
        else -> "Không kết nối được với nhà cung cấp."
    }

    /** Live check that spends no tokens (it only asks for the model list / validates the key). Not audited: it changes nothing. */
    @PostMapping("/providers/{id}/probe")
    fun probe(@PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ProbeResult {
        guard.require(me.userId)
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val detail = if (id == "openrouter") {
                if (!openRouter.configured) throw ApiException.conflict("NOT_CONFIGURED", "Chưa có khóa kết nối cho OpenRouter.")
                openRouter.checkKey(); "Kết nối thành công. Khóa hợp lệ, tìm thấy ${ai.freeModels().size} mô hình miễn phí."
            } else {
                val p = registry.providers[id] ?: throw ApiException.notFound("PROVIDER_NOT_FOUND", "Không tìm thấy nhà cung cấp")
                if (!p.connectable) throw ApiException.conflict("NOT_CONFIGURED", "Hãy nhập đủ địa chỉ dịch vụ và khóa kết nối trước khi kiểm tra.")
                val n = p.listModels().size
                if (n > 0) "Kết nối thành công. Nhà cung cấp có $n mô hình." else "Kết nối thành công." + (if (p.models.isEmpty()) " Hãy nhập mã mô hình thủ công nếu nhà cung cấp không liệt kê được." else "")
            }
            ProbeResult(id, true, ms(), detail)
        } catch (e: AiProviderException) { ProbeResult(id, false, ms(), friendly(e)) }
    }

    /** Models the provider reports, for the admin to pick from. Nothing is saved or enabled by this call. */
    @PostMapping("/providers/{id}/discover")
    fun discover(@PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): DiscoverResult {
        guard.require(me.userId)
        return try {
            if (id == "openrouter") {
                if (!openRouter.configured) throw ApiException.conflict("NOT_CONFIGURED", "Chưa có khóa kết nối cho OpenRouter.")
                val list = ai.freeModels().map { it.id }
                DiscoverResult(id, true, list, "Tìm thấy ${list.size} mô hình miễn phí.")
            } else {
                val p = registry.providers[id] ?: throw ApiException.notFound("PROVIDER_NOT_FOUND", "Không tìm thấy nhà cung cấp")
                if (!p.connectable) throw ApiException.conflict("NOT_CONFIGURED", "Hãy nhập đủ địa chỉ dịch vụ và khóa kết nối trước.")
                val list = p.listModels()
                DiscoverResult(id, true, list, if (list.isEmpty()) "Nhà cung cấp không liệt kê được mô hình. Hãy nhập mã mô hình thủ công." else "Tìm thấy ${list.size} mô hình.")
            }
        } catch (e: AiProviderException) { DiscoverResult(id, false, emptyList(), friendly(e)) }
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
