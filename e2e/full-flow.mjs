// Real-browser E2E against the running local stack (scripts/run-local.sh). Exits non-zero on the first failed check.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";

const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const PASSWORD = env.LOCAL_ADMIN_PASSWORD;
const BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100";
const OUT = new URL("../.run/e2e/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q}"`).toString().trim();

const results = [];
let failed = false;
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }); console.log("PASS", name); }
  catch (e) { failed = true; results.push({ name, ok: false, error: String(e.message ?? e).split("\n")[0] }); console.log("FAIL", name, "-", String(e.message ?? e).split("\n")[0]); }
}
const expect = (cond, msg) => { if (!cond) throw new Error(msg); };

const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const consoleErrors = [];
async function newPage(width = 1440, height = 900) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  const page = await ctx.newPage();
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(`${m.text()} @ ${page.url()}`); });
  page.on("pageerror", (e) => consoleErrors.push(`pageerror ${e.message}`));
  return { ctx, page };
}
const frameText = async (page) => (await (await page.waitForSelector("iframe.previewFrame")).contentFrame()).evaluate(() => document.body.innerText);
async function login(page, username, password = PASSWORD) {
  await page.goto(BASE);
  await page.getByLabel("Tên đăng nhập").fill(username);
  await page.getByLabel("Mật khẩu").fill(password);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
}
const NAME = `E2E ${new Date().toISOString().slice(11, 19)} ${Math.random().toString(36).slice(2, 6)}`;
let PID = "";
async function openProject(page) { await page.getByRole("button", { name: new RegExp(NAME) }).click(); await page.waitForSelector("iframe.previewFrame", { state: "attached" }); }
async function frameHas(page, text, present = true) {
  for (let i = 0; i < 40; i++) { if ((await frameText(page)).includes(text) === present) return; await page.waitForTimeout(250); }
  throw new Error(`preview ${present ? "missing" : "still has"} "${text}"`);
}
async function sendPrompt(page, text) {
  await page.getByPlaceholder(/Mô tả thay đổi/).fill(text);
  const [resp] = await Promise.all([page.waitForResponse((r) => r.url().endsWith("/prompts") && r.request().method() === "POST"), page.getByRole("button", { name: /Gửi/ }).click()]);
  return resp.status();
}
const versionCount = () => Number(sql(`select count(*) from project_versions where project_id='${PID}'`));
async function openDemo(page) { await page.getByRole("button", { name: /Water Purifier Website/ }).click(); await page.waitForSelector("iframe.previewFrame", { state: "attached" }); }

// ---- admin flow -------------------------------------------------------------------------------
const { page: admin, ctx: adminCtx } = await newPage();
let publishedUrl = "";

await check("wrong credentials show an error and stay on login", async () => {
  await login(admin, "nobody", "definitely-wrong-pass");
  await admin.getByText("Sai tên đăng nhập hoặc mật khẩu.").waitFor({ timeout: 8000 });
});
await check("login with the real backend lists projects", async () => {
  await login(admin, "local.admin");
  await admin.getByRole("button", { name: /Water Purifier Website/ }).waitFor({ timeout: 10000 });
});
await check("session cookie is HttpOnly and no auth data is in localStorage", async () => {
  const cookies = await adminCtx.cookies();
  const session = cookies.find((c) => c.name === "STUDIO_SESSION");
  expect(session?.httpOnly === true, "STUDIO_SESSION must be HttpOnly");
  const ls = await admin.evaluate(() => JSON.stringify({ ...localStorage }));
  expect(ls === "{}", `localStorage should be empty in http mode, got ${ls}`);
});
await check("create a project through the UI (RBAC + initial schema + audit)", async () => {
  await admin.getByPlaceholder("Tên project mới").fill(NAME);
  await admin.getByRole("button", { name: "Tạo project" }).click();
  await admin.waitForSelector("iframe.previewFrame");
  PID = sql(`select id from projects where name='${NAME}'`);
  expect(PID.length === 36, "project not created");
  expect(sql(`select count(*) from audit_events where project_id='${PID}' and action='CREATE_PROJECT'`) === "1", "no audit row");
});
await check("open project renders the schema preview from PostgreSQL", async () => {
  await admin.reload(); await openProject(admin);
  expect((await frameText(admin)).includes("Nước sạch mỗi ngày"), "hero title missing");
});
await check("prompt adds a ComparisonBlock and it survives a page reload", async () => {
  expect(await sendPrompt(admin, "Thêm bảng so sánh 3 sản phẩm") === 200, "prompt failed");
  await frameHas(admin, "So sánh sản phẩm");
  expect(versionCount() === 2, "version not stored");
  await admin.reload(); await openProject(admin);
  await frameHas(admin, "So sánh sản phẩm");
});
await check("prompt hides testimonials and persists", async () => {
  expect(await sendPrompt(admin, "bỏ phần đánh giá") === 200, "prompt failed");
  await frameHas(admin, "Khách hàng nói gì", false);
  await admin.reload(); await openProject(admin);
  await frameHas(admin, "Khách hàng nói gì", false);
});
await check("unsupported prompt changes nothing and says so", async () => {
  const before = versionCount();
  expect(await sendPrompt(admin, "hãy viết thơ về mùa thu") === 200, "prompt failed");
  await admin.getByText("UNSUPPORTED").first().waitFor({ timeout: 10000 });
  expect(versionCount() === before, "version created for unsupported prompt");
});
await check("history lists versions and restore creates a NEW version with the old content", async () => {
  await admin.getByRole("button", { name: "Lịch sử" }).click();
  await admin.getByText(/Phiên bản 1\b/).waitFor();
  const count = versionCount();
  admin.once("dialog", (d) => d.accept());
  await admin.locator("article.versionItem", { hasText: "Phiên bản 1" }).getByRole("button", { name: "Khôi phục" }).click();
  await admin.getByText(/Đã khôi phục phiên bản 1/).waitFor({ timeout: 10000 });
  expect(versionCount() === count + 1, "restore must add exactly one version");
  await admin.reload(); await openProject(admin);
  await frameHas(admin, "Khách hàng nói gì"); await frameHas(admin, "So sánh sản phẩm", false);
});
await check("direct edit through the registry-driven inspector is validated, versioned and persists", async () => {
  await admin.getByRole("button", { name: "Chỉnh sửa", exact: true }).click();
  await admin.getByRole("button", { name: /Danh sách sản phẩm/ }).click();
  const inspector = admin.getByRole("region", { name: /Chỉnh sửa Danh sách sản phẩm/ });
  await inspector.getByLabel("Tên", { exact: true }).first().fill("K-Series Pure E2E");
  await inspector.getByRole("button", { name: "Lưu thay đổi" }).click();
  await admin.getByText(/Đã lưu/).first().waitFor({ timeout: 10000 });
  await frameHas(admin, "K-Series Pure E2E");
  await admin.reload(); await openProject(admin);
  await frameHas(admin, "K-Series Pure E2E");
  expect(sql(`select count(*) from project_versions where project_id='${PID}' and kind='EDIT' and summary like 'Chỉnh sửa %'`) !== "0", "inspector edit not stored as its own version");
});
await check("settings save to the backend and persist", async () => {
  await admin.getByRole("button", { name: "Cài đặt" }).first().click();
  await admin.getByLabel("Tên miền xem trước").fill("e2e.example.com");
  await admin.getByRole("button", { name: "Lưu thay đổi" }).click();
  await admin.getByText("Đã lưu cài đặt project.").waitFor({ timeout: 10000 });
  expect(sql(`select domain from projects where id='${PID}'`) === "e2e.example.com", "domain not stored");
});
await check("invalid domain is rejected by the server and shown to the user", async () => {
  await admin.getByRole("button", { name: "Cài đặt" }).first().click();
  await admin.getByLabel("Tên miền xem trước").fill("not a domain!!");
  await admin.getByRole("button", { name: "Lưu thay đổi" }).click();
  await admin.locator(".toast").waitFor({ timeout: 10000 });
  expect(sql(`select domain from projects where id='${PID}'`) === "e2e.example.com", "invalid domain stored");
  await admin.keyboard.press("Escape"); await admin.getByRole("dialog").waitFor({ state: "detached" });   // Escape closes the dialog
});
await check("asset upload (presigned MinIO), list and delete", async () => {
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
  writeFileSync(OUT + "pixel.png", png);
  await admin.getByRole("button", { name: "Tệp" }).click();
  await admin.locator('input[type=file]').setInputFiles(OUT + "pixel.png");
  await admin.getByText("pixel.png").waitFor({ timeout: 15000 });
  expect(sql("select count(*) from assets where name='pixel.png' and status='READY'") !== "0", "asset not READY in DB");
  admin.once("dialog", (d) => d.accept());
  await admin.locator("article.versionItem", { hasText: "pixel.png" }).getByRole("button", { name: "Xóa" }).click();
  await admin.getByText("Chưa có tệp nào.").waitFor({ timeout: 10000 });
  await admin.getByRole("button", { name: "Đóng" }).click();
});
await check("publish runs the pipeline through RabbitMQ to RUNNING and labels the URL as mock", async () => {
  await admin.getByRole("button", { name: "Xuất bản" }).first().click();
  await admin.getByRole("button", { name: /Công khai/ }).click();
  await admin.getByRole("button", { name: "Xuất bản" }).last().click();
  await admin.getByText("Đang chạy", { exact: true }).waitFor({ timeout: 30000 });
  const text = await admin.locator(".deployBox").innerText();
  expect(/mô phỏng/.test(text), "mock URL must be labelled");
  publishedUrl = (await admin.locator(".deployBox code").innerText()).trim();
  expect(sql("select status from deployments order by created_at desc limit 1") === "RUNNING", "deployment not RUNNING in DB");
  await admin.getByRole("button", { name: "Đóng" }).click();
});
await check("session survives a backend restart (Redis-backed)", async () => {
  execSync("lsof -ti tcp:8080 | xargs kill", { stdio: "ignore" });
  execSync("sleep 3");
  execSync("./scripts/run-local.sh < /dev/null > .run/run-local.out 2>&1", { cwd: new URL("..", import.meta.url).pathname, shell: "/bin/bash", timeout: 240000 });
  await admin.reload();
  await admin.getByRole("button", { name: /Water Purifier Website/ }).waitFor({ timeout: 15000 });
});
await check("logout returns to login and the old session is dead", async () => {
  await openProject(admin);
  await admin.getByRole("button", { name: "Đăng xuất" }).click();
  await admin.getByRole("button", { name: "Đăng nhập" }).waitFor();
  const r = await adminCtx.request.get(BASE + "/api/v1/auth/me");
  expect(r.status() === 401, `expected 401 after logout, got ${r.status()}`);
});

// ---- RBAC -------------------------------------------------------------------------------------
const { page: viewer, ctx: viewerCtx } = await newPage();
await check("viewer: can read, cannot edit/publish in UI, and the API refuses direct calls", async () => {
  await login(viewer, "local.viewer"); await openDemo(viewer);
  expect(await viewer.getByRole("button", { name: "Xuất bản" }).first().isDisabled(), "publish must be disabled");
  expect(await viewer.getByRole("button", { name: "Chỉnh sửa" }).isDisabled(), "edit must be disabled");
  expect(await viewer.getByPlaceholder(/chỉ có quyền xem/).isDisabled(), "prompt box must be disabled");
  const csrf = (await (await viewerCtx.request.get(BASE + "/api/v1/auth/csrf")).json()).token;
  const wsId = "00000000-0000-0000-0000-000000000001", pid = "00000000-0000-0000-0000-0000000000d1";
  const hdr = { "X-XSRF-TOKEN": csrf, "Content-Type": "application/json" };
  const p = await viewerCtx.request.post(`${BASE}/api/v1/workspaces/${wsId}/projects/${pid}/prompts`, { headers: hdr, data: { prompt: "bỏ đánh giá", expectedRevision: 0 } });
  const pub = await viewerCtx.request.post(`${BASE}/api/v1/workspaces/${wsId}/projects/${pid}/publish`, { headers: { ...hdr, "Idempotency-Key": "viewer-attempt-0001" }, data: { visibility: "PUBLIC", expectedRevision: 0 } });
  expect(p.status() === 403 && pub.status() === 403, `expected 403/403 got ${p.status()}/${pub.status()}`);
});
await check("disabled user loses access at runtime without logging in again", async () => {
  const { page: ed } = await newPage();
  await login(ed, "local.editor"); await openDemo(ed);
  sql("update users set enabled=false where username='local.editor'");
  try {
    await ed.getByPlaceholder(/Mô tả thay đổi/).fill("hiện đánh giá");
    await ed.getByRole("button", { name: /Gửi/ }).click();
    await ed.getByRole("button", { name: "Đăng nhập" }).waitFor({ timeout: 10000 });
  } finally { sql("update users set enabled=true where username='local.editor'"); }
});
await check("editor cannot publish; publisher cannot edit (UI)", async () => {
  const { page: ed } = await newPage(); await login(ed, "local.editor"); await openDemo(ed);
  expect(await ed.getByRole("button", { name: "Xuất bản" }).first().isDisabled(), "editor publish must be disabled");
  const { page: pb } = await newPage(); await login(pb, "local.publisher"); await openDemo(pb);
  expect(!(await pb.getByRole("button", { name: "Xuất bản" }).first().isDisabled()), "publisher publish must be enabled");
  expect(await pb.getByRole("button", { name: "Chỉnh sửa" }).isDisabled(), "publisher edit must be disabled");
});

// ---- two editors, stale revision ----------------------------------------------------------------
await check("stale revision from a second tab yields a conflict message and reloads server state", async () => {
  const a = await newPage(), b = await newPage();
  await login(a.page, "local.admin"); await openProject(a.page);
  await login(b.page, "local.admin"); await openProject(b.page);
  await a.page.getByPlaceholder(/Mô tả thay đổi/).fill("thêm sản phẩm"); await a.page.getByRole("button", { name: /Gửi/ }).click();
  await a.page.getByText(/Đã thêm sản phẩm/).waitFor({ timeout: 10000 });
  await b.page.getByPlaceholder(/Mô tả thay đổi/).fill("rút gọn hero"); await b.page.getByRole("button", { name: /Gửi/ }).click();
  await b.page.getByText(/vừa được thay đổi ở nơi khác/).waitFor({ timeout: 10000 });
});

// ---- responsive widths ----------------------------------------------------------------------------
for (const width of [1280, 1440, 1920, 2560]) {
  await check(`layout at ${width}px: no horizontal page scroll, toolbar visible, screenshot saved`, async () => {
    const { page } = await newPage(width, 1000);
    await login(page, "local.admin"); await openProject(page);
    const m = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, top: document.querySelector(".topbar")?.getBoundingClientRect().top }));
    expect(m.sw <= m.cw, `horizontal overflow ${m.sw} > ${m.cw}`);
    expect(m.top === 0, `topbar not pinned (top=${m.top})`);
    await page.screenshot({ path: `${OUT}studio-${width}.png` });
  });
}
await check("layout at 390px (mobile): no horizontal scroll", async () => {
  const { page } = await newPage(390, 800); await login(page, "local.admin"); await openProject(page);
  const m = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
  expect(m.sw <= m.cw, `overflow ${m.sw} > ${m.cw}`);
});

await check("no unexpected console errors", async () => {
  const unexpected = consoleErrors.filter((e) => !/401|403|409|429|Failed to load resource/.test(e));
  expect(unexpected.length === 0, unexpected.slice(0, 3).join(" | "));
});

await browser.close();
writeFileSync(OUT + "results.json", JSON.stringify({ at: new Date().toISOString(), publishedUrl, results, consoleErrors }, null, 2));
console.log(`\n${results.filter((r) => r.ok).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
