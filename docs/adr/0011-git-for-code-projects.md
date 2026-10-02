# ADR 0011 — Git for code projects: platform-owned repositories on a self-hosted server
Status: **proposed** (2026-10-02) — design only, not implemented. Increment 7.2. Applies to `STATIC_APP` projects only;
`PAGE_SCHEMA` projects keep `project_versions` (the existing `DatabaseBackedGitProvider`) and never get a fake history.

## Decisions requested by the spec
* **Provider:** self-hosted Forgejo/Gitea (recommended: code stays on company infrastructure, simple to run in Docker, API for
  repositories/tokens/protected branches) behind the existing `GitProvider` port. GitHub/GitLab organisation adapters remain possible.
  The Git server is part of the backup and DR routine (database + repository volume).
* **Repository ownership:** every repository belongs to a platform organisation (e.g. `factory`), never to an employee. One repository
  per `STATIC_APP` project, named `<workspace-slug>/<project-slug>-<short-id>`. The Studio's RBAC is the only source of permissions;
  employees get no direct write access in v1. Read-only clone for an external IDE is a later feature using short-lived, per-user,
  per-repository tokens issued by the API and audited.
* **Lifecycle:** created with the project (initial commit = company scaffold, ADR 0012) → active → archived read-only when the project is
  soft-deleted → deleted after the project-restore window. Ownership transfer changes only Studio data; the repository stays in the
  organisation. Every lifecycle step is audited.
* **Branch model:** `main` is protected: only the platform bot merges, only after a green build of the exact head commit, never force-push.
  AI changes go to `ai/<code-change-id>`; Code Mode edits to `edit/<user-id>/<code-change-id>`. Merge = squash by the platform.
  Optional workspace policy "require approval before merge" (Owner/Publisher role) — no new role is invented.
* **Commit model:** author = the Studio user who asked or edited; committer = `factory-bot`; message = one-line summary + trailers
  `Code-Change-Id`, `Prompt-Id` (if AI), `Model` (if AI), `Request-Id`, `Studio-User`. Bot commits are signed (SSH signing key from the
  SecretProvider). History is never rewritten; "restore version" = a new commit that restores the tree of an older SHA.
* **AI commit policy:** the AI never commits to `main`; every AI commit references its prompt and model and is accounted in `ai_calls`;
  patches limited to allowlisted paths and sizes (ADR 0012); a pre-receive hook runs a secret scan and rejects commits with secrets;
  dependency changes only within the mirror allowlist.
* **UI:** the existing Versions panel lists real commits on `main` (with SHA, author, AI trailers) for code projects — taken from the Git
  server, not invented; each merge also writes a `project_versions` row pointing at the SHA so the existing APIs keep working.

## Note — owner decision 2026-10-02
Forgejo runs on this machine (Docker, bound to 127.0.0.1, data volume included in backups); it hosts only generated code projects.
The platform's own source code stays on GitHub (`luan20495/system-web-studio`) and is not mixed with project repositories.

## Consequences
New infrastructure (Git server) to operate and back up; the API needs a bot token (SecretProvider) and issues per-job read-only tokens
for the build plane.
