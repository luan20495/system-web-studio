# C5 — Runbook: test suites and the real-backend E2E

Owner C5. Matrix and statuses: `docs/C5_REAL_BACKEND_E2E_MATRIX.md`. Nothing in this runbook starts, stops or configures a backend for you: the real-backend suite only talks to a stack that an operator started.

## 1. The four classes (never mixed, never renamed)

Every test file starts with `// @class: …`; `npm run test:classify` fails if a tag is missing or a file breaks its class rules.

| Class | What it proves | Backend | Command | Counts as real-backend evidence |
|---|---|---|---|---|
| `unit` | pure logic, SSR markup, contract mirror vs shared fixtures | none | `XWEB_CONFORMANCE_DIR=<fixtures> npm run test:unit` | no |
| `mock` | the API client / runner against in-process fake servers | in-process fake | part of `test:unit`; `npm run test:e2e:real:selftest` | no |
| `harness` | real Chromium on the component harness / the 3 Next apps with no API | none | `tests/browser/README.md` | no |
| `real-backend` | real browser → real Studio app → real backend; no interception | **yes** | `npm run test:e2e:real` | **yes — the only kind** |

`e2e/*.mjs` are the pre-V2 single-origin scripts (`@legacy`); they are real-backend by nature but target the old root app on :3100 and are not run against `integration/v2`.

## 2. Gates (what the C5 report runs)

```
npm ci
npm audit
npm run typecheck:all && npm run typecheck:apps
npm run test:classify
XWEB_CONFORMANCE_DIR=<checkout>/backend/src/test/resources/app-definition npm run test:unit   # 0 skipped with the fixtures
npm run test:e2e:real:selftest
npm run build:apps && npm run build && NEXT_PUBLIC_API_MODE=http npm run build
# then, separately: browser harness + portals specs (tests/browser/README.md)
# then, only with a backend: npm run test:e2e:real
```

## 3. Running the real-backend suite

### 3.1 Backend stack (operator)

Build and start `integration/v2` (JDK 21, PostgreSQL, Redis, MinIO; Docker for Testcontainers/compose) as C0's local-stack docs describe. For the flows that need them, set flags explicitly and record them with the run:

| Property | Needed by | Note |
|---|---|---|
| `app.data-platform.enabled=true` | E2E-07 (queries route is mounted only with it) | without it `…/app-runtime/queries/*` answers 404 with no code → UI shows NOT_READY "flag off" |
| `app.workflow.enabled=true` | E2E-10, E2E-11, E2E-S1 (actions/workflows routes) | same 404-without-code behaviour |
| `app.workflow.allow-volatile-stores=true` | only for LIVE mutating actions / workflow starts on a stack without V29 | otherwise 503 `RUNTIME_STORES_VOLATILE`; do not use for E2E-12 |
| `app.publish-configs.enabled=true` + a SELF_HOSTED deployment provider | E2E-13 | a MOCK provider only produces a demo URL → BLOCKED |
| `app.tenancy.system-admin-business-access=false` (default) | all | keep |

Local login must be on (`/api/v1/auth/config` → `localLogin: true`). CORS must allow the Studio origin exactly (`app.web.origins.studio`).

### 3.2 Studio app

```
npm run build:studio
cd apps/studio && API_PROXY_TARGET=http://127.0.0.1:8080 npx next start -H 127.0.0.1 -p 3003
```

The Studio's same-origin `/api` is rewritten to `API_PROXY_TARGET`. Platform (:3001) and Admin (:3002) are not needed by the suite.

### 3.3 Environment (no secrets in the repo; see `tests/e2e-real/.env.example`)

| Variable | Required | Meaning |
|---|---|---|
| `E2E_STUDIO_URL` | yes | origin of the running Studio, e.g. `http://127.0.0.1:3003` |
| `E2E_ADMIN_USER`, `E2E_ADMIN_PASSWORD` | yes | a system-admin account of the stack under test, from the stack's own secret store; used only to create per-run fixtures |
| `E2E_STUDIO_PREFIX` | no | portal prefix, default `/studio` |
| `E2E_CHROME` (or `CHROME`) | no | Chromium/Chrome binary |
| `E2E_HEADED=1` | no | show the browser |
| `E2E_OUT_DIR` | no | report folder, default `.run/e2e-real` (git-ignored) |
| `E2E_ONLY` | no | comma list of flow ids, e.g. `E2E-01,E2E-02` |
| `E2E_RUN_ID` | no | fixture name suffix |
| `E2E_DATA_WORKSPACE_ID`, `_PROJECT_ID`, `_QUERY_ID`, `_USER`, `_PASSWORD` | no | operator-seeded project with a TEST query; user needs APP_EDIT + QUERY_EXECUTE. Absent → E2E-07/08 BLOCKED |
| `E2E_PUBLIC_BASE` | no | origin of the public site if different from Studio |
| `E2E_RESTART_BACKEND_CMD`, `E2E_DURABLE_RUN_STORES=1` | no | E2E-12 only: command that restarts the backend; assert durable stores exist (V29) |
| `E2E_STOP_RABBIT_CMD`, `E2E_START_RABBIT_CMD`, `E2E_RABBITMQ_WIRED=1` | no | E2E-14 only |

The suite never guesses how to stop a service: without the hooks E2E-12/14 stay BLOCKED.

### 3.4 Run

```
E2E_STUDIO_URL=http://127.0.0.1:3003 E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… npm run test:e2e:real
```

Exit codes: `0` every runnable flow passed (BLOCKED/SKIP are listed, never counted as PASS) · `1` a flow failed · `2` NOT RUN (missing env, backend unreachable or not the platform API, local login off, fixtures failed, or zero PASS). Exit 2 is never a pass.
The JSON report (`.run/e2e-real/report-*.json`) records the observed route probes (status/code of the queries and actions routes), fixture notes and per-check evidence; paste its summary into the C5 report.

### 3.5 Cleanup

The runner deletes its projects and disables/revokes its users. Workspaces have no delete route, so the two per-run workspaces remain and are named in the output; remove them with the stack's own admin tooling.

## 4. Release-gate notes (C5)

- C5 may report "real backend PASS X/14" only from a run report. Until then the C5 status is **"C5 implementation ready / integration blocked"**, never "C5 FULL DONE".
- The blockers that cap the matrix are in `docs/parallel/c5/HANDOFF_*.md` (each lists the test ids it blocks).
