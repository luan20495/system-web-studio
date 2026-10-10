/** Shapes returned by the real backend (docs/API_CONTRACT.md). Independent from the mock-mode types. Owner: C5 (hot file; moved from lib/http-types.ts, which now re-exports this module). */
export type Section = { id: string; type: string; componentVersion?: string; props: Record<string, unknown> };
export type Seo = { title?: string; description?: string; noindex?: boolean };
export type NavLink = { id: string; label: string; pageId?: string; url?: string; anchor?: string };
export type SitePage = { id: string; slug: string; title: string; seo?: Seo; sections: Section[] };
export type SiteMeta = { title?: string; home?: { title?: string; seo?: Seo }; navigation?: NavLink[]; notFound?: { title?: string; message?: string } };
/** The root page is the home page; `pages` are the other pages of a multi-page site (stage G). */
export type PageSchema = { page: string; sections: Section[]; site?: SiteMeta; pages?: SitePage[] };

/** MIRROR of identity/AuthController.kt `WorkspaceSummary` + `MeResponse` and access/MeTenancy.kt @ integration/v2 8b944cc.. 4884be3 — manual.
 *  The tenancy fields are OPTIONAL on purpose: an older backend (and the legacy mock) does not send them, and every gate below falls back to `systemAdmin`.
 *  The client NEVER sends a tenant id back: the server derives it from the workspace in the URL (tenant-permission.md §2). */
export type TenantRoleName = "TENANT_ADMIN" | "MEMBER";
export type TenantMembershipSummary = { id: string; slug: string; name: string; status: string; role: TenantRoleName | string };
export type WorkspaceSummary = { id: string; name: string; role: string; tenantId?: string | null; permissions?: string[] };
export type Me = {
  id: string; username: string; displayName: string; roles: string[]; workspaces: WorkspaceSummary[]; systemAdmin?: boolean;
  /** primary tenant; null = no tenant membership (a pure platform operator) */
  tenantId?: string | null; tenantRole?: TenantRoleName | null;
  /** SYSTEM_ADMIN acts on the platform scope; says nothing about business data (see businessAccess) */
  platformScope?: boolean; businessAccess?: boolean;
  tenants?: TenantMembershipSummary[];
  /** platform + primary-tenant permissions as the 14 canonical codes (never legacy storage constants) */
  permissions?: string[];
  /**
   * C1 H-C1-04 (`docs/parallel/c1/h-c1-04-project-scoped-auth-me.md`): one row per project the person can really use, resolved by the same AccessService.forProject() as the project APIs.
   * `permissions` are canonical codes of THAT project only; they are never merged into `permissions` / `workspaces[].permissions` and never unioned across rows.
   * `role` is informational (display): never branch on it. ABSENT = an older backend (it cannot say no); an empty array = no project scope.
   */
  projectScopes?: ProjectScope[];
};

export type ProjectScope = { projectId: string; workspaceId: string; role?: string; permissions: string[] };

export type ApiProject = {
  id: string; workspaceId: string; name: string; description: string | null; ownerUserId: string; framework: string;
  siteVisibility: "PRIVATE" | "PUBLIC"; authMode: "NONE" | "LOCAL" | "OIDC"; domain: string | null; customDomain: string | null;
  deploymentMode: "MOCK" | "SELF_HOSTED" | "CLOUD"; deploymentTarget: string | null; status: string; revision: number;
  createdAt: string; updatedAt: string; permissions: string[]; appKind?: AppKind;
  /** PAGE_SCHEMA (website from approved components) or STATIC_APP (code project with a Git repository) */
  appType?: "PAGE_SCHEMA" | "STATIC_APP";
};

