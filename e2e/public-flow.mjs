// End-to-end check of the PUBLISHED deployment (scripts/public-up.sh) from a real browser through Cloudflare.
// E2E_RESOLVE_IP maps the two public hostnames to a Cloudflare IP while the local resolver still has a stale negative cache.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const envf = Object.fromEntries(readFileSync(new URL("../.run/public/public.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const HOST = envf.PUBLIC_HOST, FILES = envf.PUBLIC_FILES_HOST, SITES = envf.SITES_HOST ?? "sites.toolsmcp.uk", BASE = `https://${HOST}`;
const OUT = new URL("../.run/e2e-public/", import.meta.url).pathname; mkdirSync(OUT, { recursive: true });
const sql = (q) => execSync(`docker exec hblpub-postgres-1 psql -U studio -d studio -tAc "${q}"`).toString().trim();
const args = ["--ignore-certificate-errors-spki-list="];
if (process.env.E2E_RESOLVE_IP) args.push(`--host-resolver-rules=MAP ${HOST} ${process.env.E2E_RESOLVE_IP}, MAP ${FILES} ${process.env.E2E_RESOLVE_IP}, MAP ${SITES} ${process.env.E2E_RESOLVE_IP}`);
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

let PID = "", SITE = "", otherCtx = null; const u3 = "pub" + Math.random().toString(36).slice(2, 9);
const frameEl = async () => (await page.waitForSelector("iframe.previewFrame", { state: "attached" })).contentFrame();
await check("public URL serves the app over HTTPS with HSTS and a nonce CSP", async () => {
  const r = await page.goto(BASE + "/login"); expect(r.status() === 200, `status ${r.status()}`);
  const h = r.headers(); expect(/max-age=63072000/.test(h["strict-transport-security"] ?? ""), "HSTS missing");
  expect(/script-src 'self' 'nonce-/.test(h["content-security-policy"] ?? ""), "CSP nonce missing");
  await page.getByLabel("Tên đăng nhập").waitFor();
});
await check("sign-up (Builder portal) creates the account and lands in the Studio", async () => {
  await page.getByRole("radio", { name: /Builder Studio/ }).check();
  await page.getByRole("button", { name: /Chưa có tài khoản/ }).click();
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Tên hiển thị").fill("Public E2E");
  await page.getByLabel("Mật khẩu").fill(pw);
  await page.getByRole("button", { name: "Tạo tài khoản", exact: true }).click();
  await page.waitForURL((u) => u.pathname.startsWith("/studio"), { timeout: 20000 });
});
await check("cookies: session is HttpOnly + Secure, CSRF cookie Secure", async () => {
  const c = await ctx.cookies(); const s = c.find((x) => x.name === "STUDIO_SESSION"), x = c.find((x) => x.name === "XSRF-TOKEN");
  expect(s?.httpOnly && s?.secure, `session cookie ${JSON.stringify(s)}`); expect(x?.secure, `csrf cookie ${JSON.stringify(x)}`);
});
await check("create a website; AI status shows the simulator while no key is set", async () => {
  await page.goto(BASE + "/studio/new"); await page.getByLabel("Tên ứng dụng").fill("Public E2E site"); await page.getByRole("button", { name: "Tạo website" }).click();
  await page.waitForURL(/\/studio\/projects\/[0-9a-f-]{36}\/ai/, { timeout: 20000 }); PID = page.url().match(/projects\/([0-9a-f-]{36})/)[1];
  if (!envf.OPENROUTER_API_KEY) await page.getByText(/AI: mô phỏng/).waitFor({ timeout: 10000 }); else await page.getByLabel("Model AI").waitFor({ timeout: 15000 });
});
await check("prompt through Cloudflare updates the page and survives a reload", async () => {
  await page.getByLabel("Mô tả thay đổi").fill("Thêm bảng so sánh 3 sản phẩm");
  const [resp] = await Promise.all([page.waitForResponse((r) => r.url().endsWith("/prompts") && r.request().method() === "POST", { timeout: 150000 }), page.getByRole("button", { name: /Gửi/ }).click()]);
  expect(resp.status() === 200, `prompt status ${resp.status()}`);
  await frameHas("So sánh sản phẩm"); await page.reload(); await frameHas("So sánh sản phẩm");
});
await check("Design mode: click a section in the preview, edit it in the inspector, it persists", async () => {
  await page.goto(`${BASE}/studio/projects/${PID}/design`);
  const insp = page.getByRole("region", { name: /Chỉnh sửa Đầu trang/ });
  for (let i = 0; i < 8 && !(await insp.isVisible()); i++) { await page.waitForTimeout(700); await (await frameEl()).click("section.hero").catch(() => undefined); }
  await insp.getByLabel("Tiêu đề", { exact: true }).fill("Tiêu đề chỉnh bằng inspector"); await insp.getByRole("button", { name: "Lưu thay đổi" }).click();
  await frameHas("Tiêu đề chỉnh bằng inspector"); await page.reload(); await frameHas("Tiêu đề chỉnh bằng inspector");
});
await check("asset upload goes browser -> public MinIO host (presigned, CORS) and the image loads back", async () => {
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"); writeFileSync(OUT + "px.png", png);
  await page.goto(`${BASE}/studio/projects/${PID}/assets`);
  await page.locator("input[type=file]").setInputFiles(OUT + "px.png");
  await page.getByText("px.png").waitFor({ timeout: 30000 });
  const loaded = await page.locator("img.assetThumb").first().evaluate((img) => new Promise((ok) => { if (img.complete) ok(img.naturalWidth > 0); else { img.onload = () => ok(true); img.onerror = () => ok(false); } }));
  expect(loaded, "thumbnail did not load from the storage host");
});
async function publish(label) {
  await page.goto(`${BASE}/studio/projects/${PID}/publish`); const dlg = page.getByRole("dialog", { name: "Xuất bản website" }); await dlg.waitFor();
  await dlg.getByRole("button", { name: label }).click(); await dlg.getByRole("button", { name: "Xuất bản", exact: true }).click();
  await dlg.getByText("Đang chạy", { exact: true }).waitFor({ timeout: 60000 });
  return dlg.getByRole("link", { name: /^https:/ }).getAttribute("href");
}
await check("publish builds a REAL static site served at https://" + SITES + "/<slug>/ (same content, strict headers, no cookie)", async () => {
  SITE = await publish(/Công khai/);
  expect(SITE.startsWith(`https://${SITES}/`), `site url ${SITE}`);
  const anon = await browser.newContext(); const r = await anon.request.get(SITE); const html = await r.text();
  expect(r.status() === 200, `site ${r.status()}`); expect(html.includes("Tiêu đề chỉnh bằng inspector"), "published page lacks the edited title");
  expect(!/<script/i.test(html), "published page contains a script"); expect(!r.headers()["set-cookie"], "visitor got a cookie");
  expect((r.headers()["content-security-policy"] ?? "").includes("default-src 'none'"), "CSP missing"); expect(r.headers()["x-content-type-options"] === "nosniff", "nosniff missing");
  await anon.close();
});
await check("private site over real HTTPS: anonymous -> sign in redirect; the member gets in (Secure host-only cookie); another user is refused", async () => {
  expect(await publish(/Riêng tư/) === SITE, "slug changed");
  const anon = await browser.newContext(); const r = await anon.request.get(SITE, { maxRedirects: 0 });
  expect(r.status() === 302 && (r.headers()["location"] ?? "").startsWith(`${BASE}/studio/site-access?site=`), `anonymous got ${r.status()} ${r.headers()["location"]}`);
  await anon.close();
  await page.goto(SITE); await page.waitForURL(SITE, { timeout: 20000 }); await page.getByText("Tiêu đề chỉnh bằng inspector").first().waitFor();
  const sc = (await ctx.cookies(`https://${SITES}`)).find((c) => c.name === "site_session");
  expect(sc?.secure && sc?.httpOnly && !sc.domain.startsWith("."), `site cookie ${JSON.stringify(sc)}`);
  const other = await browser.newContext(); otherCtx = other; const op = await other.newPage();
  await op.goto(BASE + "/login"); await op.getByRole("radio", { name: /Builder Studio/ }).check(); await op.getByRole("button", { name: /Chưa có tài khoản/ }).click();
  await op.getByLabel("Tên đăng nhập").fill(u3); await op.getByLabel("Mật khẩu").fill(pw); await op.getByRole("button", { name: "Tạo tài khoản", exact: true }).click(); await op.waitForURL((u) => u.pathname.startsWith("/studio"));
  await op.goto(SITE); await op.getByText("Không mở được trang").waitFor({ timeout: 20000 });
});
await check("a second public user cannot see the first user's workspace or project", async () => {
  const ws = (await (await ctx.request.get(`${BASE}/api/v1/auth/me`)).json()).workspaces[0].id;
  expect(otherCtx, "second user was not created");
  expect((await otherCtx.request.get(`${BASE}/api/v1/workspaces/${ws}/projects`)).status() === 404, "foreign workspace must be 404");
  expect((await otherCtx.request.get(`${BASE}/api/v1/workspaces/${ws}/members`)).status() === 404, "foreign members must be 404");
  expect((await otherCtx.request.get(`${BASE}/api/v1/projects/${PID}`)).status() === 404, "foreign project must be 404");
});
await check("audit rows carry the REAL client IP (trusted-proxy chain), not 127.0.0.1", async () => {
  const ips = sql(`select distinct ip_address from audit_events where action in ('LOGIN_SUCCESS','REGISTER','CREATE_PROJECT') and created_at > now() - interval '15 minutes'`).split("\n").filter(Boolean);
  expect(ips.length > 0 && !ips.includes("127.0.0.1") && !ips.includes("0:0:0:0:0:0:0:1"), `audit IPs: ${ips.join(",")}`);
});
await check("operational endpoints are not reachable from the internet", async () => {
  // the UI answers unknown paths with its own "not found" screen (HTML); none of these may reach the API
  for (const p of ["/actuator/health", "/actuator/prometheus", "/v3/api-docs", "/swagger-ui.html"]) { const r = await ctx.request.get(BASE + p, { failOnStatusCode: false }); const t = await r.text(); expect(r.status() === 404 || !/"status":"UP"|"openapi"|swagger-ui-bundle/.test(t), `${p} -> ${r.status()}`); }
  for (const p of ["/api/v1/auth/config", "/actuator/health"]) { const r = await ctx.request.get(`https://${SITES}${p}`, { failOnStatusCode: false }); expect(r.status() === 404, `sites host ${p} -> ${r.status()}`); }
  const direct = await ctx.request.get(`https://${FILES}/`, { failOnStatusCode: false }); expect([400, 403].includes(direct.status()), `bucket listing exposed? ${direct.status()}`);
});
await check("no unexpected console errors (CSP etc.)", async () => {
  const bad = consoleErrors.filter((e) => !/401|403|409|429|Failed to load resource/.test(e)); expect(bad.length === 0, bad.slice(0, 3).join(" | "));
});
await browser.close();
console.log(`${results.filter(Boolean).length}/${results.length} passed (test user ${user})`);
process.exit(failed ? 1 : 0);
