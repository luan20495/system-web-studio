// @class: mock — fetch or backend is stubbed; proves what the client sends/reads, NOT backend behaviour
/**
 * the api-client against a stubbed `fetch`. It proves what the client SENDS and how it reads the two error shapes of runtime-api.md.
 * It does not talk to a backend and says nothing about the backend's behaviour.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, call, normaliseError, resetCsrf } from "../../packages/api-client/src/core";
import { api, newIdempotencyKey } from "../../packages/api-client/src/api";

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

test("normaliseError reads the standard body AND the action-result envelope (error.code, retryable)", () => {
  assert.deepEqual(normaliseError({ code: "X", message: "m", retryable: false }), { code: "X", message: "m", requestId: undefined, details: undefined, retryable: false });
  const n = normaliseError({ status: "FAILED", error: { code: "IDEMPOTENCY_OUTCOME_UNKNOWN", message: "u", retryable: false, details: { a: 1 } } });
  assert.equal(n?.code, "IDEMPOTENCY_OUTCOME_UNKNOWN"); assert.equal(n?.retryable, false); assert.deepEqual(n?.details, { a: 1 });
  assert.equal(normaliseError(null), null);
});

test("executeAction: POST to the frozen route, strict body, CSRF header; a FAILED envelope on 409 becomes ApiError(code from error.code)", async () => {
  const seen = stub(() => ({ status: 409, body: { status: "FAILED", actionId: "a1", mode: "LIVE", error: { code: "IDEMPOTENCY_OUTCOME_UNKNOWN", message: "unknown", retryable: false } } }));
  await assert.rejects(api.appRuntime.executeAction("w1", "p1", "a1", { mode: "TEST", idempotencyKey: "k:1", inputs: { x: 1 } }), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 409); assert.equal(e.code, "IDEMPOTENCY_OUTCOME_UNKNOWN"); assert.equal(e.retryable, false);
    return true;
  });
  const post = seen.find((s) => s.method === "POST")!;
  assert.equal(post.url, "/api/v1/workspaces/w1/projects/p1/app-runtime/actions/a1/execute");
  assert.equal(post.headers["X-XSRF-TOKEN"], "t1");
  assert.deepEqual(Object.keys(JSON.parse(post.body!)).sort(), ["idempotencyKey", "inputs", "mode"]);
  assert.equal(post.headers["Idempotency-Key"], undefined); // the contract carries the key in the body only
});

test("no tenantId / userId / dataSourceId is ever sent by the runtime calls", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await api.appRuntime.runQuery("w", "p", "q", { mode: "TEST", params: { a: 1 }, page: { limit: 10, offset: 0 } });
  await api.appRuntime.startWorkflow("w", "p", "wf", { mode: "TEST", idempotencyKey: "k" });
  for (const s of seen.filter((x) => x.body)) assert.doesNotMatch(s.body!, /tenantId|userId|dataSourceId|"sql"|"url"/);
  assert.ok(seen.some((s) => s.url.endsWith("/queries/q/run")));
  assert.ok(seen.some((s) => s.url.endsWith("/workflows/wf/runs")));
});

test("idempotency keys: invalid ones are refused BEFORE any request; workflow start requires one; generated keys match the contract pattern", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await assert.rejects(api.appRuntime.executeAction("w", "p", "a", { idempotencyKey: "bad key!" }), (e: unknown) => e instanceof ApiError && e.code === "IDEMPOTENCY_KEY_INVALID" && e.status === 400);
  await assert.rejects(api.appRuntime.startWorkflow("w", "p", "wf", { mode: "TEST" } as never), (e: unknown) => e instanceof ApiError && e.code === "IDEMPOTENCY_KEY_INVALID");
  await assert.rejects(api.appRuntime.startWorkflow("w", "p", "wf", { idempotencyKey: "x".repeat(129) }), ApiError);
  assert.equal(seen.length, 0);
  for (let i = 0; i < 5; i++) assert.match(newIdempotencyKey("test"), /^[A-Za-z0-9._:-]{1,128}$/);
  assert.notEqual(newIdempotencyKey(), newIdempotencyKey());
});

test("path segments are encoded (an id can never escape the route)", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await api.appRuntime.workflowRun("w", "p", "../x?y");
  assert.match(seen[0].url, /workflow-runs\/\.\.%2Fx%3Fy$/);
});

test("network failure and timeout are different codes, both status 0", async () => {
  stub(() => new TypeError("fetch failed"));
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && e.status === 0 && e.code === "NETWORK");
  stub(() => new DOMException("t", "TimeoutError"));
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && e.status === 0 && e.code === "TIMEOUT");
});

test("429 appends Retry-After; 404 without a body keeps the HTTP_404 code (= route/flag not mounted)", async () => {
  stub(() => ({ status: 429, body: { code: "RATE_LIMITED", message: "Chậm lại." }, headers: { "Retry-After": "7" } }));
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && /7s/.test(e.message) && e.code === "RATE_LIMITED");
  (globalThis as { fetch: unknown }).fetch = async () => new Response("<html>", { status: 404 });
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && e.code === "HTTP_404");
});
