# INTEGRATION_V2 — C0 review of C1–C5 and integration plan

Date: 2026-10-05 · Reviewer: C0 · Base of all five branches: `a82660f` (merge-base verified) · Reviewed tips: C1 `e3a48eb`, C2 `a4d5f8b`, C3 `607c116`, C4 `a38ff60`, C5 `8e04d79`.
Method: static review of every changed file of each branch (≈2,400 / 6,100 / 10,200 / 8,500 / 2,900 added lines) against the base, plus spot checks by C0 of V26, `PublicAddress` and `SchemaService.ensureInitialized`. **Nothing was compiled or executed.** This document makes no pass/ready claim for backend or frontend.

## 1. Verdicts
| Agent | Verdict | Why |
|---|---|---|
| C1 | **FIX** | Sound V26 core and composite FKs; defects in trigger semantics, missing removal plan for the DEFAULT/triggers, untested migration on real rows, behaviour change for system admins unverified against base tests, canonical permission codes not yet in `Permission.kt`. |
| C2 | **FIX** | Backward-compatible, flags OFF, preserved systems intact. Needs: enum/shape alignment with C3/C4 (`REFRESH_QUERY`, `DATE`, `required` default), lossless extended `ActionDef/WorkflowDef/MappingDef`, `ensureInitialized` gap, resolver, ID renumbering. Touches live paths (`SchemaCommitService`, `PromptController`, `Templates`) → highest regression risk of the code branches. |
| C3 | **FIX** | Inert (no Spring annotations, no endpoints, no SQL on platform tables) and better security than base; real defects: `SqlGuard` `U&` bypass, cache stale-put race, webhook replay, superuser check only in `test()`, fail-open deny-list, placeholder `TenantContext`. Packages are mutually dependent → import as a whole. |
| C4 | **FIX** | Clean, isolated, fails closed; but no adapters/migrations/queue yet, sweeper starvation and nack-requeue DLQ burn, idempotency-key mismatch with C3, alias map to remove. Import dark. |
| C5 | **FIX** | Coherent, defensive; monorepo touches C0-gated files (`package.json`, lockfile, `tsconfig.json`, `next.config.ts`, `proxy.ts`), supersedes ADR 0001 without an ADR, CORS/OIDC/bind-address gaps, results for the monorepo commits not recorded. |
No BLOCK: nothing found that destroys a preserved system. Common gate for all five: Gradle/Docker (or the full npm build) has never run on this code.

## 2. Conflict map (mechanical)
Code files changed by more than one branch: **none** (verified by file-list intersection of all 10 pairs). The only overlaps are `docs/parallel/BOARD.md`, `BLOCKERS.md`, `DECISIONS.md` (all five) — merged by hand (§5). Consequence: integrate **path-scoped, one commit per agent step**, not commit-by-commit cherry-pick (commits inside each branch also interleave docs edits and, for C3, cyclic packages).
ID collisions: `D-008`, `D-009` used by C2 and C3; `D-C2-05` missing; V26 row of BOARD edited by C1 (acceptable, C0 confirms assignment).

## 3. Contract conflict table
Canonical decisions are in `docs/contracts/v2/*`. "Files" are the files that must change; "Owner" does the change.

