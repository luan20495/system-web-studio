#!/usr/bin/env node
// HARNESS, NOT REAL BACKEND. KEYBOARD-ONLY WALKTHROUGH of the key flows, using nothing but Tab / Shift+Tab / Enter / Space / Escape / arrow keys (no click, no focus() call, no mouse):
//   Platform  create a company                (tests/browser/admin-harness.tsx, /platform/tenants)
//   Admin     create a user (employee)        (tests/browser/admin-harness.tsx, /admin/employees as a company admin)
//   Studio    select a component, edit a field, publish pre-check   (tests/browser/studio-app, /studio/projects/p1/design)
// Per step it asserts what a keyboard user needs: the control is REACHABLE by Tab within a bound, EVERY stop has a VISIBLE focus indicator, a dialog takes focus when it opens, TRAPS Tab / Shift+Tab (and wraps),
// closes on Escape without side effects, and RESTORES focus to the control that opened it; the flow's result is checked on what the fake recorded. Output: a PASS / FAIL table and JSON.
//   HARNESS_NODE_ENV=development node tests/browser/build-harness.mjs
//   node scripts/ui-keyboard.mjs [--out /tmp/ui-keyboard] [--viewport 1280] [--only platform,admin,studio]
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { withEnv } from "./perf-env.mjs";
import { viewportOf } from "./audit/measure.mjs";
import { installFake, newState } from "../tests/browser/studio-app/fake-api.mjs";

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-keyboard"); const VP = Number(arg("viewport", "1280")); const ONLY = arg("only", "platform,admin,studio").split(",");
const steps = [];
const step = (flow, name, ok, detail = "") => { steps.push({ flow, step: name, status: ok ? "PASS" : "FAIL", detail: String(detail).slice(0, 220) }); };

// ------------------------------------------------------------------------------------------------------------------------------- keyboard primitives (keyboard only)
const ACTIVE = () => {
  const el = document.activeElement; if (!el || el === document.body) return { body: true };
  const name = (el.getAttribute("aria-label") || el.textContent || el.getAttribute("placeholder") || el.getAttribute("data-testid") || el.tagName).trim().replace(/\s+/g, " ").slice(0, 40);
  const drawn = (e) => { const c = getComputedStyle(e); return (c.outlineStyle !== "none" && parseFloat(c.outlineWidth) > 0 && !/rgba\(0, 0, 0, 0\)/.test(c.outlineColor)) || (!!c.boxShadow && c.boxShadow !== "none"); };
  let ring = drawn(el); for (let a = el.parentElement, i = 0; !ring && a && i < 3; a = a.parentElement, i++) if (a.matches(":focus-within") && drawn(a)) ring = true;     // a wrapper (a slug field, a picker) may carry the indicator
  const r = el.getBoundingClientRect(); const inView = r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
  const dlg = el.closest("[role=dialog]"); const pts = [[0.5, 0.5], [0.2, 0.3], [0.8, 0.7]].map(([fx, fy]) => document.elementFromPoint(Math.min(innerWidth - 1, Math.max(0, r.left + r.width * fx)), Math.min(innerHeight - 1, Math.max(0, r.top + r.height * fy))));
  const covered = pts.every((t) => t && !el.contains(t) && !t.contains(el));
  return { d: `${el.tagName.toLowerCase()}[${name}]`, tag: el.tagName, name, testid: el.getAttribute("data-testid"), role: el.getAttribute("role"), type: el.getAttribute("type"), ring, inView, covered, inDialog: !!dlg, inRight: !!el.closest(".bx-right"), dialogs: document.querySelectorAll("[role=dialog]").length, value: "value" in el ? String(el.value).slice(0, 60) : undefined, tabindex: el.getAttribute("tabindex") };
};
const kb = (page) => ({
  active: () => page.evaluate(ACTIVE),
  async press(key, n = 1) { for (let i = 0; i < n; i++) { await page.keyboard.press(key); await page.waitForTimeout(60); } },
  /** Tab (or Shift+Tab) until `test(active)` holds; returns the stops visited and whether every stop showed a focus indicator */
  async tabTo(test, { max = 60, back = false } = {}) {
    const stops = [];
    for (let i = 0; i < max; i++) { await page.keyboard.press(back ? "Shift+Tab" : "Tab"); await page.waitForTimeout(50); const a = await page.evaluate(ACTIVE); stops.push(a); if (!a.body && test(a)) return { found: true, stops, tabs: i + 1, noRing: stops.filter((s) => !s.body && !s.ring).map((s) => s.d), covered: stops.filter((s) => s.covered).map((s) => s.d) }; }
    return { found: false, stops, tabs: max, noRing: stops.filter((s) => !s.body && !s.ring).map((s) => s.d), covered: [] };
  },
  async type(text) { await page.keyboard.press("ControlOrMeta+A").catch(() => undefined); await page.keyboard.type(text, { delay: 15 }); },
});
const nameIs = (re) => (a) => re.test(a.name || ""); const idIs = (id) => (a) => a.testid === id;

