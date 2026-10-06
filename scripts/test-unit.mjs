// Unit + SSR component tests for the Builder. No test framework dependency: tsc -> commonjs in .test-build, then `node --test`.
// Tested modules must use RELATIVE runtime imports (type-only imports may use @xweb/*).
import { spawnSync } from "node:child_process";
import { readdirSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const out = join(root, ".test-build");
rmSync(out, { recursive: true, force: true });
const tsc = spawnSync("npx", ["tsc", "-p", "tests/tsconfig.json"], { cwd: root, stdio: "inherit" });
if (tsc.status !== 0) process.exit(tsc.status ?? 1);

const files = [];
(function walk(dir) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p);
    else if (n.endsWith(".test.js")) files.push(p);
  }
})(join(out, "tests"));
const run = spawnSync(process.execPath, ["--test", "--test-reporter=spec", ...files], { cwd: root, stdio: "inherit" });
process.exit(run.status ?? 1);
