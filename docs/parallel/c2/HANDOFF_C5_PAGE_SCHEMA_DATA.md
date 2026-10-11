> **SUPERSEDED_BY:** `docs/PUBLISH_RUNTIME.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C2 → C5 — the exact contract for a data-bound PAGE_SCHEMA (V1 option b, D-C0-35)

As built on `fix/c2-v3` (2026-10-07). Everything below is implemented and tested; C2 changed no Studio UI. All of it travels through the existing `PATCH …/schema` operations and the existing publish / site API (`PUBLISH_API_CONTRACT.md` is unchanged except where stated). One file of C5's was touched, minimally: `lib/schema-preview.ts` (an optional `bindings` render option; with no bindings, and in the Studio preview, the output is byte-for-byte what it was — asserted by `tests/page-runtime/page-runtime.test.ts`). Please review that hunk.

## DATA_SOURCE_SLOT (B-C0-W-07) — three new operations
```
{"type":"ADD_DATA_SOURCE",    "definition":{"id":"orders","name":"Orders","type":"postgres","description":"…"}}
{"type":"UPDATE_DATA_SOURCE", "definitionId":"orders","definition":{"name":"Order table"}}        // null clears name / description
{"type":"REMOVE_DATA_SOURCE", "definitionId":"orders"}
```
A slot is `{id, name?, type, description?}` and nothing else. `id` `^[a-z0-9][a-z0-9-]{0,63}$`, `type` `^[a-z][a-z0-9-]{0,31}$` (required on add). **No `sourceRef`, no credential, no URL, host, SQL or any other field**: `400 INVALID_OPERATION` naming the field (`sourceRef` is refused by name, also `null`; the registration of a real source is the Management API `data-bindings`). A slot that already has a `sourceRef` (granted by the data platform) can be renamed but its `type` cannot change. Duplicate id / missing target → `400 INVALID_OPERATION`. REMOVE does not cascade: if a query, action or permission still names the slot the whole edit is rejected `422` with the path (`queries[0].dataSourceRef`: `unknown data source 'orders'`); remove the dependants first. Each edit is an immutable version. The AI planner cannot propose these operations. Frontend types to add: the three operation names in the operation union; `DataSourceDef.sourceRef` stays read-only in the editor.

## PUBLIC_QUERY_REFERENCE — which queries visitors may run
A query definition gets one optional boolean: `"public": true` (`ADD_QUERY` / `UPDATE_QUERY`; `null` clears; default false = deny). **Only a READ query can be public** (a WRITE query with `public: true` → `422`, path `queries[i].public`). The allow-list of a release is the set of its public queries, frozen in the version snapshot the release names: editing the draft after publishing cannot change what an already published release lists. Publishing writes a deployment event `PUBLIC_QUERIES` ("Public queries of this release …: a, b") — show it in the publish confirmation so the author sees what becomes public. TS: `QueryDef.public?: boolean`.

## COMPONENT_BINDING — what a page can bind
`dataBindings[]` is unchanged: `{id, sectionId, prop, queryRef | viewModelRef (+ mappingRef with queryRef)}`. At publish each binding is resolved to the LOCAL id of a public READ query (directly, through its view model's `queryRef`, or through the view model's mapping). Bindable in V1:

| component | scalar text props (one value) | list props (one row template) |
|---|---|---|
| Navbar | `brand` | |
| Hero | `eyebrow`, `title`, `description` | |
| ProductGrid | `heading` | `items` → row fields `name`, `description` |
| TechnologySection | `heading`, `body` | |
| ComparisonBlock | `heading` | |
| Testimonials | `heading` | `items` → row fields `quote`, `author`, `location`, `rating` (number → stars, clamped 0–5) |
| ContactForm | `heading` | |
| Footer | `text` | |
Anything else (e.g. `Hero.ctaLabel`, `ComparisonBlock.rows`, an image) is refused at publish with `[BUILD_FAILED] The page cannot be published: binding '<id>': <Component>.<prop> cannot be bound to data`. A binding to a query that is not public, does not exist, is not READ, a section that does not exist, or more than 8 distinct queries → the same refusal with the reason; nothing is silently skipped. Scalar rule: the first row's field named like the prop, else the only field of the row; otherwise a contract error (`field-missing`). List rule: one row per result row, unknown fields ignored, at most 200 rows. The authored values stay in the page as the fallback and are replaced only when the data arrives. The data is whatever the query's mapping / view model returns (`result.rows` of the runtime-api R1 shape), so name the mapped fields after the component fields above.
The Studio preview is not data-aware (it renders the authored values): a data-bound editor preview is C5's decision.

## RUNTIME STATES (what a test or the editor's "open published site" can observe)
Root `<main data-xw-runtime data-xw-state="…">`: `loading-config` → `not-ready` | `loading-data` → `ready` | `error`; `data-xw-detail` carries the reason (`api-base-missing`, `api-base-invalid`, `api-base-cross-origin`, `config-unavailable`; for `error`: `<queryId>:<code>,…`); `aria-busy` while loading; a bubbling-free `xw:state` CustomEvent on the root for every change. Each bound element: `data-xw-state` = `ready` | `empty` | `error` and `data-xw-error`. `apiBase == null` ⇒ `not-ready:api-base-missing`, **no request is made, nothing is guessed, the authored page stays**.

## ERRORS
Publish time: see COMPONENT_BINDING; builder: `[BUILD_FAILED] Rendered page contains a script` etc. Runtime codes (`data-xw-error` / detail): `forbidden` (401, 403), `not-found` (404: the query is not in the active release's list, or the site is offline), `rate-limited` (429), `unavailable` (5xx), `http-NNN`, `invalid-response`, `timeout` (15 s), `network`, `field-missing`, `template-missing`, `query-missing`, `too-many-queries`. One attempt per page load, never a retry loop.

## CONFIG SHAPE — `GET {site}/__factory/config.json` (now also for a data-bound page site; a page without bindings has none → 404)
`{"appId":uuid,"appName":string,"environment":"production","visibility":"PUBLIC"|"PRIVATE","version":string|null,"user":{"displayName"}|null,"flags":{},"apiBase":string|null,"releaseId":uuid|null,"generatedAt":instant}` — `no-store`; `apiBase` = `app.sites.data-api-base` with `{slug}` replaced, e.g. `http://127.0.0.1:18088/{slug}/_data` locally; **`null` for a PRIVATE page site** (how a member reaches the anonymous data route is not frozen). No tenant, workspace, credential, lease, fence or artifact hash is ever in it.

## What is NOT possible yet (so the editor can say so)
The public data controller behind `/{slug}/_data/…` (C0 + C3, the `PUBLIC_SITE` principal of C1) does not exist: a published data-bound page ends in `error:…not-found`/`unavailable` until it does. Everything up to it is real and tested (gateway, page, runtime, config, allow-list).
