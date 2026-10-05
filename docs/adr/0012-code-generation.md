# ADR 0012 — Code generation for STATIC_APP projects: spec first, patches on branches, validated by builds
Status: **accepted; implemented locally** (2026-10-03). Increment 7.4. Depends on ADR 0008–0011.

## Pipeline
```
prompt ─► planner (LLM, structured JSON plan) ─► registry retrieval (approved code components) ─► project spec (JSON, stored)
       ─► generator (LLM, file patches for allowlisted paths) ─► API validation (text only, nothing executed)
       ─► commit on ai/<change> ─► build job (sandbox: typecheck, lint, tests, build, scans) ─► preview artifact (preview domain)
       ─► user accepts ─► squash-merge to main ─► publish (ADR 0009)
```
* **Scaffold:** a company-owned template (recommended: React/Next.js static export with the company design system), pinned versions,
  CSP-friendly, no server code, a smoke test. Every new `STATIC_APP` starts from it (ADR 0011 initial commit).
* **Registry for code:** the approved components become a versioned internal package (e.g. `@company/ui`) published to the build mirror;
  the generator must use it rather than inventing UI. Contributed blocks (ADR 0006) map to component usages with preset props. This keeps
  "reuse approved components" true for code projects too.
* **Project spec:** pages, routes, component usages, static content — stored per code change, so a change can be explained, reviewed
  and regenerated.
* **Generator output rules (enforced by the API before committing):** only `src/**`, `public/**`, tests and allowlisted config files;
  dependency changes only to mirror-allowlisted packages; max files and bytes per change; text files only; no secrets (scan); no network
  calls to non-allowlisted origins (lint + CSP `connect-src 'none'` by default); no server code.
* **Prompt-injection stance:** user text, repository content and component docs are untrusted input to the model; model output is
  untrusted data. It is applied as text patches by the API and executed only inside the sandbox; the served result is static and runs under
  a strict CSP on a separate domain.
* **Code Mode:** file tree and editor showing the real repository at a SHA (from the Git server through the API), diff view of AI changes
  before merge, edits saved as commits on an edit branch, then the same build → preview → merge path. No terminal for users in v1.
* **Preview of generated code:** served from the preview domain (separate registrable domain), iframe `sandbox="allow-scripts"` without
  `allow-same-origin`; never on the Studio origin.
* **Accounting:** planner/generator calls go through the existing providers and `ai_calls` (tokens, cost, budgets); build minutes are
  accounted per job.

## Note — owner decisions 2026-10-02
* Scaffold: **React + Vite** static build (TypeScript), company UI package, no server runtime — the simplest pure static output.
* Only one domain (`toolsmcp.uk`) is available: previews of generated code get their own host (e.g. `preview.toolsmcp.uk`). Because that is
  the same registrable domain as the Studio, the Studio's cookies must stay host-only (they are: no `Domain` attribute) and preview content
  must never be served from `studio.toolsmcp.uk`; a separate registrable domain remains the recommended hardening before external users
  can create code projects.

## Not in scope
Server-side code, databases, secrets inside apps, connectors to company systems, mobile apps — they belong to `DYNAMIC_APP` (separate
approval).

## Acceptance criteria for 7.4
A prompt produces a branch, a passing build and a preview; a malicious patch (path outside allowlist, unapproved dependency, secret,
network call) is rejected before commit or fails the build; merge only after green build; Code Mode shows the same files as the Git
server; every AI change is traceable to prompt, model, tokens and commit.

## Implementation (2026-10-03)
* `code/CodeAi.kt` — `POST …/code/ai`: the current editable source files (≤ 40 000 chars) and the request go to the chosen model as delimited data
  (`ExternalLLMProvider.complete`, same fail-over/accounting as page prompts, tokens in `ai_calls`); the answer must be JSON with complete
  file contents (≤ 10 files); `CodeChangePolicy` (src/, public/, index.html; text only; size limits; credential patterns) is applied before a
  commit on `ai/<id>`; then the normal build → preview → merge path. Without a provider a **simulator** makes small real edits (title, button
  text, background) and says so. No project-spec layer and no company UI package yet (v1: plain React).
* Previews and published code apps are served by the sites gateway with **CSP `sandbox allow-scripts`** (opaque origin: no cookies, storage or
  same-origin access to sites/Studio — verified in the browser), assets with `Access-Control-Allow-Origin: *`; previews are unguessable,
  expiring capability URLs (`/_preview/<token>/`, revoked on discard) framed by the Studio only. **Private code apps are not supported** yet.
* UI: `features/studio/CodeWorkspace.tsx` (AI and Code modes, file tree + editor, changes with preview/diff/build & scan report, merge/discard,
  commit history, publish public).
