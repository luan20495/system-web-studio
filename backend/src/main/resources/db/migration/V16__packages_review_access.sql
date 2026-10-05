-- Source-app completion: approved package catalog, dependency requests, review-before-merge, IDE access.

-- Admin-approved npm packages (ADR 0013). resolved = transitive closure (name/version) from the RESOLVE job; findings = OSV results.
CREATE TABLE approved_packages (
    name VARCHAR(214) PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version_range VARCHAR(100) NOT NULL DEFAULT 'latest',
    pinned_version VARCHAR(64),
    note VARCHAR(500) NOT NULL DEFAULT '',
    resolved JSONB,
    findings JSONB,
    risk_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    requested_by UUID REFERENCES users(id),
    decided_by UUID REFERENCES users(id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT approved_packages_status_check CHECK (status IN ('PENDING', 'RESOLVING', 'ALLOWED', 'DENIED'))
);

-- A user's (or the AI's) request to add an approved package to a code project.
CREATE TABLE dependency_requests (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    package VARCHAR(214) NOT NULL,
    spec VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'LOCKING',
    build_job_id UUID,
    code_change_id UUID REFERENCES code_changes(id),
    error VARCHAR(1000),
    requested_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dependency_requests_status_check CHECK (status IN ('LOCKING', 'COMMITTED', 'REJECTED', 'FAILED'))
);
CREATE INDEX dependency_requests_project_idx ON dependency_requests (project_id, created_at DESC);

-- Build jobs gain non-build work (LOCK: lockfile for a dependency request; RESOLVE: closure of a package being approved).
ALTER TABLE build_jobs DROP CONSTRAINT build_jobs_purpose_check;
ALTER TABLE build_jobs ADD CONSTRAINT build_jobs_purpose_check CHECK (purpose IN ('PREVIEW', 'PUBLISH', 'LOCK', 'RESOLVE'));
ALTER TABLE build_jobs ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE build_jobs ALTER COLUMN commit_sha DROP NOT NULL;
ALTER TABLE build_jobs ADD COLUMN input JSONB;
ALTER TABLE build_jobs ADD COLUMN result JSONB;

-- Review before merge (per workspace, overridable per project; NULL on a project = inherit).
ALTER TABLE workspaces ADD COLUMN merge_policy VARCHAR(24) NOT NULL DEFAULT 'AUTO_MERGE_ALLOWED';
ALTER TABLE projects ADD COLUMN merge_policy VARCHAR(24);
ALTER TABLE workspaces ADD CONSTRAINT workspaces_merge_policy_check CHECK (merge_policy IN ('AUTO_MERGE_ALLOWED', 'REVIEW_REQUIRED'));
ALTER TABLE projects ADD CONSTRAINT projects_merge_policy_check CHECK (merge_policy IS NULL OR merge_policy IN ('AUTO_MERGE_ALLOWED', 'REVIEW_REQUIRED'));
ALTER TABLE code_changes ADD COLUMN approved_by UUID REFERENCES users(id);
ALTER TABLE code_changes ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE code_changes ADD COLUMN review_comment VARCHAR(1000);

-- Read-only clone access for external IDEs: one Forgejo account per Studio user, tokens revocable.
CREATE TABLE git_access (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    git_username VARCHAR(64) NOT NULL UNIQUE,
    token_name VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    token_created_at TIMESTAMPTZ
);

ALTER TABLE code_changes ALTER COLUMN kind TYPE VARCHAR(16);
ALTER TABLE code_changes DROP CONSTRAINT code_changes_kind_check;
ALTER TABLE code_changes ADD CONSTRAINT code_changes_kind_check CHECK (kind IN ('AI', 'EDIT', 'DEPENDENCY', 'DESIGN'));

-- Repositories a user was given read access to (removed again by the cleanup job when project access ends).
CREATE TABLE git_access_repos (
    user_id UUID NOT NULL REFERENCES users(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, project_id)
);
