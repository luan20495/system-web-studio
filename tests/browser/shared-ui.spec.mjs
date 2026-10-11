// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// Shared UI (packages/ui + packages/ui/src/styles): Modal dismissal / nesting / focus (M-010, M-022), mobile nav drawer (M-013), .grid2 reflow (M-014), Toast (M-016),
// ErrorBoundary (M-006), DataSourcesPanel in the Admin CSS set (M-012). Every check names the defect it guards.
//   ESBUILD_DIR=<dir with esbuild> node tests/browser/build-harness.mjs
//   CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/shared-ui.spec.mjs        (ONLY=modal,nav,grid,toast,boundary,ds to select)
import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { harnessOrigin, launch } from "./lib/spec.mjs";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const AXE = require.resolve("axe-core/axe.min.js");
const BASE = harnessOrigin() + "/";
const ONLY = (process.env.ONLY ?? "modal,nav,grid,toast,boundary,ds").split(",");
const SHOTS = process.env.SHOT_DIR; if (SHOTS) mkdirSync(SHOTS, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + String(detail).replace(/\s+/g, " ").slice(0, 200) : ""}`); };
const browser = await launch({ headless: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function open(page, w = 1000, h = 800) {
  const p = await browser.newPage({ viewport: { width: w, height: h } }); p.setDefaultTimeout(3000);
  p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(BASE + page); await p.waitForSelector("#root > *", { timeout: 8000 }).catch(() => undefined); return p;
}
const dialogs = (p) => p.locator('[role="dialog"]').count();
const logOf = (p) => p.evaluate(() => window.__log);
async function axeProblems(p, ctx) {
  await p.addScriptTag({ path: AXE });
  const r = await p.evaluate(async (c) => { const res = await window.axe.run(c ? document.querySelector(c) : document, { resultTypes: ["violations"] }); return res.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}:${v.nodes.length}`); }, ctx);
  return r;
}
const shot = async (p, name) => { if (SHOTS) await p.screenshot({ path: join(SHOTS, name) }); };

