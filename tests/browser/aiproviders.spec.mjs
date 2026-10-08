// @class: harness — real Chromium on Platform → AI → Nhà cung cấp with an in-page FAKE of the admin AI routes (no backend): list rows (logo, name, status, model count, test connection), the add dialog (icons, picker, switches, key field),
// the exact request body (contract unchanged), keyboard, and "no icon comes from the internet". NOT a backend E2E.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/aiproviders.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = []; const hosts = new Set();
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
async function open(s = "ok") {
  const p = await browser.newPage({ viewport: { width: 1200, height: 1000 } }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  p.on("request", (r) => { try { hosts.add(new URL(r.url()).host); } catch { /* data: */ } });
  await p.goto(`${ORIGIN}/ai.html?s=${s}`); await p.getByTestId("provider:p1").waitFor().catch(() => undefined); return p;
}
const T = (p, id) => p.getByTestId(id);
const reqs = (p) => p.evaluate(() => window.__ai);

// ---- list ---------------------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  const rows = await p.locator('[data-testid^="provider:"]').count();
  const logos = await p.locator('[data-testid^="provider:"] .xp-logo').evaluateAll((l) => l.map((x) => [x.getAttribute("data-kind"), x.getAttribute("data-brand"), !!x.querySelector("svg")]));
  check("list: every provider row has a logo (inline SVG): OpenRouter / Anthropic / Gemini brand marks, OpenAI / OpenAI-compatible / Local generic Lucide icons", rows === 6 && logos.length === 6 && logos.every((l) => l[2]) && JSON.stringify(logos.map((l) => l[1])) === JSON.stringify(["brand", "brand", "brand", "generic", "generic", "generic"]), JSON.stringify(logos));
  check("list: no <img>, no external icon: logos are inline SVG", (await p.locator('[data-testid^="provider:"] img, .xp-logo img').count()) === 0);
  const row1 = await T(p, "provider:p1").innerText();
  check("list: name, kind, status pills (Đang bật / Đã cấu hình), model count and allowed count", /OpenRouter/.test(row1) && /Đang bật/.test(row1) && /Đã cấu hình/.test(row1) && /3 mô hình · 2 đang được phép dùng/.test(row1));
  check("list: a disabled provider shows Đang tắt and offers Bật; a provider without models says so", /Đang tắt/.test(await T(p, "provider:p3").innerText()) && /Chưa có mô hình nào/.test(await T(p, "provider:p3").innerText()) && (await T(p, "provider:p3").getByRole("button", { name: "Bật" }).count()) === 1);
  await T(p, "provider:p1").getByRole("button", { name: "Kiểm tra kết nối" }).click(); await T(p, "provider:p1").getByTestId("probe-result").waitFor();
  await T(p, "provider:p3").getByRole("button", { name: "Kiểm tra kết nối" }).click(); await T(p, "provider:p3").getByTestId("probe-result").waitFor();
  check("list: test connection per provider: success (green, latency text) and failure (red, the server's reason)", (await T(p, "provider:p1").getByTestId("probe-result").getAttribute("data-ok")) === "true" && /Kết nối thành công/.test(await T(p, "provider:p1").getByTestId("probe-result").innerText()) && (await T(p, "provider:p3").getByTestId("probe-result").getAttribute("data-ok")) === "false" && /từ chối/.test(await T(p, "provider:p3").innerText()));
  const sent = (await reqs(p)).filter((r) => /probe/.test(r.path)); check("list: the probe calls POST /admin/ai/providers/{id}/probe, nothing else", sent.length === 2 && sent.every((r) => r.method === "POST"));
  await p.close(); }
{ const p = await open("empty"); check("empty state keeps the add action (with the icon button)", (await p.getByText("Chưa cấu hình AI thật.").count()) === 1 && (await p.getByRole("button", { name: /Thêm nhà cung cấp/ }).count()) >= 1); await p.close(); }

