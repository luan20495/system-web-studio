#!/usr/bin/env node
// Import gate (D-C1-13A): the portals must not create a tenant-owned workspace through the legacy `POST /api/v1/admin/workspaces` (it puts the workspace in the DEFAULT tenant).
// Static part: no client method / call site posts to "/admin/workspaces". The only allowed workspace-creation route is `/admin/tenants/{tenantId}/workspaces`.
// Reading (`GET /admin/workspaces`, `/admin/workspaces/{id}`) is NOT workspace creation and is allowed.
//   node tests/guards/no-legacy-admin-workspaces.mjs [rootDir]        (default: this checkout; C5 candidates: pass the worktree path)
// The dynamic part (a real browser run asserting no such request) is in scripts/provisioning-e2e.mjs.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = process.argv[2] ?? new URL("../..", import.meta.url).pathname;
const DIRS = ["packages", "apps", "app", "features", "lib", "components", "e2e"];
const SKIP = new Set(["node_modules", ".next", ".next-public", "dist", ".git", ".tmp-stage"]);
const files = [];
(function walk(d) { let es; try { es = readdirSync(d); } catch { return; } for (const e of es) { if (SKIP.has(e) || e.startsWith(".next")) continue; const p = join(d, e); const s = statSync(p); if (s.isDirectory()) walk(p); else if (/\.(ts|tsx|mjs|js)$/.test(e)) files.push(p); } })(ROOT);
const scoped = files.filter((f) => DIRS.some((d) => relative(ROOT, f).startsWith(d + "/")));
const hits = [];
for (const f of scoped) {
  const lines = readFileSync(f, "utf8").split("\n");
  lines.forEach((l, i) => {
    if (/^\s*(\/\/|\*|\/\*)/.test(l)) return;                                             // comments may mention the legacy route
    const posts = /["'`]\/(api\/v1\/)?admin\/workspaces["'`]/.test(l) && /method:\s*["']POST["']/.test(l);   // client method posting to the collection
    const usesClient = /\.admin\.createWorkspace\s*\(/.test(l) || /\bcreateWorkspace\s*:\s*\(/.test(l);      // call site or definition of the legacy client method
    if (posts || usesClient) hits.push(`${relative(ROOT, f)}:${i + 1}: ${l.trim().slice(0, 140)}`);
  });
}
console.log(`scanned ${scoped.length} files under ${ROOT}`);
if (hits.length) { console.log("FAIL legacy workspace creation (POST /api/v1/admin/workspaces) is still reachable from the portals:"); hits.forEach((h) => console.log("  " + h)); process.exit(1); }
console.log("PASS no portal code creates a workspace through POST /api/v1/admin/workspaces");
