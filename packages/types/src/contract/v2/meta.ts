/*
 * MIRROR of docs/contracts/v2/{app-definition,data-runtime,action-workflow,tenant-permission,integration-contract}.md
 *   @ integration/v2 c59604b786b375fa3817982c611c0fa2b4de6f13 — manual
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
  commit: "c59604b786b375fa3817982c611c0fa2b4de6f13",
  files: [
    "docs/contracts/v2/app-definition.md",
    "docs/contracts/v2/data-runtime.md",
    "docs/contracts/v2/action-workflow.md",
    "docs/contracts/v2/tenant-permission.md",
    "docs/contracts/v2/integration-contract.md",
  ],
  /** values the docs delegate to the owner's code; integration/v2 has no backend code yet, so these are read from the owners' branches */
  codeRefs: [
    { what: "AppDefinitionV2 model, op shapes (definitionId/definition), validator rules", where: "fix/c2-v2 @ 99d8169 (app/definition/*, schema/DefinitionPatch.kt)" },
    { what: "FieldType, MappingErrorPolicy, ValueFormat, Transform names", where: "agent/c3-data (data/mapping/*; enums identical at 607c116)" },
    { what: "StepKind, CompareOp, Condition tree", where: "agent/c4-workflow (logic/workflow/WorkflowModel.kt)" },
    { what: "ComponentMetadataV2 JSON", where: "fix/c2-v2 (component/ComponentMetadata.kt), served by GET /api/v1/component-metadata" },
  ],
} as const;

export type ContractSource = typeof CONTRACT_SOURCE;
