// @class: unit — organization structure + employee directory: the NOT_READY contract layer, the dynamic tree, move/cycle rules, validation, the gate and the error mapping (pure logic, no browser, no network)
import test from "node:test";
import assert from "node:assert/strict";
import type { TenantMemberView } from "@xweb/types";
import { CAPABILITIES, OrganizationNotReady, createOrganizationApi, type OrgCapabilityId, type OrgCapabilityState, type OrgUnit, type OrgUnitType } from "../../features/admin/organization";
import * as M from "../../features/admin/organizationModel";

const u = (id: string, parentId: string | null, name = id, o: Partial<OrgUnit> = {}): OrgUnit => ({ id, parentId, typeId: null, name, enabled: true, version: 1, ...o });
const TYPES: OrgUnitType[] = [
  { id: "t-div", code: "DIVISION", name: "Khối", icon: "building" },
  { id: "t-dept", code: "DEPT", name: "Phòng", icon: "briefcase", allowedParentTypeIds: ["t-div"] },
  { id: "t-team", code: "TEAM", name: "Team", icon: "users", allowedParentTypeIds: ["t-dept", "t-team"] },
];
const TREE = [u("tech", null, "Khối Công nghệ", { typeId: "t-div" }), u("mobile", "tech", "Mobile", { typeId: "t-dept" }), u("flutter", "mobile", "Flutter Team", { typeId: "t-team" }), u("web", "tech", "Web", { typeId: "t-dept" }), u("hr", null, "Nhân sự", { typeId: "t-div" })];
const scope = (tenants: { id: string; name: string }[]) => ({ platform: false, tenants: tenants.map((t) => ({ ...t, slug: t.id, status: "ACTIVE" })), workspaces: [], dataWorkspaces: [] });
const ALL_IDS = Object.keys(CAPABILITIES) as OrgCapabilityId[];

test("contract layer: every operation is NOT_READY (owner C1), names no URL, and the adapter sends NOTHING", async () => {
  for (const id of ALL_IDS) { const c = CAPABILITIES[id]; assert.equal(c.status, "NOT_READY", id); assert.equal((c as { owner: string }).owner, "C1"); assert.ok(!("route" in c), `${id} must not name a route`); assert.ok(!JSON.stringify(c).includes("/api/"), id); }
  const sent: string[] = []; const spy = new Proxy({}, { get: (_t, k) => (...a: unknown[]) => { sent.push(String(k)); return Promise.resolve(a); } }) as never;
  const api = createOrganizationApi(spy, M.employeesFromMembers);
  const calls: [string, () => Promise<unknown>][] = [
    ["listOrganizationUnits", () => api.listOrganizationUnits("t")], ["createOrganizationUnit", () => api.createOrganizationUnit("t", { parentId: null, typeId: null, name: "x" })],
    ["updateOrganizationUnit", () => api.updateOrganizationUnit("t", "u", 1, { name: "y" })], ["moveOrganizationUnit", () => api.moveOrganizationUnit("t", "u", 1, null)], ["deleteOrganizationUnit", () => api.deleteOrganizationUnit("t", "u", 1)],
    ["listOrganizationUnitTypes", () => api.listOrganizationUnitTypes("t")], ["createOrganizationUnitType", () => api.createOrganizationUnitType("t", { code: "A1", name: "A", icon: "folder" })], ["listPositions", () => api.listPositions("t")],
    ["createEmployee", () => api.createEmployee("t", { userId: "u" })], ["updateEmployeeOrganization", () => api.updateEmployeeOrganization("t", "u", null)], ["updateEmployeePosition", () => api.updateEmployeePosition("t", "u", null)],
  ];
  for (const [name, call] of calls) await assert.rejects(call, (e: unknown) => e instanceof OrganizationNotReady && e.capability === name && e.owner === "C1" && /chưa hỗ trợ/.test(e.reason), name);
  assert.deepEqual(sent, [], "nothing reaches the transport while the capability is NOT_READY");
});

