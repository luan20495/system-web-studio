// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// XWEB brand system (C5-S3): the brand page (ui-brand.html, light and ?dark) at 360 390 430 600 768 1024 1280 1440 1920 px:
// no horizontal scroll, logo sizes and aspect, lockup per portal, banner safe text area and media drop under 600px, the auth card fits,
// computed contrast of the brand text on its real backgrounds, axe 0 serious / critical. SHOT_DIR=<dir> keeps a full-page screenshot per width and theme.
//   CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/ui-brand.spec.mjs
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
const WIDTHS = [360, 390, 430, 600, 768, 1024, 1280, 1440, 1920];
const RATIO = `(a, b) => { const n = (s) => (s.match(/[\\d.]+/g) || []).slice(0, 3).map(Number); const l = (c) => { const [r, g, bl] = c.map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * bl; }; const x = l(n(a)), y = l(n(b)); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }`;
const browser = await launch({ headless: true });
try {
  for (const theme of ["light", "dark"]) for (const w of WIDTHS) {
    const p = await browser.newPage({ viewport: { width: w, height: 900 }, reducedMotion: "reduce" }); p.setDefaultTimeout(4000);
    const errors = []; p.on("pageerror", (e) => errors.push(e.message));
    await p.goto(BASE + "ui-brand.html" + (theme === "dark" ? "?dark" : "")); await p.waitForSelector("#root > *", { timeout: 8000 });
    const g = await p.evaluate(() => {
      const r = (s) => document.querySelector(s)?.getBoundingClientRect();
      const logo = r("#logos .xp-brandLogo"); const banners = [...document.querySelectorAll(".xp-banner")].map((b) => ({ w: b.getBoundingClientRect().width, text: b.querySelector(".xp-bannerText").getBoundingClientRect().width, media: getComputedStyle(b.querySelector(".xp-bannerMedia")).display, right: b.getBoundingClientRect().right }));
      const lockups = [...document.querySelectorAll("#lockups .xp-brandLockup")].map((l) => ({ portal: l.dataset.portal, logo: l.querySelector("svg").getAttribute("aria-label"), context: l.querySelector(".xp-brandContext").textContent }));
      return { scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth, logoW: logo.width, logoH: logo.height, auth: r("#auth .authPanel"), banners, lockups,
        clipped: [...document.querySelectorAll(".xp-bannerTitle, .authPanel h2, .xp-brandContext")].filter((e) => e.scrollWidth > e.clientWidth + 1).length };
    });
    const tag = `${theme} ${w}px`;
    check(`${tag}: no horizontal scroll`, g.scroll <= g.client, `${g.scroll} > ${g.client}`);
    check(`${tag}: the horizontal logo keeps its 136:32 aspect at 32px`, Math.abs(g.logoH - 32) < 0.5 && Math.abs(g.logoW - 136) < 0.5, `${g.logoW}x${g.logoH}`);
    check(`${tag}: one logo family in the three portal lockups, the portal named in words`, g.lockups.length === 3 && g.lockups.every((l) => l.logo === "Xweb") && g.lockups.map((l) => l.context).join("|") === "Platform|Quản trị công ty|Studio", JSON.stringify(g.lockups));
    check(`${tag}: the auth card fits the viewport (16px gutters at most)`, g.auth.left >= 0 && g.auth.right <= w, JSON.stringify(g.auth));
    check(`${tag}: banners: text column >= 280px wide, media ${w <= 600 ? "dropped" : "shown"}, nothing past the edge`, g.banners.every((b) => b.text >= Math.min(280, b.w - 50) && b.media === (w <= 600 ? "none" : "grid") && b.right <= w), JSON.stringify(g.banners));
    check(`${tag}: no clipped title / context label`, g.clipped === 0, String(g.clipped));
    check(`${tag}: no page error`, errors.length === 0, errors.join(" ; "));
    if (w === 390 || w === 1280) {
      const pairs = await p.evaluate((RATIO) => {
        const ratio = eval(RATIO); // the backdrop of an element = every colour stop of the first ancestor that paints (background colour and/or gradient); the WORST stop counts
        const bgsOf = (el) => { for (let e = el; e; e = e.parentElement) { const cs = getComputedStyle(e); const stops = (cs.backgroundImage.match(/rgba?\([^)]*\)/g) || []).filter((c) => !/rgba\([^)]*,\s*0\)$/.test(c)); const c = cs.backgroundColor; const solid = c !== "rgba(0, 0, 0, 0)" && c !== "transparent"; if (solid || stops.length) return [...stops.filter((x) => !/rgba\([^)]*,\s*0?\.\d+\)$/.test(x)), ...(solid ? [c] : [])]; } return ["rgb(255, 255, 255)"]; };
        const worst = (fg, el) => Math.min(...bgsOf(el).map((b) => ratio(fg, b)));
        const out = []; const stroke = (s) => getComputedStyle(document.querySelector(s)).stroke;
        for (const s of [".xp-bannerTitle", ".xp-bannerBody", ".xp-bannerEyebrow", ".xp-brandContext", ".authLead", ".authFoot", "#auth h2"]) { const el = document.querySelector(s); out.push([s, worst(getComputedStyle(el).color, el), 4.5]); }
        out.push(["wordmark ink on page", worst(stroke("#logos .xp-brandInk"), document.querySelector("#logos")), 4.5]);
        out.push(["logo glyph on tile", ratio(stroke("#logos .xp-brandGlyph"), getComputedStyle(document.querySelector("#logos .xp-brandTile")).fill), 3]);
        return out;
      }, RATIO);
      for (const [s, r, min] of pairs) check(`${tag}: computed contrast ${s} >= ${min}`, r >= min, r.toFixed(2));
      await p.addScriptTag({ path: AXE });
      const v = await p.evaluate(async () => (await window.axe.run(document, { resultTypes: ["violations"] })).violations.filter((x) => x.impact === "serious" || x.impact === "critical").map((x) => `${x.id}: ${x.nodes.slice(0, 2).map((n) => n.target.join(" ")).join(" | ")}`));
      check(`${tag}: axe 0 serious / critical`, v.length === 0, v.join(" ; "));
    }
    if (SHOTS) await p.screenshot({ path: join(SHOTS, `brand-${theme}-${w}.png`), fullPage: true });
    await p.close();
  }
} catch (e) { check("scenario aborted", false, e.message); }
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\nui-brand: ${results.length - failed.length}/${results.length} passed  (HARNESS: no backend involved)`);
process.exit(failed.length ? 1 : 0);
