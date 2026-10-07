// @class: real-backend — result bookkeeping. A flow can only be PASS if it ran to the end against the real backend with every check true.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/** thrown by a flow when a prerequisite owned by someone else is missing; the reason is what the server actually answered / what is documented */
export class Blocked extends Error { constructor(owner, reason, ref = "") { super(reason); this.owner = owner; this.ref = ref; } }
/** thrown when the backend contradicts a contract that is DECIDED (not a missing prerequisite): the flow ends FAIL with the owner, never PASS and never a silent BLOCKED.
 *  `expected`/`actual`/`impact` are written into the report as the CONTRACT MISMATCH block; the frontend must not work around it. */
export class Mismatch extends Error { constructor(owner, { expected, actual, impact, workaround = "NONE" }, ref = "") { super(`CONTRACT MISMATCH — expected: ${expected}; actual: ${actual}; impact: ${impact}`); this.owner = owner; this.detail = { expected, actual, impact, workaround }; this.ref = ref; } }
/** thrown when the operator did not provide an optional input; not a defect of the product */
export class Skip extends Error {}

/** what a check is evidence OF. Explicit `kind` wins; otherwise inferred from the check name (flows were written before kinds existed). */
export function kindOf(name, kind) {
  if (kind) return kind;
  if (/after the (backend )?(restart|outage|reconnect)|reachable again|reconnect|still readable|survives|completes after|recover/i.test(name)) return "recovery";
  if (/^\(setup\)|^\[api\]|→ ?\d{3}|\(\d{3}\)|\b(PATCH|POST|GET|PUT|DELETE)\b|answered|status|HTTP|idempotency|Idempotency-Key|accepted/i.test(name)) return "http";
  if (/persist|read back|revision|version row|server state|row exists|on the server|\[backend\]|unchanged|separate (call|session)|second session|agrees/i.test(name)) return "persistence";
  return "ui";
}

export class Check {
  constructor() { this.items = []; }
  ok(name, cond, detail = "", kind) { this.items.push({ name, ok: !!cond, detail: String(detail).slice(0, 300), kind: kindOf(name, kind) }); console.log(`    ${cond ? "✓" : "✗"} ${name}${!cond && detail ? `  — ${String(detail).slice(0, 200)}` : ""}`); return !!cond; }
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

/** The per-flow evidence block C6 asks for (one reporter, no second framework). `meta`: frontendHead, backend{url,head}, studio; `r`: a result of run.mjs. */
export function formatEvidence(meta, r) {
  const by = (k) => (r.checks ?? []).filter((c) => c.kind === k).map((c) => `${c.ok ? "ok " : "FAIL "}${c.name}${c.ok || !c.detail ? "" : ` (${c.detail.slice(0, 120)})`}`);
  const list = (a) => (a.length ? "\n  - " + a.join("\n  - ") : " none recorded");
  const rec = by("recovery");
  return [
    `FLOW: ${r.id} — ${r.title}`,
    `RESULT: ${r.status}`,
    `START: ${r.start ?? ""}`,
    `END: ${r.end ?? ""}`,
    `FRONTEND_HEAD: ${meta.frontendHead ?? "unknown"}`,
    `BACKEND_HEAD: ${meta.backend?.head ?? "not given (E2E_BACKEND_HEAD)"}`,
    `BACKEND_URL: ${meta.backend?.url ?? "not given (E2E_BACKEND_URL); studio proxy " + meta.studio}`,
    `PROJECT: ${r.project ?? "n/a"}`,
    `WORKSPACE: ${r.workspace ?? "n/a"}`,
    `HTTP EVIDENCE:${list(by("http"))}`,
    `UI ASSERTION:${list(by("ui"))}`,
    `PERSISTENCE ASSERTION:${list(by("persistence"))}`,
    `RESTART/RECOVERY:${rec.length ? list(rec) : " not applicable to this flow"}`,
    ...(r.mismatch ? [`CONTRACT MISMATCH: expected ${r.mismatch.expected} | actual ${r.mismatch.actual} | impact ${r.mismatch.impact} | frontend workaround ${r.mismatch.workaround}`] : []),
    `BLOCKER: ${r.status === "BLOCKED" || r.status === "FAIL" ? (r.reason ?? (r.failed?.length ? r.failed.map((f) => f.name).join("; ") : "")).replace(/\s+/g, " ").slice(0, 400) : "none"}`,
    `OWNER: ${r.owner ?? (r.status === "FAIL" ? "unassigned (triage)" : "n/a")}${r.ref ? ` · ref ${r.ref}` : ""}`,
  ].join("\n");
}
export function writeEvidence(outDir, meta, results) {
  mkdirSync(outDir, { recursive: true });
  const file = join(outDir, `evidence-${new Date().toISOString().replace(/[:.]/g, "-")}.txt`);
  writeFileSync(file, results.map((r) => formatEvidence(meta, r)).join("\n\n---\n\n") + "\n");
  return file;
}
