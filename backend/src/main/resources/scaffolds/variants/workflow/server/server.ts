// Approval workflow (stage K): SUBMITTED → APPROVED | REJECTED with a history of every step and who did it. Approvers are listed in the
// APPROVERS secret (comma-separated usernames). Optional: on approval, call an approved connector (NOTIFY_CONNECTOR = "<key>/<path>") through
// the runtime gateway — the credential is added by the platform, this app never sees it. Runs only in the isolated runtime (ADR 0017).
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
const approvers = new Set((process.env.APPROVERS ?? "").split(",").map((s) => s.trim()).filter(Boolean));
let ready: Promise<unknown> | null = null;
const migrate = () => (ready ??= pool!.query(`CREATE TABLE IF NOT EXISTS requests (id SERIAL PRIMARY KEY, title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (amount >= 0), state TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (state IN ('SUBMITTED','APPROVED','REJECTED')),
  requested_by TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
  CREATE TABLE IF NOT EXISTS steps (id SERIAL PRIMARY KEY, request_id INT NOT NULL REFERENCES requests(id) ON DELETE CASCADE, action TEXT NOT NULL, actor TEXT, comment TEXT, at TIMESTAMPTZ NOT NULL DEFAULT now());`));
const userOf = (req: http.IncomingMessage): string => verifiedUser(req)?.username ?? "anonymous";

async function notify(requestId: number) {
  const target = process.env.NOTIFY_CONNECTOR; if (!target || !process.env.CONNECTOR_URL) return "none";
  const r = await fetch(`${process.env.CONNECTOR_URL}/${target}`, { method: "POST", headers: { "X-App-Token": process.env.APP_TOKEN ?? "", "Content-Type": "application/json" },
    body: JSON.stringify({ requestId, state: "APPROVED" }), signal: AbortSignal.timeout(15_000) }).catch(() => null);
  return r ? `HTTP ${r.status}` : "failed";
}

async function handle(req: http.IncomingMessage, path: string, body: Record<string, unknown> | null): Promise<[number, unknown]> {
  if (!pool) return [503, { error: "no database" }];
  await migrate(); const who = userOf(req);
  if (req.method === "GET" && path === "/api/requests") return [200, (await pool.query("SELECT * FROM requests ORDER BY created_at DESC LIMIT 500")).rows];
  if (req.method === "POST" && path === "/api/requests") {
    const title = String(body?.title ?? "").trim(); const amount = Number(body?.amount ?? 0);
    if (!title || title.length > 200 || !Number.isFinite(amount) || amount < 0) return [400, { error: "title 1–200 characters, amount ≥ 0" }];
    const r = await pool.query("INSERT INTO requests (title, amount, requested_by) VALUES ($1, $2, $3) RETURNING *", [title, amount, who]);
    await pool.query("INSERT INTO steps (request_id, action, actor) VALUES ($1, 'SUBMITTED', $2)", [r.rows[0].id, who]);
    return [201, r.rows[0]];
  }
  let m = path.match(/^\/api\/requests\/(\d+)\/decision$/);
  if (m && req.method === "POST") {
    if (!approvers.has(who)) return [403, { error: "only approvers (APPROVERS secret) can decide" }];
    const decision = body?.decision === "APPROVE" ? "APPROVED" : body?.decision === "REJECT" ? "REJECTED" : null;
    if (!decision) return [400, { error: "decision: APPROVE | REJECT" }];
    const r = await pool.query("UPDATE requests SET state = $2 WHERE id = $1 AND state = 'SUBMITTED' RETURNING *", [Number(m[1]), decision]);
    if (!r.rowCount) return [409, { error: "not waiting for a decision" }];
    const comment = String(body?.comment ?? "").slice(0, 500);
    await pool.query("INSERT INTO steps (request_id, action, actor, comment) VALUES ($1, $2, $3, $4)", [Number(m[1]), decision, who, comment]);
    if (decision === "APPROVED") await pool.query("INSERT INTO steps (request_id, action, actor, comment) VALUES ($1, 'NOTIFIED', 'system', $2)", [Number(m[1]), await notify(Number(m[1]))]);
    return [200, r.rows[0]];
  }
  m = path.match(/^\/api\/requests\/(\d+)\/history$/);
  if (m && req.method === "GET") return [200, (await pool.query("SELECT action, actor, comment, at FROM steps WHERE request_id = $1 ORDER BY at, id", [Number(m[1])])).rows];
  return [404, { error: "not found" }];
}

http.createServer(async (req, res) => {
  const send = (s: number, v: unknown) => { res.writeHead(s, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }); res.end(JSON.stringify(v)); };
  try {
    const path = new URL(req.url ?? "/", "http://app").pathname;
    if (path === "/health") return send(200, { ok: true });
    const chunks: Buffer[] = []; let size = 0;
    for await (const c of req) { size += (c as Buffer).length; if (size > 1_000_000) return send(413, { error: "too large" }); chunks.push(c as Buffer); }
    const text = Buffer.concat(chunks).toString("utf8");
    const [s, v] = await handle(req, path, text ? JSON.parse(text) as Record<string, unknown> : null);
    send(s, v);
  } catch (e) { console.error("request failed:", (e as Error).message); send(500, { error: "internal error" }); }
}).listen(8080, "0.0.0.0", () => console.log("workflow listening on :8080"));
