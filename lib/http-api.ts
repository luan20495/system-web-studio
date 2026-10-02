import type {
  AdminAi, AiCallRow, AiPrice, AiProbe, AiProviderInfo, AiUsageReport, AdminApp, BlockDto, CheckResult, TemplateDto, AdminAppDetail, AdminComponent, AdminOverview, AdminUser, AdminUserDetail, AdminWorkspace, AdminWorkspaceDetail, AuditRow, MyUsage, Page, PlatformHealth,
  AiStatus, ApiProject, AuthConfig, Member, SiteInfo, RegistryComponent, AssetDto, Deployment, Me, PromptHistoryItem, PromptResponse, SchemaOperation, SchemaResponse, UploadUrl, VersionSummary
} from "./http-types";

/** Error with the backend's stable {code, message, requestId, details} contract. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly requestId?: string, readonly details?: unknown) {
    super(message);
  }
  get isConflict() { return this.status === 409; }
}

let csrfRequest: Promise<string> | undefined;

async function csrf(): Promise<string> {
  csrfRequest ??= fetch("/api/v1/auth/csrf", { credentials: "include", cache: "no-store" })
    .then(async (r) => {
      if (!r.ok) throw new ApiError(r.status, "CSRF_UNAVAILABLE", `Không lấy được CSRF token (${r.status}).`);
      const body = await r.json() as { token?: string };
      if (!body.token) throw new ApiError(500, "CSRF_UNAVAILABLE", "API không trả CSRF token.");
      return body.token;
    })
    .catch((e: unknown) => { csrfRequest = undefined; throw e; });
  return csrfRequest;
}

/** Forget the cached CSRF token (it is bound to the session, so login/logout rotate it). */
export const resetCsrf = () => { csrfRequest = undefined; };

/** One place decides what an expired session means for the UI (the router sends the user to /auth/session-expired). */
let unauthorizedHandler: ((code: string) => void) | null = null;
export const onUnauthorized = (fn: ((code: string) => void) | null) => { unauthorizedHandler = fn; };


async function call<T>(path: string, init: RequestInit & { idempotencyKey?: string; onTotal?: (n: number) => void } = {}, retry = true): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const mutating = !["GET", "HEAD", "OPTIONS"].includes(method);
  const token = mutating ? await csrf() : undefined;
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      ...init, credentials: "include", cache: "no-store", signal: init.signal ?? AbortSignal.timeout(15_000),
      headers: {
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { "X-XSRF-TOKEN": token } : {}),
        ...(init.idempotencyKey ? { "Idempotency-Key": init.idempotencyKey } : {}),
        ...(init.headers ?? {})
      }
    });
  } catch {
    throw new ApiError(0, "NETWORK", "Không kết nối được tới máy chủ. Kiểm tra backend rồi thử lại.");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { code?: string; message?: string; requestId?: string; details?: unknown } | null;
    if (response.status === 403 && body?.code === "CSRF_INVALID" && retry) { resetCsrf(); return call<T>(path, init, false); }
    if (response.status === 401 && !path.startsWith("/auth/")) unauthorizedHandler?.(body?.code ?? "AUTHENTICATION_REQUIRED");
    const retryAfter = response.headers.get("Retry-After");
    const message = response.status === 429 && retryAfter ? `${body?.message ?? "Quá nhiều yêu cầu"} Thử lại sau ${retryAfter}s.` : body?.message ?? `Lỗi ${response.status}`;
    throw new ApiError(response.status, body?.code ?? `HTTP_${response.status}`, message, body?.requestId, body?.details);
  }
  init.onTotal?.(Number(response.headers.get("X-Total-Count") ?? "0"));
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

const json = (body: unknown) => JSON.stringify(body);
const qs = (params: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams(); Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)); });
  const t = p.toString(); return t ? `?${t}` : "";
};
const P = (w: string, p: string) => `/workspaces/${w}/projects/${p}`;

