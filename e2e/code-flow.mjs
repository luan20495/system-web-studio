// Code projects end to end (Phase 7.2–7.4) with the REAL build runner, Docker sandbox and Forgejo: create, AI (simulator) change,
// sandboxed preview, diff, merge, Code-mode edit, failing build, publish to the sites gateway, isolation and access checks.
// Needs the local stack (scripts/run-local.sh), which also starts Forgejo, the package mirror and the runner.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";

const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const PW = env.LOCAL_ADMIN_PASSWORD, BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100", SITES = process.env.E2E_SITES ?? "http://127.0.0.1:18088";
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q.replace(/"/g, '\\"')}"`).toString().trim();
const results = []; let failed = false; const consoleErrors = [];
async function check(name, fn) { try { await fn(); results.push(true); console.log("PASS", name); } catch (e) { failed = true; results.push(false); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } }
const expect = (c, m) => { if (!c) throw new Error(m); };
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
async function session(user) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage();
  p.on("console", (m) => { if (m.type() === "error") consoleErrors.push(`${m.text()} @ ${p.url()}`); }); p.on("dialog", (d) => d.accept());
  await p.goto(BASE + "/login"); await p.getByRole("radio", { name: /Builder Studio/ }).check();
  await p.getByLabel("Tên đăng nhập").fill(user); await p.getByLabel("Mật khẩu").fill(PW); await p.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await p.waitForURL((u) => u.pathname.startsWith("/studio")); return { c, p };
}
const ed = await session("local.editor"); const p = ed.p;
const NAME = `Code E2E ${Date.now().toString(36)}`; let PID = "", PROJECT_URL = "";
const waitChange = async (text) => { await p.locator(".changeItem", { hasText: text }).getByText(/Sẵn sàng|Build lỗi/).waitFor({ timeout: 180000 }); };

