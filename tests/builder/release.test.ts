// @class: mock — fetch is stubbed (what the client SENDS and how it reads the C2 contract); the pure helpers are real logic. NOT backend behaviour: that is the real-backend suite (E2E-P*).
/** Authoritative source: docs/parallel/c2/PUBLISH_API_CONTRACT.md @ fix/c2-v3 8d40218 (pinned there by PublishApiContractTests). Nothing below goes beyond it. */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, resetCsrf } from "../../packages/api-client/src/core";
import { api } from "../../packages/api-client/src/api";
import * as R from "../../packages/api-client/src/release";

type Seen = { url: string; method: string; headers: Record<string, string>; body?: string };
function stub(handler: (u: string, i: RequestInit) => { status: number; body?: unknown; headers?: Record<string, string> }) {
  const seen: Seen[] = [];
  (globalThis as { fetch: unknown }).fetch = async (u: string, i: RequestInit = {}) => {
    seen.push({ url: String(u), method: String(i.method ?? "GET"), headers: { ...(i.headers as Record<string, string> ?? {}) }, body: i.body as string | undefined });
    if (String(u).endsWith("/auth/csrf")) return new Response(JSON.stringify({ token: "t1" }), { status: 200 });
    const r = handler(String(u), i);
    return new Response(r.status === 204 ? null : JSON.stringify(r.body ?? {}), { status: r.status, headers: r.headers });
  };
  resetCsrf();
  return seen;
}
const last = (seen: Seen[]) => seen.filter((s) => !s.url.endsWith("/auth/csrf")).at(-1)!;
const SITE = { slug: "s", url: null, online: false, visibility: null, currentDeploymentId: null, currentVersionNumber: null, provider: "static", updatedAt: null, pointerVersion: 3, operation: null };
const ID_A = "11111111-1111-4111-8111-111111111111", ID_B = "22222222-2222-4222-8222-222222222222";

