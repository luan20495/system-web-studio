-- Component registry
CREATE TABLE components (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(24) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    latest_version VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT components_category_check CHECK (category IN ('TEMPLATE', 'BLOCK', 'BUSINESS', 'UI', 'PRIMITIVE', 'CUSTOM')),
    CONSTRAINT components_status_check CHECK (status IN ('ACTIVE', 'DEPRECATED', 'BLOCKED'))
);

CREATE TABLE component_versions (
    component_id VARCHAR(64) NOT NULL REFERENCES components(id),
    version VARCHAR(16) NOT NULL,
    props_schema JSONB NOT NULL,
    actions JSONB NOT NULL DEFAULT '[]',
    permissions JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (component_id, version),
    CONSTRAINT component_versions_status_check CHECK (status IN ('ACTIVE', 'DEPRECATED', 'BLOCKED'))
);

-- Current page schema (one per project) and immutable history
CREATE TABLE page_schemas (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    workspace_id UUID NOT NULL,
    schema JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT page_schemas_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)
);

CREATE TABLE prompts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    text VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT prompts_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)
);
CREATE INDEX prompts_project_time_idx ON prompts(project_id, created_at DESC, id);

CREATE TABLE project_versions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    schema_snapshot JSONB NOT NULL,
    kind VARCHAR(16) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    prompt_id UUID REFERENCES prompts(id),
    restored_from_version_id UUID REFERENCES project_versions(id),
    source_revision BIGINT,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT project_versions_unique UNIQUE (project_id, version_number),
    CONSTRAINT project_versions_kind_check CHECK (kind IN ('INITIAL', 'PROMPT', 'EDIT', 'RESTORE')),
    CONSTRAINT project_versions_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)
);
CREATE INDEX project_versions_project_idx ON project_versions(project_id, version_number DESC);

CREATE TABLE prompt_runs (
    id UUID PRIMARY KEY,
    prompt_id UUID NOT NULL REFERENCES prompts(id),
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    intent VARCHAR(48) NOT NULL,
    status VARCHAR(16) NOT NULL,
    schema_patch JSONB NOT NULL DEFAULT '[]',
    assistant_message VARCHAR(2000) NOT NULL,
    version_id UUID REFERENCES project_versions(id),
    registry_reuse INTEGER,
    expected_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT prompt_runs_status_check CHECK (status IN ('UPDATED', 'NO_CHANGE', 'UNSUPPORTED', 'FAILED'))
);
CREATE INDEX prompt_runs_prompt_idx ON prompt_runs(prompt_id);

CREATE TABLE component_usage (
    version_id UUID NOT NULL REFERENCES project_versions(id),
    project_id UUID NOT NULL,
    component_id VARCHAR(64) NOT NULL,
    component_version VARCHAR(16) NOT NULL,
    usage_count INTEGER NOT NULL,
    PRIMARY KEY (version_id, component_id, component_version),
    FOREIGN KEY (component_id, component_version) REFERENCES component_versions(component_id, version)
);
CREATE INDEX component_usage_component_idx ON component_usage(component_id, component_version);

-- Assets (metadata; bytes live in object storage)
CREATE TABLE assets (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT assets_status_check CHECK (status IN ('PENDING', 'READY', 'DELETED')),
    CONSTRAINT assets_size_check CHECK (size_bytes > 0),
    CONSTRAINT assets_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)
);
CREATE INDEX assets_project_idx ON assets(project_id, status, created_at DESC);

-- Publishing
CREATE TABLE deployments (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    version_id UUID NOT NULL REFERENCES project_versions(id),
    requested_by UUID NOT NULL REFERENCES users(id),
    visibility VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    url VARCHAR(512),
    error VARCHAR(1000),
    provider VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT deployments_visibility_check CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT deployments_status_check CHECK (status IN
        ('QUEUED', 'POLICY_CHECK', 'SECURITY_CHECK', 'BUILDING', 'DEPLOYING', 'RUNNING', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT deployments_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)
);
CREATE INDEX deployments_project_idx ON deployments(project_id, created_at DESC);
CREATE INDEX deployments_status_idx ON deployments(status, updated_at) WHERE status NOT IN ('RUNNING', 'FAILED', 'ROLLED_BACK');

CREATE TABLE deployment_events (
    id UUID PRIMARY KEY,
    deployment_id UUID NOT NULL REFERENCES deployments(id),
    status VARCHAR(24) NOT NULL,
    message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX deployment_events_deployment_idx ON deployment_events(deployment_id, created_at);

CREATE TABLE idempotency_keys (
    scope_key VARCHAR(300) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
