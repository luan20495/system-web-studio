// @class: tooling
// ROUTE INVENTORY derived from the SOURCE, so "every route was visited" is provable and not assumed. STATIC: reads source text, executes nothing.
//   node scripts/audit/inventory.mjs [--json]
// Admin (Platform + Admin consoles): the section registry `features/admin/console/sections.tsx` (`export const SECTIONS`) when it exists (S2 wave 1); otherwise the pre-registry tables in
// `features/admin/AdminApp.tsx` (NAV / SCOPED_NAV / COMING / the `case "x":` switch) and `features/admin/base.ts` (OWNED).
// Studio: the `route()` switch of `features/studio/StudioApp.tsx` and the project views (`MODES`, `PANELS`) of `features/studio/ProjectWorkspace.tsx`.
// A route is identified by `console/key` (`platform/users`, `admin/employees`, `studio/projects`) and `console/key/:id` for detail pages (a `seg[1]` branch). The auditing tools build their visit lists FROM this
// inventory and `missingRoutes()` fails the run when anything in the inventory was not visited.
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(new URL("../..", import.meta.url).pathname);
const read = (root, p) => readFileSync(join(root, p), "utf8");

/** parse the entries of `export const SECTIONS: readonly Section[] = [ { key: "...", ... }, ... ]` (one object per entry, key / portals / access / surface / listed, `seg[1]` in render = a detail page) */
export function parseSectionRegistry(src) {
  const start = src.indexOf("export const SECTIONS"); if (start < 0) return [];
  const body = src.slice(src.indexOf("[", src.indexOf("=", start)));
  const out = []; let depth = 0, i = 0, from = -1;
  for (; i < body.length; i++) {
    const c = body[i];
    if (c === "{") { if (depth === 0) from = i; depth++; } else if (c === "}") { depth--; if (depth === 0 && from >= 0) { out.push(body.slice(from, i + 1)); from = -1; } }
    else if (c === "]" && depth === 0 && out.length) break;
    else if (c === '"' || c === "'" || c === "`") { const q = c; for (i++; i < body.length && body[i] !== q; i++) if (body[i] === "\\") i++; }   // skip strings
  }
  const portalsOf = (v) => (v === "both" ? ["platform", "admin"] : v === "platform" ? ["platform"] : v === "admin" ? ["admin"] : []);
  return out.map((e) => {
    const g = (k) => new RegExp(`\\b${k}:\\s*"([^"]*)"`).exec(e)?.[1];
    const key = /\bkey:\s*"([^"]*)"/.exec(e)?.[1]; if (key === undefined) return null;
    const p = /\bportals:\s*(both|platform|admin|none)\b/.exec(e)?.[1] ?? "none";
    return { key, portals: portalsOf(p), access: g("access") ?? "open", surface: g("surface") ?? "standard", listed: g("listed") ?? "main", detail: /seg\[1\]/.test(e) };
  }).filter(Boolean);
}

