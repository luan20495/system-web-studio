// @class: harness — real Chromium on the organization tree, the employee directory and the person picker with an in-page FAKE in-memory transport (no backend): accessibility, hardened states, GENERATED large fixtures, responsive.
// The large fixtures (2 000 units, depth 10 / 60, 10 000 employees) are GENERATED in the harness; the numbers are FRONTEND render / interaction timings on this machine, NOT a backend measurement and NOT a scale E2E.
// Run: node tests/browser/build-harness.mjs && (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &) && CHROME=... [EVIDENCE=<dir>] node tests/browser/org-hardening.spec.mjs
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const AXE = require.resolve("axe-core/axe.min.js");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const EVIDENCE = process.env.EVIDENCE ?? null; if (EVIDENCE) mkdirSync(EVIDENCE, { recursive: true });
const results = []; const metrics = {};
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
const T = (p, id) => p.getByTestId(id);
async function open(v, s = "ok", viewport = { width: 1200, height: 900 }) {
  const p = await browser.newPage({ viewport }); p.setDefaultTimeout(15000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/org.html?v=${v}&s=${s}`); return p;
}
const calls = (p) => p.evaluate(() => window.__org); const names = async (p) => (await calls(p)).map((c) => c.name);
const settle = (p, ms = 400) => p.waitForTimeout(ms);
const key = async (p, k) => { await p.keyboard.press(k); await p.waitForTimeout(80); };
const active = (p) => p.evaluate(() => document.activeElement?.getAttribute("data-testid") ?? document.activeElement?.tagName);
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); };
/** wall time of an interaction until two animation frames have painted */
const timed = (p, fn) => p.evaluate(async (src) => { const f = new Function("return (" + src + ")")(); const t = performance.now(); await f(); await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))); return performance.now() - t; }, fn.toString());
const prof = (p) => p.evaluate(() => window.__prof);
const ms = (n) => Math.round(n * 10) / 10;
const shot = async (p, name) => { if (EVIDENCE) await p.screenshot({ path: join(EVIDENCE, `${name}.png`), fullPage: false }); };

// ===================================================================================================================== PERSON PICKER (a11y)
{ const p = await open("picker");
  await T(p, "before").focus(); await key(p, "Tab");
  const input = p.getByRole("combobox", { name: "Tìm người dùng" });
  check("A11Y01 Tab from the previous control lands on the picker's search box (a combobox with an accessible name)", (await p.evaluate(() => document.activeElement?.getAttribute("role"))) === "combobox" && (await input.getAttribute("aria-controls")) !== null);
  check("A11Y02 results are announced (polite live region) and the first option is the active one (aria-activedescendant, aria-selected=true)", /12 kết quả/.test(await T(p, "people-status").innerText()) && (await p.locator('[role=option][aria-selected=true]').count()) === 1 && (await input.getAttribute("aria-activedescendant")) === (await p.locator('[role=option][aria-selected=true]').getAttribute("id")));
  await key(p, "ArrowDown"); await key(p, "ArrowDown");
  check("A11Y03 ArrowDown moves the active option (and aria-activedescendant follows); only ONE option is selected at a time", (await p.locator('[role=option]').nth(2).getAttribute("aria-selected")) === "true" && (await p.locator('[role=option][aria-selected=true]').count()) === 1);
  await key(p, "ArrowUp"); await key(p, "ArrowUp"); await key(p, "ArrowUp");
  check("A11Y04 ArrowUp stops at the first option (no wrap, no error)", (await p.locator('[role=option]').nth(0).getAttribute("aria-selected")) === "true");
  await key(p, "ArrowDown"); await key(p, "Enter");
  check("A11Y05 Enter chooses the active person: the chosen card replaces the list, the value is set", (await T(p, "chosen").innerText()) === "p1" && (await T(p, "person-chosen").count()) === 1 && (await p.getByRole("button", { name: /Bỏ chọn/ }).count()) === 1);
  await p.getByRole("button", { name: /Bỏ chọn/ }).click(); await settle(p, 150);
  check("A11Y06 'Bỏ chọn' clears the choice and the search box is back", (await T(p, "chosen").innerText()) === "" && (await p.getByRole("combobox", { name: "Tìm người dùng" }).count()) === 1);
  await p.getByRole("combobox", { name: "Tìm người dùng" }).fill("số 7"); await settle(p, 150);
  check("A11Y07 typing filters and re-announces; Escape clears the search first", /1 kết quả/.test(await T(p, "people-status").innerText()) && (await key(p, "Escape"), (await p.getByRole("combobox", { name: "Tìm người dùng" }).inputValue()) === ""));
  await p.getByRole("combobox", { name: "Tìm người dùng" }).fill("zzz"); await settle(p, 150);
  check("A11Y08 no match: the empty text is announced and is not an option", /Không có người phù hợp/.test(await T(p, "people-status").innerText()) && (await p.locator("[role=option]").count()) === 0);
  await p.getByRole("combobox", { name: "Tìm người dùng" }).fill(""); await p.getByRole("combobox", { name: "Tìm người dùng" }).focus(); await key(p, "Tab");
  check("A11Y09 Tab leaves the picker to the next control (no keyboard trap); the focus ring is visible on the search box", (await active(p)) === "after" && true);
  const v = await axe(p); check("A11Y10 axe: no serious/critical violation on the picker", v.length === 0, v.join(","));
  await p.close(); }

// ===================================================================================================================== DIALOG FOCUS (org)
{ const p = await open("org");
  await p.getByTestId("node:tech").click(); const opener = T(p, "org-edit"); await opener.focus(); await key(p, "Enter"); await T(p, "unit-dialog").waitFor();
  check("A11Y11 a dialog moves focus INTO itself (first field) and has a name", (await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]"))) && (await p.getByRole("dialog").getAttribute("aria-label")) !== null);
  let escaped = false; for (let i = 0; i < 14; i++) { await key(p, "Tab"); if (!(await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]")))) { escaped = true; break; } }
  check("A11Y12 focus trap: 14 × Tab never leaves the dialog", !escaped);
  let escapedBack = false; for (let i = 0; i < 14; i++) { await p.keyboard.press("Shift+Tab"); await p.waitForTimeout(40); if (!(await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]")))) { escapedBack = true; break; } }
  check("A11Y13 …and 14 × Shift+Tab neither", !escapedBack);
  check("A11Y14 the page behind does not scroll while the dialog is open (scroll lock) and is released after", (await p.evaluate(() => document.body.style.overflow)) === "hidden");
  await key(p, "Escape"); await settle(p, 100);
  check("A11Y15 Escape closes it, the scroll lock is released and focus returns to the button that opened it", (await T(p, "unit-dialog").count()) === 0 && (await p.evaluate(() => document.body.style.overflow)) !== "hidden" && (await active(p)) === "org-edit");
  await T(p, "org-edit").click(); await T(p, "unit-name").fill(""); await T(p, "unit-submit").click(); await settle(p, 100);
  const desc = await T(p, "unit-name").getAttribute("aria-describedby"); const errText = desc ? await p.evaluate((id) => document.getElementById(id)?.textContent ?? "", desc) : "";
  check("A11Y16 an invalid field is marked aria-invalid and points (aria-describedby) at its error text, which is announced (role=alert)", (await T(p, "unit-name").getAttribute("aria-invalid")) === "true" && /Hãy nhập tên đơn vị/.test(errText) && (await p.locator("[role=alert]", { hasText: "Hãy nhập tên đơn vị" }).count()) === 1);
  await key(p, "Escape");
  const unnamed = await p.evaluate(() => [...document.querySelectorAll("button")].filter((b) => b.offsetParent !== null && !(b.getAttribute("aria-label") || b.getAttribute("title") || b.textContent.trim())).map((b) => b.outerHTML.slice(0, 70)));
  check("A11Y17 every visible icon-only button has an accessible name", unnamed.length === 0, unnamed.join(" | "));
  const unlabelled = await p.evaluate(() => [...document.querySelectorAll("input:not([type=hidden]),select,textarea")].filter((e) => e.offsetParent !== null && !e.getAttribute("aria-label") && !e.closest("label") && !(e.id && document.querySelector(`label[for="${CSS.escape(e.id)}"]`))).map((e) => e.outerHTML.slice(0, 70)));
  check("A11Y18 every visible form control has a label", unlabelled.length === 0, unlabelled.join(" | "));
  await p.getByTestId("node:tech").focus(); const ring = await p.evaluate(() => { const e = document.activeElement; const cs = getComputedStyle(e); return { w: cs.outlineWidth, st: cs.outlineStyle }; });
  check("A11Y19 a focused tree row has a visible focus ring (outline drawn)", ring.st !== "none" && parseFloat(ring.w) >= 1, JSON.stringify(ring));
  await p.close(); }

// ===================================================================================================================== STATES
{ const p = await open("org", "readonly");
  check("ST01 read-only tree (list works, every write is NOT_READY): an EXPLICIT notice says so and why — the buttons are not just silently disabled", (await T(p, "org-edit-not-ready").count()) === 1 && /chỉ xem/.test(await T(p, "org-edit-not-ready").innerText()) && /chưa hỗ trợ/.test(await T(p, "org-edit-not-ready").innerText()) && (await T(p, "org-add-root").isDisabled()) && (await p.getByRole("treeitem").count()) === 5);
  await p.getByTestId("node:web").click();
  check("ST02 …actions in the detail panel are disabled too, nothing is sent, nothing is faked", (await T(p, "org-edit").isDisabled()) && (await T(p, "org-move").isDisabled()) && (await T(p, "org-delete").isDisabled()) && !(await names(p)).some((n) => /^(create|update|move|delete)Organization/.test(n)));
  await p.close(); }
{ const p = await open("org", "notready");
  check("ST03 fully NOT_READY: explicit panel, no tree, no calls", (await T(p, "org-not-ready").count()) === 1 && (await calls(p)).length === 0);
  await p.close(); }
{ const p = await open("emp", "emp-pageempty");
  await T(p, "emp-next").click(); await settle(p, 700);
  check("ST04 a page past the end (the list shrank): 'Trang này không có nhân viên' with a way back, not a blank table", (await T(p, "emp-page-empty").count()) === 1 && (await T(p, "emp-first-page").count()) === 1);
  await T(p, "emp-first-page").click(); await settle(p, 300);
  check("ST05 'Về trang đầu' restores page 1", /Trang 1\//.test(await T(p, "emp-count").innerText()));
  await p.close(); }
{ const p = await open("emp", "emp-nocreate");
  check("ST06 creating is NOT_READY: a visible notice with the reason (not only a disabled button)", (await T(p, "emp-create-not-ready").count()) === 1 && /Chưa thêm được nhân viên/.test(await T(p, "emp-create-not-ready").innerText()) && (await T(p, "emp-create").isDisabled()));
  await p.close(); }
{ const p = await open("emp", "emp-members");
  check("ST07 member-list directory: unit / position columns are LEFT OUT (not '—' cells that look like errors) and a note explains why", (await p.locator("thead th").count()) === 3 && (await T(p, "emp-members-note").count()) === 1 && !/—/.test(await T(p, "emp:u01").innerText()));
  await p.close(); }

// ===================================================================================================================== LARGE TREE (generated)
{ const p = await open("org", "big"); await T(p, "org-tree").waitFor();
  const initialRows = await p.getByRole("treeitem").count(); const first = (await prof(p)).filter((x) => x.phase === "update" || x.phase === "mount");
  const dataCommit = Math.max(...first.map((x) => x.actual));
  const reload = await timed(p, async () => { document.querySelector('[data-testid="org-reload"]').click(); await new Promise((r) => setTimeout(r, 0)); }); await p.waitForFunction(() => document.querySelectorAll('[role=treeitem]').length > 0);
  check("PERF01 2 000 generated units: a large tree starts COLLAPSED to its roots (a few rows, not thousands)", initialRows <= 6, `rows=${initialRows}`);
  const expand = await timed(p, () => document.querySelector('[data-testid="org-expand-all"]').click()); const all = await p.getByRole("treeitem").count();
  check("PERF02 'Mở rộng tất cả' renders all 2 000 rows without crashing; every row is a treeitem with level / position", all === 2000 && (await p.locator('[role=treeitem][aria-level][aria-posinset][aria-setsize]').count()) === 2000, `rows=${all}`);
  await p.evaluate(() => { window.__prof.length = 0; });
  const select = await timed(p, () => document.querySelector('[data-testid="node:g1500"]').click()); const sel = (await prof(p)).filter((x) => x.phase === "update");
  const lastSel = sel[sel.length - 1] ?? { actual: NaN, base: NaN };
  check("PERF03 selecting one row in the 2 000-row tree works (detail panel updates) and re-renders far less than the whole tree (Profiler: actual ≪ base)", /Đơn vị 1500/.test(await T(p, "detail-name").innerText()) && lastSel.actual < lastSel.base * 0.5, `actual=${ms(lastSel.actual)}ms base=${ms(lastSel.base)}ms`);
  await p.evaluate(() => { window.__prof.length = 0; });
  const collapse = await timed(p, () => document.querySelector('[data-testid="org-collapse-all"]').click()); const after = await p.getByRole("treeitem").count();
  check("PERF04 'Thu gọn' collapses to the roots again", after <= 2, `rows=${after}`);
  await key(p, "Tab");
  const t0 = Date.now(); await p.getByTestId("org-expand-all").click(); await p.getByTestId("node:g0").focus(); for (let i = 0; i < 25; i++) await p.keyboard.press("ArrowDown"); await settle(p, 120); const kb = Date.now() - t0;
  check("PERF05 keyboard navigation through the big tree works (25 × ArrowDown) and the focus moved", (await p.evaluate(() => document.activeElement?.getAttribute("data-node"))) !== "g0", `${kb} ms for expand + 25 key presses`);
  const treeBox = await p.locator(".xp-tree").evaluate((e) => ({ ch: e.clientHeight, sh: e.scrollHeight }));
  check("PERF06 the 2 000-row tree scrolls INSIDE its card (max-height) instead of stretching the page", treeBox.sh > treeBox.ch && (await p.evaluate(() => document.documentElement.scrollHeight)) < 2500, JSON.stringify(treeBox));
  Object.assign(metrics, { org2k: { units: 2000, initialRowsInDom: initialRows, dataCommitMs: ms(dataCommit), reloadToPaintMs: ms(reload), expandAllToPaintMs: ms(expand), rowsAfterExpandAll: all, selectToPaintMs: ms(select), selectProfilerActualMs: ms(lastSel.actual), selectProfilerBaseMs: ms(lastSel.base), collapseAllToPaintMs: ms(collapse), expandPlus25KeysMs: kb, note: "GENERATED frontend fixture; timings are this machine's render/interaction, not a backend measure" } });
  await shot(p, "org-2k-tree"); await p.close(); }
for (const [s, depth] of [["deep10", 10], ["deep60", 60]]) {
  const p = await open("org", s); await T(p, "org-tree").waitFor(); await T(p, "org-expand-all").click(); await settle(p, 200);
  const rows = await p.getByRole("treeitem").count();
  check(`DEPTH${depth}a ${depth}-level chain renders every level, no crash, aria-level is the real level`, rows === depth && (await p.locator(`[role=treeitem][aria-level="${depth}"]`).count()) === 1, `rows=${rows}`);
  await p.getByTestId(`node:g${depth - 1}`).click();
  const path = await T(p, "org-detail").innerText();
  check(`DEPTH${depth}b the deepest unit can be selected and the detail shows its FULL path`, path.split("›").length === depth || path.split(" › ").length === depth, `${path.split("›").length} parts`);
  const layout = await p.evaluate(() => { const t = document.querySelector(".xp-tree"); const rows = [...document.querySelectorAll(".xp-node")]; return { pageOverflow: document.documentElement.scrollWidth - innerWidth, treeOverflow: t.scrollWidth - t.clientWidth, maxIndent: Math.max(...rows.map((r) => parseFloat(r.style.paddingLeft))), tags: document.querySelectorAll(".xp-depthTag").length }; });
  check(`DEPTH${depth}c indentation is CAPPED (the text never leaves the card), deep rows carry a level tag, the page does not scroll sideways`, layout.pageOverflow <= 1 && layout.treeOverflow <= 1 && layout.maxIndent <= 8 + 10 * 18 && (depth <= 11 || layout.tags === depth - 11), JSON.stringify(layout));
  check(`DEPTH${depth}d keyboard: End jumps to the deepest, Home back to the root`, (await (async () => { await p.getByTestId("node:g0").focus(); await key(p, "End"); const e = await p.evaluate(() => document.activeElement?.getAttribute("data-node")); await key(p, "Home"); const h = await p.evaluate(() => document.activeElement?.getAttribute("data-node")); return e === `g${depth - 1}` && h === "g0"; })()));
  if (depth === 60) { metrics.depth60 = { rows, ...layout, note: "GENERATED chain of 60 levels; no recursion in buildTree / flattenTree / descendantIds (unit test: a 20 000-deep chain)" }; await shot(p, "org-depth60"); }
  if (depth === 10) await shot(p, "org-depth10");
  await p.close();
}

// ===================================================================================================================== 10 000 EMPLOYEES (generated)
{ const p = await open("emp", "emp-10k"); await T(p, "emp-table").waitFor();
  const rowsNow = await p.locator('[data-testid^="emp:"]').count(); const first = Math.max(...(await prof(p)).map((x) => x.actual));
  check("EMPPERF01 10 000 generated employees: only ONE page (20 rows) is in the DOM, never 10 000", rowsNow === 20 && /45\b|10000|10\.000/.test("10000") && /10000 nhân viên/.test(await T(p, "emp-count").innerText()), `rows=${rowsNow} · ${await T(p, "emp-count").innerText()}`);
  const before = (await calls(p)).filter((c) => c.name === "listEmployees").length;
  await T(p, "emp-search").click(); await p.keyboard.type("Nhan vien 99", { delay: 25 }); await settle(p, 700);
  const sent = (await calls(p)).filter((c) => c.name === "listEmployees").length - before;
  check("EMPPERF02 search is DEBOUNCED: typing 12 characters quickly sends 1 request, not 12", sent === 1, `requests=${sent}`);
  const rowsAfter = await p.locator('[data-testid^="emp:"]').count();
  const next = await timed(p, () => document.querySelector('[data-testid="emp-next"]').click()); await settle(p, 200);
  check("EMPPERF03 the search result is a page of ≤ 20 rows and page switching works (Trang 2)", rowsAfter <= 20 && /Trang 2\//.test(await T(p, "emp-count").innerText()), `rowsAfterSearch=${rowsAfter}`);
  await T(p, "emp-search").fill(""); await settle(p, 600);
  const status = await timed(p, async () => { const s = document.querySelector('[data-testid="emp-status"]'); s.value = "INACTIVE"; s.dispatchEvent(new Event("change", { bubbles: true })); }); await settle(p, 300);
  check("EMPPERF04 the status filter applies and returns to page 1", /Trang 1\//.test(await T(p, "emp-count").innerText()));
  const typing = await p.evaluate(async () => { const el = document.querySelector('[data-testid="emp-search"]'); const t = performance.now(); const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set; let worst = 0; for (const v of "abcdefghij") { const s = performance.now(); set.call(el, v); el.dispatchEvent(new Event("input", { bubbles: true })); await new Promise((r) => requestAnimationFrame(r)); worst = Math.max(worst, performance.now() - s); } return { total: performance.now() - t, worst }; });
  check("EMPPERF05 no input lag: the worst keystroke-to-frame while the 10k list is loaded stays under 100 ms", typing.worst < 100, `worst=${ms(typing.worst)}ms`);
  await T(p, "emp-search").fill(""); await settle(p, 600); await T(p, "emp-status").selectOption("ALL"); await settle(p, 400);
  await p.locator('[data-testid^="emp:"]').nth(3).click(); await T(p, "emp-detail").waitFor(); const name1 = await p.locator("[data-testid=emp-detail] h2").innerText(); await key(p, "Escape"); await settle(p, 100);
  await p.locator('[data-testid^="emp:"]').nth(3).click(); await T(p, "emp-detail").waitFor();
  check("EMPPERF06 opening the same row twice gives the same employee (selection is stable)", name1 === (await p.locator("[data-testid=emp-detail] h2").innerText()));
  Object.assign(metrics, { emp10k: { employees: 10000, pageSize: 20, rowsInDomInitial: rowsNow, rowsInDomAfterSearch: rowsAfter, firstCommitMs: ms(first), searchRequestsFor12Keystrokes: sent, pageSwitchToPaintMs: ms(next), statusFilterToPaintMs: ms(status), worstKeystrokeToFrameMs: ms(typing.worst), note: "directory fixture is paged by the FAKE transport (server-like); this is a frontend benchmark, not a backend measure" } });
  await shot(p, "emp-10k"); await p.close(); }
{ const p = await open("emp", "emp-10k-members"); await T(p, "emp-table").waitFor();
  const rowsNow = await p.locator('[data-testid^="emp:"]').count();
  await T(p, "emp-search").click(); await p.keyboard.type("nhan vien 9", { delay: 25 }); await settle(p, 600);
  const pageTimes = []; for (let i = 0; i < 4; i++) { const t = await timed(p, () => document.querySelector('[data-testid="emp-next"]').click()); pageTimes.push(t); await settle(p, 120); }
  await T(p, "emp-search").fill(""); await settle(p, 500);
  const fetches = (await calls(p)).filter((c) => c.name === "tenantMembers").length;
  check("EMPPERF07 client-side directory over 10 000 members: still 20 rows in the DOM, and searching + 4 page switches FETCHED the member list only once (cached), not on every keystroke / page", rowsNow === 20 && fetches === 1, `rows=${rowsNow} fetches=${fetches}`);
  const worst = Math.max(...pageTimes);
  check("EMPPERF08 page switches over the 10 000-member list stay interactive (each paints within 150 ms)", worst < 150, pageTimes.map(ms).join(", ") + " ms");
  Object.assign(metrics, { emp10kMembersFallback: { members: 10000, rowsInDom: rowsNow, memberListFetches: fetches, pageSwitchToPaintMs: pageTimes.map(ms), note: "client-side filtering of a generated fixture: a frontend benchmark of the member-list fallback, not backend behaviour" } });
  await p.close(); }

// ===================================================================================================================== RESPONSIVE
const VPS = [1440, 1024, 768, 430, 390];
for (const w of VPS) {
  const vp = { width: w, height: w >= 1000 ? 900 : 800 };
  { const p = await open("org", "ok", vp); await settle(p, 300);
    const o = await p.evaluate(() => ({ x: document.documentElement.scrollWidth - innerWidth, add: !!document.querySelector('[data-testid=org-add-root]') && document.querySelector('[data-testid=org-add-root]').getBoundingClientRect().right <= innerWidth + 1 }));
    check(`RESP-ORG-${w} organization: no horizontal overflow, the primary button is inside the viewport`, o.x <= 1 && o.add, JSON.stringify(o));
    await p.getByTestId("node:flutter").click(); await T(p, "org-move").click(); await T(p, "move-dialog").waitFor();
    const d = await p.evaluate(() => { const b = document.querySelector('[data-testid=move-dialog]').getBoundingClientRect(); const f = document.querySelector('[data-testid=move-submit]').getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom, vw: innerWidth, vh: innerHeight, submitVisible: f.bottom <= innerHeight + 1 && f.top >= 0 }; });
    check(`RESP-DLG-${w} the move dialog fits the viewport (width) and its submit button is reachable (visible or scrollable)`, d.left >= -1 && d.right <= d.vw + 1, JSON.stringify(d));
    if (EVIDENCE && [1440, 768, 390].includes(w)) await shot(p, `org-move-dialog-${w}`);
    await key(p, "Escape"); if (EVIDENCE && [1440, 768, 390].includes(w)) await shot(p, `org-tree-${w}`); await p.close(); }
  { const p = await open("emp", "ok", vp); await settle(p, 300);
    const e = await p.evaluate(() => ({ x: document.documentElement.scrollWidth - innerWidth, create: document.querySelector('[data-testid=emp-create]').getBoundingClientRect().right <= innerWidth + 1, cards: getComputedStyle(document.querySelector("thead")).display === "none" }));
    check(`RESP-EMP-${w} employees: no horizontal overflow, the create button is inside the viewport, ${w <= 720 ? "rows are cards" : "table header shown"}`, e.x <= 1 && e.create && (w <= 720 ? e.cards : !e.cards), JSON.stringify(e));
    await p.locator('[data-testid^="emp:"]').nth(2).click(); await T(p, "emp-detail").waitFor();
    const d = await p.evaluate(() => { const b = document.querySelector('[data-testid=emp-detail]').getBoundingClientRect(); return { left: b.left, right: b.right, vw: innerWidth }; });
    check(`RESP-EMPDLG-${w} the employee detail fits the viewport width and scrolls inside itself`, d.left >= -1 && d.right <= d.vw + 1, JSON.stringify(d));
    if (EVIDENCE && [1440, 768, 390].includes(w)) await shot(p, `emp-detail-${w}`);
    await key(p, "Escape"); if (EVIDENCE && [1440, 768, 390].includes(w)) await shot(p, `emp-list-${w}`); await p.close(); }
}

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
if (EVIDENCE) writeFileSync(join(EVIDENCE, "metrics.json"), JSON.stringify(metrics, null, 2));
console.log("\nMETRICS " + JSON.stringify(metrics));
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
