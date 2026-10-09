// @class: unit
// The guards are tested like code: each one must PASS on a clean fixture and FAIL — with the right rule — when its invariant is deliberately broken. Nothing here touches the real tree
// except the last block, which mutates a COPY of the real organization files. Run: node --test tests/guards/guards.test.mjs   (npm run guard:test)
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, cpSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { spawnSync } from "node:child_process";
import { REPO } from "./lib.mjs";
import { guardHierarchy, guardFailClosed, guardRelationNotPermission } from "./org-source-guards.mjs";
import { guardLegacyWorkspaceRoute } from "./no-legacy-admin-workspaces.mjs";
import { guardTestLabeling } from "./test-labeling.mjs";
import { guardMigrationLedger } from "./migration-ledger.mjs";
import { guardProcessSafety } from "./process-safety.mjs";
import { guardPermissionMirror } from "./permission-mirror.mjs";
import { scanAll } from "../../scripts/scan-prod-bundles.mjs";

const dirs = [];
function fx(files) { const d = mkdtempSync(join(tmpdir(), "guard-")); dirs.push(d); for (const [p, c] of Object.entries(files)) { if (c === undefined) continue; mkdirSync(dirname(join(d, p)), { recursive: true }); writeFileSync(join(d, p), c); } return d; }
test.after(() => dirs.forEach((d) => rmSync(d, { recursive: true, force: true })));
const rules = (f) => [...new Set(f.map((x) => x.rule))].sort();
const PERM_KT = `object PermissionCodes {\n    val CANONICAL: Set<String> = setOf(\n        "APP_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS", "MEMBER_MANAGE"\n    )\n}\n`;
const CONTRACT = (wired = []) => JSON.stringify({ wired });
const ORG = "features/admin/organizationTree.ts";

// ----------------------------------------------------------------------------------------------------------------------------------- 1. hierarchy
test("G1 hierarchy: data-driven tree code, labels, placeholders, comments, tests and pragma'd lines are CLEAN", () => {
  const root = fx({
    [ORG]: `// depth 3 is a Department in some companies — a comment never counts
export const hint = "Ví dụ: Phòng Kỹ thuật, Team Mobile, Công ty A";
export const build = (u, depth) => ({ ...u, depth, indent: depth * 18, aria: depth + 1, isRoot: !depth });
export const sameLevel = (a, b) => a.depth === b.depth - 1 || a.depth === b.depth;
export const ok = (type, parent) => !type.allowedParentTypeIds?.length || type.allowedParentTypeIds.includes(parent?.typeId);
const legacy = (n) => n.depth > 4; // guard-allow: ORG-HIERARCHY-LEVEL-INDEX — pagination chunk size, not a tree level
const H = level === 2 ? "h2" : "h3"; // a heading level (document structure), not an organization level
export const Panel = ({ level }) => level === 3 ? <h3>Title</h3> : <h2>Title</h2>;
`,
    "tests/builder/organization.test.ts": `if (node.depth === 2) { /* a fixture may say anything */ } const MAX_DEPTH = 3;`,
  });
  assert.deepEqual(guardHierarchy(root), []);
});
for (const [name, code, rule] of [
  ["a level index with a meaning", `export const kind = (n) => n.depth === 2 ? "department" : "x";`, "ORG-HIERARCHY-LEVEL-INDEX"],
  ["a heading-looking level that picks a NAME (the heading exemption is not a loophole)", `export const kind = (level) => level === 2 ? "manager" : "h2-looking-but-not-a-tag-x";`, "ORG-HIERARCHY-LEVEL-INDEX"],
  ["a level compared the other way round", `export const deep = (level) => 3 <= level;`, "ORG-HIERARCHY-LEVEL-INDEX"],
  ["a fixed maximum depth (max-depth > 5)", `export const tooDeep = (u) => u.depth > 5;`, "ORG-HIERARCHY-LEVEL-INDEX"],
  ["level 0 means company", `export const label = (n) => n.level === 0 ? "Company" : "Unit";`, "ORG-HIERARCHY-LEVEL-NAME"],
  ["a switch per level", `export function f(depth) { switch (depth) { case 0: return 1; default: return 2; } }`, "ORG-HIERARCHY-SWITCH"],
  ["a MAX_DEPTH constant", `export const MAX_DEPTH = 6;`, "ORG-HIERARCHY-MAX-DEPTH"],
  ["maxDepth property", `export const cfg = { maxDepth: 4 };`, "ORG-HIERARCHY-MAX-DEPTH"],
  ["a table of level names", `export const LEVEL_NAMES = ["Company", "Department", "Team"];`, "ORG-HIERARCHY-LEVEL-TABLE"],
  ["a name looked up by depth", `export const nameOf = (n) => LEVEL_LABELS[n.depth];`, "ORG-HIERARCHY-LEVEL-TABLE"],
  ["logic keyed on a type name", `export const isDept = (u) => u.typeId === "department";`, "ORG-HIERARCHY-TYPE-NAME"],
  ["logic keyed on a Vietnamese type name", `export const isDept = (t) => t.type.name === "Phòng";`, "ORG-HIERARCHY-TYPE-NAME"],
  ["a hard-coded transition table", `export const rules = { company: ["department"], department: ["team"] };`, "ORG-HIERARCHY-TRANSITIONS"],
  ["ALLOWED_PARENTS constant", `export const ALLOWED_PARENTS = { team: "department" };`, "ORG-HIERARCHY-TRANSITIONS"],
]) test(`G1 hierarchy FAILS: ${name}`, () => assert.ok(rules(guardHierarchy(fx({ [ORG]: code }))).includes(rule), `${rule} expected for: ${code}`));
test("G1 hierarchy also guards the future backend (a Kotlin organization file)", () => {
  const root = fx({ "backend/src/main/kotlin/com/systemwebstudio/organization/OrgUnitService.kt": `class OrgUnitService { fun ok(depth: Int) = depth > 5 }` });
  assert.deepEqual(rules(guardHierarchy(root)), ["ORG-HIERARCHY-LEVEL-INDEX"]);
});

