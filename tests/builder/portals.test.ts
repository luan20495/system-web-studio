// @class: unit — pure logic / server-side render of components; no browser, no network
import { test } from "node:test";
import assert from "node:assert/strict";
import { accessiblePortals, capabilitiesOf, hasPermission, hasWorkspace, isAdmin, portalHref, portalOfPath, portalPath, PORTAL_PREFIX, resolvePortalPostLogin, resolvePostLogin, safeNext } from "../../packages/permissions/src/index";
import type { Me } from "@xweb/types";

const ws = [{ id: "w1", name: "W" }] as unknown as Me["workspaces"];
const member: Me = { id: "u1", username: "a", displayName: "A", roles: [], workspaces: ws };
const admin: Me = { ...member, workspaces: [], systemAdmin: true };
const both: Me = { ...member, systemAdmin: true };
const nobody: Me = { ...member, workspaces: [] };

test("portal paths come from PORTAL_PREFIX, not from literals", () => {
  assert.equal(portalPath("studio", "/projects/1"), `${PORTAL_PREFIX.studio}/projects/1`);
  assert.equal(portalPath("admin"), PORTAL_PREFIX.admin);
  assert.equal(portalPath("platform", "/tenants"), "/platform/tenants");
});

test("portalHref without configured origins is path-only (same origin)", () => {
  // NEXT_PUBLIC_PORTAL_URL_* are unset in unit tests: the legacy root app and single-host dev keep working.
  assert.equal(portalHref("studio"), "/studio");
  assert.equal(portalHref("admin", "/users"), "/admin/users");
});

test("portalOfPath identifies the owning portal by first segment only", () => {
  assert.equal(portalOfPath("/studio/projects/1?x=1"), "studio");
  assert.equal(portalOfPath("/admin"), "admin");
  assert.equal(portalOfPath("/platform/tenants#a"), "platform");
  assert.equal(portalOfPath("/studiox"), null);
  assert.equal(portalOfPath("/auth/login"), null);
  assert.equal(portalOfPath(null), null);
});

test("safeNext accepts only same-app portal paths", () => {
  assert.equal(safeNext("/studio/projects/1"), "/studio/projects/1");
  assert.equal(safeNext("/studio"), "/studio");
  assert.equal(safeNext("//evil.example/studio"), null);
  assert.equal(safeNext("https://evil.example/studio"), null);
  assert.equal(safeNext("/studiox/y"), null);
  assert.equal(safeNext("/\\evil"), null);
  assert.equal(safeNext("/auth/no-access"), null);
});

test("resolvePostLogin (legacy single app) follows capabilities", () => {
  assert.equal(resolvePostLogin({ me: member, portal: null }), "/studio");
  assert.equal(resolvePostLogin({ me: admin, portal: null }), "/admin");
  assert.equal(resolvePostLogin({ me: nobody, portal: null }), "/auth/no-workspace");
  assert.equal(resolvePostLogin({ me: member, portal: "admin" }), "/auth/no-access");
  assert.equal(resolvePostLogin({ me: both, portal: "admin" }), "/admin");
  assert.equal(resolvePostLogin({ me: member, portal: null, next: "/admin/users" }), "/auth/no-access");
  assert.equal(resolvePostLogin({ me: admin, portal: null, next: "/admin/users" }), "/admin/users");
  assert.equal(resolvePostLogin({ me: member, portal: null, next: "/platform/tenants" }), "/auth/no-access");
  assert.equal(resolvePostLogin({ me: null, portal: null, next: "/studio/x" }), "/login?next=%2Fstudio%2Fx");
  assert.equal(resolvePostLogin({ me: both, portal: null, disabled: true }), "/auth/no-access?reason=disabled");
});