// ------------------------------------------------------------------------------------------------------------------------------------------------ Modal (M-010, M-022)
if (ONLY.includes("modal")) try {
  { // initial focus + Tab loop (M-022)
    const p = await open("ui-modal.html"); await p.click("#open-a"); await sleep(100);
    const f = await p.evaluate(() => ({ hidden: !!document.activeElement?.closest(".srOnly"), tag: document.activeElement?.tagName, cls: document.activeElement?.className }));
    check("M-022 modal opens with focus on a VISIBLE control, not the Picker's hidden native select", !f.hidden && f.tag !== "BODY", JSON.stringify(f));
    const seq = []; for (let i = 0; i < 12; i++) { await p.keyboard.press("Tab"); seq.push(await p.evaluate(() => !!document.activeElement?.closest(".srOnly") || !document.querySelector('[role="dialog"]').contains(document.activeElement))); }
    check("M-022 the Tab loop never lands on a hidden element or leaves the dialog", seq.every((x) => !x), seq.join(","));
    await p.close();
  }
  { // FQ-A11Y-02: the dialog is a route; opening / closing re-renders the opener as a NEW node (the real Studio remounts its workspace per path). Focus must still end on the control that opened it.
    const active = (p) => p.evaluate(() => document.activeElement?.id || document.activeElement?.tagName);
    for (const how of ["mouse", "keyboard"]) for (const close of ["Escape", "button"]) {
      const p = await open("ui-modal.html");
      if (how === "mouse") await p.click("#open-r"); else { await p.focus("#open-r"); await p.keyboard.press("Enter"); }
      await sleep(150); const opened = await dialogs(p);
      if (close === "Escape") await p.keyboard.press("Escape"); else await p.click("#close-r");
      await sleep(400);
      check(`FQ-A11Y-02 route-style dialog (${how} open, ${close} close): focus returns to the opener although the opener node was replaced`, opened === 1 && (await dialogs(p)) === 0 && (await active(p)) === "open-r", `opened=${opened} focus=${await active(p)}`);
      await p.close();
    }
    { // the opener is gone for good: nothing throws, focus is not sent to an unrelated control, and a person who clicks elsewhere in the meantime is not overridden
      const p = await open("ui-modal.html"); await p.click("#open-r"); await sleep(150); await p.click("#drop-r"); await sleep(600);
      check("FQ-A11Y-02 a stale opener (removed from the page): no error, focus stays where it is (<body>), nothing else is focused", p.errors.length === 0 && (await dialogs(p)) === 0 && (await active(p)) === "BODY", `focus=${await active(p)} errors=${p.errors.join("|")}`);
      await p.close();
    }
    { // the person moves on (clicks a field) before the opener re-appears: the pending restore is dropped
      const p = await open("ui-modal.html"); await p.click("#open-r"); await sleep(150); await p.click("#drop-r"); await p.click("#outside"); await sleep(600);
      check("FQ-A11Y-02 a restore that is still waiting is dropped when the person presses something else", (await active(p)) === "outside", `focus=${await active(p)}`);
      await p.close();
    }
  }
  { // not dismissible while busy (M-010)
    const p = await open("ui-modal.html"); await p.click("#open-a"); await p.fill("#name-a", "x"); await p.click("#submit-a"); await sleep(120);
    await p.keyboard.press("Escape"); await sleep(80);
    check("M-010 Escape does NOT close the dialog while its action is running (aria-busy)", (await dialogs(p)) === 1, `dialogs=${await dialogs(p)}`);
    await p.mouse.click(4, 4); await sleep(80);
    check("M-010 a backdrop click does NOT close the dialog while its action is running", (await dialogs(p)) === 1);
    await sleep(800);
    await p.keyboard.press("Escape"); await sleep(80);
    check("M-010 … and Escape closes it again once the action finished", (await dialogs(p)) === 0 && (await logOf(p)).includes("closeA"), JSON.stringify(await logOf(p)));
    await p.close();
  }
  { // nesting: Escape closes only the top modal; scroll lock restored in the right order (M-010)
    const p = await open("ui-modal.html"); await p.click("#open-a"); await p.click("#open-b"); await sleep(80);
    check("two modals stack", (await dialogs(p)) === 2);
    await p.keyboard.press("Escape"); await sleep(80);
    const l1 = await logOf(p);
    check("M-010 Escape closes only the TOP modal (B), A stays open", (await dialogs(p)) === 1 && l1.includes("closeB") && !l1.includes("closeA"), JSON.stringify(l1));
    await p.click("#open-b"); await sleep(60);
    await p.evaluate(() => window.__closeA()); await sleep(80);
    const o1 = await p.evaluate(() => document.body.style.overflow);
    check("M-010 closing the LOWER modal first keeps the page scroll locked while the upper one is open", o1 === "hidden", `overflow=${o1}`);
    await p.keyboard.press("Escape"); await sleep(80);
    const o2 = await p.evaluate(() => document.body.style.overflow);
    check("M-010 the scroll lock is restored to the value before the first modal when the last one closes", o2 === "" && (await dialogs(p)) === 0, `overflow='${o2}'`);
    await p.close();
  }
  { // stale onClose (M-010)
    const p = await open("ui-modal.html"); await p.click("#open-c"); await p.click("#inc-c"); await sleep(60); await p.keyboard.press("Escape"); await sleep(60);
    const l = await logOf(p);
    check("M-010 Escape calls the LATEST onClose (no stale closure)", l.at(-1) === "closeC:1", JSON.stringify(l));
    await p.close();
  }
  { // backdrop = mousedown AND mouseup on the backdrop (M-010)
    const p = await open("ui-modal.html"); await p.click("#open-d"); await sleep(60);
    const b = await p.locator("#in-d").boundingBox();
    await p.mouse.move(b.x + 20, b.y + b.height / 2); await p.mouse.down(); await p.mouse.move(4, 4); await p.mouse.up(); await sleep(80);
    check("M-010 pressing in a field and releasing on the backdrop does NOT close (typed text is safe)", (await dialogs(p)) === 1);
    await p.mouse.click(4, 4); await sleep(80);
    check("M-010 a press that starts AND ends on the backdrop closes a closeOnBackdrop modal", (await dialogs(p)) === 0 && (await logOf(p)).includes("closeD"), JSON.stringify(await logOf(p)));
    await p.close();
  }
  { // Picker keeps its own Escape
    const p = await open("ui-modal.html"); await p.click("#open-a"); await p.locator(".xp-pickerBtn").click(); await sleep(60);
    await p.keyboard.press("Escape"); await sleep(60);
    check("Escape with a Picker open closes only the Picker", (await dialogs(p)) === 1 && !(await p.locator(".xp-pickerList").count()));
    await p.close();
  }
} catch (e) { check("modal: scenario aborted", false, String(e.message).split("\n")[0]); }

