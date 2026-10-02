# ADR 0008 — Application types and the Software Factory roadmap
Status: **proposed** (2026-10-02) — design only, not implemented. Supersedes the "boundary only" scope of ADR 0005.

## Decision
* Introduce `projects.app_type`, backward compatible:
  * `PAGE_SCHEMA` — every existing project and the default. Page schema + approved registry + fixed renderer (today's product).
  * `STATIC_APP` — a source repository generated and edited by the factory, built in the build plane into a **static** artifact
    (HTML/CSS/JS/assets). No server-side code at runtime. First app type of the code track.
  * `DYNAMIC_APP` — server-side code, databases, connectors. **Not designed here**; requires its own approval (a hosting platform).
* The type is chosen at creation and never changes in place; "convert to code" (later) creates a new `STATIC_APP` project whose first
  commit is generated from the page schema, leaving the original untouched.
* UI: the "Create application" cards stay honest: Website (`PAGE_SCHEMA`) active; "Static web app (code)" appears only when 7.2–7.4 are
  live; Dashboard / Internal tool / Workflow remain "Coming soon" until `DYNAMIC_APP` exists (or until they can be expressed as
  `STATIC_APP` with approved data connectors — not before).
* Per-type capabilities (server-enforced, not just hidden in the UI):

| Capability | PAGE_SCHEMA | STATIC_APP |
| --- | --- | --- |
| AI edit | schema patches (today) | code patches on a branch (ADR 0012) |
| Design mode | registry inspector (today) | later: visual editing mapped to code components |
| Code mode | "not available" (honest) | real files from Git, edits become commits |
| Versions | `project_versions` | Git commits (+ `project_versions` rows pointing at SHAs for the existing UI) |
| Publish | render → artifact → runtime (ADR 0009) | sandbox build → artifact → runtime (ADR 0010, 0009) |

## Roadmap (each increment needs its own go-ahead)
7.1 static runtime for page-schema sites → 7.2 Git → 7.3 build plane → 7.4 code generation + Code Mode for `STATIC_APP` → 7.5 dynamic apps
(separate decision). Details, threat model and the decisions needed: `docs/SOFTWARE_FACTORY_DESIGN.md`.

## Consequences
Two editing models coexist; every API that edits content must check `app_type`. Existing projects and tests are unaffected by the column
(default `PAGE_SCHEMA`).
