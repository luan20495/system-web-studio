package com.systemwebstudio.ai

import com.systemwebstudio.settings.SettingsService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/** A limit and where it comes from: DEFAULT (company default), WORKSPACE or USER (set for that scope). 0 means "unlimited" for requests/tokens and "no budget granted" for paid budgets. */
data class Limit(val value: BigDecimal, val source: String) { val long get() = value.toLong() }
data class EffectiveLimits(
    val requestsPerDay: Limit, val tokensPerDay: Limit, val tokensPerMonthWorkspace: Limit,
    val paidBudgetUserMonth: Limit, val paidBudgetWorkspaceMonth: Limit
)
data class LimitOverride(val id: UUID, val scopeType: String, val scopeId: String, val requestsPerDay: Int?, val tokensPerDay: Long?, val tokensPerMonth: Long?, val paidBudgetMonth: BigDecimal?)

/** Company defaults (system settings) plus per-workspace / per-user / per-app overrides. Most specific wins: user, then workspace, then default. */
@Service
class AiLimitService(private val jdbc: JdbcTemplate, private val settings: SettingsService) {
    private val select = "SELECT id, scope_type, scope_id, requests_per_day, tokens_per_day, tokens_per_month, paid_budget_month FROM ai_limit_overrides"
    private fun row(rs: java.sql.ResultSet) = LimitOverride(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3),
        rs.getObject(4)?.let { (it as Number).toInt() }, rs.getObject(5)?.let { (it as Number).toLong() }, rs.getObject(6)?.let { (it as Number).toLong() }, rs.getBigDecimal(7))

    fun overrides(): List<LimitOverride> = jdbc.query("$select ORDER BY scope_type, updated_at DESC") { rs, _ -> row(rs) }
    fun find(scopeType: String, scopeId: UUID): LimitOverride? = jdbc.query("$select WHERE scope_type = ? AND scope_id = ?", { rs, _ -> row(rs) }, scopeType, scopeId.toString()).firstOrNull()

    fun defaults() = EffectiveLimits(
        Limit(BigDecimal(settings.long("ai.daily-requests-per-user")), "DEFAULT"), Limit(BigDecimal(settings.long("ai.daily-tokens-per-user")), "DEFAULT"),
        Limit(BigDecimal(settings.long("ai.monthly-tokens-per-workspace")), "DEFAULT"), Limit(settings.decimal("ai.paid-budget-per-user-month"), "DEFAULT"),
        Limit(settings.decimal("ai.paid-budget-per-workspace-month"), "DEFAULT"))

    fun effective(userId: UUID, workspaceId: UUID?): EffectiveLimits {
        val d = defaults(); val u = find("USER", userId); val w = workspaceId?.let { find("WORKSPACE", it) }
        fun pick(user: Number?, ws: Number?, def: Limit) = when { user != null -> Limit(BigDecimal(user.toString()), "USER"); ws != null -> Limit(BigDecimal(ws.toString()), "WORKSPACE"); else -> def }
        return EffectiveLimits(
            pick(u?.requestsPerDay, w?.requestsPerDay, d.requestsPerDay), pick(u?.tokensPerDay, w?.tokensPerDay, d.tokensPerDay),
            w?.tokensPerMonth?.let { Limit(BigDecimal(it), "WORKSPACE") } ?: d.tokensPerMonthWorkspace,
            u?.paidBudgetMonth?.let { Limit(it, "USER") } ?: d.paidBudgetUserMonth,
            w?.paidBudgetMonth?.let { Limit(it, "WORKSPACE") } ?: d.paidBudgetWorkspaceMonth)
    }

    fun paidSpentUserMonth(userId: UUID): BigDecimal = jdbc.queryForObject("SELECT coalesce(sum(cost_usd), 0) FROM ai_calls WHERE user_id = ? AND created_at >= date_trunc('month', now())", BigDecimal::class.java, userId) ?: BigDecimal.ZERO
    fun paidSpentWorkspaceMonth(workspaceId: UUID): BigDecimal = jdbc.queryForObject("SELECT coalesce(sum(cost_usd), 0) FROM ai_calls WHERE workspace_id = ? AND created_at >= date_trunc('month', now())", BigDecimal::class.java, workspaceId) ?: BigDecimal.ZERO
}
