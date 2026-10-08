// Static import-graph analysis (no ports, no processes, reads sources only). Usage: node docs/parallel/c5/audit/tools/R-import-graph.mjs <repoRoot> <out.json>   (needs `npm ci`: uses the repo TypeScript compiler API)
// Computes: file graph, SCC cycles (Tarjan), unused exports, orphan files (not reachable from entries), any/as counts.
import { createRequire } from "node:module";
import { readdirSync, readFileSync, statSync, existsSync, writeFileSync } from "node:fs";
import { join, relative, dirname, resolve, extname } from "node:path";
const root = resolve(process.argv[2]);
const require = createRequire(join(root, "package.json"));
const ts = require("typescript");

const SCAN = ["app", "apps", "components", "features", "lib", "packages", "workers", "tests", "scripts", "e2e"];
const SKIP = new Set(["node_modules", ".next", ".test-build", "dist", ".run", "backend", ".next-http"]);
const files = [];
(function walk(d) {
  for (const n of readdirSync(d)) {
    if (SKIP.has(n)) continue;
    const p = join(d, n); const st = statSync(p);
    if (st.isDirectory()) walk(p);
    else if (/\.(tsx?|mjs|js)$/.test(n) && !n.endsWith(".d.ts")) files.push(p);
  }
})(root);
const inScan = (p) => SCAN.includes(relative(root, p).split("/")[0]);
const all = files.filter(inScan);
const rel = (p) => relative(root, p);

const pkgDirs = { "@xweb/types": "packages/types", "@xweb/api-client": "packages/api-client", "@xweb/auth": "packages/auth", "@xweb/permissions": "packages/permissions", "@xweb/i18n": "packages/i18n", "@xweb/ui": "packages/ui", "@company/ui": "packages/company-ui", "@company/app-sdk": "packages/app-sdk" };
const EXT = [".ts", ".tsx", ".mjs", ".js", "/index.ts", "/index.tsx"];
function tryFile(base) {
  if (existsSync(base) && statSync(base).isFile()) return base;
  for (const e of EXT) if (existsSync(base + e) && statSync(base + e).isFile()) return base + e;
  return null;
}
function resolveSpec(from, spec) {
  if (spec.startsWith(".")) return tryFile(resolve(dirname(from), spec));
  if (spec.startsWith("@/")) {
    // apps/* map @/* to repo root too ("../../*")
    return tryFile(join(root, spec.slice(2)));
  }
  for (const k of Object.keys(pkgDirs)) {
    if (spec === k) return tryFile(join(root, pkgDirs[k], "src/index"));
    if (spec.startsWith(k + "/")) return tryFile(join(root, pkgDirs[k], "src", spec.slice(k.length + 1))) || tryFile(join(root, pkgDirs[k], spec.slice(k.length + 1)));
  }
  return null;
}

