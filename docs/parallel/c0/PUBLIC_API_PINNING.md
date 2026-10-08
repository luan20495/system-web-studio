# Explicit public API release pinning (D-C0-50)

Invariant: **API recovery is not API deployment.** What the public API (port 18081) runs is an immutable, approved backend artifact. A crash, a restart, `public-up.sh`, the watchdog or a changed working tree can restart *that jar*; none of them can compile, build or choose another one. Changing it is one explicit act: `deploy-api <sha>`.

Owner: C0. Code: `scripts/lib/public-api-release.mjs`, `scripts/public-api.mjs` (CLI), `scripts/public-api.sh` (wrapper); wired into `public-up.sh` (API recovery = `public-api.sh up`), `public-down.sh`, `public-status.sh`. Tests: `tests/infra/public-api-pinning.test.mjs` (`npm run test:infra:api`). Sibling of D-C0-49 (portals, `PUBLIC_DEPLOYMENT_PINNING.md`); C2 published-site release semantics are untouched. No backend production code, no migration, no Cloudflare / DNS / tunnel change.

## Release model

```
.run/public/api/
  approved.json                 { releaseId, releaseJsonSha256, approvedAt, approvedBy, previousKnownGood, history[≤20] }   (atomic rename)
  releases/<sha12>-<cfg8>/
    app.jar                     the Spring Boot jar that runs (java -jar <this file>)
    release.json                immutable record, NO secrets (below)
  release.lock                  one deploy / rollback / up at a time
.run/public/api.owned.json      owned-process identity (pid, start time, command, cwd, releaseId, jar sha256, config fingerprint)   (D-C0-48)
```

`release.json`: `sourceSha`, `backendTreeSha` (the `backend/` tree of that commit), `buildFingerprint` (= backend tree + Java major: what the build read), `configFingerprint` (= the **non-secret** runtime configuration + Java major), `runtimeConfig` (the pinned non-secret `KEY=VALUE` list), `secretKeyNames` (names only), `jar {sha256, size, entryDigest}`, `schema {versions[{version, checksum}], maxVersion, expandOnly[]}`, `verification {tests: full|skipped|"not-run (adopted)", rehearsal}`, `javaMajor`, `kind` (`built` | `adopted`) and for adopted ones the evidence.

* The API process gets a **clean environment**: a small OS base (`PATH`, `HOME`, `LANG`, `TMPDIR`, `USER`, `JAVA_HOME`) + the pinned non-secret configuration + the secrets of `public.env` (read at start, handed to the child, never recorded). Nothing else of the caller's environment is inherited (the old launcher inherited the whole shell).
* Secrets are keys matching `SECRET|PASSWORD|TOKEN|API_KEY|…_KEY|SALT|CREDENTIAL|PRIVATE|INVITE_CODE` plus the `DATABASE|RABBITMQ|REDIS|MINIO_ROOT` users. A restart uses the **pinned** non-secret values even if `public.env` changed (reported as `config differs from public.env now`); a secret rotation just works because secrets are external.
* Retention: approved + previous known-good only; the approved release is never deleted (prune refuses).

## Commands (`scripts/public-api.sh <cmd>`)

| command | what it does | what it never does |
|---|---|---|
| `status [--json]` | PORT, PID, HEALTH, RUNNING_SOURCE, APPROVED_SOURCE, INTEGRATION_SOURCE, ARTIFACT_DIGEST, CONFIG_FINGERPRINT, STATE (+ rollback readiness, drift). Exit 0 only for `CURRENT_APPROVED` | change anything |
| `up` | start the approved jar if the API is not running | build, call Gradle, read the source tree; start with no approved release (`NO_APPROVED_RELEASE`, exit 5) |
| `restart` | stop (owned process only) + start the **same** approved jar | rebuild; move the pointer |
| `down` | stop the API this tool started | touch a foreign listener |
| `prepare-api <sha> [--skip-tests] [--no-reproduce]` | the immutable release only: snapshot → full backend tests → **two independent clean builds that must be byte-identical** (`NON_REPRODUCIBLE_RELEASE_BUILD`, exit 10, else) → `release.json`. Approves and starts nothing | touch the running API |
| `reproduce-api <sha>` · `validate-api <id>` · `verify-running-api` | two clean builds compared (nothing recorded) · the temporary-port proof of a release · is the process on 18081 the approved release (owned identity, open jar inode, sha256, readiness) | approve or start anything |
| `deploy-api <sha> [--skip-tests] [--expand-only] [--reproduce] [--replace-legacy-unverified]` | explicit source → (reuse the prepared release or build one) → candidate on a temporary port against a scratch DB → schema verdict against the real DB → **stop the current API → start the artifact on 18081 → prove the process IS the release → only then write `approved.json`** (atomic); on failure the old process is restored and the pointer has not moved | move the pointer before 18081 is proven; deploy implicitly; replace a legacy-started API without `--replace-legacy-unverified` |
| `rollback-api [id]` | previous known-good (or the given retained id): schema verdict → start on temp + scratch → pointer → replace | rebuild; roll back across an incompatible schema |
| `init-api --from-running [--source <sha>] [--dry-run]` | pin the API that runs **now**, from evidence | accept anything it cannot prove |
| `releases-api · prune-api · verify-api` | list / drop unreferenced / deep-verify the approved jar (sha256) | delete approved or previous |