// ---- dialog -------------------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  await p.getByRole("button", { name: "Thêm nhà cung cấp" }).first().click(); await T(p, "provider-dialog").waitFor();
  const head = await p.locator(".xp-modalHead").innerText();
  check("dialog: header = icon tile + title + subtitle; the dialog's accessible name is still 'Thêm nhà cung cấp'", (await p.locator(".xp-modalHead .xp-headIcon svg").count()) === 1 && /Thêm nhà cung cấp/.test(head) && /Kết nối một nhà cung cấp AI cho cả công ty/.test(head) && (await p.getByRole("dialog", { name: "Thêm nhà cung cấp" }).count()) === 1);
  const sections = await p.locator(".xp-section h3").allInnerTexts();
  check("dialog: sections with icons — Nhà cung cấp, API key, Trạng thái; Nâng cao is a collapsible with an icon; Save has a Save icon", sections.join("|").toUpperCase().includes("NHÀ CUNG CẤP") && sections.join("|").toUpperCase().includes("API KEY") && sections.join("|").toUpperCase().includes("TRẠNG THÁI") && (await p.locator(".xp-section h3 svg").count()) >= 3 && (await p.locator(".xp-advBtn svg").count()) >= 1 && (await p.getByRole("button", { name: "Lưu" }).locator("svg").count()) === 1);
  check("dialog: the security note is shown next to the key (encrypted, never shown again)", /mã hóa khi lưu/.test(await p.locator(".xp-note").innerText()) && (await p.locator(".xp-note svg").count()) === 1);
  const key = p.getByLabel(/Khóa kết nối/);
  check("dialog: the key field is a PASSWORD input, no autocomplete, empty", (await key.getAttribute("type")) === "password" && (await key.getAttribute("autocomplete")) === "new-password" && (await key.inputValue()) === "");
  // picker
  await T(p, "picker-button").click();
  const opts = await p.locator('[role="option"]').evaluateAll((l) => l.map((o) => [o.getAttribute("data-value"), !!o.querySelector("svg")]));
  check("picker: a listbox with all six kinds, each with its logo and a hint; the current one is selected", JSON.stringify(opts.map((o) => o[0])) === JSON.stringify(["OPENROUTER", "OPENAI", "ANTHROPIC", "GEMINI", "OPENAI_COMPATIBLE", "LOCAL"]) && opts.every((o) => o[1]) && (await p.locator('[role="option"][aria-selected="true"]').getAttribute("data-value")) === "OPENROUTER" && (await T(p, "picker-button").getAttribute("aria-expanded")) === "true");
  await p.keyboard.press("ArrowDown"); await p.keyboard.press("ArrowDown"); await p.keyboard.press("Enter");
  check("picker keyboard: ↓ ↓ Enter chooses Anthropic and closes; focus returns to the button", (await T(p, "picker-button").innerText()).includes("Anthropic") && (await p.locator('[role="listbox"]').count()) === 0 && (await p.evaluate(() => document.activeElement?.getAttribute("data-testid"))) === "picker-button");
  await T(p, "picker-button").press("ArrowDown"); await p.keyboard.press("Escape");
  check("picker keyboard: Esc closes the list but NOT the dialog", (await p.locator('[role="listbox"]').count()) === 0 && (await T(p, "provider-dialog").count()) === 1);
  await T(p, "picker-button").click(); await p.locator('[role="option"][data-value="LOCAL"]').click();
  check("picker: choosing 'AI nội bộ (Local)' shows the service address field and the model fields (as before)", (await p.getByLabel("Địa chỉ dịch vụ").count()) === 1 && (await p.getByLabel(/Danh sách mô hình/).count()) === 1);
  check("picker: the address placeholder is neutral (no localhost)", !/localhost|127\.0\.0\.1/.test((await p.getByLabel("Địa chỉ dịch vụ").getAttribute("placeholder")) ?? ""));
  await p.getByLabel("Loại").selectOption({ label: "Tương thích OpenAI" });
  check("compat: the hidden native select still answers getByLabel('Loại').selectOption(…) (existing scripts keep working) and the picker follows", (await T(p, "picker-button").innerText()).includes("Tương thích OpenAI"));
  // switches
  const sw = p.getByRole("switch", { name: "Bật nhà cung cấp này" });
  check("switch: 'Bật nhà cung cấp này' is a role=switch, ON by default, with a state hint", (await sw.getAttribute("aria-checked")) === "true" && /có thể dùng/.test(await p.locator(".xp-switchRow").first().innerText()));
  await sw.focus(); await p.keyboard.press("Space");
  check("switch: Space toggles it (OFF, hint changes)", (await sw.getAttribute("aria-checked")) === "false" && /Đang tắt/.test(await p.locator(".xp-switchRow").first().innerText()));
  await sw.click(); await p.locator(".xp-advBtn").click();
  const paid = p.getByRole("switch", { name: "Có tính phí" });
  check("Nâng cao opens a 'Có tính phí' switch (expanded state announced)", (await p.locator(".xp-advBtn").getAttribute("aria-expanded")) === "true" && (await paid.count()) === 1);
  // submit: contract unchanged
  await p.getByLabel("Tên").fill("Cổng nội bộ"); await p.getByLabel("Địa chỉ dịch vụ").fill("https://ai.example.com/v1"); await p.getByLabel(/Khóa kết nối/).fill("sk-test-123");
  await p.getByRole("button", { name: "Lưu" }).click(); await p.waitForTimeout(500);
  const post = (await reqs(p)).find((r) => r.method === "POST" && r.path === "/admin/ai/providers");
  check("submit: POST /admin/ai/providers with EXACTLY the fields of before (name, baseUrl, apiKey, models, defaultModel, paid, enabled, kind) — nothing new", !!post && JSON.stringify(Object.keys(post.body).sort()) === JSON.stringify(["apiKey", "baseUrl", "defaultModel", "enabled", "kind", "models", "name", "paid"]) && post.body.kind === "OPENAI_COMPATIBLE" && post.body.apiKey === "sk-test-123" && post.body.enabled === true && post.body.paid === true, JSON.stringify(post?.body));
  check("after saving the dialog closes, the new provider is listed with its logo, and the key never appears in the page", (await T(p, "provider-dialog").count()) === 0 && (await p.getByTestId("provider:pn").count()) === 1 && !(await p.locator("body").innerHTML()).includes("sk-test-123"));
  await p.close(); }
{ const p = await open("dup");
  await p.getByRole("button", { name: "Thêm nhà cung cấp" }).first().click(); await p.getByLabel("Tên").fill("Dup"); await p.getByLabel(/Khóa kết nối/).fill("k"); await p.getByRole("button", { name: "Lưu" }).click();
  await p.getByRole("alert").filter({ hasText: "name already used" }).waitFor().catch(() => undefined);
  check("error: a server refusal is shown in the dialog (by the server's message) and the dialog stays open", (await T(p, "provider-dialog").count()) === 1 && /already used/.test(await T(p, "provider-dialog").innerText()));
  await p.close(); }
{ const p = await open();
  await T(p, "provider:p3").getByRole("button", { name: "Bật" }).click(); await p.waitForTimeout(400);
  const put = (await reqs(p)).find((r) => r.method === "PUT"); check("toggle on a provider still sends PUT {enabled}", put?.path === "/admin/ai/providers/p3" && put.body.enabled === true, JSON.stringify(put));
  await T(p, "provider:p5").getByRole("button", { name: "Sửa" }).click(); await T(p, "provider-dialog").waitFor();
  check("edit: no picker (the kind is fixed): logo + name + hint; key field empty with 'Để trống nếu không muốn thay đổi' when a key is set", (await T(p, "picker-button").count()) === 0 && (await p.locator("[data-testid=provider-dialog] .xp-logo").count()) === 1 && (await p.getByLabel(/Khóa kết nối/).inputValue()) === "");
  await p.close(); }
check("no request left the page's own origin (no hot-linked icon, font or logo)", [...hosts].every((h) => h === new URL(ORIGIN).host), [...hosts].join(","));
check("no console error / warning / uncaught exception", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok); console.log(`\n${results.length - failed.length}/${results.length} checks passed`); process.exit(failed.length ? 1 : 0);
