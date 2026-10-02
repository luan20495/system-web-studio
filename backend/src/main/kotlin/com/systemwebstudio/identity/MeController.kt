package com.systemwebstudio.identity

import com.systemwebstudio.admin.AUDIT_SELECT
import com.systemwebstudio.admin.AuditRow
import com.systemwebstudio.admin.auditRow
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
                   val promptsPerMinute: Long, val promptsToday: Long)

/** Self-service views for the signed-in user (Builder Studio). */
@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val limiter: RateLimiter, private val ai: AiService, private val jdbc: JdbcTemplate,
    @Value("\${app.rate-limit.prompt-max:30}") private val promptMax: Long
) {
    /** Real quota: the same Redis counter that enforces the daily AI allowance (a rolling 24 h window from the first AI request). */
    @GetMapping("/usage")
    fun usage(@AuthenticationPrincipal me: StudioUserDetails): MyUsage {
        val d = limiter.peek("ai:${me.userId}", ai.dailyLimitPerUser)
        return MyUsage(ai.externalEnabled, d.count, ai.dailyLimitPerUser, if (d.count > 0) d.retryAfterSeconds else null, promptMax,
            jdbc.queryForObject("SELECT count(*) FROM prompts WHERE created_by = ? AND created_at >= date_trunc('day', now())", Long::class.java, me.userId) ?: 0)
    }

    @GetMapping("/activity")
    fun activity(@RequestParam(defaultValue = "30") limit: Int, @AuthenticationPrincipal me: StudioUserDetails): List<AuditRow> =
        jdbc.query("$AUDIT_SELECT WHERE a.actor_id = ? ORDER BY a.created_at DESC LIMIT ?", { rs, _ -> auditRow(rs) }, me.userId, limit.coerceIn(1, 100))
}
