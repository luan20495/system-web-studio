#!/usr/bin/env node
// C6 FINAL RC QA — PART 15 brand / visual measurements (REAL_STACK: real portals in a real browser) + screenshots for the MANUAL review.
// Env: FIXTURE (final-ui-fixture.json, mode 600)  BROWSER=chromium|webkit  TARGET=local|public  FINAL_* from final-env.sh. Never prints credentials.
import { chromium, webkit } from "playwright-core"; import { readFileSync, mkdirSync } from "node:fs";
import { recorder, OUT } from "./final-lib.mjs";
const BROWSER = process.env.BROWSER ?? "chromium", TARGET = process.env.TARGET ?? "local"; const NAME = `part15-brand-${TARGET}-${BROWSER}`; const { rec, save } = recorder(NAME, "REAL_STACK");
const SHOTS = `${OUT}/${NAME}/shots`; mkdirSync(SHOTS, { recursive: true });
const BASE = TARGET === "public" ? { platform: "https://platform.toolsmcp.uk", admin: "https://admin.toolsmcp.uk", studio: "https://studio.toolsmcp.uk" } : { platform: process.env.FINAL_PLATFORM, admin: process.env.FINAL_ADMIN, studio: process.env.FINAL_STUDIO };
const fx = TARGET === "local" ? JSON.parse(readFileSync(process.env.FIXTURE, "utf8")) : null;
const browser = BROWSER === "webkit" ? await webkit.launch({ headless: true }) : await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const LEGACY = /AI Software Factory|Company Builder Studio|Admin Console/i;
const probe = () => ({
  title: document.title, lang: document.documentElement.lang,
  primary: getComputedStyle(document.documentElement).getPropertyValue("--color-primary").trim().toLowerCase(), tile: getComputedStyle(document.documentElement).getPropertyValue("--color-brand-tile").trim().toLowerCase(),
  font: getComputedStyle(document.body).fontFamily, bodyBg: getComputedStyle(document.body).backgroundColor,
  logo: [...document.querySelectorAll('[role=img][aria-label], img[alt], svg[aria-label]')].filter((e) => /xweb/i.test(e.getAttribute("aria-label") ?? e.getAttribute("alt") ?? "")).map((e) => { const r = e.getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height), vis: r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== "hidden" }; }),
  bgClasses: [...new Set([...document.querySelectorAll('[class*="xp-bg-"]')].flatMap((e) => String(e.className).split(/\s+/).filter((c) => c.startsWith("xp-bg-"))))],
  bgImage: [...document.querySelectorAll('[class*="xp-bg-"]')].slice(0, 1).map((e) => getComputedStyle(e).backgroundImage.slice(0, 80))[0] ?? "",
  banners: document.querySelectorAll('[class*="xp-banner"], [class*="Banner"]').length, lockup: (document.querySelector(".sideBrand")?.innerText ?? "").replace(/\s+/g, " ").trim(),
  sidebarBg: document.querySelector(".sidebar, aside")?.matches(".dark") ? getComputedStyle(document.querySelector(".sidebar, aside")).backgroundColor : "", text: document.body.innerText.slice(0, 4000), themeAttr: document.documentElement.getAttribute("data-theme") ?? "", h1: [...document.querySelectorAll("h1")].length,
  icons: [...document.querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"]')].map((l) => ({ rel: l.rel, href: l.href, type: l.type })),
});
async function look(portal, label, { scheme = "light", w = 1440, h = 900, state = undefined, path = "/login", name } = {}) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, colorScheme: scheme, storageState: state }); const page = await ctx.newPage(); const external = []; const origin = new URL(BASE[portal]).origin;
  page.on("request", (r) => { try { const u = new URL(r.url()); if (u.origin !== origin && !/^(data|blob):/.test(r.url())) external.push(u.hostname + " " + r.resourceType()); } catch {} });
  await page.goto(BASE[portal] + path, { waitUntil: "networkidle", timeout: 40000 }).catch(() => {}); await page.waitForTimeout(600); const p = await page.evaluate(probe);
  const shot = `${portal}-${name ?? label}-${scheme}-${w}.png`; await page.screenshot({ path: `${SHOTS}/${shot}`, fullPage: false }).catch(() => {}); await ctx.close(); return { p, external, shot };
}
const portals = [["platform", "Platform"], ["admin", "Admin"], ["studio", "Studio"]]; const results = {};
for (const [portal, nm] of portals) {
  for (const scheme of ["light", "dark"]) for (const w of [1440, 390]) {
    const r = await look(portal, "login", { scheme, w, path: portal === "studio" ? "/login" : "/login" }); results[`${portal}-${scheme}-${w}`] = r; const k = `BR-${portal}-login-${scheme}-${w}`;
    if (scheme === "light" && w === 1440) {
      rec(`${k}-title`, "identity", `${nm} login: document title names Xweb ${nm}`, `/Xweb ${nm}/`, r.p.title, new RegExp(`Xweb ${nm}`, "i").test(r.p.title));
      rec(`${k}-legacy`, "identity", `${nm} login: no legacy product name in title/text`, "none", LEGACY.test(r.p.title + r.p.text) ? "LEGACY NAME PRESENT" : "none", !LEGACY.test(r.p.title + r.p.text));
      rec(`${k}-primary`, "tokens", `${nm}: --color-primary is the brand cobalt #1d5bd8 (light)`, "#1d5bd8", r.p.primary, r.p.primary === "#1d5bd8");
      rec(`${k}-extfont`, "typography", `${nm} login: no external font/CDN request`, "0", `${r.external.length}${r.external.length ? " " + [...new Set(r.external)].slice(0, 3).join(",") : ""}`, r.external.filter((e) => /font|stylesheet/.test(e)).length === 0);
      rec(`${k}-fontstack`, "typography", `${nm}: system font stack (no web font family)`, "system/Inter stack", r.p.font.slice(0, 80), !/Roboto|Open Sans|Poppins|Montserrat|Lato/i.test(r.p.font));
      rec(`${k}-icons`, "favicon", `${nm}: favicon + apple-touch-icon links present`, ">=1 icon", JSON.stringify(r.p.icons.map((i) => i.rel + ":" + i.href.split("/").pop())).slice(0, 120), r.p.icons.length >= 1);
    }
    rec(`${k}-logo`, "logo", `${nm} login (${scheme}, ${w}px): Xweb logo visible, ≥ 20px high`, "visible", JSON.stringify(r.p.logo).slice(0, 80), r.p.logo.some((l) => l.vis && l.h >= 20));
    rec(`${k}-bg`, "background", `${nm} login (${scheme}, ${w}px): auth background class xp-bg-auth with a gradient`, "xp-bg-auth + gradient", `${r.p.bgClasses.join(",")} | ${r.p.bgImage.slice(0, 40)}`, r.p.bgClasses.includes("xp-bg-auth") && /gradient/.test(r.p.bgImage));
  }
  // favicon files really served
  const l = results[`${portal}-light-1440`].p.icons; for (const ic of l.slice(0, 3)) { const x = await fetch(ic.href).catch(() => null); rec(`BR-${portal}-icon-${ic.rel}`, "favicon", `${nm}: ${ic.rel} ${ic.href.split("/").pop()} is served as an image`, "200 image/*", x ? `${x.status} ${x.headers.get("content-type")}` : "no response", !!x && x.status === 200 && /image\//.test(x.headers.get("content-type") ?? "")); }
}
// authenticated identity: lockup text per portal, dark rail, backgrounds, banners — local only (needs the fixture accounts)
if (fx) {
  const login = async (portal, acc) => { const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const page = await ctx.newPage(); await page.goto(`${BASE[portal]}/login`, { waitUntil: "networkidle" }); if (await page.getByRole("radio").count()) await page.getByRole("radio").first().check().catch(() => {});
    await page.getByLabel("Tên đăng nhập").fill(acc.username); await page.getByLabel("Mật khẩu").fill(acc.password); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 25000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {}); const st = await ctx.storageState(); await ctx.close(); return st; };
  const accs = { platform: fx.accounts.superAdmin, admin: fx.accounts.tenantAdmin, studio: fx.accounts.workspaceAdmin }; const expectLock = { platform: /Platform/i, admin: /Quản trị công ty/i, studio: /Studio/i };
  for (const [portal, nm] of portals) {
    let state; try { state = await login(portal, accs[portal]); } catch (e) { rec(`BR-${portal}-login`, "identity", `${nm}: authenticated brand checks need a UI login`, "login ok", String(e.message).split("\n")[0].slice(0, 120), null, { note: "harness could not log in (see message); not a brand result" }); continue; }
    for (const scheme of ["light", "dark"]) {
      const r = await look(portal, "home", { scheme, state, path: `/${portal}`, name: "home" }); const k = `BR-${portal}-home-${scheme}`;
      rec(`${k}-lockup`, "identity", `${nm} home (${scheme}): sidebar lockup names the portal in words`, String(expectLock[portal]), r.p.lockup, expectLock[portal].test(r.p.lockup), { note: "one logo for all three portals; only the words differ" });
      rec(`${k}-logo`, "logo", `${nm} home (${scheme}): Xweb logo/mark visible in the shell`, "visible", JSON.stringify(r.p.logo).slice(0, 80), r.p.logo.some((l) => l.vis));
      rec(`${k}-legacy`, "identity", `${nm} home (${scheme}): no legacy product name`, "none", LEGACY.test(r.p.text) ? "LEGACY NAME PRESENT" : "none", !LEGACY.test(r.p.text));
      rec(`${k}-bgclasses`, "background", `${nm} home (${scheme}): brand background classes in use`, "recorded", r.p.bgClasses.join(",") || "(none)", r.p.bgClasses.length > 0 ? true : null, { note: "observation; the dashboard/hero/pattern backgrounds are optional per page" });
    }
  }
}
await browser.close(); const failed = save(); process.exit(failed ? 1 : 0);
