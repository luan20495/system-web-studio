// @class: real-backend — real browser -> real backend
// @legacy: pre-V2 single-origin root app (:3100) + scripts/run-local.sh; NOT run against integration/v2; some steps seed via SQL or stub the AI provider
// Core product E2E: a company admin sets everything up on the web, an employee then uses it. No manual DB edits, no ENV names.
//   admin login → create employee (activation link) → add a provider → key is stored write-only → test connection → discover/add models →
//   enable → default model → default limit → user-specific limit → user detail shows the effective policy → employee activates the account,
//   sees only allowed models, is stopped by the quota with a clear Vietnamese message.
// Needs the local stack (./scripts/run-local.sh). A local OpenAI-compatible stub plays the "AI provider" on 127.0.0.1:18099.
import { chromium } from "playwright-core";
import { createServer } from "node:http";
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";

const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const PW = env.LOCAL_ADMIN_PASSWORD, BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100";
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q.replace(/"/g, '\\"')}"`).toString().trim();
const results = []; let failed = false;
const pages = {};
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }); console.log("PASS", name); }
  catch (e) { failed = true; results.push({ name, ok: false }); console.log("FAIL", name, "-", String(e.message).split("\n")[0]);
    if (process.env.E2E_DEBUG) for (const [k, pg] of Object.entries(pages)) await pg.screenshot({ path: `/tmp/e2e-${k}-${results.length}.png` }).catch(() => {}); }
}
const expect = (c, m) => { if (!c) throw new Error(m); };
const KEY = "e2e-secret-key-" + randomBytes(6).toString("hex");
const USER = "nv" + randomBytes(4).toString("hex"), EMP_PW = "Mk-" + randomBytes(6).toString("hex") + "9";

// ---- stub provider (OpenAI-compatible)
const seen = [];
const stub = createServer((req, res) => {
  let body = ""; req.on("data", (c) => (body += c)); req.on("end", () => {
    seen.push({ path: req.url, auth: req.headers.authorization ?? null });
    res.setHeader("Content-Type", "application/json");
    if (req.url.endsWith("/models")) return req.headers.authorization === `Bearer ${KEY}` ? res.end(JSON.stringify({ data: [{ id: "stub-model" }, { id: "stub-big" }] })) : (res.statusCode = 401, res.end("{}"));
    const content = JSON.stringify({ message: "Đã đổi tiêu đề bằng AI của công ty", operations: [{ type: "UPDATE_PROP", sectionId: "hero-1", path: "title", value: "Tiêu đề từ AI" }] });
    res.end(JSON.stringify({ id: "stub-1", choices: [{ message: { role: "assistant", content } }], usage: { prompt_tokens: 1000, completion_tokens: 200, total_tokens: 1200 } }));
  });
}).listen(18099, "127.0.0.1");

const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
async function newPage() { const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage(); p.on("dialog", (d) => d.accept());
  if (process.env.E2E_DEBUG) p.on("response", (r) => { if (r.status() >= 400 && !r.url().endsWith("/auth/me")) console.log("   HTTP", r.status(), r.request().method(), r.url().replace(BASE, "")); });
  return { c, p }; }
async function login(s, user, pw, portal) {
  await s.p.goto(BASE + "/login"); await s.p.getByRole("radio", { name: portal === "admin" ? /Admin Console/ : /Builder Studio/ }).check();
  await s.p.getByLabel("Tên đăng nhập").fill(user); await s.p.getByLabel("Mật khẩu").fill(pw); await s.p.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await s.p.waitForURL(portal === "admin" ? /\/admin/ : /\/studio/);
}
const cleanup = () => { sql("delete from ai_limit_overrides"); sql("delete from ai_model_policies where model_id like '%stub-%'"); sql("delete from system_settings where key like 'ai.%'"); sql("delete from ai_providers"); };
cleanup();
const admin = await newPage(); pages.admin = admin.p; await login(admin, "local.admin", PW, "admin");
let activationLink = "", PID = "", WS = "";