test("resolvePortalPostLogin stays inside the portal and never trusts hiding as authorisation", () => {
  assert.equal(resolvePortalPostLogin({ me: member, portal: "studio" }), "/studio");
  assert.equal(resolvePortalPostLogin({ me: member, portal: "studio", next: "/studio/projects/1" }), "/studio/projects/1");
  assert.equal(resolvePortalPostLogin({ me: member, portal: "studio", next: "/admin/users" }), "/studio");
  assert.equal(resolvePortalPostLogin({ me: member, portal: "admin" }), "/auth/no-access?portal=admin");
  assert.equal(resolvePortalPostLogin({ me: nobody, portal: "studio" }), "/auth/no-access?portal=studio");
});

test("accessiblePortals reflects the TEMPORARY systemAdmin mapping (D-C5-05)", () => {
  assert.deepEqual(accessiblePortals(member), ["studio"]);
  assert.deepEqual(accessiblePortals(admin), ["platform", "admin"]);
  assert.deepEqual(accessiblePortals(both), ["platform", "admin", "studio"]);
  assert.deepEqual(accessiblePortals(null), []);
});

// ---- Phase 3 prep: the portal gate reads the backend tenancy fields (MeResponse @ integration/v2) --------------------------------
const baseMe: Me = { id: "u", username: "u", displayName: "U", roles: [], workspaces: [] };
const wsRow = (permissions?: string[]) => ({ id: "w", name: "W", role: "EDITOR", tenantId: "t", permissions });

test("backend without tenancy fields: unchanged behaviour (systemAdmin / any workspace)", () => {
  assert.ok(capabilitiesOf({ ...baseMe, systemAdmin: true }).has("platform.operate"));
  assert.ok(capabilitiesOf({ ...baseMe, systemAdmin: true }).has("tenant.administer"));
  assert.ok(!capabilitiesOf({ ...baseMe, systemAdmin: false }).has("platform.operate"));
  assert.ok(capabilitiesOf({ ...baseMe, workspaces: [wsRow(undefined)] }).has("studio.build"));
});

test("platformScope wins over systemAdmin when the server sends it", () => {
  assert.ok(capabilitiesOf({ ...baseMe, systemAdmin: false, platformScope: true }).has("platform.operate"));
  assert.ok(!capabilitiesOf({ ...baseMe, systemAdmin: true, platformScope: false }).has("platform.operate"));
});

test("a TENANT_ADMIN gets the Admin console (the tenant members API + screens exist) but NOT platform operations or the SYSTEM_ADMIN-only sections", () => {
  const me: Me = { ...baseMe, tenantId: "t", tenantRole: "TENANT_ADMIN", platformScope: false, permissions: ["APP_VIEW", "APP_EDIT", "TENANT_MEMBERS"], workspaces: [wsRow(["APP_VIEW", "APP_EDIT"])] };
  const caps = capabilitiesOf(me);
  assert.ok(!caps.has("tenant.administer")); assert.ok(caps.has("tenant.members")); assert.ok(caps.has("admin.console")); assert.ok(caps.has("studio.build")); assert.ok(!caps.has("platform.operate"));
  assert.equal(isAdmin(me), true); assert.deepEqual(accessiblePortals(me), ["admin", "studio"]);
  assert.equal(hasPermission(me, "TENANT_MEMBERS"), true); assert.equal(hasPermission(me, "APP_PUBLISH"), false);
});

