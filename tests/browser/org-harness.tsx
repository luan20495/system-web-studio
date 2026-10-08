// @class: harness — in-page host of the organization tree and the employee directory with a FAKE in-memory transport; NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY harness for <OrganizationView> / <EmployeesView>. The adapters are the REAL `createOrganizationApi` / `createProvisioningApi`; only their transports are in-page fakes that keep a tiny in-memory
 * organization and record every call in window.__org / window.__prov. The fake applies the rules the screens must cope with (version check, cycle, "has children / employees") so the screens' states can be driven.
 * It proves what the SCREENS do with the answers C1's contract is expected to give (its codes are ASSUMED, see organizationModel.ts), never what a server answers. ?v=org|emp, ?s=<scenario>.
 * With ?s=notready the REAL default capability table is used: every operation is NOT_READY and the transport must receive nothing.
 */
import { createRoot } from "react-dom/client";
import type { Me, TenantMemberView } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { CAPABILITIES, createOrganizationApi, type Employee, type OrgCapabilityId, type OrgCapabilityState, type OrgUnit, type OrgUnitType, type OrganizationTransport, type Position } from "../../features/admin/organization";
import { employeesFromMembers, organizationPlan } from "../../features/admin/organizationModel";
import { OrganizationView } from "../../features/admin/OrganizationScreens";
import { EmployeesView } from "../../features/admin/EmployeesScreens";
import { CAPABILITIES as PROV_CAPS, createProvisioningApi } from "../../features/admin/provisioning";
import { provisioningPlan } from "../../features/admin/provisioningModel";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";

declare global { interface Window { __org: { name: string; args: unknown[] }[]; __prov: { name: string; args: unknown[] }[] } }
window.__org = []; window.__prov = [];
const P = new URLSearchParams(location.search); const V = P.get("v") ?? "org"; const S = P.get("s") ?? "ok";
const err = (status: number, code: string) => Object.assign(new Error("fixed text"), { status, code });
const me = (o: Partial<Me>): Me => ({ id: "me", username: "me", displayName: "Me", roles: [], workspaces: [], ...o });
const T1 = { id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }; const T3 = { id: "t3", slug: "cong", name: "Công ty C", status: "ACTIVE", role: "TENANT_ADMIN" };
const ME: Me = S === "forbidden" ? me({ tenants: [{ ...T1, role: "MEMBER" }] }) : S === "multi" ? me({ tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: [T1, T3] })
  : me({ tenantId: "t1", permissions: ["TENANT_MEMBERS", "TENANT_MANAGE"], tenants: [T1], workspaces: [{ id: "w1", name: "Kinh doanh", role: "x", tenantId: "t1", permissions: ["MEMBER_MANAGE"] }] });

// ---- the fake organization -------------------------------------------------------------------------------------------------------------------------
let seq = 100; const nid = (p: string) => `${p}${++seq}`;
const empty = S === "empty" || S === "emp-empty";
let units: OrgUnit[] = empty ? [] : [
  { id: "tech", parentId: null, typeId: "t-div", name: "Khối Công nghệ", enabled: true, version: 1 },
  { id: "mobile", parentId: "tech", typeId: "t-dept", name: "Mobile", enabled: true, version: 1 },
  { id: "flutter", parentId: "mobile", typeId: "t-team", name: "Flutter Team", enabled: true, version: 1, employeeCount: 2 },
  { id: "web", parentId: "tech", typeId: "t-dept", name: "Web", enabled: true, version: 1 },
  { id: "hr", parentId: null, typeId: "t-div", name: "Nhân sự", enabled: true, version: 1, employeeCount: 3 },
];
let types: OrgUnitType[] = empty ? [] : [{ id: "t-div", code: "DIVISION", name: "Khối", icon: "building" }, { id: "t-dept", code: "DEPT", name: "Phòng", icon: "briefcase", allowedParentTypeIds: ["t-div"] }, { id: "t-team", code: "TEAM", name: "Team", icon: "users", allowedParentTypeIds: ["t-dept", "t-team"] }];
const positions: Position[] = [{ id: "p-jr", name: "Nhân viên", level: 1 }, { id: "p-sr", name: "Trưởng nhóm", level: 3 }];
const emps: Employee[] = S === "emp-empty" ? [] : Array.from({ length: 45 }, (_, i): Employee => {
  const unit = ["flutter", "web", "hr"][i % 3]; const n = i + 1;
  return { userId: `u${String(n).padStart(2, "0")}`, username: `user${n}`, displayName: n === 5 ? "Nguyễn Đức Anh" : `Nhân viên ${n}`, email: `u${n}@acme.vn`, tenantRole: n === 1 ? "TENANT_ADMIN" : "MEMBER", active: n !== 7 && n !== 8,
    orgUnitId: unit, orgUnitName: units.find((u) => u.id === unit)?.name ?? null, positionId: n % 2 ? "p-jr" : "p-sr", positionName: n % 2 ? "Nhân viên" : "Trưởng nhóm" };
});
const rec = <A extends unknown[], R>(name: string, f: (...a: A) => R) => (...args: A): Promise<Awaited<R>> => { window.__org.push({ name, args }); return Promise.resolve().then(() => f(...args)) as Promise<Awaited<R>>; };
const below = (id: string): Set<string> => { const out = new Set<string>(); const st = [id]; while (st.length) { const c = st.pop()!; for (const u of units) if (u.parentId === c && !out.has(u.id)) { out.add(u.id); st.push(u.id); } } return out; };
const withCounts = (u: OrgUnit): OrgUnit => ({ ...u, childCount: units.filter((x) => x.parentId === u.id).length });
const bump = (u: OrgUnit, patch: Partial<OrgUnit>): OrgUnit => { const n = { ...u, ...patch, version: u.version + 1 }; units = units.map((x) => (x.id === u.id ? n : x)); return withCounts(n); };
const stale = (u: OrgUnit | undefined, v: number) => { if (!u) throw err(404, "UNIT_NOT_FOUND"); if (S === "version" || u.version !== v) throw err(409, "VERSION_CONFLICT"); return u; };