test("employee directory falls back to the tenant member list (a real, existing route) and only that", async () => {
  const sent: string[] = []; const members: TenantMemberView[] = [{ tenantId: "t", userId: "u1", role: "TENANT_ADMIN", active: true, username: "an", displayName: "An" }];
  const api = createOrganizationApi({ tenantMembers: async (t) => { sent.push(`members:${t}`); return members; } }, M.employeesFromMembers);
  const page = await api.listEmployees("t1", { page: 0, size: 20 });
  assert.deepEqual(sent, ["members:t1"]); assert.equal(page.source, "members"); assert.equal(page.items[0].username, "an");
  assert.equal(page.items[0].orgUnitId, undefined, "the member list has no organization unit; none is invented");
  await assert.rejects(createOrganizationApi({}, M.employeesFromMembers).listEmployees("t", { page: 0, size: 20 }), OrganizationNotReady);
});

test("a READY capability calls the transport (the wiring C1's contract will flip)", async () => {
  const caps = { ...CAPABILITIES, moveOrganizationUnit: { status: "READY", needs: ["TENANT_MANAGE"], route: "PATCH /x/{tenantId}" } as OrgCapabilityState };
  const sent: unknown[][] = []; const api = createOrganizationApi({ moveOrganizationUnit: async (...a) => { sent.push(a); return u("a", null); } }, M.employeesFromMembers, caps);
  await api.moveOrganizationUnit("t1", "a", 7, "p"); assert.deepEqual(sent, [["t1", "a", 7, "p"]]);
  await assert.rejects(api.deleteOrganizationUnit("t1", "a", 1), OrganizationNotReady);
});

test("tree: dynamic nesting by name, nothing dropped (orphans and pure cycles are surfaced)", () => {
  const t = M.buildTree(TREE);
  assert.deepEqual(t.map((n) => n.unit.id), ["tech", "hr"].sort((a, b) => TREE.find((x) => x.id === a)!.name.localeCompare(TREE.find((x) => x.id === b)!.name, "vi")));
  const tech = t.find((n) => n.unit.id === "tech")!; assert.deepEqual(tech.children.map((c) => c.unit.id), ["mobile", "web"]); assert.equal(tech.children[0].children[0].unit.id, "flutter"); assert.equal(tech.children[0].children[0].depth, 2);
  assert.equal(M.flattenTree(t).length, TREE.length);
  assert.equal(M.flattenTree(t, new Set(["tech"])).length, 4, "collapsed mobile hides flutter");
  const orphan = M.buildTree([u("a", "ghost", "A"), u("b", "a", "B")]); assert.equal(orphan.length, 1); assert.equal(orphan[0].orphan, true); assert.equal(M.flattenTree(orphan).length, 2);
  const loop = M.buildTree([u("x", "y", "X"), u("y", "x", "Y")]); assert.equal(M.flattenTree(loop).length, 2, "a cycle in the data is shown, not lost or looped");
  assert.equal(M.unitPath(TREE, "flutter"), "Khối Công nghệ › Mobile › Flutter Team"); assert.equal(M.unitPath(TREE, null), "");
});

test("move: self / subtree are refused (no cycle), type rules and 'already here' are explained, the root is offered when the type allows it", () => {
  assert.equal(M.wouldCycle(TREE, "tech", "flutter"), true); assert.equal(M.wouldCycle(TREE, "tech", "tech"), true); assert.equal(M.wouldCycle(TREE, "flutter", "web"), false); assert.equal(M.wouldCycle(TREE, "flutter", null), false);
  const t = M.moveTargets(TREE, TYPES, "mobile"); const by = (id: string | null) => t.find((x) => x.id === id)!;
  assert.match(by("mobile").reason!, /chính nó/); assert.match(by("flutter").reason!, /vòng/); assert.equal(by("tech").current, true); assert.match(by("tech").reason!, /Đang ở đây/);
  assert.equal(by("hr").disabled, false, "Phòng may move to another Khối"); assert.match(by(null).reason!, /phải nằm trong: Khối/, "a Phòng cannot be a root");
  const f = M.moveTargets(TREE, TYPES, "flutter"); assert.equal(f.find((x) => x.id === "web")!.disabled, false, "a Team may move to another Phòng"); assert.match(f.find((x) => x.id === "hr")!.reason!, /chỉ đặt được trong: Phòng, Team/, "…but not straight under a Khối");
});