export const api = {
  async login(username: string, password: string): Promise<Me> {
    await call("/auth/login", { method: "POST", body: json({ username, password }) });
    resetCsrf();
    return call<Me>("/auth/me");
  },
  me: () => call<Me>("/auth/me"),
  authConfig: () => call<AuthConfig>("/auth/config"),
  register: (username: string, password: string, displayName: string, inviteCode?: string) =>
    call<{ username: string }>("/auth/register", { method: "POST", body: json({ username, password, displayName: displayName || undefined, inviteCode: inviteCode || undefined }) }),
  aiStatus: () => call<AiStatus>("/ai/status"),
  components: () => call<RegistryComponent[]>("/components?details=true"),
  async logout() { await call<void>("/auth/logout", { method: "POST" }).finally(resetCsrf); },

  listProjects: (w: string) => call<ApiProject[]>(`/workspaces/${w}/projects`),
  async projectsPage(w: string, page: number, size: number, q?: string, scope?: "all" | "owned" | "shared"): Promise<Page<ApiProject>> {
    let total = 0;
    const items = await call<ApiProject[]>(`/workspaces/${w}/projects${qs({ page, size, q, scope })}`, { onTotal: (n) => { total = n; } });
    return { items, total, page, size };
  },
  deleteProject: (w: string, p: string, expectedRevision: number) => call<void>(`${P(w, p)}${qs({ expectedRevision })}`, { method: "DELETE" }),
  myUsage: () => call<MyUsage>("/me/usage"),
  myActivity: (limit = 30) => call<AuditRow[]>(`/me/activity${qs({ limit })}`),
  admin: {
    overview: () => call<AdminOverview>("/admin/overview"),
    users: (page: number, q?: string, status?: string) => call<Page<AdminUser>>(`/admin/users${qs({ page, size: 25, q, status })}`),
    user: (id: string) => call<AdminUserDetail>(`/admin/users/${id}`),
    setUserStatus: (id: string, enabled: boolean) => call<AdminUser>(`/admin/users/${id}/status`, { method: "PATCH", body: json({ enabled }) }),
    revokeSessions: (id: string) => call<{ revoked: number }>(`/admin/users/${id}/revoke-sessions`, { method: "POST" }),
    workspaces: (page: number, q?: string) => call<Page<AdminWorkspace>>(`/admin/workspaces${qs({ page, size: 25, q })}`),
    workspace: (id: string) => call<AdminWorkspaceDetail>(`/admin/workspaces/${id}`),
    applications: (params: { page: number; q?: string; visibility?: string; status?: string; workspaceId?: string }) => call<Page<AdminApp>>(`/admin/applications${qs({ size: 25, ...params })}`),
    application: (id: string) => call<AdminAppDetail>(`/admin/applications/${id}`),
    transferOwnership: (id: string, userId: string) => call<AdminApp>(`/admin/applications/${id}/transfer-ownership`, { method: "POST", body: json({ userId }) }),
    audit: (params: Record<string, string | number | undefined>) => call<Page<AuditRow>>(`/admin/audit${qs({ size: 50, ...params })}`),
    auditActions: () => call<string[]>("/admin/audit/actions"),
    ai: () => call<AdminAi>("/admin/ai"),
    aiProviders: () => call<AiProviderInfo[]>("/admin/ai/providers"),
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
    templateStatus: (id: string, status: "ACTIVE" | "ARCHIVED") => call<TemplateDto>(`/admin/templates/${id}/status`, { method: "POST", body: json({ status }) }),
    health: () => call<PlatformHealth>("/admin/system/health"),
    settings: () => call<Record<string, Record<string, unknown>>>("/admin/settings")
  },
  createProject: (w: string, name: string, description?: string, templateId?: string) =>
    call<ApiProject>(`/workspaces/${w}/projects`, { method: "POST", body: json({ name, description, ...(templateId ? { templateId } : {}) }) }),
  templates: (scope: "company" | "mine") => call<TemplateDto[]>(`/templates${qs({ scope })}`),
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
  patchSchema: (w: string, p: string, expectedRevision: number, operations: SchemaOperation[], summary?: string) =>
    call<SchemaResponse>(`${P(w, p)}/schema`, { method: "PATCH", body: json({ expectedRevision, operations, summary }) }),
  sendPrompt: (w: string, p: string, prompt: string, expectedRevision: number, model?: string) =>
    // AI calls can take a while when the first free model is busy and the server fails over to the next one
    call<PromptResponse>(`${P(w, p)}/prompts`, { method: "POST", body: json({ prompt, expectedRevision, ...(model ? { model } : {}) }), signal: AbortSignal.timeout(130_000) }),
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

  publish: (w: string, p: string, visibility: "PRIVATE" | "PUBLIC", expectedRevision: number, idempotencyKey: string) =>
    call<Deployment>(`${P(w, p)}/publish`, { method: "POST", body: json({ visibility, expectedRevision }), idempotencyKey }),
  getDeployment: (w: string, p: string, id: string) => call<Deployment>(`${P(w, p)}/deployments/${id}`),
  listDeployments: (w: string, p: string) => call<Deployment[]>(`${P(w, p)}/deployments`),
  site: (w: string, p: string) => call<SiteInfo>(`${P(w, p)}/site`),
  rollbackSite: (w: string, p: string, deploymentId: string) => call<SiteInfo>(`${P(w, p)}/site/rollback`, { method: "POST", body: json({ deploymentId }) }),
  unpublishSite: (w: string, p: string) => call<SiteInfo>(`${P(w, p)}/site`, { method: "DELETE" }),
  siteAccessTicket: (slug: string, path: string) => call<{ redirect: string }>(`/sites/${encodeURIComponent(slug)}/access-ticket`, { method: "POST", body: json({ path }) })
};
