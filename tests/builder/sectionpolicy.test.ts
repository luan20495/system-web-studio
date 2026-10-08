// @class: unit — which sections the Platform / Admin consoles list, own, open and route (pure policy over ONE table; the real table is exercised by the admin harness route snapshot)
import test from "node:test";
import assert from "node:assert/strict";
import type { Me } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { navSections, owns, resolveSection, sectionAccess, type SectionMeta } from "../../features/admin/console/sectionPolicy";

const me = (o: Partial<Me> = {}): Me => ({ id: "u1", username: "a", displayName: "A", roles: [], workspaces: [], ...o });
const ws = (id: string, permissions: string[]) => ({ id, name: `W-${id}`, role: "x", tenantId: "t", permissions });

/** a small table with one section of each kind (same shape as console/sections.tsx, no React) */
const S = (o: Partial<SectionMeta> & { key: string }): SectionMeta => ({ label: o.key || "home", portals: [], access: "open", surface: "standard", listed: "main", ...o });
const TABLE: SectionMeta[] = [
  S({ key: "", portals: ["platform", "admin"] }),
  S({ key: "tenants", portals: ["platform"], access: "system", surface: "platform-only", listed: "platform-main" }),
  S({ key: "users", portals: ["platform", "admin"], access: "system" }),
  S({ key: "workspaces", portals: ["platform", "admin"], access: "system", listed: "hidden" }),
  S({ key: "ai", portals: ["platform"], access: "system" }),
  S({ key: "applications", portals: ["admin"], access: "system" }),
  S({ key: "company", access: "company", surface: "scoped", listed: "scoped", denied: "công ty", navWhen: (s) => !s.platform && s.tenants.length > 0 }),
  S({ key: "organization", access: "tenant", surface: "scoped", listed: "scoped", denied: "công ty", navWhen: (s) => !s.platform && s.tenants.length > 0 }),
  S({ key: "people", access: "open", surface: "people", listed: "scoped", denied: "công ty hay workspace", navWhen: (s) => !s.platform && (s.tenants.length > 0 || s.workspaces.length > 0) }),
  S({ key: "my-workspaces", access: "workspace", surface: "scoped", listed: "scoped", denied: "workspace", navWhen: (s) => !s.platform && s.workspaces.length > 0 }),
  S({ key: "data-sources", portals: ["admin"], access: "data", surface: "scoped", listed: "scoped", denied: "nguồn dữ liệu", navWhen: (s) => s.dataWorkspaces.length > 0 }),
  S({ key: "groups", portals: ["admin"], surface: "coming", listed: "coming" }),
];
const none = adminScope(me()); const sys = adminScope(me({ platformScope: true }));
const tenantAdmin = adminScope(me({ tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: [{ id: "t1", slug: "a", name: "A", status: "ACTIVE", role: "MEMBER" }], workspaces: [ws("w", ["MEMBER_MANAGE", "DATA_SOURCE_MANAGE"])] }));
const keys = (l: SectionMeta[]) => l.map((s) => s.key);

test("sectionAccess: system sections need the platform; scoped sections need their own scope; the overview and unknown keys are open", () => {
  for (const k of ["users", "workspaces", "applications", "ai", "tenants"]) { assert.equal(sectionAccess(TABLE, k, none), "needs-platform", k); assert.equal(sectionAccess(TABLE, k, sys), "ok", k); }
  assert.equal(sectionAccess(TABLE, "", none), "ok"); assert.equal(sectionAccess(TABLE, "nope", none), "ok", "an unknown key is not a permission question (the router says notfound / elsewhere)");
  assert.equal(sectionAccess(TABLE, "company", none), "needs-scope"); assert.equal(sectionAccess(TABLE, "my-workspaces", none), "needs-scope"); assert.equal(sectionAccess(TABLE, "data-sources", none), "needs-scope");
  const wsAdmin = adminScope(me({ workspaces: [ws("w", ["MEMBER_MANAGE"])] })); assert.equal(sectionAccess(TABLE, "my-workspaces", wsAdmin), "ok"); assert.equal(sectionAccess(TABLE, "company", wsAdmin), "needs-scope");
  assert.equal(sectionAccess(TABLE, "company", sys), "ok", "a SYSTEM_ADMIN may open any tenant");
  assert.equal(sectionAccess(TABLE, "organization", sys), "needs-scope", "organization needs a tenant of one's own");
});

