package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.limits.FairSelection
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class WorkflowRunStatus(val terminal: Boolean) {
    PENDING(false), RUNNING(false),
    /** Waiting for a timer (WAIT step, retry backoff) or an approval. */
    WAITING(false),
    SUCCEEDED(true), FAILED(true), CANCELLED(true)
}

enum class StepStatus { PENDING, RUNNING, WAITING, RETRY_WAIT, SUCCEEDED, FAILED }

enum class CompensationState { NONE, IN_PROGRESS, DONE, PARTIAL }

/** One row of the future `workflow_run_steps` table. [input]/[output] are what the step received/produced (bounded by the payload limit). */
data class StepState(
    val stepId: String,
    val status: StepStatus,
    val attempt: Int = 0,
    val input: JsonNode? = null,
    val output: JsonNode? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** WAITING (timer) / RETRY_WAIT: when the sweeper may continue. */
    val wakeAt: Instant? = null,
    val approvalId: UUID? = null,
    /** TEST mode: nothing was executed; [dryRunLevel] says how far it was verified. */
    val simulated: Boolean = false,
    val dryRunLevel: DryRunLevel? = null,
    val compensated: Boolean = false,
    /** How many times the run has entered this step (a loop comes back to a step). Part of the step's idempotency key from the 2nd visit on. */
    val visit: Int = 1
)

/**
 * One row of the future `workflow_runs` table. The [definition] is a **snapshot** taken at start: editing or republishing the workflow
 * never changes a run that is already in flight.
 */
data class WorkflowRun(
    val runId: UUID,
    val tenantId: UUID,
    val appId: UUID,
    val workflowId: String,
    val definition: WorkflowDefinition,
    val mode: ExecutionMode,
    val status: WorkflowRunStatus,
    /** The run acts as this actor at every step; permissions are re-checked per step, never captured at start. */
    val createdBy: ActionActor,
    val workspaceId: UUID?,
    val idempotencyKey: String,
    val fingerprint: String,
    val input: JsonNode,
    val currentStepId: String?,
    val steps: Map<String, StepState> = emptyMap(),
    /** Succeeded ACTION steps that have a compensation, in execution order. */
    val compensable: List<String> = emptyList(),
    val stepExecutions: Int = 0,
    val depth: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val compensation: CompensationState = CompensationState.NONE,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant? = null,
    /**
     * Consecutive failures **attributed to this run** while a worker processed one of its messages (an exception, not an infrastructure outage).
     * Reset when the run makes progress. At `maxProcessFailures` the run is failed (DEAD_LETTERED) - the only way a bad run reaches the DLQ path.
     */
    val processFailures: Int = 0,
    /** Consecutive sweeper passes that could not move this run (publish failed, contention, exception). Only drives the backoff, never fails the run. */
    val sweepFailures: Int = 0,
    /** Backoff: neither a worker nor the sweeper touches the run before this instant. */
    val notBefore: Instant? = null,
    /** Rotation cursor of the sweeper: the run was last examined (claimed) at this instant. Oldest cursor is served first. */
    val lastSweptAt: Instant? = null,
    val version: Long = 0
) {
    val currentStep: StepState? get() = currentStepId?.let { steps[it] }
    fun withStep(s: StepState) = copy(steps = steps + (s.stepId to s))
}

sealed interface CreateOutcome {
    data class Created(val run: WorkflowRun) : CreateOutcome
    data class Existing(val run: WorkflowRun) : CreateOutcome
    /** Same key, different input/workflow: a client bug or an attack. */
    data object KeyReused : CreateOutcome
}

