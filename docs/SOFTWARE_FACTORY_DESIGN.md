# Software Factory — design proposal (Phase 7)

Status (2026-10-04): **7.1 implemented and public; 7.2–7.5 implemented on the local stack** (source-code apps, sandbox builds, AI code
generation, server runtime with per-app databases). The public pilot runs websites only (no Git server / runtime there). Decisions: ADR 0008–0020.
Section 0 is the current architecture; sections 1–7 are the original proposal, kept for history.

## 0. Current architecture (2026-10-04)
```
Browser ──► Studio UI (Next.js, nonce CSP) ──► API (Spring Boot, modular monolith) ──► PostgreSQL · Redis · MinIO · RabbitMQ
                                                 │  control plane: identity (password/OIDC/SAML-brokered/SCIM), RBAC, audit, AI gateway
                                                 │  (model access, budgets, alerts, streaming, tools), templates/blocks review, settings
          Visitors ──► Sites gateway (nginx) ──► API /sites/** ── static artifacts (MinIO, content-addressed, atomic per publish)
                                                 │                └ server app APIs /<slug>/api/** (declared routes only)
                                                 ▼
                                   Apps gateway (token) ──► one internal network per app: app container ◄─► apps DB (own DB/role)
                                                 ▲                                        └─► connectors via the API proxy (credential injected)
          Build plane: runner (host process, Docker) ── sandbox containers: mirror-only install, no-network build, OSV, gitleaks, SBOM
                                                    └── runtime: image from the server bundle, hardened container, blue/green, health checks
          Git plane: Forgejo (platform-owned repos, protected main, signed bot commits, read-only IDE tokens)
          Render worker: page schema → static HTML (same renderer as the Studio preview), JS-free screenshot previews, AST service
```
Isolation levels and the production path (gVisor on a Linux host): ADR 0019. Backups, drills, profiles: ADR 0020.

## 1. Where we are (verified in code, 2026-10-02)
* A project is a **page schema** (JSON) validated against an approved component registry and rendered by fixed code
  (`lib/schema-preview.ts` in the browser). No source code, repository, build or runtime exists.
* Ports already in place: `GitProvider` (only `DatabaseBackedGitProvider`: `project_versions` is the history, no SHAs),
  `DeployProvider` (only `MockDeployProvider`: no site is served, UI says "Demo deployment"), `SecretProvider` (environment),
  `StorageProvider` (MinIO), RabbitMQ publish queue with a resumable state machine
  (`QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING → DEPLOYING → RUNNING | FAILED`), where BUILDING/DEPLOYING are mocks.
* Constraints carried over from earlier phases: modular monolith for the control plane, **no Kubernetes**, no GitHub Actions,
  generated code **never** runs inside the Spring Boot JVM, nothing is faked in the UI.
* Deployment today: one machine (macOS dev host; Docker Compose for PostgreSQL/Redis/MinIO/RabbitMQ), public access through a dedicated
  Cloudflare tunnel.

## 2. Target shape
```
                 CONTROL PLANE (existing Spring Boot API, PostgreSQL, Redis, MinIO, RabbitMQ)
 Studio ──► API ──► planner (AI) ──► project spec ──► code generator ──► Git (repo per code project)
                                                          │
                                   build job (RabbitMQ)   ▼
                 BUILD PLANE (separate Linux host, never the API process)
                 runner agent ──► per-job sandbox (no host mounts, no secrets, egress only to package mirror)
                                  install → build → test → scan → artifact (+ SBOM) ──► MinIO (artifacts bucket)
                                                          │
                 RUNTIME PLANE                            ▼
                 gateway (Nginx/Caddy) ── serves immutable static artifacts from object storage
                   ├─ public sites       <slug>.sites.<company-domain>
                   ├─ private sites      same, behind the gateway's auth check (company SSO)
                   └─ previews           <id>.preview.<separate-domain>   (untrusted output, never the Studio origin)
```

## 3. Increments (each one is shippable and honest on its own)
| # | Increment | What becomes real | Executes generated code? | Main risks |
| --- | --- | --- | --- | --- |
| **7.1** | **Static runtime for existing schema sites** (ADR 0009) | Publish produces a real artifact: the page schema is rendered to static HTML/CSS by the same fixed renderer, assets copied, uploaded immutably to MinIO, served by a gateway on a real URL; private sites behind SSO; rollback = point to an older artifact | **No** (fixed renderer, data only) | gateway config, auth on private sites, domain/TLS |
| 7.2 | Git as history for code projects (ADR 0011) | Repository per *code* project on a self-hosted Git server, platform-owned; commit model with AI trailers | No | repository lifecycle, credentials |
| 7.3 | Build plane + sandbox (ADR 0010) | Isolated build of a repository commit into a static artifact, with tests, secret and dependency scans, SBOM | **Yes, in the sandbox only** | sandbox escape, supply chain, resource abuse |
| 7.4 | Code generation + Code Mode for a new app type (ADR 0012, 0008) | `appType = STATIC_APP` (e.g. Next.js static export from a company scaffold): planner → spec → patches → branch → sandbox build → preview → merge; Code Mode shows real files from Git | Yes (sandbox) | prompt injection into code, quality, cost |
| 7.5 | Dynamic applications (ADR 0008) | Server-side apps (APIs, databases, connectors) | Yes, long-running | a whole hosting platform: later, separate approval |

