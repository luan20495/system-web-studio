// @class: unit — the C1 permission contract as pure logic (packages/permissions/src/canonical.ts). Frontend capability checks are UX only: the server re-checks everything.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import * as P from "../../packages/permissions/src/canonical";
import { capabilitiesOf, resolvePortalPostLogin } from "../../packages/permissions/src/index";
import { capabilitiesFor, testGates } from "../../features/studio/builder/core/permissions";
import type { Me } from "@xweb/types";

const set = (...c: string[]) => P.resolvePermissions(c);

// ---- Studio access / project view / edit ------------------------------------------------------------------------------------------------------------
test("APP_VIEW allows Studio (portal gate and project view)", () => {
  assert.equal(P.canViewStudioIn(["APP_VIEW"]), true);
  assert.equal(P.canViewProject(set("APP_VIEW")), true);
});
test("no APP_VIEW denies Studio: an EMPTY list is an answer, and APP_USE / APP_EDIT / data codes never stand in for APP_VIEW", () => {
  assert.equal(P.canViewStudioIn([]), false);
  for (const c of ["APP_USE", "APP_EDIT", "APP_PUBLISH", "DATA_SOURCE_VIEW", "QUERY_EXECUTE", "ACTION_EXECUTE", "TENANT_MANAGE"]) {
    assert.equal(P.canViewStudioIn([c]), false, c); assert.equal(P.canViewProject(set(c)), false, c);
  }
});
test("an ABSENT list (older backend) cannot say no: it does not block (UX only, the server still answers 403/404)", () => {
  assert.equal(P.canViewStudioIn(undefined), true); assert.equal(P.canViewStudioIn(null), true);
});
test("APP_VIEW without APP_EDIT => read-only (opens, never redirects); APP_EDIT lifts it", () => {
  const viewer = set("APP_VIEW", "APP_USE");
  assert.equal(P.canViewProject(viewer), true); assert.equal(P.canEditProject(viewer), false); assert.equal(P.isReadOnlyProject(viewer), true);
  assert.equal(P.isReadOnlyProject(set("APP_VIEW", "APP_EDIT")), false);
  assert.equal(P.isReadOnlyProject(set()), false, "no APP_VIEW is not 'read-only', it is no access");
  const cap = capabilitiesFor(["APP_VIEW", "APP_USE"]);
  assert.equal(cap.canView, true); assert.equal(cap.canEdit, false); assert.equal(cap.canPublish, false); assert.equal(cap.canManageDataSources, false);
});
test("the project payload still carries storage names: PROJECT_READ is APP_VIEW (documented alias); unknown codes are dropped, never invented", () => {
  assert.deepEqual([...P.resolvePermissions(["APP_USE", "PROJECT_READ"])].sort(), ["APP_USE", "APP_VIEW"]);
  assert.deepEqual([...P.resolvePermissions(["PROJECT_CREATE", "AUDIT_READ", "SUPER", "VIEWER"])], []);
  assert.equal(P.canViewProject(P.resolvePermissions(["APP_USE", "PROJECT_READ"])), true);
});

