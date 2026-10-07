#!/usr/bin/env node
// PUBLIC 3-PORTAL browser smoke (D-C0-39): real Chrome, empty profile, the Internet hostnames only.
//   per portal: / -> login (UI), real sign-in, post-login page, reload (refresh), deep link, sign-out; every request/response/console message is recorded:
//   no request to localhost / loopback / a private port, no request outside {portal, studio-files, sites}, no CORS error, no ChunkLoadError, no hydration error, no 5xx.
//   then in ONE browser profile: a session on platform is not a session on admin (host-only cookies).
// Accounts as in global-portals-smoke.mjs (never printed). C5 owns the full product E2E; this is the ingress-level proof.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const ROOT = new URL("..", import.meta.url).pathname;
const env = Object.fromEntries(readFileSync(`${ROOT}.run/public/public.env`, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const demo = readFileSync(`${ROOT}.run/public/demo-accounts.txt`, "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p.length >= 2 && /^demo/.test(p[0]));
const HOSTS = { platform: env.PUBLIC_PLATFORM_HOST ?? "platform.toolsmcp.uk", admin: env.PUBLIC_ADMIN_HOST ?? "admin.toolsmcp.uk", studio: env.PUBLIC_HOST ?? "studio.toolsmcp.uk" };
// static.cloudflareinsights.com = the Cloudflare Web Analytics beacon injected at the edge by the (shared) zone setting; it is not requested by the portals' own code
const EDGE_INJECTED = new Set(["static.cloudflareinsights.com", "cloudflareinsights.com"]);
const ALLOWED = new Set([...Object.values(HOSTS), env.PUBLIC_FILES_HOST ?? "studio-files.toolsmcp.uk", env.SITES_HOST ?? "sites.toolsmcp.uk"]);
const ACC = { platform: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], admin: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], studio: [demo?.[0], demo?.[1]] };
const CHROME = process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const { chromium } = createRequire(`${ROOT}package.json`)("playwright-core");
const results = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };
const browser = await chromium.launch({ executablePath: CHROME, headless: true });

function watch(page) {
  const log = { requests: [], bad: [], errors: [], nav: [] };
  page.on("request", (r) => log.requests.push(r.url()));
  page.on("response", (r) => { const u = new URL(r.url()); if (r.status() >= 500 || (r.status() === 404 && /\/_next\//.test(u.pathname))) log.bad.push(`${r.status()} ${u.host}${u.pathname}`); });
  page.on("requestfailed", (r) => { if (r.failure()?.errorText === "net::ERR_ABORTED") return; log.bad.push(`failed ${r.failure()?.errorText} ${new URL(r.url()).host}${new URL(r.url()).pathname}`); });
  page.on("console", (m) => { if (m.type() === "error") log.errors.push(m.text().slice(0, 200)); });
  page.on("pageerror", (e) => log.errors.push("pageerror " + String(e.message).slice(0, 200)));
  return log;
}
async function signIn(page, n, base) {
  await page.goto(base + "/", { waitUntil: "networkidle" });
  const atLogin = /\/login/.test(page.url());
  await page.fill('input:not([type="password"])', ACC[n][0]); await page.fill('input[type="password"]', ACC[n][1]);
  await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 20000 }).catch(() => null), page.click('form button')]);
  await page.waitForLoadState("networkidle").catch(() => {});
  await page.waitForFunction(() => !document.body.innerText.includes("Đang tải"), null, { timeout: 20000 }).catch(() => {});
  return atLogin;
}