// ---- statuses ---------------------------------------------------------------------------------------------------------------------------------------------------
test("the closed list of nine deployment statuses; ROLLBACK_FAILED / ROLLBACK_OFFLINE are event names, never statuses", () => {
  assert.deepEqual([...R.DEPLOYMENT_STATUSES], ["QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "ROLLING_BACK", "RUNNING", "FAILED", "ROLLED_BACK"]);
  assert.ok(!(R.DEPLOYMENT_STATUSES as readonly string[]).includes("ROLLBACK_FAILED") && !(R.DEPLOYMENT_STATUSES as readonly string[]).includes("ROLLBACK_OFFLINE"));
  for (const ev of ["SWITCH", "ROLLBACK_OK", "ROLLBACK_FAILED", "ROLLBACK_OFFLINE", "SCOPE_BUSY", "STALE_PUBLISH", "ROLLED_BACK"]) assert.ok(R.deploymentLabel(ev) !== ev || ev === "ROLLED_BACK", `event ${ev} has a label`);
});
test("terminal = {RUNNING, FAILED, ROLLED_BACK}: unchanged by V30, so a poller stops on exactly that set", () => {
  assert.deepEqual([...R.TERMINAL_DEPLOYMENT_STATUSES].sort(), ["FAILED", "ROLLED_BACK", "RUNNING"]);
  for (const s of R.DEPLOYMENT_STATUSES) { const t = ["RUNNING", "FAILED", "ROLLED_BACK"].includes(s); assert.equal(R.isTerminalDeployment(s), t, s); assert.equal(R.shouldStopPolling(s), t, s); assert.equal(R.isBusyDeployment(s), !t, s); }
});
test("ROLLING_BACK is busy, non-terminal and NEVER a success; ROLLED_BACK is terminal and not a success; only RUNNING is a success", () => {
  assert.equal(R.isBusyDeployment("ROLLING_BACK"), true); assert.equal(R.isTerminalDeployment("ROLLING_BACK"), false); assert.equal(R.isDeploymentSuccess("ROLLING_BACK"), false);
  assert.equal(R.isTerminalDeployment("ROLLED_BACK"), true); assert.equal(R.isDeploymentSuccess("ROLLED_BACK"), false);
  assert.deepEqual(R.DEPLOYMENT_STATUSES.filter(R.isDeploymentSuccess), ["RUNNING"]);
  assert.match(R.deploymentLabel("ROLLING_BACK"), /chưa phải thành công/);
});
test("an UNKNOWN status is busy (keep polling), never a success, never silently terminal", () => {
  assert.equal(R.isBusyDeployment("SOMETHING_NEW"), true); assert.equal(R.isDeploymentSuccess("SOMETHING_NEW"), false); assert.equal(R.isTerminalDeployment(undefined), false);
});

// ---- STALE_PUBLISH is a FAILED deployment, not an HTTP conflict ---------------------------------------------------------------------------------------------------
test("STALE_PUBLISH is a deployment that ended FAILED with error '[STALE_PUBLISH] …': shown as a failure with its own explanation, retry = publish again", () => {
  const d = { status: "FAILED", error: "[STALE_PUBLISH] a newer release operation already moved the active release" };
  assert.equal(R.failureCode(d.error), "STALE_PUBLISH");
  const v = R.explainFailedDeployment(d);
  assert.equal(v.code, "STALE_PUBLISH"); assert.match(v.title, /cũ hơn bản đang chạy/); assert.match(v.detail, /a newer release operation already moved the active release/); assert.equal(v.retryByPublishingAgain, true);
  assert.equal(R.isTerminalDeployment(d.status), true); assert.equal(R.isDeploymentSuccess(d.status), false);
  assert.equal(R.explainReleaseError({ status: 409, code: "STALE_PUBLISH" }).kind, "other", "STALE_PUBLISH is not an HTTP error code of the contract");
});
test("failure codes: none/unknown still produce a failure view that never looks like success", () => {
  assert.equal(R.failureCode(null), null); assert.equal(R.failureCode("no code here"), null);
  const v = R.explainFailedDeployment({ status: "FAILED", error: null }); assert.match(v.title, /Không xuất bản được/); assert.ok(v.detail.length > 0);
  assert.equal(R.explainFailedDeployment({ status: "FAILED", error: "[SCOPE_BUSY] Another release operation owned the scope for more than 300 s" }).code, "SCOPE_BUSY");
});

// ---- requests: exactly the contract's fields ----------------------------------------------------------------------------------------------------------------------
test("publish: POST …/publish, Idempotency-Key + CSRF, body is EXACTLY {visibility, expectedRevision}", async () => {
  const seen = stub(() => ({ status: 202, body: { id: ID_A, status: "QUEUED", events: [] } }));
  await api.publish("w1", "p1", "PRIVATE", 7, "ui-key-0001");
  const r = last(seen);
  assert.equal(r.url, "/api/v1/workspaces/w1/projects/p1/publish"); assert.equal(r.method, "POST");
  assert.deepEqual(JSON.parse(r.body!), { visibility: "PRIVATE", expectedRevision: 7 });
  assert.deepEqual(Object.keys(JSON.parse(r.body!)).sort(), ["expectedRevision", "visibility"]);
  assert.equal(r.headers["Idempotency-Key"], "ui-key-0001"); assert.equal(r.headers["X-XSRF-TOKEN"], "t1");
  assert.ok(!/tenantId|pointerVersion/.test(r.body!));
});
test("publish refuses a missing or malformed Idempotency-Key BEFORE sending anything (required, 8–120 chars of A-Za-z0-9_.:-)", async () => {
  const seen = stub(() => ({ status: 202, body: {} }));
  for (const k of ["", "short", "has space in it", "x".repeat(121), undefined as unknown as string]) await assert.rejects(api.publish("w", "p", "PUBLIC", 1, k), (e: unknown) => e instanceof ApiError && e.code === "INVALID_IDEMPOTENCY_KEY", String(k));
  assert.equal(seen.filter((s) => s.method === "POST").length, 0);
  for (const k of ["abcdefgh", "a".repeat(120), "ui-1234:5.6_7"]) assert.ok(R.isValidReleaseKey(k), k);
});
test("rollback: POST …/site/rollback, body {deploymentId} (+ expectedActiveDeploymentId only when known), key optional, never pointerVersion", async () => {
  const seen = stub(() => ({ status: 200, body: SITE }));
  await api.rollbackSite("w1", "p1", { deploymentId: ID_A });
  let r = last(seen);
  assert.equal(r.url, "/api/v1/workspaces/w1/projects/p1/site/rollback"); assert.equal(r.method, "POST");
  assert.deepEqual(JSON.parse(r.body!), { deploymentId: ID_A }); assert.equal(r.headers["Idempotency-Key"], undefined);
  await api.rollbackSite("w1", "p1", { deploymentId: ID_A, expectedActiveDeploymentId: ID_B }, "ui-rollback-1");
  r = last(seen);
  assert.deepEqual(JSON.parse(r.body!), { deploymentId: ID_A, expectedActiveDeploymentId: ID_B }); assert.equal(r.headers["Idempotency-Key"], "ui-rollback-1");
  await api.rollbackSite("w1", "p1", { deploymentId: ID_A, expectedActiveDeploymentId: null });
  assert.deepEqual(JSON.parse(last(seen).body!), { deploymentId: ID_A }, "an unknown expectation is OMITTED, never sent as null");
  await assert.rejects(api.rollbackSite("w1", "p1", { deploymentId: ID_A }, "bad"), (e: unknown) => e instanceof ApiError && e.code === "INVALID_IDEMPOTENCY_KEY");
});
test("unpublish: DELETE …/site with NO body; the optional expectation is the query parameter; key optional", async () => {
  const seen = stub(() => ({ status: 200, body: SITE }));
  await api.unpublishSite("w1", "p1");
  let r = last(seen); assert.equal(r.url, "/api/v1/workspaces/w1/projects/p1/site"); assert.equal(r.method, "DELETE"); assert.equal(r.body, undefined);
  await api.unpublishSite("w1", "p1", ID_A, "ui-unpublish-1");
  r = last(seen); assert.equal(r.url, `/api/v1/workspaces/w1/projects/p1/site?expectedActiveDeploymentId=${ID_A}`); assert.equal(r.body, undefined); assert.equal(r.headers["Idempotency-Key"], "ui-unpublish-1");
  assert.equal(R.unpublishQuery(null), ""); assert.equal(R.unpublishQuery(undefined), "");
});
test("the browser never sends pointerVersion, tenantId or any invented concurrency field on any release route", async () => {
  const seen = stub(() => ({ status: 200, body: { ...SITE, id: ID_A, status: "QUEUED", events: [] } }));
  await api.publish("w", "p", "PUBLIC", 2, "ui-key-0002"); await api.rollbackSite("w", "p", { deploymentId: ID_A, expectedActiveDeploymentId: ID_B }, "ui-key-0003"); await api.unpublishSite("w", "p", ID_A, "ui-key-0004");
  await api.site("w", "p"); await api.getDeployment("w", "p", ID_A); await api.listDeployments("w", "p");
  for (const s of seen) assert.ok(!/pointerVersion|tenantId|expectedPointer|If-Match/i.test(`${s.url} ${s.body ?? ""} ${JSON.stringify(s.headers)}`), s.url);
});
test("SiteInfo is read as the contract shape: pointerVersion integer, operation null or {kind, deploymentId, since, leaseUntil}", async () => {
  stub(() => ({ status: 200, body: { ...SITE, pointerVersion: 12, operation: { kind: "ROLLBACK", deploymentId: null, since: "2026-10-07T00:00:00Z", leaseUntil: "2026-10-07T00:01:30Z" } } }));
  const s = await api.site("w", "p");
  assert.equal(s.pointerVersion, 12); assert.equal(s.operation?.kind, "ROLLBACK"); assert.equal(R.isReleaseBusy(s), true);
  stub(() => ({ status: 200, body: SITE })); assert.equal(R.isReleaseBusy(await api.site("w", "p")), false);
  assert.equal(R.isReleaseBusy(null), false); assert.equal(R.isReleaseBusy({ operation: null }), false);
});
test("SiteInfo.operation drives a busy banner for each kind; idle shows nothing", () => {
  for (const [kind, text] of [["PUBLISH", /đang xuất bản/], ["ROLLBACK", /đang hoàn tác/], ["UNPUBLISH", /đang gỡ trang xuống/]] as const) {
    const b = R.operationBanner({ kind, deploymentId: null, since: "x", leaseUntil: "y" }); assert.match(b ?? "", text); assert.match(b ?? "", /tạm khóa/);
  }
  assert.equal(R.operationBanner(null), null);
});

// ---- errors ---------------------------------------------------------------------------------------------------------------------------------------------------------
test("409 SCOPE_BUSY (rollback / unpublish): retryable, Retry-After 5 s is read from the header, the holder is named", async () => {
  stub(() => ({ status: 409, headers: { "Retry-After": "5" }, body: { code: "SCOPE_BUSY", message: "Another release operation (PUBLISH) is running for this app; try again when it has finished", requestId: "r", details: { appId: "a", environment: "PRODUCTION", operation: { kind: "PUBLISH", deploymentId: ID_A, since: "x", leaseUntil: "y" } } } }));
  let err: unknown; await api.rollbackSite("w", "p", { deploymentId: ID_A }).catch((e) => { err = e; });
  assert.ok(err instanceof ApiError); assert.equal((err as ApiError).retryAfterSeconds, 5);
  const v = R.explainReleaseError(err as ApiError);
  assert.equal(v.kind, "scope-busy"); assert.equal(v.retry, true); assert.equal(v.retryAfterSeconds, 5); assert.equal(v.reload, true); assert.match(v.detail, /đang xuất bản/); assert.match(v.detail, /5 giây/);
});
test("409 ROLLBACK_STALE: NOT retryable as is — reload the site and decide again; nothing changed", () => {
  const v = R.explainReleaseError({ status: 409, code: "ROLLBACK_STALE", details: { activeDeploymentId: ID_B, expectedActiveDeploymentId: ID_A } });
  assert.equal(v.kind, "stale"); assert.equal(v.retry, false); assert.equal(v.reload, true); assert.match(v.detail, /Không có gì bị thay đổi/);
});
test("409 IDEMPOTENCY_KEY_REUSED: never retried with the same key; a NEW key is required; 409 IDEMPOTENCY_IN_PROGRESS waits and rechecks", () => {
  const k = R.explainReleaseError({ status: 409, code: "IDEMPOTENCY_KEY_REUSED" }); assert.equal(k.kind, "key-reused"); assert.equal(k.retry, false); assert.equal(k.newKey, true);
  const p = R.explainReleaseError({ status: 409, code: "IDEMPOTENCY_IN_PROGRESS" }); assert.equal(p.kind, "in-progress"); assert.equal(p.retry, true);
});
test("the other documented codes keep their own meaning (restorable, rollback failed, no site, revision, forbidden, public disabled, rate limit)", () => {
  assert.equal(R.explainReleaseError({ status: 400, code: "DEPLOYMENT_NOT_RESTORABLE" }).kind, "not-restorable");
  const f = R.explainReleaseError({ status: 409, code: "ROLLBACK_FAILED", message: "The release could not be restored: artifact missing" }); assert.equal(f.kind, "rollback-failed"); assert.match(f.detail, /artifact missing/); assert.equal(f.retry, false);
  assert.equal(R.explainReleaseError({ status: 404, code: "SITE_NOT_FOUND" }).kind, "no-site");
  assert.equal(R.explainReleaseError({ status: 409, code: "REVISION_CONFLICT" }).reload, true);
  assert.equal(R.explainReleaseError({ status: 403, code: "FORBIDDEN" }).kind, "forbidden"); assert.match(R.explainReleaseError({ status: 403, code: "FORBIDDEN" }).detail, /quyền xuất bản/); assert.doesNotMatch(R.explainReleaseError({ status: 403, code: "FORBIDDEN" }).detail, /APP_PUBLISH/); // M-060: no permission code in the copy
  assert.equal(R.explainReleaseError({ status: 403, code: "PUBLIC_PUBLISH_DISABLED" }).kind, "public-disabled");
  assert.equal(R.explainReleaseError({ status: 429, code: "RATE_LIMITED", retryAfterSeconds: 30 }).retryAfterSeconds, 30);
});
test("an unknown outcome (network / 5xx) is replay-safe with the same key but is reloaded first; it is not shown as success or as a plain failure", () => {
  for (const e of [{ status: 0, code: "NETWORK" }, { status: 502, code: "HTTP_502" }]) { const v = R.explainReleaseError(e); assert.equal(v.kind, "unreachable"); assert.equal(v.retry, true); assert.equal(v.reload, true); assert.match(v.title, /Chưa rõ/); }
});

// ---- idempotency key lifecycle ------------------------------------------------------------------------------------------------------------------------------------
test("one key per LOGICAL request: the same payload keeps its key, another payload gets a new key (no IDEMPOTENCY_KEY_REUSED by construction)", () => {
  let n = 0; const book = new R.ReleaseKeyBook(() => `ui-key-${++n}0000`);
  const a = book.keyFor(R.publishBody("PRIVATE", 5));
  assert.equal(book.keyFor(R.publishBody("PRIVATE", 5)), a, "retry of the same request = replay-safe");
  const b = book.keyFor(R.publishBody("PUBLIC", 5)); assert.notEqual(b, a, "another visibility is another request");
  const c = book.keyFor(R.publishBody("PUBLIC", 6)); assert.notEqual(c, b, "another revision is another request");
  assert.ok(R.isValidReleaseKey(a) && R.isValidReleaseKey(b) && R.isValidReleaseKey(c));
});
test("after an outcome the user wants to repeat, rotate() makes the next identical request a NEW operation (a replay would return the old outcome)", () => {
  let n = 0; const book = new R.ReleaseKeyBook(() => `ui-key-${++n}0000`);
  const body = R.rollbackBody({ deploymentId: ID_A, expectedActiveDeploymentId: ID_B });
  const k1 = book.keyFor(body); book.rotate(); const k2 = book.keyFor(body);
  assert.notEqual(k1, k2);
});
test("generated keys satisfy the contract format", () => { for (let i = 0; i < 20; i++) assert.ok(R.isValidReleaseKey(R.newReleaseKey()), R.newReleaseKey()); });

// ---- rollback candidates -----------------------------------------------------------------------------------------------------------------------------------------
test("rollback candidates: RUNNING, not mock, not the one being served; ROLLED_BACK / FAILED / in-progress are not offered", () => {
  const h = [{ id: "a", status: "RUNNING" }, { id: "b", status: "RUNNING" }, { id: "c", status: "ROLLED_BACK" }, { id: "d", status: "FAILED" }, { id: "e", status: "ROLLING_BACK" }, { id: "f", status: "RUNNING", mock: true }];
  assert.deepEqual(R.rollbackCandidates(h, "a").map((x) => x.id), ["b"]);
  assert.deepEqual(R.rollbackCandidates(h, null).map((x) => x.id), ["a", "b"]);
});
