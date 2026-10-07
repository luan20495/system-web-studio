import type { AppDefinitionV2, ComponentMetadataV2, DefinitionOperation, RegistryComponent } from "@xweb/types";
import type { Readiness } from "./core/readiness";
import type { DataManagementCalls } from "./core/dataManagement";

/** What every definition editor (data, action, workflow, permission, theme) receives. One write funnel: `commit` = applyOps → PATCH /schema. */
export type DefCtx = {
  doc: AppDefinitionV2;
  commit: (ops: DefinitionOperation[], summary: string) => Promise<boolean>;
  /** may typed V2 definition operations be sent? (backend probe) */
  readiness: Readiness;
  canEdit: boolean;
  busy: boolean;
  /** the site's current visibility (PRIVATE | PUBLIC | …) — only used to word the readiness of public data; never to decide what is public */
  siteVisibility?: string | null;
  metadata: Map<string, ComponentMetadataV2>;
  registry: RegistryComponent[];
  labelOf: (type: string) => string;
  /** C3 Management API bound to this workspace/project; absent = no server (the data-source step says "Chưa sẵn sàng", nothing is simulated) */
  dataManagement?: DataManagementCalls;
  /** workspace-level DATA_SOURCE_MANAGE (the server decides; this only disables controls with a reason) */
  canManageData?: boolean;
  manageDataReason?: string;
  /** DATA_SOURCE_VIEW (metadata only) · and DATA_SOURCE_MANAGE + APP_EDIT for a TEST/draft binding; each with the reason shown on the disabled control */
  canViewData?: boolean;
  viewDataReason?: string;
  canBindData?: boolean;
  bindDataReason?: string;
};