/** Persistence with compare-and-set; a JDBC implementation needs migrations (BOARD.md request). */
interface WorkflowRunStore {
    /** Idempotent on (tenant, app, workflow, creator, mode, idempotencyKey): a TEST run and a LIVE run never share a key scope. */
    fun create(run: WorkflowRun): CreateOutcome
    fun get(tenantId: UUID, runId: UUID): WorkflowRun?
    /** Succeeds only if the stored version equals [expected].version; the stored row gets version + 1. */
    fun compareAndSet(expected: WorkflowRun, next: WorkflowRun): Boolean
    /**
     * **Fair, bounded claim for the sweeper** (replaces the three "first N by updatedAt" queries that let long-waiting runs fill every slot).
     * Eligible = [needsSweep] (a timer/retry is due, an approval is awaited, or the run is stale) and `notBefore` is null or passed and the run was
     * not claimed within the last [minInterval]. Among the eligible, order by rotation cursor (`lastSweptAt`, never-examined first) then `updatedAt` -
     * oldest eligible first - then take at most [perTenant] per tenant round-robin and [limit] in total, and stamp `lastSweptAt = now` on exactly
     * the returned runs in the same atomic step, so a second sweeper node does not receive them and every eligible run is examined within
     * `ceil(eligible / limit)` passes whatever the others do. Stamping bumps `version` (a worker's compare-and-set simply re-reads).
     * A run that is *only* waiting for an approval (nothing due, not stale: the callback normally resumes it, polling is a fallback) is re-examined
     * at most every [approvalInterval], so long-waiting approvals do not take sweeper slots from runs that need action.
     * SQL equivalent: `WHERE <needsSweep> AND (not_before IS NULL OR not_before <= :now) AND (last_swept_at IS NULL OR last_swept_at <= :now - :minInterval)`
     * ordered by `last_swept_at NULLS FIRST, updated_at`, with `row_number() OVER (PARTITION BY tenant_id …) <= :perTenant`, `FOR UPDATE SKIP LOCKED`.
     */
    fun claimForSweep(now: Instant, limit: Int, perTenant: Int, minInterval: Duration, staleBefore: Instant, approvalInterval: Duration = minInterval): List<WorkflowRun>
    /** A worker failed while processing a message of this run: count it and push the run back until [notBefore]. @return the new count, or -1 if the run does not exist. */
    fun recordProcessFailure(tenantId: UUID, runId: UUID, notBefore: Instant): Int
    /** Outcome of examining a claimed run: [failed] backs it off until [notBefore] and counts; success clears the sweep failure count. */
    fun recordSweepResult(tenantId: UUID, runId: UUID, failed: Boolean, notBefore: Instant?)
    fun list(tenantId: UUID, appId: UUID?, limit: Int): List<WorkflowRun>
}

/** The current step waits on a timer or a retry backoff that has come due. */
fun WorkflowRun.isTimerDue(now: Instant): Boolean = !status.terminal && currentStep.let { s ->
    s != null && s.approvalId == null && (s.status == StepStatus.WAITING || s.status == StepStatus.RETRY_WAIT) && s.wakeAt != null && !s.wakeAt.isAfter(now)
}
/** The current step waits for an approval decision (the sweeper reconciles a decision whose callback was lost). */
fun WorkflowRun.isAwaitingApproval(): Boolean = !status.terminal && currentStep.let { it != null && it.status == StepStatus.WAITING && it.approvalId != null }
/** Not finished (or compensation still IN_PROGRESS) and untouched since [staleBefore]: lost job, crashed worker, stuck compensation, overdue run. */
fun WorkflowRun.isStale(staleBefore: Instant): Boolean = (!status.terminal || compensation == CompensationState.IN_PROGRESS) && updatedAt.isBefore(staleBefore)
fun WorkflowRun.needsSweep(now: Instant, staleBefore: Instant): Boolean = isTimerDue(now) || isAwaitingApproval() || isStale(staleBefore)

class InMemoryWorkflowRunStore : WorkflowRunStore {
    private val rows = ConcurrentHashMap<Pair<UUID, UUID>, WorkflowRun>()

    @Synchronized
    override fun create(run: WorkflowRun): CreateOutcome {
        val existing = rows.values.firstOrNull {
            it.tenantId == run.tenantId && it.appId == run.appId && it.workflowId == run.workflowId &&
                it.createdBy.userId == run.createdBy.userId && it.mode == run.mode && it.idempotencyKey == run.idempotencyKey
        }
        if (existing != null) return if (existing.fingerprint == run.fingerprint) CreateOutcome.Existing(existing) else CreateOutcome.KeyReused
        rows[run.tenantId to run.runId] = run
        return CreateOutcome.Created(run)
    }

    override fun get(tenantId: UUID, runId: UUID) = rows[tenantId to runId]

