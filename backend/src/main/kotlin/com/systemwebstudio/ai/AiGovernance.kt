package com.systemwebstudio.ai

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiCall
import com.systemwebstudio.integration.llm.AiProviderRegistry
import com.systemwebstudio.integration.llm.AiService
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------- alerts (6.4)

data class AlertDto(val id: UUID, val kind: String, val severity: String, val scopeType: String?, val scopeId: String?, val message: String,
                    val data: Map<String, Any?>, val createdAt: Instant, val acknowledgedBy: String?, val acknowledgedAt: Instant?)

/** Admin console alerts. One row per condition (dedupe key): raising the same condition again does nothing until a new period. */
@Service
class AlertService(private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    fun raise(kind: String, severity: String, message: String, dedupeKey: String, scopeType: String? = null, scopeId: String? = null, data: Map<String, Any?> = emptyMap()): Boolean =
        jdbc.update("""INSERT INTO admin_alerts (id, kind, severity, scope_type, scope_id, message, data, dedupe_key) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)
            ON CONFLICT (dedupe_key) DO NOTHING""", UUID.randomUUID(), kind, severity, scopeType, scopeId, message.take(500), json.writeValueAsString(data), dedupeKey.take(200)) == 1

    fun list(open: Boolean, limit: Int): List<AlertDto> = jdbc.query("""SELECT a.id, a.kind, a.severity, a.scope_type, a.scope_id, a.message, a.data::text, a.created_at,
        coalesce(u.display_name, u.username), a.acknowledged_at FROM admin_alerts a LEFT JOIN users u ON u.id = a.acknowledged_by
        ${if (open) "WHERE a.acknowledged_at IS NULL" else ""} ORDER BY a.created_at DESC LIMIT ?""", { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        AlertDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            json.readValue(rs.getString(7), Map::class.java) as Map<String, Any?>, rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getTimestamp(10)?.toInstant())
    }, limit.coerceIn(1, 500))

    fun openCount(): Long = jdbc.queryForObject("SELECT count(*) FROM admin_alerts WHERE acknowledged_at IS NULL", Long::class.java) ?: 0
}

@RestController
@RequestMapping("/api/v1/admin/alerts")
class AdminAlertController(private val guard: AdminGuard, private val alerts: AlertService, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "false") all: Boolean, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        guard.require(me.userId)
        return mapOf("open" to alerts.openCount(), "items" to alerts.list(!all, 200))
    }

    @PostMapping("/{id}/acknowledge")
    @Transactional
    fun ack(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        guard.require(me.userId)
        val n = jdbc.update("UPDATE admin_alerts SET acknowledged_by = ?, acknowledged_at = now() WHERE id = ? AND acknowledged_at IS NULL", me.userId, id)
        if (n == 0) throw ApiException.notFound("ALERT_NOT_FOUND", "No open alert with this id")
        audit.record("ALERT_ACKNOWLEDGED", "ALERT", id)
        return mapOf("ok" to true)
    }
}

// ---------------------------------------------------------------- model access (6.2)

data class AccessRuleDto(val id: UUID, val scopeType: String, val scopeId: String, val scopeLabel: String?, val modelId: String, val createdAt: Instant)
data class AccessRuleRequest(@field:NotBlank @field:Pattern(regexp = "ORG|WORKSPACE|ROLE|USER") val scopeType: String,
                             @field:Size(max = 64) val scopeId: String? = null, @field:NotBlank @field:Size(max = 200) val modelId: String)
data class EffectiveModel(val id: String, val provider: String, val paid: Boolean, val allowed: Boolean, val reason: String)

/**
 * Who may use which model (ADR 0014). The global enable flag (Admin → AI) is the org gate; rules below it can only DENY, at org,
 * workspace, the user's workspace role or the user. Most restrictive wins: one matching deny anywhere blocks the model.
 */
@Service
class ModelAccessService(private val jdbc: JdbcTemplate, private val ai: AiService, private val registry: AiProviderRegistry) {
    fun paid(modelId: String) = registry.split(modelId)?.first?.paid ?: false

