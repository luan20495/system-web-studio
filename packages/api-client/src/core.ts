import type { StreamHandlers } from "@xweb/types";

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


export async function call<T>(path: string, init: RequestInit & { idempotencyKey?: string; onTotal?: (n: number) => void } = {}, retry = true): Promise<T> {
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

/**
 * POST that answers with server-sent events (AI streaming): start → delta* / status* → result | error. Resolves with the `result` body.
 * Refusals before the stream starts (quota, budget, model access) arrive as ordinary JSON errors.
 */
export async function stream<T>(path: string, body: unknown, h: StreamHandlers): Promise<T> {
  const token = await csrf();
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, { method: "POST", credentials: "include", cache: "no-store",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", "X-XSRF-TOKEN": token }, body: JSON.stringify(body) });
  } catch { throw new ApiError(0, "NETWORK", "Không kết nối được tới máy chủ. Kiểm tra backend rồi thử lại."); }
  if (!response.ok || !response.body) {
    const b = await response.json().catch(() => null) as { code?: string; message?: string; requestId?: string; details?: unknown } | null;
    if (response.status === 403 && b?.code === "CSRF_INVALID") resetCsrf();
    throw new ApiError(response.status, b?.code ?? `HTTP_${response.status}`, b?.message ?? `Lỗi ${response.status}`, b?.requestId, b?.details);
  }
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += value;
    let i: number;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const block = buf.slice(0, i); buf = buf.slice(i + 2);
      const event = /^event:(.*)$/m.exec(block)?.[1]?.trim() ?? "message";
      const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5)).join("\n");
      if (!data) continue;
      const parsed = JSON.parse(data) as Record<string, unknown>;
      if (event === "start") h.onStart?.(String(parsed.streamId));
      else if (event === "delta") h.onDelta?.(String(parsed.text ?? ""));
      else if (event === "status") h.onStatus?.(String(parsed.text ?? ""));
      else if (event === "result") return parsed as T;
      else if (event === "error") throw new ApiError(500, String(parsed.code ?? "AI_STREAM_FAILED"), String(parsed.message ?? "AI request failed"));
    }
  }
  throw new ApiError(0, "STREAM_ENDED", "Kết nối AI bị ngắt trước khi có kết quả.");
}

export const json = (body: unknown) => JSON.stringify(body);
export const qs = (params: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams(); Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)); });
  const t = p.toString(); return t ? `?${t}` : "";
};