// ----------------------------------------------------------------------------------------------------------------------------------- 2. fail-closed
const FC = (code, extra = {}) => fx({ "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt": PERM_KT, "tests/guards/org-contract.json": CONTRACT(), "tests/guards/org-guards.allow.json": JSON.stringify({ allow: [] }), [ORG]: code, ...extra });
test("G2 fail-closed: an adapter that throws, reports errors and uses canonical permissions is CLEAN", () => {
  assert.deepEqual(guardFailClosed(FC(`import x from "./organization";\nexport async function save(api) { try { await api.create(); } catch (e) { setError(String(e)); throw e; } }\nconst need = ["TENANT_MANAGE", "MEMBER_MANAGE"];\nconst code = "ORGANIZATION_NOT_READY";\nconst role = "TENANT_ADMIN";`)), []);
});
for (const [name, code, rule] of [
  ["an empty catch swallows OrganizationNotReady", `export async function f(api) { try { await api.create(); } catch {} }`, "ORG-FAIL-CLOSED-SWALLOW"],
  ["a comment-only catch is still empty", `export async function f(api) { try { await api.create(); } catch (e) { /* ignore */ } }`, "ORG-FAIL-CLOSED-SWALLOW"],
  [".catch(() => null)", `export const f = (p) => p.catch(() => null);`, "ORG-FAIL-CLOSED-SWALLOW"],
  [".catch(() => true) (fake success)", `export const f = (p) => p.catch(() => true);`, "ORG-FAIL-CLOSED-SWALLOW"],
  ["localStorage as a fake database", `export const save = (u) => localStorage.setItem("org-units", JSON.stringify(u));`, "ORG-FAIL-CLOSED-PERSIST"],
  ["indexedDB", `export const open = () => indexedDB.open("org");`, "ORG-FAIL-CLOSED-PERSIST"],
  ["a unit id made in the browser", `export const mk = (name) => ({ id: crypto.randomUUID(), name });`, "ORG-FAIL-CLOSED-CLIENT-ID"],
  ["Math.random id", `export const mk = () => ({ id: "u" + Math.random() });`, "ORG-FAIL-CLOSED-CLIENT-ID"],
  ["a guessed route", `export const url = (t) => \`/api/v1/admin/tenants/\${t}/organization-units\`;`, "ORG-FAIL-CLOSED-ROUTE"],
  ["a guessed route (employees)", `export const u = "/api/v1/employees";`, "ORG-FAIL-CLOSED-ROUTE"],
  ["an invented permission", `export const need = "ORG_MANAGE";`, "ORG-FAIL-CLOSED-PERMISSION"],
  ["an invented permission, entity style", `export const need = "UNIT_EDIT";`, "ORG-FAIL-CLOSED-PERMISSION"],
]) test(`G2 fail-closed FAILS: ${name}`, () => assert.ok(rules(guardFailClosed(FC(code))).includes(rule), `${rule} expected for: ${code}`));
test("G2 fail-closed: a guessed route in api-client is caught too (that is where it would really be added), and a relative import is not a route", () => {
  assert.ok(rules(guardFailClosed(FC(`import a from "./organization";`, { "packages/api-client/src/api.ts": `export const x = call("/admin/tenants/1/org-units");` }))).includes("ORG-FAIL-CLOSED-ROUTE"));
  assert.deepEqual(guardFailClosed(FC(`import a from "./organization"; import b from "../organization/x";`)), []);
});
test("G2 fail-closed: a route is allowed ONLY when a capability is wired in org-contract.json (two-key change)", () => {
  const code = `export const url = (t) => \`/api/v1/admin/tenants/\${t}/organization-units\`;`;
  assert.ok(rules(guardFailClosed(FC(code))).includes("ORG-FAIL-CLOSED-ROUTE"));
  assert.deepEqual(guardFailClosed(FC(code, { "tests/guards/org-contract.json": CONTRACT(["listOrganizationUnits"]) })), []);
});
test("G2 fail-closed: the allow-list needs owner + reason and goes stale when the token is gone", () => {
  const allow = (o) => ({ "tests/guards/org-guards.allow.json": JSON.stringify({ allow: [o] }) });
  assert.deepEqual(guardFailClosed(FC(`export type Need = "ORG_REPORT_VIEW";`, allow({ file: ORG, token: "ORG_REPORT_VIEW", owner: "C1+C5", reason: "placeholder", trigger: "H-C1-17 freezes the permissions", outcome: "replace or remove, then delete this entry" }))), []);
  assert.ok(rules(guardFailClosed(FC(`export type Need = "ORG_REPORT_VIEW";`, allow({ file: ORG, token: "ORG_REPORT_VIEW" })))).includes("ORG-FAIL-CLOSED-ALLOWLIST"));
  assert.ok(rules(guardFailClosed(FC(`export type Need = "ORG_REPORT_VIEW";`, allow({ file: ORG, token: "ORG_REPORT_VIEW", owner: "C1+C5", reason: "x" })))).includes("ORG-FAIL-CLOSED-ALLOWLIST"), "an entry without trigger / outcome is refused");
  assert.ok(rules(guardFailClosed(FC(`export const a = 1;`, allow({ file: ORG, token: "ORG_REPORT_VIEW", owner: "C1+C5", reason: "x", trigger: "t", outcome: "o" })))).includes("ORG-FAIL-CLOSED-ALLOWLIST"));
  assert.ok(rules(guardFailClosed(FC(`export const a = 1;`, { "tests/guards/org-contract.json": undefined }))).includes("ORG-FAIL-CLOSED-LEDGER"), "the wired-capabilities ledger must exist");
});