/** the pre-registry tables (AdminApp.tsx + base.ts): keys from NAV / SCOPED_NAV / COMING / `case "x":`, ownership from OWNED */
export function parseLegacyAdmin(appSrc, baseSrc) {
  const keys = new Map();
  const add = (key, patch = {}) => keys.set(key, { key, portals: [], access: "open", surface: "standard", listed: "main", detail: false, ...(keys.get(key) ?? {}), ...patch });
  const nav = /const NAV: \[string, string, ReactNode\]\[\] = \[([\s\S]*?)\n\];/.exec(appSrc)?.[1] ?? ""; for (const m of nav.matchAll(/\["([a-z-]*)",/g)) add(m[1]);
  const scoped = /const SCOPED_NAV[^=]*= \{([\s\S]*?)\n\};/.exec(appSrc)?.[1] ?? ""; for (const m of scoped.matchAll(/\["([a-z-]+)", "/g)) add(m[1], { surface: "scoped", listed: "scoped" });
  const coming = /const COMING[^=]*= \{([\s\S]*?)\n\};/.exec(appSrc)?.[1] ?? ""; for (const m of coming.matchAll(/^\s{2}([a-z-]+): \{ title:/gm)) add(m[1], { surface: "coming", listed: "coming" });
  for (const m of appSrc.matchAll(/case "([a-z-]*)": return ([^\n]*)/g)) add(m[1], { detail: /seg\[1\]/.test(m[2]) });
  for (const m of appSrc.matchAll(/key === "([a-z-]+)"\s*&&\s*adminPortal\(\) === "platform"\) return seg\[1\]/g)) add(m[1], { detail: true, surface: "platform-only" });
  const owned = (name) => { const m = new RegExp(`${name}: new Set\\(\\[([^\\]]*)\\]\\)`).exec(baseSrc)?.[1] ?? ""; return [...m.matchAll(/"([a-z-]*)"/g)].map((x) => x[1]); };
  const plat = owned("platform"), adm = owned("admin");
  for (const [k, v] of keys) v.portals = [...(plat.includes(k) ? ["platform"] : []), ...(adm.includes(k) ? ["admin"] : [])];
  for (const k of ["tenants"]) if (keys.has(k)) keys.get(k).portals = ["platform"];
  return [...keys.values()];
}

/** sub-views of a section that are routed by `seg[1]` as a tab name (the AI console: providers / models / limits / usage) */
export function parseTabs(aiSrc) { const m = /const TABS: \[string, string\]\[\] = \[(.*?)\];/s.exec(aiSrc)?.[1] ?? ""; return [...m.matchAll(/\["([a-z-]+)", "/g)].map((x) => x[1]); }

export function parseStudio(appSrc, wsSrc) {
  const sw = /function route\(seg: string\[\]\): ReactNode \{[\s\S]*?\n\}/.exec(appSrc)?.[0] ?? "";
  const sections = [...sw.matchAll(/case "([a-z-]*)":/g)].map((m) => m[1]);
  const list = (name) => { const m = new RegExp(`const ${name}[^=]*= \\[([^\\]]*)\\]`).exec(wsSrc)?.[1] ?? ""; return [...m.matchAll(/"([a-z-]+)"/g)].map((x) => x[1]); };
  return { sections, projectDetail: /seg\[0\] === "projects" && seg\[1\]/.test(appSrc), modes: list("MODES"), panels: list("PANELS") };
}

/** everything the source declares. `source` says which admin tables were read ("registry" | "legacy"). */
export function inventory(root = ROOT) {
  const reg = join(root, "features/admin/console/sections.tsx");
  const sections = existsSync(reg) ? parseSectionRegistry(read(root, "features/admin/console/sections.tsx")) : parseLegacyAdmin(read(root, "features/admin/AdminApp.tsx"), read(root, "features/admin/base.ts"));
  const studio = parseStudio(read(root, "features/studio/StudioApp.tsx"), read(root, "features/studio/ProjectWorkspace.tsx"));
  const routes = [];
  for (const s of sections) {
    const consoles = ["platform", "admin"];     // every console must answer every key: the page when it owns it, the "other console" note otherwise
    for (const c of consoles) { routes.push({ id: `${c}/${s.key}`, console: c, key: s.key, owned: s.portals.includes(c), surface: s.surface, access: s.access }); if (s.detail) routes.push({ id: `${c}/${s.key}/:id`, console: c, key: s.key, detail: true, owned: s.portals.includes(c), surface: s.surface, access: s.access }); }
  }
  for (const k of studio.sections) routes.push({ id: `studio/${k}`, console: "studio", key: k, owned: true, surface: "standard" });
  if (studio.projectDetail) { routes.push({ id: "studio/projects/:id", console: "studio", key: "projects", detail: true, owned: true, surface: "standard" });
    for (const v of [...studio.modes, ...studio.panels]) routes.push({ id: `studio/projects/:id/${v}`, console: "studio", key: "projects", detail: true, view: v, owned: true, surface: "standard" }); }
  const tabs = { ai: existsSync(join(root, "features/admin/AiSetup.tsx")) ? parseTabs(read(root, "features/admin/AiSetup.tsx")) : [] };
  return { source: existsSync(reg) ? "registry" : "legacy", sections, studio, tabs, routes };
}

/** a visit list for one console / persona with the ids of the data it was given: ids = { users: "...", tenants: "...", ... } for detail pages (a value may be an array: several ids / tabs for one route; `ai` defaults to the tabs of AiSetup) and { project: "..." } for studio */
export function visitPaths(inv, consoleName, { ids = {}, only = null } = {}) {
  const out = [];
  for (const r of inv.routes.filter((x) => x.console === consoleName && (!only || only(x)))) {
    if (r.id.startsWith("studio/projects/:id")) { const p = ids.project; if (!p) continue; out.push({ route: r, path: r.view ? `/studio/projects/${p}/${r.view}` : `/studio/projects/${p}` }); continue; }
    if (r.detail) { const v = ids[r.key] ?? (r.key === "ai" ? inv.tabs?.ai : undefined); if (!v || (Array.isArray(v) && !v.length)) continue; for (const id of Array.isArray(v) ? v : [v]) out.push({ route: r, path: `/${consoleName}/${r.key}/${id}` }); continue; }
    out.push({ route: r, path: `/${consoleName}${r.key ? "/" + r.key : ""}${consoleName === "studio" && !r.key ? "" : ""}` });
  }
  return out;
}

/** every inventory route that no visit covered (a visit covers a route id; `visited` = Set of route ids). Used by the runners to FAIL. */
export function missingRoutes(inv, visited, { consoles = null } = {}) {
  return inv.routes.filter((r) => (!consoles || consoles.includes(r.console)) && !visited.has(r.id)).map((r) => r.id);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const inv = inventory(); if (process.argv.includes("--json")) { console.log(JSON.stringify(inv, null, 1)); process.exit(0); }
  console.log(`admin tables read: ${inv.source}; sections ${inv.sections.length}; studio sections ${inv.studio.sections.length} (+ project views: ${[...inv.studio.modes, ...inv.studio.panels].join(", ")}); routes ${inv.routes.length}`);
  console.log("| route | owned | surface | access |\n|---|---|---|---|"); for (const r of inv.routes) console.log(`| ${r.id} | ${r.owned ? "yes" : "other console"} | ${r.surface} | ${r.access ?? ""} |`);
}
