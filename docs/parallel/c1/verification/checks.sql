-- Behaviour checks for V26 on a database that already holds V25 rows (seed25.sql). Every line prints "ok: ..." or a BAD marker; the runner fails on BAD.
\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned
CREATE OR REPLACE FUNCTION pg_temp.expect_fail(sql text, needle text) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE sql;
  RETURN 'BAD no error: ' || sql;
EXCEPTION WHEN OTHERS THEN
  IF position(needle in SQLERRM) > 0 THEN RETURN 'ok: rejected (' || needle || ')'; ELSE RETURN 'BAD wrong error: ' || SQLERRM; END IF;
END $$;
CREATE OR REPLACE FUNCTION pg_temp.expect_true(label text, cond boolean) RETURNS text LANGUAGE sql AS $$ SELECT CASE WHEN cond THEN 'ok: ' || label ELSE 'BAD ' || label END $$;

-- backfill (counts of seed25.sql: 2 workspaces, 3 workspace_members, 2 projects, 1 project_member, 4 users)
SELECT pg_temp.expect_true('all workspaces in DEFAULT tenant', (SELECT count(*) FROM workspaces WHERE tenant_id <> '00000000-0000-0000-0000-000000000001') = 0);
SELECT pg_temp.expect_true('no NULL tenant_id in children', (SELECT count(*) FROM workspace_members WHERE tenant_id IS NULL)+(SELECT count(*) FROM projects WHERE tenant_id IS NULL)+(SELECT count(*) FROM project_members WHERE tenant_id IS NULL) = 0);
SELECT pg_temp.expect_true('row counts unchanged', (SELECT count(*) FROM workspaces)=2 AND (SELECT count(*) FROM workspace_members)=3 AND (SELECT count(*) FROM projects)=2 AND (SELECT count(*) FROM project_members)=1 AND (SELECT count(*) FROM users)=4);
SELECT pg_temp.expect_true('every user is a DEFAULT-tenant MEMBER, nobody TENANT_ADMIN', (SELECT count(*) FROM tenant_members WHERE role='MEMBER' AND active)=4 AND (SELECT count(*) FROM tenant_members WHERE role='TENANT_ADMIN')=0);
SELECT pg_temp.expect_true('ids unchanged', (SELECT count(*) FROM workspaces WHERE id IN ('22222222-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000002'))=2);

INSERT INTO tenants (id,slug,name) VALUES ('44444444-0000-0000-0000-000000000001','other','Other');
-- legacy inserts without tenant_id
INSERT INTO workspaces (id,name,slug) VALUES ('22222222-0000-0000-0000-000000000009','WS9','ws9');
INSERT INTO workspace_members (workspace_id,user_id,role) VALUES ('22222222-0000-0000-0000-000000000009','11111111-0000-0000-0000-000000000004','EDITOR');
INSERT INTO projects (id,workspace_id,name,owner_user_id) VALUES ('33333333-0000-0000-0000-000000000008','22222222-0000-0000-0000-000000000009','P8','11111111-0000-0000-0000-000000000004');
INSERT INTO project_members (workspace_id,project_id,user_id,role) VALUES ('22222222-0000-0000-0000-000000000009','33333333-0000-0000-0000-000000000008','11111111-0000-0000-0000-000000000004','OWNER');
SELECT pg_temp.expect_true('legacy insert: every table filled with DEFAULT tenant',
  (SELECT tenant_id FROM workspaces WHERE id='22222222-0000-0000-0000-000000000009')='00000000-0000-0000-0000-000000000001'
  AND (SELECT tenant_id FROM workspace_members WHERE workspace_id='22222222-0000-0000-0000-000000000009')='00000000-0000-0000-0000-000000000001'
  AND (SELECT tenant_id FROM projects WHERE id='33333333-0000-0000-0000-000000000008')='00000000-0000-0000-0000-000000000001'
  AND (SELECT tenant_id FROM project_members WHERE project_id='33333333-0000-0000-0000-000000000008')='00000000-0000-0000-0000-000000000001');
