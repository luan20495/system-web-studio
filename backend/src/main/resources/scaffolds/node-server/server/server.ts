// Server part of a factory server app (ADR 0017). Runs ONLY in the platform's isolated runtime container: non-root, read-only
// filesystem, no internet. It gets its own database (DATABASE_URL, a role that owns only this app's database) and may call approved
// connectors through the runtime gateway (CONNECTOR_URL + APP_TOKEN). Every public route must be declared in openapi.json:
// the gateway refuses anything else. The platform passes the signed-in user of a private app in X-Factory-User, signed (see verifiedUser).
import http from "node:http";
import { createHmac, timingSafeEqual } from "node:crypto";
import { Pool } from "pg";

/**
 * The signed-in member of a private app, as signed by the platform (X-Factory-Signature: t=<epoch s>,sig=hex HMAC-SHA256(APP_TOKEN, t + "." + user)).
 * Unsigned, wrongly signed or stale (> 5 min) values are ignored, so nothing but the platform can claim to be a user.
 */
function verifiedUser(req: http.IncomingMessage): { id?: string; username?: string; displayName?: string; email?: string } | null {
  const raw = req.headers["x-factory-user"]; const sig = req.headers["x-factory-signature"]; const key = process.env.APP_TOKEN;
  if (typeof raw !== "string" || typeof sig !== "string" || !key) return null;
  const m = /^t=(\d+),sig=([0-9a-f]{64})$/.exec(sig);
  if (!m || Math.abs(Date.now() / 1000 - Number(m[1])) > 300) return null;
  const want = createHmac("sha256", key).update(`${m[1]}.${raw}`).digest();
  if (!timingSafeEqual(want, Buffer.from(m[2], "hex"))) return null;
  try { return JSON.parse(raw) as { id?: string; username?: string; displayName?: string; email?: string }; } catch { return null; }
}

const pool = process.env.DATABASE_URL ? new Pool({ connectionString: process.env.DATABASE_URL, max: 5, idleTimeoutMillis: 30_000 }) : null;
let ready: Promise<void> | null = null;
function migrate(): Promise<void> {
  ready ??= pool ? pool.query("CREATE TABLE IF NOT EXISTS items (id SERIAL PRIMARY KEY, title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200), created_at TIMESTAMPTZ NOT NULL DEFAULT now())").then(() => undefined) : Promise.resolve();
  return ready;
}

type Handler = (req: http.IncomingMessage, body: unknown, params: Record<string, string>) => Promise<[number, unknown]>;
const routes: { method: string; pattern: RegExp; keys: string[]; handler: Handler }[] = [];
function route(method: string, path: string, handler: Handler) {
  const keys: string[] = [];
  const pattern = new RegExp("^" + path.replace(/\{(\w+)\}/g, (_, k) => { keys.push(k); return "([^/]+)"; }) + "$");
  routes.push({ method, pattern, keys, handler });
}

route("GET", "/api/items", async () => {
  if (!pool) return [503, { error: "no database" }];
  await migrate();
  const r = await pool.query("SELECT id, title, created_at FROM items ORDER BY id DESC LIMIT 100");
  return [200, r.rows];
});
route("POST", "/api/items", async (_req, body) => {
  if (!pool) return [503, { error: "no database" }];
  const title = typeof (body as { title?: unknown })?.title === "string" ? (body as { title: string }).title.trim() : "";
  if (!title || title.length > 200) return [400, { error: "title: 1–200 characters" }];
  await migrate();
  const r = await pool.query("INSERT INTO items (title) VALUES ($1) RETURNING id, title, created_at", [title]);
  return [201, r.rows[0]];
});
route("DELETE", "/api/items/{id}", async (_req, _b, p) => {
  if (!pool) return [503, { error: "no database" }];
  await migrate();
  await pool.query("DELETE FROM items WHERE id = $1", [Number(p.id) || 0]);
  return [204, null];
});
route("GET", "/api/me", async (req) => [200, verifiedUser(req) ?? { anonymous: true }]);

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://app");
  const send = (status: number, value: unknown) => {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
    res.end(status === 204 ? undefined : JSON.stringify(value));
  };
  try {
    if (url.pathname === "/health") return send(200, { ok: true });
    const r = routes.find((x) => x.method === req.method && x.pattern.test(url.pathname));
    if (!r) return send(404, { error: "not found" });
    const chunks: Buffer[] = []; let size = 0;
    for await (const c of req) { size += (c as Buffer).length; if (size > 1_000_000) return send(413, { error: "too large" }); chunks.push(c as Buffer); }
    const text = Buffer.concat(chunks).toString("utf8");
    const body = text ? JSON.parse(text) : null;
    const m = url.pathname.match(r.pattern)!;
    const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
    const [status, value] = await r.handler(req, body, params);
    send(status, value);
  } catch (e) {
    console.error("request failed:", (e as Error).message);
    send(500, { error: "internal error" });
  }
});
server.listen(8080, "0.0.0.0", () => console.log("server app listening on :8080"));
for (const sig of ["SIGTERM", "SIGINT"]) process.on(sig, () => { server.close(); pool?.end().finally(() => process.exit(0)); });
