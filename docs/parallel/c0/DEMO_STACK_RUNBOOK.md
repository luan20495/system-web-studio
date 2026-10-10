# Isolated integration / demo stack (C0, D-C0-55)

An isolated, real stack for demos and real-backend E2E (E2E-ORG01, USER01, PL01, AD01 ...): its own Docker containers, its own ports, its own state directory, **never the public or the default local stack**. Tool: `docs/parallel/c5/e2e-stack.sh` (C5's script; C0 reconciled it in D-C0-55 so the options below exist). Process safety: every process is started through the owned-process CLI (D-C0-48), nothing is stopped by name or by port.

## Bring it up from an exact SHA
```
git -C <checkout> checkout --detach <SHA>                        # the portals are built from THIS checkout
export E2E_STACK_NAME=c0rc E2E_BASE_REF=<SHA>                      # the backend worktree is created from the same SHA (pin_check refuses a mix; E2E_ALLOW_SKEW=1 overrides knowingly)
export E2E_API_PORT=.. E2E_PG_PORT=.. E2E_REDIS_PORT=.. E2E_MINIO_PORT=.. E2E_RABBIT_PORT=.. E2E_SITES_PORT=.. E2E_RENDER_PORT=.. E2E_STUDIO_PORT=.. E2E_PLATFORM_PORT=.. E2E_ADMIN_PORT=..   # free ports (the defaults are shared with other agents)
export E2E_ORG_PERSISTENCE=true E2E_PUBLISH_CONFIGS=true E2E_PORTALS=1
docs/parallel/c5/e2e-stack.sh up
```
`up` = pin check, prepare (worktree + random-secret `stack.env`, mode 600), infra (PostgreSQL / Redis / MinIO / RabbitMQ / sites gateway), the API (Flyway to the latest migration, V32 included), the render worker, Studio and, with `E2E_PORTALS=1`, Platform and Admin; it writes `$DIR/SERVING.json` (stack, SHA, ports, flags) and prints `status`.

* `E2E_ORG_PERSISTENCE=true` sets `ORGANIZATION_PERSISTENCE_ENABLED=true` **in this stack only**. The default of the application, of the script (`false`) and of the public API stay OFF.
* `E2E_PUBLISH_CONFIGS=true` sets `PUBLISH_CONFIGS_ENABLED=true` (needed for the real H-C2-07 answers `422 PUBLIC_DATA_NOT_APPROVED` / `409 PUBLISH_POLICY_MISMATCH` and the approval route `PUT publish-config`).
* `E2E_SITES_PUBLIC_DATA=true` sets `SITES_PUBLIC_DATA_ENABLED=true` (the anonymous Public Runtime `POST /sites/{slug}/_data/queries/{id}/run`, D-C0-36; it is mounted only together with `DATA_PLATFORM_ENABLED`, which the stack already sets) and, unless `E2E_SITES_DATA_API_BASE` is given, `SITES_DATA_API_BASE=http://127.0.0.1:<sites port>/{slug}/_data` (D-C0-34: the browser-facing address a PUBLISHED page finds in `__factory/config.json`; blank = `apiBase: null`). Needed for E2E-PD01 / PD02. Add it to `export` before `up`.
* The sites gateway container gets `GATEWAY_FORCE_HTTPS=0` and `GATEWAY_REAL_IP_FROM` (`E2E_GATEWAY_REAL_IP_FROM`, default `127.0.0.1`): the template refuses to start without them.
* The web origins and CORS of the API follow `E2E_PLATFORM_PORT` / `E2E_ADMIN_PORT` / `E2E_STUDIO_PORT` (they were hard-coded to 3001 / 3002).
* `status` shows every port and whether the listener is owned by this stack.

## Use it
`docs/parallel/c5/e2e-stack.sh e2e E2E-ORG01,E2E-USER01,...` runs the real flows against it (Platform / Admin / Studio URLs and the admin account are wired). The `local.admin` password is in `$DIR/stack.env` (mode 600): never print it.

## Tear it down
`docs/parallel/c5/e2e-stack.sh down --infra --worktree` stops only what this stack started (validated by pid + start time + command), removes its containers and its backend worktree.

## Not this
The public portals / API are pinned releases (`scripts/public-portals.sh`, `scripts/public-api.sh`; `PUBLIC_DEPLOYMENT_PINNING.md`, `PUBLIC_API_PINNING.md`). A stack from this runbook never touches them, and they never touch it.
