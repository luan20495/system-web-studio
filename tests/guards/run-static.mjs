#!/usr/bin/env node
// @class: unit
// All static guards in one run (no build, no browser, seconds): `npm run guard:static`. Part of `npm run gate:frontend`.
import { spawnSync } from "node:child_process";
import { REPO } from "./lib.mjs";

const STEPS = [
  ["org: no hard-coded hierarchy (D-C0-43)", ["tests/guards/org-source-guards.mjs", "hierarchy"]],
  ["org: NOT_READY stays fail-closed", ["tests/guards/org-source-guards.mjs", "fail-closed"]],
  ["org: relation type is not permission", ["tests/guards/org-source-guards.mjs", "relation"]],
  ["no tenant-admin call to POST /api/v1/admin/workspaces", ["tests/guards/no-legacy-admin-workspaces.mjs"]],
  ["a fake transport is never called a real E2E", ["tests/guards/test-labeling.mjs"]],
  ["every test file is classified (C5 classifier)", ["scripts/test-classify.mjs"]],
  ["migrations: no duplicate number, V31 C2 / V32 Dynamic Organization reserved", ["tests/guards/migration-ledger.mjs"]],
];
let failed = 0; const rows = [];
for (const [name, args] of STEPS) {
  const r = spawnSync(process.execPath, args, { cwd: REPO, encoding: "utf8" });
  const ok = r.status === 0; if (!ok) failed++; rows.push([ok, name]);
  if (!ok) process.stdout.write((r.stdout ?? "") + (r.stderr ?? ""));
}
console.log(rows.map(([ok, n]) => `${ok ? "PASS" : "FAIL"}  ${n}`).join("\n"));
console.log(`\nstatic guards: ${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 1 : 0);
