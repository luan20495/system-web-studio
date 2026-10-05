package com.systemwebstudio.logic.scheduler

import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinitionValidator
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.LogicAuditRecord
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.ResourceKind
import com.systemwebstudio.logic.action.TenantGate
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ScheduleSpec(
    val appId: UUID,
    val name: String,
    val cron: String,
    val timezone: String = "UTC",
    val target: ScheduleTarget,
    val input: JsonNode? = null,
    val enabled: Boolean = true,
    val misfirePolicy: MisfirePolicy = MisfirePolicy.SKIP
)

sealed interface ScheduleResult<out T> {
    data class Ok<T>(val value: T) : ScheduleResult<T>
    data class Failed(val code: String, val message: String, val retryable: Boolean = false) : ScheduleResult<Nothing>
}

data class TickReport(val fired: Int = 0, val skippedMisfire: Int = 0, val skippedTenant: Int = 0, val enqueueFailed: Int = 0, val retried: Int = 0)

/**
 * Schedules. **The scheduler only enqueues**: a tick claims due schedules (compare-and-set, so only one node wins) and hands a
 * [ScheduledRunRequest] to [ScheduledRunEnqueuer]; it never calls an action, a data connector or a notification itself. Everything that
 * follows — retries, timeouts, permissions at fire time, audit of the run — is the workflow runtime's job.
 */