// ----------------------------------------------------------------------------------------------------------------------------------- 3. relation != permission
test("G3 relation: labels, HTTP HEAD, tenant role MEMBER, and a relation used only for display are CLEAN", () => {
  const root = fx({
    "features/admin/Directory.tsx": `export const label = (e) => e.isManager ? "Trưởng đơn vị" : "Nhân viên";\nexport const sort = (a, b) => Number(b.isHead) - Number(a.isHead);\nexport const badge = (r) => r.relationType === "MANAGER" ? "M" : "";`,
    "features/admin/Roles.tsx": `export const isMember = (m) => m.tenantRole === "MEMBER" || me.tenantRole === "TENANT_ADMIN";`,
    "backend/src/main/kotlin/com/systemwebstudio/identity/Sec.kt": `val cfg = http.authorizeHttpRequests { it.requestMatchers(HttpMethod.HEAD, "/sites/**").permitAll() }`,
    "packages/permissions/src/index.ts": `export const capabilitiesOf = (me) => me.permissions;`,
  });
  assert.deepEqual(guardRelationNotPermission(root), []);
});
for (const [name, files, rule] of [
  ["a manager decides canEdit", { "features/admin/X.tsx": `export const canEdit = (e) => e.isManager && hasPermission(me, "APP_EDIT");` }, "ORG-RELATION-AUTH"],
  ["relation type compared inside a permission check (wrapped over lines)", { "features/admin/X.tsx": `export const allowed = (r) =>\n  r.relationType === "HEAD"\n    ? capabilities.add("TENANT_MANAGE") : null;` }, "ORG-RELATION-AUTH"],
  ["a unit head becomes admin", { "features/admin/X.tsx": `export const role = (u) => (u.isUnitHead ? "WORKSPACE_ADMIN" : "VIEWER");` }, "ORG-RELATION-AUTH"],
  ["a relation → permission table", { "features/admin/X.tsx": `export const GRANTS = { MANAGER: "MEMBER_MANAGE", HEAD: "TENANT_MANAGE" };` }, "ORG-RELATION-MAP"],
  ["a relation → role table", { "lib/x.ts": `const m = { LEADER: "TENANT_ADMIN" };` }, "ORG-RELATION-MAP"],
  ["Kotlin: relation enum feeding a Permission", { "backend/src/main/kotlin/com/systemwebstudio/organization/R.kt": `fun perms(r: UnitRelation) = if (r == UnitRelation.MANAGER) setOf(Permission.MEMBER_MANAGE) else emptySet()` }, "ORG-RELATION-AUTH"],
  ["the permissions package reads a unit", { "packages/permissions/src/index.ts": `export const f = (me) => me.orgUnitId ? ["APP_VIEW"] : [];` }, "ORG-RELATION-AUTH-LAYER"],
  ["the backend access layer reads an employee position", { "backend/src/main/kotlin/com/systemwebstudio/access/AccessService.kt": `fun can(u: User) = u.positionId != null` }, "ORG-RELATION-AUTH-LAYER"],
]) test(`G3 relation FAILS: ${name}`, () => assert.ok(rules(guardRelationNotPermission(fx(files))).includes(rule), `${rule} expected`));

// ----------------------------------------------------------------------------------------------------------------------------------- 4. production bundle scan
const BUNDLE = (files, compose) => fx({ ...Object.fromEntries(Object.entries(files).map(([k, v]) => [`apps/platform/.next/${k}`, v])), ...(compose ? { "compose.yml": compose } : {}) });
const scan = (root) => scanAll(root, ["platform"]);
test("G4 bundle scan: a clean production bundle passes; manifests / server-only files that hold the proxy target are NOT browser-visible and are ignored", () => {
  const root = BUNDLE({ "static/chunks/a.js": `fetch("/api/v1/auth/me");const o="https://studio.toolsmcp.uk";`, "static/css/a.css": "a{color:red}", "routes-manifest.json": `{"rewrites":[{"destination":"http://127.0.0.1:18081/api/:path*"}]}`, "required-server-files.json": `{"x":"http://127.0.0.1:18081"}`, "server/chunks/ssr.js": `const t="http://127.0.0.1:18081";` });
  assert.equal(scan(root).findings.length, 0);
});
test("G4 bundle scan: the documented URL-parser literal is allowed, nothing else about localhost is", () => {
  assert.equal(scan(BUNDLE({ "static/chunks/p.js": `if("localhost"===s.host&&(s.host=""),e)return;` })).findings.length, 0);
  assert.equal(scan(BUNDLE({ "static/chunks/p.js": `fetch("http://localhost:3000/api")` })).findings[0].kind, "localhost");
});
for (const [name, files, kind, compose] of [
  ["localhost", { "static/chunks/a.js": `const a="http://localhost:8080"` }, "localhost"],
  ["127.0.0.1", { "static/chunks/a.js": `const a="http://127.0.0.1:3301"` }, "loopback"],
  ["0.0.0.0", { "static/chunks/a.js": `const a="http://0.0.0.0:3000"` }, "loopback"],
  ["host.docker.internal", { "static/chunks/a.js": `const a="http://host.docker.internal:18081"` }, "docker-host"],
  ["a container name", { "static/chunks/a.js": `const a="hblpub-postgres-1"` }, "container-name"],
  ["a compose service as a host", { "static/chunks/a.js": `const a="redis://sites-gateway:8080/"` }, "compose-service", "services:\n  sites-gateway:\n    image: nginx\n  redis:\n    image: redis\n"],
  ["a private address with a port", { "static/chunks/a.js": `const a="http://192.168.1.20:8080"` }, "private-ip"],
  ["a prerendered page (server/app html)", { "static/chunks/ok.js": "1", "server/app/index.html": `<script>window.__api="http://127.0.0.1:18081"</script>` }, "loopback"],
  ["a CSS file", { "static/css/a.css": `a{background:url(http://localhost/x.png)}` }, "localhost"],
]) test(`G4 bundle scan FAILS: ${name}`, () => { const r = scan(BUNDLE(files, compose)); assert.ok(r.findings.some((f) => f.kind === kind), JSON.stringify(r.findings.map((f) => f.kind))); });
test("G4 bundle scan: no build to scan is a FAILURE, never a silent pass", () => assert.equal(scan(fx({ "apps/platform/package.json": "{}" })).findings[0].kind, "no-build"));

