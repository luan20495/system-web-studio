// @class: unit — pure logic of the data-source management UI (no browser, no network)
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import type { ConnectorDescriptor } from "@xweb/types";
import { TEST_FAILURE_CODES } from "../../packages/types/src/contract/v2/management";
import {
  bindingFor, checkSourceForm, compactMap, credentialView, explainManagementError, looksLikeSecretKey, managementReadinessFromError, needsReload, slotsOf, testResultView, unboundLive,
} from "../../features/studio/builder/core/dataManagement";
import { explainError } from "../../features/studio/builder/core/errors";
import { stepReadiness } from "../../features/studio/builder/core/dataFlow";
import { DataSourcesPanel } from "../../features/studio/builder/DataSourcesPanel";
import { DataWizard } from "../../features/studio/builder/DataWizard";
import { available, notReady } from "../../features/studio/builder/core/readiness";
import type { DefCtx } from "../../features/studio/builder/ctx";
import { a11yProblems } from "./a11y";
import { doc as baseDoc } from "./fixtures";

const PG: ConnectorDescriptor = { type: "postgres", displayName: "PostgreSQL", status: "AVAILABLE", capabilities: ["QUERY"], configKeys: [{ name: "host", required: true, description: "" }, { name: "database", required: true, description: "" }, { name: "port", required: false, description: "" }], credentialKeys: ["username", "password"], notes: "" };
const PLANNED: ConnectorDescriptor = { ...PG, type: "mysql", displayName: "MySQL", status: "PLANNED" };
const SECRET = "hunter2-very-secret";

test("failed test connection is a RESULT: every documented code has its own plain-language text, warnings never hide a green result", () => {
  for (const code of TEST_FAILURE_CODES) {
    const v = testResultView({ ok: false, code, message: "fixed text" });
    assert.equal(v.state, "FAILED"); assert.equal(v.code, code); assert.ok(v.detail.length > 20, code); assert.doesNotMatch(v.detail, /undefined/);
  }
  assert.equal(testResultView({ ok: false, code: "SOMETHING_NEW", message: "m" }).state, "FAILED");
  const ok = testResultView({ ok: true, latencyMs: 12, warnings: [] }); assert.equal(ok.state, "OK"); assert.match(ok.detail, /12 ms/);
  const warn = testResultView({ ok: true, latencyMs: 9, warnings: ["the database role can write to 3 table(s); use a SELECT-only role"] });
  assert.equal(warn.state, "WARN"); assert.deepEqual(warn.warnings, ["the database role can write to 3 table(s); use a SELECT-only role"]);
});

test("credential view: key NAMES only, never a value; configured/not configured; falls back to hasCredential", () => {
  const v = credentialView({ configured: true, type: "postgres", keys: ["username", "password"], updatedAt: "t", updatedBy: null });
  assert.equal(v.configured, true); assert.match(v.text, /username, password/); assert.match(v.text, /giấu/);
  assert.equal(credentialView({ configured: false, type: "postgres", keys: [], updatedAt: null, updatedBy: null }).configured, false);
  assert.equal(credentialView(undefined, true).configured, true); assert.equal(credentialView(undefined, false).configured, false);
  assert.doesNotMatch(JSON.stringify(credentialView({ configured: true, type: "p", keys: ["password"], updatedAt: null, updatedBy: null })), new RegExp(SECRET));
});

test("source form: required config, secret-looking config refused (secrets belong in the credential), planned connector refused, name rule", () => {
  const ok = { name: "billing-db", type: "postgres", config: { host: "db.example.com", database: "shop" }, credential: { username: "u", password: SECRET } };
  assert.deepEqual(checkSourceForm(ok, PG, { requireCredential: true }), []);
  assert.ok(checkSourceForm({ ...ok, name: "-x" }, PG).some((e) => e.field === "name"));
  assert.ok(checkSourceForm({ ...ok, config: { host: "h" } }, PG).some((e) => e.field === "config" && /database/.test(e.message)));
  assert.ok(checkSourceForm({ ...ok, config: { ...ok.config, password: "x" } }, PG).some((e) => /bí mật/.test(e.message)));
  assert.ok(checkSourceForm({ ...ok, config: { ...ok.config, host: "postgres://u:p@h/db" } }, PG).some((e) => /bí mật/.test(e.message)));
  assert.ok(checkSourceForm({ ...ok, credential: { username: "u" } }, PG, { requireCredential: true }).some((e) => e.field === "credential" && /password/.test(e.message)));
  assert.ok(checkSourceForm(ok, PLANNED).some((e) => e.field === "type"));
  assert.ok(checkSourceForm(ok, undefined).some((e) => e.field === "type"));
  for (const k of ["password", "dbPassword", "api_key", "apiKey", "authToken", "client_secret"]) assert.ok(looksLikeSecretKey(k), k);
  for (const k of ["host", "database", "schemas", "port"]) assert.ok(!looksLikeSecretKey(k), k);
  // no error text ever contains a credential value
  assert.doesNotMatch(JSON.stringify(checkSourceForm({ ...ok, credential: { username: "u" } }, PG, { requireCredential: true })), new RegExp(SECRET));
});

