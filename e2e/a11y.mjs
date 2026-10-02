// Accessibility: axe-core (WCAG 2.2 A/AA rule tags) on every main screen/dialog + keyboard behaviour. Needs the running stack.
import { chromium } from "playwright-core";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
const axeSource = readFileSync(createRequire(import.meta.url).resolve("axe-core/axe.min.js"), "utf8");
const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100";
// Credentials: A11Y_USER/A11Y_PASSWORD, else the local dev admin.
const USER = process.env.A11Y_USER ?? "local.admin", PASSWORD = process.env.A11Y_PASSWORD ?? env.LOCAL_ADMIN_PASSWORD;
const PROJECT = new RegExp(process.env.A11Y_PROJECT ?? "Water Purifier Website");
const launchArgs = process.env.E2E_RESOLVE_IP ? [`--host-resolver-rules=MAP ${new URL(BASE).hostname} ${process.env.E2E_RESOLVE_IP}`] : [];
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true, args: launchArgs });
let failed = false; const report = [];
const pass = (n) => { console.log("PASS", n); }; const fail = (n, m) => { failed = true; console.log("FAIL", n, "-", m); };

async function scan(page, name) {
  await page.evaluate(axeSource.replace(/^\/\*.*?\*\//s, "") + ";0");     // inject axe-core (CSP allows it via evaluate)
  const r = await page.evaluate(async (tags) => await window.axe.run(document, { runOnly: { type: "tag", values: tags }, resultTypes: ["violations"] }), TAGS);
  const serious = r.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
  const minor = r.violations.filter((v) => v.impact !== "serious" && v.impact !== "critical");
  report.push({ name, serious: serious.map((v) => `${v.id}: ${v.nodes.length}x ${v.nodes[0]?.target}`), minor: minor.map((v) => `${v.id} (${v.impact}): ${v.nodes.length}x`) });
  if (serious.length) fail(`axe ${name}`, serious.map((v) => `${v.id}[${v.impact}] ${v.help} @ ${v.nodes.slice(0, 2).map((n) => n.target).join(" | ")}`).join(" ; "));
  else pass(`axe ${name}: no serious/critical violations${minor.length ? ` (${minor.length} minor: ${minor.map((v) => v.id).join(", ")})` : ""}`);
}

for (const width of [1280, 1440, 1920, 2560]) {
  const ctx = await browser.newContext({ viewport: { width, height: 1000 } }); const page = await ctx.newPage();
  await page.goto(BASE); await page.getByLabel("Tên đăng nhập").waitFor();
  await scan(page, `login @${width}`);
  await page.getByLabel("Tên đăng nhập").fill(USER); await page.getByLabel("Mật khẩu").fill(PASSWORD);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.getByRole("button", { name: /Tạo project|Đăng xuất/ }).first().waitFor(); await page.waitForTimeout(500);
  await scan(page, `project list @${width}`);
  if (await page.locator(".projectList button").count() > 0) await page.locator(".projectList button").filter({ hasText: PROJECT }).first().click();
  else { await page.getByPlaceholder("Tên project mới").fill("A11y check " + width); await page.getByRole("button", { name: "Tạo project" }).click(); }
  await page.waitForSelector("iframe.previewFrame", { state: "attached" });
  await scan(page, `studio @${width}`);
  if (width === 1440) {
    for (const [btn, title] of [["Lịch sử", "Lịch sử phiên bản"], ["Tệp", "Tệp của project"], ["Thành viên", "Thành viên và quyền"]]) {
      await page.getByRole("button", { name: btn, exact: true }).click(); await page.getByRole("dialog", { name: title }).waitFor();
      await scan(page, `dialog "${title}"`);
      await page.keyboard.press("Escape"); await page.getByRole("dialog").waitFor({ state: "detached" });
    }
    // structure tab + registry-driven inspector (not a dialog)
    await page.getByRole("button", { name: "Chỉnh sửa", exact: true }).click(); await page.getByRole("tab", { name: "Cấu trúc trang", selected: true }).waitFor();
    await page.getByRole("button", { name: /Danh sách sản phẩm/ }).click(); await page.getByRole("region", { name: /Chỉnh sửa Danh sách sản phẩm/ }).waitFor();
    await scan(page, "structure tab + inspector"); await page.getByRole("tab", { name: /Hỏi AI/ }).click(); await scan(page, "ask-AI tab");
    await page.getByRole("button", { name: "Cài đặt" }).first().click(); await page.getByRole("dialog", { name: "Cài đặt project" }).waitFor();
    await scan(page, 'dialog "Cài đặt project"'); await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Xuất bản" }).first().click(); await page.getByRole("dialog", { name: "Xuất bản website" }).waitFor();
    await scan(page, 'dialog "Xuất bản website"'); await page.keyboard.press("Escape");

    // ---- keyboard behaviour
    try {
      const opener = page.getByRole("button", { name: "Lịch sử" });
      await opener.focus(); await page.keyboard.press("Enter");
      const dlg = page.getByRole("dialog", { name: "Lịch sử phiên bản" }); await dlg.waitFor();
      const insideAfterOpen = await page.evaluate(() => document.querySelector('[role="dialog"]').contains(document.activeElement));
      for (let i = 0; i < 12; i++) await page.keyboard.press("Tab");
      const insideAfterTabs = await page.evaluate(() => document.querySelector('[role="dialog"]').contains(document.activeElement));
      await page.keyboard.press("Shift+Tab"); const insideShift = await page.evaluate(() => document.querySelector('[role="dialog"]').contains(document.activeElement));
      await page.keyboard.press("Escape"); await dlg.waitFor({ state: "detached" });
      const restored = await page.evaluate(() => document.activeElement?.textContent?.trim());
      if (insideAfterOpen && insideAfterTabs && insideShift && restored === "Lịch sử") pass("keyboard: focus moves into dialog, is trapped, Escape closes, focus returns to the opener");
      else fail("keyboard dialog", JSON.stringify({ insideAfterOpen, insideAfterTabs, insideShift, restored }));
    } catch (e) { fail("keyboard dialog", e.message.split("\n")[0]); }
    try {   // visible focus indicator on tabbable controls
      await page.keyboard.press("Tab");
      const outline = await page.evaluate(() => { const s = getComputedStyle(document.activeElement); return { outline: s.outlineStyle + " " + s.outlineWidth, shadow: s.boxShadow }; });
      if (!/none/.test(outline.outline) || outline.shadow !== "none") pass("keyboard: focused control has a visible indicator"); else fail("focus visible", JSON.stringify(outline));
    } catch (e) { fail("focus visible", e.message); }
    try {   // form errors are announced
      await page.getByRole("button", { name: "Đăng xuất" }).click(); await page.getByLabel("Tên đăng nhập").fill("nobody"); await page.getByLabel("Mật khẩu").fill("wrong-password-xx");
      await page.getByRole("button", { name: "Đăng nhập" }).click(); await page.getByRole("alert").waitFor({ timeout: 8000 });
      pass("form error is exposed with role=alert");
    } catch (e) { fail("form error alert", e.message.split("\n")[0]); }
  }
  await ctx.close();
}
await browser.close();
console.log("\nA11Y SUMMARY"); for (const r of report) console.log(` ${r.name}: serious=${r.serious.length} minor=${r.minor.length}`);
process.exit(failed ? 1 : 0);