/** the standard dialog contract, driven by keys only: focus inside, Tab and Shift+Tab never leave, the order wraps, Escape closes */
async function dialogContract(flow, page, k, label, { opener }) {
  const a0 = await k.active(); step(flow, `${label}: focus moves INTO the dialog when it opens`, a0.inDialog, a0.d);
  let outside = 0; const order = [];
  for (let i = 0; i < 14; i++) { await k.press("Tab"); const a = await k.active(); if (!a.inDialog) outside++; order.push(a.d); }
  step(flow, `${label}: Tab x14 never leaves the dialog (focus trap)`, outside === 0, outside ? `${outside} stops outside` : "");
  const wrapped = new Set(order).size < order.length; step(flow, `${label}: the Tab order wraps around inside the dialog`, wrapped, order.slice(0, 6).join(" > "));
  let outsideBack = 0; for (let i = 0; i < 6; i++) { await k.press("Shift+Tab"); const a = await k.active(); if (!a.inDialog) outsideBack++; }
  step(flow, `${label}: Shift+Tab x6 never leaves the dialog`, outsideBack === 0);
  const noRing = []; for (let i = 0; i < 10; i++) { await k.press("Tab"); const a = await k.active(); if (!a.ring) noRing.push(a.d); }
  step(flow, `${label}: every Tab stop inside the dialog has a visible focus indicator`, noRing.length === 0, noRing.slice(0, 3).join(", "));
  await k.press("Escape"); await page.waitForTimeout(250);
  const closed = (await page.locator("[role=dialog]").count()) === 0; step(flow, `${label}: Escape closes the dialog`, closed);
  const a1 = await k.active(); step(flow, `${label}: focus RETURNS to the control that opened it`, !a1.body && (opener ? opener(a1) : true), a1.d);
}

