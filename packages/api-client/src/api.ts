import type {
  AdminAi, AiCallRow, SettingView, BuildPolicyReport, CleanupResult, RepoRow, AiPrice, AiProbe, AiProviderInfo, AiProviderForm, AiDiscover, AiLimitsView, AiLimitDefaults, AiUserView, AiUsageReport, AdminApp, BlockDto, CheckResult, TemplateDto, AdminAppDetail, AdminComponent, AdminOverview, AdminUser, TenantView, TenantMemberView, TenantMemberCandidate, ActivationLink, AdminUserDetail, AdminWorkspace, AdminWorkspaceDetail, AuditRow, MyUsage, Page, PlatformHealth,
  BackupEnvironment, AppKind, RuntimeStatus, Connector, Department, CostPrice, CostReport, SecurityReport, FormSubmission, SiteDomain, TemplateReview, LibraryCategories, AccessRule, EffectiveModel, AiBudget, AdminAlert, StreamHandlers,
  PublishConfigView, SetPublishConfigBody, AiStatus, ApiProject, AuthConfig, Member, SiteInfo, DesignNode, DependencyRequest, PackageView, CloneAccess, TreeFile, CodeFile, CodeCommit, CodeChange, DiffFile, CodeAiResponse, CodeAiHistoryItem, RegistryComponent, ComponentMetadataV2, DefinitionOperation, AssetDto, Deployment, Me, RunQueryRequest, RunQueryResponse, ExecuteActionRequest, ActionEnvelope, StartWorkflowRequest, WorkflowRunView, PromptHistoryItem, PromptResponse, SchemaOperation, SchemaResponse, UploadUrl, VersionSummary
} from "@xweb/types";
import type {
  ConnectorList, DataSourceView, DataSourceList, CreateDataSourceRequest, UpdateDataSourceRequest, CredentialMetadata, SetCredentialRequest,
  ConnectionTestResult, DataBinding, DataBindingList, BindingMode,
} from "@xweb/types";
import { ApiError, call, json, qs, sessionChanged, stream } from "./core";
import { isValidReleaseKey, publishBody, publishConfigBody, rollbackBody, unpublishQuery } from "./release";
import { orgApi } from "./org";

const P = (w: string, p: string) => `/workspaces/${w}/projects/${p}`;
const RT = (w: string, p: string) => `${P(w, p)}/app-runtime`;
const seg = encodeURIComponent;

/** a client-side refusal before anything is sent: same shape as a server 400, so the UI treats it as "nothing was written" */
function badKey(): never { throw new ApiError(400, "IDEMPOTENCY_KEY_INVALID", "Khóa chống ghi trùng không hợp lệ (1–128 ký tự A–Z a–z 0–9 . _ : -)."); }
/** same pattern as IDEMPOTENCY_KEY_PATTERN in the contract mirror (kept local: this module must load under plain node in the unit tests) */
const KEY = /^[A-Za-z0-9._:-]{1,128}$/;
const checkKey = (k: string | undefined) => { if (k !== undefined && !KEY.test(k)) badKey(); };
/** publish / rollback / unpublish keys follow the C2 contract (8–120 chars): refused before anything is sent */
function badReleaseKey(): never { throw new ApiError(400, "INVALID_IDEMPOTENCY_KEY", "Khóa chống ghi trùng không hợp lệ (8–120 ký tự A–Z a–z 0–9 . _ : -)."); }
const checkReleaseKey = (k: string | undefined, required: boolean) => { if (k === undefined ? required : !isValidReleaseKey(k)) badReleaseKey(); };


/** same patterns as DATA_SOURCE_NAME_PATTERN / SLOT_ID_PATTERN in the management mirror (local copies: this module must load under plain node in the unit tests) */
const DS_NAME = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$/;
const SLOT = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const WS = (w: string) => `/workspaces/${seg(w)}/data-sources`;
/** a refusal before anything is sent: same shape as the server's 400 (nothing was written) */
function invalid(message: string): never { throw new ApiError(400, "INVALID_PARAMS", message); }
const bindingMode = (m: string): BindingMode => { const u = m.toUpperCase(); if (u !== "LIVE" && u !== "TEST") invalid("Chế độ liên kết phải là TEST hoặc LIVE."); return u as BindingMode; };
/** the secret-bearing request body must never be echoed anywhere: errors thrown here carry fixed text only */
const MGMT_TEST_TIMEOUT_MS = 30_000;