export type VersionSummary = {
  id: string; versionNumber: number; kind: string; summary: string; createdBy: string | null; createdAt: string;
  current: boolean; restorable: boolean; restoredFromVersionId: string | null;
};
export type SchemaResponse = { schema: PageSchema; revision: number; version: VersionSummary | null };
export type PromptResponse = {
  promptId: string; outcome: "UPDATED" | "NO_CHANGE" | "UNSUPPORTED" | "CANCELLED" | "TIMEOUT"; message: { role: string; content: string };
  schemaPatch: SchemaOperation[]; pageSchema: PageSchema; revision: number; version: VersionSummary | null; registryReuse: number; provider?: string; model?: string | null;
  /** null for the simulator; token/cost fields are null when the provider did not report them */
  usage?: PromptUsage | null;
  stopped?: "CANCELLED" | "TIMEOUT" | null; partial?: string | null; reuseSources?: ReuseSources | null;
};
export type ReuseSources = { components: string[]; blocks: string[]; templates: string[]; generated: number };
export type StreamHandlers = { onStart?: (streamId: string, deadlineMs?: number | null) => void; onDelta?: (text: string) => void; onStatus?: (text: string) => void };
export type PromptUsage = { attempts: number; promptTokens: number | null; completionTokens: number | null; totalTokens: number | null; costUsd: number | null; latencyMs: number };
export type PromptHistoryItem = { id: string; text: string; createdAt: string; outcome: string; assistantMessage: string; versionId: string | null; registryReuse: number | null;
  provider?: string | null; model?: string | null; aiCalls?: number; totalTokens?: number | null; costUsd?: number | null };

export type SchemaOperation = {
  type: "ADD_SECTION" | "REMOVE_SECTION" | "MOVE_SECTION" | "UPDATE_SECTION" | "UPDATE_PROP" | "ADD_ITEM" | "REMOVE_ITEM"
    | "ADD_PAGE" | "UPDATE_PAGE" | "REMOVE_PAGE" | "SET_NAVIGATION" | "UPDATE_SITE";
  sectionId?: string; sectionType?: string; itemId?: string; arrayPath?: string; path?: string; value?: unknown; item?: unknown; props?: unknown;
  beforeSectionId?: string; afterSectionId?: string; index?: number; pageId?: string;
};
export type FormSubmission = { id: string; formId: string; pageId: string; data: Record<string, string>; createdAt: string };
export type SiteDomain = { id: string; hostname: string; status: "PENDING" | "VERIFIED" | "FAILED"; tlsStatus: "UNKNOWN" | "PENDING" | "ACTIVE" | "ERROR"; lastError: string | null;
  verifiedAt: string | null; lastCheckedAt: string | null; createdAt: string; txtName: string; txtValue: string; cnameTarget: string };

export type AssetDto = { id: string; name: string; contentType: string; size: number; status: string; createdAt: string; downloadUrl: string | null };
export type UploadUrl = { assetId: string; uploadUrl: string; method: string; headers: Record<string, string>; expiresInSeconds: number };

/** The closed list of `deployments.status` (docs/parallel/c2/PUBLISH_API_CONTRACT.md §5, V30). ROLLBACK_FAILED / ROLLBACK_OFFLINE are EVENT names, never statuses. */
export type DeploymentStatus = "QUEUED" | "POLICY_CHECK" | "SECURITY_CHECK" | "BUILDING" | "DEPLOYING" | "ROLLING_BACK" | "RUNNING" | "FAILED" | "ROLLED_BACK";
export type Deployment = {
  id: string; projectId?: string; versionId?: string; updatedAt?: string; versionNumber: number; visibility: "PRIVATE" | "PUBLIC"; status: DeploymentStatus; url: string | null; error: string | null;
  provider: string; mock: boolean; createdAt: string; finishedAt: string | null; events: { status: string; message: string | null; createdAt: string }[];
};

export type AuthConfig = { localLogin: boolean; oidc: boolean; oidcLoginUrl: string; signup?: boolean; signupInviteRequired?: boolean;
  /** this server has the Git server + build runner configured for code projects */
  codeProjects?: boolean; codeAppPublicPublish?: boolean; publicPublish?: boolean;
  /** SAML through the OIDC provider's identity brokering (stage I) */
  saml?: boolean; samlLabel?: string; samlLoginUrl?: string; mfa?: "IDP";
  /** server apps: policy on and the isolated runtime configured (stage J) */
  serverApps?: boolean;
  /** no enabled system administrator exists yet: the operator must create the first one */
  needsSetup?: boolean };
export type AiModel = { id: string; name: string; contextLength: number; provider?: string; paid?: boolean };
export type AiProviderStatus = { id: string; name: string; paid: boolean; models: AiModel[]; dataNotice: string };
export type AiStatus = { provider: "openrouter" | "providers" | "mock"; configured: boolean; defaultModel: string; dailyLimitPerUser: number; models: AiModel[]; dataNotice: string;
  providers?: AiProviderStatus[] };
