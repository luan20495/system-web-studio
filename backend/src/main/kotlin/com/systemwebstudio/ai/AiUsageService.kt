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
    @Value("\${app.ai.daily-token-limit-per-user:0}") val dailyTokenLimitPerUser: Long,
    /** 0 = off. Calendar month (database time zone) per workspace. */
    @Value("\${app.ai.monthly-token-limit-per-workspace:0}") val monthlyTokenLimitPerWorkspace: Long
) {
    /**
     * Must be called OUTSIDE the prompt's transaction (each insert auto-commits), so tokens that were spent stay on record even
     * if the edit is rolled back afterwards.
     */
    fun record(calls: List<AiCall>, promptId: UUID?, workspaceId: UUID, projectId: UUID, userId: UUID) {
        if (calls.isEmpty()) return
        jdbc.batchUpdate(
            """INSERT INTO ai_calls (id, prompt_id, workspace_id, project_id, user_id, provider, model, outcome, http_status,
               prompt_tokens, completion_tokens, total_tokens, cost_usd, latency_ms, generation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            calls.map { c ->
                arrayOf<Any?>(UUID.randomUUID(), promptId, workspaceId, projectId, userId, c.provider.take(32), c.model.take(160), c.outcome, c.httpStatus,
                    c.usage?.promptTokens, c.usage?.completionTokens, c.usage?.totalTokens, c.usage?.costUsd,
                    c.latencyMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), c.usage?.generationId)
            }
        )
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

    /** Checked before a real-AI prompt. A single answer can overshoot a little: the size of the next answer is unknown in advance. */
    fun requireBudget(userId: UUID, workspaceId: UUID) {
        if (dailyTokenLimitPerUser > 0) {
            val used = userTokensLast24h(userId)
            if (used >= dailyTokenLimitPerUser) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_LIMIT",
                "Daily AI token allowance used up; try again later or use the simulator.", mapOf("scope" to "user", "used" to used, "limit" to dailyTokenLimitPerUser))
        }
        if (monthlyTokenLimitPerWorkspace > 0) {
            val used = workspaceTokensThisMonth(workspaceId)
            if (used >= monthlyTokenLimitPerWorkspace) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_LIMIT",
                "This workspace has used its monthly AI token budget.", mapOf("scope" to "workspace", "used" to used, "limit" to monthlyTokenLimitPerWorkspace))
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
