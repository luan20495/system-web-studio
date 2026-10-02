/** Shapes returned by the real backend (docs/API_CONTRACT.md). Independent from the mock-mode types. */
export type Section = { id: string; type: string; componentVersion?: string; props: Record<string, unknown> };
export type PageSchema = { page: string; sections: Section[] };

export type WorkspaceSummary = { id: string; name: string; role: string };
export type Me = { id: string; username: string; displayName: string; roles: string[]; workspaces: WorkspaceSummary[] };

export type ApiProject = {
  id: string; workspaceId: string; name: string; description: string | null; framework: string;
  siteVisibility: "PRIVATE" | "PUBLIC"; authMode: "NONE" | "LOCAL" | "OIDC"; domain: string | null; customDomain: string | null;
  deploymentMode: "MOCK" | "SELF_HOSTED" | "CLOUD"; deploymentTarget: string | null; status: string; revision: number;
  createdAt: string; updatedAt: string; permissions: string[];
};

export type VersionSummary = {
  id: string; versionNumber: number; kind: string; summary: string; createdBy: string | null; createdAt: string;
  current: boolean; restorable: boolean; restoredFromVersionId: string | null;
};
export type SchemaResponse = { schema: PageSchema; revision: number; version: VersionSummary | null };
export type PromptResponse = {
  promptId: string; outcome: "UPDATED" | "NO_CHANGE" | "UNSUPPORTED"; message: { role: string; content: string };
  schemaPatch: SchemaOperation[]; pageSchema: PageSchema; revision: number; version: VersionSummary | null; registryReuse: number; provider?: string; model?: string | null;
};
export type PromptHistoryItem = { id: string; text: string; createdAt: string; outcome: string; assistantMessage: string; versionId: string | null; registryReuse: number | null };

export type SchemaOperation = {
  type: "ADD_SECTION" | "REMOVE_SECTION" | "MOVE_SECTION" | "UPDATE_SECTION" | "UPDATE_PROP" | "ADD_ITEM" | "REMOVE_ITEM";
  sectionId?: string; sectionType?: string; itemId?: string; arrayPath?: string; path?: string; value?: unknown; item?: unknown; props?: unknown;
};

export type AssetDto = { id: string; name: string; contentType: string; size: number; status: string; createdAt: string; downloadUrl: string | null };
export type UploadUrl = { assetId: string; uploadUrl: string; method: string; headers: Record<string, string>; expiresInSeconds: number };

export type DeploymentStatus = "QUEUED" | "POLICY_CHECK" | "SECURITY_CHECK" | "BUILDING" | "DEPLOYING" | "RUNNING" | "FAILED" | "ROLLED_BACK";
export type Deployment = {
  id: string; versionNumber: number; visibility: "PRIVATE" | "PUBLIC"; status: DeploymentStatus; url: string | null; error: string | null;
  provider: string; mock: boolean; createdAt: string; finishedAt: string | null; events: { status: string; message: string | null; createdAt: string }[];
};

export type AuthConfig = { localLogin: boolean; oidc: boolean; oidcLoginUrl: string; signup?: boolean; signupInviteRequired?: boolean };
export type AiModel = { id: string; name: string; contextLength: number };
export type AiStatus = { provider: "openrouter" | "mock"; configured: boolean; defaultModel: string; dailyLimitPerUser: number; models: AiModel[]; dataNotice: string };
export type Member = { userId: string; username: string; displayName: string | null; email: string | null; role: string; joinedAt: string };
export const WORKSPACE_ROLES = ["WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER"] as const;
export const PROJECT_ROLES = ["OWNER", "EDITOR", "PUBLISHER", "VIEWER"] as const;