test("a WORKSPACE_ADMIN (MEMBER_MANAGE listed by the server) gets the Admin console; an editor / viewer does not; no role name is read", () => {
  const wsAdmin: Me = { ...baseMe, platformScope: false, permissions: [], workspaces: [wsRow(["APP_VIEW", "APP_EDIT", "MEMBER_MANAGE"])] };
  assert.ok(capabilitiesOf(wsAdmin).has("workspace.members")); assert.equal(isAdmin(wsAdmin), true); assert.ok(!capabilitiesOf(wsAdmin).has("platform.operate"));
  const editor: Me = { ...baseMe, platformScope: false, permissions: [], workspaces: [wsRow(["APP_VIEW", "APP_EDIT"])] };
  assert.equal(isAdmin(editor), false); assert.deepEqual(accessiblePortals(editor), ["studio"]);
  // DATA_SOURCE_MANAGE (canonical, role-free) opens the console for data-source administration; MEMBER_MANAGE is not a canonical code today (H-C1-05), so a WORKSPACE_ADMIN as /auth/me lists them has THIS only
  const dataAdmin: Me = { ...baseMe, platformScope: false, permissions: [], workspaces: [wsRow(["APP_VIEW", "APP_EDIT", "DATA_SOURCE_MANAGE", "DATA_SOURCE_VIEW"])] };
  assert.ok(capabilitiesOf(dataAdmin).has("workspace.data")); assert.ok(!capabilitiesOf(dataAdmin).has("workspace.members")); assert.equal(isAdmin(dataAdmin), true);
  assert.equal(isAdmin({ ...baseMe, platformScope: false, permissions: [], workspaces: [wsRow(["APP_VIEW", "DATA_SOURCE_VIEW"])] }), false, "viewing data sources alone is not administering");
  // a role NAME alone grants nothing
  const claims: Me = { ...baseMe, platformScope: false, permissions: [], workspaces: [{ id: "w", name: "W", role: "WORKSPACE_ADMIN", tenantId: "t", permissions: ["APP_VIEW"] }] };
  assert.equal(isAdmin(claims), false);
  assert.equal(resolvePortalPostLogin({ me: editor, portal: "admin" }), "/auth/no-access?portal=admin");
  assert.equal(resolvePortalPostLogin({ me: wsAdmin, portal: "admin" }).startsWith("/auth/"), false);
});

test("a platform-only SYSTEM_ADMIN (tenant-level codes in every workspace row) is not offered Studio and lands on the console", () => {
  const me: Me = { ...baseMe, systemAdmin: true, platformScope: true, businessAccess: false, tenantId: null, permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"],
    workspaces: [{ id: "w", name: "W", role: "ADMIN", tenantId: "t", permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] }] };
  assert.equal(hasWorkspace(me), false);
  assert.equal(resolvePostLogin({ me, portal: null }), "/admin");
  assert.equal(resolvePostLogin({ me, portal: "builder" }), "/auth/no-workspace");
});

test("a SYSTEM_ADMIN with businessAccess keeps Studio (workspace rows carry app codes)", () => {
  const me: Me = { ...baseMe, systemAdmin: true, platformScope: true, businessAccess: true, workspaces: [{ id: "w", name: "W", role: "ADMIN", permissions: ["APP_VIEW", "APP_EDIT", "TENANT_MANAGE"] }] };
  assert.equal(hasWorkspace(me), true);
});

test("a VIEWER-style member (APP_VIEW only) can open Studio but not the console", () => {
  const me: Me = { ...baseMe, tenantRole: "MEMBER", workspaces: [wsRow(["APP_VIEW", "APP_USE"])] };
  assert.equal(hasWorkspace(me), true); assert.equal(isAdmin(me), false);
});

test("REGRESSION (found on the real stack): a `next` that points at an authentication page is dropped, so signing in from /platform/login lands on the console, not on 'not found'", () => {
  for (const bad of ["/platform/login", "/admin/login", "/studio/login", "/login", "/login?next=/admin", "/auth/no-access", "/admin/auth/activate", "/platform/login?x=1"]) assert.equal(safeNext(bad), null, bad);
  assert.equal(safeNext("/platform/tenants"), "/platform/tenants"); assert.equal(safeNext("/admin/company"), "/admin/company");
  const me: Me = { id: "u", username: "u", displayName: "U", roles: [], workspaces: [], systemAdmin: true, platformScope: true };
  assert.equal(resolvePortalPostLogin({ me, portal: "platform", next: "/platform/login" }), "/platform");
  assert.equal(resolvePortalPostLogin({ me, portal: "admin", next: "/admin/login" }), "/admin");
});
