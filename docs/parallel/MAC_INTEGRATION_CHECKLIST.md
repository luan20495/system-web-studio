# Mac integration checklist (C0) — the real Gradle / Docker gate

Frozen 2026-10-06. **Nothing below has been run.** Every box is empty on purpose: until a step is ticked **with the output recorded in `BASELINE.md` (section "Re-baseline (macOS)")**, nothing on `integration/v2` is "passing", "verified" or "production-ready". Run the steps **in order**; a red step stops the run (fix it, re-run the step, only then go on).

Prerequisites on the Mac: JDK 21 (`/opt/homebrew/opt/openjdk@21`), Docker Desktop running, Node ≥ 22 with the repo's `node_modules` for macOS, `psql` client, clean `git status` on the branch you are about to test. Repo: `/Users/hoangluan/code/HBL` (branch `integration/v2`); owner worktrees: `/Users/hoangluan/code/xweb-c1` (`fix/c1-v2`), `xweb-c2` (`fix/c2-v2`), `xweb-c3` (`agent/c3-data`), `xweb-c4` (`agent/c4-workflow`), `xweb-c5` (`agent/c5-web`).

Rules: **(1)** never edit a test, a flag or `build.gradle.kts` to get a green; **(2)** a failure in an agent's code goes back to the owner branch as a commit titled `fix(c<N>): …` (C0 may author it on the owner's behalf), never a silent patch on `integration/v2`; **(3)** all V2 flags stay OFF (`WEB_SECURITY_CONFIG.md` §1) in every step except where a step says "test profile only"; **(4)** record each step: date · `git rev-parse --short HEAD` · exact command · counts (tests / failures / skipped) · verdict.

Record template (copy per step into `BASELINE.md`): `S<N> · <date> · <sha> · <command> · tests=<n> fail=<n> skipped=<n> · PASS|FAIL · <notes>`.

---

### S1 — Baseline `integration/v2` (C0 prep commit only)
- [ ] `cd /Users/hoangluan/code/HBL && git checkout integration/v2 && git status --short` (clean)
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin --console=plain`
- [ ] `./gradlew test --console=plain`
- [ ] `cd .. && npx tsc --noEmit && NEXT_PUBLIC_API_MODE=http npx next build` (legacy UI; or `scripts/check.sh` for the full set)
**Expected:** compile succeeds on Kotlin 2.2.21 (this is the first compile of the C0 patches: `Gateway.kt`, `SecurityConfiguration.kt`, `AdminController.kt`, `wiring/WebOrigins.kt`). Tests = the previous baseline (192, 3 skipped) **+ 17 new** (`PublicAddressTests` 9, `WebhookSecurityTests` 5, `WebOriginsTests` 3) → **209 run, 0 failures, 3 skipped**; any other number must be explained before going on. `AuthSecurityTests` (CORS) unchanged and green. Frontend typecheck/build unchanged.
**If red:** fix on `integration/v2` as `fix(c0): …`; the most likely suspects are the `PathPatternRequestMatcher` import/overload and `ignoringRequestMatchers(RequestMatcher)` in `SecurityConfiguration`.
- [ ] Manual (needs the stack, may be postponed to S18): `curl -i -X POST http://127.0.0.1:8080/api/v1/webhooks/data/x` → **404** (not 401/403); same `GET` → **401**; `OPTIONS` with `Origin: http://evil.example` → **403**.

