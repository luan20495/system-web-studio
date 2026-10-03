# ADR 0013 — Approved package catalog for source-code apps
Status: accepted and implemented (2026-10-03)

## Decision
* Users and the AI can never run install commands or edit `package.json` / `package-lock.json`. They can **request** a package by name.
* A package is usable only when a system admin approves it (Admin → Packages): the runner resolves its dependency closure with
  `npm install <name>@<range> --package-lock-only --ignore-scripts` in a hardened container (**RESOLVE** job). This is the only sandbox step with
  internet access (default bridge network): no user code is present and install scripts are off, so npm only reads registry metadata. The
  closure is scanned with OSV; **HIGH/CRITICAL advisories deny the package by default**. An admin may still allow it with an explicit, audited
  risk acceptance and a written reason.
* The allowlist = scaffold lockfile + `@company/*` (private, published by the operator, never proxied) + the closures of ALLOWED packages.
  The runner rewrites the mirror (Verdaccio) config whenever the allowlist hash changes and restarts the mirror; it also refuses a build whose
  lockfile names fall outside the allowlist (defense in depth).
* A dependency request creates a **LOCK** job: npm adds `<name>@<catalog spec>` (pinned version or the admin's range — users cannot pick
  other versions) inside the sandbox, on the build network (mirror only), scripts off. The API accepts the result only if `package.json` differs
  from `main` solely by that dependency and every lockfile name is allowlisted; then the platform commits both files on `dep/<id>` and the
  normal build → scan → preview → (review) → merge path follows.
* Company packages: `@company/ui` (components with design tokens) and `@company/app-sdk` (runtime config, user context, API client,
  logging, flags, assets) live in `packages/`, are built from source and published to the mirror by `scripts/publish-company-packages.sh`;
  the scaffold depends on them and the AI is told to prefer them.

## Consequences
New packages need an admin; a resolve needs internet once per approval. Versions inside an approved range can change when a new lockfile is
made; the build's OSV scan still blocks HIGH/CRITICAL findings at build time.
