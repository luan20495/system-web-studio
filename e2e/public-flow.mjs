// End-to-end check of the PUBLISHED deployment (scripts/public-up.sh) from a real browser through Cloudflare.
// E2E_RESOLVE_IP maps the two public hostnames to a Cloudflare IP while the local resolver still has a stale negative cache.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const envf = Object.fromEntries(readFileSync(new URL("../.run/public/public.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const HOST = envf.PUBLIC_HOST, FILES = envf.PUBLIC_FILES_HOST, BASE = `https://${HOST}`;
const OUT = new URL("../.run/e2e-public/", import.meta.url).pathname; mkdirSync(OUT, { recursive: true });
const sql = (q) => execSync(`docker exec hblpub-postgres-1 psql -U studio -d studio -tAc "${q}"`).toString().trim();
const args = ["--ignore-certificate-errors-spki-list="];
if (process.env.E2E_RESOLVE_IP) args.push(`--host-resolver-rules=MAP ${HOST} ${process.env.E2E_RESOLVE_IP}, MAP ${FILES} ${process.env.E2E_RESOLVE_IP}`);
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true, args });
const results = []; let failed = false; const consoleErrors = [];
const check = async (name, fn) => { try { await fn(); results.push(1); console.log("PASS", name); } catch (e) { failed = true; results.push(0); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } };
const expect = (c, m) => { if (!c) throw new Error(m); };
const user = "pub" + Math.random().toString(36).slice(2, 9), pw = "Correct-Horse-9-" + Math.random().toString(36).slice(2, 8);
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
page.on("pageerror", (e) => consoleErrors.push("pageerror " + e.message));
const frameText = async () => (await (await page.waitForSelector("iframe.previewFrame", { state: "attached" })).contentFrame()).evaluate(() => document.body.innerText);
const frameHas = async (t, present = true) => { for (let i = 0; i < 40; i++) { if ((await frameText()).includes(t) === present) return; await page.waitForTimeout(250); } throw new Error(`preview ${present ? "missing" : "still has"} ${t}`); };

