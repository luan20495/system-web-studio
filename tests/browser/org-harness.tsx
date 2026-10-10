// @class: harness — in-page host of the organization tree and the employee directory over a FAKE in-memory transport; NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY harness for <OrganizationView> / <EmployeesView>. The service is the REAL `createOrganizationApi` over the typed transport shape (`api.org`); the transport is `createFakeOrg` (org-fake-server.ts), an
 * in-memory server that answers the way the contract says (versions, cycles, archive blocks, type rules, directory limits, 503 busy, 501 off, 403 suspended). Every call is recorded in window.__org (and the
 * provisioning calls in window.__prov); window.__fake gives the specs the "someone else changed it" / "next writes are busy" / "server switched off" levers and the server state.
 * It proves what the SCREENS do with the answers of the contract, never what a server answers (E2E-ORG01 does that, against a flag-ON stack). ?v=org|emp|picker, ?s=<scenario> (see org-fake-server.ts).
 */
import { Profiler, useState } from "react";
import { createRoot } from "react-dom/client";
import type { Me } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { createOrganizationApi } from "../../features/admin/organization";
import { employeeProvisioningPlan, organizationPlan } from "../../features/admin/organizationModel";
import { OrganizationView } from "../../features/admin/OrganizationScreens";
import { EmployeesView } from "../../features/admin/EmployeesScreens";
import { PersonPicker } from "../../features/admin/PersonPicker";
import { CAPABILITIES as PROV_CAPS, createProvisioningApi } from "../../features/admin/provisioning";
import { provisioningPlan } from "../../features/admin/provisioningModel";
import { createFakeOrg, type FakeOrg } from "./org-fake-server";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/ui.css";   // the real layouts load it last (apps/*/app/layout.tsx); a harness without it is not the product