### S2 — C1 Gradle on its own branch
- [ ] `cd /Users/hoangluan/code/xweb-c1 && git rev-parse --short HEAD` = `0d7a527` (else re-read the log, update the manifest)
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin --console=plain`
- [ ] `./gradlew test --tests 'com.systemwebstudio.tenancy.*' --tests 'com.systemwebstudio.isolation.*' --console=plain`
- [ ] `./gradlew test --console=plain` (full)
**Expected:** compiles; C1's tests pass (`AdminTransferOwnershipSelfGrantSpec` = 1 skipped, it is `@Disabled`); list every **base** test that fails because SYSTEM_ADMIN no longer has god mode (R-03) — they are expected and are rewritten at S5, not hidden. Compile errors → `fix(c1): …` on `fix/c1-v2`.

### S3 — C2 Gradle on its own branch
- [ ] `cd /Users/hoangluan/code/xweb-c2 && git rev-parse --short HEAD` = `99d8169`
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin --console=plain`
- [ ] `./gradlew test --tests 'com.systemwebstudio.app.definition.*' --tests 'com.systemwebstudio.component.*' --tests 'com.systemwebstudio.template.*' --tests 'com.systemwebstudio.ai.planner.*' --tests 'com.systemwebstudio.ai.tenant.*' --tests 'com.systemwebstudio.project.publishconfig.*' --console=plain`
- [ ] `./gradlew test --console=plain` (full: `PromptApiTests`, `VersionApiTests`, `LibraryCatalogTests`, `PublishApiTests`, `StaticSiteTests` exercise the live paths C2 modified)
**Expected:** compile on Kotlin 2.2 / Jackson 3 (C2 never compiled against them: watch `JsonNode.propertyNames()`, `asString()`, `deepCopy()` casts); all green; **no existing suite changes outcome**. `ContractConformanceTests` reads `../docs/contracts/v2/tenant-permission.md` — it is skipped silently if the file is not found; on this worktree it is (the worktree has `docs/contracts/v2` from `c59604b`): check it ran (non-zero count), 14 codes.

### S4 — Import C1 into `integration/v2`
- [ ] Follow `V2_IMPORT_MANIFEST.md` §1 and §2: `git checkout integration/v2`, verify `git diff c59604b integration/v2 -- <each overwritten path>` is empty, `git checkout fix/c1-v2 -- <paths>`, run the manifest's check command (must print nothing), merge `BOARD/BLOCKERS/DECISIONS` by hand.
- [ ] `git commit` → `feat(c1): import tenancy/access/V26/tests from fix/c1-v2@0d7a527 (paths only)`
**Expected:** exactly one new migration file (`V26__tenant_foundation.sql`); no `V27+`; `AdminController.kt`, `SecurityConfiguration.kt`, `Gateway.kt`, `application.yml` untouched by the import commit.