    /** deny rules that apply to this user in this workspace, with the level that set them */
    fun denials(userId: UUID, workspaceId: UUID?): List<Pair<String, String>> {
        val role = workspaceId?.let { jdbc.query("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND active", { rs, _ -> rs.getString(1) }, it, userId).firstOrNull() }
        val scopes = buildList {
            add("ORG" to "org"); add("USER" to userId.toString())
            if (workspaceId != null) add("WORKSPACE" to workspaceId.toString())
            if (workspaceId != null && role != null) add("ROLE" to "$workspaceId:$role")
        }
        return jdbc.query("SELECT scope_type, scope_id, model_id FROM ai_model_access", { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) })
            .filter { (t, s, _) -> (t to s) in scopes }.map { it.third to it.first }
    }

    private fun matches(rule: String, modelId: String) = rule == modelId || rule == "*" || (rule == "paid:*" && paid(modelId))

    /** null = allowed; otherwise the level that denies it */
    fun deniedBy(modelId: String, denials: List<Pair<String, String>>): String? = denials.firstOrNull { matches(it.first, modelId) }?.second

    /** Throws unless the user may use this explicit model ("auto"/"mock"/null are checked through [autoExclusions]). */
    fun require(userId: UUID, workspaceId: UUID, model: String?) {
        if (model == null || model == "mock") return
        val d = denials(userId, workspaceId)
        if (model == "auto") { if (d.any { it.first == "*" }) throw ApiException.forbidden("AI models are not enabled for you", "MODEL_ACCESS_DENIED"); return }
        deniedBy(model, d)?.let { throw ApiException.forbidden("This model is restricted for you (by ${it.lowercase()} policy)", "MODEL_ACCESS_DENIED") }
    }

    /** free models "auto" must skip for this user */
    fun autoExclusions(userId: UUID, workspaceId: UUID): Set<String> {
        val d = denials(userId, workspaceId); if (d.isEmpty()) return emptySet()
        return if (ai.externalEnabled) ai.freeModels().map { it.id }.filter { deniedBy(it, d) != null }.toSet() else emptySet()
    }

    /** every model of the catalog with the decision for this user (Admin → Model Access) */
    fun effective(userId: UUID, workspaceId: UUID?): List<EffectiveModel> {
        val pol = ai.policies(); val d = denials(userId, workspaceId)
        val free = if (ai.externalEnabled) ai.freeModels().map { Triple(it.id, "openrouter", pol[it.id] != false) } else emptyList()
        val others = ai.providerModels(pol).map { (m, enabled) -> Triple(m.id, m.provider, enabled) }
        return (free + others).map { (id, provider, enabled) ->
            val by = deniedBy(id, d)
            EffectiveModel(id, provider, paid(id), enabled && by == null, when { !enabled -> "Tắt ở mức hệ thống (AI Control)"; by != null -> "Bị chặn ở mức ${by.lowercase()}"; else -> "Được phép" })
        }
    }

    /** the catalog the user may pick from */
    fun filter(userId: UUID, workspaceId: UUID?, models: List<com.systemwebstudio.integration.llm.AiModel>) =
        denials(userId, workspaceId).let { d -> models.filter { deniedBy(it.id, d) == null } }
}

