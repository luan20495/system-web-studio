package com.systemwebstudio.logic.scheduler

import com.systemwebstudio.logic.limits.FairSelection
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface ScheduleTarget {
    data class Workflow(val workflowRef: String) : ScheduleTarget
    data class Action(val actionRef: String) : ScheduleTarget
}

/**
 * One fire of one schedule, identified by `(tenantId, scheduleId, fireAt)` - the **schedule-execution identity**. The ledger row is created
 * atomically (insert-if-absent, a unique key in SQL) *before* anything is enqueued, so a fire time is handed to the runtime at most once however
 * many nodes tick, however often the process restarts, and even if the schedule row is rewound (restore, cron edit, clock jump back).
 * [dedupeKey] is the idempotency key given to the workflow runtime (`sched:<scheduleId>:<fireEpochMilli>`), a second, independent guard.
 */
data class ScheduleExecution(
    val tenantId: UUID,
    val scheduleId: UUID,
    val appId: UUID,
    val fireAt: Instant,
    val dedupeKey: String,
    val status: ExecutionStatus,
    val attempts: Int = 0,
    val runRef: String? = null,
    val errorCode: String? = null,
    val claimedAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun dedupeKey(scheduleId: UUID, fireAt: Instant) = "sched:$scheduleId:${fireAt.toEpochMilli()}"
    }
}

/** CLAIMED = reserved, enqueue not confirmed (a crash leaves it here and the retry path finishes it); the rest are final. */
enum class ExecutionStatus(val final: Boolean) { CLAIMED(false), ENQUEUED(true), SKIPPED(true), FAILED(true) }

sealed interface ClaimResult {
    /** This call created the row: the caller owns the fire. */
    data class Claimed(val execution: ScheduleExecution) : ClaimResult
    /** The fire was already claimed (by this or another node, before a restart, ...): [execution] is what is stored. */
    data class Existing(val execution: ScheduleExecution) : ClaimResult
}

/** What to do with fire times that were missed (node down, long outage). Never "fire every missed time": at most one catch-up run. */
enum class MisfirePolicy { SKIP, FIRE_ONCE }

/**
 * A stored schedule. [pendingFireAt] is the outbox: a fire time that was claimed but whose enqueue is not confirmed yet; the next tick
 * re-enqueues it (the idempotency key `sched:<id>:<fireAt>` makes that harmless).
 */
data class Schedule(
    val id: UUID,
    val tenantId: UUID,
    val appId: UUID,
    val name: String,
    val cron: String,
    val timezone: String,
    val enabled: Boolean,
    val target: ScheduleTarget,
    val input: JsonNode?,
    /** The schedule runs as this user — never with more permission than that user has when it fires. */
    val createdBy: UUID,
    val misfirePolicy: MisfirePolicy = MisfirePolicy.SKIP,
    val nextRunAt: Instant?,
    val lastRunAt: Instant? = null,
    val lastRunStatus: String? = null,
    val pendingFireAt: Instant? = null,
    /** Enqueue attempts made for [pendingFireAt]; drives the backoff and the give-up point. */
    val pendingAttempts: Int = 0,
    /** The pending fire is not retried before this instant (null = as soon as it is old enough). */
    val pendingNotBefore: Instant? = null,
    /**
     * Stable identity of a schedule that is *declared* by an app definition (`workflow:<workflowId>`), unique per (tenant, app). Publishing the same
     * definition again finds the schedule by this key instead of creating a second one. Null for schedules created by hand.
     */
    val declaredKey: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0
)

data class ScheduledRunRequest(
    val tenantId: UUID,
    val appId: UUID,
    val scheduleId: UUID,
    val actorUserId: UUID,
    val target: ScheduleTarget,
    val input: JsonNode?,
    val fireAt: Instant,
    val idempotencyKey: String
)

sealed interface EnqueueOutcome {
    data class Enqueued(val runRef: String) : EnqueueOutcome
    /** Already enqueued earlier with the same key. */
    data class Duplicate(val runRef: String) : EnqueueOutcome
    data class Failed(val code: String, val retryable: Boolean) : EnqueueOutcome
}

/** The scheduler's only way to cause work: hand a request to the workflow runtime's queue. Implemented in `logic.workflow`. */
interface ScheduledRunEnqueuer {
    fun enqueue(request: ScheduledRunRequest): EnqueueOutcome
}

interface ScheduleStore {
    fun create(s: Schedule)
    fun get(tenantId: UUID, id: UUID): Schedule?
    /** Succeeds only if the stored version equals [expected].version; the stored row gets version + 1. */
    fun compareAndSet(expected: Schedule, next: Schedule): Boolean
    fun delete(tenantId: UUID, id: UUID): Boolean
    fun list(tenantId: UUID, appId: UUID?): List<Schedule>
    /** Due schedules, oldest fire time first, at most [limit] in total and [perTenant] per tenant (round-robin across tenants, see [com.systemwebstudio.logic.limits.FairSelection]). */
    fun due(now: Instant, limit: Int, perTenant: Int = Int.MAX_VALUE): List<Schedule>
    /** Claimed but unconfirmed fires whose retry time ([Schedule.pendingNotBefore]) has come, oldest first, with the same fairness rule as [due]. */
    fun pending(now: Instant, limit: Int, perTenant: Int = Int.MAX_VALUE): List<Schedule>
    /** The schedule a definition declared under [declaredKey], if any. */
    fun findDeclared(tenantId: UUID, appId: UUID, declaredKey: String): Schedule?