test("compactMap: empty fields are not sent; config is trimmed, credentials are sent as typed", () => {
  assert.deepEqual(compactMap({ host: " h ", port: " ", db: "d" }, true), { host: "h", db: "d" });
  assert.deepEqual(compactMap({ password: " pw ", username: "" }, false), { password: " pw " });
});

test("404 isolation: a foreign source, an unknown id and a project of another workspace read the SAME to the user; 404 without a code means the flag is off", () => {
  const foreign = explainManagementError({ status: 404, code: "NOT_FOUND", message: "not found" });
  const random = explainManagementError({ status: 404, code: "NOT_FOUND", message: "data source not found" });
  const wsProof = explainManagementError({ status: 404, code: "WORKSPACE_NOT_FOUND", message: "x" });
  for (const m of [foreign, random, wsProof]) { assert.equal(m.kind, "not-found"); assert.equal(m.title, foreign.title); assert.equal(m.detail, foreign.detail); assert.match(m.detail, /không tồn tại hoặc không thuộc/); }
  assert.equal(managementReadinessFromError({ status: 404, code: "HTTP_404" })?.state, "NOT_READY");
  assert.equal(managementReadinessFromError({ status: 404 })?.state, "NOT_READY");
  assert.equal(managementReadinessFromError({ status: 404, code: "NOT_FOUND" }), null);
  assert.equal(managementReadinessFromError({ status: 403, code: "PERMISSION_DENIED" }), null);
  assert.equal(explainManagementError({ status: 404, code: "HTTP_404" }).kind, "unavailable");
});

test("management errors: every status of the contract table has its own class and text", () => {
  const k = (e: object, w = false) => explainManagementError(e, { write: w });
  assert.equal(k({ status: 400, code: "INVALID_PARAMS", message: "body has a field that is not accepted" }).kind, "invalid");
  assert.equal(k({ status: 400, code: "INVALID_CONFIG", message: "m" }).field, "config");
  assert.equal(k({ status: 400, code: "INVALID_CREDENTIAL", message: "m" }).field, "credential");
  assert.equal(k({ status: 401 }).kind, "forbidden");
  assert.equal(k({ status: 403, code: "PERMISSION_DENIED" }).kind, "forbidden"); assert.equal(k({ status: 403, code: "PERMISSION_DENIED" }).retrySafe, false);
  assert.equal(k({ status: 409, code: "CONFLICT", message: "name in use" }).kind, "conflict");
  assert.equal(k({ status: 409, code: "DISABLED" }).kind, "unavailable");
  assert.equal(k({ status: 422, code: "UNSUPPORTED_TYPE" }).kind, "invalid");
  assert.equal(k({ status: 429, code: "RATE_LIMITED", message: "Thử lại sau 30s." }).kind, "rate-limited");
  assert.equal(k({ status: 501, code: "NOT_IMPLEMENTED" }).kind, "unavailable");
  assert.equal(k({ status: 500, code: "INTERNAL" }).kind, "error"); assert.equal(k({ status: 500, code: "INTERNAL" }).retrySafe, true);
  assert.doesNotMatch(JSON.stringify(k({ status: 500, code: "INTERNAL" })), /undefined/);
});

test("unknown outcome: a failed WRITE (network, timeout, 5xx) is never 'press again'; the panel reloads instead of guessing", () => {
  for (const e of [{ status: 0, code: "NETWORK" }, { status: 0, code: "TIMEOUT" }, { status: 502, code: "HTTP_502" }, { status: 500, code: "INTERNAL" }]) {
    const m = explainManagementError(e, { write: true });
    assert.equal(m.retrySafe, false, JSON.stringify(e)); assert.ok(needsReload(m), JSON.stringify(e)); assert.match(m.detail, /tải lại danh sách/i);
  }
  assert.equal(explainManagementError({ status: 0, code: "NETWORK" }).retrySafe, true);          // a read can simply be repeated
  assert.ok(needsReload(explainManagementError({ status: 409, code: "CONFLICT" }, { write: true })));
});

