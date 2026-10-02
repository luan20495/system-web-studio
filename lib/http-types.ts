/** Shapes returned by the real backend (docs/API_CONTRACT.md). Independent from the mock-mode types. */
export type Section = { id: string; type: string; componentVersion?: string; props: Record<string, unknown> };
export type PageSchema = { page: string; sections: Section[] };

export type WorkspaceSummary = { id: string; name: string; role: string };
export type Me = { id: string; username: string; displayName: string; roles: string[]; workspaces: WorkspaceSummary[]; systemAdmin?: boolean };

export type ApiProject = {
  id: string; workspaceId: string; name: string; description: string | null; ownerUserId: string; framework: string;
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
  beforeSectionId?: string; afterSectionId?: string; index?: number;
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

export type PropDef = { type?: string; format?: string; enum?: string[]; maxLength?: number; maxItems?: number; itemRequired?: string[]; itemProperties?: Record<string, PropDef> };
export type PropsSchema = { required?: string[]; properties?: Record<string, PropDef> };
export type RegistryComponent = {
  id: string; name: string; category: string; description: string; latestVersion: string; status: string;
  versions: { version: string; status: string; propsSchema: PropsSchema }[];
  usedInProjects?: number | null;
};

export type Page<T> = { items: T[]; total: number; page: number; size: number };
export type AuditRow = { id: string; createdAt: string; action: string; resourceType: string; resourceId: string | null; actorId: string | null; actor: string | null;
  workspaceId: string | null; projectId: string | null; ipAddress: string | null; requestId: string | null; newValue: string | null; oldValue: string | null };
export type AdminOverview = { users: number; activeUsers: number; disabledUsers: number; usersLoggedIn30d: number; workspaces: number; projects: number;
  publishedProjects: number; aiRequestsToday: number; aiRequestsMonth: number; versionsToday: number; recentActivity: AuditRow[] };
export type AdminUser = { id: string; username: string; displayName: string | null; email: string | null; enabled: boolean; systemAdmin: boolean; authSource: string;
  createdAt: string; workspaces: number; projects: number; lastLoginAt: string | null };
export type AdminMembership = { id: string; name: string; role: string; workspaceId: string | null; workspaceName: string | null; owner: boolean };
export type AdminUserDetail = { user: AdminUser; workspaces: AdminMembership[]; projects: AdminMembership[]; activeSessions: number; recentActivity: AuditRow[] };
export type AdminWorkspace = { id: string; name: string; slug: string; createdAt: string; members: number; projects: number; lastActivityAt: string | null };
export type AdminMember = { userId: string; username: string; displayName: string | null; role: string; enabled: boolean };
export type AdminApp = { id: string; name: string; workspaceId: string; workspaceName: string; ownerId: string; owner: string; members: number; visibility: string;
  revision: number; latestVersion: number | null; createdAt: string; updatedAt: string; active: boolean; publishStatus: string | null; publishedAt: string | null };
export type AdminWorkspaceDetail = { workspace: AdminWorkspace; members: AdminMember[]; projects: AdminApp[]; recentActivity: AuditRow[] };
export type AdminAppDetail = { app: AdminApp; members: AdminMember[];
  versions: { id: string; versionNumber: number; kind: string; summary: string; createdBy: string | null; createdAt: string }[];
  prompts: { id: string; text: string; createdAt: string; user: string | null; provider: string | null; model: string | null; outcome: string | null }[];
  deployments: { id: string; status: string; visibility: string; provider: string; url: string | null; error: string | null; createdAt: string; finishedAt: string | null; versionNumber: number | null }[];
  audit: AuditRow[] };
export type AdminAi = { provider: string; configured: boolean; mode: string; models: AiModel[]; dailyLimitPerUser: number; promptsPerMinute: number; requestsToday: number;
  requestsMonth: number; externalToday: number; byModelMonth: { provider: string; model: string | null; outcome: string; count: number }[];
  recent: AdminAppDetail["prompts"]; tokenAccounting: string; costAccounting: string };
export type AdminComponent = { id: string; name: string; category: string; description: string; latestVersion: string; status: string; usedInProjects: number; sections: number; propsSchema: string | null };
export type HealthItem = { name: string; status: "HEALTHY" | "DEGRADED" | "UNAVAILABLE" | "UNKNOWN" | "NOT_CONFIGURED"; latencyMs: number | null; detail: string | null };
export type PlatformHealth = { checkedAt: string; items: HealthItem[]; uptimeSeconds: number; javaVersion: string; schemaVersion: string | null; profiles: string[];
  publishQueueDepth: number | null; deadLetterDepth: number | null };
export type MyUsage = { aiConfigured: boolean; aiRequestsUsed: number; aiRequestsLimit: number; aiWindowResetsInSeconds: number | null; promptsPerMinute: number; promptsToday: number };
