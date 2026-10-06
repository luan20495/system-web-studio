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
| `RequestContexts.kt.skel` | checklist step 4 (import C1) | HTTP session → `Principal` → `TenantContext` → `GatewayContext` / `ActionContext` | C1 |
| `C1PortAdapters.kt.skel` | steps 4 / 11 / 14 (C1, then C3 and C4 imports) | C1 policy cores → C3 `GatewayAuthorizer`, C4 `AccessPort` / `TenantGate` / `PrincipalResolver` | C1, C3, C4 |
| `AppDefinitionSourceAdapter.kt.skel` | step 6 (import C2) | C4 `AppDefinitionSource` ← committed AppDefinition (LIVE = published, TEST = draft) | C2, C4 |
| `ActionDataPortAdapter.kt.skel` | step 14 (import C4) | C4 `ActionDataPort` → C2 `AppDataBindingResolver` → C3 `DataGateway` | C2, C3, C4 |
| `AiDataCatalogAdapter.kt.skel` | step 11 (import C3) | C3 `AiDataCatalogProvider` → C2 `ai.planner.AiDataCatalog` | C1, C2, C3 |
| `DataWebhookController.kt.skel` | step 11 (import C3) | `POST /api/v1/webhooks/data/{endpointId}` → C3 `WebhookIngress` | C3 |

Domain models are never copied here: if a type appears in a skeleton it is imported from its owner's package.
