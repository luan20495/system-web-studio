// @class: unit
// Runs tests/lib/audit-tools.test.mjs (plain node:test, no browser) from `npm run test:unit`, which only collects compiled *.test.js. Same pattern as tests/lib/owned-process.wire.test.ts.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

test("audit tools: the route inventory is derived from the source and the 'every route visited' check fails on a missing route (tests/lib/audit-tools.test.mjs)", () => {
  const root = join(__dirname, "..", "..", "..");                                   // .test-build/tests/lib -> repo root
  const env: NodeJS.ProcessEnv = { ...process.env }; delete env.NODE_TEST_CONTEXT;
  const r = spawnSync(process.execPath, ["--test", "--test-reporter=spec", "tests/lib/audit-tools.test.mjs"], { cwd: root, env, encoding: "utf8", timeout: 60_000 });
  if (r.status !== 0) process.stderr.write(`${r.stdout ?? ""}\n${r.stderr ?? ""}`);
  assert.equal(r.status, 0, "tests/lib/audit-tools.test.mjs failed (output above)");
});
