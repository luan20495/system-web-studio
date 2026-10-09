// @class: unit
// Runs tests/browser/lib/spec.test.mjs (plain node:test, no browser) from `npm run test:unit`, which only collects compiled *.test.js. Same pattern as tests/lib/owned-process.wire.test.ts.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

test("browser spec toolkit: HARNESS_URL required, per-OS Chrome path, check list, no spec bypasses it (tests/browser/lib/spec.test.mjs)", () => {
  const root = join(__dirname, "..", "..", "..", "..");                              // .test-build/tests/browser/lib -> repo root
  const env: NodeJS.ProcessEnv = { ...process.env }; delete env.NODE_TEST_CONTEXT;
  const r = spawnSync(process.execPath, ["--test", "--test-reporter=spec", "tests/browser/lib/spec.test.mjs"], { cwd: root, env, encoding: "utf8", timeout: 60_000 });
  if (r.status !== 0) process.stderr.write(`${r.stdout ?? ""}\n${r.stderr ?? ""}`);
  assert.equal(r.status, 0, "tests/browser/lib/spec.test.mjs failed (output above)");
});
