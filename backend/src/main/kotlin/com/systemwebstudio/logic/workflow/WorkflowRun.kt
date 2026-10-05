package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import tools.jackson.databind.JsonNode
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
    /** Current step is WAITING (timer) or RETRY_WAIT with wakeAt <= [now]. */
    fun dueTimers(now: Instant, limit: Int): List<WorkflowRun>
    /** Current step is WAITING for an approval. */
    fun waitingApprovals(limit: Int): List<WorkflowRun>
    /** Not finished (or compensation still IN_PROGRESS) and untouched since [updatedBefore]: lost job, crashed worker, stuck compensation. */
    fun stale(updatedBefore: Instant, limit: Int): List<WorkflowRun>
    fun list(tenantId: UUID, appId: UUID?, limit: Int): List<WorkflowRun>
}

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

    override fun dueTimers(now: Instant, limit: Int) = rows.values.filter { r ->
        !r.status.terminal && r.currentStep.let { s ->
            s != null && s.approvalId == null && (s.status == StepStatus.WAITING || s.status == StepStatus.RETRY_WAIT) && s.wakeAt != null && !s.wakeAt.isAfter(now)
        }
    }.sortedBy { it.updatedAt }.take(limit)

    override fun waitingApprovals(limit: Int) = rows.values.filter { r ->
        !r.status.terminal && r.currentStep.let { it != null && it.status == StepStatus.WAITING && it.approvalId != null }
    }.sortedBy { it.updatedAt }.take(limit)

    override fun stale(updatedBefore: Instant, limit: Int) = rows.values.filter {
        (!it.status.terminal || it.compensation == CompensationState.IN_PROGRESS) && it.updatedAt.isBefore(updatedBefore)
    }.sortedBy { it.updatedAt }.take(limit)

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