    /** Atomic insert-if-absent on `(tenantId, scheduleId, fireAt)`. SQL: `INSERT ... ON CONFLICT DO NOTHING`, then read the winner. */
    fun claimExecution(execution: ScheduleExecution): ClaimResult
    /** Moves a ledger row forward (never from a final status to another one). @return the stored row, or null if there is none. */
    fun updateExecution(tenantId: UUID, scheduleId: UUID, fireAt: Instant, status: ExecutionStatus, attempts: Int, runRef: String?, errorCode: String?, now: Instant): ScheduleExecution?
    fun execution(tenantId: UUID, scheduleId: UUID, fireAt: Instant): ScheduleExecution?
    /** Newest first. */
    fun executions(tenantId: UUID, scheduleId: UUID, limit: Int): List<ScheduleExecution>
    /** Retention: deletes ledger rows in a **final** status (never CLAIMED) last updated before [olderThan], oldest first, at most [limit]. @return rows deleted */
    fun purgeExecutions(olderThan: Instant, limit: Int): Int
}

class InMemoryScheduleStore : ScheduleStore {
    private val rows = ConcurrentHashMap<Pair<UUID, UUID>, Schedule>()
    private val ledger = ConcurrentHashMap<Triple<UUID, UUID, Instant>, ScheduleExecution>()

    @Synchronized override fun create(s: Schedule) {
        require(s.declaredKey == null || findDeclared(s.tenantId, s.appId, s.declaredKey) == null) { "duplicate declared schedule" }
        require(rows.putIfAbsent(s.tenantId to s.id, s) == null) { "duplicate schedule" }
    }
    override fun findDeclared(tenantId: UUID, appId: UUID, declaredKey: String) =
        rows.values.firstOrNull { it.tenantId == tenantId && it.appId == appId && it.declaredKey == declaredKey }
    override fun get(tenantId: UUID, id: UUID) = rows[tenantId to id]
    override fun compareAndSet(expected: Schedule, next: Schedule): Boolean {
        var ok = false
        rows.computeIfPresent(expected.tenantId to expected.id) { _, cur -> if (cur.version == expected.version) { ok = true; next.copy(version = cur.version + 1) } else cur }
        return ok
    }
    override fun delete(tenantId: UUID, id: UUID) = rows.remove(tenantId to id) != null
    override fun list(tenantId: UUID, appId: UUID?) = rows.values.filter { it.tenantId == tenantId && (appId == null || it.appId == appId) }.sortedBy { it.createdAt }
    override fun due(now: Instant, limit: Int, perTenant: Int) = FairSelection.pick(
        rows.values.filter { it.enabled && it.pendingFireAt == null && it.nextRunAt != null && !it.nextRunAt.isAfter(now) }.sortedBy { it.nextRunAt }, limit, perTenant
    ) { it.tenantId }
    override fun pending(now: Instant, limit: Int, perTenant: Int) = FairSelection.pick(
        rows.values.filter { it.pendingFireAt != null && (it.pendingNotBefore == null || !it.pendingNotBefore.isAfter(now)) }.sortedBy { it.pendingNotBefore ?: it.updatedAt }, limit, perTenant
    ) { it.tenantId }

    override fun claimExecution(execution: ScheduleExecution): ClaimResult {
        val key = Triple(execution.tenantId, execution.scheduleId, execution.fireAt)
        val prev = ledger.putIfAbsent(key, execution)
        return if (prev == null) ClaimResult.Claimed(execution) else ClaimResult.Existing(prev)
    }

    override fun updateExecution(tenantId: UUID, scheduleId: UUID, fireAt: Instant, status: ExecutionStatus, attempts: Int, runRef: String?, errorCode: String?, now: Instant): ScheduleExecution? =
        ledger.computeIfPresent(Triple(tenantId, scheduleId, fireAt)) { _, cur ->
            if (cur.status.final) cur else cur.copy(status = status, attempts = attempts, runRef = runRef ?: cur.runRef, errorCode = errorCode, updatedAt = now)
        }

    override fun purgeExecutions(olderThan: Instant, limit: Int): Int {
        val victims = ledger.values.filter { it.status.final && it.updatedAt.isBefore(olderThan) }.sortedBy { it.updatedAt }.take(limit.coerceAtLeast(0))
        var n = 0
        for (v in victims) if (ledger.remove(Triple(v.tenantId, v.scheduleId, v.fireAt), v)) n++
        return n
    }

    override fun execution(tenantId: UUID, scheduleId: UUID, fireAt: Instant) = ledger[Triple(tenantId, scheduleId, fireAt)]
    override fun executions(tenantId: UUID, scheduleId: UUID, limit: Int) =
        ledger.values.filter { it.tenantId == tenantId && it.scheduleId == scheduleId }.sortedByDescending { it.fireAt }.take(limit)
}
