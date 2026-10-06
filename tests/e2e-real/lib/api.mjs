// @class: real-backend — a Node-side HTTP session that behaves like the browser client (cookie jar, CSRF token, same-origin Origin header).
// Used ONLY for fixtures, for reading back server state after a browser step, and for cleanup. Business assertions that matter to the user are made in the browser.
import { randomBytes } from "node:crypto";

export class Session {
  constructor(base, label = "session") { this.base = base; this.label = label; this.jar = new Map(); this.csrf = null; }
  #cookieHeader() { return [...this.jar].map(([k, v]) => `${k}=${v}`).join("; "); }
  #store(res) {
    for (const c of res.headers.getSetCookie?.() ?? []) { const [pair] = c.split(";"); const i = pair.indexOf("="); if (i > 0) { const v = pair.slice(i + 1); if (v === "" || /max-age=0/i.test(c)) this.jar.delete(pair.slice(0, i)); else this.jar.set(pair.slice(0, i), v); } }
  }
  async request(method, path, body, extra = {}) {
    const mutating = !["GET", "HEAD", "OPTIONS"].includes(method);
    if (mutating && !this.csrf) await this.refreshCsrf();
    const headers = { Accept: "application/json", Origin: this.base, Cookie: this.#cookieHeader(), ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...(mutating ? { "X-XSRF-TOKEN": this.csrf } : {}), ...(extra.headers ?? {}) };
    let res;
    try { res = await fetch(`${this.base}/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(extra.timeoutMs ?? 20_000), redirect: "manual" }); }
    catch (e) { return { status: 0, body: null, error: String(e?.message ?? e) }; }
    this.#store(res);
    const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: res.status, body: json, text, headers: res.headers };
  }
  async refreshCsrf() { const r = await this.request("GET", "/auth/csrf"); this.csrf = r.body?.token ?? null; return this.csrf; }
  get = (p, x) => this.request("GET", p, undefined, x);
  post = (p, b, x) => this.request("POST", p, b ?? {}, x);
  patch = (p, b, x) => this.request("PATCH", p, b, x);
  del = (p, x) => this.request("DELETE", p, undefined, x);
  async login(username, password) {
    const r = await this.post("/auth/login", { username, password });
    if (r.status !== 200 && r.status !== 204) throw new Error(`${this.label}: login refused (${r.status} ${r.body?.code ?? ""})`);
    this.csrf = null; // the token is bound to the session
    return (await this.get("/auth/me")).body;
  }
}

/** random per run, kept in memory only, never printed or written */
export const randomSecret = () => `Aa1!${randomBytes(12).toString("base64url")}`;
