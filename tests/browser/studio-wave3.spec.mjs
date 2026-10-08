// @class: harness — real Chromium on the REAL Studio app with an in-test FAKE of /api/v1 (tests/browser/studio-app/); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Regression checks of Studio wave-3 issues (docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md): M-011 + M-104 (AI / Code workspace on a phone), ...
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/studio-wave3.spec.mjs
import { open, check, wait, finish, newState } from "./studio-app/lib.mjs";
import { launch } from "./lib/spec.mjs";
import { newCodeState } from "./studio-app/fake-api.mjs";
import { readFileSync } from "node:fs";
const AXE = readFileSync(new URL("../../node_modules/axe-core/axe.min.js", import.meta.url), "utf8");
const axe = async (p) => { await p.addScriptTag({ content: AXE }); return p.evaluate(async () => (await window.axe.run(document)).violations.map((v) => `${v.id}(${v.impact})`)); };
const b = await launch();
const box = (p, sel) => p.evaluate((s) => { const e = document.querySelector(s); if (!e) return null; const r = e.getBoundingClientRect(); return { y: Math.round(r.top), h: Math.round(r.height), shown: getComputedStyle(e).display !== "none" && r.height > 0 }; }, sel);
const hscroll = (p) => p.evaluate(() => document.documentElement.scrollWidth > innerWidth);