declare global { interface Window { __org: { name: string; args: unknown[] }[]; __prov: { name: string; args: unknown[] }[]; __prof: { phase: string; actual: number; base: number; at: number }[]; __fake: FakeOrg } }
window.__org = []; window.__prov = []; window.__prof = [];
const P = new URLSearchParams(location.search); const V = P.get("v") ?? "org"; const S = P.get("s") ?? "ok";
const me = (o: Partial<Me>): Me => ({ id: "me", username: "me", displayName: "Me", roles: [], workspaces: [], ...o });
const T1 = { id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }; const T3 = { id: "t3", slug: "cong", name: "Công ty C", status: "ACTIVE", role: "TENANT_ADMIN" };
/** what the server lists for a TENANT_ADMIN of the primary tenant (C1 PERMISSION_MATRIX): the tenant codes + all six organization codes; a SYSTEM_ADMIN lists exactly TENANT_MANAGE + TENANT_MEMBERS (platform scope) and NO organization code */
const ORG_ALL = ["ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];
const ORG_VIEW = ["ORG_STRUCTURE_VIEW", "EMPLOYEE_VIEW", "POSITION_GRADE_VIEW"];
const ME: Me = S === "forbidden" ? me({ tenants: [{ ...T1, role: "MEMBER" }] }) : S === "sysadmin" ? me({ platformScope: true, systemAdmin: true, tenantId: "t1", permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"], tenants: [T1] })
  : S === "viewer" ? me({ tenantId: "t1", permissions: ["TENANT_MEMBERS", ...ORG_VIEW], tenants: [T1] })
  : S === "emp-only" ? me({ tenantId: "t1", permissions: ["EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "TENANT_MEMBERS"], tenants: [T1] })
  : S === "multi" ? me({ tenantId: "t1", permissions: ["TENANT_MEMBERS", ...ORG_ALL], tenants: [T1, T3] })
  : me({ tenantId: "t1", permissions: ["TENANT_MEMBERS", "TENANT_MANAGE", ...ORG_ALL], tenants: [T1], workspaces: [{ id: "w1", name: "Kinh doanh", role: "x", tenantId: "t1", permissions: ["MEMBER_MANAGE"] }] });

const fake = createFakeOrg(S, (name, args) => { window.__org.push({ name, args }); });
window.__fake = fake;
const api = createOrganizationApi(fake.api);
const rec2 = <A extends unknown[], R>(name: string, f: (...a: A) => R) => (...args: A): Promise<Awaited<R>> => { window.__prov.push({ name, args }); return Promise.resolve().then(() => f(...args)) as Promise<Awaited<R>>; };
const provApi = createProvisioningApi({
  createTenantUser: rec2("createTenantUser", (_t: string, b: { username: string; displayName: string }) => ({ userId: "id-" + b.username, username: b.username, displayName: b.displayName, purpose: "ACTIVATION" as const, token: "x".repeat(43), expiresAt: "2026-10-09T00:00:00Z" })),
  createTenantWorkspace: rec2("createTenantWorkspace", (t: string, name: string) => ({ id: "w-new", name, tenantId: t })), setTenantMember: rec2("setTenantMember", () => ({ tenantId: "t1", userId: "u", role: "MEMBER", active: true })),
  changeWorkspaceMember: rec2("changeWorkspaceMember", () => ({}) as never), addWorkspaceMember: rec2("addWorkspaceMember", () => ({}) as never), tenantMemberCandidates: rec2("tenantMemberCandidates", () => []),
  activationLink: rec2("activationLink", () => ({}) as never), setUserStatus: rec2("setUserStatus", () => ({})),
}, S === "emp-nocreate" ? { ...PROV_CAPS, createTenantUser: { status: "NOT_READY", needs: ["TENANT_MEMBERS"], owner: "C1", reason: "Máy chủ chưa có API tạo tài khoản." } } : PROV_CAPS);
const scope = adminScope(ME); const plan = organizationPlan(scope, api.state);
const provPlan = employeeProvisioningPlan(scope, provisioningPlan(scope, "admin", provApi.state));
const tenant = plan.fixedTenant ?? plan.tenantChoice[0] ?? { id: "", name: "" };
const root = document.getElementById("root")!;
function PickerHost() {
  const all = Array.from({ length: 12 }, (_, i) => ({ id: `p${i}`, username: `person${i}`, displayName: i === 3 ? "Nguyễn Hoàng Thiên Phúc Bảo Long Quang Vinh" : `Người số ${i}` }));
  const [q, setQ] = useState(""); const [v, setV] = useState("");
  const list = all.filter((p) => `${p.displayName} ${p.username}`.toLowerCase().includes(q.toLowerCase()));
  return <div><button data-testid="before">trước</button><PersonPicker id="pp" label="Tìm người dùng" people={list} q={q} setQ={setQ} value={v} onChange={setV} placeholder="Tìm theo tên" emptyText="Không có người phù hợp"/><p data-testid="chosen">{v}</p><button data-testid="after">sau</button></div>;
}
function Host() {
  if (V === "picker") return <PickerHost/>;
  return V === "emp"
    ? <EmployeesView api={api} plan={plan} tenant={tenant} onTenant={() => undefined}
        prov={{ api: provApi, plan: provPlan, tenants: plan.tenantChoice.length ? plan.tenantChoice : plan.fixedTenant ? [plan.fixedTenant] : [], workspacesOf: (t) => (ME.workspaces ?? []).filter((w) => w.tenantId === t).map((w) => ({ id: w.id, name: w.name })) }}/>
    : <OrganizationView api={api} plan={plan} tenant={tenant}/>;
}
const onRender = (_id: string, phase: string, actual: number, base: number) => { window.__prof.push({ phase, actual, base, at: performance.now() }); };
createRoot(root).render(<div className="shell admin" style={{ display: "block", height: "auto", minHeight: "100vh" }}><main className="page" style={{ maxWidth: 1100, margin: "0 auto", padding: 16 }}><Profiler id="host" onRender={onRender}><Host/></Profiler></main></div>);
