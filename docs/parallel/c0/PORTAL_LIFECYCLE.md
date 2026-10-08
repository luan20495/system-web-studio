# Portal build / restart lifecycle (C0, D-C0-45)

`scripts/portals.sh` (local) and `scripts/public-portals.sh` (public) share `scripts/_portals_lib.sh`. Commands: `up | down | restart | status | build`, each with `--force-rebuild` where it makes sense. Old command lines keep working (`up`, `down`, `status`).

## What is compared
| | Content | Where it comes from |
|---|---|---|
| **build fingerprint** | SHA-256 of the CONTENT of every file the build reads + the build environment + the Node major version | `scripts/portal-fingerprint.mjs --app <p> -- KEY=VAL ...` |
| **run fingerprint** | build fingerprint + the run-time environment (`STUDIO_HSTS`, `MINIO_PUBLIC_ENDPOINT`, `SITES_ORIGIN` in public) | `RUN_ENV` of each script |
| **dist directory** | `apps/<p>/.next-<local\|public>-<build fingerprint:12>` | never `.next`, never shared by the two modes, never rebuilt in place while a process serves it |

Fingerprint inputs: `package.json`, `package-lock.json`, `tsconfig*.json`, `next.config.*`, `.npmrc`, `.nvmrc`, `.node-version`; `packages/**`, `features/**`, `components/**`, `lib/**`, `app/**`, `public/**`; **only the portal's own** `apps/<p>/**` (a change in admin does not rebuild platform); build environment: local = `NEXT_PUBLIC_API_MODE`, `API_PROXY_TARGET`, `HBL_ENV`, the three `NEXT_PUBLIC_PORTAL_URL_*`; public = the same without `HBL_ENV`, with the https hostnames. Never hashed: `node_modules`, any `.next*` directory, `*.tsbuildinfo`, `next-env.d.ts`, logs, `.DS_Store`, `.git`, `.run`. A dirty tree is fine (content, not commits; git is only used to list tracked + untracked-not-ignored files; without git the tree is walked). A new commit with identical content is the same fingerprint (no needless rebuild); reverting content restores the fingerprint; a deleted file changes it.

## `up`
1. **Ownership check first.** A port used by a process this script did not start is refused with exit 2, the pid and command are named, nothing is built, stopped or started (the other portals included), and the process is never killed. "Ours" = the recorded pid is still THE listener of the port **and** has the recorded start time (a reused pid number or a foreign listener never qualifies).
2. **Build** every portal whose build is not current (fingerprint differs, or the dist directory is incomplete) into a NEW fingerprint-named directory while any running process keeps serving its own. The fingerprint state is written **last** and atomically, only after `BUILD_ID` exists; a failed build writes nothing, removes the partial directory, restores `tsconfig.json` / `next-env.d.ts` (which `next build` rewrites) and **aborts `up` before any process is touched**.
3. **Swap** per portal: keep a process that runs this exact build with this exact run environment and is healthy; otherwise stop OURS (TERM, KILL after 15 s) and start the new build; start portals that are down. A new process that does not come up is rolled back to the previous build (reported, exit 1).
4. **Prune** dist directories nothing refers to (the one served and the last built are kept; the other mode's are never touched; public also removes the orphaned legacy `apps/<p>/.next-public`). The local legacy `.next` is NOT touched (it may belong to `next dev` or another worktree): delete it by hand once.

`restart` = `down` + `up`; `build` builds without touching processes; `down` stops only what this script started; `status` exits 0 only when every portal is CURRENT.

## `status`
`PORTAL PORT PID OWNER BUILT RUNNING DESIRED COMMIT STATE`. BUILT = last successful build, RUNNING = the build the process was started from, DESIRED = what the source says now, COMMIT = the commit the process was started from (`+dirty` = it was started from a dirty tree). STATE: `CURRENT`, `STALE: source/build environment changed`, `STALE: run environment changed (restart needed, no rebuild)`, `STALE: build missing`, `DOWN (build current | stale | missing)`, `UNHEALTHY (HTTP n)`, `FOREIGN (pid ...)`.

## Limits (stated, not hidden)
* Public is **stop-then-start per portal**: a second or two of refused connections on that hostname. Not zero-downtime: nothing in front of the portals can switch upstreams, and the portal gateway is shared infrastructure this script does not reconfigure. What IS guaranteed: a failed build or a failed start never leaves the public portals worse than before (old process kept / rolled back).
* The first `up` after this change finds portals started by the previous script version (pid file, no run fingerprint): they are recognised as ours, judged stale, and restarted onto fingerprint directories (tested). `.next-public` of the old public script is then removed.
* A source change made WHILE a build runs is not part of that build's fingerprint (it is taken before): the next `up` sees the difference and rebuilds.

## Tests
`npm run test:infra:portals` (`tests/infra/portals-reliability.mjs`, class `integration`, 26 tests, ~2 min): a sandbox git repository with the REAL scripts, a fake `npx` (only `next build` / `next start` are replaced), real processes and ports. Covers first build, no-op, per-app and shared changes, dirty / untracked / ignored files, tsconfig restore, build env and run env changes, failed build (old process kept, state untouched), failed start (rollback), foreign port (exit 2, never killed, nothing else started), stale / dead / legacy-version processes, corrupted and poisoned dist directories, `--force-rebuild`, `restart`, `down`, local and public independence, legacy dist pruning, and the fingerprint itself. The suite was checked against three deliberate bugs in the library (no foreign-port check, fingerprint written before the build result, stale process kept): each turns it red.
