# Explicit public deployment pinning (D-C0-49)

Invariant: **process recovery is not deployment.** What the public portals serve is decided by one explicit act (`deploy <sha>`), recorded in an immutable release, and nothing else — not a crash, not a restart, not `up`, not the watchdog, not the state of the working tree — can change it.

Owner: C0. Code: `scripts/lib/public-release.mjs`, `scripts/public-release.mjs` (CLI), `scripts/public-portals.sh` (thin wrapper), `scripts/watchdog.sh`. Tests: `tests/infra/public-pinning.test.mjs` (`npm run test:infra:public`). Local portals (3301–3303, `scripts/portals.sh`) are NOT pinned and stay source-aware (D-C0-45). C2 published-site releases (`docs/parallel/c0/C2_DEPLOY_CONTRACT.md`) are a different thing and are untouched.

## Three different SHAs (never confused)

| name | meaning | where it comes from |
|---|---|---|
| `INTEGRATION_HEAD` | what `integration/v2` is now | `git rev-parse` of the checkout (informational only) |
| `APPROVED_PUBLIC_SOURCE` | the source of the release the public portals are *supposed* to run | `.run/public/approved.json` → `releases/<id>/release.json` |
| `RUNNING_PUBLIC_SOURCE` | the source of the release the process that listens on the port actually runs | the process's own recorded identity (`releaseId`) |

Running behind integration is the normal, valid state (`CURRENT_APPROVED (BEHIND_INTEGRATION +N)`); it is **never** a reason to deploy on restart.

## Release model

```
.run/public/
  approved.json                 { releaseId, releaseJsonSha256, approvedAt, approvedBy, previousKnownGood, history[≤20] }   (atomic rename)
  releases/<sha12>-<cfg8>/
    release.json                immutable: sourceSha, sourceTreeSha, builtAt, kind, per portal {distName, buildId, distDigest, fingerprint}, buildEnv, runtimeEnv, configFingerprint
    src/ … apps/<p>/<dist> …    snapshot of the approved SHA (git archive), the built dists, own node_modules
  portal-<p>.owned.json         owned-process identity (pid, start time, command, cwd, releaseId)
  release.lock  crash-loop.json
```

* A release is the **snapshot of an approved SHA + its built dists + its own `node_modules` + pinned non-secret env**. It does not depend on the working tree: `git checkout`, a dirty tree, a new commit, or a deleted branch cannot alter it.
* `buildFingerprint` (per portal; `portal-fingerprint.mjs`: what the build reads) is separate from `configFingerprint` (= sha of build env + **runtime** env + Node major + lockfile sha). Release id = `<sha12>-<cfg8>`: the same SHA under another config is a different release.
* Metadata holds only an **allow-listed set of non-secret keys** of `public.env` (hosts, ports, `STUDIO_HSTS`, `SITES_ORIGIN`, `MINIO_PUBLIC_ENDPOINT`, …). A secret in `public.env` is never copied (tested with sentinel values over every metadata file).
* Retention is bounded to **approved + previous known-good**; anything else is pruned after a successful deploy or by `prune`. The approved release is never deleted (prune refuses if it would).

## Commands (`scripts/public-portals.sh <cmd>`)

| command | what it does | what it never does |
|---|---|---|
| `status [--json]` | portal, port, pid, health, running / approved / integration source, build + config fingerprint, state. Exit 0 only if every portal is `CURRENT_APPROVED` | change anything |
| `up` | start the **approved** release where it is not running | build; use HEAD; start with no approved release (`NO_APPROVED_RELEASE`, exit 5) |
| `restart` | stop + start the **same** approved release | rebuild; move the pointer |
| `down` | stop the portals this tool started | touch a foreign listener |
| `deploy <sha\|ref>` | resolve → snapshot → deps → **build candidate** → **prove it on temporary ports** (`/login` 200, served asset, CSP, API proxy) → move the approved pointer (atomic) → replace the running portals → automatic rollback if the real-port start fails | move the pointer before the candidate is proven; deploy implicitly (an explicit source is required) |
| `rollback [id]` | explicit return to the previous known-good (retained) | delete the artifact; invent a target |
| `init --from-running [--dry-run]` | pin what is running **now**, from evidence, without restarting it | accept anything it cannot prove |
| `releases · prune · verify` | list / drop unreferenced / deep-verify the approved artifact | delete approved or previous known-good |

