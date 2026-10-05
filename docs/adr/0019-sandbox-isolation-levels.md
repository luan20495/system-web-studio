# ADR 0019: Sandbox isolation levels for builds and app runtimes
Status: accepted (2026-10-04). Level 2 implemented; level 3 prepared (runner flag); levels 4–5 documented for production.

| Level | Mechanism | Kernel shared with host? | Escape needs | Startup / overhead | Fit |
|---|---|---|---|---|---|
| 1 | plain container (default Docker) | yes | one kernel or runtime bug, or a misconfiguration | ms / none | not enough for untrusted code |
| 2 | **hardened container** (current): non-root, read-only rootfs, `cap-drop ALL`, `no-new-privileges`, seccomp default, pid/mem/CPU limits, no host mounts, no Docker socket, internal network (builds: mirror only / none; apps: DB + gateway only) | yes | a kernel bug reachable through the seccomp allowlist | ms / none | dev, internal pilot |
| 3 | **gVisor** (`runsc`): user-space kernel intercepts syscalls | no (Sentry) | bug in gVisor + host attack surface of ~70 syscalls | ~100 ms / 10–30 % syscall-heavy | production default on Linux; runner: `RUNTIME_OCI=runsc` |
| 4 | **Kata Containers**: each container in a lightweight VM (QEMU/Cloud Hypervisor) | no | hypervisor escape | ~0.5–1 s / RAM per VM | multi-tenant production |
| 5 | **Firecracker microVMs** (one VM per build/app, jailer, minimal device model) | no | microVM escape | ~125 ms / 5 MB per VM | highest density + isolation; needs bare metal or nested virt, own orchestration |

Decision: keep level 2 everywhere today (macOS Docker Desktop cannot run gVisor/Kata/Firecracker inside its VM in a supported way). For the
Linux production host: level 3 (gVisor) for both builds and app runtimes as the default, level 4/5 when apps from different trust domains
(external customers) share hosts. Independent of the level: network isolation (internal networks), resource limits, secrets never inside
images, artifacts scanned (OSV, gitleaks), and the runner never runs generated code on the host.
