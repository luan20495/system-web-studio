package com.systemwebstudio.admin

import com.systemwebstudio.ai.AiLimitService
import com.systemwebstudio.ai.AiUsageService
import com.systemwebstudio.ai.EffectiveLimits
import com.systemwebstudio.ai.LimitOverride
import com.systemwebstudio.ai.ModelAccessService
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiService
import com.systemwebstudio.settings.SettingCatalog
import com.systemwebstudio.settings.SettingsService
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.UUID

data class LimitDefaults(val defaultModel: String, val requestsPerUserDay: Long, val tokensPerUserDay: Long, val tokensPerWorkspaceMonth: Long,
                         val paidBudgetPerUserMonth: BigDecimal, val paidBudgetPerWorkspaceMonth: BigDecimal)
data class DefaultsRequest(val defaultModel: String? = null,
                           @field:Min(0) @field:Max(100000) val requestsPerUserDay: Long? = null,
                           @field:Min(0) @field:Max(1_000_000_000) val tokensPerUserDay: Long? = null,
                           @field:Min(0) @field:Max(10_000_000_000) val tokensPerWorkspaceMonth: Long? = null,
                           @field:DecimalMin("0") @field:DecimalMax("1000000") val paidBudgetPerUserMonth: BigDecimal? = null,
                           @field:DecimalMin("0") @field:DecimalMax("10000000") val paidBudgetPerWorkspaceMonth: BigDecimal? = null)
data class OverrideDto(val id: UUID, val scopeType: String, val scopeId: String, val scopeLabel: String?, val requestsPerDay: Int?, val tokensPerDay: Long?,
                       val tokensPerMonth: Long?, val paidBudgetMonth: BigDecimal?)
data class OverrideRequest(@field:NotBlank @field:Pattern(regexp = "USER|WORKSPACE|PROJECT") val scopeType: String, @field:NotBlank val scopeId: String,
                           @field:Min(0) @field:Max(100000) val requestsPerDay: Int? = null,
                           @field:Min(0) @field:Max(1_000_000_000) val tokensPerDay: Long? = null,
                           @field:Min(0) @field:Max(10_000_000_000) val tokensPerMonth: Long? = null,
                           @field:DecimalMin("0") @field:DecimalMax("10000000") val paidBudgetMonth: BigDecimal? = null)
/** customized: an administrator has saved at least one AI default (used by the setup checklist) */
data class LimitsView(val defaults: LimitDefaults, val overrides: List<OverrideDto>, val customized: Boolean)
data class LimitLine(val value: BigDecimal, val source: String)
data class UserAiView(
    val workspaceId: UUID?, val workspaces: List<Map<String, Any>>, val allowedModels: List<Map<String, Any?>>, val effectiveDefaultModel: String,
    val requestsToday: Long, val tokensToday: Long, val tokensThisMonth: Long, val paidSpentThisMonthUsd: BigDecimal,
    val limits: Map<String, LimitLine>, val override: OverrideDto?
)

