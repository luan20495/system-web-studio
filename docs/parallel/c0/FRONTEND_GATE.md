# Frontend gate and Dynamic Organization guards (C0, D-C0-44)

One command: **`npm run gate:frontend`** (about 90 s; `-- --no-build` skips step 5-6 and takes about 30 s; `-- --keep` leaves `apps/*/.next-gate`).

| # | Step | What it proves |
|---|---|---|
| 1 | `npm run guard:static` | the seven static guards below |
| 2 | `npm run guard:test` | every guard FAILS on a deliberately broken fixture (and passes a clean one), plus mutated copies of the real organization files (93 tests) |
| 3 | typecheck | `typecheck:all`, `typecheck:apps`, root `tsc` |
| 4 | `npm run test:unit` | all unit tests, including `organization-fail-closed.test.ts` and `organization-depth.test.ts` |
| 5 | production builds | Platform, Admin, Studio with the production configuration (https origins, same-origin `/api`) into `apps/<app>/.next-gate` (never `.next`, `.next-public`, the local portals) |
| 6 | `npm run scan:bundles` | nothing a browser receives names localhost, loopback, `host.docker.internal`, a container or a compose service |

Not in the gate (they need a browser, a stack or a network): the organization browser harness (`tests/browser/org.spec.mjs`, in-page FAKE transport, class `harness`), the real-backend flows (`npm run test:e2e:real`), the public smokes.

## The guards (all contract-independent: they name no H-C1-17 route and no permission)

| Guard | File | Fails when |
|---|---|---|
| 1 `ORG-HIERARCHY-*` | `tests/guards/org-source-guards.mjs hierarchy` | a depth / level number is compared with a literal >= 1, `level 0` is compared next to a hierarchy word, a `switch` on depth, `MAX_DEPTH` / `maxDepth: n`, a table of level names or a name looked up by depth, logic keyed on a type name (`typeId === "department"`, `type.name === "Phòng"`), a hard-coded type-transition table. Labels, placeholders, comments, tests and docs are never scanned; a deliberate exception needs `// guard-allow: <RULE> — <reason>`. Also covers a future Kotlin organization file. |
| 2 `ORG-FAIL-CLOSED-*` | `... fail-closed` + `tests/builder/organization-fail-closed.test.ts` | an empty / value-returning `catch`, browser persistence (`localStorage`, `indexedDB`, ...), an id made in the browser (`randomUUID`, `Math.random`), a route literal for organization data while nothing is wired (also in `packages/api-client`), a permission-looking name that is not in the backend's canonical set, a stale or reason-less allow entry. Runtime half: every NOT_READY capability REJECTS with `OrganizationNotReady` and sends 0 calls even when the transport would succeed, a failed create leaves nothing readable, the only call while `listEmployees` is NOT_READY is the tenant member list, the adapter passes a server 409 through, and **READY capabilities == `tests/guards/org-contract.json` `wired`** (a two-key change when C1 delivers). |
| 3 `ORG-RELATION-*` | `... relation` | a relation (`isManager`, `isHead`, `relationType === "MANAGER"`, `UnitRelation.MANAGER` ...) sits in the same statement as an authorization decision, a relation is mapped to a role / permission, or `packages/permissions` / backend `access/` reads organization data (unit, position, grade). Tenant role `MEMBER`, the HTTP verb `HEAD` and display-only uses are not flagged. |
| 4 bundle scan | `scripts/scan-prod-bundles.mjs`, allow-list `scripts/prod-bundle-allowlist.json` | any of the above strings in `<dist>/static/**` or prerendered `<dist>/server/app/**`; no build = failure. Server manifests (`routes-manifest.json`, `required-server-files.json`) hold the server-side proxy target and are not browser-visible. The allow-list holds documented library literals only (the URL parser's `"localhost"===host`). A LOCAL build (portals.sh, 127.0.0.1:330x baked in) correctly fails. |
| 5 legacy route | `tests/guards/no-legacy-admin-workspaces.mjs` | the `createWorkspace` client method exists, or the collection `/admin/workspaces` is used by a non-GET call (method on any later line of the same call, `.post(` / `call("POST", ...)` in front, a split literal). GET of the collection and of `/admin/workspaces/{id}` is allowed. |
| 6 test labeling | `tests/guards/test-labeling.mjs` (+ C5's `scripts/test-classify.mjs`) | a test tagged `real-backend` uses a fake / in-memory / intercepted transport, a non-real test calls itself `REAL BACKEND` / `REAL_E2E`, a C0 test / smoke script has no tag, an unknown class, a non-real test in `tests/e2e-real/`. Convention: `// @class: unit | mock | harness | integration | real-backend` (HARNESS / INTEGRATION / REAL_E2E in reports = harness / integration / real-backend). |
| 7 migrations | `tests/guards/migration-ledger.mjs` | duplicate version (any `db/migration` directory), a number above every ledger row, V31 not named `*candidate*` / V32 not named `*organization*`, the V31 (C2) or V32 (Dynamic Organization) ledger row removed or rewritten, `out-of-order: true`. It never requires or creates V31 / V32. |

## When C1 delivers H-C1-17
1. Wire the operation (`CAPABILITIES.<op>`: `status: "READY"`, `route`, `needs` = a CANONICAL permission) **and** add the id to `tests/guards/org-contract.json` `wired` in the same commit; `organization-fail-closed.test.ts` compares the two, and the route guard stops flagging organization paths once something is wired.
2. ~~Remove the `ORG_MANAGE` entry of `tests/guards/org-guards.allow.json`~~ DONE (D-C0-51): the entry is gone, `ORG_MANAGE` is obsolete (guard `ORG-FAIL-CLOSED-OBSOLETE`), the canonical organization permissions are `ORG_STRUCTURE_*`, `EMPLOYEE_*`, `POSITION_GRADE_*`; the permission vocabularies are checked by `tests/guards/permission-mirror.mjs`.
3. Allocate V32 in `MIGRATION_LEDGER.md` (row now says RESERVED); the file must be named `V32__*organization*.sql`.
