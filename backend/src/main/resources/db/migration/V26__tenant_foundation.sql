-- V26 · T2 Tenant foundation (C1).  Granted by C0 for task T2 only.
-- Order: create tenants -> DEFAULT tenant -> add NULLABLE tenant_id -> backfill -> verify -> constraints / NOT NULL -> triggers.
-- Nothing is deleted or renamed; no workspace / project / user id changes.
-- Runbook (locks, transaction, large tables, removing compatibility): docs/parallel/c1/runbook-tenant-migration.md
-- Contract: docs/contracts/v2/tenant-permission.md.   Decisions: D-C1-01 (workspace belongs to ONE tenant), D-C1-02 (TENANT_ADMIN lives in tenant_members).
--
-- COMPATIBILITY OBJECTS (scaffolding, NOT part of the target model): the DEFAULT on workspaces.tenant_id and the three
-- *_tenant_fill triggers exist only so INSERTs written before tenancy (that do not pass tenant_id) keep working.
-- They are removed by the follow-up migration "tenant_compat_removal" (BOARD migration request, owner C1) once every INSERT path
-- passes tenant_id explicitly (proven by TenantInsertPathsGrepTest). Until then new code must pass tenant_id explicitly.
-- Flyway runs this whole file in ONE transaction: any failure (including the verification block) rolls everything back.

-- 1. tenants -------------------------------------------------------------------------------------------------------------
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenants_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT tenants_slug_check CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$'),
    -- 'default' is the seeded DEFAULT tenant and is allowed; names that would collide with routes/system identities are not
    CONSTRAINT tenants_slug_reserved_check CHECK (slug NOT IN ('admin', 'api', 'platform', 'system', 'root', 'www', 'tenant', 'tenants', 'null', 'undefined'))
);

CREATE FUNCTION tenancy_touch_updated_at() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenants_touch_updated_at BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION tenancy_touch_updated_at();

-- 2. DEFAULT tenant: every existing workspace belongs to it (fixed id so code and tests can refer to it) ------------------
INSERT INTO tenants (id, slug, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Default tenant')
ON CONFLICT (id) DO NOTHING;

-- 3. tenant_members: the source of truth for tenant roles (TENANT_ADMIN | MEMBER). A user may belong to several tenants. -----
CREATE TABLE tenant_members (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    user_id UUID NOT NULL REFERENCES users (id),
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users (id),
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT tenant_members_role_check CHECK (role IN ('TENANT_ADMIN', 'MEMBER'))
);
CREATE INDEX tenant_members_user_idx ON tenant_members (user_id) WHERE active;
CREATE INDEX tenant_members_tenant_role_idx ON tenant_members (tenant_id, role) WHERE active;

-- 4. add NULLABLE tenant_id (no NOT NULL yet) ----------------------------------------------------------------------------
ALTER TABLE workspaces ADD COLUMN tenant_id UUID;
ALTER TABLE workspace_members ADD COLUMN tenant_id UUID;
ALTER TABLE projects ADD COLUMN tenant_id UUID;
ALTER TABLE project_members ADD COLUMN tenant_id UUID;

-- 5. backfill (idempotent; the DEFAULT tenant for every workspace, children copy their workspace's tenant) -----------------
UPDATE workspaces SET tenant_id = '00000000-0000-0000-0000-000000000001' WHERE tenant_id IS NULL;
UPDATE workspace_members m SET tenant_id = w.tenant_id FROM workspaces w WHERE w.id = m.workspace_id AND m.tenant_id IS NULL;
UPDATE projects p SET tenant_id = w.tenant_id FROM workspaces w WHERE w.id = p.workspace_id AND p.tenant_id IS NULL;
UPDATE project_members m SET tenant_id = w.tenant_id FROM workspaces w WHERE w.id = m.workspace_id AND m.tenant_id IS NULL;
-- every existing user is a plain MEMBER of the DEFAULT tenant; nobody is promoted to TENANT_ADMIN automatically
INSERT INTO tenant_members (tenant_id, user_id, role)
SELECT '00000000-0000-0000-0000-000000000001', u.id, 'MEMBER' FROM users u
ON CONFLICT (tenant_id, user_id) DO NOTHING;

-- 6. verify before tightening: the migration fails (and rolls back) if anything is left without a tenant -----------------
DO $$
DECLARE missing BIGINT;
BEGIN
    SELECT (SELECT count(*) FROM workspaces WHERE tenant_id IS NULL)
         + (SELECT count(*) FROM workspace_members WHERE tenant_id IS NULL)
         + (SELECT count(*) FROM projects WHERE tenant_id IS NULL)
         + (SELECT count(*) FROM project_members WHERE tenant_id IS NULL)
      INTO missing;
    IF missing > 0 THEN
        RAISE EXCEPTION 'V26 backfill incomplete: % rows without tenant_id', missing;
    END IF;
END $$;

-- 7. constraints: NOT NULL, FK, composite FK that makes a child row's tenant always equal its workspace's tenant ------------
-- COMPATIBILITY: the DEFAULT below is removed by the follow-up "tenant_compat_removal" migration (see header).
ALTER TABLE workspaces ALTER COLUMN tenant_id SET DEFAULT '00000000-0000-0000-0000-000000000001';
COMMENT ON COLUMN workspaces.tenant_id IS 'COMPATIBILITY DEFAULT (V26): remove when every INSERT INTO workspaces passes tenant_id (migration tenant_compat_removal, owner C1).';
ALTER TABLE workspaces ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE workspaces ADD CONSTRAINT workspaces_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE workspaces ADD CONSTRAINT workspaces_id_tenant_unique UNIQUE (id, tenant_id);

ALTER TABLE workspace_members ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE workspace_members ADD CONSTRAINT workspace_members_tenant_fk
    FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id) ON UPDATE CASCADE;
ALTER TABLE projects ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE projects ADD CONSTRAINT projects_tenant_fk
    FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id) ON UPDATE CASCADE;
