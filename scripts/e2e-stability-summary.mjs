// Reads report-*.json files that earlier REAL runs wrote and prints a per-flow table (passes / runs, failed checks, durations). It runs nothing and proves nothing by itself.
// usage: node scripts/e2e-stability-summary.mjs <dir-with-round*/report-*.json> [more dirs...]
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
const files = [];
const walk = (d) => { for (const n of readdirSync(d)) { const p = join(d, n); statSync(p).isDirectory() ? walk(p) : /^report-.*\.json$/.test(n) && files.push(p); } };
for (const d of process.argv.slice(2)) walk(d);
if (!files.length) { console.error("no report-*.json found"); process.exit(2); }
const flows = new Map(); let rounds = 0;
for (const f of files.sort()) {
  const r = JSON.parse(readFileSync(f, "utf8")); rounds++;
  for (const x of r.results) {
    const e = flows.get(x.id) ?? { runs: 0, PASS: 0, FAIL: 0, BLOCKED: 0, SKIP: 0, failures: [], ms: [] };
    e.runs++; e[x.status]++; if (x.status === "FAIL") e.failures.push(`${f.split("/").slice(-2, -1)[0]}: ${x.failed.map((c) => c.name).join("; ") || x.reason}`.slice(0, 200));
    if (x.start && x.end) e.ms.push(new Date(x.end) - new Date(x.start));
    flows.set(x.id, e);
  }
}
console.log(`reports: ${rounds}\n\n| Flow | PASS/runs | FAIL | BLOCKED | duration min–max (s) |\n|---|---|---|---|---|`);
for (const [id, e] of [...flows].sort()) console.log(`| ${id} | ${e.PASS}/${e.runs} | ${e.FAIL} | ${e.BLOCKED} | ${e.ms.length ? `${Math.round(Math.min(...e.ms) / 1000)}–${Math.round(Math.max(...e.ms) / 1000)}` : "-"} |`);
const failures = [...flows].flatMap(([id, e]) => e.failures.map((m) => `- ${id} ${m}`));
if (failures.length) console.log(`\nFailures:\n${failures.join("\n")}`);
