# QA FINAL

Canonical QA document for XWEB / System Web Studio: test strategy, test-class taxonomy, technical gates and how to run each, business journeys, evidence policy, QA verdict history, and the template for the final acceptance record.
Audience: engineers and QA who take over with no chat history. Language: English. Every claim carries `(src: path[:line])`; `UNVERIFIED` marks what could not be checked and says what would verify it.

| Field | Value |
|---|---|
| Source of truth | `integration/v2` (read at local HEAD `28376ded2307`, 2026-10-11; final `{{INTEGRATION_SHA}}`) |
| Final release candidate under acceptance | `{{FINAL_RC_SHA}}` |
| Public portals / API releases | `{{PUBLIC_FRONTEND_SHA}}` / `{{PUBLIC_API_SHA}}` |
| QA documents | `docs/parallel/c6/**` on `integration/v2` (imported from `agent/c6-qa @ 04803d7644fc`, 2026-10-10 16:36 +0700, by commit `28376de`): reports, harness, text evidence; screenshots stay in external archives (section 6) |
| Written | 2026-10-11 |

**Important framing.** Section 5 is a DATED RECORD of what QA concluded on 2026-10-10 about product SHA `bc5c47f292d0`. It is not the verdict for `{{FINAL_RC_SHA}}`. The acceptance run of the final SHA, including the "Golden Company" flow, is recorded ONLY in `## Final acceptance record` (section 8), which this draft leaves as a template.

Related canonical docs: [OPERATIONS](OPERATIONS.md) (how to run the stacks and gates), [KNOWN_LIMITATIONS](KNOWN_LIMITATIONS.md) (open items), [ARCHITECTURE](ARCHITECTURE.md).

---

## 1. Test strategy

