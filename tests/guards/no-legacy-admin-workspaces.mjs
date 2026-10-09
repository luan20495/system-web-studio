#!/usr/bin/env node
// @class: unit
// Import gate (D-C1-13A, strengthened D-C0-44): the portals must not create a tenant-owned workspace through the legacy `POST /api/v1/admin/workspaces`
// (it puts the workspace in the DEFAULT tenant). The only workspace-creation route for a tenant is `/admin/tenants/{tenantId}/workspaces`.
// A reading of the collection (`GET /admin/workspaces`, `/admin/workspaces/{id}`) is NOT creation and is allowed.
//   node tests/guards/no-legacy-admin-workspaces.mjs [rootDir | --root DIR] [--json]
// Rules (on comment-stripped code, string concatenations such as "/admin/" + "workspaces" folded first):
//   LEGACY-WORKSPACE-CLIENT   the legacy client method `createWorkspace` exists (definition or call)
//   LEGACY-WORKSPACE-ROUTE    the collection literal `/admin/workspaces` is used by a non-GET call: `method: "POST|PUT|PATCH"` in the same call (any number of lines),
//                             `.post(` / `.put(` / `.patch(` / `call("POST", ...)` in front of it, or `fetch(...)` with such a method
// Scope: production code (packages, apps, app, features, lib, components) and the legacy `e2e/` scripts. A deliberate negative test lives outside this scope (scripts/provisioning-e2e.mjs asserts 403).
import { read, walk, stripComments, lineOf, isNonProduction, allowedByPragma, report, REPO } from "./lib.mjs";

const DIRS = /^(packages|apps|app|features|lib|components|e2e)\//;
const COLLECTION = /["'`]\/?(?:api\/v1\/)?admin\/workspaces(?=["'`?]|\$\{(?!\w*\}\/))/g;      // not /admin/workspaces/{id} and not /admin/workspaces/${id}

export function guardLegacyWorkspaceRoute(root = REPO) {
  const out = [];
  for (const f of walk(root).filter((x) => /\.(tsx?|mjs|js)$/.test(x) && DIRS.test(x) && !(isNonProduction(x) && !x.startsWith("e2e/")))) {
    const raw = read(root, f); const lines = raw.split("\n");
    // fold "a" + "b" so a split literal cannot hide the path (keeps line count: only same-line concatenations are folded)
    const code = stripComments(raw).replace(/(["'`])\s*\+\s*\1/g, "").replace(/["'`]\s*\+\s*["'`]/g, "");
    let m; const cm = /\bcreateWorkspace\b\s*(?::|\(|=)/g;
    while ((m = cm.exec(code))) { const line = lineOf(code, m.index); if (!allowedByPragma(lines, line, "LEGACY-WORKSPACE-CLIENT")) out.push({ rule: "LEGACY-WORKSPACE-CLIENT", file: f, line, message: "the legacy workspace client method exists / is called (POST /api/v1/admin/workspaces): use the tenant route", excerpt: lines[line - 1] }); }
    const re = new RegExp(COLLECTION.source, "g");
    while ((m = re.exec(code))) {
      const before = code.slice(Math.max(0, m.index - 48), m.index); const after = code.slice(m.index, m.index + 360);
      // the enclosing call ends at the first unbalanced ")" : cut the window there
      let depth = 0, end = after.length; for (let i = 0; i < after.length; i++) { const c = after[i]; if (c === "(") depth++; else if (c === ")") { if (depth === 0) { end = i; break; } depth--; } }
      const call = after.slice(0, end);
      const nonGet = /method\s*:\s*["'`](?:POST|PUT|PATCH)["'`]/i.test(call) || /\.(?:post|put|patch)\s*\(\s*$/i.test(before) || /["'`](?:POST|PUT|PATCH)["'`]\s*,\s*$/i.test(before) || /\b(?:POST|PUT|PATCH)\s+$/.test(before);
      const line = lineOf(code, m.index);
      if (nonGet && !allowedByPragma(lines, line, "LEGACY-WORKSPACE-ROUTE")) out.push({ rule: "LEGACY-WORKSPACE-ROUTE", file: f, line, message: "a non-GET call to the legacy collection /admin/workspaces: tenant workspaces are created through /admin/tenants/{tenantId}/workspaces", excerpt: lines[line - 1] });
    }
  }
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const a = process.argv.slice(2); const ri = a.indexOf("--root"); const pos = a.find((x) => !x.startsWith("--"));
  const root = ri >= 0 ? a[ri + 1] : pos ?? REPO; const findings = guardLegacyWorkspaceRoute(root);
  if (!a.includes("--json")) console.log(`scanned ${walk(root).filter((x) => /\.(tsx?|mjs|js)$/.test(x) && DIRS.test(x)).length} files under ${root}`);
  process.exit(report(findings.length ? "LEGACY-WORKSPACE no portal code creates a workspace through POST /api/v1/admin/workspaces" : "no portal code creates a workspace through POST /api/v1/admin/workspaces", findings, { json: a.includes("--json") }));
}
