// Phase 6 browser E2E: a non-OpenRouter provider end to end, using a local OpenAI-compatible stub as the "internal model".
// The API must be started with the stub configured, e.g.:
//   LOCAL_LLM_BASE_URL=http://127.0.0.1:18099/v1 LOCAL_LLM_MODELS=stub-model ./scripts/run-local.sh
// This script starts the stub on 127.0.0.1:18099 itself (start the API after or before; the stub only has to be up during the run).
import { chromium } from "playwright-core";
import { createServer } from "node:http";
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";

const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const PW = env.LOCAL_ADMIN_PASSWORD, BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100", MODEL = "local:stub-model";
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q.replace(/"/g, '\\"')}"`).toString().trim();
const results = []; let failed = false;
async function check(name, fn) { try { await fn(); results.push({ name, ok: true }); console.log("PASS", name); } catch (e) { failed = true; results.push({ name, ok: false }); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } }
const expect = (c, m) => { if (!c) throw new Error(m); };

// ---- stub internal model (OpenAI-compatible)
const seen = [];
const stub = createServer((req, res) => {
  let body = ""; req.on("data", (c) => (body += c)); req.on("end", () => {
    seen.push({ path: req.url, auth: req.headers.authorization ?? null, body: body ? JSON.parse(body) : null });
    res.setHeader("Content-Type", "application/json");
    if (req.url.endsWith("/models")) return res.end(JSON.stringify({ data: [{ id: "stub-model" }] }));
    const content = JSON.stringify({ message: "Đã đổi tiêu đề bằng model nội bộ", operations: [{ type: "UPDATE_PROP", sectionId: "hero-1", path: "title", value: "Tiêu đề từ model nội bộ" }] });
    res.end(JSON.stringify({ id: "stub-1", choices: [{ message: { role: "assistant", content } }], usage: { prompt_tokens: 1000, completion_tokens: 200, total_tokens: 1200 } }));
  });
}).listen(18099, "127.0.0.1");

const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
async function session(user, portal) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage(); p.on("dialog", (d) => d.accept());
  await p.goto(BASE + "/login"); await p.getByRole("radio", { name: portal === "admin" ? /Admin Console/ : /Builder Studio/ }).check();
  await p.getByLabel("Tên đăng nhập").fill(user); await p.getByLabel("Mật khẩu").fill(PW); await p.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await p.waitForURL(portal === "admin" ? /\/admin/ : /\/studio/); return { c, p };
}
sql(`delete from ai_model_policies where model_id='${MODEL}'`);
const admin = await session("local.admin", "admin"); const editor = await session("local.editor", "builder");
let PID = "";

await check("provider is listed as configured (no key shown), its model starts disabled and is not offered to employees", async () => {
  await admin.p.goto(BASE + "/admin/ai"); const item = admin.p.getByRole("region", { name: /Model nội bộ/ });
  await item.getByText(/Đã cấu hình/).waitFor(); expect(!(await item.getByLabel(`Cho phép ${MODEL}`).isChecked()), "model should start disabled");
  const status = await (await editor.c.request.get(BASE + "/api/v1/ai/status")).json();
  expect(!status.models.some((m) => m.id === MODEL), "disabled model offered");
});
await check("live connection check reaches the provider", async () => {
  const item = admin.p.getByRole("region", { name: /Model nội bộ/ });
  await item.getByRole("button", { name: "Kiểm tra kết nối" }).click(); await item.getByText(/Kết nối được/).waitFor();
});
await check("admin enables the model and adds a price (explicit, applies from now)", async () => {
  const item = admin.p.getByRole("region", { name: /Model nội bộ/ });
  await item.getByLabel(`Cho phép ${MODEL}`).check(); await item.getByText("Bật", { exact: true }).waitFor();
  const card = admin.p.locator(".card", { has: admin.p.getByRole("heading", { name: "Bảng giá model" }) });
  await card.getByLabel("Model").selectOption(MODEL); await card.getByLabel("Giá token vào (USD / 1 triệu)").fill("0.5"); await card.getByLabel("Giá token ra (USD / 1 triệu)").fill("1.5");
  await card.getByLabel("Ghi chú (nguồn giá)").fill("E2E"); await card.getByRole("button", { name: "Thêm giá" }).click(); await card.getByText(/Đã thêm giá/).waitFor();
  expect(sql(`select enabled from ai_model_policies where model_id='${MODEL}'`) === "t", "policy not stored");
});
await check("employee picks the internal model; the edit is applied; tokens and catalog cost are shown", async () => {
  const p = editor.p;
  await p.goto(`${BASE}/studio/new`); await p.getByLabel("Tên ứng dụng").fill("Provider E2E"); await p.getByRole("button", { name: "Tạo website" }).click();
  await p.waitForURL(/\/studio\/projects\/[0-9a-f-]{36}\/ai/); PID = p.url().match(/projects\/([0-9a-f-]{36})/)[1];
  await p.getByLabel("Model AI").selectOption(MODEL);
  await p.getByText(/máy chủ model nội bộ/).waitFor();
  await p.getByLabel("Mô tả thay đổi").fill("đổi tiêu đề"); await p.getByRole("button", { name: /Gửi/ }).click();
  await p.getByText("Đã đổi tiêu đề bằng model nội bộ").waitFor({ timeout: 20000 });
  await p.getByText(/1\.200 token · \$0\.00080/).waitFor();                  // (1000×0.5 + 200×1.5) / 1e6 = 0.0008
  const call = seen.find((s) => s.path.endsWith("/chat/completions")); expect(call && call.body.model === "stub-model", "bare model not sent"); expect(call.auth === null, "no key configured, none must be sent");
  expect(sql(`select cost_source||':'||provider from ai_calls where project_id='${PID}'`) === "CATALOG:local", "cost source / provider not recorded");
});
await check("admin call log shows the call priced from the catalog", async () => {
  await admin.p.goto(BASE + "/admin/ai"); await admin.p.getByRole("button", { name: "Hôm nay" }).click();
  const log = admin.p.locator(".card", { has: admin.p.getByRole("heading", { name: "Nhật ký lượt gọi model" }) });
  await log.getByLabel("Model").selectOption(MODEL); await log.getByText("theo bảng giá").first().waitFor();
});
await check("disabling the model takes effect immediately", async () => {
  const item = admin.p.getByRole("region", { name: /Model nội bộ/ }); await item.getByLabel(`Cho phép ${MODEL}`).uncheck(); await item.getByText("Tắt", { exact: true }).waitFor();
  const t = (await (await editor.c.request.get(BASE + "/api/v1/auth/csrf")).json()).token;
  const proj = await (await editor.c.request.get(`${BASE}/api/v1/projects/${PID}`)).json();
  const rev = proj.revision;
  const r = await editor.c.request.post(`${BASE}/api/v1/workspaces/${proj.workspaceId}/projects/${PID}/prompts`, { headers: { "X-XSRF-TOKEN": t, "Content-Type": "application/json" }, data: { prompt: "x", expectedRevision: rev, model: MODEL } });
  expect(r.status() === 400, `expected 400, got ${r.status()}`);
});

sql(`delete from ai_model_policies where model_id='${MODEL}'`);
await browser.close(); stub.close();
console.log(`\n${results.filter((r) => r.ok).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
