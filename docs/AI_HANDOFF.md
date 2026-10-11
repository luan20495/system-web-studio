# AI handoff

Written for the next engineering team (human or AI) that takes over XWEB. It assumes you have **no access to any earlier conversation**. It tells you where the truth is, how to build and test, what you may and may not do, and what is known to be wrong. Facts here are verified against the repository at `9d2fc8b9758077ac880d80cfbec767e2e776f9c5`; where a value changes over time it carries a date.

## Read these files in order

1. `README.md`
2. `docs/PROJECT_STATUS.md`
3. `docs/ARCHITECTURE.md`
4. `docs/SECURITY_AND_PERMISSION.md`
5. `docs/GOLDEN_COMPANY_FLOW.md`
6. `docs/OPERATIONS.md`
7. `docs/QA_FINAL.md`
8. `docs/KNOWN_LIMITATIONS.md`

Then, as needed: `docs/DYNAMIC_ORG.md`, `docs/DATA_RUNTIME.md`, `docs/ACTION_WORKFLOW.md`, `docs/PUBLISH_RUNTIME.md`, `docs/BRAND_GUIDELINE.md`, `docs/DECISIONS.md` (map of the decision record) and the frozen contracts in `docs/contracts/v2/`. Everything under `docs/parallel/` is the working record of the build (decisions, handoffs, evidence); it is authoritative only for the item you look up and carries `SUPERSEDED_BY` notes when a canonical document replaced it (`docs/archive/2026-10-finalization/README.md`).

## Repository, source of truth, local path

| Item | Value |
|---|---|
| REPOSITORY | `https://github.com/luan20495/system-web-studio` |
| SOURCE OF TRUTH BRANCH | `integration/v2` (`main` is older and is **not** the implementation: last commit `da348ff`) |
| SOURCE OF TRUTH SHA | `9d2fc8b9758077ac880d80cfbec767e2e776f9c5` |
| RELEASE CANDIDATE SHA | `75643ad8700f42df05c3151c1d0875b063c81620` (the commit the golden company acceptance ran on) |
| PUBLIC DEPLOYMENT (pinned, older) | portals `bc5c47f292d0`, API `1006cbf441f6` |
| LOCAL PATH | `/Users/hoangluan/code/HBL` (the canonical checkout). It was not renamed: the local compose project `hbl`, the public pinned deployment and running stacks use this path |
| START A TASK FROM | a branch cut from `integration/v2`, in a worktree you create for the task (below) |

## Working model

C0..C7 were **logical roles**, not folders or permanent worktrees: C0 architect / integrator (contracts, imports, migrations, wiring), C1 identity / tenancy / permissions, C2 app definition / schema / publish, C3 data, C4 action / workflow / approval, C5 web (portals, Builder), C6 independent QA, C7 release decision. You may keep the roles as a way to divide responsibility (`docs/ARCHITECTURE.md` section 13) but do **not** keep one long-lived directory per role or per sub-task.

Recommended filesystem: the canonical checkout (`HBL`), one `xweb-fix` worktree **only while a P0/P1 is open** (remove it after the merge), `xweb-release` only while building or deploying a release, `xweb-qa` only for independent QA, and the evidence archives (`/Users/hoangluan/code/xweb-c6-evidence-archives`, kept outside git, verified by `SHA256SUMS`). Never create "final2 / repair2 / overlay2 / tests2" style copies.

## Build and test commands

JDK **21** is mandatory. On the maintainer's Mac the default `java` is 11: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

```bash
# backend (Testcontainers needs Docker; the test JVM heap is 2 GB, set in build.gradle.kts)
cd backend && ./gradlew test
# gate-grade run (what QA and the integrator use): uncached, real execution
./gradlew clean test --no-build-cache --rerun-tasks --no-daemon -Pkotlin.daemon.jvmargs=-Xmx3g -Dorg.gradle.jvmargs=-Xmx2g
# frontend
npm ci && npm run gate:frontend         # guards, typecheck, unit tests, 3 production builds, bundle scan (~90 s)
./scripts/secret-scan.sh                # gitleaks: all history + working tree
node scripts/golden-company-flow.mjs    # the 27-step acceptance chain against a running stack (docs/GOLDEN_COMPANY_FLOW.md)
```