**Recommendation:** approve and do **7.1 first**. It replaces the last mock in the product (deployment) without creating any new
execution surface, and every later increment reuses its artifact store and gateway. 7.2–7.4 should be approved together as the
"code projects" track because none of them is useful alone.

## 4. Threat model (what changes when code is generated)
| Threat | Where | Control |
| --- | --- | --- |
| Generated code attacks the platform (RCE, credential theft) | build plane | separate host; one throw-away sandbox per job; no host mounts, no Docker socket, no cloud/instance metadata, no platform secrets inside; read-only base image; non-root; seccomp; CPU/memory/pid/disk/time limits (ADR 0010) |
| Malicious or typo-squatted dependency | build plane | install only through an internal package mirror with an allowlist; lockfile required; dependency scan (OSV) fails the build on known-critical issues; SBOM stored with the artifact |
| Secret leaks into repo or artifact | generator, Git, artifact | generator never receives secrets; secret scan on every commit and every artifact; no secret is ever injected into a static build |
| Prompt injection turning into code (e.g. exfiltration script in the site) | generator → artifact | path/size/dependency allowlists on patches; CSP on served sites; static only (no server code) until 7.5; previews on a separate origin |
| Generated site attacks Studio users | preview/runtime | previews and sites on separate registrable domains from the Studio, never same-origin; iframe `sandbox` without `allow-same-origin`; strict CSP |
| Abuse of compute | build plane | per-user/workspace build quotas, queue limits, timeouts, budget accounting like AI usage |
| Tampered artifact | runtime | content-addressed artifacts (SHA-256), immutable bucket keys, gateway serves only artifacts recorded as RUNNING |

## 5. Data model additions (proposed, not created)
* `projects.app_type` `PAGE_SCHEMA` (default for every existing row) | `STATIC_APP` | later `DYNAMIC_APP` — backward compatible.
* `artifacts` (id, project_id, version_id or commit_sha, sha256, size, kind `STATIC_SITE`, storage_key, sbom_key, scan_report jsonb, created_at).
* `deployments` gains `artifact_id`; `sites` (project_id, slug, visibility, current_deployment_id, custom_domain, created_at).
* `repositories` (project_id, provider, remote_id, default_branch, state `ACTIVE|ARCHIVED|DELETED`, created_at).
* `build_jobs` (id, project_id, commit_sha, status, runner_id, limits jsonb, started_at, finished_at, exit_code, log_key, artifact_id).
* `code_changes` (id, project_id, prompt_id, branch, base_sha, head_sha, status `PROPOSED|BUILDING|READY|MERGED|REJECTED|FAILED`).

## 6. Decisions (answered by the owner on 2026-10-02)
| # | Question | Decision |
| --- | --- | --- |
| 1 | Approve 7.1 | Yes — implemented (ADR 0009). |
| 2 | Domains | `toolsmcp.uk` (existing Cloudflare zone, dedicated tunnel `hbl-studio`). Sites: **`https://sites.toolsmcp.uk/<slug>/`** (one host, path per site: Cloudflare's free certificate covers only one wildcard level, no wildcard DNS record is needed, and the host differs from `studio.toolsmcp.uk` so Studio cookies never reach sites). Previews of generated code (7.4) must get their own host (e.g. `preview.toolsmcp.uk`, still under the same registrable domain — see the note in ADR 0012). |
| 3 | Build host | This machine for now, extended later. On macOS the sandbox runs inside Docker Desktop's Linux VM (ADR 0010 note); a dedicated Linux build host is the upgrade path. |
| 4 | Git | Self-hosted Forgejo on this machine for generated code projects (ADR 0011). The platform's own source stays on GitHub (`luan20495/system-web-studio`). |
| 5 | First code app type | React + Vite static build from a company scaffold (ADR 0012 note) — simplest pure static output, no server runtime. |
| 6 | Package policy | Allowlist-only npm through a local mirror (Verdaccio), `--ignore-scripts`, lockfile required (ADR 0010). |

## 6b. Original questions
1. **Approve 7.1** (real static runtime) as the next implementation step? (Recommended.)
2. **Domains:** a domain for sites (e.g. `*.sites.<company>.vn`) and a *separate* one for previews; DNS/TLS through the existing
   Cloudflare account or another provider.
3. **Where the build plane runs** (needed for 7.3): a separate Linux VM/server (recommended), not this macOS host.
4. **Git server** (7.2): self-hosted Gitea/Forgejo (recommended, keeps code on company infrastructure) vs a GitHub/GitLab organisation.
5. **First code app type** (7.4): static Next.js/React export from a company scaffold (recommended) or something else.
6. **Package policy** (7.3): allowlist-only npm through a mirror (recommended) vs open registry with scanning.

## 7. What stays untouched
Page-schema projects keep working exactly as today (they become `PAGE_SCHEMA` app type). The registry security guarantee, RBAC, audit,
AI accounting and budgets apply to the new track too. Code Mode keeps its honest "not available" screen for `PAGE_SCHEMA` projects.
