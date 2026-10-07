# Runbook — V26 Tenant foundation (C1 · T2)

Scope: Flyway `V26__tenant_foundation.sql`. Contract: `docs/contracts/v2/tenant-permission.md` (§1, §7). Decisions: D-C1-01, D-C1-02, D-C1-12, D-C1-13.

## 1. What the migration does (in order)
1. Creates `tenants` and `tenant_members` (source of truth for TENANT_ADMIN / MEMBER; a user may belong to several tenants).
2. Inserts the DEFAULT tenant `00000000-0000-0000-0000-000000000001` (slug `default`).
3. Adds **nullable** `tenant_id` to `workspaces`, `workspace_members`, `projects`, `project_members`.
4. Backfills: every workspace -> DEFAULT tenant; child rows copy their workspace's tenant; every existing user -> `MEMBER` of the DEFAULT tenant (nobody becomes TENANT_ADMIN).
5. Verifies nothing is left NULL (`RAISE EXCEPTION` → whole migration rolls back).
6. NOT NULL, FK to `tenants`, `UNIQUE (id, tenant_id)` on workspaces, composite FKs `(workspace_id, tenant_id) -> workspaces(id, tenant_id) ON UPDATE CASCADE` (a child can never carry a different tenant than its workspace), indexes.
7. Triggers: `*_tenant_fill` (compatibility, see §4) and `workspace_members_ensure_tenant_member` (kept: active workspace member ⇒ tenant member, on insert / re-activation / workspace change / workspace tenant change).

No row is deleted, no workspace/project/user id changes, `audit_events` is untouched (append-only).

## 2. Transaction and lock behaviour (exact)
- Flyway runs the file in **one transaction**. Anything that fails — including the verification block — rolls back the whole file; the schema stays at V25.
- The `ALTER TABLE` statements take `ACCESS EXCLUSIVE` locks on `workspaces`, `workspace_members`, `projects`, `project_members` that are **held until the transaction commits**, so reads and writes on those four tables wait for the whole migration. `ADD COLUMN` (nullable, no default) is metadata-only; the cost is the four backfill `UPDATE`s (full-table rewrite of each table's rows), the `SET NOT NULL` scans and the FK validation scans. `CREATE INDEX` (non-concurrent) blocks writes on its table.
- Expected duration is proportional to row count; measured on the seeded test database: milliseconds. For pilot-size data (thousands of rows) run it in a quiet window. Take a backup first.
- **Large tables:** do not run V26 as-is. Split it into separate migrations and use `ADD CONSTRAINT ... NOT VALID` (short lock) followed by `VALIDATE CONSTRAINT` (only `SHARE UPDATE EXCLUSIVE`), backfill in id-range batches outside the migration, and `CREATE INDEX CONCURRENTLY` (needs `executeInTransaction=false` for that script). C0 decides before any large installation; this repo's installations are pilot-size.
- The migration is **not** safe to interrupt halfway by hand: use the Flyway transaction, never run pieces manually on production.

## 3. Pre-flight / post-flight
Pre: DB backup/snapshot; note `SELECT count(*)` of the four tables and `users` (must be equal after, `tenant_members` = `users`).
Post (all must be 0 / true):
```sql
SELECT count(*) FROM workspaces WHERE tenant_id <> '00000000-0000-0000-0000-000000000001';       -- 0 on first rollout
SELECT count(*) FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE p.tenant_id <> w.tenant_id;  -- 0
SELECT count(*) FROM users u LEFT JOIN tenant_members t ON t.user_id = u.id AND t.tenant_id = '00000000-0000-0000-0000-000000000001' WHERE t.user_id IS NULL; -- 0
```
Repeatable evidence: `docs/parallel/c1/verification/run-v26-checks.sh` applies V1–V25, seeds rows, applies V26 in one transaction, checks backfill, triggers (reject mismatching `tenant_id`, legacy insert), constraints and the undo script (guard refusals and clean undo). It needs only a PostgreSQL server.

## 4. Removing compatibility (owner: C1)
Compatibility objects — exist only so INSERTs written before tenancy keep working:
| Object | What it does | Remove by |
|---|---|---|
| `DEFAULT` on `workspaces.tenant_id` (comment on column) | a workspace INSERT without `tenant_id` lands in the DEFAULT tenant | follow-up migration `tenant_compat_removal`: `ALTER TABLE workspaces ALTER COLUMN tenant_id DROP DEFAULT` |
| triggers `workspace_members_tenant_fill`, `projects_tenant_fill`, `project_members_tenant_fill` + function `tenancy_fill_tenant_from_workspace()` | fill NULL `tenant_id` from the workspace; **reject** a non-NULL value that differs from the workspace's tenant | same migration: drop the three triggers and the function (the composite FKs keep enforcing "child tenant = workspace tenant") |
**Condition to remove** (all must hold): every `INSERT INTO workspaces / workspace_members / projects / project_members` in the code base passes `tenant_id` explicitly — including C2's `WorkspaceEntity` / `ProjectEntity` / member entities, `RegistrationController`, `BootstrapAdmin`, admin create-workspace, `LocalSeed`, SCIM, test fixtures — proven by `TenantInsertPathsGrepTest` (fails while a listed insert does not pass `tenant_id`; the test is a ratchet: its allow-list may only shrink). The invariant trigger `workspace_members_ensure_tenant_member` stays.
**Migration request:** `tenant_compat_removal` is recorded in `docs/parallel/BOARD.md` (REQUESTED, no number; C0 assigns). Until it lands, new code must pass `tenant_id` explicitly.

## 5. Rollback
Application-only rollback needs no schema rollback: older builds ignore the extra columns and the DEFAULT/trigger keep their INSERTs working.
Schema rollback: stop the app, run `docs/parallel/c1/undo/U26__tenant_foundation.sql` (one transaction), then delete the V26 row from `flyway_schema_history`.
The undo script **refuses to run (changes nothing)** as soon as any V2 / tenant data exists: a tenant other than DEFAULT, a workspace in another tenant, tenant_members rows that are not the plain V26 backfill (a TENANT_ADMIN, an inactive row, a `created_by`), a foreign key from any other table to `tenants` / `tenant_members`, or a Flyway version newer than 26. Past that point the supported path is a roll-forward fix or a backup restore — never dropping tenant data.