// ----------------------------------------------------------------------------------------------------------------------------------- 5. legacy workspace route
test("G5 legacy route: GET of the collection, the item, the tenant route and comments are CLEAN", () => {
  const root = fx({ "packages/api-client/src/api.ts": `// POST /admin/workspaces is legacy\nexport const api = { workspaces: (p) => call(\`/admin/workspaces\${qs({ page: p })}\`), workspace: (id) => call(\`/admin/workspaces/\${id}\`), createTenantWorkspace: (t, name) => call(\`/admin/tenants/\${t}/workspaces\`, { method: "POST", body: json({ name }) }) };` });
  assert.deepEqual(guardLegacyWorkspaceRoute(root), []);
});
for (const [name, code, rule] of [
  ["method POST on the same line", `export const c = (n) => call("/admin/workspaces", { method: "POST", body: json({ n }) });`, "LEGACY-WORKSPACE-ROUTE"],
  ["method POST on a later line", `export const c = (n) =>\n  call<{ id: string }>(\n    "/admin/workspaces",\n    {\n      method: "POST",\n      body: json({ n }),\n    },\n  );`, "LEGACY-WORKSPACE-ROUTE"],
  ["fetch with POST", `await fetch("/api/v1/admin/workspaces", { method: 'POST', body })`, "LEGACY-WORKSPACE-ROUTE"],
  ["a template literal route", "await http(`/admin/workspaces`, { method: \"PUT\" })", "LEGACY-WORKSPACE-ROUTE"],
  [".post( helper", `await client.post("/admin/workspaces", { name })`, "LEGACY-WORKSPACE-ROUTE"],
  ["call(\"POST\", path) helper", `await sa.call("POST", "/api/v1/admin/workspaces", { name })`, "LEGACY-WORKSPACE-ROUTE"],
  ["a split literal", `call("/admin/" + "workspaces", { method: "POST" })`, "LEGACY-WORKSPACE-ROUTE"],
  ["the legacy client method defined", `export const admin = { createWorkspace: (name) => call("/x") };`, "LEGACY-WORKSPACE-CLIENT"],
  ["the legacy client method called", `await api.admin.createWorkspace(name.trim());`, "LEGACY-WORKSPACE-CLIENT"],
]) test(`G5 legacy route FAILS: ${name}`, () => assert.ok(rules(guardLegacyWorkspaceRoute(fx({ "features/admin/X.tsx": code }))).includes(rule), code));
test("G5 legacy route: tests and docs may name it (they assert its absence); e2e/ legacy scripts are scanned", () => {
  assert.deepEqual(guardLegacyWorkspaceRoute(fx({ "tests/e2e-real/flows/x.mjs": `await sys.post("/admin/workspaces", {})` })), []);
  assert.ok(rules(guardLegacyWorkspaceRoute(fx({ "e2e/x.mjs": `await call("/admin/workspaces", { method: "POST" })` }))).includes("LEGACY-WORKSPACE-ROUTE"));
});

// ----------------------------------------------------------------------------------------------------------------------------------- 6. test labeling
test("G6 labeling: harness / mock / unit / integration with fake transports are CLEAN; a fake marker only in a COMMENT is clean for real-backend", () => {
  const root = fx({
    "tests/browser/org.spec.mjs": `// @class: harness\nconst fake = createFakeTransport(); console.log("89/89 checks passed");`,
    "tests/gateway/x.mjs": `// @class: integration\nconst stub = http.createServer(); // a stub upstream`,
    "tests/e2e-real/flows/e1.mjs": `// @class: real-backend\n// no fake transport, no page.route here\nawait page.goto(url);`,
    "scripts/global-smoke.mjs": `#!/usr/bin/env node\n// @class: real-backend\nawait fetch(base)`,
  });
  assert.deepEqual(guardTestLabeling(root), []);
});
for (const [name, files, rule] of [
  ["real-backend that intercepts the network", { "tests/e2e-real/flows/e1.mjs": `// @class: real-backend\nawait page.route("**/api/**", (r) => r.fulfill({ status: 200 }));` }, "TEST-LABEL-FAKE-AS-REAL"],
  ["real-backend on a fake in-memory transport", { "tests/e2e-real/flows/e1.mjs": `// @class: real-backend\nconst t = createFakeTransport();` }, "TEST-LABEL-FAKE-AS-REAL"],
  ["a real-backend SCRIPT with a mock api", { "scripts/x-smoke.mjs": `// @class: real-backend\nconst api = mockApi();` }, "TEST-LABEL-FAKE-AS-REAL"],
  ["a harness that reports 'REAL BACKEND'", { "tests/browser/h.spec.mjs": `// @class: harness\nconsole.log("REAL BACKEND: PASS 3/3");` }, "TEST-LABEL-CLAIMS-REAL"],
  ["a mock that names itself REAL_E2E", { "tests/builder/m.test.ts": `// @class: mock\nconst kind = "REAL_E2E";` }, "TEST-LABEL-CLAIMS-REAL"],
  ["a C0 script without a tag", { "scripts/new-smoke.mjs": `await fetch(base)` }, "TEST-LABEL-MISSING"],
  ["a gateway test without a tag", { "tests/gateway/x.mjs": `import x from "y";` }, "TEST-LABEL-MISSING"],
  ["an unknown class", { "scripts/x-e2e.mjs": `// @class: e2e\n` }, "TEST-LABEL-INVALID"],
  ["a harness living in tests/e2e-real", { "tests/e2e-real/flows/h.mjs": `// @class: harness\n` }, "TEST-LABEL-WRONG-HOME"],
]) test(`G6 labeling FAILS: ${name}`, () => assert.ok(rules(guardTestLabeling(fx(files))).includes(rule), `${rule} expected`));

