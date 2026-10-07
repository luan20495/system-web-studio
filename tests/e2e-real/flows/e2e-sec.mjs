// @class: real-backend — SEC01-03 in ONE flow (shared fixtures): the provisioning boundaries, asserted against the real API with real sessions (the UI half of each is in tests/browser/provisioning.spec.mjs, which is a harness).
import { Blocked } from "../lib/report.mjs";
import { makeUser } from "../lib/portals.mjs";
export const id = "E2E-SEC01", title = "SEC01 tenant admin cannot create / grant SYSTEM_ADMIN · SEC02 tenant A cannot see or create into tenant B · SEC03 workspace admin cannot escalate";
const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
export async function run({ cfg, fx, check }) {
  const sys = fx.sessions.admin; const probe = await sys.get("/admin/tenants");
  if (probe.status === 404 && !probe.body?.code) throw new Blocked("C1", "tenant API not on this build", "T2");
  const ta = fx.users.lonelyA, wsAdmin = fx.users.adminA;
  const tA = (await sys.post("/admin/tenants", { slug: `e2e-${fx.runId}-sa`.toLowerCase(), name: `E2E SEC A ${fx.runId}`, firstAdminUserId: ta.id })).body;
  const other = await makeUser(fx, cfg, "secb", "B", "VIEWER");
  const tB = (await sys.post("/admin/tenants", { slug: `e2e-${fx.runId}-sb`.toLowerCase(), name: `E2E SEC B ${fx.runId}`, firstAdminUserId: other.id })).body;
  const A = fx.sessions.lonelyA, B = other.session, WA = fx.sessions.adminA;
  // ---- SEC01
  const c1 = await A.post("/admin/users", { username: `e2e-${fx.runId}-s1`, displayName: "s", workspaceId: fx.workspaces.A, role: "VIEWER" });
  const c2 = await A.post(`/admin/users/${other.id}/system-admin`, { grant: true, confirm: true });
  const c3 = await A.post(`/admin/users/${ta.id}/system-admin`, { grant: true, confirm: true });
  check.ok("SEC01 [api] a tenant admin cannot create an account through the platform route (403 ADMIN_REQUIRED)", c1.status === 403 && c1.body?.code === "ADMIN_REQUIRED", `status=${c1.status} ${c1.body?.code}`, "http");
  check.ok("SEC01 [api] …cannot grant SYSTEM_ADMIN to anyone, nor to themselves (403)", c2.status === 403 && c3.status === 403, `${c2.status}/${c3.status}`, "http");
  check.ok("SEC01 [api] …and the account stayed non-admin", (await sys.get(`/admin/users/${ta.id}`)).body?.user?.systemAdmin === false && (await sys.get(`/admin/users/${other.id}`)).body?.user?.systemAdmin === false, "", "persistence");
  // ---- SEC02
  const x = [await A.get(`/admin/tenants/${tB.id}`), await A.get(`/admin/tenants/${tB.id}/members`), await A.get(`/admin/tenants/${tB.id}/member-candidates`), await A.put(`/admin/tenants/${tB.id}/members/${ta.id}`, { role: "TENANT_ADMIN" }), await A.put(`/admin/tenants/${tB.id}/members/${other.id}`, { role: "MEMBER" }), await A.del(`/admin/tenants/${tB.id}/members/${other.id}`)];
  check.ok("SEC02 [api] tenant A's admin gets 404 for tenant B (tenant, members, candidates, add self, re-role, remove)", x.every((r) => r.status === 404), x.map((r) => r.status).join("/"), "http");
  const y = [await B.get(`/admin/tenants/${tA.id}/members`), await B.put(`/admin/tenants/${tA.id}/members/${other.id}`, { role: "TENANT_ADMIN" })];
  check.ok("SEC02 [api] …and the other way round (B's admin on tenant A): 404", y.every((r) => r.status === 404), y.map((r) => r.status).join("/"), "http");
  check.ok("SEC02 [api] neither can list the system-wide users (403): no cross-tenant user directory", (await A.get("/admin/users")).status === 403 && (await B.get("/admin/users")).status === 403, "", "http");
  const mb = (await B.get(`/admin/tenants/${tB.id}/members`)).body ?? [];
  check.ok("SEC02 [api] each tenant's member list holds only its own people", !mb.some((m) => m.userId === ta.id) && ((await A.get(`/admin/tenants/${tA.id}/members`)).body ?? []).every((m) => m.userId !== other.id), "", "persistence");
  // ---- SEC03
  const s = [await WA.put(`/admin/tenants/${tA.id}/members/${wsAdmin.id}`, { role: "TENANT_ADMIN" }), await WA.put(`/admin/tenants/${DEFAULT_TENANT}/members/${wsAdmin.id}`, { role: "TENANT_ADMIN" }), await WA.post("/admin/users", { username: `e2e-${fx.runId}-s3`, displayName: "s", workspaceId: fx.workspaces.A, role: "VIEWER" }), await WA.post(`/admin/users/${wsAdmin.id}/system-admin`, { grant: true, confirm: true }), await WA.post(`/admin/tenants`, { slug: `e2e-${fx.runId}-s3`, name: "x" }), await WA.patch(`/admin/tenants/${DEFAULT_TENANT}/status`, { status: "SUSPENDED" })];
  check.ok("SEC03 [api] a workspace admin cannot make themselves tenant admin (own tenant, another tenant), create accounts, grant SYSTEM_ADMIN, create or suspend tenants (all 403/404)", s.every((r) => [403, 404].includes(r.status)), s.map((r) => r.status).join("/"), "http");
  const self = await WA.patch(`/workspaces/${fx.workspaces.A}/members/${wsAdmin.id}`, { role: "WORKSPACE_ADMIN" });
  check.ok("SEC03 [api] …cannot change their own workspace role (403 SELF_GRANT_FORBIDDEN)", self.status === 403 && self.body?.code === "SELF_GRANT_FORBIDDEN", `status=${self.status} ${self.body?.code}`, "http");
  const other2 = [await WA.get(`/workspaces/${fx.workspaces.B}/members`), await WA.post(`/workspaces/${fx.workspaces.B}/members`, { username: wsAdmin.username, role: "WORKSPACE_ADMIN" })];
  check.ok("SEC03 [api] …and cannot add themselves to (or read) another workspace (403/404)", other2.every((r) => [403, 404].includes(r.status)), other2.map((r) => r.status).join("/"), "http");
  const tm = (await sys.get(`/admin/tenants/${DEFAULT_TENANT}/members`)).body ?? [];
  check.ok("SEC03 [api] nothing changed: the workspace admin is still a plain tenant MEMBER and not SYSTEM_ADMIN", tm.find((m) => m.userId === wsAdmin.id)?.role === "MEMBER" && (await sys.get(`/admin/users/${wsAdmin.id}`)).body?.user?.systemAdmin === false, "", "persistence");
  await sys.patch(`/admin/tenants/${tA.id}/status`, { status: "DELETED" }); await sys.patch(`/admin/tenants/${tB.id}/status`, { status: "DELETED" });
}
