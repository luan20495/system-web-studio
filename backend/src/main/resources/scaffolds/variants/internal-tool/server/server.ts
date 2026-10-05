// Internal tool (stage K): records with status, who created/changed them, change history. Runs only in the isolated runtime (ADR 0017):
// its own database (DATABASE_URL), no internet. Publish it PRIVATE so only members of the app can use it; the platform passes the member in
// X-Factory-User. Every public route must be declared in openapi.json.
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

const pool = process.env.DATABASE_URL ? new Pool({ connectionString: process.env.DATABASE_URL, max: 5 }) : null;
let ready: Promise<unknown> | null = null;
const migrate = () => (ready ??= pool!.query(`CREATE TABLE IF NOT EXISTS records (id SERIAL PRIMARY KEY, title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','DONE')), note TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 2000),
  created_by TEXT, updated_by TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
  CREATE TABLE IF NOT EXISTS record_history (id SERIAL PRIMARY KEY, record_id INT NOT NULL, actor TEXT, change JSONB NOT NULL, at TIMESTAMPTZ NOT NULL DEFAULT now());`));

const userOf = verifiedUser;
const STATUSES = ["OPEN", "IN_PROGRESS", "DONE"];

async function handle(req: http.IncomingMessage, path: string, body: Record<string, unknown> | null): Promise<[number, unknown]> {
  if (!pool) return [503, { error: "no database" }];
  await migrate();
  const who = userOf(req)?.username ?? "anonymous";
  if (req.method === "GET" && path === "/api/me") return [200, userOf(req) ?? { anonymous: true }];
  if (req.method === "GET" && path === "/api/records") return [200, (await pool.query("SELECT * FROM records ORDER BY updated_at DESC LIMIT 500")).rows];
  if (req.method === "POST" && path === "/api/records") {
    const title = String(body?.title ?? "").trim(); const note = String(body?.note ?? "").slice(0, 2000);
    if (!title || title.length > 200) return [400, { error: "title: 1–200 characters" }];
    const r = await pool.query("INSERT INTO records (title, note, created_by, updated_by) VALUES ($1, $2, $3, $3) RETURNING *", [title, note, who]);
    await pool.query("INSERT INTO record_history (record_id, actor, change) VALUES ($1, $2, $3)", [r.rows[0].id, who, { created: { title } }]);
    return [201, r.rows[0]];
  }
  const m = path.match(/^\/api\/records\/(\d+)$/);
  if (m && req.method === "PATCH") {
    const status = body?.status === undefined ? null : String(body.status);
    if (status !== null && !STATUSES.includes(status)) return [400, { error: "status: OPEN | IN_PROGRESS | DONE" }];
    const r = await pool.query("UPDATE records SET status = coalesce($2, status), note = coalesce($3, note), updated_by = $4, updated_at = now() WHERE id = $1 RETURNING *",
      [Number(m[1]), status, body?.note === undefined ? null : String(body.note).slice(0, 2000), who]);
    if (!r.rowCount) return [404, { error: "not found" }];
    await pool.query("INSERT INTO record_history (record_id, actor, change) VALUES ($1, $2, $3)", [Number(m[1]), who, body ?? {}]);
    return [200, r.rows[0]];
  }
  if (m && req.method === "DELETE") { await pool.query("DELETE FROM records WHERE id = $1", [Number(m[1])]); return [204, null]; }
  return [404, { error: "not found" }];
}

http.createServer(async (req, res) => {
  const send = (s: number, v: unknown) => { res.writeHead(s, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }); res.end(s === 204 ? undefined : JSON.stringify(v)); };
  try {
    const path = new URL(req.url ?? "/", "http://app").pathname;
    if (path === "/health") return send(200, { ok: true });
    const chunks: Buffer[] = []; let size = 0;
    for await (const c of req) { size += (c as Buffer).length; if (size > 1_000_000) return send(413, { error: "too large" }); chunks.push(c as Buffer); }
    const text = Buffer.concat(chunks).toString("utf8");
    const [s, v] = await handle(req, path, text ? JSON.parse(text) as Record<string, unknown> : null);
    send(s, v);
  } catch (e) { console.error("request failed:", (e as Error).message); send(500, { error: "internal error" }); }
}).listen(8080, "0.0.0.0", () => console.log("internal tool listening on :8080"));
