// Accessibility: axe-core (WCAG 2.0/2.1/2.2 A+AA + best practice) on the login, Admin Console, Studio shell and project editor,
// plus keyboard checks. Needs the running stack; credentials A11Y_USER/A11Y_PASSWORD (system admin) or the local dev admin.
import { chromium } from "playwright-core";
import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { createRequire } from "node:module";
const axeSource = readFileSync(createRequire(import.meta.url).resolve("axe-core/axe.min.js"), "utf8");
const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100";
const USER = process.env.A11Y_USER ?? "local.admin", PASSWORD = process.env.A11Y_PASSWORD ?? env.LOCAL_ADMIN_PASSWORD;
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
let failed = false; const report = [];
async function scan(page, name) {
  await page.waitForTimeout(500);
  await page.evaluate(axeSource);
  const r = await page.evaluate(async (tags) => await window.axe.run(document, { runOnly: { type: "tag", values: tags }, resultTypes: ["violations"] }), TAGS);
  const serious = r.violations.filter((v) => v.impact === "serious" || v.impact === "critical"), minor = r.violations.filter((v) => !serious.includes(v));
  report.push({ name, serious: serious.length, minor: minor.length });
  if (serious.length) { failed = true; console.log("FAIL", name, serious.map((v) => `${v.id}[${v.impact}] @ ${v.nodes.slice(0, 2).map((n) => n.target).join(" | ")}`).join(" ; ")); }
  else console.log("PASS", name, minor.length ? `(minor: ${minor.map((v) => `${v.id}@${v.nodes[0]?.target}`).join(", ")})` : "");
}
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage();
await p.goto(BASE + "/login"); await p.getByLabel("Tên đăng nhập").waitFor(); await scan(p, "login");
await p.goto(BASE + "/auth/session-expired?next=/studio"); await scan(p, "session expired");
await p.goto(BASE + "/login"); await p.getByRole("radio", { name: /Admin Console/ }).check();
await p.getByLabel("Tên đăng nhập").fill(USER); await p.getByLabel("Mật khẩu").fill(PASSWORD); await p.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await p.waitForURL(/\/admin/);
for (const path of ["/admin", "/admin/users", "/admin/workspaces", "/admin/applications", "/admin/ai", "/admin/ai-governance", "/admin/alerts", "/admin/builds", "/admin/packages", "/admin/components", "/admin/templates", "/admin/audit", "/admin/system", "/admin/settings"]) { await p.goto(BASE + path); await p.locator("h1").first().waitFor(); await scan(p, path); }
await p.goto(BASE + "/admin/users"); await p.locator(".clickRow").first().click(); await p.locator("h1").first().waitFor(); await scan(p, "/admin/users/{id}");
await p.goto(BASE + "/admin/applications"); await p.locator(".clickRow").first().click(); await p.locator("h1").first().waitFor(); await scan(p, "/admin/applications/{id}");
for (const path of ["/studio", "/studio/projects", "/studio/new", "/studio/templates", "/studio/components", "/studio/activity"]) { await p.goto(BASE + path); await p.locator("h1").first().waitFor(); await scan(p, path); }
// a page-schema website for the editor screens (the newest card may be a code project)
const pageId = execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "select p.id from projects p join workspace_members m on m.workspace_id = p.workspace_id join users u on u.id = m.user_id and u.username = 'local.admin' where p.app_type='PAGE_SCHEMA' and p.active order by p.updated_at desc limit 1"`).toString().trim();
const href = `/studio/projects/${pageId}`;
for (const v of ["ai", "design", "code", "versions", "assets", "members", "settings", "publish", "site"]) { await p.goto(BASE + href + "/" + v); await p.waitForSelector(".topbar"); await p.waitForTimeout(600); await scan(p, `project/${v}`); }
// code project (STATIC_APP), if one exists locally (created by e2e/code-flow.mjs)
const codeId = (() => { try { return execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "select id from projects where app_type='STATIC_APP' and active order by created_at desc limit 1"`).toString().trim(); } catch { return ""; } })();
if (codeId) for (const v of ["ai", "code"]) { await p.goto(`${BASE}/studio/projects/${codeId}/${v}`); await p.waitForSelector(".codeStudio"); await p.waitForTimeout(1500); await scan(p, `code-project/${v}`); }
// keyboard: dialogs trap focus and Escape returns to the editor route
try {
  await p.goto(BASE + href + "/design"); await p.getByRole("button", { name: "Phiên bản" }).focus(); await p.keyboard.press("Enter");
  await p.getByRole("dialog", { name: "Lịch sử phiên bản" }).waitFor();
  const inside = await p.evaluate(() => document.querySelector('[role="dialog"]').contains(document.activeElement));
  for (let i = 0; i < 10; i++) await p.keyboard.press("Tab");
  const stillInside = await p.evaluate(() => document.querySelector('[role="dialog"]').contains(document.activeElement));
  await p.keyboard.press("Escape"); await p.waitForURL(/\/design$/);
  if (inside && stillInside) console.log("PASS keyboard: focus enters and stays in the dialog; Escape closes back to the editor"); else { failed = true; console.log("FAIL keyboard dialog", inside, stillInside); }
} catch (e) { failed = true; console.log("FAIL keyboard dialog", e.message.split("\n")[0]); }
try {
  await p.goto(BASE + "/login"); await p.keyboard.press("Tab"); const o = await p.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
  if (o !== "none") console.log("PASS keyboard: visible focus indicator"); else { failed = true; console.log("FAIL focus indicator"); }
} catch (e) { failed = true; console.log("FAIL focus", e.message); }
await browser.close();
console.log(`\nA11Y: ${report.filter((r) => r.serious === 0).length}/${report.length} screens without serious/critical violations; minor total ${report.reduce((a, r) => a + r.minor, 0)}`);
process.exit(failed ? 1 : 0);
