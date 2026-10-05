-- Identity / RBAC
ALTER TABLE users
    ADD COLUMN display_name VARCHAR(160),
    ADD COLUMN system_admin BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE workspace_members DROP CONSTRAINT workspace_members_role_check;
UPDATE workspace_members SET role = 'EDITOR' WHERE role = 'MEMBER';
ALTER TABLE workspace_members
    ADD CONSTRAINT workspace_members_role_check
    CHECK (role IN ('WORKSPACE_ADMIN', 'EDITOR', 'PUBLISHER', 'VIEWER'));

-- Project settings
ALTER TABLE projects
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN framework VARCHAR(32) NOT NULL DEFAULT 'nextjs',
    ADD COLUMN auth_mode VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN domain VARCHAR(253),
    ADD COLUMN custom_domain VARCHAR(253),
    ADD COLUMN deployment_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK',
    ADD COLUMN deployment_target VARCHAR(120),
    ADD CONSTRAINT projects_framework_check CHECK (framework IN ('nextjs', 'react', 'static')),
    ADD CONSTRAINT projects_auth_mode_check CHECK (auth_mode IN ('NONE', 'LOCAL', 'OIDC')),
    ADD CONSTRAINT projects_deployment_mode_check CHECK (deployment_mode IN ('MOCK', 'SELF_HOSTED', 'CLOUD'));

-- Append-only audit log
CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    workspace_id UUID,
    project_id UUID,
    actor_id UUID,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(48) NOT NULL,
    resource_id VARCHAR(64),
    old_value JSONB,
    new_value JSONB,
    ip_address VARCHAR(64),
    user_agent VARCHAR(400),
    request_id VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_events_workspace_time_idx ON audit_events(workspace_id, created_at DESC, id);
CREATE INDEX audit_events_project_time_idx ON audit_events(project_id, created_at DESC, id);
CREATE INDEX audit_events_actor_time_idx ON audit_events(actor_id, created_at DESC);
CREATE INDEX audit_events_action_idx ON audit_events(action);

CREATE FUNCTION audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
CREATE TRIGGER audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_immutable();
