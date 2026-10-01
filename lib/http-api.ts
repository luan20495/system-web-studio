import type {
  ApiProject, AssetDto, Deployment, Me, PromptHistoryItem, PromptResponse, SchemaOperation, SchemaResponse, UploadUrl, VersionSummary
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

async function call<T>(path: string, init: RequestInit & { idempotencyKey?: string } = {}, retry = true): Promise<T> {
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
    const retryAfter = response.headers.get("Retry-After");
    const message = response.status === 429 && retryAfter ? `${body?.message ?? "Quá nhiều yêu cầu"} Thử lại sau ${retryAfter}s.` : body?.message ?? `Lỗi ${response.status}`;
    throw new ApiError(response.status, body?.code ?? `HTTP_${response.status}`, message, body?.requestId, body?.details);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

const json = (body: unknown) => JSON.stringify(body);
const P = (w: string, p: string) => `/workspaces/${w}/projects/${p}`;

export const api = {
  async login(username: string, password: string): Promise<Me> {
    await call("/auth/login", { method: "POST", body: json({ username, password }) });
    resetCsrf();
    return call<Me>("/auth/me");
  },
  me: () => call<Me>("/auth/me"),
  async logout() { await call<void>("/auth/logout", { method: "POST" }).finally(resetCsrf); },

  listProjects: (w: string) => call<ApiProject[]>(`/workspaces/${w}/projects`),
  createProject: (w: string, name: string, description?: string) => call<ApiProject>(`/workspaces/${w}/projects`, { method: "POST", body: json({ name, description }) }),
  getProject: (w: string, p: string) => call<ApiProject>(P(w, p)),
  updateProject: (w: string, p: string, expectedRevision: number, patch: Partial<ApiProject>) =>
    call<ApiProject>(P(w, p), { method: "PATCH", body: json({ ...patch, expectedRevision }) }),

  getSchema: (w: string, p: string) => call<SchemaResponse>(`${P(w, p)}/schema`),
  patchSchema: (w: string, p: string, expectedRevision: number, operations: SchemaOperation[], summary?: string) =>
    call<SchemaResponse>(`${P(w, p)}/schema`, { method: "PATCH", body: json({ expectedRevision, operations, summary }) }),
  sendPrompt: (w: string, p: string, prompt: string, expectedRevision: number) =>
    call<PromptResponse>(`${P(w, p)}/prompts`, { method: "POST", body: json({ prompt, expectedRevision }) }),
  listPrompts: (w: string, p: string) => call<PromptHistoryItem[]>(`${P(w, p)}/prompts`),

  listVersions: (w: string, p: string) => call<VersionSummary[]>(`${P(w, p)}/versions`),
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
  listDeployments: (w: string, p: string) => call<Deployment[]>(`${P(w, p)}/deployments`)
};
