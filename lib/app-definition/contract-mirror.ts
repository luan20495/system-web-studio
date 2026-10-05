/**
 * MIRROR of docs/contracts/app-definition-v2.md — NOT AUTHORITATIVE. The backend (C2, `com.systemwebstudio.app.definition`)
 * owns AppDefinitionV2; this file only restates, verbatim, the leaf types that the contract spells out, so that T12 has a typed
 * landing place. It is type-only (no runtime code), imported nowhere, and must be deleted or regenerated if the contract changes.
 *
 * Deliberately NOT mirrored, because the contract is missing or ambiguous (see docs/parallel/audit/PREP-T12-builder-architecture.md §3):
 *  - the AppDefinitionV2 root (`pages: List<PageDef>` does not match the real Page Schema, whose home page is the root `sections`) — G1
 *  - `FieldMapping`, `ViewModelData`, `PageSpec`, `TriggerInfo` (named by the contracts, no shape) — G4
 *  - the new operation types (`ADD_BINDING`, `REMOVE_BINDING`, `ADD_ACTION_REF`, … the contract ends the list with "…") — G9
 * Section props must never carry bindings: PageSchemaValidator rejects unknown props, and the contract keeps bindings in a separate list.
 */

/** Opaque until the contract defines `FieldMapping` (data-connector.md / app-definition-v2.md only name it). Do not read its fields. */
export type FieldMapping = unknown;

/** `DataBinding` — app-definition-v2.md. `queryRef` is the id of a defined Query (data-connector.md), never SQL or a URL. */
export type DataBinding = {
  id: string;
  sectionId: string;
  prop: string;
  queryRef: string;
  mapping: FieldMapping[];     // contract default: empty list
};

/** `ActionRef` — app-definition-v2.md. `trigger` is e.g. "sectionId.event"; `actionId` references the ActionRuntime (action-workflow.md). */
export type ActionRef = {
  id: string;
  trigger: string;
  actionId: string;
};
