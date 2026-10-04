-- Stage J: server runtime (ADR 0017), per-app databases (ADR 0018), project secrets, connector catalog, app kinds (stage K).

-- What the user builds; app_type stays the build model (PAGE_SCHEMA = page schema, STATIC_APP = repository).
ALTER TABLE projects ADD COLUMN app_kind VARCHAR(24);
UPDATE projects SET app_kind = CASE WHEN app_type = 'STATIC_APP' THEN 'SOURCE_WEB_APP' ELSE 'WEBSITE_STATIC' END;
ALTER TABLE projects ALTER COLUMN app_kind SET NOT NULL;
ALTER TABLE projects ALTER COLUMN app_kind SET DEFAULT 'WEBSITE_STATIC';
ALTER TABLE projects ADD CONSTRAINT projects_app_kind_check CHECK (app_kind IN ('WEBSITE_STATIC', 'SOURCE_WEB_APP', 'DASHBOARD', 'INTERNAL_TOOL', 'WORKFLOW', 'SERVER_APP'));

-- One runtime per server app: its database/role on the apps DB server (password encrypted), the app token (hash) used for connector calls,
-- and pointers to the deployment being served and the one being started.
CREATE TABLE app_runtimes (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    db_name VARCHAR(63) NOT NULL UNIQUE,
    db_role VARCHAR(63) NOT NULL UNIQUE,
    db_password_enc TEXT NOT NULL,
    app_token_enc TEXT NOT NULL,
    app_token_hash VARCHAR(64) NOT NULL UNIQUE,
    current_deployment_id UUID,
    desired_deployment_id UUID,
    last_logs TEXT,
    logs_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE server_deployments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    version INTEGER NOT NULL,
    artifact_id UUID NOT NULL REFERENCES artifacts(id),
    commit_sha VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error VARCHAR(1000),
    routes JSONB NOT NULL DEFAULT '[]'::jsonb,
    requested_by UUID REFERENCES users(id),
    rollback_of UUID REFERENCES server_deployments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    stopped_at TIMESTAMPTZ,
    CONSTRAINT server_deployments_status_check CHECK (status IN ('PENDING', 'STARTING', 'RUNNING', 'FAILED', 'SUPERSEDED', 'STOPPED')),
    CONSTRAINT server_deployments_version_unique UNIQUE (project_id, version)
);
CREATE INDEX server_deployments_project_idx ON server_deployments (project_id, created_at DESC);

-- Write-only project secrets: AES-256-GCM with the server's master key; values are never returned by the API, never put in a repository
-- and never sent to an AI model. Names are environment-variable names.
CREATE TABLE project_secrets (
    project_id UUID NOT NULL REFERENCES projects(id),
    name VARCHAR(64) NOT NULL,
    value_enc TEXT NOT NULL,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, name),
    CONSTRAINT project_secrets_name_check CHECK (name ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

-- Connector catalog (admin-managed). A connector is an approved HTTPS API: base URL, allowed operations, and an auth header whose value is
-- an encrypted secret injected by the API's connector proxy. Apps never see the secret; metadata (no secret) is searchable by the AI.
CREATE TABLE connectors (
    key VARCHAR(40) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    base_url VARCHAR(500) NOT NULL,
    auth_header VARCHAR(64),
    auth_value_enc TEXT,
    operations JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT connectors_key_check CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
    CONSTRAINT connectors_status_check CHECK (status IN ('APPROVED', 'DISABLED'))
);
CREATE TABLE project_connectors (
    project_id UUID NOT NULL REFERENCES projects(id),
    connector_key VARCHAR(40) NOT NULL REFERENCES connectors(key),
    granted_by UUID REFERENCES users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, connector_key)
);
