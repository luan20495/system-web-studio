// @class: unit
// Tests of the route inventory of the final-gate audit tools (scripts/audit/inventory.mjs): it must be DERIVED FROM THE SOURCE, must understand both the pre-registry tables and S2's section registry, and the
// "every route visited" check must fail when a route is missing. Plain node:test, no browser; run by `npm run test:unit` through tests/lib/audit-tools.wire.test.ts.
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { inventory, missingRoutes, parseSectionRegistry, parseStudio, parseTabs, visitPaths } from "../../scripts/audit/inventory.mjs";
import { flag, summarize } from "../../scripts/audit/engine.mjs";

const REGISTRY = `
export const SECTIONS: readonly Section[] = [
  { key: "", label: "Tổng quan", icon: ic(LayoutDashboard), portals: both, access: "open", surface: "standard", listed: "main", render: () => <Overview/> },
  { key: "tenants", label: "Công ty (tenant)", icon: ic(Building2), portals: platform, access: "system", surface: "platform-only", listed: "platform-main", render: (seg) => (seg[1] ? <TenantDetailPage id={seg[1]}/> : <TenantsPage/>) },
  { key: "ai-governance", label: "Quyền & ngân sách AI", icon: ic(Scale), portals: admin, access: "system", surface: "standard", listed: "main", render: () => <AiGovernancePage/> },
  { key: "company", label: "Công ty của tôi", icon: ic(Building2), portals: none, access: "company", surface: "scoped", listed: "scoped", navWhen: (s) => !s.platform && s.tenants.length > 0, denied: "công ty", render: () => <CompanyPage/> },
  { key: "groups", label: "Nhóm", icon: ic(Users), portals: admin, access: "open", surface: "coming", listed: "coming",
    coming: { why: "Nhóm { với } dấu ngoặc \\"trong\\" chuỗi", needs: "x" } },
];`;

test("registry parser: key, owning consoles, access, surface, detail pages; braces inside strings do not confuse it", () => {
  const s = parseSectionRegistry(REGISTRY);
  assert.deepEqual(s.map((x) => x.key), ["", "tenants", "ai-governance", "company", "groups"]);
  assert.deepEqual(s[0].portals, ["platform", "admin"]); assert.deepEqual(s[1].portals, ["platform"]); assert.deepEqual(s[2].portals, ["admin"]); assert.deepEqual(s[3].portals, []);
  assert.equal(s[1].detail, true); assert.equal(s[0].detail, false); assert.equal(s[1].surface, "platform-only"); assert.equal(s[3].surface, "scoped"); assert.equal(s[4].surface, "coming"); assert.equal(s[3].access, "company");
});

test("studio parser: the route switch and the project views", () => {
  const app = `function route(seg: string[]): ReactNode {\n  switch (seg[0] ?? "") {\n    case "": return <Home/>;\n    case "projects": return <Projects/>;\n    case "new": return <NewApp/>;\n    default: return null;\n  }\n}\n if (seg[0] === "projects" && seg[1]) return <ProjectWorkspace/>;`;
  const ws = `type Mode = "ai" | "design";\nconst MODES: Mode[] = ["ai", "design", "code"];\nconst PANELS: PanelName[] = ["members", "versions"];`;
  const st = parseStudio(app, ws); assert.deepEqual(st.sections, ["", "projects", "new"]); assert.equal(st.projectDetail, true); assert.deepEqual(st.modes, ["ai", "design", "code"]); assert.deepEqual(st.panels, ["members", "versions"]);
  assert.deepEqual(parseTabs(`const TABS: [string, string][] = [["providers", "Nhà cung cấp"], ["usage", "Sử dụng"]];`), ["providers", "usage"]);
});

test("the inventory of THIS repository is non-trivial and complete: every admin section and every Studio view the source routes", () => {
  const inv = inventory(); const ids = new Set(inv.routes.map((r) => r.id));
  for (const id of ["platform/", "platform/users", "platform/users/:id", "platform/tenants", "platform/tenants/:id", "platform/ai/:id", "admin/employees", "admin/organization", "admin/data-sources", "admin/groups", "studio/", "studio/projects", "studio/new", "studio/projects/:id", "studio/projects/:id/design", "studio/projects/:id/publish"]) assert.ok(ids.has(id), `missing ${id}`);
  assert.ok(inv.routes.length > 60); assert.ok(["registry", "legacy"].includes(inv.source));
  assert.deepEqual(inv.tabs.ai.sort(), ["limits", "models", "providers", "usage"]);
});