| Area | C1 shape | C2 shape | C3 shape | C4 shape | C5 expectation | Canonical decision | Files needing change | Owner |
|---|---|---|---|---|---|---|---|---|
| TenantContext | `(tenantId, tenantRole, status, platformScope)` | none (uses `workspaceId`) | placeholder `(tenantId, organizationId, workspaceId, actorUserId, actorKind, requestId)` | `ActionContext(tenantId, actor, workspaceId, projectId, requestId)` | client never sends tenant | C1 shape; draft fields removed; adapters build `GatewayContext`/`ActionContext` | `data/datasource/DataSource.kt` (delete placeholder + `ActorKind`), `logic/action/ActionModel.kt` (use `tenancy.ActorKind` at the adapter), contract `tenant-context.md` superseded | C3, C4, C0 |
| ActorKind | not defined | – | own enum | own enum | – | one enum in `tenancy` | as above | C1 defines, C3/C4 adapt |
| Permissions | 12 codes (+`TENANT_*`) | free regex on `PermissionDef.permission` | `GatewayOperation` ×10 | strings `APP_USE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE` | reads only `systemAdmin`, `workspaces` | canonical vocabulary table (`tenant-permission.md` §5); new constants named exactly | `access/Permission.kt` (+matrix), `AppDefinitionReader` (validate against set), adapters | C1, C2, C0 |
| DataSource | – | `DataSourceDef{id,name,type,sourceRef,description}` | `DataSourceRef{id,tenantId,type,configNonSecret}` / `DataSource` | `dataSourceRef: String` in `OperationRequest` | none | document slot (C2) → `sourceRef` UUID → C3 `DataSource`; resolver translates | `AppDataBindingResolver` (new) | C2 |
| Query | – | `QueryDef{id,dataSourceRef,mode,operationKey,params,maxRows}` | `QueryDefinition` (Sql/Rest) + `QueryRequest` + `GatewayQuery` | `queryRef` in `WriteRequest` | – | doc = binding; `operationKey` = C3 definition id; runtime request = `GatewayQuery/GatewayMutation` | resolver; C3 gateway signature differs from old contract (contract rewritten) | C2, C3 |
| ParamType | – | 5 values (no DATE), `required=false` | 6 values (+DATE), `required=true` | `InputType{STRING,NUMBER,BOOLEAN,OBJECT,ARRAY,ANY}` (INTEGER/TIMESTAMP→ANY) | – | C3's enum; `required=true`; C4 `InputType` is action-input typing, separate, but INTEGER/TIMESTAMP/DATE must map, not collapse | `ParamDef` (C2), `ActionModel.kt` InputType mapping (C4) | C2, C4 |
| ViewModel | – | `ViewModelDef` | `ViewModelDefinition` (same fields) | `VIEW_MODEL` input source | – | C3 owns; `FieldType` values must be equal | conformance test | C0 |
| Mapping | – | `MappingDef{id,queryRef,fields[{from,to}]}` | `MappingDefinition` + `FieldMapping{from?,to,transforms,default,nullable,validation}` + `errorPolicy` | – | – | C3 shape, stored verbatim; `queryRef` = local query id, resolver translates; `from` nullable | `AppDefinitionModel.kt`/Reader (C2) | C2 |
| DataBinding | – | `DataBindingDef{id,sectionId,prop,viewModelRef\|queryRef,mappingRef}` | none | – | `DataBinding{id,sectionId,prop,queryRef,mapping[]}` in `contract-mirror.ts` (unused) | C2's shape; draft/mirror withdrawn | delete `lib/app-definition/contract-mirror.ts`; add mirror in `packages/types` | C5 |
| ActionType | – | 8 values (no `REFRESH_QUERY`) | – | 9 values + legacy aliases (`RUN_QUERY→REFRESH_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`) | – | C4's 9 values; aliases removed | `AppDefinitionModel.kt`/Validator (C2), `CanonicalActionCatalog.kt` (C4) | C2, C4 |
| Action declaration | – | `ActionDef` thin (12 fields) | – | `ActionDefinition` rich; reader reads ~25 keys | – | C4 owns declaration; C2 extends `ActionDef`, lossless, delegates validation | C2 model + validator | C2 |
| Event / Trigger | – | `ActionTriggerDef{sectionId,event:String}`, `ActionRefDef` view | – | `EventType` wire names, `ActionRef(id,sectionId,event,actionId)`, `TriggerKind` | – | stored = inline `trigger`; `event` ∈ EventType wire; `ActionRef` derived | Validator `checkTrigger`, component metadata events | C2 |
| Workflow | – | `WorkflowDef/WorkflowStepDef` thin; triggers MANUAL/SCHEDULE/ACTION | `workflowRef` in webhook/sync (`WorkflowTriggerPort`) | `WorkflowDefinition` rich | – | C4 owns; C2 extends stored form; `WorkflowTriggerPort` adapter by C0 | C2 model, `wiring.*` | C2, C0 |
| Publish | – | `publishConfig` draft + `publish_configs` + API | – | – | – | draft (doc) vs policy (table, V27) vs served state (deployments) | `application.yml` flag; publish re-check of `public_data_approved` | C2, C0 |
| AI | – | Planner + Platform/Tenant AI (flags OFF) | `AiSafeSchema` (source for `AiDataCatalog`) | – | – | one structured-operation path; `AiDataCatalog` from C3; no AI-specific data model | `wiring.AiDataCatalogAdapter` | C0 |
| Component metadata | – | `ComponentMetadataV2` read-only API | – | – | B-C5-03 wants bindable props/events | C2's metadata satisfies it; events = EventType wire | – | C2 (done) |
| Network policy | – | Tenant AI static host allow-list only | `AddressPolicy` = `PublicAddress` + `SupplementaryRanges` | – | – | one `PublicAddress` (patched, §8); `SupplementaryRanges` deleted | `runtime/Gateway.kt` (C0), `AddressPolicy.kt` (C3) | C0, C3 |

