package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActorKind
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.limits.FairSelection
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.CreateOutcome
import com.systemwebstudio.logic.workflow.StepState
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowRun
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.instantOrNull
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuidOrNull
import com.systemwebstudio.logic.arrayItems
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `workflow_runs` + `workflow_run_steps` (V29): the durable twin of `InMemoryWorkflowRunStore`, same contract, same compare-and-set discipline.
 *
 *  - **compare-and-set**: one transaction = `UPDATE workflow_runs .. WHERE tenant_id AND run_id AND version = expected.version` (+1) and an upsert of the steps that
 *    changed; 0 rows = the CAS lost and nothing was written. A step is never visible without the run state that produced it.
 *  - **create** is idempotent on (tenant, app, workflow, creator, mode, idempotencyKey): the same key and fingerprint is `Existing`, another fingerprint `KeyReused`.
 *  - **step lease** (C4 H-3): `lease_owner` / `lease_until` are part of the run row, so the claim of a step and its lease are ONE compare-and-set; a renewal is a compare-and-set
 *    too, and every transition that leaves RUNNING clears both. A run whose lease ran out is abandoned; a run without a lease falls back to `updated_at`.
 *  - **sweeper claim** (`claimForSweep`): eligible = timer due / approval awaited / abandoned (the same predicates as `WorkflowRun.needsSweep`, evaluated on the denormalised
 *    `cur_*` and lease columns), not backing off, not claimed within the interval; ordered by rotation cursor then age, at most `perTenant` per tenant; each chosen run is stamped
 *    (`last_swept_at = now`, version + 1) by a version-guarded UPDATE, so two sweeper nodes never both receive the same run.
 *  - **retention**: redaction replaces `input` and the step payloads on finished runs only; purge deletes finished runs only (steps cascade). Active, in-flight and
 *    compensating runs are never touched, however old.
 * Isolation: every statement carries `tenant_id`; a run for an application that is not a project of the tenant is refused at `create` (default deny), and the
 * composite foreign keys of V29 refuse a workspace that is not the project's or the tenant's.
 */