test("forms: unit and type validation (names, codes, parent-type rule, allow-listed icon, no duplicate code)", () => {
  const ctx = { types: TYPES, parent: TREE[0] };
  assert.deepEqual(M.validateUnitForm({ name: "Mobile", code: "", typeId: "t-dept" }, ctx), {});
  assert.match(M.validateUnitForm({ name: "  ", code: "", typeId: null }, ctx).name!, /Hãy nhập tên/); assert.match(M.validateUnitForm({ name: "x".repeat(121), code: "", typeId: null }, ctx).name!, /120/);
  assert.match(M.validateUnitForm({ name: "A", code: "bad code!", typeId: null }, ctx).code!, /Mã gồm/); assert.deepEqual(M.validateUnitForm({ name: "A", code: "MOB-01", typeId: null }, ctx), {});
  assert.match(M.validateUnitForm({ name: "A", code: "", typeId: "t-team" }, ctx).type!, /chỉ đặt được trong/, "a Team directly under a Khối");
  assert.match(M.validateUnitForm({ name: "A", code: "", typeId: "t-dept" }, { types: TYPES, parent: null }).type!, /phải nằm trong/, "a Phòng as a root");
  assert.match(M.validateUnitForm({ name: "A", code: "", typeId: "gone" }, ctx).type!, /không còn tồn tại/);
  assert.deepEqual(M.validateTypeForm({ name: "Chi nhánh", code: "branch", icon: "map-pin" }, TYPES), {});
  assert.match(M.validateTypeForm({ name: "", code: "x", icon: "nope" }, TYPES).name!, /Hãy nhập tên loại/); assert.match(M.validateTypeForm({ name: "A", code: "1X", icon: "folder" }, TYPES).code!, /2–32 ký tự/);
  assert.match(M.validateTypeForm({ name: "A", code: "division", icon: "folder" }, TYPES).code!, /đã được dùng/); assert.match(M.validateTypeForm({ name: "A", code: "AB", icon: "https://x/y.png" }, TYPES).icon!, /danh sách/);
});

test("icons: a closed allow-list; anything else (a URL, markup, an unknown id) falls back to the folder", () => {
  assert.ok(M.UNIT_ICONS.length >= 12 && M.UNIT_ICONS.every((i) => /^[a-z-]+$/.test(i.id))); assert.equal(M.safeIcon("users"), "users");
  for (const bad of ["https://evil.example/x.png", "<svg onload=1>", "../x", "", null, undefined]) assert.equal(M.safeIcon(bad as string), "folder");
});

test("delete: the UI explains a known block (children / employees) and leaves unknown counts to the server", () => {
  assert.match(M.deleteBlock(TREE[0], TREE)!, /2 đơn vị con/); assert.match(M.deleteBlock(u("e", null, "E", { employeeCount: 3 }), [])!, /3 nhân viên/);
  assert.equal(M.deleteBlock(u("x", null), []), null); assert.equal(M.deleteBlock(u("x", null, "X", { childCount: 0, employeeCount: 0 }), []), null);
});

