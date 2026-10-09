// @class: unit — GUARD (M-107): ONE import spelling per dependency, with the one documented exception the unit runner forces.
// The runner (scripts/test-unit.mjs, C0) compiles to CommonJS and runs the compiled files with plain node: a module that a unit test LOADS cannot import `@xweb/*` (it resolves to .ts sources), so those
// modules (and the shims they go through) import `../packages/<pkg>/src/...` relatively. EVERY OTHER module under features / components / lib / app must use the package name `@xweb/<pkg>`.
// The loaded set is computed from the compiled output, so the rule follows the tests instead of a hand-kept list. When C0's runner resolves `@xweb/*` at runtime this whole exception (and the nine shim files) can go.
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";

// __dirname = <root>/.test-build/tests/builder when compiled
const ROOT = join(__dirname, "..", "..", "..");
const OUT = join(ROOT, ".test-build");

function loadedModules(): Set<string> {
  const loaded = new Set<string>();
  const resolveJs = (from: string, spec: string): string | null => {
    const base = resolve(dirname(from), spec);
    for (const c of [`${base}.js`, join(base, "index.js"), base]) if (existsSync(c) && statSync(c).isFile() && c.endsWith(".js")) return c;
    return null;
  };
  const visit = (f: string) => {
    if (loaded.has(f)) return; loaded.add(f);
    for (const m of readFileSync(f, "utf8").matchAll(/require\("(\.[^"]+)"\)/g)) { const r = resolveJs(f, m[1]); if (r) visit(r); }
  };
  const walk = (d: string) => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else if (n.endsWith(".test.js")) visit(p); } };
  walk(join(OUT, "tests"));
  return new Set([...loaded].map((f) => relative(OUT, f).replace(/\.js$/, "")));
}

const REL_PACKAGE = /^import\s+(?:type\s+)?[^;]*?from\s+"(?:\.\.\/)+packages\/(ui|permissions|api-client|types|i18n|auth|company-ui)\/src\/[^"]+"/gm;

test("GUARD: a module no unit test loads imports packages by name (@xweb/*), never through ../packages/<pkg>/src", () => {
  const loaded = loadedModules();
  assert.ok(loaded.size > 100, `the loaded set looks wrong (${loaded.size})`);
  const bad: string[] = [];
  const walk = (d: string) => {
    for (const n of readdirSync(d)) {
      if (["node_modules", ".next", ".test-build", "dist"].includes(n) || n.startsWith(".")) continue;
      const p = join(d, n);
      if (statSync(p).isDirectory()) { walk(p); continue; }
      if (!/\.tsx?$/.test(n)) continue;
      const rel = relative(ROOT, p).replace(/\.tsx?$/, "");
      if (loaded.has(rel)) continue;
      if (REL_PACKAGE.test(readFileSync(p, "utf8"))) bad.push(relative(ROOT, p));
      REL_PACKAGE.lastIndex = 0;
    }
  };
  for (const d of ["features", "components", "lib", "app"]) walk(join(ROOT, d));
  assert.deepEqual(bad, [], "import from \"@xweb/<pkg>\" (only modules a unit test loads must stay relative)");
});

test("GUARD self-check: the loaded set contains modules the tests really load and the shims", () => {
  const loaded = loadedModules();
  for (const m of ["features/studio/builder/core/permissions", "features/admin/adminModel", "packages/permissions/src/canonical"]) assert.ok(loaded.has(m), m);
  assert.ok(!loaded.has("app/layout"), "the root layout is not a unit-test module");
});
