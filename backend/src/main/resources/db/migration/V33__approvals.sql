-- V33 - durable workflow approvals (FQ-WF-01 / FQ-WF-02). Number allocated by C0 (D-C0-61, docs/parallel/MIGRATION_LEDGER.md); DDL authored by C4 and moved here verbatim.
-- Additive only: one new table, no existing table is touched. Manual guarded undo: docs/parallel/c0/undo/U33__approvals.sql
--
-- approvals: the durable twin of InMemoryApprovalStore. One row per approval request of a workflow APPROVAL step (or a standalone request).
--  - approvers is the SNAPSHOT taken when the request was created (uuid[]), approver_specs the definition it came from; decisions / comments are small bounded documents
--    (at most MAX_APPROVERS decisions, MAX_COMMENT characters per comment) replaced by the same compare-and-set (version) as the status: a decision is never visible without its status.
--  - (tenant_id, idempotency_key) is unique: the engine requests with a key derived from (run, step), so a re-delivered or re-swept step finds the same approval, never a second one.
--  - no foreign key to workflow_runs on purpose: retention purges finished runs and finished approvals independently (purgeFinal keeps the approval of a run that is still active).
CREATE TABLE approvals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    app_id UUID REFERENCES projects (id),
    title VARCHAR(200) NOT NULL,
    requested_by UUID NOT NULL,                       -- no FK to users, like workflow_runs.created_by (V29): the actor is whoever C1 authenticated
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    approver_specs JSONB NOT NULL,
    approvers UUID[] NOT NULL,
    required_approvals INTEGER NOT NULL,
    allow_self_approval BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    decisions JSONB NOT NULL DEFAULT '[]'::jsonb,
    comments JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_run_id UUID,
    source_step_id VARCHAR(128),
    idempotency_key VARCHAR(160),
    finished_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT approvals_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT approvals_required_check CHECK (required_approvals >= 1),
    CONSTRAINT approvals_decisions_check CHECK (jsonb_typeof(decisions) = 'array' AND jsonb_typeof(comments) = 'array'),
    -- a PENDING approval is not finished; a final one is
    CONSTRAINT approvals_finished_check CHECK ((status = 'PENDING') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX approvals_idempotency_unique ON approvals (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX approvals_due_idx ON approvals (expires_at) WHERE status = 'PENDING';
CREATE INDEX approvals_inbox_idx ON approvals USING GIN (approvers) WHERE status = 'PENDING';
CREATE INDEX approvals_source_idx ON approvals (tenant_id, source_run_id) WHERE source_run_id IS NOT NULL;
CREATE INDEX approvals_retention_idx ON approvals (finished_at) WHERE status <> 'PENDING';
