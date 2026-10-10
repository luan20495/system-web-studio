// @class: mock — fetch is stubbed; proves what the publish client SENDS and how the two H-C2-07 refusals are worded, NOT backend behaviour (the rule itself is C2's PublicDataApprovalTests)
/**
 * H-C2-07 (docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md): POST /publish is judged against the PERSISTED publish policy. 422 PUBLIC_DATA_NOT_APPROVED and 409 PUBLISH_POLICY_MISMATCH are pre-accept refusals.
 * The approval is persisted only through PUT publish-config; POST /publish never carries it.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, resetCsrf } from "../../packages/api-client/src/core";
import { api } from "../../packages/api-client/src/api";
import * as R from "../../packages/api-client/src/release";

type Seen = { url: string; method: string; body?: string; headers: Record<string, string> };
function stub(handler: (u: string, i: RequestInit) => { status: number; body?: unknown }) {
  const seen: Seen[] = [];
  (globalThis as { fetch: unknown }).fetch = async (u: string, i: RequestInit = {}) => {
    seen.push({ url: String(u), method: String(i.method ?? "GET"), body: i.body as string | undefined, headers: { ...(i.headers as Record<string, string> ?? {}) } });
    if (String(u).endsWith("/auth/csrf")) return new Response(JSON.stringify({ token: "t1" }), { status: 200 });
    const r = handler(String(u), i); return new Response(JSON.stringify(r.body ?? {}), { status: r.status });
  };
  resetCsrf(); return seen;
}
const W = "/api/v1/workspaces/w1/projects/p1";
const POLICY = { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, cacheSeconds: null, publicDataApproved: false, linkTokenSet: false, revision: 3, updatedAt: null };

test("PUBLISH_REQUEST_CARRIES_FAKE_APPROVAL: NO — POST /publish carries exactly {visibility, expectedRevision}; no approval, acknowledgement or draft policy can be added to it", async () => {
  const seen = stub(() => ({ status: 202, body: {} }));
  await api.publish("w1", "p1", "PUBLIC", 7, "ui-key-12345678");
  // @ts-expect-error the client has no parameter for an approval: even a caller that forces one in cannot change the body
  await api.publish("w1", "p1", "PUBLIC", 7, "ui-key-12345678", true, { acknowledgePublicData: true, publicDataApproved: true });
  const w = seen.filter((s) => !s.url.endsWith("/auth/csrf"));
  assert.deepEqual(w.map((s) => `${s.method} ${s.url}`), [`POST ${W}/publish`, `POST ${W}/publish`]);
  for (const s of w) { assert.deepEqual(Object.keys(JSON.parse(s.body!)).sort(), ["expectedRevision", "visibility"]); assert.ok(!/approv|acknowledge|publicData/i.test(s.body!)); }
});

test("the policy is read with GET and the approval is persisted with PUT publish-config: requiresAuth always present, acknowledgePublicData only when given, the revision that was read", async () => {
  const seen = stub((u, i) => ({ status: 200, body: { config: POLICY, draft: null } }));
  const r = await api.getPublishConfig("w1", "p1");
  await api.putPublishConfig("w1", "p1", { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: true, expectedRevision: 3 });
  await api.putPublishConfig("w1", "p1", { mode: "DYNAMIC", visibility: "PRIVATE", requiresAuth: true, cacheSeconds: 60 } as never);
  const w = seen.filter((s) => !s.url.endsWith("/auth/csrf"));
  assert.equal(r.config?.revision, 3);
  assert.deepEqual(w.map((s) => `${s.method} ${s.url}`), [`GET ${W}/publish-config`, `PUT ${W}/publish-config`, `PUT ${W}/publish-config`]);
  assert.equal(w[0].body, undefined); assert.equal(w[0].headers["X-XSRF-TOKEN"], undefined);
  assert.deepEqual(JSON.parse(w[1].body!), { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: true, expectedRevision: 3 }); assert.equal(w[1].headers["X-XSRF-TOKEN"], "t1");
  assert.deepEqual(JSON.parse(w[2].body!), { mode: "DYNAMIC", visibility: "PRIVATE", requiresAuth: true, cacheSeconds: 60 }, "no acknowledgement unless the person gave it; no revision when none is known");
  assert.deepEqual(R.publishConfigBody({ mode: "STATIC", visibility: "PUBLIC" }), { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false }, "requiresAuth is never omitted (the server's request class needs it)");
});

test("approvalRequest: the stored policy's own mode / auth / cache + PUBLIC + the acknowledgement + the revision read; null when no policy is stored (nothing to persist)", () => {
  assert.deepEqual(R.approvalRequest(POLICY), { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: true, expectedRevision: 3, cacheSeconds: null });
  assert.deepEqual(R.approvalRequest({ ...POLICY, mode: "DYNAMIC", requiresAuth: true, cacheSeconds: 30, revision: 9 }), { mode: "DYNAMIC", visibility: "PUBLIC", requiresAuth: true, cacheSeconds: 30, acknowledgePublicData: true, expectedRevision: 9 });
  assert.equal(R.approvalRequest(null), null); assert.equal(R.approvalRequest(undefined), null);
});

test("422 PUBLIC_DATA_NOT_APPROVED: its own actionable state (not the generic failure), no blind retry, the approval is persisted through publish-config, a fresh key, nothing says it was published", () => {
  const v = R.explainReleaseError({ status: 422, code: "PUBLIC_DATA_NOT_APPROVED", details: { issues: [{ field: "acknowledgePublicData", code: "PUBLIC_DATA_NOT_APPROVED" }] } });
  assert.equal(v.kind, "public-data-not-approved"); assert.equal(v.action, "approve-public-data"); assert.equal(v.retry, false); assert.equal(v.newKey, true);
  assert.match(v.title, /phê duyệt công khai dữ liệu/); assert.match(v.detail, /chưa lưu phê duyệt/); assert.match(v.detail, /Ô xác nhận trong hộp thoại chỉ là ý định/); assert.match(v.detail, /Chưa có gì được xuất bản/);
  assert.notEqual(v.kind, "other"); assert.doesNotMatch(v.title, /Không thực hiện được/);
});

test("409 PUBLISH_POLICY_MISMATCH: says the policy changed / conflicts, offers a reload of the authoritative policy, no retry with the cached policy, a fresh key", () => {
  const v = R.explainReleaseError({ status: 409, code: "PUBLISH_POLICY_MISMATCH" });
  assert.equal(v.kind, "policy-mismatch"); assert.equal(v.action, "reload-policy"); assert.equal(v.retry, false); assert.equal(v.reload, true); assert.equal(v.newKey, true);
  assert.match(v.title, /Chính sách xuất bản đã thay đổi hoặc xung đột/); assert.match(v.detail, /không thể mở rộng chính sách/); assert.match(v.detail, /không tự gửi lại với chính sách cũ/); assert.match(v.detail, /Chưa có gì được xuất bản/);
  // the existing refusals are unchanged
  assert.equal(R.explainReleaseError({ status: 409, code: "SCOPE_BUSY" }).kind, "scope-busy"); assert.equal(R.explainReleaseError({ status: 409, code: "REVISION_CONFLICT" }).kind, "revision");
  assert.equal(R.explainReleaseError({ status: 422, code: "VALIDATION_FAILED" }).kind, "invalid"); assert.equal(R.explainReleaseError({ status: 403, code: "FORBIDDEN" }).kind, "forbidden");
});

test("the policy line says what the SERVER holds (visibility, approval, revision) and what 'no policy' means; refusals of PUT publish-config are worded by code", () => {
  assert.match(R.policyLine(POLICY), /bản 3.*công khai, chưa phê duyệt dữ liệu công khai/); assert.match(R.policyLine({ ...POLICY, publicDataApproved: true }), /đã phê duyệt dữ liệu công khai/);
  assert.match(R.policyLine({ ...POLICY, visibility: "PRIVATE" }), /riêng tư\./); assert.doesNotMatch(R.policyLine({ ...POLICY, visibility: "PRIVATE" }), /phê duyệt/); assert.match(R.policyLine(null), /Chưa có chính sách xuất bản được lưu/);
  assert.equal(R.explainPublishConfigError({ status: 409, code: "REVISION_CONFLICT" }).reload, true); assert.match(R.explainPublishConfigError({ status: 409, code: "REVISION_CONFLICT" }).detail, /chưa được lưu/);
  assert.match(R.explainPublishConfigError({ status: 422, code: "PUBLISH_CONFIG_INVALID", details: { issues: [{ message: "Tenant policy forbids public." }] } }).detail, /Tenant policy forbids public\..*chưa được lưu/);
  assert.match(R.explainPublishConfigError({ status: 403, code: "FORBIDDEN" }).detail, /quyền xuất bản/); assert.match(R.explainPublishConfigError({ status: 404, code: "NOT_FOUND" }).detail, /chưa được lưu/); assert.match(R.explainPublishConfigError({ status: 0 }).title, /Chưa rõ/);
});

test("the refusals reach the dialog as ApiError with the server's code (no deployment id exists for them)", async () => {
  stub(() => ({ status: 422, body: { code: "PUBLIC_DATA_NOT_APPROVED", message: "x", details: { issues: [{ field: "acknowledgePublicData", code: "PUBLIC_DATA_NOT_APPROVED", message: "m" }] } } }));
  const a = await api.publish("w1", "p1", "PUBLIC", 7, "ui-key-12345678").then(() => null, (e: unknown) => e as ApiError); assert.equal(a!.status, 422); assert.equal(a!.code, "PUBLIC_DATA_NOT_APPROVED");
  stub(() => ({ status: 409, body: { code: "PUBLISH_POLICY_MISMATCH", message: "x" } }));
  const b = await api.publish("w1", "p1", "PUBLIC", 7, "ui-key-12345678").then(() => null, (e: unknown) => e as ApiError); assert.equal(b!.status, 409); assert.equal(b!.code, "PUBLISH_POLICY_MISMATCH");
});
