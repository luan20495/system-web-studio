#!/usr/bin/env node
// C6 Visual regression: pixel comparison of two evidence directories (same relative PNG paths). BASELINE = the previous accepted run (or the pilot), CURRENT = the run under test.
// Usage: TOOLS=<dir with pngjs, pixelmatch> node ui-compare.mjs <baselineDir> <currentDir> [thresholdPercent=0.5]
// Output (in CURRENT): visual-diff.tsv (path, status, diff %, size change) and diff/<path>.png for every CHANGED case. A change is a REVIEW item, not an automatic defect:
// an intended C5 change shows up here too; an unintended one (layout shift, lost icon, truncated text) is what the reviewer is looking for.
import { createRequire } from "node:module"; import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from "node:fs"; import { join, dirname } from "node:path";
const R = createRequire(process.env.TOOLS + "/package.json"); const { PNG } = R("pngjs"); const pm = (await import(R.resolve("pixelmatch"))).default;
const [base, cur, thr = "0.5"] = process.argv.slice(2); if (!base || !cur) { console.error("usage: ui-compare.mjs <baselineDir> <currentDir> [thresholdPercent]"); process.exit(2); }
const walk = (d, rel = "") => readdirSync(d).flatMap((n) => { const p = join(d, n); return statSync(p).isDirectory() ? (n === "diff" ? [] : walk(p, join(rel, n))) : n.endsWith(".png") ? [join(rel, n)] : []; });
const B = new Set(walk(base)), C = walk(cur); const rows = []; let changed = 0, same = 0, added = 0;
for (const rel of C) { if (!B.has(rel)) { rows.push([rel, "NEW", "", ""]); added++; continue; } const a = PNG.sync.read(readFileSync(join(base, rel))), b = PNG.sync.read(readFileSync(join(cur, rel)));
  if (a.width !== b.width || a.height !== b.height) { rows.push([rel, "CHANGED_SIZE", "", `${a.width}x${a.height} -> ${b.width}x${b.height}`]); changed++; continue; }
  const diff = new PNG({ width: a.width, height: a.height }); const n = pm(a.data, b.data, diff.data, a.width, a.height, { threshold: 0.1 }); const pct = (100 * n) / (a.width * a.height);
  if (pct > Number(thr)) { const out = join(cur, "diff", rel); mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, PNG.sync.write(diff)); rows.push([rel, "CHANGED", pct.toFixed(2), ""]); changed++; } else { rows.push([rel, "SAME", pct.toFixed(3), ""]); same++; } }
for (const rel of B) if (!C.includes(rel)) rows.push([rel, "MISSING_IN_CURRENT", "", ""]);
writeFileSync(join(cur, "visual-diff.tsv"), ["path\tstatus\tdiff_percent\tnote", ...rows.map((r) => r.join("\t"))].join("\n") + "\n");
console.log(JSON.stringify({ compared: C.length, same, changed, added, missing: rows.filter((r) => r[1] === "MISSING_IN_CURRENT").length, thresholdPercent: Number(thr) }));
