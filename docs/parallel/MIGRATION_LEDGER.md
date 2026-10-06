# Migration ledger (Flyway) — C0

Frozen 2026-10-06 on `integration/v2`. Single source for **which version number belongs to whom and in what order the others will be numbered**. `BOARD.md` ("Migration requests") holds each agent's request text; this file holds the numbers and the order. Only C0 edits it.

Rules (unchanged): only C0 allocates a number; one number = one task; migrations are applied in strictly ascending order; `spring.flyway.out-of-order` stays **off**; an agent creates its file only after its number is written here; an already-applied migration is never edited (a fix is a new migration).

## 1. Allocated (frozen)
| Version | File | Task / owner | Status | Gate to apply |
|---|---|---|---|---|
| base | `V1 … V25` | — | applied on every environment | — |
| **V26** | `V26__tenant_foundation.sql` | T2 · **C1** | ASSIGNED. Fixed version lives on `fix/c1-v2` (`0d7a527`, psql-verified by C1; **not Gradle-verified**). **Not on `integration/v2` yet.** | `MAC_INTEGRATION_CHECKLIST` steps 2 (C1 Gradle), 4–5 (import C1 + tests) and 8 (V26 verify on V25-shaped data) |
| **V27** | `V27__publish_configs.sql` | T7 · **C2** | ASSIGNED, **conditional**: C2 may create the file only after V26 is integrated **and** step 8 is green. Until then C2 keeps it unnumbered in its branch. Flag `app.publish-configs.enabled` stays OFF after V27 is applied. | step 9 (allocate/run V27) |

**V27 is the last number allocated.** The next number C0 will hand out is **V28, but none is allocated now.** Nobody may pick it, reserve it or write a file with it.

## 2. Dependency order of the migrations that do NOT have a number yet
Numbers are handed out one at a time, in this order, each only after the previous one is applied and verified on the Mac and its owner has submitted the request text in `BOARD.md`. Every table in every item: `tenant_id NOT NULL REFERENCES tenants(id)`; workspace-scoped tables use the composite FK `(workspace_id, tenant_id)`; `(tenant_id, created_at)` index where rows grow; a retention statement before a number is given.

| # | Item (working name) | Owner | Needs applied first | What it adds (summary) | Blocking questions before a number is given |
|---|---|---|---|---|---|
| 1 | **tenant resources** | C1 (+ C2 for its table) | V26 verified | `tenant_id` on existing tenant-owned things not covered by V26 (`templates`, the T5 scope columns) and C2's `tenant_ai_providers` (encrypted per-tenant provider config, uses `SecretsCrypto`) | which existing tables are tenant-owned vs platform-wide (templates: both?); `tenant_ai_providers` is needed before `app.tenant-ai.enabled` can ever be considered |
| 2 | **data foundation** | C3 | V26, item 1 | the ONE consolidated request: `data_sources`, `data_credentials` (ciphertext `v1:`; `data_sources` keeps only `credential_ref`), `data_queries`, `data_mutations`, `source_schemas`, `data_idempotency` (RESERVED/DONE/UNKNOWN + lease), `sync_jobs`, `sync_state`, `webhook_endpoints` (+ `webhook_replay` only if Redis is not used) | one request, not two (BOARD currently has an old sketch and the new one: the new one wins); retention for `data_idempotency` and sync state |
| 3 | **sharing / departments / groups** | C1 | items 1–2 (grants point at apps and data sources) | departments, groups, resource grants (T4 / T15), cross-tenant share policy (T16) | the `APP_SHARE` code moves from `PROJECT_MEMBERS` here; role matrix rows for explicit grants |
| 4 | **action / workflow persistence** | C4 | V26, items 2–3 (approvals address groups/departments; webhook triggers address endpoints by id) | the consolidated request in `BOARD.md`: `action_runs` (derived 43-char key), `workflow_runs`, `workflow_run_steps`, `approvals`, `schedules`, `schedule_executions`, `notification_deliveries`, `in_app_notifications` | retention per table (C4 D-C4-16), tenant FK on every table, DLQ/queue declarations are not a migration |
| 5 | **RLS** (T18) | C1 + C0 | items 1–4 (every tenant-owned table exists and is backfilled) | row-level policies keyed on a per-connection tenant setting; pooled connections must set/reset it (wiring) | proof that no table is left without `tenant_id`; performance on the hot lists; superuser/Flyway role bypass |
| 6 | **audit hardening** (T19) | C0 | item 5 | `audit_events` tenant column + append-only guarantees/privileges, retention note (the app never deletes audit rows) | append-only is already by convention (`audit/`); decide DB-level enforcement |
| 7 | **compat removal** `tenant_compat_removal` | C1 | V26, and the C1 condition below | drop `DEFAULT` on `workspaces.tenant_id`, drop the three `*_tenant_fill` triggers and `tenancy_fill_tenant_from_workspace()` | **Condition (C1 D-C1-13):** `TenantInsertPathsGrepTest` PENDING list is empty — every INSERT into `workspaces / workspace_members / projects / project_members` passes `tenant_id` (C2 entities, `AdminController`, two test files, identity/member inserts). Until then the scaffolding stays. May be moved earlier than item 5 only by a C0 decision recorded in `DECISIONS.md` |

Also **not requested, not numbered, tracked here so it is not forgotten**: (a) widening `deployments_visibility_check` for `TENANT` / `PRIVATE_LINK` (C0, tied to publish wiring; after V27); (b) whatever C5 needs (nothing today: the frontend has no migration).

## 3. How a number is allocated (checklist for C0)
1. The previous migration is applied on the Mac **and** its verification step in `MAC_INTEGRATION_CHECKLIST.md` is ticked.
2. The owner's request in `BOARD.md` names: tables, `tenant_id` handling, indexes, retention, rollback/undo story, tests.
3. C0 writes the number into §1 of this file, commits, and only then tells the owner. One number per commit.
