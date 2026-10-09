// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// Shared widgets (packages/ui): Tabs (M-028), RadioGroup (M-030), DisclosureRow (M-029), ReasonButton (M-031), Pill tones (M-032), Picker aria-activedescendant (M-024), LoadGate / ErrorState (M-056 M-079 M-084).
//   ESBUILD_DIR=<dir with esbuild> node tests/browser/build-harness.mjs
//   CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/ui-widgets.spec.mjs
import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { harnessOrigin, launch } from "./lib/spec.mjs";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const AXE = require.resolve("axe-core/axe.min.js");
const BASE = harnessOrigin() + "/";
const SHOTS = process.env.SHOT_DIR; if (SHOTS) mkdirSync(SHOTS, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + String(detail).replace(/\s+/g, " ").slice(0, 200) : ""}`); };
const browser = await launch({ headless: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function open(page, w = 1000, h = 900) {
  const p = await browser.newPage({ viewport: { width: w, height: h } }); p.setDefaultTimeout(3000);
  p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(BASE + page); await p.waitForSelector("#root > *", { timeout: 8000 }).catch(() => undefined); return p;
}
const logOf = (p) => p.evaluate(() => window.__log);
const active = (p) => p.evaluate(() => ({ id: document.activeElement?.id, role: document.activeElement?.getAttribute("role"), text: document.activeElement?.textContent?.trim().slice(0, 30) }));
async function axeProblems(p, ctx) {
  await p.addScriptTag({ path: AXE });
  return p.evaluate(async (c) => { const res = await window.axe.run(c ? document.querySelector(c) : document, { resultTypes: ["violations"] }); return res.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.nodes.slice(0, 2).map((n) => n.target.join(" ")).join(" | ")}`); }, ctx ?? null);
}
const shot = async (p, name) => { if (SHOTS) await p.screenshot({ path: join(SHOTS, name), fullPage: true }); };

