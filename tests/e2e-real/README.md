# tests/e2e-real — real-backend browser E2E (class `real-backend`)

Real Chromium → real Studio app → real backend. No network interception, no stubs, no fake data. Matrix: `docs/C5_REAL_BACKEND_E2E_MATRIX.md` · full runbook: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md` · env template: `tests/e2e-real/.env.example`.

    E2E_STUDIO_URL=http://127.0.0.1:3003 E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… npm run test:e2e:real
    npm run test:e2e:real:selftest      # class `mock`: proves the runner can never report a pass without a backend

Exit codes: `0` runnable flows passed · `1` a flow failed · `2` NOT RUN (never a pass). BLOCKED flows (owner + reason + reference) are listed in the table and the JSON report and are never counted as PASS.

Layout: `run.mjs` (guard, fixtures, flows, cleanup, report) · `lib/` (env, API session with CSRF, fixtures, report, UI helpers) · `flows/e2e-NN.mjs` (one per matrix id, plus `e2e-s1`, `e2e-s2`).
Fixtures are created through the product API with random per-run passwords and removed afterwards; workspaces cannot be deleted by any route and are reported as left behind.

Data-management flows (E2E-06…09): each flow is split into `[fixture]` / `[api]` / `[ui]` / `[backend]` / `[cleanup]` stages (check names carry the stage). E2E-06 drives C3's Management API and the Studio "Dữ liệu" panel (sources, credential metadata, test connection, bindings, isolation, permissions) and then ends BLOCKED on the part no route can do (query + declared slot). Created sources/bindings are tracked and removed by cleanup. `E2E_DS_*` (optional) supplies a reachable source for the success path; values come from the environment only and are never printed.