await check("admin home shows the setup checklist with links to the exact screens", async () => {
  await admin.p.goto(BASE + "/admin"); await admin.p.getByRole("heading", { name: "Thiết lập ban đầu" }).waitFor();
  for (const t of ["Tài khoản quản trị", "Thêm nhà cung cấp AI", "Chọn mô hình mặc định", "Thiết lập hạn mức AI", "Thêm người dùng", "Tạo website đầu tiên"]) await admin.p.getByText(t, { exact: true }).first().waitFor();
});
await check("admin creates an employee and gets a one-time activation link (no password shown)", async () => {
  await admin.p.goto(BASE + "/admin/users"); await admin.p.getByRole("button", { name: "+ Thêm người dùng" }).click();
  const d = admin.p.getByRole("dialog", { name: "Thêm người dùng" });
  await d.getByLabel("Tên đăng nhập").fill(USER); await d.getByLabel("Tên hiển thị").fill("Nhân viên E2E");
  await admin.p.waitForFunction(() => { const s = document.querySelector("[role=dialog] select"); return s && !s.disabled && s.options.length > 1; });
  await d.locator("select").nth(0).selectOption({ index: 0 });
  await d.locator("select").nth(1).selectOption({ label: "Biên tập viên" }); await d.getByRole("button", { name: "Tạo người dùng" }).click();
  const link = admin.p.getByRole("dialog", { name: "Liên kết kích hoạt" }); activationLink = await link.getByLabel("Liên kết").inputValue();
  expect(/\/auth\/activate#[A-Za-z0-9_-]{30,}/.test(activationLink), "link shape"); expect(!(await link.innerText()).match(/mật khẩu tạm/i), "temporary password shown");
  await link.getByRole("button", { name: "Xong" }).click(); await link.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click();   // asks before discarding a link that was not copied (M-007)
  WS = sql(`select workspace_id from workspace_members m join users u on u.id=m.user_id where u.username='${USER}'`);
});
await check("admin adds an AI provider; the key is stored write-only and the connection test passes", async () => {
  await admin.p.goto(BASE + "/admin/ai/providers"); await admin.p.getByRole("button", { name: "Thêm nhà cung cấp" }).first().click();
  const d = admin.p.getByRole("dialog", { name: "Thêm nhà cung cấp" });
  await d.getByLabel("Loại").selectOption({ label: "AI nội bộ (Local)" }); await d.getByLabel("Tên").fill("Stub E2E");
  await d.getByLabel("Địa chỉ dịch vụ").fill("http://127.0.0.1:18099/v1"); await d.getByLabel(/Khóa kết nối/).fill(KEY); await d.getByRole("button", { name: "Lưu" }).click();
  const item = admin.p.getByRole("region", { name: "Stub E2E" }); await item.getByText(/Kết nối thành công/).waitFor();
  await item.getByText("✓ Đã cấu hình").first().waitFor();
  expect(!(await admin.p.content()).includes(KEY), "key visible in the page");
  expect(!sql("select api_key_enc from ai_providers").includes(KEY) && sql("select api_key_enc from ai_providers").startsWith("v1:"), "key not encrypted at rest");
  expect(sql(`select count(*) from audit_events where new_value::text like '%${KEY}%'`) === "0", "key in the audit log");
  await item.getByRole("button", { name: "Kiểm tra kết nối" }).click(); await item.getByText(/Kết nối thành công/).waitFor();
});
await check("editing shows the key only as configured and offers to keep it", async () => {
  const item = admin.p.getByRole("region", { name: "Stub E2E" }); await item.getByRole("button", { name: "Sửa" }).click();
  const d = admin.p.getByRole("dialog", { name: "Sửa nhà cung cấp" }); await d.getByText("Để trống nếu không muốn thay đổi khóa hiện tại.").waitFor();
  expect((await d.getByLabel(/Khóa kết nối/).inputValue()) === "", "key field must be empty"); await d.getByRole("button", { name: "Hủy" }).click();
});
await check("admin loads the model list from the provider and adds a model", async () => {
  const item = admin.p.getByRole("region", { name: "Stub E2E" }); await item.getByRole("button", { name: "Tải danh sách mô hình" }).click();
  const d = admin.p.getByRole("dialog", { name: "Chọn mô hình" }); await d.getByText(/Tìm thấy 2 mô hình/).waitFor();
  await d.getByLabel("stub-model").check(); await d.getByRole("button", { name: "Lưu danh sách" }).click();
  await admin.p.getByRole("region", { name: "Stub E2E" }).getByText("Đã cấu hình").first().waitFor();
});
await check("admin enables the model and makes it the default", async () => {
  await admin.p.getByRole("tab", { name: "Mô hình" }).click();
  const row = admin.p.getByRole("row", { name: /stub-model/ });
  await row.getByText("Miễn phí").waitFor(); await row.getByLabel("Cho phép stub-model").check(); await row.getByText("Bật", { exact: true }).waitFor();
  await row.getByRole("button", { name: "Đặt làm mặc định" }).click(); await admin.p.getByRole("row", { name: /stub-model/ }).getByText("Mặc định", { exact: true }).waitFor();
  expect(sql("select value from system_settings where key='ai.default-model'").endsWith(":stub-model"), "default not stored");
});
await check("default limits are shown plainly (0 is never a bare zero) and can be changed", async () => {
  await admin.p.getByRole("tab", { name: "Hạn mức" }).click(); await admin.p.getByRole("heading", { name: "Hạn mức mặc định" }).waitFor();
  await admin.p.getByText("Chưa cấp ngân sách").first().waitFor(); await admin.p.getByText("Không giới hạn").first().waitFor();
  await admin.p.getByLabel("Lượt AI / người / ngày").fill("3"); await admin.p.getByRole("button", { name: "Lưu hạn mức mặc định" }).click();
  await admin.p.getByText("Đã lưu hạn mức mặc định.").waitFor(); expect(sql("select value from system_settings where key='ai.daily-requests-per-user'") === "3", "limit not stored");
});
await check("admin sets a limit for this employee only and the user detail shows the effective policy", async () => {
  const uid = sql(`select id from users where username='${USER}'`);
  await admin.p.goto(`${BASE}/admin/users/${uid}`); const card = admin.p.locator(".card", { has: admin.p.getByRole("heading", { name: "AI của người này" }) });
  await card.getByText("Mặc định của công ty").first().waitFor();
  await card.getByRole("button", { name: "Thiết lập hạn mức riêng" }).click();
  const d = admin.p.getByRole("dialog", { name: /Thiết lập hạn mức riêng/ }); await d.getByLabel("Lượt AI / ngày").fill("1"); await d.getByRole("button", { name: "Lưu" }).click();
  await admin.p.getByText("Đã lưu hạn mức riêng.").waitFor();
  await card.getByText("Riêng cho người này").first().waitFor(); await card.getByText(/stub-model/).first().waitFor();
});
await check("employee activates the account with the link and sets their own password", async () => {
  const e = await newPage(); await e.p.goto(activationLink); await e.p.getByRole("heading", { name: /Chào/ }).waitFor();
  await e.p.getByLabel("Mật khẩu", { exact: true }).fill(EMP_PW); await e.p.getByLabel("Nhập lại mật khẩu").fill(EMP_PW); await e.p.getByRole("button", { name: "Lưu mật khẩu" }).click();
  await e.p.getByRole("heading", { name: "Đã đặt mật khẩu" }).waitFor();
  await e.c.close();
  const again = await newPage(); await again.p.goto(activationLink); await again.p.getByText("Liên kết không dùng được").waitFor();   // single use
  await again.c.close();
});
const emp = await newPage();
await check("employee only sees the enabled model and no provider details", async () => {
  await login(emp, USER, EMP_PW, "builder");
  const status = await (await emp.c.request.get(BASE + "/api/v1/ai/status")).json();
  expect(status.models.length === 1 && status.models[0].name === "stub-model", `models: ${JSON.stringify(status.models.map((m) => m.id))}`);
  expect(!JSON.stringify(status).includes(KEY) && !JSON.stringify(status).includes("18099"), "key or address visible to the employee");
  expect((await emp.c.request.get(BASE + "/api/v1/admin/ai/providers")).status() === 403, "employee reached the provider list");
  const me = await (await emp.c.request.get(BASE + "/api/v1/auth/me")).json(); WS = me.workspaces[0].id;
});
await check("employee uses the company default model; the second request is stopped with a clear Vietnamese message", async () => {
  const t = (await (await emp.c.request.get(BASE + "/api/v1/auth/csrf")).json()).token;
  const created = await emp.c.request.post(`${BASE}/api/v1/workspaces/${WS}/projects`, { headers: { "X-XSRF-TOKEN": t, "Content-Type": "application/json" }, data: { name: "AI E2E" } });
  PID = (await created.json()).id;
  const p = emp.p; await p.goto(`${BASE}/studio/projects/${PID}/ai`);
  const picker = p.getByLabel("Model AI"); await picker.waitFor(); expect((await picker.inputValue()).endsWith(":stub-model"), "company default not preselected");
  const options = await picker.locator("option").allInnerTexts(); expect(!options.some((o) => /18099|e2e-secret/.test(o)), "technical detail in model list");
  await p.getByLabel("Mô tả thay đổi").fill("đổi tiêu đề"); await p.getByRole("button", { name: /Gửi/ }).click();
  await p.getByText("Đã đổi tiêu đề bằng AI của công ty").waitFor({ timeout: 20000 });
  expect(seen.some((s) => s.path.endsWith("/chat/completions") && s.auth === `Bearer ${KEY}`), "provider did not get the key server-side");
  await p.getByLabel("Mô tả thay đổi").fill("đổi tiêu đề lần nữa"); await p.getByRole("button", { name: /Gửi/ }).click();
  await p.getByText(/Bạn đã dùng hết 1 lượt AI hôm nay/).waitFor({ timeout: 15000 });
  expect(!(await p.content()).match(/AI_DAILY_LIMIT|RATE_LIMITED/), "raw error code visible");
});
await check("admin removes the provider; its models stop working at once", async () => {
  await admin.p.goto(BASE + "/admin/ai/providers"); await admin.p.getByRole("region", { name: "Stub E2E" }).getByRole("button", { name: "Xóa" }).click();
  await admin.p.getByText(/Đã xóa “Stub E2E”/).waitFor();
  const status = await (await emp.c.request.get(BASE + "/api/v1/ai/status")).json(); expect(status.models.length === 0, "model still offered");
});

cleanup();
await browser.close(); stub.close();
console.log(`\n${results.filter((r) => r.ok).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