**A test run only counts if the `:test` task really executed.** `org.gradle.caching=true` is the default: a result shown as `FROM-CACHE` or `UP-TO-DATE` ran nothing (QA found a false GREEN this way). Use `--no-build-cache` for evidence and read the XML in `backend/build/test-results/test/`. Do not run `gradlew --stop` on the shared machine and do not run two heavy jobs at once (see "Resource limits").

Many backend tests read files under `docs/` (contracts, undo scripts, publish API contract): **do not move or rewrite those files**; the list is in `docs/QA_FINAL.md`.

## Environment variables and secrets

The complete table is `docs/OPERATIONS.md` section 12. The essentials: `LOCAL_ADMIN_PASSWORD` (local login), `SECRETS_MASTER_KEY` (encrypts every stored credential), the datasource / Redis / RabbitMQ / MinIO settings, and the feature switches that are all **OFF by default**: `DATA_PLATFORM_ENABLED`, `WORKFLOW_ENABLED` (+ `WORKFLOW_QUEUE=amqp` in any real environment), `PUBLISH_CONFIGS_ENABLED`, `SITES_PUBLIC_DATA_ENABLED` (+ `SITES_DATA_API_BASE`), `ORGANIZATION_PERSISTENCE_ENABLED`. Turning one on is a recorded decision.

**Secret handling.** Secrets live in `.env` and `.run/` (both git-ignored) and, for isolated stacks, in `~/.xweb-e2e-stack/<stack>/stack.env` (mode 600, outside the repository). Never print a secret into a terminal log, a document or an evidence file; never commit one; `.env.example` holds only loopback placeholders. Stored application secrets are AES-GCM encrypted and write-only. The data-target dev CA (`.run/data-target`) must never be installed on a production host.

## Docker stacks and ports

Local development stack (`PORTALS=1 ./scripts/run-local.sh`): API 8080, Platform 3001, Admin 3002, Studio 3003, sites gateway 18088, render worker 18095, data target 15440; PostgreSQL / Redis / MinIO / RabbitMQ on 15432 / 16379 / 19000 / 15674. Public pilot (compose project `hblpub`): API 18081, portals 3201-3203 (gateway 3210), sites gateway 28088. Isolated stack for a release candidate or acceptance (`docs/parallel/c5/e2e-stack.sh`, ports chosen with `E2E_*_PORT`): its own containers, state directory and a backend worktree created from the exact SHA; `up` / `status` / `down --infra --worktree`; the SHA proof is `node scripts/rc-verify.mjs --stack <name> --sha <sha>`. Full port map and disagreements between documents: `docs/OPERATIONS.md` section 5. Only one isolated stack of yours at a time.

## Policies

- **MIGRATION POLICY.** Forward-only Flyway. Only one role (the integrator) allocates numbers, in `docs/parallel/MIGRATION_LEDGER.md`, before the file exists. One number = one task. Applied in ascending order; `outOfOrder` stays off; **never edit a migration that is on `integration/v2`**. V31 is a permanent void gap, V32 = dynamic organization, V33 = approvals; the next free number is **V34** (none allocated). Every new table: `tenant_id NOT NULL REFERENCES tenants(id)`, composite foreign keys for workspace-scoped data, a retention statement, a guarded manual undo under `docs/parallel/<owner>/undo/`. The guard `tests/guards/migration-ledger.mjs` and `OrgV32FlywayTests` / `ApprovalsV33FlywayTests` pin the numbering; update them in the same commit when you add a number.
- **BRANCH POLICY.** `integration/v2` is the source of truth. Work on a branch `agent/<topic>`, `fix/<topic>` or `feat/<topic>`; reach `integration/v2` by a non-fast-forward merge commit `import(<role>): <branch> @ <sha12> - <summary>` after reviewing the exact delta (files, owner scope, no stray migration or contract change). Never merge into `main`. No `push --force`, no `reset --hard`, no `clean -fd`, no history rewrite, no GitHub Actions (by decision). One task = one commit series + its own test run.
- **RELEASE POLICY.** The release unit is a SHA on `integration/v2`. A candidate is accepted only after: all imports are on the branch; the uncached backend gate, the frontend gate and the browser harness are green; Flyway V1..latest on a clean database passes; the secret scan is clean; one isolated stack is built from that exact SHA with a SHA proof; and the golden company flow ran on it. The public deployment moves only by an explicit `deploy` of an approved SHA (`scripts/public-portals.sh`, `scripts/public-api.sh`).
- **QA POLICY.** QA evidence names the SHA, the command, the result and the evidence path; a result from another SHA, a cached run, a mock or a local merge stack is not evidence for the release. P0 and P1 must be zero for a release; P2/P3 may remain only when documented and explicitly non-blocking (`docs/KNOWN_LIMITATIONS.md`). Evidence archives are preserved with hashes.
- **PROCESS SAFETY (shared machine).** Never kill a process by name or by port. Stop only pids or process groups you started, or use `scripts/owned-process.mjs`. The guard `tests/guards/process-safety.mjs` is part of `npm run gate:frontend`.