/** Admin → AI → Hạn mức: company defaults and the exceptions per workspace / person / app, plus the effective AI picture of one person. */
@RestController
@RequestMapping("/api/v1/admin/ai/limits")
class AdminAiLimitsController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService, private val limits: AiLimitService, private val settings: SettingsService,
    private val ai: AiService, private val access: ModelAccessService, private val limiter: RateLimiter, private val usage: AiUsageService
) {
    private fun defaults() = LimitDefaults(settings.raw("ai.default-model"), settings.long("ai.daily-requests-per-user"), settings.long("ai.daily-tokens-per-user"),
        settings.long("ai.monthly-tokens-per-workspace"), settings.decimal("ai.paid-budget-per-user-month"), settings.decimal("ai.paid-budget-per-workspace-month"))

    private fun dto(o: LimitOverride, labels: Map<String, String>) = OverrideDto(o.id, o.scopeType, o.scopeId, labels["${o.scopeType}:${o.scopeId}"], o.requestsPerDay, o.tokensPerDay, o.tokensPerMonth, o.paidBudgetMonth)
    private fun labels() = jdbc.query("""SELECT 'WORKSPACE', id::text, name FROM workspaces WHERE id::text IN (SELECT scope_id FROM ai_limit_overrides WHERE scope_type = 'WORKSPACE')
        UNION ALL SELECT 'USER', id::text, coalesce(display_name, username) FROM users WHERE id::text IN (SELECT scope_id FROM ai_limit_overrides WHERE scope_type = 'USER')
        UNION ALL SELECT 'PROJECT', id::text, name FROM projects WHERE id::text IN (SELECT scope_id FROM ai_limit_overrides WHERE scope_type = 'PROJECT')""") { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}" to rs.getString(3) }.toMap()
    private fun view() = labels().let { l -> LimitsView(defaults(), limits.overrides().map { dto(it, l) }, (jdbc.queryForObject("SELECT count(*) FROM system_settings WHERE key IN ('ai.default-model','ai.daily-requests-per-user','ai.daily-tokens-per-user','ai.monthly-tokens-per-workspace','ai.paid-budget-per-user-month','ai.paid-budget-per-workspace-month')", Long::class.java) ?: 0) > 0) }

    @GetMapping
    fun get(@AuthenticationPrincipal me: StudioUserDetails): LimitsView { guard.require(me.userId); return view() }

    private fun put(key: String, value: String, me: StudioUserDetails) {
        val d = SettingCatalog.all.getValue(key); val v = settings.normalise(d, value)
        val old = settings.raw(key)
        if (old == v) return
        jdbc.update("""INSERT INTO system_settings (key, value, updated_by, updated_at) VALUES (?,?,?, now())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()""", key, v, me.userId)
        settings.invalidate()
        audit.record("SETTING_CHANGED", "SETTING", key, oldValue = mapOf("value" to old), newValue = mapOf("value" to v, "risk" to d.risk.name))
    }

    /** a default model must be "auto" or a model an administrator enabled (and that is still configured) */
    private fun requireUsableModel(id: String) {
        if (id == "auto") return
        val enabledModels = ai.catalog().map { it.id }
        if (id !in enabledModels) throw ApiException.badRequest("MODEL_NOT_ENABLED", "Mô hình mặc định phải là mô hình đang được bật. Hãy bật mô hình này trước.")
    }

    @PutMapping("/defaults")
    @Transactional
    fun setDefaults(@Valid @RequestBody r: DefaultsRequest, @AuthenticationPrincipal me: StudioUserDetails): LimitsView {
        guard.require(me.userId)
        r.defaultModel?.let { requireUsableModel(it.trim()); put("ai.default-model", it.trim(), me) }
        r.requestsPerUserDay?.let { put("ai.daily-requests-per-user", it.toString(), me) }
        r.tokensPerUserDay?.let { put("ai.daily-tokens-per-user", it.toString(), me) }
        r.tokensPerWorkspaceMonth?.let { put("ai.monthly-tokens-per-workspace", it.toString(), me) }
        r.paidBudgetPerUserMonth?.let { put("ai.paid-budget-per-user-month", it.toPlainString(), me) }
        r.paidBudgetPerWorkspaceMonth?.let { put("ai.paid-budget-per-workspace-month", it.toPlainString(), me) }
        return view()
    }

    private val allowedFields = mapOf("USER" to setOf("rpd", "tpd", "budget"), "WORKSPACE" to setOf("rpd", "tpd", "tpm", "budget"), "PROJECT" to setOf("rpd", "tpm"))

    @PutMapping("/overrides")
    @Transactional
    fun setOverride(@Valid @RequestBody r: OverrideRequest, @AuthenticationPrincipal me: StudioUserDetails): LimitsView {
        guard.require(me.userId)
        val id = runCatching { UUID.fromString(r.scopeId) }.getOrNull() ?: throw ApiException.badRequest("INVALID_SCOPE", "Đối tượng không hợp lệ")
        val table = mapOf("USER" to "users", "WORKSPACE" to "workspaces", "PROJECT" to "projects").getValue(r.scopeType)
        if (jdbc.queryForObject("SELECT count(*) FROM $table WHERE id = ?", Long::class.java, id) == 0L) throw ApiException.notFound("SCOPE_NOT_FOUND", "Không tìm thấy đối tượng")
        val given = buildSet { if (r.requestsPerDay != null) add("rpd"); if (r.tokensPerDay != null) add("tpd"); if (r.tokensPerMonth != null) add("tpm"); if (r.paidBudgetMonth != null) add("budget") }
        if (!allowedFields.getValue(r.scopeType).containsAll(given)) throw ApiException.badRequest("FIELD_NOT_ALLOWED", "Hạn mức này không áp dụng cho đối tượng đã chọn.")
        val old = limits.find(r.scopeType, id)
        if (given.isEmpty()) {
            jdbc.update("DELETE FROM ai_limit_overrides WHERE scope_type = ? AND scope_id = ?", r.scopeType, id.toString())
            if (old != null) audit.record("AI_LIMIT_OVERRIDE_REMOVED", r.scopeType, id.toString())
        } else {
            jdbc.update("""INSERT INTO ai_limit_overrides (id, scope_type, scope_id, requests_per_day, tokens_per_day, tokens_per_month, paid_budget_month, updated_by) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (scope_type, scope_id) DO UPDATE SET requests_per_day = EXCLUDED.requests_per_day, tokens_per_day = EXCLUDED.tokens_per_day,
                tokens_per_month = EXCLUDED.tokens_per_month, paid_budget_month = EXCLUDED.paid_budget_month, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                UUID.randomUUID(), r.scopeType, id.toString(), r.requestsPerDay, r.tokensPerDay, r.tokensPerMonth, r.paidBudgetMonth, me.userId)
            audit.record("AI_LIMIT_OVERRIDE_SET", r.scopeType, id.toString(),
                oldValue = old?.let { mapOf("requestsPerDay" to it.requestsPerDay, "tokensPerDay" to it.tokensPerDay, "tokensPerMonth" to it.tokensPerMonth, "paidBudgetMonth" to it.paidBudgetMonth) },
                newValue = mapOf("requestsPerDay" to r.requestsPerDay, "tokensPerDay" to r.tokensPerDay, "tokensPerMonth" to r.tokensPerMonth, "paidBudgetMonth" to r.paidBudgetMonth))
        }
        return view()
    }

    @DeleteMapping("/overrides/{id}")
    @Transactional
    fun removeOverride(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): LimitsView {
        guard.require(me.userId)
        val old = jdbc.queryForList("SELECT scope_type, scope_id FROM ai_limit_overrides WHERE id = ?", id).firstOrNull() ?: throw ApiException.notFound("OVERRIDE_NOT_FOUND", "Không tìm thấy hạn mức riêng")
        jdbc.update("DELETE FROM ai_limit_overrides WHERE id = ?", id)
        audit.record("AI_LIMIT_OVERRIDE_REMOVED", old["scope_type"] as String, old["scope_id"] as String)
        return view()
    }

    /** What AI one person can use today and what limits apply to them (Admin → Người dùng → chi tiết) */
    @GetMapping("/users/{userId}")
    fun user(@PathVariable userId: UUID, @RequestParam(required = false) workspaceId: UUID?, @AuthenticationPrincipal me: StudioUserDetails): UserAiView {
        guard.require(me.userId)
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long::class.java, userId) == 0L) throw ApiException.notFound("USER_NOT_FOUND", "Không tìm thấy người dùng")
        val ws = jdbc.query("SELECT w.id, w.name FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id WHERE m.user_id = ? AND m.active ORDER BY w.name",
            { rs, _ -> mapOf<String, Any>("id" to rs.getObject(1, UUID::class.java), "name" to rs.getString(2)) }, userId)
        val chosen = workspaceId ?: ws.firstOrNull()?.get("id") as UUID?
        val eff: EffectiveLimits = limits.effective(userId, chosen)
        val models = access.filter(userId, chosen, ai.catalog()).map { mapOf("id" to it.id, "name" to it.name, "provider" to it.provider, "paid" to it.paid) }
        val d = ai.effectiveModel(null)
        val modelName = d?.let { id -> ai.catalog().firstOrNull { it.id == id }?.name ?: id } ?: "Tự động"
        val requests = limiter.peek("ai:$userId", Long.MAX_VALUE).count
        val line = { l: com.systemwebstudio.ai.Limit -> LimitLine(l.value, l.source) }
        val o = limits.find("USER", userId)
        return UserAiView(chosen, ws, models, modelName, requests, usage.userTokensLast24h(userId),
            jdbc.queryForObject("SELECT coalesce(sum(total_tokens), 0) FROM ai_calls WHERE user_id = ? AND created_at >= date_trunc('month', now())", Long::class.java, userId) ?: 0,
            limits.paidSpentUserMonth(userId),
            mapOf("requestsPerDay" to line(eff.requestsPerDay), "tokensPerDay" to line(eff.tokensPerDay), "tokensPerMonthWorkspace" to line(eff.tokensPerMonthWorkspace),
                "paidBudgetUserMonth" to line(eff.paidBudgetUserMonth), "paidBudgetWorkspaceMonth" to line(eff.paidBudgetWorkspaceMonth)),
            o?.let { dto(it, mapOf("USER:$userId" to userId.toString())) })
    }
}
