// AI Software Factory — real-browser E2E against the local stack (scripts/run-local.sh, profile local seed users).
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";

const env = Object.fromEntries(readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const PW = env.LOCAL_ADMIN_PASSWORD, BASE = process.env.E2E_BASE ?? "http://127.0.0.1:3100", DEMO = "00000000-0000-0000-0000-0000000000d1";
const OUT = new URL("../.run/e2e-factory/", import.meta.url).pathname; mkdirSync(OUT, { recursive: true });
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q.replace(/"/g, '\\"')}"`).toString().trim();
const results = []; let failed = false; const consoleErrors = [];
async function check(name, fn) { try { await fn(); results.push({ name, ok: true }); console.log("PASS", name); } catch (e) { failed = true; results.push({ name, ok: false, error: String(e.message).split("\n")[0] }); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } }
const expect = (c, m) => { if (!c) throw new Error(m); };
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
async function ctx(width = 1440) {
  const c = await browser.newContext({ viewport: { width, height: 900 } }); const p = await c.newPage();
  p.on("console", (m) => { if (m.type() === "error") consoleErrors.push(`${m.text()} @ ${p.url()}`); }); p.on("pageerror", (e) => consoleErrors.push(`pageerror ${e.message}`));
  p.on("dialog", (d) => d.accept());
  return { c, p };
}
async function login(p, user, portal, password = PW) {
  if (!p.url().includes("/login")) await p.goto(BASE + "/login");
  await p.getByRole("radio", { name: portal === "admin" ? /Admin Console/ : /Builder Studio/ }).check();
  await p.getByLabel("Tên đăng nhập").fill(user); await p.getByLabel("Mật khẩu").fill(password);
  await p.getByRole("button", { name: "Đăng nhập", exact: true }).click();
}
const frameOf = async (p) => (await p.waitForSelector("iframe.previewFrame", { state: "attached" })).contentFrame();
async function frameHas(p, text, present = true) { for (let i = 0; i < 40; i++) { const t = await (await frameOf(p)).evaluate(() => document.body.innerText); if (t.includes(text) === present) return; await p.waitForTimeout(250); } throw new Error(`preview ${present ? "missing" : "still has"} "${text}"`); }
async function apiCall(c, method, path, data) {
  const t = (await (await c.request.get(`${BASE}/api/v1/auth/csrf`)).json()).token;
  return c.request.fetch(`${BASE}/api/v1${path}`, { method, headers: { "X-XSRF-TOKEN": t, "Content-Type": "application/json" }, data });
}

// ---------------------------------------------------------------- authentication & portals
const admin = await ctx(); const editor = await ctx();
await check("employee choosing Builder lands in /studio", async () => { await login(editor.p, "local.editor", "builder"); await editor.p.waitForURL(/\/studio$/); });
await check("employee choosing Admin is denied (/auth/no-access) and can go to the Studio", async () => {
  const t = await ctx(); await login(t.p, "local.editor", "admin"); await t.p.waitForURL(/\/auth\/no-access/);
  await t.p.getByText("Bạn không có quyền truy cập Admin Console.").waitFor(); await t.p.getByRole("link", { name: "Vào Builder Studio" }).click(); await t.p.waitForURL(/\/studio$/); await t.c.close();
});
await check("direct /admin as employee: screen denied and admin API returns 403", async () => {
  await editor.p.goto(BASE + "/admin/users"); await editor.p.waitForURL(/\/auth\/no-access/);
  const r = await editor.c.request.get(`${BASE}/api/v1/admin/overview`); expect(r.status() === 403, `admin API status ${r.status()}`);
});
await check("system admin choosing Admin lands in /admin with real overview data", async () => {
  await login(admin.p, "local.admin", "admin"); await admin.p.waitForURL(/\/admin$/); await admin.p.getByRole("heading", { name: "Tổng quan" }).waitFor();
  await admin.p.getByText("Người dùng", { exact: true }).waitFor();
});
await check("system admin choosing Builder lands in /studio", async () => { const t = await ctx(); await login(t.p, "local.admin", "builder"); await t.p.waitForURL(/\/studio$/); await t.c.close(); });
await check("portal choice is not authority: forging the remembered portal does not open the console", async () => {
  const t = await ctx(); await t.p.goto(BASE + "/login"); await t.p.evaluate(() => sessionStorage.setItem("factory-portal", "admin"));
  await login(t.p, "local.viewer", "builder"); await t.p.waitForURL(/\/studio/); await t.p.goto(BASE + "/"); await t.p.waitForURL(/\/studio/);
  await t.p.goto(BASE + "/admin"); await t.p.waitForURL(/no-access/); await t.c.close();
});

// ---------------------------------------------------------------- project lifecycle in the Studio
let PID = "";
const NAME = `Factory E2E ${Date.now().toString(36)}`;
await check("create a Website from /studio/new (every app type is listed; unavailable ones say why)", async () => {
  await admin.p.goto(BASE + "/studio/new"); await admin.p.getByRole("radio", { name: /Ứng dụng có máy chủ/ }).waitFor();
  expect(await admin.p.getByRole("radiogroup", { name: "Loại ứng dụng" }).getByRole("radio").count() === 6, "six app types expected");
  await admin.p.getByLabel("Tên ứng dụng").fill(NAME); await admin.p.getByRole("button", { name: "Tạo website" }).click();
  await admin.p.waitForURL(/\/studio\/projects\/[0-9a-f-]{36}\/ai/); PID = admin.p.url().match(/projects\/([0-9a-f-]{36})/)[1];
  expect(sql(`select name from projects where id='${PID}'`) === NAME, "project not stored");
});
await check("AI prompt (simulator) updates the page; response shows real metadata (model, version, changed components)", async () => {
  await admin.p.getByLabel("Mô tả thay đổi").fill("Thêm bảng so sánh 3 sản phẩm");
  const [r] = await Promise.all([admin.p.waitForResponse((x) => x.url().endsWith("/prompts") && x.request().method() === "POST"), admin.p.getByRole("button", { name: /Gửi/ }).click()]);
  expect(r.status() === 200, `prompt ${r.status()}`); await frameHas(admin.p, "So sánh sản phẩm");
  await admin.p.getByText(/Đã đổi: Bảng so sánh/).waitFor(); await admin.p.getByText("Phiên bản 2", { exact: true }).waitFor();
  // the simulator calls no model: no tokens are shown or invented
  expect((await r.json()).usage == null, "simulator reported usage"); await admin.p.getByText("không tính token").last().waitFor();
});
await check("deep link refresh stays inside the project and mode", async () => {
  await admin.p.goto(`${BASE}/studio/projects/${PID}/design`); await admin.p.reload();
  await admin.p.getByRole("heading", { name: "Cấu trúc trang" }).waitFor(); expect(admin.p.url().endsWith(`/studio/projects/${PID}/design`), admin.p.url());
});
await check("preview sandbox: AI mode has no scripts, Design mode only allow-scripts (never allow-same-origin)", async () => {
  const d = await admin.p.locator("iframe.previewFrame").getAttribute("sandbox"); expect(d === "allow-scripts", `design sandbox=${d}`);
  await admin.p.goto(`${BASE}/studio/projects/${PID}/ai`); const a = await admin.p.locator("iframe.previewFrame").getAttribute("sandbox"); expect(a === "", `ai sandbox=${a}`);
});
await check("Design: clicking a section in the preview selects it; inspector edit is saved as a version and survives reload", async () => {
  await admin.p.goto(`${BASE}/studio/projects/${PID}/design`);
  const insp = admin.p.getByRole("region", { name: /Chỉnh sửa Đầu trang/ });
  // the preview document is re-rendered when the asset list arrives; click again until the selection registers
  for (let i = 0; i < 5 && !(await insp.isVisible()); i++) { await admin.p.waitForTimeout(600); await (await frameOf(admin.p)).click("section.hero").catch(() => undefined); }
  await insp.waitFor({ timeout: 5000 });
  await insp.getByLabel("Tiêu đề", { exact: true }).fill("Tiêu đề từ Design mode"); await insp.getByRole("button", { name: "Lưu thay đổi" }).click();
  await frameHas(admin.p, "Tiêu đề từ Design mode"); await admin.p.reload(); await frameHas(admin.p, "Tiêu đề từ Design mode");
  expect(sql(`select count(*) from project_versions where project_id='${PID}' and kind='EDIT'`) !== "0", "no EDIT version");
});
await check("Design: add a component from the library and reorder with the keyboard (dnd)", async () => {
  const before = Number(sql(`select jsonb_array_length(schema->'sections') from page_schemas where project_id='${PID}'`));
  await admin.p.getByRole("button", { name: /\+ Công nghệ/ }).click(); await admin.p.waitForTimeout(800);
  const after = Number(sql(`select jsonb_array_length(schema->'sections') from page_schemas where project_id='${PID}'`)); expect(after === before + 1, `sections ${before}->${after}`);
  const firstBefore = sql(`select schema->'sections'->0->>'type' from page_schemas where project_id='${PID}'`);
  const handle = admin.p.getByRole("button", { name: /^Kéo để di chuyển/ }).first(); await handle.focus();
  await admin.p.keyboard.press("Space"); await admin.p.waitForTimeout(300); await admin.p.keyboard.press("ArrowDown"); await admin.p.waitForTimeout(400); await admin.p.keyboard.press("Space"); await admin.p.waitForTimeout(1500);
  const firstAfter = sql(`select schema->'sections'->0->>'type' from page_schemas where project_id='${PID}'`); expect(firstAfter !== firstBefore, `order unchanged (${firstBefore})`);
});
await check("Assets: upload an image, use it as the Hero image (asset://), preview shows it", async () => {
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"); writeFileSync(OUT + "hero.png", png);
  await admin.p.goto(`${BASE}/studio/projects/${PID}/assets`); await admin.p.locator("input[type=file]").setInputFiles(OUT + "hero.png");
  await admin.p.getByText("hero.png").first().waitFor({ timeout: 20000 }); await admin.p.keyboard.press("Escape"); await admin.p.waitForURL(/\/design$/);
  await admin.p.getByRole("button", { name: /^Đầu trang \(Hero\)/ }).click();
  const insp = admin.p.getByRole("region", { name: /Chỉnh sửa Đầu trang/ }); await insp.getByLabel("Ảnh", { exact: true }).selectOption({ label: "hero.png" });
  await insp.getByRole("button", { name: "Lưu thay đổi" }).click(); await admin.p.waitForTimeout(1000);
  expect(/^asset:\/\//.test(sql(`select schema->'sections'->1->'props'->>'image' from page_schemas where project_id='${PID}'`) || sql(`select string_agg(s->'props'->>'image', '') from page_schemas, jsonb_array_elements(schema->'sections') s where project_id='${PID}'`)), "image ref not stored");
  const n = await (await frameOf(admin.p)).evaluate(() => document.querySelectorAll(".visual.photo img").length); expect(n === 1, "hero image not rendered");
});
await check("Versions: restore creates a NEW version and history is kept", async () => {
  const count = Number(sql(`select count(*) from project_versions where project_id='${PID}'`));
  await admin.p.goto(`${BASE}/studio/projects/${PID}/versions`); await admin.p.locator("article.versionItem", { hasText: "Phiên bản 1" }).getByRole("button", { name: "Khôi phục" }).click();
  await admin.p.getByText(/Đã khôi phục phiên bản 1/).waitFor({ timeout: 10000 });
  expect(Number(sql(`select count(*) from project_versions where project_id='${PID}'`)) === count + 1, "restore must add one version");
});
await check("Members: share with a workspace member as Viewer; Reviewer role is not offered", async () => {
  await admin.p.goto(`${BASE}/studio/projects/${PID}/members`);
  const roles = await admin.p.getByLabel("Vai trò khi thêm").first().locator("option").allInnerTexts(); expect(!roles.some((r) => /review/i.test(r)), roles.join(","));
  const dlg = admin.p.getByRole("dialog", { name: "Thành viên và quyền" });
  await dlg.getByLabel("Tên đăng nhập hoặc email để thêm vào: Thành viên project").fill("local.viewer");
  await dlg.getByLabel("Vai trò khi thêm").first().selectOption("VIEWER"); await dlg.getByRole("button", { name: "Thêm", exact: true }).first().click();
  await dlg.locator("section").first().getByRole("cell", { name: /local\.viewer/ }).first().waitFor({ timeout: 10000 });
  expect(sql(`select m.role from project_members m join users u on u.id=m.user_id where m.project_id='${PID}' and u.username='local.viewer'`) === "VIEWER", "viewer not added in DB");
  await admin.p.keyboard.press("Escape");
});
const SITES = process.env.E2E_SITES ?? "http://127.0.0.1:18088"; let SITE_URL = "";
const heroTitle = () => sql(`select s->'props'->>'title' from page_schemas, jsonb_array_elements(schema->'sections') s where project_id='${PID}' and s->>'type'='Hero' limit 1`);
async function publishVia(p, label) {
  await p.goto(`${BASE}/studio/projects/${PID}/publish`); const dlg = p.getByRole("dialog", { name: "Xuất bản website" }); await dlg.waitFor();
  await dlg.getByRole("button", { name: label }).click(); await dlg.getByRole("button", { name: "Xuất bản", exact: true }).click();
  await dlg.getByText("Đang chạy", { exact: true }).waitFor({ timeout: 30000 });
  return dlg.getByRole("link", { name: /^http/ }).getAttribute("href");
}
await check("Publish: a real static site is built and served by the sites gateway (preview markup, strict headers, no scripts)", async () => {
  SITE_URL = await publishVia(admin.p, /Công khai/);
  expect(SITE_URL.startsWith(SITES + "/"), `site url ${SITE_URL}`);
  const r = await fetch(SITE_URL); const html = await r.text();
  expect(r.status === 200, `site ${r.status}`); expect(html.includes(heroTitle()), "published page does not show the current hero title");
  expect(!/<script/i.test(html), "published page contains a script"); expect(!r.headers.get("set-cookie"), "visitor got a cookie");
  expect((r.headers.get("content-security-policy") ?? "").includes("default-src 'none'"), "missing CSP"); expect(r.headers.get("x-content-type-options") === "nosniff", "missing nosniff");
  expect((await fetch(SITES + "/api/v1/auth/config")).status === 404, "gateway must not expose the API");
  expect(sql(`select provider from deployments where project_id='${PID}' order by created_at desc limit 1`) === "static", "provider not static");
});
await check("Private site: visitors are sent to sign in, a member gets in with a single-use ticket, a non-member is refused; rollback and unpublish", async () => {
  expect(await publishVia(admin.p, /Riêng tư/) === SITE_URL, "slug must stay the same");
  const anon = await fetch(SITE_URL, { redirect: "manual" });
  expect(anon.status === 302 && (anon.headers.get("location") ?? "").includes("/studio/site-access?site="), `anonymous got ${anon.status}`);
  await admin.p.goto(SITE_URL); await admin.p.waitForURL(SITE_URL, { timeout: 15000 }); await admin.p.getByText(heroTitle()).first().waitFor();
  await editor.p.goto(SITE_URL); await editor.p.getByText("Không mở được trang").waitFor({ timeout: 15000 });
  // serve the earlier public deployment again (no rebuild), then take the site offline
  await admin.p.goto(`${BASE}/studio/projects/${PID}/publish`); const dlg = admin.p.getByRole("dialog", { name: "Xuất bản website" });
  await dlg.getByText(/Các lần xuất bản/).click(); await dlg.locator("li", { hasText: "công khai" }).getByRole("button", { name: "Phục vụ lại bản này" }).first().click();
  for (let i = 0; i < 20 && (await fetch(SITE_URL, { redirect: "manual" })).status !== 200; i++) await admin.p.waitForTimeout(300);
  expect((await fetch(SITE_URL, { redirect: "manual" })).status === 200, "rollback to the public deployment did not take effect");
  await dlg.getByRole("button", { name: "Gỡ trang xuống" }).click();
  for (let i = 0; i < 20 && (await fetch(SITE_URL + "?v=" + i)).status !== 404; i++) await admin.p.waitForTimeout(300);
  expect((await fetch(SITE_URL + "?after-unpublish")).status === 404, "unpublished site still served");
  expect(sql(`select count(*) from audit_events where project_id='${PID}' and action in ('SITE_ROLLBACK','SITE_UNPUBLISHED')`) === "2", "site actions not audited");
});
// ---------------------------------------------------------------- Phase 5: templates & contributed blocks
const TNAME = `Mẫu E2E ${Date.now().toString(36)}`, BNAME = `Khối E2E ${Date.now().toString(36)}`; let EPID = "";
await check("Templates: save the page as a template, an admin shares it company-wide, another employee starts a website from it", async () => {
  await admin.p.goto(`${BASE}/studio/projects/${PID}/settings`);
  const sec = admin.p.getByRole("region", { name: "Lưu trang thành mẫu" });
  await sec.getByLabel("Tên mẫu").fill(TNAME); await sec.getByRole("button", { name: "Lưu thành mẫu" }).click(); await sec.getByText(/Đã lưu mẫu/).waitFor();
  expect(sql(`select visibility from templates where name='${TNAME}'`) === "PRIVATE", "template not stored as PRIVATE");
  expect(sql(`select count(*) from templates where name='${TNAME}' and schema::text like '%asset://%'`) === "0", "template kept an image reference");
  await admin.p.goto(`${BASE}/admin/templates`); await admin.p.getByLabel("Tìm mẫu").fill(TNAME); await admin.p.getByRole("button", { name: "Lọc" }).click();
  const row = admin.p.locator("tr", { hasText: TNAME }); await row.getByRole("button", { name: "Chia sẻ toàn công ty" }).click(); await row.getByRole("button", { name: "Thu hồi về riêng tư" }).waitFor();
  await editor.p.goto(`${BASE}/studio/templates`); const card = editor.p.locator("article.libCard", { hasText: TNAME }); await card.getByRole("button", { name: "Dùng mẫu này" }).click();
  await editor.p.waitForURL(/\/studio\/new\?template=/); expect(await editor.p.getByRole("radio", { name: new RegExp(TNAME) }).isChecked(), "template not preselected");
  await editor.p.getByLabel("Tên ứng dụng").fill(`Từ ${TNAME}`); await editor.p.getByRole("button", { name: "Tạo website" }).click();
  await editor.p.waitForURL(/\/studio\/projects\/[0-9a-f-]{36}\/ai/); EPID = editor.p.url().match(/projects\/([0-9a-f-]{36})/)[1];
  expect(sql(`select summary from project_versions where project_id='${EPID}' and version_number=1`).includes(TNAME), "first version does not name the template");
  expect(sql(`select jsonb_array_length(schema->'sections') from page_schemas where project_id='${EPID}'`) === sql(`select jsonb_array_length(schema->'sections') from templates where name='${TNAME}'`), "page differs from template");
});
await check("Blocks: an employee saves a section as a block and submits it; automated checks pass; an admin approves; it inserts as an ordinary section", async () => {
  await editor.p.goto(`${BASE}/studio/projects/${EPID}/design`);
  const insp = editor.p.getByRole("region", { name: /Chỉnh sửa Đầu trang/ });
  for (let i = 0; i < 6 && !(await insp.isVisible()); i++) { await editor.p.waitForTimeout(600); await (await frameOf(editor.p)).click("section.hero").catch(() => undefined); }
  await editor.p.getByRole("button", { name: "Lưu thành khối…" }).click();
  const dlg = editor.p.getByRole("dialog", { name: "Lưu thành khối" }); await dlg.getByLabel("Tên khối").fill(BNAME); await dlg.getByRole("button", { name: "Lưu khối" }).click();
  await editor.p.getByText(/Đã lưu khối/).waitFor();
  await editor.p.goto(`${BASE}/studio/components`); const mine = editor.p.locator("article.libCard", { hasText: BNAME });
  await mine.getByRole("button", { name: "Gửi duyệt" }).click(); await mine.getByText("Chờ duyệt").waitFor();
  expect(sql(`select status from component_packages where name='${BNAME}'`) === "REVIEW", "not in review");
  await admin.p.goto(`${BASE}/admin/components`); await admin.p.getByRole("tab", { name: "Khối đóng góp" }).click();
  await admin.p.locator("tr", { hasText: BNAME }).getByRole("button", { name: "Xem xét" }).click();
  await admin.p.getByText("Thuộc tính hợp lệ", { exact: true }).waitFor(); await admin.p.getByRole("button", { name: "Phê duyệt v1" }).click();
  for (let i = 0; i < 20 && sql(`select status from component_packages where name='${BNAME}'`) !== "APPROVED"; i++) await admin.p.waitForTimeout(250);
  expect(sql(`select status from component_packages where name='${BNAME}'`) === "APPROVED", "not approved");
  const before = Number(sql(`select jsonb_array_length(schema->'sections') from page_schemas where project_id='${PID}'`));
  await admin.p.goto(`${BASE}/studio/projects/${PID}/design`); await admin.p.getByRole("button", { name: new RegExp(`^\\+ ${BNAME}`) }).click(); await admin.p.waitForTimeout(1000);
  expect(Number(sql(`select jsonb_array_length(schema->'sections') from page_schemas where project_id='${PID}'`)) === before + 1, "block not inserted");
  expect(sql(`select count(*) from page_schemas, jsonb_array_elements(schema->'sections') s where project_id='${PID}' and s->>'type'='Hero'`) === "2", "inserted section is not an ordinary Hero");
});

// ---------------------------------------------------------------- RBAC / security
const viewer = await ctx();
await check("viewer: Design is read-only (no library, no drag handles, preview without scripts)", async () => {
  await login(viewer.p, "local.viewer", "builder"); await viewer.p.waitForURL(/\/studio/);
  await viewer.p.goto(`${BASE}/studio/projects/${PID}/design`); await viewer.p.getByText("Bạn chỉ có quyền xem.").waitFor();
  expect(await viewer.p.getByRole("button", { name: /^Kéo để di chuyển/ }).count() === 0, "drag handles visible");
  expect(await viewer.p.getByRole("heading", { name: "Thư viện component" }).count() === 0, "library visible");
  expect((await viewer.p.locator("iframe.previewFrame").getAttribute("sandbox")) === "", "viewer preview must not run scripts");
  const r = await apiCall(viewer.c, "PATCH", `/workspaces/00000000-0000-0000-0000-000000000001/projects/${PID}/schema`, { expectedRevision: 0, operations: [{ type: "REMOVE_SECTION", sectionId: "footer-1" }] });
  expect(r.status() === 403, `viewer PATCH ${r.status()}`);
});
await check("foreign project: a non-member gets 'not found', API 404", async () => {
  const pub = await ctx(); await login(pub.p, "local.publisher", "builder"); await pub.p.waitForURL(/\/studio/);
  await pub.p.goto(`${BASE}/studio/projects/${PID}`); await pub.p.getByText("Không tìm thấy").waitFor({ timeout: 10000 });
  expect((await pub.c.request.get(`${BASE}/api/v1/projects/${PID}`)).status() === 404, "lookup must be 404"); await pub.c.close();
});
await check("CSRF: a state-changing call without the token is refused (403)", async () => {
  const r = await admin.c.request.post(`${BASE}/api/v1/workspaces/00000000-0000-0000-0000-000000000001/projects`, { headers: { "Content-Type": "application/json" }, data: { name: "x" } });
  expect(r.status() === 403, `status ${r.status()}`);
});
await check("revision conflict from a second tab is reported and server state reloaded", async () => {
  const a = await ctx(), b = await ctx();
  await login(a.p, "local.admin", "builder"); await a.p.waitForURL(/studio/); await login(b.p, "local.admin", "builder"); await b.p.waitForURL(/studio/);
  await a.p.goto(`${BASE}/studio/projects/${PID}/ai`); await b.p.goto(`${BASE}/studio/projects/${PID}/ai`); await a.p.getByLabel("Mô tả thay đổi").waitFor(); await b.p.getByLabel("Mô tả thay đổi").waitFor();
  await a.p.getByLabel("Mô tả thay đổi").fill("thêm sản phẩm"); await a.p.getByRole("button", { name: /Gửi/ }).click(); await a.p.getByText(/Đã thêm sản phẩm/).waitFor({ timeout: 10000 });
  await b.p.getByLabel("Mô tả thay đổi").fill("rút gọn hero"); await b.p.getByRole("button", { name: /Gửi/ }).click(); await b.p.getByText(/vừa được thay đổi ở nơi khác/).waitFor({ timeout: 10000 });
  await a.c.close(); await b.c.close();
});

// ---------------------------------------------------------------- admin console
await check("admin users: search, disable (sessions revoked, login blocked), enable again", async () => {
  const pub = await ctx(); await login(pub.p, "local.publisher", "builder"); await pub.p.waitForURL(/studio/);
  await admin.p.goto(BASE + "/admin/users"); await admin.p.getByLabel("Tìm người dùng").fill("local.publisher"); await admin.p.getByRole("button", { name: "Tìm" }).click();
  await admin.p.getByRole("link", { name: "Publisher" }).click(); await admin.p.getByRole("button", { name: "Khóa tài khoản" }).click();
  await admin.p.getByText("Đã khóa tài khoản và thu hồi phiên.").waitFor();
  expect((await pub.c.request.get(`${BASE}/api/v1/auth/me`)).status() === 401, "publisher session must be revoked");
  const t = await ctx(); await login(t.p, "local.publisher", "builder"); await t.p.getByText("Sai tên đăng nhập hoặc mật khẩu.").waitFor(); await t.c.close();
  await admin.p.getByRole("button", { name: "Mở khóa" }).click(); await admin.p.getByText("Đã mở khóa tài khoản.").waitFor(); await pub.c.close();
});
await check("expired session: user is sent to sign in and comes back to the same project route", async () => {
  await editor.p.goto(`${BASE}/studio/projects/${DEMO}/design`); await editor.p.getByRole("heading", { name: "Cấu trúc trang" }).waitFor();
  const uid = sql("select id from users where username='local.editor'");
  expect((await apiCall(admin.c, "POST", `/admin/users/${uid}/revoke-sessions`)).status() === 200, "revoke failed");
  await editor.p.reload(); await editor.p.waitForURL(/\/login\?next=/);
  await login(editor.p, "local.editor", "builder"); await editor.p.waitForURL(new RegExp(`/studio/projects/${DEMO}/design$`));
});
await check("inventory lists the new app; detail shows versions, AI activity and the real deployment history", async () => {
  await admin.p.goto(BASE + "/admin/applications"); await admin.p.getByLabel("Tìm ứng dụng").fill(NAME); await admin.p.getByRole("button", { name: "Tìm" }).click();
  await admin.p.getByRole("link", { name: NAME }).click(); await admin.p.getByRole("tab", { name: "Xuất bản" }).click(); await admin.p.getByText("Trang tĩnh (thật)").first().waitFor();
  await admin.p.getByRole("tab", { name: "Hoạt động AI" }).click(); await admin.p.getByText("Thêm bảng so sánh 3 sản phẩm").waitFor();
});
await check("audit log filters by action and shows request ids", async () => {
  await admin.p.goto(BASE + "/admin/audit"); await admin.p.getByLabel("Hành động").selectOption("USER_DISABLED"); await admin.p.getByRole("button", { name: "Lọc" }).click();
  await admin.p.locator(".table").getByText("Vô hiệu hóa người dùng").first().waitFor();
});
await check("AI usage: report and call log come from ai_calls (provider-reported tokens; unknown is not zero)", async () => {
  // no network model in this run: two call rows are written directly, as the server records them after an upstream call
  const uid = sql(`select id from users where username='local.admin'`), ws = sql(`select workspace_id from projects where id='${PID}'`);
  sql(`insert into ai_calls (id, prompt_id, workspace_id, project_id, user_id, provider, model, outcome, http_status, prompt_tokens, completion_tokens, total_tokens, cost_usd, latency_ms)
       values (gen_random_uuid(), null, '${ws}', '${PID}', '${uid}', 'openrouter', 'e2e/stub:free', 'OK', 200, 1000, 234, 1234, 0, 900),
              (gen_random_uuid(), null, '${ws}', '${PID}', '${uid}', 'openrouter', 'e2e/stub:free', 'ERROR', 429, null, null, null, null, 300)`);
  try {
    await admin.p.goto(BASE + "/admin/ai/usage"); await admin.p.getByRole("heading", { name: "Mức sử dụng model" }).waitFor();
    await admin.p.getByRole("button", { name: "Hôm nay" }).click();
    const byModel = admin.p.locator(".card", { has: admin.p.getByRole("heading", { name: "Theo model", exact: true }) });
    await byModel.getByText("e2e/stub:free").first().waitFor(); await byModel.getByText("1 lượt không có số liệu").first().waitFor();
    const log = admin.p.locator(".card", { has: admin.p.getByRole("heading", { name: "Nhật ký lượt gọi model" }) });
    await log.getByLabel("Model").selectOption("e2e/stub:free");
    await log.getByText("HTTP 429").first().waitFor(); await log.getByText("không báo").first().waitFor(); await log.getByText("1.000 / 234").first().waitFor();
    await admin.p.goto(BASE + "/studio"); await admin.p.getByText(/Token AI 24 giờ qua/).waitFor(); await admin.p.getByText(/30 ngày: .*token/).waitFor();
  } finally { sql(`delete from ai_calls where model='e2e/stub:free'`); }
});
await check("component registry shows usage; platform health shows real probes", async () => {
  await admin.p.goto(BASE + "/admin/components"); await admin.p.getByText("Hero", { exact: true }).first().waitFor();
  await admin.p.goto(BASE + "/admin/system"); for (const n of ["PostgreSQL", "Redis", "RabbitMQ", "MinIO"]) await admin.p.locator(".healthCard", { hasText: n }).getByText("Khỏe").waitFor();
});

// ---------------------------------------------------------------- layout widths & console
for (const width of [1280, 1440, 1920, 2560]) {
  await check(`layout ${width}px: admin and studio design without horizontal page scroll`, async () => {
    const t = await ctx(width); await login(t.p, "local.admin", "admin"); await t.p.waitForURL(/admin/);
    for (const path of ["/admin/applications", `/studio/projects/${PID}/design`, "/studio"]) {
      await t.p.goto(BASE + path); await t.p.waitForTimeout(700);
      const m = await t.p.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
      expect(m.sw <= m.cw, `${path} overflow ${m.sw}>${m.cw}`);
      await t.p.screenshot({ path: `${OUT}${width}-${path.replace(/[^a-z]/gi, "_")}.png` });
    }
    await t.c.close();
  });
}
await check("no unexpected console errors", async () => {
  const bad = consoleErrors.filter((e) => !/401|403|404|409|Failed to load resource/.test(e)); expect(bad.length === 0, bad.slice(0, 3).join(" | "));
});
await browser.close();
writeFileSync(OUT + "results.json", JSON.stringify({ at: new Date().toISOString(), results, consoleErrors }, null, 2));
console.log(`\n${results.filter((r) => r.ok).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