@RestController
@RequestMapping("/api/v1/admin/ai/access")
class AdminModelAccessController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService, private val access: ModelAccessService) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<AccessRuleDto> {
        guard.require(me.userId)
        return jdbc.query("""SELECT r.id, r.scope_type, r.scope_id, r.model_id, r.created_at,
              CASE r.scope_type WHEN 'WORKSPACE' THEN (SELECT name FROM workspaces WHERE id::text = r.scope_id)
                WHEN 'USER' THEN (SELECT coalesce(display_name, username) FROM users WHERE id::text = r.scope_id)
                WHEN 'ROLE' THEN (SELECT name FROM workspaces WHERE id::text = split_part(r.scope_id, ':', 1)) || ' · ' || split_part(r.scope_id, ':', 2)
                ELSE 'Toàn tổ chức' END
            FROM ai_model_access r ORDER BY r.scope_type, r.created_at""") { rs, _ ->
            AccessRuleDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(6), rs.getString(4), rs.getTimestamp(5).toInstant())
        }
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@Valid @RequestBody r: AccessRuleRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        guard.require(me.userId)
        val scopeId = when (r.scopeType) {
            "ORG" -> "org"
            "WORKSPACE", "USER" -> runCatching { UUID.fromString(r.scopeId) }.getOrNull()?.toString() ?: throw ApiException.badRequest("INVALID_SCOPE", "scopeId must be a UUID")
            else -> r.scopeId?.takeIf { Regex("^[0-9a-f-]{36}:(WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER)$").matches(it) } ?: throw ApiException.badRequest("INVALID_SCOPE", "scopeId must be <workspaceId>:<WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER>")
        }
        val exists = when (r.scopeType) {
            "WORKSPACE" -> jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ?::uuid", Long::class.java, scopeId)!! > 0
            "USER" -> jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?::uuid", Long::class.java, scopeId)!! > 0
            "ROLE" -> jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ?::uuid", Long::class.java, scopeId.substringBefore(':'))!! > 0
            else -> true
        }
        if (!exists) throw ApiException.notFound("SCOPE_NOT_FOUND", "Scope not found")
        if (!Regex("^(\\*|paid:\\*|[A-Za-z0-9._/:@-]{1,200})$").matches(r.modelId)) throw ApiException.badRequest("INVALID_MODEL", "Model id, '*' or 'paid:*'")
        val id = UUID.randomUUID()
        val n = jdbc.update("INSERT INTO ai_model_access (id, scope_type, scope_id, model_id, created_by) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING", id, r.scopeType, scopeId, r.modelId, me.userId)
        if (n == 0) throw ApiException.conflict("RULE_EXISTS", "This rule already exists")
        audit.record("AI_MODEL_ACCESS_DENY_ADDED", "AI_MODEL", r.modelId, newValue = mapOf("scopeType" to r.scopeType, "scopeId" to scopeId))
        return mapOf("id" to id)
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        guard.require(me.userId)
        val old = jdbc.queryForList("SELECT scope_type, scope_id, model_id FROM ai_model_access WHERE id = ?", id).firstOrNull() ?: throw ApiException.notFound("RULE_NOT_FOUND", "Rule not found")
        jdbc.update("DELETE FROM ai_model_access WHERE id = ?", id)
        audit.record("AI_MODEL_ACCESS_DENY_REMOVED", "AI_MODEL", old["model_id"], oldValue = old)
    }

    @GetMapping("/effective")
    fun effective(@RequestParam userId: UUID, @RequestParam(required = false) workspaceId: UUID?, @AuthenticationPrincipal me: StudioUserDetails): List<EffectiveModel> {
        guard.require(me.userId)
        return access.effective(userId, workspaceId)
    }
}

// ---------------------------------------------------------------- money budgets (6.3)

data class BudgetDto(val id: UUID, val scopeType: String, val scopeId: String, val scopeLabel: String?, val period: String, val amount: BigDecimal, val currency: String,
                     val usdPerUnit: BigDecimal, val softPercent: Int, val hard: Boolean,
                     /** spent in the budget currency (known costs only) */
                     val spent: BigDecimal, val spentUsd: BigDecimal, val unknownCostCalls: Long, val percent: Int, val periodStart: Instant)
data class BudgetRequest(@field:NotBlank @field:Pattern(regexp = "ORG|WORKSPACE|USER|PROJECT") val scopeType: String, @field:Size(max = 64) val scopeId: String? = null,
                         @field:NotBlank @field:Pattern(regexp = "DAILY|MONTHLY") val period: String,
                         @field:NotNull @field:DecimalMin("0.0001") @field:DecimalMax("100000000") val amount: BigDecimal?,
                         @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String? = "USD",
                         @field:DecimalMin("0.0000000001") val usdPerUnit: BigDecimal? = null,
                         @field:Min(1) @field:Max(100) val softPercent: Int? = 80, val hard: Boolean? = true)

