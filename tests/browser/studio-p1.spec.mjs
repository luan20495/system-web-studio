// @class: harness — real Chromium on the REAL Studio app with an in-test FAKE of /api/v1 (tests/browser/studio-app/); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Regression checks of the confirmed Studio P1 issues M-001..M-004 (docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md).
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/studio-p1.spec.mjs
import { launch, open, check, wait, finish, newState } from "./studio-app/lib.mjs";
import { newCodeState } from "./studio-app/fake-api.mjs";
const b = await launch();
const posts = (p, re) => p.state.log.filter((l) => l.method === "POST" && re.test(l.path));

// ---------- M-001: Cancel in the code-change review must never approve ----------
{
  const s = newCodeState(); const p = await open(b, "/studio/projects/p1/code", { state: s }); await p.waitForSelector(".changeItem"); await wait(500);
  p.removeAllListeners("dialog"); p.on("dialog", (d) => { (p.dialogs ??= []).push({ type: d.type(), message: d.message() }); void d.dismiss(); });   // a native prompt would be CANCELLED
  await p.getByRole("button", { name: "Duyệt" }).click(); await wait(300);
  const dlg = p.getByRole("dialog");
  check("M-001: 'Duyệt' opens an in-app dialog (no native prompt)", (await dlg.count()) === 1 && !(p.dialogs ?? []).some((d) => d.type === "prompt"), JSON.stringify(p.dialogs ?? []));
  check("M-001: the dialog has an accessible name and aria-modal", (await dlg.count()) === 1 && (await dlg.getAttribute("aria-modal")) === "true" && !!(await dlg.getAttribute("aria-labelledby")));
  await p.keyboard.press("Escape"); await wait(300);
  check("M-001: Escape closes the dialog and sends NO approve request", (await p.getByRole("dialog").count()) === 0 && posts(p, /\/approve$/).length === 0, `approve POSTs=${posts(p, /\/approve$/).length}`);
  await p.getByRole("button", { name: "Duyệt" }).click(); await wait(200);
  await p.getByRole("dialog").getByRole("button", { name: "Hủy" }).click().catch(() => undefined); await wait(300);
  check("M-001: 'Hủy' closes the dialog and sends NO approve request", (await p.getByRole("dialog").count()) === 0 && posts(p, /\/approve$/).length === 0);
  if ((await p.getByRole("dialog").count()) === 0) await p.getByRole("button", { name: "Duyệt" }).click();
  await wait(200);
  for (let i = 0; i < 8; i++) await p.keyboard.press("Tab");
  check("M-001: Tab stays inside the dialog (focus trap)", await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]")));
  await p.getByRole("dialog").getByLabel(/Nhận xét/).fill("Đã xem, ổn").catch(() => undefined);
  const ok = p.getByRole("dialog").getByRole("button", { name: "Duyệt" });
  await ok.dblclick().catch(() => undefined); await wait(700);
  const ap = posts(p, /\/approve$/);
  check("M-001: confirming sends exactly ONE approve request, with the comment", ap.length === 1 && ap[0].body?.comment === "Đã xem, ổn", JSON.stringify(ap.map((x) => x.body)));
  check("M-001: the dialog closes after a successful approve", (await p.getByRole("dialog").count()) === 0);
  await p.close();
}
// ---------- M-004: AI conversation follows the newest message and announces each finished message once ----------
{
  const prompts = Array.from({ length: 25 }, (_, i) => ({ id: `h${25 - i}`, text: `Yêu cầu số ${25 - i}`, createdAt: "2026-10-08T08:00:00Z", outcome: "UPDATED", assistantMessage: `Đã xử lý yêu cầu số ${25 - i}. ` + "Chi tiết ".repeat(12), versionId: null, registryReuse: 0, model: "mock", aiCalls: 0, totalTokens: null, costUsd: null }));
  const atBottom = (p) => p.locator(".conversation").evaluate((e) => ({ top: Math.round(e.scrollTop), h: e.scrollHeight, c: e.clientHeight, ok: e.scrollHeight - e.scrollTop - e.clientHeight <= 8 }));
  const s = newState(); s.prompts = prompts;
  const p = await open(b, "/studio/projects/p1/ai", { state: s, viewport: { width: 1440, height: 800 } }); await p.waitForSelector(".conversation .message"); await wait(700);
  let g = await atBottom(p);
  check("M-004: a long history opens at the newest message", g.ok, JSON.stringify(g));
  const log = p.locator("[role=log]");
  check("M-004: the messages are a role=log with a polite live region and a name", (await log.count()) === 1 && (await log.getAttribute("aria-live")) === "polite" && !!(await log.getAttribute("aria-label")));
  check("M-004: the typing / progress status is NOT inside the log (it keeps its own status role)", (await p.locator("[role=log] [role=status]").count()) === 0 && (await p.locator("[role=log] .typing").count()) === 0);
  await p.evaluate(() => { const l = document.querySelector("[role=log]"); window.__adds = 0; new MutationObserver((ms) => { for (const m of ms) window.__adds += [...m.addedNodes].filter((n) => n.nodeType === 1).length; }).observe(l, { childList: true }); });
  await p.getByLabel("Mô tả thay đổi").fill("Thêm bảng so sánh"); await p.getByLabel("Mô tả thay đổi").press("Enter"); await wait(1200);
  g = await atBottom(p);
  check("M-004: after sending, the new answer is scrolled into view", g.ok, JSON.stringify(g));
  const adds = await p.evaluate(() => window.__adds);
  check("M-004: one send adds exactly two nodes to the live region (your message + the finished answer), not one per update", adds === 2, `added=${adds}`);
  // the person scrolled up to read history while the answer is on its way: it must not yank them down
  let release; s.hold = { "POST /workspaces/w1/projects/p1/prompts$": { promise: new Promise((r) => { release = r; }) } };
  await p.getByLabel("Mô tả thay đổi").fill("Ẩn phần đánh giá"); await p.getByLabel("Mô tả thay đổi").press("Enter"); await wait(500);
  await p.locator(".conversation").evaluate((e) => { e.scrollTop = 0; }); await wait(300);
  release(); await wait(1000);
  const up = await p.locator(".conversation").evaluate((e) => Math.round(e.scrollTop));
  check("M-004: a reply arriving while the person reads older messages does not pull the view away", up < 40, `scrollTop=${up}`);
  const jump = p.getByRole("button", { name: /Tin mới/ });
  check("M-004: ...and a 'Tin mới' button offers the jump", (await jump.count()) === 1);
  await jump.click(); await wait(2000);
  g = await atBottom(p);
  check("M-004: the 'Tin mới' button scrolls to the newest message and goes away", g.ok && (await p.getByRole("button", { name: /Tin mới/ }).count()) === 0, JSON.stringify(g));
  check("M-004: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
await b.close(); finish();
