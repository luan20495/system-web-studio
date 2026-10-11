// @class: real-backend — FQ-A11Y-02: every Studio builder dialog opened from the header returns focus to the control that opened it when it closes (Escape, the close button), by mouse AND by keyboard.
import { loginUi, newPage, openBuilder, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-A11Y01", title = "(a11y) Builder dialogs: Escape / close returns focus to the opener";
const OPENERS = [["Chia sẻ", /^Chia sẻ/], ["Phiên bản", /^Phiên bản/], ["Tệp", /^Tệp/], ["Cài đặt project", /^Cài đặt/], ["Xuất bản", /^Xuất bản/]];
export async function run({ cfg, fx, browser, check }) {
  const p = fx.projects.A.id;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const bar = page.locator("header.bx-top");
  const dlg = page.getByRole("dialog");
  const active = () => page.evaluate(() => { const a = document.activeElement; return a ? `${a.tagName.toLowerCase()}|${(a.getAttribute("aria-label") || a.textContent || "").trim().slice(0, 30)}` : "none"; });
  // closing a route-style dialog re-renders the workspace (and reloads the project): focus is given back once the opener exists again, so read it when it has settled (or after 6 s)
  let want = null; const settled = async () => { const t0 = Date.now(); let a = await active(); while (a !== want && Date.now() - t0 < 6000) { await page.waitForTimeout(150); a = await active(); } return a; };
  const closeAndRead = async (how) => { if (how === "escape") await page.keyboard.press("Escape"); else await dlg.getByRole("button", { name: /^(Đóng|Hủy)$/ }).first().click(); await dlg.waitFor({ state: "detached", timeout: 8000 }).catch(() => undefined); return settled(); };
  for (const [label, re] of OPENERS) {
    for (const how of ["click", "keyboard"]) {
      const opener = bar.getByRole("button", { name: re }).first();
      if (!(await opener.count())) { check.ok(`${label}: the opener exists in the header`, false); continue; }
      await opener.evaluate((e) => { window.__op = e; });
      if (how === "click") await opener.click(); else { await opener.focus(); await page.keyboard.press("Enter"); }
      const opened = await dlg.first().waitFor({ timeout: 8000 }).then(() => true, () => false);
      if (!opened) { check.ok(`${label} (${how}): the dialog opens`, false); continue; }
      want = await opener.evaluate((e) => `${e.tagName.toLowerCase()}|${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 30)}`);
      const seen = await closeAndRead("escape"); const kept = await page.evaluate(() => document.contains(window.__op));
      const expected = await opener.evaluate((e) => `${e.tagName.toLowerCase()}|${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 30)}`);
      check.ok(`${label} (${how}): Escape closes the dialog and focus returns to the opener (not <body>)`, seen === expected, `focus after Escape = ${seen} · expected ${expected} · opener element kept in the DOM: ${kept}`, "ui");
    }
    // the close button restores focus too
    const opener = bar.getByRole("button", { name: re }).first(); await opener.click(); await dlg.first().waitFor({ timeout: 8000 }).catch(() => undefined);
    want = await opener.evaluate((e) => `${e.tagName.toLowerCase()}|${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 30)}`);
    const seenBtn = await closeAndRead("button"); const expected = await opener.evaluate((e) => `${e.tagName.toLowerCase()}|${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 30)}`);
    check.ok(`${label}: the close button returns focus to the opener too`, seenBtn === expected, `focus = ${seenBtn} · expected ${expected}`, "ui");
  }
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
