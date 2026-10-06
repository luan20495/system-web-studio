// Every test file must say what it really is. `// @class: unit | mock | harness | real-backend` in the first lines.
//   unit          pure logic / server-side rendering; no browser, no network
//   mock          fetch or backend stubbed — proves what the client sends/reads, NOT backend behaviour
//   harness       real Chromium on a test-only host (fake host, or no API behind it) — NOT a backend E2E
//   real-backend  real browser -> real backend. Allowed only in tests/e2e-real/ (the Phase-3 suite) and in the legacy e2e/ scripts, which must also carry `// @legacy:`
// Rules enforced here: no untagged test; no `mock`/`harness` file under tests/e2e-real (except its guard self-test); a `real-backend` file must not intercept the network
// (page.route / route.fulfill / setRequestInterception / msw) — that would be a mock calling itself E2E; no mock/harness file may call itself "full" or "real backend" E2E.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const CLASSES = ["unit", "mock", "harness", "real-backend"];
const HELPERS = new Set(["tests/builder/a11y.ts", "tests/builder/fixtures.ts", "tests/browser/harness.tsx", "tests/browser/build-harness.mjs", "tests/tsconfig.json", "tests/browser/README.md", "tests/e2e-real/README.md"]);
const files = [];
(function walk(dir) {
  for (const n of readdirSync(dir)) {
    if (n === "node_modules" || n.startsWith(".")) continue;
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p); else if (/\.(test\.tsx?|spec\.mjs|mjs)$/.test(n) || (/\.(ts|tsx)$/.test(n) && dir.includes("tests"))) files.push(relative(root, p));
  }
})(join(root, "tests"));
for (const n of readdirSync(join(root, "e2e"))) if (n.endsWith(".mjs")) files.push(`e2e/${n}`);

const rows = []; const problems = [];
for (const f of files.sort()) {
  if (HELPERS.has(f)) continue;
  const src = readFileSync(join(root, f), "utf8"); const head = src.split("\n").slice(0, 6).join("\n");
  const m = /^\/\/ @class: ([a-z-]+)/m.exec(head);
  if (!m || !CLASSES.includes(m[1])) { problems.push(`${f}: no valid '// @class:' tag in the first lines`); continue; }
  const c = m[1]; rows.push([f, c]);
  if (f.startsWith("tests/e2e-real/") && c !== "real-backend" && !f.endsWith("selftest.mjs")) problems.push(`${f}: only real-backend files may live in tests/e2e-real/`);
  if (c === "real-backend") {
    if (f.startsWith("e2e/") && !/^\/\/ @legacy:/m.test(head)) problems.push(`${f}: legacy real-backend script must carry '// @legacy:'`);
    if (!f.startsWith("e2e/") && !f.startsWith("tests/e2e-real/")) problems.push(`${f}: real-backend tests belong in tests/e2e-real/`);
    if (/page\.route\(|context\.route\(|route\.fulfill|setRequestInterception|\bmsw\b|nock\(/.test(src)) problems.push(`${f}: a real-backend test must not intercept or stub the network`);
  } else if (/\b(full|real[- ]backend)\b[^\n]{0,20}\bE2E\b/i.test(src.split("\n").slice(0, 8).join("\n")) && !/NOT/.test(src.split("\n").slice(0, 8).join("\n"))) problems.push(`${f}: a ${c} test calls itself full/real-backend E2E`);
}
const by = (c) => rows.filter((r) => r[1] === c).length;
console.log(`test classification: ${rows.length} files · unit ${by("unit")} · mock ${by("mock")} · harness ${by("harness")} · real-backend ${by("real-backend")}`);
for (const [f, c] of rows) if (process.argv.includes("--list")) console.log(`  ${c.padEnd(13)} ${f}`);
if (problems.length) { console.error("\n" + problems.map((p) => "✗ " + p).join("\n")); process.exit(1); }
console.log("OK: every test file is classified and no mock/harness test claims to be a backend E2E");