### S5 — Test after C1
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin && ./gradlew test --console=plain`
- [ ] Each base test that fails because of the removed god mode is rewritten in **its own commit** with the reason (`test: <name> needs explicit workspace membership (D-C1-11)`) — never by switching `app.tenancy.system-admin-business-access` on.
**Expected:** 0 failures; skipped = 3 + 1 (`AdminTransferOwnershipSelfGrantSpec`, still disabled). `TenantMigrationFromV25Tests`, `TenantFoundationMigrationTests`, `TenantInsertPathsGrepTest` (PENDING list = known B-C1-16 entries), `PrivilegeEscalationTests`, `TenantAccessLegacyFlagTests` (flag ON vs OFF) all pass. `WebhookSecurityTests`, `PublicAddressTests` still green.

### S6 — Import C2
- [ ] `V2_IMPORT_MANIFEST.md` §3. **Read the diff of every modified live file** (`SchemaService`, `SchemaPatchEngine`, `SchemaOperation`, `SchemaCommitService`, `PromptController`, `Library`, `Templates`, `ChatProviders`, `ExternalLLMProvider`) before committing.
- [ ] `feat(c2): import AppDefinition V2, planner, tenant AI, publish-config model, templates from fix/c2-v2@99d8169 (paths only)`
**Expected:** check command prints nothing; **no** `V27` file; conformance fixtures under `backend/src/test/resources/app-definition/`.

### S7 — Test after C2
- [ ] `./gradlew compileKotlin compileTestKotlin && ./gradlew test --console=plain`
- [ ] Flags OFF check: start the app with defaults (S18 can do this) and confirm there is **no** `/api/v1/**/plan`, no tenant-AI route, no publish-config route, and only `GET /api/v1/component-metadata` is new.
**Expected:** 0 failures; the C2 groups of S3 pass **on top of C1**; `ContractConformanceTests` ran. Existing live-path suites unchanged.

### S8 — V26 verify (real PostgreSQL, V25-shaped data)
- [ ] `PGHOST=localhost PGUSER=postgres PGPASSWORD=… docs/parallel/c1/verification/run-v26-checks.sh` (needs a reachable Postgres, e.g. `docker run -d -p 55432:5432 -e POSTGRES_PASSWORD=x postgres:17.6`, `PGPORT=55432`)
- [ ] `./gradlew test --tests '*TenantMigrationFromV25Tests' --tests '*TenantFoundationMigrationTests'`
- [ ] Post-flight SQL from `docs/parallel/c1/runbook-tenant-migration.md` §3 on a copy of a **real** dump (row counts equal, `tenant_members` = `users`, mismatching tenant rows = 0). Note the migration duration and the lock window.
**Expected:** script ends `ALL V26 CHECKS PASSED` (≥ 20 `ok` lines, no `BAD`); undo guards refuse in every guarded case; V26 re-applies after undo. **This tick unlocks V27.**

### S9 — Allocate / run V27 `publish_configs`
- [ ] C0 reviews the DDL in `docs/parallel/audit/C2-T7-publish-configs.md` §4 against the ledger rules (`tenant_id NOT NULL REFERENCES tenants(id)`, composite FK to the workspace, `(tenant_id, created_at)` index, no change to existing tables). Anything missing → back to C2.
- [ ] C2 creates exactly one file `V27__publish_configs.sql` (on `fix/c2-v2`, or C0 on its behalf), imported path-scoped as `feat(c2): V27 publish_configs`.
- [ ] `./gradlew test --tests 'com.systemwebstudio.project.publishconfig.*'` then the full suite.
- [ ] `psql … -c "select version, success from flyway_schema_history order by installed_rank desc limit 3"` → 27 then 26, both `success = t`; `outOfOrder` not set.
**Expected:** V27 applies after V26 in order; `app.publish-configs.enabled` is still **false** in every shared config (a test profile may set it true for the publish-config tests only). Nothing else gets a number (`MIGRATION_LEDGER.md` §1).

### S10 — C3 Gradle (trial import: C3 does not compile alone)
`agent/c3-data` imports `tenancy.TenantContext`/`ActorKind` from C1, which its branch does not contain, so its own worktree cannot build. Use a throwaway branch.
- [ ] `git checkout -b try/c3 integration/v2` (S4–S9 are in) and import `agent/c3-data@02fe1d1` per `V2_IMPORT_MANIFEST.md` §4 (paths only, no commit to `integration/v2` yet)
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin --console=plain`
- [ ] `./gradlew test --tests 'com.systemwebstudio.data.*' --console=plain` (`PostgresConnectorIntegrationTests` needs Docker)
**Expected:** compiles; **`AddressRangeSpecTests` passes** (it fails by design on the old `PublicAddress`; passing proves the C0 patch matches C3's spec); `SqlGuard` `U&"…"` cases refused; cache-ticket, idempotency (RESERVED/DONE/UNKNOWN), webhook replay tests green; inertness grep (`@Component|@Service|@RestController|@Configuration|@Bean|@Scheduled` in `data/**`) prints nothing.

### S11 — Import C3
- [ ] `git checkout integration/v2 && git merge --ff-only try/c3` (same commit as S10: `feat(c3): import data platform from agent/c3-data@02fe1d1 (paths only)`), hand-merge the shared docs (rename the duplicate `D-008`), `git branch -d try/c3`
**Expected:** `git diff --stat` shows only `data/**`, its tests and `docs/parallel/agents/C3_*.md` + the three hand-merged docs.

### S12 — Test after C3
- [ ] `./gradlew test --console=plain` (full)
**Expected:** 0 failures; the only difference from S9 is the new `data.*` tests; no new route, no new bean (`curl` of the route list / actuator mappings unchanged).

### S13 — C4 Gradle on its own branch
- [ ] `cd /Users/hoangluan/code/xweb-c4 && git rev-parse --short HEAD` = `6fff346`
- [ ] `cd backend && ./gradlew compileKotlin compileTestKotlin && ./gradlew test --tests 'com.systemwebstudio.logic.*' --console=plain`
**Expected:** compiles alone (it imports no other module); 277 harness tests were never a Gradle result — record the real count; `ActionDataPathTests` (architecture) passes.

### S14 — Import C4
- [ ] `V2_IMPORT_MANIFEST.md` §5 → `feat(c4): import action/workflow runtime from agent/c4-workflow@6fff346 (paths only)`; hand-merge shared docs.
**Expected:** only `logic/**`, its tests, two audit docs, and the hand-merged docs.

### S15 — Test after C4
- [ ] `./gradlew test --console=plain` (full)
**Expected:** 0 failures; the only difference from S12 is the new `logic.*` tests; inertness grep on `logic/**` prints nothing. **Wiring (W-01…W-07) is NOT part of S4–S15**: it follows after S16 as separate commits (`backend/src/wiring-skeleton/README.md`).

### S16 — Full backend
- [ ] `./gradlew clean test --console=plain` · `./gradlew bootJar`
- [ ] Un-disable `AdminTransferOwnershipSelfGrantSpec` (delete the `@Disabled` line) **in its own commit**, run `./gradlew test --tests '*AdminTransferOwnershipSelfGrantSpec'` → **403 `SELF_GRANT_FORBIDDEN`**, owner unchanged, no `project_members` row.
**Expected:** all pass; record the totals in `BASELINE.md`: this is the first number that may be called the V2 backend baseline (still not "production-ready").

### S17 — Frontend
- [ ] `npm ci && npx tsc --noEmit` · `NEXT_PUBLIC_API_MODE=mock NEXT_DIST_DIR=.next-check STUDIO_BASE_PATH=/system-web-studio npx next build` · `NEXT_PUBLIC_API_MODE=http NEXT_DIST_DIR=.next-check-http npx next build` · render worker `tsc`
- [ ] **17b — C5 monorepo import (separate commits, only after S16 is green; ADR 0022):** (1) packages + shims, (2) `apps/*` + root `package.json`/lockfile/`tsconfig.json`/`next.config.ts`/`proxy.ts` merged by hand. Then `npm ci && npm run typecheck:all && npm run typecheck:apps && npm run build:apps && npm run build && NEXT_PUBLIC_API_MODE=http npm run build`; delete `lib/app-definition/contract-mirror.ts`; check each `apps/*/next.config.ts` rewrites `/api`, `/oauth2`, `/login/oauth2`; switch the CORS default to the three origins and update `AuthSecurityTests` in the same commit (`WEB_SECURITY_CONFIG.md` §3).
**Expected:** identical results to the legacy baseline for the legacy app; the three apps build; no `contract-mirror.ts`; `packages/types` carries the `MIRROR of docs/contracts/v2/…` header.

### S18 — Docker Compose / local stack
- [ ] `scripts/run-local.sh` (or `docker compose --profile full up -d --wait` + API + UI) · `scripts/smoke-test.sh`
- [ ] `curl -s localhost:8080/actuator/health/readiness` → UP (db, redis, rabbit, minio); `psql … "select version from flyway_schema_history where success order by installed_rank desc limit 2"` → 27, 26
- [ ] Route/flag audit with every default: no planner / tenant-AI / publish-config / data / workflow route; webhook POST → 404; `app.tenancy.system-admin-business-access` resolves to **false**
**Expected:** smoke test passes; no new open port or service; flags OFF.

### S19 — E2E
- [ ] `node e2e/factory-flow.mjs` · `node e2e/public-flow.mjs` · `node e2e/a11y.mjs` · `node e2e/admin-setup-flow.mjs` · `node e2e/code-flow.mjs` · `node e2e/runtime-flow.mjs` · `scripts/sso-up.sh` then `node e2e/sso-flow.mjs` · `node e2e/pages-mock.mjs`
**Expected:** same pass set as `docs/IMPLEMENTATION_STATUS.md` records. A script that signs in as `local.admin` (SYSTEM_ADMIN) and then works in a workspace may now get 404: fix the **seed membership** (add the admin as a member), do not turn the flag on. SSO: only one portal's redirect URI works until B-C0-WEB-01 is fixed (`WEB_SECURITY_CONFIG.md` §4).

### S20 — Migration V1 → V2 on realistic data
- [ ] Restore the latest real backup into a throwaway Postgres (`scripts/restore-postgres.sh` / `scripts/test-backup-restore.sh` pattern, never the live DB); record row counts of `users, workspaces, workspace_members, projects, project_members, project_versions, deployments, audit_events`
- [ ] Start the integrated API against it (Flyway applies V26 then V27); measure duration and lock window
- [ ] Post-flight SQL (runbook §3): counts equal, `tenant_members = users`, no mismatching tenant rows; `audit_events` count unchanged
- [ ] Sign in, list projects, open one, edit + commit a version (CAS revision), publish → everything behaves as before
**Expected:** no data loss; nothing becomes TENANT_ADMIN; old projects open and publish; V2 documents are not required.

### S21 — Security / tenant isolation
- [ ] `./gradlew test --tests '*isolation*' --tests '*PrivilegeEscalationTests' --tests '*TenantAccessTests'`
- [ ] Manual with two tenants (platform API creates the second): user A cannot read B's workspace/project/version/asset (**404**); SYSTEM_ADMIN that is not a member gets **404** on business data and an empty project list; TENANT_ADMIN of A has no data access without membership; self-grant (`add self`, `transfer-ownership` to self) → **403 `SELF_GRANT_FORBIDDEN`**
- [ ] SSRF: `PublicAddressTests` + a connector/domain check against `10.0.0.1`, `100.64.0.1`, `198.18.0.1`, `[64:ff9b::a00:1]` (must be refused) and a public address (allowed)
- [ ] Webhook route: unsigned POST (when C3 wiring exists) → uniform 401, replay → rejected; neighbours stay 401/403; CORS from an unlisted origin → 403; CSRF still enforced elsewhere
**Expected:** every item as written; any "allowed" where "refused" is written is a blocker.

### S22 — Publish / rollback
- [ ] `./gradlew test --tests '*PublishApiTests' --tests '*StaticSiteTests' --tests '*DeploymentTests'`; `node e2e/public-flow.mjs`
- [ ] Manual: publish a page app (PUBLIC) → served version = published; publish a second version → rollback → the previous version is served and `publish_configs` is **not** read (rollback uses the deployment's recorded visibility); a document with V2 sections (data bindings) publishes the same static snapshot (D-C5-03: no runtime data call)
**Expected:** publish/rollback unchanged; `deployments_visibility_check` still `PRIVATE|PUBLIC`.

### S23 — Backup / restore
- [ ] `scripts/test-backup-restore.sh` (applies the real migrations, now including V26 and V27) · `scripts/backup-restore-drill.sh` · `scripts/restore-drill-all.sh local`
- [ ] Extend the table list of `scripts/backup-restore-drill.sh` with `tenants`, `tenant_members` (and `publish_configs`) — a C0 script change in its own commit — and re-run
**Expected:** PASS everywhere; restored `flyway_schema_history` contains 26 and 27 and the row counts of the new tables match.

---

## Finish
- [ ] All steps ticked and recorded in `BASELINE.md` ("Re-baseline (macOS)") with SHAs and counts.
- [ ] `BOARD.md` rows for C1–C4 updated by C0 (`DONE` only for what was imported and verified); `BLOCKERS.md` closes B-C1-13/14, B-C3-04/06/07, B-C4-10 with the commit that proved them.
- [ ] Only now: open the question of merging `integration/v2` into `feat/production-hardening`. Not before; and "production-ready" is a separate claim that needs load, backup, and security review evidence beyond this list.
