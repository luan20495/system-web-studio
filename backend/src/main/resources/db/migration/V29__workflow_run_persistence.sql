-- V29 workflow_run_persistence — durable Action / Workflow run state (C0, D-C0-21 / D-C0-23). Allocated in docs/parallel/MIGRATION_LEDGER.md.
-- Scope (reduced from the C4 request in docs/parallel/audit/FINAL-C4-runtime-design.md section 10, rules kept):
--   action_runs (idempotent run state of ONE LIVE action call), workflow_runs (the run row: pinned definition snapshot + state + sweeper columns),
--   workflow_run_steps (one row per step of a run, written in the same transaction as the compare-and-set of its run).
-- NOT here (still unnumbered): approvals, schedules, schedule_executions, notification_deliveries, in_app_notifications (they need the consolidated request), RLS.
-- Rules: every table has tenant_id NOT NULL REFERENCES tenants(id); a run that names an application also names its workspace and carries
-- (workspace_id, tenant_id) -> workspaces(id, tenant_id) and (workspace_id, app_id) -> projects(workspace_id, id), so a run can never point at another
-- tenant's workspace or at a project of another workspace. The JDBC adapters additionally insert only for an application that belongs to the tenant
-- (default deny). Steps hang off their run with (run_id, tenant_id) -> workflow_runs(run_id, tenant_id). No client idempotency key is stored: action_runs
-- keeps the derived key only (C4 IdempotencyKeys.derive); workflow_runs keeps the key the caller gave to start() (the run's own idempotency scope; step keys of a run are derived from the run id). Additive only: no existing table is touched.
-- C4 contract (audit/C4-V29-readiness-and-queue-contract.md section 9, D-C0-24): action_runs.mutating (A-1) and workflow_runs.lease_owner / lease_until (H-3) are part of V29
-- itself: V29 is not on integration/v2 yet, so no later migration is allocated for them.
-- Undo: docs/parallel/c0/undo/U29__workflow_run_persistence.sql (guarded). The code behind it is selected by app.workflow.run-store (jdbc is the default) and
-- gated by app.workflow.enabled (default false).

-- ---------------------------------------------------------------------------------------------------------------------------------------------
-- T13: run state + idempotency of one LIVE action call. TEST mode never reaches this table (it has no side effect, so there is nothing to de-duplicate).
-- app_id is NULLABLE: the key scope of C4 (RunKey) allows an action without an application; a NULL app is one scope, not "every app" (see the unique index).
-- ---------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE action_runs (
    run_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    workspace_id UUID,
    app_id UUID,
    action_id VARCHAR(128) NOT NULL,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    mode VARCHAR(8) NOT NULL DEFAULT 'LIVE',
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL,
    result JSONB,
    worker_id VARCHAR(64),
    -- the action changes state (ActionType.mutatesState), decided by the caller at begin(). It decides what an ABANDONED run becomes (AbandonedRuns): a mutating one may have
    -- written something, so the sweep records IDEMPOTENCY_OUTCOME_UNKNOWN (retryable = false); a non-mutating one becomes a retryable TIMEOUT. DEFAULT TRUE = the safe side.
    mutating BOOLEAN NOT NULL DEFAULT TRUE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT action_runs_status_check CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT action_runs_mode_check CHECK (mode = 'LIVE'),
    CONSTRAINT action_runs_attempt_check CHECK (attempt >= 1),
    CONSTRAINT action_runs_key_check CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT action_runs_result_check CHECK (result IS NULL OR (jsonb_typeof(result) = 'object' AND octet_length(result::text) <= 1048576)),
    -- a RUNNING run has no result and no end; a finished one has both (the result is what a replay answers with)
    CONSTRAINT action_runs_state_check CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND result IS NULL) OR
        (status <> 'RUNNING' AND finished_at IS NOT NULL AND result IS NOT NULL)
    ),
    -- an application and its workspace come together (or neither does)
    CONSTRAINT action_runs_scope_check CHECK ((app_id IS NULL) = (workspace_id IS NULL)),
    CONSTRAINT action_runs_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id),
    CONSTRAINT action_runs_project_fk FOREIGN KEY (workspace_id, app_id) REFERENCES projects (workspace_id, id)
);
-- the idempotency scope of C4: (tenant, app, action, USER, derived key). COALESCE makes "no app" ONE scope (a plain UNIQUE would treat every NULL as different)
CREATE UNIQUE INDEX action_runs_key_unique ON action_runs (tenant_id, COALESCE(app_id, '00000000-0000-0000-0000-000000000000'::uuid), action_id, user_id, idempotency_key);
CREATE INDEX action_runs_stale_idx ON action_runs (updated_at) WHERE status = 'RUNNING';
CREATE INDEX action_runs_retention_idx ON action_runs (finished_at) WHERE status <> 'RUNNING';

