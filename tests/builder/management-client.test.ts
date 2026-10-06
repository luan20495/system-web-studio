// @class: mock — fetch is stubbed; proves what the client SENDS and how it reads C3's responses/errors, NOT backend behaviour (the routes are unverified against a running backend)
/**
 * api.dataManagement against a stubbed `fetch`, written from docs/parallel/c3/MANAGEMENT_API.md @ e606465 and the controller code it describes.
 * It cannot prove that the real routes answer like this: that needs a backend (tests/e2e-real E2E-06…09).
 */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, resetCsrf } from "../../packages/api-client/src/core";
import { api } from "../../packages/api-client/src/api";

type Seen = { url: string; method: string; headers: Record<string, string>; body?: string };
function stub(handler: (u: string, i: RequestInit) => { status: number; body?: unknown; headers?: Record<string, string> } | Error) {
  const seen: Seen[] = [];
  (globalThis as { fetch: unknown }).fetch = async (u: string, i: RequestInit = {}) => {
    seen.push({ url: String(u), method: String(i.method ?? "GET"), headers: { ...(i.headers as Record<string, string> ?? {}) }, body: i.body as string | undefined });
    if (String(u).endsWith("/auth/csrf")) return new Response(JSON.stringify({ token: "t1" }), { status: 200 });
    const r = handler(String(u), i);
    if (r instanceof Error) throw r;
    return new Response(r.status === 204 ? null : JSON.stringify(r.body ?? {}), { status: r.status, headers: r.headers });
  };
  resetCsrf();
  return seen;
}
const W = "/api/v1/workspaces/w1";
const DS = { id: "11111111-1111-1111-1111-111111111111", workspaceId: "w1", name: "billing-db", type: "postgres", config: { host: "db.example.com", port: "5432" }, hasCredential: true, status: "ACTIVE", version: 3, createdBy: null, createdAt: "t", updatedAt: "t" };
const SECRET = "hunter2-very-secret";

test("reads: exact method + path, no body, no CSRF header on GET", async () => {
  const seen = stub((u) => ({ status: 200, body: u.endsWith("/connectors") || u.endsWith("data-sources") || u.endsWith("data-bindings") ? { items: [] } : DS }));
  await api.dataManagement.connectors("w1"); await api.dataManagement.list("w1"); await api.dataManagement.get("w1", DS.id); await api.dataManagement.listBindings("w1", "p1");
  assert.deepEqual(seen.map((s) => `${s.method} ${s.url}`), [`GET ${W}/data-sources/connectors`, `GET ${W}/data-sources`, `GET ${W}/data-sources/${DS.id}`, `GET ${W}/projects/p1/data-bindings`]);
  for (const s of seen) { assert.equal(s.body, undefined); assert.equal(s.headers["X-XSRF-TOKEN"], undefined); }
});

test("create: POST with CSRF, ONLY contract fields, 201 body parsed; identity fields can never be sent", async () => {
  const seen = stub(() => ({ status: 201, body: DS }));
  const created = await api.dataManagement.create("w1", { name: "billing-db", type: "postgres", config: { host: "db.example.com", port: 5432, writable: true }, credential: { username: "u", password: SECRET },
    // @ts-expect-error not part of the request type: even if a caller forced them in, the client must not forward them
    tenantId: "t", workspaceId: "w", credentialRef: "r", id: "i", createdBy: "c" });
  assert.equal(created.id, DS.id);
  const post = seen.find((s) => s.method === "POST")!;
  assert.equal(post.url, `${W}/data-sources`); assert.equal(post.headers["X-XSRF-TOKEN"], "t1");
  assert.deepEqual(Object.keys(JSON.parse(post.body!)).sort(), ["config", "credential", "name", "type"]);
  assert.doesNotMatch(post.body!, /tenantId|workspaceId|credentialRef|createdBy/);
});

test("create: an invalid name is refused BEFORE any request, with fixed text that cannot carry the credential", async () => {
  const seen = stub(() => ({ status: 201, body: DS }));
  await assert.rejects(api.dataManagement.create("w1", { name: "-bad name", type: "postgres", credential: { password: SECRET } }), (e: unknown) => e instanceof ApiError && e.status === 400 && e.code === "INVALID_PARAMS" && !e.message.includes(SECRET));
  await assert.rejects(api.dataManagement.create("w1", { name: "x".repeat(81), type: "postgres" }), ApiError);
  assert.equal(seen.length, 0);
});

