// Usage: node docs/parallel/c5/audit/tools/R-test-reach.mjs <repoRoot>   (static; reads sources only)
// Which source files are reachable (import graph) from unit tests (tests/builder, tests/page-runtime, tests/lib) and from browser harness entry files (tests/browser/*.tsx)?
import { createRequire } from "node:module";
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative, dirname, resolve } from "node:path";
const root = resolve(process.argv[2]);
const ts = createRequire(join(root, "package.json"))("typescript");
const pk = { "@xweb/types": "packages/types", "@xweb/api-client": "packages/api-client", "@xweb/auth": "packages/auth", "@xweb/permissions": "packages/permissions", "@xweb/i18n": "packages/i18n", "@xweb/ui": "packages/ui" };
const SKIP = new Set(["node_modules", ".next", ".test-build", "dist", ".run", "backend", ".git"]);
const all = [];
(function walk(d) { for (const n of readdirSync(d)) { if (SKIP.has(n)) continue; const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else if (/\.(tsx?)$/.test(n) && !n.endsWith(".d.ts")) all.push(p); } })(root);
const tryF = (b) => { for (const e of ["", ".ts", ".tsx", "/index.ts", "/index.tsx"]) { const f = b + e; if (existsSync(f) && statSync(f).isFile()) return f; } return null; };
const res = (from, s) => { if (s.startsWith(".")) return tryF(resolve(dirname(from), s)); if (s.startsWith("@/")) return tryF(join(root, s.slice(2))); for (const k in pk) { if (s === k) return tryF(join(root, pk[k], "src/index")); if (s.startsWith(k + "/")) return tryF(join(root, pk[k], "src", s.slice(k.length + 1))); } return null; };
const deps = new Map();
for (const f of all) { const sf = ts.createSourceFile(f, readFileSync(f, "utf8"), ts.ScriptTarget.ES2022, true); const out = []; const v = (n) => { if ((ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) && n.moduleSpecifier && ts.isStringLiteral(n.moduleSpecifier)) { if (!(ts.isImportDeclaration(n) && n.importClause?.isTypeOnly)) { const t = res(f, n.moduleSpecifier.text); if (t) out.push(t); } } ts.forEachChild(n, v); }; v(sf); deps.set(f, out); }
const reach = (starts) => { const s = new Set(); const q = [...starts]; while (q.length) { const f = q.pop(); if (s.has(f)) continue; s.add(f); for (const d of deps.get(f) ?? []) q.push(d); } return s; };
const rel = (p) => relative(root, p);
const unit = reach(all.filter((f) => /^tests\/(builder|page-runtime|lib)\//.test(rel(f)) && /\.test\.tsx?$/.test(f)));
const harness = reach(all.filter((f) => /^tests\/browser\/.*harness.*\.tsx$/.test(rel(f)) || /^tests\/browser\/harness\.tsx$/.test(rel(f))));
const src = all.filter((f) => /^(features|packages\/(ui|auth|api-client|permissions|i18n|types)|lib|components)\//.test(rel(f)));
const rows = src.map((f) => ({ f: rel(f), unit: unit.has(f), harness: harness.has(f) }));
const none = rows.filter((r) => !r.unit && !r.harness);
console.log("total", rows.length, "unit-reached", rows.filter((r) => r.unit).length, "harness-reached", rows.filter((r) => r.harness).length, "either", rows.filter((r) => r.unit || r.harness).length, "neither", none.length);
for (const r of none) console.log("NONE", r.f, readFileSync(join(root, r.f), "utf8").split("\n").length);