// ------------------------------------------------------------------------------------------------------------------------------------------------ nav drawer (M-013)
if (ONLY.includes("nav")) try {
  for (const w of [360, 800, 900]) {
    const p = await open("ui-nav.html", w, 700);
    const stops = []; for (let i = 0; i < 7; i++) { await p.keyboard.press("Tab"); stops.push(await p.evaluate(() => !!document.activeElement?.closest("#admin-sidebar"))); }
    const vis = await p.evaluate(() => getComputedStyle(document.getElementById("admin-sidebar")).visibility);
    check(`M-013 @${w}: the CLOSED drawer is not a Tab stop (7 Tab presses never enter #admin-sidebar)`, stops.every((x) => !x) && vis === "hidden", `visibility=${vis} stops=${stops.join(",")}`);
    await p.focus("button.navToggle"); await p.keyboard.press("Enter"); await sleep(350);
    const o = await p.evaluate(() => { const a = document.activeElement; const sb = document.getElementById("admin-sidebar"); return { inSb: !!a?.closest("#admin-sidebar"), first: a === sb.querySelector("a"), vis: getComputedStyle(sb).visibility, inert: document.querySelector(".shellMain").hasAttribute("inert") }; });
    check(`M-013 @${w}: opening moves focus to the first link, shows the drawer and makes the rest of the shell inert`, o.inSb && o.first && o.vis === "visible" && o.inert, JSON.stringify(o));
    const trapped = []; for (let i = 0; i < 9; i++) { await p.keyboard.press("Tab"); trapped.push(await p.evaluate(() => !!document.activeElement?.closest("#admin-sidebar"))); }
    check(`M-013 @${w}: Tab / Shift+Tab stay inside the open drawer`, trapped.every(Boolean), trapped.join(","));
    await p.keyboard.down("Shift"); await p.keyboard.press("Tab"); await p.keyboard.up("Shift");
    check(`M-013 @${w}: Shift+Tab wraps inside as well`, await p.evaluate(() => !!document.activeElement?.closest("#admin-sidebar")));
    if (w === 800) { const ax = await axeProblems(p); check("M-013 axe (serious/critical) on the OPEN drawer", ax.length === 0, ax.join(" ")); await shot(p, "nav-open-800.png"); }
    await p.keyboard.press("Escape"); await sleep(400);
    const c = await p.evaluate(() => ({ btn: document.activeElement === document.querySelector("button.navToggle"), vis: getComputedStyle(document.getElementById("admin-sidebar")).visibility, inert: document.querySelector(".shellMain").hasAttribute("inert") }));
    check(`M-013 @${w}: Escape closes, returns focus to the menu button, hides the drawer again and removes inert`, c.btn && c.vis === "hidden" && !c.inert, JSON.stringify(c));
    await p.keyboard.press("Enter"); await sleep(300);
    await p.mouse.click(w - 20, 400); await sleep(400);
    check(`M-013 @${w}: the backdrop closes it`, (await p.evaluate(() => document.getElementById("admin-sidebar").closest(".shell").dataset.nav)) === "closed");
    await p.close();
  }
  { // growing past the breakpoint with the drawer open
    const p = await open("ui-nav.html", 800, 700); await p.focus("button.navToggle"); await p.keyboard.press("Enter"); await sleep(300);
    await p.setViewportSize({ width: 1280, height: 700 }); await sleep(300);
    const r = await p.evaluate(() => ({ nav: document.querySelector(".shell").dataset.nav, inert: document.querySelector(".shellMain").hasAttribute("inert") }));
    check("M-013 widening the window closes the drawer and never leaves the page inert next to a static sidebar", r.nav === "closed" && !r.inert, JSON.stringify(r));
    await p.evaluate(() => { document.activeElement?.blur?.(); window.scrollTo(0, 0); });
    await p.keyboard.press("Tab");
    check("M-013 @1280 the static sidebar is still the first Tab stop (wide layout unchanged)", await p.evaluate(() => !!document.activeElement?.closest("#admin-sidebar")));
    await p.close();
  }
} catch (e) { check("nav: scenario aborted", false, String(e.message).split("\n")[0]); }

// ------------------------------------------------------------------------------------------------------------------------------------------------ .grid2 reflow (M-014)
if (ONLY.includes("grid")) try {
  for (const w of [320, 360, 390, 430, 768]) {
    const p = await open("ui-nav.html", w, 700);
    const r = await p.evaluate(() => { const pg = document.querySelector("main.page"); const c = document.querySelector(".grid2 .card").getBoundingClientRect(); return { scrollW: pg.scrollWidth, clientW: pg.clientWidth, cardRight: Math.round(c.right) }; });
    check(`M-014 @${w}: main.page does not scroll sideways on a .grid2 page (WCAG 1.4.10)`, r.scrollW <= r.clientW + 1, JSON.stringify(r));
    await p.close();
  }
} catch (e) { check("grid: scenario aborted", false, String(e.message).split("\n")[0]); }

