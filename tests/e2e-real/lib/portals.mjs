// @class: real-backend — helpers for the Platform (:3001) and Admin (:3002) portals. Each portal is its own Next app with its own login; the API is reached through the same-origin /api proxy of THAT portal.
export const portalUrl = (cfg, which) => (which === "platform" ? cfg.platformUrl : cfg.adminUrl);
export const portalPrefix = (which) => (which === "platform" ? "/platform" : "/admin");

/** logs in through the portal's own login form; returns where it landed */
export async function loginPortal(page, cfg, which, username, password) {
  await page.goto(`${portalUrl(cfg, which)}${portalPrefix(which)}/login`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("Tên đăng nhập").fill(username); await page.getByLabel("Mật khẩu").fill(password);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  // the form posts, loads /auth/me, then moves on: wait for the move (to a screen or to /auth/no-access), not for a fixed time
  await page.waitForURL((u) => !/\/login$/.test(u.pathname), { timeout: 15_000 }).catch(() => undefined);
  await page.waitForLoadState("networkidle", { timeout: 20_000 }).catch(() => undefined);
  await page.waitForTimeout(400);
  return new URL(page.url()).pathname;
}
export const openPortal = async (page, cfg, which, path = "") => { await page.goto(`${portalUrl(cfg, which)}${portalPrefix(which)}${path}`, { waitUntil: "domcontentloaded" }); await page.waitForLoadState("networkidle", { timeout: 20_000 }).catch(() => undefined); await page.waitForTimeout(400); };
export const navLabels = (page) => page.locator("aside nav a").evaluateAll((l) => l.map((a) => a.textContent.replace(/^[^A-Za-zÀ-ỹ]+/, "").trim()));
/** every /api response with status ≥ 400 the page received (path, status): the flows decide which ones are expected */
export function watchApi(page) { const bad = []; page.on("response", (r) => { if (r.status() >= 400 && /\/api\//.test(r.url())) bad.push(`${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()}`); }); return bad; }

import { Session, randomSecret } from "./api.mjs";
/** an extra fixture account (registered for cleanup): created by the SYSTEM_ADMIN, activated, logged in. `activate: false` leaves it non-activated. */
export async function makeUser(fx, cfg, key, workspace, role, { activate = true } = {}) {
  const username = `e2e-${fx.runId}-${key}`.toLowerCase().replace(/[^a-z0-9._-]/g, "-").slice(0, 40);
  const r = await fx.sessions.admin.post("/admin/users", { username, displayName: `E2E ${key} ${fx.runId}`, workspaceId: fx.workspaces[workspace], role });
  if (r.status !== 201) throw new Error(`makeUser ${key}: ${r.status} ${r.body?.code}`);
  fx.created.users.push(r.body.userId);
  const u = { id: r.body.userId, username, password: null, session: null };
  if (activate) {
    u.password = randomSecret();
    const c = await new Session(cfg.studio, "activation").post("/auth/activation/complete", { token: r.body.token, password: u.password });
    if (c.status >= 300) throw new Error(`makeUser ${key}: activation ${c.status}`);
    u.session = new Session(cfg.studio, key); await u.session.login(username, u.password);
  }
  return u;
}

/** the activation link the create-account dialog shows (read from the DOM, never from the API response) → the person opens it and chooses a password through the real activation page */
export async function activateByLink(browser, link, password) {
  const ctx = await browser.newContext(); const pg = await ctx.newPage(); pg.setDefaultTimeout(10_000);
  await pg.goto(link, { waitUntil: "domcontentloaded" });
  await pg.getByLabel("Mật khẩu", { exact: true }).fill(password); await pg.getByLabel("Nhập lại mật khẩu").fill(password);
  await pg.getByRole("button", { name: "Lưu mật khẩu" }).click();
  const ok = await pg.getByText("Đã đặt mật khẩu").waitFor({ timeout: 15_000 }).then(() => true).catch(() => false);
  await ctx.close(); return ok;
}