## 4. Risk register
R-01 No code was compiled or run (Gradle/Docker/JDK 21 unavailable to C0's sandbox; the agents' "harness" counts are not Gradle runs). R-02 C1 commit hashes cited in C1 docs are placeholders ("commit kế tiếp"). R-03 Default `system-admin-business-access=false` changes behaviour (system admins lose god mode; `ProjectController.list` empty for non-member admins); base tests (`AdminApiTests`, `MemberApiTests`, `ProjectApiTests`, …) may rely on it. R-04 `AccessService` constructor grew 4 dependencies (manual constructions in tests?). R-05 `AccessContext` defaults fail open to the DEFAULT tenant. R-06 V26 `workspaces.tenant_id DEFAULT` + fill triggers have no scheduled removal. R-07 "no `tenant_members` row ⇒ MEMBER" cannot be revoked at tenant level (accepted by D-C1-12). R-08 system admin can self-add as WORKSPACE_ADMIN. R-09 C2's V2 detection: existing stored documents with top-level keys named `kind/theme/queries/actions/permissions/extensions/publishConfig` would become "V2" (low likelihood). R-10 Render/preview/publish planes were never checked against documents with V2 keys. R-11 `REMOVE_SECTION` of a bound section now 422 at commit for V2 documents. R-12 `SchemaService.ensureInitialized` (templates/initial version) skips the V2 validator. R-13 Tenant AI has only a PUT-time host check (no DNS rebinding guard). R-14 `public_data_approved` checked only at PUT time. R-15 C3 modules inert but cyclic (cannot be split). R-16 C5 monorepo breaks single-origin assumptions (CORS default only `localhost:3000`, single OIDC redirect URI, apps bind all interfaces, `run-local.sh`/nginx/compose/e2e assume one UI on 3100).

## 5. Integration strategy
Branch: `integration/v2`, created from the commit that carries this document (same tree as `feat/production-hardening` + contracts). **No cherry-picks have been done.** Every step = one commit authored as "import agent/cX paths (<tip SHA>)" using `git checkout agent/cX -- <paths>` for **code/tests/non-shared docs only**; BOARD/BLOCKERS/DECISIONS are reconciled by hand at step 5. After each step the user runs the verification in §9 on a machine that can; a step is not "done" until recorded in `BASELINE.md`.

| Step | Content | Preconditions (patches first) | Boundary |
|---|---|---|---|
| 1 | **C1** `tenancy/**`, `access/**`, V26, tests, `docs/parallel/{audit,c1}/` | V26 fixes (§7); canonical permission constants added; flag declared in `application.yml` (C0); base tests adjusted for sysadmin change | commit "step 1: tenant core (V26)" |
| 2 | **C2** `app/definition/**`, `schema/**`, `version/**`, `component/**`, `template/**`, `project/publishconfig/**`, `ai/**`, `prompt/**`, `integration/llm/**`, tests/resources, `docs/parallel/audit/C2-*` — **no V27 yet** | fixes: `ensureInitialized`, `REFRESH_QUERY`, `DATE`, `required`; flags OFF in yml; publish-config bean stays off (needs V27) | commit "step 2: AppDefinition core" |
| 3 | **C3** `data/**`, tests, `agents/C3_DATA_PLATFORM.md` as a whole | `PublicAddress` patch (§8) or keep `SupplementaryRanges` until it lands; delete placeholder `TenantContext`; `U&`, cache race, replay fixes | commit "step 3: data contracts/core" |
| 4 | **C4** `logic/**`, tests, `docs/parallel/audit/{FINAL,PREP}*` | remove alias map; mode/derived key fields; sweeper/nack fixes can follow in step 5 patches | commit "step 4: action/workflow core" |
| 5 | **Reconcile patches** (C0): union BOARD/BLOCKERS/DECISIONS with renumbering, conformance fixtures + tests (§3 of integration-contract), `PublicAddress` patch, yml flags, enum alignment commits by owners (as separate commits) | steps 1–4 compile | one commit per patch |
| 6 | **C5** monorepo: shared packages + shims first, then `apps/*`, then root config | CORS/OIDC/`-H` notes, AuthPages links, delete `contract-mirror.ts`, `packages/types` mirror with version header; ADR superseding 0001 | two commits: packages+shims / apps+root config |
| 7 | **Persistence/API wiring** (C0 `wiring/**`): V27 then later migrations in order; JDBC stores, controllers, RabbitMQ adapter, adapters (§1 of integration-contract) | steps 1–6 green | one commit per migration/feature |
| 8 | **Full tests**: Gradle suite incl. Testcontainers, 14 PG tests, npm builds, E2E (`factory-flow`, `public-flow`, `a11y`) | – | recorded in `BASELINE.md`; only then discuss merge to `feat/production-hardening` |

