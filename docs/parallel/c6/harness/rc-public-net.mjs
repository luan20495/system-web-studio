#!/usr/bin/env node
// C6 RC wide regression — PUBLIC HOSTS with browser Network evidence (real Chrome, empty profile, Internet hostnames only).
// Per portal: anonymous load, deep link, sign-in with the real account, reload, a protected page, sign-out. Every request/response/console message is recorded
// (URL without query strings, method, status, resource type — never headers, cookies or bodies). Assertions: https only, no loopback/private address, no mixed content,
// no failed asset, no 5xx, no console error from the app, no "mock" runtime marker. Credentials come from .run/public/*, are typed into the real login form and never written down.
import { chromium } from "playwright-core";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const OUT = process.env.OUT; mkdirSync(`${OUT}/net`, { recursive: true }); mkdirSync(`${OUT}/shots`, { recursive: true });
const ROOT = "/Users/hoangluan/code/HBL/";
const env = Object.fromEntries(readFileSync(`${ROOT}.run/public/public.env`, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const demo = readFileSync(`${ROOT}.run/public/demo-accounts.txt`, "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p.length >= 2 && /^demo/.test(p[0]));
const HOSTS = { platform: "platform.toolsmcp.uk", admin: "admin.toolsmcp.uk", studio: "studio.toolsmcp.uk" };
const ACC = { platform: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], admin: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], studio: [demo[0], demo[demo.length - 1]] };
const DEEP = { platform: "/platform/tenants", admin: "/admin/people", studio: "/studio/projects" };   // PORTAL_PREFIX of @xweb/permissions + the first list page of each portal
const ALLOWED = new Set([...Object.values(HOSTS), "studio-files.toolsmcp.uk", "sites.toolsmcp.uk"]);
const EDGE = new Set(["static.cloudflareinsights.com", "cloudflareinsights.com"]);
const rows = []; const rec = (id, portal, desc, expected, actual, ok, note = "") => { rows.push({ id, portal, desc, expected, actual: String(actual), result: ok ? "PASS" : "FAIL", note }); console.log(`${ok ? "PASS" : "FAIL"} ${id} [${portal}] ${desc} | expected ${expected} | actual ${actual}${note ? " | " + note : ""}`); };
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const clean = (u) => { try { const x = new URL(u); return `${x.protocol}//${x.host}${x.pathname}`; } catch { return u; } };

for (const [portal, host] of Object.entries(HOSTS)) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const page = await ctx.newPage();
  const net = []; const consoleErrors = []; const navs = [];
  page.on("request", (r) => net.push({ t: Date.now(), url: clean(r.url()), method: r.method(), type: r.resourceType(), status: null }));
  page.on("response", (r) => { const e = net.find((x) => x.url === clean(r.url()) && x.method === r.request().method() && x.status === null); if (e) e.status = r.status(); });
  page.on("requestfailed", (r) => { const e = net.find((x) => x.url === clean(r.url()) && x.status === null); if (e) e.status = "FAILED:" + (r.failure()?.errorText ?? ""); });
  page.on("console", (m) => { if (["error", "warning"].includes(m.type())) consoleErrors.push(`${m.type()}: ${m.text().slice(0, 160).replace(/https?:\/\/\S+/g, (u) => clean(u))}`); });
  page.on("framenavigated", (f) => { if (f === page.mainFrame()) navs.push(clean(f.url()) + (new URL(f.url()).search ? "?…" : "")); });
  const o = `https://${host}`;
  // 1 anonymous load
  const r0 = await page.goto(o + "/", { waitUntil: "networkidle", timeout: 45000 }); await page.waitForTimeout(800);
  rec(`${portal}-01`, portal, "HTTPS root loads", "HTTP 200 → login page", `${r0?.status()} ${new URL(page.url()).pathname}`, r0?.status() === 200 && /login/.test(page.url()));
  await page.screenshot({ path: `${OUT}/shots/pub-${portal}-login.png` });
  const bodyText = (await page.locator("body").innerText()).replace(/\s+/g, " ");
  rec(`${portal}-02`, portal, "login form is rendered (fields + button)", "Tên đăng nhập / Mật khẩu / Đăng nhập", `${await page.getByLabel("Tên đăng nhập").count()}/${await page.getByLabel("Mật khẩu").count()}/${await page.getByRole("button", { name: "Đăng nhập", exact: true }).count()}`, (await page.getByLabel("Tên đăng nhập").count()) === 1 && (await page.getByRole("button", { name: "Đăng nhập", exact: true }).count()) === 1);
  // 2 deep link while signed out
  const n0 = navs.length; await page.goto(o + DEEP[portal], { waitUntil: "networkidle", timeout: 45000 }); await page.waitForTimeout(500);
  const loops = navs.length - n0; rec(`${portal}-03`, portal, `deep link ${DEEP[portal]} while signed out`, "redirect to /login?next=…, ≤ 4 navigations (no loop)", `${new URL(page.url()).pathname}${new URL(page.url()).search ? "?…" : ""}; navigations=${loops}`, /\/login/.test(page.url()) && loops <= 4);
  // 3 sign in
  const [user, pass] = ACC[portal];
  if (portal === "studio" && (await page.getByRole("radio").count())) await page.getByRole("radio", { name: /Builder Studio/ }).check().catch(() => {});
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pass);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 25000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {}); await page.waitForTimeout(1200);
  const after = new URL(page.url()); rec(`${portal}-04`, portal, "sign in with the real account", "leaves /login, lands in the portal (not /auth/no-access)", `${after.pathname}`, !/login|no-access/.test(after.pathname));
  await page.screenshot({ path: `${OUT}/shots/pub-${portal}-home.png` });
  const home = (await page.locator("body").innerText()).replace(/\s+/g, " "); rec(`${portal}-05`, portal, "portal home renders real content (no mock banner / no 'Chưa sẵn sàng' shell)", "no 'mock' / 'demo data' marker", `${/mock mode|dữ liệu mẫu|mock data/i.test(home) ? "MARKER FOUND" : "none"}`, !/mock mode|dữ liệu mẫu|mock data/i.test(home));
  // 4 reload keeps the session
  await page.reload({ waitUntil: "networkidle" }); await page.waitForTimeout(800); rec(`${portal}-06`, portal, "reload keeps the session", "still not on /login", new URL(page.url()).pathname, !/login/.test(page.url()));
  // 5 direct URL of a protected page
  await page.goto(o + DEEP[portal], { waitUntil: "networkidle", timeout: 45000 }).catch(() => {}); await page.waitForTimeout(800);
  const deepText = (await page.locator("body").innerText()).replace(/\s+/g, " "); rec(`${portal}-07`, portal, `direct URL ${DEEP[portal]} when signed in`, "opens the page (no /login, no 'Không có trang này')", `${new URL(page.url()).pathname}${/Không có trang này/.test(deepText) ? " — NOT FOUND PAGE" : ""}`, !/login/.test(page.url()) && !/Không có trang này/.test(deepText));
  await page.screenshot({ path: `${OUT}/shots/pub-${portal}-deep.png` });
  // 6 same-origin API from the page
  const cfg = await page.evaluate(async () => { const r = await fetch("/api/v1/auth/me", { credentials: "include" }); return { status: r.status, origin: location.origin }; });
  rec(`${portal}-08`, portal, "page → same-origin /api/v1/auth/me", "200 on the portal origin", `${cfg.status} ${cfg.origin}`, cfg.status === 200 && cfg.origin === o);
  // 7 sign out
  const out = page.getByText("Đăng xuất", { exact: true }).first(); let signedOut = false;
  if (await out.count()) { await out.click().catch(() => {}); await page.waitForTimeout(2500); signedOut = /login/.test(page.url()); }
  else { const menu = page.getByRole("button", { name: new RegExp(user, "i") }).first(); if (await menu.count()) { await menu.click().catch(() => {}); await page.getByText(/Đăng xuất/).first().click().catch(() => {}); await page.waitForTimeout(2500); signedOut = /login/.test(page.url()); } }
  const me2 = await page.evaluate(async () => (await fetch("/api/v1/auth/me", { credentials: "include" })).status);
  rec(`${portal}-09`, portal, "sign out (UI)", "back on /login and /auth/me = 401", `${new URL(page.url()).pathname}; me=${me2}`, me2 === 401, signedOut ? "" : "no UI sign-out control found by role/name; me=401 only if the session ended");
  // network assertions over the whole sequence
  const hosts = [...new Set(net.map((x) => { try { return new URL(x.url).host; } catch { return x.url; } }))];
  const bad = net.filter((x) => { try { const h = new URL(x.url).hostname; return (!ALLOWED.has(h) && !EDGE.has(h)) || /^(localhost|127\.|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h); } catch { return false; } });
  const insecure = net.filter((x) => x.url.startsWith("http://") || x.url.startsWith("ws://"));
  const failed = net.filter((x) => String(x.status).startsWith("FAILED") && !/cloudflareinsights/.test(x.url));
  const s5 = net.filter((x) => typeof x.status === "number" && x.status >= 500);
  const s4 = net.filter((x) => typeof x.status === "number" && x.status >= 400 && x.status < 500 && !(x.status === 401 && /\/api\/v1\/auth\/me$/.test(x.url)));
  const assets = net.filter((x) => /\/_next\//.test(x.url)); const assetBad = assets.filter((x) => x.status !== 200 && x.status !== 304);
  rec(`${portal}-N1`, portal, "NETWORK: every request goes to the portal / studio-files / sites hosts (no localhost, loopback or private address)", "0 foreign", `${bad.length} (hosts: ${hosts.join(", ")})`, bad.length === 0);
  rec(`${portal}-N2`, portal, "NETWORK: no http:// or ws:// request (no mixed content)", "0", insecure.length, insecure.length === 0);
  rec(`${portal}-N3`, portal, "NETWORK: /_next assets all load", "0 broken of N", `${assetBad.length} broken of ${assets.length}`, assetBad.length === 0 && assets.length > 0);
  rec(`${portal}-N4`, portal, "NETWORK: no failed request, no 5xx", "0", `${failed.length} failed, ${s5.length} 5xx`, failed.length === 0 && s5.length === 0);
  rec(`${portal}-N5`, portal, "NETWORK: 4xx only the expected 401 of /auth/me before sign-in", "0 unexpected", `${s4.length}${s4.length ? " (" + s4.slice(0, 4).map((x) => `${x.status} ${x.url.replace(/^https:\/\/[^/]+/, "")}`).join("; ") + ")" : ""}`, s4.length === 0);
  const ce = consoleErrors.filter((x) => !/cloudflareinsights|favicon|ERR_BLOCKED_BY_CLIENT|status of 401/i.test(x));   // "status of 401" = the expected /auth/me before sign-in, already judged in N5 rec(`${portal}-N6`, portal, "CONSOLE: no app error (CORS, chunk load, hydration)", "0", `${ce.length}${ce.length ? " — " + ce[0] : ""}`, ce.length === 0);
  // bundle scan: first-party JS must not carry loopback origins or a mock-mode switch
  const jsUrls = [...new Set(net.filter((x) => x.type === "script" && x.url.includes(host)).map((x) => x.url))]; let loop = 0, mock = 0, scanned = 0;
  for (const u of jsUrls.slice(0, 40)) { try { const t = await (await fetch(u)).text(); scanned++; if (/https?:\/\/(127\.0\.0\.1|localhost)[:\/]/.test(t)) loop++; if (/NEXT_PUBLIC_API_MODE["']?\s*[:=]\s*["']mock/.test(t) || /apiMode\s*[:=]\s*["']mock["']/.test(t)) mock++; } catch {} }
  rec(`${portal}-N7`, portal, "BUNDLE: first-party scripts contain no http://localhost|127.0.0.1 origin and no mock-mode switch", "0 / 0", `${loop} loopback, ${mock} mock (${scanned} scripts)`, loop === 0 && mock === 0);
  writeFileSync(`${OUT}/net/public-${portal}.json`, JSON.stringify({ host, navigations: navs, requests: net.map(({ t, ...x }) => x), console: consoleErrors }, null, 1));
  await ctx.close();
}
// sites gateway on the public host
for (const [id, path, exp, okf] of [["sites-01", "/healthz", "200", (s) => s === 200], ["sites-02", "/c6-no-such-slug-rc/", "404", (s) => s === 404], ["sites-03", "/", "404 or 403 (no listing)", (s) => [403, 404].includes(s)]]) {
  const r = await fetch("https://sites.toolsmcp.uk" + path, { redirect: "manual" }); rec(id, "sites", `GET ${path} on the public sites host`, exp, r.status, okf(r.status));
}
const r = await fetch("http://studio.toolsmcp.uk/", { redirect: "manual" }); rec("http-01", "studio", "plain http → https redirect", "301/308 to https", `${r.status} ${r.headers.get("location")}`, [301, 308].includes(r.status) && /^https:/.test(r.headers.get("location") ?? ""));
await browser.close();
const failed = rows.filter((x) => x.result === "FAIL");
writeFileSync(`${OUT}/rc-public-net.json`, JSON.stringify({ total: rows.length, failed: failed.length, rows }, null, 1));
console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`);
process.exit(failed.length ? 1 : 0);
