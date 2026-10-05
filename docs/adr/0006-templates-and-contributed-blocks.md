# ADR 0006 — Templates and contributed components ("blocks") are data, not code
Status: accepted (2026-10-02), implemented in Phase 5 (migration V11)

## Context
The spec asks for a template library (Company Templates / My Templates) and a component contribution workflow
(PRIVATE → SUBMITTED → VALIDATING → REVIEW → APPROVED → DEPRECATED) "without silently migrating the renderer into arbitrary
JavaScript execution". Every section today is rendered by fixed code for an approved registry component, and that is the
security guarantee: user content is data validated against a props schema, escaped by the renderer.

## Decision
* **Template** = a validated page schema (`templates.schema`), copied server-side from a project's *saved* page. Images
  (`asset://`) are cleared on save because they belong to the source project. Visibility `PRIVATE` (author) or `COMPANY`; only a
  system admin makes a template `COMPANY`, and from then on only a system admin may change or archive it. A new project can start
  from a visible, active template; the schema is re-validated against today's registry (`422 TEMPLATE_OUTDATED` otherwise) before
  anything is stored. Templates carry a version number (re-saving bumps it); there is no template review queue.
* **Block** (`component_packages`, `component_package_versions`, `component_reviews`) = a named, versioned preset of the props of
  an **approved** registry component, copied server-side from a section of a saved page. No HTML, CSS or JavaScript is stored.
  Inserting a block creates an ordinary section of the base component, which goes through the same validator and renderer as any
  edit. A genuinely new component type still needs a renderer in source code and normal code review.
* Workflow: owner drafts (`PRIVATE`, usable only by the owner) → submit runs synchronous automated checks (base component approved,
  props valid, no project files, size ≤ 16 000 chars, no embedded-code patterns, name unique in the library) and records
  `SUBMIT` + `VALIDATION_PASSED|FAILED` → `REVIEW` → a system admin **other than the owner** approves (checks re-run at decision
  time) or rejects with a mandatory reason → `DEPRECATED` hides it from the library without touching pages that use it; restore
  is possible. Editing an approved block creates a new draft version; the approved version stays in the library until the new one
  is approved. `SUBMITTED`/`VALIDATING` are reserved states for a future asynchronous validator.

## Consequences
* The registry security guarantee is unchanged; there is no new execution surface.
* Usage of a block is not measurable (an inserted block is a plain section with no origin marker). Shown as "not measured".
* The AI planner does not know about blocks yet.
* Template thumbnails and block previews are rendered by the same script-less renderer in `sandbox=""` iframes.