-- ---------------------------------------------------------------------------------------------------------------------------------------------
-- T14: workflow run. The row keeps the definition SNAPSHOT taken at start and the state; compare-and-set on `version`.
-- workspace_id is nullable because C4's WorkflowRun.workspaceId is; when it is present it must match the project (composite FK), and app_id must always be a project.
-- ---------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE workflow_runs (
    run_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    workspace_id UUID,
    app_id UUID NOT NULL REFERENCES projects (id),
    workflow_id VARCHAR(128) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by UUID NOT NULL,
    actor_kind VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    input JSONB NOT NULL,
    definition JSONB NOT NULL,
    current_step_id VARCHAR(64),
    compensable JSONB NOT NULL DEFAULT '[]'::jsonb,
    step_executions INTEGER NOT NULL DEFAULT 0,
    depth INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    compensation VARCHAR(16) NOT NULL DEFAULT 'NONE',
    -- denormalised from the current step so the sweeper can find due timers / awaited approvals without reading steps
    cur_step_status VARCHAR(16),
    cur_wake_at TIMESTAMPTZ,
    cur_approval_id UUID,
    -- poison isolation and sweeper backoff / rotation cursor
    process_failures INTEGER NOT NULL DEFAULT 0,
    sweep_failures INTEGER NOT NULL DEFAULT 0,
    not_before TIMESTAMPTZ,
    last_swept_at TIMESTAMPTZ,
    -- durable step lease (C4 H-3): who claimed the RUNNING step and until when. Written by the same compare-and-set as the claim; both NULL whenever no step is RUNNING.
    -- A lease that ran out marks the run abandoned for the sweeper; without a lease the old rule (updated_at older than the stale threshold) applies.
    lease_owner VARCHAR(64),
    lease_until TIMESTAMPTZ,
    -- retention stage 1: input and step payloads were replaced by a placeholder
    redacted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT workflow_runs_mode_check CHECK (mode IN ('LIVE', 'TEST')),
    CONSTRAINT workflow_runs_status_check CHECK (status IN ('PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT workflow_runs_actor_kind_check CHECK (actor_kind IN ('USER', 'SYSTEM', 'APP_TOKEN', 'SERVICE')),
    CONSTRAINT workflow_runs_compensation_check CHECK (compensation IN ('NONE', 'IN_PROGRESS', 'DONE', 'PARTIAL')),
    CONSTRAINT workflow_runs_cur_status_check CHECK (cur_step_status IS NULL OR cur_step_status IN ('PENDING', 'RUNNING', 'WAITING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT workflow_runs_counts_check CHECK (step_executions >= 0 AND depth >= 0 AND process_failures >= 0 AND sweep_failures >= 0 AND version >= 0),
    CONSTRAINT workflow_runs_definition_check CHECK (jsonb_typeof(definition) = 'object' AND jsonb_typeof(compensable) = 'array'),
    CONSTRAINT workflow_runs_payload_check CHECK (octet_length(input::text) <= 1048576 AND octet_length(definition::text) <= 1048576),
    -- only a finished run has an end time (every terminal transition of the engine sets it; retention falls back to updated_at if one ever does not)
    CONSTRAINT workflow_runs_finished_check CHECK (finished_at IS NULL OR status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- owner and expiry always come together
    CONSTRAINT workflow_runs_lease_check CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT workflow_runs_run_tenant_unique UNIQUE (run_id, tenant_id),
    CONSTRAINT workflow_runs_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id),
    CONSTRAINT workflow_runs_project_fk FOREIGN KEY (workspace_id, app_id) REFERENCES projects (workspace_id, id),
    -- C4's create() is idempotent on this scope: a TEST run and a LIVE run never share a key scope
    CONSTRAINT workflow_runs_key_unique UNIQUE (tenant_id, app_id, workflow_id, created_by, mode, idempotency_key)
);
CREATE INDEX workflow_runs_list_idx ON workflow_runs (tenant_id, app_id, created_at DESC);
CREATE INDEX workflow_runs_timers_idx ON workflow_runs (cur_wake_at) WHERE cur_step_status IN ('WAITING', 'RETRY_WAIT') AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
CREATE INDEX workflow_runs_approval_idx ON workflow_runs (cur_approval_id) WHERE cur_approval_id IS NOT NULL;
-- the fair claim of the sweeper: ORDER BY last_swept_at NULLS FIRST, updated_at ... FOR UPDATE SKIP LOCKED
CREATE INDEX workflow_runs_sweep_idx ON workflow_runs (last_swept_at NULLS FIRST, updated_at) WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS';
-- runs whose step lease can expire (the lease predicate of the sweeper)
CREATE INDEX workflow_runs_lease_idx ON workflow_runs (lease_until) WHERE lease_until IS NOT NULL;
CREATE INDEX workflow_runs_retention_idx ON workflow_runs (finished_at) WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND compensation <> 'IN_PROGRESS';
CREATE INDEX workflow_runs_redact_idx ON workflow_runs (finished_at) WHERE redacted_at IS NULL AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND compensation <> 'IN_PROGRESS';

-- ---------------------------------------------------------------------------------------------------------------------------------------------
-- One row per step of a run. Written in the SAME transaction as the compare-and-set of the run row (the adapter upserts the steps that changed).
-- `step_order` is the index of the step in the pinned definition (stable listing order); `action_ref` is informational, copied from the definition.
-- ---------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE workflow_run_steps (
    run_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    step_id VARCHAR(64) NOT NULL,
    step_order INTEGER NOT NULL DEFAULT 0,
    action_ref VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    visit INTEGER NOT NULL DEFAULT 1,
    input JSONB,
    output JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    wake_at TIMESTAMPTZ,
    approval_id UUID,
    simulated BOOLEAN NOT NULL DEFAULT FALSE,
    dry_run_level VARCHAR(16),
    compensated BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (run_id, step_id),
    CONSTRAINT workflow_run_steps_status_check CHECK (status IN ('PENDING', 'RUNNING', 'WAITING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT workflow_run_steps_dry_run_check CHECK (dry_run_level IS NULL OR dry_run_level IN ('NOT_EXECUTED', 'VALIDATED', 'SANDBOX')),
    CONSTRAINT workflow_run_steps_counts_check CHECK (attempt >= 0 AND visit >= 1 AND step_order >= 0),
    CONSTRAINT workflow_run_steps_run_fk FOREIGN KEY (run_id, tenant_id) REFERENCES workflow_runs (run_id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX workflow_run_steps_tenant_idx ON workflow_run_steps (tenant_id, run_id);
