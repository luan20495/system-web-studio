// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// M-025: skip link, focus + scroll on a route change, <main> is a Tab stop only while it scrolls (packages/ui SkipLink + useMain).
//   ESBUILD_DIR=<dir with esbuild> node tests/browser/build-harness.mjs
//   CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/ui-route.spec.mjs
import { createRequire } from "node:module";
import { harnessOrigin, launch } from "./lib/spec.mjs";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const AXE = require.resolve("axe-core/axe.min.js");
const BASE = harnessOrigin() + "/";
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + String(detail).replace(/\s+/g, " ").slice(0, 200) : ""}`); };
const browser = await launch({ headless: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function open(w = 1100, h = 700) {
  const p = await browser.newPage({ viewport: { width: w, height: h } }); p.setDefaultTimeout(3000);
  p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(BASE + "ui-route.html"); await p.waitForSelector("main", { timeout: 8000 }); await sleep(100); return p;
}
const act = (p) => p.evaluate(() => ({ tag: document.activeElement?.tagName, id: document.activeElement?.id, text: document.activeElement?.textContent?.trim().slice(0, 24), cls: document.activeElement?.className }));

try {
  { const p = await open();
    check("first load does not steal focus (the browser's start-of-document focus is right)", (await act(p)).tag === "BODY", JSON.stringify(await act(p)));
    await p.keyboard.press("Tab");
    const a = await act(p); const box = await p.locator(".xp-skip").boundingBox();
    check("M-025 the FIRST Tab stop is the skip link and it is visible on screen while focused", a.cls === "xp-skip" && !!box && box.y >= 0 && box.y < 100 && box.width > 100, JSON.stringify({ a, box }));
    const hidden = await (async () => { const q = await open(); const b = await q.locator(".xp-skip").boundingBox(); await q.close(); return b; })();
    check("M-025 unfocused, the skip link is off screen (not a visual element)", !hidden || hidden.y + hidden.height <= 0, JSON.stringify(hidden));
    await p.keyboard.press("Enter");
    const m = await act(p);
    check("M-025 Enter on the skip link moves focus to <main> (past the 6 nav links + header button) and the URL hash is untouched", m.tag === "MAIN" && m.id === "main" && !(await p.url()).includes("#"), JSON.stringify(m));
    { const q = await open(); for (let i = 0; i < 8; i++) await q.keyboard.press("Tab");
      check("M-025 what the link saves: 8 Tab stops (skip link, 6 nav links, header button) sit in front of the content", (await act(q)).id === "logout", JSON.stringify(await act(q))); await q.close(); }
    const tab0 = await p.locator("main").getAttribute("tabindex");
    check("M-025 main is NOT a Tab stop while its content fits (tabindex=-1)", tab0 === "-1", tab0);
    await p.close(); }

  { const p = await open();
    await p.click("#nav-1"); await sleep(150);
    const f = await act(p);
    check("M-025 route change moves focus to the new page's <h1>", f.tag === "H1" && /Người dùng/.test(f.text ?? ""), JSON.stringify(f));
    check("M-025 the h1 is focusable only programmatically (tabindex=-1: never an extra Tab stop)", (await p.locator("main h1").getAttribute("tabindex")) === "-1");
    check("M-025 the tall page (1800px) makes <main> scrollable, so it now IS a keyboard stop (tabindex=0)", (await p.locator("main").getAttribute("tabindex")) === "0", await p.locator("main").getAttribute("tabindex"));
    await p.evaluate(() => { document.querySelector("main").scrollTop = 600; });
    await p.click("#nav-2"); await sleep(150);
    check("M-025 route change scrolls the page back to the top", (await p.evaluate(() => document.querySelector("main").scrollTop)) === 0);
    check("M-025 and the short page is no longer a Tab stop again", (await p.locator("main").getAttribute("tabindex")) === "-1");
    await p.click("#nav-3"); await sleep(60);
    const early = await act(p);
    check("M-025 a page that renders its heading late: focus first goes to <main>", early.tag === "MAIN", JSON.stringify(early));
    await sleep(500);
    const late = await act(p);
    check("M-025 ...and moves to the <h1> once it exists", late.tag === "H1" && /Nhật ký/.test(late.text ?? ""), JSON.stringify(late));
    await p.click("#nav-4"); await sleep(40); await p.focus("#logout"); await sleep(400);
    check("M-025 if the user moved focus meanwhile, the late upgrade does not steal it back", (await act(p)).id === "logout");
    // M-053: a lazily fetched section whose heading appears AFTER the 300 ms look (900 ms here): the observer upgrades the focus; a user who moved on is still left alone
    await p.click("#nav-5"); await sleep(60);
    check("M-053 a section that renders its heading very late: focus first goes to <main>", (await act(p)).tag === "MAIN");
    await sleep(1200);
    const slow = await act(p);
    check("M-053 ...and moves to the <h1> when it finally appears (not only within 300 ms)", slow.tag === "H1" && /Báo cáo/.test(slow.text ?? ""), JSON.stringify(slow));
    await p.click("#nav-4"); await p.click("#nav-5"); await sleep(40); await p.focus("#logout"); await sleep(1200);
    check("M-053 a very late heading does not steal the focus from a user who moved on", (await act(p)).id === "logout");
    await p.addScriptTag({ path: AXE });
    const v = await p.evaluate(async () => (await window.axe.run(document, { resultTypes: ["violations"] })).violations.filter((x) => x.impact === "serious" || x.impact === "critical").map((x) => `${x.id}: ${x.nodes[0].target.join(" ")}`));
    check("axe (serious+critical) on the shell with skip link", v.length === 0, v.join(" ; "));
    check("no page errors", p.errors.length === 0, p.errors.join(" | ")); await p.close(); }
} catch (e) { check("scenario aborted", false, e.message); }
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\nui-route: ${results.length - failed.length}/${results.length} passed  (HARNESS: no backend involved)`);
process.exit(failed.length ? 1 : 0);
