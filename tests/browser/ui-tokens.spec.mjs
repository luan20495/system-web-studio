// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// Computed styles of the shared CSS: focus ring (M-026), control borders (M-027), link cue (M-117), provider row at 390px (M-033), wide-screen column (M-087), forced colors (M-086).
//   ESBUILD_DIR=<dir with esbuild> node tests/browser/build-harness.mjs
//   CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/ui-tokens.spec.mjs
import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { harnessOrigin, launch } from "./lib/spec.mjs";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const AXE = require.resolve("axe-core/axe.min.js");
const BASE = harnessOrigin() + "/";
const SHOTS = process.env.SHOT_DIR; if (SHOTS) mkdirSync(SHOTS, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + String(detail).replace(/\s+/g, " ").slice(0, 220) : ""}`); };
const browser = await launch({ headless: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function open(page, w = 1000, h = 900, opts = {}) {
  const p = await browser.newPage({ viewport: { width: w, height: h }, ...opts }); p.setDefaultTimeout(3000);
  p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(BASE + page); await p.waitForSelector("#root > *", { timeout: 8000 }).catch(() => undefined); return p;
}
const shot = async (p, name) => { if (SHOTS) await p.screenshot({ path: join(SHOTS, name), fullPage: true }); };
// WCAG contrast of two computed rgb() strings, evaluated in the page
const RATIO = `(a, b) => { const n = (s) => (s.match(/[\\d.]+/g) || []).slice(0, 3).map(Number); const l = (c) => { const [r, g, bl] = c.map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * bl; }; const x = l(n(a)), y = l(n(b)); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }`;
const ratioOf = (p, a, b) => p.evaluate(`(${RATIO})(${JSON.stringify(a)}, ${JSON.stringify(b)})`);
const style = (p, sel, props) => p.evaluate(([s, ps]) => { const el = document.querySelector(s); const c = getComputedStyle(el); return Object.fromEntries(ps.map((k) => [k, c[k]])); }, [sel, props]);

try {
  { const p = await open("ui-widgets.html");
    // M-026: keyboard focus ring colour on every control type, against the white card
    const white = "rgb(255, 255, 255)";
    for (const [sel, name] of [["#c-check", "checkbox"], ["#c-radio", "radio"], ["#c-text", "text input"], ["#c-select", "select"], ["#c-area", "textarea"], ["#c-btn", "button"], ["#c-primary", "primary button"], ["#p-link", "link"], ["[role=switch]", "switch"]]) {
      await p.focus("#c-text"); await p.keyboard.press("Shift+Tab"); await p.keyboard.press("Tab"); // make the page "keyboard modality" so :focus-visible applies
      await p.locator(sel).first().focus(); await p.keyboard.press("Shift+Tab"); await p.keyboard.press("Tab");
      const st = await style(p, sel === "[role=switch]" ? "[role=switch]" : sel, ["outlineColor", "outlineStyle", "outlineWidth", "borderColor"]);
      const edge = st.outlineStyle !== "none" ? st.outlineColor : st.borderColor;
      const r = await ratioOf(p, edge, white);
      check(`M-026 ${name}: the keyboard focus indicator is >= 3:1 on white`, st.outlineStyle !== "none" && parseFloat(st.outlineWidth) >= 2 && r >= 3, `${st.outlineStyle} ${st.outlineWidth} ${st.outlineColor} ratio ${r.toFixed(2)}`);
    }
    // M-027: resting borders
    for (const [sel, name] of [["#c-text", "text input"], ["#c-select", "select"], ["#c-area", "textarea"], ["#c-btn", "outlined button"], ["button.xp-pickerBtn", "picker"]]) {
      const st = await style(p, sel, ["borderTopColor", "borderTopWidth"]); const r = await ratioOf(p, st.borderTopColor, white);
      check(`M-027 ${name}: the resting border is >= 3:1 on white (it was 1.24:1)`, parseFloat(st.borderTopWidth) >= 1 && r >= 3, `${st.borderTopColor} ratio ${r.toFixed(2)}`);
    }
    { const st = await style(p, "[role=switch][aria-checked=false]", ["backgroundColor"]); const r = await ratioOf(p, st.backgroundColor, white); check("M-027 switch OFF track is >= 3:1 on white", r >= 3, `${st.backgroundColor} ${r.toFixed(2)}`); }
    { const st = await style(p, "#c-danger", ["borderTopColor", "color"]); check("M-027 outlined danger button border is >= 3:1", (await ratioOf(p, st.borderTopColor, white)) >= 3, st.borderTopColor); }
    // M-117
    const deco = await style(p, "#td-link", ["textDecorationLine"]);
    check("M-117 a link inside running table text (`.table a` used to be text-decoration:none) is underlined: a cue that is not colour", deco.textDecorationLine.includes("underline"), deco.textDecorationLine);
    await p.addScriptTag({ path: AXE });
    const v = await p.evaluate(async () => (await window.axe.run("body", { resultTypes: ["violations"] })).violations.filter((x) => x.impact === "serious" || x.impact === "critical").map((x) => `${x.id}: ${x.nodes[0].target.join(" ")}`));
    check("M-117 axe (serious+critical) on the page, including link-in-text-block (table cell and paragraph)", v.length === 0, v.join(" ; "));
    await shot(p, "tokens-light.png"); await p.close(); }

  // M-033: provider row at 390px
  { const p = await open("ui-widgets.html", 390, 800);
    const g = await p.evaluate(() => { const m = document.querySelector("#prow .xp-provMain").getBoundingClientRect(), a = document.querySelector("#pact").getBoundingClientRect(), r = document.querySelector("#prow").getBoundingClientRect(); return { main: m.width, mainBottom: m.bottom, actTop: a.top, actW: a.width, row: r.width, scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }; });
    check("M-033 at 390px the provider's main column keeps a readable width (it collapsed to ~0 next to the 244px actions)", g.main >= 200, JSON.stringify(g));
    check("M-033 the actions wrap onto their own line below the name", g.actTop >= g.mainBottom - 1, JSON.stringify(g));
    check("M-033 no horizontal scroll at 390px", g.scroll <= g.client, JSON.stringify(g));
    await shot(p, "tokens-390.png"); await p.close(); }

  // M-087: a readable column on a very wide screen; 100dvh pairs
  { const p = await open("ui-route.html", 1900, 900);
    const g = await p.evaluate(() => { const m = document.querySelector("main"); const cs = getComputedStyle(m); const inner = m.firstElementChild.getBoundingClientRect(); return { pl: parseFloat(cs.paddingLeft), pr: parseFloat(cs.paddingRight), w: m.clientWidth, content: inner.width, shell: document.querySelector(".shell").getBoundingClientRect().height, vh: innerHeight }; });
    check("M-087 at 1900px the page content is a <= 1360px column (it stretched to the full width)", g.content <= 1361 && g.pl > 28, JSON.stringify(g));
    check("M-087 at 1280..1359px the padding stays the normal 28px", await (async () => { const q = await open("ui-route.html", 1300, 900); const pl = await q.evaluate(() => parseFloat(getComputedStyle(document.querySelector("main")).paddingLeft)); await q.close(); return pl === 28 || pl <= 30; })());
    check("M-087 the shell fills exactly the dynamic viewport (100dvh, with a 100vh fallback line before it)", Math.abs(g.shell - g.vh) <= 1, JSON.stringify(g));
    await p.close(); }

  // M-086: forced colors
  { const p = await open("ui-widgets.html", 1000, 900, {}); await p.emulateMedia({ forcedColors: "active" }); await sleep(100);
    const off = await style(p, "[role=switch][aria-checked=false]", ["backgroundColor", "borderTopWidth", "borderTopColor"]);
    const on = await style(p, "[role=switch][aria-checked=true]", ["backgroundColor", "borderTopWidth"]);
    check("M-086 forced colors: the switch ON and OFF differ without relying on the author colours", off.backgroundColor !== on.backgroundColor && parseFloat(off.borderTopWidth) >= 2, JSON.stringify({ off, on }));
    const pill = await style(p, "#pills .pill", ["borderTopWidth", "borderTopStyle"]);
    check("M-086 forced colors: a status pill keeps a visible edge", parseFloat(pill.borderTopWidth) >= 1 && pill.borderTopStyle !== "none", JSON.stringify(pill));
    await p.locator(".xp-radioCard").nth(1).locator("input").check({ force: true }).catch(() => undefined);
    const sel = await p.evaluate(() => { const b = document.querySelector(".xp-radioCard.selected .xp-radioBody"); const c = b && getComputedStyle(b); return c ? { w: c.borderTopWidth, s: c.borderTopStyle } : null; });
    check("M-086 forced colors: the selected radio card has a 2px border (not only a background)", !!sel && parseFloat(sel.w) >= 2 && sel.s !== "none", JSON.stringify(sel));
    const tab = await style(p, '[role=tab][aria-selected=true]', ["borderBottomWidth"]);
    check("M-086 forced colors: the selected tab has a thick underline", parseFloat(tab.borderBottomWidth) >= 3, JSON.stringify(tab));
    const btn = await style(p, "#c-btn", ["borderTopWidth", "borderTopStyle"]); check("M-086 forced colors: buttons keep their border", parseFloat(btn.borderTopWidth) >= 1 && btn.borderTopStyle !== "none");
    await shot(p, "tokens-forced-colors.png"); await p.close(); }

  // M-026 on the dark Studio page: the ring is the bright one and passes against the dark surface
  { const p = await open("ui-widgets.html?dark");
    await p.focus("#c-text"); await p.keyboard.press("Shift+Tab"); await p.keyboard.press("Tab");
    const st = await style(p, "#c-text", ["outlineColor", "outlineStyle"]);
    const bg = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
    check("M-026 dark builder page: the focus ring is >= 3:1 on the dark surface", st.outlineStyle !== "none" && (await ratioOf(p, st.outlineColor, bg)) >= 3, `${st.outlineColor} on ${bg}`);
    await shot(p, "tokens-dark.png"); await p.close(); }
} catch (e) { check("scenario aborted", false, e.message); }
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\nui-tokens: ${results.length - failed.length}/${results.length} passed  (HARNESS, NOT REAL BACKEND)`);
process.exit(failed.length ? 1 : 0);
