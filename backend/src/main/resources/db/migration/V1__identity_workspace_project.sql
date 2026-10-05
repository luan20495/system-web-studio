CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT workspace_members_role_check CHECK (role IN ('WORKSPACE_ADMIN', 'MEMBER'))
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(160) NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    project_access_policy VARCHAR(32) NOT NULL DEFAULT 'PRIVATE_MEMBERS',
    site_visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    revision BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT projects_workspace_id_id_unique UNIQUE (workspace_id, id),
    CONSTRAINT projects_access_policy_check CHECK (project_access_policy = 'PRIVATE_MEMBERS'),
    CONSTRAINT projects_site_visibility_check CHECK (site_visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT projects_revision_check CHECK (revision >= 0)
);

CREATE TABLE project_members (
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT project_members_role_check CHECK (role IN ('VIEWER', 'EDITOR', 'PUBLISHER', 'OWNER')),
    CONSTRAINT project_members_workspace_project_fk FOREIGN KEY (workspace_id, project_id)
        REFERENCES projects(workspace_id, id),
    CONSTRAINT project_members_workspace_user_fk FOREIGN KEY (workspace_id, user_id)
        REFERENCES workspace_members(workspace_id, user_id)
);

CREATE INDEX workspace_members_active_user_idx ON workspace_members(user_id, workspace_id) WHERE active;
CREATE INDEX projects_workspace_updated_idx ON projects(workspace_id, updated_at DESC, id) WHERE active;
CREATE INDEX project_members_active_user_idx ON project_members(user_id, project_id) WHERE active;

INSERT INTO workspaces (id, name, slug)
VALUES ('00000000-0000-0000-0000-000000000001', 'Local Workspace', 'local');