/** A fresh idempotency key. One per user intent: reuse it only to retry the SAME intent, never to repeat it. */
export function newIdempotencyKey(prefix = "ui"): string {
  const id = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}:${id}`.slice(0, 128);
}

export const api = {
  async login(username: string, password: string): Promise<Me> {
    await call("/auth/login", { method: "POST", body: json({ username, password }) });
    sessionChanged();
    return call<Me>("/auth/me");
  },
  me: () => call<Me>("/auth/me"),
  authConfig: () => call<AuthConfig>("/auth/config"),
  register: (username: string, password: string, displayName: string, inviteCode?: string) =>
    call<{ username: string }>("/auth/register", { method: "POST", body: json({ username, password, displayName: displayName || undefined, inviteCode: inviteCode || undefined }) }),
  inspectActivation: (token: string) => call<{ username: string; displayName: string; purpose: "ACTIVATION" | "RESET" }>("/auth/activation/inspect", { method: "POST", body: json({ token }) }),
  completeActivation: (token: string, password: string) => call<{ status: string }>("/auth/activation/complete", { method: "POST", body: json({ token, password }) }),
  aiStatus: (workspaceId?: string) => call<AiStatus>(`/ai/status${qs({ workspaceId })}`),
  cancelStream: (id: string) => call<{ cancelled: boolean }>(`/ai/streams/${id}/cancel`, { method: "POST" }),
  components: () => call<RegistryComponent[]>("/components?details=true"),
  /** ComponentMetadataV2 (bindable props, events, supported actions). Exists in the v2 contract (C2); a server without it answers 404, which the Builder shows as "Chưa sẵn sàng". */
  componentMetadata: () => call<ComponentMetadataV2[]>("/component-metadata"),
  /** returns the identity provider's end-session URL when the user signed in with SSO (RP-initiated logout) */
  async logout(): Promise<string | null> {
    const r = await call<{ status: string; redirect?: string }>("/auth/logout", { method: "POST" }).finally(sessionChanged);
    return r?.redirect && /^https?:\/\//.test(r.redirect) ? r.redirect : null;
  },
  adminScim: () => call<{ enabled: boolean; users: number; groups: { id: string; displayName: string; members: number }[];
    mappings: { id: string; group: string; workspace: string; role: string; groupId: string; workspaceId: string }[] }>("/admin/scim"),
  addScimMapping: (groupId: string, workspaceId: string, role: string) => call<unknown>("/admin/scim/mappings", { method: "POST", body: json({ groupId, workspaceId, role }) }),
  deleteScimMapping: (id: string) => call<unknown>(`/admin/scim/mappings/${id}`, { method: "DELETE" }),

  listProjects: (w: string) => call<ApiProject[]>(`/workspaces/${w}/projects`),
  async projectsPage(w: string, page: number, size: number, q?: string, scope?: "all" | "owned" | "shared"): Promise<Page<ApiProject>> {
    let total = 0;
    const items = await call<ApiProject[]>(`/workspaces/${w}/projects${qs({ page, size, q, scope })}`, { onTotal: (n) => { total = n; } });
    return { items, total, page, size };
  },
  archiveProject: (w: string, p: string) => call<unknown>(`${P(w, p)}/archive`, { method: "POST" }),
  restoreProject: (w: string, p: string) => call<unknown>(`${P(w, p)}/restore`, { method: "POST" }),
  deleteProject: (w: string, p: string, expectedRevision: number) => call<void>(`${P(w, p)}${qs({ expectedRevision })}`, { method: "DELETE" }),
  myUsage: () => call<MyUsage>("/me/usage"),
  myActivity: (limit = 30) => call<AuditRow[]>(`/me/activity${qs({ limit })}`),
  /** Dynamic Organization (types, units, positions, grades, employees, memberships): every path lives in ./org.ts */
  org: orgApi,
  admin: {
    overview: () => call<AdminOverview>("/admin/overview"),
    users: (page: number, q?: string, status?: string) => call<Page<AdminUser>>(`/admin/users${qs({ page, size: 25, q, status })}`),
    user: (id: string) => call<AdminUserDetail>(`/admin/users/${id}`),
    setUserStatus: (id: string, enabled: boolean) => call<AdminUser>(`/admin/users/${id}/status`, { method: "PATCH", body: json({ enabled }) }),
    revokeSessions: (id: string) => call<{ revoked: number }>(`/admin/users/${id}/revoke-sessions`, { method: "POST" }),
    activationLink: (id: string) => call<ActivationLink>(`/admin/users/${id}/activation-link`, { method: "POST" }),
    setSystemAdmin: (id: string, grant: boolean) => call<{ systemAdmin: boolean }>(`/admin/users/${id}/system-admin`, { method: "POST", body: json({ grant, confirm: true }) }),
    tenants: () => call<TenantView[]>("/admin/tenants"),
    tenant: (id: string) => call<TenantView>(`/admin/tenants/${id}`),
    createTenant: (b: { slug: string; name: string; firstAdminUserId?: string }) => call<TenantView>("/admin/tenants", { method: "POST", body: json(b) }),
    /** C1 final contract §tenant admin: PATCH /admin/tenants/{t} {name} - TENANT_MANAGE of THAT tenant; the slug is immutable; a SUSPENDED company refuses a Tenant Admin (403 TENANT_SUSPENDED) */
    renameTenant: (id: string, name: string) => call<TenantView>(`/admin/tenants/${id}`, { method: "PATCH", body: json({ name }) }),
    setTenantStatus: (id: string, status: "ACTIVE" | "SUSPENDED" | "DELETED") => call<TenantView>(`/admin/tenants/${id}/status`, { method: "PATCH", body: json({ status }) }),
    tenantMembers: (id: string) => call<TenantMemberView[]>(`/admin/tenants/${id}/members`),
    /** C1 `tenant-provisioning-contract.md` @ 2356d64: a brand-new account in THIS tenant, by invitation (one-time activation link, no password). `workspaceId` and `workspaceRole` come together or not at all. */
    createTenantUser: (tenantId: string, b: { username: string; displayName: string; email?: string; tenantRole?: "MEMBER" | "TENANT_ADMIN"; workspaceId?: string; workspaceRole?: string }) => call<ActivationLink>(`/admin/tenants/${tenantId}/users`, { method: "POST", body: json(b) }),
    /** a workspace OF the tenant. The legacy collection POST (no tenant) puts it in the DEFAULT tenant: the portals have no client method for it. */
    createTenantWorkspace: (tenantId: string, name: string) => call<{ id: string; name: string; slug: string; tenantId: string }>(`/admin/tenants/${tenantId}/workspaces`, { method: "POST", body: json({ name }) }),
    tenantMemberCandidates: (id: string, q?: string) => call<TenantMemberCandidate[]>(`/admin/tenants/${id}/member-candidates${qs({ q })}`),
    setTenantMember: (id: string, userId: string, role: string) => call<TenantMemberView>(`/admin/tenants/${id}/members/${userId}`, { method: "PUT", body: json({ role }) }),
    removeTenantMember: (id: string, userId: string) => call<void>(`/admin/tenants/${id}/members/${userId}`, { method: "DELETE" }),
    workspaces: (page: number, q?: string) => call<Page<AdminWorkspace>>(`/admin/workspaces${qs({ page, size: 25, q })}`),
    workspace: (id: string) => call<AdminWorkspaceDetail>(`/admin/workspaces/${id}`),
    applications: (params: { page: number; q?: string; visibility?: string; status?: string; workspaceId?: string }) => call<Page<AdminApp>>(`/admin/applications${qs({ size: 25, ...params })}`),
    application: (id: string) => call<AdminAppDetail>(`/admin/applications/${id}`),
    transferOwnership: (id: string, userId: string) => call<AdminApp>(`/admin/applications/${id}/transfer-ownership`, { method: "POST", body: json({ userId }) }),
    audit: (params: Record<string, string | number | undefined>) => call<Page<AuditRow>>(`/admin/audit${qs({ size: 50, ...params })}`),
    auditActions: () => call<string[]>("/admin/audit/actions"),
    ai: () => call<AdminAi>("/admin/ai"),
    aiProviders: () => call<AiProviderInfo[]>("/admin/ai/providers"),
    addAiProvider: (b: AiProviderForm) => call<AiProviderInfo>("/admin/ai/providers", { method: "POST", body: json(b) }),
    updateAiProvider: (id: string, b: AiProviderForm) => call<AiProviderInfo>(`/admin/ai/providers/${id}`, { method: "PUT", body: json(b) }),
    deleteAiProvider: (id: string) => call<void>(`/admin/ai/providers/${id}`, { method: "DELETE" }),
    discoverAiModels: (id: string) => call<AiDiscover>(`/admin/ai/providers/${id}/discover`, { method: "POST" }),
    aiLimits: () => call<AiLimitsView>("/admin/ai/limits"),
    setAiDefaults: (b: Partial<AiLimitDefaults>) => call<AiLimitsView>("/admin/ai/limits/defaults", { method: "PUT", body: json(b) }),
    setAiOverride: (b: { scopeType: string; scopeId: string; requestsPerDay?: number | null; tokensPerDay?: number | null; tokensPerMonth?: number | null; paidBudgetMonth?: number | null }) =>
      call<AiLimitsView>("/admin/ai/limits/overrides", { method: "PUT", body: json(b) }),
    deleteAiOverride: (id: string) => call<AiLimitsView>(`/admin/ai/limits/overrides/${id}`, { method: "DELETE" }),
    aiUserView: (userId: string, workspaceId?: string) => call<AiUserView>(`/admin/ai/limits/users/${userId}${qs({ workspaceId })}`),
    aiProbe: (id: string) => call<AiProbe>(`/admin/ai/providers/${id}/probe`, { method: "POST" }),
    aiModelPolicy: (modelId: string, enabled: boolean) => call<unknown>("/admin/ai/models/policy", { method: "PUT", body: json({ modelId, enabled }) }),
    aiPricing: () => call<AiPrice[]>("/admin/ai/pricing"),
    aiAddPrice: (body: { modelId: string; inputUsdPerMTok: number; outputUsdPerMTok: number; note?: string }) => call<AiPrice>("/admin/ai/pricing", { method: "POST", body: json(body) }),
    aiUsage: (days: number) => call<AiUsageReport>(`/admin/ai/usage${qs({ days })}`),
    aiCalls: (params: { page: number; outcome?: string; model?: string; userId?: string; workspaceId?: string }) => call<Page<AiCallRow>>(`/admin/ai/calls${qs({ size: 25, ...params })}`),
    components: () => call<AdminComponent[]>("/admin/components"),
    blocks: (status: string, page: number) => call<{ page: Page<BlockDto>; counts: Record<string, number> }>(`/admin/component-packages${qs({ status, page, size: 25 })}`),
    block: (id: string) => call<BlockDto>(`/admin/component-packages/${id}`),
    reviewBlock: (id: string, decision: "APPROVE" | "REJECT", version: number, comment?: string) =>
      call<BlockDto>(`/admin/component-packages/${id}/review`, { method: "POST", body: json({ decision, version, comment }) }),
    deprecateBlock: (id: string, comment?: string) => call<BlockDto>(`/admin/component-packages/${id}/deprecate`, { method: "POST", body: json({ comment }) }),
    restoreBlock: (id: string) => call<BlockDto>(`/admin/component-packages/${id}/restore`, { method: "POST" }),
    templates: (params: { page: number; visibility?: string; status?: string; q?: string }) => call<Page<TemplateDto>>(`/admin/templates${qs({ size: 25, ...params })}`),
    templateVisibility: (id: string, visibility: "PRIVATE" | "COMPANY") => call<TemplateDto>(`/admin/templates/${id}/visibility`, { method: "POST", body: json({ visibility }) }),
    reviewTemplate: (id: string, decision: "APPROVE" | "REJECT", comment?: string) => call<TemplateDto>(`/admin/templates/${id}/review`, { method: "POST", body: json({ decision, comment }) }),
    templatePreview: (id: string) => call<{ previewStatus: string }>(`/admin/templates/${id}/preview`, { method: "POST" }),
    blockPreview: (id: string) => call<{ previewStatus: string }>(`/admin/component-packages/${id}/preview`, { method: "POST" }),
    templateStatus: (id: string, status: "ACTIVE" | "ARCHIVED") => call<TemplateDto>(`/admin/templates/${id}/status`, { method: "POST", body: json({ status }) }),
    health: () => call<PlatformHealth>("/admin/system/health"),
    settings: () => call<Record<string, Record<string, unknown>>>("/admin/settings"),
    policies: () => call<SettingView[]>("/admin/settings/policies"),
    setPolicy: (key: string, value: string, confirm?: boolean) => call<SettingView>(`/admin/settings/policies/${key}`, { method: "PUT", body: json({ value, confirm }) }),
    resetPolicy: (key: string) => call<SettingView>(`/admin/settings/policies/${key}`, { method: "DELETE" }),
    builds: () => call<BuildPolicyReport>("/admin/builds"),
    retentionPreview: () => call<CleanupResult>("/admin/retention/preview"),
    retentionRun: () => call<CleanupResult>("/admin/retention/run", { method: "POST" }),
    repositories: () => call<RepoRow[]>("/admin/retention/repositories"),
    packages: () => call<PackageView[]>("/admin/packages"),
    approvePackage: (body: { name: string; versionRange?: string; pinnedVersion?: string; note?: string }) => call<PackageView>("/admin/packages", { method: "POST", body: json(body) }),
    decidePackage: (name: string, status: "ALLOWED" | "DENIED", acceptRisk?: boolean, note?: string) =>
      call<PackageView>(`/admin/packages/${encodeURIComponent(name)}/decision`, { method: "PUT", body: json({ status, acceptRisk, note }) }),
    backups: () => call<BackupEnvironment[]>("/admin/backups"),
    connectors: () => call<Connector[]>("/admin/connectors"),
    saveConnector: (body: { key: string; name: string; description?: string; baseUrl: string; authHeader?: string; authValue?: string; operations: { method: string; path: string }[] }) =>
      call<Connector[]>("/admin/connectors", { method: "PUT", body: json(body) }),
    connectorStatus: (key: string, status: "APPROVED" | "DISABLED") => call<Connector[]>(`/admin/connectors/${key}/status${qs({ status })}`, { method: "POST" }),
    grantConnector: (key: string, projectId: string) => call<Connector[]>(`/admin/connectors/${key}/grants`, { method: "POST", body: json({ projectId }) }),
    departments: () => call<Department[]>("/admin/departments"),
    createDepartment: (body: { name: string; kind: "DEPARTMENT" | "TEAM"; parentId?: string }) => call<Department[]>("/admin/departments", { method: "POST", body: json(body) }),
    renameDepartment: (id: string, body: { name: string; parentId?: string | null }) => call<Department[]>(`/admin/departments/${id}`, { method: "PATCH", body: json(body) }),
    deleteDepartment: (id: string) => call<Department[]>(`/admin/departments/${id}`, { method: "DELETE" }),
    assignUserDepartment: (userId: string, departmentId: string | null) => call<unknown>(`/admin/departments/assign/users/${userId}`, { method: "PUT", body: json({ departmentId }) }),
    assignWorkspaceDepartment: (workspaceId: string, departmentId: string | null) => call<unknown>(`/admin/departments/assign/workspaces/${workspaceId}`, { method: "PUT", body: json({ departmentId }) }),
    costs: (days: number) => call<CostReport>(`/admin/costs${qs({ days })}`),
    addCostPrice: (body: { item: string; unitPrice: number; currency: string; usdPerUnit?: number; note?: string }) => call<CostPrice[]>("/admin/costs/prices", { method: "POST", body: json(body) }),
    securityFindings: () => call<SecurityReport>("/admin/security/findings"),
    archiveApp: (id: string) => call<unknown>(`/admin/applications/${id}/archive`, { method: "POST" }),
    restoreApp: (id: string) => call<unknown>(`/admin/applications/${id}/restore`, { method: "POST" }),
    accessRules: () => call<AccessRule[]>("/admin/ai/access"),
    addAccessRule: (body: { scopeType: string; scopeId?: string; modelId: string }) => call<{ id: string }>("/admin/ai/access", { method: "POST", body: json(body) }),
    deleteAccessRule: (id: string) => call<void>(`/admin/ai/access/${id}`, { method: "DELETE" }),
    effectiveModels: (userId: string, workspaceId?: string) => call<EffectiveModel[]>(`/admin/ai/access/effective${qs({ userId, workspaceId })}`),
    budgets: () => call<AiBudget[]>("/admin/ai/budgets"),
    setBudget: (body: { scopeType: string; scopeId?: string; period: string; amount: number; currency: string; usdPerUnit?: number; softPercent: number; hard: boolean }) =>
      call<AiBudget[]>("/admin/ai/budgets", { method: "PUT", body: json(body) }),
    deleteBudget: (id: string) => call<void>(`/admin/ai/budgets/${id}`, { method: "DELETE" }),
    alerts: (all = false) => call<{ open: number; items: AdminAlert[] }>(`/admin/alerts${qs({ all: all ? "true" : undefined })}`),
    ackAlert: (id: string) => call<{ ok: boolean }>(`/admin/alerts/${id}/acknowledge`, { method: "POST" }),
    deleteRepository: (projectId: string) => call<{ state: string }>(`/admin/retention/repositories/${projectId}/delete`, { method: "POST" })
  },
  createProject: (w: string, name: string, description?: string, templateId?: string, appType?: "PAGE_SCHEMA" | "STATIC_APP", appKind?: AppKind) =>
    call<ApiProject>(`/workspaces/${w}/projects`, { method: "POST", body: json({ name, description, ...(templateId ? { templateId } : {}), ...(appType ? { appType } : {}), ...(appKind ? { appKind } : {}) }) }),
  runtime: (w: string, p: string) => call<RuntimeStatus>(`${P(w, p)}/runtime`),
  runtimeRollback: (w: string, p: string, deploymentId: string) => call<RuntimeStatus>(`${P(w, p)}/runtime/rollback`, { method: "POST", body: json({ deploymentId }) }),
  runtimeStop: (w: string, p: string) => call<RuntimeStatus>(`${P(w, p)}/runtime/stop`, { method: "POST" }),
  setSecret: (w: string, p: string, name: string, value: string) => call<RuntimeStatus>(`${P(w, p)}/runtime/secrets`, { method: "PUT", body: json({ name, value }) }),
  deleteSecret: (w: string, p: string, name: string) => call<RuntimeStatus>(`${P(w, p)}/runtime/secrets/${encodeURIComponent(name)}`, { method: "DELETE" }),
  code: {
    tree: (w: string, p: string, ref = "main") => call<TreeFile[]>(`${P(w, p)}/code/tree${qs({ ref })}`),
    file: (w: string, p: string, path: string, ref = "main") => call<CodeFile>(`${P(w, p)}/code/file${qs({ path, ref })}`),
    commits: (w: string, p: string) => call<CodeCommit[]>(`${P(w, p)}/code/commits`),
    changes: (w: string, p: string) => call<CodeChange[]>(`${P(w, p)}/code/changes`),
    change: (w: string, p: string, id: string) => call<CodeChange>(`${P(w, p)}/code/changes/${id}`),
    propose: (w: string, p: string, summary: string, files: { path: string; content?: string; delete?: boolean }[]) =>
      call<CodeChange>(`${P(w, p)}/code/changes`, { method: "POST", body: json({ summary, files }) }),
    diff: (w: string, p: string, id: string) => call<DiffFile[]>(`${P(w, p)}/code/changes/${id}/diff`),
    merge: (w: string, p: string, id: string) => call<CodeChange>(`${P(w, p)}/code/changes/${id}/merge`, { method: "POST" }),
    discard: (w: string, p: string, id: string) => call<CodeChange>(`${P(w, p)}/code/changes/${id}/discard`, { method: "POST" }),
    ai: (w: string, p: string, prompt: string, model?: string) =>
      call<CodeAiResponse>(`${P(w, p)}/code/ai`, { method: "POST", body: json({ prompt, ...(model ? { model } : {}) }), signal: AbortSignal.timeout(130_000) }),
    aiStream: (w: string, p: string, prompt: string, model: string | undefined, h: StreamHandlers) =>
      stream<CodeAiResponse>(`${P(w, p)}/code/ai/stream`, { prompt, ...(model ? { model } : {}) }, h),
    aiHistory: (w: string, p: string) => call<CodeAiHistoryItem[]>(`${P(w, p)}/code/ai`),
    design: (w: string, p: string, path = "src/App.tsx") => call<{ ok: boolean; nodes: DesignNode[] }>(`${P(w, p)}/code/design${qs({ path })}`),
    designEdit: (w: string, p: string, body: { path: string; nodeId: string; text?: string; hidden?: boolean; props?: Record<string, string | null>; summary?: string }) =>
      call<CodeChange>(`${P(w, p)}/code/design/edit`, { method: "POST", body: json(body) }),
    dependencies: (w: string, p: string) => call<{ requests: DependencyRequest[]; approved: { name: string; spec: string }[] }>(`${P(w, p)}/code/dependencies`),
    requestDependency: (w: string, p: string, name: string) => call<DependencyRequest>(`${P(w, p)}/code/dependencies`, { method: "POST", body: json({ name }) }),
    approve: (w: string, p: string, id: string, comment?: string) => call<CodeChange>(`${P(w, p)}/code/changes/${id}/approve`, { method: "POST", body: json({ comment }) }),
    mergePolicy: (w: string, p: string, policy: "AUTO_MERGE_ALLOWED" | "REVIEW_REQUIRED" | null) => call<{ effective: string; project: string }>(`${P(w, p)}/code/merge-policy`, { method: "PUT", body: json({ policy }) }),
    cloneAccess: (w: string, p: string) => call<CloneAccess>(`${P(w, p)}/code/clone-access`, { method: "POST" }),
    revokeCloneAccess: () => call<void>("/me/clone-access", { method: "DELETE" })
  },
  templates: (scope: "company" | "mine", f: { category?: string; tag?: string; sort?: "recent" | "popular"; q?: string } = {}) => call<TemplateDto[]>(`/templates${qs({ scope, ...f })}`),
  libraryCategories: () => call<LibraryCategories>("/library/categories"),
  templateCatalog: (id: string, body: { category?: string; tags?: string[] }) => call<TemplateDto>(`/templates/${id}/catalog`, { method: "PATCH", body: json(body) }),
  submitTemplate: (id: string) => call<{ template: TemplateDto; passed: boolean; checks: CheckResult[] }>(`/templates/${id}/submit`, { method: "POST" }),
  withdrawTemplate: (id: string) => call<TemplateDto>(`/templates/${id}/withdraw`, { method: "POST" }),
  templateReviews: (id: string) => call<TemplateReview[]>(`/templates/${id}/reviews`),
  blockCatalog: (id: string, body: { category?: string; tags?: string[] }) => call<unknown>(`/component-packages/${id}/catalog`, { method: "PATCH", body: json(body) }),
  saveTemplate: (w: string, p: string, body: { name: string; description?: string; templateId?: string }) =>
    call<{ template: TemplateDto; removedImages: number }>(`${P(w, p)}/templates`, { method: "POST", body: json(body) }),
  updateTemplate: (id: string, body: { name?: string; description?: string }) => call<TemplateDto>(`/templates/${id}`, { method: "PATCH", body: json(body) }),
  archiveTemplate: (id: string) => call<void>(`/templates/${id}`, { method: "DELETE" }),
  blocks: (scope: "company" | "mine") => call<BlockDto[]>(`/component-packages${qs({ scope })}`),
  block: (id: string) => call<BlockDto>(`/component-packages/${id}`),
  saveBlock: (w: string, p: string, body: { sectionId: string; name: string; description?: string; packageId?: string }) =>
    call<{ block: BlockDto; removedImages: number }>(`${P(w, p)}/component-packages`, { method: "POST", body: json(body) }),
  updateBlock: (id: string, body: { name?: string; description?: string }) => call<BlockDto>(`/component-packages/${id}`, { method: "PATCH", body: json(body) }),
  submitBlock: (id: string) => call<{ block: BlockDto; passed: boolean; checks: CheckResult[] }>(`/component-packages/${id}/submit`, { method: "POST" }),
  withdrawBlock: (id: string) => call<BlockDto>(`/component-packages/${id}/withdraw`, { method: "POST" }),
  deleteBlock: (id: string) => call<void>(`/component-packages/${id}`, { method: "DELETE" }),
  getProject: (w: string, p: string) => call<ApiProject>(P(w, p)),
  /**
   * Browser-facing runtime routes (api.appRuntime; `api.runtime` is the unrelated deployment-status call) (docs/contracts/v2/runtime-api.md, FROZEN). Flags: `app.data-platform.enabled` (queries), `app.workflow.enabled`
   * (actions, workflows). A disabled flag = the controller does not exist = a 404 WITHOUT a domain code (see readiness.runtimeReadinessFromError).
   * Bodies carry only the fields of the contract; the server rejects anything else (400 INVALID_REQUEST).
   */
  appRuntime: {
    runQuery: (w: string, p: string, queryId: string, body: RunQueryRequest = {}) =>
      call<RunQueryResponse>(`${RT(w, p)}/queries/${seg(queryId)}/run`, { method: "POST", body: json(body) }),
    executeAction: async (w: string, p: string, actionId: string, body: ExecuteActionRequest = {}) => {
      checkKey(body.idempotencyKey);
      return call<ActionEnvelope>(`${RT(w, p)}/actions/${seg(actionId)}/execute`, { method: "POST", body: json(body) });
    },
    startWorkflow: async (w: string, p: string, workflowId: string, body: StartWorkflowRequest) => { // async: a refusal is a rejected promise, never a synchronous throw
      checkKey(body.idempotencyKey); // required by the contract
      if (!body.idempotencyKey) badKey();
      return call<WorkflowRunView>(`${RT(w, p)}/workflows/${seg(workflowId)}/runs`, { method: "POST", body: json(body) });
    },
    workflowRun: (w: string, p: string, runId: string) => call<WorkflowRunView>(`${RT(w, p)}/workflow-runs/${seg(runId)}`),
    cancelWorkflowRun: (w: string, p: string, runId: string) => call<WorkflowRunView>(`${RT(w, p)}/workflow-runs/${seg(runId)}/cancel`, { method: "POST" }),
  },
  /**
   * Data Source Management API (C3, MANAGEMENT_API.md @ e606465; routes exist in C3's code, NOT verified against a running backend). Flag: `app.data-platform.enabled`
   * (off = controller not mounted = 404 WITHOUT a domain code). Tenant is derived by the server from the workspace in the path; the client never sends tenant/workspace/credentialRef/id in a body.
   * No call here ever returns or logs a secret: `credential` bodies are write-only and the only answer about a credential is CredentialMetadata (key names).
   */
  dataManagement: {
    connectors: (w: string) => call<ConnectorList>(`${WS(w)}/connectors`),
    list: (w: string) => call<DataSourceList>(WS(w)),
    get: (w: string, id: string) => call<DataSourceView>(`${WS(w)}/${seg(id)}`),
    create: async (w: string, body: CreateDataSourceRequest) => {
      if (!DS_NAME.test(body.name)) invalid("Tên nguồn dữ liệu không hợp lệ (chữ/số, khoảng trắng . _ -, tối đa 80 ký tự, bắt đầu bằng chữ hoặc số).");
      return call<DataSourceView>(WS(w), { method: "POST", body: json({ name: body.name, type: body.type, ...(body.config ? { config: body.config } : {}), ...(body.credential ? { credential: body.credential } : {}) }) });
    },
    /** `config` REPLACES the whole configuration (send every key to keep). The server applies name/config first and status second, not atomically: after any error, re-read. */
    update: async (w: string, id: string, body: UpdateDataSourceRequest) => {
      if (body.name === undefined && body.config === undefined && body.status === undefined) invalid("Không có gì để thay đổi.");
      if (body.name !== undefined && !DS_NAME.test(body.name)) invalid("Tên nguồn dữ liệu không hợp lệ.");
      return call<DataSourceView>(`${WS(w)}/${seg(id)}`, { method: "PATCH", body: json({ ...(body.name !== undefined ? { name: body.name } : {}), ...(body.config !== undefined ? { config: body.config } : {}), ...(body.status !== undefined ? { status: body.status } : {}) }) });
    },
    remove: (w: string, id: string) => call<void>(`${WS(w)}/${seg(id)}`, { method: "DELETE" }),
    credential: (w: string, id: string) => call<CredentialMetadata>(`${WS(w)}/${seg(id)}/credential`),
    setCredential: async (w: string, id: string, credential: SetCredentialRequest["credential"]) => {
      const keys = Object.keys(credential);
      if (keys.length < 1 || keys.length > 8) invalid("Khóa kết nối phải có từ 1 đến 8 trường.");
      return call<CredentialMetadata>(`${WS(w)}/${seg(id)}/credential`, { method: "PUT", body: json({ credential }) });
    },
    removeCredential: (w: string, id: string) => call<void>(`${WS(w)}/${seg(id)}/credential`, { method: "DELETE" }),
    /** HTTP 200 means "the test ran"; read `ok`. Non-200: 404 / 409 DISABLED / 403 / 429. */
    testConnection: (w: string, id: string) => call<ConnectionTestResult>(`${WS(w)}/${seg(id)}/test`, { method: "POST", signal: AbortSignal.timeout(MGMT_TEST_TIMEOUT_MS) }),
    listBindings: (w: string, p: string) => call<DataBindingList>(`${P(w, p)}/data-bindings`),
    bind: async (w: string, p: string, mode: string, slotId: string, dataSourceId: string) => {
      const m = bindingMode(mode); if (!SLOT.test(slotId)) invalid("Mã khe dữ liệu không hợp lệ.");
      return call<DataBinding>(`${P(w, p)}/data-bindings/${m}/${seg(slotId)}`, { method: "PUT", body: json({ dataSourceId }) });
    },
    unbind: async (w: string, p: string, mode: string, slotId: string) => {
      const m = bindingMode(mode); if (!SLOT.test(slotId)) invalid("Mã khe dữ liệu không hợp lệ.");
      return call<void>(`${P(w, p)}/data-bindings/${m}/${seg(slotId)}`, { method: "DELETE" });
    },
  },
  lookupProject: (p: string) => call<ApiProject>(`/projects/${p}`),
  updateProject: (w: string, p: string, expectedRevision: number, patch: Partial<ApiProject>) =>
    call<ApiProject>(P(w, p), { method: "PATCH", body: json({ ...patch, expectedRevision }) }),

  listWorkspaceMembers: (w: string) => call<Member[]>(`/workspaces/${w}/members`),
  addWorkspaceMember: (w: string, who: { username?: string; email?: string }, role: string) => call<Member>(`/workspaces/${w}/members`, { method: "POST", body: json({ ...who, role }) }),
  changeWorkspaceMember: (w: string, userId: string, role: string) => call<Member>(`/workspaces/${w}/members/${userId}`, { method: "PATCH", body: json({ role }) }),
  removeWorkspaceMember: (w: string, userId: string) => call<void>(`/workspaces/${w}/members/${userId}`, { method: "DELETE" }),
  listProjectMembers: (w: string, p: string) => call<Member[]>(`${P(w, p)}/members`),
  addProjectMember: (w: string, p: string, who: { username?: string; email?: string }, role: string) => call<Member>(`${P(w, p)}/members`, { method: "POST", body: json({ ...who, role }) }),
  changeProjectMember: (w: string, p: string, userId: string, role: string) => call<Member>(`${P(w, p)}/members/${userId}`, { method: "PATCH", body: json({ role }) }),
  removeProjectMember: (w: string, p: string, userId: string) => call<void>(`${P(w, p)}/members/${userId}`, { method: "DELETE" }),

  getSchema: (w: string, p: string) => call<SchemaResponse>(`${P(w, p)}/schema`),
  patchSchema: (w: string, p: string, expectedRevision: number, operations: (SchemaOperation | DefinitionOperation)[], summary?: string, blockId?: string) =>
    call<SchemaResponse>(`${P(w, p)}/schema`, { method: "PATCH", body: json({ expectedRevision, operations, summary, ...(blockId ? { blockId } : {}) }) }),
  sendPrompt: (w: string, p: string, prompt: string, expectedRevision: number, model?: string) =>
    // AI calls can take a while when the first free model is busy and the server fails over to the next one
    call<PromptResponse>(`${P(w, p)}/prompts`, { method: "POST", body: json({ prompt, expectedRevision, ...(model ? { model } : {}) }), signal: AbortSignal.timeout(130_000) }),
  streamPrompt: (w: string, p: string, prompt: string, expectedRevision: number, model: string | undefined, h: StreamHandlers, signal?: AbortSignal) =>
    stream<PromptResponse>(`${P(w, p)}/prompts/stream`, { prompt, expectedRevision, ...(model ? { model } : {}) }, h, signal),
  listPrompts: (w: string, p: string) => call<PromptHistoryItem[]>(`${P(w, p)}/prompts?limit=100`),   // newest first

  listVersions: (w: string, p: string) => call<VersionSummary[]>(`${P(w, p)}/versions?limit=100`),
  restoreVersion: (w: string, p: string, versionId: string, expectedRevision: number) =>
    call<{ version: VersionSummary; schema: SchemaResponse["schema"]; revision: number }>(`${P(w, p)}/versions/${versionId}/restore`, { method: "POST", body: json({ expectedRevision }) }),

  listAssets: (w: string, p: string) => call<AssetDto[]>(`${P(w, p)}/assets`),
  async uploadAsset(w: string, p: string, file: File): Promise<AssetDto> {
    const ticket = await call<UploadUrl>(`${P(w, p)}/assets/upload-url`, { method: "POST", body: json({ fileName: file.name, contentType: file.type, size: file.size }) });
    const put = await fetch(ticket.uploadUrl, { method: ticket.method, headers: { "Content-Type": file.type, ...ticket.headers }, body: file });
    if (!put.ok) throw new ApiError(put.status, "UPLOAD_FAILED", `Tải lên kho lưu trữ thất bại (${put.status}).`);
    return call<AssetDto>(`${P(w, p)}/assets/complete`, { method: "POST", body: json({ assetId: ticket.assetId }) });
  },
  deleteAsset: (w: string, p: string, id: string) => call<void>(`${P(w, p)}/assets/${id}`, { method: "DELETE" }),

  /** 202 + the deployment (asynchronous: poll getDeployment). Idempotency-Key REQUIRED; body is exactly {visibility, expectedRevision}. */
  publish: async (w: string, p: string, visibility: "PRIVATE" | "PUBLIC", expectedRevision: number, idempotencyKey: string) => {
    checkReleaseKey(idempotencyKey, true);
    return call<Deployment>(`${P(w, p)}/publish`, { method: "POST", body: json(publishBody(visibility, expectedRevision)), idempotencyKey });
  },
  /** the authoritative publish policy (C2 PublishConfigApi): `config` null = none stored. Reading needs only to see the project; the link token is never returned here. */
  getPublishConfig: (w: string, p: string) => call<PublishConfigView>(`${P(w, p)}/publish-config`),
  /** Sets the policy. The ONLY way `publicDataApproved` becomes true: `acknowledgePublicData: true` by a holder of APP_PUBLISH, with the `expectedRevision` of the config the person saw (409 REVISION_CONFLICT otherwise). Never part of POST /publish. */
  putPublishConfig: (w: string, p: string, b: SetPublishConfigBody) => call<PublishConfigView>(`${P(w, p)}/publish-config`, { method: "PUT", body: json(publishConfigBody(b)) }),
  getDeployment: (w: string, p: string, id: string) => call<Deployment>(`${P(w, p)}/deployments/${id}`),
  listDeployments: (w: string, p: string) => call<Deployment[]>(`${P(w, p)}/deployments`),
  site: (w: string, p: string) => call<SiteInfo>(`${P(w, p)}/site`),
  formSubmissions: (w: string, p: string, page = 0) => call<{ items: FormSubmission[]; total: number; page: number; size: number }>(`${P(w, p)}/form-submissions${qs({ page })}`),
  deleteFormSubmission: (w: string, p: string, id: string) => call<void>(`${P(w, p)}/form-submissions/${id}`, { method: "DELETE" }),
  formExportUrl: (w: string, p: string) => `/api/v1${P(w, p)}/form-submissions/export`,
  domains: (w: string, p: string) => call<SiteDomain[]>(`${P(w, p)}/domains`),
  addDomain: (w: string, p: string, hostname: string) => call<SiteDomain>(`${P(w, p)}/domains`, { method: "POST", body: json({ hostname }) }),
  verifyDomain: (w: string, p: string, id: string) => call<SiteDomain>(`${P(w, p)}/domains/${id}/verify`, { method: "POST" }),
  checkDomainTls: (w: string, p: string, id: string) => call<SiteDomain>(`${P(w, p)}/domains/${id}/check-tls`, { method: "POST" }),
  removeDomain: (w: string, p: string, id: string) => call<void>(`${P(w, p)}/domains/${id}`, { method: "DELETE" }),
  /** 200 + SiteInfo, SYNCHRONOUS (the site serves the restored release when it returns). Body {deploymentId, expectedActiveDeploymentId?}; Idempotency-Key optional. */
  rollbackSite: async (w: string, p: string, req: { deploymentId: string; expectedActiveDeploymentId?: string | null }, idempotencyKey?: string) => {
    checkReleaseKey(idempotencyKey, false);
    return call<SiteInfo>(`${P(w, p)}/site/rollback`, { method: "POST", body: json(rollbackBody(req)), idempotencyKey });
  },
  /** 200 + SiteInfo, NO body; the optional expectation is the query parameter; Idempotency-Key optional. Already offline = 200, nothing written. */
  unpublishSite: async (w: string, p: string, expectedActiveDeploymentId?: string | null, idempotencyKey?: string) => {
    checkReleaseKey(idempotencyKey, false);
    return call<SiteInfo>(`${P(w, p)}/site${unpublishQuery(expectedActiveDeploymentId)}`, { method: "DELETE", idempotencyKey });
  },
  siteAccessTicket: (slug: string, path: string) => call<{ redirect: string }>(`/sites/${encodeURIComponent(slug)}/access-ticket`, { method: "POST", body: json({ path }) })
};
