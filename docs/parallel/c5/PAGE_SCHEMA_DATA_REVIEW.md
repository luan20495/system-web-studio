# C5 — review of C2's PAGE_SCHEMA data runtime (before any C5 code)

Sources read (authoritative): C2 `fix/c2-v3 @ c1e0df5` — `docs/parallel/c2/HANDOFF_C5_PAGE_SCHEMA_DATA.md`, `workers/render/page-runtime.ts`, `lib/schema-preview.ts` (hunk),
`DeploymentProcessor.announcePublicQueries`, `PublishConfigApi/Policy`, `PagePublishedRuntimeTests`; C0 baseline `integration/v2 @ 27f7b6f` (Public Runtime route behind a flag).
Written down BEFORE coding, as the task asked. "Mismatch" = a place where the task text and the code/contract differ, or where C5's code cannot be exact.

## What the "hook" really is
`lib/schema-preview.ts` takes `RenderOptions.bindings?: RuntimeBinding[]` (`{id, sectionId, prop, query, kind: "text"|"list"}`) and, ONLY with `published: true`, annotates the HTML with `data-xw-*`.
The behaviour (loading config → not-ready | loading-data → ready | error) lives in the published runtime script `_runtime/page-runtime.js`, produced by the render worker. There is NO React hook and NO
function C5 can call to get "state"; the state is observable only in a published page (`<main data-xw-state>`, `xw:state` event, per element `data-xw-state`). The Studio preview stays "not data-aware" (C2 says so).

## Mismatches
| # | Task text | Contract / code | C5 decision |
|---|---|---|---|
| M1 | "publish approval … `acknowledgePublicData`" | It is NOT a field of `POST /publish` (release contract unchanged). It exists only in the flag-gated publish-config API (`PUT …/publish-config`, `POST …/adopt-draft`, policy issue `PUBLIC_DATA_NOT_APPROVED`) and `publish` does not consult it. | The dialog asks for a LOCAL acknowledgement (checkbox) when the draft has public queries; nothing is added to the publish body. Enforcement on the server = handoff **H-C2-07**. |
| M2 | "show `PUBLIC_QUERIES` in the publish approval" | `PUBLIC_QUERIES` is a deployment EVENT written during BUILDING, i.e. AFTER the click. Before the click the server tells nothing. | Before publishing: C5 computes the same list from the draft (`collectPublicQueries`, mirror of `PublicQueries.of`). After: the dialog shows the server's `PUBLIC_QUERIES` event text and flags any difference between the two. |
| M3 | "component/page → logical slot → query → public READ query" | `dataBindings[]` has no slot: `{sectionId, prop, queryRef|viewModelRef(+mappingRef)}`; the slot is `query.dataSourceRef`. | A binding is edited as section+prop → query; the slot is DERIVED and shown, never stored on the binding. |
| M4 | "validate missing slot/query, WRITE-public, non-public query" for a binding | The real check is `resolveBindings` in the render worker at publish (`[BUILD_FAILED] … binding '<id>' …`). Also: a bound prop must be in the V1 table (Hero.ctaLabel, ComparisonBlock.rows, images are refused), ≤ 8 distinct queries. | C5 mirrors the table + rules in one pure module with a provenance header and fixtures taken from C2's own tests; it is advisory, the server stays the authority. |
| M5 | "preview states reusing the C2 hook" | No hook (see above); preview is not data-aware and fake data in public mode is forbidden. | Studio shows a READINESS panel derived from the contract (config → apiBase → binding resolvable → query public) with the SAME five state names, never rows. The five runtime states are reproduced in the harness by feeding the REAL `page-runtime.js` fixture markup, not a C5 state machine (see tests). The real states are asserted in E2E-PD01 against the real stack. |
| M6 | "the public data controller" | Exists on integration only behind a flag (C0 D-C0-36); C2's code (`fix/c2-v3`) is not on integration/v2. | Real E2E is prepared and returns BLOCKED/NOT_READY with the exact missing piece when the stack lacks it. |
| M7 | slot id | `^[a-z0-9][a-z0-9-]{0,63}$` (lowercase, no `.`/`_`), type `^[a-z][a-z0-9-]{0,31}$`. C5's existing `ID_RE` is looser. | Slot validation uses the stricter C2 regexes, not `ID_RE`. |
| M8 | slot with a `sourceRef` | Renamable, `type` immutable. | The editor disables `type` for such a slot; `sourceRef` is never shown as editable, never sent. |

## Handoffs raised (docs/parallel/c5/HANDOFFS_PAGE_SCHEMA_DATA.md)
- H-C2-07: consult `publicDataApproved`/acknowledgement in `publish` (today a PUBLIC release with public queries is not blocked by anything); expose public queries BEFORE publish (dry-run/preflight) so the dialog need not mirror `PublicQueries.of`.
- H-C0-10: import `fix/c2-v3 @ c1e0df5` (types for the three operations + `QueryDef.public`) and the public data controller flag, for the canonical E2E.

## Result (what C5 built from this review)
| Mismatch | Resolution in code |
|---|---|
| M1 acknowledgement | `ReleaseModal` (prop `draft`): local checkbox, required only when `publishApproval(draft).required`; `publishBody` untouched; asserted by `publicdata.spec.mjs` 20–22 and E2E-PD02 |
| M2 PUBLIC_QUERIES | announced before from the draft (`collectPublicQueries`), shown after from the server event (`parsePublicQueriesEvent`), a difference is displayed (`diffAnnounced`) |
| M3 binding has no slot | `validateBinding` derives slot from the query; the row shows section.prop → query → slot; `buildQueryBinding` writes only `{id, sectionId, prop, queryRef}` |
| M4 binding validation | `core/publicData.ts` mirrors `resolveBindings`; 23 cases generated from C2's own code (`scripts/gen-publicdata-fixtures.mjs`) |
| M5 no hook | `PublicReadinessPanel` + the five state names; the real states are asserted on a page built by C2's `renderSitePages` with C2's runtime script (`tests/browser/build-c2-site.mjs`) |
| M6 controller | E2E-PD01 BLOCKED with the exact reason; PD02 passes on a `c1e0df5` stack |
| M7 slot regexes | `PUBLIC_ID_RE`, `SLOT_TYPE_RE` in `validateSlot` |
| M8 sourceRef slot | type locked, never shown, never sent (`slotPatch`) |

Defects found by running it against the real C2 backend: the query form kept an empty slot after a slot was added in the same session (fixed, harness check 13b). The readiness panel reports "not ready" for a site that was never published PUBLIC even when the draft is complete (correct: a PRIVATE page site has no `apiBase`); the E2E expectation was adjusted, not the UI.