Exit codes: 0 ok · 1 failed / not current · 3 `FOREIGN_PROCESS` · 5 `NO_APPROVED_RELEASE` · 6 `APPROVED_BUILD_MISSING` / `RELEASE_TAMPERED` · 7 `CANDIDATE_*` / `ACTIVATION_*` / `DEPS_FAILED` / `SNAPSHOT_FAILED` · 8 `LOCKED` · 64 usage.

### Fail closed

* No approved release → `up`, `restart`, `status`-for-restart refuse; nothing is built, nothing starts, HEAD is never assumed.
* Approved artifact missing / without `BUILD_ID` / dist digest ≠ `release.json` / `release.json` hash ≠ approved pointer / no `node_modules` → state `APPROVED_BUILD_MISSING` (or tamper), exit 6, nothing started. The tool never "repairs" by building HEAD or the newest release.
* A foreign process on a public port is refused (exit 3) and never killed (D-C0-48 identity rules).

## Status states

`CURRENT_APPROVED` (running = approved; suffix ` (BEHIND_INTEGRATION +N)` when integration moved on) · `STOPPED` · `CRASH_LOOP` (stopped while the watchdog's marker exists) · `APPROVED_BUILD_MISSING` · `RUNNING_UNAPPROVED` (healthy process of another release than approved, or no record) · `UNHEALTHY` · `NO_APPROVED_RELEASE`. `STALE` is not reused: it belonged to the local, source-aware lifecycle.

## Watchdog (`scripts/watchdog.sh`)

Recovery = `public-portals.sh up` = **start the approved release**. Bounded: at most `WATCHDOG_MAX_RESTARTS` (5) recoveries per `WATCHDOG_WINDOW` (900 s), exponential back-off (`WATCHDOG_BACKOFF_BASE` 30 s → `WATCHDOG_BACKOFF_MAX` 600 s). Beyond that it stops restarting, writes `.run/public/crash-loop.json` (`status` shows `CRASH_LOOP`) and keeps checking; healthy again clears the marker. It has no code path that builds or deploys.

## Adoption of the running release (`init --from-running`)

No assumption from conversation text. Every portal must show: listener pid = pid file, start time equal (whitespace-normalised: the old lifecycle stored `ps lstart` day-padded), cwd = `apps/<p>` of this checkout, the dist it runs exists with its `BUILD_ID`, **every asset its served `/login` links exists in that dist**, it was built from a **clean** tree of one commit (`runmeta`), and the fingerprint of that commit's snapshot equals the recorded one. Then: snapshot of that commit, the dist copied (digest-verified equal), `node_modules` cloned from the running install tree (the lockfile is the one of that commit), the release recorded, the pointer written, the old process's ownership converted to helper metadata (`extra.releaseId`). **Nothing is restarted.** `--dry-run` prints the evidence and writes nothing.

## Limits (stated, not hidden)

1. **Not zero-downtime.** Activation is stop-then-start per portal (seconds of refused connections); the portal gateway cannot switch upstreams. Guaranteed instead: a failed candidate never moves the pointer or touches the running release; a failed real-port start rolls back automatically.
2. Adoption pins the **source and build** of the running process by evidence; the **runtime env** of a process started before this change cannot be read back from the OS without secrets, so it is the current non-secret `public.env` allow-list. The first `restart` after adoption runs with that pinned env.
3. The API (18081) is not pinned by D-C0-49; it is pinned by D-C0-50 (`PUBLIC_API_PINNING.md`).
4. Until this branch is imported into the checkout that runs the watchdog, the old watchdog / `_portals_lib.sh` lifecycle is the code that runs; after import, a process the old code started that is not the approved release shows `RUNNING_UNAPPROVED`.
5. The process-safety guard (D-C0-48) checks committed code only; it does not protect manual commands or `/tmp` scripts.
6. No Cloudflare / DNS / tunnel / gateway change.
