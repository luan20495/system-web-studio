-- Stage I: SCIM 2.0 provisioning (off unless SCIM_ENABLED=true).

ALTER TABLE users DROP CONSTRAINT users_auth_source_check;
ALTER TABLE users ADD CONSTRAINT users_auth_source_check CHECK (auth_source IN ('LOCAL', 'OIDC', 'SCIM'));
-- the IdP's id for the user (SCIM externalId); an OIDC login whose subject equals it signs in to this account
ALTER TABLE users ADD COLUMN scim_external_id VARCHAR(255);
CREATE UNIQUE INDEX users_scim_external_id_unique ON users (scim_external_id) WHERE scim_external_id IS NOT NULL;

CREATE TABLE scim_groups (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL UNIQUE,
    external_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE scim_group_members (
    group_id UUID NOT NULL REFERENCES scim_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY (group_id, user_id)
);
-- explicit admin mapping: members of an IdP group get a WORKSPACE role. There is no mapping to system admin, by design.
CREATE TABLE scim_group_mappings (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES scim_groups(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    role VARCHAR(32) NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT scim_group_mappings_role_check CHECK (role IN ('WORKSPACE_ADMIN', 'EDITOR', 'PUBLISHER', 'VIEWER')),
    CONSTRAINT scim_group_mappings_unique UNIQUE (group_id, workspace_id)
);
-- memberships created by SCIM are removed by SCIM; manual memberships are never touched
ALTER TABLE workspace_members ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE workspace_members ADD CONSTRAINT workspace_members_source_check CHECK (source IN ('MANUAL', 'SCIM'));
