// @class: unit — what the Admin / Platform consoles show to whom, and the rules of the tenant screens
import test from "node:test";
import assert from "node:assert/strict";
import type { Me, TenantMemberView } from "@xweb/types";
import * as M from "../../features/admin/adminModel";
import { relatedPeople, peopleWhen } from "../../features/admin/shared/peopleSections";
import { ApiError } from "../../packages/api-client/src/core";
import { errorText } from "../../packages/api-client/src/errorText";

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
test("one mapper (M-075): admin refusals come from the shared catalog by code; a non-ApiError is never printed", () => {
  const ae = (code: string, status: number, message?: string) => new ApiError(status, code, message ?? "english server text");
  assert.equal(errorText(ae("LAST_TENANT_ADMIN", 409), "x"), "Công ty phải còn ít nhất một quản trị viên.");
  assert.equal(errorText(ae("DEFAULT_TENANT_PROTECTED", 409), "x"), "Công ty mặc định không thể bị tạm khóa hoặc xóa.");
  assert.equal(errorText(ae("ADMIN_REQUIRED", 403), "x"), "Màn hình này chỉ dành cho quản trị hệ thống.");
  assert.match(errorText(ae("HTTP_403", 403), "x"), /không có quyền/); assert.match(errorText(ae("HTTP_404", 404), "x"), /Không tìm thấy/);
  assert.equal(errorText(new SyntaxError("Unexpected token <"), "Chưa lưu được"), "Chưa lưu được");
  assert.ok(!/boom|english server text/.test(errorText(ae("HTTP_500", 500, "boom"), "Chưa lưu được")));
});

test("workspace member panel opens only on MEMBER_MANAGE of THAT workspace: a SYSTEM_ADMIN (tenant codes only) and a plain member are not offered it; an absent field does not block", () => {
  const sys = me({ platformScope: true, workspaces: [ws("w1", ["TENANT_MANAGE", "TENANT_MEMBERS"])] });
  const wsa = me({ workspaces: [ws("w1", ["MEMBER_MANAGE", "APP_VIEW"]), ws("w2", ["APP_VIEW"])] });
  assert.equal(M.canManageWorkspaceMembers(sys, "w1"), false);
  assert.equal(M.canManageWorkspaceMembers(wsa, "w1"), true);
  assert.equal(M.canManageWorkspaceMembers(wsa, "w2"), false);
  assert.equal(M.canManageWorkspaceMembers(wsa, "nope"), false);
  assert.equal(M.canManageWorkspaceMembers(null, "w1"), false);
  assert.equal(M.canManageWorkspaceMembers(me({ workspaces: [{ id: "w9", name: "old", role: "x", tenantId: "t" } as never] }), "w9"), true);
  // a role NAME grants nothing
  assert.equal(M.canManageWorkspaceMembers(me({ workspaces: [{ ...ws("w3", []), role: "WORKSPACE_ADMIN" }] }), "w3"), false);
});

test("slugify proposes a valid company code from a Vietnamese name; initials make an avatar", () => {
  assert.equal(M.slugify("Công ty Cổ phần Ánh Dương"), "cong-ty-co-phan-anh-duong");
  assert.equal(M.slugify("  Đại học  Bách Khoa!! "), "dai-hoc-bach-khoa");
  assert.equal(M.slugify("ACME_Corp 2026"), "acme-corp-2026");
  assert.equal(M.slugify("***"), "");
  assert.equal(M.slugify("a".repeat(200)).length, 120);
  for (const n of ["Công ty Cổ phần Ánh Dương", "ACME_Corp 2026", "Đại học Bách Khoa"]) assert.deepEqual(M.checkTenantForm({ slug: M.slugify(n), name: n }), {});
  assert.equal(M.initials({ username: "tom.le", displayName: "Tom Lê" }), "TL");
  assert.equal(M.initials({ username: "app.creator", displayName: null }), "AC");
  assert.equal(M.initials({ username: "pubku0sdsj", displayName: "pubku0sdsj" }), "P");
  assert.equal(M.initials({ username: "x", displayName: "Nguyễn Văn Đức" }), "ND");
  assert.equal(M.initials({ username: "x", displayName: "Bùi Văn Đức (12)" }), "BD", "a trailing counter is not a word");
  assert.equal(M.initials({ username: "x", displayName: "(12)" }), "X", "no letters in the name: fall back to the username");
});

