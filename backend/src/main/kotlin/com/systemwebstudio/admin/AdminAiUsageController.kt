package com.systemwebstudio.admin

import com.systemwebstudio.ai.AiUsageService
import com.systemwebstudio.ai.UsageTotals
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.AiService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UsageBucket(val key: String, val label: String?, val totals: UsageTotals)
data class DailyUsage(val day: LocalDate, val calls: Long, val failedCalls: Long, val totalTokens: Long, val costUsd: BigDecimal?)
data class AiLimits(val dailyRequestsPerUser: Long, val dailyTokensPerUser: Long?, val monthlyTokensPerWorkspace: Long?)
data class AiUsageReport(
    val days: Int, val since: Instant, val totals: UsageTotals, val byModel: List<UsageBucket>, val byUser: List<UsageBucket>,
    val byWorkspace: List<UsageBucket>, val daily: List<DailyUsage>, val limits: AiLimits,
    /** where the numbers come from, so the UI can say it */
    val tokenSource: String, val costSource: String
)
data class AiCallRow(
    val id: UUID, val createdAt: Instant, val userId: UUID, val user: String?, val workspaceId: UUID, val workspace: String?,
    val projectId: UUID, val project: String?, val promptId: UUID?, val provider: String, val model: String, val outcome: String,
    val httpStatus: Int?, val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?, val costUsd: BigDecimal?, val latencyMs: Int,
    /** PROVIDER (reported) or CATALOG (pricing row); null = unknown */
    val costSource: String? = null, val requestId: String? = null
)

/** AI usage accounting for the Admin Console. Everything is read from ai_calls (provider-reported usage), nothing is estimated. */
@RestController
@RequestMapping("/api/v1/admin/ai")
class AdminAiUsageController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val usage: AiUsageService, private val ai: AiService
) {
    private val totalsCols = """count(c.id), count(c.id) FILTER (WHERE c.outcome <> 'OK'), count(c.id) FILTER (WHERE c.total_tokens IS NULL),
        coalesce(sum(c.prompt_tokens), 0), coalesce(sum(c.completion_tokens), 0), coalesce(sum(c.total_tokens), 0), sum(c.cost_usd),
        count(c.cost_usd), avg(c.latency_ms)::bigint"""
    private val since = "date_trunc('day', now()) - make_interval(days => ?)"

    @GetMapping("/usage")
    @Transactional(readOnly = true)
    fun usage(@RequestParam(defaultValue = "30") days: Int, @AuthenticationPrincipal me: StudioUserDetails): AiUsageReport {
        guard.require(me.userId)
        val d = days.coerceIn(1, 366)
        val back = d - 1                                      // "1 day" = today, "30 days" = today and the 29 before it
        fun buckets(sql: String) = jdbc.query(sql, { rs, _ -> UsageBucket(rs.getString(1), rs.getString(2), AiUsageService.totalsRow(rs, 3)) }, back)
        return AiUsageReport(
            days = d,
            since = jdbc.queryForObject("SELECT $since", java.sql.Timestamp::class.java, back)!!.toInstant(),
            totals = usage.totals("created_at >= $since", back),
            byModel = buckets("SELECT c.model, NULL, $totalsCols FROM ai_calls c WHERE c.created_at >= $since GROUP BY c.model ORDER BY 8 DESC, 3 DESC LIMIT 50"),
            byUser = buckets("""SELECT c.user_id::text, max(coalesce(u.display_name, u.username)), $totalsCols FROM ai_calls c LEFT JOIN users u ON u.id = c.user_id
                WHERE c.created_at >= $since GROUP BY c.user_id ORDER BY 8 DESC, 3 DESC LIMIT 20"""),
            byWorkspace = buckets("""SELECT c.workspace_id::text, max(w.name), $totalsCols FROM ai_calls c LEFT JOIN workspaces w ON w.id = c.workspace_id
                WHERE c.created_at >= $since GROUP BY c.workspace_id ORDER BY 8 DESC, 3 DESC LIMIT 20"""),
            daily = jdbc.query("""SELECT g.day::date, count(c.id), count(c.id) FILTER (WHERE c.outcome <> 'OK'), coalesce(sum(c.total_tokens), 0), sum(c.cost_usd)
                FROM generate_series($since, date_trunc('day', now()), interval '1 day') AS g(day)
                LEFT JOIN ai_calls c ON c.created_at >= g.day AND c.created_at < g.day + interval '1 day' GROUP BY g.day ORDER BY g.day""",
                { rs, _ -> DailyUsage(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBigDecimal(5)) }, back),
            limits = AiLimits(ai.dailyLimitPerUser, usage.dailyTokenLimitPerUser.takeIf { it > 0 }, usage.monthlyTokenLimitPerWorkspace.takeIf { it > 0 }),
            tokenSource = "PROVIDER_REPORTED", costSource = "PROVIDER_REPORTED"
        )
    }

    @GetMapping("/calls")
    @Transactional(readOnly = true)
    fun calls(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int,
              @RequestParam(required = false) outcome: String?, @RequestParam(required = false) model: String?,
              @RequestParam(required = false) userId: UUID?, @RequestParam(required = false) workspaceId: UUID?,
              @AuthenticationPrincipal me: StudioUserDetails): PageDto<AiCallRow> {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val where = StringBuilder(" WHERE TRUE"); val args = mutableListOf<Any>()
        outcome?.trim()?.uppercase()?.takeIf { it in setOf("OK", "BAD_OUTPUT", "ERROR") }?.let { where.append(" AND c.outcome = ?"); args += it }
        model?.trim()?.takeIf { it.isNotEmpty() }?.let { where.append(" AND c.model = ?"); args += it }
        userId?.let { where.append(" AND c.user_id = ?"); args += it }
        workspaceId?.let { where.append(" AND c.workspace_id = ?"); args += it }
        val total = jdbc.queryForObject("SELECT count(*) FROM ai_calls c$where", Long::class.java, *args.toTypedArray()) ?: 0
        val items = jdbc.query("""SELECT c.id, c.created_at, c.user_id, coalesce(u.display_name, u.username), c.workspace_id, w.name, c.project_id, pr.name,
                c.prompt_id, c.provider, c.model, c.outcome, c.http_status, c.prompt_tokens, c.completion_tokens, c.total_tokens, c.cost_usd, c.latency_ms, c.cost_source, c.request_id
            FROM ai_calls c LEFT JOIN users u ON u.id = c.user_id LEFT JOIN workspaces w ON w.id = c.workspace_id LEFT JOIN projects pr ON pr.id = c.project_id
            $where ORDER BY c.created_at DESC, c.id LIMIT $s OFFSET ${p.toLong() * s}""", { rs, _ ->
            fun int(i: Int) = rs.getObject(i)?.let { (it as Number).toInt() }
            AiCallRow(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID::class.java), rs.getString(4),
                rs.getObject(5, UUID::class.java), rs.getString(6), rs.getObject(7, UUID::class.java), rs.getString(8), rs.getObject(9, UUID::class.java),
                rs.getString(10), rs.getString(11), rs.getString(12), int(13), int(14), int(15), int(16), rs.getBigDecimal(17), rs.getInt(18), rs.getString(19), rs.getString(20))
        }, *args.toTypedArray())
        return PageDto(items, total, p, s)
    }
}