// ----------------------------------------------------------------------------------------------------------------------------------- 7. migrations
const LEDGER = (extra = "") => `| Version | File |\n|---|---|\n| base | V1 … V25 |\n| **V26** | tenant |\n| **V30** | C2 rollback |\n| **V31** | candidate activation · **C2** |\n| **V32** | Dynamic Organization · **C1** reserved |\n${extra}`;
const LEDGER_ALLOCATED = () => LEDGER().replace("candidate activation · **C2** |", "candidate activation · **C2** · VOID gap (D-C0-52) |").replace("**C1** reserved", "contract **C1** · persistence **C3** · ALLOCATED, file created");
const MIG = (names, ledger = LEDGER(), extra = {}) => fx({ "docs/parallel/MIGRATION_LEDGER.md": ledger, ...Object.fromEntries(names.map((n) => [`backend/src/main/resources/db/migration/${n}`, "select 1;"])), ...extra });
test("G7 migrations: V1..V30 with the V31 / V32 reservations present and NO V31 / V32 file is CLEAN", () => assert.deepEqual(guardMigrationLedger(MIG(["V1__a.sql", "V26__tenant.sql", "V30__x.sql"])), []));
test("G7 migrations: the reserved numbers may be used ONLY for their purpose", () => {
  assert.deepEqual(guardMigrationLedger(MIG(["V30__x.sql", "V32__dynamic_organization.sql"], LEDGER_ALLOCATED())), [], "V32 created, V31 a declared gap");
  assert.deepEqual(guardMigrationLedger(MIG(["V30__x.sql", "V31__candidate_activation.sql"])), [], "V31 alone (before V32 existed) is still legitimate");
});
for (const [name, build, rule] of [
  ["a duplicate version", () => MIG(["V30__a.sql", "V30__b.sql"]), "MIGRATION-DUPLICATE"],
  ["a duplicate in another migration directory", () => MIG(["V30__a.sql"], LEDGER(), { "backend/src/test/resources/db/migration/V30__copy.sql": "select 1;" }), "MIGRATION-DUPLICATE"],
  ["a number above every ledger row", () => MIG(["V30__a.sql", "V33__sneaky.sql"]), "MIGRATION-UNALLOCATED"],
  ["V31 taken by something else", () => MIG(["V31__add_index.sql"]), "MIGRATION-RESERVED"],
  ["V32 taken by something else", () => MIG(["V32__workflow_tweak.sql"]), "MIGRATION-RESERVED"],
  ["the V32 row removed from the ledger", () => MIG(["V30__a.sql"], LEDGER().replace(/\| \*\*V32\*\*.*\n/, "")), "MIGRATION-RESERVED"],
  ["the V31 row no longer says C2 / candidate", () => MIG(["V30__a.sql"], LEDGER().replace("candidate activation · **C2**", "something")), "MIGRATION-RESERVED"],
  ["V32 exists and V31 is not declared a gap", () => MIG(["V30__a.sql", "V32__dynamic_organization.sql"], LEDGER().replace("**C1** reserved", "ALLOCATED")), "MIGRATION-GAP"],
  ["V32 exists but its ledger row does not say ALLOCATED", () => MIG(["V30__a.sql", "V32__dynamic_organization.sql"], LEDGER_ALLOCATED().replace("ALLOCATED", "reserved")), "MIGRATION-GAP"],
  ["V31 created next to V32 (out-of-order hazard)", () => MIG(["V30__a.sql", "V31__candidate_activation.sql", "V32__dynamic_organization.sql"], LEDGER_ALLOCATED()), "MIGRATION-ORDER-HAZARD"],
  ["flyway out-of-order on", () => MIG(["V30__a.sql"], LEDGER(), { "backend/src/main/resources/application.yml": "spring:\n  flyway:\n    out-of-order: true\n" }), "MIGRATION-OUT-OF-ORDER"],
]) test(`G7 migrations FAILS: ${name}`, () => assert.ok(rules(guardMigrationLedger(build())).includes(rule), `${rule} expected`));


