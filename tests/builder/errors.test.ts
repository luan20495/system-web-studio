import test from "node:test";
import assert from "node:assert/strict";
import { explainError, violationsOf } from "../../features/studio/builder/core/errors";
import { outcomeFromError, outcomeFromServer, describeTestEffect, publishInTest } from "../../features/studio/builder/core/testMode";

test("409 IDEMPOTENCY_OUTCOME_UNKNOWN: ambiguous, retry NOT safe, says check the data first", () => {
  const m = explainError({ status: 409, code: "IDEMPOTENCY_OUTCOME_UNKNOWN" });
  assert.equal(m.kind, "unknown-outcome");
  assert.equal(m.retrySafe, false);
  assert.match(m.detail, /kiểm tra/i);
});

test("409 REVISION_CONFLICT is a plain conflict, not the idempotency one", () => {
  const m = explainError({ status: 409, code: "REVISION_CONFLICT" });
  assert.equal(m.kind, "conflict");
  assert.equal(m.retrySafe, true);
});

test("422 MUTATION_REJECTED is rejected (not 'invalid schema')", () => {
  const m = explainError({ status: 422, code: "MUTATION_REJECTED", message: "Giá phải >= 0" });
  assert.equal(m.kind, "rejected");
  assert.match(m.detail, /Giá phải/);
});

test("422 SCHEMA_INVALID lists violations", () => {
  const e = { status: 422, code: "SCHEMA_INVALID", details: { violations: [{ path: "a", message: "sai A" }, { path: "b", message: "sai B" }] } };
  assert.equal(violationsOf(e).length, 2);
  assert.match(explainError(e).detail, /sai A/);
});

test("404/501 -> unavailable (NOT_READY), 403 -> forbidden", () => {
  assert.equal(explainError({ status: 404 }).kind, "unavailable");
  assert.equal(explainError({ status: 501 }).kind, "unavailable");
  assert.equal(explainError({ status: 403 }).kind, "forbidden");
});

test("test mode: errors become outcomes; never SUCCESS", () => {
  assert.equal(outcomeFromError({ status: 409, code: "IDEMPOTENCY_OUTCOME_UNKNOWN" }).state, "UNKNOWN");
  assert.equal(outcomeFromError({ status: 422, code: "MUTATION_REJECTED" }).state, "REJECTED");
  assert.equal(outcomeFromError({ status: 404 }).state, "NOT_READY");
  assert.equal(outcomeFromError(new Error("x")).state, "ERROR");
});

test("test mode: SUCCESS only when the server says so", () => {
  assert.equal(outcomeFromServer({ status: "SUCCESS" }).state, "SUCCESS");
  assert.equal(outcomeFromServer({}).state, "NOT_READY");
  assert.equal(outcomeFromServer(null).state, "NOT_READY");
  assert.equal(outcomeFromServer({ status: "weird" }).state, "NOT_READY");
});

test("test mode rules per action kind", () => {
  const a = (type: string) => ({ id: "a", name: "a", type } as never);
  assert.equal(describeTestEffect(a("UPDATE_RECORD")).state, "WOULD_RUN");
  assert.equal(describeTestEffect(a("CALL_API")).state, "UNSUPPORTED");
  assert.equal(describeTestEffect(a("CALL_API"), { connectorSupportsDryRun: true }).state, "WOULD_RUN");
  assert.equal(describeTestEffect(a("NOTIFY")).state, "NOT_SENT");
  assert.equal(describeTestEffect(a("START_WORKFLOW")).state, "WOULD_RUN");
  assert.match((describeTestEffect(a("START_WORKFLOW")) as { note: string }).note, /workflow_run/);
  assert.equal(publishInTest().state, "NOT_RUN");
});

import { backendFrom } from "../../features/studio/builder/core/backend";

test("backend probe: 404 -> definition operations NOT_READY with reason; ok -> AVAILABLE; 500 -> ERROR; loading -> LOADING", () => {
  assert.equal(backendFrom({ status: "loading" }).definitionOps.state, "LOADING");
  const nf = backendFrom({ status: "error", error: { status: 404 } });
  assert.equal(nf.definitionOps.state, "NOT_READY");
  assert.ok(nf.definitionOps.state === "NOT_READY" && nf.definitionOps.reason.length > 10);
  assert.equal(backendFrom({ status: "error", error: { status: 500, message: "boom" } }).definitionOps.state, "ERROR");
  const ok = backendFrom({ status: "ok", metadata: [{ id: "Hero" } as never] });
  assert.equal(ok.definitionOps.state, "AVAILABLE"); assert.ok(ok.metadata.has("Hero"));
});