ALTER TABLE project_members ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE project_members ADD CONSTRAINT project_members_tenant_fk
    FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id) ON UPDATE CASCADE;

CREATE INDEX workspaces_tenant_idx ON workspaces (tenant_id);
CREATE INDEX workspace_members_tenant_idx ON workspace_members (tenant_id, user_id) WHERE active;
CREATE INDEX projects_tenant_idx ON projects (tenant_id, workspace_id) WHERE active;
CREATE INDEX project_members_tenant_idx ON project_members (tenant_id, user_id) WHERE active;

-- 8. triggers ------------------------------------------------------------------------------------------------------------
-- COMPATIBILITY (removed by tenant_compat_removal): fills tenant_id from the workspace when the caller did not pass one.
-- A tenant_id that IS passed must equal the workspace's tenant; anything else is a bug in the caller and is REJECTED
-- (never silently overwritten). On UPDATE OF workspace_id the old tenant_id is kept, so moving a row to a workspace of
-- another tenant is rejected too (a workspace itself moves between tenants with UPDATE workspaces SET tenant_id, which cascades).
CREATE FUNCTION tenancy_fill_tenant_from_workspace() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE ws_tenant UUID;
BEGIN
    SELECT w.tenant_id INTO ws_tenant FROM workspaces w WHERE w.id = NEW.workspace_id;
    IF NEW.tenant_id IS NULL THEN
        NEW.tenant_id := ws_tenant;       -- a missing workspace leaves NULL: NOT NULL / FK reject the row
    ELSIF ws_tenant IS NOT NULL AND NEW.tenant_id <> ws_tenant THEN
        RAISE EXCEPTION 'tenant_id % does not match the tenant % of workspace % (table %)', NEW.tenant_id, ws_tenant, NEW.workspace_id, TG_TABLE_NAME
            USING ERRCODE = '23514', CONSTRAINT = 'tenant_id_matches_workspace';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_members_tenant_fill BEFORE INSERT OR UPDATE OF workspace_id ON workspace_members
    FOR EACH ROW EXECUTE FUNCTION tenancy_fill_tenant_from_workspace();
CREATE TRIGGER projects_tenant_fill BEFORE INSERT OR UPDATE OF workspace_id ON projects
    FOR EACH ROW EXECUTE FUNCTION tenancy_fill_tenant_from_workspace();
CREATE TRIGGER project_members_tenant_fill BEFORE INSERT OR UPDATE OF workspace_id ON project_members
    FOR EACH ROW EXECUTE FUNCTION tenancy_fill_tenant_from_workspace();

-- invariant (kept after compatibility removal): an ACTIVE workspace member is a member of its tenant (role MEMBER).
-- Covers insert, re-activation, workspace change and a workspace moving to another tenant. An existing tenant_members row is never touched, so a user who was
-- deactivated at tenant level stays deactivated (AccessService then denies that tenant's workspaces).
CREATE FUNCTION tenancy_ensure_tenant_member() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO tenant_members (tenant_id, user_id, role) VALUES (NEW.tenant_id, NEW.user_id, 'MEMBER')
    ON CONFLICT (tenant_id, user_id) DO NOTHING;
    RETURN NULL;
END;
$$;

CREATE TRIGGER workspace_members_ensure_tenant_member AFTER INSERT OR UPDATE OF active, workspace_id, tenant_id ON workspace_members
    FOR EACH ROW WHEN (NEW.active) EXECUTE FUNCTION tenancy_ensure_tenant_member();
