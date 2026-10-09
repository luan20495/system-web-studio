// @class: harness — real Chromium on a test-only host (fake host / no API behind it); NOT a backend E2E
// Real-browser checks of the three portals WITHOUT a backend: everything that does not need a session.
// Needs the three apps running: platform 127.0.0.1:3001, admin 127.0.0.1:3002, studio 127.0.0.1:3003 (PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT / PORTAL_STUDIO_PORT override the ports) (npm run build:<app> && cd apps/<app> && npx next start -H 127.0.0.1 -p <port>).
// With no API behind the same-origin /api proxy the browser sees 500s; the UI must still show the login form. It does NOT test login, session, OIDC or cookies after sign-in.
import { launch, makeChecks } from "./lib/spec.mjs";
// every port is overridable (PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT / PORTAL_STUDIO_PORT): a second stack on the machine must not be tested by accident (S4-audit)
const PORTALS = [["platform", Number(process.env.PORTAL_PLATFORM_PORT ?? 3001), "/platform", "Xweb Platform"], ["admin", Number(process.env.PORTAL_ADMIN_PORT ?? 3002), "/admin", "Quản trị công ty"], ["studio", Number(process.env.PORTAL_STUDIO_PORT ?? 3003), "/studio", "Xweb Studio"]];
const { check, finish } = makeChecks();
const browser = await launch();

for (const [name, port, prefix, title] of PORTALS) {
  const origin = `http://127.0.0.1:${port}`;
  // M-093 (a): a failed /auth/config is an error with a retry, never a guessed form (fail closed). So the form checks below answer `/api/v1/auth/config` with a minimal valid config (the login POST still goes to the unreachable proxy)
  const fail = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const fp = await fail.newPage();
  await fp.goto(origin + "/login", { waitUntil: "networkidle" }); await fp.waitForTimeout(600);
  check(`[${name}] no backend: the sign-in methods are unknown, so the page FAILS CLOSED (error + retry, no password form)`, (await fp.getByText("Chưa tải được cách đăng nhập").count()) === 1 && (await fp.getByRole("button", { name: /thử lại/i }).count()) >= 1 && (await fp.getByLabel("Mật khẩu").count()) === 0);
  await fail.close();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  await ctx.route("**/api/v1/auth/config*", (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ localLogin: true, oidc: false, saml: false, signup: false, needsSetup: false }) }));
  const page = await ctx.newPage(); const bad = [];
  page.on("console", (m) => { if (/Content Security Policy|Refused to/i.test(m.text())) bad.push(m.text().slice(0, 160)); });
  page.on("pageerror", (e) => bad.push("pageerror " + e.message.slice(0, 120)));
  const r = await page.goto(origin + prefix, { waitUntil: "networkidle" }); await page.waitForTimeout(600);
  const h = r.headers();
  check(`[${name}] direct URL ${prefix} without a session lands on /login and keeps the destination`, page.url() === `${origin}/login?next=${encodeURIComponent(prefix)}`, page.url().replace(origin, ""));
  check(`[${name}] login page is for this portal`, (await page.locator("body").innerText()).includes(title));
  check(`[${name}] login fields have accessible labels`, (await page.getByLabel("Tên đăng nhập").count()) === 1 && (await page.getByLabel("Mật khẩu").count()) === 1);
  const csp = h["content-security-policy"] ?? "";
  check(`[${name}] CSP: nonce + strict-dynamic, no unsafe-eval, frame-ancestors 'none', object-src 'none'`, /script-src[^;]*'nonce-[^']+'[^;]*'strict-dynamic'/.test(csp) && !/unsafe-eval/.test(csp) && /frame-ancestors 'none'/.test(csp) && /object-src 'none'/.test(csp));
  check(`[${name}] security headers: nosniff, X-Frame-Options DENY, Referrer-Policy`, h["x-content-type-options"] === "nosniff" && h["x-frame-options"] === "DENY" && !!h["referrer-policy"]);
  check(`[${name}] no CSP violations or page errors while rendering`, bad.length === 0, bad.join(" ; "));
  check(`[${name}] no cookies are set before sign-in`, (await ctx.cookies()).length === 0);
  // other portal's prefix on this origin must not show another console's screens
  await page.goto(`${origin}${PORTALS.find((x) => x[0] !== name)[2]}/users`, { waitUntil: "networkidle" }); await page.waitForTimeout(500);
  check(`[${name}] a path of another portal also ends at /login (no screen without a session)`, /\/login/.test(page.url()));
  // refresh keeps the page
  await page.goto(origin + "/login", { waitUntil: "networkidle" }); await page.reload({ waitUntil: "networkidle" });
  check(`[${name}] refresh on /login stays on /login`, page.url() === `${origin}/login`);
  // login with the backend unreachable shows an error instead of hanging or faking success (test-only values, local dev origin)
  await page.getByLabel("Tên đăng nhập").fill("test-user"); await page.getByLabel("Mật khẩu").fill("test-only-value-1");
  await page.getByRole("button", { name: "Đăng nhập" }).click(); await page.waitForTimeout(1500);
  const txt = (await page.locator("body").innerText()).replace(/\s+/g, " ");
  check(`[${name}] login with no backend shows an error and stays on /login`, /login/.test(page.url()) && /(không|lỗi|thất bại|thử lại|kết nối)/i.test(txt.replace(/Tên đăng nhập|Mật khẩu/g, "")), txt.slice(txt.indexOf("Đăng nhập vào") + 40, txt.indexOf("Đăng nhập vào") + 200));
  check(`[${name}] no cookies after a failed login`, (await ctx.cookies()).length === 0);
  await page.screenshot({ path: `${process.env.SHOTS ?? "/tmp/shots"}/portal-${name}-login-error.png` });
  await ctx.close();
}
await browser.close();
finish();