// ---- Phase 6: providers, model policy, pricing catalog (admin)
export type AiPrice = { id: string; provider: string; modelId: string; inputUsdPerMTok: number; outputUsdPerMTok: number; effectiveFrom: string; note: string; createdBy: string | null; createdAt: string };
export type AiProviderKind = "OPENROUTER" | "OPENAI" | "ANTHROPIC" | "GEMINI" | "OPENAI_COMPATIBLE" | "LOCAL";
export type AiProviderModel = { id: string; name: string; enabled: boolean; paid: boolean; price: AiPrice | null; default: boolean };
export type AiProviderInfo = { id: string; name: string; configured: boolean; paid: boolean; endpointHost: string | null; defaultPolicy: "ENABLED_UNLESS_DISABLED" | "DISABLED_UNLESS_ENABLED";
  models: AiProviderModel[]; kind: AiProviderKind | ""; enabled: boolean;
  /** settings come from the operator: shown as "Được quản lý bởi hệ thống" and not editable on the web */
  managedBySystem: boolean; keySet: boolean; baseUrl: string | null; defaultModel: string | null; savedModels: string[] };
export type AiProviderForm = { name?: string; kind?: AiProviderKind; baseUrl?: string; /** write-only */ apiKey?: string; models?: string[]; defaultModel?: string; paid?: boolean; enabled?: boolean; removeKey?: boolean };
export type AiDiscover = { id: string; ok: boolean; models: string[]; detail: string };
export type AiLimitDefaults = { defaultModel: string; requestsPerUserDay: number; tokensPerUserDay: number; tokensPerWorkspaceMonth: number; paidBudgetPerUserMonth: number; paidBudgetPerWorkspaceMonth: number };
export type AiOverride = { id: string; scopeType: "USER" | "WORKSPACE" | "PROJECT"; scopeId: string; scopeLabel: string | null; requestsPerDay: number | null; tokensPerDay: number | null; tokensPerMonth: number | null; paidBudgetMonth: number | null };
export type AiLimitsView = { defaults: AiLimitDefaults; overrides: AiOverride[]; customized: boolean };
export type AiLimitLine = { value: number; source: "DEFAULT" | "WORKSPACE" | "USER" };
export type AiUserView = { workspaceId: string | null; workspaces: { id: string; name: string }[]; allowedModels: { id: string; name: string; provider: string; paid: boolean }[];
  effectiveDefaultModel: string; requestsToday: number; tokensToday: number; tokensThisMonth: number; paidSpentThisMonthUsd: number;
  limits: Record<"requestsPerDay" | "tokensPerDay" | "tokensPerMonthWorkspace" | "paidBudgetUserMonth" | "paidBudgetWorkspaceMonth", AiLimitLine>; override: AiOverride | null };
export type AiProbe = { id: string; ok: boolean; latencyMs: number; detail: string };
/** MIRROR of tenancy/TenantController.kt @ integration/v2 1a9995c (T2). A member row carries only ids: names are resolved by the caller (see features/admin/tenantModel.ts). */
export type TenantStatusName = "ACTIVE" | "SUSPENDED" | "DELETED";
export type TenantView = { id: string; slug: string; name: string; status: TenantStatusName | string; createdAt: string };
/** `username`/`displayName`/`email` come with C1's tenant member directory (fix/c1-portal-authz-blockers @ 7ecea1a); optional so an older backend still parses */
export type TenantMemberView = { tenantId: string; userId: string; role: TenantRoleName | string; active: boolean; username?: string | null; displayName?: string | null; email?: string | null };
/** `GET /admin/tenants/{id}/member-candidates?q=` — users already related to THIS tenant (workspace member, or a former member), enabled, activated, not SYSTEM_ADMIN; ≤ 50; `q` ≥ 2 characters */
export type TenantMemberCandidate = { userId: string; username: string; displayName: string | null; email: string | null };
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
  createdAt: string; workspaces: number; projects: number; lastLoginAt: string | null; pending?: boolean };