// ------------------------------------------------------------------------------------------------------------------------------------------------ Toast (M-016)
if (ONLY.includes("toast")) try {
  const p = await open("ui-toast.html");
  const regions = await p.evaluate(() => ({ status: document.querySelectorAll('[data-testid="toasts"] [role="status"]').length, alert: document.querySelectorAll('[data-testid="toasts"] [role="alert"]').length }));
  check("M-016 both live regions (status + alert) exist BEFORE any message (inserted content is announced)", regions.status === 1 && regions.alert === 1, JSON.stringify(regions));
  await p.click("#ok"); await sleep(80);
  check("M-016 a success lands inside the polite role=status region", await p.evaluate(() => !!document.querySelector('[role="status"] .xp-toast-success')));
  await sleep(1000);
  check("M-016 a success auto-dismisses", !(await p.locator(".xp-toast-success").count()));
  await p.click("#err"); await sleep(80);
  check("M-016 an error lands inside the assertive role=alert region, with its title", await p.evaluate(() => !!document.querySelector('[role="alert"] .xp-toast-error .xp-toastTitle')));
  await sleep(1600);
  check("M-016 an error does NOT auto-dismiss", (await p.locator(".xp-toast-error").count()) === 1);
  check("M-016 every toast has a dismiss button with an accessible name", (await p.getByRole("button", { name: "Đóng thông báo" }).count()) === 1);
  await p.getByRole("button", { name: "Đóng thông báo" }).click(); await sleep(60);
  check("M-016 the dismiss button removes the toast", !(await p.locator(".xp-toast").count()));
  await p.evaluate(() => { window.__seen = new Set(); new MutationObserver(() => document.querySelectorAll(".xp-toastMsg").forEach((e) => window.__seen.add(e.textContent))).observe(document.body, { childList: true, subtree: true, characterData: true }); });
  await p.click("#five"); await sleep(80);
  const q = await p.evaluate(() => ({ shown: document.querySelectorAll(".xp-toast").length, more: document.querySelector(".xp-toastMore")?.textContent }));
  check("M-016 five messages: 3 visible, the other 2 queued and announced as waiting (not silently replaced)", q.shown === 3 && /\+2/.test(q.more ?? ""), JSON.stringify(q));
  await sleep(2600);
  const seen = await p.evaluate(() => [...window.__seen].sort());
  check("M-016 no message is lost: all five are shown, one after another", seen.length === 5, seen.join(" | "));
  await p.click("#same"); await p.click("#same"); await p.click("#same"); await sleep(60);
  check("M-016 re-using an id updates the toast in place (one toast, not three)", (await p.locator('[data-testid="toast:same"]').count()) === 1);
  await sleep(1000);
  await p.click("#ok"); await sleep(60);
  const box = await p.locator(".xp-toast-success").boundingBox();
  await p.mouse.move(box.x + 30, box.y + box.height / 2); await sleep(1100);
  check("M-016 the timer pauses while the pointer is on the toast (WCAG 2.2.1)", (await p.locator(".xp-toast-success").count()) === 1);
  await p.mouse.move(2, 2); await sleep(1100);
  check("M-016 … and resumes when it leaves", !(await p.locator(".xp-toast-success").count()));
  await p.click("#err"); await sleep(60);
  await p.getByRole("button", { name: "Đóng thông báo" }).focus(); await p.keyboard.press("Escape"); await sleep(60);
  check("M-016 Escape on a focused toast dismisses it", !(await p.locator(".xp-toast").count()));
  await p.click("#modal"); await p.click("#toast-in-modal"); await sleep(80);
  const above = await p.evaluate(() => { const t = document.querySelector(".xp-toast"); const r = t.getBoundingClientRect(); const hit = document.elementFromPoint(r.x + 20, r.y + r.height / 2); return { onTop: !!hit?.closest(".xp-toast"), z: getComputedStyle(document.querySelector(".xp-toasts")).zIndex }; });
  check("M-016 a toast raised inside a modal is rendered ABOVE the modal", above.onTop && Number(above.z) > 80, JSON.stringify(above));
  { // imperative confirm() / prompt() on the same overlay core
    await p.click("#close-m"); await sleep(60);
    await p.click("#confirm"); await p.waitForSelector('[role="dialog"]');
    check("confirm(): a destructive confirmation focuses 'Hủy' first and its confirm button is the red one", (await p.evaluate(() => document.activeElement?.textContent)) === "Hủy" && (await p.locator('[role="dialog"] .btn.primary.danger').count()) === 1);
    await p.keyboard.press("Escape"); await sleep(120);
    check("confirm(): Escape resolves false, closes, and returns focus to the opener", (await p.evaluate(() => window.__log.at(-1))) === "confirm:false" && (await dialogs(p)) === 0 && (await p.evaluate(() => document.activeElement?.id)) === "confirm");
    await p.click("#confirm"); await p.getByRole("button", { name: "Xóa", exact: true }).click(); await sleep(120);
    check("confirm(): the confirm button resolves true", (await p.evaluate(() => window.__log.at(-1))) === "confirm:true");
    await p.click("#confirm2"); await p.waitForSelector('[role="dialog"]');
    check("confirm(string): a plain question starts on the confirm button", (await p.evaluate(() => document.activeElement?.textContent)) === "Xác nhận");
    await p.mouse.click(4, 4); await sleep(80);
    check("confirm(): a backdrop click does NOT answer the question", (await dialogs(p)) === 1);
    await p.getByRole("button", { name: "Hủy" }).click(); await sleep(120);
    check("confirm(): Hủy resolves false", (await p.evaluate(() => window.__log.at(-1))) === "confirm2:false");
    await p.click("#prompt"); await p.waitForSelector('[role="dialog"] textarea');
    check("prompt(): focus starts in the field", (await p.evaluate(() => document.activeElement?.tagName)) === "TEXTAREA");
    await p.getByRole("button", { name: "Lưu" }).click(); await sleep(80);
    const v = await p.evaluate(() => { const t = document.querySelector('[role="dialog"] textarea'); return { open: !!t, invalid: t?.getAttribute("aria-invalid"), err: !!document.querySelector('[role="dialog"] [role="alert"]'), described: t?.getAttribute("aria-describedby") === document.querySelector('[role="dialog"] [role="alert"]')?.id }; });
    check("prompt(): a required field stays open with an associated inline error (nothing resolved)", v.open && v.invalid === "true" && v.err && v.described, JSON.stringify(v));
    const axp = await axeProblems(p); check("prompt(): axe (serious/critical) with the error shown", axp.length === 0, axp.join(" "));
    await p.fill('[role="dialog"] textarea', "thiếu thông tin"); await p.getByRole("button", { name: "Lưu" }).click(); await sleep(120);
    check("prompt(): the typed text is returned", (await p.evaluate(() => window.__log.at(-1))) === 'prompt:"thiếu thông tin"');
    await p.click("#prompt"); await p.waitForSelector('[role="dialog"]'); await p.keyboard.press("Escape"); await sleep(120);
    check("prompt(): Escape resolves null", (await p.evaluate(() => window.__log.at(-1))) === "prompt:null");
  }
  await p.click("#ok"); await sleep(80);
  const ax = await axeProblems(p); check("M-016 axe (serious/critical) with toasts on screen", ax.length === 0, ax.join(" ")); await shot(p, "toast.png");
  await p.close();
} catch (e) { check("toast: scenario aborted", false, String(e.message).split("\n")[0]); }

