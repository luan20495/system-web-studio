package com.systemwebstudio.logic.action

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotency scope: a key is unique per tenant, app, action **and acting user**. The user is part of the scope on purpose: user B can neither
 * replay user A's recorded result (information leak) nor block A's key (denial of service).
 */
data class RunKey(val tenantId: UUID, val appId: UUID?, val actionId: String, val userId: UUID, val idempotencyKey: String)

enum class RunStatus { RUNNING, SUCCEEDED, FAILED }

/** One row of the future `action_runs` table (proposed DDL: docs/parallel/audit/FINAL-C4-runtime-design.md, migration request in BOARD.md). */
data class ActionRunRecord(
    val key: RunKey,
    val runId: String,
    val fingerprint: String,
    val status: RunStatus,
    val attempt: Int,
    val result: ActionResult?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val updatedAt: Instant
)

sealed interface RunBegin {
    /** This caller owns the run and must call [ActionRunStore.complete]. */
    data class Started(val runId: String, val attempt: Int) : RunBegin
    /** Same key + same inputs already finished (or failed non-retryably): return the recorded result, do not run again. */
    data class Replay(val result: ActionResult) : RunBegin
    /** Same key + same inputs currently running elsewhere. */
    data class InProgress(val runId: String) : RunBegin
    /** Same key but different inputs: a client bug or an attack — never merge. */
    data object KeyReused : RunBegin
}

/**
 * Run state + idempotency, with the compare-and-set discipline of the publish pipeline: `begin` is atomic and `complete`
 * only succeeds for the run that still owns the RUNNING state. A persistent implementation needs a table with a unique index on
 * (tenant_id, app_id, action_id, user_id, idempotency_key); until C0 grants a migration only [InMemoryActionRunStore] exists.
 *
 * Retry rule: a previous FAILED run with `retryable = true` may be restarted with the same key (attempt + 1);
 * SUCCEEDED and non-retryable FAILED are replayed as-is — so a retry can never create a second business record.
 *
 * TEST-mode requests never reach this store (they have no side effect, so there is nothing to de-duplicate).
 */
interface ActionRunStore {
    fun begin(key: RunKey, fingerprint: String, now: Instant): RunBegin
    /** @return false when [runId] no longer owns the RUNNING state (e.g. the sweeper already failed it). */
    fun complete(key: RunKey, runId: String, result: ActionResult, now: Instant): Boolean
    fun find(key: RunKey): ActionRunRecord?
    /** Sweeper hook: turns RUNNING runs untouched since [staleBefore] into retryable FAILED. Returns how many. */
    fun sweepStale(staleBefore: Instant, now: Instant): Int
    /**
     * Retention: deletes **finished** records (SUCCEEDED / FAILED) that finished before [olderThan], oldest first, at most [limit]. A RUNNING record is
     * never deleted, however old (the sweeper turns an abandoned one into FAILED first, which then ages like any other). Deleting a record ends
     * the replay guarantee of its idempotency key, so the horizon must exceed every client's retry window (RetentionPolicy enforces a minimum).
     * SQL: `DELETE FROM action_runs WHERE id IN (SELECT id FROM action_runs WHERE status <> 'RUNNING' AND finished_at < :olderThan ORDER BY finished_at LIMIT :limit)`.
     * @return the number of rows deleted
     */
    fun purgeFinished(olderThan: Instant, limit: Int): Int
}

class InMemoryActionRunStore : ActionRunStore {
    private val runs = ConcurrentHashMap<RunKey, ActionRunRecord>()

    override fun begin(key: RunKey, fingerprint: String, now: Instant): RunBegin {
        var outcome: RunBegin = RunBegin.KeyReused
        runs.compute(key) { _, existing ->
            when {
                existing == null -> newRun(key, fingerprint, 1, now).also { outcome = RunBegin.Started(it.runId, 1) }
                existing.fingerprint != fingerprint -> existing.also { outcome = RunBegin.KeyReused }
                existing.status == RunStatus.RUNNING -> existing.also { outcome = RunBegin.InProgress(it.runId) }
                existing.status == RunStatus.SUCCEEDED -> existing.also { outcome = RunBegin.Replay(it.result!!) }
                (existing.result as? ActionResult.Failed)?.retryable == true ->
                    newRun(key, fingerprint, existing.attempt + 1, now).also { outcome = RunBegin.Started(it.runId, it.attempt) }
                else -> existing.also { outcome = RunBegin.Replay(it.result!!) }
            }
        }
        return outcome
    }

    override fun complete(key: RunKey, runId: String, result: ActionResult, now: Instant): Boolean {
        var done = false
        runs.computeIfPresent(key) { _, r ->
            if (r.runId == runId && r.status == RunStatus.RUNNING) {
                done = true
                r.copy(status = if (result is ActionResult.Ok) RunStatus.SUCCEEDED else RunStatus.FAILED, result = result, finishedAt = now, updatedAt = now)
            } else r
        }
        return done
    }

    override fun find(key: RunKey): ActionRunRecord? = runs[key]

    override fun sweepStale(staleBefore: Instant, now: Instant): Int {
        var n = 0
        for (key in runs.keys) {
            runs.computeIfPresent(key) { _, r ->
                if (r.status == RunStatus.RUNNING && r.updatedAt.isBefore(staleBefore)) {
                    n++
                    r.copy(
                        status = RunStatus.FAILED, finishedAt = now, updatedAt = now,
                        result = failed(ActionErrorCodes.TIMEOUT, "Run was abandoned and swept", retryable = true)
                    )
                } else r
            }
        }
        return n
    }

    override fun purgeFinished(olderThan: Instant, limit: Int): Int {
        val victims = runs.values.filter { it.status != RunStatus.RUNNING && (it.finishedAt ?: it.updatedAt).isBefore(olderThan) }
            .sortedBy { it.finishedAt ?: it.updatedAt }.take(limit.coerceAtLeast(0))
        var n = 0
        for (v in victims) if (runs.remove(v.key, v)) n++     // remove(key, value): a record that changed meanwhile (restarted retry) is kept
        return n
    }

    /** Test helper: number of stored runs. */
    fun size(): Int = runs.size

    private fun newRun(key: RunKey, fingerprint: String, attempt: Int, now: Instant) =
        ActionRunRecord(key, UUID.randomUUID().toString(), fingerprint, RunStatus.RUNNING, attempt, null, now, null, now)
}
