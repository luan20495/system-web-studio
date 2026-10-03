-- Lockdown + quotas + retention.

-- Admin overrides of policy values (defaults come from configuration); every change is audited.
CREATE TABLE system_settings (
    key VARCHAR(100) PRIMARY KEY,
    value VARCHAR(500) NOT NULL,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Build accounting: who asked, where, and what it cost in measured terms.
ALTER TABLE build_jobs ADD COLUMN workspace_id UUID;
ALTER TABLE build_jobs ADD COLUMN requested_by UUID REFERENCES users(id);
ALTER TABLE build_jobs ADD COLUMN duration_ms BIGINT;
ALTER TABLE build_jobs ADD COLUMN cpu_ms BIGINT;
ALTER TABLE build_jobs ADD COLUMN artifact_bytes BIGINT;
ALTER TABLE build_jobs ADD COLUMN source_bytes BIGINT;
UPDATE build_jobs b SET workspace_id = p.workspace_id FROM projects p WHERE p.id = b.project_id;
CREATE INDEX build_jobs_user_idx ON build_jobs (requested_by, queued_at DESC);
CREATE INDEX build_jobs_workspace_idx ON build_jobs (workspace_id, queued_at DESC);

-- Builds refused by policy (quota, size, disabled) — shown in Admin → Build Policy.
CREATE TABLE build_rejections (
    id UUID PRIMARY KEY,
    project_id UUID REFERENCES projects(id),
    workspace_id UUID,
    user_id UUID REFERENCES users(id),
    reason_code VARCHAR(48) NOT NULL,
    detail VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX build_rejections_created_idx ON build_rejections (created_at DESC);

-- Storage accounting and retention.
ALTER TABLE repositories ADD COLUMN size_bytes BIGINT;
ALTER TABLE repositories ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE repositories ADD COLUMN delete_after TIMESTAMPTZ;
ALTER TABLE repositories DROP CONSTRAINT repositories_state_check;
ALTER TABLE repositories ADD CONSTRAINT repositories_state_check CHECK (state IN ('ACTIVE', 'ARCHIVED', 'PENDING_DELETE', 'DELETED'));
ALTER TABLE artifacts ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX artifacts_project_idx ON artifacts (project_id, created_at DESC);
