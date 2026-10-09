#!/usr/bin/env node
// C5-R2 audit helper (read-only, no servers): WCAG 2.x contrast ratios for the key token pairs.
//   node scripts/r2-contrast.mjs [rootDir]
// Reads the real :root tokens of packages/ui/src/styles/{factory,globals}.css, then evaluates (1) today's light shell, (2) today's dark builder (.studio),
// (3) three ILLUSTRATIVE brand accents substituted for --f-accent (this is a probe of the token model, NOT a tenant theme contract), and (4) a naive
// "invert the light tokens" dark palette, to show which pairs break first. Thresholds: 4.5 text, 3.0 large text / UI components (WCAG 1.4.3 / 1.4.11).
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.argv[2] ?? new URL("..", import.meta.url).pathname;
const read = (f) => readFileSync(join(ROOT, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const tokens = (css) => { const o = {}; for (const m of css.matchAll(/(--[\w-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\b/g)) o[m[1]] = m[2]; return o; };
const F = tokens(read("packages/ui/src/styles/factory.css"));   // light shell, --f-*
const G = tokens(read("packages/ui/src/styles/globals.css"));   // dark builder, --bg/--panel/...
const hex = (h) => { h = h.replace("#", ""); if (h.length === 3 || h.length === 4) h = [...h].map((c) => c + c).join(""); return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)); };
const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = (h) => { const [r, g, b] = hex(h).map(lin); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
export const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const row = (label, fg, bg, need = 4.5) => { const r = ratio(fg, bg); console.log(`${(r >= need ? "PASS" : "FAIL").padEnd(5)} ${r.toFixed(2).padStart(6)}:1 (need ${need})  ${label.padEnd(58)} ${fg} on ${bg}`); return r >= need; };

console.log("== 1. light shell today (--f-*), from factory.css");
const L = F;
row("--f-text on --f-bg", L["--f-text"], L["--f-bg"]); row("--f-text on --f-panel", L["--f-text"], L["--f-panel"]);
row("--f-muted on --f-bg", L["--f-muted"], L["--f-bg"]); row("--f-muted on --f-panel", L["--f-muted"], L["--f-panel"]);
row("--f-accent (active nav text) on --f-accent-soft", L["--f-accent"], L["--f-accent-soft"]); row("--f-accent as link/text on --f-panel", L["--f-accent"], L["--f-panel"]);
row("--f-accent-ink on --f-accent (primary button)", L["--f-accent-ink"], L["--f-accent"]);
for (const k of ["ok", "warn", "bad", "info"]) row(`--f-${k} on --f-${k}-bg (pill)`, L[`--f-${k}`], L[`--f-${k}-bg`]);
row("--f-placeholder on #fff (input)", L["--f-placeholder"], "#ffffff"); row("--f-disabled-ink on --f-disabled-bg", L["--f-disabled-ink"], L["--f-disabled-bg"], 3);
row("--f-focus ring on --f-panel (UI component)", L["--f-focus"], L["--f-panel"], 3); row("--f-line on --f-panel (border, UI component)", L["--f-line"], L["--f-panel"], 3);

console.log("\n== 2. dark builder today (.studio), from globals.css");
const D = G;
row("--text on --bg", D["--text"], D["--bg"]); row("--text on --panel", D["--text"], D["--panel"]); row("--muted on --panel", D["--muted"], D["--panel"]);
row("--muted on --panel2", D["--muted"], D["--panel2"]); row("--accent on --panel", D["--accent"], D["--panel"]); row("--blue on --panel", D["--blue"], D["--panel"]);
row("--line on --panel (border, UI component)", D["--line"], D["--panel"], 3);
row("hard-coded #9cc3ff link on --panel (.studio a)", "#9cc3ff", D["--panel"]); row("hard-coded #ff9a92 error on --panel (.studio .formError)", "#ff9a92", D["--panel"]);
console.log("   (the same dark-surface colours on the LIGHT shell, i.e. what happens if the scope class is missing)");
row("#ff9a92 (dark error) on --f-panel", "#ff9a92", L["--f-panel"]); row("#9cc3ff (dark link) on --f-panel", "#9cc3ff", L["--f-panel"]);

console.log("\n== 3. ILLUSTRATIVE brand accents replacing --f-accent (probe only; one token carries THREE roles: button fill, text on white, text on accent-soft)");
const soft = (acc) => { const [r, g, b] = hex(acc); const mix = (c) => Math.round(c * 0.08 + 255 * 0.92); return "#" + [r, g, b].map(mix).map((x) => x.toString(16).padStart(2, "0")).join(""); };
for (const [name, acc] of [["brand blue #1f4fd8", "#1f4fd8"], ["brand yellow #f2b705", "#f2b705"], ["brand green #12a150", "#12a150"], ["brand orange #f26a1b", "#f26a1b"]]) {
  console.log(`-- ${name}, derived soft ${soft(acc)}`);
  row("white ink on accent (button)", "#ffffff", acc); row("accent as text on panel (links, headings)", acc, L["--f-panel"]); row("accent as text on derived accent-soft (active nav)", acc, soft(acc));
  row("accent as UI boundary/focus on panel", acc, L["--f-panel"], 3);
}

console.log("\n== 4. naive dark palette made by inverting the light shell tokens (what a first 'dark mode' pass tends to produce)");
const inv = { bg: "#0f1115", panel: "#171a21", text: "#e6e8ee", muted: "#8b93a3", accent: "#4f46e5", accentSoft: "#1e1f4a", ok: "#067647", okBg: "#0f2a1f", bad: "#b42318", badBg: "#2a1411" };
row("text on bg", inv.text, inv.bg); row("muted (#8b93a3) on panel", inv.muted, inv.panel); row("UNCHANGED --f-accent #4f46e5 as text on dark panel", inv.accent, inv.panel);
row("UNCHANGED --f-accent on dark accent-soft (active nav)", inv.accent, inv.accentSoft); row("UNCHANGED --f-ok #067647 on dark ok-bg", inv.ok, inv.okBg); row("UNCHANGED --f-bad #b42318 on dark bad-bg", inv.bad, inv.badBg);
row("lightened accent #a5b4fc as text on dark panel", "#a5b4fc", inv.panel);

console.log("\n== 5. preview document (iframe, lib/preview-document.ts palette) — fixed light palette, not token driven");
row("body #10303c on #fff", "#10303c", "#ffffff"); row(".links #58717a on #fff", "#58717a", "#ffffff"); row(".logo #078675 on #fff", "#078675", "#ffffff");
row("form label #46636b on #fff", "#46636b", "#ffffff"); row("button #fff on #0b6f63", "#ffffff", "#0b6f63");