test("update: PATCH carries only the given fields; an empty change is refused locally; DELETE answers 204", async () => {
  const seen = stub((u, i) => (i.method === "DELETE" ? { status: 204 } : { status: 200, body: { ...DS, status: "DISABLED" } }));
  const u = await api.dataManagement.update("w1", DS.id, { status: "DISABLED" });
  assert.equal(u.status, "DISABLED");
  const patch = seen.find((s) => s.method === "PATCH")!; assert.equal(patch.url, `${W}/data-sources/${DS.id}`); assert.deepEqual(JSON.parse(patch.body!), { status: "DISABLED" });
  await assert.rejects(api.dataManagement.update("w1", DS.id, {}), (e: unknown) => e instanceof ApiError && e.code === "INVALID_PARAMS");
  assert.equal(await api.dataManagement.remove("w1", DS.id), undefined);
  assert.ok(seen.some((s) => s.method === "DELETE" && s.url === `${W}/data-sources/${DS.id}`));
});

test("delete while bound: 409 CONFLICT surfaces as ApiError(409, CONFLICT) so the UI can say 'unbind first'", async () => {
  stub(() => ({ status: 409, body: { code: "CONFLICT", message: "data source is in use", requestId: "r1" } }));
  await assert.rejects(api.dataManagement.remove("w1", DS.id), (e: unknown) => e instanceof ApiError && e.status === 409 && e.code === "CONFLICT" && e.requestId === "r1");
});

test("credential: PUT body is exactly {credential}; the only answer is metadata (key NAMES), and no error ever echoes the secret", async () => {
  const meta = { configured: true, type: "postgres", keys: ["username", "password"], updatedAt: "t", updatedBy: null };
  const seen = stub((u, i) => (i.method === "DELETE" ? { status: 204 } : { status: 200, body: meta }));
  const r = await api.dataManagement.setCredential("w1", DS.id, { username: "u", password: SECRET });
  assert.deepEqual(r, meta); assert.doesNotMatch(JSON.stringify(r), new RegExp(SECRET));
  const put = seen.find((s) => s.method === "PUT")!; assert.equal(put.url, `${W}/data-sources/${DS.id}/credential`); assert.deepEqual(JSON.parse(put.body!), { credential: { username: "u", password: SECRET } });
  assert.deepEqual(await api.dataManagement.credential("w1", DS.id), meta);
  assert.equal(await api.dataManagement.removeCredential("w1", DS.id), undefined);
  stub(() => ({ status: 400, body: { code: "INVALID_CREDENTIAL", message: "credential has an invalid shape", requestId: "r2" } }));
  await assert.rejects(api.dataManagement.setCredential("w1", DS.id, { username: "u", password: SECRET }), (e: unknown) => e instanceof ApiError && e.code === "INVALID_CREDENTIAL" && !JSON.stringify([e.message, e.code, e.details]).includes(SECRET));
  await assert.rejects(api.dataManagement.setCredential("w1", DS.id, {}), (e: unknown) => e instanceof ApiError && e.code === "INVALID_PARAMS");
});

test("test connection: POST with no body; HTTP 200 with ok:false RESOLVES (a failed test is a result), warnings are passed through verbatim", async () => {
  let n = 0;
  const seen = stub(() => ({ status: 200, body: [{ ok: true, latencyMs: 12, warnings: ["the database role can write to 3 table(s); use a SELECT-only role"] }, { ok: false, code: "AUTH_REJECTED", message: "x" }][n++] }));
  const ok = await api.dataManagement.testConnection("w1", DS.id);
  assert.equal(ok.ok, true); assert.deepEqual(ok.ok && ok.warnings, ["the database role can write to 3 table(s); use a SELECT-only role"]);
  const bad = await api.dataManagement.testConnection("w1", DS.id);
  assert.equal(bad.ok, false); assert.equal(!bad.ok && bad.code, "AUTH_REJECTED");
  const p = seen.filter((s) => s.method === "POST"); assert.equal(p.length, 2); assert.equal(p[0].url, `${W}/data-sources/${DS.id}/test`); assert.equal(p[0].body, undefined);
});

test("test connection on a disabled source is an ERROR (409 DISABLED), unlike a failed test", async () => {
  stub(() => ({ status: 409, body: { code: "DISABLED", message: "data source is disabled" } }));
  await assert.rejects(api.dataManagement.testConnection("w1", DS.id), (e: unknown) => e instanceof ApiError && e.status === 409 && e.code === "DISABLED");
});