// ----------------------------------------------------------------------------------------------------------------------------------- 8. process safety (D-C0-48)
// The fixtures spell the forbidden commands with placeholders and expand them at run time: another owner's tooling scanner (tests/lib/owned-process.test.mjs) reads every file under tests/ for these literals.
const X = (t) => String(t).replaceAll("@PK@", "pk" + "ill").replaceAll("@KA@", "kill" + "all").replaceAll("@XK@", "xargs " + "kill").replaceAll("@KS@", "kill " + "$(");
const PS = (file, code) => fx({ [file]: X(code) });
test("G8 process safety: the lifecycle idioms that ARE safe are CLEAN", () => {
  const root = fx(Object.fromEntries(Object.entries({
    "scripts/ok.sh": [
      'kill "$owned_pid" 2>/dev/null || true', 'kill -TERM "$pid"', 'kill -0 "$pid" 2>/dev/null && echo alive', 'kill "$(cat "$RUN/api.pid")"',
      '@PK@ -TERM -P "$owned_pid" 2>/dev/null || true', '@PK@ -P "$parent"', 'kill -- -"$pgid"',
      'node "$ROOT/scripts/owned-process.mjs" stop-port --port 8080 --cwd-under "$ROOT"', 'op_stop_port 3001 --state "$f"', 'op_stop "$STATE"',
      'docker stop hbl-v1-data-target', 'docker kill "$container"', 'docker compose -f compose.yml stop sites-gateway',
      'for _ in $(seq 1 60); do lsof -ti tcp:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done',          // waiting for a port is not killing
      '# @PK@ -f "next start" is forbidden here (a comment explaining it)', 'echo "never kill by name"  # @KA@ node in a comment',
    ].join("\n"),
    "tests/infra/ok.test.mjs": `process.kill(pid, "SIGTERM"); process.kill(-pgid, "SIGKILL"); child.kill("SIGKILL"); // @PK@ -f x in a comment\nspawnSync("@PK@", ["-TERM", "-P", String(parentPid)]);`,
    "e2e/ok.py": "os.kill(pid, 15)  # @KA@ node\nproc.terminate()\n",
    "scripts/pragma.sh": 'lsof -ti tcp:"$p" | @XK@  # guard-allow: PROCESS-SAFETY — the port is the one this very script just opened, checked two lines above',
    "scripts/owned.sh": 'owner="$(lsof -ti tcp:"$p")"\n[ "$(ps -o lstart= -p "$owner")" = "$recorded_lstart" ] && lsof -ti tcp:"$p" | @XK@',            // ownership identity checked in the same statement
    "docs/parallel/policy.md": "never run @PK@ -f \"next start\" or @KA@ node", "tests/guards/fixture.mjs": 'const bad = "@PK@ -f next";', "tmp/elsewhere.sh": '@PK@ -f "next start"',            // docs, the guard's own dir and non-project dirs are out of scope
  }).map(([k, v]) => [k, X(v)])));
  assert.deepEqual(guardProcessSafety(root), []);
});
for (const [name, file, code, rule] of [
  ['@PK@ -f "next start"', "scripts/a.sh", '@PK@ -f "next start"', "PROCESS-SAFETY-PKILL"],
  ['@PK@ -f "next build"', "scripts/a.sh", '@PK@ -f "next build"; sleep 1', "PROCESS-SAFETY-PKILL"],
  ['@PK@ -f "http.server 4000"', "tests/x.sh", '@PK@ -f "http.server 4000"', "PROCESS-SAFETY-PKILL"],
  ['@PK@ -f "tests/e2e-real/run.mjs"', "scripts/a.sh", '@PK@ -f "tests/e2e-real/run.mjs" || true', "PROCESS-SAFETY-PKILL"],
  ["@PK@ -f gradlew (the 11:38 incident)", "scripts/a.sh", '@PK@ -f "gradlew"', "PROCESS-SAFETY-PKILL"],
  ["@PK@ by exact name", "scripts/a.sh", "@PK@ -x node", "PROCESS-SAFETY-PKILL"],
  ["@PK@ -TERM without -P", "scripts/a.sh", '@PK@ -TERM node', "PROCESS-SAFETY-PKILL"],
  ["@PK@ in a JS array form", "tests/x.mjs", 'spawnSync("@PK@", ["-f", "next start"]);', "PROCESS-SAFETY-PKILL"],
  ["@PK@ in a JS exec string", "scripts/x.mjs", 'execSync(\'@PK@ -f "next start" || true\');', "PROCESS-SAFETY-PKILL"],
  ["@PK@ in Python", "e2e/x.py", 'subprocess.run(["@PK@", "-f", "next"])', "PROCESS-SAFETY-PKILL"],
  ["@KA@ node", "scripts/a.sh", "@KA@ node", "PROCESS-SAFETY-KILLALL"],
  ["@KA@ java", "scripts/a.sh", "@KA@ java 2>/dev/null", "PROCESS-SAFETY-KILLALL"],
  ["@KA@ in JS", "tests/x.mjs", 'execSync("@KA@ -9 node")', "PROCESS-SAFETY-KILLALL"],
  ["@KS@pgrep -f foo)", "scripts/a.sh", "@KS@pgrep -f foo)", "PROCESS-SAFETY-PGREP-KILL"],
  ["kill `pgrep foo`", "scripts/a.sh", "kill -9 `pgrep foo`", "PROCESS-SAFETY-PGREP-KILL"],
  ["pgrep | @XK@", "scripts/a.sh", 'pgrep -f "next" | @XK@', "PROCESS-SAFETY-PGREP-KILL"],
  ["ps | grep | @XK@", "scripts/a.sh", "ps aux | grep next | awk '{print $2}' | @XK@", "PROCESS-SAFETY-PGREP-KILL"],
  ["a for loop over pgrep", "scripts/a.sh", 'for p in $(pgrep -f next); do kill "$p"; done', "PROCESS-SAFETY-PGREP-KILL"],
  ["@KS@lsof -ti tcp:$p -sTCP:LISTEN)", "scripts/a.sh", '@KS@lsof -ti tcp:$p -sTCP:LISTEN)', "PROCESS-SAFETY-KILL-BY-PORT"],
  ["lsof -ti ... | @XK@", "scripts/a.sh", 'lsof -ti tcp:3001 -sTCP:LISTEN | @XK@ 2>/dev/null || true', "PROCESS-SAFETY-KILL-BY-PORT"],
  ["kill-by-port in JS", "tests/x.mjs", 'execSync(`@KS@lsof -ti tcp:${port})`)', "PROCESS-SAFETY-KILL-BY-PORT"],
  ["fuser -k", "scripts/a.sh", "fuser -k 3001/tcp", "PROCESS-SAFETY-KILL-BY-PORT"],
  ["kill 0", "scripts/a.sh", "kill -9 0", "PROCESS-SAFETY-GROUP-ZERO"],
  ["process.kill(0)", "tests/x.mjs", 'process.kill(0, "SIGKILL")', "PROCESS-SAFETY-GROUP-ZERO"],
  ["kill -1 (everything)", "scripts/a.sh", "kill -9 -1", "PROCESS-SAFETY-GROUP-ZERO"],
  ["a continued command line", "scripts/a.sh", '@PK@ \\\n  -f "next start"', "PROCESS-SAFETY-PKILL"],
  ["in workers/", "workers/x/run.sh", "@KA@ node", "PROCESS-SAFETY-KILLALL"],
  ["in infra/", "infra/x/run.sh", '@PK@ -f java', "PROCESS-SAFETY-PKILL"],
]) test(`G8 process safety FAILS: ${X(name)}`, () => assert.ok(rules(guardProcessSafety(PS(file, code))).includes(rule), `${rule} expected for: ${X(code)}`));
test("G8 process safety: the ORIGINAL scripts/stop-local.sh (@PK@ -f + kill-by-port, the 2026-10 hazard) is caught line by line", () => {
  const orig = ['@PK@ -f "system-web-studio.*bootRun" 2>/dev/null || true', "lsof -ti tcp:8080 -sTCP:LISTEN | @XK@ 2>/dev/null || true", 'lsof -ti tcp:"$FRONTEND_PORT" -sTCP:LISTEN | @XK@ 2>/dev/null || true', 'lsof -ti tcp:"${RENDER_PORT:-18095}" -sTCP:LISTEN | @XK@ 2>/dev/null || true'].join("\n");
  assert.deepEqual(rules(guardProcessSafety(PS("scripts/stop-local.sh", orig))), ["PROCESS-SAFETY-KILL-BY-PORT", "PROCESS-SAFETY-PKILL"]); assert.equal(guardProcessSafety(PS("scripts/stop-local.sh", orig)).length, 4);
});
test("G8 process safety: STRINGS are scanned (a JS command lives in a string), so a message that merely NAMES a forbidden command is flagged too: rephrase it or add a pragma with a reason", () => {
  assert.ok(rules(guardProcessSafety(PS("scripts/a.sh", 'echo "do not run @KA@ node"'))).includes("PROCESS-SAFETY-KILLALL"));
  assert.deepEqual(guardProcessSafety(PS("scripts/a.sh", 'echo "do not kill by name"')), []);
});
test("G8 process safety: a pragma without a reason does NOT silence it", () => assert.ok(rules(guardProcessSafety(PS("scripts/a.sh", '@PK@ -f x  # guard-allow: PROCESS-SAFETY'))).includes("PROCESS-SAFETY-PKILL")));

// ----------------------------------------------------------------------------------------------------------------------------------- nested checkouts
test("a NESTED CHECKOUT (.worktrees/*, found when the guards first ran in the main checkout) and build output are never scanned: no false duplicate migration, no foreign violation", () => {
  const nested = { ".worktrees/c3-overlay/backend/src/main/resources/db/migration/V30__a.sql": "select 1;", ".worktrees/c3-overlay/features/admin/organizationTree.ts": `export const MAX_DEPTH = 3; localStorage.x;`, "backend/build/resources/main/db/migration/V30__a.sql": "select 1;" };
  assert.deepEqual(guardMigrationLedger(MIG(["V30__a.sql"], LEDGER(), nested)), []);
  assert.deepEqual(guardHierarchy(fx(nested)), []); assert.deepEqual(guardFailClosed(FC(`export const a = 1;`, nested)), []);
});

