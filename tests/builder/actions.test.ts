import test from "node:test";
import assert from "node:assert/strict";
import * as A from "../../features/studio/builder/core/actions";
import { preflight } from "../../features/studio/builder/core/preflight";
import { doc } from "./fixtures";

const d = () => doc({
  dataSources: [{ id: "ds1", name: "d" }],
  queries: [{ id: "qr", name: "Đọc", dataSourceRef: "ds1", mode: "READ", operationKey: "k.read", params: [] }, { id: "qw", name: "Ghi", dataSourceRef: "ds1", mode: "WRITE", operationKey: "k.write", params: [] }],
  workflows: [{ id: "wf1", name: "W", trigger: "MANUAL", steps: [{ id: "s1", kind: "ACTION", actionRef: "child" }, { id: "end", kind: "END" }] }],
  actions: [{ id: "child", name: "Con", type: "NOTIFY", channel: "IN_APP", templateRef: "tpl-1" }],
} as never);
const trig = { sectionId: "s-hero", event: "onClick" } as const;

test("exactly the nine canonical types have labels; REFRESH_QUERY present, RUN_QUERY absent", () => {
  assert.equal(Object.keys(A.ACTION_LABEL).length, 9);
  assert.ok("REFRESH_QUERY" in A.ACTION_LABEL);
  assert.ok(!("RUN_QUERY" in A.ACTION_LABEL));
  assert.equal(A.isActionType("RUN_QUERY"), false);
  assert.equal(A.isRejectedAlias("RUN_QUERY"), true);
});

test("RUN_QUERY (alias) is refused with a pointer to REFRESH_QUERY", () => {
  const r = A.checkAction({ id: "a", name: "x", type: "RUN_QUERY", queryRef: "qr", trigger: trig } as never, d());
  assert.equal(r.length, 1); assert.match(r[0].message, /REFRESH_QUERY/);
});

test("REFRESH_QUERY needs an existing READ query", () => {
  const ok = { id: "a", name: "x", type: "REFRESH_QUERY", queryRef: "qr", trigger: trig } as never;
  assert.deepEqual(A.checkAction(ok, d()), []);
  assert.ok(A.checkAction({ id: "a", name: "x", type: "REFRESH_QUERY", trigger: trig } as never, d()).length, "missing queryRef");
  assert.ok(A.checkAction({ id: "a", name: "x", type: "REFRESH_QUERY", queryRef: "ghost", trigger: trig } as never, d()).length);
  assert.ok(A.checkAction({ id: "a", name: "x", type: "REFRESH_QUERY", queryRef: "qw", trigger: trig } as never, d()).length, "WRITE query cannot be refreshed");
});

test("trigger is OPTIONAL: UI-bound has one, child (workflow step) does not and is valid; unattached is classified", () => {
  const doc0 = d();
  assert.equal(A.roleOf(doc0.actions![0], doc0), "CHILD");
  const bound = { id: "b", name: "b", type: "NAVIGATE", pageRef: "home", trigger: trig } as never;
  assert.equal(A.roleOf(bound, doc0), "UI_BOUND");
  assert.deepEqual(A.checkAction(doc0.actions![0], doc0), []);
  const lone = { id: "z", name: "z", type: "NAVIGATE", pageRef: "home" } as never;
  assert.equal(A.roleOf(lone, doc0), "UNATTACHED");
  assert.ok(preflight({ ...doc0, actions: [...doc0.actions!, lone] }).some((i) => i.code === "ACTION_UNATTACHED"));
});

test("no arbitrary code: forbidden keys (sql/script/url/headers/token/...) are reported anywhere in a declaration", () => {
  assert.deepEqual(A.forbiddenKeys({ type: "CALL_API", operationKey: "k", input: { sql: "x" }, headers: { a: 1 } }).sort(), ["headers", "input.sql"]);
  assert.ok(A.checkAction({ id: "a", name: "x", type: "NAVIGATE", pageRef: "home", script: "alert(1)", trigger: trig } as never, d()).some((i) => /script/.test(i.message)));
});

test("which permission the server checks per action kind (informational)", () => {
  assert.equal(A.permissionToRun("UPDATE_RECORD"), "DATA_MUTATE");
  assert.equal(A.permissionToRun("START_WORKFLOW"), "WORKFLOW_EXECUTE");
  assert.equal(A.permissionToRun("REFRESH_QUERY"), "APP_USE");
  assert.equal(A.permissionToRun("NOTIFY"), "ACTION_EXECUTE");
});

test("events/actions offered come from component-metadata only", () => {
  assert.deepEqual(A.eventsFor(undefined), []);
  const meta = { events: [{ name: "onClick", label: "x", supportedActions: ["NAVIGATE", "RUN_QUERY", "REFRESH_QUERY"] }, { name: "onBogus", label: "b", supportedActions: [] }] } as never;
  assert.deepEqual(A.eventsFor(meta).map((e) => e.name), ["onClick"]);
  assert.deepEqual(A.actionTypesFor(meta, "onClick"), ["NAVIGATE", "REFRESH_QUERY"]);
  assert.deepEqual(A.actionTypesFor(undefined, "onClick"), []);
});

test("describeAction reads naturally and survives a deleted target", () => {
  const doc0 = d();
  const s = A.describeAction({ id: "a", name: "x", type: "REFRESH_QUERY", queryRef: "qr", trigger: trig } as never, doc0);
  assert.match(s, /Làm mới|làm mới/); assert.match(s, /Đọc/);
  assert.match(A.describeAction({ id: "a", name: "x", type: "NAVIGATE", pageRef: "gone", trigger: trig } as never, doc0), /đã xoá/);
});

test("ops use canonical typed definition operations", () => {
  const op = A.actionOps.add({ id: "a", name: "x", type: "REFRESH_QUERY", queryRef: "qr", trigger: trig } as never);
  assert.equal(op.type, "ADD_ACTION"); assert.equal(op.definitionId, "a");
  assert.equal(A.actionOps.remove("a").type, "REMOVE_ACTION");
});