1. **Evidence beats assertion.** A result counts only if it comes from an executed run with a recorded SHA, command and output. A cached Gradle `:test` (`FROM-CACHE` / `UP-TO-DATE`) executed no test and is not evidence (QA found a false "GREEN" this way; the harness was patched to fail on it) (src: `docs/parallel/c6/QA_STATUS.md` batch 4 on `origin/agent/c6-qa`).
2. **Never relabel a weaker class as a stronger one.** A harness or mock run is never "E2E". A BLOCKED item (environment, tooling, owner decision) is never counted as PASS and is never a product FAIL (src: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:101`, `docs/parallel/c6/final-rc-brief.md` rules).
3. **The backend boundary is the final authority.** A hidden or disabled button is not authorization proof: every "denied" and every "allowed" claim is also proven at the API with real sessions (src: `final-rc-brief.md` rules).
4. **Fixtures only through product routes** (invite -> activate), names prefixed `c6f-`, random per-run passwords, no SQL writes, no test hooks, no DB edits; read-only SQL only to confirm persistence and labelled as such (src: `final-rc-brief.md`).
5. **Do not stop at the first failure**; capture evidence for each failure and continue independent flows; rule out a harness error before calling a product defect (src: `final-rc-brief.md`).
6. **Test the deployed product, not a working tree**: the stack under test is built from an exact SHA through the stack tooling and its SHA is proven from live evidence (`scripts/rc-verify.mjs`) before and after the run; the served build ids must be identical at start and end (src: `OPERATIONS.md` section 6, `FINAL_RC_QA_REPORT.md` section 1).
7. **Safety**: QA never kills a process it did not start, never touches another stack, never prints a secret (src: `docs/parallel/c0/PROCESS_SAFETY.md`, `final-rc-brief.md`).
8. **Independence**: QA (C6) does not modify production code (`backend/`, `app/`, `apps/`, `packages/`, `features/`, `components/`, `lib/`, migrations, `docs/contracts/**`); it records, routes to an owner, and retests (src: `final-rc-brief.md`, `docs/parallel/c6/BUGS.md` header).

### 1.1 Defect severity and routing (final-QA scale)
P0 data/security breach, privilege escalation, data loss; P1 core journey broken / wrong authorization / false success; P2 wrong behaviour with workaround, a11y serious, major visual; P3 minor. Owners: C1 IAM/Tenant/Auth/Permission; C2 Publish/Release/Deployment; C3 DB/Data/Dynamic-Org persistence; C4 Action/Workflow/Queue; C5 Frontend/UX/test-flow assertion; C0 Integration/Env/Proxy/Gateway/Deployment. Defect id format `FQ-<area>-NN` with severity, owner, business flow, technical flow, expected, actual, evidence (file + row/screenshot), reproducibility (src: `final-rc-brief.md:38`). Release vocabulary: `READY_FOR_RC_FREEZE`, `READY_FOR_RELEASE` (YES/NO) (src: `FINAL_RC_QA_REPORT.md` section 2).

---

## 2. Test classes (taxonomy)

The repository enforces one convention: the first line of every test file is `// @class: unit | mock | harness | integration | real-backend` (reports spell them UNIT, MOCK, HARNESS, INTEGRATION, REAL_BACKEND / REAL_E2E). `npm run test:classify` and `tests/guards/test-labeling.mjs` (inside `npm run gate:frontend`) fail on a missing tag, a `real-backend` test that uses a fake/in-memory/intercepted transport, a non-real test that calls itself `REAL BACKEND` / `REAL_E2E`, a C0 test or smoke script without a tag, an unknown class, or a non-real test under `tests/e2e-real/` (src: `docs/parallel/c0/FRONTEND_GATE.md:25`, `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:5-14`).

| Class | What it proves | Backend | Command / location | Counts as real-backend evidence |
|---|---|---|---|---|
| `unit` | pure logic, SSR markup, contract mirror vs shared fixtures | none | backend JUnit under `backend/src/test/kotlin/...`; frontend `npm run test:unit` (`tests/builder`, `tests/lib`, `tests/page-runtime`) | no |
| `mock` | API client / runner against in-process fake servers | in-process fake | part of `test:unit`; `npm run test:e2e:real:selftest` | no |
| `harness` | real Chromium/WebKit on a component harness or on the three Next apps with no API (fake transport in the page) | none | `tests/browser/*.spec.mjs` via `tests/browser/harness-server.mjs`; `scripts/ui-audit-harness.mjs`, `ui-state-matrix.mjs`, `ui-keyboard.mjs` | **no** |
| `integration` | backend code against REAL PostgreSQL / Redis / RabbitMQ / MinIO started by Testcontainers (Spring context, Flyway V1->latest), plus infra tests with real processes | real throwaway services | `cd backend && ./gradlew test`; `npm run test:infra:*`; `tests/guards` | no (not a full stack) |
| `real-backend` | real browser or real HTTP -> real Studio/portal -> real API -> real PostgreSQL on a running stack; no interception | **yes** | `tests/e2e-real/` (`npm run test:e2e:real` or `e2e-stack.sh e2e`), C6 `final-*.mjs` | **yes, the only kind** |

(src: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:5-16`; `tests/browser/README.md:3`; `docs/parallel/c0/FRONTEND_GATE.md:25`.) Pre-V2 scripts `e2e/*.mjs` (tag `@legacy`) are real-backend by nature but target the old root app on :3100 and are not run against `integration/v2` (src: runbook line 16).

QA evidence classes used in C6 rows (different axis: how the evidence was obtained), with the number of rows in the normalised master `QA_MASTER_NORMALIZED.tsv` (2300 rows): `REAL_BACKEND_E2E` 1112, `REAL_STACK` 266 (real portals/API observed, no business write), `JUNIT_XML` 292, `JSON_ROWS` 287, `BROWSER_API_RUN` 104, `SCREENSHOT_AXE_AUDIT` 39, `MANUAL` 9 (judged by eye), `HARNESS` + `HARNESS_RESULTS` 5, others (`DERIVED`, `NODE_TEST_LOG`, `STATIC_LOG`, `NONE`) (src: computed from the TSV on `integration/v2`; definitions in `final-rc-brief.md:35`). Row statuses in that TSV: PASS 1964, BLOCKED 210, FAIL 87, GAP 37, RETEST 1, NOT_RUN 1 (all batches 1-12; many rows are superseded by later batches).

Rules a test must obey (enforced where noted):
- A real-backend flow passes only with at least one check and zero failed checks; a thrown `Blocked` is listed, never counted as PASS; any failed check wins over BLOCKED. Exit codes of `node tests/e2e-real/run.mjs`: 0 all runnable flows passed, 1 a flow failed, 2 NOT RUN (never a pass) (src: `docs/C5_REAL_BACKEND_E2E_MATRIX.md:85-86`).
- The harness server starts one static server on a free port, records its identity and always stops exactly that process; `HARNESS_URL` is required (src: `tests/browser/README.md:10`, `:57`).
- Process safety applies to tests: no kill by name/port (guard `tests/guards/process-safety.mjs`).

---

## 3. Technical gates and how to run each

All commands are run from the repository root of the checkout under test unless stated. Heavy gates one at a time on the shared host (`OPERATIONS.md` section 9). "Last recorded" values are dated records, not results for `{{FINAL_RC_SHA}}`.

| # | Gate | How to run | Pass criteria | Last recorded (dated) |
|---|---|---|---|---|
| T1 | **Backend full** | `cd backend && ./gradlew clean test --no-build-cache --rerun-tasks --no-daemon -Pkotlin.daemon.jvmargs=-Xmx3g` (JDK 21, Docker, test heap 2 GB); run it ALONE, never together with the browser harness (`OPERATIONS.md` section 9.2) | 0 failures, 0 errors; skipped only the 3 `OpenRouterLiveTests` (need a real key); `:test` executed, not cached; counts taken from the JUnit XML. A mass `NoClassDefFoundError` from container start-up or a single load-induced failure (`LockdownSettingsTests`) is a host problem: re-run on a quiet host before recording FAIL | 2026-10-11 merged C1 hardening tree (D-C0-60 item 3, load about 10): 256 suites, 2298 tests, 2297 pass, the single failure `LockdownSettingsTests` (503 on register, host starvation; 5/5 pass alone). 2026-10-10 on `ef5989e` (D-C0-58 item 5): 249 classes, 2260 tests, 1 failure (same class), 0 errors, 3 skipped. Earlier: 2187/0 on `2d3f520` (D-C0-54); 2003/0 on `62ce9697cd56` (C6 batch 9). For `{{FINAL_RC_SHA}}`: section 8 |
| T2 | **Migrations V1..Vn** | (a) every Testcontainers class runs Flyway from an empty database V1 -> latest (so T1 is the proof that a fresh DB builds); (b) targeted classes: `DataRuntimeMigrationTests` (V28), `PublishConfigsMigrationTests` (V27), the V29 run-persistence migration tests, `TenantMigrationFromV25Tests` (V26), `OrgV32FlywayTests` (clean V1->V32, upgrade V30->V32), the V33 Flyway tests (clean, upgrade, undo, commit `b3fcb53`); (c) on a stack: read-only `select max(version::int), count(*), count(*) filter (where not success) from flyway_schema_history` (as C6 `final-env-check.mjs` ENV-06b and `scripts/rc-verify.mjs`, which now expects max 33); (d) immutability: pinned hashes `docs/parallel/c6/migration-hashes.sha256` (V1..V29) must match the files; (e) `tests/guards/migration-ledger.mjs` (inside `gate:frontend`); (f) public API: `scripts/public-api.sh validate-api` / `deploy-api` rehearsal on an empty scratch database | latest version applied, 0 failed rows, no gap other than V31, applied files byte-identical, ledger guard green | Checked 2026-10-11 for this document: all 29 pinned hashes (V1..V29, pinned by C6 at `8e91172`) are byte-identical on `integration/v2` `28376ded2307`; the directory holds 32 migration files (V1..V30, V32, V33; V31 is a void gap). The candidate-1 SHA proof (2026-10-10) expected "32 / 31 migrations" and passed; the script now expects 33 (not executed on a V33 stack by this pass). V33 file sha256 prefix `2e50cab087fbeb8d` |
| T3 | **Frontend gate** | `npm run gate:frontend` (about 90 s; `-- --no-build` about 30 s) = static guards (9) + guard self-tests (93) + typecheck (root, packages, apps) + `npm run test:unit` + three production builds (`apps/*/.next-gate`) + `npm run scan:bundles`. Do not run it concurrently with the backend gate | all steps green | Reported by the coordinator: GREEN on `205707e` (static guards, 539 unit tests, builds, bundle scan; the RTL ratchet now counts source CSS only and skips generated `packages/*/dist`) - not recorded in a repository document I read. D-C0-58: unit 555/555, guards 9/9, builds + bundle scan green; D-C0-55: same on `5a4ede5` |
| T4 | **Browser harness** (class `harness`) | `node tests/browser/build-harness.mjs`; then per spec `CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs`; live-portal specs (`portals`, `portals-lazy`) need the three apps served via the owned-process CLI. 24+ specs: `builder`, `org`, `org-employees`, `org-hardening`, `provisioning`, `aiproviders`, `datasources`, `release`, `publicdata`, `publish-policy`, `sanity`, `page-runtime`, `shared-ui`, `ui-brand`, `ui-tokens`, `ui-widgets`, `ui-route`, `studio-p1`, `studio-wave2`, `studio-wave3`, `data-binding`, `hooks`, `admin`, `portals-lazy`, `portals` | every spec exits 0; browsers: Chromium full, WebKit (`BROWSER=webkit`) | D-C0-58: 24 specs + portals = 1568 checks, 0 failures; D-C0-55: 1553. `hooks.spec` is timing-sensitive under load; `sanity` is Chromium-only |
| T5 | **Real-backend E2E** | start an isolated stack from the exact SHA (`OPERATIONS.md` section 6), then `docs/parallel/c5/e2e-stack.sh e2e "<flow ids>"` (wires admin URL/account and the restart/pause hooks) or `npm run test:e2e:real`; flows live in `tests/e2e-real/flows/` | each requested flow PASS (>=1 check, 0 failed); BLOCKED/NOT RUN listed, never PASS | 2026-10-10 on `c0rc` (`953d17e`, flag-ON, product `bc5c47f292d0`): E2E-PL01, AD01, AD02, ADMIN01, SUPER01, USER01, E2E-04, E2E-05, E2E-P09, PD02 PASS; E2E-ORG01 31/31; plus the C5 hardening run (E2E-01..05, S1..S9, 10..13, P01..P09, SEC01, AD03) on `c0rc`/`c5fx` (src: D-C0-56/57; `C5_NEXT_SESSION_HANDOFF.md` sections 8-9) |
| T6 | **Independent API/security regression** (C6) | `. docs/parallel/c6/harness/final-env.sh` then `node harness/final-*.mjs` (see section 4); older: `rc-sec.mjs`, `rc-data.mjs`, `rc-rabbit.mjs`, `rc-gateway.mjs`, `rc-portals.mjs` | every row has expected vs actual; cross-tenant 0 | 2026-10-10: IAM 208/209, adversarial 51/55, rc-sec regression 74/74; tenant isolation 0 cross-tenant data/field/write in >1,200 swept requests |
| T7 | **Secret scan** | `./scripts/secret-scan.sh` (gitleaks over the whole history and the working tree with `.gitleaks.toml`); C6 phase `SECRET_SCAN` | `SECRET SCAN CLEAN` | Reported by the coordinator at integration `205707e`: 454 commits, no leaks (not recorded in a repository document I read). Earlier: PASS in the C6 Mac gate of 2026-10-06 and clean in the documented scans (`docs/SECURITY.md:72`, `:99`) |
| T8 | **Recovery / restart** | stack hooks: `e2e-stack.sh backend-restart|backend-down|backend-pause|store-pause|render-pause` used by flows E2E-12, S6, S7, S9, P04, P05, P08; C6 journey 08 `final-recovery.mjs`; older `harness/recovery.sh` (R01..R08); data stores: `scripts/test-backup-restore.sh`, `scripts/test-minio-backup.sh`, `scripts/pitr-drill.sh`, `scripts/restore-drill-all.sh` | the API restarted (about 60 s) with sessions, data, audit, releases, workflow runs and idempotency keys intact; clean failure states while a dependency is paused | Journey 08 on `c6fin`: 24 pass / 0 fail / 2 not executed (2026-10-10); backup drills: 41 + 28 checks, 0 failed (`docs/BACKUP_DR.md:23`, `:46`, throwaway containers, tiny data) |
| T9 | **Infra tests** (process safety, pinning, portals) | `npm run test:infra:processes`, `test:infra:portals` (26 tests, about 2 min), `test:infra:public`, `test:infra:api`; `tests/gateway/portal-route.mjs` | green | documented as green by C0 at their implementation (D-C0-48/49/50); `portal-route.mjs` is flaky (BUG-C6-016, `KNOWN_LIMITATIONS.md` KL-FLK-04) |
| T10 | **UI audit: responsive, accessibility, brand, cross-browser, performance** | `scripts/ui-audit-selftest.mjs` first (a clean audit proves nothing until the self-test passed), then `ui-audit-harness.mjs` (HARNESS), `ui-audit.mjs --private-api <api>` (REAL stack), `ui-state-matrix.mjs`, `ui-keyboard.mjs`; C6 `final-ui.mjs`, `final-clip.mjs`, `final-brand.mjs`, `final-perf.mjs`; browsers Chromium (system Chrome) and Playwright WebKit | axe critical 0 and serious 0; no overflow; targets >= 24 px; focus ring; brand tokens; no localStorage fallback | 2026-10-10: Chromium 1089 cases at 9 widths, axe 0 critical / 0 serious / 13 moderate; WebKit 392 cases at 3 widths; strict clipping detector 76 routes x 4 widths: only `/admin/employees` (FQ-UI-01); dialog focus restore FQ-A11Y-02; performance 26/30. **Firefox: BLOCKED_TOOLING** |
| T11 | **C6 static / contract checks** | `node docs/parallel/c6/harness/static-qa.mjs`; `python3 harness/contract-checks.py` (22 checks with a self-test injecting 10 violations); `python3 harness/db-probes.py` (isolated PostgreSQL: audit append-only, undo U27); `bash harness/run-overlay-tests.sh` (C6 Kotlin permission tests in a temp worktree) | green | batch 7 (2026-10-07, `8e91172`): static 10/10, contract checks green, overlay 4/4; may be stale |
| T12 | **SHA proof of the stack** | `node scripts/rc-verify.mjs --stack <name> --sha {{FINAL_RC_SHA}} --out .../RC_STAMP.json` before and after QA | `SHA_ALL_MATCH YES` | candidate 1 `2f5a86c4e0f9`: 17 live checks PASS (D-C0-59, migration expectation 32 at that time). The script now expects migration max 33 (`scripts/rc-verify.mjs:40`, commit `b3fcb53`) |
| T13 | **Dependency audit** | `npm audit --omit=dev` (part of `./scripts/check.sh`); OSV scans documented in `docs/SECURITY.md` | 0 known vulnerabilities in runtime dependencies | 0 at the last documented runs (`docs/SECURITY.md:72`, `:99`; `FINAL_HARDENING`/C6 batch 7); not re-run for this draft |

The C6 single entry point `bash docs/parallel/c6/harness/final-gate.sh plan|dry-run|status` lists the ordered phases (1 selftest, 2 process safety, 3 backend+frontend+static gate `mac-gate.sh`, 4 full stack E2E/browser/recovery `mac-full.sh`, 5 contract checks and DB probes, 6 RC wide regression, 7 UI/UX regression, 8 user-guide QA, 10 FINAL RC QA, 9 QA master regeneration); it deliberately has no `run` mode: the expensive gate is started by hand phase by phase after C0 approves the target SHA (src: `docs/parallel/c6/harness/final-gate.sh:1-23`). Exit codes of `mac-full.sh`: 0 FINAL PASS, 1 FINAL FAIL, 2 FINAL INCOMPLETE (a critical phase NOT_RUN) (src: `MAC_QA_HANDOFF.md:22`, `:59`). `FINAL|PASS` is not production readiness: GAP rows remain in the file.

### 3.1 Testcontainers and host prerequisites for the gates
Docker daemon running, JDK 21 (`JAVA_HOME`), Node 22+, Chrome at `/Applications/Google Chrome.app` (or `CHROME`), free ports (the stack tooling refuses a busy port and names the holder), `gitleaks` (optional), network access to the npm registry for `esbuild` (installed outside the repo for the browser harness) (src: `MAC_QA_HANDOFF.md:24-34`, `tests/browser/README.md:5-6`). Details and the Testcontainers image list: `OPERATIONS.md` section 16.

---

## 4. Business journeys

Defined in the final QA run of 2026-10-10 (`FINAL_RC_QA_REPORT.md` section 3). Each is exercised at the API and in the browser against the isolated stack; every "denied" claim is proven at the API. The harness file per journey is inferred from file names in `docs/parallel/c6/harness/` and `evidence/final-rc-bc5c47f292d0/` (UNVERIFIED mapping where marked "by name").

| # | Journey | What it proves | Harness / flows (by name) | Evidence class | Result at the dated record (`bc5c47f292d0`, 2026-10-10) |
|---|---|---|---|---|---|
| 01 | Company onboarding | a platform operator creates a company and its admins; activation links, tenant lifecycle (suspend/restore), last-admin rules, workspace creation through the tenant route; the UI half is covered by C5 flows | `final-onboarding.mjs`; C5 `E2E-PL01`, `SUPER01`, `ADMIN01` | REAL_BACKEND_E2E | **PASS** - API 61/62 (one P3 doc deviation FQ-IAM-01); PL01 28, SUPER01 18, ADMIN01 22 checks PASS |
| 02 | Dynamic organization | tenant-defined unit types with placement rules, org tree, positions, grades, employees, memberships, move/archive/restore, counts, paging, 501 fail-closed when the flag is OFF, optimistic concurrency (ETag/version) | `final-org.mjs`, `final-org-authz.mjs`; C5 `E2E-ORG01` | REAL_BACKEND_E2E | **PASS** - API 97/97 + 31/32 (Z19 blocked); C5 ORG01 31/31 |
| 03 | IAM / permission lifecycle | M-052 exact per-tenant permission sets, every permission change effective on the very next request (4-34 ms measured), role downgrade, suspended tenant, cross-tenant/workspace/project isolation, adversarial cases | `final-iam.mjs`, `final-adv.mjs`, `final-rcsec.mjs`; C5 `E2E-AD01..03`, `USER01`, `SEC01` | REAL_BACKEND_E2E | **PASS** - 208/209 (P3 FQ-IAM-02); adversarial 51/55; regression 74/74 |
| 04 | Build an app from zero | create project, edit schema/definition through typed operations, save/version/restore, concurrent edit (one winner + 409), every write confirmed by a fresh-session read | `final-app.mjs`; C5 `E2E-01/02/04/05`, `S2..S9` | REAL_BACKEND_E2E | **PASS** - 44/45 (device preview: client side, not server-verifiable) |
| 05 | Data-backed app | register a data source (credential write-only), test connection, schema discovery, SSRF refusal (16/16), query read of real rows, CREATE_RECORD real INSERT confirmed in the target, replay / concurrent double submit = 1 row | `final-data.mjs` (log `c6fin/final-data.log`); C5 `E2E-06/07`, `PD01` | REAL_BACKEND_E2E | **PASS on own stack `c6fin`** (105/107; the 2 fails are FQ-ACT-02); **BLOCKED on `c0rc`** (TLS_FAILED: no data-target trust store, FQ-ENV-01) |
| 06 | Action / workflow business process | action execute with permission matrix, workflow start/branch/wait/timeout/cancel, idempotent duplicate start (1 run/1 row), ambiguous mutation -> 409 `IDEMPOTENCY_OUTCOME_UNKNOWN`, UPDATE_RECORD status change, APPROVAL step | `final-workflow.mjs` (`journey06-workflow.json`) | REAL_BACKEND_E2E | **FAIL** - c6fin 43 pass / 3 fail / 4 blocked: approval not wired (FQ-WF-01), no decision route (FQ-WF-02), UPDATE_RECORD unusable (FQ-ACT-02). Proven: duplicate start, TIMEOUT, CANCEL, BRANCH validation, ambiguous mutation semantics |
| 07 | Publish / public site / rollback | publish policy, H-C2-07 409/422 and approval only through `PUT publish-config`, release v1 -> v2 -> v3, rollback, unpublish reflected by the gateway without stale content, visitor needs no session, public data runtime | `final-publish.mjs`, `final-pd02.mjs`, `final-site.mjs`; C5 `E2E-13`, `P01..P09`, `PD02` | REAL_BACKEND_E2E | **PASS** - 90/92 (2 P3) |
| 08 | Operations / recovery | API restart (62 s): published site stayed 200 and byte/ETag identical, Redis session survived, org data/audit/release history intact, WAIT run survived, 60 s timer completed after restart, idempotency key survived; render pause -> clean FAILED while live site kept v1; MinIO pause -> BUILDING then recovered | `final-recovery.mjs` | REAL_BACKEND_E2E | **PASS on `c6fin`** (24/26; portal redeploy and hard crash with a step in flight not executed) |

Total at the dated record: BUSINESS_JOURNEYS 7/8 (06 FAIL; 05 and 08 PASS on `c6fin` only) (src: `FINAL_RC_QA_REPORT.md` section 2).

### 4.1 Real-backend flow catalogue (`tests/e2e-real/flows/`)
Ids: `E2E-01..14`, `S1..S9`, `P01..P09`, `PD01`, `PD02`, `PL01`, `AD01..AD03`, `ADMIN01`, `SUPER01`, `USER01`, `SEC01`, `ORG01` (src: `git ls-tree` of `tests/e2e-real/flows`). Purpose (src: `docs/C5_REAL_BACKEND_E2E_MATRIX.md:22-63`, dated 2026-10-06/07; status cells there are historical):
- 01 load project; 02 edit-save-reload; 03 workspace isolation A/B; 04 authorized user reaches a valid resource; 05 forbidden UX without leak; 06 configure data source + query; 07 TEST-binding query; 08 LIVE-binding query; 09 mutation with a real side effect; 10 failure without fake success; 11 workflow start/read; 12 durable workflow after backend restart; 13 publish -> public page; 14 RabbitMQ down -> recovery.
- S1 TEST action = WOULD_RUN; S2 offline save -> retry; S3 action without trigger; S4 one run per double click; S5 save conflict; S6 backend stopped; S7 publish interrupted by outage; S8 failed workflow run; S9 backend hang.
- P01 publish success; P02 concurrent publish + replay; P03 rollback; P04 scope busy; P05 refresh during rollback; P06 rollback stale; P07 idempotency; P08 stale publish; P09 unpublish.
- PD01 public data with a real source; PD02 public data document side; PL01 Platform portal; AD01 tenant admin (Admin portal); AD02 workspace admin; AD03 tenant member directory; SUPER01 SYSTEM_ADMIN provisions a tenant admin; ADMIN01 tenant admin provisions people; USER01 app creator in Studio; SEC01 SEC01-03 isolation/escalation; ORG01 Dynamic Organization (31 checks).
- Known stale or order-dependent flows: E2E-06/08/09/14 skeletons, S7 after PD01/PD02 (`KNOWN_LIMITATIONS.md` KL-UI-09, KL-FLK-03).

### 4.2 Technical flows tracked by the final QA
ENVIRONMENT, PLATFORM/ADMIN/STUDIO portals, IAM, TENANT_ISOLATION, WORKSPACE/PROJECT isolation, DYNAMIC_ORG, DATA_SOURCE/QUERY, MUTATION, ACTION, WORKFLOW, QUEUE, PUBLISH/ROLLBACK/PUBLIC RUNTIME/PUBLISHED_SITE, H_C2_07, RECOVERY, RESPONSIVE (9 widths), ACCESSIBILITY, BRAND, CHROMIUM, WEBKIT, FIREFOX, PERFORMANCE (src: `FINAL_RC_QA_REPORT.md` section 4). Results at the dated record are in section 5.

---

## 5. QA verdict history (dated records, newest last)

Source: `docs/parallel/c6/QA_STATUS.md`, `QA_MASTER.md`, `FINAL_RC_QA_REPORT.md`, `RC_WIDE_REGRESSION_62ce9697cd56.md` (head), `EVIDENCE_INDEX.md` (all under `docs/parallel/c6/`). `QA_STATUS.md` was last updated at batch 9 (2026-10-08) and carries no header for batch 12: the final verdict lives in `FINAL_RC_QA_REPORT.md`.

| Date | Batch | SHA tested | Verdict / result |
|---|---|---|---|
| 2026-10-06 | 1-3 | `f894cc6` (Linux VM, no Docker/JDK/Chrome) | YELLOW; backend/E2E/browser not run |
| 2026-10-06 | 4 (first Mac run) | `f894cc6` | YELLOW; backend "GREEN" of run 1 was a Gradle cache replay (not evidence), run 2 failed at `compileKotlin` OOM; stack-up failed on busy ports of another stack; frontend typecheck/unit 130/130/build PASS |
| 2026-10-06 | 5 | `8e91172` | YELLOW; Production Ready NO; backend 1502 tests / 1499 passed / 0 failed / 3 skipped (real, uncached); 26 security suites PASS |
| 2026-10-06/07 | 6-7 (traceability master) | `8e91172` | YELLOW; 191 requirements; implementation x verification matrix; gates G1-G4 PASS_WITH_GAPS, G5-G9 BLOCKED, G10-G11 NOT_RUN |
| 2026-10-07 | 8 (user-guide QA) | public build, SHA not provable | GUIDE NEEDS FIX; READY TO GIVE USER NO; 104 steps: PASS 57 / FAIL 14 / BLOCKED 33 |
| 2026-10-08 | 9 (RC wide regression) | `62ce9697cd56` | WIDE_REGRESSION PASS; APP_CREATOR BLOCKED_BY_H-C1-04; PUBLIC_SITE BLOCKED (H-C2-07); FINAL_V1_READY NO; backend 211 classes / 2003 tests / 2000 pass / 0 fail / 3 skip; harness 345/345; provisioning 35/35, security 74/74, management+data+action+workflow 99/99, queue/recovery 16/16 |
| 2026-10-08 | 10-11 (UI/UX regression and retest) | `5cc230e491a6`, `40ee45bc16fe` | reports `UI_UX_REGRESSION_5cc230e.md`, `UI_UX_RETEST_40ee45b.md` exist; not read for this draft: verdict UNVERIFIED (read them) |
| **2026-10-10** | **12 (FINAL RC QA)** | product `bc5c47f292d00846c106669b09679a6fc36daef6` as served on `c0rc` (backend worktree `953d17e53d05`, portals built from checkout `fad4a7b4356f`, product-file diff 0) + own stack `c6fin` (same product); integration at that time `edf32dfe187a` | **FULL_BUSINESS_QA FAIL; BUSINESS_JOURNEYS 7/8 (06 FAIL); FULL_TECHNICAL_QA FAIL; E2E_ORG01 31/31; PD02 PASS; P0 0 / P1 2 / P2 4 / P3 12; READY_FOR_RC_FREEZE NO; READY_FOR_RELEASE NO** |

### 5.1 The last full verdict, as recorded (2026-10-10, `bc5c47f292d0`)
```
RC_SHA_TESTED:           bc5c47f292d00846c106669b09679a6fc36daef6 (c0rc, stack 953d17e53d05) + own stack c6fin (fad4a7b4356f, same product)
FULL_BUSINESS_QA:        FAIL
BUSINESS_JOURNEYS:       7/8   (06 FAIL; 05 and 08 PASS on own stack c6fin only)
FULL_TECHNICAL_QA:       FAIL
E2E_ORG01:               31/31 (C5 flow run by C6 on c0rc; C6 independent API re-execution 128/129, Z19 blocked)
PD02:                    PASS
P0: 0   P1: 2 (FQ-ACT-02, FQ-WF-01)   P2: 4 (FQ-WF-02, FQ-UI-01, FQ-A11Y-02, FQ-ENV-01 environment)   P3: 12
READY_FOR_RC_FREEZE:     NO
READY_FOR_RELEASE:       NO
```
(src: `FINAL_RC_QA_REPORT.md` section 2.) Technical gates at that record: ENVIRONMENT PASS (25/26 at start; ENV-13 closed later by a browser probe), PLATFORM/ADMIN/STUDIO PASS, IAM PASS, TENANT_ISOLATION PASS, WORKSPACE/PROJECT isolation PASS, DYNAMIC_ORG PASS, DATA_SOURCE/QUERY PASS (c6fin; blocked on c0rc), MUTATION PASS for CREATE_RECORD and FAIL for UPDATE/DELETE, ACTION FAIL, WORKFLOW FAIL, QUEUE PASS, PUBLISH/ROLLBACK/PUBLIC RUNTIME PASS, H_C2_07 PASS, RECOVERY PASS (c6fin), RESPONSIVE FAIL (FQ-UI-01), ACCESSIBILITY FAIL (FQ-A11Y-02), BRAND PASS, CHROMIUM PASS, WEBKIT PASS, **FIREFOX BLOCKED_TOOLING**, PERFORMANCE PASS (26/30) (src: section 4).

Caveats stated by QA: by the end of the run C0's checkout had moved to `fabea81` (C2 publish-authorization and C4 runtime-authorization imports); the RUNNING stack did not change, so everything is for `bc5c47f292d0` as served and `fabea81` was not tested; the c0rc API was restarted by someone other than QA at least 6 times (13:13-14:05) and affected cases were re-run; the shared per-IP activation and login limits forced 429 waits (src: `FINAL_RC_QA_REPORT.md` sections 1, 7).

### 5.2 State of the findings after that verdict (from C0 decisions; NOT QA verdicts)
| Finding | Recorded state | Source |
|---|---|---|
| FQ-ACT-02 (P1) | fixed by C4 (`49b2ae98c303`), imported as `eab61be`: **FIXED_PENDING_C6_RETEST**, closed only after C6's independent PASS | D-C0-58 item 2, D-C0-59 item 5 |
| FQ-PERF-01 (P3) | fixed (C5 `8e10fed01ee2`, imported `bd0c2c7`): INTEGRATED, C6 to retest | D-C0-58 item 2 |
| FQ-WF-01 (P1) / FQ-WF-02 (P2) approvals | delivered and imported (`fa42ad2`, migration V33, decision route, D-C0-61): **awaiting independent retest** | D-C0-61; C4 handoff `docs/parallel/audit/C4-FQ-WF-01-approval.md` |
| FQ-ENV-01 (P2, env) | data-target trust store provisioned and functionally proven on `c0rc` (data source test `ok:true`); C6 retest pending | D-C0-59 item 4 |
| FQ-UI-01, FQ-A11Y-02 (P2) | C5 fixes imported (`a47eefe`, merge `2cf2ed6`): **awaiting independent retest** | `git log integration/v2` |
| Runtime authorization re-check, publish authorization tests | imported (`e4896ad`, `fabea81`) after the QA run; not covered by that verdict. C4 interrupted-worker fix (`140dc36`, `WorkflowHardeningTests` 6) imported later; C1 final hardening (D-C0-60) imported: also not covered | D-C0-58 item 1; `git log` |
| Product code at QA time vs now | `bc5c47f292d0` product + the imports above; the SHA of record for acceptance is `{{FINAL_RC_SHA}}` | - |

### 5.3 What must be retested on `{{FINAL_RC_SHA}}` (handoff list)
Journey 05 and Journey 06 with the data-target trust store; UPDATE_RECORD and DELETE_RECORD; non-`recordId` keys (`key=id`, `key=uuid`, `key=order_no`); ambiguous mutation (`IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`); authorization (`ACTION_EXECUTE` / `DATA_MUTATE` / tenant and workspace isolation); FQ-PERF-01 session lifecycle; the C4 runtime-authorization fix; approvals end to end (approve, reject, wrong approver, duplicate and opposite decisions, restart while WAITING); FQ-UI-01 and FQ-A11Y-02; plus every P1/P2 that was open (src: D-C0-59 item 5, `FINAL_RC_QA_REPORT.md` section 8).

---

## 6. Evidence policy

1. **What is in git and what is not.** Text evidence (logs, JSON, TSV, JUnit XML, reports, coordinator notes) is committed under `docs/parallel/c6/evidence/` on `integration/v2` (about 11 MB). PNG screenshots, `.bin` and `.tgz` files are NOT in git: they are in external archives (one per logical batch) (src: `docs/parallel/c6/EVIDENCE_INDEX.md:3-7`).
2. **Archive location.** `/Users/hoangluan/code/xweb-c6-evidence-archives/` (outside any repo, same Mac); `SHA256SUMS` sits next to the archives. It is a single copy on one machine: copy it to durable storage (shared drive / object storage) before relying on it (src: `EVIDENCE_INDEX.md:5`). Checked on 2026-10-11 (read-only listing): 11 archives plus `SHA256SUMS`; `shasum -a 256 -c SHA256SUMS` reported all 11 OK; `SHA256SUMS` equals the hash column of `docs/parallel/c6/evidence-archives.tsv` on `integration/v2` (re-checked 2026-10-11 against local HEAD `28376ded2307`; about 490 MiB in total; `evidence-external-files.tsv` has 5,304 data rows). The 29 pinned migration hashes also match (section 3, T2).
3. **Manifests.** `evidence-archives.tsv` (archive, batch, tested SHA, date, bytes, SHA-256, file and PNG counts, content) and `evidence-external-files.tsv` (path, bytes, SHA-256 of every PNG/TGZ inside the archives: 5304 files) so a screenshot cited in a report can be matched to its archive and verified byte for byte (src: `EVIDENCE_INDEX.md:6-7`).
4. **Archives (name, batch, tested SHA, date, SHA-256 of the archive):**

| Archive | Batch | Tested SHA | Date | SHA-256 |
|---|---|---|---|---|
| `c6-evidence-batch1-f894cc6-baseline.tar.gz` | 1-7 | f894cc6 | 2026-10-06 | `b3f0721b2adfd11cfc00064eeb78015e9862e97d9e1a6f498c83fedb4aaaf082` |
| `c6-evidence-batch2-8e91172-gate-nostack-contract.tar.gz` | 1-7 | 8e91172 | 2026-10-06/07 | `10db6d9443556f481b0f5c5a4099ce87b5b2c193d5b142ca28038ff3bb73e1c7` |
| `c6-evidence-rc-62ce9697cd56.tar.gz` | 9 | 62ce9697cd56 | 2026-10-08 | `5d516b832ee15d8331401fb35fc8d468c647cbc3ce891b1f180bf4e8487aad96` |
| `c6-evidence-user-guide-20261007.tar.gz` | 8 | public build, SHA not provable | 2026-10-07 | `f6b24a83faca751232dcd27f451103f73fc7ce6c84745bcd9e1eff1ddae6dcaa` |
| `c6-evidence-ui-ux-5cc230e491a6.tar.gz` | 10 | 5cc230e491a6 | 2026-10-08 | `903565b605ce17aa9493f7f2f50c83672dff9c0a730c0fee60618a25aedae54a` |
| `c6-evidence-ui-ux-40ee45bc16fe.tar.gz` | 11 | 40ee45bc16fe | 2026-10-08 | `b9779985b3446b37187b5872becba0959becebefd5a39db89d7c52e51e3c4540` |
| `c6-evidence-ui-ux-baseline-rc-62ce9697cd56-partial.tar.gz` | 10 | 62ce9697cd56 (baseline, partial) | 2026-10-08 | `85d9b9e169d62a9ce52b5be29322aacebbf33dae05a57c33a36464a437576204` |
| `c6-evidence-ui-ux-pilot-pass1-no-transforms.tar.gz` | 10 | 62ce9697cd56 (pilot) | 2026-10-08 | `cc7d765944df3909c3cd9d77d11a270ea74af2a1033ece59a42bd3dc02f17330` |
| `c6-evidence-final-rc-ui-chromium.tar.gz` | 12 | bc5c47f292d0 (stack 953d17e) | 2026-10-10 | `03ed0b13616c63f616844e4003da6813b1b1e8fcc85fd3d367f0d321a1e6d4ca` |
| `c6-evidence-final-rc-ui-webkit.tar.gz` | 12 | bc5c47f292d0 (stack 953d17e) | 2026-10-10 | `51753c0151426bd2a663b66771ce5da7fe92bd1a35903a26bbc3d2d87949e1a6` |
| `c6-evidence-final-rc-other.tar.gz` | 12 | bc5c47f292d0 (c0rc) + c6fin (fad4a7b4356f) | 2026-10-10 | `7c7c505b3aab81a19244e91afc15effbe88e168a3a0bd82684b0bac1c680877d` |

(src: `evidence-archives.tsv`; hashes verified 2026-10-11.) Restore: `cd /Users/hoangluan/code/xweb-c6-evidence-archives && shasum -a 256 -c SHA256SUMS`; `tar -xzf <archive> -C docs/parallel/c6/evidence` from the worktree root puts the PNGs back next to the committed text evidence (src: `EVIDENCE_INDEX.md:25-29`).
5. **Deliberately not archived**: generated Gradle `output-events.bin` / `results-generic.bin` (5.4 MB; reproducible; the JUnit XML next to it is committed), caches, `node_modules`, `.next`, `.DS_Store`; no secret is in any evidence file (a scan of every committed text file found only a shell variable reference and a fake `sk-test-123` key posted to the test API) (src: `EVIDENCE_INDEX.md:31-37`).
6. **What an evidence row must carry**: id, area, description, expected, actual, result, class (REAL_BACKEND_E2E / REAL_STACK / HARNESS / MANUAL), owner, note, evidence path; for UI cases the case screenshot path; for the run: the tested SHAs (integration, product, backend, frontend), the served build ids at start and end, the stack name and ports, the flags, and the date (src: TSV columns `CASE_ID ... LAST_TESTED_SHA, LAST_TESTED_DATE, EVIDENCE_PATH, BACKEND_SHA, FRONTEND_SHA, INTEGRATION_SHA, ENVIRONMENT, PROVENANCE`; `final-rc-brief.md`). Evidence of a different SHA is never reused for a new SHA (src: `QA_STATUS.md` batch 5).
7. **Per-run committed layout** for the final run: `docs/parallel/c6/evidence/final-rc-bc5c47f292d0/` with `part1-environment*.json|tsv` (identity at start/end), `final-*.json|tsv|log` per workstream, `journey04-app`, `journey05-data`, `journey06-workflow`, `c6fin/` (own-stack legs and recovery), `c5-flows-*/` (C5 flow reports with `evidence-*.txt` blocks), `part15-brand-*`, `part17-performance`, `ui-gates.json`, `ui/chromium/`, `coordinator-notes.md`. The C5 suite writes per run `report-*.json` and `evidence-*.txt` (blocks: FLOW, RESULT, START, END, FRONTEND_HEAD, BACKEND_HEAD, BACKEND_URL, PROJECT, WORKSPACE, HTTP EVIDENCE, UI ASSERTION, PERSISTENCE ASSERTION, RESTART/RECOVERY, BLOCKER, OWNER) (src: `git ls-tree` of the c6 branch; `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:93`).
8. **Hashes in the acceptance record**: for each result row give the sha256 of the evidence file (or of the archive containing it) and the SHA proof stamp `RC_STAMP.json` (section 8). Applied migrations are pinned by `docs/parallel/c6/migration-hashes.sha256` (29 files V1..V29 at `8e91172`; verified identical on `integration/v2` on 2026-10-11).
9. **Secrets**: harnesses read credentials from files outside the repo and never echo them; passwords, tokens and keys never appear in evidence (src: `EVIDENCE_INDEX.md:37`, `final-rc-brief.md`).
10. **Fixtures left behind**: tenants, workspaces and organizations have no delete route; accounts created by tests are disabled where the product allows (`c6f-*`) (src: `FINAL_RC_QA_REPORT.md` section 7).

### 6.1 Preserved evidence branches (pushed to `origin`, NOT imported into `integration/v2`)

Three test-only branches hold regression tests and reports that were accepted but deliberately not merged (no production code, no migration, no `application.yml` change). They are preserved evidence: their coverage is NOT part of the `integration/v2` test suite until a C0 import. Read them with `git show origin/<branch>:<path>`; never merge `fix/c2-global` as a whole (it is based on an older commit).

| Branch @ SHA | What it adds (tests) | What it proves | Report / source |
|---|---|---|---|
| `agent/c3-data-hardening` @ `c3e3b23ef95e` (2026-10-10, base `fad4a7b`) | `DataMutationOnPostgresTests` (5), `DataQueryScaleOnPostgresTests` (4), `DataSchemaAndPlanAuditTests` (6), `DataPermissionMatrixTests` (3), `OrgReloadPersistenceTests` (1); a polling wait in `PostgresConnectorIntegrationTests` | idempotency reservation is committed before the external write; ambiguous failure persists UNKNOWN and is never retried; 32 simultaneous requests with one key write once; a pool of 3 serves 12 simultaneous external calls; 1,000,000-row query bounded by `maxRows`/page/byte cap and a statement timeout; every adapter statement uses an index (plans on 20k-1M rows); `QUERY_EXECUTE` / `DATA_MUTATE` over real HTTP for every caller kind; organization data identical after a new pool and a real PostgreSQL restart. Verdict: P0 none, P1 none; findings F-1..F-7 (see `KNOWN_LIMITATIONS.md` KL-DATA-17, KL-FLK-10, KL-FLK-11) | `docs/parallel/c3/DATA_HARDENING_REPORT.md`, `DATA_PLAN_AUDIT.md`, `DATA_QUERY_SCALE.md`, `ORGANIZATION_FINAL_BENCHMARK.md` on that branch |
| `fix/c2-publish-hardening` @ `1514789775a5` (2026-10-10, base `fad4a7b`) | `PublishAuthorizationTests` (14), `PublishDeniedTests` (7), `PublishHardeningMatrixTests` (5) | `APP_PUBLISH` on every user-triggerable route (publish, rollback, unpublish, server-runtime, domains); a denied request creates no deployment, event, artifact, key, audit entry or pointer move; 4 truly concurrent publishes are ordered by the scope (one pointer move per activation, one artifact); one deployment delivered as a duplicate queue message, by the sweeper and by three workers at once has one effect; rollback / duplicate rollback / unpublish / roll-forward after consumers restart; stored approval kept by a refused update, reset by a policy change, audited without a secret; "a rollback does not read the policy" pinned as a P1 decision (`KNOWN_LIMITATIONS.md` KL-PUB-12) | `docs/parallel/c2/PUBLISH_HARDENING_MATRIX.md`, `APP_PUBLISH_AUTHORIZATION_AUDIT.md` on that branch |
| `fix/c2-global` @ `e4dc0a87dc42` (2026-10-07) | one commit not in `fad4a7b`: S7 test | a publish refused before acceptance fails at the API and leaves no deployment, event, artifact, key, audit entry or pointer move | commit message; the same test is carried by `fix/c2-publish-hardening` (`PublishDeniedTests`) |

Already imported and therefore NOT in this table: the C4 runtime-authorization tests (`RuntimeAuthorizationTests` 25, `RuntimeAuthorizationApiTests` 12, `e4896ad`), the C2 publish-authorization commit `fabea81`, the C4 interrupted-worker tests (`WorkflowHardeningTests` 6, `140dc36`), the C4 approval tests (D-C0-61 item 7), the C1 hardening tests (`HardeningLifecycleTests`, `HardeningRaceTests`, `HardeningLockTests`, `HardeningSessionTests`, `SecurityIdMatrixTests`, `SecurityRuntimeRoutesMatrixTests`, `GatewayAuthorizerNoOracleTests`, D-C0-60 item 1).


---

### 6.2 Documents that tests and guards read by path (do not move, rename or reword)

Some automated checks open files under `docs/` and compare or parse them. Moving one breaks a gate. The list below is generated from the sources (`grep` of `docs/...` literals in `backend/src/test`, and in `tests/`, `scripts/`, `packages/`); a literal that appears only in a comment is still listed: treat every entry as pinned.

**Backend tests (`backend/src/test`):**
- `docs/contracts/v2/action-workflow.md`
- `docs/contracts/v2/app-definition.md`
- `docs/contracts/v2/management-api.md`
- `docs/contracts/v2/published-runtime.md`
- `docs/contracts/v2/runtime-api.md`
- `docs/contracts/v2/tenant-permission.md`
- `docs/parallel/INTEGRATION_V2.md`
- `docs/parallel/MAC_INTEGRATION_CHECKLIST.md`
- `docs/parallel/WEB_SECURITY_CONFIG.md`
- `docs/parallel/audit/T1-isolation-audit.md`
- `docs/parallel/c0/undo/U28__data_runtime.sql`
- `docs/parallel/c0/undo/U29__workflow_run_persistence.sql`
- `docs/parallel/c0/undo/U33__approvals.sql`
- `docs/parallel/c1/B-C1-13-admin-transfer-ownership.md`
- `docs/parallel/c1/organization-employee-contract.md`
- `docs/parallel/c1/undo/U26__tenant_foundation.sql`
- `docs/parallel/c2/PUBLISHED_RUNTIME_TOPOLOGY.md`
- `docs/parallel/c2/PUBLISH_API_CONTRACT.md`
- `docs/parallel/c2/undo/U30__deployment_rollback_and_scope_lease.sql`
- `docs/parallel/c3/undo/U32__dynamic_organization.sql`

**Frontend tests, guards and scripts (`tests/`, `scripts/`, `packages/`):**
- `docs/API_CONTRACT.md`
- `docs/BRAND_GUIDELINE.md`
- `docs/C5_REAL_BACKEND_E2E_MATRIX.md`
- `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`
- `docs/GOLDEN_COMPANY_FLOW.md`
- `docs/OPERATIONS.md`
- `docs/contracts/permission-model.md`
- `docs/contracts/v2/action-workflow.md`
- `docs/contracts/v2/app-definition.md`
- `docs/contracts/v2/data-runtime.md`
- `docs/contracts/v2/integration-contract.md`
- `docs/contracts/v2/runtime-api.md`
- `docs/contracts/v2/tenant-permission.md`
- `docs/parallel/MIGRATION_LEDGER.md`
- `docs/parallel/V1_LOCAL_TARGET.md`
- `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md`
- `docs/parallel/c0/PUBLIC_API_PINNING.md`
- `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md`
- `docs/parallel/c1/h-c1-04-project-scoped-auth-me.md`
- `docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md`
- `docs/parallel/c2/PUBLISH_API_CONTRACT.md`
- `docs/parallel/c3/MANAGEMENT_API.md`
- `docs/parallel/c5/ICON_LICENSES.md`
- `docs/parallel/c5/MAC_RUN_2026-10-06.md`
- `docs/parallel/c5/PHASE3_AUDIT.md`
- `docs/parallel/c5/PROCESS_SAFETY.md`
- `docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md`
- `docs/parallel/c5/audit/R2-i18n-theme.md`
- `docs/parallel/c5/audit/S3-glossary.md`
- `docs/parallel/c5/audit/S4-realstack-baseline.md`
- `docs/parallel/policy.md`

The finalization archived only documents outside these lists and outside every link from another document (`docs/archive/2026-10-finalization/README.md`).

## 7. Known QA gaps (do not read a PASS as covering these)
Firefox BLOCKED_TOOLING (not PASS, not product failure); Safari NOT_TESTED, "WebKit" = Playwright WebKit; axe is not a WCAG certification; performance numbers were taken on a loaded 16 GB machine; recovery was exercised on `c6fin`, not on `c0rc`; hard crash with a workflow step in flight, portal redeploy, `ORG_STRUCTURE_BUSY` shape, idle-timeout expiry, retry of a retryable failure, VIEW-vs-MANAGE independence at runtime (Z19), immutable-asset cache after unpublish, Studio rollback/unpublish buttons (API only), the M-052 DEFAULT-primary variant were not executed; real rows through the PD02 public query on `c0rc` were BLOCKED_ENV; the public API was not used for any backend verification (src: `FINAL_RC_QA_REPORT.md` sections 4, 6). The full open-item register is `KNOWN_LIMITATIONS.md`.

---

## 8. Final acceptance record

> **TEMPLATE. Do not treat any cell as a result until it is filled by the person who ran the test.** This section is the single place where the acceptance run of the **Golden Company flow** on the final release candidate is recorded. Everything above this section is background and dated history.

| Field | Value |
|---|---|
| Final release candidate SHA (`{{FINAL_RC_SHA}}`) | `{{FINAL_RC_SHA}}` |
| Integration head when run | `{{INTEGRATION_SHA}}` |
| Stack name and ports | `{{STACK_NAME}}` / `{{PORTS}}` |
| SHA proof (`rc-verify.mjs`) | `{{SHA_ALL_MATCH_YES_NO}}`, stamp path `{{RC_STAMP_PATH}}`, sha256 `{{RC_STAMP_SHA256}}` |
| Served build ids at start / end | Platform `{{BID_PLATFORM_START}}` / `{{BID_PLATFORM_END}}`; Admin `{{BID_ADMIN_START}}` / `{{BID_ADMIN_END}}`; Studio `{{BID_STUDIO_START}}` / `{{BID_STUDIO_END}}` |
| Flags in this stack | organization persistence `{{ON_OFF}}`, publish configs `{{ON_OFF}}`, public data runtime `{{ON_OFF}}`, data target `{{ON_OFF}}` |
| Migrations applied (max / count / failed) | `{{MAX_VERSION}}` / `{{COUNT}}` / `{{FAILED}}` |
| Executed by / date | `{{QA_OWNER}}` / `{{DATE}}` |
| Public portals / API releases at the time | `{{PUBLIC_FRONTEND_SHA}}` / `{{PUBLIC_API_SHA}}` |

"Golden Company flow": no document read defines this term. For this template it is taken to mean the ordered end-to-end business chain Journey 01 -> 08 (company onboarding, organization, IAM, build, data, action/workflow with approval, publish, recovery) plus its technical gates, run on one stack built from `{{FINAL_RC_SHA}}`. UNVERIFIED: the owner must confirm or replace this definition before the run.

Result vocabulary: `PASS` / `FAIL` / `BLOCKED_ENV` / `BLOCKED_TOOLING` / `NOT_RUN` (a BLOCKED or NOT_RUN row is never a pass). Evidence path = repository-relative path of the committed text evidence plus archive name for screenshots, with sha256. Date = UTC date of the run.

### 8.1 Business journeys
| Test | Result | Evidence path | Date |
|---|---|---|---|
| Journey 01 Company onboarding (API + UI: PL01, SUPER01, ADMIN01) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 02 Dynamic organization (API + E2E-ORG01) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 03 IAM / permission lifecycle (IAM, adversarial, AD01-03, USER01, SEC01) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 04 Build an app from zero | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 05 Data-backed app (data-target trust store present; real rows; real INSERT; SSRF refusals; replay) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 06 Action / workflow business process incl. APPROVAL (approve, reject, wrong approver, duplicate/opposite decision) and UPDATE_RECORD / DELETE_RECORD | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 07 Publish / public site / rollback / unpublish (H-C2-07 409/422, PD02) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Journey 08 Operations / recovery (API restart with a WAIT run and idempotency key; render pause; MinIO pause) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |

### 8.2 Technical gates
| Test | Result | Evidence path | Date |
|---|---|---|---|
| T1 Backend full uncached (classes / tests / failures / errors / skipped = `{{COUNTS}}`) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T2 Migrations V1..V33 (fresh DB, stack history, pinned hashes, ledger guard) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T3 Frontend gate (`npm run gate:frontend`, unit count `{{COUNT}}`) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T4 Browser harness (all specs; checks `{{COUNT}}`) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T5 Real-backend E2E (flows run: `{{FLOW_IDS}}`) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T6 Independent API / security / isolation regression | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T7 Secret scan (history + tree) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T8 Recovery / restart | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T9 Infra tests (processes, portals, public pinning, API pinning) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T10a Responsive (9 widths, strict clipping detector) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T10b Accessibility (axe, dialog focus restore, keyboard) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T10c Brand and no-localStorage-fallback | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T10d Chromium / WebKit / Firefox (Firefox status stated, not omitted) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T10e Performance | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T12 SHA proof before and after the run | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| T13 Dependency audit | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |

### 8.3 Retest of items open at the last verdict
| Test | Result | Evidence path | Date |
|---|---|---|---|
| FQ-ACT-02 UPDATE_RECORD / DELETE_RECORD with `key=id`, `key=uuid`, `key=order_no` | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| FQ-WF-01 / FQ-WF-02 approval runtime and decision route | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| FQ-ENV-01 data-target trust store on the acceptance stack | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| FQ-UI-01 `/admin/employees` at 360/390/768/1440 | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| FQ-A11Y-02 builder dialog focus restoration after Escape | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| FQ-PERF-01 `/auth/me` request count | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| C4 runtime-authorization fix (`APP_USE` + `WORKFLOW_EXECUTE` per effectful step) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| C2 publish authorization (publish / rollback / unpublish from current DB state) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| C1 final IAM hardening: DECISION A (gateway does not check data-source ownership; C3 answers 404 for a foreign or unknown data source, no existence oracle) | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |
| Other P1/P2 listed in `KNOWN_LIMITATIONS.md` marked OPEN at this date | `{{RESULT}}` | `{{EVIDENCE_PATH}}` | `{{DATE}}` |

### 8.4 Verdict (fill last)
```
RC_SHA_TESTED:       {{FINAL_RC_SHA}}
FULL_BUSINESS_QA:    {{PASS_FAIL}}
BUSINESS_JOURNEYS:   {{N}}/8
FULL_TECHNICAL_QA:   {{PASS_FAIL}}
P0: {{N}}   P1: {{N}}   P2: {{N}}   P3: {{N}}
READY_FOR_RC_FREEZE: {{YES_NO}}
READY_FOR_RELEASE:   {{YES_NO}}
Signed off by:       {{QA_OWNER}} on {{DATE}}; release decision: {{RELEASE_DECIDER}}
```

---

## Sources read
- Local `integration/v2` HEAD `28376ded2307` (2026-10-11): `docs/parallel/c6/*` (FINAL_RC_QA_REPORT full, final-rc-brief, EVIDENCE_INDEX, evidence-archives.tsv, QA_STATUS batches 4-9, QA_MASTER verdict/gates, QA_MASTER_NORMALIZED.tsv (counts computed), E2E_MATRIX, BUGS table, MAC_QA_HANDOFF, migration-hashes.sha256 (compared with the files), harness/final-gate.sh, final-env-check.mjs head, evidence/final-rc-bc5c47f292d0/coordinator-notes.md and ui-gates.json); `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`, `C5_REAL_BACKEND_E2E_MATRIX.md`, `docs/parallel/c0/FRONTEND_GATE.md`, `PROCESS_SAFETY.md`, `docs/parallel/DECISIONS.md` (D-C0-54..61), `BLOCKERS.md`, `MIGRATION_LEDGER.md`, `docs/BACKUP_DR.md`, `docs/SECURITY.md` (grep), `docs/parallel/c5/C5_NEXT_SESSION_HANDOFF.md`, `docs/parallel/c5/e2e-stack.sh`, `scripts/rc-verify.mjs` (expects 33), `tests/browser/README.md`, `package.json`, `backend/build.gradle.kts`, `git ls-tree` of `tests/e2e-real` and `db/migration`.
- `/Users/hoangluan/code/xweb-c6-evidence-archives/`: directory listing, `SHA256SUMS`, `shasum -a 256 -c` (read-only, 2026-10-11).
- Evidence branches (read-only): `origin/agent/c3-data-hardening` (`DATA_HARDENING_REPORT.md`, commit messages and stat), `origin/fix/c2-publish-hardening` (`PUBLISH_HARDENING_MATRIX.md`, commit messages and stat), `origin/fix/c2-global` (commit message).
- Not read: `UI_UX_REGRESSION_5cc230e.md`, `UI_UX_RETEST_40ee45b.md`, `USER_GUIDE_QA*.md`, `REGRESSION_MATRIX.md`, the bodies of the `final-*.mjs` harness scripts (journey-to-script mapping is by file name), evidence JSON/TSV bodies other than the ones named, the contents of the archives.

## Open questions / UNVERIFIED
1. "Golden Company flow" is not defined in any document read; section 8 assumes Journey 01 -> 08. The owner confirms or replaces this definition before the run.
2. The C6 verdict for batches 10-11 (UI/UX regression and retest) was not read.
3. No gate result exists yet for `{{FINAL_RC_SHA}}`; the "last recorded" cells are dated. `scripts/rc-verify.mjs` was changed to expect migration 33 but was not run on a V33 stack by this pass.
4. The retests in 8.3 (FQ-ACT-02, FQ-WF-01/02, FQ-ENV-01, FQ-UI-01, FQ-A11Y-02, FQ-PERF-01, C4 authorization, C2 publish authorization, C1 hardening) have not been performed by QA.
5. Coordinator-reported facts not found in a repository document: `gate:frontend` GREEN on `205707e` with 539 unit tests; secret scan 454 commits no leaks; Docker VM about 8 GB.
6. The journey-to-harness mapping in section 4 is inferred from file names; open the scripts to confirm.
7. The archives exist on one machine only; durable off-machine copies are not recorded.