// ---- test query / actions / workflows -------------------------------------------------------------------------------------------------------------------
test("TEST_QUERY requires ALL THREE of APP_USE + QUERY_EXECUTE + APP_EDIT", () => {
  assert.equal(P.canRunTestQuery(set("APP_USE", "QUERY_EXECUTE", "APP_EDIT")), true);
  for (const lacking of ["APP_USE", "QUERY_EXECUTE", "APP_EDIT"]) assert.equal(P.canRunTestQuery(set(...["APP_USE", "QUERY_EXECUTE", "APP_EDIT"].filter((c) => c !== lacking))), false, `without ${lacking}`);
  assert.match(testGates(["APP_VIEW", "QUERY_EXECUTE"]).query() ?? "", /Dùng ứng dụng.*Chỉnh sửa ứng dụng/);
  assert.equal(testGates(["APP_USE", "QUERY_EXECUTE", "APP_EDIT"]).query(), null);
});
test("ACTION_RUN requires APP_USE + ACTION_EXECUTE (not ACTION_EXECUTE alone)", () => {
  const nav = { type: "NAVIGATE" };
  assert.equal(P.canRunAction(set("APP_USE", "ACTION_EXECUTE"), nav), true);
  assert.equal(P.canRunAction(set("ACTION_EXECUTE"), nav), false); assert.equal(P.canRunAction(set("APP_USE"), nav), false);
  assert.deepEqual(P.actionRequires(nav), ["APP_USE", "ACTION_EXECUTE"]);
});
test("a mutating action additionally requires DATA_MUTATE (the server's DATA_MUTATING set)", () => {
  for (const type of ["SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API"]) {
    assert.deepEqual(P.actionRequires({ type }), ["APP_USE", "ACTION_EXECUTE", "DATA_MUTATE"], type);
    assert.equal(P.canRunAction(set("APP_USE", "ACTION_EXECUTE"), { type }), false, type);
    assert.equal(P.canRunAction(set("APP_USE", "ACTION_EXECUTE", "DATA_MUTATE"), { type }), true, type);
  }
  assert.ok(!P.actionRequires({ type: "NOTIFY" }).includes("DATA_MUTATE") && !P.actionRequires({ type: "REFRESH_QUERY" }).includes("DATA_MUTATE"));
});
test("an action that declares its own permission additionally requires it; a START_WORKFLOW action also needs WORKFLOW_EXECUTE (what the server checks)", () => {
  assert.deepEqual(P.actionRequires({ type: "NAVIGATE", declaredPermission: "APP_PUBLISH" }), ["APP_USE", "ACTION_EXECUTE", "APP_PUBLISH"]);
  assert.deepEqual(P.actionRequires({ type: "NAVIGATE", declaredPermission: "NOT_A_CODE" }), ["APP_USE", "ACTION_EXECUTE"], "a non-canonical declaration is ignored, not invented");
  assert.deepEqual(P.actionRequires({ type: "START_WORKFLOW" }), ["APP_USE", "ACTION_EXECUTE", "WORKFLOW_EXECUTE"]);
});
test("a TEST action additionally requires APP_EDIT", () => {
  const a = { type: "CREATE_RECORD", declaredPermission: "APP_SHARE" };
  assert.deepEqual(P.actionRequires(a, { test: true }), ["APP_USE", "ACTION_EXECUTE", "DATA_MUTATE", "APP_SHARE", "APP_EDIT"]);
  assert.equal(P.canRunAction(set("APP_USE", "ACTION_EXECUTE", "DATA_MUTATE", "APP_SHARE"), a, { test: true }), false);
  assert.equal(P.canRunAction(set("APP_USE", "ACTION_EXECUTE", "DATA_MUTATE", "APP_SHARE", "APP_EDIT"), a, { test: true }), true);
  assert.match(testGates(["APP_USE", "ACTION_EXECUTE"]).action({ type: "NAVIGATE" }) ?? "", /Chỉnh sửa ứng dụng/);
});
test("WORKFLOW start requires APP_USE + WORKFLOW_EXECUTE", () => {
  assert.equal(P.canStartWorkflow(set("APP_USE", "WORKFLOW_EXECUTE")), true);
  assert.equal(P.canStartWorkflow(set("WORKFLOW_EXECUTE")), false); assert.equal(P.canStartWorkflow(set("APP_USE")), false);
  assert.equal(P.canStartWorkflow(set("APP_USE", "WORKFLOW_MANAGE")), false, "WORKFLOW_MANAGE does not start a run");
});
test("a TEST workflow additionally requires APP_EDIT", () => {
  assert.equal(P.canStartWorkflow(set("APP_USE", "WORKFLOW_EXECUTE"), { test: true }), false);
  assert.equal(P.canStartWorkflow(set("APP_USE", "WORKFLOW_EXECUTE", "APP_EDIT"), { test: true }), true);
  assert.equal(testGates(["APP_USE", "WORKFLOW_EXECUTE", "APP_EDIT"]).workflow(), null);
});
test("status / cancel: the creator is NOT an authority in the UI (C4 F-1 is open); only WORKFLOW_MANAGE is a hint", () => {
  assert.equal(P.canManageWorkflows(set("WORKFLOW_MANAGE")), true); assert.equal(P.canManageWorkflows(set("WORKFLOW_EXECUTE", "APP_USE")), false);
  assert.equal(P.canManageWorkflows.length, 1, "the helper takes the permission set only: there is no 'creator' input to trust");
});

