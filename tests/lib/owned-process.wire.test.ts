// @class: unit
// Runs tests/lib/owned-process.test.mjs (plain node:test, real processes) from `npm run test:unit`, which only collects compiled *.test.js. No framework, no new dependency.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

test("owned-process helper: FOREIGN_NEXT_SURVIVES / STALE_PID_SAFE / HARNESS_PID_SCOPED / unsafe-cleanup guard (tests/lib/owned-process.test.mjs)", () => {
  const root = join(__dirname, "..", "..", "..");                                   // .test-build/tests/lib -> repo root
  const env: NodeJS.ProcessEnv = { ...process.env }; delete env.NODE_TEST_CONTEXT;  // a nested `node --test` must report on its own
  const r = spawnSync(process.execPath, ["--test", "--test-reporter=spec", "tests/lib/owned-process.test.mjs"], { cwd: root, env, encoding: "utf8", timeout: 170_000 });
  if (r.status !== 0) process.stderr.write(`${r.stdout ?? ""}\n${r.stderr ?? ""}`);
  assert.equal(r.status, 0, "tests/lib/owned-process.test.mjs failed (output above)");
});