// ----------------------------------------------------------------------------------------------------------------------------------- real files, mutated copies
function realCopy() {
  const d = mkdtempSync(join(tmpdir(), "guard-real-")); dirs.push(d);
  for (const p of ["features/admin", "packages/permissions", "packages/api-client", "tests/guards/org-contract.json", "tests/guards/org-guards.allow.json", "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt", "docs/parallel/MIGRATION_LEDGER.md"]) { mkdirSync(dirname(join(d, p)), { recursive: true }); cpSync(join(REPO, p), join(d, p), { recursive: true }); }
  return d;
}
test("REAL FILES (copy): the unmodified organization code passes all three source guards", () => {
  const d = realCopy(); assert.deepEqual(guardHierarchy(d), []); assert.deepEqual(guardFailClosed(d), []); assert.deepEqual(guardRelationNotPermission(d), []);
});
test("REAL FILES (copy) MUTATED: a localStorage 'save', a depth switch and a manager→admin line in the real screens are each caught", () => {
  const d = realCopy(); const f = join(d, "features/admin/OrganizationScreens.tsx"); const src = readFileSync(f, "utf8");
  writeFileSync(f, src + `\nexport const _m1 = (u: unknown) => localStorage.setItem("org", JSON.stringify(u));\nexport const _m2 = (n: { depth: number }) => (n.depth === 3 ? "Team" : "Unit");\nexport const _m3 = (e: { isManager: boolean }) => e.isManager && hasPermission(me, "TENANT_MANAGE");\n`);
  assert.ok(rules(guardFailClosed(d)).includes("ORG-FAIL-CLOSED-PERSIST")); assert.ok(rules(guardHierarchy(d)).includes("ORG-HIERARCHY-LEVEL-INDEX")); assert.ok(rules(guardRelationNotPermission(d)).includes("ORG-RELATION-AUTH"));
});
test("REAL FILES (copy) MUTATED: flipping the org adapter's permission need to a made-up ORG_ADMIN, or reintroducing the obsolete ORG_MANAGE, is caught", () => {
  const d = realCopy(); const f = join(d, "features/admin/organization.ts"); writeFileSync(f, readFileSync(f, "utf8").replace('"ORG_STRUCTURE_VIEW"', '"ORG_ADMIN"'));
  assert.ok(guardFailClosed(d).some((x) => x.rule === "ORG-FAIL-CLOSED-PERMISSION" && /ORG_ADMIN/.test(x.message)));
  assert.ok(guardFailClosed(d).some((x) => x.rule === "ORG-FAIL-CLOSED-OBSOLETE" && /ORG_ADMIN/.test(x.message)), "an alias is an obsolete name too");
  const e = realCopy(); const g = join(e, "features/admin/organization.ts"); writeFileSync(g, readFileSync(g, "utf8").replace('"ORG_STRUCTURE_VIEW"', '"ORG_MANAGE"'));
  assert.ok(guardFailClosed(e).some((x) => x.rule === "ORG-FAIL-CLOSED-OBSOLETE" && /ORG_MANAGE/.test(x.message)), "ORG_MANAGE is obsolete: there is no allow-list for it any more");
});
// ---- the legitimate dynamic-organization BACKEND (C1, D-C0-51) must pass; the invariants must still bite ----
const BE = "backend/src/main/kotlin/com/systemwebstudio/organization/OrganizationServices.kt";
const BE_OK = `package com.systemwebstudio.organization
@RestController @RequestMapping("/api/v1/admin/tenants/{tenantId}/organization-units")
class C { @PostMapping("/{unitId}/move") fun move() { val id = UUID.randomUUID()
  r.maxDepth?.let { if (depth > it) throw ruleViolation("MAX_DEPTH", "too deep") }
  for (n in subtree) types[n.typeId]?.rules?.maxDepth?.let { if (base + n.relativeDepth > it) throw ruleViolation("MAX_DEPTH", "deeper than $it") }
  try { repos.units.move() } catch (e: OrganizationCycle) { throw ApiException.conflict("ORG_CYCLE", "no cycle") }
  throw ApiException.notFound("ORG_UNIT_NOT_FOUND", "x"); audit("ORG_UNIT_MOVED", "ORG_UNIT_TYPE_CREATED") } }`;