const transport: OrganizationTransport = {
  listOrganizationUnits: rec("listOrganizationUnits", (_t: string) => { if (S === "down") throw err(503, "UNAVAILABLE"); return units.map(withCounts); }),
  listOrganizationUnitTypes: rec("listOrganizationUnitTypes", (_t: string) => types),
  createOrganizationUnit: rec("createOrganizationUnit", (_t: string, u: { parentId: string | null; typeId: string | null; name: string; code?: string }) => { if (units.some((x) => x.parentId === u.parentId && x.name === u.name)) throw err(409, "DUPLICATE_NAME"); const n: OrgUnit = { id: nid("n"), parentId: u.parentId, typeId: u.typeId, name: u.name, code: u.code ?? null, enabled: true, version: 1 }; units = [...units, n]; return withCounts(n); }),
  updateOrganizationUnit: rec("updateOrganizationUnit", (_t: string, id: string, v: number, p: Partial<OrgUnit>) => bump(stale(units.find((x) => x.id === id), v), p)),
  moveOrganizationUnit: rec("moveOrganizationUnit", (_t: string, id: string, v: number, parent: string | null) => { const u = stale(units.find((x) => x.id === id), v); if (S === "cycle" || parent === id || (parent && below(id).has(parent))) throw err(409, "ORG_CYCLE"); return bump(u, { parentId: parent }); }),
  deleteOrganizationUnit: rec("deleteOrganizationUnit", (_t: string, id: string, v: number) => { const u = stale(units.find((x) => x.id === id), v); if (S === "blocked") throw err(409, "ORG_HAS_EMPLOYEES"); if (units.some((x) => x.parentId === id)) throw err(409, "ORG_HAS_CHILDREN"); if ((u.employeeCount ?? 0) > 0) throw err(409, "ORG_HAS_EMPLOYEES"); units = units.filter((x) => x.id !== id); }),
  createOrganizationUnitType: rec("createOrganizationUnitType", (_t: string, t: Omit<OrgUnitType, "id">) => { const n = { ...t, id: nid("ty") }; types = [...types, n]; return n; }),
  listPositions: rec("listPositions", (_t: string) => positions),
  listEmployees: rec("listEmployees", (_t: string, q: { q?: string; orgUnitId?: string | null; includeSubtree?: boolean; status?: string; page: number; size: number }) => {
    if (S === "emp-down") throw err(500, "INTERNAL");
    const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/gi, "d").toLowerCase(); const needle = fold(q.q ?? "");
    const scope = q.orgUnitId ? new Set([q.orgUnitId, ...below(q.orgUnitId)]) : null;
    const hit = emps.filter((e) => (!needle || fold(`${e.displayName} ${e.username} ${e.email}`).includes(needle)) && (!scope || (e.orgUnitId && scope.has(e.orgUnitId))) && (!q.status || q.status === "ALL" || (q.status === "ACTIVE") === e.active));
    const last = Math.max(0, Math.ceil(hit.length / q.size) - 1); const page = Math.min(q.page, last);
    return { items: hit.slice(page * q.size, page * q.size + q.size), total: hit.length, page, size: q.size, source: "directory" as const };
  }),
  updateEmployeeOrganization: rec("updateEmployeeOrganization", (_t: string, userId: string, unit: string | null) => { const e = emps.find((x) => x.userId === userId)!; e.orgUnitId = unit; e.orgUnitName = units.find((u) => u.id === unit)?.name ?? null; return { ...e }; }),
  updateEmployeePosition: rec("updateEmployeePosition", (_t: string, userId: string, pos: string | null) => { const e = emps.find((x) => x.userId === userId)!; e.positionId = pos; e.positionName = positions.find((p) => p.id === pos)?.name ?? null; return { ...e }; }),
  tenantMembers: rec("tenantMembers", (_t: string): TenantMemberView[] => { if (S === "emp-down") throw err(500, "INTERNAL"); return emps.map((e) => ({ tenantId: "t1", userId: e.userId, role: e.tenantRole, active: e.active, username: e.username, displayName: e.displayName, email: e.email })); }),
};