test("runtime error codes of the data layer are explained, never as success; an ambiguous mutation is not auto-retryable", () => {
  const unknown = explainError({ status: 409, code: "IDEMPOTENCY_OUTCOME_UNKNOWN", retryable: false }, { write: true });
  assert.equal(unknown.kind, "unknown-outcome"); assert.equal(unknown.retrySafe, false);
  assert.equal(explainError({ status: 422, code: "MUTATION_REJECTED", message: "constraint" }, { write: true }).kind, "rejected");
  assert.equal(explainError({ status: 422, code: "READ_ONLY_VIOLATION" }).kind, "not-executable");
  assert.equal(explainError({ status: 502, code: "AUTH_REJECTED" }).kind, "unavailable"); assert.match(explainError({ status: 502, code: "AUTH_REJECTED" }).title, /đăng nhập/);
  assert.equal(explainError({ status: 409, code: "DISABLED" }).kind, "unavailable");
  assert.equal(explainError({ status: 403, code: "PERMISSION_DENIED" }).kind, "forbidden");
  assert.equal(explainError({ status: 403, code: "FORBIDDEN" }).kind, "forbidden");
  for (const code of ["INVALID_QUERY", "INVALID_CONFIG", "INVALID_EXPRESSION", "INVALID_CREDENTIAL"]) assert.equal(explainError({ status: 400, code }).kind, "invalid", code);
  assert.equal(explainError({ status: 409, code: "IDEMPOTENCY_OUTCOME_UNKNOWN" }, { write: false }).retrySafe, false);
});

test("bindings: TEST and LIVE are separate per slot; a LIVE-unbound slot is reported before publishing", () => {
  const d = baseDoc({ dataSources: [{ id: "erp-db", name: "ERP", type: "CONNECTOR" }, { id: "crm", type: "CONNECTOR" }] } as never);
  const bs = [{ mode: "TEST" as const, slotId: "erp-db", dataSourceId: "a", updatedAt: null }, { mode: "LIVE" as const, slotId: "crm", dataSourceId: "b", updatedAt: null }];
  assert.deepEqual(slotsOf(d).map((s) => s.id), ["erp-db", "crm"]); assert.equal(slotsOf(d)[1].name, "crm");
  assert.equal(bindingFor(bs, "TEST", "erp-db")?.dataSourceId, "a"); assert.equal(bindingFor(bs, "LIVE", "erp-db"), undefined);
  assert.deepEqual(unboundLive(d, bs), ["erp-db"]);
  assert.deepEqual(slotsOf(baseDoc({})), []);
});

test("data step readiness: the source step is available only with a management host; without one it stays NOT_READY (nothing simulated)", () => {
  assert.equal(stepReadiness("source", available()).state, "NOT_READY");
  assert.equal(stepReadiness("source", available(), true).state, "AVAILABLE");
  assert.equal(stepReadiness("discovery", available(), true).state, "NOT_READY");
});

const ctx = (over: Partial<DefCtx> = {}): DefCtx => ({ doc: baseDoc({}), commit: async () => true, readiness: available(), canEdit: true, busy: false, metadata: new Map(), registry: [], labelOf: (t) => t, ...over });

test("panel SSR: no host → NOT_READY (no fake source); with a host the first paint is the loading state; no input ever holds a value", () => {
  const none = renderToStaticMarkup(<DataSourcesPanel doc={baseDoc({})} canView viewReason="" canManage manageReason="" canBind bindReason=""/>);
  assert.match(none, /Chưa sẵn sàng/); assert.match(none, /không có nguồn nào được giả lập/i); assert.doesNotMatch(none, /data-testid="ds-list"/);
  const noop = async () => { throw new Error("not called in SSR"); };
  const calls = { connectors: noop, list: noop, create: noop, update: noop, remove: noop, credential: noop, setCredential: noop, removeCredential: noop, test: noop, listBindings: noop, bind: noop, unbind: noop } as never;
  const html = renderToStaticMarkup(<DataSourcesPanel doc={baseDoc({})} calls={calls} canView viewReason="" canManage manageReason="" canBind bindReason=""/>);
  assert.match(html, /Đang tải/); assert.deepEqual(a11yProblems(html), []);
});

test("data wizard source step: with no host it says Chưa sẵn sàng; the document's slots are listed; with no slot it says so honestly", () => {
  const none = renderToStaticMarkup(<DataWizard ctx={ctx()}/>);
  assert.match(none, /Chưa sẵn sàng/); assert.match(none, /Chưa có khe nào/);
  const withSlot = renderToStaticMarkup(<DataWizard ctx={ctx({ doc: baseDoc({ dataSources: [{ id: "erp-db", name: "ERP", type: "CONNECTOR" }] } as never) })}/>);
  assert.match(withSlot, /ERP/); assert.match(withSlot, /chưa gắn nguồn trong tài liệu/);
  assert.deepEqual(a11yProblems(none), []);
});