export type ActivationLink = { userId: string; username: string; displayName: string; purpose: "ACTIVATION" | "RESET"; token: string; expiresAt: string };
export type AdminMembership = { id: string; name: string; role: string; workspaceId: string | null; workspaceName: string | null; owner: boolean };
export type AdminUserDetail = { user: AdminUser; workspaces: AdminMembership[]; projects: AdminMembership[]; activeSessions: number; recentActivity: AuditRow[] };
export type AdminWorkspace = { id: string; name: string; slug: string; createdAt: string; members: number; projects: number; lastActivityAt: string | null };
export type AdminMember = { userId: string; username: string; displayName: string | null; role: string; enabled: boolean };
export type AdminApp = { id: string; name: string; workspaceId: string; workspaceName: string; ownerId: string; owner: string; members: number; visibility: string;
  revision: number; latestVersion: number | null; createdAt: string; updatedAt: string; active: boolean; publishStatus: string | null; publishedAt: string | null;
  lifecycle?: "ACTIVE" | "ARCHIVED"; appType?: string };
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
export type MyUsage = { aiConfigured: boolean; aiRequestsUsed: number; aiRequestsLimit: number; aiWindowResetsInSeconds: number | null; promptsPerMinute: number; promptsToday: number;
  tokensLast24h: number; tokensLimitPerDay: number | null; usageLast30Days: UsageTotals | null };
/** Provider-reported totals over ai_calls. callsWithoutUsage / costReportedCalls tell how complete the sums are. */
export type UsageTotals = { calls: number; failedCalls: number; callsWithoutUsage: number; promptTokens: number; completionTokens: number; totalTokens: number;
  costUsd: number | null; costReportedCalls: number; avgLatencyMs: number | null };
export type UsageBucket = { key: string; label: string | null; totals: UsageTotals };
export type AiUsageReport = { days: number; since: string; totals: UsageTotals; byModel: UsageBucket[]; byUser: UsageBucket[]; byWorkspace: UsageBucket[];
  daily: { day: string; calls: number; failedCalls: number; totalTokens: number; costUsd: number | null }[];
  limits: { dailyRequestsPerUser: number; dailyTokensPerUser: number | null; monthlyTokensPerWorkspace: number | null }; tokenSource: string; costSource: string };
export type AiCallRow = { id: string; createdAt: string; userId: string; user: string | null; workspaceId: string; workspace: string | null; projectId: string; project: string | null;
  promptId: string | null; provider: string; model: string; outcome: "OK" | "BAD_OUTPUT" | "ERROR"; httpStatus: number | null; promptTokens: number | null;
  completionTokens: number | null; totalTokens: number | null; costUsd: number | null; latencyMs: number; costSource?: "PROVIDER" | "CATALOG" | null; requestId?: string | null };

// ---- Phase 5: templates (page schema JSON, never source code) and contributed blocks (reviewed presets of approved components)
export type TemplateDto = { id: string; name: string; description: string; visibility: "PRIVATE" | "COMPANY"; status: "ACTIVE" | "ARCHIVED"; version: number;
  authorId: string; author: string | null; sourceProjectId: string | null; createdAt: string; updatedAt: string; sections: number; componentTypes: string[];
  schema: PageSchema; canEdit: boolean;
  category: string; tags: string[]; reviewStatus: "PRIVATE" | "SUBMITTED" | "REVIEW" | "APPROVED" | "ARCHIVED"; usageCount: number;
  previewStatus: "NONE" | "READY" | "FAILED" | "UNAVAILABLE"; submittedAt: string | null; reviewedBy: string | null; reviewedAt: string | null; reviewComment: string | null; canReview: boolean };
export type TemplateReview = { id: string; version: number; actor: string | null; decision: string; comment: string; checks: CheckResult[] | null; createdAt: string };
export type LibraryCategories = { templates: Record<string, string>; blocks: Record<string, string> };
export type CheckResult = { check: string; ok: boolean; message: string };
export type BlockVersion = { version: number; baseComponentVersion: string; props: Record<string, unknown>; status: "DRAFT" | "REVIEW" | "APPROVED" | "REJECTED" | "SUPERSEDED";
  validation: CheckResult[] | null; sourceProjectId: string | null; createdAt: string; submittedAt: string | null; decidedAt: string | null };