await check("public URL serves the app over HTTPS with HSTS and a nonce CSP", async () => {
  const r = await page.goto(BASE); expect(r.status() === 200, `status ${r.status()}`);
  const h = r.headers(); expect(/max-age=63072000/.test(h["strict-transport-security"] ?? ""), "HSTS missing");
  expect(/script-src 'self' 'nonce-/.test(h["content-security-policy"] ?? ""), "CSP nonce missing");
  await page.getByLabel("Tên đăng nhập").waitFor();
});
await check("sign-up creates the account and logs in; the user lands in an empty workspace", async () => {
  await page.getByRole("button", { name: /Chưa có tài khoản/ }).click();
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Tên hiển thị").fill("Public E2E");
  await page.getByLabel("Mật khẩu").fill(pw);
  await page.getByRole("button", { name: "Tạo tài khoản", exact: true }).click();
  await page.getByPlaceholder("Tên project mới").waitFor({ timeout: 20000 });
});
await check("cookies: session is HttpOnly + Secure, CSRF cookie Secure", async () => {
  const c = await ctx.cookies(); const s = c.find((x) => x.name === "STUDIO_SESSION"), x = c.find((x) => x.name === "XSRF-TOKEN");
  expect(s?.httpOnly && s?.secure, `session cookie ${JSON.stringify(s)}`); expect(x?.secure, `csrf cookie ${JSON.stringify(x)}`);
});
await check("create project, AI status shows the simulator while no key is set", async () => {
  await page.getByPlaceholder("Tên project mới").fill("Public E2E site"); await page.getByRole("button", { name: "Tạo project" }).click();
  await page.waitForSelector("iframe.previewFrame", { state: "attached" });
  const keyed = !!envf.OPENROUTER_API_KEY;
  if (!keyed) await page.getByText(/AI: mô phỏng/).waitFor({ timeout: 10000 }); else await page.getByLabel("Model AI").waitFor({ timeout: 15000 });
});
await check("prompt through Cloudflare updates the page and survives a reload", async () => {
  await page.getByPlaceholder(/Mô tả thay đổi/).fill("Thêm bảng so sánh 3 sản phẩm");
  const [resp] = await Promise.all([page.waitForResponse((r) => r.url().endsWith("/prompts") && r.request().method() === "POST", { timeout: 150000 }), page.getByRole("button", { name: /Gửi/ }).click()]);
  expect(resp.status() === 200, `prompt status ${resp.status()}`);
  await frameHas("So sánh sản phẩm");
  await page.reload(); await page.getByRole("button", { name: /Public E2E site/ }).click(); await frameHas("So sánh sản phẩm");
});
await check("header shows plain-language status (no internal revision), save state and visibility", async () => {
  const meta = await page.locator(".projectMeta").innerText();
  expect(/Phiên bản \d+/.test(meta) && /Riêng tư|Công khai/.test(meta) && !/\br\d+\b/.test(meta), `meta: ${meta}`);
  await page.getByText(/Đã lưu/).first().waitFor({ timeout: 5000 });
});
await check("structure tab: pick a section, it is outlined in the preview, edit a field through the inspector and it persists", async () => {
  await page.getByRole("button", { name: "Chỉnh sửa", exact: true }).click();
  await page.getByRole("button", { name: /Đầu trang \(Hero\)/ }).click();
  const frame = await (await page.waitForSelector("iframe.previewFrame", { state: "attached" })).contentFrame();
  await frame.waitForSelector(".__sel", { timeout: 10000 });
  const insp = page.getByRole("region", { name: /Chỉnh sửa Đầu trang/ });
  await insp.getByLabel("Tiêu đề", { exact: true }).fill("Tiêu đề chỉnh bằng inspector");
  await insp.getByRole("button", { name: "Lưu thay đổi" }).click();
  await frameHas("Tiêu đề chỉnh bằng inspector");
  await page.reload(); await page.getByRole("button", { name: /Public E2E site/ }).click(); await frameHas("Tiêu đề chỉnh bằng inspector");
});
await check("asset upload goes browser -> public MinIO host (presigned, CORS) and the image loads back", async () => {
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"); writeFileSync(OUT + "px.png", png);
  await page.getByRole("button", { name: "Tệp", exact: true }).click();
  await page.locator("input[type=file]").setInputFiles(OUT + "px.png");
  await page.getByText("px.png").waitFor({ timeout: 30000 });
  const loaded = await page.locator("img.assetThumb").evaluate((img) => new Promise((ok) => { if (img.complete) ok(img.naturalWidth > 0); else { img.onload = () => ok(true); img.onerror = () => ok(false); } }));
  expect(loaded, "thumbnail did not load from the storage host");
  await page.keyboard.press("Escape");
});
await check("publish reaches RUNNING through RabbitMQ and the URL is labelled as simulated", async () => {
  await page.getByRole("button", { name: "Xuất bản" }).first().click(); await page.getByRole("button", { name: /Công khai/ }).click();
  await page.getByRole("button", { name: "Xuất bản" }).last().click();
  await page.getByText("Đang chạy", { exact: true }).waitFor({ timeout: 40000 });
  expect(/mô phỏng/.test(await page.locator(".deployBox").innerText()), "mock label missing");
});
await check("a second public user cannot see the first user's workspace or project", async () => {
  const ws = (await (await ctx.request.get(`${BASE}/api/v1/auth/me`)).json()).workspaces[0].id;
  const other = await browser.newContext(); const csrf = async () => (await (await other.request.get(`${BASE}/api/v1/auth/csrf`)).json()).token;
  const u2 = "pub" + Math.random().toString(36).slice(2, 9);
  const reg = await other.request.post(`${BASE}/api/v1/auth/register`, { headers: { "X-XSRF-TOKEN": await csrf(), "Content-Type": "application/json" }, data: { username: u2, password: pw } });
  expect(reg.status() === 201, `register ${reg.status()}`);
  await other.request.post(`${BASE}/api/v1/auth/login`, { headers: { "X-XSRF-TOKEN": await csrf(), "Content-Type": "application/json" }, data: { username: u2, password: pw } });
  expect((await other.request.get(`${BASE}/api/v1/workspaces/${ws}/projects`)).status() === 404, "foreign workspace must be 404");
  expect((await other.request.get(`${BASE}/api/v1/workspaces/${ws}/members`)).status() === 404, "foreign members must be 404");
});
await check("audit rows carry the REAL client IP (trusted-proxy chain), not 127.0.0.1", async () => {
  const ips = sql(`select distinct ip_address from audit_events where action in ('LOGIN_SUCCESS','REGISTER','CREATE_PROJECT') and created_at > now() - interval '15 minutes'`).split("\n").filter(Boolean);
  expect(ips.length > 0 && !ips.includes("127.0.0.1") && !ips.includes("0:0:0:0:0:0:0:1"), `audit IPs: ${ips.join(",")}`);
});
await check("operational endpoints are not reachable from the internet", async () => {
  for (const p of ["/actuator/health", "/actuator/prometheus", "/v3/api-docs", "/swagger-ui.html"]) { const r = await ctx.request.get(BASE + p, { failOnStatusCode: false }); expect(r.status() === 404, `${p} -> ${r.status()}`); }
  const direct = await ctx.request.get(`https://${FILES}/`, { failOnStatusCode: false }); expect([400, 403].includes(direct.status()), `bucket listing exposed? ${direct.status()}`);
});
await check("no unexpected console errors (CSP etc.)", async () => {
  const bad = consoleErrors.filter((e) => !/401|403|409|429|Failed to load resource/.test(e)); expect(bad.length === 0, bad.slice(0, 3).join(" | "));
});
await browser.close();
console.log(`${results.filter(Boolean).length}/${results.length} passed (test user ${user})`);
process.exit(failed ? 1 : 0);