-- explicit matching tenant_id
INSERT INTO projects (id,workspace_id,name,owner_user_id,tenant_id) VALUES ('33333333-0000-0000-0000-000000000009','22222222-0000-0000-0000-000000000009','P9','11111111-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_true('explicit matching tenant_id accepted', (SELECT count(*) FROM projects WHERE id='33333333-0000-0000-0000-000000000009')=1);
-- explicit mismatching tenant_id => REJECTED on every child table
SELECT pg_temp.expect_fail($$INSERT INTO projects (id,workspace_id,name,owner_user_id,tenant_id) VALUES (gen_random_uuid(),'22222222-0000-0000-0000-000000000009','PX','11111111-0000-0000-0000-000000000004','44444444-0000-0000-0000-000000000001')$$, 'does not match the tenant');
SELECT pg_temp.expect_fail($$INSERT INTO workspace_members (workspace_id,user_id,role,tenant_id) VALUES ('22222222-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000004','VIEWER','44444444-0000-0000-0000-000000000001')$$, 'does not match the tenant');
SELECT pg_temp.expect_fail($$INSERT INTO project_members (workspace_id,project_id,user_id,role,tenant_id) VALUES ('22222222-0000-0000-0000-000000000001','33333333-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001','VIEWER','44444444-0000-0000-0000-000000000001')$$, 'does not match the tenant');
-- a row cannot be moved into a workspace of another tenant; a workspace move cascades
UPDATE workspaces SET tenant_id='44444444-0000-0000-0000-000000000001' WHERE id='22222222-0000-0000-0000-000000000002';
SELECT pg_temp.expect_fail($$UPDATE projects SET workspace_id='22222222-0000-0000-0000-000000000002' WHERE id='33333333-0000-0000-0000-000000000001'$$, 'does not match the tenant');
SELECT pg_temp.expect_true('workspace move cascades to members', (SELECT count(*) FROM workspace_members WHERE workspace_id='22222222-0000-0000-0000-000000000002' AND tenant_id='44444444-0000-0000-0000-000000000001')=1);
-- constraints
SELECT pg_temp.expect_fail($$INSERT INTO workspaces (id,name,slug,tenant_id) VALUES (gen_random_uuid(),'x','nx1',NULL)$$, 'null value');
SELECT pg_temp.expect_fail($$INSERT INTO workspaces (id,name,slug,tenant_id) VALUES (gen_random_uuid(),'x','nx2',gen_random_uuid())$$, 'foreign key');
SELECT pg_temp.expect_fail($$INSERT INTO tenants (id,slug,name) VALUES (gen_random_uuid(),'admin','x')$$, 'tenants_slug_reserved_check');
SELECT pg_temp.expect_fail($$INSERT INTO tenants (id,slug,name) VALUES (gen_random_uuid(),'Bad Slug','x')$$, 'tenants_slug_check');
SELECT pg_temp.expect_fail($$INSERT INTO tenant_members (tenant_id,user_id,role) VALUES ('44444444-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001','WORKSPACE_ADMIN')$$, 'tenant_members_role_check');
-- workspace member => tenant member (an inactive member creates no tenant row; re-activation does; a tenant-level deactivation is never reverted)
SELECT pg_temp.expect_true('inactive workspace member: no tenant row in the other tenant', (SELECT count(*) FROM tenant_members WHERE tenant_id='44444444-0000-0000-0000-000000000001' AND user_id='11111111-0000-0000-0000-000000000002')=0);
UPDATE workspace_members SET active=true WHERE workspace_id='22222222-0000-0000-0000-000000000002';
SELECT pg_temp.expect_true('re-activation creates the MEMBER row', (SELECT count(*) FROM tenant_members WHERE tenant_id='44444444-0000-0000-0000-000000000001' AND user_id='11111111-0000-0000-0000-000000000002' AND role='MEMBER' AND active)=1);
UPDATE tenant_members SET active=false WHERE tenant_id='44444444-0000-0000-0000-000000000001' AND user_id='11111111-0000-0000-0000-000000000002';
UPDATE workspace_members SET active=true, role='EDITOR' WHERE workspace_id='22222222-0000-0000-0000-000000000002';
SELECT pg_temp.expect_true('tenant-level deactivation is not reverted by the trigger', (SELECT NOT active FROM tenant_members WHERE tenant_id='44444444-0000-0000-0000-000000000001' AND user_id='11111111-0000-0000-0000-000000000002'));
UPDATE tenants SET name='Other2' WHERE id='44444444-0000-0000-0000-000000000001';
SELECT pg_temp.expect_true('updated_at maintained', (SELECT updated_at > created_at FROM tenants WHERE id='44444444-0000-0000-0000-000000000001'));