Exit codes: 0 ok · 1 failed / not current · 3 `FOREIGN_PROCESS` · 5 `NO_APPROVED_RELEASE` · 6 `APPROVED_API_BUILD_MISSING` / `API_RELEASE_TAMPERED` · 7 `CANDIDATE_*` / `ACTIVATION_*` · 8 `LOCKED` · 9 schema (`ROLLBACK_SCHEMA_INCOMPATIBLE`, `FLYWAY_CHECKSUM_MISMATCH`, `SCHEMA_DIRTY`, `SCHEMA_CHECK_UNAVAILABLE`) · 64 usage.

### Fail closed
* No approved release → `up` / `restart` refuse, nothing is built or started, HEAD is never assumed.
* Approved jar missing / truncated / modified (size, sha256) or `release.json` altered (hash ≠ pointer) → `APPROVED_API_BUILD_MISSING` (exit 6). The tool never builds HEAD, never picks "the newest jar", never downloads one. A restored artifact starts again with no build.
* A foreign listener on 18081 (not the recorded identity: pid + start time + command + cwd, D-C0-48) is never signalled: `up` / `restart` / `deploy-api` / `rollback-api` refuse with exit 3, `down` leaves it alone. A stale record whose pid now belongs to another process is dropped without signalling it. The API that the **old** lifecycle started is "legacy": refused too, with the hint to run `init-api --from-running`.

## Status states
`CURRENT_APPROVED` (suffix `BEHIND_INTEGRATION +N backend commits` only when `backend/` changed after the approved source; otherwise `integration +N commits, backend unchanged`) · `STOPPED` · `CRASH_LOOP` (stopped while the watchdog's marker exists) · `APPROVED_API_BUILD_MISSING` · `RUNNING_UNAPPROVED` (another release, or the running process's recorded jar / config fingerprint is not the approved one: **a config-incompatible artifact is never reported as the approved runtime**) · `UNHEALTHY` (listening, readiness not UP) · `NO_APPROVED_RELEASE` · `FOREIGN_PROCESS` · `ROLLBACK_SCHEMA_INCOMPATIBLE` / `FLYWAY_CHECKSUM_MISMATCH` / `SCHEMA_DIRTY` (an activation failed and the previous release could not be restarted because of the schema: recorded in `api/incident.json`, API stopped, operator decision). `status` also prints the rollback verdict of the previous known-good against the database as it is now.

## Database migration policy (exact)

Audit: Flyway community (`spring-boot-starter-flyway`, `flyway-database-postgresql`), `spring.flyway.enabled=true`, forward-only versioned migrations `V1…V30`; **no down migrations**; `ddl-auto: validate`; Flyway's default `ignore-migration-patterns=*:future` makes an *older* jar on a *newer* schema start with a warning only. The manual, human-run undo scripts `docs/parallel/c0/undo/U28`, `U29` are outside Flyway and are not used by this tooling.

