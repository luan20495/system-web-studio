// @class: harness — real Chromium on the REAL Studio app with an in-test FAKE of /api/v1 (tests/browser/studio-app/); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Regression checks of Studio wave-2 issues (docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md): M-015, M-006 (Builder boundary), ...
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/studio-wave2.spec.mjs
import { launch, open, check, wait, finish, newState } from "./studio-app/lib.mjs";
import { newCodeState } from "./studio-app/fake-api.mjs";
const b = await launch();

// ---------- M-015: the code editor must not trap the keyboard (WCAG 2.1.2) ----------
{
  const s = newCodeState(); const p = await open(b, "/studio/projects/p1/code", { state: s }); await p.waitForSelector(".codeEditor"); await wait(600);
  const ed = p.locator(".codeEditor");
  const inEditor = () => p.evaluate(() => document.activeElement?.classList.contains("codeEditor") ?? false);
  const val = () => ed.inputValue();
  await ed.focus(); const v0 = await val();
  await p.keyboard.press("Tab"); await wait(100);
  check("M-015: Tab leaves the editor by default (focus moves on) and changes no text", !(await inEditor()) && (await val()) === v0);
  await ed.focus(); await p.keyboard.press("Shift+Tab"); await wait(100);
  check("M-015: Shift+Tab leaves the editor", !(await inEditor()));
  const hint = p.getByText(/Tab/, { exact: false }).filter({ hasText: /thụt lề/ });
  check("M-015: a visible hint explains how Tab works in the editor", (await hint.count()) > 0);
  const toggle = p.getByLabel(/Dùng phím Tab để thụt lề/);
  check("M-015: indenting with Tab is an explicit, visible, OFF-by-default option", (await toggle.count()) === 1 && !(await toggle.isChecked()));
  await toggle.check(); await ed.focus(); await ed.evaluate((e) => { e.setSelectionRange(0, 0); });
  await p.keyboard.press("Tab"); await wait(150);
  check("M-015: with the option ON, Tab indents (two spaces) and focus stays in the editor", (await inEditor()) && (await val()).startsWith("  ") && (await val()).length === v0.length + 2);
  await p.keyboard.press("Escape"); await p.keyboard.press("Tab"); await wait(150);
  check("M-015: with the option ON, Esc then Tab is the escape hatch (focus leaves, no extra indent)", !(await inEditor()) && (await val()).length === v0.length + 2);
  await ed.focus(); await p.keyboard.press("Shift+Tab"); await wait(100);
  check("M-015: with the option ON, Shift+Tab still leaves", !(await inEditor()));
  check("M-015: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
await b.close(); finish();
