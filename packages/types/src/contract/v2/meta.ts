/*
 * MIRROR of docs/contracts/v2/{app-definition,data-runtime,action-workflow,tenant-permission,integration-contract}.md
 *   @ integration/v2 8b944cca03c8851ade3ae61b5063fb4bea5acd84 — manual
 *   (first mirrored at c59604b; the docs changed once since: 8b944cc froze data-runtime §4b mutation errors, ActionDef.trigger optional, mapping
 *    `transforms[]` canonical, the 14-code list, and moved the fixtures to C2's conformance directory. Re-checked against integration/v2 4884be3
 *    (C1–C4 code imported) on 2026-10-06: every enum below equals the Kotlin enum of the owner; see docs/parallel/c5/PHASE3_AUDIT.md.)
 *
 * This directory is the ONLY frontend mirror of the canonical v2 backend contracts (integration-contract.md §8). It replaces
 * lib/app-definition/contract-mirror.ts (deleted). Rules:
 *  - names and wire values follow the contract; where the docs defer to the owner's enum (FieldType, Transform, CompareOp, ComponentMetadataV2)
 *    the value comes from the owner's code and the exact source is recorded in CONTRACT_SOURCE.codeRefs;
 *  - nothing here is a runtime: no function validates on behalf of the server, the server stays the authority;
 *  - to change a shape, change the contract (C0 decision) first, then bump CONTRACT_VERSION and this mirror together.
 */
export const CONTRACT_VERSION = "v2" as const;

export const CONTRACT_SOURCE = {
  branch: "integration/v2",
  commit: "8b944cca03c8851ade3ae61b5063fb4bea5acd84",
  /** the first commit this mirror was written from, and the last integration/v2 head it was re-verified against */
  firstMirroredAt: "c59604b786b375fa3817982c611c0fa2b4de6f13",
  verifiedAgainst: "4884be3678c11c6a6b3ae548cac75f611153c370",
  files: [
    "docs/contracts/v2/app-definition.md",
    "docs/contracts/v2/data-runtime.md",
    "docs/contracts/v2/action-workflow.md",
    "docs/contracts/v2/tenant-permission.md",
    "docs/contracts/v2/integration-contract.md",
  ],
  /** values the docs delegate to the owner's code. Read from integration/v2 @ 4884be3 now that C1–C4 are imported (earlier: the owners' branches). */
  codeRefs: [
    { what: "AppDefinitionV2 model, op shapes (definitionId/definition), validator rules", where: "integration/v2 app/definition/* (AppDefinitionModel.kt, AppDefinitionCodec.kt), schema/DefinitionPatch.kt" },
    { what: "FieldType, MappingErrorPolicy, ValueFormat, Transform names", where: "integration/v2 data/mapping/MappingModel.kt, data/query/Query.kt" },
    { what: "StepKind, CompareOp, Condition tree", where: "integration/v2 logic/workflow/WorkflowModel.kt, logic/action/ActionModel.kt" },
    { what: "ComponentMetadataV2 JSON", where: "integration/v2 component/ComponentMetadata.kt, served by GET /api/v1/component-metadata" },
  ],
} as const;

export type ContractSource = typeof CONTRACT_SOURCE;
