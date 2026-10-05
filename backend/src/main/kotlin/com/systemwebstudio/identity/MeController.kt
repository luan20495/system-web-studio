package com.systemwebstudio.identity

import com.systemwebstudio.admin.AUDIT_SELECT
import com.systemwebstudio.admin.AuditRow
import com.systemwebstudio.admin.auditRow
import com.systemwebstudio.ai.AiUsageService
import com.systemwebstudio.ai.UsageTotals
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.integration.llm.AiService
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class MyUsage(val aiConfigured: Boolean, val aiRequestsUsed: Long, val aiRequestsLimit: Long, val aiWindowResetsInSeconds: Long?,
                   val promptsPerMinute: Long, val promptsToday: Long,
                   /** provider-reported tokens/cost of my real-AI calls; the limits are null when not configured */
                   val tokensLast24h: Long = 0, val tokensLimitPerDay: Long? = null, val usageLast30Days: UsageTotals? = null)

/** Self-service views for the signed-in user (Builder Studio). */
@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val limiter: RateLimiter, private val ai: AiService, private val jdbc: JdbcTemplate, private val aiUsage: AiUsageService,
    private val limits: com.systemwebstudio.ai.AiLimitService,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    /** Real quota: the same Redis counter that enforces the daily AI allowance (a rolling 24 h window from the first AI request). */
    @GetMapping("/usage")
    fun usage(@AuthenticationPrincipal me: StudioUserDetails): MyUsage {
        val lim = limits.effective(me.userId, null)
        val perDay = lim.requestsPerDay.long            // 0 = unlimited
        val d = limiter.peek("ai:${me.userId}", if (perDay > 0) perDay else Long.MAX_VALUE)
        return MyUsage(ai.externalEnabled, d.count, perDay, if (d.count > 0) d.retryAfterSeconds else null, promptMax,
            jdbc.queryForObject("SELECT count(*) FROM prompts WHERE created_by = ? AND created_at >= date_trunc('day', now())", Long::class.java, me.userId) ?: 0,
            aiUsage.userTokensLast24h(me.userId), lim.tokensPerDay.long.takeIf { it > 0 },
            aiUsage.totals("user_id = ? AND created_at >= now() - interval '30 days'", me.userId))
    }

    @GetMapping("/activity")
    fun activity(@RequestParam(defaultValue = "30") limit: Int, @AuthenticationPrincipal me: StudioUserDetails): List<AuditRow> =
        jdbc.query("$AUDIT_SELECT WHERE a.actor_id = ? ORDER BY a.created_at DESC LIMIT ?", { rs, _ -> auditRow(rs) }, me.userId, limit.coerceIn(1, 100))
}