// ------------------------------------------------------------------------------------------------------------------------------------------------ ErrorBoundary (M-006)
if (ONLY.includes("boundary")) try {
  const p = await open("ui-boundary.html");
  check("boundary: the screen renders normally", (await p.locator("#ok").count()) === 1);
  await p.click("#arm"); await sleep(200);
  const f = await p.evaluate(() => ({ fb: !!document.querySelector('[data-testid="error-fallback"]'), focus: document.activeElement?.tagName, text: document.body.innerText }));
  check("M-006 a render exception shows the fallback instead of an empty page", f.fb);
  check("M-006 focus moves to the fallback heading", f.focus === "H1", f.focus);
  check("M-006 the fallback shows the request id and 'Thử lại', and NEVER the message or stack", /req-7f3a/.test(f.text) && /Thử lại/.test(f.text) && !/secret internal|Bomb\.tsx|at /.test(f.text), f.text.slice(0, 160));
  check("M-006 the real error is handed to onError (logging), not to the screen", (await p.evaluate(() => window.__reported)).some((m) => /secret internal/.test(m)));
  const ax = await axeProblems(p); check("M-006 axe (serious/critical) on the fallback", ax.length === 0, ax.join(" ")); await shot(p, "error-fallback.png");
  await p.getByRole("button", { name: "Thử lại" }).click(); await sleep(150);
  check("M-006 'Thử lại' clears the error and renders the screen again", (await p.locator("#ok").count()) === 1 && !(await p.locator('[data-testid="error-fallback"]').count()));
  await p.click("#arm"); await sleep(150); await p.click("#route"); await sleep(150);
  check("M-006 changing the route (resetKeys) clears the error without a click on Thử lại", (await p.locator("#ok").count()) === 1);
  await p.close();
} catch (e) { check("boundary: scenario aborted", false, String(e.message).split("\n")[0]); }

