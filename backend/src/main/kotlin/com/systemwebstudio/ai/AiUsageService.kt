package com.systemwebstudio.ai

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.llm.AiCall
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

/**
 * Totals over ai_calls. Tokens/cost are only what the provider reported: `callsWithoutUsage` says how many calls had no usage
 * (HTTP errors, timeouts) and `costReportedCalls` how many carried a cost, so a total is never presented as more complete than it is.
 */
data class UsageTotals(
    val calls: Long, val failedCalls: Long, val callsWithoutUsage: Long, val promptTokens: Long, val completionTokens: Long,
    val totalTokens: Long, val costUsd: BigDecimal?, val costReportedCalls: Long, val avgLatencyMs: Long?
)

/** Per-prompt summary returned with a prompt answer; null for the simulator (no upstream call, nothing to count). */
data class PromptUsage(val attempts: Int, val promptTokens: Long?, val completionTokens: Long?, val totalTokens: Long?, val costUsd: BigDecimal?, val latencyMs: Long)

@Service
class AiUsageService(
    private val jdbc: JdbcTemplate,
    /** 0 = off. Rolling 24 h per user, counted from reported tokens. */
    private val settings: com.systemwebstudio.settings.SettingsService
) {
    val dailyTokenLimitPerUser: Long get() = settings.long("ai.daily-tokens-per-user")
    val monthlyTokenLimitPerWorkspace: Long get() = settings.long("ai.monthly-tokens-per-workspace")
    /**
     * Must be called OUTSIDE the prompt's transaction (each insert auto-commits), so tokens that were spent stay on record even
     * if the edit is rolled back afterwards.
     */
    fun record(calls: List<AiCall>, promptId: UUID?, workspaceId: UUID, projectId: UUID, userId: UUID) {
        if (calls.isEmpty()) return
        val requestId = com.systemwebstudio.common.RequestIdFilter.current()?.take(64)
        jdbc.batchUpdate(
            """INSERT INTO ai_calls (id, prompt_id, workspace_id, project_id, user_id, provider, model, outcome, http_status,
               prompt_tokens, completion_tokens, total_tokens, cost_usd, latency_ms, generation_id, cost_source, pricing_id, request_id)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            calls.map { c ->
                arrayOf<Any?>(UUID.randomUUID(), promptId, workspaceId, projectId, userId, c.provider.take(32), c.model.take(160), c.outcome, c.httpStatus,
                    c.usage?.promptTokens, c.usage?.completionTokens, c.usage?.totalTokens, c.usage?.costUsd,
                    c.latencyMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), c.usage?.generationId, c.costSource, c.pricingId, requestId)
            }
        )
    }

    /**
     * Cost per call, reproducibly: the provider-reported cost if there is one (OpenRouter); otherwise reported tokens × the pricing-catalog
     * row in force now (immutable rows, so the same inputs always give the same cost). No row or no token counts → cost stays unknown.
     */
    fun price(calls: List<AiCall>): List<AiCall> = calls.map { c ->
        val u = c.usage ?: return@map c
        if (u.costUsd != null) return@map c.copy(costSource = "PROVIDER")
        val input = u.promptTokens ?: return@map c; val output = u.completionTokens ?: return@map c
        val row = jdbc.query("""SELECT id, input_usd_per_mtok, output_usd_per_mtok FROM ai_model_pricing WHERE model_id = ? AND effective_from <= now()
            ORDER BY effective_from DESC, created_at DESC LIMIT 1""", { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getBigDecimal(2), rs.getBigDecimal(3)) }, c.model).firstOrNull()
            ?: return@map c
        val cost = (row.second * BigDecimal(input) + row.third * BigDecimal(output)).divide(BigDecimal(1_000_000), 10, java.math.RoundingMode.HALF_UP)
        c.copy(usage = u.copy(costUsd = cost), costSource = "CATALOG", pricingId = row.first)
    }

    fun summarize(calls: List<AiCall>): PromptUsage? {
        if (calls.isEmpty()) return null
        val reported = calls.mapNotNull { it.usage }
        fun sum(f: (com.systemwebstudio.integration.llm.ChatUsage) -> Int?) = reported.mapNotNull(f).takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }
        return PromptUsage(calls.size, sum { it.promptTokens }, sum { it.completionTokens }, sum { it.totalTokens },
            reported.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add), calls.sumOf { it.latencyMs })
    }

    fun userTokensLast24h(userId: UUID): Long =
        jdbc.queryForObject("SELECT coalesce(sum(total_tokens), 0) FROM ai_calls WHERE user_id = ? AND created_at > now() - interval '24 hours'", Long::class.java, userId) ?: 0

    fun workspaceTokensThisMonth(workspaceId: UUID): Long =
        jdbc.queryForObject("SELECT coalesce(sum(total_tokens), 0) FROM ai_calls WHERE workspace_id = ? AND created_at >= date_trunc('month', now())", Long::class.java, workspaceId) ?: 0

    /** Checked before a real-AI prompt. A single answer can overshoot a little: the size of the next answer is unknown in advance. 0 = unlimited. */
    fun requireBudget(userId: UUID, workspaceId: UUID, limits: EffectiveLimits, projectId: UUID? = null, appTokensPerMonth: Long? = null) {
        val daily = limits.tokensPerDay.long
        if (daily > 0) {
            val used = userTokensLast24h(userId)
            if (used >= daily) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_LIMIT",
                "Bạn đã dùng hết lượng AI (token) cho hôm nay. Hãy thử lại vào ngày mai hoặc nhờ quản trị viên tăng hạn mức.", mapOf("scope" to "user", "used" to used, "limit" to daily))
        }
        val monthly = limits.tokensPerMonthWorkspace.long
        if (monthly > 0) {
            val used = workspaceTokensThisMonth(workspaceId)
            if (used >= monthly) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_LIMIT",
                "Không gian làm việc này đã dùng hết lượng AI (token) của tháng. Hãy nhờ quản trị viên tăng hạn mức.", mapOf("scope" to "workspace", "used" to used, "limit" to monthly))
        }
        if (projectId != null && appTokensPerMonth != null && appTokensPerMonth > 0) {
            val used = jdbc.queryForObject("SELECT coalesce(sum(total_tokens), 0) FROM ai_calls WHERE project_id = ? AND created_at >= date_trunc('month', now())", Long::class.java, projectId) ?: 0
            if (used >= appTokensPerMonth) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_LIMIT",
                "Website/ứng dụng này đã dùng hết lượng AI (token) của tháng. Hãy nhờ quản trị viên tăng hạn mức.", mapOf("scope" to "project", "used" to used, "limit" to appTokensPerMonth))
        }
    }

    /** `where` is a fixed SQL fragment from callers in this codebase (never user text); values go through `args`. */
    fun totals(where: String, vararg args: Any?): UsageTotals = jdbc.queryForObject(
        """SELECT count(*), count(*) FILTER (WHERE outcome <> 'OK'), count(*) FILTER (WHERE total_tokens IS NULL),
           coalesce(sum(prompt_tokens), 0), coalesce(sum(completion_tokens), 0), coalesce(sum(total_tokens), 0), sum(cost_usd),
           count(cost_usd), avg(latency_ms)::bigint FROM ai_calls WHERE $where""", { rs, _ -> totalsRow(rs, 1) }, *args)!!

    companion object {
        fun totalsRow(rs: ResultSet, from: Int) = UsageTotals(
            rs.getLong(from), rs.getLong(from + 1), rs.getLong(from + 2), rs.getLong(from + 3), rs.getLong(from + 4), rs.getLong(from + 5),
            rs.getBigDecimal(from + 6), rs.getLong(from + 7), rs.getObject(from + 8)?.let { (it as Number).toLong() })
    }
}