test("bindings: PUT/DELETE …/data-bindings/{MODE}/{slot}; mode is normalised to upper case; bad mode/slot refused locally", async () => {
  const seen = stub((u, i) => (i.method === "DELETE" ? { status: 204 } : { status: 200, body: { mode: u.includes("/LIVE/") ? "LIVE" : "TEST", slotId: "erp-db", dataSourceId: DS.id, updatedAt: null } }));
  const t = await api.dataManagement.bind("w1", "p1", "test", "erp-db", DS.id); assert.equal(t.mode, "TEST");
  const l = await api.dataManagement.bind("w1", "p1", "LIVE", "erp-db", DS.id); assert.equal(l.mode, "LIVE");
  await api.dataManagement.unbind("w1", "p1", "live", "erp-db");
  const calls = seen.filter((s) => !s.url.endsWith("/auth/csrf"));
  assert.deepEqual(calls.map((s) => `${s.method} ${s.url}`), [`PUT ${W}/projects/p1/data-bindings/TEST/erp-db`, `PUT ${W}/projects/p1/data-bindings/LIVE/erp-db`, `DELETE ${W}/projects/p1/data-bindings/LIVE/erp-db`]);
  assert.deepEqual(JSON.parse(calls[0].body!), { dataSourceId: DS.id });
  const before = seen.length;
  await assert.rejects(api.dataManagement.bind("w1", "p1", "STAGING", "erp-db", DS.id), (e: unknown) => e instanceof ApiError && e.code === "INVALID_PARAMS");
  await assert.rejects(api.dataManagement.bind("w1", "p1", "TEST", "../etc", DS.id), ApiError);
  await assert.rejects(api.dataManagement.unbind("w1", "p1", "TEST", "a b"), ApiError);
  assert.equal(seen.length, before);
});

test("ids are percent-encoded in the path (no path injection through a data source id)", async () => {
  const seen = stub(() => ({ status: 404, body: { code: "NOT_FOUND", message: "not found" } }));
  await assert.rejects(api.dataManagement.get("w1", "../../admin?x=1"), ApiError);
  assert.equal(seen[0].url, `${W}/data-sources/..%2F..%2Fadmin%3Fx%3D1`);
});

test("error statuses of the contract table reach the UI as ApiError(status, code); Retry-After is carried in the message", async () => {
  const cases: [number, string][] = [[400, "INVALID_PARAMS"], [400, "INVALID_CONFIG"], [403, "PERMISSION_DENIED"], [404, "NOT_FOUND"], [409, "CONFLICT"], [422, "UNSUPPORTED_TYPE"], [501, "NOT_IMPLEMENTED"], [500, "INTERNAL"]];
  for (const [status, code] of cases) {
    stub(() => ({ status, body: { code, message: `fixed text for ${code}`, requestId: "rid" } }));
    await assert.rejects(api.dataManagement.list("w1"), (e: unknown) => e instanceof ApiError && e.status === status && e.code === code && e.requestId === "rid");
  }
  stub(() => ({ status: 429, body: { code: "RATE_LIMITED", message: "slow down" }, headers: { "Retry-After": "30" } }));
  await assert.rejects(api.dataManagement.create("w1", { name: "a", type: "postgres" }), (e: unknown) => e instanceof ApiError && e.status === 429 && /30s/.test(e.message));
});

test("404 of the workspace/project proof (platform ApiError shape) and 404 NOT_FOUND of a data source are both just 'not there'", async () => {
  for (const code of ["WORKSPACE_NOT_FOUND", "PROJECT_NOT_FOUND", "NOT_FOUND"]) {
    stub(() => ({ status: 404, body: { code, message: "x" } }));
    await assert.rejects(api.dataManagement.listBindings("w1", "p1"), (e: unknown) => e instanceof ApiError && e.status === 404 && e.code === code);
  }
  stub(() => ({ status: 404, body: {} }));
  await assert.rejects(api.dataManagement.list("w1"), (e: unknown) => e instanceof ApiError && e.status === 404 && e.code === "HTTP_404");   // controller not mounted: no domain code
});

test("network failure and client timeout are distinct and carry status 0 (the outcome of a write is unknown either way)", async () => {
  stub(() => new TypeError("fetch failed"));
  await assert.rejects(api.dataManagement.create("w1", { name: "a", type: "postgres" }), (e: unknown) => e instanceof ApiError && e.status === 0 && e.code === "NETWORK");
  stub(() => new DOMException("t", "TimeoutError"));
  await assert.rejects(api.dataManagement.testConnection("w1", DS.id), (e: unknown) => e instanceof ApiError && e.status === 0 && e.code === "TIMEOUT");
});

test("401 outside /auth/* triggers the one expired-session handler", async () => {
  const { onUnauthorized } = await import("../../packages/api-client/src/core");
  let hit = ""; onUnauthorized((c) => { hit = c; });
  stub(() => ({ status: 401, body: { code: "AUTHENTICATION_REQUIRED", message: "x" } }));
  await assert.rejects(api.dataManagement.list("w1"), (e: unknown) => e instanceof ApiError && e.status === 401);
  onUnauthorized(null);
  assert.equal(hit, "AUTHENTICATION_REQUIRED");
});