await check("create a code app (React + Vite scaffold) — repository with the scaffold on main", async () => {
  await p.goto(BASE + "/studio/new"); await p.getByRole("radio", { name: /Ứng dụng web \(mã nguồn\)/ }).click();
  await p.getByLabel("Tên ứng dụng").fill(NAME); await p.getByRole("button", { name: "Tạo ứng dụng" }).click();
  await p.waitForURL(/\/projects\/[0-9a-f-]{36}\/ai/); PROJECT_URL = p.url().replace(/\/ai$/, ""); PID = PROJECT_URL.split("/").pop();
  expect(sql(`select app_type from projects where id='${PID}'`) === "STATIC_APP", "app_type");
  expect(sql(`select owner from repositories where project_id='${PID}'`) === "factory", "repository not in the platform organisation");
});
await check("AI (simulator) change: ai/ branch, sandbox build, preview renders inside the Studio in an isolated origin", async () => {
  await p.getByLabel("Mô tả thay đổi mã").fill('Đổi tiêu đề thành "Chào từ E2E"'); await p.getByRole("button", { name: /Gửi/ }).click();
  await waitChange("Chào từ E2E");
  expect(sql(`select status||':'||kind||':'||left(branch,3) from code_changes where project_id='${PID}'`) === "READY:AI:ai/", "change row");
  const frame = p.frameLocator("iframe.appPreview"); await frame.getByText("Chào từ E2E").waitFor({ timeout: 20000 });
  expect(await p.locator("iframe.appPreview").getAttribute("sandbox") === "allow-scripts", "preview iframe must be sandboxed without allow-same-origin");
  const f = p.frames().find((x) => x.url().includes("/_preview/"));
  expect(await f.evaluate(() => self.origin) === "null", "preview must run in an opaque origin");
  expect(await f.evaluate(() => { try { localStorage.setItem("x", "1"); return "open"; } catch { return "blocked"; } }) === "blocked", "storage must be blocked");
});
await check("build & scan report: no secrets, no vulnerable packages, SBOM recorded", async () => {
  await p.getByRole("tab", { name: "Build & quét" }).click();
  await p.getByText(/Lỗ hổng thư viện \(OSV\): 0 cảnh báo/).waitFor(); await p.getByText(/SBOM: \d+ gói/).waitFor();
});
await check("diff shows the change; merge fast-forwards main with the user as author", async () => {
  await p.getByRole("tab", { name: "Mã thay đổi" }).click(); await p.locator(".dl.add", { hasText: "Chào từ E2E" }).waitFor();
  await p.getByRole("button", { name: "Hợp nhất vào main" }).click(); await p.getByText(/Đã hợp nhất vào main/).waitFor({ timeout: 30000 });
  await p.goto(PROJECT_URL + "/versions"); await p.getByText("Đổi tiêu đề thành").first().waitFor(); await p.getByText(/Tác giả Editor · commit bởi factory-bot/).first().waitFor();
});
await check("Code mode: configuration is read-only; an edit becomes a change that builds", async () => {
  await p.goto(PROJECT_URL + "/code"); await p.getByRole("button", { name: "package.json", exact: true }).click();
  await p.getByText(/Chỉ đọc/).waitFor(); expect(await p.getByLabel("Nội dung package.json").getAttribute("readonly") !== null, "package.json must be read-only");
  await p.getByRole("button", { name: "src/styles.css" }).click(); const box = p.getByLabel("Nội dung src/styles.css"); await box.waitFor();
  await box.fill((await box.inputValue()).replace("#f5f8fb", "#fff3d6")); await p.getByLabel("Mô tả thay đổi").fill("Nền E2E");
  await p.getByRole("button", { name: "Tạo thay đổi & build" }).click(); await waitChange("Nền E2E");
  expect(await p.locator(".changeItem", { hasText: "Nền E2E" }).getByText("Sẵn sàng").isVisible(), "edit did not build");
});
await check("a type error fails the build in the sandbox and cannot be merged", async () => {
  await p.getByRole("button", { name: "src/App.tsx" }).click(); const box = p.getByLabel("Nội dung src/App.tsx"); await box.waitFor();
  await box.fill((await box.inputValue()) + "\nexport const broken: number = \"not a number\";\n"); await p.getByLabel("Mô tả thay đổi").fill("Lỗi kiểu E2E");
  await p.getByRole("button", { name: "Tạo thay đổi & build" }).click(); await waitChange("Lỗi kiểu E2E");
  await p.locator(".changeItem", { hasText: "Lỗi kiểu E2E" }).click(); await p.getByText("Build không thành công").waitFor();
  expect(!(await p.getByRole("button", { name: "Hợp nhất vào main" }).isVisible()), "merge offered for a failed build");
  expect(sql(`select error from code_changes where project_id='${PID}' and summary='Lỗi kiểu E2E'`).includes("BUILD"), "failure stage not recorded");
});
await check("publish: the merged commit is built in the sandbox and served by the sites gateway with the sandbox policy", async () => {
  await p.goto(PROJECT_URL + "/publish"); const dlg = p.getByRole("dialog", { name: "Xuất bản website" }); await dlg.waitFor();
  expect(!(await dlg.getByRole("button", { name: /Riêng tư/ }).isVisible()), "private must not be offered for code apps");
  await dlg.getByRole("button", { name: /Công khai/ }).click(); await dlg.getByRole("button", { name: "Xuất bản", exact: true }).click();
  await dlg.getByText("Đang chạy", { exact: true }).waitFor({ timeout: 180000 });
  const url = await dlg.getByRole("link", { name: /^http/ }).getAttribute("href"); expect(url.startsWith(SITES + "/"), url);
  const r = await fetch(url); expect(r.status === 200, `site ${r.status}`);
  expect((r.headers.get("content-security-policy") ?? "").startsWith("sandbox allow-scripts"), "site must be sandboxed");
  const v = await browser.newContext(); const vp = await v.newPage(); await vp.goto(url); await vp.getByText("Chào từ E2E").waitFor({ timeout: 15000 }); await v.close();
});
await check("access: a non-member gets 404 on the code API; runner endpoints are not reachable through the UI or the gateway", async () => {
  const other = await session("local.viewer");
  const ws = sql(`select workspace_id from projects where id='${PID}'`);
  expect((await other.c.request.get(`${BASE}/api/v1/workspaces/${ws}/projects/${PID}/code/tree`)).status() === 404, "foreign code tree must be 404");
  // the UI host answers unknown paths with its own pages (HTML); the API's runner endpoint must never answer there
  const viaUi = await fetch(`${BASE}/internal/build-jobs/claim`, { method: "POST" });
  expect(!(viaUi.headers.get("content-type") ?? "").includes("json") && !(await viaUi.text()).includes("commitSha"), "runner endpoint reachable via UI");
  expect((await fetch(`${SITES}/internal/build-jobs/claim`, { method: "POST" })).status !== 200, "runner endpoint via gateway");
  await other.c.close();
});
await check("no unexpected console errors", async () => {
  const bad = consoleErrors.filter((e) => !/401|403|404|409|Failed to load resource/.test(e)); expect(bad.length === 0, bad.slice(0, 3).join(" | "));
});
await browser.close();
console.log(`\n${results.filter(Boolean).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
