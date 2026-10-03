import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

/**
 * Runtime configuration served next to the app by the factory gateway at `__factory/config.json` (relative to the app root).
 * The app runs in a sandboxed (opaque) origin: no cookies or storage — identity and flags come from this file.
 */
export type RuntimeConfig = {
  appId: string; appName: string; environment: "preview" | "production"; visibility: "PUBLIC" | "PRIVATE";
  version: string | null; user: { displayName: string } | null; flags: Record<string, boolean>; apiBase: string | null; generatedAt: string;
};

const FALLBACK: RuntimeConfig = { appId: "local", appName: "Local app", environment: "preview", visibility: "PUBLIC", version: null, user: null, flags: {}, apiBase: null, generatedAt: "" };

/** Loads the runtime config once (falls back to a local default during `vite dev`). */
export async function loadRuntimeConfig(base = "./"): Promise<RuntimeConfig> {
  try {
    const r = await fetch(`${base}__factory/config.json`, { credentials: "omit", cache: "no-store" });
    if (!r.ok) return FALLBACK;
    return { ...FALLBACK, ...(await r.json()) as Partial<RuntimeConfig> };
  } catch { return FALLBACK; }
}

const Ctx = createContext<RuntimeConfig | null>(null);
/** Provide the runtime config to the app (renders `fallback` until loaded). */
export function FactoryApp({ children, fallback = null }: { children?: ReactNode; fallback?: ReactNode }) {
  const [cfg, setCfg] = useState<RuntimeConfig | null>(null);
  useEffect(() => { void loadRuntimeConfig().then(setCfg); }, []);
  return cfg ? <Ctx.Provider value={cfg}>{children}</Ctx.Provider> : <>{fallback}</>;
}
export function useRuntimeConfig(): RuntimeConfig { return useContext(Ctx) ?? FALLBACK; }
/** Signed-in user for PRIVATE apps (company SSO through the gateway); null for public apps. */
export function useUser() { return useRuntimeConfig().user; }
/** Feature flags (false unless set). */
export function useFlag(name: string): boolean { return useRuntimeConfig().flags[name] === true; }

/** Resolve a file shipped in `public/` relative to the app root (works under any base path). */
export function assetUrl(path: string): string { return new URL(path.replace(/^\//, ""), document.baseURI).toString(); }

// ---------------------------------------------------------------- logging
type Level = "debug" | "info" | "warn" | "error";
/** Structured console logger; never sends data anywhere (the sandbox has no network beyond the app's own origin). */
export const logger = (["debug", "info", "warn", "error"] as Level[]).reduce((acc, level) => {
  acc[level] = (message: string, fields: Record<string, unknown> = {}) => console[level === "debug" ? "log" : level](JSON.stringify({ level, message, ...fields, at: new Date().toISOString() }));
  return acc;
}, {} as Record<Level, (message: string, fields?: Record<string, unknown>) => void>);

// ---------------------------------------------------------------- API client
export class ApiError extends Error { constructor(readonly status: number, message: string, readonly body?: unknown) { super(message); } }
export type ApiClient = { get<T>(path: string): Promise<T>; post<T>(path: string, body: unknown): Promise<T> };
/**
 * JSON client with timeout and error normalisation. Base = `apiBase` from the runtime config (the factory runtime gateway for server apps /
 * approved connectors); static apps have none and calls throw. Never put credentials in the client: the gateway authenticates the app.
 */
export function createApiClient(base: string | null, timeoutMs = 15000): ApiClient {
  async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    if (!base) throw new ApiError(0, "This app has no API (static app)");
    const r = await fetch(new URL(path.replace(/^\//, ""), base.endsWith("/") ? base : `${base}/`), { method, credentials: "omit",
      headers: body === undefined ? undefined : { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) });
    const text = await r.text(); const data = text ? JSON.parse(text) : undefined;
    if (!r.ok) throw new ApiError(r.status, (data as { message?: string } | undefined)?.message ?? `HTTP ${r.status}`, data);
    return data as T;
  }
  return { get: (p) => call("GET", p), post: (p, b) => call("POST", p, b) };
}
export function useApi(): ApiClient { return createApiClient(useRuntimeConfig().apiBase); }

export const version = "1.0.0";
