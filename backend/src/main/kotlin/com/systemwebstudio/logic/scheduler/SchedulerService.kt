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
import com.systemwebstudio.logic.limits.InMemoryTenantRateLimiter
import com.systemwebstudio.logic.limits.RateDecision
import com.systemwebstudio.logic.limits.RateScope
import com.systemwebstudio.logic.limits.TenantRateLimiter
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

data class TickReport(
    val fired: Int = 0, val skippedMisfire: Int = 0, val skippedTenant: Int = 0, val enqueueFailed: Int = 0, val retried: Int = 0, val rateLimited: Int = 0,
    /** Fires that were already in the execution ledger (restart, rewound schedule row, another node got there first) and were therefore not handed over again. */
    val deduplicated: Int = 0,
    /** Pending fires given up after [SchedulerService]'s attempt budget. */
    val gaveUp: Int = 0
)

/**
 * A schedule an app definition declares for one of its workflows. The scheduler owns this type so it does not depend on `logic.workflow`;
 * the wiring maps `workflow.canonical.DeclaredSchedule(workflowId, cron, timezone)` onto it one to one.
 */
data class DeclaredScheduleSpec(val workflowId: String, val cron: String, val timezone: String = "UTC")

/** Result of [SchedulerService.syncDeclared]: what happened to the schedules an app definition declares. */
data class SyncReport(val created: Int, val updated: Int, val unchanged: Int, val disabled: Int)

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
    /** A claimed-but-unconfirmed fire is re-enqueued after this long; every further failure doubles it (cap [maxPendingBackoff]). */
    private val pendingRetryAfter: Duration = Duration.ofSeconds(30),
    private val maxPendingBackoff: Duration = Duration.ofMinutes(15),
    /** After this many failed enqueues of one fire time the fire is given up (ledger FAILED); the schedule itself carries on with its next fire time. */
    private val maxEnqueueAttempts: Int = 8,
    private val maxSchedulesPerApp: Int = 100,
    private val maxInputBytes: Int = 16 * 1024,
    /** Per-tenant limit on fires handed to the workflow runtime ([RateScope.SCHEDULER_ENQUEUE]). */
    private val limiter: TenantRateLimiter = InMemoryTenantRateLimiter(clock = clock),
    /**
     * Cap on due schedules of one tenant per tick. Selection is round-robin across tenants in any case (every tenant with due schedules gets an
     * equal share of the batch, a lone tenant gets all of it); the cap additionally bounds a single tenant's share when that is wanted.
     */
    private val maxPerTenantPerTick: Int = Int.MAX_VALUE
) {
    private val log = System.getLogger(SchedulerService::class.java.name)

    init { require(maxEnqueueAttempts >= 1) { "maxEnqueueAttempts must be at least 1" } }

    fun create(ctx: ActionContext, spec: ScheduleSpec): ScheduleResult<Schedule> {
        deny(ctx, spec.appId)?.let { return it }
        return createChecked(ctx, spec, null)
    }

    private fun createChecked(ctx: ActionContext, spec: ScheduleSpec, declaredKey: String?): ScheduleResult<Schedule> {
        validate(spec)?.let { return it }
        if (store.list(ctx.tenantId, spec.appId).size >= maxSchedulesPerApp) return fail("LIMIT_EXCEEDED", "At most $maxSchedulesPerApp schedules per app")
        val now = clock.instant()
        val next = if (spec.enabled) CronExpression.parse(spec.cron).next(now, ZoneId.of(spec.timezone)) else null
        val s = Schedule(
            id = UUID.randomUUID(), tenantId = ctx.tenantId, appId = spec.appId, name = spec.name.trim(), cron = spec.cron.trim(), timezone = spec.timezone,
            enabled = spec.enabled, target = spec.target, input = spec.input, createdBy = ctx.actor.userId, misfirePolicy = spec.misfirePolicy,
            nextRunAt = next, declaredKey = declaredKey, createdAt = now, updatedAt = now
        )
        try { store.create(s) } catch (e: IllegalArgumentException) { return fail("CONFLICT", "The schedule already exists", retryable = true) }
        record(ctx.tenantId, ctx, s, "CREATED")
        return ScheduleResult.Ok(s)
    }

    fun setEnabled(ctx: ActionContext, scheduleId: UUID, enabled: Boolean): ScheduleResult<Schedule> {
        repeat(5) {
            val cur = store.get(ctx.tenantId, scheduleId) ?: return notFound()
            deny(ctx, cur.appId)?.let { return it }
            val now = clock.instant()
            // Never earlier than the last fire: a clock that jumped back must not make an already-fired time due again.
            val next = if (enabled) CronExpression.parse(cur.cron).next(laterOf(now, cur.lastRunAt), ZoneId.of(cur.timezone)) else null
            val upd = cur.copy(enabled = enabled, nextRunAt = next, pendingFireAt = if (enabled) cur.pendingFireAt else null, pendingNotBefore = if (enabled) cur.pendingNotBefore else null, updatedAt = now)
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
     * Makes the schedules of [appId] match what the app definition declares ([declared], from the canonical workflow catalog), **idempotently**:
     * publishing the same definition again changes nothing (no second schedule, `nextRunAt` untouched); a changed cron/timezone updates the
     * existing schedule; a schedule that is no longer declared is disabled (history is kept). Identity is `(tenant, app, workflow:<id>)`, enforced
     * by the store's unique key, so two publishers racing cannot create two schedules either.
     */
    fun syncDeclared(ctx: ActionContext, appId: UUID, declared: List<DeclaredScheduleSpec>): ScheduleResult<SyncReport> {
        deny(ctx, appId)?.let { return it }
        var created = 0; var updated = 0; var unchanged = 0; var disabled = 0
        val keys = linkedSetOf<String>()
        for (d in declared.distinctBy { it.workflowId }) {
            val key = "workflow:${d.workflowId}"; keys += key
            var cur = store.findDeclared(ctx.tenantId, appId, key)
            if (cur == null) {
                val spec = ScheduleSpec(appId, "workflow ${d.workflowId}".take(120), d.cron, d.timezone, ScheduleTarget.Workflow(d.workflowId))
                when (val r = createChecked(ctx, spec, key)) {
                    is ScheduleResult.Ok -> { created++; continue }
                    is ScheduleResult.Failed -> { if (r.code != "CONFLICT") return r; cur = store.findDeclared(ctx.tenantId, appId, key) ?: return r }
                }
            }
            if (cur.cron == d.cron.trim() && cur.timezone == d.timezone && cur.enabled && cur.target == ScheduleTarget.Workflow(d.workflowId)) { unchanged++; continue }
            validate(ScheduleSpec(appId, cur.name, d.cron, d.timezone, ScheduleTarget.Workflow(d.workflowId)))?.let { return it }
            val now = clock.instant()
            val upd = cur.copy(cron = d.cron.trim(), timezone = d.timezone, enabled = true, target = ScheduleTarget.Workflow(d.workflowId), updatedAt = now,
                nextRunAt = CronExpression.parse(d.cron).next(laterOf(now, cur.lastRunAt), ZoneId.of(d.timezone)))
            if (store.compareAndSet(cur, upd)) { record(ctx.tenantId, ctx, upd, "UPDATED"); updated++ } else return fail("CONFLICT", "Concurrent update, please retry", retryable = true)
        }
        for (s in store.list(ctx.tenantId, appId)) {
            val k = s.declaredKey ?: continue
            if (k in keys || !s.enabled) continue
            val now = clock.instant()
            if (store.compareAndSet(s, s.copy(enabled = false, nextRunAt = null, pendingFireAt = null, pendingNotBefore = null, updatedAt = now))) { record(ctx.tenantId, ctx, s, "DISABLED_UNDECLARED"); disabled++ }
        }
        return ScheduleResult.Ok(SyncReport(created, updated, unchanged, disabled))
    }

    /** Execution history of a schedule (newest first): who fired when, and what the runtime answered. */
    fun executions(ctx: ActionContext, scheduleId: UUID, limit: Int = 50): ScheduleResult<List<ScheduleExecution>> {
        val cur = store.get(ctx.tenantId, scheduleId) ?: return notFound()
        deny(ctx, cur.appId)?.let { return it }
        return ScheduleResult.Ok(store.executions(ctx.tenantId, scheduleId, limit.coerceIn(1, 200)))
    }

    /**
     * One scheduler pass; call it every few seconds from a platform timer (C0 wiring). Safe to run on several nodes at once, to be restarted at any
     * moment and to run on skewed clocks. Three independent guards make a fire time reach the workflow runtime at most once:
     *  1. the compare-and-set on the schedule row (only one node claims a due fire time),
     *  2. the **execution ledger**, an atomic insert-if-absent on `(tenant, schedule, fireAt)` made before the enqueue: a fire time that is already
     *     there is not handed over again, even if the schedule row was rewound (restore, cron edit, clock jump back),
     *  3. the idempotency key `sched:<id>:<fireAt>` on the enqueue itself (the runtime returns the same run).
     * A schedule never fires a time that is not after its last fire ([Schedule.lastRunAt]).
     */
    fun tick(limit: Int = 100): TickReport {
        val now = clock.instant()
        var fired = 0; var misfire = 0; var tenantSkip = 0; var failed = 0; var retried = 0; var limited = 0; var deduped = 0; var gaveUp = 0

        for (s in store.pending(now, limit, maxPerTenantPerTick)) {
            val fireAt = s.pendingFireAt ?: continue
            if (store.execution(s.tenantId, s.id, fireAt)?.status == ExecutionStatus.ENQUEUED) { confirm(s, "FIRED"); deduped++; continue } // confirmation was lost, the hand-over was not
            when (enqueue(s, fireAt)) {
                true -> { confirm(s, "FIRED"); retried++ }
                false -> { failed++; if (backOffOrGiveUp(s, fireAt, now)) gaveUp++ }
                null -> Unit
            }
        }

        for (s in store.due(now, limit, maxPerTenantPerTick)) {
            val fireAt = s.nextRunAt ?: continue
            val zone = ZoneId.of(s.timezone)
            val cron = CronExpression.parse(s.cron)

            // Monotonic guard: a fire time that is not after the last fire has already been handled (clock went backwards, row rewound). Skip it.
            if (s.lastRunAt != null && !fireAt.isAfter(s.lastRunAt)) {
                val upd = s.copy(nextRunAt = cron.next(laterOf(now, s.lastRunAt), zone), updatedAt = now, lastRunStatus = "SKIPPED_DUPLICATE")
                if (store.compareAndSet(s, upd)) { deduped++; recordSystem(s, "SKIPPED_DUPLICATE") }
                continue
            }

            val missed = Duration.between(fireAt, now) > misfireGrace
            val nextAfter = cron.next(laterOf(if (missed) now else fireAt, s.lastRunAt), zone)
            val tenantOn = try { tenants.isEnabled(s.tenantId) } catch (e: Exception) { null }
            if (tenantOn == null) continue // cannot decide: leave it due, try again next tick

            val skip = !tenantOn || (missed && s.misfirePolicy == MisfirePolicy.SKIP)
            val status = when { !tenantOn -> "SKIPPED_TENANT_DISABLED"; missed && skip -> "SKIPPED_MISFIRE"; else -> null }
            if (!skip) {
                // Over the tenant's budget: leave the schedule due (nothing is claimed or lost) and try again on a later tick.
                when (try { limiter.tryAcquire(s.tenantId, RateScope.SCHEDULER_ENQUEUE) } catch (e: Exception) { null }) {
                    is RateDecision.Allowed -> Unit
                    is RateDecision.Limited -> { limited++; continue }
                    null -> continue
                }
            }
            val upd = s.copy(
                nextRunAt = nextAfter, updatedAt = now,
                lastRunAt = if (skip) s.lastRunAt else fireAt, lastRunStatus = status ?: "CLAIMED",
                pendingFireAt = if (skip) null else fireAt, pendingAttempts = 0, pendingNotBefore = if (skip) null else now.plus(pendingRetryAfter)
            )
            if (!store.compareAndSet(s, upd)) continue // another node won

            // The ledger: one row per (schedule, fire time), created before anything is handed over.
            val ledgerRow = ScheduleExecution(s.tenantId, s.id, s.appId, fireAt, ScheduleExecution.dedupeKey(s.id, fireAt), if (skip) ExecutionStatus.SKIPPED else ExecutionStatus.CLAIMED, 0, null, status, now, now)
            val claim = try { store.claimExecution(ledgerRow) } catch (e: Exception) {
                // The ledger is unavailable: do not hand over without it. The fire stays pending and is retried (with backoff) once it is back.
                log.log(System.Logger.Level.WARNING, "Execution ledger unavailable: ${e.javaClass.simpleName}")
                failed++; if (!skip) backOffOrGiveUp(upd.copy(version = s.version + 1), fireAt, now); continue
            }
            if (skip) {
                if (!tenantOn) tenantSkip++ else misfire++
                recordSystem(s, status!!)
                continue
            }
            val claimed = upd.copy(version = s.version + 1)
            if (claim is ClaimResult.Existing && claim.execution.status.final) {
                // This fire time was already handled: do not hand it over a second time.
                confirm(claimed, if (claim.execution.status == ExecutionStatus.ENQUEUED) "FIRED" else "FIRED_ALREADY")
                deduped++
                continue
            }
            when (enqueue(claimed, fireAt)) {
                true -> { confirm(claimed, "FIRED"); fired++ }
                false -> { failed++; if (backOffOrGiveUp(claimed, fireAt, now)) gaveUp++ } // stays pending; a later tick retries after the backoff
                null -> Unit
            }
        }
        return TickReport(fired, misfire, tenantSkip, failed, retried, limited, deduped, gaveUp)
    }

    private fun laterOf(a: Instant, b: Instant?): Instant = if (b != null && b.isAfter(a)) b else a

    /**
     * A transient enqueue failure: count it, push the retry out (exponential from [pendingRetryAfter], capped at [maxPendingBackoff]) so a failing
     * target neither hogs the ticks nor hammers the runtime, and after [maxEnqueueAttempts] give this fire time up (the schedule keeps its next one).
     * @return true when the fire was given up.
     */
    private fun backOffOrGiveUp(s: Schedule, fireAt: Instant, now: Instant): Boolean {
        repeat(3) {
            val cur = store.get(s.tenantId, s.id) ?: return false
            if (cur.pendingFireAt != fireAt) return false
            val attempts = cur.pendingAttempts + 1
            if (attempts >= maxEnqueueAttempts) {
                if (store.compareAndSet(cur, cur.copy(pendingFireAt = null, pendingNotBefore = null, pendingAttempts = 0, lastRunStatus = "FAILED_ENQUEUE_EXHAUSTED", updatedAt = now))) {
                    try { store.updateExecution(cur.tenantId, cur.id, fireAt, ExecutionStatus.FAILED, attempts, null, "ENQUEUE_EXHAUSTED", now) } catch (e: Exception) { /* the row stays CLAIMED; harmless */ }
                    recordSystem(cur, "ENQUEUE_GAVE_UP", "ENQUEUE_EXHAUSTED")
                    return true
                }
            } else {
                var millis = pendingRetryAfter.toMillis().coerceAtLeast(1_000)
                repeat((attempts - 1).coerceIn(0, 30)) { millis = minOf(millis * 2, maxPendingBackoff.toMillis()) }
                if (store.compareAndSet(cur, cur.copy(pendingAttempts = attempts, pendingNotBefore = now.plusMillis(minOf(millis, maxPendingBackoff.toMillis())), updatedAt = now))) {
                    try { store.updateExecution(cur.tenantId, cur.id, fireAt, ExecutionStatus.CLAIMED, attempts, null, null, now) } catch (e: Exception) { /* best effort */ }
                    return false
                }
            }
        }
        return false
    }

    /** true = confirmed, false = failed (retry later), null = nothing to do. Records the answer in the execution ledger. */
    private fun enqueue(s: Schedule, fireAt: Instant): Boolean? {
        val key = ScheduleExecution.dedupeKey(s.id, fireAt)
        val outcome = try {
            enqueuer.enqueue(ScheduledRunRequest(s.tenantId, s.appId, s.id, s.createdBy, s.target, s.input, fireAt, key))
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Enqueue threw ${e.javaClass.simpleName}")
            EnqueueOutcome.Failed("DEPENDENCY_UNAVAILABLE", true)
        }
        val now = clock.instant()
        return when (outcome) {
            is EnqueueOutcome.Enqueued -> { ledger(s, fireAt, ExecutionStatus.ENQUEUED, outcome.runRef, null, now); true }
            is EnqueueOutcome.Duplicate -> { ledger(s, fireAt, ExecutionStatus.ENQUEUED, outcome.runRef, null, now); true }
            is EnqueueOutcome.Failed -> {
                recordSystem(s, "ENQUEUE_FAILED", outcome.code)
                // A permanent refusal (e.g. the owner lost the permission) must not be retried every tick: drop the pending fire, keep the schedule.
                if (!outcome.retryable) { ledger(s, fireAt, ExecutionStatus.FAILED, null, outcome.code, now); confirm(s, "FAILED_${outcome.code}"); null } else false
            }
        }
    }

    private fun ledger(s: Schedule, fireAt: Instant, status: ExecutionStatus, runRef: String?, errorCode: String?, now: Instant) {
        try {
            val attempts = (store.execution(s.tenantId, s.id, fireAt)?.attempts ?: 0) + 1
            store.updateExecution(s.tenantId, s.id, fireAt, status, attempts, runRef, errorCode, now)
        } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Execution ledger update failed: ${e.javaClass.simpleName}") } // the row stays CLAIMED; the key dedupes a retry
    }

    private fun confirm(s: Schedule, status: String) {
        repeat(3) {
            val cur = store.get(s.tenantId, s.id) ?: return
            if (cur.pendingFireAt == null) return
            if (store.compareAndSet(cur, cur.copy(pendingFireAt = null, pendingAttempts = 0, pendingNotBefore = null, lastRunStatus = status, updatedAt = clock.instant()))) { recordSystem(cur, status); return }
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