for (const n of ["platform", "admin", "studio"]) {
  const base = `https://${HOSTS[n]}`; console.log(`\n===== ${n.toUpperCase()}  ${base}`);
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const page = await ctx.newPage(); const log = watch(page);
  const atLogin = await signIn(page, n, base);
  check(`${n}: the origin root leads an anonymous visitor to the login page`, atLogin);
  const landing = new URL(page.url());
  check(`${n}: real sign-in through the UI leaves the login page (same host, https)`, !/\/login/.test(landing.pathname) && landing.host === HOSTS[n] && landing.protocol === "https:", landing.pathname);
  const body1 = (await page.innerText("body")).replace(/\s+/g, " ").slice(0, 120);
  check(`${n}: the signed-in page renders content`, body1.length > 20, body1.slice(0, 70));
  const cookies = await ctx.cookies(base); const sess = cookies.find((c) => c.name === "STUDIO_SESSION");
  check(`${n}: browser cookie STUDIO_SESSION is host-only, Secure, HttpOnly, Lax`, !!sess && sess.secure && sess.httpOnly && sess.sameSite === "Lax" && sess.domain === HOSTS[n] && sess.path === "/", sess ? `${sess.domain} ${sess.sameSite}` : "missing");
  await page.reload({ waitUntil: "networkidle" });
  check(`${n}: refresh keeps the session (still not on /login)`, !/\/login/.test(new URL(page.url()).pathname), new URL(page.url()).pathname);
  const deep = await page.evaluate(async () => (await fetch("/api/v1/auth/me", { credentials: "same-origin" })).status);
  check(`${n}: the page's own same-origin fetch of /api/v1/auth/me is 200`, deep === 200, `${deep}`);
  // links to other portals must be public hostnames
  const hrefs = await page.evaluate(() => [...document.querySelectorAll("a[href]")].map((a) => a.href));
  const portalLinks = hrefs.filter((h) => Object.values(HOSTS).some((x) => x !== HOSTS[n] && new URL(h).host === x));
  console.log(`INFO ${n}: links to the other portals: ${[...new Set(portalLinks.map((h) => new URL(h).origin))].join(", ") || "none"}`);
  check(`${n}: no link on the page points at localhost / loopback`, !hrefs.some((h) => /localhost|127\.0\.0\.1|host\.docker\.internal/.test(h)), `${hrefs.length} links, ${portalLinks.length} to the other portals`);
  const hosts = [...new Set(log.requests.map((u) => new URL(u).host))];
  check(`${n}: every request went to an allowed public host (portal hosts, files, sites; the edge-injected analytics beacon is reported, not counted)`, hosts.filter((h) => !EDGE_INJECTED.has(h)).every((h) => ALLOWED.has(h)), hosts.join(","));
  if (hosts.some((h) => EDGE_INJECTED.has(h))) console.log(`INFO ${n}: Cloudflare Web Analytics beacon is injected by the edge (zone setting, not C0 / not the portals)`);
  check(`${n}: no request to localhost / loopback / a private port`, !log.requests.some((u) => /localhost|127\.0\.0\.1|host\.docker\.internal|:(3201|3202|3203|3200|18081|28088|25432|26379|29000|8080)\b/.test(u)));
  const noisy = log.errors.filter((e) => !/401|status of 401/.test(e));
  check(`${n}: no CORS, ChunkLoadError, hydration or script error in the console`, !noisy.some((e) => /CORS|ChunkLoadError|Hydration|hydrat|pageerror|Loading chunk|Refused to (load|execute|connect)/i.test(e)), noisy.slice(0, 2).join(" ; "));
  check(`${n}: no 5xx, no failed request, no missing /_next asset`, log.bad.length === 0, log.bad.slice(0, 3).join(" ; "));
  // sign out through the UI when the portal has the control, else through the API with the page's CSRF cookie
  const out = page.getByRole("button", { name: /đăng xuất|sign out|logout/i }).first();
  if (await out.count()) { await out.click().catch(() => {}); await page.waitForURL((u) => /\/login/.test(u.pathname), { timeout: 15000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {}); }
  else { await page.evaluate(async () => { const t = (await (await fetch("/api/v1/auth/csrf")).json()).token; await fetch("/api/v1/auth/logout", { method: "POST", headers: { "x-xsrf-token": t } }); }); await page.goto(base + "/", { waitUntil: "networkidle" }); }
  const after = await page.evaluate(async () => (await fetch("/api/v1/auth/me")).status);
  check(`${n}: after sign-out /api/v1/auth/me is 401 and the portal shows the login page`, after === 401 && /\/login/.test(new URL(page.url()).pathname), `${after} ${new URL(page.url()).pathname}`);
  await ctx.close();
}

// ---- one browser profile, two portals: no shared session
{
  const ctx = await browser.newContext(); const page = await ctx.newPage();
  await signIn(page, "platform", `https://${HOSTS.platform}`);
  const onPlatform = await page.evaluate(async () => (await fetch("/api/v1/auth/me")).status);
  await page.goto(`https://${HOSTS.admin}/`, { waitUntil: "networkidle" });
  const onAdmin = await page.evaluate(async () => (await fetch("/api/v1/auth/me")).status);
  check("cross-portal (one browser profile): signed in on platform, NOT signed in on admin; admin shows its login page", onPlatform === 200 && onAdmin === 401 && /\/login/.test(new URL(page.url()).pathname), `platform=${onPlatform} admin=${onAdmin}`);
  const ck = await ctx.cookies();
  check("cross-portal: no cookie carries a Domain wider than its host (no .toolsmcp.uk)", !ck.some((c) => c.domain.startsWith(".")), ck.map((c) => c.domain).join(","));
  await page.goto(`https://${HOSTS.platform}/`, { waitUntil: "networkidle" });
  await page.evaluate(async () => { const t = (await (await fetch("/api/v1/auth/csrf")).json()).token; await fetch("/api/v1/auth/logout", { method: "POST", headers: { "x-xsrf-token": t } }); });
  await ctx.close();
}
await browser.close();
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}`);
process.exit(failed.length ? 1 : 0);