/**
 * Money budgets (ADR 0014) on what calls cost: the provider-reported cost or reported tokens × the admin's price row. Calls without a known cost
 * are counted, never priced by guess. A hard budget blocks PAID models once spent ≥ amount (free models cost nothing and stay usable); a paid
 * model without a price row cannot be budget-checked, so it is refused while a hard budget applies.
 */
@Service
class AiBudgetService(private val jdbc: JdbcTemplate, private val alerts: AlertService, private val access: ModelAccessService) {
    private data class Row(val id: UUID, val scopeType: String, val scopeId: String, val period: String, val amount: BigDecimal, val currency: String,
                           val usdPerUnit: BigDecimal, val soft: Int, val hard: Boolean)

    private fun rows(where: String = "", vararg args: Any?) = jdbc.query("SELECT id, scope_type, scope_id, period, amount, currency, usd_per_unit, soft_percent, hard FROM ai_budgets $where",
        { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getBigDecimal(7), rs.getInt(8), rs.getBoolean(9)) }, *args)

    private fun applicable(userId: UUID, workspaceId: UUID, projectId: UUID?) =
        rows().filter { r -> when (r.scopeType) { "ORG" -> true; "WORKSPACE" -> r.scopeId == workspaceId.toString(); "USER" -> r.scopeId == userId.toString(); else -> r.scopeId == projectId?.toString() } }

    private fun column(scope: String) = when (scope) { "WORKSPACE" -> "workspace_id"; "USER" -> "user_id"; "PROJECT" -> "project_id"; else -> null }

    /** (spent USD, calls with unknown cost, period start) */
    private fun spend(r: Row): Triple<BigDecimal, Long, Instant> {
        val trunc = if (r.period == "DAILY") "day" else "month"
        val col = column(r.scopeType)
        val where = (if (col != null) "$col = ?::uuid AND " else "") + "created_at >= date_trunc('$trunc', now())"
        val args = if (col != null) arrayOf<Any?>(r.scopeId) else emptyArray()
        return jdbc.queryForObject("""SELECT coalesce(sum(cost_usd), 0), count(*) FILTER (WHERE cost_usd IS NULL AND total_tokens IS NOT NULL AND provider NOT IN ('openrouter', 'local')),
            date_trunc('$trunc', now()) FROM ai_calls WHERE $where""", { rs, _ -> Triple(rs.getBigDecimal(1), rs.getLong(2), rs.getTimestamp(3).toInstant()) }, *args)!!
    }

    private fun limitUsd(r: Row) = r.amount.multiply(r.usdPerUnit)

    private fun hasPrice(model: String) = jdbc.queryForObject("SELECT count(*) FROM ai_model_pricing WHERE model_id = ? AND effective_from <= now()", Long::class.java, model)!! > 0

    /** Before a call. Free models ("auto", OpenRouter :free, the simulator) are never blocked by money budgets. */
    fun require(userId: UUID, workspaceId: UUID, projectId: UUID?, model: String?) {
        if (model == null || model == "auto" || model == "mock" || !access.paid(model)) return
        val list = applicable(userId, workspaceId, projectId); if (list.isEmpty()) return
        if (list.any { it.hard } && !hasPrice(model)) throw ApiException.conflict("AI_COST_UNKNOWN",
            "This paid model has no price in the AI pricing catalog, so the spending budget cannot be checked. Ask an administrator to add its price.")
        for (r in list) {
            val (spent, _, start) = spend(r)
            if (spent >= limitUsd(r)) {
                alerts.raise("AI_BUDGET_EXCEEDED", "CRITICAL", "AI budget ${r.period.lowercase()} ${r.scopeType.lowercase()} reached (${r.amount} ${r.currency})",
                    "budget:${r.id}:$start:hard", r.scopeType, r.scopeId, mapOf("budgetId" to r.id, "spentUsd" to spent))
                if (r.hard) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUDGET_EXCEEDED", "The ${r.period.lowercase()} AI spending budget (${r.scopeType.lowercase()}) is used up. Free models remain available.",
                    mapOf("scope" to r.scopeType, "period" to r.period, "amount" to r.amount, "currency" to r.currency))
            }
        }
    }

    /** After calls were recorded: raise soft / hard threshold alerts for budgets that crossed them. */
    fun afterSpend(userId: UUID, workspaceId: UUID, projectId: UUID?) {
        for (r in applicable(userId, workspaceId, projectId)) {
            val (spent, _, start) = spend(r); val limit = limitUsd(r)
            val pct = if (limit.signum() == 0) 0 else spent.multiply(BigDecimal(100)).divide(limit, 0, RoundingMode.DOWN).toInt()
            if (pct >= 100) alerts.raise("AI_BUDGET_EXCEEDED", "CRITICAL", "AI budget ${r.period.lowercase()} ${r.scopeType.lowercase()} reached ($pct%, ${r.amount} ${r.currency})",
                "budget:${r.id}:$start:hard", r.scopeType, r.scopeId, mapOf("budgetId" to r.id, "spentUsd" to spent, "percent" to pct))
            else if (pct >= r.soft) alerts.raise("AI_BUDGET_SOFT", "WARNING", "AI budget ${r.period.lowercase()} ${r.scopeType.lowercase()} at $pct% of ${r.amount} ${r.currency}",
                "budget:${r.id}:$start:soft", r.scopeType, r.scopeId, mapOf("budgetId" to r.id, "spentUsd" to spent, "percent" to pct))
        }
    }

    fun list(): List<BudgetDto> {
        val labels = jdbc.query("""SELECT 'WORKSPACE', id::text, name FROM workspaces UNION ALL SELECT 'USER', id::text, coalesce(display_name, username) FROM users
            UNION ALL SELECT 'PROJECT', id::text, name FROM projects WHERE id::text IN (SELECT scope_id FROM ai_budgets WHERE scope_type = 'PROJECT')""") { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}" to rs.getString(3) }.toMap()
        return rows("ORDER BY scope_type, period").map { r ->
            val (spentUsd, unknown, start) = spend(r); val limit = limitUsd(r)
            BudgetDto(r.id, r.scopeType, r.scopeId, if (r.scopeType == "ORG") "Toàn tổ chức" else labels["${r.scopeType}:${r.scopeId}"], r.period, r.amount, r.currency, r.usdPerUnit, r.soft, r.hard,
                spentUsd.divide(r.usdPerUnit, 4, RoundingMode.HALF_UP), spentUsd, unknown,
                if (limit.signum() == 0) 0 else spentUsd.multiply(BigDecimal(100)).divide(limit, 0, RoundingMode.DOWN).toInt(), start)
        }
    }
}