class SchedulerService(
    private val store: ScheduleStore,
    private val enqueuer: ScheduledRunEnqueuer,
    private val access: AccessPort,
    private val tenants: TenantGate,
    private val audit: LogicAuditPort,
    private val clock: Clock = Clock.systemUTC(),
    /** A fire time older than this when the tick sees it counts as missed (misfire). */
    private val misfireGrace: Duration = Duration.ofMinutes(2),
    /** A claimed-but-unconfirmed fire is re-enqueued after this long. */
    private val pendingRetryAfter: Duration = Duration.ofSeconds(30),
    private val maxSchedulesPerApp: Int = 100,
    private val maxInputBytes: Int = 16 * 1024
) {
    private val log = System.getLogger(SchedulerService::class.java.name)

    fun create(ctx: ActionContext, spec: ScheduleSpec): ScheduleResult<Schedule> {
        deny(ctx, spec.appId)?.let { return it }
        validate(spec)?.let { return it }
        if (store.list(ctx.tenantId, spec.appId).size >= maxSchedulesPerApp) return fail("LIMIT_EXCEEDED", "At most $maxSchedulesPerApp schedules per app")
        val now = clock.instant()
        val next = if (spec.enabled) CronExpression.parse(spec.cron).next(now, ZoneId.of(spec.timezone)) else null
        val s = Schedule(
            id = UUID.randomUUID(), tenantId = ctx.tenantId, appId = spec.appId, name = spec.name.trim(), cron = spec.cron.trim(), timezone = spec.timezone,
            enabled = spec.enabled, target = spec.target, input = spec.input, createdBy = ctx.actor.userId, misfirePolicy = spec.misfirePolicy,
            nextRunAt = next, createdAt = now, updatedAt = now
        )
        store.create(s)
        record(ctx.tenantId, ctx, s, "CREATED")
        return ScheduleResult.Ok(s)
    }

    fun setEnabled(ctx: ActionContext, scheduleId: UUID, enabled: Boolean): ScheduleResult<Schedule> {
        repeat(5) {
            val cur = store.get(ctx.tenantId, scheduleId) ?: return notFound()
            deny(ctx, cur.appId)?.let { return it }
            val now = clock.instant()
            val next = if (enabled) CronExpression.parse(cur.cron).next(now, ZoneId.of(cur.timezone)) else null
            val upd = cur.copy(enabled = enabled, nextRunAt = next, pendingFireAt = if (enabled) cur.pendingFireAt else null, updatedAt = now)
            if (store.compareAndSet(cur, upd)) { record(ctx.tenantId, ctx, cur, if (enabled) "ENABLED" else "DISABLED"); return ScheduleResult.Ok(upd.copy(version = cur.version + 1)) }
        }
        return fail("CONFLICT", "Concurrent update, please retry", retryable = true)
    }

    fun delete(ctx: ActionContext, scheduleId: UUID): ScheduleResult<Unit> {
        val cur = store.get(ctx.tenantId, scheduleId) ?: return notFound()
        deny(ctx, cur.appId)?.let { return it }
        store.delete(ctx.tenantId, scheduleId)
        record(ctx.tenantId, ctx, cur, "DELETED")
        return ScheduleResult.Ok(Unit)
    }

    fun list(ctx: ActionContext, appId: UUID?): ScheduleResult<List<Schedule>> {
        deny(ctx, appId)?.let { return it }
        return ScheduleResult.Ok(store.list(ctx.tenantId, appId))
    }

    /**
     * One scheduler pass; call it every few seconds from a platform timer (C0 wiring). Safe to run on several nodes at once.
     */
    fun tick(limit: Int = 100): TickReport {
        val now = clock.instant()
        var fired = 0; var misfire = 0; var tenantSkip = 0; var failed = 0; var retried = 0

        for (s in store.pending(now.minus(pendingRetryAfter), limit)) {
            when (enqueue(s, s.pendingFireAt!!)) {
                true -> { confirm(s, "FIRED"); retried++ }
                false -> failed++
                null -> Unit
            }
        }

        for (s in store.due(now, limit)) {
            val fireAt = s.nextRunAt ?: continue
            val zone = ZoneId.of(s.timezone)
            val cron = CronExpression.parse(s.cron)
            val missed = Duration.between(fireAt, now) > misfireGrace
            val nextAfter = cron.next(if (missed) now else fireAt, zone)
            val tenantOn = try { tenants.isEnabled(s.tenantId) } catch (e: Exception) { null }
            if (tenantOn == null) continue // cannot decide: leave it due, try again next tick

            val skip = !tenantOn || (missed && s.misfirePolicy == MisfirePolicy.SKIP)
            val status = when { !tenantOn -> "SKIPPED_TENANT_DISABLED"; missed && skip -> "SKIPPED_MISFIRE"; else -> null }
            val upd = s.copy(
                nextRunAt = nextAfter, updatedAt = now,
                lastRunAt = if (skip) s.lastRunAt else fireAt, lastRunStatus = status ?: "CLAIMED",
                pendingFireAt = if (skip) null else fireAt
            )
            if (!store.compareAndSet(s, upd)) continue // another node won
            if (skip) {
                if (!tenantOn) tenantSkip++ else misfire++
                recordSystem(s, status!!)
                continue
            }
            val claimed = upd.copy(version = s.version + 1)
            when (enqueue(claimed, fireAt)) {
                true -> { confirm(claimed, "FIRED"); fired++ }
                false -> failed++ // stays pending; the next tick retries
                null -> Unit
            }
        }
        return TickReport(fired, misfire, tenantSkip, failed, retried)
    }

    /** true = confirmed, false = failed (retry later), null = nothing to do. */
    private fun enqueue(s: Schedule, fireAt: Instant): Boolean? {
        val outcome = try {
            enqueuer.enqueue(
                ScheduledRunRequest(s.tenantId, s.appId, s.id, s.createdBy, s.target, s.input, fireAt, "sched:${s.id}:${fireAt.toEpochMilli()}")
            )
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Enqueue threw ${e.javaClass.simpleName}")
            EnqueueOutcome.Failed("DEPENDENCY_UNAVAILABLE", true)
        }
        return when (outcome) {
            is EnqueueOutcome.Enqueued, is EnqueueOutcome.Duplicate -> true
            is EnqueueOutcome.Failed -> {
                recordSystem(s, "ENQUEUE_FAILED", outcome.code)
                // A permanent refusal (e.g. the owner lost the permission) must not be retried every tick: drop the pending fire, keep the schedule.
                if (!outcome.retryable) { confirm(s, "FAILED_${outcome.code}"); null } else false
            }
        }
    }

    private fun confirm(s: Schedule, status: String) {
        repeat(3) {
            val cur = store.get(s.tenantId, s.id) ?: return
            if (cur.pendingFireAt == null) return
            if (store.compareAndSet(cur, cur.copy(pendingFireAt = null, lastRunStatus = status, updatedAt = clock.instant()))) { recordSystem(cur, status); return }
        }
    }

    private fun validate(spec: ScheduleSpec): ScheduleResult.Failed? {
        if (spec.name.isBlank() || spec.name.length > 120) return fail("INVALID", "name must be 1-120 characters")
        try { CronExpression.parse(spec.cron) } catch (e: IllegalArgumentException) { return fail("INVALID_CRON", e.message ?: "invalid cron") }
        try { ZoneId.of(spec.timezone) } catch (e: Exception) { return fail("INVALID_TIMEZONE", "unknown timezone") }
        val ref = when (val t = spec.target) { is ScheduleTarget.Workflow -> t.workflowRef; is ScheduleTarget.Action -> t.actionRef }
        if (!ActionDefinitionValidator.REF_ID.matches(ref)) return fail("INVALID", "target must be a plain id")
        if (spec.input != null && spec.input.toString().length > maxInputBytes) return fail("LIMIT_EXCEEDED", "input exceeds $maxInputBytes bytes")
        return null
    }

    private fun deny(ctx: ActionContext, appId: UUID?): ScheduleResult.Failed? {
        val gate = try { tenants.isEnabled(ctx.tenantId) } catch (e: Exception) { null }
        if (gate != true) return fail("TENANT_DISABLED", "Tenant is disabled")
        val d = try { access.check(ctx, AccessRequest(LogicPermissions.WORKFLOW_MANAGE, ResourceKind.SCHEDULE, null, appId, ExecutionMode.LIVE)) } catch (e: Exception) { null }
            ?: return fail("DEPENDENCY_UNAVAILABLE", "Authorization could not be evaluated", retryable = true)
        return if (d is AuthorizationDecision.Denied) fail("FORBIDDEN", "You do not have permission to manage schedules") else null
    }

    private fun record(tenantId: UUID, ctx: ActionContext, s: Schedule, event: String) = try {
        audit.record(LogicAuditRecord(AuditDomains.SCHEDULE, event, tenantId, ctx.actor, s.appId, "schedule", s.id.toString(), attributes = mapOf("cron" to s.cron, "tz" to s.timezone), at = clock.instant()))
    } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Schedule audit failed: ${e.javaClass.simpleName}") }

    private fun recordSystem(s: Schedule, event: String, code: String? = null) = try {
        audit.record(LogicAuditRecord(AuditDomains.SCHEDULE, event, s.tenantId, null, s.appId, "schedule", s.id.toString(), errorCode = code, at = clock.instant()))
    } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Schedule audit failed: ${e.javaClass.simpleName}") }

    private fun fail(code: String, msg: String, retryable: Boolean = false) = ScheduleResult.Failed(code, msg, retryable)
    private fun notFound() = ScheduleResult.Failed("SCHEDULE_NOT_FOUND", "Schedule not found")
}