Verdict of an artifact against the **real** database (read-only query of `flyway_schema_history`; Flyway's checksum algorithm was reproduced and verified equal on all 30 applied rows):

| verdict | meaning | effect |
|---|---|---|
| `COMPATIBLE` | every applied migration is carried by the artifact with the same checksum (migrations the DB lacks are *pending*: the artifact applies them at start — a **one-way** step) | deploy / rollback may continue |
| `ROLLBACK_SCHEMA_INCOMPATIBLE` | the database is **ahead** of the artifact (it holds migrations the artifact does not carry) and they were **not declared expand-only** | exit 9, nothing stopped, approved unchanged |
| `FLYWAY_CHECKSUM_MISMATCH` | an applied migration differs from the artifact's file | exit 9 |
| `SCHEMA_DIRTY` | a failed migration is recorded | exit 9 |
| `SCHEMA_CHECK_UNAVAILABLE` | the database cannot be queried | exit 9 (fail closed: it cannot be proven compatible) |

**Rollback policy: an artifact rollback is allowed only while the database is not ahead of the target artifact, or every migration it is ahead by was declared expand-only by the release that introduced it** (`deploy-api --expand-only`: the operator asserts that the new migrations are additive and the previous release keeps working on them; recorded in `release.json.schema.expandOnly`, shown by `status`). The default is NOT expand-only: after a release that adds a migration, `rollback-api` fails closed with `ROLLBACK_SCHEMA_INCOMPATIBLE`, and so does deploying an older commit. When the declaration exists the rollback target is additionally started on a scratch copy of the current schema before the pointer moves, which proves the claim. Down migrations are not implemented.

An activation that fails after the pointer moved returns to the previous release **only if the schema verdict still allows it**; otherwise the previous release is approved again but not started, `incident.json` records `ROLLBACK_SCHEMA_INCOMPATIBLE`, the API stays stopped and the exit is `ACTIVATION_FAILED_ROLLBACK_BLOCKED` — an explicit incident, never a silent start of an incompatible jar.

## Release build and candidate verification
**Release build (clean, isolated, reproducible).** Exact SHA snapshot (`backend/` + `docs/`: the backend tests read `../docs` for contracts and the manual undo scripts; the jar depends on `backend/` only; never the working tree, never another worktree's `build/`) → JDK verified first (`JDK_MISMATCH` unless Java 21 and `JAVA_HOME` agree) → `./gradlew clean bootJar -x test --no-build-cache --rerun-tasks --no-daemon -Pkotlin.incremental=false` with an **isolated `GRADLE_USER_HOME`** (`.run/public/api/gradle-home`: no shared build cache, no daemon of another agent; `GRADLE_OPTS` / `JAVA_TOOL_OPTIONS` are removed; `./gradlew --stop` is never used). `prepare-api` builds twice in separate snapshot directories and requires the two **whole-jar sha256 to be equal** (Gradle 9 writes archives in a stable order without timestamps). `release.json` records the JDK version, Gradle distribution, build command, the two digests, and the full backend test counts (`total / failures / errors / skipped`, skipped class names, failing test names if any; on failure the log and result XMLs are kept in `.run/public/api/failed/`).

**Candidate verification (`deploy-api`, `rollback-api`, `validate-api`):**
1. Backend tests: full, clean, uncached (`--skip-tests` is recorded in the release, not hidden).
2. **Candidate started on a temporary port** against a **scratch database** created in the same Postgres: by default an EMPTY database (`PUBLIC_API_REHEARSE=fresh`): Flyway applies every migration from scratch and the real application starts with the real Redis, RabbitMQ and MinIO. `schema` (structure + `flyway_schema_history` only, no data) is an explicit opt-in: it was measured NOT to work for this application (the rows that migrations insert, e.g. the default tenant of V26, are missing, so startup fails on a foreign key). The scratch database is dropped afterwards (also on failure). **No production data is copied.**
3. Candidate-smoke limitations (recorded, deliberate): `WORKFLOW_ENABLED=false` (no workflow queue / scheduler) and `SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false`: `PublishWorker` listens on the REAL `studio.publish` queue, so the candidate must not consume the real broker's messages. The broker connection itself is still checked by readiness.
4. `GET /actuator/health/readiness` (the group includes `db, redis, rabbit, minio`) and `/liveness` are UP; every migration of the jar is applied and successful on the scratch database. No mutation endpoint is called.
5. Schema verdict against the real database (above).
6. **The window** (not zero-downtime: stop-then-start, ~10 s measured): stop the current API (owned process; or the legacy-started one with `--replace-legacy-unverified`), start the artifact on 18081, **prove the process is the release** (owned identity, the open jar is the release jar by inode, its sha256 equals the record, readiness UP), and **only then** write `approved.json`. If the new process is not proven healthy it is stopped, the previous approved release (or the legacy API from its saved jar, with its captured environment) is restored when the schema allows, and the approved pointer has not moved.

## Adoption of the API that runs now (`init-api --from-running`)
Nothing is restarted or deployed; it stops (`ADOPT_REFUSED`) when any proof fails: the listener is the pid the old lifecycle recorded (`api.pid`), cwd = `.run/public`, command `java … -jar <jar>` · the jar on disk is **the file the process has open** (same inode, from `lsof`) and was not modified after the process started · readiness UP · the database's applied migrations equal the jar's (versions + checksums, nothing pending, nothing ahead) · **the exact source**: the candidate commit (explicit `--source`, else the newest commit not newer than the jar) is rebuilt in a throw-away snapshot and the rebuilt jar must have the same content digest (name + length + CRC of every entry) as the running one — a jar built from a dirty tree or another commit is refused · the non-secret environment of the process is recorded **as it is** (differences from what `public.env` would derive are listed; secrets are only compared, never printed) and every environment variable the backend configuration reads that the process has must be accounted for. Then: the running jar is copied into the release (digest-verified), ownership is converted to helper metadata, the pointer is written.

## Result of the adoption of the LIVE API (2026-10-08): STOPPED, not pinned

`init-api --from-running` was run against the real public API (pid 83521, `java -jar <checkout>/backend/build/libs/system-web-studio-backend-0.1.0-SNAPSHOT.jar`, started 2026-10-07 23:27:39, profile prod). Every proof passed **except the source**: the listener and the pid file, cwd, the open jar (same inode, not modified after the start), readiness UP, 30 applied Flyway migrations with the jar's 30 and equal checksums (nothing pending, nothing ahead), the environment accounting. The source: the newest commit not newer than the jar is `1006cbf441f6` (backend tree `ecb7b00c1a63`, identical in every later commit - no commit since touched `backend/`); a jar rebuilt from it is **byte-identical in 2003 of 2005 entries** and differs in **`logic/workflow/WorkflowEngine.class` and `runtime/AppDbProvisioner.class`** only. The rebuild is deterministic: three independent clean builds (two with the Gradle build cache, one with `--no-build-cache --rerun-tasks`) give the same digest `711035b3…` against the running jar's `afdd6b86…`. The differing classes have the same constant strings (only the pool indices shift) and the same Kotlin metadata (`mv=[2,2,0]`); the visible differences are code-generation details (an inlined sweep loop / lambda shape, a `checkNotNullExpressionValue("getString(...)")` call). Likely cause: classes of an incremental / cached Kotlin compile that no clean build reproduces - **not provable**. Per the invariant ("if the exact source cannot be proven: stop, do not guess") nothing was recorded: no `approved.json`, no release, no ownership conversion; the API was not touched (same pid, same jar sha256 `ba59a48c…`).

**Decision (C0 / C7, 2026-10-08): option (a), option (b) rejected.** The currently running jar is never adopted and no approved metadata claims an unproven source. Instead the first approved API release was created by an explicit, hardened `deploy-api 1006cbf441f6 --replace-legacy-unverified`: two clean independent builds (`62cd1bbc7fcd…` = `62cd1bbc7fcd…`), the full backend suite on the exact candidate (211 classes, 2003 tests, 0 failures, 0 errors, 3 skipped = `OpenRouterLiveTests`), the candidate proven on a temporary port, then the window (≈10 s), then `approved.json`. The old jar (`sha256 ba59a48c…`, provenance UNVERIFIED) is kept only as incident evidence under `.run/public/api/legacy/` + `legacy.json`; it is not a release and not a rollback target (`previousKnownGood` is null, the history entry says `from: LEGACY_UNVERIFIED`).

## Watchdog
`scripts/watchdog.sh` runs `public-up.sh`; its API step is `public-api.sh up` = the approved jar. Bounded (5 recoveries / 900 s, back-off 30 → 600 s), crash-loop marker `crash-loop.json` (status `CRASH_LOOP`), cleared when healthy; no Gradle on any path (tested: a working tree that moved on is never built by the recovery).

## Limits (stated, not hidden)
1. Not zero-downtime. 2. The candidate runs with workflow consumers off and without production data: the workflow consumer path and data-dependent migrations are not exercised before activation; the schema-only copy exercises the real structure and the real migration history. 3. A first installation on a machine with no API has no approved release: `deploy-api <sha>` is the explicit first step (the old implicit "build on first start" is gone). 4. Adoption records the process environment as it is; a variable the backend reads through Spring *relaxed binding* (not a `${…}` placeholder or `getenv`) cannot be enumerated by the scan. 5. The process-safety guard checks committed code only. 6. The render worker, the sites gateway and the tunnel keep their existing lifecycle (D-C0-50 is the API).
