// @class: real-backend — result bookkeeping. A flow can only be PASS if it ran to the end against the real backend with every check true.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/** thrown by a flow when a prerequisite owned by someone else is missing; the reason is what the server actually answered / what is documented */
export class Blocked extends Error { constructor(owner, reason, ref = "") { super(reason); this.owner = owner; this.ref = ref; } }
/** thrown when the operator did not provide an optional input; not a defect of the product */
export class Skip extends Error {}

export class Check {
  constructor() { this.items = []; }
  ok(name, cond, detail = "") { this.items.push({ name, ok: !!cond, detail: String(detail).slice(0, 300) }); console.log(`    ${cond ? "✓" : "✗"} ${name}${!cond && detail ? `  — ${String(detail).slice(0, 200)}` : ""}`); return !!cond; }
  get failed() { return this.items.filter((i) => !i.ok); }
}

export function summarise(results) {
  const n = (s) => results.filter((r) => r.status === s).length;
  return { total: results.length, PASS: n("PASS"), FAIL: n("FAIL"), BLOCKED: n("BLOCKED"), SKIP: n("SKIP") };
}

export function printTable(results) {
  console.log("\n| ID | Status | Owner | Detail |\n|---|---|---|---|");
  for (const r of results) console.log(`| ${r.id} | ${r.status} | ${r.owner ?? ""} | ${(r.reason ?? (r.failed?.length ? `${r.failed.length} check(s) failed: ${r.failed.map((f) => f.name).join("; ")}` : "")).replace(/\|/g, "/").slice(0, 200)} |`);
  const s = summarise(results);
  console.log(`\nREAL BACKEND: PASS ${s.PASS}/${s.total} · FAIL ${s.FAIL}/${s.total} · BLOCKED ${s.BLOCKED}/${s.total} · SKIP ${s.SKIP}/${s.total}`);
}

export function writeReport(outDir, meta, results) {
  mkdirSync(outDir, { recursive: true });
  const file = join(outDir, `report-${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  // meta never contains credentials: only the studio origin, run id, and server facts that were observed
  writeFileSync(file, JSON.stringify({ ...meta, summary: summarise(results), results }, null, 2));
  return file;
}
