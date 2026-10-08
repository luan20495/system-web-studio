#!/usr/bin/env node
// Screenshots of the Studio builder rail panels (Trang / Dữ liệu / Hành động / Workflow …), the publish drawer and the test mode, desktop + phone, on a real stack. A developer tool (NOT a test); nothing is mocked.
//   node scripts/ui-studio-shots.mjs --out <dir>      env: AUDIT_STACK_ENV, AUDIT_STUDIO, CHROME
import { createRequire } from "node:module";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
const require = createRequire(new URL("../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const OUT = (() => { const i = process.argv.indexOf("--out"); return i > 0 ? process.argv[i + 1] : "/tmp/ui-studio-shots"; })(); mkdirSync(OUT, { recursive: true });
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const STUDIO = process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086"; const run = Date.now().toString(36);
const must = (r, w) => { if (r.status < 200 || r.status >= 300) throw new Error(`${w}: ${r.status} ${r.body?.code ?? ""}`); return r.body; };
const sys = new Session(STUDIO, "sys"); await sys.login("local.admin", ENV.LOCAL_ADMIN_PASSWORD);
const tenant = must(await sys.post("/admin/tenants", { slug: `shots-${run}`, name: "Công ty Cổ phần Ánh Dương" }), "tenant");
const ws = must(await sys.post(`/admin/tenants/${tenant.id}/workspaces`, { name: "Kinh doanh" }), "ws");
const l = must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `shots-${run}`, displayName: "Trần Văn Ưu Tú", tenantRole: "MEMBER", workspaceId: ws.id, workspaceRole: "WORKSPACE_ADMIN" }), "user");
const pw = randomSecret(); must(await new Session(STUDIO, "a").post("/auth/activation/complete", { token: l.token, password: pw }), "activate");
const u = new Session(STUDIO, "u"); await u.login(`shots-${run}`, pw);
const proj = must(await u.post(`/workspaces/${ws.id}/projects`, { name: "Trang giới thiệu sản phẩm", appType: "PAGE_SCHEMA" }), "project");
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const RAIL = [["Trang", "builder-pages"], ["Dữ liệu", "builder-data"], ["Hành động", "builder-action"], ["Workflow", "builder-workflow"], ["Giao diện", "builder-theme"]];
for (const w of [1440, 390]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: w > 1000 ? 900 : 800 } }); const p = await ctx.newPage(); p.setDefaultTimeout(12000);
  await p.goto(`${STUDIO}/login`, { waitUntil: "networkidle" }); await p.getByLabel("Tên đăng nhập").fill(`shots-${run}`); await p.getByLabel("Mật khẩu").fill(pw);
  await Promise.all([p.waitForURL((x) => !/\/login/.test(x.pathname)), p.getByRole("button", { name: "Đăng nhập" }).click()]); await p.waitForLoadState("networkidle").catch(() => undefined);
  await p.goto(`${STUDIO}/studio/projects/${proj.id}/design`, { waitUntil: "domcontentloaded" }); await p.waitForSelector("iframe", { timeout: 20000 }).catch(() => undefined); await p.waitForTimeout(800);
  if (w === 1440) console.log("top bar rects", JSON.stringify(await p.evaluate(() => { const r = (e) => { const x = e.getBoundingClientRect(); return [Math.round(x.left), Math.round(x.right)]; }; return { brand: r(document.querySelector(".bx-top .brand")), center: r(document.querySelector(".bx-top-center")), segs: [...document.querySelectorAll(".bx-top .segmented")].map((s) => ({ box: r(s), btns: [...s.querySelectorAll("button")].map(r) })), actions: r(document.querySelector(".bx-top .topActions")), actionKids: [...document.querySelector(".bx-top .topActions").children].map((c) => c.className + ":" + r(c).join("-")) }; })));
  for (const [name, file] of RAIL) {
    const tab = p.getByRole("tab", { name, exact: true }).first();
    if (await tab.count()) { await tab.click().catch(() => undefined); await p.waitForTimeout(400); }
    await p.screenshot({ path: join(OUT, `${file}-${w}.png`) });
  }
  const dung = p.getByRole("button", { name: /Dùng thử/ }).first(); if (await dung.count()) { await dung.click().catch(() => undefined); await p.waitForTimeout(500); await p.screenshot({ path: join(OUT, `builder-test-mode-${w}.png`) }); }
  await p.goto(`${STUDIO}/studio/projects/${proj.id}/publish`, { waitUntil: "domcontentloaded" }); await p.waitForTimeout(1200); await p.screenshot({ path: join(OUT, `studio-publish-${w}.png`) });
  await ctx.close();
}
await browser.close(); console.log("shots in " + OUT);
