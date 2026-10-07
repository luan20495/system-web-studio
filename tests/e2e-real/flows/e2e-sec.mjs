// @class: real-backend — SEC01-03 on C1's tenant-scoped contract, asserted against the real API with real sessions of real, activated accounts (the UI half is asserted in e2e-admin01/super01 and in the provisioning harness, which is labelled harness).
// Every tenant here is NEW (made through POST /admin/tenants) and its accounts through POST /admin/tenants/{t}/users: nothing touches the DEFAULT tenant.
import { makeTenant, makeTenantUser } from "../lib/portals.mjs";
import { Session } from "../lib/api.mjs";
export const id = "E2E-SEC01", title = "SEC01 tenant admin cannot create SYSTEM_ADMIN · SEC02 cross-tenant is a safe 404 · SEC03 workspace admin cannot escalate";
export async function run({ cfg, fx, check }) {
  const sys = fx.sessions.admin;
  const A = await makeTenant(fx, "sa"), B = await makeTenant(fx, "sb");
  const taA = await makeTenantUser(fx, cfg, A.id, "sataA", { tenantRole: "TENANT_ADMIN" });
  const taB = await makeTenantUser(fx, cfg, B.id, "satab", { tenantRole: "TENANT_ADMIN" });
  const wsaA = await makeTenantUser(fx, cfg, A.id, "sawsa", { workspaceId: A.workspaceId, workspaceRole: "WORKSPACE_ADMIN" });
  const memB = await makeTenantUser(fx, cfg, B.id, "samemb", { workspaceId: B.workspaceId, workspaceRole: "VIEWER" });
  const name = (k) => `e2e-${fx.runId}-${k}`.toLowerCase();

  // ---- SEC01: a tenant admin cannot create / grant SYSTEM_ADMIN --------------------------------------------------------------------------------------------
  const s1 = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s1a"), displayName: "s", tenantRole: "SYSTEM_ADMIN" });
  check.ok("SEC01 [api] tenantRole SYSTEM_ADMIN is refused (400 TENANT_ROLE_INVALID)", s1.status === 400 && s1.body?.code === "TENANT_ROLE_INVALID", `status=${s1.status} ${s1.body?.code}`, "http");
  const s2 = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s1b"), displayName: "s", tenantRole: "MEMBER", systemAdmin: true, platformScope: true, role: "SYSTEM_ADMIN" });
  const s2id = s2.body?.userId; if (s2id) fx.created.users.push(s2id);
  const s2u = s2id ? (await sys.get(`/admin/users/${s2id}`)).body?.user : null;
  check.ok("SEC01 [api] unknown fields (systemAdmin, platformScope, role) never take effect: the account is a plain, non-admin MEMBER", s2.status === 201 && s2u?.systemAdmin === false && ((await sys.get(`/admin/tenants/${A.id}/members`)).body ?? []).find((m) => m.userId === s2id)?.role === "MEMBER", `status=${s2.status} systemAdmin=${s2u?.systemAdmin}`, "persistence");
  const s3 = await taA.session.post("/admin/users", { username: name("s1c"), displayName: "s", workspaceId: A.workspaceId, role: "VIEWER" });
  check.ok("SEC01 [api] the legacy platform route is closed to a tenant admin (403 ADMIN_REQUIRED)", s3.status === 403 && s3.body?.code === "ADMIN_REQUIRED", `status=${s3.status} ${s3.body?.code}`, "http");
  const s4 = [await taA.session.post(`/admin/users/${memB.id}/system-admin`, { grant: true, confirm: true }), await taA.session.post(`/admin/users/${taA.id}/system-admin`, { grant: true, confirm: true })];
  check.ok("SEC01 [api] …cannot grant SYSTEM_ADMIN to anyone nor to itself (403)", s4.every((r) => r.status === 403), s4.map((r) => r.status).join("/"), "http");
  check.ok("SEC01 [api] nobody became a SYSTEM_ADMIN", (await sys.get(`/admin/users/${taA.id}`)).body?.user?.systemAdmin === false && (await sys.get(`/admin/users/${memB.id}`)).body?.user?.systemAdmin === false, "", "persistence");
  const meA = (await taA.session.get("/auth/me")).body;
  check.ok("SEC01 [api] the tenant admin's /auth/me carries no platform scope", meA?.platformScope !== true && meA?.systemAdmin !== true, JSON.stringify({ platformScope: meA?.platformScope, systemAdmin: meA?.systemAdmin }), "http");

  // ---- SEC02: cross-tenant is a safe 404 ----------------------------------------------------------------------------------------------------------------------
  const x = {
    createUser: await taA.session.post(`/admin/tenants/${B.id}/users`, { username: name("s2x"), displayName: "x" }),
    createWs: await taA.session.post(`/admin/tenants/${B.id}/workspaces`, { name: "x" }),
    members: await taA.session.get(`/admin/tenants/${B.id}/members`),
    candidates: await taA.session.get(`/admin/tenants/${B.id}/member-candidates?q=e2e`),
    setRole: await taA.session.put(`/admin/tenants/${B.id}/members/${taA.id}`, { role: "TENANT_ADMIN" }),
    reRole: await taA.session.put(`/admin/tenants/${B.id}/members/${memB.id}`, { role: "TENANT_ADMIN" }),
    remove: await taA.session.del(`/admin/tenants/${B.id}/members/${memB.id}`),
    tenant: await taA.session.get(`/admin/tenants/${B.id}`),
  };
  check.ok("SEC02 [api] tenant A's admin gets 404 TENANT_NOT_FOUND on every tenant-B route (create user, create workspace, members, candidates, add self, re-role, remove, read)", Object.values(x).every((r) => r.status === 404 && (!r.body?.code || r.body.code === "TENANT_NOT_FOUND" || r.body.code === "NOT_FOUND")), Object.entries(x).map(([k, r]) => `${k}=${r.status}/${r.body?.code ?? ""}`).join(" "), "http");
  check.ok("SEC02 [api] …and the refused create left nothing behind (no account named like it)", ((await sys.get(`/admin/users?q=${encodeURIComponent(name("s2x"))}`)).body?.items ?? []).length === 0, "", "persistence");
  const y = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s2y"), displayName: "y", workspaceId: B.workspaceId, workspaceRole: "VIEWER" });
  check.ok("SEC02 [api] a workspace of ANOTHER tenant in my tenant's create → 404 WORKSPACE_NOT_FOUND, and no account is created", y.status === 404 && y.body?.code === "WORKSPACE_NOT_FOUND" && ((await sys.get(`/admin/users?q=${encodeURIComponent(name("s2y"))}`)).body?.items ?? []).length === 0, `status=${y.status} ${y.body?.code}`, "http");
  const z = [await taB.session.get(`/admin/tenants/${A.id}/members`), await taB.session.post(`/admin/tenants/${A.id}/users`, { username: name("s2z"), displayName: "z" }), await taB.session.get(`/admin/tenants/${A.id}`)];
  check.ok("SEC02 [api] …and the other way round (B's admin on tenant A): 404", z.every((r) => r.status === 404), z.map((r) => r.status).join("/"), "http");
  check.ok("SEC02 [api] neither tenant admin can list the system-wide users or workspaces (403)", (await taA.session.get("/admin/users")).status === 403 && (await taB.session.get("/admin/users")).status === 403, "", "http");
  const mb = (await taB.session.get(`/admin/tenants/${B.id}/members`)).body ?? [], ma = (await taA.session.get(`/admin/tenants/${A.id}/members`)).body ?? [];
  check.ok("SEC02 [api] each tenant's member list holds only its own people", !mb.some((m) => [taA.id, wsaA.id].includes(m.userId)) && !ma.some((m) => [taB.id, memB.id].includes(m.userId)), `A=${ma.length} B=${mb.length}`, "persistence");

  // ---- SEC03: a workspace admin cannot escalate ---------------------------------------------------------------------------------------------------------------
  const w = {
    createUser: await wsaA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s3a"), displayName: "w" }),
    createWs: await wsaA.session.post(`/admin/tenants/${A.id}/workspaces`, { name: "w" }),
    candidates: await wsaA.session.get(`/admin/tenants/${A.id}/member-candidates?q=e2e`),
    members: await wsaA.session.get(`/admin/tenants/${A.id}/members`),
    selfTenant: await wsaA.session.put(`/admin/tenants/${A.id}/members/${wsaA.id}`, { role: "TENANT_ADMIN" }),
    legacyUser: await wsaA.session.post("/admin/users", { username: name("s3b"), displayName: "w", workspaceId: A.workspaceId, role: "VIEWER" }),
    sysAdmin: await wsaA.session.post(`/admin/users/${wsaA.id}/system-admin`, { grant: true, confirm: true }),
    newTenant: await wsaA.session.post("/admin/tenants", { slug: name("s3t"), name: "x" }),
    suspend: await wsaA.session.patch(`/admin/tenants/${A.id}/status`, { status: "SUSPENDED" }),
  };
  check.ok("SEC03 [api] a workspace admin cannot call the tenant routes (create user / workspace, candidates, members, make itself tenant admin) → 403, nor platform routes (legacy create, system-admin, new tenant, suspend)", Object.values(w).every((r) => r.status === 403), Object.entries(w).map(([k, r]) => `${k}=${r.status}/${r.body?.code ?? ""}`).join(" "), "http");
  check.ok("SEC03 [api] …with the codes of the contract (FORBIDDEN on tenant routes, ADMIN_REQUIRED on platform routes)", w.createUser.body?.code === "FORBIDDEN" && w.selfTenant.body?.code === "FORBIDDEN" && w.legacyUser.body?.code === "ADMIN_REQUIRED", `${w.createUser.body?.code}/${w.selfTenant.body?.code}/${w.legacyUser.body?.code}`, "http");
  const self = await wsaA.session.patch(`/workspaces/${A.workspaceId}/members/${wsaA.id}`, { role: "WORKSPACE_ADMIN" });
  check.ok("SEC03 [api] …cannot change its own workspace role (403 SELF_GRANT_FORBIDDEN)", self.status === 403 && self.body?.code === "SELF_GRANT_FORBIDDEN", `status=${self.status} ${self.body?.code}`, "http");
  const o = [await wsaA.session.get(`/workspaces/${B.workspaceId}/members`), await wsaA.session.post(`/workspaces/${B.workspaceId}/members`, { username: wsaA.username, role: "WORKSPACE_ADMIN" })];
  check.ok("SEC03 [api] …cannot read, nor add itself to, a workspace of another tenant (403/404)", o.every((r) => [403, 404].includes(r.status)), o.map((r) => r.status).join("/"), "http");
  const foreign = await wsaA.session.post(`/workspaces/${A.workspaceId}/members`, { username: memB.username, role: "VIEWER" });
  check.ok("SEC03 [api] …cannot pull a person of ANOTHER tenant into its workspace (404 USER_NOT_FOUND)", foreign.status === 404 && foreign.body?.code === "USER_NOT_FOUND", `status=${foreign.status} ${foreign.body?.code}`, "http");
  const tm = (await sys.get(`/admin/tenants/${A.id}/members`)).body ?? [];
  check.ok("SEC03 [api] nothing changed: still a plain tenant MEMBER, not a SYSTEM_ADMIN, the tenant still ACTIVE", tm.find((m) => m.userId === wsaA.id)?.role === "MEMBER" && (await sys.get(`/admin/users/${wsaA.id}`)).body?.user?.systemAdmin === false && (await sys.get(`/admin/tenants/${A.id}`)).body?.status !== "SUSPENDED", "", "persistence");

  // ---- 401 / CSRF / body injection ---------------------------------------------------------------------------------------------------------------------------
  const anon = new Session(cfg.studio, "anon"); await anon.refreshCsrf();
  const a401 = [await anon.post(`/admin/tenants/${A.id}/users`, { username: name("s4a"), displayName: "a" }), await anon.post(`/admin/tenants/${A.id}/workspaces`, { name: "a" }), await anon.get(`/admin/tenants/${A.id}/members`)];
  check.ok("401 [api] an unauthenticated caller (valid CSRF, no session) is refused on create user / create workspace / list members: 401", a401.every((r) => r.status === 401), a401.map((r) => `${r.status}/${r.body?.code ?? ""}`).join(" "), "http");
  check.ok("401 [api] …and nothing was created (no account named like it)", ((await sys.get(`/admin/users?q=${encodeURIComponent(name("s4a"))}`)).body?.items ?? []).length === 0, "", "persistence");
  const c1 = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s4b"), displayName: "c" }, { noCsrf: true });
  const c2 = await taA.session.post(`/admin/tenants/${A.id}/workspaces`, { name: name("s4ws") }, { noCsrf: true });
  const c3 = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s4c"), displayName: "c" }, { headers: { "X-XSRF-TOKEN": "not-the-token" } });
  check.ok("CSRF [api] an authenticated mutating request WITHOUT the CSRF header, or with a wrong one, is refused (403) and does nothing", [c1, c2, c3].every((r) => r.status === 403) && ((await sys.get(`/admin/users?q=${encodeURIComponent(name("s4b"))}`)).body?.items ?? []).length === 0, `${c1.status}/${c2.status}/${c3.status} codes=${[c1, c2, c3].map((r) => r.body?.code ?? "").join(",")}`, "http");
  const okc = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s4d"), displayName: "c" });
  if (okc.body?.userId) fx.created.users.push(okc.body.userId);
  check.ok("CSRF [api] the same request WITH the session's CSRF token succeeds (201): the refusal above was the missing token, not the route", okc.status === 201, `status=${okc.status} ${okc.body?.code}`, "http");
  const inj = await taA.session.post(`/admin/tenants/${A.id}/users`, { username: name("s4e"), displayName: "i", tenantId: B.id, tenant: B.id, workspaceId: undefined });
  if (inj.body?.userId) fx.created.users.push(inj.body.userId);
  const inA = ((await sys.get(`/admin/tenants/${A.id}/members`)).body ?? []).some((m) => m.userId === inj.body?.userId), inB = ((await sys.get(`/admin/tenants/${B.id}/members`)).body ?? []).some((m) => m.userId === inj.body?.userId);
  check.ok("body injection [api] a foreign tenant id in the BODY (tenantId / tenant) is ignored: the path is the authority — the account is a member of tenant A, not of B", inj.status === 201 && inA && !inB, `status=${inj.status} inA=${inA} inB=${inB}`, "persistence");
  const injW = await taA.session.post(`/admin/tenants/${A.id}/workspaces`, { name: name("s4w"), tenantId: B.id });
  if (injW.body?.id) fx.created.workspaces.push(injW.body.id);
  check.ok("body injection [api] …and a workspace created with a foreign tenantId in the body belongs to tenant A (the path)", injW.status === 201 && injW.body?.tenantId === A.id, `status=${injW.status} tenantId=${injW.body?.tenantId === A.id ? "A" : injW.body?.tenantId}`, "persistence");
}