try {
  // ------------------------------------------------------------------------------------------------------------------------------------------ Tabs (M-028)
  { const p = await open("ui-widgets.html");
    const tabs = p.locator('[role="tab"]');
    check("M-028 tabs: role=tablist with a name, 4 tabs, exactly the selected one is selected", (await p.locator('[role="tablist"][aria-label="Chi tiết"]').count()) === 1 && (await tabs.count()) === 4 && (await p.locator('[role="tab"][aria-selected="true"]').count()) === 1);
    check("M-028 roving tabindex: ONE tab stop (selected = 0, the rest -1)", JSON.stringify(await tabs.evaluateAll((els) => els.map((e) => e.tabIndex))) === JSON.stringify([0, -1, -1, -1]));
    const sel = await p.locator('[role="tab"][aria-selected="true"]').getAttribute("aria-controls");
    check("M-028 aria-controls only on the selected tab and it points at an EXISTING tabpanel", !!sel && (await p.locator(`[role="tabpanel"]#${await p.evaluate((s) => CSS.escape(s), sel)}`).count()) === 1 && (await p.locator('[role="tab"]:not([aria-selected="true"])[aria-controls]').count()) === 0, sel);
    check("M-028 the tabpanel is labelled by its tab", (await p.locator('[role="tabpanel"]').getAttribute("aria-labelledby")) === (await p.locator('[role="tab"][aria-selected="true"]').getAttribute("id")));
    await p.focus("#before"); await p.keyboard.press("Tab");
    check("M-028 Tab from the previous control lands on the selected tab", (await active(p)).role === "tab" && /Thông tin/.test((await active(p)).text ?? ""), JSON.stringify(await active(p)));
    await p.keyboard.press("Tab"); const a2 = await active(p);
    check("M-028 the next Tab leaves the tab list (goes to the tabpanel, not to another tab)", a2.role === "tabpanel", JSON.stringify(a2));
    await p.keyboard.press("Tab"); check("M-028 and then to the control after the panel", (await active(p)).id === "after");
    await p.focus('[role="tab"][aria-selected="true"]'); await p.keyboard.press("ArrowRight");
    check("M-028 ArrowRight selects and focuses the next tab (automatic activation)", /Thành viên/.test((await active(p)).text ?? "") && (await p.locator("#panel-text").innerText()) === "Nội dung members" && (await logOf(p)).includes("tab:members"));
    check("M-028 the count is read as part of the name, separated", /Thành viên\s*,\s*4/.test(await p.locator('[role="tab"][aria-selected="true"]').evaluate((e) => e.textContent.replace(/\s+/g, " "))));
    await p.keyboard.press("ArrowRight");
    check("M-028 ArrowRight skips a disabled tab", /Nhật ký/.test((await active(p)).text ?? ""), JSON.stringify(await active(p)));
    await p.keyboard.press("ArrowRight"); check("M-028 ArrowRight wraps to the first tab", /Thông tin/.test((await active(p)).text ?? ""));
    await p.keyboard.press("End"); check("M-028 End goes to the last enabled tab", /Nhật ký/.test((await active(p)).text ?? ""));
    await p.keyboard.press("Home"); check("M-028 Home goes to the first tab", /Thông tin/.test((await active(p)).text ?? ""));
    await p.keyboard.press("ArrowLeft"); check("M-028 ArrowLeft wraps backwards over the disabled tab", /Nhật ký/.test((await active(p)).text ?? ""));
    const ax = await axeProblems(p, '[role="tablist"]'); check("M-028 axe (serious+critical) on the tab list", ax.length === 0, ax.join(" ; "));
    check("no page errors", p.errors.length === 0, p.errors.join(" | ")); await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ RadioGroup (M-030)
  { const p = await open("ui-widgets.html");
    check("M-030 radiogroup is named by its legend and holds 3 native radios", (await p.getByRole("radiogroup", { name: "Ai xem được ứng dụng?" }).count()) === 1 && (await p.locator('.xp-radioGroup input[type="radio"]').count()) === 3);
    await p.focus("#after"); await p.keyboard.press("Tab");
    for (let i = 0; i < 12 && !(await p.evaluate(() => document.activeElement?.matches('.xp-radioGroup input'))); i++) await p.keyboard.press("Tab");
    const onRadio = await p.evaluate(() => ({ v: document.activeElement?.value, checked: document.activeElement?.checked }));
    check("M-030 Tab lands on the CHECKED radio (the group is one tab stop)", onRadio.v === "PRIVATE" && onRadio.checked, JSON.stringify(onRadio));
    const ring = await p.evaluate(() => { const b = document.activeElement?.nextElementSibling; const s = b && getComputedStyle(b); return { style: s?.outlineStyle, w: s?.outlineWidth }; });
    check("M-030 keyboard focus shows a visible ring on the card", ring.style !== "none" && parseFloat(ring.w) >= 2, JSON.stringify(ring));
    await p.keyboard.press("ArrowDown");
    check("M-030 ArrowDown moves the choice (native radio behaviour): PUBLIC is checked and reported", (await p.evaluate(() => document.querySelector('.xp-radioGroup input:checked')?.value)) === "PUBLIC" && (await logOf(p)).includes("aud:PUBLIC"));
    await p.keyboard.press("Tab"); check("M-030 the next Tab leaves the group (not to the other radios)", (await active(p)).id === "after-radio", JSON.stringify(await active(p)));
    await p.click(".xp-radioCard:nth-child(3) b");
    check("M-030 clicking the card text chooses it", (await p.evaluate(() => document.querySelector('.xp-radioGroup input:checked')?.value)) === "COMPANY");
    check("M-030 the selected card is marked by a filled dot AND border, not by colour alone (class + ::before)", await p.evaluate(() => { const c = document.querySelector(".xp-radioCard.selected .xp-radioBody"); return !!c && getComputedStyle(c, "::before").backgroundImage.includes("radial-gradient"); }));
    const ax = await axeProblems(p, ".xp-radioGroup"); check("M-030 axe on the radio group", ax.length === 0, ax.join(" ; "));
    await shot(p, "widgets-light.png"); await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ DisclosureRow (M-029)
  { const p = await open("ui-widgets.html");
    const btn = p.locator(".xp-discBtn").first();
    check("M-029 each row has a real button with aria-expanded=false and a row-specific name", (await btn.getAttribute("aria-expanded")) === "false" && /Chi tiết sự kiện Đăng nhập/.test(await btn.innerText()) && (await p.locator("button.xp-discBtn").count()) === 2);
    check("M-029 collapsed: no aria-controls (nothing to point at) and no detail row", (await btn.getAttribute("aria-controls")) === null && (await p.locator("#detail-1").count()) === 0);
    await btn.focus(); await p.keyboard.press("Enter");
    const ctl = await btn.getAttribute("aria-controls");
    check("M-029 Enter expands: aria-expanded=true, aria-controls points at the detail row, detail visible", (await btn.getAttribute("aria-expanded")) === "true" && !!ctl && (await p.locator("#detail-1").isVisible()) && (await p.evaluate((i) => !!document.getElementById(i)?.querySelector("#detail-1"), ctl)), ctl);
    await p.keyboard.press("Space"); check("M-029 Space collapses again", (await btn.getAttribute("aria-expanded")) === "false" && (await p.locator("#detail-1").count()) === 0);
    await p.click("tr.xp-disc >> nth=1 >> td >> nth=1"); check("M-029 a mouse click on the row still toggles (convenience, not the only way)", (await p.locator(".xp-discBtn").nth(1).getAttribute("aria-expanded")) === "true");
    await p.click("#row-link", { noWaitAfter: true }); check("M-029 a click on a link inside the row does NOT toggle it", (await btn.getAttribute("aria-expanded")) === "false");
    const ax = await axeProblems(p, "table"); check("M-029 axe on the table", ax.length === 0, ax.join(" ; "));
    await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ ReasonButton (M-031)
  { const p = await open("ui-widgets.html");
    const off = p.locator("#rb-off");
    check("M-031 an unavailable action is aria-disabled, NOT disabled, and has no title attribute", (await off.getAttribute("aria-disabled")) === "true" && (await off.getAttribute("disabled")) === null && (await off.getAttribute("title")) === null);
    const rid = await off.getAttribute("aria-describedby");
    check("M-031 the reason is VISIBLE text tied to the button (aria-describedby)", !!rid && (await p.locator(`[id="${rid}"]`).isVisible()) && /Bật mô hình trước/.test(await p.locator(`[id="${rid}"]`).innerText()), rid);
    await off.focus(); check("M-031 it is focusable (a disabled button is not: keyboard users could never reach the reason)", (await active(p)).id === "rb-off");
    await off.click({ force: true }); await p.keyboard.press("Enter"); await p.keyboard.press("Space");
    check("M-031 activating it does nothing", !(await logOf(p)).includes("rb-off"));
    await p.click("#rb-on"); check("M-031 an available action works and shows no reason", (await logOf(p)).includes("rb-on") && (await p.locator("#rb-on").getAttribute("aria-describedby")) === null);
    await p.locator("#rb-busy").click({ force: true }); check("M-031 busy: aria-busy, click ignored, no reason shown", (await p.locator("#rb-busy").getAttribute("aria-busy")) === "true" && !(await logOf(p)).includes("rb-busy"));
    const ax = await axeProblems(p, "#rb-off"); check("M-031 axe", ax.length === 0, ax.join(" ; "));
    await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ Pill (M-032)
  { const p = await open("ui-widgets.html");
    const cls = await p.locator("#pills .pill").evaluateAll((els) => els.map((e) => [e.textContent, e.className.replace("pill ", "")]));
    const m = Object.fromEntries(cls);
    check("M-032 risk-high is red, paid is amber, awaiting review is amber, an explicit tone wins, unknown stays grey (the old code showed all of them grey)", m["Rủi ro cao"] === "pill-bad" && m["Trả phí"] === "pill-warn" && m["Chờ duyệt"] === "pill-warn" && m["Hoạt động"] === "pill-ok" && m["Thông tin"] === "pill-info" && m["Chưa rõ"] === "pill-muted", JSON.stringify(m));
    await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ Picker (M-024)
  { const p = await open("ui-widgets.html");
    const b = p.locator("button.xp-pickerBtn");
    check("M-024 closed picker: no aria-controls / aria-activedescendant pointing at a list that does not exist", (await b.getAttribute("aria-controls")) === null && (await b.getAttribute("aria-activedescendant")) === null);
    await b.focus(); await p.keyboard.press("Enter");
    const ad = await b.getAttribute("aria-activedescendant");
    check("M-024 open: aria-activedescendant is on the focused combobox (the button) and names the active option", !!ad && (await p.locator(`[id="${ad}"][role="option"]`).count()) === 1 && (await p.locator('[role="listbox"]').getAttribute("aria-activedescendant")) === null, ad);
    check("M-024 aria-controls points at the open listbox", (await b.getAttribute("aria-controls")) === (await p.locator('[role="listbox"]').getAttribute("id")));
    await p.keyboard.press("ArrowDown"); const ad2 = await b.getAttribute("aria-activedescendant");
    check("M-024 ArrowDown moves aria-activedescendant to the next option", !!ad2 && ad2 !== ad && /Loại B/.test(await p.locator(`[id="${ad2}"]`).innerText()), ad2);
    await p.keyboard.press("Escape"); check("M-024 Escape closes and removes both attributes", (await b.getAttribute("aria-activedescendant")) === null && (await b.getAttribute("aria-controls")) === null);
    await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ LoadGate / ErrorState (M-056 M-079 M-084)
  { const p = await open("ui-widgets.html");
    const gate = (s) => p.locator(`section[aria-label="Cổng tải"] ${s}`);
    check("M-056 a failed load is an alert with a retry and a reference code, never a spinner", (await gate('[role="alert"]').count()) === 1 && (await gate("button:has-text('Thử lại')").count()) === 1 && /req-77/.test(await gate('[role="alert"]').innerText()) && (await gate(".spinner").count()) === 0);
    check("M-056 the raw exception text is not shown", !/NullPointer/.test(await gate('[role="alert"]').innerText()));
    await gate("button:has-text('Thử lại')").click(); check("M-056 Thử lại calls reload", (await logOf(p)).includes("retry") && (await p.locator("#tries").innerText()) === "1");
    await p.click("#g-loading"); check("M-056 loading is a polite status that says what loads", (await gate('[role="status"]').count()) === 1 && /Đang tải danh sách…/.test(await gate('[role="status"]').innerText()));
    await p.click("#g-notfound"); const nf = await gate('[role="alert"]').innerText();
    check("M-079 404: 'Không tìm thấy' appears once and there is no retry button", (nf.match(/Không tìm thấy/g) ?? []).length === 1 && (await gate("button:has-text('Thử lại')").count()) === 0, nf.replace(/\s+/g, " "));
    await p.click("#g-empty"); check("M-056 empty state", /Chưa có mục nào/.test(await gate(".stateView").innerText()) && (await gate("#ready-list").count()) === 0);
    await p.click("#g-ready"); check("M-056 ready renders the children", (await p.locator("#ready-list li").count()) === 2);
    check("M-084 a page-level state is an h1; the same state in a card is an h2", (await p.locator("#notfound-page h1").count()) === 1 && (await p.locator("#notfound-page button").count()) === 0 && (await p.locator('section[aria-label="Cổng tải"]').locator("h1,h2").count()) === 0);
    await p.click("#g-error"); const ax = await axeProblems(p); check("axe (serious+critical) over the whole widgets page, light", ax.length === 0, ax.join(" ; "));
    await p.close(); }

  // ------------------------------------------------------------------------------------------------------------------------------------------ the same widgets on the dark Studio page
  { const p = await open("ui-widgets.html?dark");
    await p.click("#g-ready"); const ax = await axeProblems(p); check("axe (serious+critical) over the widgets page on the dark .studio theme", ax.length === 0, ax.join(" ; "));
    await shot(p, "widgets-dark.png"); await p.close(); }
} catch (e) { check("scenario aborted", false, e.message); }
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\nui-widgets: ${results.length - failed.length}/${results.length} passed  (HARNESS: no backend involved)`);
process.exit(failed.length ? 1 : 0);
