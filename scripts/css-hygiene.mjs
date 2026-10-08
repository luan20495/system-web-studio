#!/usr/bin/env node
// CSS hygiene counts for the shared stylesheets (M-068 buttons, M-069 tokens, M-070 duplicates). Read-only; companion of r2-css-count.mjs.
//   node scripts/css-hygiene.mjs [--json] [--top N]
// Reports: (1) hex literals outside token definitions, how often each repeats and whether a token already carries that value;
//          (2) selectors defined more than once, and how many of those have conflicting declarations;
//          (3) the button class systems (.btn / .button / .smallButton / .sendButton / .bx-btn): rules in CSS and uses in TS/TSX.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;
const STYLES = join(ROOT, "packages/ui/src/styles");
const JSON_OUT = process.argv.includes("--json");
const TOP = Number(process.argv[process.argv.indexOf("--top") + 1]) || 25;
const files = readdirSync(STYLES).filter((f) => f.endsWith(".css")).sort();
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "");
const css = Object.fromEntries(files.map((f) => [f, strip(readFileSync(join(STYLES, f), "utf8"))]));

// ---- rules (flat; @media blocks are unwrapped but remembered)
function rules(text) {
  const out = []; let i = 0; const stack = [];
  const re = /([^{};]+)\{|\}|([^{};]+;)/g; let m; let start = 0; const buf = [];
  while ((m = re.exec(text))) {
    if (m[1]) { const sel = m[1].trim(); if (sel.startsWith("@")) { stack.push({ at: sel, rule: false }); } else { stack.push({ sel, rule: true, decl: "" }); } start = re.lastIndex; }
    else if (m[0] === "}") { const top = stack.pop(); if (top?.rule) out.push({ sel: top.sel, decl: top.decl.trim(), at: stack.filter((s) => !s.rule).map((s) => s.at).join(" ") }); }
    else if (m[2]) { const top = stack[stack.length - 1]; if (top?.rule) top.decl += m[2]; }
  }
  void i; void start; void buf;
  return out;
}
const all = [];
for (const f of files) for (const r of rules(css[f])) all.push({ ...r, file: f });
const split = (sel) => sel.split(",").map((s) => s.trim().replace(/\s+/g, " ")).filter(Boolean);
const declMap = (d) => Object.fromEntries(d.split(";").map((x) => x.trim()).filter(Boolean).map((x) => { const k = x.indexOf(":"); return [x.slice(0, k).trim(), x.slice(k + 1).trim()]; }));

// ---- (1) hex
const tokenValue = new Map(); // hex -> [token names]
for (const [f, text] of Object.entries(css)) for (const m of text.matchAll(/(--[\w-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*[;}]/g)) { const k = m[2].toLowerCase(); (tokenValue.get(k) ?? tokenValue.set(k, []).get(k)).push(m[1]); void f; }
const hexUse = new Map();
for (const [f, text] of Object.entries(css)) {
  const noDefs = text.replace(/--[\w-]+\s*:\s*#[0-9a-fA-F]{3,8}\s*;?/g, "");
  for (const m of noDefs.matchAll(/#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b(?![\w-])/g)) { const k = m[0].toLowerCase(); const e = hexUse.get(k) ?? { n: 0, files: new Set() }; e.n++; e.files.add(f); hexUse.set(k, e); }
}
const hexList = [...hexUse].map(([hex, e]) => ({ hex, uses: e.n, files: [...e.files], token: tokenValue.get(hex)?.[0] ?? null })).sort((a, b) => b.uses - a.uses);
const hexTotal = hexList.reduce((n, h) => n + h.uses, 0);
const hexTokenised = hexList.filter((h) => h.token).reduce((n, h) => n + h.uses, 0);

// ---- (2) duplicate selectors (same selector text, same @media context)
const by = new Map();
for (const r of all) for (const s of split(r.sel)) { const k = `${r.at}|${s}`; (by.get(k) ?? by.set(k, []).get(k)).push(r); }
let dupSelectors = 0, conflicting = 0; const dupList = [];
for (const [k, rs] of by) if (rs.length > 1) {
  dupSelectors++;
  const seen = {}; let conflict = false;
  for (const r of rs) for (const [p, v] of Object.entries(declMap(r.decl))) { if (p in seen && seen[p] !== v) conflict = true; seen[p] = v; }
  if (conflict) conflicting++;
  dupList.push({ selector: k.split("|")[1], at: k.split("|")[0], defs: rs.length, files: [...new Set(rs.map((r) => r.file))], conflict });
}

// ---- (3) button systems
const SYS = { ".btn": /\.btn\b/, ".button": /\.button\b/, ".smallButton": /\.smallButton\b/, ".sendButton": /\.sendButton\b/, ".bx-btn": /\.bx-btn\b/ };
const cssRules = Object.fromEntries(Object.keys(SYS).map((k) => [k, all.filter((r) => SYS[k].test(r.sel)).length]));
const tsFiles = []; (function walk(d) { for (const e of readdirSync(d)) { if (["node_modules", ".next", ".test-build", "dist", ".git"].includes(e) || e.startsWith(".")) continue; const p = join(d, e); if (statSync(p).isDirectory()) walk(p); else if (/\.(tsx?)$/.test(e)) tsFiles.push(p); } })(ROOT);
const NAMES = { ".btn": "btn", ".button": "button", ".smallButton": "smallButton", ".sendButton": "sendButton", ".bx-btn": "bx-btn" };
const uses = Object.fromEntries(Object.keys(SYS).map((k) => [k, 0]));
for (const f of tsFiles) { if (/\/tests\//.test(f)) continue; const t = readFileSync(f, "utf8"); for (const m of t.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\}|\{"([^"]*)"\})/g)) { const cls = (m[1] ?? m[2] ?? m[3] ?? "").split(/\s+/); for (const [k, n] of Object.entries(NAMES)) if (cls.includes(n)) uses[k]++; } }

const report = { hex: { total: hexTotal, unique: hexList.length, tokenised: hexTokenised, repeated3plus: hexList.filter((h) => h.uses >= 3).length, repeated3plusNoToken: hexList.filter((h) => h.uses >= 3 && !h.token).length, top: hexList.slice(0, TOP) },
  duplicates: { selectors: dupSelectors, conflicting, list: dupList.filter((d) => d.conflict).slice(0, 80) }, buttons: { cssRules, tsUses: uses } };
if (JSON_OUT) { console.log(JSON.stringify(report, null, 1)); process.exit(0); }
console.log(`HEX outside token definitions: ${hexTotal} uses, ${hexList.length} unique, ${hexTokenised} uses already equal a token's value (${Math.round((hexTokenised / hexTotal) * 100)}%)`);
console.log(`  values used 3+ times: ${report.hex.repeated3plus}, of which with no token: ${report.hex.repeated3plusNoToken}`);
console.log(`  top ${TOP} (uses, hex, token that already carries it):`);
for (const h of hexList.slice(0, TOP)) console.log(`    ${String(h.uses).padStart(4)}  ${h.hex.padEnd(9)} ${(h.token ?? "-").padEnd(18)} ${h.files.join(",")}`);
console.log(`DUPLICATE selectors (same selector, same @media): ${dupSelectors}; with conflicting declarations: ${conflicting}`);
for (const d of dupList.filter((x) => x.conflict).slice(0, 15)) console.log(`    ${d.selector}  ${d.at ? `[${d.at}]` : ""}  x${d.defs} in ${d.files.join(",")}`);
console.log(`BUTTON systems: CSS rules ${JSON.stringify(cssRules)}; className uses in TS/TSX ${JSON.stringify(uses)}`);