export type BlockReview = { id: string; version: number; actorId: string | null; actor: string | null; decision: string; comment: string; createdAt: string };
export type BlockDto = { id: string; name: string; description: string; baseComponent: string; ownerId: string; owner: string | null;
  status: "PRIVATE" | "SUBMITTED" | "VALIDATING" | "REVIEW" | "APPROVED" | "DEPRECATED"; latestVersion: number; approvedVersion: number | null;
  createdAt: string; updatedAt: string; current: BlockVersion | null; versions: BlockVersion[]; reviews: BlockReview[]; canEdit: boolean; canReview: boolean;
  category?: string; tags?: string[]; usageCount?: number; previewStatus?: "NONE" | "READY" | "FAILED" | "UNAVAILABLE" };

// ---- Phase 7.1: real static sites (ADR 0009)
/** The release operation that holds the app's scope right now (PUBLISH_API_CONTRACT.md §4). `null` on SiteInfo = idle (also when a lease expired). `deploymentId` is non-null only for PUBLISH. */
export type ReleaseOperationKind = "PUBLISH" | "ROLLBACK" | "UNPUBLISH";
export type SiteOperation = { kind: ReleaseOperationKind; deploymentId: string | null; since: string; leaseUntil: string };
export type SiteInfo = { slug: string | null; url: string | null; online: boolean; visibility: "PRIVATE" | "PUBLIC" | null; currentDeploymentId: string | null;
  currentVersionNumber: number | null; provider: string; updatedAt: string | null;
  /** +1 on every change of the active pointer. OBSERVABILITY ONLY: no request accepts it, the client never sends it and never builds optimistic concurrency on it. */
  pointerVersion: number;
  /** the key is always present; null = idle */
  operation: SiteOperation | null };

// ---- Phase 7.2–7.4: code projects
export type TreeFile = { path: string; size: number };
export type CodeFile = { path: string; ref: string; size: number; text: string | null; editable: boolean };
export type CodeCommit = { sha: string; message: string; author: string; committer: string; date: string; verified?: boolean; signer?: string | null };
export type BuildInfo = { id: string; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"; stage: string | null; error: string | null; log: string | null;
  scans: { dependencies?: { tool: string; packages: number; findings: { package: string; id: string; severity: string }[] }; sourceSecrets?: { findings: unknown[] };
    outputSecrets?: { findings: unknown[] }; sbom?: { name: string; version: string }[] } | null; queuedAt: string; startedAt: string | null; finishedAt: string | null };
export type CodeChange = { id: string; kind: "AI" | "EDIT"; status: "BUILDING" | "READY" | "FAILED" | "MERGED" | "DISCARDED"; summary: string; files: string[];
  branch: string; baseSha: string; headSha: string; promptId: string | null; error: string | null; createdBy: string | null; createdAt: string; updatedAt: string;
  previewUrl: string | null; previewExpiresAt: string | null; build: BuildInfo | null;
  reviewRequired?: boolean; approvedBy?: string | null; approvedAt?: string | null; reviewComment?: string | null };
export type DiffFile = { path: string; before: string | null; after: string | null };
export type CodeAiResponse = { promptId: string; outcome: "UPDATED" | "NO_CHANGE" | "UNSUPPORTED" | "CANCELLED" | "TIMEOUT"; message: string; change: CodeChange | null; provider: string; model: string | null;
  usage: PromptUsage | null; stopped?: "CANCELLED" | "TIMEOUT" | null; partial?: string | null };
export type CodeAiHistoryItem = { promptId: string; text: string; createdAt: string; outcome: string; message: string; model: string | null; changeId: string | null; changeStatus: string | null };

// ---- Lockdown, settings, build policy, retention
export type SettingView = { key: string; group: string; type: "BOOL" | "INT" | "DECIMAL" | "DOMAINS"; label: string; risk: "LOW" | "HIGH"; min: number; max: number; unit: string;
  value: string; defaultValue: string; overridden: boolean; updatedBy: string | null; updatedAt: string | null };
