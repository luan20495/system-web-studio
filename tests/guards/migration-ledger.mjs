#!/usr/bin/env node
// @class: unit
// Guard 7 (D-C0-44): the Flyway version numbers are handed out by C0 only (docs/parallel/MIGRATION_LEDGER.md) and never collide.
//   MIGRATION-DUPLICATE   two files with the same version (in any db/migration directory of the repository)
//   MIGRATION-UNALLOCATED a file whose version is higher than any number the ledger has allocated / reserved
//   MIGRATION-RESERVED    V31 is reserved for C2 candidate activation, V32 for Dynamic Organization: a file with such a number must carry that purpose in its name,
//                         and the ledger must still say so (a row per number). This guard NEVER requires or creates those files.
//   MIGRATION-GAP         V32 (Dynamic Organization) is on the tree and V31 is not: the ledger row of V31 must say it is a GAP / VOID, and V32's row must say ALLOCATED (D-C0-52)
//   MIGRATION-ORDER-HAZARD V31 and V32 both exist: with out-of-order off, a V31 added after V32 was applied fails Flyway validation on every migrated database. V31 can never be created.
//   MIGRATION-OUT-OF-ORDER Flyway out-of-order stays off
import { read, walk, report, REPO } from "./lib.mjs";

/** version → purpose keyword (must appear in the file name) and the ledger words that prove the reservation */
export const RESERVED = { 31: { name: /candidate/i, ledger: /candidate/i, owner: /C2/, label: "C2 candidate activation" }, 32: { name: /organi[sz]ation|\borg\b|org_/i, ledger: /organi[sz]ation/i, owner: /C1|C3/, label: "Dynamic Organization" } };

export function guardMigrationLedger(root = REPO) {
  const out = []; const files = walk(root).filter((f) => /(^|\/)db\/migration\/[^/]+\.sql$/.test(f));
  const byVer = new Map();
  for (const f of files) { const m = /(?:^|\/)V(\d+)__([^/]+)\.sql$/.exec(f); if (!m) continue; const v = Number(m[1]); (byVer.get(v) ?? byVer.set(v, []).get(v)).push({ f, name: m[2] }); }
  for (const [v, list] of byVer) if (list.length > 1) out.push({ rule: "MIGRATION-DUPLICATE", file: list.map((x) => x.f).join(" , "), message: `version V${v} is used by ${list.length} files` });
  let ledger = ""; try { ledger = read(root, "docs/parallel/MIGRATION_LEDGER.md"); } catch { out.push({ rule: "MIGRATION-LEDGER", file: "docs/parallel/MIGRATION_LEDGER.md", message: "the ledger is missing" }); }
  const rows = ledger.split("\n").filter((l) => l.startsWith("|"));
  const rowOf = (v) => rows.find((r) => new RegExp(`^\\|\\s*\\*{0,2}V${v}\\*{0,2}\\s*\\|`).test(r));
  const allocated = [...ledger.matchAll(/\bV(\d+)\b/g)].map((m) => Number(m[1])).filter((n) => rows.some((r) => new RegExp(`^\\|\\s*\\*{0,2}V${n}\\*{0,2}\\s*\\|`).test(r)));
  const maxAllocated = Math.max(0, ...allocated);
  for (const [v, list] of byVer) if (v > maxAllocated && !RESERVED[v]) out.push({ rule: "MIGRATION-UNALLOCATED", file: list[0].f, message: `V${v} is higher than every number in the ledger table (highest row: V${maxAllocated}): only C0 allocates a number` });
  for (const [vs, r] of Object.entries(RESERVED)) {
    const v = Number(vs); const row = rowOf(v);
    if (!row) out.push({ rule: "MIGRATION-RESERVED", file: "docs/parallel/MIGRATION_LEDGER.md", message: `the ledger has no table row for V${v} (${r.label}): the reservation was removed` });
    else if (!r.ledger.test(row) || !r.owner.test(row)) out.push({ rule: "MIGRATION-RESERVED", file: "docs/parallel/MIGRATION_LEDGER.md", message: `the ledger row of V${v} no longer says ${r.label} / ${r.owner.source}` });
    for (const x of byVer.get(v) ?? []) if (!r.name.test(x.name)) out.push({ rule: "MIGRATION-RESERVED", file: x.f, message: `V${v} is reserved for ${r.label}; '${x.name}' is something else` });
  }
  if (byVer.has(32)) {
    if (byVer.has(31)) out.push({ rule: "MIGRATION-ORDER-HAZARD", file: byVer.get(31)[0].f, message: "V31 exists next to V32: V32 (Dynamic Organization) was allocated first, so V31 is a permanent gap; a V31 created now fails Flyway validation (out-of-order is off) on every database that already applied V32. Take the next free number from C0." });
    else { const r31 = rowOf(31), r32 = rowOf(32); if (!r31 || !/\b(?:gap|void)\b/i.test(r31)) out.push({ rule: "MIGRATION-GAP", file: "docs/parallel/MIGRATION_LEDGER.md", message: "V32 is on the tree and V31 is not: the V31 row must say it is a GAP / VOID (nobody may create it later)" }); if (!r32 || !/allocated/i.test(r32)) out.push({ rule: "MIGRATION-GAP", file: "docs/parallel/MIGRATION_LEDGER.md", message: "the V32 file exists: its ledger row must say ALLOCATED" }); }
  }
  for (const f of walk(root).filter((x) => /(^|\/)application[^/]*\.ya?ml$/.test(x) && x.startsWith("backend/"))) if (/out-of-order:\s*true/.test(read(root, f))) out.push({ rule: "MIGRATION-OUT-OF-ORDER", file: f, message: "spring.flyway.out-of-order must stay false" });
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const a = process.argv.slice(2); const ri = a.indexOf("--root"); process.exit(report("MIGRATION-LEDGER (no duplicate number; V31 C2 / V32 Dynamic Organization reserved)", guardMigrationLedger(ri >= 0 ? a[ri + 1] : REPO), { json: a.includes("--json") }));
}
