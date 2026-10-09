// @class: harness — real Chromium on the three REAL portal builds (apps/*) with the /api/v1 session answers FAKED in the test (page.route); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// M-053 step 1: the console is a lazy chunk behind the login gate. Proves: the login page does not download the console, a signed-in route loads it (with a role=status fallback), and a console chunk that fails to load
// shows the shared error fallback instead of a blank portal.
// Needs the three apps running (npm run build:<app>, then each through tests/lib/owned-process-cli.mjs on a free port): PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT / PORTAL_STUDIO_PORT (default 3001 / 3002 / 3003).
import { launch, makeChecks } from "./lib/spec.mjs";
const { check, finish } = makeChecks();
const PORTS = { platform: Number(process.env.PORTAL_PLATFORM_PORT ?? 3001), admin: Number(process.env.PORTAL_ADMIN_PORT ?? 3002), studio: Number(process.env.PORTAL_STUDIO_PORT ?? 3003) };
const ME = {
  platform: { id: "u-sys", username: "root", displayName: "Quản trị Hệ thống", roles: ["SYSTEM_ADMIN"], systemAdmin: true, platformScope: true, businessAccess: false, workspaces: [], tenants: [], permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] },
  studio: { id: "u1", username: "luan", displayName: "Nguyễn Luân", roles: ["EDITOR"], workspaces: [{ id: "w1", name: "Workspace chính", role: "EDITOR", permissions: ["APP_VIEW", "APP_EDIT"] }], permissions: [] },
};
ME.admin = { ...ME.platform };
const json = (route, body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
const browser = await launch();

async function fakeApi(page, signedIn, who) {
  await page.route("**/api/v1/**", (route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api\/v1/, "");
    if (p === "/auth/me") return signedIn() ? json(route, ME[who]) : json(route, { code: "AUTHENTICATION_REQUIRED" }, 401);
    if (p === "/auth/csrf") return json(route, { token: "t" });
    if (p === "/auth/config") return json(route, { localLogin: true, oidc: false, saml: false, signup: false });
    if (p === "/admin/overview") return json(route, { users: 4, activeUsers: 3, disabledUsers: 1, usersLoggedIn30d: 3, workspaces: 2, projects: 3, publishedProjects: 1, aiRequestsToday: 5, aiRequestsMonth: 50, versionsToday: 2, recentActivity: [] });
    if (p === "/me/usage") return json(route, { aiConfigured: false, aiRequestsUsed: 0, aiRequestsLimit: 0, aiWindowResetsInSeconds: 0, tokensLast24h: 0, tokensLimitPerDay: 0, promptsToday: 0, promptsPerMinute: 0 });
    return json(route, []);
  });
}

for (const [portal, nav] of [["platform", /Tổng quan/], ["admin", /Tổng quan/], ["studio", /Trang chủ/]]) {
  const origin = `http://127.0.0.1:${PORTS[portal]}`; const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const page = await ctx.newPage();
  const errors = []; page.on("pageerror", (e) => errors.push(e.message)); page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|favicon/.test(m.text())) errors.push(m.text()); });
  let signed = false; await fakeApi(page, () => signed, portal);
  const seen = new Set(); page.on("response", (r) => { if (/\/_next\/static\/chunks\/.*\.js/.test(r.url())) seen.add(new URL(r.url()).pathname); });
  await page.goto(`${origin}/${portal}/login`, { waitUntil: "networkidle" }); await page.waitForTimeout(400);
  const first = new Set(seen);
  check(`[${portal}] the login page renders without the console: a login form and no console chrome`, (await page.getByLabel("Tên đăng nhập").count()) === 1 && (await page.locator("nav").count()) === 0);
  // sign in (the fake answers /auth/me as signed in from now on) and open the console
  await page.route("**/_next/static/chunks/*.js", async (route) => { if (!first.has(new URL(route.request().url()).pathname)) await new Promise((r) => setTimeout(r, 1500)); await route.continue(); });   // slow console chunk: the fallback must be visible meanwhile
  signed = true; await page.goto(`${origin}/${portal}`, { waitUntil: "domcontentloaded" }); await page.waitForTimeout(700);
  check(`[${portal}] while the console chunk is loading a role=status fallback is shown`, (await page.locator("[role=status]").count()) >= 1);
  await page.getByRole("link", { name: nav }).first().waitFor({ timeout: 15000 }).catch(() => undefined);
  const lazy = [...seen].filter((u) => !first.has(u));
  check(`[${portal}] a signed-in route renders the console (navigation present)`, (await page.getByRole("link", { name: nav }).count()) >= 1);
  check(`[${portal}] the console came in as extra chunk(s) AFTER the login page`, lazy.length >= 1, `${lazy.length} chunk(s) loaded only after sign-in`);
  check(`[${portal}] no console error or page error`, errors.length === 0, errors.slice(0, 2).join(" | "));
  await ctx.close();

  // a console chunk that fails to load: the shared fallback, not a blank portal
  const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const p2 = await ctx2.newPage(); const errs2 = []; p2.on("pageerror", (e) => errs2.push(e.message));
  let signed2 = false; await fakeApi(p2, () => signed2, portal); const known = new Set();
  p2.on("response", (r) => { if (/\/_next\/static\/chunks\/.*\.js/.test(r.url())) known.add(new URL(r.url()).pathname); });
  await p2.goto(`${origin}/${portal}/login`, { waitUntil: "networkidle" }); await p2.waitForTimeout(300);
  await p2.route("**/_next/static/chunks/*.js", (route) => (known.has(new URL(route.request().url()).pathname) ? route.continue() : route.abort()));
  signed2 = true; await p2.goto(`${origin}/${portal}`, { waitUntil: "domcontentloaded" }); await p2.waitForTimeout(2500);
  const text = await p2.locator("body").innerText();
  check(`[${portal}] when the console chunk cannot be loaded the portal shows an error fallback (role=alert), not a blank page`, (await p2.locator("[role=alert]").count()) >= 1 && text.trim().length > 20, text.replace(/\s+/g, " ").slice(0, 100));
  await ctx2.close();
}
await browser.close();
finish();
