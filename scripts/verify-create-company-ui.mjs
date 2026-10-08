#!/usr/bin/env node
// @class: real-backend
// Visual / behavioural check of the redesigned "Platform -> Công ty -> Tạo công ty" dialog in a REAL Chrome with an EMPTY profile (no build or browser cache reused).
// It opens the dialog and never submits it: no company is created. Also fails on any /_next asset error, any failed API call (other than the anonymous 401 probes),
// any console / page error, and any request to localhost when the portal is a public one.
//   node scripts/verify-create-company-ui.mjs https://platform.toolsmcp.uk            (operator account of .run/public/public.env)
//   LOCAL=1 node scripts/verify-create-company-ui.mjs http://127.0.0.1:3301           (local.admin, password LOCAL_ADMIN_PASSWORD from .env)
import { readFileSync, mkdirSync } from "node:fs";
import { createRequire } from "node:module";

const ROOT = new URL("..", import.meta.url).pathname;
const BASE = (process.argv[2] ?? "https://platform.toolsmcp.uk").replace(/\/$/, "");
const kv = (f) => Object.fromEntries(readFileSync(f, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const [USER, PASS] = process.env.LOCAL === "1" ? ["local.admin", kv(`${ROOT}.env`).LOCAL_ADMIN_PASSWORD] : [kv(`${ROOT}.run/public/public.env`).BOOTSTRAP_ADMIN_USERNAME, kv(`${ROOT}.run/public/public.env`).BOOTSTRAP_ADMIN_PASSWORD];
const SHOTS = process.env.SHOTS ?? `${ROOT}.run/ui-verify`; mkdirSync(SHOTS, { recursive: true });
const { chromium } = createRequire(`${ROOT}package.json`)("playwright-core");
const results = []; const check = (n, ok, d = "") => { results.push([n, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${n}${d ? " | " + d : ""}`); return ok; };

const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: "block" }); const page = await ctx.newPage();
const bad = [], errors = [], reqs = [];
page.on("request", (r) => reqs.push(r.url()));
page.on("response", (r) => { const u = new URL(r.url()); if (r.status() >= 500 || (/\/_next\//.test(u.pathname) && r.status() >= 400) || (/\/api\//.test(u.pathname) && r.status() >= 400 && !(r.status() === 401 && /\/auth\/me$/.test(u.pathname)))) bad.push(`${r.status()} ${r.request().method()} ${u.pathname}`); });
page.on("console", (m) => { if (m.type() === "error" && !/status of 401/.test(m.text())) errors.push(m.text().slice(0, 160)); });
page.on("pageerror", (e) => errors.push("pageerror " + e.message.slice(0, 160)));

await page.goto(BASE + "/", { waitUntil: "networkidle" });
await page.fill('input:not([type="password"])', USER); await page.fill('input[type="password"]', PASS);
await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 20000 }), page.click("form button")]);
await page.waitForFunction(() => !document.body.innerText.includes("Đang tải"), null, { timeout: 20000 }).catch(() => {});
check("signed in on the Platform portal", /\/platform/.test(new URL(page.url()).pathname), new URL(page.url()).pathname);
await page.getByRole("link", { name: /Công ty \(tenant\)/ }).first().click();
await page.getByRole("button", { name: "+ Tạo công ty" }).waitFor({ timeout: 15000 });
await page.getByRole("button", { name: "+ Tạo công ty" }).click();
const dlg = page.getByTestId("tenant-create"); await dlg.waitFor({ timeout: 10000 });
await page.waitForTimeout(500);
check("the dialog is the NEW form (data-testid tenant-create)", await dlg.count() === 1);
check("header has an icon (svg) and the title 'Tạo công ty'", await dlg.locator("svg").first().count() === 1 && (await dlg.innerText()).includes("Tạo công ty"));
check("subtitle describes a company", /Mỗi công ty là một không gian riêng/.test(await dlg.innerText()));
check("section 'Thông tin công ty'", await dlg.locator('section[aria-label="Thông tin công ty"]').count() === 1);
check("section 'Quản trị viên đầu tiên' (optional tag)", await dlg.locator('section[aria-label="Quản trị viên đầu tiên"]').count() === 1 && /Không bắt buộc/.test(await dlg.innerText()));
// auto slug from the name (Vietnamese diacritics, đ)
await dlg.getByTestId("tenant-name").fill("Công ty Cổ phần Ánh Dương Đà Nẵng");
check("company code is generated from the name (no diacritics, đ -> d)", await dlg.getByTestId("tenant-slug").inputValue() === "cong-ty-co-phan-anh-duong-da-nang", await dlg.getByTestId("tenant-slug").inputValue());
check("a valid code shows the check mark", await dlg.locator('[aria-label="Mã hợp lệ"]').count() === 1);
await dlg.getByTestId("tenant-slug").fill("X!"); await dlg.getByTestId("tenant-slug").blur();
check("hand-editing the code stops the auto-generation (hint changes)", /Chữ thường, số và dấu/.test(await dlg.innerText()));
await dlg.getByTestId("tenant-name").fill("Công ty thử"); check("...and the edited code is kept", await dlg.getByTestId("tenant-slug").inputValue() === "X!");
// validation (no request must be sent)
let created = 0; page.on("request", (r) => { if (r.method() === "POST" && /\/api\/v1\/admin\/tenants$/.test(new URL(r.url()).pathname)) created++; });
await dlg.getByTestId("tenant-name").fill(" "); await dlg.getByRole("button", { name: "Tạo công ty" }).click(); await page.waitForTimeout(400);
check("submitting an invalid form shows clear errors (role=alert) and sends nothing", (await dlg.locator('[role="alert"]').count()) >= 1 && created === 0, `${await dlg.locator('[role="alert"]').count()} alert(s), POST tenants=${created}`);
await page.screenshot({ path: `${SHOTS}/create-company-validation.png` });
// person picker
const combo = dlg.getByRole("combobox"); check("the person picker is a searchable combobox + listbox", await combo.count() === 1 && await dlg.getByRole("listbox").count() === 1);
check("no multi-line <select> (old picker) anywhere in the dialog", await dlg.locator("select[size], select[multiple]").count() === 0 && await dlg.locator("select").count() === 0, `${await dlg.locator("select").count()} select`);
await page.waitForTimeout(800);
const n0 = await dlg.getByRole("option").count();
check("the list shows people with avatars (initials)", n0 >= 1 && await dlg.locator(".xp-avatar").count() >= 1, `${n0} option(s), ${await dlg.locator(".xp-avatar").count()} avatar(s)`);
await page.screenshot({ path: `${SHOTS}/create-company-people.png` });
await combo.click(); await combo.fill("zz-no-such-person-zz"); await page.waitForTimeout(900);
check("search with no match shows the empty text", /Không có người phù hợp/.test(await dlg.innerText()), `${await dlg.getByRole("option").count()} option(s)`);
await combo.fill(""); await page.waitForTimeout(900);
if (n0 >= 2) {
  const first = await dlg.locator(".xp-person.active").count();
  await combo.press("ArrowDown"); const idx = await dlg.locator("li.xp-person").evaluateAll((els) => els.findIndex((e) => e.classList.contains("active")));
  check("keyboard: ArrowDown moves the active row", first === 1 && idx === 1, `active index ${idx}`);
  await combo.press("ArrowUp"); const idx2 = await dlg.locator("li.xp-person").evaluateAll((els) => els.findIndex((e) => e.classList.contains("active"))); check("keyboard: ArrowUp moves it back", idx2 === 0, `${idx2}`);
} else check("keyboard navigation needs >= 2 people in the list", false, `only ${n0}`);
await combo.press("Enter"); await page.waitForTimeout(300);
check("keyboard: Enter chooses the person (chosen card with avatar and 'Bỏ chọn')", await dlg.getByTestId("person-chosen").count() === 1 && await dlg.getByTestId("person-chosen").locator(".xp-avatar").count() === 1 && await dlg.getByRole("button", { name: /Bỏ chọn/ }).count() === 1);
await page.screenshot({ path: `${SHOTS}/create-company-chosen.png` });
await dlg.getByRole("button", { name: /Bỏ chọn/ }).click(); await page.waitForTimeout(200);
check("'Bỏ chọn' returns to the search", await dlg.getByRole("combobox").count() === 1);
await dlg.getByRole("button", { name: "Hủy" }).click(); await page.waitForTimeout(300);
check("Hủy closes the dialog; nothing was created", await page.getByTestId("tenant-create").count() === 0 && created === 0);
// build / cache / network hygiene
const assets = reqs.filter((u) => /\/_next\/static\//.test(u)); const hosts = [...new Set(reqs.map((u) => new URL(u).host))];
check("assets were fetched fresh from the network by an empty profile", assets.length > 3, `${assets.length} /_next/static requests`);
check("no /_next asset error, no 5xx, no failed API call", bad.length === 0, bad.slice(0, 3).join(" ; "));
check("no console / page error (hydration, chunk, CORS)", errors.length === 0, errors.slice(0, 2).join(" ; "));
const publicPortal = /^https:\/\//.test(BASE);
check(publicPortal ? "public: no request to localhost / loopback / a private port" : "local: every request went to the portal origin or the API proxy", !reqs.some((u) => /localhost|127\.0\.0\.1|host\.docker\.internal/.test(new URL(u).host) && (publicPortal || new URL(u).origin !== BASE)), hosts.join(","));
console.log(`screenshots: ${SHOTS}`);
await browser.close();
const failed = results.filter(([, ok]) => !ok).map(([n]) => n); console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}`);
process.exit(failed.length ? 1 : 0);
