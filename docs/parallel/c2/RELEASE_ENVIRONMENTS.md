# C2 — Batch 3 readiness: candidate model, health / readiness, environments, CDN / DNS / TLS, observability, artifact integrity

Audited 2026-10-07 at `fix/c2-v3 @ 1ab26f9` + the audit commits. **Audit and recommendation, not a contract**: the parts that need C0 are marked HANDOFF. No infrastructure was provisioned and nothing public was contacted.

## 1. Candidate → verify → activate → serve

**Today (code, tested):** verify the artifact (record + manifest checksum + sizes + **bytes**, §6) → `stage()` → server runtime healthy (server apps) → switch the pointer by compare-and-set → confirm (`provider.verify`, optional public HTTP probe). A candidate is **never** switched in unverified: a failure before the switch leaves the serving release and the server runtime untouched. **Known limit LIM-1 (C0 F-8):** from the switch until the confirmation ends, the new release is already what the address serves (`live()` serves `DEPLOYING` and `RUNNING`), so the *post-switch* confirmation (HTTP probe) can fail after a visitor has seen it; the answer is then `ROLLING_BACK` → previous release.

**What a true candidate model needs (design, for C0's decision):**
1. Candidate identity needs **no new column**: while a publish holds the scope, `sites.lease_deployment_id` already names the deployment being published; the active pointer stays on the old release.
2. The gateway serves only `current_deployment_id` whose deployment is `RUNNING` (drop `DEPLOYING` from the served set); the deployment becomes `RUNNING` **in the same transaction** as the pointer flip (the fence already runs a step inside the commit transaction: `PointerFence.withNextCommit`).
3. Verification of the candidate over HTTP needs a way to fetch it before it is active: an internal, server-signed, short-lived candidate route (`/sites/_candidate/{deploymentId}/…`, token minted and consumed by C2 itself, never given to a browser). That is a new route and it needs the C0 sign-off on LIM-1.
4. Contract changes this implies (all C0's): `DeploymentStatus.allowed` (DEPLOYING → RUNNING performed with the flip; a failed probe never reaches RUNNING, so `ROLLING_BACK` would no longer be needed for the static provider), `live()` served set, a `DeployProvider.verifyCandidate` amendment (F-8 says this needs C0's signature).
**Recommendation:** do it as Batch 3 after a public host exists to probe against; until then the pre-switch verification (artifact bytes, runtime health) is the mitigation. **HANDOFF C0** — decision on LIM-1 and the amendment; no migration is required by this design.

## 2. Verification layers (kept separate)

| layer | what it proves | where | cost | when |
|---|---|---|---|---|
| artifact verification | the record is live, the manifest matches the artifact checksum, every file is present with its size; **and** (artifacts ≤ `app.deploy.verify-bytes-limit`, 16 MiB) every file's SHA-256 matches the manifest | `ArtifactVerifier.verify` (cheap) / `verifyContent` (bytes), `StoredArtifactVerifier` | one `stat` per file / one read of the artifact | content check once before the switch (publish and rollback target); cheap checks again right before and after the switch |
| runtime health | the server part of a server app became healthy on the runner; a late runner cannot start an abandoned release | `RuntimePlane.serve` (waits ≤ `app.deploy.runtime-timeout-seconds`, 120) | runner round trip | before the pointer moves |
| public HTTP health | the published address answers like a visitor would | `ReleaseHealthProbe` / `HttpReleaseHealthProbe` (`app.deploy.health-probe.enabled`, default **off**) | one GET | after the switch (LIM-1) — **BLOCKED for real hosts: no reachable public host (the `toolsmcp.uk` demo profile's API and tunnel are not running)** |
| post-deploy confirmation | the pointer is this release, its files are present, and (if on) the probe says healthy; UNKNOWN is never success | `DeployProvider.verify`, `ReleaseDeployer` step 5 | – | after the switch |
| process readiness | the API itself is ready | `GET /actuator/health/readiness` as listed in C0's V1 note §2.8 (**not audited by C2**) | – | orchestrator |

## 3. Environment contract (what each environment must provide)

| | DEV (Mac) | STAGING | PRODUCTION |
|---|---|---|---|
| site origin (`SITES_ORIGIN`) | `http://127.0.0.1:18088` (gateway container, running) | **NOT CONFIGURED** | **NOT CONFIGURED** |
| studio origin (`STUDIO_ORIGIN`) | `http://localhost:3100` | – | – |
| data-api-base (`APP_SITES_DATA_API_BASE` → `app.sites.data-api-base`) | unset ⇒ `apiBase:null`; set per environment | operator-set | operator-set |
| runtime config source | API, per request, `no-store`; value of data-api-base fixed per API process (restart to change) | same | same |
| TLS | none (`http`, `SITES_COOKIE_SECURE=false`) | terminated in front of nginx | terminated in front of nginx; `SITES_COOKIE_SECURE=true`, `COOKIE_SECURE=true` |
| CDN | none | optional | optional, see §4 |
| cache | nginx `proxy_cache` keeps only what the API marks cacheable (`assets/` of websites: immutable) | same | same |
| rollback | immediate: pages are `no-cache` (revalidated by ETag), config `no-store`; no rebuild, no purge | same | same (a CDN must honour it) |
**Required keys today with a loopback default in `application.yml` (C0 hygiene H1, L-2):** `SITES_ORIGIN`, `STUDIO_ORIGIN`, `RENDER_URL`, and for the gateway `API_UPSTREAM`, `SITES_HOST`. C2 removed the duplicate loopback literals from `publish/**` (the `@Value` defaults of `SiteService` and `StaticSites`); the YAML defaults are C0's to move and to make mandatory in `application-prod.yml`. `app.sites.data-api-base` is still **code-default only** (not in `application.yml`).

## 4. DNS / TLS / CDN expectations (documented, not provisioned)

* **DNS:** one host for the sites gateway (`sites.<domain>` — slugs are path segments, no wildcard needed) resolving to the gateway / its edge; **a different host from the Studio**, and the Studio cookies must stay host-only, so no Studio cookie is ever sent to a published page (ADR 0009). Custom domains (public websites only) are verified by C2's `Domains` flow and answered by the default server block.
* **TLS ownership:** terminated by whoever owns the public edge (tunnel, CDN or load balancer); nginx listens on plain `8080` and trusts `X-Forwarded-Proto`; the API must see `https` for `SITES_COOKIE_SECURE=true`. Certificate issuance / renewal is outside C2.
* **CDN cache invalidation:** none required for correctness. Pages are `public, no-cache, no-transform` (revalidate with the ETag), private sites `private, no-store`. **Immutable assets:** only `assets/<assetId>.<ext>` of public websites, `public, max-age=31536000, immutable` (the id never changes its bytes). **Runtime config:** `Cache-Control: no-store, no-transform` — **a CDN must never cache `__factory/config.json`, `/_app/**`, `/_access`, `/_preview/**`**, must not rewrite bodies (`no-transform`), must not strip `Cache-Control`. Purge-based edge caching of pages is an optimisation noted in the code, not built.

## 5. Observability — what exists, what was added, what is missing

| item | where it is | note |
|---|---|---|
| deployment id = release id | `deployments.id`, SiteInfo, runtime config `releaseId`, logs | |
| operation id | publish = deployment id; rollback / unpublish = UUID derived from the Idempotency-Key (`idempotency_keys.resource_id`); **logs** (added) | not stored on the deployment row for rollback / unpublish |
| idempotency ref / hash | `idempotency_keys.request_hash`; **logs** carry the first 12 hex of the hash, **never the key** (added) | |
| build job id | `build_jobs.id`, linked by `build_jobs.deployment_id` | not on the deployment row |
| artifact id / hash | `deployments.artifact_id` → `artifacts.sha256`; logs of the artifact store / verifier carry the failure reason | |
| pointerVersion, active deployment | `sites.pointer_version`, `sites.current_deployment_id`, SiteInfo, **logs of every pointer move / refusal** (added) | |
| fencing token, lease | `sites.lease_fence`, `sites.fence_counter`, **logs on acquire / busy / refused / lost** (added); SiteInfo shows kind / since / leaseUntil only | the token is never in an API response |
| runtime current / desired | `app_runtimes`, `server_deployments`, `GET …/runtime` | |
| error code | `deployments.error` (`[CODE] …`), audit `DEPLOY_STATUS_CHANGE.newValue.code` | |
| rollback events | `deployment_events`: `ROLLING_BACK`, `ROLLBACK_OK`, `ROLLBACK_FAILED`, `ROLLBACK_OFFLINE`, `ROLLED_BACK`, `SCOPE_BUSY`, `STALE_PUBLISH` | |
| step duration | **logs only** (added: `deployment <id> project=<id> step=<S> outcome=<O> durationMs=<n>`) | **gap:** not persisted, no metric; the recovery sweeper does not log durations |
| no secrets | logs carry identifiers and outcomes only; no payload, key, credential or token | |
**Gaps for V2:** Micrometer meters for lease contention / stale publishes / step durations (the Prometheus endpoint exists, no C2 meters), the HTTP `requestId` of the publish request is not stored on the deployment, centralised log shipping.

## 6. Artifact integrity — analysis and what was done

* **Gap found:** the publish path verified record, manifest checksum and per-file **sizes** (a `stat` each). A file replaced by other bytes of the same length was invisible until the gateway refused to serve it (the gateway already hashes every file it serves against the manifest, so it was never *served*, but a release could be activated that would fail at the first request).
* **Is a full SHA-256 needed before activation?** Yes for correctness of "verified before serve"; the checksums already exist (the manifest holds a SHA-256 per file, computed from the bytes at build time), so no new hashing pipeline is needed — the check re-reads and compares.
* **Cost:** one read of the whole artifact per activation (publish and rollback target, once each, before the switch). Page-schema sites are kilobytes to a few MB; code apps up to the artifact quota. Hence the bound: **done** for artifacts ≤ `app.deploy.verify-bytes-limit` (default 16 MiB, `0` = off); larger artifacts keep the cheap check and are still protected at serve time. Reading is sequential through the same store client; 16 MiB is well inside the step timeouts.
* **Implemented and tested** (`StoredArtifactVerifier.verifyContent`, `StoredArtifactVerifierTests`, `ReleaseDeployerTests`): same-size tampering passes the cheap check and fails the content check; the deployer never switches in, nor rolls back to, altered bytes; after the switch only the cheap check runs.
* **Recommendation for V2:** store the object-store ETag / a content checksum at upload and compare with `stat` (no re-read) so large artifacts can be verified cheaply too; run the content check as a periodic scrub of the active and rollback-target artifacts.