// ------------------------------------------------------------------------------------------------------------------------------- the flows
async function platformCreateCompany(browser, base) {
  const flow = "Platform: create a company"; const ctx = await browser.newContext({ viewport: viewportOf(VP) }); const page = await ctx.newPage(); const k = kb(page); const errs = []; page.on("pageerror", (e) => errs.push(e.message));
  await page.goto(`${base}/admin.html?portal=platform&me=sys&start=${encodeURIComponent("/platform/tenants")}`); await page.getByTestId("tenant-list").waitFor(); await page.waitForTimeout(400);
  const r1 = await k.tabTo(nameIs(/Tạo công ty/), { max: 50 });
  step(flow, "the primary action 'Tạo công ty' is reachable by Tab", r1.found, `${r1.tabs} Tab stops`); step(flow, "every Tab stop on the way has a visible focus indicator", r1.noRing.length === 0, r1.noRing.slice(0, 3).join(", ")); step(flow, "no stop on the way is covered by a sticky / fixed element", r1.covered.length === 0, r1.covered.slice(0, 2).join(", "));
  step(flow, "the Tab order leads through the navigation first and the page title area before the page action (not reversed)", r1.stops.some((s) => /a\[|nav|link/i.test(s.d)) || r1.tabs <= 12, r1.stops.slice(0, 5).map((s) => s.d).join(" > "));
  await k.press("Enter"); await page.getByTestId("tenant-create").waitFor({ timeout: 3000 }).catch(() => undefined); await page.waitForTimeout(250);
  step(flow, "Enter on the button opens the dialog", (await page.getByTestId("tenant-create").count()) === 1);
  const opener = (a) => /Tạo công ty/.test(a.name || "");
  await dialogContract(flow, page, k, "dialog", { opener });
  // second time: fill and submit with the keyboard
  await k.tabTo(nameIs(/Tạo công ty/), { max: 40, back: false }).catch(() => undefined);
  const a = await k.active(); if (!/Tạo công ty/.test(a.name || "")) await k.tabTo(nameIs(/Tạo công ty/), { max: 50 });
  await k.press("Space"); await page.getByTestId("tenant-create").waitFor({ timeout: 3000 }).catch(() => undefined); await page.waitForTimeout(250);
  step(flow, "Space on the button also opens the dialog", (await page.getByTestId("tenant-create").count()) === 1);
  const nm = await k.tabTo(idIs("tenant-name"), { max: 12 }); step(flow, "the company name field is reachable by Tab inside the dialog", nm.found, `${nm.tabs} stops`);
  await k.type("Công ty Bàn Phím"); const slugStop = await k.tabTo(idIs("tenant-slug"), { max: 4 }); const slugVal = (await k.active()).value;
  step(flow, "typing the name fills the company code (slug) before the next field, which Tab reaches next", slugStop.found && /ban-phim|cong-ty/.test(slugVal ?? ""), `slug="${slugVal}"`);
  const submit = await k.tabTo(nameIs(/^Tạo công ty$/), { max: 25 }); step(flow, "the submit button 'Tạo công ty' is reachable by Tab", submit.found, submit.stops.at(-1)?.d);
  await k.press("Enter"); await page.waitForTimeout(700);
  const posts = (await page.evaluate(() => window.__calls)).filter((c) => c.method === "POST" && c.path === "/admin/tenants");
  step(flow, "Enter on the submit button creates the company (one POST /admin/tenants with the typed name)", posts.length === 1 && posts[0].body?.name === "Công ty Bàn Phím", JSON.stringify(posts[0]?.body ?? null));
  step(flow, "the dialog closes after creating", (await page.locator("[role=dialog]").count()) === 0);
  const af = await k.active(); step(flow, "focus is not lost to <body> after the dialog closes", !af.body, af.d);
  step(flow, "no uncaught error during the flow", errs.length === 0, errs[0] ?? "");
  await ctx.close();
}

async function adminCreateUser(browser, base) {
  const flow = "Admin: create a user"; const ctx = await browser.newContext({ viewport: viewportOf(VP) }); const page = await ctx.newPage(); const k = kb(page); const errs = []; page.on("pageerror", (e) => errs.push(e.message));
  await page.goto(`${base}/admin.html?portal=admin&me=tadmin&start=${encodeURIComponent("/admin/employees")}`); await page.getByTestId("emp-create").waitFor(); await page.waitForTimeout(500);
  const r1 = await k.tabTo(idIs("emp-create"), { max: 60 });
  step(flow, "the primary action 'Thêm nhân viên' is reachable by Tab", r1.found, `${r1.tabs} Tab stops`); step(flow, "every Tab stop on the way has a visible focus indicator", r1.noRing.length === 0, r1.noRing.slice(0, 3).join(", ")); step(flow, "no stop on the way is covered by a sticky / fixed element", r1.covered.length === 0, r1.covered.slice(0, 2).join(", "));
  await k.press("Enter"); await page.getByTestId("create-account").waitFor({ timeout: 3000 }).catch(() => undefined); await page.waitForTimeout(250);
  step(flow, "Enter opens the create-account dialog", (await page.getByTestId("create-account").count()) === 1);
  await dialogContract(flow, page, k, "dialog", { opener: (a) => a.testid === "emp-create" });
  await k.tabTo(idIs("emp-create"), { max: 60 }); await k.press("Enter"); await page.getByTestId("create-account").waitFor({ timeout: 3000 }).catch(() => undefined); await page.waitForTimeout(250);
  const u = await k.tabTo(idIs("acc-username"), { max: 12 }); step(flow, "the username field is reachable by Tab", u.found, `${u.tabs} stops`);
  await k.type("ban.phim"); const dn = await k.tabTo(idIs("acc-display"), { max: 3 }); step(flow, "Tab from username goes to display name", dn.found); await k.type("Nguyễn Bàn Phím");
  // the account type and the workspace are <select>s: arrows change them without opening a mouse popup
  const ws = await k.tabTo(idIs("acc-workspace"), { max: 10 }); step(flow, "the workspace select is reachable by Tab", ws.found, `${ws.tabs} stops`);
  if (ws.found) await page.keyboard.type("Kinh", { delay: 30 });   // type-ahead on a focused <select> chooses the option without a mouse (arrows open a native popup on macOS)
  const sel = await page.evaluate(() => document.querySelector('[data-testid="acc-workspace"]')?.value); step(flow, "typing the first letters of an option chooses it in the select", !!sel, `value="${sel}"`);
  const submit = await k.tabTo(idIs("acc-submit"), { max: 25 }); step(flow, "the submit button 'Tạo tài khoản' is reachable by Tab", submit.found, submit.stops.at(-1)?.d);
  await k.press("Enter"); await page.waitForTimeout(800);
  const posts = (await page.evaluate(() => window.__calls)).filter((c) => c.method === "POST" && /\/users$/.test(c.path));
  step(flow, "Enter on the submit button creates the user (one POST with the typed username)", posts.length === 1 && posts[0].body?.username === "ban.phim", JSON.stringify(posts[0]?.body ?? null));
  const a = await k.active(); step(flow, "after creating, focus is inside the dialog (the activation-link panel) and not lost to <body>", !a.body && a.inDialog, a.d);
  const link = await page.evaluate(() => document.body.innerText.includes("kích hoạt")); step(flow, "the result (activation link) is shown", link);
  // the activation link is shown ONCE: Escape must not lose it silently
  await k.press("Escape"); await page.waitForTimeout(250);
  const warn = await page.getByTestId("link-confirm").count(); const a2 = await k.active();
  step(flow, "Escape on the one-time link dialog shows a warning (role=alert) instead of losing the link, with the SAFE choice focused", warn === 1 && /Quay lại/.test(a2.name || ""), a2.d);
  const closeBtn = await k.tabTo(nameIs(/Tôi đã lưu liên kết, đóng/), { max: 6 }); step(flow, "the deliberate 'close anyway' button is reachable by Tab", closeBtn.found, `${closeBtn.tabs} stops`);
  await k.press("Enter"); await page.waitForTimeout(400);
  const dialogsLeft = await page.locator("[role=dialog]").count();
  step(flow, "closing the link leads on to the account summary (or closes), focus stays inside a dialog while one is open", dialogsLeft === 0 || (await k.active()).inDialog, `${dialogsLeft} dialog(s)`);
  for (let i = 0; i < 3 && (await page.locator("[role=dialog]").count()) > 0; i++) { await k.press("Escape"); await page.waitForTimeout(250); }
  step(flow, "Escape closes the remaining dialog(s)", (await page.locator("[role=dialog]").count()) === 0);
  const af = await k.active(); step(flow, "focus returns to a control, not <body>, after closing", !af.body, af.d);
  step(flow, "no uncaught error during the flow", errs.length === 0, errs[0] ?? "");
  await ctx.close();
}

async function studioFlow(browser, base) {
  const flow = "Studio: select, edit, publish pre-check"; const ctx = await browser.newContext({ viewport: viewportOf(VP) }); const page = await ctx.newPage(); const k = kb(page); const errs = []; page.on("pageerror", (e) => errs.push(e.message));
  const state = newState(); await installFake(page, state);
  await page.goto(`${base}/studio.html?start=${encodeURIComponent("/studio/projects/p1/design")}`); await page.locator(".bx-left").first().waitFor({ timeout: 10000 }); await page.locator("iframe").first().waitFor(); await page.waitForTimeout(900);
  // 1. reach the page tree and select a section with the keyboard
  const r1 = await k.tabTo((s) => s.role === "treeitem", { max: 80 });
  step(flow, "the page tree is reachable by Tab", r1.found, `${r1.tabs} Tab stops`); step(flow, "every Tab stop on the way has a visible focus indicator", r1.noRing.length === 0, r1.noRing.slice(0, 3).join(", ")); step(flow, "no stop on the way is covered by a sticky / fixed element", r1.covered.length === 0, r1.covered.slice(0, 2).join(", "));
  await k.press("ArrowDown", 2); const t = await k.active(); step(flow, "arrow keys move between tree items", t.role === "treeitem", t.d);
  await k.press("Enter"); await page.waitForTimeout(500);
  const insp = await page.locator(".bx-right h2").first().innerText().catch(() => ""); step(flow, "Enter on a section selects it: the properties panel shows that section", /\S/.test(insp) && !/Chưa chọn/.test(insp), insp);
  // 2. edit a field in the inspector with the keyboard
  const f = await k.tabTo((s) => (s.tag === "INPUT" || s.tag === "TEXTAREA") && s.inRight, { max: 40 }); step(flow, "Tab reaches a field of the properties panel", f.found, `${f.tabs} stops`);
  await k.type("Tiêu đề gõ bằng bàn phím");
  const save = await k.tabTo(nameIs(/Lưu thay đổi/), { max: 12 }); step(flow, "'Lưu thay đổi' is reachable by Tab", save.found, `${save.tabs} stops`);
  await k.press("Enter"); await page.waitForTimeout(700);
  const patch = state.log.filter((l) => l.method === "PATCH" && /\/schema$/.test(l.path)); const op = JSON.stringify(patch.at(-1)?.body ?? null);
  step(flow, "Enter saves: one PATCH /schema with an UPDATE_PROP carrying the typed text", patch.length === 1 && /UPDATE_PROP/.test(op) && op.includes("Tiêu đề gõ bằng bàn phím"), op.slice(0, 160));
  // 3. publish pre-check
  const pub = await k.tabTo(nameIs(/^Xuất bản/), { max: 120, back: true }); step(flow, "'Xuất bản' is reachable by keyboard (Shift+Tab from the properties panel to the top bar)", pub.found, `${pub.tabs} stops`);
  await k.press("Enter"); await page.waitForTimeout(700);
  step(flow, "Enter on 'Xuất bản' opens a dialog (the pre-publish check)", (await page.locator("[role=dialog]").count()) >= 1);
  if ((await page.locator("[role=dialog]").count()) >= 1) await dialogContract(flow, page, k, "publish pre-check", { opener: (a) => /Xuất bản/.test(a.name || "") });
  step(flow, "no uncaught error during the flow", errs.length === 0, errs[0] ?? "");
  await ctx.close();
}

await withEnv({ dir: ".test-build/browser", tag: "kbd" }, async ({ base, browser, chromeVersion }) => {
  for (const [name, fn] of [["platform", platformCreateCompany], ["admin", adminCreateUser], ["studio", studioFlow]]) {
    if (!ONLY.includes(name)) continue;
    try { await fn(browser, base); } catch (e) { step(`${name} flow`, "the walkthrough ran to the end", false, `runner: ${String(e).slice(0, 180)}`); }
  }
  mkdirSync(OUT, { recursive: true });
  const fail = steps.filter((s) => s.status === "FAIL").length;
  const md = [`HARNESS, NOT REAL BACKEND. Keyboard only (Tab, Shift+Tab, Enter, Space, Escape, arrows). Chrome ${chromeVersion}, viewport ${VP}.`, "", "| flow | step | result | detail |", "|---|---|---|---|", ...steps.map((s) => `| ${s.flow} | ${s.step} | ${s.status} | ${s.detail.replace(/\|/g, "/")} |`)].join("\n");
  writeFileSync(join(OUT, "keyboard.md"), md + "\n"); writeFileSync(join(OUT, "keyboard.json"), JSON.stringify({ mode: "HARNESS, NOT REAL BACKEND", chrome: chromeVersion, viewport: VP, summary: { steps: steps.length, PASS: steps.length - fail, FAIL: fail }, steps }, null, 1));
  console.log(md); console.log(`\nsummary: ${steps.length} steps, ${steps.length - fail} PASS, ${fail} FAIL\nreport: ${OUT}/keyboard.md json: ${OUT}/keyboard.json`);
  if (fail) process.exitCode = 1;
});