const info = new Map(); // file -> {imports:[{to, names:Set|'*'|'side', typeOnly}], exports:Set, reexports:[{to,names|'*'}], counts}
for (const f of all) {
  if (!/\.(tsx?)$/.test(f)) { // mjs/js: regex imports only
    const src = readFileSync(f, "utf8");
    const imps = [];
    for (const m of src.matchAll(/(?:import\s+(?:[^'"]*?from\s+)?|from\s+|import\()\s*['"]([^'"]+)['"]/g)) {
      const to = resolveSpec(f, m[1]); if (to) imps.push({ to, names: "*", typeOnly: false });
    }
    info.set(f, { imports: imps, exports: new Set(), reexports: [], lines: src.split("\n").length, counts: {} });
    continue;
  }
  const src = readFileSync(f, "utf8");
  const sf = ts.createSourceFile(f, src, ts.ScriptTarget.ES2022, true, f.endsWith("x") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const imports = [], exports = new Map(), reexports = [];
  const counts = { any: 0, asAny: 0, asUnknownAs: 0, asNever: 0, tsIgnore: 0, tsExpectError: 0, nonNull: 0, eslintDisable: 0 };
  const hasMod = (n, k) => n.modifiers?.some((m) => m.kind === k);
  const visit = (n) => {
    if (ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier)) {
      const to = resolveSpec(f, n.moduleSpecifier.text);
      const ic = n.importClause;
      let names = new Set(), typeOnly = !!ic?.isTypeOnly;
      if (!ic) names = "side";
      else {
        if (ic.name) names.add("default");
        if (ic.namedBindings) {
          if (ts.isNamespaceImport(ic.namedBindings)) names = "*";
          else for (const el of ic.namedBindings.elements) names.add((el.propertyName ?? el.name).text);
        }
      }
      imports.push({ to, spec: n.moduleSpecifier.text, names, typeOnly });
    } else if (ts.isExportDeclaration(n)) {
      if (n.moduleSpecifier && ts.isStringLiteral(n.moduleSpecifier)) {
        const to = resolveSpec(f, n.moduleSpecifier.text);
        if (!n.exportClause) reexports.push({ to, names: "*" });
        else if (ts.isNamespaceExport(n.exportClause)) { reexports.push({ to, names: "*", as: n.exportClause.name.text }); }
        else reexports.push({ to, names: n.exportClause.elements.map((e) => ({ from: (e.propertyName ?? e.name).text, as: e.name.text })) });
      } else if (n.exportClause && ts.isNamedExports(n.exportClause)) for (const e of n.exportClause.elements) exports.set(e.name.text, n.getStart());
    } else if (ts.isExportAssignment(n)) exports.set("default", n.getStart());
    else if (ts.isVariableStatement(n) && hasMod(n, ts.SyntaxKind.ExportKeyword)) { for (const d of n.declarationList.declarations) if (ts.isIdentifier(d.name)) exports.set(d.name.text, d.getStart()); else d.name.elements?.forEach((e) => ts.isIdentifier(e.name) && exports.set(e.name.text, d.getStart())); }
    else if ((ts.isFunctionDeclaration(n) || ts.isClassDeclaration(n) || ts.isInterfaceDeclaration(n) || ts.isTypeAliasDeclaration(n) || ts.isEnumDeclaration(n)) && hasMod(n, ts.SyntaxKind.ExportKeyword)) {
      exports.set(hasMod(n, ts.SyntaxKind.DefaultKeyword) ? "default" : n.name?.text, n.getStart());
    }
    if (ts.isCallExpression(n) && n.expression.kind === ts.SyntaxKind.ImportKeyword && ts.isStringLiteral(n.arguments[0])) {
      const to = resolveSpec(f, n.arguments[0].text); imports.push({ to, spec: n.arguments[0].text, names: "*", typeOnly: false, dynamic: true });
    }
    if (n.kind === ts.SyntaxKind.AnyKeyword) counts.any++;
    if (ts.isAsExpression(n)) {
      const t = n.type;
      if (t.kind === ts.SyntaxKind.AnyKeyword) counts.asAny++;
      if (t.kind === ts.SyntaxKind.NeverKeyword) counts.asNever++;
      if (ts.isAsExpression(n.expression) && n.expression.type.kind === ts.SyntaxKind.UnknownKeyword) counts.asUnknownAs++;
    }
    if (ts.isNonNullExpression(n)) counts.nonNull++;
    ts.forEachChild(n, visit);
  };
  visit(sf);
  counts.tsIgnore = (src.match(/@ts-ignore/g) || []).length;
  counts.tsExpectError = (src.match(/@ts-expect-error/g) || []).length;
  counts.eslintDisable = (src.match(/eslint-disable/g) || []).length;
  info.set(f, { imports, exports, reexports, lines: src.split("\n").length, counts, src });
}

// ---- cycles (runtime + type edges; report both)
function sccs(edgesOf, nodes) {
  let idx = 0; const st = [], on = new Set(), index = new Map(), low = new Map(), out = [];
  const strong = (v) => {
    index.set(v, idx); low.set(v, idx); idx++; st.push(v); on.add(v);
    for (const w of edgesOf(v)) {
      if (!index.has(w)) { strong(w); low.set(v, Math.min(low.get(v), low.get(w))); }
      else if (on.has(w)) low.set(v, Math.min(low.get(v), index.get(w)));
    }
    if (low.get(v) === index.get(v)) { const c = []; let w; do { w = st.pop(); on.delete(w); c.push(w); } while (w !== v); if (c.length > 1 || edgesOf(v).includes(v)) out.push(c); }
  };
  for (const n of nodes) if (!index.has(n)) strong(n);
  return out;
}
const srcFiles = all.filter((f) => /\.(tsx?)$/.test(f) && !rel(f).startsWith("tests/"));
const edgeSets = (typeToo) => (v) => {
  const i = info.get(v); if (!i) return [];
  const set = new Set();
  for (const e of i.imports) { if (!e.to || !info.has(e.to)) continue; if (!typeToo && (e.typeOnly)) continue; set.add(e.to); }
  for (const e of i.reexports) if (e.to && info.has(e.to)) set.add(e.to);
  return [...set];
};
const cyclesAll = sccs(edgeSets(true), srcFiles);
const cyclesRuntime = sccs(edgeSets(false), srcFiles);
// package-level graph
const pkgOf = (f) => { const r = rel(f).split("/"); if (r[0] === "packages") return "packages/" + r[1]; if (r[0] === "apps") return "apps/" + r[1]; if (["features", "components", "lib", "app"].includes(r[0])) return r[0] + (r[0] === "features" ? "/" + r[1] : ""); return r[0]; };
const pkgEdges = new Map();
for (const f of srcFiles) for (const e of info.get(f).imports) { if (!e.to || !info.has(e.to) || rel(e.to).startsWith("tests/")) continue; const a = pkgOf(f), b = pkgOf(e.to); if (a === b) continue; const k = a + " -> " + b; pkgEdges.set(k, (pkgEdges.get(k) || 0) + 1); }
const pkgNodes = [...new Set([...pkgEdges.keys()].flatMap((k) => k.split(" -> ")))];
const pkgAdj = new Map(pkgNodes.map((n) => [n, []]));
for (const k of pkgEdges.keys()) { const [a, b] = k.split(" -> "); pkgAdj.get(a).push(b); }
const pkgCycles = sccs((v) => pkgAdj.get(v) || [], pkgNodes);

// ---- unused exports
// usage[file] = Set(names) | '*'
const usage = new Map();
const mark = (file, names) => { if (!file) return; const cur = usage.get(file); if (cur === "*") return; if (names === "*") { usage.set(file, "*"); return; } if (names === "side") return; const s = cur || new Set(); for (const n of names) s.add(n); usage.set(file, s); };
const testUsage = new Map();
for (const f of all) {
  const i = info.get(f); const isTest = rel(f).startsWith("tests/") || rel(f).startsWith("e2e/") || rel(f).startsWith("scripts/");
  const tgt = isTest ? testUsage : usage;
  const m = (file, names) => { const cur = tgt.get(file); if (cur === "*") return; if (names === "*") { tgt.set(file, "*"); return; } if (names === "side") return; const s = cur || new Set(); for (const n of names) s.add(n); tgt.set(file, s); };
  for (const e of i.imports) if (e.to) m(e.to, e.names);
  for (const r of i.reexports) if (r.to) m(r.to, r.names === "*" ? "*" : r.names.map((x) => x.from));
}
// propagate through re-exports: if file A re-exports name n from B and A.n is used => B.n used. iterate.
function propagate(tgt) {
  let changed = true;
  while (changed) {
    changed = false;
    for (const f of all) {
      const i = info.get(f); const u = tgt.get(f); if (!u) continue;
      for (const r of i.reexports) {
        if (!r.to) continue;
        if (r.names === "*") { if (u === "*") { if (tgt.get(r.to) !== "*") { tgt.set(r.to, "*"); changed = true; } } else { const cur = tgt.get(r.to); if (cur !== "*") { const s = cur || new Set(); const before = s.size; for (const n of u) s.add(n); tgt.set(r.to, s); if (s.size !== before) changed = true; } } }
        else for (const x of r.names) if (u === "*" || u.has(x.as)) { const cur = tgt.get(r.to); if (cur !== "*") { const s = cur || new Set(); if (!s.has(x.from)) { s.add(x.from); tgt.set(r.to, s); changed = true; } } }
      }
    }
  }
}
propagate(usage); propagate(testUsage);
const unusedExports = [];
for (const f of srcFiles) {
  const i = info.get(f);
  const u = usage.get(f), t = testUsage.get(f);
  // entry files: Next convention files
  if (/(^|\/)(page|layout|route|loading|error|not-found|proxy|next\.config)\.(tsx?)$/.test(rel(f)) || /next\.config\.ts$/.test(f)) continue;
  for (const [name, pos] of i.exports) {
    const used = u === "*" || (u && u.has(name));
    const usedT = t === "*" || (t && t.has(name));
    if (!used) {
      const line = ts.getLineAndCharacterOfPosition(ts.createSourceFile(f, i.src, ts.ScriptTarget.ES2022, true), pos).line + 1;
      unusedExports.push({ file: rel(f), name, line, usedInTests: !!usedT });
    }
  }
}
// star-used files (import * / export *) hide unused: report separately
// ---- orphan files: not reachable from entries
const entries = all.filter((f) => /(^|\/)app\/.*(page|layout|entry)\.tsx$/.test(rel(f)) || /proxy\.ts$/.test(f) || /next\.config\.ts$/.test(f));
const reach = new Set(); const q = [...entries];
while (q.length) { const f = q.pop(); if (reach.has(f)) continue; reach.add(f); const i = info.get(f); if (!i) continue; for (const e of i.imports) if (e.to && info.has(e.to)) q.push(e.to); for (const r of i.reexports) if (r.to && info.has(r.to)) q.push(r.to); }
const testReach = new Set(); const q2 = all.filter((f) => rel(f).startsWith("tests/") || rel(f).startsWith("e2e/") || rel(f).startsWith("scripts/") || rel(f).startsWith("workers/"));
while (q2.length) { const f = q2.pop(); if (testReach.has(f)) continue; testReach.add(f); const i = info.get(f); if (!i) continue; for (const e of i.imports) if (e.to && info.has(e.to)) q2.push(e.to); for (const r of i.reexports) if (r.to && info.has(r.to)) q2.push(r.to); }
const orphans = srcFiles.filter((f) => !reach.has(f)).map((f) => ({ file: rel(f), reachableFromTests: testReach.has(f), lines: info.get(f).lines }));
// per-portal reach
const portalReach = {};
for (const p of ["platform", "admin", "studio"]) {
  const r = new Set(); const qq = entries.filter((f) => rel(f).startsWith(`apps/${p}/`) || /proxy|next\.config/.test(rel(f)) && rel(f).startsWith(`apps/${p}/`));
  while (qq.length) { const f = qq.pop(); if (r.has(f)) continue; r.add(f); const i = info.get(f); if (!i) continue; for (const e of i.imports) if (e.to && info.has(e.to)) qq.push(e.to); for (const x of i.reexports) if (x.to && info.has(x.to)) qq.push(x.to); }
  portalReach[p] = r;
}
const legacyEntries = entries.filter((f) => rel(f).startsWith("app/"));
const legacyReach = new Set(); { const qq = [...legacyEntries]; while (qq.length) { const f = qq.pop(); if (legacyReach.has(f)) continue; legacyReach.add(f); const i = info.get(f); if (!i) continue; for (const e of i.imports) if (e.to && info.has(e.to)) qq.push(e.to); for (const x of i.reexports) if (x.to && info.has(x.to)) qq.push(x.to); } }

// ---- type safety counts by file
const typeSafety = srcFiles.map((f) => ({ file: rel(f), ...info.get(f).counts, lines: info.get(f).lines })).filter((x) => x.any || x.asAny || x.asUnknownAs || x.asNever || x.tsIgnore || x.tsExpectError || x.eslintDisable);
const out = {
  fileCount: srcFiles.length, testFiles: all.length - srcFiles.length,
  cyclesAll: cyclesAll.map((c) => c.map(rel)), cyclesRuntime: cyclesRuntime.map((c) => c.map(rel)), pkgCycles,
  pkgEdges: Object.fromEntries([...pkgEdges.entries()].sort()),
  unusedExports, orphans, typeSafety,
  portal: Object.fromEntries(Object.entries(portalReach).map(([k, v]) => [k, { files: [...v].filter((f) => !rel(f).startsWith("apps/")).length, features: [...new Set([...v].map((f) => pkgOf(f)))] }])),
  legacy: [...legacyReach].map(rel),
  bigFiles: srcFiles.map((f) => ({ file: rel(f), lines: info.get(f).lines })).sort((a, b) => b.lines - a.lines).slice(0, 40),
  sharedAcrossPortals: srcFiles.filter((f) => ["platform", "admin", "studio"].filter((p) => portalReach[p].has(f)).length > 1).length,
  onlyOnePortal: Object.fromEntries(["platform", "admin", "studio"].map((p) => [p, srcFiles.filter((f) => portalReach[p].has(f) && ["platform", "admin", "studio"].filter((q) => portalReach[q].has(f)).length === 1).map(rel)]))
};
writeFileSync(resolve(process.argv[3] ?? "graph-out.json"), JSON.stringify(out, null, 1));
console.log(JSON.stringify({ fileCount: out.fileCount, cyclesAll: out.cyclesAll.length, cyclesRuntime: out.cyclesRuntime.length, pkgCycles: out.pkgCycles, unused: out.unusedExports.length, orphans: out.orphans.length }, null, 1));
