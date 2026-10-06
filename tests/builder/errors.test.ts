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
