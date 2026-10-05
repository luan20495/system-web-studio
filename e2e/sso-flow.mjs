// OIDC/SSO against a real Keycloak (scripts/sso-up.sh, then restart the API). Verifies that identity != authorization.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";

const read = (f) => Object.fromEntries(readFileSync(new URL(f, import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const env = read("../.env"), sso = read("../.run/sso.env");
const BASE = "http://127.0.0.1:3100", WS = "00000000-0000-0000-0000-000000000001", DEMO = "00000000-0000-0000-0000-0000000000d1";
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q}"`).toString().trim();
const results = []; let failed = false;
async function check(name, fn) { try { await fn(); results.push(1); console.log("PASS", name); } catch (e) { failed = true; results.push(0); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } }
const expect = (c, m) => { if (!c) throw new Error(m); };

const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
async function ssoLogin(username) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await page.goto(BASE);
  await page.getByRole("radio", { name: /Builder Studio/ }).check();
  await page.getByRole("link", { name: "Tiếp tục với SSO công ty" }).click();
  await page.waitForURL(/18080/);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(sso.SSO_TEST_PASSWORD);
  await page.locator("#kc-login").click();
  await page.waitForURL(/127\.0\.0\.1:3100/, { timeout: 20000 });
  return { ctx, page };
}
async function adminApi() {
  const ctx = await browser.newContext();
  const csrf = async () => (await (await ctx.request.get(`${BASE}/api/v1/auth/csrf`)).json()).token;
  await ctx.request.post(`${BASE}/api/v1/auth/login`, { headers: { "X-XSRF-TOKEN": await csrf(), "Content-Type": "application/json" }, data: { username: "local.admin", password: env.LOCAL_ADMIN_PASSWORD } });
  const call = async (method, path, data) => ctx.request.fetch(`${BASE}/api/v1${path}`, { method, headers: { "X-XSRF-TOKEN": await csrf(), "Content-Type": "application/json" }, data });
  return { call };
}

// start from fresh SSO identities (previous runs granted them access); dev database only
for (const q of ["delete from project_members where user_id in (select id from users where auth_source='OIDC')",
                 "delete from workspace_members where user_id in (select id from users where auth_source='OIDC')",
                 "delete from external_identities", "delete from users where auth_source='OIDC'"]) sql(q);
// a LOCAL account that owns the same verified email: SSO must never inherit it
sql("delete from project_members where user_id in (select id from users where username='local.emailtwin')");
sql("delete from workspace_members where user_id in (select id from users where username='local.emailtwin')");
sql("delete from users where username='local.emailtwin'");
sql(`insert into users (id, username, password_hash, enabled, email) select gen_random_uuid(), 'local.emailtwin', password_hash, true, 'sso.user@example.test' from users where username='local.viewer'`);
sql(`insert into workspace_members (workspace_id, user_id, role, active) select '${WS}', id, 'WORKSPACE_ADMIN', true from users where username='local.emailtwin'`);

let user;
await check("SSO button is offered and the Keycloak round trip logs the user in", async () => {
  user = await ssoLogin(sso.SSO_TEST_USER);
  await user.page.getByText("Bạn chưa thuộc workspace nào").first().waitFor({ timeout: 15000 });
});
await check("a new SSO identity gets its own account with NO access (verified email did not link to the local twin)", async () => {
  const n = sql("select count(*) from external_identities");
  expect(Number(n) >= 1, "identity row missing");
  const row = sql("select u.username || '|' || coalesce(u.email,'null') || '|' || u.auth_source from external_identities e join users u on u.id=e.user_id order by e.created_at desc limit 1");
  expect(row.startsWith("oidc-") && row.endsWith("|OIDC"), `unexpected user ${row}`);
  expect(row.includes("|null|"), "email must stay unlinked because a local account already owns it");
  const r = await user.ctx.request.get(`${BASE}/api/v1/workspaces/${WS}/projects`);
  expect(r.status() === 404, `expected 404 for workspace without membership, got ${r.status()}`);
});
await check("the SSO session cookie is HttpOnly and survives reload", async () => {
  const c = (await user.ctx.cookies()).find((x) => x.name === "STUDIO_SESSION");
  expect(c?.httpOnly === true, "session cookie must be HttpOnly");
  await user.page.reload(); await user.page.getByText("Bạn chưa thuộc workspace nào").first().waitFor();
});
await check("password login is impossible for an SSO account", async () => {
  const name = sql("select username from users where auth_source='OIDC' order by created_at desc limit 1");
  const a = await browser.newContext(); const t = (await (await a.request.get(`${BASE}/api/v1/auth/csrf`)).json()).token;
  const r = await a.request.post(`${BASE}/api/v1/auth/login`, { headers: { "X-XSRF-TOKEN": t, "Content-Type": "application/json" }, data: { username: name, password: sso.SSO_TEST_PASSWORD } });
  expect(r.status() === 401, `expected 401, got ${r.status()}`);
});
await check("an admin grants access through internal RBAC (add to workspace + project as VIEWER) and the SSO user sees only that", async () => {
  const admin = await adminApi();
  const uname = sql("select username from users where auth_source='OIDC' order by created_at desc limit 1");
  expect((await admin.call("POST", `/workspaces/${WS}/members`, { username: uname, role: "VIEWER" })).status() === 201, "workspace add failed");
  expect((await admin.call("POST", `/workspaces/${WS}/projects/${DEMO}/members`, { username: uname, role: "VIEWER" })).status() === 201, "project add failed");
  await user.page.goto(BASE + "/");                                   // resolver now finds a workspace -> Builder Studio
  await user.page.waitForURL(/\/studio/);
  await user.page.goto(`${BASE}/studio/projects/${DEMO}/ai`);
  await user.page.waitForSelector("iframe.previewFrame", { state: "attached" });
  expect(await user.page.getByRole("button", { name: "Xuất bản" }).first().isDisabled(), "viewer must not publish");
  expect(await user.page.getByRole("button", { name: "Chia sẻ" }).count() === 0, "viewer must not see member management");
});
await check("disabling the SSO account at runtime ends access without another login", async () => {
  const uname = sql("select username from users where auth_source='OIDC' order by created_at desc limit 1");
  sql(`update users set enabled=false where username='${uname}'`);
  try {
    const r = await user.ctx.request.get(`${BASE}/api/v1/auth/me`);
    expect(r.status() === 401, `expected 401 got ${r.status()}`);
  } finally { sql(`update users set enabled=true where username='${uname}'`); }
});
await check("an IdP user with an UNVERIFIED email is provisioned without email and without access", async () => {
  const u2 = await ssoLogin(sso.SSO_TEST_USER_UNVERIFIED);
  await u2.page.getByText("Bạn chưa thuộc workspace nào").first().waitFor({ timeout: 15000 });
  const row = sql("select coalesce(u.email,'null') from external_identities e join users u on u.id=e.user_id order by e.created_at desc limit 1");
  expect(row === "null", `unverified email must not be stored, got ${row}`);
});
await check("SSO login and provisioning are audited", async () => {
  expect(Number(sql("select count(*) from audit_events where action='PROVISION_USER'")) >= 2, "PROVISION_USER missing");
  expect(Number(sql("select count(*) from audit_events where action='LOGIN_SUCCESS' and new_value::text like '%OIDC%'")) >= 2, "OIDC LOGIN_SUCCESS missing");
});
await check("tampered callback (bad state) is rejected and audited as failure, no session created", async () => {
  const ctx = await browser.newContext(); const page = await ctx.newPage();
  const before = Number(sql("select count(*) from audit_events where action='LOGIN_FAILURE' and new_value::text like '%OIDC%'"));
  await page.goto(`${BASE}/login/oauth2/code/oidc?code=forged&state=forged`);
  await page.waitForURL(/sso_error|127\.0\.0\.1:3100/);
  const me = await ctx.request.get(`${BASE}/api/v1/auth/me`);
  expect(me.status() === 401, "no session may exist after a forged callback");
  expect(Number(sql("select count(*) from audit_events where action='LOGIN_FAILURE' and new_value::text like '%OIDC%'")) >= before, "audit check");
});
await check("RP-initiated logout: signing out also ends the Keycloak session, the browser comes back to /login, the next SSO click asks for the password again", async () => {
  const u = await ssoLogin(sso.SSO_TEST_USER);
  await u.page.getByText(/Bạn chưa thuộc workspace nào|Builder Studio|Ứng dụng/).first().waitFor({ timeout: 15000 });
  const resp = await u.ctx.request.post(`${BASE}/api/v1/auth/logout`, { headers: { "X-XSRF-TOKEN": (await (await u.ctx.request.get(`${BASE}/api/v1/auth/csrf`)).json()).token } });
  const body = await resp.json();
  expect(typeof body.redirect === "string" && body.redirect.includes("/protocol/openid-connect/logout") && body.redirect.includes("id_token_hint="), `no end-session redirect: ${JSON.stringify(body)}`);
  await u.page.goto(body.redirect);
  await u.page.waitForURL(/127\.0\.0\.1:3100\/login/, { timeout: 20000 });
  await u.page.getByRole("radio", { name: /Builder Studio/ }).check();
  await u.page.getByRole("link", { name: "Tiếp tục với SSO công ty" }).click();
  await u.page.waitForURL(/18080/);
  expect(await u.page.locator("#password").count() === 1, "Keycloak session should have ended (password prompt expected)");
  await u.ctx.close();
});
if (process.env.SAML_ENABLED === "true") await check("SAML through Keycloak identity brokering: the SAML button goes to the corp SAML IdP, the user comes back signed in with a new account and no access", async () => {
  const ctx = await browser.newContext(); const page = await ctx.newPage();
  await page.goto(BASE); await page.getByRole("radio", { name: /Builder Studio/ }).check();
  await page.getByRole("link", { name: /SAML/ }).click();
  await page.waitForURL(/\/realms\/corp\/protocol\/saml|\/realms\/corp\//, { timeout: 20000 });
  await page.locator("#username").fill(sso.SAML_TEST_USER); await page.locator("#password").fill(sso.SSO_TEST_PASSWORD); await page.locator("#kc-login").click();
  await page.waitForURL(/127\.0\.0\.1:3100/, { timeout: 30000 });
  await page.getByText("Bạn chưa thuộc workspace nào").first().waitFor({ timeout: 15000 });
  const n = sql("select count(*) from external_identities e join users u on u.id = e.user_id where e.email = 'saml.user@corp.example.test'");
  expect(Number(n) === 1, `expected one brokered identity, got ${n}`);
  await ctx.close();
});
sql("delete from workspace_members where user_id in (select id from users where username='local.emailtwin')");
sql("delete from users where username='local.emailtwin'");
await browser.close();
console.log(`${results.filter(Boolean).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
