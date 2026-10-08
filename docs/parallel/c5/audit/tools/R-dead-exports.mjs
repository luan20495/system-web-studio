// Usage: node R-dead-exports.mjs <repoRoot> <graph-out.json>.  For each unused export of R-import-graph.mjs output, count word-boundary references: in own file (excluding the declaration line) and elsewhere (src, tests, scripts, e2e, workers).
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
const root = process.argv[2];
const o = JSON.parse(readFileSync(process.argv[3]));
const SKIP = new Set(["node_modules", ".next", ".test-build", "dist", ".run", "backend", ".git", "docs"]);
const files = [];
(function walk(d) { for (const n of readdirSync(d)) { if (SKIP.has(n)) continue; const p = join(d, n); const st = statSync(p); if (st.isDirectory()) walk(p); else if (/\.(tsx?|mjs|js|json|md|sh)$/.test(n)) files.push(p); } })(root);
const text = new Map(files.map((f) => [relative(root, f), readFileSync(f, "utf8")]));
const rows = [];
for (const u of o.unusedExports) {
  if (u.file.startsWith("packages/app-sdk") || u.file.startsWith("packages/company-ui")) continue;
  const re = new RegExp(`(?<![\\w$])${u.name.replace(/\$/g, "\\$")}(?![\\w$])`, "g");
  let own = 0, other = 0, otherFiles = [];
  for (const [f, t] of text) {
    const m = t.match(re); if (!m) continue;
    if (f === u.file) own = m.length - 1; else { other += m.length; otherFiles.push(f); }
  }
  rows.push({ ...u, own, other, otherFiles: otherFiles.slice(0, 4) });
}
const kind = (r) => (r.own === 0 && r.other === 0 ? "DEAD" : r.other === 0 && !r.usedInTests ? "EXPORT_ONLY_NOT_NEEDED" : r.usedInTests ? "TEST_SEAM" : "OTHER");
for (const r of rows) console.log(`${kind(r)}\t${r.file}:${r.line}\t${r.name}\town=${r.own}\tother=${r.other}\t${r.otherFiles.join(",")}`);
