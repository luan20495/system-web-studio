// @class: unit — what the Admin / Platform consoles show to whom, and the rules of the tenant screens
import test from "node:test";
import assert from "node:assert/strict";
import type { Me, TenantMemberView } from "@xweb/types";
import * as M from "../../features/admin/adminModel";

const me = (o: Partial<Me> = {}): Me => ({ id: "u1", username: "a", displayName: "A", roles: [], workspaces: [], ...o });
const ws = (id: string, permissions: string[]) => ({ id, name: `W-${id}`, role: "x", tenantId: "t", permissions });
const mem = (userId: string, role: string, active = true): TenantMemberView => ({ tenantId: "t", userId, role, active });

test("scope: SYSTEM_ADMIN = platform; TENANT_MEMBERS lists the primary tenant; MEMBER_MANAGE lists workspaces; data codes list data workspaces", () => {
  const sys = M.adminScope(me({ platformScope: true, tenantId: null, tenants: [], permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] }));
  assert.equal(sys.platform, true);
  const tenantAdmin = M.adminScope(me({ platformScope: false, tenantId: "t1", tenantRole: "TENANT_ADMIN", permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"],
    tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "TENANT_ADMIN" }, { id: "t2", slug: "b", name: "B", status: "ACTIVE", role: "MEMBER" }],
    workspaces: [ws("w1", ["MEMBER_MANAGE", "DATA_SOURCE_MANAGE"]), ws("w2", ["APP_VIEW"]), ws("w3", ["DATA_SOURCE_VIEW"])] }));
  assert.equal(tenantAdmin.platform, false); assert.deepEqual(tenantAdmin.tenants.map((t) => t.id), ["t1"], "only the tenants where the server says TENANT_ADMIN (t2 is a plain MEMBER)");
  const second = M.adminScope(me({ platformScope: false, permissions: [], tenantId: "t1", tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "MEMBER" }, { id: "t2", slug: "b", name: "B", status: "ACTIVE", role: "TENANT_ADMIN" }] }));
  assert.deepEqual(second.tenants.map((t) => t.id), ["t2"], "admin of a NON-primary tenant is listed too");
  assert.deepEqual(tenantAdmin.workspaces.map((w) => w.id), ["w1"]); assert.deepEqual(tenantAdmin.dataWorkspaces.map((w) => w.id), ["w1", "w3"]);
  const plain = M.adminScope(me({ platformScope: false, permissions: [], tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "MEMBER" }], tenantId: "t1", workspaces: [ws("w", ["APP_VIEW"])] }));
  assert.deepEqual([plain.platform, plain.tenants.length, plain.workspaces.length, plain.dataWorkspaces.length], [false, 0, 0, 0]);
  assert.deepEqual(M.adminScope(null), { platform: false, tenants: [], workspaces: [], dataWorkspaces: [] });
});
test("sections: system-only sections need the platform; scoped sections need their own scope; the overview is for everyone", () => {
  const none = M.adminScope(me()); const sys = M.adminScope(me({ platformScope: true }));
  for (const k of ["users", "workspaces", "applications", "audit", "system", "settings", "tenants", "ai", "identity"]) { assert.equal(M.sectionAccess(k, none), "needs-platform", k); assert.equal(M.sectionAccess(k, sys), "ok", k); }
  assert.equal(M.sectionAccess("", none), "ok");
  assert.equal(M.sectionAccess("company", none), "needs-scope"); assert.equal(M.sectionAccess("my-workspaces", none), "needs-scope"); assert.equal(M.sectionAccess("data-sources", none), "needs-scope");
  const wsAdmin = M.adminScope(me({ workspaces: [ws("w", ["MEMBER_MANAGE"])] })); assert.equal(M.sectionAccess("my-workspaces", wsAdmin), "ok"); assert.equal(M.sectionAccess("company", wsAdmin), "needs-scope");
  assert.equal(M.sectionAccess("company", sys), "ok", "a SYSTEM_ADMIN may open any tenant");
});
test("tenant form mirrors TenantService: slug 2–120 of a-z 0-9 -, name required ≤160", () => {
  assert.deepEqual(M.checkTenantForm({ slug: "acme-vn", name: "Acme" }), {});
  assert.deepEqual(M.checkTenantForm({ slug: " ACME ", name: "Acme" }), {}, "the server lowercases and trims");
  for (const slug of ["a", "-a", "a-", "a_b", "a b", "x".repeat(121), ""]) assert.ok(M.checkTenantForm({ slug, name: "n" }).slug, slug);
  assert.ok(M.checkTenantForm({ slug: "ok", name: " " }).name); assert.ok(M.checkTenantForm({ slug: "ok", name: "x".repeat(161) }).name);
});
test("status actions: ACTIVE → suspend/delete, SUSPENDED → unlock/delete, DELETED → restore; the DEFAULT tenant offers none", () => {
  const t = (status: string, id = "t") => ({ id, status, name: "Acme" });
  assert.deepEqual(M.tenantActions(t("ACTIVE")).map((a) => a.to), ["SUSPENDED", "DELETED"]);
  assert.deepEqual(M.tenantActions(t("SUSPENDED")).map((a) => a.to), ["ACTIVE", "DELETED"]);
  assert.deepEqual(M.tenantActions(t("DELETED")).map((a) => a.to), ["ACTIVE"]);
  assert.deepEqual(M.tenantActions(t("ACTIVE", M.DEFAULT_TENANT_ID)), []);
  assert.ok(M.tenantActions(t("ACTIVE")).every((a) => a.confirm.includes("Acme")));
  assert.equal(M.tenantActions(t("ACTIVE")).find((a) => a.to === "DELETED")!.danger, true);
});
test("member rows: named from the member row (C1 directory metadata), an older backend falls back to the people map, an unresolved id is shown as such; inactive members are not listed", () => {
  const withMeta = (userId: string, role: string, username: string, displayName: string | null, email: string | null): TenantMemberView => ({ ...mem(userId, role), username, displayName, email });
  const rows = M.tenantMemberRows([withMeta("u2", "TENANT_ADMIN", "bao", "Bảo", "bao@x.vn"), withMeta("u3", "MEMBER", "an", null, null), mem("deadbeef-0000", "MEMBER"), mem("u4", "MEMBER", false)]);
  assert.deepEqual(rows.map((r) => [r.label, r.known, r.roleLabel, r.email]), [["an", true, "Thành viên", null], ["Bảo (bao)", true, "Quản trị công ty", "bao@x.vn"], ["Người dùng deadbeef…", false, "Thành viên", null]]);
  const people = new Map([["u9", { id: "u9", username: "cu", displayName: "Cũ" }]]);
  assert.equal(M.tenantMemberRows([mem("u9", "MEMBER")], people)[0].label, "Cũ (cu)");
});
test("candidate directory: 2+ characters when a search is typed, empty asks for the first 50, the label carries the email", () => {
  assert.deepEqual(M.candidateQuery(""), { ask: true }); assert.deepEqual(M.candidateQuery("  "), { ask: true });
  assert.equal(M.candidateQuery("a").ask, false); assert.match(M.candidateQuery("a").hint!, /ít nhất 2/);
  assert.deepEqual(M.candidateQuery(" ab "), { ask: true, q: "ab" });
  assert.equal(M.CANDIDATE_MAX_RESULTS, 50);
  assert.equal(M.candidateLabel({ username: "bao", displayName: "Bảo", email: "b@x.vn" }), "Bảo (bao) · b@x.vn"); assert.equal(M.candidateLabel({ username: "an", displayName: null, email: null }), "an");
});
test("rules the server enforces are said before the click: self change, last TENANT_ADMIN", () => {
  const all = [mem("u1", "TENANT_ADMIN"), mem("u2", "TENANT_ADMIN"), mem("u3", "MEMBER")];
  assert.match(M.memberChangeBlock(all[0], { id: "u1" }, all, "MEMBER")!, /tự đổi/);
  assert.equal(M.memberChangeBlock(all[1], { id: "u1" }, all, "MEMBER"), null, "another admin remains");
  const one = [mem("u1", "TENANT_ADMIN"), mem("u3", "MEMBER")];
  assert.match(M.memberChangeBlock(one[0], { id: "u9" }, one, "REMOVE")!, /ít nhất một quản trị/);
  assert.equal(M.memberChangeBlock(one[1], { id: "u1" }, one, "REMOVE"), null);
  const wm = (userId: string, role: string) => ({ userId, username: userId, displayName: null, email: null, role, joinedAt: "" });
  const wsAll = [wm("a", "WORKSPACE_ADMIN"), wm("b", "EDITOR")];
  assert.match(M.workspaceMemberBlock(wsAll[0], { id: "z" }, wsAll, "EDITOR")!, /ít nhất một quản trị/);
  assert.match(M.workspaceMemberBlock(wsAll[1], { id: "b" }, wsAll, "VIEWER")!, /tự đổi/);
  assert.equal(M.workspaceMemberBlock(wsAll[1], { id: "z" }, wsAll, "VIEWER"), null);
});
test("server refusals are explained by CODE in Vietnamese; unknown ones keep the fallback and the server's text", () => {
  assert.equal(M.adminErrorText({ code: "LAST_TENANT_ADMIN" }, "x"), "Công ty phải còn ít nhất một quản trị viên.");
  assert.equal(M.adminErrorText({ code: "DEFAULT_TENANT_PROTECTED" }, "x"), "Công ty mặc định không thể bị tạm khóa hoặc xóa.");
  assert.equal(M.adminErrorText({ code: "ADMIN_REQUIRED", status: 403 }, "x"), "Màn hình này chỉ dành cho quản trị hệ thống.");
  assert.match(M.adminErrorText({ status: 403 }, "x"), /không có quyền/); assert.match(M.adminErrorText({ status: 404 }, "x"), /Không tìm thấy/);
  assert.equal(M.adminErrorText({ status: 500, message: "boom" }, "Chưa lưu được"), "Chưa lưu được (boom)");
});