// ---------- M-011 + M-104: AI mode on a phone (REAL ProjectWorkspace) ----------
{
  const p = await open(b, "/studio/projects/p1/ai", { viewport: { width: 390, height: 844 } }); await p.waitForSelector(".topbar"); await wait(900);
  const top = await box(p, ".topbar");
  check("M-104 [390]: the top bar takes at most 100 px (it was 141 px)", top.h <= 100, `${top.h}px`);
  const menu = p.getByRole("button", { name: "Thêm thao tác" });
  check("M-011 [390]: a 'Thêm thao tác' menu button replaces the hidden Website / Phiên bản / Tệp / Chia sẻ / Cài đặt", (await menu.count()) === 1 && (await menu.getAttribute("aria-haspopup")) === "menu" && (await menu.getAttribute("aria-expanded")) === "false");
  await menu.click(); await wait(200);
  const items = await p.getByRole("menuitem").allInnerTexts();
  check("M-011 [390]: the menu lists every hidden action", ["Website", "Phiên bản", "Tệp", "Chia sẻ", "Cài đặt project"].every((t) => items.some((x) => x.startsWith(t))), items.join(" | "));
  check("M-011 [390]: axe finds no violation with the menu open", (await axe(p)).length === 0, (await axe(p)).join(","));
  check("M-011 [390]: opening the menu puts focus on its first item", await p.evaluate(() => document.activeElement?.getAttribute("role") === "menuitem"));
  await p.keyboard.press("ArrowDown"); await p.keyboard.press("Escape"); await wait(150);
  check("M-011 [390]: Escape closes the menu and returns focus to the menu button", (await p.getByRole("menu").count()) === 0 && (await p.evaluate(() => document.activeElement?.getAttribute("aria-haspopup"))) === "menu");
  await menu.click(); await p.mouse.click(200, 500); await wait(150);
  check("M-011 [390]: a click outside closes the menu", (await p.getByRole("menu").count()) === 0);
  await menu.click(); await p.getByRole("menuitem", { name: "Phiên bản" }).click(); await wait(500);
  check("M-011 [390]: 'Phiên bản' in the menu opens the versions drawer", (await p.getByRole("dialog").count()) === 1 && /Lịch sử phiên bản/.test(await p.getByRole("dialog").innerText()), JSON.stringify(await p.evaluate(() => window.__nav.log.slice(-1))));
  await p.keyboard.press("Escape"); await wait(300);
  // panes
  const tabs = await p.getByRole("tab").allInnerTexts();
  check("M-104 [390]: a Trò chuyện / Xem trước pane switch", tabs.includes("Trò chuyện") && tabs.includes("Xem trước"), tabs.join(" | "));
  const chat = await box(p, ".promptPane"), prev = await box(p, ".previewPane");
  check("M-104 [390]: the chat pane gets most of the screen (>= 600 px; it was 495) and the preview is not squeezed under it", chat.shown && chat.h >= 600 && !prev.shown, JSON.stringify({ chat, prev }));
  check("M-104 [390]: the composer is visible without scrolling the page", await p.getByLabel("Mô tả thay đổi").isVisible());
  await p.getByLabel("Mô tả thay đổi").fill("Bản nháp chưa gửi");
  await p.getByRole("tab", { name: "Xem trước" }).click(); await wait(400);
  const prev2 = await box(p, ".previewPane"), frame = await box(p, ".previewPane iframe"), chat2 = await box(p, ".promptPane");
  check("M-104 [390]: 'Xem trước' shows the preview full height (>= 600 px; it was 208) and hides the chat", prev2.shown && prev2.h >= 600 && frame.h >= 400 && !chat2.shown, JSON.stringify({ prev2, frame }));
  await p.getByRole("tab", { name: "Trò chuyện" }).click(); await wait(300);
  check("M-104 [390]: switching panes keeps the draft prompt (both stay mounted)", (await p.getByLabel("Mô tả thay đổi").inputValue()) === "Bản nháp chưa gửi");
  await p.getByRole("tab", { name: "Trò chuyện" }).focus(); await p.keyboard.press("ArrowRight"); await wait(300);
  check("M-104 [390]: the switch is a real tablist: ArrowRight moves to Xem trước", (await p.getByRole("tab", { name: "Xem trước" }).getAttribute("aria-selected")) === "true");
  check("M-104 [390]: no horizontal page overflow", !(await hscroll(p)));
  check("M-104 [390]: axe finds no violation on the phone workspace", (await axe(p)).length === 0, (await axe(p)).join(","));
  check("M-011/M-104 [390]: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
// widths where the menu must NOT show and the layout must hold (768, 1000 = the 901-1023 band)
for (const w of [768, 1000]) {
  const p = await open(b, "/studio/projects/p1/ai", { viewport: { width: w, height: 844 } }); await p.waitForSelector(".topbar"); await wait(800);
  check(`M-011 [${w}]: the text buttons are shown and the phone menu is not`, (await p.getByRole("button", { name: "Phiên bản", exact: true }).isVisible()) && !(await p.getByRole("button", { name: "Thêm thao tác" }).isVisible()));
  const chat = await box(p, ".promptPane"), prev = await box(p, ".previewPane"), switched = await p.getByRole("tab", { name: "Xem trước" }).isVisible();
  if (w <= 900) check(`M-104 [${w}]: where the panes would stack (<= 900 px) one pane is shown at a time (the preview was 211 px under a 490 px chat)`, switched && chat.shown !== prev.shown && Math.max(chat.h, prev.h) >= 600, JSON.stringify({ chat, prev }));
  else check(`M-104 [${w}]: chat and preview side by side, both usable (>= 300 px; at 1000 px the preview used to collapse to 48 px), no pane switch`, !switched && chat.h >= 300 && prev.h >= 300, JSON.stringify({ chat, prev }));
  check(`M-104 [${w}]: no horizontal overflow`, !(await hscroll(p)));
  await p.close();
}
// ---------- M-011 + M-104: Code workspace on a phone ----------
{
  const s = newCodeState(); s.config = { ...s.config, serverApps: true }; s.project = { ...s.project, appKind: "SERVER_APP" }; s.projects = [s.project];
  const p = await open(b, "/studio/projects/p1/ai", { state: s, viewport: { width: 390, height: 844 } }); await p.waitForSelector(".topbar"); await wait(900);
  const top = await box(p, ".topbar");
  check("M-104 [code 390]: the top bar takes at most 100 px, no horizontal overflow, 'Xuất bản' not clipped", top.h <= 100 && !(await hscroll(p)) && (await p.getByRole("button", { name: "Xuất bản" }).evaluate((e) => e.scrollWidth <= e.clientWidth + 1)));
  await p.getByRole("button", { name: "Thêm thao tác" }).click(); await wait(200);
  const items = await p.getByRole("menuitem").allInnerTexts();
  check("M-011 [code 390]: the menu lists Lịch sử, Thư viện, IDE, Máy chủ, Chia sẻ", ["Lịch sử", "Thư viện", "IDE", "Máy chủ", "Chia sẻ"].every((t) => items.some((x) => x.startsWith(t))), items.join(" | "));
  await p.getByRole("menuitem", { name: "Thư viện" }).click(); await wait(500);
  check("M-011 [code 390]: 'Thư viện' opens the packages drawer", (await p.getByRole("dialog").count()) === 1 && /Thư viện/.test(await p.getByRole("dialog").innerText()));
  await p.keyboard.press("Escape"); await wait(300);
  const tabs = await p.getByRole("tab").allInnerTexts();
  check("M-104 [code 390]: a Trò chuyện / Thay đổi pane switch; changes are hidden until chosen", tabs.includes("Trò chuyện") && tabs.includes("Thay đổi") && !(await box(p, ".codeRight")).shown);
  await p.getByRole("tab", { name: "Thay đổi" }).click(); await wait(300);
  check("M-104 [code 390]: 'Thay đổi' shows the change list and hides the conversation", (await box(p, ".codeRight")).shown && !(await box(p, ".codeLeft")).shown && (await p.locator(".changeItem").count()) > 0);
  await p.close();
}
// ---------- M-016 + M-075: feedback goes through the shared toast; error text never leaks codes ----------
{
  const s = newState(); const p = await open(b, "/studio/projects/p1/design", { state: s }); await p.waitForSelector("iframe"); await wait(900);
  const row = (n) => p.locator("[role=treeitem][aria-level='2']").filter({ hasText: n }).first();
  s.fail = { "PATCH /workspaces/w1/projects/p1/schema": { status: 422, code: "VALIDATION_FAILED", message: "Dữ liệu không hợp lệ" } };
  await row("Đánh giá").click(); await p.getByRole("button", { name: "↑ Lên" }).first().click(); await wait(1000);
  const err = p.locator("[role=alert] .xp-toast-error");
  check("M-016: a failed save raises an ERROR toast in an assertive live region (role=alert)", (await err.count()) === 1 && /Dữ liệu không hợp lệ/.test(await err.innerText()));
  check("M-016: the legacy <button class=toast> is gone", (await p.locator("button.toast").count()) === 0);
  check("M-016: the toast has a 'Đóng thông báo' button", (await err.getByRole("button", { name: "Đóng thông báo" }).count()) === 1);
  await wait(7500);
  check("M-016: an error toast does NOT time out by itself (it was dismissed only by a click)", (await p.locator("[role=alert] .xp-toast-error").count()) === 1);
  await err.getByRole("button", { name: "Đóng thông báo" }).click(); await wait(300);
  check("M-016: ...and the close button removes it", (await p.locator(".xp-toast-error").count()) === 0);
  // a failure raised from inside a dialog is not hidden under the dialog's overlay
  await p.locator(".bx-panel-head").getByRole("button", { name: /Trang/ }).click(); await wait(200);
  await p.getByRole("dialog").getByLabel("Tên trang").fill("Liên hệ"); s.fail = { "PATCH /workspaces/w1/projects/p1/schema": { status: 422, code: "VALIDATION_FAILED", message: "Đường dẫn trùng" } };
  await p.getByRole("dialog").getByRole("button", { name: "Thêm trang" }).click(); await wait(1000);
  const onTop = await p.evaluate(() => { const t = document.querySelector(".xp-toast-error"); if (!t) return "no toast"; const r = t.getBoundingClientRect(); const e = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return !!e?.closest(".xp-toast") || `${e?.tagName}.${e?.className} @${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.width)}x${Math.round(r.height)}`; });
  check("M-016: a toast raised while a dialog is open is ABOVE the dialog overlay (it used to sit under it)", onTop === true, String(onTop));
  check("M-016: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
  // success: polite status, leaves by itself
  const s2 = newState(); const q = await open(b, "/studio/projects/p1/design", { state: s2 }); await q.waitForSelector("iframe"); await wait(900);
  await q.getByRole("button", { name: "Cài đặt project" }).click(); await wait(400);
  await q.getByRole("button", { name: "Lưu thay đổi" }).click(); await wait(1000);
  const ok = q.locator("[role=status] .xp-toast-success");
  check("M-016: a success toast ('Đã lưu cài đặt.') is a polite status", (await ok.count()) === 1 && /Đã lưu cài đặt/.test(await ok.innerText()));
  await wait(7000);
  check("M-016: ...and leaves by itself after about 6 s", (await q.locator(".xp-toast-success").count()) === 0);
  await q.close();
}
{
  // M-075: a server error code is not appended to the toast; the person gets the message (+ reference)
  const s = newCodeState(); s.changes[0] = { ...s.changes[0], reviewRequired: false }; const p = await open(b, "/studio/projects/p1/code", { state: s }); await p.waitForSelector(".changeItem"); await wait(600);
  s.fail = { "POST .*/merge$": { status: 500, code: "INTERNAL_X7", message: "Không hợp nhất được vì máy chủ bận" } };
  await p.getByRole("button", { name: "Hợp nhất vào main" }).click(); await wait(900);
  const t = await p.locator(".xp-toast-error").innerText().catch(() => "(none)");
  check("M-075: the toast says what happened without the raw error code", /máy chủ bận/.test(t) && !/INTERNAL_X7/.test(t), t.replace(/\n/g, " "));
  await p.close();
}
await b.close(); finish();
