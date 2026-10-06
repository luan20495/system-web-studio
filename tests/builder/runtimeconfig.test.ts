// @class: mock — fetch is injected/stubbed; proves the loader's validation and fail-closed rules, NOT that any host exists (the published host is not decided)
import test from "node:test";
import assert from "node:assert/strict";
import { RuntimeConfigError, dataApiUrl, describeRuntimeConfigError, initDataApi, loadRuntimeConfig, parseRuntimeConfig, renderRuntimeConfigFailure, validateDataApiBaseUrl, type RuntimeConfigErrorCode } from "../../packages/api-client/src/runtimeConfig";

const answer = (status: number, body: unknown, raw = false) => (async () => new Response(raw ? String(body) : JSON.stringify(body), { status })) as unknown as typeof fetch;
const reject = (e: unknown) => (async () => { throw e; }) as unknown as typeof fetch;
const code = (p: Promise<unknown>) => p.then(() => "OK", (e: unknown) => (e instanceof RuntimeConfigError ? e.code : `OTHER:${e}`));
const VALID = { DATA_API_BASE_URL: "https://data.example.com", ENVIRONMENT: "staging", RELEASE_ID: "r-1", VERSION: "1.2.3" };

test("valid config: loaded from /runtime-config.json with no-store and no credentials, only known keys returned, trailing slash removed", async () => {
  let seen: { url: string; init: RequestInit } | undefined;
  const f = (async (u: string, i: RequestInit) => { seen = { url: u, init: i }; return new Response(JSON.stringify({ ...VALID, DATA_API_BASE_URL: "https://data.example.com/v1/", EXTRA: "ignored" }), { status: 200 }); }) as unknown as typeof fetch;
  const c = await loadRuntimeConfig({ fetchImpl: f });
  assert.deepEqual(c, { DATA_API_BASE_URL: "https://data.example.com/v1", ENVIRONMENT: "staging", RELEASE_ID: "r-1", VERSION: "1.2.3" });
  assert.equal(seen?.url, "/runtime-config.json"); assert.equal(seen?.init.cache, "no-store"); assert.equal(seen?.init.credentials, "omit");
  assert.equal((await loadRuntimeConfig({ fetchImpl: answer(200, { DATA_API_BASE_URL: "https://d.example.com" }) })).ENVIRONMENT, undefined);
});

test("missing / empty / non-string DATA_API_BASE_URL → typed errors, never a default host", async () => {
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, {}) })), "MISSING_DATA_API_BASE_URL");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, { DATA_API_BASE_URL: "  " }) })), "MISSING_DATA_API_BASE_URL");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, { DATA_API_BASE_URL: 5 }) })), "INVALID_DATA_API_BASE_URL");
});

test("malformed JSON, non-object JSON, wrong field types", async () => {
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, "{not json", true) })), "NOT_JSON");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, "<html>", true) })), "NOT_JSON");
  for (const body of [[], "x", 5, null]) assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, body) })), "INVALID_SHAPE");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(200, { ...VALID, VERSION: 3 }) })), "INVALID_SHAPE");
});

test("unreachable / timeout / HTTP errors fail with typed errors in production (no fallback at all)", async () => {
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: reject(new TypeError("fetch failed")) })), "UNREACHABLE");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: reject(new DOMException("t", "TimeoutError")) })), "TIMEOUT");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(404, {}) })), "HTTP_STATUS");
  assert.equal(await code(loadRuntimeConfig({ fetchImpl: answer(500, {}) })), "HTTP_STATUS");
  // production ignores a dev fallback even when one is supplied
  assert.equal(await code(loadRuntimeConfig({ mode: "production", devFallback: "http://localhost:8080", fetchImpl: reject(new TypeError("x")) })), "UNREACHABLE");
  assert.equal(await code(loadRuntimeConfig({ devFallback: "http://localhost:8080", fetchImpl: answer(404, {}) })), "HTTP_STATUS");           // default mode is production
});

test("invalid URLs: relative, wrong scheme, credentials, query, fragment, garbage", () => {
  for (const [v, c] of [["/relative", "INVALID_DATA_API_BASE_URL"], ["data.example.com", "INVALID_DATA_API_BASE_URL"], ["ftp://d.example.com", "INVALID_DATA_API_BASE_URL"], ["javascript:alert(1)", "INVALID_DATA_API_BASE_URL"],
    ["https://u:p@d.example.com", "INVALID_DATA_API_BASE_URL"], ["https://d.example.com/?a=1", "INVALID_DATA_API_BASE_URL"], ["https://d.example.com/#x", "INVALID_DATA_API_BASE_URL"], ["not a url", "INVALID_DATA_API_BASE_URL"]] as [string, RuntimeConfigErrorCode][]) {
    assert.throws(() => validateDataApiBaseUrl(v), (e: unknown) => e instanceof RuntimeConfigError && e.code === c, v);
  }
});