// ---- publish / rollback / data sources ---------------------------------------------------------------------------------------------------------------
test("APP_PUBLISH enables publish AND rollback; APP_EDIT does not", () => {
  assert.equal(P.canPublish(set("APP_PUBLISH")), true); assert.equal(P.canRollback(set("APP_PUBLISH")), true);
  for (const f of [P.canPublish, P.canRollback]) { assert.equal(f(set("APP_EDIT", "APP_VIEW", "APP_SHARE")), false); }
});
test("DATA_SOURCE_VIEW only gives the metadata view", () => {
  const v = set("DATA_SOURCE_VIEW");
  assert.equal(P.canViewDataSources(v), true); assert.equal(P.canManageDataSources(v), false); assert.equal(P.canBindDataSources(v), false);
  assert.equal(P.canViewDataSources(set("DATA_SOURCE_MANAGE")), false, "MANAGE does not imply VIEW: each is granted separately");
});
test("DATA_SOURCE_MANAGE gates management; a TEST/draft binding additionally requires APP_EDIT", () => {
  assert.equal(P.canManageDataSources(set("DATA_SOURCE_MANAGE")), true);
  assert.equal(P.canBindDataSources(set("DATA_SOURCE_MANAGE")), false);
  assert.equal(P.canBindDataSources(set("DATA_SOURCE_MANAGE", "APP_EDIT")), true);
  assert.equal(P.canBindDataSources(set("APP_EDIT")), false);
  const c = capabilitiesFor(["DATA_SOURCE_MANAGE", "DATA_SOURCE_VIEW"]); assert.equal(c.canManageDataSources, true); assert.equal(c.canBindDataSources, false);
});

// ---- role names change nothing -----------------------------------------------------------------------------------------------------------------------
test("a role NAME alone changes nothing: only the resolved permission list is read", () => {
  for (const role of ["VIEWER", "EDITOR", "PUBLISHER", "OWNER", "WORKSPACE_ADMIN", "ADMIN"]) {
    assert.deepEqual([...P.resolvePermissions([role])], [], role);
    const c = capabilitiesFor([role]); assert.ok(Object.values(c).every((v) => v === false), role);
  }
  const base: Me = { id: "u", username: "u", displayName: "U", roles: ["ADMIN", "VIEWER"], workspaces: [] };
  const withRole = (role: string, permissions: string[]) => ({ ...base, workspaces: [{ id: "w", name: "W", role, tenantId: "t", permissions }] }) as unknown as Me;
  // the same permissions give the same answer whatever the role label says; the same role label with different permissions gives different answers
  assert.equal(capabilitiesOf(withRole("VIEWER", ["APP_VIEW"])).has("studio.build"), true);
  assert.equal(capabilitiesOf(withRole("WORKSPACE_ADMIN", ["APP_VIEW"])).has("studio.build"), true);
  assert.equal(capabilitiesOf(withRole("WORKSPACE_ADMIN", [])).has("studio.build"), false, "an admin LABEL with an empty resolved list is not Studio access");
  assert.equal(capabilitiesOf(withRole("VIEWER", [])).has("studio.build"), false);
  assert.equal(resolvePortalPostLogin({ me: withRole("VIEWER", ["APP_VIEW"]), portal: "studio" }), "/studio");
  assert.equal(resolvePortalPostLogin({ me: withRole("EDITOR", []), portal: "studio" }), "/auth/no-access?portal=studio");
});
test("GUARD: no Studio source decides anything from a role name (role === \"VIEWER\" | \"EDITOR\" | …)", () => {
  const roots = ["features/studio", "packages/permissions/src", "packages/auth/src", "packages/ui/src"].map((d) => join(__dirname, "..", "..", "..", d));
  const bad: string[] = [];
  const walk = (d: string) => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else if (/\.(ts|tsx)$/.test(n)) {
    readFileSync(p, "utf8").split("\n").forEach((line, i) => { if (/^\s*(\*|\/\/|\/\*)/.test(line)) return; if (/\brole\w*\s*[!=]==?\s*"(VIEWER|EDITOR|PUBLISHER|OWNER|WORKSPACE_ADMIN|ADMIN)"/.test(line) || /"(VIEWER|EDITOR|PUBLISHER|WORKSPACE_ADMIN)"\s*\)?\s*\.includes\(\s*\w*role/i.test(line)) bad.push(`${p.split("xweb-c5/").pop()}:${i + 1}: ${line.trim().slice(0, 100)}`); }); } } };
  roots.forEach(walk);
  assert.deepEqual(bad, [], "a role label is data, not authority; use the resolved permission list");
});
