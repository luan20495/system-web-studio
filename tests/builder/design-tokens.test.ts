// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * M-026 / M-027 / M-085 / M-087 / M-086 / M-117 / M-070-lite: the colour tokens of the light theme are checked by COMPUTED contrast ratios (WCAG 2.x relative luminance), read from the
 * stylesheets themselves, so a token edit that breaks 3:1 / 4.5:1 fails here. Plus guards over how the CSS uses them.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const dir = join(root, "packages/ui/src/styles");
const files = readdirSync(dir).filter((f) => f.endsWith(".css"));
const css = Object.fromEntries(files.map((f) => [f, readFileSync(join(dir, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "")]));
const all = Object.values(css).join("\n");

// ---------------------------------------------------------------- colour maths
const hex = (h: string): [number, number, number] => { const s = h.replace("#", ""); const f = s.length === 3 ? s.split("").map((c) => c + c).join("") : s; return [0, 2, 4].map((i) => parseInt(f.slice(i, i + 2), 16) / 255) as [number, number, number]; };
const lin = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const lum = (h: string) => { const [r, g, b] = hex(h); return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b); };
export const ratio = (a: string, b: string) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

/** first definition of a custom property across the stylesheets, with var() references followed */
const tokens = new Map<string, string>();
for (const m of all.matchAll(/(--[\w-]+)\s*:\s*([^;}]+)/g)) if (!tokens.has(m[1])) tokens.set(m[1], m[2].trim());
const tok = (name: string): string => { let v = tokens.get(name); assert.ok(v, `token ${name} is not defined`); for (let i = 0; v!.startsWith("var(") && i < 5; i++) v = tokens.get(/var\((--[\w-]+)/.exec(v!)![1])!; assert.match(v!, /^#[0-9a-fA-F]{3,6}$/, `${name} = ${v}`); return v!; };

const LIGHT_SURFACES = ["--f-bg", "--f-panel", "--f-line2", "--f-accent-soft", "--f-disabled-bg", "--f-ok-bg", "--f-bad-bg", "--f-warn-bg", "--f-info-bg"].map((n) => [n, tok(n)] as const).concat([["white", "#ffffff"]]);

test("M-026: the light focus ring is >= 3:1 on EVERY light surface (it was #67b8ff: 2.13:1 on white; the checkbox / radio halo #c7d2fe: 1.49:1)", () => {
  const ring = tok("--f-focus");
  for (const [n, c] of LIGHT_SURFACES) assert.ok(ratio(ring, c) >= 3, `focus ring ${ring} on ${n} ${c}: ${ratio(ring, c).toFixed(2)}`);
  assert.match(tokens.get("--ui-ring") ?? "", /#67b8ff/);                                                          // the default (dark pages) keeps the bright ring
  assert.match(css["factory.css"], /\.shell,\.authPage,\.splash,\.wsError\{--ui-ring:var\(--f-focus\)\}/);       // light pages use the indigo one
  assert.match(css["responsive.css"], /outline:\s*2px solid var\(--ui-ring, #67b8ff\)/);                         // the global rule reads the token
});
test("M-026: the ring on the dark surfaces is also >= 3:1 (dark builder pages, the dark sidebar)", () => {
  for (const s of ["--bg", "--panel", "--panel2"]) assert.ok(ratio("#67b8ff", tok(s)) >= 3, `#67b8ff on ${s}`);
  assert.ok(ratio("#a5b4fc", "#0f1115") >= 3); assert.match(css["factory.css"], /\.sidebar\.dark\{--ui-ring:#a5b4fc\}/);
});
test("M-026: no FOCUS rule still draws the 1.49:1 pale halo", () => {
  for (const [f, text] of Object.entries(css)) for (const m of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) if (/focus/.test(m[1])) assert.doesNotMatch(m[2], /outline:[^;]*#c7d2fe/, `${f}: ${m[1].trim().slice(0, 80)}`);
});

test("M-027: --f-control-border is >= 3:1 on every light surface a control sits on (it was --f-line: 1.24:1 on white)", () => {
  const b = tok("--f-control-border");
  for (const [n, c] of LIGHT_SURFACES.filter(([n]) => !/ok-bg|bad-bg|warn-bg|info-bg/.test(n))) assert.ok(ratio(b, c) >= 3, `control border ${b} on ${n}: ${ratio(b, c).toFixed(2)}`);
  assert.ok(ratio(tok("--f-line"), "#ffffff") < 3, "the decorative hairline stays light: it must not be used for controls");
});
test("M-027: inputs, selects, textareas, the picker, key / slug / search wrappers and outlined buttons use the control border; the switch OFF track does too", () => {
  const f = css["factory.css"]; const rule = (sel: string) => { const m = new RegExp(`(?:^|\\})\\s*${sel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*\\{([^{}]*)\\}`, "m").exec(f); assert.ok(m, `rule ${sel}`); return m![1]; };
  for (const sel of [".shell select,.shell input,.authPage input", ".shell textarea,.authPage textarea", ".xp-pickerBtn", ".xp-keyInput", ".xp-slugInput", ".xp-search", ".btn"]) assert.match(rule(sel), /border:1px solid var\(--f-control-border\)/, sel);
  assert.match(rule(".xp-switch"), /background:var\(--f-control-border\)/);
  const b = tok("--f-control-border");
  assert.ok(ratio(b, "#ffffff") >= 3 && ratio(b, tok("--f-bg")) >= 3, "switch off track vs the surface");
  assert.ok(ratio("#ffffff", b) >= 3, "white knob on the off track");
  assert.ok(ratio("#ffffff", tok("--f-accent")) >= 3 && ratio(tok("--f-accent"), "#ffffff") >= 4.5, "on track / primary button");
  assert.match(rule(".btn.danger"), /border-color:var\(--f-bad\)/); assert.ok(ratio(tok("--f-bad"), "#ffffff") >= 4.5);
});

test("text pairs that carry meaning: status pills >= 4.5:1, muted text >= 4.5:1 on its surfaces, accent text on white >= 4.5:1", () => {
  for (const t of ["ok", "warn", "bad", "info"]) assert.ok(ratio(tok(`--f-${t}`), tok(`--f-${t}-bg`)) >= 4.5, `pill ${t}`);
  assert.ok(ratio("#344054", "#f2f4f7") >= 4.5, "pill muted");
  for (const [n, c] of LIGHT_SURFACES.filter(([n]) => /bg|panel|white/.test(n) && !/ok|bad|warn|info/.test(n))) assert.ok(ratio(tok("--f-muted"), c) >= 4.5, `muted on ${n}`);
  assert.ok(ratio(tok("--f-accent"), "#ffffff") >= 4.5);
});

test("M-085: scroll containers keep a focused control clear of a sticky footer (WCAG 2.4.11)", () => {
  assert.match(css["factory.css"], /\.page\{[^}]*scroll-padding-bottom:72px/);
  assert.match(css["factory.css"], /\.modalBody\{[^}]*scroll-padding-bottom:72px/);
});

test("M-070-lite: every custom property used in the stylesheets is defined somewhere (--danger / --warn were not)", () => {
  const defined = new Set(tokens.keys());
  const tsx = ["features", "packages", "apps"].flatMap((d) => (function walk(p: string): string[] { return readdirSync(p, { withFileTypes: true }).flatMap((e) => e.name === "node_modules" ? [] : e.isDirectory() ? walk(join(p, e.name)) : /\.tsx$/.test(e.name) ? [join(p, e.name)] : []); })(join(root, d)));
  for (const f of tsx) for (const m of readFileSync(f, "utf8").matchAll(/(--[\w-]+)\s*[:=]/g)) defined.add(m[1]);
  const missing = new Set<string>(); for (const m of all.matchAll(/var\((--[\w-]+)/g)) if (!defined.has(m[1])) missing.add(m[1]);
  assert.deepEqual([...missing], []);
  assert.equal(tokens.get("--danger"), "#e5484d"); assert.equal(tokens.get("--warn"), "#d9a21b");
});

test("M-087: every 100vh has a 100dvh twin right after it (a mobile URL bar no longer pushes the toolbar off screen); wide screens get a readable column", () => {
  for (const [f, text] of Object.entries(css)) for (const m of text.matchAll(/((?:min-)?height)\s*:\s*100vh\s*;?([^;}]*)/g)) if (f !== "builder.css") assert.match(m[2], new RegExp(`${m[1]}\\s*:\\s*100dvh`), `${f}: ${m[0]}`);
  assert.match(css["factory.css"], /@media\(min-width:1280px\)\{\.page\{padding-inline:max\(28px,calc\(\(100% - var\(--ui-content-max\)\)\/2\)\)\}\}/);
  assert.equal(tokens.get("--ui-content-max"), "1360px");
});

test("M-087: breakpoint RATCHET: the set of media-query thresholds may shrink, not grow (it was 14 max-width + 4 min-width values at the audit); the named three agree with breakpoints.ts", () => {
  const max = new Set(Array.from(all.matchAll(/@media[^{]*?max-width\s*:\s*(\d+)px/g), (m) => m[1])); const min = new Set(Array.from(all.matchAll(/@media[^{]*?min-width\s*:\s*(\d+)px/g), (m) => m[1]));
  assert.ok(max.size <= 11, `max-width thresholds: ${[...max].join(", ")}`); assert.ok(min.size <= 4, `min-width thresholds: ${[...min].join(", ")}`);
  const bp = readFileSync(join(root, "packages/ui/src/breakpoints.ts"), "utf8");
  for (const n of ["900", "760", "600"]) { assert.match(bp, new RegExp(`\\b${n}\\b`)); assert.ok(max.has(n), `${n}px is used in CSS`); }
  assert.match(readFileSync(join(root, "packages/ui/src/NavDrawer.tsx"), "utf8"), /MQ\.tablet/);
});

test("M-033: at <= 600px the provider row wraps and its actions take their own line (the main column collapsed to width 0 next to a 244px action group at 390px)", () => {
  assert.match(css["factory.css"], /@media\(max-width:600px\)\{\.providerItem \.xp-provRow\{flex-wrap:wrap\}\.xp-provMain\{flex:1 1 220px\}\.xp-provActions\{flex:1 1 100%/);
});

test("M-086: forced-colors fallbacks exist for the states that lived only in a background colour (switch, pills, selected radio card, tabs, dialogs, focus)", () => {
  const m = /@media\(forced-colors:active\)\{([\s\S]*?)\n\}/.exec(css["factory.css"]); assert.ok(m);
  for (const s of [".xp-switch.on", ".pill", ".xp-radioCard.selected", ".tabs>.active", ".modalBody", ":focus-visible"]) assert.ok(m![1].includes(s), `forced-colors rule for ${s}`);
  assert.doesNotMatch(m![1], /#[0-9a-fA-F]{3,6}\b/, "system colours only");
});

test("M-117: a link inside running text carries a non-colour cue (underline) through one token", () => {
  assert.equal(tokens.get("--ui-link-line"), "underline");
  assert.match(css["factory.css"], /text-decoration:var\(--ui-link-line\)/);
});

test("harness fidelity: a browser harness that loads factory.css also loads ui.css after it, as every real layout does (a harness without it tests a stylesheet set the product never ships)", () => {
  const dir = join(root, "tests/browser");
  const list = (d: string): string[] => readdirSync(d, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? list(join(d, e.name)) : /\.tsx?$/.test(e.name) ? [join(d, e.name)] : []);
  for (const f of list(dir)) {
    const t = readFileSync(f, "utf8"); if (!/styles\/factory\.css/.test(t) && !/\.\/admin-css/.test(t)) continue;
    if (/\.\/admin-css/.test(t)) continue;                                   // admin-css.ts is itself checked against the real layouts (shared-ui.test.tsx)
    assert.ok(/styles\/ui\.css/.test(t), `${f.replace(root + "/", "")} loads factory.css but not ui.css`);
    assert.ok(t.indexOf("styles/ui.css") > t.indexOf("styles/factory.css"), `${f.replace(root + "/", "")}: ui.css must come after factory.css`);
  }
});

// ---------------------------------------------------------------------------------------------------------------------------------------------- M-068: one button vocabulary
test("every class of the vocabulary is styled in factory.css (and documented in its header), in the light skin and the dark `.studio` skin", () => {
  const f = css["factory.css"];
  for (const sel of [".btn", ".btn.primary", ".btn.danger", ".btn.ghost", ".btn.sm", ".btn.icon", ".btn.block", ".studio .btn", ".studio .btn.primary", ".studio .btn.ghost", ".studio .btn.sm", ".studio .btn.danger"]) assert.ok(f.includes(sel + "{") || f.includes(sel + ","), sel);
  const raw = readFileSync(join(dir, "factory.css"), "utf8");
  assert.match(raw, /BUTTON VOCABULARY \(M-068\)/); assert.match(raw, /\.smallButton -> \.btn\.sm/); assert.match(raw, /\.bx-btn \(builder\.css\) -> \.btn/);
});

test("ratchet: the legacy button classes (.button / .smallButton / .sendButton / .bx-btn) may not be used in MORE places; new code uses <Button> / .btn", () => {
  const BASE: Record<string, number> = { button: 73, smallButton: 72, sendButton: 3, "bx-btn": 56 };
  const uses: Record<string, string[]> = { button: [], smallButton: [], sendButton: [], "bx-btn": [] };
  const walk = (d: string) => { for (const e of readdirSync(d, { withFileTypes: true })) { if (["node_modules", ".next", ".test-build", "dist", "tests"].includes(e.name) || e.name.startsWith(".")) continue; const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.tsx?$/.test(e.name)) { const t = readFileSync(p, "utf8"); for (const m of t.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\}|\{"([^"]*)"\})/g)) for (const c of (m[1] ?? m[2] ?? m[3] ?? "").split(/\s+/)) if (c in uses) uses[c].push(p.replace(root + "/", "")); } } };
  for (const d of ["features", "apps", "packages", "components", "lib", "app"]) { try { walk(join(root, d)); } catch { /* optional */ } }
  for (const [k, max] of Object.entries(BASE)) {
    if (uses[k].length > max) assert.fail(`.${k}: ${uses[k].length} > baseline ${max}. Use <Button> / .btn instead. Files: ${[...new Set(uses[k])].join(", ")}`);
    if (uses[k].length < max) console.log(`note: .${k} is ${uses[k].length}, baseline ${max}: lower it`);
  }
});

test("M-105: `.stack` is defined (it was used in 10 places and defined nowhere); a fieldset.stack has no UA border; its legend is styled", () => {
  const f = css["factory.css"];
  assert.match(f, /\.stack\{display:grid;gap:var\(--sp-3\)\}/);
  assert.match(f, /fieldset\.stack\{border:0;margin:0;padding:0;min-width:0\}/);
  assert.match(f, /fieldset\.stack>legend\{[^}]*font-size:var\(--f-fs-md\)[^}]*text-transform:uppercase/);
  assert.match(f, /\.modalBody>h2\{margin:0;font-size:18px/);
});

test("M-070: the visually-hidden class is styled under both spellings in use (`sr-only` had no rule: a table caption rendered visibly); the 16px checkbox size that the 24px target rule always overrode is gone", () => {
  assert.match(css["http.css"], /\.srOnly,\.sr-only\{position:absolute;width:1px;height:1px/);
  const rule = /\.shell input\[type=checkbox\],\.shell input\[type=radio\]\{([^}]*)\}/.exec(css["factory.css"]); assert.ok(rule);
  assert.doesNotMatch(rule![1], /width|height/);
  assert.match(css["factory.css"], /\.shell input\[type=checkbox\],\.shell input\[type=radio\],\.adminModal input\[type=checkbox\],\.adminModal input\[type=radio\]\{width:24px;height:24px;min-height:24px\}/);
});

test("M-069: radius and type scale are tokens with their original values; the light sheets (factory / ui / http) use them instead of re-typing the scale values", () => {
  const SCALE: Record<string, string> = { "--f-r-xs": "6px", "--f-r-sm": "8px", "--f-r": "10px", "--f-r-lg": "12px", "--f-r-pill": "999px", "--f-fs-xs": "11px", "--f-fs-sm": "12px", "--f-fs-md": "13px", "--f-fs-base": "14px" };
  for (const [n, v] of Object.entries(SCALE)) assert.equal(tokens.get(n), v, n);
  for (const f of ["factory.css", "ui.css", "http.css"]) {
    const raw = Array.from(css[f].matchAll(/(border-radius:(?:6|8|10|12|999)px|font-size:(?:11|12|13|14)px)(?=[;}!])/g), (m) => m[1]);
    assert.deepEqual(raw, [], `${f}: use var(--f-r-*) / var(--f-fs-*)`);
  }
});