test("initials: bracket groups, counters, punctuation, emoji, empty input and any Unicode letters never give '(' or crash", () => {
  const i = (displayName: string | null, username = "u") => M.initials({ username, displayName });
  assert.equal(i("App Creator (Demo)"), "AC"); assert.equal(i("Tenant Admin (Demo)"), "TA"); assert.equal(i("(Demo)", "app.creator"), "AC", "only a bracket group: fall back to the username");
  assert.equal(i("Nguyễn Văn An"), "NA"); assert.equal(i("Đặng Ánh"), "DA"); assert.equal(i("Bùi Văn Đức (12)"), "BD"); assert.equal(i("Lê  Hoàng   Anh"), "LA");
  assert.equal(i("Tom"), "T"); assert.equal(i("tom.le"), "TL"); assert.equal(i("A [QA] B {x}"), "AB"); assert.equal(i("Mary-Jane O'Neil"), "MO");
  assert.equal(i(""), "U", "empty display name falls back to the username"); assert.equal(i("   ", "bob"), "B"); assert.equal(i(null, "x"), "X");
  assert.equal(i("😀"), "U", "emoji-only falls back to the username"); assert.equal(i("😀 🎉", "😀"), "?", "nothing with a letter anywhere → '?'"); assert.equal(i("!!! ---"), "U"); assert.equal(i("(", "("), "?"); assert.equal(i("()", ""), "?");
  assert.equal(i("Иван Петров"), "ИП", "Cyrillic"); assert.equal(i("山田 太郎"), "山太", "CJK"); assert.equal(i("José Álvarez"), "JA");
  assert.equal(i("a".repeat(5000) + " " + "b".repeat(5000)), "AB", "a very long name is fine");
  for (const v of ["", " ", "😀", "(Demo)", "!!!", "a(", ")(", "\u0000", "\uD83D"]) assert.doesNotThrow(() => M.initials({ username: v, displayName: v }), JSON.stringify(v));
});

test("canActInWorkspace (M-009): a platform admin who is NOT in the workspace has no workspace-scoped actions (D-C1-13A); a member, or the legacy businessAccess flag, keeps them; nobody gets none", () => {
  const sysOut = me({ platformScope: true, systemAdmin: true, businessAccess: false, workspaces: [ws("w1", ["APP_VIEW"])] });
  assert.equal(M.canActInWorkspace(sysOut, "w9"), false, "not a member of w9, no business access");
  assert.equal(M.canActInWorkspace(sysOut, "w1"), true, "a member of w1: the server decides what the role allows");
  assert.equal(M.canActInWorkspace(me({ platformScope: true, systemAdmin: true, businessAccess: true }), "w9"), true, "the server says business access (legacy flag): keep the controls");
  assert.equal(M.canActInWorkspace(me({ platformScope: true, systemAdmin: true }), "w9"), false, "an older /auth/me without the field is NOT read as business access");
  assert.equal(M.canActInWorkspace(null, "w1"), false); assert.equal(M.canActInWorkspace(undefined, "w1"), false);
});

test("M-098 the activation link uses the configured Studio origin, else the current one; the token stays in the fragment", () => {
  assert.equal(M.activationUrl("TOK", "https://app.xweb.vn/", "http://10.0.0.5:3202"), "https://app.xweb.vn/auth/activate#TOK");
  assert.equal(M.activationUrl("TOK", "", "http://localhost:3202"), "http://localhost:3202/auth/activate#TOK");
});

test("M-065 the people screens stay separate; the cross-links list only the sibling screens the person can open", () => {
  const tadmin = M.adminScope(me({ platformScope: false, tenantRole: "TENANT_ADMIN", permissions: ["TENANT_MEMBERS"], tenantId: "t1", tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "TENANT_ADMIN" }] } as Partial<Me>));
  assert.deepEqual(relatedPeople("employees", tadmin).map((p) => p.key), ["company", "organization", "people"]);
  const wsadmin = M.adminScope(me({ workspaces: [ws("w1", ["MEMBER_MANAGE"])] }));
  assert.deepEqual(relatedPeople("people", wsadmin).map((p) => p.key), []);
  assert.equal(peopleWhen("people")(wsadmin), true);
  const sys = M.adminScope(me({ platformScope: true, tenantId: null, tenants: [], permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] } as Partial<Me>));
  assert.deepEqual(relatedPeople("company", sys), []);
});
