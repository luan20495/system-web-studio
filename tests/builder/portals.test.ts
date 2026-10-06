import { test } from "node:test";
import assert from "node:assert/strict";
import { accessiblePortals, portalHref, portalOfPath, portalPath, PORTAL_PREFIX, resolvePortalPostLogin, resolvePostLogin, safeNext } from "../../packages/permissions/src/index";
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
