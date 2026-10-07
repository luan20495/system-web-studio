-- V27 publish_configs (T7 · C2) — persistent per-project publish POLICY. Allocated by C0 in docs/parallel/MIGRATION_LEDGER.md.
-- Source: DDL draft in docs/parallel/audit/C2-T7-publish-configs.md §4, corrected by C0 to the ledger rules (the draft had no tenant):
--   * tenant_id NOT NULL REFERENCES tenants(id), and the composite FK (workspace_id, tenant_id) -> workspaces(id, tenant_id) so a row's tenant
--     can never differ from its workspace's tenant (same device as V26);
--   * (tenant_id, created_at) index; created_at added for it.
-- Additive only: no existing table is changed. The served state stays in deployments/server_deployments (rollback never reads this table).
-- Undo: docs/parallel/c2/undo/U27__publish_configs.sql (guarded). The API behind it is OFF: app.publish-configs.enabled=false.

CREATE TABLE publish_configs (
    project_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    mode VARCHAR(16) NOT NULL DEFAULT 'STATIC',
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    requires_auth BOOLEAN NOT NULL DEFAULT FALSE,
    cache_seconds INTEGER,
    public_data_approved BOOLEAN NOT NULL DEFAULT FALSE,
    link_token_hash VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1,
    updated_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT publish_configs_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id),
    CONSTRAINT publish_configs_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id) ON UPDATE CASCADE,
    CONSTRAINT publish_configs_mode_check CHECK (mode IN ('STATIC', 'DYNAMIC', 'SERVER_APP')),
    CONSTRAINT publish_configs_visibility_check CHECK (visibility IN ('PRIVATE', 'TENANT', 'PUBLIC', 'PRIVATE_LINK')),
    CONSTRAINT publish_configs_cache_check CHECK (cache_seconds IS NULL OR cache_seconds BETWEEN 0 AND 86400),
    CONSTRAINT publish_configs_link_check CHECK (visibility <> 'PRIVATE_LINK' OR link_token_hash IS NOT NULL),
    CONSTRAINT publish_configs_public_auth_check CHECK (visibility <> 'PUBLIC' OR requires_auth = FALSE)
);

CREATE INDEX publish_configs_tenant_idx ON publish_configs (tenant_id, created_at);

-- Backfill: only projects that have ever been published; visibility mirrors what they are served as today. Nothing becomes TENANT/PRIVATE_LINK.
INSERT INTO publish_configs (project_id, workspace_id, tenant_id, mode, visibility, revision)
SELECT p.id, p.workspace_id, p.tenant_id,
       CASE WHEN p.app_kind = 'SERVER_APP' THEN 'SERVER_APP' ELSE 'STATIC' END,
       CASE WHEN p.site_visibility = 'PUBLIC' THEN 'PUBLIC' ELSE 'PRIVATE' END,
       1
FROM projects p
WHERE EXISTS (SELECT 1 FROM deployments d WHERE d.project_id = p.id)
   OR EXISTS (SELECT 1 FROM server_deployments s WHERE s.project_id = p.id);
