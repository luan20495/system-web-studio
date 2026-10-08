#!/usr/bin/env node
// @class: unit
// ONE command for the frontend gate (D-C0-44):  npm run gate:frontend  [-- --no-build] [-- --keep]
//   1 static guards        tests/guards/run-static.mjs   (org hierarchy / fail-closed / relation, legacy workspace route, test labeling, test classification, migration ledger)
//   2 guard self-tests     tests/guards/guards.test.mjs  (every guard must FAIL on a deliberately broken fixture)
//   3 typecheck            packages, the three apps, the root app
//   4 unit                 npm run test:unit
//   5 production builds    Platform, Admin, Studio with the PRODUCTION configuration (https origins, same-origin /api) into apps/<app>/.next-gate (never the dev / local / public dist directories)
//   6 bundle scan          scripts/scan-prod-bundles.mjs on those builds: no localhost / loopback / container / compose-service name in what a browser receives
// Not part of it (they need a browser, a stack or a network): the organization browser harness (tests/browser/org.spec.mjs), real-backend flows (npm run test:e2e:real), public smokes.
// Build env: GATE_ORIGIN_PLATFORM / _ADMIN / _STUDIO (default https://platform|admin|studio.toolsmcp.uk), GATE_API_PROXY_TARGET (server-side only, default http://127.0.0.1:18081).
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname.replace(/\/$/, "");
const args = process.argv.slice(2); const NO_BUILD = args.includes("--no-build"); const KEEP = args.includes("--keep");
const APPS = ["platform", "admin", "studio"]; const results = [];
const run = (name, cmd, argv, opts = {}) => {
  const t = Date.now(); const r = spawnSync(cmd, argv, { cwd: opts.cwd ?? ROOT, env: { ...process.env, ...(opts.env ?? {}) }, encoding: "utf8", stdio: opts.quiet ? ["ignore", "pipe", "pipe"] : "inherit" });
  const ok = r.status === 0; results.push([name, ok, ((Date.now() - t) / 1000).toFixed(0) + "s"]);
  if (!ok && opts.quiet) process.stdout.write(((r.stdout ?? "") + (r.stderr ?? "")).split("\n").slice(-25).join("\n") + "\n");
  return ok;
};
const node = process.execPath;

run("static guards", node, ["tests/guards/run-static.mjs"]);
run("guard self-tests", node, ["--test", "tests/guards/guards.test.mjs"], { quiet: true });
run("typecheck packages", "npm", ["run", "typecheck:all"], { quiet: true });
run("typecheck apps", "npm", ["run", "typecheck:apps"], { quiet: true });
run("typecheck root", "npx", ["tsc", "--noEmit", "-p", "tsconfig.json"], { quiet: true });
run("unit tests", "npm", ["run", "test:unit"], { quiet: true });

if (NO_BUILD) results.push(["production builds + bundle scan", null, "skipped (--no-build)"]);
else {
  const env = { NEXT_PUBLIC_API_MODE: "http", NEXT_DIST_DIR: ".next-gate", API_PROXY_TARGET: process.env.GATE_API_PROXY_TARGET ?? "http://127.0.0.1:18081", NODE_ENV: "production",
    NEXT_PUBLIC_PORTAL_URL_PLATFORM: process.env.GATE_ORIGIN_PLATFORM ?? "https://platform.toolsmcp.uk", NEXT_PUBLIC_PORTAL_URL_ADMIN: process.env.GATE_ORIGIN_ADMIN ?? "https://admin.toolsmcp.uk", NEXT_PUBLIC_PORTAL_URL_STUDIO: process.env.GATE_ORIGIN_STUDIO ?? "https://studio.toolsmcp.uk" };
  let built = true;
  for (const app of APPS) {
    const dir = join(ROOT, "apps", app); const keep = ["tsconfig.json", "next-env.d.ts"].map((f) => [join(dir, f), existsSync(join(dir, f)) ? readFileSync(join(dir, f), "utf8") : null]);
    rmSync(join(dir, ".next-gate"), { recursive: true, force: true });
    const ok = run(`build ${app} (production config)`, "npx", ["next", "build"], { cwd: dir, env, quiet: true });
    for (const [p, c] of keep) if (c !== null && readFileSync(p, "utf8") !== c) writeFileSync(p, c);       // `next build` rewrites tsconfig for a custom distDir: put the tracked files back
    built = built && ok;
  }
  if (built) run("bundle scan (no localhost / loopback / container in browser output)", node, ["scripts/scan-prod-bundles.mjs", "--dist", ".next-gate"]);
  else results.push(["bundle scan", false, "not run: a build failed"]);
  if (!KEEP) for (const app of APPS) rmSync(join(ROOT, "apps", app, ".next-gate"), { recursive: true, force: true });
}
const failed = results.filter(([, ok]) => ok === false).length;
console.log("\n==== gate:frontend ====\n" + results.map(([n, ok, t]) => `${ok === null ? "SKIP" : ok ? "PASS" : "FAIL"}  ${n.padEnd(64)} ${t}`).join("\n"));
console.log(failed ? `\nGATE FAILED (${failed})` : "\nGATE GREEN");
process.exit(failed ? 1 : 0);
