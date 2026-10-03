-- Phase 7.2–7.4 (ADR 0008, 0010–0012): code projects (STATIC_APP) with a Git repository, sandbox builds and previews.

ALTER TABLE projects ADD COLUMN app_type VARCHAR(16) NOT NULL DEFAULT 'PAGE_SCHEMA';
ALTER TABLE projects ADD CONSTRAINT projects_app_type_check CHECK (app_type IN ('PAGE_SCHEMA', 'STATIC_APP'));

-- One platform-owned repository per code project (never owned by an employee).
CREATE TABLE repositories (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    provider VARCHAR(16) NOT NULL,
    owner VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    default_branch VARCHAR(64) NOT NULL DEFAULT 'main',
    head_sha VARCHAR(64),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT repositories_state_check CHECK (state IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT repositories_remote_unique UNIQUE (provider, owner, name)
);

-- A proposed change = one commit on its own branch; merged into main only after a green sandbox build.
CREATE TABLE code_changes (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    kind VARCHAR(8) NOT NULL,
    prompt_id UUID,
    branch VARCHAR(120) NOT NULL,
    base_sha VARCHAR(64) NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
    summary VARCHAR(500) NOT NULL,
    files JSONB NOT NULL,
    build_job_id UUID,
    preview_artifact_id UUID REFERENCES artifacts(id),
    preview_token VARCHAR(64),
    preview_expires_at TIMESTAMPTZ,
    error VARCHAR(1000),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT code_changes_kind_check CHECK (kind IN ('AI', 'EDIT')),
    CONSTRAINT code_changes_status_check CHECK (status IN ('BUILDING', 'READY', 'FAILED', 'MERGED', 'DISCARDED'))
);
CREATE INDEX code_changes_project_idx ON code_changes (project_id, created_at DESC);
CREATE UNIQUE INDEX code_changes_preview_token_idx ON code_changes (preview_token) WHERE preview_token IS NOT NULL;

-- Work for the build runner (a separate process; generated code never runs in the API).
CREATE TABLE build_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    purpose VARCHAR(16) NOT NULL,
    code_change_id UUID REFERENCES code_changes(id),
    deployment_id UUID REFERENCES deployments(id),
    commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    stage VARCHAR(16),
    runner VARCHAR(64),
    log TEXT,
    error VARCHAR(1000),
    scans JSONB,
    artifact_id UUID REFERENCES artifacts(id),
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    claimed_until TIMESTAMPTZ,
    CONSTRAINT build_jobs_purpose_check CHECK (purpose IN ('PREVIEW', 'PUBLISH')),
    CONSTRAINT build_jobs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX build_jobs_queue_idx ON build_jobs (status, queued_at);
ALTER TABLE code_changes ADD CONSTRAINT code_changes_build_job_fk FOREIGN KEY (build_job_id) REFERENCES build_jobs(id);

ALTER TABLE artifacts DROP CONSTRAINT artifacts_kind_check;
ALTER TABLE artifacts ADD CONSTRAINT artifacts_kind_check CHECK (kind IN ('STATIC_SITE', 'STATIC_APP'));
ALTER TABLE artifacts ADD COLUMN commit_sha VARCHAR(64);

-- Versions of a code project are commits on main (the existing Versions UI keeps working).
ALTER TABLE project_versions DROP CONSTRAINT project_versions_kind_check;
ALTER TABLE project_versions ADD CONSTRAINT project_versions_kind_check CHECK (kind IN ('INITIAL', 'PROMPT', 'EDIT', 'RESTORE', 'COMMIT'));
ALTER TABLE project_versions ADD COLUMN commit_sha VARCHAR(64);
ALTER TABLE deployments ADD COLUMN commit_sha VARCHAR(64);
