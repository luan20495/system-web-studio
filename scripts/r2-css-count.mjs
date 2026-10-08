#!/usr/bin/env node
// C5-R2 audit helper (read-only, no servers): counts design-token coverage, physical vs logical CSS properties, and dark-mode hooks.
//   node scripts/r2-css-count.mjs [rootDir] [--json]
// Scope: every .css file under packages/ + app/ + apps/ + features/ + components/, and the style={{...}} / template-string CSS inside .ts/.tsx
// (lib/preview-document.ts, lib/schema-preview.ts, features/**/*.tsx). Comments are stripped first.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const ROOT = process.argv[2] && !process.argv[2].startsWith("--") ? process.argv[2] : new URL("..", import.meta.url).pathname;
const JSON_OUT = process.argv.includes("--json");
const DIRS = ["app", "apps", "components", "features", "lib", "packages"];
const SKIP = new Set(["node_modules", ".next", "dist", ".git"]);
const files = [];
(function walk(d) {
  let es; try { es = readdirSync(d); } catch { return; }
  for (const e of es) {
    if (SKIP.has(e) || e.startsWith(".next")) continue;
    const p = join(d, e); const s = statSync(p);
    if (s.isDirectory()) walk(p); else if (/\.(css|ts|tsx)$/.test(e)) files.push(p);
  }
})(ROOT);

const stripCss = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "");
const stripTs = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`\\])\/\/.*$/gm, "$1");
const HEX = /#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b(?![\w-])/g;
const FUNC = /\b(?:rgba?|hsla?|oklch|oklab|color-mix)\(/g;
const VARUSE = /var\(\s*--[\w-]+/g;
const VARDEF = /(^|[;{\s])--[\w-]+\s*:/g;
const PHYS = {
  "margin-left/right": /\bmargin-(?:left|right)\s*:/g, "padding-left/right": /\bpadding-(?:left|right)\s*:/g,
  "border-left/right": /\bborder-(?:left|right)(?:-[a-z]+)?\s*:/g, "left/right offset": /(?:^|[;{\s])(?:left|right)\s*:/g,
  "text-align left/right": /\btext-align\s*:\s*(?:left|right)\b/g, "float left/right": /\bfloat\s*:\s*(?:left|right)\b/g,
  "corner radius (physical)": /\bborder-(?:top|bottom)-(?:left|right)-radius\s*:/g,
  "margin/padding shorthand 4-value (inspect)": /\b(?:margin|padding)\s*:\s*[^;{}]*?\s[^;{}]*?\s[^;{}]*?\s[^;{}]+;/g,
  "translateX (direction-sensitive)": /translateX\(/g, "background-position x": /\bbackground-position\s*:\s*(?:left|right)/g,
  "scaleX(-1)/rotate on arrows (inspect)": /scaleX\(-1\)/g,
};
const LOGI = {
  "margin/padding-inline|block": /\b(?:margin|padding)-(?:inline|block)(?:-(?:start|end))?\s*:/g, "inset-inline|block": /\binset(?:-inline|-block)?(?:-(?:start|end))?\s*:/g,
  "border-inline|block": /\bborder-(?:inline|block)(?:-(?:start|end))?(?:-[a-z]+)?\s*:/g, "text-align start/end": /\btext-align\s*:\s*(?:start|end)\b/g,
  "corner radius (logical)": /\bborder-(?:start|end)-(?:start|end)-radius\s*:/g, "float inline-start/end": /\bfloat\s*:\s*inline-(?:start|end)\b/g,
};
const THEME = { "prefers-color-scheme": /prefers-color-scheme/g, "color-scheme": /\bcolor-scheme\s*:/g, "data-theme/[data-theme]": /\[data-theme|data-theme/g, "forced-colors": /forced-colors/g, "prefers-contrast": /prefers-contrast/g, "prefers-reduced-motion": /prefers-reduced-motion/g, "dir=rtl / :dir(": /\[dir=|:dir\(|dir="rtl"/g };

const count = (s, re) => (s.match(re) ?? []).length;
const perFile = [];
const tot = { hex: 0, fn: 0, varUse: 0, varDef: 0 }; const physTot = {}; const logiTot = {}; const themeTot = {};
for (const f of files) {
  const rel = relative(ROOT, f); if (!DIRS.includes(rel.split(sep)[0])) continue;
  const raw = readFileSync(f, "utf8"); const isCss = f.endsWith(".css");
  const s = isCss ? stripCss(raw) : stripTs(raw);
  // for ts/tsx only look at lines that plausibly hold CSS: style objects, template CSS, or hex literals
  const hasCss = isCss || /style=\{\{|<style>|`[^`]*[{;]\s*(?:color|background|margin|padding)[^`]*`|#[0-9a-fA-F]{3,8}\b/.test(s);
  if (!hasCss) continue;
  const row = { file: rel, kind: isCss ? "css" : "ts", hex: count(s, HEX), fn: count(s, FUNC), varUse: count(s, VARUSE), varDef: isCss ? count(s, VARDEF) : 0, lines: raw.split("\n").length };
  if (!isCss) { // exclude non-colour hash uses (ids, anchors, css selectors like "#top", urls) by requiring a hex-looking colour with a digit-letter mix of valid lengths
    row.hex = (s.match(HEX) ?? []).filter((h) => /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(h) && /[0-9]/.test(h)).length;
  }
  for (const [k, re] of Object.entries(PHYS)) { const c = count(s, re); row["P:" + k] = c; physTot[k] = (physTot[k] ?? 0) + c; }
  for (const [k, re] of Object.entries(LOGI)) { const c = count(s, re); row["L:" + k] = c; logiTot[k] = (logiTot[k] ?? 0) + c; }
  for (const [k, re] of Object.entries(THEME)) { const c = count(s, re); row["T:" + k] = c; themeTot[k] = (themeTot[k] ?? 0) + c; }
  tot.hex += row.hex; tot.fn += row.fn; tot.varUse += row.varUse; tot.varDef += row.varDef; perFile.push(row);
}
const colourFiles = perFile.filter((r) => r.hex || r.fn || r.varUse || r.varDef).sort((a, b) => b.hex - a.hex);
const out = { root: ROOT, totals: tot, physical: physTot, logical: logiTot, theme: themeTot, files: colourFiles.map((r) => ({ file: r.file, kind: r.kind, lines: r.lines, hex: r.hex, colourFn: r.fn, varUse: r.varUse, varDef: r.varDef })) };
if (JSON_OUT) console.log(JSON.stringify(out, null, 1));
else {
  console.log("TOTAL hex literals", tot.hex, "| rgb()/hsl()/color-mix() calls", tot.fn, "| var(--x) uses", tot.varUse, "| --x definitions", tot.varDef);
  console.log("\nper file (hex, colour fns, var uses, var defs, lines)");
  for (const r of out.files) console.log(r.file.padEnd(52), String(r.hex).padStart(5), String(r.colourFn).padStart(5), String(r.varUse).padStart(5), String(r.varDef).padStart(5), String(r.lines).padStart(6));
  console.log("\nPHYSICAL (direction-sensitive) declarations:"); for (const [k, v] of Object.entries(physTot)) console.log("  " + k.padEnd(46), v);
  console.log("LOGICAL declarations:"); for (const [k, v] of Object.entries(logiTot)) console.log("  " + k.padEnd(46), v);
  console.log("Theme / media hooks:"); for (const [k, v] of Object.entries(themeTot)) console.log("  " + k.padEnd(46), v);
}
