// @class: unit — GUARD (M-052): the Platform / Admin consoles and the permissions package read an admin ROLE NAME (TENANT_ADMIN / WORKSPACE_ADMIN / SYSTEM_ADMIN) in ONE place only
import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { isTenantAdminRole, isWorkspaceAdminRole } from "../../packages/permissions/src/roles";

// __dirname = .test-build/tests/builder when compiled
const ROOT = join(__dirname, "..", "..", "..");
/** the single place allowed to compare a role name: a role label is display data, never an authority (the server lists the codes); the console reads it ONLY for "which tenants to show" and the last-admin hint */
const ALLOWED = new Set(["packages/permissions/src/roles.ts"]);
const NAMES = "TENANT_ADMIN|WORKSPACE_ADMIN|SYSTEM_ADMIN";
const COMPARES = [new RegExp(`[!=]==?\\s*"(${NAMES})"`), new RegExp(`"(${NAMES})"\\s*[!=]==?`), new RegExp(`\\.(includes|indexOf)\\(\\s*"(${NAMES})"`), new RegExp(`\\bcase\\s+"(${NAMES})"`)];

function scan(roots: string[]): string[] {
  const bad: string[] = [];
  const walk = (d: string) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n); if (statSync(p).isDirectory()) { walk(p); continue; }
      if (!/\.(ts|tsx)$/.test(n) || ALLOWED.has(relative(ROOT, p))) continue;
      readFileSync(p, "utf8").split("\n").forEach((line, i) => {
        if (/^\s*(\*|\/\/|\/\*)/.test(line)) return;
        if (COMPARES.some((re) => re.test(line))) bad.push(`${relative(ROOT, p)}:${i + 1}: ${line.trim().slice(0, 110)}`);
      });
    }
  };
  roots.forEach((r) => walk(join(ROOT, r)));
  return bad;
}

test("GUARD: features/admin and packages/permissions never compare an admin role name outside packages/permissions/src/roles.ts", () => {
  assert.deepEqual(scan(["features/admin", "packages/permissions/src"]), [], "use isTenantAdminRole / isWorkspaceAdminRole (roles.ts), or better a permission code the server lists");
});

test("GUARD self-check: the scanner does see a comparison (so an empty result means something)", () => {
  const sample = ['x.role === "TENANT_ADMIN"', '"WORKSPACE_ADMIN" !== r', 'roles.includes("SYSTEM_ADMIN")', 'case "TENANT_ADMIN":'];
  for (const line of sample) assert.ok(COMPARES.some((re) => re.test(line)), line);
  for (const line of ['tenantRole: "TENANT_ADMIN",', 'const next: "TENANT_ADMIN" | "MEMBER"', 'ACCOUNT_TYPES.WORKSPACE_ADMIN.roles']) assert.ok(!COMPARES.some((re) => re.test(line)), line);
});

test("the helper answers exactly the names the console used to compare (behaviour unchanged), and nothing for null / unknown", () => {
  assert.equal(isTenantAdminRole("TENANT_ADMIN"), true); assert.equal(isTenantAdminRole("MEMBER"), false); assert.equal(isTenantAdminRole(null), false); assert.equal(isTenantAdminRole(undefined), false); assert.equal(isTenantAdminRole("tenant_admin"), false);
  assert.equal(isWorkspaceAdminRole("WORKSPACE_ADMIN"), true); assert.equal(isWorkspaceAdminRole("EDITOR"), false); assert.equal(isWorkspaceAdminRole("TENANT_ADMIN"), false);
});
