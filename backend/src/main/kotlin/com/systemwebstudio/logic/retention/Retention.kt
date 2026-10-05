package com.systemwebstudio.logic.retention

import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.approval.ApprovalStore
import com.systemwebstudio.logic.scheduler.ScheduleStore
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How long finished rows are kept. Every horizon is **age since the row finished**; rows that are not finished (RUNNING, PENDING, WAITING, a
 * compensation in progress, a pending approval, an unconfirmed schedule fire) are never touched whatever their age - that rule lives in the
 * store contracts, not in these numbers.
 *
 * The minimum is not cosmetic: deleting an `action_runs` row ends the replay guarantee of its idempotency key, and deleting a `workflow_runs`
 * row ends the de-duplication of a start key, so a horizon shorter than the longest client retry window would turn retries into duplicate effects.
 */
data class RetentionPolicy(
    val actionRuns: Duration = Duration.ofDays(30),
    /** Stage 1 for workflow runs: after this the run's input and step payloads are blanked (the row stays for status/audit history). */
    val workflowRunPayloads: Duration = Duration.ofDays(14),
    /** Stage 2 for workflow runs: the row (and its steps) is deleted. */
    val workflowRuns: Duration = Duration.ofDays(90),
    val approvals: Duration = Duration.ofDays(180),
    val scheduleExecutions: Duration = Duration.ofDays(30),
    /** Rows per store call; every call is one short transaction. */
    val batchSize: Int = 500,
    /** Batches per target per [RetentionService.runOnce]; a backlog is worked off over several runs instead of one long delete. */
    val maxBatchesPerTarget: Int = 20
) {
    init {
        require(batchSize in 1..5000) { "batchSize must be 1..5000" }
        require(maxBatchesPerTarget >= 1) { "maxBatchesPerTarget must be at least 1" }
        mapOf("actionRuns" to actionRuns, "workflowRunPayloads" to workflowRunPayloads, "workflowRuns" to workflowRuns, "approvals" to approvals, "scheduleExecutions" to scheduleExecutions)
            .forEach { (name, d) -> require(d >= MIN_RETENTION) { "$name retention must be at least $MIN_RETENTION (idempotency / replay window)" } }
        require(workflowRunPayloads < workflowRuns) { "workflowRunPayloads must be shorter than workflowRuns" }
    }

    companion object { val MIN_RETENTION: Duration = Duration.ofDays(7) }
}

/** One store's retention work: an optional redact stage and a delete stage, each with its own age horizon. Both stages only ever see finished rows. */
class RetentionTarget(
    val name: String,
    val redactAfter: Duration?,
    val deleteAfter: Duration,
    /** (rows finished before this instant, batch size) -> rows redacted */
    val redact: (Instant, Int) -> Int = { _, _ -> 0 },
    /** (rows finished before this instant, batch size) -> rows deleted */
    val purge: (Instant, Int) -> Int
)

data class TargetReport(val name: String, val redacted: Int = 0, val purged: Int = 0, val errors: Int = 0, val moreToDo: Boolean = false)
data class RetentionReport(val targets: List<TargetReport>) {
    val redacted get() = targets.sumOf { it.redacted }
    val purged get() = targets.sumOf { it.purged }
    val errors get() = targets.sumOf { it.errors }
}

object RetentionTargets {
    fun actionRuns(store: ActionRunStore, policy: RetentionPolicy) =
        RetentionTarget("action_runs", null, policy.actionRuns, purge = { before, n -> store.purgeFinished(before, n) })

    fun workflowRuns(store: WorkflowRunStore, policy: RetentionPolicy, placeholder: JsonNode, clock: Clock) =
        RetentionTarget("workflow_runs", policy.workflowRunPayloads, policy.workflowRuns,
            redact = { before, n -> store.redactFinished(before, n, placeholder, clock.instant()) },
            purge = { before, n -> store.purgeFinished(before, n) })

    /** [runs] lets retention keep a final approval whose workflow run is still active (the run has not consumed the decision yet). */
    fun approvals(store: ApprovalStore, policy: RetentionPolicy, runs: WorkflowRunStore? = null) =
        RetentionTarget("approvals", null, policy.approvals, purge = { before, n ->
            store.purgeFinal(before, n) { a ->
                val runId = a.source.workflowRunId
                runId != null && runs != null && (try { runs.get(a.tenantId, runId)?.status?.terminal == false } catch (e: Exception) { true })
            }
        })

    fun scheduleExecutions(store: ScheduleStore, policy: RetentionPolicy) =
        RetentionTarget("schedule_executions", null, policy.scheduleExecutions, purge = { before, n -> store.purgeExecutions(before, n) })

    /** The four standard targets. */
    fun standard(
        policy: RetentionPolicy, actionRuns: ActionRunStore, workflowRuns: WorkflowRunStore, approvals: ApprovalStore, schedules: ScheduleStore,
        json: JsonMapper, clock: Clock = Clock.systemUTC()
    ) = listOf(
        actionRuns(actionRuns, policy),
        workflowRuns(workflowRuns, policy, json.createObjectNode().put("redacted", true), clock),
        approvals(approvals, policy, workflowRuns),
        scheduleExecutions(schedules, policy)
    )
}

/**
 * The cleanup service: call [runOnce] from a platform timer (C0 wiring), e.g. hourly. It deletes nothing itself - it asks each store for a **bounded
 * batch of finished rows older than the horizon**, oldest first, and repeats until a batch comes back short or [RetentionPolicy.maxBatchesPerTarget]
 * is reached (the rest is for the next run). Safe on several nodes at once: stage 1 is idempotent and stage 2 deletes are idempotent. One failing
 * target never stops the others. A flapping store cannot make the service spin: each target does at most `maxBatchesPerTarget` calls per run.
 *
 * Active and in-flight rows are protected by the **store contracts** ([ActionRunStore.purgeFinished], [WorkflowRunStore.purgeFinished],
 * [ApprovalStore.purgeFinal], [ScheduleStore.purgeExecutions]) - not by anything this class could get wrong - and by the tests that put ancient
 * active rows next to ancient finished ones.
 */
class RetentionService(
    private val targets: List<RetentionTarget>,
    private val policy: RetentionPolicy = RetentionPolicy(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = System.getLogger(RetentionService::class.java.name)

    fun runOnce(): RetentionReport {
        val now = clock.instant()
        return RetentionReport(targets.map { t -> runTarget(t, now) })
    }

    private fun runTarget(t: RetentionTarget, now: Instant): TargetReport {
        var redacted = 0; var purged = 0; var errors = 0; var more = false
        t.redactAfter?.let { after ->
            val (n, err, m) = drain({ before, size -> t.redact(before, size) }, now.minus(after))
            redacted += n; errors += err; more = more || m
        }
        val (n, err, m) = drain({ before, size -> t.purge(before, size) }, now.minus(t.deleteAfter))
        purged += n; errors += err; more = more || m
        if (redacted > 0 || purged > 0 || errors > 0) log.log(System.Logger.Level.INFO, "Retention ${t.name}: redacted=$redacted purged=$purged errors=$errors moreToDo=$more")
        return TargetReport(t.name, redacted, purged, errors, more)
    }

    private fun drain(stage: (Instant, Int) -> Int, before: Instant): Triple<Int, Int, Boolean> {
        var total = 0
        repeat(policy.maxBatchesPerTarget) {
            val n = try { stage(before, policy.batchSize) } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "Retention batch failed: ${e.javaClass.simpleName}")
                return Triple(total, 1, false)
            }
            total += n
            if (n < policy.batchSize) return Triple(total, 0, false)
        }
        return Triple(total, 0, true)
    }
}
