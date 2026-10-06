import test from "node:test";
import assert from "node:assert/strict";
import * as C from "../../features/studio/builder/core/contract";
import { staticReadiness } from "../../features/studio/builder/core/readiness";

test("contract mirror is versioned and pinned to integration/v2", () => {
  assert.equal(C.CONTRACT_VERSION, "v2");
  assert.match(C.CONTRACT_SOURCE.commit, /^8b944cc/);
  assert.match(C.CONTRACT_SOURCE.firstMirroredAt, /^c59604b/);
  assert.match(C.CONTRACT_SOURCE.verifiedAgainst, /^4884be3/);
  assert.equal(C.CONTRACT_SOURCE.branch, "integration/v2");
});

test("14 canonical permission codes, incl. TENANT_MANAGE and TENANT_MEMBERS, nothing invented", () => {
  assert.equal(C.PERMISSION_CODES.length, 14);
  assert.ok(C.PERMISSION_CODES.includes("TENANT_MANAGE"));
  assert.ok(C.PERMISSION_CODES.includes("TENANT_MEMBERS"));
  assert.equal(new Set(C.PERMISSION_CODES).size, 14);
});

test("9 canonical action types, REFRESH_QUERY present, RUN_QUERY and friends rejected", () => {
  assert.equal(C.ACTION_TYPES.length, 9);
  assert.ok(C.ACTION_TYPES.includes("REFRESH_QUERY"));
  for (const alias of ["RUN_QUERY", "WRITE_DATA", "CALL_CONNECTOR_OPERATION", "SET_VALUE"]) {
    assert.ok(!(C.ACTION_TYPES as readonly string[]).includes(alias), alias);
    assert.ok((C.REJECTED_ACTION_ALIASES as readonly string[]).includes(alias), alias);
  }
});

test("param types include DATE and required defaults to true", () => {
  assert.ok(C.PARAM_TYPES.includes("DATE"));
  assert.equal(C.paramRequired({}), true);
  assert.equal(C.paramRequired({ required: false }), false);
});

test("event wire names are the six canonical ones", () => {
  assert.deepEqual([...C.EVENT_TYPES].sort(), ["onChange", "onClick", "onError", "onLoad", "onSubmit", "onSuccess"]);
});

test("static NOT_READY features carry a reason, none is faked AVAILABLE", () => {
  for (const k of ["DATA_SOURCES", "SCHEMA_DISCOVERY", "QUERY_PREVIEW", "ACTION_RUNTIME", "WORKFLOW_RUNTIME", "TEST_MODE", "SHARING"] as const) {
    const r = staticReadiness(k);
    assert.equal(r.state, "NOT_READY", k);
    assert.ok(r.state === "NOT_READY" && r.reason.length > 10);
  }
});