// ---- Phase 3 prep: the rest of the data-runtime.md §4b write-path table (frozen 2026-10-06) ---------------------------------
test("409 IDEMPOTENCY_IN_PROGRESS is 'wait', not 'the app changed elsewhere'; IDEMPOTENCY_CONFLICT is a non-retryable client bug", () => {
  const p = explainError({ status: 409, code: "IDEMPOTENCY_IN_PROGRESS" });
  assert.equal(p.kind, "in-progress"); assert.equal(p.retrySafe, true);
  const c = explainError({ status: 409, code: "IDEMPOTENCY_CONFLICT" });
  assert.equal(c.kind, "key-conflict"); assert.equal(c.retrySafe, false);
  assert.notEqual(p.kind, "conflict"); assert.notEqual(c.kind, "conflict");
});

test("422 MUTATION_UNSUPPORTED / READ_ONLY_VIOLATION / INVALID_MAPPING / MAPPING_FAILED / UNSUPPORTED_TYPE: nothing applied, not retryable, not 'schema invalid'", () => {
  for (const code of ["MUTATION_UNSUPPORTED", "READ_ONLY_VIOLATION", "INVALID_MAPPING", "MAPPING_FAILED", "UNSUPPORTED_TYPE"]) {
    const m = explainError({ status: 422, code });
    assert.equal(m.kind, "not-executable", code); assert.equal(m.retrySafe, false, code);
  }
});

test("429 RATE_LIMITED is retryable with backoff", () => {
  assert.equal(explainError({ status: 429, code: "RATE_LIMITED" }).kind, "rate-limited");
  assert.equal(explainError({ status: 429 }).retrySafe, true);
});

test("a 500/502/504/network failure of a WRITE is 'outcome unknown' and not retry-safe; the same failure of a read stays a plain error", () => {
  for (const status of [500, 502, 504, 0]) {
    const w = explainError({ status, code: "TIMEOUT" }, { write: true });
    assert.equal(w.kind, "unknown-outcome", String(status)); assert.equal(w.retrySafe, false, String(status));
    assert.equal(explainError({ status, code: "TIMEOUT" }).kind, "error", String(status));
  }
  assert.equal(outcomeFromError({ status: 504, code: "TIMEOUT" }, { write: true }).state, "UNKNOWN");
  assert.equal(outcomeFromError({ status: 504, code: "TIMEOUT" }).state, "ERROR");
});

test("4xx permission/validation answers of a write are NOT treated as unknown outcome", () => {
  assert.equal(explainError({ status: 403, code: "PERMISSION_DENIED" }, { write: true }).kind, "forbidden");
  assert.equal(explainError({ status: 422, code: "MUTATION_REJECTED" }, { write: true }).kind, "rejected");
});

test("C4 action runtime codes map to the same user classes (ActionErrorCodes)", () => {
  assert.equal(explainError({ status: 409, code: "ACTION_IN_PROGRESS" }).kind, "in-progress");
  assert.equal(explainError({ status: 409, code: "IDEMPOTENCY_KEY_REUSED" }).kind, "key-conflict");
  assert.equal(explainError({ status: 400, code: "IDEMPOTENCY_KEY_REQUIRED" }).retrySafe, false);
  assert.equal(explainError({ status: 403, code: "TENANT_DISABLED" }).kind, "suspended");
  assert.equal(explainError({ status: 503, code: "DEPENDENCY_UNAVAILABLE" }).kind, "unavailable");
  assert.equal(explainError({ status: 503, code: "DEPENDENCY_UNAVAILABLE" }, { write: true }).retrySafe, false);
  assert.equal(explainError({ status: 422, code: "INVALID_INPUT" }).kind, "invalid");
  assert.equal(explainError({ status: 200, code: "IDEMPOTENCY_OUTCOME_UNKNOWN" }).kind, "unknown-outcome");
});

test("a C4 TEST-mode WouldRun answer is shown as 'would run' at every level and is never a success", () => {
  for (const level of ["NOT_EXECUTED", "VALIDATED", "SANDBOX"]) {
    const o = outcomeFromServer({ actionId: "a1", type: "CREATE_RECORD", level, plan: {}, reason: "không hỗ trợ dry-run" });
    assert.equal(o.state, "WOULD_RUN", level);
    assert.match((o as { note: string }).note, /theo máy chủ/);
  }
  assert.equal(outcomeFromServer({ level: "SOMETHING_NEW" }).state, "NOT_READY");
});