test("employees from the member list: accent-insensitive search, status, stable order, paging clamps", () => {
  const mem = (i: number, o: Partial<TenantMemberView> = {}): TenantMemberView => ({ tenantId: "t", userId: `u${String(i).padStart(2, "0")}`, role: "MEMBER", active: true, username: `user${i}`, displayName: `Nhân viên ${i}`, email: `u${i}@x.vn`, ...o });
  const all = Array.from({ length: 45 }, (_, i) => mem(i + 1, i === 4 ? { displayName: "Nguyễn Đức Anh", active: false } : {}));
  const p0 = M.employeesFromMembers(all, { page: 0, size: 20 }); assert.equal(p0.total, 45); assert.equal(p0.items.length, 20); assert.equal(M.pageCount(p0), 3); assert.equal(p0.source, "members");
  assert.equal(M.employeesFromMembers(all, { page: 2, size: 20 }).items.length, 5); assert.equal(M.employeesFromMembers(all, { page: 99, size: 20 }).page, 2, "a page past the end is clamped");
  assert.deepEqual(M.employeesFromMembers(all, { q: "nguyen duc anh", page: 0, size: 20 }).items.map((e) => e.userId), ["u05"], "search ignores accents and case");
  assert.equal(M.employeesFromMembers(all, { status: "INACTIVE", page: 0, size: 20 }).total, 1); assert.equal(M.employeesFromMembers(all, { status: "ACTIVE", page: 0, size: 20 }).total, 44);
  assert.equal(M.employeesFromMembers(all, { q: "u7@", page: 0, size: 20 }).total, 1); assert.equal(M.employeesFromMembers(all, { q: "zzz", page: 0, size: 20 }).items.length, 0);
  assert.equal(M.employeesFromMembers([], { page: 0, size: 20 }).total, 0); assert.equal(M.employeeName({ displayName: " ", username: "bob" }), "bob");
});

test("gate: offered only when the server lists a tenant the caller administers; one tenant is fixed, several are the caller's own; no role name is read", () => {
  const st = (id: OrgCapabilityId) => CAPABILITIES[id];
  const none = M.organizationPlan(scope([]), st); assert.equal(none.access.granted, false); assert.match((none.access as { reason: string }).reason, /không liệt kê công ty/);
  const one = M.organizationPlan(scope([{ id: "t1", name: "Acme" }]), st); assert.equal(one.access.granted, true); assert.deepEqual(one.fixedTenant, { id: "t1", name: "Acme" }); assert.deepEqual(one.tenantChoice, []);
  const two = M.organizationPlan(scope([{ id: "t1", name: "Acme" }, { id: "t3", name: "C" }]), st); assert.equal(two.fixedTenant, null); assert.deepEqual(two.tenantChoice.map((t) => t.id), ["t1", "t3"]);
  assert.equal(one.units.state, "not-ready"); assert.equal(one.edit.state, "not-ready"); assert.equal(one.directory, "members", "the member list backs the directory while listEmployees is NOT_READY");
  const ready = Object.fromEntries(ALL_IDS.map((id) => [id, { status: "READY", needs: ["TENANT_MANAGE"], route: "x" }])) as Record<OrgCapabilityId, OrgCapabilityState>;
  const r = M.organizationPlan(scope([{ id: "t1", name: "Acme" }]), (id) => ready[id]); assert.equal(r.units.state, "ready"); assert.equal(r.edit.state, "ready"); assert.equal(r.directory, "directory"); assert.equal(r.assignOrg.state, "ready");
  const partial = M.organizationPlan(scope([{ id: "t1", name: "Acme" }]), (id) => (id === "moveOrganizationUnit" ? CAPABILITIES[id] : ready[id])); assert.equal(partial.edit.state, "not-ready", "one missing edit operation disables editing as a whole, with its reason");
});