test("production is fail-closed on transport: http is refused, loopback is refused, https is required", () => {
  assert.throws(() => validateDataApiBaseUrl("http://data.example.com"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "INSECURE_DATA_API_BASE_URL");
  assert.throws(() => validateDataApiBaseUrl("http://localhost:8080"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "INSECURE_DATA_API_BASE_URL");
  assert.throws(() => validateDataApiBaseUrl("https://localhost:8443"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "LOOPBACK_IN_PRODUCTION");
  assert.throws(() => validateDataApiBaseUrl("https://127.0.0.1"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "LOOPBACK_IN_PRODUCTION");
  assert.throws(() => validateDataApiBaseUrl("https://[::1]:9"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "LOOPBACK_IN_PRODUCTION");
  assert.equal(validateDataApiBaseUrl("https://data.example.com/"), "https://data.example.com");
});

test("a config that says ENVIRONMENT=production never accepts a loopback host, even in development mode", () => {
  assert.throws(() => parseRuntimeConfig({ DATA_API_BASE_URL: "https://localhost:8443", ENVIRONMENT: "production" }, "development"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "LOOPBACK_IN_PRODUCTION");
});

test("development ergonomics: loopback http is accepted in development, and an explicit fallback is used ONLY when the file is missing or unreachable", async () => {
  assert.equal(validateDataApiBaseUrl("http://localhost:8080", "development"), "http://localhost:8080");
  assert.equal(validateDataApiBaseUrl("http://127.0.0.1:8080/", "development"), "http://127.0.0.1:8080");
  assert.throws(() => validateDataApiBaseUrl("http://data.example.com", "development"), (e: unknown) => e instanceof RuntimeConfigError && e.code === "INSECURE_DATA_API_BASE_URL");     // http to a real host is never fine
  const dev = { mode: "development" as const, devFallback: "http://localhost:8080" };
  assert.deepEqual(await loadRuntimeConfig({ ...dev, fetchImpl: answer(404, {}) }), { DATA_API_BASE_URL: "http://localhost:8080", ENVIRONMENT: "development" });
  assert.deepEqual(await loadRuntimeConfig({ ...dev, fetchImpl: reject(new TypeError("x")) }), { DATA_API_BASE_URL: "http://localhost:8080", ENVIRONMENT: "development" });
  // present-but-broken, or a server error, never falls back (it would hide a real misconfiguration)
  assert.equal(await code(loadRuntimeConfig({ ...dev, fetchImpl: answer(200, "{bad", true) })), "NOT_JSON");
  assert.equal(await code(loadRuntimeConfig({ ...dev, fetchImpl: answer(200, {}) })), "MISSING_DATA_API_BASE_URL");
  assert.equal(await code(loadRuntimeConfig({ ...dev, fetchImpl: answer(500, {}) })), "HTTP_STATUS");
  // no fallback supplied → even development fails visibly
  assert.equal(await code(loadRuntimeConfig({ mode: "development", fetchImpl: answer(404, {}) })), "HTTP_STATUS");
  // an invalid fallback is refused too
  assert.equal(await code(loadRuntimeConfig({ mode: "development", devFallback: "http://data.example.com", fetchImpl: answer(404, {}) })), "INSECURE_DATA_API_BASE_URL");
});

test("Data API URLs are built on the configured host and cannot leave it", () => {
  const c = { DATA_API_BASE_URL: "https://data.example.com/v1" };
  assert.equal(dataApiUrl(c, "/api/v1/q?x=1"), "https://data.example.com/v1/api/v1/q?x=1");
  for (const bad of ["api/v1", "//evil.com/x", "https://evil.com", "/a/../b", "/a\\b", "", "/x://y"]) assert.throws(() => dataApiUrl(c, bad), (e: unknown) => e instanceof RuntimeConfigError && e.code === "INVALID_PATH", bad);
});

test("initDataApi: the client is created only AFTER a valid config; a bad config means no client at all", async () => {
  const api = await initDataApi({ fetchImpl: answer(200, VALID) });
  assert.equal(api.baseUrl, "https://data.example.com"); assert.equal(api.url("/x"), "https://data.example.com/x");
  await assert.rejects(initDataApi({ fetchImpl: answer(200, { ENVIRONMENT: "x" }) }), (e: unknown) => e instanceof RuntimeConfigError);
});

test("the failure is visible: every code has user text, no raw error leaks, and the DOM helper writes textContent only", () => {
  for (const c of ["UNREACHABLE", "TIMEOUT", "HTTP_STATUS", "NOT_JSON", "INVALID_SHAPE", "MISSING_DATA_API_BASE_URL", "INVALID_DATA_API_BASE_URL", "INSECURE_DATA_API_BASE_URL", "LOOPBACK_IN_PRODUCTION", "INVALID_PATH"] as RuntimeConfigErrorCode[]) {
    const d = describeRuntimeConfigError(new RuntimeConfigError(c, "internal detail <b>x</b>")); assert.equal(d.code, c); assert.ok(d.detail.length > 15); assert.doesNotMatch(d.detail, /internal detail/);
  }
  assert.equal(describeRuntimeConfigError(new Error("boom")).code, "UNKNOWN");
  const made: { tag: string; attrs: Record<string, string>; text: string; kids: unknown[] }[] = [];
  const el = (tag: string) => { const o = { tag, attrs: {} as Record<string, string>, text: "", kids: [] as unknown[], setAttribute(k: string, v: string) { o.attrs[k] = v; }, append(...n: unknown[]) { o.kids.push(...n); }, set textContent(v: string) { o.text = v; } }; made.push(o); return o; };
  let rendered: unknown;
  renderRuntimeConfigFailure({ ownerDocument: { createElement: el } as never, replaceChildren: ((n: unknown) => { rendered = n; }) as never }, new RuntimeConfigError("MISSING_DATA_API_BASE_URL", "x"));
  const box = rendered as (typeof made)[number]; assert.equal(box.attrs.role, "alert"); assert.equal(box.attrs["data-runtime-config-error"], "MISSING_DATA_API_BASE_URL"); assert.equal(box.kids.length, 2);
});
