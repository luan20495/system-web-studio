/**
 * Runtime configuration of a PUBLISHED app (C2 proposal, not yet verified by C2 / C0 / C3): the page loads `GET /runtime-config.json` BEFORE it creates any Data API
 * client, so one built artifact works in DEV / STAGING / PROD and a host change never needs a rebuild.
 *
 *   { "DATA_API_BASE_URL": "https://data.<domain>", "ENVIRONMENT": "staging", "RELEASE_ID": "…", "VERSION": "…" }
 *
 * Rules (fail closed):
 *  - DATA_API_BASE_URL is required, absolute, `https:` (plain `http:` only for a loopback host and only in `development` mode), no credentials, no query, no fragment;
 *  - nothing is baked into the artifact and no host is hard-coded here; in `production` mode there is NO fallback of any kind;
 *  - `development` mode may fall back to an explicit `devFallback` URL (given by the dev host, e.g. from its own env) when the file is missing or unreachable — never when it is present but invalid;
 *  - every failure is a typed RuntimeConfigError that the page shows (see `describeRuntimeConfigError`), never a silent default and never an unhandled rejection.
 * This module has no React/DOM dependency and loads under plain node (unit tests).
 */

export type RuntimeConfig = { DATA_API_BASE_URL: string; ENVIRONMENT?: string; RELEASE_ID?: string; VERSION?: string };
export type RuntimeConfigMode = "development" | "production";

export type RuntimeConfigErrorCode =
  | "UNREACHABLE" | "TIMEOUT" | "HTTP_STATUS" | "NOT_JSON" | "INVALID_SHAPE"
  | "MISSING_DATA_API_BASE_URL" | "INVALID_DATA_API_BASE_URL" | "INSECURE_DATA_API_BASE_URL" | "LOOPBACK_IN_PRODUCTION" | "INVALID_PATH";

export class RuntimeConfigError extends Error {
  constructor(readonly code: RuntimeConfigErrorCode, message: string, readonly status?: number) { super(message); this.name = "RuntimeConfigError"; }
}

export const RUNTIME_CONFIG_PATH = "/runtime-config.json";

const LOOPBACK = /^(localhost|127(?:\.\d{1,3}){3}|\[::1\]|::1)$/i;
const isLoopback = (host: string) => LOOPBACK.test(host) || host.toLowerCase().endsWith(".localhost");

/** Returns the normalised base URL (no trailing slash) or throws. `mode` decides whether a loopback http host is acceptable. */
export function validateDataApiBaseUrl(value: unknown, mode: RuntimeConfigMode = "production", environment?: string): string {
  if (value === undefined || value === null || (typeof value === "string" && value.trim() === "")) throw new RuntimeConfigError("MISSING_DATA_API_BASE_URL", "DATA_API_BASE_URL is missing in the runtime configuration.");
  if (typeof value !== "string") throw new RuntimeConfigError("INVALID_DATA_API_BASE_URL", "DATA_API_BASE_URL must be a string.");
  let u: URL;
  try { u = new URL(value.trim()); } catch { throw new RuntimeConfigError("INVALID_DATA_API_BASE_URL", "DATA_API_BASE_URL is not an absolute URL."); }
  if (u.protocol !== "https:" && u.protocol !== "http:") throw new RuntimeConfigError("INVALID_DATA_API_BASE_URL", "DATA_API_BASE_URL must be an http(s) URL.");
  if (u.username || u.password) throw new RuntimeConfigError("INVALID_DATA_API_BASE_URL", "DATA_API_BASE_URL must not contain credentials.");
  if (u.search || u.hash) throw new RuntimeConfigError("INVALID_DATA_API_BASE_URL", "DATA_API_BASE_URL must not contain a query or fragment.");
  const loop = isLoopback(u.hostname);
  if (u.protocol === "http:" && !(loop && mode === "development")) throw new RuntimeConfigError("INSECURE_DATA_API_BASE_URL", "DATA_API_BASE_URL must use https (plain http is accepted only for localhost in development).");
  if (loop && (mode === "production" || (environment ?? "").toLowerCase() === "production")) throw new RuntimeConfigError("LOOPBACK_IN_PRODUCTION", "DATA_API_BASE_URL points to localhost in a production configuration.");
  return `${u.origin}${u.pathname.replace(/\/+$/, "")}`;
}

/** Validates an already-parsed document. Unknown keys are dropped (the config is not a bag for arbitrary data). */
export function parseRuntimeConfig(raw: unknown, mode: RuntimeConfigMode = "production"): RuntimeConfig {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new RuntimeConfigError("INVALID_SHAPE", "The runtime configuration must be a JSON object.");
  const o = raw as Record<string, unknown>;
  for (const k of ["ENVIRONMENT", "RELEASE_ID", "VERSION"] as const) if (o[k] !== undefined && typeof o[k] !== "string") throw new RuntimeConfigError("INVALID_SHAPE", `${k} must be a string.`);
  const environment = typeof o.ENVIRONMENT === "string" ? o.ENVIRONMENT : undefined;
  const base = validateDataApiBaseUrl(o.DATA_API_BASE_URL, mode, environment);
  return { DATA_API_BASE_URL: base, ...(environment !== undefined ? { ENVIRONMENT: environment } : {}), ...(typeof o.RELEASE_ID === "string" ? { RELEASE_ID: o.RELEASE_ID } : {}), ...(typeof o.VERSION === "string" ? { VERSION: o.VERSION } : {}) };
}

