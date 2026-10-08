// @class: mock — fetch or backend is stubbed; proves what the client sends/reads, NOT backend behaviour
/** M-074 / M-075 / M-092 at the client: request id from the RESPONSE header, no leaked parser text, no 'backend'/'CSRF' wording, stream CSRF retry only before the first stream byte. */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, call, resetCsrf, stream } from "../../packages/api-client/src/core";
import { errorText } from "../../packages/api-client/src/errorText";

type Reply = { status: number; body?: unknown; raw?: string; headers?: Record<string, string>; sse?: string[] } | Error;
function stub(handler: (u: string, i: RequestInit, n: number) => Reply) {
  const seen: Array<{ url: string; method: string; token?: string }> = [];
  let csrfCount = 0;
  (globalThis as { fetch: unknown }).fetch = async (u: string, i: RequestInit = {}) => {
    const h = (i.headers ?? {}) as Record<string, string>;
    if (String(u).endsWith("/auth/csrf")) { csrfCount++; return new Response(JSON.stringify({ token: `t${csrfCount}` }), { status: 200 }); }
    seen.push({ url: String(u), method: String(i.method ?? "GET"), token: h["X-XSRF-TOKEN"] });
    const r = handler(String(u), i, seen.length);
    if (r instanceof Error) throw r;
    if (r.sse) return new Response(new ReadableStream({ start(c) { for (const b of r.sse!) c.enqueue(new TextEncoder().encode(b)); c.close(); } }), { status: r.status, headers: { "Content-Type": "text/event-stream", ...(r.headers ?? {}) } });
    return new Response(r.raw ?? (r.status === 204 ? null : JSON.stringify(r.body ?? {})), { status: r.status, headers: r.headers });
  };
  resetCsrf();
  return seen;
}
const handlers = { onStart: () => {}, onDelta: () => {}, onStatus: () => {} };

test("request id: the X-Request-Id RESPONSE header wins, the JSON body id is the fallback, a bare 502 still carries the header", async () => {
  stub(() => ({ status: 500, body: { code: "INTERNAL_ERROR", message: "Unexpected server error", requestId: "body-id" }, headers: { "X-Request-Id": "hdr-id" } }));
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && e.requestId === "hdr-id");
  stub(() => ({ status: 500, body: { code: "INTERNAL_ERROR", message: "m", requestId: "body-id" } }));
  await assert.rejects(call("/x"), (e: unknown) => e instanceof ApiError && e.requestId === "body-id");
  stub(() => ({ status: 502, raw: "<html>Bad gateway</html>", headers: { "X-Request-Id": "edge-1" } }));
  await assert.rejects(call("/x"), (e: unknown) => { assert.ok(e instanceof ApiError); assert.equal(e.requestId, "edge-1"); assert.equal(e.code, "HTTP_502"); assert.match(errorText(e), /Mã tham chiếu: edge-1/); return true; });
});

test("a 200 whose body is not JSON (a proxy's HTML page) is a BAD_RESPONSE, never 'Unexpected token <'", async () => {
  stub(() => ({ status: 200, raw: "<!DOCTYPE html><html></html>", headers: { "X-Request-Id": "r1" } }));
  await assert.rejects(call("/x"), (e: unknown) => { assert.ok(e instanceof ApiError); assert.equal(e.code, "BAD_RESPONSE"); assert.doesNotMatch(e.message, /Unexpected|token|JSON/i); assert.equal(e.requestId, "r1"); return true; });
});

test("client-side messages never say 'backend' or 'CSRF token'", async () => {
  stub(() => new TypeError("Failed to fetch"));
  await assert.rejects(call("/x"), (e: unknown) => { assert.ok(e instanceof ApiError); assert.doesNotMatch(e.message, /backend/i); assert.equal(errorText(e).includes("backend"), false); return true; });
  // the csrf endpoint refusing
  (globalThis as { fetch: unknown }).fetch = async () => new Response("{}", { status: 503 });
  resetCsrf();
  await assert.rejects(call("/x", { method: "POST", body: "{}" }), (e: unknown) => { assert.ok(e instanceof ApiError); assert.doesNotMatch(e.message, /csrf|token/i); assert.doesNotMatch(errorText(e), /csrf|token/i); return true; });
});

test("429 with Retry-After: the sentence is Vietnamese and carries the seconds", async () => {
  stub(() => ({ status: 429, body: { code: "RATE_LIMITED", message: "Too many requests" }, headers: { "Retry-After": "7" } }));
  await assert.rejects(call("/x"), (e: unknown) => { assert.ok(e instanceof ApiError); assert.equal(e.retryAfterSeconds, 7); assert.match(errorText(e), /Thử lại sau 7 giây/); return true; });
});

test("stream(): CSRF_INVALID before any stream byte is retried ONCE with a fresh token", async () => {
  const seen = stub((_u, _i, n) => n === 1
    ? { status: 403, body: { code: "CSRF_INVALID", message: "Missing or invalid CSRF token" } }
    : { status: 200, sse: ['event: result\ndata: {"ok":true}\n\n'] });
  const r = await stream<{ ok: boolean }>("/ai/x", { a: 1 }, handlers);
  assert.deepEqual(r, { ok: true });
  assert.equal(seen.length, 2);
  assert.notEqual(seen[0].token, seen[1].token);
});

test("stream(): a second CSRF_INVALID is NOT retried again (one retry, then the error surfaces)", async () => {
  const seen = stub(() => ({ status: 403, body: { code: "CSRF_INVALID", message: "x" } }));
  await assert.rejects(stream("/ai/x", {}, handlers), (e: unknown) => e instanceof ApiError && e.code === "CSRF_INVALID");
  assert.equal(seen.length, 2);
});

test("stream(): once the stream has started, an `error` event is never retried and the handlers are not replayed", async () => {
  let deltas = 0;
  const seen = stub(() => ({ status: 200, sse: ['event: start\ndata: {"streamId":"s1"}\n\n', 'event: delta\ndata: {"text":"a"}\n\n', 'event: error\ndata: {"code":"CSRF_INVALID","message":"late"}\n\n'] }));
  await assert.rejects(stream("/ai/x", {}, { ...handlers, onDelta: () => { deltas++; } }), (e: unknown) => e instanceof ApiError && e.code === "CSRF_INVALID");
  assert.equal(seen.length, 1);
  assert.equal(deltas, 1);
});

test("stream(): non-CSRF refusals are not retried and keep the header request id", async () => {
  const seen = stub(() => ({ status: 429, body: { code: "AI_DAILY_LIMIT", message: "x" }, headers: { "X-Request-Id": "s-9" } }));
  await assert.rejects(stream("/ai/x", {}, handlers), (e: unknown) => e instanceof ApiError && e.requestId === "s-9" && e.code === "AI_DAILY_LIMIT");
  assert.equal(seen.length, 1);
});