## 6. Migration order
V26 (C1/T2) → V27 (C2/T7 `publish_configs`) only after V26 is integrated and verified → nothing else until requested one by one, in the dependency order of `integration-contract.md` §5. No bulk assignment. `outOfOrder` stays off. BOARD currently shows requests from several agents with no number; they stay unnumbered. **Authoritative ledger: `MIGRATION_LEDGER.md` (2026-10-06).**

## 7. Review of V26 (`V26__tenant_foundation.sql`) — C0 findings
Verified correct: order (create → DEFAULT tenant → nullable columns → backfill → verification block → NOT NULL/FK/UNIQUE/composite FK/indexes → triggers); duplicate slugs impossible; composite FKs `(workspace_id, tenant_id)→workspaces(id, tenant_id)` make child tenant ≡ workspace tenant; every base INSERT into the four tables omits `tenant_id` and is filled by the DEFAULT/trigger (base code keeps working); triggers fire only on `UPDATE OF workspace_id`.
Defects (all must be fixed or recorded before acceptance):
1. **Trigger hides bugs** — `tenancy_fill_tenant_from_workspace()` unconditionally overwrites a caller-supplied `tenant_id` (and a test asserts it). Change: fill only when NULL; if non-NULL and different from the workspace's tenant → `RAISE EXCEPTION`. Update the test.
2. **No removal plan** — the `workspaces.tenant_id DEFAULT` and the three fill triggers are compatibility scaffolding. Required: SQL comment on each object (`-- COMPATIBILITY, remove when …`), a runbook section "Removing compatibility" (owner C1, condition: all INSERT paths into `workspaces/workspace_members/projects/project_members` pass `tenant_id`, proven by a grep test), and a BOARD migration request for the follow-up migration (drop DEFAULT, drop triggers). Until then new code must pass `tenant_id` explicitly.
3. **Invariant "workspace member ⇒ tenant member"** is enforced only `AFTER INSERT` on `workspace_members`; reactivation by UPDATE (`MemberController`, SCIM) and workspace moves don't create `tenant_members`. Cover `UPDATE OF active, workspace_id` or document the fail-open rule everywhere (it is, by D-C1-12, accepted).
4. **Locking** — the runbook says "short ACCESS EXCLUSIVE lock"; Flyway runs one transaction: four table rewrites/validations under ACCESS EXCLUSIVE until commit. Fine for pilot-size data; runbook must say so and give the `NOT VALID` + `VALIDATE` path for large tables.
5. **Undo script** unguarded (silently destroys non-DEFAULT tenants/admins) and untested; add guard and a test, or state it is manual-only.
6. **Untested on rows** — `TenantFoundationMigrationTests` runs on an empty DB (V2 removed the seed workspace). Add a test that migrates from V25 with seeded users/workspaces/projects/members.
7. Minor: pin `search_path` in plpgsql functions; `tenants.updated_at` maintenance; slug reserved words; `assertNotLastAdmin` race; slug race → 409.