@RestController
@RequestMapping("/api/v1/admin/ai/budgets")
class AdminBudgetController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService, private val budgets: AiBudgetService) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<BudgetDto> { guard.require(me.userId); return budgets.list() }

    @PutMapping
    @Transactional
    fun upsert(@Valid @RequestBody r: BudgetRequest, @AuthenticationPrincipal me: StudioUserDetails): List<BudgetDto> {
        guard.require(me.userId)
        val currency = r.currency ?: "USD"
        val rate = if (currency == "USD") BigDecimal.ONE else r.usdPerUnit
            ?: throw ApiException.badRequest("EXCHANGE_RATE_REQUIRED", "A non-USD budget needs usdPerUnit (USD per 1 $currency), entered by you; no rate is assumed")
        val scopeId = if (r.scopeType == "ORG") "org" else runCatching { UUID.fromString(r.scopeId) }.getOrNull()?.toString()
            ?: throw ApiException.badRequest("INVALID_SCOPE", "scopeId must be a UUID")
        val table = mapOf("WORKSPACE" to "workspaces", "USER" to "users", "PROJECT" to "projects")[r.scopeType]
        if (table != null && jdbc.queryForObject("SELECT count(*) FROM $table WHERE id = ?::uuid", Long::class.java, scopeId)!! == 0L) throw ApiException.notFound("SCOPE_NOT_FOUND", "Scope not found")
        val old = jdbc.queryForList("SELECT amount, currency, usd_per_unit, soft_percent, hard FROM ai_budgets WHERE scope_type = ? AND scope_id = ? AND period = ?", r.scopeType, scopeId, r.period).firstOrNull()
        jdbc.update("""INSERT INTO ai_budgets (id, scope_type, scope_id, period, amount, currency, usd_per_unit, soft_percent, hard, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (scope_type, scope_id, period) DO UPDATE SET amount = EXCLUDED.amount, currency = EXCLUDED.currency, usd_per_unit = EXCLUDED.usd_per_unit,
            soft_percent = EXCLUDED.soft_percent, hard = EXCLUDED.hard, updated_at = now()""",
            UUID.randomUUID(), r.scopeType, scopeId, r.period, r.amount, currency, rate, r.softPercent ?: 80, r.hard ?: true, me.userId)
        audit.record("AI_BUDGET_SET", "AI_BUDGET", "${r.scopeType}:$scopeId:${r.period}", oldValue = old,
            newValue = mapOf("amount" to r.amount, "currency" to currency, "usdPerUnit" to rate, "softPercent" to (r.softPercent ?: 80), "hard" to (r.hard ?: true)))
        return budgets.list()
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        guard.require(me.userId)
        val old = jdbc.queryForList("SELECT scope_type, scope_id, period, amount, currency FROM ai_budgets WHERE id = ?", id).firstOrNull() ?: throw ApiException.notFound("BUDGET_NOT_FOUND", "Budget not found")
        jdbc.update("DELETE FROM ai_budgets WHERE id = ?", id)
        audit.record("AI_BUDGET_REMOVED", "AI_BUDGET", id, oldValue = old)
    }
}