export type BuildUsageRow = { key: string; label: string | null; builds: number; succeeded: number; failed: number; cpuMs: number; durationMs: number; artifactBytes: number };
export type BuildPolicyReport = { days: number; totals: BuildUsageRow; byWorkspace: BuildUsageRow[]; byUser: BuildUsageRow[]; byProject: BuildUsageRow[]; running: number; queued: number;
  rejections: { createdAt: string; user: string | null; project: string | null; reason: string; detail: string }[]; storage: Record<string, number> };
export type RetentionResult = { previewsExpired: number; artifactsDeleted: number; artifactBytesFreed: number; failedBuildLogsCleared: number; repositoriesPendingDelete: number };
export type CleanupResult = { dryRun: boolean; abandonedUploads: number; deletedAssetRows: number; idempotencyKeys: number; failedDeployments: number; aiCalls: number; retention: RetentionResult | null };
export type RepoRow = { projectId: string; project: string | null; name: string; state: "ACTIVE" | "ARCHIVED" | "PENDING_DELETE" | "DELETED"; sizeBytes: number | null; archivedAt: string | null; deleteAfter: string | null };

// ---- source-app completion
export type DesignProp = { name: string; kind: "string" | "number" | "boolean" | "expression" | "absent"; value: string | null; allowed: string[] | null };
export type DesignNode = { id: string; tag: string; line: number; depth: number; company: boolean; text: string | null; textEditable: boolean; hidden: boolean;
  hiddenEditable: boolean; props: DesignProp[]; codeOnly: string | null };
export type DependencyRequest = { id: string; packageName: string; spec: string; status: "LOCKING" | "COMMITTED" | "REJECTED" | "FAILED"; changeId: string | null; error: string | null; createdAt: string };
export type PackageView = { name: string; status: "PENDING" | "RESOLVING" | "ALLOWED" | "DENIED"; versionRange: string; pinnedVersion: string | null; note: string; riskAccepted: boolean;
  dependencies: number | null; findings: { package: string; id: string; severity: string }[] | null; requestedBy: string | null; decidedBy: string | null; decidedAt: string | null; updatedAt: string };
export type CloneAccess = { cloneUrl: string; username: string; token: string | null; note: string };

// ---- Stage E: AI governance
export type AccessRule = { id: string; scopeType: "ORG" | "WORKSPACE" | "ROLE" | "USER"; scopeId: string; scopeLabel: string | null; modelId: string; createdAt: string };
export type EffectiveModel = { id: string; provider: string; paid: boolean; allowed: boolean; reason: string };
export type AiBudget = { id: string; scopeType: "ORG" | "WORKSPACE" | "USER" | "PROJECT"; scopeId: string; scopeLabel: string | null; period: "DAILY" | "MONTHLY"; amount: number; currency: string;
  usdPerUnit: number; softPercent: number; hard: boolean; spent: number; spentUsd: number; unknownCostCalls: number; percent: number; periodStart: string };
export type AdminAlert = { id: string; kind: string; severity: "INFO" | "WARNING" | "CRITICAL"; scopeType: string | null; scopeId: string | null; message: string; data: Record<string, unknown>;
  createdAt: string; acknowledgedBy: string | null; acknowledgedAt: string | null };

// ---- Stage H: organisation, costs, security findings
export type Department = { id: string; name: string; kind: "DEPARTMENT" | "TEAM"; parentId: string | null; users: number; workspaces: number; createdAt: string };
export type CostPrice = { id: string; item: string; unitPrice: number; currency: string; usdPerUnit: number; note: string; effectiveFrom: string; createdBy: string | null };
export type CostLine = { key: string; label: string | null; storageBytes: number; buildCpuMs: number; buildMs: number; aiUsd: number; aiUnknownCalls: number;
  storageUsd: number | null; cpuUsd: number | null; buildUsd: number | null; totalKnownUsd: number; complete: boolean };
export type CostReport = { days: number; prices: CostPrice[]; missingPrices: string[]; egress: string; total: CostLine; byDepartment: CostLine[]; byWorkspace: CostLine[]; byApplication: CostLine[] };
export type SecurityFinding = { severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO"; source: string; title: string; detail: string; resourceType: string | null; resourceId: string | null;
  resourceName: string | null; detectedAt: string | null };
export type SecurityReport = { counts: Record<string, number>; findings: SecurityFinding[]; note: string };