test("errors: every refusal is mapped to words by code, then by status; an unknown code shows the server's message; NOT_READY is its own kind", () => {
  const k = (e: unknown) => M.orgProblem(e).kind;
  assert.equal(k({ status: 409, code: "ORG_CYCLE" }), "cycle"); assert.equal(k({ status: 409, code: "ORG_HAS_CHILDREN" }), "blocked"); assert.equal(k({ status: 409, code: "ORG_HAS_EMPLOYEES" }), "blocked");
  assert.equal(k({ status: 409, code: "VERSION_CONFLICT" }), "version"); assert.equal(k({ status: 409, code: "ORG_CODE_TAKEN" }), "duplicate"); assert.equal(k({ status: 400, code: "ORG_PARENT_TYPE_INVALID" }), "validation");
  assert.equal(k({ status: 403, code: "FORBIDDEN" }), "forbidden"); assert.equal(k({ status: 404, code: "UNIT_NOT_FOUND" }), "notfound");
  assert.equal(k({ status: 422 }), "validation"); assert.equal(k({ status: 401 }), "forbidden"); assert.equal(k({ status: 409 }), "conflict"); assert.equal(k({ status: 503 }), "unavailable");
  assert.equal(k({ status: 0 }), "unavailable"); assert.match(M.orgProblem({ status: 500 }).text, /Chưa rõ thao tác đã được ghi/); assert.equal(k(new TypeError("fetch failed")), "unavailable");
  assert.equal(k(new OrganizationNotReady("moveOrganizationUnit", "Máy chủ chưa hỗ trợ", "C1")), "not-ready"); assert.match(M.orgProblem({ status: 418, message: "teapot" }).text, /teapot/);
  for (const t of [M.orgProblem({ code: "ORG_CYCLE" }).text, M.orgProblem({ code: "ORG_HAS_EMPLOYEES" }).text]) assert.doesNotMatch(t, /Something went wrong|error/i);
});

test("tree at scale: a 20 000-deep chain does not overflow the stack, 2 000 nodes build in linear time, aria positions are right", () => {
  const chain = Array.from({ length: 20_000 }, (_, i) => u(`c${i}`, i ? `c${i - 1}` : null, `N${i}`));
  const flat = M.flattenTree(M.buildTree(chain)); assert.equal(flat.length, 20_000); assert.equal(flat[19_999].depth, 19_999);
  assert.equal(M.descendantIds(chain, "c0").size, 19_999); assert.equal(M.unitPath(chain.slice(0, 50), "c49").split(" › ").length, 50);
  const wide = Array.from({ length: 2_000 }, (_, i) => u(`w${i}`, i < 10 ? null : `w${i % 10}`, `U${String(i).padStart(4, "0")}`));
  const t0 = performance.now(); const tree = M.buildTree(wide); const f = M.flattenTree(tree); const ms = performance.now() - t0;
  assert.equal(f.length, 2_000); assert.ok(ms < 1_000, `build + flatten of 2000 nodes took ${ms.toFixed(1)} ms`); console.log(`  [metric] buildTree + flattenTree, 2000 nodes: ${ms.toFixed(1)} ms`);
  const root = tree[0]; assert.equal(root.size, 10); assert.deepEqual(root.children.map((c) => c.pos).slice(0, 3), [1, 2, 3]); assert.ok(root.children.every((c) => c.size === root.children.length));
});

test("directory fallback at scale: filtering / paging 10 000 members is a single pass (frontend fixture benchmark, NOT a backend measure)", () => {
  const members: TenantMemberView[] = Array.from({ length: 10_000 }, (_, i) => ({ tenantId: "t", userId: `u${i}`, role: "MEMBER", active: i % 7 !== 0, username: `user${i}`, displayName: `Nhân viên Nguyễn ${i}`, email: `e${i}@x.vn` }));
  const t0 = performance.now(); const p = M.employeesFromMembers(members, { q: "nguyen 99", page: 0, size: 20 }); const ms = performance.now() - t0;
  assert.equal(p.items.length, 20); assert.ok(p.total > 20 && p.total <= 10_000); assert.ok(ms < 1_500, `filter + sort + page of 10 000 members took ${ms.toFixed(1)} ms`);
  const t1 = performance.now(); for (let k = 0; k < 20; k++) M.employeesFromMembers(members, { page: k, size: 20 }); const per = (performance.now() - t1) / 20;
  console.log(`  [metric] employeesFromMembers, 10 000 members: search ${ms.toFixed(1)} ms, page switch ${per.toFixed(1)} ms each`);
  assert.ok(per < 500);
});