// ---------------------------------------------------------------- one gate for every AI entry point

/**
 * Everything checked before a prompt reaches a model, in one place: model enabled globally, access rules, request quota, token budgets,
 * money budgets. Returns the free models "auto" must skip for this user. After the calls, [after] records them and raises alerts.
 */
@Service
class AiGate(private val ai: AiService, private val access: ModelAccessService, private val usage: AiUsageService, private val budgets: AiBudgetService,
             private val alerts: AlertService, private val limiter: RateLimiter) {
    data class Decision(val external: Boolean, val exclude: Set<String>)

    fun authorize(ctx: AccessContext, model: String?, projectId: UUID?): Decision {
        ai.requireAllowed(model)
        val external = ai.isExternal(model)
        if (!external) return Decision(false, emptySet())
        access.require(ctx.userId, ctx.workspaceId, model ?: "auto")
        usage.requireBudget(ctx.userId, ctx.workspaceId)
        budgets.require(ctx.userId, ctx.workspaceId, projectId, model)
        // free-tier quotas are shared by everyone using the key, so each user gets a daily allowance of real-AI prompts
        limiter.require("ai:${ctx.userId}", ai.dailyLimitPerUser, 86_400, "ai-daily")
        return Decision(true, if (model == null || model == "auto") access.autoExclusions(ctx.userId, ctx.workspaceId) else emptySet())
    }

    /** prices and records the calls (outside any transaction), raises budget and provider alerts; returns the priced calls */
    fun after(calls: List<AiCall>, promptId: UUID?, ctx: AccessContext, projectId: UUID): List<AiCall> {
        if (calls.isEmpty()) return calls
        val priced = usage.price(calls)
        usage.record(priced, promptId, ctx.workspaceId, projectId, ctx.userId)
        budgets.afterSpend(ctx.userId, ctx.workspaceId, projectId)
        priced.filter { it.httpStatus in setOf(401, 402, 403) }.forEach { c ->
            alerts.raise("AI_PROVIDER_FAILURE", "CRITICAL", "AI provider ${c.provider} refused requests (HTTP ${c.httpStatus}: key rejected or no credit)",
                "provider:${c.provider}:${c.httpStatus}:${java.time.LocalDate.now()}", "PROVIDER", c.provider, mapOf("model" to c.model))
        }
        return priced
    }
}