## 8. `PublicAddress` — canonical patch (APPLIED 2026-10-06 to `runtime/Gateway.kt`; **not compiled, not run** — Mac step S1 verifies it; tests in `PublicAddressTests`)
Verified in the base: `isPublic` blocks only loopback/site-local/link-local/any-local/multicast/fc00::/7/100.64/10/169.254.169.254/0.* and treats as **public**: 240.0.0.0/4, 192.0.0.0/24, 198.18.0.0/15, TEST-NETs and 192.88.99/24; IPv6 `::a.b.c.d`, NAT64 `64:ff9b::/96` and `64:ff9b:1::/48`, 6to4 `2002::/16`, Teredo `2001::/32`, `2001:db8::/32`, `100::/64`, SIIT. C3 stop-gap `SupplementaryRanges` covers part. Proposed (written by C3's reviewer, mirrored and probed in Java on JDK 21; **not compiled as Kotlin** — compile and test it before use):
```kotlin
object PublicAddress {
    fun isPublic(host: String): Boolean {                       // signature kept
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addrs.isNotEmpty() && addrs.all { isPublicAddress(it) }
    }
    fun isPublicAddress(a: InetAddress): Boolean {              // already-resolved address, no DNS, fail closed
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> V4.none { it.matches(b) }
            is Inet6Address -> when {
                (b[0].toInt() and 0xfe) == 0xfc -> false
                V6.any { it.matches(b) } -> false
                NAT64.matches(b) -> isPublicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))   // judge the embedded IPv4
                else -> true
            }
            else -> false
        }
    }
    private class Cidr(lit: String, val bits: Int) { /* literal-only, byte/bit prefix match */ }
    private val V4 = listOf(Cidr("0.0.0.0",8), Cidr("100.64.0.0",10), Cidr("192.0.0.0",24), Cidr("192.0.2.0",24), Cidr("192.88.99.0",24),
        Cidr("198.18.0.0",15), Cidr("198.51.100.0",24), Cidr("203.0.113.0",24), Cidr("240.0.0.0",4))
    private val V6 = listOf(Cidr("::",96), Cidr("::ffff:0:0:0",96), Cidr("64:ff9b:1::",48), Cidr("100::",64), Cidr("2001::",23),
        Cidr("2001:db8::",32), Cidr("2002::",16), Cidr("3fff::",20), Cidr("5f00::",16))
    private val NAT64 = Cidr("64:ff9b::", 96)
}
```
Required tests: each range blocked, `8.8.8.8`/`1.1.1.1`/a public IPv6 allowed, `64:ff9b::808:808` allowed and `64:ff9b::7f00:1` blocked, `::127.0.0.1` blocked. Callers to re-check: `ConnectorProxyController` (resolves twice → rebinding exposure remains until it pins), `publish/Domains.kt`, C3 `AddressPolicy` (import path), C2 Tenant AI call-time check. Goal: one policy, `SupplementaryRanges` removed.

## 9. Test environment and status
- Sandbox used by C0: JDK 11 only, no Docker, no Gradle/Maven cache; wrapper needs `gradle-9.8.0-bin.zip` from `services.gradle.org` (blocked by organisation policy per the project owner). **No `build.gradle.kts` change or untrusted download was attempted.**
- **Backend test status: NOT RUN.** Last real figure remains the on-disk run from the dev machine (192 tests, 0 failures, 3 skipped — `BASELINE.md`). Claimed since then: C2 "32/32 or 128", C3 "257", C4 "277" — all from a stubbed kotlinc/JUnit harness, none from Gradle; C1 and C2 explicitly say not run. Compile against Kotlin 2.2.21 / Spring Boot 4.1 / Jackson 3 is unverified for ≈25k new lines.
- **Frontend test status:** C5 reports builds passing for platform, admin, studio, root (mock and http) and the render worker; the only recorded evidence is for the PREP commit. C0 did not re-run (sandbox lacks the SWC binary and network). Re-run after step 6: `npm ci && npm run typecheck:all && npm run typecheck:apps && npm run build:apps && npm run build && NEXT_PUBLIC_API_MODE=http npm run build`, plus the render worker `tsc` against the shims.
- On the Mac, per step: `cd backend && ./gradlew compileKotlin compileTestKotlin --offline` (if the cache is complete) then `./gradlew test`; if `--offline` fails, record the blocker — do not edit `build.gradle.kts` to get around policy.

## 10. Next tasks
- **C1**: V26 fixes (§7); add canonical constants + role matrix doc; adapters (`GatewayAuthorizer`, `AccessPort`, `TenantGate`, `PrincipalResolver`); `MeResponse` tenant fields; close/accept R-08; rebase onto `integration/v2`; report real commit hashes; run Gradle.
- **C2**: align enums (`REFRESH_QUERY`, `DATE`, `required=true`); extend `ActionDef/WorkflowDef/MappingDef` losslessly + delegate validation; `AppDataBindingResolver`; close `ensureInitialized`; validate `permission` codes; renumber D-008/D-009; conformance fixtures; flags OFF.
- **C3**: remove placeholder `TenantContext/ActorKind`; fix `U&`, cache race, webhook replay, per-connection superuser check, fail-closed deny-list, drop `sslmode=require`, keep ambiguous idempotency keys; consolidated DDL request; `AiDataCatalog` provider from `AiSafeSchema`; correct webhook path in docs; remove `SupplementaryRanges` after the patch.
- **C4**: drop alias map; add `mode/appId` and derived-key fields to port requests; sweeper starvation, nack loop, backoff floor, rate limit, retention, schedule dedupe; fix doc claims; consolidated DDL request.
- **C5**: delete `contract-mirror.ts`, add versioned mirror in `packages/types`; mark D-C5-05 TEMPORARY with removal condition; fix hard-coded `/admin` `/studio` links; bind `-H 127.0.0.1`; document CORS/OIDC for three origins; record builds; propose ADR text (C0 files it); rename frontend type collision with `AppDefinitionKind`.
- **C0**: this commit; `integration/v2`; `application.yml` flags; `PublicAddress` patch; BOARD/BLOCKERS/DECISIONS union; conformance fixtures; ADR for monorepo; `wiring/**`; permitAll/CSRF rule for webhook ingest.