## Resource limits

Docker VM memory is about 8 GB. During the final gates the host had ~90% swap in use and load 5-10; a starved host makes public requests time out and tests fail spuriously (`INC-2026-10-10` in `docs/OPERATIONS.md` section 9). Rules: run heavy jobs sequentially (a full backend run, the browser harness, a Next production build and a stack bring-up never together); precheck RAM, swap, load and container count and record them with the run; keep at most one isolated stack; tear down stale stacks (`down --infra --worktree`); do heavy work outside demos and public use.

## Known flaky tests and known limitations

- No test is known to be flaky at the release candidate. History: `LockdownSettingsTests` failed in full runs with `503 SIGNUP_FULL` because all test classes share one PostgreSQL and the suite grew past `app.signup.max-users` (500); it is fixed in the test (D-C0-62, `docs/KNOWN_LIMITATIONS.md` `KL-FLK-01`). If a test fails only in a full run and passes alone, suspect shared state (users, settings, Redis counters) before suspecting load, and read the failing assertion's status code.
- Anything else flaky is listed in `docs/KNOWN_LIMITATIONS.md`, with its owner and workaround. Read that file before estimating work: it lists the open IAM findings, the deferred features (approval notifications and inbox, a dedicated approval permission, candidate activation, cross-tenant sharing), and the fact that a browser UI for starting LIVE workflows and deciding approvals does not exist (API-only).

## Contracts and rules you must not change without a decision

Change a frozen contract (`docs/contracts/v2/*`) only after a numbered entry exists in `docs/parallel/DECISIONS.md`. In particular:

1. The **permission vocabulary** (21 canonical codes) and the matrix: default deny; a role name, an organization relation, a position or a grade is never authorization; the tenant is derived by the server, never taken from input; an unknown or foreign resource is the same `404` (no existence oracle).
2. **Decision A** (`D-C0-60`): the gateway authorizer decides on permission and scope only; a foreign or unknown data source is C3's canonical `404`. Do not add a data-source ownership check at the authorizer.
3. **Mutations**: no browser-callable mutation route; writes happen only through actions; an executor-level timeout or interruption of a mutating action is `IDEMPOTENCY_OUTCOME_UNKNOWN` (not retryable, not routed, not compensated).
4. **Credentials** only on the server, encrypted, never returned; outbound addresses only through `PublicAddress` / the pinned transports; do not fork the SSRF guard.
5. **Page Schema + `SchemaPatchEngine` + `PageSchemaValidator`**, immutable project versions, the component registry, the AI Gateway, audit append-only, the publish pipeline (lease with fencing token, compare-and-set pointer, verify-before-serve), and the separation of render, build and runtime planes.
6. **Approvals** use `WORKFLOW_MANAGE` and the approver snapshot taken when the step starts (`D-C0-61`).
7. Backward compatibility: the existing API, the `projects` table, Page Schema and `STATIC_APP` keep working.

## First things to do on day one

1. Read the eight files above. 2. Create the environment (`README.md`), run `npm run gate:frontend` and a targeted backend test to prove your setup. 3. Run `docs/GOLDEN_COMPANY_FLOW.md` against a local stack once, by hand or with the runner, to see the product work end to end. 4. Open `docs/KNOWN_LIMITATIONS.md` and pick the work from there; propose any contract or migration change as a decision first.
