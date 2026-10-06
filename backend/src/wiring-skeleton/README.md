# wiring skeleton (C0) — NOT COMPILED, NOT A SOURCE SET

`*.kt.skel` files are the typed shape of `com.systemwebstudio.wiring.*` (the only package allowed to import across C1–C4, see
`docs/contracts/v2/integration-contract.md` §1). They are **not** in `src/main`, Gradle does not see them, and **nothing here is implemented**:
every body is an explicit `TODO(...)` or a commented mapping, because the types they import do not exist on `integration/v2` yet
(C1–C4 code is imported later, `docs/parallel/V2_IMPORT_MANIFEST.md`). A body that pretended to work would be a fake implementation.

When the owner's code has been imported and the Mac gate for that step is green (`docs/parallel/MAC_INTEGRATION_CHECKLIST.md`):
1. `git mv backend/src/wiring-skeleton/<File>.kt.skel backend/src/main/kotlin/com/systemwebstudio/wiring/<File>.kt`
2. replace each `TODO(...)` using the mapping written above it (the mapping is the specification; the signatures are copied from the owner branch heads recorded in the manifest),
3. add the test named in the file header, then run the step's checklist block.

| File | Moves at | Adapts | Needs |
|---|---|---|---|
| `AiDataCatalogAdapter.kt.skel` | step 11 (import C3) | C3 `AiDataCatalogProvider` → C2 `ai.planner.AiDataCatalog` | C1, C2, C3 |
| `DataWebhookController.kt.skel` | step 11 (import C3) | `POST /api/v1/webhooks/data/{endpointId}` → C3 `WebhookIngress` | C3 |

Domain models are never copied here: if a type appears in a skeleton it is imported from its owner's package.

**Implemented on `wire/c3-c4-runtime` (2026-10-06, Mac verification pending):** `RequestContexts` -> `wiring/RuntimeContexts.kt` + `ActorKinds.kt` (W-01, D-C0-14), `C1PortAdapters` -> `wiring/C1PortAdapters.kt` (W-02/03),
`AppDefinitionSourceAdapter` -> `wiring/RuntimeAppDefinitions.kt` (W-04), `ActionDataPortAdapter` -> `wiring/ActionDataPortAdapter.kt` + `DataWriteErrors.kt` (W-05, D-C0-15). The skeleton files were removed (`git rm`).
Still skeletons: W-06 `AiDataCatalogAdapter`, W-07 `DataWebhookController`.