// ------------------------------------------------------------------------------------------------------------------------------------------------ Admin: Nguồn dữ liệu (M-012, CSS only)
if (ONLY.includes("ds")) try {
  const p = await open("admin-ds.html", 1280, 900); await p.waitForSelector('[data-testid="ds-panel"]', { timeout: 5000 }).catch(() => undefined);
  const s = await p.evaluate(() => {
    const px = (v) => parseFloat(v); const rgb = (v) => v.match(/[\d.]+/g).map(Number);
    const create = document.querySelector('[data-testid="ds-create"]'); const card = document.querySelector(".card");
    const cs = getComputedStyle(create), cardBg = getComputedStyle(card).backgroundColor;
    const lum = (c) => { const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
    const ratio = (a, b) => { const [x, y] = [lum(rgb(a)), lum(rgb(b))].sort((m, n) => n - m); return (x + 0.05) / (y + 0.05); };
    const grp = document.querySelector(".bx-group"); const h3 = document.querySelector(".bx-h3"); const ul = document.querySelector('[data-testid="ds-list"]');
    return { btnBg: cs.backgroundColor, btnFg: cs.color, btnRatio: ratio(cs.color, cs.backgroundColor), btnVsCard: ratio(cs.backgroundColor, cardBg), groupBorder: getComputedStyle(grp).borderTopStyle, h3: px(getComputedStyle(h3).fontSize), listStyle: getComputedStyle(ul).listStyleType, ulPad: getComputedStyle(ul).paddingLeft };
  });
  check("M-012 the primary 'Tạo nguồn' button is a visible button (distinct from the white card, readable text)", s.btnVsCard >= 1.5 && s.btnRatio >= 4.5, JSON.stringify(s));
  check("M-012 fieldsets / lists / headings use the shared look (no UA groove border, no bullets, no 21px h2)", s.groupBorder === "solid" && s.listStyle === "none" && s.h3 <= 16, JSON.stringify(s));
  await shot(p, "admin-ds-list.png");
  const axeList = await axeProblems(p); check("M-012 axe (serious/critical) on the Admin data-sources list", axeList.length === 0, axeList.join(" "));
  await p.click('[data-testid="ds-delete:ds-1"]'); await p.waitForSelector('[role="dialog"]');
  const d = await p.evaluate(() => { const dlg = document.querySelector('[role="dialog"]'); const ov = dlg.parentElement; const r = ov.getBoundingClientRect(); const cs = getComputedStyle(ov); return { pos: cs.position, z: cs.zIndex, bg: cs.backgroundColor, coversViewport: Math.round(r.width) === innerWidth && Math.round(r.height) === innerHeight, dlgBg: getComputedStyle(dlg).backgroundColor, inCardFlow: getComputedStyle(dlg).position === "static" }; });
  check("M-012 the delete dialog is a real modal: fixed over the whole viewport on a scrim, white box (not inline in the page)", d.pos === "fixed" && d.coversViewport && d.dlgBg === "rgb(255, 255, 255)" && /0\.45/.test(d.bg), JSON.stringify(d));
  const foc = await p.evaluate(() => document.activeElement?.textContent);
  check("M-012 the dialog still focuses 'Hủy' first and traps Tab (behaviour unchanged)", foc === "Hủy", foc);
  await shot(p, "admin-ds-dialog.png");
  const axeDlg = await axeProblems(p); check("M-012 axe (serious/critical) with the dialog open", axeDlg.length === 0, axeDlg.join(" "));
  await p.keyboard.press("Escape"); await sleep(80);
  check("M-012 Escape closes the dialog", (await dialogs(p)) === 0);
  await p.close();
} catch (e) { check("ds: scenario aborted", false, String(e.message).split("\n")[0]); }

await browser.close();
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} checks passed`);
process.exit(bad.length ? 1 : 0);
