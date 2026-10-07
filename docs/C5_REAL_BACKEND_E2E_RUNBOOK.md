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
# then, separately (tests/browser/README.md): node tests/browser/build-harness.mjs; serve .test-build/browser on :4000;
#   CHROME=… node tests/browser/builder.spec.mjs        (64 checks)
#   CHROME=… node tests/browser/datasources.spec.mjs    (47 checks, the Data sources panel against an in-page fake)
#   the 3 apps on :3001-3003, then CHROME=… node tests/browser/portals.spec.mjs   (33 checks)
# render worker: (cd workers/render && npx tsc --noEmit -p .)
# then, only with a backend: npm run test:e2e:real
```

## 3. Running the real-backend suite

### 3.1 Backend stack (operator)

Build and start `integration/v2` (JDK 21, PostgreSQL, Redis, MinIO; Docker for Testcontainers/compose) as C0's local-stack docs describe. For the flows that need them, set flags explicitly and record them with the run:

| Property | Needed by | Note |
|---|---|---|
| `app.data-platform.enabled=true` | E2E-06 (C3 Management routes), E2E-07 (queries route) — both mounted only with it | without it `…/app-runtime/queries/*` and `…/data-sources*` answer 404 with no code → UI shows NOT_READY "flag off"; E2E-06 reports BLOCKED (C0) "not mounted". The Management routes need a build that contains the C3 Management API (integrated in `integration/v2 >= 3333aa7`; before that only on `agent/c3-data-prod`) |
| `app.workflow.enabled=true` | E2E-10, E2E-11, E2E-S1 (actions/workflows routes) | same 404-without-code behaviour |
| `app.workflow.allow-volatile-stores=true` | only for LIVE mutating actions / workflow starts on a stack without V29 | otherwise 503 `RUNTIME_STORES_VOLATILE`; do not use for E2E-12 |
| `app.publish-configs.enabled=true` + a SELF_HOSTED deployment provider | E2E-13 | a MOCK provider only produces a demo URL → BLOCKED |
| `app.tenancy.system-admin-business-access=false` (default) | all | keep |
| `SECRETS_MASTER_KEY` (base64, 32 bytes) | E2E-06 (credentials) | without it `POST …/data-sources` with a credential answers 500 `SECRETS_UNAVAILABLE` |
| `DEPLOY_PROVIDER=static` + `SITES_ORIGIN`, `RENDER_URL`, `RENDER_TOKEN` + a running render worker and sites gateway | E2E-13 | the default `mock` provider only produces a demo URL → BLOCKED. Gateway: `infra/sites-gateway/default.conf.template` with `API_UPSTREAM` = the API |
| the C2 publish contract (`fix/c2-v3 8d40218`, not yet in `integration/v2`) and `RATE_LIMIT_PUBLISH_MAX` (default 10/min/user) raised | E2E-P01…P09 | `E2E_BASE_REF=8d40218 docs/parallel/c5/e2e-stack.sh up`; the script sets `RATE_LIMIT_PUBLISH_MAX=500`. Publish is rate limited per user: the helpers wait `Retry-After` once on 429 |
| C2's PAGE_SCHEMA data runtime (`fix/c2-v3 c1e0df5`: slots, `QueryDef.public`, page runtime, runtime config `apiBase`, `PUBLIC_QUERIES`) and `SITES_DATA_API_BASE` | E2E-PD01, PD02 | `E2E_BASE_REF=c1e0df5 E2E_SITES_DATA_API_BASE='http://127.0.0.1:<sites port>/{slug}/_data' docs/parallel/c5/e2e-stack.sh up` (it is NOT in `integration/v2`: H-C0-10). PD01 also needs a real source the C3 address policy accepts: `E2E_DS_TYPE/CONFIG_JSON/CREDENTIAL_JSON` + `E2E_PD_SQL` (a read-only SELECT) or `E2E_PD_OPERATION_KEY`, and `E2E_PD_EXPECT_TEXT` (the text of its first row, shown as the Navbar brand). Without them the flow is BLOCKED with the exact missing piece. |
| `app.workflow.run-store=jdbc` (default on a build with V29) | E2E-12 | `memory` or a build without V29 loses runs on restart |
| `E2E_RESTART_BACKEND_CMD` | E2E-12 | must kill ONLY the API process (never `kill` by name) and start it again; the suite waits up to 150 s |

Local login must be on (`/api/v1/auth/config` → `localLogin: true`). CORS must allow the Studio origin exactly (`app.web.origins.studio`).

### 3.2 Studio app

```
API_PROXY_TARGET=http://127.0.0.1:8080 npm run build:studio          # the proxy target is BAKED IN AT BUILD TIME (rewrites are evaluated by `next build`)
cd apps/studio && npx next start -H 127.0.0.1 -p 3003               # setting API_PROXY_TARGET only here has no effect: every /api call answers 500 ECONNREFUSED :8080
```

(Found on the Mac run 2026-10-06; an earlier version of this runbook said to set it at `next start`.) The Studio's same-origin `/api` is rewritten to the target given at build. Platform (:3001) and Admin (:3002) are not needed by the suite. The Builder is the `design` view: `/studio/projects/<id>/design` (`/studio/projects/<id>` opens the AI mode or the session's last mode).

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
| `E2E_DS_TYPE`, `E2E_DS_CONFIG_JSON`, `E2E_DS_CREDENTIAL_JSON` | no | a **reachable** source (public host, valid certificate; the platform's own DB is refused) for E2E-06's "connection test succeeds" check. Environment only; never printed; a malformed value is reported without its content. Absent → only the failing-test path (a host that cannot exist) runs |
| `E2E_PUBLIC_BASE` | no | origin of the public site if different from Studio (E2E-13); E2E-08 reports what `<origin>/runtime-config.json` answers |
| `E2E_RESTART_BACKEND_CMD`, `E2E_DURABLE_RUN_STORES=1` | no | E2E-12 only: command that restarts the backend (twice per run); assert durable stores exist (V29). E2E-12 publishes the project and starts a **LIVE** WAIT-only run: the engine simulates every step in TEST mode, so a TEST run is already terminal before any restart |
| `E2E_STOP_BACKEND_CMD`, `E2E_START_BACKEND_CMD` | no | E2E-S6, S7: stop / start ONLY the API (the start hook must return once the process is launched; the flows wait for the API) |
| `E2E_PAUSE_BACKEND_CMD`, `E2E_RESUME_BACKEND_CMD` | no | E2E-S9: `SIGSTOP` / `SIGCONT` of ONLY the API process (a hang, not an outage) |
| `E2E_PAUSE_STORE_CMD`, `E2E_RESUME_STORE_CMD` | no | E2E-P04, P05: pause / unpause ONLY the artifact store (MinIO); the flow pauses it when a publish reaches DEPLOYING or during a rollback |
| `E2E_PAUSE_RENDER_CMD`, `E2E_RESUME_RENDER_CMD` | no | E2E-P08: SIGSTOP / SIGCONT ONLY the render worker |
| `E2E_SHUFFLE_SEED=<n>` | no | shuffles the order of the selected flows (deterministic per seed): exposes order dependencies and state leaks between flows |
| `E2E_BACKEND_URL`, `E2E_BACKEND_HEAD` | no | informational: written into every evidence block (the suite only talks to the Studio origin) |
| `E2E_STOP_RABBIT_CMD`, `E2E_START_RABBIT_CMD`, `E2E_RABBITMQ_WIRED=1` | no | E2E-14 only |

The suite never guesses how to stop a service: without the hooks E2E-12, S6, S7, S9 and 14 stay BLOCKED. `docs/parallel/c5/e2e-stack.sh` wires all of them for the local Mac stack.

Per run the reporter writes, next to `report-*.json`, an `evidence-*.txt` with one block per flow (FLOW, RESULT, START, END, FRONTEND_HEAD, BACKEND_HEAD, BACKEND_URL, PROJECT, WORKSPACE, HTTP EVIDENCE, UI ASSERTION, PERSISTENCE ASSERTION, RESTART/RECOVERY, BLOCKER, OWNER). The kind of each check is inferred from its name (`kindOf` in `lib/report.mjs`) unless the flow passes one.

### 3.4 Run

```
E2E_STUDIO_URL=http://127.0.0.1:3003 E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… npm run test:e2e:real
```

Exit codes: `0` every runnable flow passed (BLOCKED/SKIP are listed, never counted as PASS) · `1` a flow failed · `2` NOT RUN (missing env, backend unreachable or not the platform API, local login off, fixtures failed, or zero PASS). Exit 2 is never a pass.
The JSON report (`.run/e2e-real/report-*.json`) records the observed route probes (status/code of the queries and actions routes), fixture notes and per-check evidence; paste its summary into the C5 report.

### 3.5 Cleanup

The runner deletes its projects and disables/revokes its users. Workspaces have no delete route, so the two per-run workspaces remain and are named in the output; remove them with the stack's own admin tooling.

## 4. Runtime configuration of a published app

The published page loads `GET /runtime-config.json` before it creates a Data API client: `{"DATA_API_BASE_URL":"https://…","ENVIRONMENT":"staging","RELEASE_ID":"…","VERSION":"…"}`. The operator (C0/C2) serves it next to the artifact; nothing is baked in. Rules (C5 client, fail closed): https required (http only for loopback in development), no credentials/query/fragment, loopback refused in production, broken JSON / missing URL / 5xx never fall back, the page shows the failure. **Open (not C5's to decide):** which host serves the file and the Data API, and the CORS/cookie model between the published origin and the Data API — C0/C2/C3 have not fixed the topology and C2's proposal is unverified.

## 5. Release-gate notes (C5)

- C5 may report "real backend PASS X/14" only from a run report. Until then the C5 status is **"C5 implementation ready / backend integration pending"**, never "C5 FULL DONE".
- The blockers that cap the matrix are in `docs/parallel/c5/HANDOFF_*.md` (each lists the test ids it blocks).
