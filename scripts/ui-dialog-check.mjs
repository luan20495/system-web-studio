#!/usr/bin/env node
// Real-browser, real-backend check of the "Tạo công ty" dialog (Platform) and the Admin organization / employee screens at 1440 / 1024 / 768 / 430 / 390:
// overflow, dialog fit, reachable buttons, focus trap, Escape + focus restore, the person picker by keyboard, error association, Vietnamese strings, axe. A developer tool (NOT a test); nothing is mocked.
//   node scripts/ui-dialog-check.mjs --out <dir>      env: AUDIT_STACK_ENV, AUDIT_STUDIO, AUDIT_PLATFORM, AUDIT_ADMIN, CHROME
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
const require = createRequire(new URL("../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core"); const AXE = require.resolve("axe-core/axe.min.js");
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-dialog-check"); mkdirSync(OUT, { recursive: true });
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const U = { studio: process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086", platform: process.env.AUDIT_PLATFORM ?? "http://127.0.0.1:3001", admin: process.env.AUDIT_ADMIN ?? "http://127.0.0.1:3002" };
const results = []; const check = (n, ok, d = "") => { results.push({ name: n, ok: !!ok, detail: d }); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${d ? "  — " + d : ""}`); };
const run = Date.now().toString(36);
const must = (r, w) => { if (r.status < 200 || r.status >= 300) throw new Error(`${w}: ${r.status} ${r.body?.code ?? ""}`); return r.body; };
const sys = new Session(U.studio, "sys"); await sys.login("local.admin", ENV.LOCAL_ADMIN_PASSWORD);
const tenant = must(await sys.post("/admin/tenants", { slug: `dlg-${run}`, name: "Công ty Cổ phần Ánh Dương" }), "tenant");
const mk = async (key, name, role) => { const l = must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `dlg-${run}-${key}`, displayName: name, tenantRole: role }), key); const pw = randomSecret(); must(await new Session(U.studio, "a").post("/auth/activation/complete", { token: l.token, password: pw }), `act ${key}`); return { username: `dlg-${run}-${key}`, password: pw }; };
const ta = await mk("ta", "Nguyễn Thị Quản Trị Viên Đầu Tiên Của Công Ty", "TENANT_ADMIN");
for (let i = 0; i < 24; i++) must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `dlg-${run}-e${i}`, displayName: i === 3 ? "Nguyễn Hoàng Thiên Phúc Bảo Long Quang Vinh Hiển Đạt Thịnh Khang An Phú (tên rất dài)" : `Nhân viên Đặng ${i}`, tenantRole: "MEMBER", email: `e${i}@${run}.anh-duong.example.vn` }), `e${i}`);
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); };
const login = async (p, portal, u, pw) => { await p.goto(`${U[portal]}/${portal}/login`, { waitUntil: "domcontentloaded" }); await p.getByLabel("Tên đăng nhập").fill(u); await p.getByLabel("Mật khẩu").fill(pw); await p.getByRole("button", { name: "Đăng nhập" }).click(); await p.waitForURL((x) => !/\/login$/.test(x.pathname), { timeout: 20000 }); await p.waitForLoadState("networkidle").catch(() => undefined); };
const overflow = (p) => p.evaluate(() => document.documentElement.scrollWidth - innerWidth);
const inDialog = (p) => p.evaluate(() => !!document.activeElement?.closest("[role=dialog]"));

for (const w of [1440, 1024, 768, 430, 390]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: w >= 1000 ? 900 : 800 } }); const p = await ctx.newPage(); p.setDefaultTimeout(15000);
  await login(p, "platform", "local.admin", ENV.LOCAL_ADMIN_PASSWORD); await p.goto(`${U.platform}/platform/tenants`, { waitUntil: "domcontentloaded" }); await p.waitForLoadState("networkidle").catch(() => undefined);
  const opener = p.getByRole("button", { name: /Tạo công ty/ }).first(); await opener.click(); await p.getByTestId("tenant-create").waitFor();
  const box = await p.evaluate(() => { const b = document.querySelector("[data-testid=tenant-create]").getBoundingClientRect(); const s = [...document.querySelectorAll("[data-testid=tenant-create] button.primary")].pop().getBoundingClientRect(); return { l: b.left, r: b.right, t: b.top, b: b.bottom, vw: innerWidth, vh: innerHeight, subTop: s.top, subBottom: s.bottom }; });
  check(`CC-${w} create company: the dialog fits the width, the page behind has no horizontal scroll`, box.l >= -1 && box.r <= box.vw + 1 && (await overflow(p)) <= 1, JSON.stringify(box));
  await p.getByTestId("tenant-create").evaluate((e) => e.scrollTo?.(0, 99999)); await p.waitForTimeout(100);
  const sub = await p.evaluate(() => { const s = [...document.querySelectorAll("[data-testid=tenant-create] button.primary")].pop().getBoundingClientRect(); return { top: s.top, bottom: s.bottom, vh: innerHeight }; });
  check(`CC-${w} …the submit button is reachable (visible after scrolling the dialog)`, sub.top >= 0 && sub.bottom <= sub.vh + 1, JSON.stringify(sub));
  await p.getByTestId("tenant-create").evaluate((e) => e.scrollTo?.(0, 0)); await p.screenshot({ path: join(OUT, `create-company-${w}.png`) });
  if (w === 1440) {
    await p.getByTestId("tenant-name").fill("Công ty Cổ phần Ánh Dương");
    check("CC-unicode the Vietnamese name is typed, shown and turned into a code without losing letters", (await p.getByTestId("tenant-name").inputValue()) === "Công ty Cổ phần Ánh Dương" && (await p.getByTestId("tenant-slug").inputValue()) === "cong-ty-co-phan-anh-duong");
    const text = (await p.getByTestId("tenant-create").innerText()).toLowerCase();
    check("CC-strings the dialog's Vietnamese strings render intact (title, sections, hints)", ["Tạo công ty", "Thông tin công ty", "Quản trị viên đầu tiên", "Không bắt buộc", "Hủy"].every((t) => text.includes(t.toLowerCase())) && !/�|â€|Ã/.test(text));
    let escaped = false; for (let i = 0; i < 16; i++) { await p.keyboard.press("Tab"); if (!(await inDialog(p))) { escaped = true; break; } } check("CC-focus-trap 16 × Tab never leaves the dialog", !escaped);
    await p.getByTestId("tenant-name").fill(" "); await p.getByTestId("tenant-slug").fill("A"); await p.getByRole("button", { name: "Tạo công ty" }).last().click(); await p.waitForTimeout(150);
    const d = await p.getByTestId("tenant-name").getAttribute("aria-describedby"); const err = d ? await p.evaluate((i) => document.getElementById(i)?.textContent ?? "", d) : "";
    check("CC-errors an invalid name / code is aria-invalid and linked (aria-describedby) to its error text", (await p.getByTestId("tenant-name").getAttribute("aria-invalid")) === "true" && /Hãy nhập tên công ty/.test(err));
    const person = p.getByRole("combobox", { name: "Tìm người dùng" }); await person.fill("dlg-" + run + "-e1"); await p.waitForTimeout(900);
    const n = await p.locator("#tenant-first-admin [role=option]").count(); await person.press("ArrowDown"); await person.press("Enter");
    check("CC-picker search finds people, ArrowDown + Enter chooses one, the chosen card appears", n >= 1 && (await p.getByTestId("person-chosen").count()) === 1, `options=${n}`);
    await p.getByRole("button", { name: /Bỏ chọn/ }).click(); check("CC-picker 'Bỏ chọn' returns to the search box", (await p.getByRole("combobox", { name: "Tìm người dùng" }).count()) === 1);
    const v = await axe(p, "[data-testid=tenant-create]"); check("CC-axe no serious / critical violation in the dialog", v.length === 0, v.join(","));
    await p.keyboard.press("Escape"); await p.waitForTimeout(150);
    check("CC-escape Escape closes the dialog and focus returns to the 'Tạo công ty' button that opened it", (await p.getByTestId("tenant-create").count()) === 0 && /Tạo công ty/.test(await p.evaluate(() => document.activeElement?.textContent ?? "")));
  }
  await ctx.close();
  // admin screens as the tenant admin
  const c2 = await browser.newContext({ viewport: { width: w, height: w >= 1000 ? 900 : 800 } }); const q = await c2.newPage(); q.setDefaultTimeout(15000);
  await login(q, "admin", ta.username, ta.password);
  for (const [route, label] of [["/admin/organization", "organization"], ["/admin/employees", "employees"]]) {
    await q.goto(`${U.admin}${route}`, { waitUntil: "domcontentloaded" }); await q.waitForLoadState("networkidle").catch(() => undefined); await q.waitForTimeout(500);
    check(`ADMIN-${label}-${w} no horizontal overflow of the page`, (await overflow(q)) <= 1, `overflow=${await overflow(q)}`);
    if (w <= 900) {
      const toggle = q.getByRole("button", { name: /menu điều hướng/i }); check(`ADMIN-${label}-${w} the menu button is shown at this width`, (await toggle.count()) === 1 && (await toggle.isVisible()));
      if (label === "organization") { await toggle.click(); await q.waitForTimeout(250); const open = await q.evaluate(() => { const s = document.querySelector("aside.sidebar").getBoundingClientRect(); return s.left >= -1 && s.width > 200; }); check(`ADMIN-nav-${w} the drawer opens with the navigation inside the viewport`, open); await q.keyboard.press("Escape"); await q.waitForTimeout(250); check(`ADMIN-nav-${w} Escape closes the drawer`, await q.evaluate(() => document.querySelector("aside.sidebar").getBoundingClientRect().right <= 1)); }
    }
    await q.screenshot({ path: join(OUT, `admin-${label}-${w}.png`), fullPage: w <= 430 });
    if (w === 1440) { const v = await axe(q); check(`ADMIN-${label}-axe no serious / critical violation`, v.length === 0, v.join(",")); }
  }
  await c2.close();
}
await browser.close();
writeFileSync(join(OUT, "results.json"), JSON.stringify(results, null, 1));
const failed = results.filter((r) => !r.ok); console.log(`\n${results.length - failed.length}/${results.length} checks passed`); process.exit(failed.length ? 1 : 0);