// ---- adapters (REAL) over the fakes ------------------------------------------------------------------------------------------------------------------
const ALL = Object.keys(CAPABILITIES) as OrgCapabilityId[];
const caps: Record<OrgCapabilityId, OrgCapabilityState> = S === "notready" || S === "emp-members" ? { ...CAPABILITIES }
  : Object.fromEntries(ALL.map((id) => [id, { status: "READY", needs: ["TENANT_MANAGE"], route: "FAKE (harness)" } as OrgCapabilityState])) as Record<OrgCapabilityId, OrgCapabilityState>;
if (S === "emp-members") { for (const id of ["updateEmployeeOrganization", "updateEmployeePosition"] as const) caps[id] = CAPABILITIES[id]; }
const api = createOrganizationApi(transport, employeesFromMembers, caps);
const scope = adminScope(ME); const plan = organizationPlan(scope, api.state);
const rec2 = <A extends unknown[], R>(name: string, f: (...a: A) => R) => (...args: A): Promise<Awaited<R>> => { window.__prov.push({ name, args }); return Promise.resolve().then(() => f(...args)) as Promise<Awaited<R>>; };
const provApi = createProvisioningApi({
  createTenantUser: rec2("createTenantUser", (_t: string, b: { username: string; displayName: string; tenantRole: "MEMBER" | "TENANT_ADMIN" }) => { const id = "id-" + b.username; emps.push({ userId: id, username: b.username, displayName: b.displayName, email: null, tenantRole: b.tenantRole, active: true, orgUnitId: null, positionId: null }); return { userId: id, username: b.username, displayName: b.displayName, purpose: "ACTIVATION" as const, token: "x".repeat(43), expiresAt: "2026-10-09T00:00:00Z" }; }),
  createTenantWorkspace: rec2("createTenantWorkspace", (t: string, name: string) => ({ id: "w-new", name, tenantId: t })), setTenantMember: rec2("setTenantMember", () => ({ tenantId: "t1", userId: "u", role: "MEMBER", active: true })),
  changeWorkspaceMember: rec2("changeWorkspaceMember", () => ({}) as never), addWorkspaceMember: rec2("addWorkspaceMember", () => ({}) as never), tenantMemberCandidates: rec2("tenantMemberCandidates", () => []),
  activationLink: rec2("activationLink", () => ({}) as never), setUserStatus: rec2("setUserStatus", () => ({})),
}, PROV_CAPS);
const provPlan = provisioningPlan(scope, "admin", provApi.state);
const tenant = plan.fixedTenant ?? plan.tenantChoice[0] ?? { id: "", name: "" };
const root = document.getElementById("root")!;
function Host() {
  return V === "emp"
    ? <EmployeesView api={api} plan={plan} tenant={tenant} onTenant={() => undefined} canToggleStatus={false}
        prov={{ api: provApi, plan: provPlan, tenants: plan.tenantChoice.length ? plan.tenantChoice : plan.fixedTenant ? [plan.fixedTenant] : [], workspacesOf: (t) => (ME.workspaces ?? []).filter((w) => w.tenantId === t).map((w) => ({ id: w.id, name: w.name })) }}/>
    : <OrganizationView api={api} plan={plan} tenant={tenant}/>;
}
createRoot(root).render(<div className="shell admin" style={{ display: "block", height: "auto", minHeight: "100vh" }}><main className="page" style={{ maxWidth: 1100, margin: "0 auto", padding: 16 }}><Host/></main></div>);
