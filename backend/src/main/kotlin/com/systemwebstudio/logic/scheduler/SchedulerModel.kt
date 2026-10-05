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
    /** Claimed but unconfirmed fires older than [olderThan], with the same fairness rule as [due]. */
    fun pending(olderThan: Instant, limit: Int, perTenant: Int = Int.MAX_VALUE): List<Schedule>
}

class InMemoryScheduleStore : ScheduleStore {
    private val rows = ConcurrentHashMap<Pair<UUID, UUID>, Schedule>()
    override fun create(s: Schedule) { require(rows.putIfAbsent(s.tenantId to s.id, s) == null) { "duplicate schedule" } }
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
    override fun pending(olderThan: Instant, limit: Int, perTenant: Int) = FairSelection.pick(
        rows.values.filter { it.pendingFireAt != null && it.updatedAt.isBefore(olderThan) }.sortedBy { it.updatedAt }, limit, perTenant
    ) { it.tenantId }
}