test("visit lists come from the inventory; a detail page without a known id is NOT silently dropped from the check", () => {
  const inv = inventory();
  const withIds = visitPaths(inv, "platform", { ids: { tenants: "t1", users: "u2", workspaces: "w1" } }).map((x) => x.path);
  assert.ok(withIds.includes("/platform/tenants/t1") && withIds.includes("/platform/users/u2") && withIds.includes("/platform/ai/providers") && withIds.includes("/platform"));
  const noIds = visitPaths(inv, "platform", {}).map((x) => x.route.id);
  assert.ok(!noIds.includes("platform/tenants/:id"));                       // no id -> no path ...
  const visited = new Set(noIds);                                            // ... so the completeness check reports it
  assert.ok(missingRoutes(inv, visited, { consoles: ["platform"] }).includes("platform/tenants/:id"));
  const studio = visitPaths(inv, "studio", { ids: { project: "p1" } }).map((x) => x.path); assert.ok(studio.includes("/studio/projects/p1/design") && studio.includes("/studio/new") && studio.includes("/studio"));
});

test("completeness: visiting everything leaves nothing missing; visiting nothing misses everything of the asked consoles only", () => {
  const inv = inventory();
  const all = new Set(inv.routes.map((r) => r.id)); assert.deepEqual(missingRoutes(inv, all), []);
  const none = missingRoutes(inv, new Set(), { consoles: ["studio"] }); assert.ok(none.length >= 10 && none.every((id) => id.startsWith("studio/")));
});

test("the inventory follows the SOURCE: a section added to a registry file shows up, with no change to the tool", () => {
  const root = mkdtempSync(join(tmpdir(), "inv-")); try {
    mkdirSync(join(root, "features/admin/console"), { recursive: true }); mkdirSync(join(root, "features/studio"), { recursive: true });
    const real = (p) => readFileSync(join(process.cwd(), p), "utf8");
    writeFileSync(join(root, "features/admin/console/sections.tsx"), REGISTRY.replace('{ key: "company",', '{ key: "brand-new-page", label: "Mới", icon: ic(X), portals: both, access: "system", surface: "standard", listed: "main", render: (seg) => (seg[1] ? <A/> : <B/>) },\n  { key: "company",'));
    writeFileSync(join(root, "features/studio/StudioApp.tsx"), real("features/studio/StudioApp.tsx")); writeFileSync(join(root, "features/studio/ProjectWorkspace.tsx"), real("features/studio/ProjectWorkspace.tsx"));
    const inv = inventory(root); assert.equal(inv.source, "registry"); const ids = inv.routes.map((r) => r.id);
    assert.ok(ids.includes("platform/brand-new-page") && ids.includes("admin/brand-new-page/:id")); assert.ok(!ids.includes("platform/users"));            // only what the (small) registry says
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test("flag / summarize: every new finding class is reported, a clean row is empty", () => {
  assert.equal(flag({ textLen: 100, h1: 1, axe: [], errs: [], bad: [], focus: { noIndicator: [], obscuredFully: [], offscreen: [] } }), "");
  const f = flag({ textLen: 100, h1: 1, overflowX: 20, clippedCtl: ["button[x] clipped"], covered: ["a[y] covered by div[]"], small: ["BUTTON 10x10"], nameMismatch: ["b"], focus: { noIndicator: ["input[z]"], obscuredFully: ["a[q] by header"], offscreen: [] }, axe: [{ id: "label", impact: "critical", n: 1 }], errs: ["e"], bad: ["GET /x 500"] });
  for (const word of ["overflowX+20", "unreachable(", "covered(", "<24px(1)", "nameMismatch(1)", "noFocusRing(input[z])", "focusObscured(", "axe[label:criticalx1]", "console(1)", "api(GET /x 500)"]) assert.ok(f.includes(word), word + " in " + f);
  const sm = summarize([{ portal: "p", route: "/a", overflowX: 5, textLen: 99, axe: [{ id: "a", impact: "serious", n: 1 }], errs: [], bad: [] }, { portal: "p", route: "/b", textLen: 99, axe: [{ id: "b", impact: "minor", n: 1 }], errs: [], bad: [] }]);
  assert.equal(sm.visits, 2); assert.equal(sm.overflowX, 1); assert.equal(sm.axeCriticalSerious, 1); assert.equal(sm.axeModerateMinor, 1);
});
