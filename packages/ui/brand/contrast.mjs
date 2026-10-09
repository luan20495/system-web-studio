#!/usr/bin/env node
// XWEB brand: MEASURED contrast of the semantic colour tokens, light AND dark (WCAG 2.x relative luminance; 4.5 text, 3.0 UI components / graphics).
//   node packages/ui/brand/contrast.mjs          table, exit 1 when a pair fails
//   node packages/ui/brand/contrast.mjs --json   machine-readable (tests/builder/brand.test.ts runs it)
// Reads the token blocks "semantic, LIGHT" and "semantic, DARK" of packages/ui/src/styles/factory.css: the numbers are of the shipped values, not of a copy.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(new URL(".", import.meta.url).pathname, "../../..");
const css = readFileSync(join(ROOT, "packages/ui/src/styles/factory.css"), "utf8");
const block = (marker) => { const at = css.indexOf(marker); if (at < 0) throw new Error(`token block "${marker}" not found`); const open = css.indexOf("{", at); return css.slice(open + 1, css.indexOf("}", open)); };
const parse = (text) => Object.fromEntries(Array.from(text.matchAll(/--color-([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\b/g), (m) => [m[1], m[2].toLowerCase()]));
const THEMES = { light: parse(block("/* semantic, LIGHT")), dark: parse(block("/* semantic, DARK")) };

const rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
const lin = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const lum = (h) => { const [r, g, b] = rgb(h).map(lin); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
export const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

/** [label, foreground token, background token, minimum] — the pairs the brand brief names, plus the brand graphics */
const PAIRS = [
  ["text-primary / background", "text", "bg", 4.5], ["text-primary / surface", "text", "surface", 4.5], ["text-primary / surface-raised", "text", "surface-raised", 4.5],
  ["text-secondary / background", "text-secondary", "bg", 4.5], ["text-secondary / surface", "text-secondary", "surface", 4.5],
  ["text-muted / background", "text-muted", "bg", 4.5], ["text-muted / surface", "text-muted", "surface", 4.5],
  ["button text / primary", "primary-ink", "primary", 4.5],
  ["link / background", "link", "bg", 4.5], ["link / surface", "link", "surface", 4.5], ["primary as text / surface", "primary", "surface", 4.5],
  ["error text / error surface", "danger", "danger-bg", 4.5], ["warning text / warning surface", "warning", "warning-bg", 4.5],
  ["success text / success surface", "success", "success-bg", 4.5], ["info text / info surface", "info", "info-bg", 4.5],
  ["selected state text / selected", "selected-ink", "selected", 4.5],
  ["focus indicator / background", "focus", "bg", 3], ["focus indicator / surface", "focus", "surface", 3], ["focus indicator / selected", "focus", "selected", 3],
  ["control border (border-strong) / surface", "border-strong", "surface", 3], ["control border (border-strong) / background", "border-strong", "bg", 3],
  ["banner eyebrow (primary) / primary-soft", "primary", "primary-soft", 4.5], ["banner body (text-secondary) / primary-soft", "text-secondary", "primary-soft", 4.5],
  ["logo tile / background (graphic)", "brand-tile", "bg", 3], ["logo glyph / tile (graphic)", "brand-glyph", "brand-tile", 3], ["logo accent glyph / tile (graphic)", "brand-tile-accent", "brand-tile", 3],
  ["wordmark ink / background", "brand-ink", "bg", 4.5], ["wordmark ink / surface", "brand-ink", "surface", 4.5],
];

const rows = [];
for (const [theme, t] of Object.entries(THEMES)) for (const [label, fg, bg, min] of PAIRS) {
  if (!t[fg] || !t[bg]) { rows.push({ theme, label, fg, bg, min, ratio: 0, pass: false, error: `token missing: ${t[fg] ? bg : fg}` }); continue; }
  const r = ratio(t[fg], t[bg]); rows.push({ theme, label, fg: `${fg} ${t[fg]}`, bg: `${bg} ${t[bg]}`, min, ratio: Math.round(r * 100) / 100, pass: r >= min });
}
if (process.argv.includes("--json")) console.log(JSON.stringify(rows));
else {
  for (const r of rows) console.log(`${r.pass ? "PASS" : "FAIL"}  ${r.theme.padEnd(5)}  ${r.ratio.toFixed(2).padStart(6)}:1 (min ${r.min})  ${r.label.padEnd(46)} ${r.fg} on ${r.bg}${r.error ? "  " + r.error : ""}`);
  console.log(`\n${rows.filter((r) => r.pass).length}/${rows.length} pairs pass`);
}
process.exit(rows.every((r) => r.pass) ? 0 : 1);
