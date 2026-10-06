// @class: real-backend — the PHASE-3 REAL-BACKEND suite (docs/C5_REAL_BACKEND_E2E_MATRIX.md). Real browser → real Studio app → real backend. No interception, no stubs.
//
// Exit codes:  0 = every flow that could run PASSED (BLOCKED/SKIP flows are listed, never counted as PASS)
//              1 = at least one flow FAILED
//              2 = NOT RUN: no backend configured/reachable, or the fixtures could not be created. This is never a pass.
import { loadConfig, REQUIRED, EXIT } from "./lib/env.mjs";
import { Check, Blocked, Skip, printTable, summarise, writeReport } from "./lib/report.mjs";
import { createFixtures, cleanup } from "./lib/fixtures.mjs";
import { launch } from "./lib/ui.mjs";

const cfg = loadConfig();
const notRun = (why) => { console.error(`\nREAL BACKEND: NOT RUN — ${why}\nThis is not a pass. See tests/e2e-real/README.md.`); process.exit(EXIT.NOT_RUN); };

if (cfg.missing.length) {
  console.error("Missing environment:\n" + cfg.missing.map(([k, d]) => `  ${k}  — ${d}`).join("\n"));
  notRun("the real-backend suite needs a running stack (never started by this script) and a system-admin account from the environment");
}
// guard: the backend behind the Studio origin must answer like the platform API, otherwise we would be testing nothing
let authConfig;
try {
  const r = await fetch(`${cfg.studio}/api/v1/auth/config`, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) notRun(`${cfg.studio}/api/v1/auth/config answered ${r.status}: the API behind the Studio origin is not reachable`);
  authConfig = await r.json();
  if (typeof authConfig?.localLogin !== "boolean") notRun(`${cfg.studio}/api/v1/auth/config did not return the platform's auth config: this is not the backend`);
} catch (e) { notRun(`cannot reach ${cfg.studio}: ${e?.message ?? e}`); }
if (!authConfig.localLogin) notRun("local login is disabled on this stack; the fixtures need local accounts (SSO-only stacks are out of scope for this suite)");

const ORDER = ["e2e-01", "e2e-02", "e2e-03", "e2e-04", "e2e-05", "e2e-06", "e2e-07", "e2e-08", "e2e-09", "e2e-s1", "e2e-10", "e2e-11", "e2e-12", "e2e-13", "e2e-14", "e2e-s2"];
const flows = [];
for (const f of ORDER) flows.push(await import(`./flows/${f}.mjs`));
const selected = flows.filter((f) => !cfg.only.length || cfg.only.some((o) => o.toLowerCase() === f.id.toLowerCase()));

console.log(`REAL BACKEND suite · studio=${cfg.studio} · run=${cfg.runId} · ${selected.length} flow(s)`);
let fx;
try { fx = await createFixtures(cfg, (m) => console.log(`  [fixture] ${m}`)); }
catch (e) { await cleanup(fx).catch(() => undefined); notRun(`fixtures could not be created — ${e?.message ?? e}`); }

// facts observed from the server, recorded in the report (evidence for BLOCKED statuses)
const facts = { authConfig: { localLogin: authConfig.localLogin, oidc: authConfig.oidc, publicPublish: authConfig.publicPublish }, fixtureNotes: fx.notes };
{
  const base = `/workspaces/${fx.workspaces.A}/projects/${fx.projects.A.id}/app-runtime`;
  const q = await fx.sessions.adminA.post(`${base}/queries/e2e-probe/run`, { mode: "TEST" });
  const a = await fx.sessions.adminA.post(`${base}/actions/e2e-probe/execute`, { mode: "TEST" });
  facts.routes = { queries: { status: q.status, code: q.body?.code ?? null }, actions: { status: a.status, code: a.body?.error?.code ?? a.body?.code ?? null } };
  console.log(`  [probe] queries route → ${q.status} ${q.body?.code ?? ""} · actions route → ${a.status} ${a.body?.error?.code ?? a.body?.code ?? ""}  (404 without a code = flag off)`);
}

const browser = await launch(cfg);
const results = [];
for (const f of selected) {
  console.log(`\n${f.id} — ${f.title}`);
  const check = new Check(); let status = "PASS", owner = null, reason = null, ref = null;
  try { await f.run({ cfg, fx, browser, check }); }
  catch (e) {
    if (e instanceof Blocked) { status = "BLOCKED"; owner = e.owner; reason = e.message; ref = e.ref; console.log(`    ⛔ BLOCKED (${owner}${ref ? ` · ${ref}` : ""}): ${reason}`); }
    else if (e instanceof Skip) { status = "SKIP"; reason = e.message; console.log(`    ⏭ SKIP: ${reason}`); }
    else { status = "FAIL"; reason = `exception: ${e?.stack?.split("\n").slice(0, 3).join(" ⏎ ") ?? e}`; console.log(`    ✗ ${reason}`); }
  }
  // a failed check always wins: a BLOCKED flow whose evidence checks fail is a FAIL, a PASS needs zero failed checks and at least one check
  if (check.failed.length) status = "FAIL";
  else if (status === "PASS" && check.items.length === 0) { status = "FAIL"; reason = "the flow made no assertions"; }
  results.push({ id: f.id, title: f.title, status, owner, ref, reason, checks: check.items, failed: check.failed });
}
await browser.close();
const problems = await cleanup(fx, (m) => console.log(`  [cleanup] ${m}`));
printTable(results);
const file = writeReport(cfg.outDir, { studio: cfg.studio, runId: cfg.runId, facts, cleanup: problems, node: process.version }, results);
console.log(`report: ${file}`);
const s = summarise(results);
process.exit(s.FAIL ? EXIT.FAIL : s.PASS === 0 ? EXIT.NOT_RUN : EXIT.OK);