// ---- Stage J/K: app kinds, server runtime, connectors
export type AppKind = "WEBSITE_STATIC" | "SOURCE_WEB_APP" | "DASHBOARD" | "INTERNAL_TOOL" | "WORKFLOW" | "SERVER_APP";
export const SERVER_KINDS: AppKind[] = ["SERVER_APP", "INTERNAL_TOOL", "WORKFLOW"];
export type ServerDeployment = { id: string; version: number; status: "PENDING" | "STARTING" | "RUNNING" | "FAILED" | "SUPERSEDED" | "STOPPED"; error: string | null;
  commitSha: string | null; routes: number; createdAt: string; startedAt: string | null; requestedBy: string | null; rollbackOf: string | null; current: boolean };
export type RuntimeStatus = { available: boolean; provisioned: boolean; database: string | null; currentDeploymentId: string | null; desiredDeploymentId: string | null;
  deployments: ServerDeployment[]; secrets: { name: string; updatedAt: string; updatedBy: string | null }[]; connectors: string[]; logs: string | null; logsAt: string | null; notice: string };
export type Connector = { key: string; name: string; description: string; baseUrl: string; authHeader: string | null; hasSecret: boolean;
  operations: { method: string; path: string }[]; status: "APPROVED" | "DISABLED"; grants: number; createdAt: string };

// ---- Stage L: backups
export type BackupComponent = { name: string; state: string; lastSuccess: string | null; lastRun: string | null; ageHours: number | null; sizeBytes: number | null; error: string | null; stale: boolean };
export type BackupEnvironment = { environment: string; components: BackupComponent[]; drillAt: string | null; drillPassed: boolean | null;
  drill: { component: string; result: string; detail: string }[]; healthy: boolean; problems: string[] };

// ---- publish configuration (C2, docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md; backend PublishConfigApi.kt). The AUTHORITATIVE policy of a project: the browser never decides it.
export type PublishPolicyMode = "STATIC" | "DYNAMIC" | "SERVER_APP";
export type PublishPolicyVisibility = "PRIVATE" | "TENANT" | "PUBLIC" | "PRIVATE_LINK";
/** `publicDataApproved` is the persisted approval that POST /publish enforces (422 PUBLIC_DATA_NOT_APPROVED); it only becomes true through PUT publish-config with `acknowledgePublicData: true`. */
export type PublishConfigPolicy = { mode: PublishPolicyMode; visibility: PublishPolicyVisibility; requiresAuth: boolean; cacheSeconds: number | null; publicDataApproved: boolean; linkTokenSet: boolean; revision: number; updatedAt: string | null };
/** `config` null = no stored policy (the app publishes as it always did); `draft` = the intention stored in the document (informational, never authority) */
export type PublishConfigView = { config: PublishConfigPolicy | null; draft: { mode?: PublishPolicyMode; visibility?: PublishPolicyVisibility; requiresAuth?: boolean; cacheSeconds?: number } | null; linkToken?: string | null };
/** `requiresAuth` is REQUIRED by the server's request class (omitting it is 400 MALFORMED_REQUEST) */
export type SetPublishConfigBody = { mode: PublishPolicyMode; visibility: PublishPolicyVisibility; requiresAuth: boolean; cacheSeconds?: number | null; acknowledgePublicData?: boolean; expectedRevision?: number | null };

