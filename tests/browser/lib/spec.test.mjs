// @class: unit
// Tests of tests/browser/lib/spec.mjs (plain node:test, no browser): HARNESS_URL is required and never falls back to a fixed port, the Chrome path is per OS and fails loudly, the check list
// counts and exits correctly, and no spec bypasses the toolkit. Run by `npm run test:unit` through tests/browser/lib/spec-lib.wire.test.ts.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { chromePath, harnessOrigin, harnessPage, harnessUrl, makeChecks } from "./spec.mjs";

const here = new URL(".", import.meta.url).pathname;
const run = (code, env = {}) => {
  const e = { ...process.env, ...env }; delete e.NODE_TEST_CONTEXT; for (const k of Object.keys(env)) if (env[k] === undefined) delete e[k];
  return spawnSync(process.execPath, ["--input-type=module", "-e", `import * as s from ${JSON.stringify(here + "spec.mjs")}; ${code}`], { encoding: "utf8", env: e });
};

test("harnessUrl: a spec run without HARNESS_URL fails loudly with exit 2 and names harness-server.mjs; it never falls back to a port", () => {
  const r = run(`console.log(s.harnessUrl())`, { HARNESS_URL: undefined });
  assert.equal(r.status, 2); assert.equal(r.stdout, "");
  assert.match(r.stderr, /HARNESS_URL is not set/); assert.match(r.stderr, /harness-server\.mjs run/);
  assert.doesNotMatch(r.stdout + r.stderr, /127\.0\.0\.1:4000\/index/);          // the old default URL must not appear as an instruction or a value
});
test("harnessUrl: the data-source harness variable is checked by its own name; a malformed URL is refused", () => {
  const r = run(`s.harnessUrl("DS_HARNESS_URL")`, { DS_HARNESS_URL: undefined, HARNESS_URL: "http://127.0.0.1:5/index.html" });
  assert.equal(r.status, 2); assert.match(r.stderr, /DS_HARNESS_URL is not set/);
  const bad = run(`s.harnessUrl()`, { HARNESS_URL: "not a url" }); assert.equal(bad.status, 2); assert.match(bad.stderr, /is not a URL/);
});
test("harnessUrl / harnessOrigin / harnessPage derive from the one variable", () => {
  process.env.HARNESS_URL = "http://127.0.0.1:51234/index.html";
  assert.equal(harnessUrl(), "http://127.0.0.1:51234/index.html"); assert.equal(harnessOrigin(), "http://127.0.0.1:51234"); assert.equal(harnessPage("org.html"), "http://127.0.0.1:51234/org.html");
});

test("chromePath: CHROME wins when it exists; otherwise the first installed default of the OS; each OS has its own list", () => {
  const exists = (set) => (p) => set.includes(p);
  assert.equal(chromePath({ CHROME: "/x/chrome" }, "darwin", exists(["/x/chrome"])), "/x/chrome");
  assert.equal(chromePath({}, "darwin", exists(["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])), "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
  assert.equal(chromePath({}, "linux", exists(["/usr/bin/chromium", "/usr/bin/google-chrome"])), "/usr/bin/google-chrome");
  assert.match(chromePath({}, "win32", exists(["C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"])), /chrome\.exe$/);
});
test("chromePath: a CHROME that does not exist, or an OS with no browser, stops with exit 2 and says what was looked for", () => {
  const r1 = run(`s.chromePath({ CHROME: "/definitely/not/here" })`); assert.equal(r1.status, 2); assert.match(r1.stderr, /CHROME=\/definitely\/not\/here does not exist/);
  const r2 = run(`s.chromePath({}, "linux", () => false)`); assert.equal(r2.status, 2); assert.match(r2.stderr, /no Chrome \/ Chromium found for linux/); assert.match(r2.stderr, /\/usr\/bin\/google-chrome/); assert.match(r2.stderr, /Set CHROME=/);
  const r3 = run(`s.chromePath({}, "plan9", () => false)`); assert.equal(r3.status, 2); assert.match(r3.stderr, /no default for this OS/);
});

test("makeChecks: PASS / FAIL / SKIP lines, counts, clipping, and the exit code", () => {
  const { results, check, skip } = makeChecks({ clip: 10 });
  check("a", true); check("b", 0, "x   y\nz  and a very long text"); skip("c", "why");
  assert.deepEqual(results.map((r) => [r.name, r.ok]), [["a", true], ["b", false], ["c", null]]);
  const ok = run(`const { check, finish } = s.makeChecks(); check("one", true); check("two", 1, "d"); finish();`);
  assert.equal(ok.status, 0); assert.match(ok.stdout, /PASS {2}one/); assert.match(ok.stdout, /2\/2 checks passed/);
  const bad = run(`const { check, skip, finish } = s.makeChecks({ clip: 5 }); check("one", true); check("two", false, "abcdefghij"); skip("three", "n/a"); finish();`);
  assert.equal(bad.status, 1); assert.match(bad.stdout, /FAIL {2}two {2}— abcde\n/); assert.match(bad.stdout, /SKIP {2}three/); assert.match(bad.stdout, /1\/2 checks passed, 1 skipped/);
});

test("guard: every browser spec uses the toolkit (no own chromium.launch, no fixed harness port, no hard-coded Linux Chrome path, no own HARNESS_URL default)", () => {
  const dir = join(here, ".."); const EXTERNAL = new Set(["page-runtime.spec.mjs"]);   // C2-owned (see scripts/test-classify.mjs EXTERNAL): has its own stub origin, not migrated by C5
  const specs = readdirSync(dir).filter((f) => f.endsWith(".spec.mjs") && !EXTERNAL.has(f)); assert.ok(specs.length >= 10);
  const problems = [];
  for (const f of specs) {
    const src = readFileSync(join(dir, f), "utf8");
    if (!/from "\.\/lib\/spec\.mjs"/.test(src)) problems.push(`${f}: does not import ./lib/spec.mjs`);
    if (/chromium\.launch\(/.test(src)) problems.push(`${f}: calls chromium.launch directly (use launch())`);
    if (/127\.0\.0\.1:4000|localhost:4000/.test(src.replace(/^\/\/.*$/gm, ""))) problems.push(`${f}: a fixed harness port 4000`);
    if (/opt\/pw-browsers/.test(src)) problems.push(`${f}: a hard-coded Linux Chrome path`);
    if (/process\.env\.(DS_)?HARNESS_URL\s*\?\?/.test(src)) problems.push(`${f}: its own HARNESS_URL default`);
  }
  assert.deepEqual(problems, []);
});
