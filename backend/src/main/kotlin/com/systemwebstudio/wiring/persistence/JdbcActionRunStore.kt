package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRunRecord
import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.RunBegin
import com.systemwebstudio.logic.action.RunKey
import com.systemwebstudio.logic.action.RunStatus
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.instantOrNull
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * `action_runs` (V29): the durable twin of `InMemoryActionRunStore`, with the same decision table (C4 `ActionRunStore` contract, unchanged):
 *
 *  - no row -> the caller owns a new RUNNING run (attempt 1) = Started;  - same key, other fingerprint -> KeyReused;  - RUNNING -> InProgress;
 *  - SUCCEEDED -> Replay of the recorded result;  - FAILED + `retryable` -> restarted with attempt + 1 (a NEW run id);  - any other FAILED -> Replay as it is.
 *
 * So a finished write is replayed and **never** run twice, an ambiguous write (`IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable = false`) stays that way across a restart,
 * and `complete` only succeeds for the run that still owns the RUNNING state (a run the sweeper already failed cannot overwrite the sweeper's answer).
 * `begin` runs in one transaction that locks the key's row (`SELECT .. FOR UPDATE`), so two callers/nodes cannot both get Started.
 *
 * Isolation: every statement carries `tenant_id`; an insert for an application that does not belong to the tenant is refused (default deny, the caller sees
 * an exception = "run state unavailable"), and the workspace of the run is always derived from the project in the database, never taken from a caller.
 * Only the derived key is stored; the stored result is exactly what a replay answers with (the action's output or its typed failure, no secrets by C4's contract).
 */
class JdbcActionRunStore(
    private val jdbc: JdbcTemplate,
    json: JsonMapper,
    private val tx: TransactionOperations = RunStoreSupport.transactions(jdbc),
    private val workerId: String = RunStoreSupport.workerId()
) : ActionRunStore {
    private val codec = ActionResultCodec(json)

    private class Row(val runId: UUID, val fingerprint: String, val status: String, val attempt: Int, val result: String?, val startedAt: Instant, val finishedAt: Instant?, val updatedAt: Instant)

    override fun begin(key: RunKey, fingerprint: String, now: Instant): RunBegin {
        repeat(ATTEMPTS) {
            val outcome = tx.execute { beginOnce(key, fingerprint, now) }
            if (outcome != null) return outcome
        }
        // contention that never settled: never guess Started
        throw IllegalStateException("the run state of this key could not be settled")
    }

    /** @return null = the row vanished between the statements (purged / released): take another turn */
    private fun beginOnce(key: RunKey, fingerprint: String, now: Instant): RunBegin? {
        val workspaceId = workspaceOf(key)                                                    // throws for an application of another tenant
        val runId = UUID.randomUUID()
        val inserted = jdbc.update(
            """INSERT INTO action_runs (run_id, tenant_id, workspace_id, app_id, action_id, user_id, idempotency_key, fingerprint, mode, status, attempt, worker_id,
                   started_at, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'LIVE', 'RUNNING', 1, ?, ?, ?, ?)
               ON CONFLICT DO NOTHING""",
            runId, key.tenantId, workspaceId, key.appId, key.actionId, key.userId, key.idempotencyKey, fingerprint, workerId, ts(now), ts(now), ts(now)
        )
        if (inserted == 1) return RunBegin.Started(runId.toString(), 1)

        val row = lock(key) ?: return null
        return when {
            row.fingerprint != fingerprint -> RunBegin.KeyReused
            row.status == RunStatus.RUNNING.name -> RunBegin.InProgress(row.runId.toString())
            row.status == RunStatus.SUCCEEDED.name -> RunBegin.Replay(recorded(row))
            else -> {
                val result = recorded(row)
                if ((result as? ActionResult.Failed)?.retryable == true) {
                    val next = UUID.randomUUID()
                    jdbc.update(
                        """UPDATE action_runs SET run_id = ?, status = 'RUNNING', attempt = attempt + 1, result = NULL, worker_id = ?, started_at = ?, finished_at = NULL, updated_at = ?
                           WHERE run_id = ? AND tenant_id = ?""",
                        next, workerId, ts(now), ts(now), row.runId, key.tenantId
                    )
                    RunBegin.Started(next.toString(), row.attempt + 1)
                } else RunBegin.Replay(result)
            }
        }
    }

    override fun complete(key: RunKey, runId: String, result: ActionResult, now: Instant): Boolean {
        val id = runId.toUuidOrNull() ?: return false
        val status = if (result is ActionResult.Ok) RunStatus.SUCCEEDED else RunStatus.FAILED
        return jdbc.update(
            """UPDATE action_runs SET status = ?, result = CAST(? AS jsonb), finished_at = ?, updated_at = ?
               WHERE run_id = ? AND tenant_id = ? AND action_id = ? AND user_id = ? AND idempotency_key = ?
                 AND COALESCE(app_id, ?::uuid) = COALESCE(?::uuid, ?::uuid) AND status = 'RUNNING'""",
            status.name, codec.encode(result), ts(now), ts(now), id, key.tenantId, key.actionId, key.userId, key.idempotencyKey,
            RunStoreSupport.NIL_UUID, key.appId, RunStoreSupport.NIL_UUID
        ) == 1
    }

    override fun find(key: RunKey): ActionRunRecord? =
        jdbc.query(selectKey(lock = false), { rs, _ -> mapRow(rs) }, *keyArgs(key)).firstOrNull()?.let { r ->
            ActionRunRecord(key, r.runId.toString(), r.fingerprint, RunStatus.valueOf(r.status), r.attempt, r.result?.let { codec.decode(it) }, r.startedAt, r.finishedAt, r.updatedAt)
        }

    /**
     * Sweeper hook (the contract): a RUNNING run untouched since [staleBefore] becomes a retryable FAILED `TIMEOUT`. That is safe for a write too: the retry carries the
     * same derived key into the data layer, whose own idempotency record answers Replay / OutcomeUnknown and never runs the write a second time.
     */
    override fun sweepStale(staleBefore: Instant, now: Instant): Int {
        val swept = codec.encode(ActionResult.Failed(ActionErrorCodes.TIMEOUT, true, "Run was abandoned and swept"))
        return jdbc.update(
            "UPDATE action_runs SET status = 'FAILED', result = CAST(? AS jsonb), finished_at = ?, updated_at = ? WHERE status = 'RUNNING' AND updated_at < ?",
            swept, ts(now), ts(now), ts(staleBefore)
        )
    }

    override fun purgeFinished(olderThan: Instant, limit: Int): Int {
        if (limit <= 0) return 0
        return jdbc.update(
            """DELETE FROM action_runs WHERE run_id IN (
                   SELECT run_id FROM action_runs WHERE status <> 'RUNNING' AND COALESCE(finished_at, updated_at) < ?
                   ORDER BY COALESCE(finished_at, updated_at) LIMIT ?)""",
            ts(olderThan), limit.coerceAtMost(MAX_PURGE)
        )
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The workspace of the key's application, from the database; null for "no application". Refuses an application that is not a project of this tenant. */
    private fun workspaceOf(key: RunKey): UUID? {
        val app = key.appId ?: return null
        return jdbc.queryForList(
            "SELECT p.workspace_id FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE p.id = ? AND w.tenant_id = ?",
            UUID::class.java, app, key.tenantId
        ).firstOrNull() ?: throw IllegalStateException("the application does not belong to the tenant")
    }

    private fun lock(key: RunKey): Row? = jdbc.query(selectKey(lock = true), { rs, _ -> mapRow(rs) }, *keyArgs(key)).firstOrNull()

    private fun recorded(row: Row): ActionResult = codec.decode(requireNotNull(row.result) { "a finished run has no stored result" })

    private fun selectKey(lock: Boolean) =
        """SELECT run_id, fingerprint, status, attempt, result::text AS result, started_at, finished_at, updated_at FROM action_runs
           WHERE tenant_id = ? AND COALESCE(app_id, ?::uuid) = COALESCE(?::uuid, ?::uuid) AND action_id = ? AND user_id = ? AND idempotency_key = ?""" + if (lock) " FOR UPDATE" else ""

    private fun keyArgs(key: RunKey): Array<Any?> =
        arrayOf(key.tenantId, RunStoreSupport.NIL_UUID, key.appId, RunStoreSupport.NIL_UUID, key.actionId, key.userId, key.idempotencyKey)

    private fun mapRow(rs: java.sql.ResultSet) = Row(
        rs.uuid("run_id"), rs.getString("fingerprint"), rs.getString("status"), rs.getInt("attempt"), rs.getString("result"),
        rs.instant("started_at"), rs.instantOrNull("finished_at"), rs.instant("updated_at")
    )

    private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (e: IllegalArgumentException) { null }

    companion object {
        private const val ATTEMPTS = 3
        private const val MAX_PURGE = 100_000
    }
}
