// C6 FINAL RC QA — shared helpers for the final-* scripts (API session with CSRF, product-route fixtures, recorder). Never edits production code,
// never prints a secret, never uses SQL or a test hook: accounts/tenants are created through the product routes with the prefix `c6f-` and disabled at the end.
// Evidence class of every row: HARNESS | REAL_STACK | REAL_BACKEND_E2E | MANUAL  (these scripts talk to the real c0rc API => REAL_BACKEND_E2E; a
// check that only reads the UI is REAL_STACK; anything judged by eye is MANUAL; a fake transport would be HARNESS).
import { randomBytes, randomUUID } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
export const API = (process.env.API ?? process.env.FINAL_API ?? "").replace(/\/$/, "");
export const OUT = process.env.OUT ?? process.env.FINAL_OUT ?? ".";
export const tag = randomUUID().slice(0, 6);
export const pw = () => randomBytes(15).toString("base64url") + "aA1!";
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const st = (r) => `${r.status}${r.json?.code ? " " + r.json.code : r.json?.error ? " " + r.json.error : ""}`;
export class S {
  constructor(base = API) { this.base = base; this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) {
    const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (this.csrf && !["GET", "HEAD", "OPTIONS"].includes(method) && !("x-xsrf-token" in h) && !("noCsrf" in h)) h["x-xsrf-token"] = this.csrf;
    delete h.noCsrf; if (body !== undefined) h["content-type"] = "application/json";
    const t0 = Date.now(); const res = await fetch(this.base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
    return { status: res.status, json, text, headers: res.headers, ms: Date.now() - t0 };
  }
  async login(username, password) { this.jar.clear(); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; const r = await this.call("POST", "/api/v1/auth/login", { username, password }); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; return r; }
}
export const anon = () => new S();
// provision a user through the product route and activate with the returned token; returns a logged-in session
export async function mkUser(as, tenantId, name, o, label = name) {
  const r = await as.call("POST", `/api/v1/admin/tenants/${tenantId}/users`, { username: name, displayName: o.displayName ?? name, ...o });
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${label}: ${st(r)}`);
  const p = pw(); const a0 = new S(); a0.csrf = (await a0.call("GET", "/api/v1/auth/csrf")).json?.token;
  const a = await a0.call("POST", "/api/v1/auth/activation/complete", { token: r.json.token, password: p }); if (a.status !== 200) throw new Error(`activate ${label}: ${st(a)}`);
  const s = new S(); const l = await s.login(name, p); if (l.status !== 200) throw new Error(`login ${label}: ${st(l)}`);
  return { s, name, id: r.json.userId, p };
}
export async function superAdmin() { const s = new S(); const r = await s.login(process.env.SA_USER, process.env.SA_PASSWORD); if (r.status !== 200) throw new Error("super admin login " + st(r)); return s; }
// recorder: rec(id, area, desc, expected, actual, ok, {cls, note, evidence}) ; ok===null => BLOCKED ; result strings PASS FAIL BLOCKED
export function recorder(name, defaultCls = "REAL_BACKEND_E2E") {
  const rows = []; const rec = (id, area, desc, expected, actual, ok, o = {}) => {
    const result = ok === null ? "BLOCKED" : ok ? "PASS" : "FAIL"; rows.push({ id, area, desc, expected: String(expected), actual: String(actual), result, cls: o.cls ?? defaultCls, note: o.note ?? "", owner: o.owner ?? "", evidence: o.evidence ?? "" });
    console.log(`${result} ${id} [${area}] ${desc} -> ${String(actual).slice(0, 120)}`); return ok;
  };
  const save = () => { mkdirSync(OUT, { recursive: true }); const failed = rows.filter((r) => r.result === "FAIL").length;
    writeFileSync(`${OUT}/${name}.json`, JSON.stringify({ api: API, tag, sha: process.env.FINAL_SHA_PRODUCT, total: rows.length, failed, blocked: rows.filter((r) => r.result === "BLOCKED").length, rows }, null, 1));
    const esc = (v) => String(v ?? "").replace(/[\t\r\n]+/g, " "); writeFileSync(`${OUT}/${name}.tsv`, ["id\tarea\tdescription\texpected\tactual\tresult\tclass\towner\tnote\tevidence", ...rows.map((r) => [r.id, r.area, r.desc, r.expected, r.actual, r.result, r.cls, r.owner, r.note, r.evidence].map(esc).join("\t"))].join("\n") + "\n");
    console.log(`${name}: total=${rows.length} pass=${rows.filter((r) => r.result === "PASS").length} fail=${failed} blocked=${rows.filter((r) => r.result === "BLOCKED").length}`); return failed; };
  return { rows, rec, save };
}