test("owns: a dedicated console owns what the table says; the legacy 'all' console owns everything", () => {
  assert.equal(owns(TABLE, "platform", "tenants"), true); assert.equal(owns(TABLE, "admin", "tenants"), false); assert.equal(owns(TABLE, "admin", "applications"), true); assert.equal(owns(TABLE, "platform", "applications"), false);
  assert.equal(owns(TABLE, "platform", "company"), false, "scoped screens belong to nobody's ownership list"); assert.equal(owns(TABLE, "admin", "data-sources"), true); assert.equal(owns(TABLE, "platform", "data-sources"), false);
  assert.equal(owns(TABLE, "all", "anything"), true);
});

test("navSections: Platform = overview, companies, then what it owns; Admin = a SYSTEM_ADMIN's system sections or a scoped admin's own screens, then the placeholders; 'all' = the base list", () => {
  assert.deepEqual(keys(navSections(TABLE, "platform", sys)), ["", "tenants", "users", "ai"]);
  assert.deepEqual(keys(navSections(TABLE, "admin", sys)), ["", "users", "applications", "groups"], "a SYSTEM_ADMIN with no data workspace: no data-sources");
  assert.deepEqual(keys(navSections(TABLE, "admin", tenantAdmin)), ["", "company", "organization", "people", "my-workspaces", "data-sources", "groups"]);
  assert.deepEqual(keys(navSections(TABLE, "admin", adminScope(me({ workspaces: [ws("w", ["DATA_SOURCE_VIEW"])] })))), ["", "data-sources", "groups"]);
  assert.deepEqual(keys(navSections(TABLE, "all", sys)), ["", "users", "ai", "applications"], "hidden / platform-main / scoped / coming entries are not in the legacy list");
});

test("resolveSection: the same decisions the console always made (coming, tenants, scoped, people, elsewhere, notfound)", () => {
  const r = (portal: "platform" | "admin" | "all", key: string, scope = none) => resolveSection(TABLE, portal, key, scope);
  assert.equal(r("admin", "groups").kind, "coming"); assert.equal(r("platform", "groups").kind, "elsewhere", "placeholders exist in the Admin console only"); assert.equal(r("all", "groups").kind, "notfound");
  assert.equal(r("platform", "tenants", sys).kind, "page"); assert.equal(r("admin", "tenants", sys).kind, "elsewhere"); assert.equal(r("admin", "tenants").kind, "needs-platform");
  assert.deepEqual(r("admin", "company"), { kind: "needs-scope", what: "công ty" }); assert.equal(r("admin", "company", tenantAdmin).kind, "page");
  assert.deepEqual(r("admin", "people"), { kind: "needs-scope", what: "công ty hay workspace" }); assert.equal(r("admin", "people", tenantAdmin).kind, "page");
  assert.equal(r("admin", "people", sys).kind, "elsewhere", "a SYSTEM_ADMIN does not use the people screen of the Admin console");
  assert.equal(r("admin", "").kind, "scoped-home"); assert.equal(r("admin", "", sys).kind, "page"); assert.equal(r("admin", "users").kind, "needs-platform"); assert.equal(r("admin", "users", sys).kind, "page");
  assert.equal(r("admin", "ai", sys).kind, "elsewhere"); assert.equal(r("platform", "company").kind, "elsewhere"); assert.equal(r("admin", "nope", sys).kind, "elsewhere", "today's answer for an unknown key (M-055 changes it)");
  assert.equal(r("all", "company").kind, "notfound"); assert.equal(r("all", "users").kind, "page"); assert.equal(r("all", "nope").kind, "notfound");
});
