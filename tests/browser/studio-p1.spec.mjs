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
await b.close(); finish();