    override fun compareAndSet(expected: WorkflowRun, next: WorkflowRun): Boolean {
        var ok = false
        rows.computeIfPresent(expected.tenantId to expected.runId) { _, cur ->
            if (cur.version == expected.version) { ok = true; next.copy(version = cur.version + 1) } else cur
        }
        return ok
    }

    @Synchronized
    override fun claimForSweep(now: Instant, limit: Int, perTenant: Int, minInterval: Duration, staleBefore: Instant, approvalInterval: Duration): List<WorkflowRun> {
        val eligible = rows.values.filter { r ->
            val interval = if (r.isAwaitingApproval() && !r.isTimerDue(now) && !r.isStale(staleBefore)) approvalInterval else minInterval
            r.needsSweep(now, staleBefore) && (r.notBefore == null || !r.notBefore.isAfter(now)) && (r.lastSweptAt == null || !r.lastSweptAt.isAfter(now.minus(interval)))
        }.sortedWith(compareBy<WorkflowRun, Instant?>(nullsFirst()) { it.lastSweptAt }.thenBy { it.updatedAt }.thenBy { it.runId })
        val chosen = FairSelection.pick(eligible, limit, perTenant) { it.tenantId }
        val out = ArrayList<WorkflowRun>(chosen.size)
        for (r in chosen) {
            // compute() makes the stamp atomic with respect to a concurrent compareAndSet; a run that changed under us is simply skipped this pass
            var stamped: WorkflowRun? = null
            rows.computeIfPresent(r.tenantId to r.runId) { _, cur -> if (cur.version == r.version) cur.copy(lastSweptAt = now, version = cur.version + 1).also { stamped = it } else cur }
            stamped?.let { out += it }
        }
        return out
    }

    override fun recordProcessFailure(tenantId: UUID, runId: UUID, notBefore: Instant): Int {
        var n = -1
        rows.computeIfPresent(tenantId to runId) { _, r -> n = r.processFailures + 1; r.copy(processFailures = n, notBefore = notBefore, version = r.version + 1) }
        return n
    }

    override fun recordSweepResult(tenantId: UUID, runId: UUID, failed: Boolean, notBefore: Instant?) {
        rows.computeIfPresent(tenantId to runId) { _, r ->
            when {
                failed -> r.copy(sweepFailures = r.sweepFailures + 1, notBefore = notBefore, version = r.version + 1)
                r.sweepFailures == 0 -> r
                // the backoff was the sweeper's own: lift it; a process-failure backoff stays until it expires
                else -> r.copy(sweepFailures = 0, notBefore = if (r.processFailures == 0) null else r.notBefore, version = r.version + 1)
            }
        }
    }

    override fun list(tenantId: UUID, appId: UUID?, limit: Int) =
        rows.values.filter { it.tenantId == tenantId && (appId == null || it.appId == appId) }.sortedByDescending { it.createdAt }.take(limit)
}

/** Read model returned to callers: no definition snapshot, no internals. */
data class StepView(
    val stepId: String, val status: StepStatus, val attempt: Int, val output: JsonNode?, val errorCode: String?, val errorMessage: String?,
    val startedAt: Instant?, val finishedAt: Instant?, val simulated: Boolean, val dryRunLevel: DryRunLevel?, val approvalId: UUID?
)

data class WorkflowRunView(
    val runId: UUID, val workflowId: String, val appId: UUID, val mode: ExecutionMode, val status: WorkflowRunStatus, val currentStepId: String?,
    val steps: List<StepView>, val errorCode: String?, val errorMessage: String?, val compensation: CompensationState,
    val createdBy: UUID, val createdAt: Instant, val updatedAt: Instant, val finishedAt: Instant?
)

fun WorkflowRun.toView() = WorkflowRunView(
    runId, workflowId, appId, mode, status, currentStepId,
    definition.steps.mapNotNull { steps[it.id] }.map {
        StepView(it.stepId, it.status, it.attempt, it.output, it.errorCode, it.errorMessage, it.startedAt, it.finishedAt, it.simulated, it.dryRunLevel, it.approvalId)
    },
    errorCode, errorMessage, compensation, createdBy.userId, createdAt, updatedAt, finishedAt
)