class JdbcWorkflowRunStore(
    private val jdbc: JdbcTemplate,
    private val json: JsonMapper,
    private val tx: TransactionOperations = RunStoreSupport.transactions(jdbc)
) : WorkflowRunStore {
    private val definitions = WorkflowDefinitionCodec(json)

    // ------------------------------------------------------------------------------------------------ create / get / list

    override fun create(run: WorkflowRun): CreateOutcome = tx.execute {
        requireProjectOfTenant(run.tenantId, run.appId)
        val inserted = jdbc.update(
            """INSERT INTO workflow_runs (run_id, tenant_id, workspace_id, app_id, workflow_id, mode, status, created_by, actor_kind, idempotency_key, fingerprint, input, definition,
                   current_step_id, compensable, step_executions, depth, error_code, error_message, compensation, cur_step_status, cur_wake_at, cur_approval_id,
                   process_failures, sweep_failures, not_before, last_swept_at, redacted_at, created_at, updated_at, finished_at, version, lease_owner, lease_until)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (tenant_id, app_id, workflow_id, created_by, mode, idempotency_key) DO NOTHING""",
            run.runId, run.tenantId, run.workspaceId, run.appId, run.workflowId, run.mode.name, run.status.name, run.createdBy.userId, run.createdBy.kind.name,
            run.idempotencyKey, run.fingerprint, json.writeValueAsString(run.input), definitions.encode(run.definition),
            run.currentStepId, json.writeValueAsString(json.createArrayNode().also { a -> run.compensable.forEach { a.add(it) } }), run.stepExecutions, run.depth,
            RunStoreSupport.cap(run.errorCode, 64), RunStoreSupport.cap(run.errorMessage, 1000), run.compensation.name,
            run.currentStep?.status?.name, run.currentStep?.wakeAt?.let { ts(it) }, run.currentStep?.approvalId,
            run.processFailures, run.sweepFailures, run.notBefore?.let { ts(it) }, run.lastSweptAt?.let { ts(it) }, run.redactedAt?.let { ts(it) },
            ts(run.createdAt), ts(run.updatedAt), run.finishedAt?.let { ts(it) }, run.version,
            RunStoreSupport.cap(run.leaseOwner, 64), run.leaseUntil?.let { ts(it) }
        )
        if (inserted == 1) {
            upsertSteps(run.runId, run.tenantId, run.definition, run.steps.values)
            CreateOutcome.Created(run)
        } else {
            val existing = jdbc.query(
                "$SELECT_RUN WHERE tenant_id = ? AND app_id = ? AND workflow_id = ? AND created_by = ? AND mode = ? AND idempotency_key = ?",
                { rs, _ -> mapRun(rs) }, run.tenantId, run.appId, run.workflowId, run.createdBy.userId, run.mode.name, run.idempotencyKey
            ).firstOrNull() ?: throw IllegalStateException("the run could not be created (identifier collision)")
            val full = withSteps(listOf(existing)).single()
            if (full.fingerprint == run.fingerprint) CreateOutcome.Existing(full) else CreateOutcome.KeyReused
        }
    }!!

    override fun get(tenantId: UUID, runId: UUID): WorkflowRun? =
        jdbc.query("$SELECT_RUN WHERE tenant_id = ? AND run_id = ?", { rs, _ -> mapRun(rs) }, tenantId, runId).firstOrNull()?.let { withSteps(listOf(it)).single() }

    override fun list(tenantId: UUID, appId: UUID?, limit: Int): List<WorkflowRun> {
        if (limit <= 0) return emptyList()
        val rows = if (appId == null)
            jdbc.query("$SELECT_RUN WHERE tenant_id = ? ORDER BY created_at DESC, run_id LIMIT ?", { rs, _ -> mapRun(rs) }, tenantId, limit.coerceAtMost(MAX_LIST))
        else
            jdbc.query("$SELECT_RUN WHERE tenant_id = ? AND app_id = ? ORDER BY created_at DESC, run_id LIMIT ?", { rs, _ -> mapRun(rs) }, tenantId, appId, limit.coerceAtMost(MAX_LIST))
        return withSteps(rows)
    }

    // ------------------------------------------------------------------------------------------------ compare-and-set

    override fun compareAndSet(expected: WorkflowRun, next: WorkflowRun): Boolean {
        require(next.runId == expected.runId && next.tenantId == expected.tenantId) { "a compare-and-set never changes the identity of a run" }
        return tx.execute {
            val cur = next.currentStep
            val n = jdbc.update(
                """UPDATE workflow_runs SET status = ?, current_step_id = ?, compensable = CAST(? AS jsonb), step_executions = ?, depth = ?, error_code = ?, error_message = ?,
                       compensation = ?, cur_step_status = ?, cur_wake_at = ?, cur_approval_id = ?, process_failures = ?, sweep_failures = ?, not_before = ?, last_swept_at = ?,
                       redacted_at = ?, updated_at = ?, finished_at = ?, lease_owner = ?, lease_until = ?, version = version + 1
                   WHERE tenant_id = ? AND run_id = ? AND version = ?""",
                next.status.name, next.currentStepId, json.writeValueAsString(json.createArrayNode().also { a -> next.compensable.forEach { a.add(it) } }), next.stepExecutions, next.depth,
                RunStoreSupport.cap(next.errorCode, 64), RunStoreSupport.cap(next.errorMessage, 1000), next.compensation.name,
                cur?.status?.name, cur?.wakeAt?.let { ts(it) }, cur?.approvalId, next.processFailures, next.sweepFailures, next.notBefore?.let { ts(it) },
                next.lastSweptAt?.let { ts(it) }, next.redactedAt?.let { ts(it) }, ts(next.updatedAt), next.finishedAt?.let { ts(it) },
                RunStoreSupport.cap(next.leaseOwner, 64), next.leaseUntil?.let { ts(it) },
                expected.tenantId, expected.runId, expected.version
            )
            if (n == 0) false
            else {
                upsertSteps(next.runId, next.tenantId, next.definition, next.steps.values.filter { expected.steps[it.stepId] != it })
                true
            }
        } == true
    }

    // ------------------------------------------------------------------------------------------------ sweeper

    override fun claimForSweep(now: Instant, limit: Int, perTenant: Int, minInterval: Duration, staleBefore: Instant, approvalInterval: Duration): List<WorkflowRun> {
        if (limit <= 0 || perTenant <= 0) return emptyList()
        val cap = limit.coerceAtMost(MAX_LIST)
        val candidates = jdbc.query(
            """SELECT * FROM (
                   SELECT ${RUN_COLUMNS.split(", ").joinToString(", ") { "r.$it" }},
                          row_number() OVER (PARTITION BY r.tenant_id ORDER BY r.last_swept_at NULLS FIRST, r.updated_at, r.run_id) AS rn
                   FROM workflow_runs r
                   WHERE (
                           (r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND r.cur_step_status IN ('WAITING', 'RETRY_WAIT') AND r.cur_approval_id IS NULL
                              AND r.cur_wake_at IS NOT NULL AND r.cur_wake_at <= ?)
                        OR (r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND r.cur_step_status = 'WAITING' AND r.cur_approval_id IS NOT NULL)
                        OR (r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND r.lease_until IS NOT NULL AND r.lease_until <= ?)
                        OR ((r.lease_until IS NULL OR r.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
                              AND (r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR r.compensation = 'IN_PROGRESS') AND r.updated_at < ?)
                         )
                     AND (r.not_before IS NULL OR r.not_before <= ?)
                     AND (r.last_swept_at IS NULL OR r.last_swept_at <= CAST(? AS timestamptz) - make_interval(secs => CASE
                            WHEN r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND r.cur_step_status = 'WAITING' AND r.cur_approval_id IS NOT NULL AND r.updated_at >= ? THEN ?::double precision
                            ELSE ?::double precision END))
               ) x WHERE x.rn <= ? ORDER BY x.last_swept_at NULLS FIRST, x.updated_at, x.run_id LIMIT ?""",
            { rs, _ -> mapRun(rs) },
            ts(now), ts(now), ts(staleBefore), ts(now), ts(now), ts(staleBefore), seconds(approvalInterval), seconds(minInterval), perTenant.coerceAtMost(MAX_LIST), cap
        )
        if (candidates.isEmpty()) return emptyList()
        // the fair interleaving across tenants is the reference semantics of the in-memory store (FairSelection); the stamp below is the atomic claim
        val chosen = FairSelection.pick(candidates, limit, perTenant) { it.tenantId }
        val stamped = ArrayList<WorkflowRun>(chosen.size)
        for (r in chosen) {
            val n = jdbc.update(
                "UPDATE workflow_runs SET last_swept_at = ?, version = version + 1 WHERE tenant_id = ? AND run_id = ? AND version = ?",
                ts(now), r.tenantId, r.runId, r.version
            )
            if (n == 1) stamped += r.copy(lastSweptAt = now, version = r.version + 1)       // a run that changed under us is simply skipped this pass
        }
        return withSteps(stamped)
    }

    override fun recordProcessFailure(tenantId: UUID, runId: UUID, notBefore: Instant): Int =
        jdbc.query(
            "UPDATE workflow_runs SET process_failures = process_failures + 1, not_before = ?, version = version + 1 WHERE tenant_id = ? AND run_id = ? RETURNING process_failures",
            { rs, _ -> rs.getInt(1) }, ts(notBefore), tenantId, runId
        ).firstOrNull() ?: -1

    override fun recordSweepResult(tenantId: UUID, runId: UUID, failed: Boolean, notBefore: Instant?) {
        if (failed) {
            jdbc.update(
                "UPDATE workflow_runs SET sweep_failures = sweep_failures + 1, not_before = ?, version = version + 1 WHERE tenant_id = ? AND run_id = ?",
                notBefore?.let { ts(it) }, tenantId, runId
            )
        } else {
            // the backoff was the sweeper's own: lift it; a process-failure backoff stays until it expires
            jdbc.update(
                """UPDATE workflow_runs SET sweep_failures = 0, not_before = CASE WHEN process_failures = 0 THEN NULL ELSE not_before END, version = version + 1
                   WHERE tenant_id = ? AND run_id = ? AND sweep_failures <> 0""",
                tenantId, runId
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ retention

    override fun redactFinished(olderThan: Instant, limit: Int, placeholder: JsonNode, now: Instant): Int {
        if (limit <= 0) return 0
        return tx.execute {
            val ids = jdbc.queryForList(
                """SELECT run_id FROM workflow_runs WHERE redacted_at IS NULL AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND compensation <> 'IN_PROGRESS'
                       AND COALESCE(finished_at, updated_at) < ? ORDER BY COALESCE(finished_at, updated_at) LIMIT ? FOR UPDATE SKIP LOCKED""",
                UUID::class.java, ts(olderThan), limit.coerceAtMost(MAX_LIST)
            )
            var n = 0
            for (id in ids) {
                n += jdbc.update("UPDATE workflow_runs SET input = CAST(? AS jsonb), redacted_at = ?, version = version + 1 WHERE run_id = ? AND redacted_at IS NULL", json.writeValueAsString(placeholder), ts(now), id)
                jdbc.update("UPDATE workflow_run_steps SET input = NULL, output = NULL WHERE run_id = ?", id)
            }
            n
        } ?: 0
    }

    override fun purgeFinished(olderThan: Instant, limit: Int): Int {
        if (limit <= 0) return 0
        return jdbc.update(
            """DELETE FROM workflow_runs WHERE run_id IN (
                   SELECT run_id FROM workflow_runs WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND compensation <> 'IN_PROGRESS' AND COALESCE(finished_at, updated_at) < ?
                   ORDER BY COALESCE(finished_at, updated_at) LIMIT ?)""",
            ts(olderThan), limit.coerceAtMost(MAX_LIST)
        )
    }

    // ------------------------------------------------------------------------------------------------ steps

    private fun upsertSteps(runId: UUID, tenantId: UUID, definition: com.systemwebstudio.logic.workflow.WorkflowDefinition, steps: Collection<StepState>) {
        for (s in steps) {
            val order = definition.steps.indexOfFirst { it.id == s.stepId }
            jdbc.update(
                """INSERT INTO workflow_run_steps (run_id, tenant_id, step_id, step_order, action_ref, status, attempt, visit, input, output, error_code, error_message, started_at,
                       finished_at, wake_at, approval_id, simulated, dry_run_level, compensated)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (run_id, step_id) DO UPDATE SET step_order = EXCLUDED.step_order, action_ref = EXCLUDED.action_ref, status = EXCLUDED.status, attempt = EXCLUDED.attempt,
                       visit = EXCLUDED.visit, input = EXCLUDED.input, output = EXCLUDED.output, error_code = EXCLUDED.error_code, error_message = EXCLUDED.error_message,
                       started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at, wake_at = EXCLUDED.wake_at, approval_id = EXCLUDED.approval_id,
                       simulated = EXCLUDED.simulated, dry_run_level = EXCLUDED.dry_run_level, compensated = EXCLUDED.compensated""",
                runId, tenantId, s.stepId, order.coerceAtLeast(0), definition.steps.getOrNull(order)?.actionRef, s.status.name, s.attempt, s.visit,
                s.input?.let { json.writeValueAsString(it) }, s.output?.let { json.writeValueAsString(it) },
                RunStoreSupport.cap(s.errorCode, 64), RunStoreSupport.cap(s.errorMessage, 1000), s.startedAt?.let { ts(it) }, s.finishedAt?.let { ts(it) }, s.wakeAt?.let { ts(it) },
                s.approvalId, s.simulated, s.dryRunLevel?.name, s.compensated
            )
        }
    }

    /** the runs with their steps (one query for all of them); [rows] come from [mapRun] with an empty step map */
    private fun withSteps(rows: List<WorkflowRun>): List<WorkflowRun> {
        if (rows.isEmpty()) return rows
        val byRun = HashMap<UUID, LinkedHashMap<String, StepState>>()
        val ids = rows.map { it.runId }
        val tenants = rows.map { it.tenantId }.toSet()
        val placeholders = ids.joinToString(",") { "?" }
        jdbc.query(
            "SELECT run_id, tenant_id, step_id, status, attempt, visit, input::text AS input, output::text AS output, error_code, error_message, started_at, finished_at, wake_at, approval_id, " +
                "simulated, dry_run_level, compensated FROM workflow_run_steps WHERE run_id IN ($placeholders) ORDER BY run_id, step_order, step_id",
            RowCallbackHandler { rs ->
                if (rs.uuid("tenant_id") in tenants) {
                    val s = mapStep(rs)
                    byRun.getOrPut(rs.uuid("run_id")) { LinkedHashMap() }[s.stepId] = s
                }
            },
            *ids.toTypedArray()
        )
        return rows.map { it.copy(steps = byRun[it.runId] ?: linkedMapOf()) }
    }

    private fun mapStep(rs: ResultSet) = StepState(
        stepId = rs.getString("step_id"), status = StepStatus.valueOf(rs.getString("status")), attempt = rs.getInt("attempt"),
        input = rs.getString("input")?.let { json.readTree(it) }, output = rs.getString("output")?.let { json.readTree(it) },
        errorCode = rs.getString("error_code"), errorMessage = rs.getString("error_message"),
        startedAt = rs.instantOrNull("started_at"), finishedAt = rs.instantOrNull("finished_at"), wakeAt = rs.instantOrNull("wake_at"), approvalId = rs.uuidOrNull("approval_id"),
        simulated = rs.getBoolean("simulated"), dryRunLevel = rs.getString("dry_run_level")?.let { DryRunLevel.valueOf(it) },
        compensated = rs.getBoolean("compensated"), visit = rs.getInt("visit")
    )

    // ------------------------------------------------------------------------------------------------ rows

    private fun mapRun(rs: ResultSet) = WorkflowRun(
        runId = rs.uuid("run_id"), tenantId = rs.uuid("tenant_id"), appId = rs.uuid("app_id"), workflowId = rs.getString("workflow_id"),
        definition = definitions.decode(rs.getString("definition")), mode = ExecutionMode.valueOf(rs.getString("mode")), status = WorkflowRunStatus.valueOf(rs.getString("status")),
        createdBy = ActionActor(rs.uuid("created_by"), ActorKind.valueOf(rs.getString("actor_kind"))), workspaceId = rs.uuidOrNull("workspace_id"),
        idempotencyKey = rs.getString("idempotency_key"), fingerprint = rs.getString("fingerprint"), input = json.readTree(rs.getString("input")),
        currentStepId = rs.getString("current_step_id"), steps = emptyMap(), compensable = json.readTree(rs.getString("compensable")).arrayItems().map { it.asString() },
        stepExecutions = rs.getInt("step_executions"), depth = rs.getInt("depth"), errorCode = rs.getString("error_code"), errorMessage = rs.getString("error_message"),
        compensation = CompensationState.valueOf(rs.getString("compensation")), createdAt = rs.instant("created_at"), updatedAt = rs.instant("updated_at"),
        finishedAt = rs.instantOrNull("finished_at"), processFailures = rs.getInt("process_failures"), sweepFailures = rs.getInt("sweep_failures"),
        notBefore = rs.instantOrNull("not_before"), lastSweptAt = rs.instantOrNull("last_swept_at"), redactedAt = rs.instantOrNull("redacted_at"), version = rs.getLong("version"),
        leaseOwner = rs.getString("lease_owner"), leaseUntil = rs.instantOrNull("lease_until")
    )

    private fun requireProjectOfTenant(tenantId: UUID, appId: UUID) {
        val ok = jdbc.queryForList(
            "SELECT 1 FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE p.id = ? AND w.tenant_id = ?", Int::class.javaObjectType, appId, tenantId
        ).isNotEmpty()
        if (!ok) throw IllegalStateException("the application does not belong to the tenant")
    }

    private fun seconds(d: Duration): Double = d.toMillis() / 1000.0

    companion object {
        private const val MAX_LIST = 1_000_000
        private const val RUN_COLUMNS = "run_id, tenant_id, workspace_id, app_id, workflow_id, mode, status, created_by, actor_kind, idempotency_key, fingerprint, input::text AS input, definition::text AS definition, " +
            "current_step_id, compensable::text AS compensable, step_executions, depth, error_code, error_message, compensation, process_failures, sweep_failures, not_before, last_swept_at, " +
            "redacted_at, created_at, updated_at, finished_at, version, lease_owner, lease_until"
        private val SELECT_RUN = "SELECT $RUN_COLUMNS FROM workflow_runs"
    }
}