test("G2/G1 backend organization code: routes, server-made ids, error / audit codes that start with ORG_, a tenant-defined maxDepth rule and its violation reason are CLEAN", () => {
  const root = fx({ "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt": PERM_KT, "tests/guards/org-contract.json": CONTRACT(), [BE]: BE_OK });
  assert.deepEqual(guardFailClosed(root), []); assert.deepEqual(guardHierarchy(root), []);
});
for (const [name, code, rule, which] of [
  ["a fixed maximum depth constant", "val MAX_DEPTH = 12", "ORG-HIERARCHY-MAX-DEPTH", "h"],
  ["a fixed maximum level count", "private const val MAX_TREE_LEVELS: Int = 8", "ORG-HIERARCHY-MAX-DEPTH", "h"],
  ["a literal maxDepth assignment", "val cfg = Cfg(maxDepth = 5)", "ORG-HIERARCHY-MAX-DEPTH", "h"],
  ["a depth switch in the backend", "when (depth) { 1 -> a() else -> b() }", "ORG-HIERARCHY-SWITCH", "h"],
  ["the obsolete ORG_MANAGE in backend code", `val need = "ORG_MANAGE"`, "ORG-FAIL-CLOSED-OBSOLETE", "f"],
  ["an alias EMPLOYEE_ADMIN in backend code", `Permission.of("EMPLOYEE_ADMIN")`, "ORG-FAIL-CLOSED-OBSOLETE", "f"],
  ["an alias POSITION_MANAGE in backend code", `val p = "POSITION_MANAGE"`, "ORG-FAIL-CLOSED-OBSOLETE", "f"],
]) test(`backend organization code FAILS: ${name}`, () => {
  const root = fx({ "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt": PERM_KT, "tests/guards/org-contract.json": CONTRACT(), [BE]: `${BE_OK}\n${code}\n` });
  assert.ok(rules(which === "h" ? guardHierarchy(root) : guardFailClosed(root)).includes(rule), `${rule} expected for: ${code}`);
});
test("the six canonical organization permissions are never flagged, in the frontend adapter type or in backend code", () => {
  const six = ["ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];
  const kt = `object PermissionCodes {\n    val CANONICAL: Set<String> = setOf(\n        "APP_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS", "MEMBER_MANAGE",\n        ${six.map((x) => `"${x}"`).join(", ")}\n    )\n}\n`;
  const root = fx({ "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt": kt, "tests/guards/org-contract.json": CONTRACT(), [ORG]: `export type Need = ${six.map((x) => `"${x}"`).join(" | ")};`, [BE]: `val need = ${six.map((x) => `"${x}"`).join(", ")}` });
  assert.deepEqual(guardFailClosed(root), []);
});


// ----------------------------------------------------------------------------------------------------------------------------------- CLIs on the real repository
for (const [name, args] of [["org hierarchy", ["tests/guards/org-source-guards.mjs", "hierarchy"]], ["org fail-closed", ["tests/guards/org-source-guards.mjs", "fail-closed"]], ["org relation", ["tests/guards/org-source-guards.mjs", "relation"]], ["legacy route", ["tests/guards/no-legacy-admin-workspaces.mjs"]], ["test labeling", ["tests/guards/test-labeling.mjs"]], ["migration ledger", ["tests/guards/migration-ledger.mjs"]], ["process safety", ["tests/guards/process-safety.mjs"]]])
  test(`CLI on the real repository is green: ${name}`, () => { const r = spawnSync(process.execPath, args, { cwd: REPO, encoding: "utf8" }); assert.equal(r.status, 0, r.stdout + r.stderr); });
test("CLI exit code is 1 on a violation (not just a printed message)", () => {
  const r = spawnSync(process.execPath, ["tests/guards/org-source-guards.mjs", "hierarchy", "--root", fx({ [ORG]: `export const MAX_DEPTH = 3;` })], { cwd: REPO, encoding: "utf8" });
  assert.equal(r.status, 1); assert.match(r.stdout, /ORG-HIERARCHY-MAX-DEPTH/);
});
test("G8 process safety: a documented exception (owner + reason) silences exactly its finding; an entry that matches nothing is STALE and fails; one without a reason does not count", () => {
  const code = 'test("title naming @PK@ and @KA@", () => {});';
  const allow = (extra = {}) => JSON.stringify({ allow: [{ file: "tests/lib/t.test.mjs", rule: "PROCESS-SAFETY-PKILL", contains: "title naming", owner: "C5", reason: "a title", ...extra }, { file: "tests/lib/t.test.mjs", rule: "PROCESS-SAFETY-KILLALL", contains: "title naming", owner: "C5", reason: "a title", ...extra }] });
  const mk = (a) => fx({ "tests/lib/t.test.mjs": X(code), "tests/guards/process-safety.allow.json": a });
  assert.deepEqual(guardProcessSafety(mk(allow())), []);
  assert.ok(rules(guardProcessSafety(mk(allow({ reason: "" })))).includes("PROCESS-SAFETY-PKILL"), "no reason = no exception");
  assert.ok(rules(guardProcessSafety(mk(allow({ contains: "another title" })))).includes("PROCESS-SAFETY-ALLOW-STALE"), "stale entry");
  assert.ok(rules(guardProcessSafety(fx({ "tests/lib/t.test.mjs": "const x = 1;", "tests/guards/process-safety.allow.json": allow() }))).includes("PROCESS-SAFETY-ALLOW-STALE"), "an exception for code that is gone is stale");
});

// ----------------------------------------------------------------------------------------------------------------------------------- 9. permission mirror (D-C0-51)
const PM = (over = {}) => {
  const real = (f) => readFileSync(join(REPO, f), "utf8"); const F = { ts: "packages/types/src/contract/v2/permissions.ts", doc: "docs/contracts/v2/tenant-permission.md", app: "backend/src/main/kotlin/com/systemwebstudio/app/definition/PermissionCodes.kt", perm: "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt" };
  return fx(Object.fromEntries(Object.entries(F).map(([k, f]) => [f, over[k] ? over[k](real(f)) : real(f)])));
};
test("G9 permission mirror: the real TS mirror, backend sets and contract document agree (the six organization codes included)", () => assert.deepEqual(guardPermissionMirror(PM()), []));
for (const [name, over, rule] of [
  ["a TS organization code missing", { ts: (t) => t.replace('"POSITION_GRADE_MANAGE",', "") }, "PERMISSION-MIRROR-ORG"],
  ["a TS application code missing", { ts: (t) => t.replace('"WORKFLOW_MANAGE",', "") }, "PERMISSION-MIRROR-APP"],
  ["the obsolete ORG_MANAGE added to the TS organization codes", { ts: (t) => t.replace('"EMPLOYEE_VIEW",', '"EMPLOYEE_VIEW", "ORG_MANAGE",') }, "PERMISSION-MIRROR-ORG"],
  ["an organization code added to the AppDefinition vocabulary (backend)", { app: (t) => t.replace('"TENANT_MANAGE", "TENANT_MEMBERS"', '"TENANT_MANAGE", "TENANT_MEMBERS", "EMPLOYEE_VIEW"') }, "PERMISSION-MIRROR-APP"],
  ["a backend CANONICAL code that the TS mirror lacks", { perm: (t) => t.replace('"MEMBER_MANAGE",', '"MEMBER_MANAGE", "ORG_AUDIT_VIEW",') }, "PERMISSION-MIRROR-CANONICAL"],
  ["an alias in the backend CANONICAL set", { perm: (t) => t.replace('"EMPLOYEE_VIEW", "EMPLOYEE_MANAGE",', '"EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "EMPLOYEE_ADMIN",') }, "PERMISSION-MIRROR-OBSOLETE"],
  ["a document section 5b row removed", { doc: (t) => t.replace(/\| `EMPLOYEE_MANAGE` \|[^\n]*\n/, "") }, "PERMISSION-MIRROR-ORG"],
  ["a document section 5 row removed", { doc: (t) => t.replace(/\| `QUERY_EXECUTE` \|[^\n]*\n/, "") }, "PERMISSION-MIRROR-APP"],
]) test(`G9 permission mirror FAILS: ${name}`, () => assert.ok(rules(guardPermissionMirror(PM(over))).includes(rule), `${rule} expected`));
