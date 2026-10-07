-- UNDO for V26 — NOT a Flyway migration (lives under docs/, never runs automatically). MANUAL, in one transaction, application STOPPED.
-- It removes the tenancy scaffolding ONLY while the database still holds nothing but V26 backfill data. The guard below aborts
-- (nothing is changed) as soon as ANY V2 / tenant data exists, because dropping tenant_id / tenant_members then would destroy it:
--   * a tenant other than DEFAULT exists, or the DEFAULT tenant is not ACTIVE
--   * any workspace belongs to a tenant other than DEFAULT
--   * tenant_members holds anything the V26 backfill / trigger would not create (a TENANT_ADMIN, an inactive row, a created_by,
--     a row of a tenant other than DEFAULT)
--   * any table other than the four V26 tables + tenant_members references tenants(id) or tenant_members (V27+ / V2 features)
--   * Flyway has a successful migration newer than 26
-- Once it aborts the only supported path is a roll-FORWARD fix (new migration) or a restore from backup.
-- After a successful run, delete the V26 row from flyway_schema_history (or run `flyway repair` after removing the V26 file).
BEGIN;

DO $$
DECLARE n BIGINT; later BIGINT;
BEGIN
    SELECT count(*) INTO n FROM tenants WHERE id <> '00000000-0000-0000-0000-000000000001';
    IF n > 0 THEN RAISE EXCEPTION 'U26 refused: % tenant(s) other than DEFAULT exist', n; END IF;
    SELECT count(*) INTO n FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001' AND status <> 'ACTIVE';
    IF n > 0 THEN RAISE EXCEPTION 'U26 refused: the DEFAULT tenant was changed'; END IF;
    SELECT count(*) INTO n FROM workspaces WHERE tenant_id <> '00000000-0000-0000-0000-000000000001';
    IF n > 0 THEN RAISE EXCEPTION 'U26 refused: % workspace(s) belong to a non-DEFAULT tenant', n; END IF;
    SELECT count(*) INTO n FROM tenant_members
     WHERE role <> 'MEMBER' OR NOT active OR created_by IS NOT NULL OR tenant_id <> '00000000-0000-0000-0000-000000000001';
    IF n > 0 THEN RAISE EXCEPTION 'U26 refused: tenant_members holds % row(s) created by V2 administration (roles / deactivations)', n; END IF;
    SELECT count(*) INTO n
      FROM pg_constraint c
     WHERE c.contype = 'f' AND c.confrelid IN ('tenants'::regclass, 'tenant_members'::regclass)
       AND c.conrelid NOT IN ('workspaces'::regclass, 'tenant_members'::regclass);
    IF n > 0 THEN RAISE EXCEPTION 'U26 refused: % foreign key(s) from other tables reference tenants / tenant_members (V2 data exists)', n; END IF;
    IF to_regclass('flyway_schema_history') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM flyway_schema_history WHERE success AND version ~ ''^[0-9]+$'' AND version::int > 26' INTO later;
        IF later > 0 THEN RAISE EXCEPTION 'U26 refused: % migration(s) newer than V26 are applied', later; END IF;
    END IF;
END $$;

DROP TRIGGER IF EXISTS workspace_members_ensure_tenant_member ON workspace_members;
DROP TRIGGER IF EXISTS workspace_members_tenant_fill ON workspace_members;
DROP TRIGGER IF EXISTS projects_tenant_fill ON projects;
DROP TRIGGER IF EXISTS project_members_tenant_fill ON project_members;
DROP TRIGGER IF EXISTS tenants_touch_updated_at ON tenants;
DROP FUNCTION IF EXISTS tenancy_ensure_tenant_member();
DROP FUNCTION IF EXISTS tenancy_fill_tenant_from_workspace();
DROP FUNCTION IF EXISTS tenancy_touch_updated_at();
ALTER TABLE project_members DROP CONSTRAINT IF EXISTS project_members_tenant_fk;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS projects_tenant_fk;
ALTER TABLE workspace_members DROP CONSTRAINT IF EXISTS workspace_members_tenant_fk;
ALTER TABLE workspaces DROP CONSTRAINT IF EXISTS workspaces_id_tenant_unique;
ALTER TABLE workspaces DROP CONSTRAINT IF EXISTS workspaces_tenant_fk;
DROP INDEX IF EXISTS project_members_tenant_idx;
DROP INDEX IF EXISTS projects_tenant_idx;
DROP INDEX IF EXISTS workspace_members_tenant_idx;
DROP INDEX IF EXISTS workspaces_tenant_idx;
ALTER TABLE project_members DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE projects DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE workspace_members DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE workspaces DROP COLUMN IF EXISTS tenant_id;
DROP TABLE IF EXISTS tenant_members;
DROP TABLE IF EXISTS tenants;
COMMIT;
