// @class: unit — organization structure + employee directory over the typed service: request shaping and DTO mapping, the two counts, the dynamic tree, placement rules (advisory), validation, paging guards, the gate and the error mapping (pure logic, no browser, no network)
import test from "node:test";
import assert from "node:assert/strict";
import type { OrgUnitDto, OrgUnitNodeDto, OrgUnitTypeDto } from "@xweb/types";
import { ApiError } from "../../packages/api-client/src/core";
import { CAPABILITIES, OrganizationNotReady, createOrganizationApi, flattenUnitNodes, typeFromDto, type OrgCapabilityId, type OrgCapabilityState, type OrgUnit, type OrgUnitType, type OrganizationTransport } from "../../features/admin/organization";
import * as M from "../../features/admin/organizationModel";
import { adminScope } from "../../features/admin/adminModel";

const NO_RULES = { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: null, maxDepth: null };
const u = (id: string, parentId: string | null, name = id, o: Partial<OrgUnit> = {}): OrgUnit => ({ id, parentId, typeId: "t-none", name, code: id.toUpperCase(), sortOrder: 0, active: true, archivedAt: null, version: 1, directMemberCount: null, subtreeEmployeeCount: null, childCount: 0, ...o });
const ty = (id: string, code: string, name: string, rules: Partial<OrgUnitType["rules"]> = {}, o: Partial<OrgUnitType> = {}): OrgUnitType => ({ id, code, name, icon: "folder", active: true, version: 0, rules: { ...NO_RULES, ...rules }, ...o });
const TYPES: OrgUnitType[] = [ty("t-div", "division", "Khối"), ty("t-dept", "dept", "Phòng", { allowedParentTypeIds: ["t-div"] }), ty("t-team", "team", "Team", { allowedParentTypeIds: ["t-dept", "t-team"] })];
const TREE = [u("tech", null, "Khối Công nghệ", { typeId: "t-div" }), u("mobile", "tech", "Mobile", { typeId: "t-dept" }), u("flutter", "mobile", "Flutter Team", { typeId: "t-team" }), u("web", "tech", "Web", { typeId: "t-dept" }), u("hr", null, "Khối Nhân sự", { typeId: "t-div" })];
const FULL_ORG = { structureView: true, structureManage: true, employeeView: true, employeeManage: true, employeeProvision: true, positionGradeView: true, positionGradeManage: true };
const scope = (tenants: { id: string; name: string }[], org = FULL_ORG) => ({ org, platform: false, tenants: tenants.map((t) => ({ ...t, slug: t.id, status: "ACTIVE" })), workspaces: [], dataWorkspaces: [] });
const ALL_IDS = Object.keys(CAPABILITIES) as OrgCapabilityId[];
const dto = (id: string, parentId: string | null, o: Partial<OrgUnitDto> = {}): OrgUnitDto => ({ id, tenantId: "t", typeId: "t-div", parentId, name: id, code: id.toUpperCase(), sortOrder: 0, metadata: {}, active: true, version: 0, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z", archivedAt: null, ...o });
const node = (unit: OrgUnitDto, children: OrgUnitNodeDto[] = [], direct: number | null = null, subtree: number | null = null): OrgUnitNodeDto => ({ unit, children, directMemberCount: direct, subtreeEmployeeCount: subtree });

// ------------------------------------------------------------------------------------------------------------------------------- service: request shaping and mapping
test("service: every request goes to the typed transport with the contract body (trimmed names, expectedVersion, explicit newParentId, tenant only as the first argument)", async () => {
  const sent: [string, unknown[]][] = []; const rec = (k: string, ret: unknown) => (...a: unknown[]) => { sent.push([k, a]); return Promise.resolve(ret); };
  const unitDto = dto("a", null); const typeDto: OrgUnitTypeDto = { id: "ty", tenantId: "t", name: "K", code: "k", icon: "folder", active: true, rules: NO_RULES, version: 2, createdAt: "", updatedAt: "" };
  const api = createOrganizationApi({ createUnit: rec("createUnit", unitDto), updateUnit: rec("updateUnit", unitDto), moveUnit: rec("moveUnit", unitDto), archiveUnit: rec("archiveUnit", unitDto), restoreUnit: rec("restoreUnit", unitDto), createUnitType: rec("createUnitType", typeDto), updateUnitType: rec("updateUnitType", typeDto), disableUnitType: rec("disableUnitType", typeDto), enableUnitType: rec("enableUnitType", typeDto) } as unknown as OrganizationTransport);
  await api.createOrganizationUnit("t1", { parentId: "p", typeId: "ty", name: "  Mobile  ", code: " mob " });
  await api.updateOrganizationUnit("t1", "a", 4, { name: " Web " });
  await api.moveOrganizationUnit("t1", "a", 5, null);
  await api.moveOrganizationUnit("t1", "a", 6, "p2");
  await api.archiveOrganizationUnit("t1", "a", 7); await api.restoreOrganizationUnit("t1", "a", 8);
  await api.createOrganizationUnitType("t1", { name: " Khối ", code: " DIVISION ", icon: "building", rules: { maxDepth: 4 } });
  await api.updateOrganizationUnitType("t1", "ty", 2, { rules: { allowRoot: false } });
  await api.setOrganizationUnitTypeActive("t1", "ty", 2, false); await api.setOrganizationUnitTypeActive("t1", "ty", 3, true);
  assert.deepEqual(sent, [
    ["createUnit", ["t1", { typeId: "ty", parentId: "p", name: "Mobile", code: "mob" }]],
    ["updateUnit", ["t1", "a", { name: "Web", expectedVersion: 4 }]],
    ["moveUnit", ["t1", "a", { newParentId: null, expectedVersion: 5 }]],
    ["moveUnit", ["t1", "a", { newParentId: "p2", expectedVersion: 6 }]],
    ["archiveUnit", ["t1", "a", 7]], ["restoreUnit", ["t1", "a", 8]],
    ["createUnitType", ["t1", { name: "Khối", code: "division", icon: "building", rules: { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: null, maxDepth: 4 } }]],
    ["updateUnitType", ["t1", "ty", { rules: { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: false, maxDepth: null }, expectedVersion: 2 }]],
    ["disableUnitType", ["t1", "ty", 2]], ["enableUnitType", ["t1", "ty", 3]],
  ]);
});

test("service: employees, memberships and held positions send the nested contract bodies (positions inside the membership), nothing the server would ignore", async () => {
  const sent: [string, unknown[]][] = []; const rec = (k: string) => (...a: unknown[]) => { sent.push([k, a]); return Promise.resolve({ employee: { userId: "e" }, activation: null, items: [], total: 0, page: 0, size: 20 }); };
  const keys = ["createEmployee", "addMembership", "updateMembership", "removeMembership", "addEmployeePosition", "updateEmployeePosition", "removeEmployeePosition", "disableEmployee", "enableEmployee", "listEmployees", "createPosition", "createGrade", "updateGrade"];
  const api = createOrganizationApi(Object.fromEntries(keys.map((k) => [k, rec(k)])) as unknown as OrganizationTransport);
  await api.createEmployee("t1", { username: " An.Nguyen ", displayName: " An ", email: " a@x.vn ", tenantRole: "MEMBER", workspace: { id: "w", role: "EDITOR" }, memberships: [{ unitId: "u1", primary: true, positions: [{ positionId: "p1", gradeId: "g1", primary: true }] }] });
  await api.addMembership("t1", "e", { unitId: "u2", relationType: "MANAGER", primary: true });
  await api.updateMembership("t1", "e", "m", 3, { relationType: "HEAD" });
  await api.removeMembership("t1", "e", "m", 4);
  await api.addEmployeePosition("t1", "e", { membershipId: "m", positionId: "p", gradeId: "g" });
  await api.updateEmployeePosition("t1", "e", "ep", 2, { clearGrade: true }); await api.updateEmployeePosition("t1", "e", "ep", 3, { gradeId: "g2", primary: true });
  await api.removeEmployeePosition("t1", "e", "ep", 5);
  await api.setEmployeeActive("t1", "e", false); await api.setEmployeeActive("t1", "e", true);
  await api.createPosition("t1", { name: " Kỹ sư ", code: " ENG " }); await api.createGrade("t1", { name: "Senior", code: "SR", rank: 3 }); await api.updateGrade("t1", "g", 1, { clearRank: true });
  assert.deepEqual(sent.map((s) => s[0]), ["createEmployee", "addMembership", "updateMembership", "removeMembership", "addEmployeePosition", "updateEmployeePosition", "updateEmployeePosition", "removeEmployeePosition", "disableEmployee", "enableEmployee", "createPosition", "createGrade", "updateGrade"]);
  assert.deepEqual(sent[0][1], ["t1", { username: "an.nguyen", displayName: "An", email: "a@x.vn", tenantRole: "MEMBER", workspaceId: "w", workspaceRole: "EDITOR", organizationMemberships: [{ organizationUnitId: "u1", primary: true, positions: [{ positionId: "p1", gradeId: "g1", primary: true }] }] }]);
  assert.deepEqual(sent[1][1], ["t1", "e", { organizationUnitId: "u2", relationType: "MANAGER", primary: true }]);
  assert.deepEqual(sent[2][1], ["t1", "e", "m", { relationType: "HEAD", expectedVersion: 3 }]);
  assert.deepEqual(sent[4][1], ["t1", "e", { membershipId: "m", positionId: "p", gradeId: "g" }]);
  assert.deepEqual(sent[5][1], ["t1", "e", "ep", { clearGrade: true, expectedVersion: 2 }]); assert.deepEqual(sent[6][1], ["t1", "e", "ep", { gradeId: "g2", primary: true, expectedVersion: 3 }]);
  assert.deepEqual(sent[10][1], ["t1", { name: "Kỹ sư", code: "ENG" }]); assert.deepEqual(sent[12][1], ["t1", "g", { clearRank: true, expectedVersion: 1 }]);
});

test("service: the directory request is clamped into the server's limits BEFORE it is sent (size 1..100, page * size <= 10000, a search of at least 2 characters)", async () => {
  const seen: Record<string, unknown>[] = [];
  const api = createOrganizationApi({ listEmployees: (_t: string, q: Record<string, unknown>) => { seen.push(q); return Promise.resolve({ items: [], total: 0, page: Number(q.page), size: Number(q.size) }); } } as unknown as OrganizationTransport);
  await api.listEmployees("t", { page: 0, size: 5000 });
  await api.listEmployees("t", { page: 100_000, size: 100 });
  await api.listEmployees("t", { page: 9999, size: 20 });
  await api.listEmployees("t", { page: -3, size: 0 });
  await api.listEmployees("t", { page: 0, size: 20, q: " a ", orgUnitId: "u", status: "INACTIVE", positionId: "p", gradeId: "g" });
  await api.listEmployees("t", { page: 0, size: 20, q: " an ", status: "ALL" });
  assert.deepEqual(seen.map((q) => [q.page, q.size]), [[0, 100], [100, 100], [500, 20], [0, 1], [0, 20], [0, 20]]);
  for (const q of seen) { assert.ok(Number(q.size) >= 1 && Number(q.size) <= 100); assert.ok(Number(q.page) * Number(q.size) <= 10_000, `offset ${Number(q.page) * Number(q.size)}`); }
  assert.equal(seen[4].q, undefined, "one character is never sent"); assert.equal(seen[4].organizationUnitId, "u"); assert.equal(seen[4].includeDescendants, true); assert.equal(seen[4].active, false); assert.equal(seen[4].positionId, "p"); assert.equal(seen[4].gradeId, "g");
  assert.equal(seen[5].q, "an"); assert.equal(seen[5].active, undefined); assert.equal(seen[5].organizationUnitId, undefined);
});

test("counts mapping: the tree keeps directMemberCount and subtreeEmployeeCount SEPARATE exactly as the server sent them (distinct subtree is not the sum), null stays unknown, archived children are not child units", () => {
  const rows = flattenUnitNodes([
    node(dto("root", null), [
      node(dto("a", "root"), [node(dto("a1", "a"), [], 2, 2)], 3, 4),                                  // a: 3 direct, 4 in the branch (one person is in a AND a1)
      node(dto("b", "root"), [], 5, 5),
      node(dto("old", "root", { active: false, archivedAt: "2026-02-01T00:00:00Z" }), [], 0, 0),
    ], 1, 9),                                                                                          // root: 1 direct; 9 DISTINCT in the whole branch, not 1+4+5+0
    node(dto("lone", null), [], null, null),
  ]);
  const by = Object.fromEntries(rows.map((r) => [r.id, r]));
  assert.deepEqual(rows.map((r) => r.id), ["root", "a", "a1", "b", "old", "lone"], "parents first, server order");
  assert.deepEqual([by.root.directMemberCount, by.root.subtreeEmployeeCount], [1, 9]); assert.deepEqual([by.a.directMemberCount, by.a.subtreeEmployeeCount], [3, 4]);
  assert.notEqual(by.root.subtreeEmployeeCount, by.root.directMemberCount! + by.a.subtreeEmployeeCount! + by.b.subtreeEmployeeCount!, "a distinct count is not the sum of its children");
  assert.deepEqual([by.lone.directMemberCount, by.lone.subtreeEmployeeCount], [null, null], "unknown stays null, never 0");
  assert.equal(by.root.childCount, 2, "an archived child is not a child unit"); assert.equal(by.old.active, false); assert.equal(by.old.archivedAt, "2026-02-01T00:00:00Z");
  assert.equal(typeFromDto({ id: "x", tenantId: "t", name: "X", code: "x", icon: null, active: true, rules: undefined as never, version: 1, createdAt: "", updatedAt: "" }).icon, "", "a type without icon / rules maps to safe defaults");
});

test("counts display: two labels, two meanings; unknown is not zero; the subtree label says 'distinct' and is never the word used for the direct count", () => {
  const d = M.directCountView({ directMemberCount: 12 }); const s = M.subtreeCountView({ subtreeEmployeeCount: 30 });
  assert.notEqual(d.label, s.label); assert.equal(d.value, "12"); assert.equal(s.value, "30");
  assert.match(d.label, /trực tiếp/); assert.match(s.label, /cả nhánh/); assert.doesNotMatch(d.label, /nhân viên/i, "the direct count is not called 'employees'");
  assert.match(s.title, /một lần/, "the tooltip says a person in two units counts once"); assert.match(d.title, /không tính đơn vị con/);
  assert.equal(M.directCountView({ directMemberCount: 0 }).value, "0"); assert.equal(M.directCountView({ directMemberCount: 0 }).known, true);
  const unknown = M.subtreeCountView({ subtreeEmployeeCount: null }); assert.equal(unknown.known, false); assert.equal(unknown.value, "chưa có số liệu");
  assert.equal(M.unitRowSummary(u("x", null, "X", { directMemberCount: 3, subtreeEmployeeCount: 8, childCount: 2 })), "3 trực tiếp · 8 cả nhánh · 2 đơn vị con");
  assert.equal(M.unitRowSummary(u("x", null, "X")), "", "no counts and no children: nothing is invented");
  assert.equal(M.unitRowSummary(u("x", null, "X", { directMemberCount: 0, subtreeEmployeeCount: 0 })), "0 trực tiếp · 0 cả nhánh");
});

// ------------------------------------------------------------------------------------------------------------------------------- tree and placement rules
test("tree: dynamic nesting in the server's order (sortOrder, name), nothing dropped (orphans and pure cycles are surfaced)", () => {
  const t = M.buildTree(TREE);
  assert.deepEqual(t.map((n) => n.unit.id), ["tech", "hr"].sort((a, b) => TREE.find((x) => x.id === a)!.name.localeCompare(TREE.find((x) => x.id === b)!.name, "vi")));
  const tech = t.find((n) => n.unit.id === "tech")!; assert.deepEqual(tech.children.map((c) => c.unit.id), ["mobile", "web"]); assert.equal(tech.children[0].children[0].unit.id, "flutter"); assert.equal(tech.children[0].children[0].depth, 2);
  assert.deepEqual(M.buildTree([u("z", null, "Z", { sortOrder: 0 }), u("a", null, "A", { sortOrder: 5 })]).map((n) => n.unit.id), ["z", "a"], "sortOrder first, then name");
  assert.equal(M.flattenTree(t).length, TREE.length); assert.equal(M.flattenTree(t, new Set(["tech"])).length, 4, "collapsed mobile hides flutter");
  const orphan = M.buildTree([u("a", "ghost", "A"), u("b", "a", "B")]); assert.equal(orphan.length, 1); assert.equal(orphan[0].orphan, true); assert.equal(M.flattenTree(orphan).length, 2);
  const loop = M.buildTree([u("x", "y", "X"), u("y", "x", "Y")]); assert.equal(M.flattenTree(loop).length, 2, "a cycle in the data is shown, not lost or looped");
  assert.equal(M.unitPath(TREE, "flutter"), "Khối Công nghệ › Mobile › Flutter Team"); assert.equal(M.unitPath(TREE, null), "");
  assert.equal(M.childCountOf([u("p", null), u("c", "p"), u("d", "p", "D", { active: false })], "p"), 1, "an archived child is not counted");
});

test("M-111: pathResolver gives the same path as unitPath for every unit (one map for many rows, a loop is cut), and moveTargets with a prebuilt tree equals the one without", () => {
  const path = M.pathResolver(TREE);
  for (const x of TREE) assert.equal(path(x.id), M.unitPath(TREE, x.id), x.id);
  assert.equal(path(null), ""); assert.equal(path("nope"), ""); assert.equal(path("flutter"), path("flutter"));
  const loop = [u("x", "y", "X"), u("y", "x", "Y")]; assert.equal(M.pathResolver(loop)("x"), M.unitPath(loop, "x"));
  const tree = M.buildTree(TREE);
  for (const x of TREE) assert.deepEqual(M.moveTargets(TREE, TYPES, x.id, tree), M.moveTargets(TREE, TYPES, x.id), x.id);
});

test("move: self / subtree / archived are refused (no cycle), type rules and 'already here' are explained, the root is offered only when the type allows it", () => {
  assert.equal(M.wouldCycle(TREE, "tech", "flutter"), true); assert.equal(M.wouldCycle(TREE, "tech", "tech"), true); assert.equal(M.wouldCycle(TREE, "flutter", "web"), false); assert.equal(M.wouldCycle(TREE, "flutter", null), false);
  const t = M.moveTargets(TREE, TYPES, "mobile"); const by = (id: string | null) => t.find((x) => x.id === id)!;
  assert.match(by("mobile").reason!, /chính nó/); assert.match(by("flutter").reason!, /vòng/); assert.equal(by("tech").current, true); assert.match(by("tech").reason!, /Đang ở đây/);
  assert.equal(by("hr").disabled, false, "Phòng may move to another Khối"); assert.match(by(null).reason!, /phải nằm trong: Khối/, "a Phòng cannot be a root");
  const f = M.moveTargets(TREE, TYPES, "flutter"); assert.equal(f.find((x) => x.id === "web")!.disabled, false, "a Team may move to another Phòng"); assert.match(f.find((x) => x.id === "hr")!.reason!, /chỉ đặt được trong: Phòng, Team/);
  const withArchived = [...TREE, u("gone", "tech", "Gone", { typeId: "t-dept", active: false, archivedAt: "2026-01-01" })];
  assert.match(M.moveTargets(withArchived, TYPES, "web").find((x) => x.id === "gone")!.reason!, /lưu trữ/, "an archived unit is not a target");
});

test("placement rules (tenant data, advisory): root policy, parent type list, child type list, leaf type, allowRoot override", () => {
  const types = [
    ty("a", "a", "A"), ty("b", "b", "B", { allowedParentTypeIds: ["a"] }), ty("rootOnly", "ro", "RootOnly", { allowedParentTypeIds: [] }), ty("leaf", "leaf", "Leaf", { allowedChildTypeIds: [] }),
    ty("onlyB", "ob", "OnlyB", { allowedChildTypeIds: ["b"] }), ty("noRoot", "nr", "NoRoot", { allowRoot: false }), ty("forceRoot", "fr", "ForceRoot", { allowedParentTypeIds: ["a"], allowRoot: true }),
  ];
  const T2 = (id: string) => types.find((t) => t.id === id)!; const un = (typeId: string) => u(`u-${typeId}`, null, typeId, { typeId });
  assert.equal(M.parentRule(T2("a"), null, types), null, "no rules: anywhere, also the root"); assert.equal(M.parentRule(T2("a"), un("b"), types), null);
  assert.match(M.parentRule(T2("b"), null, types)!, /phải nằm trong: A/); assert.equal(M.parentRule(T2("b"), un("a"), types), null); assert.match(M.parentRule(T2("b"), un("b"), types)!, /chỉ đặt được trong: A/);
  assert.equal(M.parentRule(T2("rootOnly"), null, types), null, "[] parents = root only: the root is fine"); assert.match(M.parentRule(T2("rootOnly"), un("a"), types)!, /chỉ được làm đơn vị gốc/);
  assert.match(M.parentRule(T2("a"), un("leaf"), types)!, /không chứa đơn vị con/, "a leaf type has no children"); assert.match(M.parentRule(T2("a"), un("onlyB"), types)!, /chỉ chứa: B/); assert.match(M.parentRule(T2("b"), un("onlyB"), types)!, /chỉ đặt được trong: A/, "the parent list of the type itself still applies");
  assert.match(M.parentRule(T2("noRoot"), null, types)!, /không được làm đơn vị gốc/); assert.equal(M.parentRule(T2("forceRoot"), null, types), null, "allowRoot=true wins over a parent list");
  assert.equal(M.parentRule(undefined, null, types), null);
});

test("max depth is the BACKEND's decision: the UI never blocks on it, it only shows the company's own limit as an advisory note (no depth number is built into the code)", () => {
  const limited = ty("d", "d", "Phòng", { maxDepth: 3 }); const units = [u("l1", null, "L1"), u("l2", "l1", "L2"), u("l3", "l2", "L3")];
  assert.equal(M.levelOf(units, "l3"), 3);
  assert.equal(M.depthAdvisory(limited, units[1], units), null, "level 3 is still within the company's limit of 3");
  assert.match(M.depthAdvisory(limited, units[2], units)!, /tối đa 3 cấp.*cấp 4.*Máy chủ sẽ kiểm tra/);
  assert.equal(M.depthAdvisory(ty("x", "x", "X"), units[2], units), null, "no limit on the type: nothing is said"); assert.equal(M.depthAdvisory(undefined, units[2], units), null);
  // creating below the limit is NOT an error of the form (advisory only) …
  assert.deepEqual(M.validateUnitForm({ name: "Deep", code: "DEEP", typeId: "d" }, { types: [limited], parent: units[2] }), {}, "the form does not refuse on depth");
  // … nor is the move target disabled by it
  const move = M.moveTargets([...units, u("m", null, "M", { typeId: "d" })], [limited], "m").find((t) => t.id === "l3")!; assert.equal(move.disabled, false); assert.match(move.note!, /tối đa 3 cấp/);
  // a different company limit gives a different answer: the number comes from data
  assert.equal(M.depthAdvisory(ty("d", "d", "P", { maxDepth: 10 }), units[2], units), null);
  assert.equal(M.levelOf([u("x", "y"), u("y", "x")], "x"), 2, "a broken chain stops the count");
});

test("rules form <-> contract: 'any' is null, 'listed' is a list (empty list = leaf / root only), allowRoot tri-state, maxDepth is a number or null", () => {
  const empty = M.emptyRulesForm(); assert.deepEqual(M.rulesFromForm(empty), NO_RULES);
  assert.deepEqual(M.rulesFromForm({ ...empty, parents: "listed" }), { ...NO_RULES, allowedParentTypeIds: [] }); assert.deepEqual(M.rulesFromForm({ ...empty, children: "listed", childIds: ["x"] }), { ...NO_RULES, allowedChildTypeIds: ["x"] });
  assert.deepEqual(M.rulesFromForm({ ...empty, allowRoot: "no", maxDepth: " 6 " }), { ...NO_RULES, allowRoot: false, maxDepth: 6 });
  for (const r of [NO_RULES, { allowedParentTypeIds: ["a"], allowedChildTypeIds: [], allowRoot: true, maxDepth: 9 }]) assert.deepEqual(M.rulesFromForm(M.formFromRules(r)), r, "round trip");
  assert.deepEqual(M.rulesSummary(NO_RULES, TYPES), ["không giới hạn nơi đặt"]); assert.deepEqual(M.rulesSummary({ ...NO_RULES, allowedParentTypeIds: [], allowedChildTypeIds: [], allowRoot: false, maxDepth: 4 }, TYPES), ["chỉ làm đơn vị gốc", "không chứa đơn vị con", "không làm đơn vị gốc", "tối đa 4 cấp"]);
});

// ------------------------------------------------------------------------------------------------------------------------------- forms and hints
test("forms: unit and type validation follow the server's patterns (required code, name 160, active type, parent-type rule, lower-case type code, allow-listed icon, no duplicate code)", () => {
  const ctx = { types: TYPES, parent: TREE[0] };
  assert.deepEqual(M.validateUnitForm({ name: "Mobile", code: "MOB", typeId: "t-dept" }, ctx), {});
  assert.match(M.validateUnitForm({ name: "  ", code: "X", typeId: "t-div" }, ctx).name!, /Hãy nhập tên/); assert.match(M.validateUnitForm({ name: "x".repeat(161), code: "X", typeId: "t-div" }, ctx).name!, /160/); assert.equal(M.validateUnitForm({ name: "x".repeat(160), code: "X", typeId: "t-div" }, { types: TYPES, parent: null }).name, undefined);
  assert.match(M.validateUnitForm({ name: "A", code: "", typeId: "t-div" }, { types: TYPES, parent: null }).code!, /mã đơn vị/i, "the code is required");
  assert.match(M.validateUnitForm({ name: "A", code: "bad code!", typeId: "t-div" }, { types: TYPES, parent: null }).code!, /Mã gồm/); assert.equal(M.validateUnitForm({ name: "A", code: "MOB-01.a_b", typeId: "t-div" }, { types: TYPES, parent: null }).code, undefined); assert.match(M.validateUnitForm({ name: "A", code: "A".repeat(61), typeId: "t-div" }, { types: TYPES, parent: null }).code!, /60/);
  assert.match(M.validateUnitForm({ name: "A", code: "X", typeId: "" }, ctx).type!, /chọn loại/, "the type is required"); assert.match(M.validateUnitForm({ name: "A", code: "X", typeId: "t-team" }, ctx).type!, /chỉ đặt được trong/, "a Team directly under a Khối");
  assert.match(M.validateUnitForm({ name: "A", code: "X", typeId: "t-dept" }, { types: TYPES, parent: null }).type!, /phải nằm trong/, "a Phòng as a root");
  assert.match(M.validateUnitForm({ name: "A", code: "X", typeId: "gone" }, ctx).type!, /không còn tồn tại/); assert.match(M.validateUnitForm({ name: "A", code: "X", typeId: "t-div" }, { types: [ty("t-div", "d", "D", {}, { active: false })], parent: null }).type!, /đang bị tắt/);
  assert.deepEqual(M.validateUnitForm({ name: "A", code: "X", typeId: "" }, { ...ctx, editing: true }), {}, "editing never re-validates the (immutable) type");
  assert.deepEqual(M.validateTypeForm({ name: "Chi nhánh", code: "branch", icon: "map-pin" }, TYPES), {}); assert.deepEqual(M.validateTypeForm({ name: "Chi nhánh", code: "BRANCH", icon: "map-pin" }, TYPES), {}, "upper-case input is accepted: the code is lower-cased like the server does");
  assert.match(M.validateTypeForm({ name: "", code: "x", icon: "nope" }, TYPES).name!, /Hãy nhập tên loại/); assert.match(M.validateTypeForm({ name: "A", code: "_x", icon: "folder" }, TYPES).code!, /1–40 ký tự/);
  assert.match(M.validateTypeForm({ name: "A", code: "DIVISION", icon: "folder" }, TYPES).code!, /đã được dùng/); assert.match(M.validateTypeForm({ name: "A", code: "ab", icon: "https://x/y.png" }, TYPES).icon!, /danh sách/);
  assert.equal(M.validateTypeForm({ name: "A", code: "", icon: "folder" }, TYPES, true).code, undefined, "the code of an existing type is not edited");
  assert.match(M.validateTypeForm({ name: "A", code: "ab", icon: "folder", maxDepth: "0" }, TYPES).maxDepth!, /1 đến 100/); assert.match(M.validateTypeForm({ name: "A", code: "ab", icon: "folder", maxDepth: "x" }, TYPES).maxDepth!, /số nguyên/); assert.equal(M.validateTypeForm({ name: "A", code: "ab", icon: "folder", maxDepth: "100" }, TYPES).maxDepth, undefined);
  assert.deepEqual(M.validateCatalogForm({ name: "Kỹ sư", code: "ENG" }, [{ code: "PM" }]), {}); assert.match(M.validateCatalogForm({ name: "Kỹ sư", code: "eng" }, [{ code: "ENG" }]).code!, /đã được dùng/, "per company, case-insensitive, also disabled ones");
  assert.match(M.validateCatalogForm({ name: "G", code: "g", rank: "10001" }, [], { withRank: true }).rank!, /0 đến 10000/); assert.deepEqual(M.validateCatalogForm({ name: "G", code: "g", rank: "10000" }, [], { withRank: true }), {}); assert.match(M.validateCatalogForm({ name: "G", code: "g", description: "x".repeat(501) }, []).description!, /500/);
  assert.equal(M.relationError("manager"), null); assert.equal(M.normalizeRelation(" head "), "HEAD"); assert.match(M.relationError("1x")!, /Quan hệ gồm/); assert.equal(M.relationError(""), null, "empty = the default MEMBER");
});

test("icons: a closed allow-list; anything else (a URL, markup, an unknown id) falls back to the folder", () => {
  assert.ok(M.UNIT_ICONS.length >= 12 && M.UNIT_ICONS.every((i) => /^[a-z-]+$/.test(i.id))); assert.equal(M.safeIcon("users"), "users");
  for (const bad of ["https://evil.example/x.png", "<svg onload=1>", "../x", "", null, undefined]) assert.equal(M.safeIcon(bad as string), "folder");
});

test("archive / restore hints are ADVISORY (the server decides): known children / members are mentioned, unknown counts say nothing, an archived parent blocks restore only as a hint", () => {
  assert.match(M.archiveHint(u("p", null, "P", { childCount: 2 }))!, /2 đơn vị con/); assert.match(M.archiveHint(u("p", null, "P", { directMemberCount: 3 }))!, /3 thành viên trực tiếp/); assert.match(M.archiveHint(u("p", null, "P", { childCount: 1, directMemberCount: 4 }))!, /Có thể bị máy chủ từ chối.*1 đơn vị con.*4 thành viên/);
  assert.equal(M.archiveHint(u("p", null, "P")), null); assert.equal(M.archiveHint(u("p", null, "P", { directMemberCount: 0, childCount: 0 })), null); assert.equal(M.archiveHint(u("p", null, "P", { active: false, childCount: 3 })), null);
  const parent = u("par", null, "Par", { active: false }); const child = u("kid", "par", "Kid", { active: false });
  assert.match(M.restoreHint(child, [parent, child])!, /khôi phục đơn vị cha/); assert.equal(M.restoreHint(u("r", null, "R", { active: false }), []), null); assert.equal(M.restoreHint(u("kid", "par", "Kid", { active: false }), [u("par", null, "Par")]), null); assert.equal(M.restoreHint(u("a", "par"), [parent]), null);
});

// ------------------------------------------------------------------------------------------------------------------------------- directory paging and rows
test("paging guard: the pager never reaches a page the server would refuse (page * size <= 10000), says so when rows lie beyond, and the search box needs 2 characters", () => {
  assert.equal(M.lastReachablePage({ total: 45, size: 20 }), 2); assert.equal(M.pagingCapped({ total: 45, size: 20 }), false);
  assert.equal(M.lastReachablePage({ total: 1_000_000, size: 20 }), 500); assert.equal(M.pagingCapped({ total: 1_000_000, size: 20 }), true, "rows beyond the reachable pages exist: narrow the search");
  assert.equal(M.lastReachablePage({ total: 1_000_000, size: 100 }), 100); assert.equal(M.lastReachablePage({ total: 0, size: 20 }), 0); assert.equal(M.pagingCapped({ total: 10_020, size: 20 }), false, "page 500 is the last page that holds rows (offset 10000)");
  assert.deepEqual(M.PAGING_LIMITS, { maxSize: 100, maxOffset: 10_000, minSearch: 2 });
  assert.deepEqual(M.searchState("  "), { send: undefined, hint: null }); assert.match(M.searchState("a").hint!, /ít nhất 2 ký tự/); assert.equal(M.searchState("a").send, undefined); assert.deepEqual(M.searchState(" an "), { send: "an", hint: null });
  assert.equal(M.pageCount({ total: 41, size: 20 }), 3); assert.equal(M.pageCount({ total: 0, size: 20 }), 1); assert.equal(M.employeeName({ displayName: " ", username: "bob" }), "bob");
});

test("membership rows: the employee's ACTIVE memberships (primary first) with the unit path and the positions held WITHIN each, grade names resolved, a missing catalog entry is said, not hidden", () => {
  const pos = [{ id: "p1", code: "ENG", name: "Kỹ sư", description: null, active: true, version: 1 }]; const grades = [{ id: "g1", code: "SR", name: "Senior", rank: 3, description: null, active: true, version: 1 }];
  const mem = (id: string, unit: string, o: Record<string, unknown> = {}) => ({ id, tenantId: "t", userId: "e", organizationUnitId: unit, relationType: "MEMBER", primary: false, active: true, version: 2, createdAt: `2026-01-0${id.slice(-1)}`, updatedAt: "", ...o });
  const hp = (id: string, membershipId: string, o: Record<string, unknown> = {}) => ({ id, tenantId: "t", userId: "e", membershipId, organizationUnitId: "x", positionId: "p1", gradeId: null, primary: false, active: true, version: 1, createdAt: "", updatedAt: "", ...o });
  const e = { organizationMemberships: [mem("m1", "flutter"), mem("m2", "web", { primary: true }), mem("m3", "hr", { active: false })], positions: [hp("ep1", "m1", { gradeId: "g1", primary: true }), hp("ep2", "m1", { positionId: "gone", gradeId: "gone-g" }), hp("ep3", "m2"), hp("ep4", "m1", { active: false })] };
  const rows = M.membershipRows(e as never, TREE, pos, grades);
  assert.deepEqual(rows.map((r) => r.membership.id), ["m2", "m1"], "primary first, ended memberships left out");
  assert.equal(rows[1].unitPath, "Khối Công nghệ › Mobile › Flutter Team"); assert.equal(rows[0].unitName, "Web");
  assert.deepEqual(rows[1].positions.map((p) => [p.positionName, p.gradeName, p.primary]), [["Kỹ sư", "Senior", true], ["Vị trí đã xóa", "Cấp bậc đã xóa", false]]);
  assert.deepEqual(rows[0].positions.map((p) => p.positionId), ["p1"], "a position is listed under the membership it is held within, nowhere else");
  assert.equal(M.membershipRows({ organizationMemberships: [mem("m1", "ghost")], positions: [] } as never, TREE, [], [])[0].unitName, "", "a unit the caller cannot see has no name");
  assert.equal(M.membershipRows({ organizationMemberships: [], positions: [] } as never, TREE, [], []).length, 0);
});

// ------------------------------------------------------------------------------------------------------------------------------- gate and permissions
test("gate: offered only when the server lists a tenant the caller administers; one tenant is fixed, several are the caller's own; no role name is read", () => {
  const st = (id: OrgCapabilityId) => CAPABILITIES[id];
  const noOrg = { ...FULL_ORG, structureView: false, employeeView: false };
  const none = M.organizationPlan(scope([{ id: "t1", name: "Acme" }], noOrg), st); assert.equal(none.access.granted, false); assert.match((none.access as { reason: string }).reason, /quyền xem cơ cấu tổ chức/); assert.equal(none.employeeAccess.granted, false);
  const one = M.organizationPlan(scope([{ id: "t1", name: "Acme" }]), st); assert.equal(one.access.granted, true); assert.deepEqual(one.fixedTenant, { id: "t1", name: "Acme" }); assert.deepEqual(one.tenantChoice, []);
  const two = M.organizationPlan(scope([{ id: "t1", name: "Acme" }, { id: "t3", name: "C" }]), st); assert.equal(two.fixedTenant, null); assert.deepEqual(two.tenantChoice.map((t) => t.id), ["t1", "t3"]);
  for (const k of ["units", "edit", "types", "typesManage", "positions", "catalogManage", "assignOrg", "assignPosition", "employeeCreate", "employeeStatus"] as const) assert.equal(one[k].state, "ready", k);
  const partial = M.organizationPlan(scope([{ id: "t1", name: "Acme" }]), (id) => (id === "restoreOrganizationUnit" ? ({ status: "NOT_READY", needs: ["ORG_STRUCTURE_MANAGE"], reason: "x", owner: "C3" } as OrgCapabilityState) : CAPABILITIES[id]));
  assert.equal(partial.edit.state, "not-ready", "one missing edit operation disables editing as a whole, with its reason"); assert.equal(partial.units.state, "ready");
});

test("gate (D-C0-51): the screens read ORG_STRUCTURE_* / EMPLOYEE_* / POSITION_GRADE_* and nothing else; TENANT_MEMBERS, platformScope and a role grant none of them", () => {
  const A = adminScope as (me: unknown) => ReturnType<typeof adminScope>;
  const base = { id: "u", username: "u", displayName: "U", roles: [], workspaces: [], tenantId: "t1", tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "TENANT_ADMIN" }] };
  const st = (id: OrgCapabilityId) => CAPABILITIES[id];
  for (const me of [{ ...base, permissions: [] }, { ...base, permissions: ["TENANT_MEMBERS", "TENANT_MANAGE"] }, { ...base, platformScope: true, systemAdmin: true, permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] }]) {
    const pl = M.organizationPlan(A(me), st); assert.equal(pl.access.granted, false); assert.equal(pl.employeeAccess.granted, false); assert.equal(A(me).org.employeeProvision, false);
  }
  // a viewer: sees structure + employees + positions, may change none of it
  const v = M.organizationPlan(A({ ...base, permissions: ["ORG_STRUCTURE_VIEW", "EMPLOYEE_VIEW", "POSITION_GRADE_VIEW", "TENANT_MEMBERS"] }), st);
  assert.equal(v.access.granted, true); assert.equal(v.employeeAccess.granted, true); assert.equal(v.units.state, "ready"); assert.equal(v.positions.state, "ready");
  for (const k of ["edit", "typesManage", "catalogManage", "assignOrg", "assignPosition"] as const) assert.equal(v[k].state, "no-permission", k);
  assert.equal(v.employeeCreate.state, "no-permission", "TENANT_MEMBERS alone does not let a viewer provision an employee");
  // *_MANAGE does not imply *_VIEW: a manager without the view code is refused the screen
  const m = M.organizationPlan(A({ ...base, permissions: ["ORG_STRUCTURE_MANAGE", "EMPLOYEE_MANAGE", "POSITION_GRADE_MANAGE"] }), st); assert.equal(m.access.granted, false); assert.equal(m.employeeAccess.granted, false); assert.equal(m.positions.state, "no-permission");
  // the full TENANT_ADMIN set
  const full = A({ ...base, permissions: ["TENANT_MEMBERS", "TENANT_MANAGE", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"] });
  assert.deepEqual(full.org, FULL_ORG); const f = M.organizationPlan(full, st); assert.equal(f.edit.state, "ready"); assert.equal(f.assignOrg.state, "ready"); assert.equal(f.catalogManage.state, "ready");
  // employee create / enable / disable also needs TENANT_MEMBERS (contract §9); the obsolete ORG_MANAGE and invented aliases are not codes
  assert.equal(A({ ...base, permissions: ["EMPLOYEE_MANAGE"] }).org.employeeProvision, false); assert.equal(A({ ...base, permissions: ["EMPLOYEE_MANAGE", "TENANT_MEMBERS"] }).org.employeeProvision, true);
  const bogus = A({ ...base, permissions: ["ORG_MANAGE", "ORG_ADMIN", "ORG_EDIT", "T-ORG-MANAGE", "ORG_VIEW"] }); assert.equal(bogus.org.structureView || bogus.org.structureManage || bogus.org.employeeView, false);
  for (const id of ALL_IDS) for (const n of CAPABILITIES[id].needs) assert.ok(["TENANT_MEMBERS", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"].includes(n), `${id} needs the canonical code ${n}`);
});

test("RELATION / POSITION / GRADE AUTHORIZE NOTHING: a person whose data says HEAD / MANAGER / a senior grade / a position still gets exactly the permissions the server listed", () => {
  const A = adminScope as (me: unknown) => ReturnType<typeof adminScope>;
  const base = { id: "u", username: "u", displayName: "U", roles: [], workspaces: [], tenantId: "t1", tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "MEMBER" }], permissions: [] };
  const decorated = { ...base, relationType: "HEAD", primaryOrganizationUnitId: "u-1", organizationMemberships: [{ relationType: "MANAGER", primary: true }], positions: [{ positionId: "p", gradeId: "g" }], grade: "SENIOR", position: "DIRECTOR", unitRole: "HEAD" };
  assert.deepEqual(A(decorated).org, { structureView: false, structureManage: false, employeeView: false, employeeManage: false, employeeProvision: false, positionGradeView: false, positionGradeManage: false });
  assert.equal(M.organizationPlan(A(decorated), (id) => CAPABILITIES[id]).access.granted, false);
  // and the other way round: the server's codes alone open the screens, whatever the person's data says
  const granted = A({ ...base, permissions: ["ORG_STRUCTURE_VIEW"], relationType: "MEMBER" }); assert.equal(granted.org.structureView, true); assert.equal(granted.org.structureManage, false);
  // a relation is a label: any well-formed word is accepted by the form, none of them changes the plan
  for (const rel of ["MEMBER", "MANAGER", "HEAD", "OWNER", "ADMIN"]) assert.equal(M.relationError(rel), null);
});

// ------------------------------------------------------------------------------------------------------------------------------- errors
test("errors: every refusal is mapped by the SERVER's code, then by status; an unknown code shows only its reference code (never the server's English message); NOT_READY is its own kind", () => {
  const k = (e: unknown) => M.orgProblem(e).kind;
  const cases: [string, string][] = [["ORG_CYCLE", "cycle"], ["VERSION_CONFLICT", "version"], ["ORG_UNIT_CODE_TAKEN", "duplicate"], ["ORG_UNIT_TYPE_CODE_TAKEN", "duplicate"], ["POSITION_CODE_TAKEN", "duplicate"], ["GRADE_CODE_TAKEN", "duplicate"], ["ORG_MEMBERSHIP_EXISTS", "duplicate"], ["POSITION_ASSIGNMENT_EXISTS", "duplicate"],
    ["ORG_UNIT_ARCHIVED", "conflict"], ["ORG_UNIT_TYPE_DISABLED", "conflict"], ["POSITION_DISABLED", "conflict"], ["GRADE_DISABLED", "conflict"], ["ORG_MEMBERSHIP_INACTIVE", "conflict"], ["EMPLOYEE_INACTIVE", "conflict"],
    ["ORG_UNIT_HAS_CHILDREN", "blocked"], ["ORG_UNIT_HAS_MEMBERS", "blocked"], ["EMPLOYEE_ORG_HAS_POSITIONS", "blocked"], ["VALIDATION_FAILED", "validation"], ["INVALID_CODE", "validation"], ["QUERY_TOO_SHORT", "validation"], ["OFFSET_TOO_LARGE", "validation"],
    ["TENANT_SUSPENDED", "forbidden"], ["FORBIDDEN", "forbidden"], ["ORG_UNIT_NOT_FOUND", "notfound"], ["ORG_UNIT_TYPE_NOT_FOUND", "notfound"], ["EMPLOYEE_NOT_FOUND", "notfound"], ["POSITION_NOT_FOUND", "notfound"], ["GRADE_NOT_FOUND", "notfound"], ["ORG_MEMBERSHIP_NOT_FOUND", "notfound"], ["POSITION_ASSIGNMENT_NOT_FOUND", "notfound"], ["TENANT_NOT_FOUND", "notfound"],
    ["ORG_STRUCTURE_BUSY", "busy"], ["ORG_PERSISTENCE_NOT_AVAILABLE", "unavailable-feature"], ["ORG_TYPE_RULE_VIOLATION", "rule"], ["RESTORE_CONFLICT", "conflict"]];
  for (const [code, kind] of cases) assert.equal(k({ status: 409, code }), kind, code);
  assert.equal(k({ status: 422 }), "validation"); assert.equal(k({ status: 401 }), "forbidden"); assert.equal(k({ status: 409 }), "conflict"); assert.equal(k({ status: 503 }), "unavailable"); assert.equal(k({ status: 501 }), "unavailable-feature", "a bare 501 is the unavailable store too");
  assert.equal(k({ status: 0 }), "unavailable"); assert.match(M.orgProblem({ status: 500 }).text, /Chưa rõ thao tác đã được ghi/); assert.equal(k(new TypeError("fetch failed")), "unavailable");
  assert.equal(k(new OrganizationNotReady("moveOrganizationUnit", "Máy chủ chưa hỗ trợ", "C1")), "not-ready"); assert.match(M.orgProblem({ status: 418, code: "X_TEAPOT", message: "teapot" }).text, /X_TEAPOT/); assert.doesNotMatch(M.orgProblem({ status: 418, code: "X_TEAPOT", message: "teapot" }).text, /teapot/);
  for (const [code] of cases) assert.doesNotMatch(M.orgProblem({ status: 409, code, message: "Something went wrong: English server text" }).text, /Something went wrong|English server text/, code);
});

test("503 ORG_STRUCTURE_BUSY is a RETRY state, not a failure and never a success: retryable, Retry-After from the header (ApiError) or details, the text says nothing was saved", () => {
  const fromHeader = M.orgProblem(new ApiError(503, "ORG_STRUCTURE_BUSY", "busy", "req-1", { retryable: true, retryAfterSeconds: 5 }, true, 5));
  assert.equal(fromHeader.kind, "busy"); assert.equal(fromHeader.retryable, true); assert.equal(fromHeader.retryAfterSeconds, 5); assert.match(fromHeader.text, /chưa có gì được lưu/); assert.match(fromHeader.text, /5 giây/);
  const fromDetails = M.orgProblem({ status: 503, code: "ORG_STRUCTURE_BUSY", details: { retryable: true, retryAfterSeconds: 2 } }); assert.equal(fromDetails.retryAfterSeconds, 2);
  const none = M.orgProblem({ status: 503, code: "ORG_STRUCTURE_BUSY" }); assert.equal(none.retryable, true); assert.equal(none.retryAfterSeconds, undefined); assert.match(none.text, /thử lại sau ít giây/i);
  assert.equal(M.orgProblem({ status: 503, code: "ORG_STRUCTURE_BUSY", retryAfterSeconds: 0 }).retryAfterSeconds, undefined, "a non-positive Retry-After is ignored"); assert.equal(M.orgProblem({ status: 503, code: "ORG_STRUCTURE_BUSY", retryAfterSeconds: 1.2 }).retryAfterSeconds, 2);
  assert.equal(M.needsReload(fromHeader), false, "a busy structure is retried, not reloaded");
  const gen = M.orgProblem({ status: 503 }); assert.equal(gen.kind, "unavailable", "a bare 503 is not claimed to be the busy structure"); assert.equal(gen.retryable, true);
});

test("501 ORG_PERSISTENCE_NOT_AVAILABLE fails CLOSED: its own kind, an explanation, not retryable as a write, no fallback wording", () => {
  const p = M.orgProblem({ status: 501, code: "ORG_PERSISTENCE_NOT_AVAILABLE", message: "x" });
  assert.equal(p.kind, "unavailable-feature"); assert.notEqual(p.retryable, true); assert.match(p.text, /chưa được bật trên máy chủ/); assert.match(p.text, /Không có bản sao tạm nào/); assert.doesNotMatch(p.text, /thành công|đã lưu/i);
});

test("the other refusals keep their meaning: stale version asks for a reload, cycle / depth / rule / archive blocks / restore conflicts / offset / suspended / not-found are each said once and none is success", () => {
  const p = (code: string, details?: unknown) => M.orgProblem({ status: 409, code, details });
  assert.equal(M.needsReload(p("VERSION_CONFLICT", { currentVersion: 9 })), true); assert.match(p("VERSION_CONFLICT").text, /chưa được lưu/);
  assert.match(p("ORG_CYCLE").text, /vòng/);
  for (const [reason, re] of [["MAX_DEPTH", /độ sâu tối đa/], ["ROOT_NOT_ALLOWED", /gốc/], ["PARENT_TYPE_NOT_ALLOWED", /dưới đơn vị cha/], ["CHILD_TYPE_NOT_ALLOWED", /không chứa loại/]] as const) { const r = p("ORG_TYPE_RULE_VIOLATION", { reason }); assert.equal(r.kind, "rule"); assert.match(r.text, re, reason); }
  assert.equal(p("ORG_TYPE_RULE_VIOLATION", { reason: "SOMETHING_NEW" }).kind, "rule", "an unknown reason is still a rule violation");
  assert.match(p("ORG_UNIT_HAS_CHILDREN").text, /đơn vị con/); assert.match(p("ORG_UNIT_HAS_MEMBERS").text, /thành viên/); assert.match(p("EMPLOYEE_ORG_HAS_POSITIONS").text, /vị trí/);
  for (const [reason, re] of [["NOT_ARCHIVED", /không ở trạng thái lưu trữ/], ["TENANT_INACTIVE", /Công ty không ở trạng thái hoạt động/], ["TYPE_DISABLED", /Bật lại loại/], ["PARENT_ARCHIVED", /Khôi phục đơn vị cha/], ["TYPE_RULE", /quy tắc đặt chỗ/], ["CODE_TAKEN", /đang dùng mã này/]] as const) { const r = p("RESTORE_CONFLICT", { reason }); assert.equal(r.kind, "conflict"); assert.match(r.text, re, reason); }
  assert.match(M.orgProblem({ status: 400, code: "OFFSET_TOO_LARGE" }).text, /thu hẹp tìm kiếm/); assert.match(M.orgProblem({ status: 400, code: "QUERY_TOO_SHORT" }).text, /ít nhất 2 ký tự/);
  assert.match(M.orgProblem({ status: 403, code: "TENANT_SUSPENDED" }).text, /tạm khóa/);
  // disclosure-safe: a foreign tenant / unit and a missing one read the same, and the text never says which
  for (const code of ["ORG_UNIT_NOT_FOUND", "TENANT_NOT_FOUND", "EMPLOYEE_NOT_FOUND"]) { const t = M.orgProblem({ status: 404, code }).text; assert.match(t, /không có quyền xem/); assert.doesNotMatch(t, /thuộc công ty khác nên|tồn tại nhưng/); }
  assert.equal(M.orgProblem({ status: 404, code: "ORG_UNIT_NOT_FOUND" }).text, M.orgProblem({ status: 404, code: "TENANT_NOT_FOUND" }).text);
});

// ------------------------------------------------------------------------------------------------------------------------------- scale
test("tree at scale: a 20 000-deep chain does not overflow the stack, 2 000 nodes build in linear time, aria positions are right", () => {
  const chain = Array.from({ length: 20_000 }, (_, i) => u(`c${i}`, i ? `c${i - 1}` : null, `N${i}`));
  const flat = M.flattenTree(M.buildTree(chain)); assert.equal(flat.length, 20_000); assert.equal(flat[19_999].depth, 19_999);
  assert.equal(M.descendantIds(chain, "c0").size, 19_999); assert.equal(M.unitPath(chain.slice(0, 50), "c49").split(" › ").length, 50);
  const wide = Array.from({ length: 2_000 }, (_, i) => u(`w${i}`, i < 10 ? null : `w${i % 10}`, `U${String(i).padStart(4, "0")}`));
  const t0 = performance.now(); const tree = M.buildTree(wide); const f = M.flattenTree(tree); const ms = performance.now() - t0;
  assert.equal(f.length, 2_000); assert.ok(ms < 1_000, `build + flatten of 2000 nodes took ${ms.toFixed(1)} ms`); console.log(`  [metric] buildTree + flattenTree, 2000 nodes: ${ms.toFixed(1)} ms`);
  const root = tree[0]; assert.equal(root.size, 10); assert.deepEqual(root.children.map((c) => c.pos).slice(0, 3), [1, 2, 3]); assert.ok(root.children.every((c) => c.size === root.children.length));
  // the nested server answer flattens iteratively too (a 20 000-deep tree)
  let deep: OrgUnitNodeDto = node(dto("d19999", "d19998"), [], 1, 1); for (let i = 19_998; i >= 0; i--) deep = node(dto(`d${i}`, i ? `d${i - 1}` : null), [deep], 1, 20_000 - i);
  const rows = flattenUnitNodes([deep]); assert.equal(rows.length, 20_000); assert.equal(rows[0].subtreeEmployeeCount, 20_000); assert.equal(rows[19_999].directMemberCount, 1);
});

test("compactPath keeps a short breadcrumb whole and shortens a long one to first 2 … last 3", () => {
  assert.equal(M.compactPath("A › B › C"), "A › B › C"); assert.equal(M.compactPath("A › B › C › D › E › F"), "A › B › C › D › E › F");
  assert.equal(M.compactPath(Array.from({ length: 60 }, (_, i) => `L${i + 1}`).join(" › ")), "L1 › L2 › … › L58 › L59 › L60"); assert.equal(M.compactPath(""), "");
});