export type LoadOptions = {
  /** where the file is served from; default `/runtime-config.json` (same origin as the page) */
  url?: string;
  /** default `production` = fail closed */
  mode?: RuntimeConfigMode;
  /** development only: used when the file is missing/unreachable. Ignored in production. Must itself validate. */
  devFallback?: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
  signal?: AbortSignal;
};

/** Loads and validates the runtime configuration. Resolves with a valid config or rejects with a RuntimeConfigError. */
export async function loadRuntimeConfig(opts: LoadOptions = {}): Promise<RuntimeConfig> {
  const mode = opts.mode ?? "production";
  const f = opts.fetchImpl ?? (typeof fetch === "function" ? fetch : undefined);
  const fallback = (cause: RuntimeConfigError): RuntimeConfig => {
    if (mode === "development" && opts.devFallback) return { DATA_API_BASE_URL: validateDataApiBaseUrl(opts.devFallback, "development"), ENVIRONMENT: "development" };
    throw cause;
  };
  if (!f) return fallback(new RuntimeConfigError("UNREACHABLE", "No fetch implementation is available to load the runtime configuration."));
  let res: Response;
  try {
    res = await f(opts.url ?? RUNTIME_CONFIG_PATH, { cache: "no-store", credentials: "omit", headers: { Accept: "application/json" }, signal: opts.signal ?? AbortSignal.timeout(opts.timeoutMs ?? 8_000) });
  } catch (e) {
    const timeout = e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError");
    return fallback(new RuntimeConfigError(timeout ? "TIMEOUT" : "UNREACHABLE", timeout ? "The runtime configuration did not answer in time." : "The runtime configuration could not be reached."));
  }
  if (!res.ok) {
    const err = new RuntimeConfigError("HTTP_STATUS", `The runtime configuration answered HTTP ${res.status}.`, res.status);
    return res.status === 404 ? fallback(err) : (() => { throw err; })();   // a missing file may fall back in development; any other status never does
  }
  let body: unknown;
  try { body = JSON.parse(await res.text()); } catch { throw new RuntimeConfigError("NOT_JSON", "The runtime configuration is not valid JSON."); }
  return parseRuntimeConfig(body, mode);
}

/** Joins a request path to the configured host. The path can never change the host: it must be a plain absolute path. */
export function dataApiUrl(config: Pick<RuntimeConfig, "DATA_API_BASE_URL">, path: string): string {
  if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//") || path.includes("\\") || /(^|\/)\.\.?(\/|$)/.test(path.split("?")[0]) || path.includes("://")) {
    throw new RuntimeConfigError("INVALID_PATH", "A Data API path must be a plain absolute path.");
  }
  return `${config.DATA_API_BASE_URL}${path}`;
}

/** What a published page creates before any Data API call: the validated config and a URL builder bound to it. */
export async function initDataApi(opts: LoadOptions = {}): Promise<{ config: RuntimeConfig; baseUrl: string; url: (path: string) => string }> {
  const config = await loadRuntimeConfig(opts);
  return { config, baseUrl: config.DATA_API_BASE_URL, url: (p) => dataApiUrl(config, p) };
}

const TEXT: Record<RuntimeConfigErrorCode, string> = {
  UNREACHABLE: "Không tải được cấu hình chạy (/runtime-config.json). Kiểm tra mạng hoặc liên hệ người vận hành.",
  TIMEOUT: "Cấu hình chạy không phản hồi kịp. Tải lại trang hoặc liên hệ người vận hành.",
  HTTP_STATUS: "Máy chủ trả lỗi khi tải cấu hình chạy. Liên hệ người vận hành.",
  NOT_JSON: "Cấu hình chạy không phải JSON hợp lệ. Liên hệ người vận hành.",
  INVALID_SHAPE: "Cấu hình chạy sai định dạng. Liên hệ người vận hành.",
  MISSING_DATA_API_BASE_URL: "Cấu hình chạy thiếu DATA_API_BASE_URL. Liên hệ người vận hành.",
  INVALID_DATA_API_BASE_URL: "DATA_API_BASE_URL trong cấu hình chạy không hợp lệ. Liên hệ người vận hành.",
  INSECURE_DATA_API_BASE_URL: "DATA_API_BASE_URL phải dùng https. Liên hệ người vận hành.",
  LOOPBACK_IN_PRODUCTION: "DATA_API_BASE_URL đang trỏ về localhost trong môi trường production. Liên hệ người vận hành.",
  INVALID_PATH: "Đường dẫn gọi dữ liệu không hợp lệ.",
};
/** Visible, user-safe text for a failed bootstrap. The page must show it: no silent default host. */
export function describeRuntimeConfigError(e: unknown): { title: string; detail: string; code: RuntimeConfigErrorCode | "UNKNOWN" } {
  if (e instanceof RuntimeConfigError) return { title: "Ứng dụng chưa thể kết nối dữ liệu", detail: TEXT[e.code], code: e.code };
  return { title: "Ứng dụng chưa thể kết nối dữ liệu", detail: "Đã có lỗi khi tải cấu hình chạy. Liên hệ người vận hành.", code: "UNKNOWN" };
}

/** Writes the failure into a container as an alert (textContent only; no HTML is ever built from the error). */
export function renderRuntimeConfigFailure(root: { replaceChildren: (...n: never[]) => void; ownerDocument: Document }, e: unknown): void {
  const d = describeRuntimeConfigError(e); const doc = root.ownerDocument;
  const box = doc.createElement("div"); box.setAttribute("role", "alert"); box.setAttribute("data-runtime-config-error", d.code);
  const h = doc.createElement("strong"); h.textContent = d.title; const p = doc.createElement("p"); p.textContent = d.detail;
  box.append(h, p); (root.replaceChildren as (...n: Node[]) => void)(box);
}