// ---- Dynamic Organization (mirror of backend/.../organization/OrganizationContract.kt; docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md). Ids are UUID strings, instants ISO-8601 strings.
export type OrgUnitTypeRules = { allowedParentTypeIds: string[] | null; allowedChildTypeIds: string[] | null; allowRoot: boolean | null; maxDepth: number | null };
export type OrgUnitTypeDto = { id: string; tenantId: string; name: string; code: string; icon: string | null; active: boolean; rules: OrgUnitTypeRules; version: number; createdAt: string; updatedAt: string };
export type OrgUnitDto = { id: string; tenantId: string; typeId: string; parentId: string | null; name: string; code: string; sortOrder: number; metadata: unknown; active: boolean; version: number; createdAt: string; updatedAt: string; archivedAt: string | null };
/** `directMemberCount` = ACTIVE memberships on exactly this unit; `subtreeEmployeeCount` = DISTINCT active employees over the unit and its non-archived descendants. null = the store gives no counts. */
export type OrgUnitNodeDto = { unit: OrgUnitDto; children: OrgUnitNodeDto[]; directMemberCount: number | null; subtreeEmployeeCount: number | null };
export type OrgUnitDetailDto = { unit: OrgUnitDto; path: OrgUnitDto[]; activeChildCount: number; activeMemberCount: number; directMemberCount: number | null; subtreeEmployeeCount: number | null };
export type OrgPositionDto = { id: string; tenantId: string; name: string; code: string; description: string | null; active: boolean; version: number; createdAt: string; updatedAt: string };
export type OrgGradeDto = { id: string; tenantId: string; name: string; code: string; rank: number | null; description: string | null; active: boolean; version: number; createdAt: string; updatedAt: string };
/** A relation (MEMBER / MANAGER / HEAD ...) is business data: it never authorizes anything. */
export type OrgMembershipDto = { id: string; tenantId: string; userId: string; organizationUnitId: string; relationType: string; primary: boolean; active: boolean; version: number; createdAt: string; updatedAt: string };
export type OrgEmployeePositionDto = { id: string; tenantId: string; userId: string; membershipId: string; organizationUnitId: string; positionId: string; gradeId: string | null; primary: boolean; active: boolean; version: number; createdAt: string; updatedAt: string };
export type OrgEmployeeDto = { userId: string; tenantId: string; username: string; displayName: string | null; email: string | null; active: boolean; accountEnabled: boolean; accountActivated: boolean; tenantRole: string;
  primaryOrganizationUnitId: string | null; positions: OrgEmployeePositionDto[]; organizationMemberships: OrgMembershipDto[] };
export type OrgEmployeePageDto = { items: OrgEmployeeDto[]; total: number; page: number; size: number };
export type OrgEmployeeCreatedDto = { employee: OrgEmployeeDto; activation: ActivationLink | null };
export type OrgUnitTypeCreateBody = { name: string; code: string; icon?: string | null; rules?: Partial<OrgUnitTypeRules> };
export type OrgUnitTypeUpdateBody = { name?: string; icon?: string | null; rules?: Partial<OrgUnitTypeRules>; expectedVersion: number };
export type OrgUnitCreateBody = { typeId: string; parentId: string | null; name: string; code: string; sortOrder?: number };
export type OrgUnitUpdateBody = { name?: string; code?: string; sortOrder?: number; expectedVersion: number };
export type OrgUnitMoveBody = { newParentId: string | null; expectedVersion: number; sortOrder?: number };
export type OrgPositionCreateBody = { name: string; code: string; description?: string | null };
export type OrgPositionUpdateBody = { name?: string; description?: string | null; expectedVersion: number };
export type OrgGradeCreateBody = { name: string; code: string; rank?: number | null; description?: string | null };
export type OrgGradeUpdateBody = { name?: string; rank?: number | null; description?: string | null; clearRank?: boolean; expectedVersion: number };
export type OrgEmployeeCreateBody = { username: string; displayName?: string | null; email?: string | null; tenantRole?: string; workspaceId?: string | null; workspaceRole?: string | null;
  organizationMemberships?: { organizationUnitId: string; relationType?: string; primary?: boolean; positions?: { positionId: string; gradeId?: string | null; primary?: boolean }[] }[] };
export type OrgMembershipCreateBody = { organizationUnitId: string; relationType?: string; primary?: boolean };
export type OrgMembershipUpdateBody = { relationType?: string; primary?: boolean; expectedVersion: number };
export type OrgEmployeePositionCreateBody = { membershipId: string; positionId: string; gradeId?: string | null; primary?: boolean };
export type OrgEmployeePositionUpdateBody = { gradeId?: string | null; clearGrade?: boolean; primary?: boolean; expectedVersion: number };
export type OrgEmployeeQuery = { q?: string; organizationUnitId?: string; includeDescendants?: boolean; positionId?: string; gradeId?: string; active?: boolean; page?: number; size?: number; sort?: "name" | "username"; dir?: "asc" | "desc" };

/** Canonical v2 contract mirror (AppDefinition, data, actions, workflows, permissions). See ./contract/v2/meta.ts for the source and version. */
export * from "./contract/v2";
