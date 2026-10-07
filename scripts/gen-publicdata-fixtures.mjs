// Regenerates tests/builder/fixtures-data/publicdata-conformance.json by running C2's REAL `resolveBindings` over tests/builder/publicdata-cases.json.
// The C2 source is read from git (default ref c1e0df5, override with C2_REF); nothing of it is copied into the repo. Types are erased with typescript.transpileModule.
// The unit test (tests/builder/publicdata.test.ts) compares C5's mirror (features/studio/builder/core/publicData.ts) with this output, so a drift in either is visible.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";
const root = new URL("..", import.meta.url).pathname;
const ref = process.env.C2_REF || "c1e0df5";
const require = createRequire(import.meta.url);
const ts = require("typescript");
const src = execFileSync("git", ["show", `${ref}:workers/render/page-runtime.ts`], { cwd: root, encoding: "utf8", maxBuffer: 1 << 24 });
const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const dir = mkdtempSync(join(tmpdir(), "c2-runtime-"));
writeFileSync(join(dir, "page-runtime.cjs"), js);
const { resolveBindings, BindingError, MAX_DISTINCT_QUERIES } = require(join(dir, "page-runtime.cjs"));
const cases = JSON.parse(readFileSync(join(root, "tests/builder/publicdata-cases.json"), "utf8"));
const out = cases.map((c) => {
  const { __extraSections, ...doc } = c.doc;
  const schema = { page: "p", sections: [...(doc.sections ?? []), ...(__extraSections ?? [])], pages: doc.pages ?? [], ...doc, sections: [...(doc.sections ?? []), ...(__extraSections ?? [])] };
  try { return { name: c.name, c2: { ok: true, bindings: resolveBindings(schema) } }; }
  catch (e) { if (!(e instanceof BindingError)) throw e; return { name: c.name, c2: { ok: false, message: e.message } }; }
});
writeFileSync(join(root, "tests/builder/fixtures-data/publicdata-conformance.json"), JSON.stringify({ ref, maxDistinctQueries: MAX_DISTINCT_QUERIES, results: out }, null, 1) + "\n");
console.log(`wrote ${out.length} results from C2 ${ref}: ${out.filter((r) => r.c2.ok).length} ok, ${out.filter((r) => !r.c2.ok).length} refused`);
