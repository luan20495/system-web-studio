# Canonical contract v2 — AppDefinitionV2

Owner: **C2** (shape + validator + commit path). Frozen by C0 on 2026-10-05 from `agent/c2-app-model` (`a4d5f8b`). Supersedes `docs/contracts/app-definition-v2.md` (its `pages: List<PageDef>` and `actions: List<ActionRef>` forms are **withdrawn**). Code behind it: written, not compiled, not run.

## 1. Principles (unchanged)
Superset of the existing Page Schema; legacy documents stay valid and round-trip unchanged. Declarative only: ids and typed references, never SQL, URLs, credentials or code. Every change goes `typed operation → patch → AppDefinitionValidator (+ PageSchemaValidator) → SchemaCommitService (CAS revision) → immutable version → audit`. AI uses the same path. `PAGE_SCHEMA` and `STATIC_APP` are untouched.

## 2. Stored document (JSON keys) — canonical
Legacy keys, kept verbatim: `page`, `sections`, `pages[]` (additional pages; the home page is the root `sections`), `site` (incl. `site.navigation`), `seo`.
V2 keys, all optional: `schemaVersion` (int), `kind` (`"PAGE_SCHEMA"`), `theme`, `dataSources[]`, `viewModels[]`, `queries[]`, `mappings[]`, `dataBindings[]`, `actions[]`, `workflows[]`, `permissions[]`, `publishConfig`, `extensions{}`.
`pages`, `components`, `navigation` in the target list are **derived read views** (`pages()`, `components()`, `navigation()`), not stored keys. A document is strict (unknown top-level keys rejected) only if it declares `schemaVersion`.
Kotlin names: `AppDefinitionV2`, `AppDefinitionKind { PAGE_SCHEMA }` (the frontend must not reuse the name `AppKind`, which already means the product type).

## 3. Who owns the shape of each list
| Key | Shape owner | Rule |
|---|---|---|
| `dataSources[]` `{id, name?, type, sourceRef?, description?}` | **C2** slot, **C3** meaning | `sourceRef` = id of a C3 `DataSource` (UUID, may be null = unresolved slot). Never a URL/connection string. |
| `queries[]` `{id, name?, dataSourceRef, mode READ\|WRITE, operationKey?, params[], maxRows?}` | **C3** (`QueryDefinition`/`MutationDefinition`) | In the document it is a *binding*: local `id` + local `dataSourceRef` + `operationKey` = id of the C3 query/mutation definition. `ParamType` = C3's (`STRING, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE`) — C2 adds `DATE`. `ParamDef.required` default **true** (C3's default). |
| `mappings[]` | **C3** (`MappingDefinition`) | Stored as C3's JSON: `{id, name?, queryRef (local query id), errorPolicy, fields[{from?, to, transforms[], default?, nullable, validation?}]}`. **Canonical = `fields[].transforms[]`** (an array, at most 8 entries, applied in order); writers (C2 ops, templates, AI planner, C5 types) emit only this. The legacy single `transform` key is **accepted by the reader only, for migration compatibility** (it is read as a one-element `transforms[]` and re-written as `transforms[]`); a field carrying **both** `transform` and `transforms` is rejected as ambiguous (D-C3-13). No new document, template or fixture may use `transform`. C2 must not drop `transforms/default/nullable/validation/errorPolicy` (lossless round-trip) and validates them by calling C3's parser. |
| `viewModels[]` | **C3** (`ViewModelDefinition`) | `{id, name?, queryRef?, mappingRef?, cardinality SINGLE\|LIST, fields[{name,type,label?}]}`; `FieldType` enum values must equal C3's. |
| `dataBindings[]` `{id, sectionId, prop, viewModelRef? \| queryRef?, mappingRef?}` | **C2** | The only binding concept. C3 has none; the draft `DataBinding{queryRef, mapping[]}` is withdrawn. |
| `actions[]` | **C4** (declaration), C2 stores + validates references | See `action-workflow.md` §2. Inline `trigger?{sectionId, event}` — **optional**: required only for an action bound to a UI event, absent for workflow/schedule/chained actions; there is no separate stored `ActionRef` list (`actionRefs()` is a derived view). |
| `workflows[]` | **C4** | See `action-workflow.md` §3. |
| `permissions[]` `{id, name?, permission, resourceType, resourceRef}` | **C2** stores, **C1** vocabulary | `permission` must be one of the **14** canonical codes in `tenant-permission.md` §5 (membership test, not a regex). |
| `publishConfig` | **C2** | **Draft intent only** (see §5). |
| `theme`, `extensions{}` | C2 | `extensions` keys must be namespaced (`xweb.*`). |

All cross-references inside the document are **local ids**. Translation to runtime identifiers (DataSource UUID, C3 operation id) is done only by `AppDataBindingResolver` (C2, `app.definition`, see `data-runtime.md` §3).

## 4. Operations
`OperationTypes.all` (12 legacy) is unchanged. `OperationTypes.definitions` (23 typed V2 ops: ADD/UPDATE/REMOVE × VIEW_MODEL, QUERY, MAPPING, DATA_BINDING, ACTION, WORKFLOW_REF, PERMISSION_REF; UPDATE_THEME; UPDATE_PUBLISH_CONFIG) is canonical. There is no op that creates a DataSource (created through the C3 admin API; the document only references it). `REMOVE_*` does not cascade; dangling references are caught at commit. A commit through `SchemaCommitService` is the only write path — **`SchemaService.ensureInitialized` (initial/template version) must call the V2 validator too** (INTEGRATION_V2 R-12).

## 5. Publish model (canonical)
| Layer | What | Mutable? | Owner |
|---|---|---|---|
| `AppDefinition.publishConfig` | declarative **draft** `{mode STATIC\|DYNAMIC, visibility, requiresAuth, cacheSeconds}`; versioned with the document | yes (draft) | C2 |
| `publish_configs` (+ API, `app.publish-configs.enabled`) | persistent **policy** per project, CAS `revision`, hashed private-link token, `public_data_approved`; `adopt-draft` copies draft → policy | yes | C2 (migration **V27**, after V26) |
| `deployments` / `server_deployments` | the **served state**; visibility fixed at deploy time; rollback never reads `publish_configs` | immutable | C0-gated (`publish/**`) |
Visibility `TENANT` and `PRIVATE_LINK` are not allowed by the base `deployments_visibility_check`; extending it is a separate C0 migration tied to publish wiring. `public_data_approved` must be re-checked against the current `dataBindings` at publish time (C0, R-14).

## 6. AI
`Prompt → structured typed operations → PlanGuard → apply on a copy → validators → proposal → SchemaCommitService → immutable version`. The planner never writes, never edits `dataSources` or `publishConfig`, never weakens `permissions`, and may only use data sources/operations/params from an `AiDataCatalog` supplied by C3 (`AiSafeSchema`). Flags `app.ai-planner.enabled`, `app.tenant-ai.enabled`, `app.publish-configs.enabled` are **OFF** and stay off until the conditions in `integration-contract.md` §6 are met. AI Gateway governance (`ai/**`, ADR 0014) is unchanged.

## 7. Component metadata
`ComponentMetadataV2` (bindableProps, events, supportedActions, visibilityConditions, preview) is read-only API `GET /api/v1/component-metadata`. Event names must be the canonical `EventType` wire names (`onLoad, onClick, onChange, onSubmit, onSuccess, onError`). No migration. This satisfies C5's B-C5-03.
