# Canonical contract v2 — Integration rules (cross-agent)

Owner: **C0**. Frozen 2026-10-05. Applies to every branch that wants to land on `integration/v2`.

## 1. Package layering (allowed imports)
```
common, audit, runtime(SecretsCrypto, PublicAddress)        ← base, read-only for agents
tenancy, access  (C1)        imports: base only
data.*           (C3)        imports: base + tenancy types; NEVER logic.*, app.*
logic.*          (C4)        imports: base only (ports); NEVER access.*, tenancy.*, data.*, app.*
app.definition   (C2)        imports: base + data.{query,mapping} *shape/parser classes* + logic.action *validator* ; NEVER data connectors/gateway
wiring.*         (C0)        the ONLY package that imports across all of the above (adapters, Spring beans, controllers' glue)
```
`wiring.*` holds: `GatewayAuthorizerAdapter`, `AccessPortAdapter`, `TenantGateAdapter`, `PrincipalResolverAdapter`, `ActionDataPortAdapter`, `AppDefinitionSourceAdapter`, `AiDataCatalogAdapter`, JDBC/Redis/RabbitMQ implementations of the in-memory ports. New C0-owned directory; no agent edits it.

## 2. One definition per concept
`ActorKind`, `TenantContext` → `tenancy` (C1). `ParamType`, `FieldType`, mapping/viewmodel/query shapes → `data.*` (C3). `ActionType`, `EventType`, `TriggerKind`, action/workflow declaration → `logic.*` (C4). `DataBindingDef`, `PublishConfigDef`, ops → `app.definition` (C2). Duplicated enums (C2's copies of `ParamType`, `FieldType`, `ActionType`) are allowed only as long as a **conformance test** asserts name-for-name equality with the owner's enum (C0 adds the test in step 5).

## 3. Conformance fixtures
One directory of JSON documents `docs/contracts/v2/fixtures/` (C0 creates in step 5): `legacy-multipage.json`, `valid-v2-full.json`, `invalid-*.json`. Every agent's reader/validator test loads the **same** files: C2 validator, C3 mapping/viewmodel parsers, C4 `CanonicalActionCatalog`/`CanonicalWorkflowCatalog`, C5 type-check of `packages/types`.

## 4. Decisions, blockers, board
IDs stay namespaced (`D-C1-*`, `B-C3-*`, …). The base ids `D-008`/`D-009` were used twice (C2 and C3): C0 renumbers at merge (`D-C2-00a`, `D-C3-00a`). `docs/parallel/{BOARD,BLOCKERS,DECISIONS}.md` are changed by all five branches; they are **merged by C0 by hand** (union, append-only), never by git conflict resolution.

## 5. Migration order (Flyway, strictly ascending, no `outOfOrder`)
1. **V26** `tenant_foundation` — C1/T2 — assigned; integrates only after the V26 fixes in INTEGRATION_V2 §7 and a green Gradle run.
2. **V27** `publish_configs` — C2/T7 — assigned **only after V26 is integrated and verified**; nothing else may take V27.
3. Later, assigned one at a time by C0 in dependency order (no numbers now): tenant-owned existing resources (`templates.tenant_id`, `tenant_ai_providers`, T5 scope columns) → data persistence (C3, single consolidated request) → sharing/departments/groups (C1) → action/workflow persistence (C4) → RLS (T18) → audit hardening (T19). Each request names the previous version it needs.
Every new table references `tenants(id)`; workspace-scoped ones use the composite FK. Request and assignment live only in `BOARD.md`.

## 6. Switches that stay OFF
`app.ai-planner.enabled`, `app.tenant-ai.enabled`, `app.publish-configs.enabled`, `app.tenancy.system-admin-business-access`, and any data-platform / workflow endpoint flag. They are declared (default false) in `application.yml` by C0.
- **Tenant AI**: stays OFF until the outbound call re-resolves and checks the address with the canonical `PublicAddress` at call time (DNS-rebinding), not only at PUT time.
- **AI Planner**: stays OFF until C1 permissions (`APP_EDIT`, data codes), C3 `AiDataCatalog` (from `AiSafeSchema`) and the canonical AppDefinition are integrated and the planner is wired to them.

## 7. Verification gate
Nothing on `integration/v2` is called passing until `cd backend && ./gradlew test` (JDK 21, Docker) and `npm ci && npm run typecheck:all && npm run build:apps && npm run build` (both legacy modes) have been run **on a machine that can run them**, with the output recorded in `docs/parallel/BASELINE.md`. `integration/v2` never merges into `feat/production-hardening` or main before that.

## 8. Frontend rules (C5)
`packages/types` mirrors the backend contracts, header states `MIRROR of docs/contracts/v2/<file>.md @ <commit> — manual`, and is the only mirror: `lib/app-definition/contract-mirror.ts` is deleted. Portal gates: Platform = `SYSTEM_ADMIN`; Admin = `TENANT_ADMIN` (or scoped manager); Studio = authorised tenant member / resource grant. `D-C5-05` (portals follow `me.systemAdmin`) is **TEMPORARY** and is replaced when C1 exposes tenant roles in `MeResponse`; the Admin portal is internal-only until then.
