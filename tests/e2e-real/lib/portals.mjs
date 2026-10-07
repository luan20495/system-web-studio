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
