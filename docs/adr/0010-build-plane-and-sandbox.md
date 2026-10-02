# ADR 0010 — Build plane: per-job sandboxes on a separate Linux host
Status: **proposed** (2026-10-02) — design only, not implemented. Increment 7.3. Needed only for `STATIC_APP` (code) projects.

## Requirements (from the spec)
Isolated sandbox, dependency install, build, tests, secret scan, dependency scan, artifact creation, resource limits, timeout,
network policy. Generated code must **never** execute inside the Spring Boot JVM — nor on the host that runs the control plane.

## Isolation options
| Option | Isolation | Ops cost | Fit |
| --- | --- | --- | --- |
| Plain Docker (runc) + hardening | shared host kernel; one kernel bug = escape | low | not enough for untrusted code on its own |
| **Docker + gVisor (`runsc`)** | user-space kernel intercepts syscalls; strong practical isolation | low–medium (Linux only, some build slowdown) | **chosen for v1** |
| Firecracker / Kata (micro-VM) | hardware virtualisation per job | medium–high (KVM host, images, networking) | upgrade path if the threat level rises |
| Hosted sandbox service | vendor-managed | low ops, but source code and prompts leave company infrastructure | rejected for now (data residency, dependency) |
| Kubernetes Jobs | depends on runtime class | high; excluded by project constraint "no Kubernetes" | rejected |

## Decision
* **Placement:** a dedicated Linux VM/server ("build host"), not this macOS machine and not the control-plane host. It holds **no**
  platform database credentials, no AI keys, no user sessions.
* **Runner agent** (separate small process on the build host) consumes `build.requested` from RabbitMQ (dedicated vhost/user with
  publish/consume rights on build queues only) and replies `build.finished`. One job at a time per slot; slots configurable.
* **Two-stage sandbox per job** (fresh container from an image pinned by digest, run with `runsc`, non-root, read-only root filesystem,
  `no-new-privileges`, all capabilities dropped, no Docker socket, no host mounts, private tmpfs workspace with a size quota):
  1. *install* — network allowed **only** to the internal package mirror; `npm ci --ignore-scripts` from a lockfile with integrity
     hashes (packages that genuinely need install scripts are allowlisted individually);
  2. *build & test* — **no network at all**; typecheck, lint, tests, build (static export).
* **Inputs/outputs move outside the sandbox:** the runner fetches the exact commit with a short-lived read-only token scoped to that
  repository and copies a tarball in; it copies the output directory out, then uploads it with a presigned, single-object PUT. The
  sandbox never sees a credential.
* **Limits (defaults, per job):** 2 vCPU, 4 GiB RAM, 512 pids, 5 GiB disk, install 5 min, build+test 10 min, output 100 MiB,
  log 10 MiB. Exceeding any limit = FAILED with the reason. Per-user and per-workspace build quotas recorded like AI usage.
* **Scans (fail the build on findings above the configured threshold):** secret scan of source and output (gitleaks), dependency
  vulnerabilities from the lockfile (OSV-Scanner), SBOM (syft, stored next to the artifact), static checks for forbidden patterns in
  output (e.g. inline `<script>` from untrusted origins, `eval`), a CSP-compatible output check.
* **Package mirror:** Verdaccio (or equivalent) on the build network with an allowlist of packages/versions; no direct access to the public
  registry from sandboxes. Mirror updates are an admin task.
* **Logs:** streamed by the runner (not the sandbox) to MinIO, redacted of tokens; shown in Studio as build output.
* **Cleanup:** container and workspace destroyed after every job; the runner host is rebuilt from an image periodically.
* **Dev on macOS:** Docker Desktop's VM is acceptable for development only; gVisor requires Linux, so production builds run only on the
  build host.

## Acceptance criteria for 7.3
A job cannot reach anything but the mirror in stage 1 and nothing in stage 2 (tested with outbound attempts); cannot read host files,
environment secrets or instance metadata (tested); limits enforced (fork bomb, memory hog, infinite loop, huge output — each FAILED
with the right reason); a planted secret and a vulnerable dependency each fail the build; artifact hash recorded and verified at deploy.
