// @class: harness — real Chromium on the create-account screens with an in-page FAKE transport behind the REAL adapter (no backend). Proves what the SCREENS do (gating, validation, states, no invented calls).
// NOT a backend E2E: the real chain is tests/e2e-real SUPER01 / ADMIN01 / USER01 / SEC01-03.
// Run: node tests/browser/build-harness.mjs && (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &) && CHROME=... node tests/browser/provisioning.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
const T = (p, id) => p.getByTestId(id);
async function open(s) {
  const p = await browser.newPage({ viewport: { width: 900, height: 1100 } }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/prov.html?s=${s}`); await p.waitForTimeout(300); return p;
}
const calls = (p) => p.evaluate(() => window.__prov);
const fill = async (p, o = {}) => { await T(p, "acc-username").fill(o.username ?? "bao.nguyen"); await T(p, "acc-display").fill(o.display ?? "Bảo Nguyễn"); await T(p, "acc-email").fill(o.email ?? ""); await T(p, "acc-workspace").selectOption(o.ws ?? "w1"); };
const opts = (p, id) => T(p, id).locator("option").evaluateAll((l) => l.map((o) => [o.value, o.textContent, o.disabled]));

// ===================================================================================================================== PLATFORM
{ const p = await open("platform");
  check("PUI01 SYSTEM_ADMIN opens the create-account dialog: four sections, no NOT_READY notice, submit enabled", (await T(p, "create-account").count()) === 1 && (await p.locator("fieldset legend").allInnerTexts()).length === 4 && (await T(p, "prov-not-ready").count()) === 0 && (await T(p, "acc-submit").isEnabled()));
  await T(p, "acc-submit").click();
  const u = await T(p, "acc-username-error").innerText(); const ws0 = await p.getByText("Hãy chọn workspace.").count();
  await T(p, "acc-username").fill("Bad Name!"); await T(p, "acc-email").fill("nope"); await T(p, "acc-submit").click();
  check("PUI02 validation: username / display name / email / workspace are refused in the form and NOTHING is sent", /3–40 ký tự/.test(u) && ws0 === 1 && (await p.getByText("Email không hợp lệ.").count()) === 1 && (await calls(p)).length === 0);
  await T(p, "acc-type").selectOption("TENANT_ADMIN");
  const tenantOpts = await opts(p, "acc-tenant");
  check("PUI03 tenant selector: appears for the tenant-admin type, lists the real tenants, required", tenantOpts.some((o) => o[0] === "t1") && tenantOpts.some((o) => o[0] === "t2") && (await T(p, "acc-submit").isEnabled()));
  await fill(p); await T(p, "acc-submit").click(); const noTenant = await p.getByText("Hãy chọn công ty.").count();
  check("PUI03b tenant-admin without a tenant is refused", noTenant === 1 && (await calls(p)).length === 0);
  const wsOpts = await opts(p, "acc-workspace");
  check("PUI04 workspace selector: the workspaces, plus '+ Tạo workspace mới' for a SYSTEM_ADMIN; the new-workspace name field appears on choosing it", wsOpts.some((o) => o[0] === "w1") && wsOpts.some((o) => o[0] === "__new"));
  await T(p, "acc-workspace").selectOption("__new"); check("PUI04b …the name field", (await T(p, "acc-new-ws").count()) === 1); await T(p, "acc-workspace").selectOption("w1");
  const typeOpts = (await opts(p, "acc-type")).map((o) => o[0]);
  await T(p, "acc-type").selectOption("USER"); const userRoles = (await opts(p, "acc-role")).map((o) => o[0]);
  await T(p, "acc-type").selectOption("WORKSPACE_ADMIN"); const wsaRoles = (await opts(p, "acc-role")).map((o) => o[0]);
  check("PUI05 account types / roles: three types, NO SYSTEM_ADMIN; user → Biên tập viên / Người xuất bản / Người xem; workspace admin → only the admin role", JSON.stringify(typeOpts) === JSON.stringify(["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]) && JSON.stringify(userRoles) === JSON.stringify(["EDITOR", "PUBLISHER", "VIEWER"]) && JSON.stringify(wsaRoles) === JSON.stringify(["WORKSPACE_ADMIN"]) && !(await p.locator("body").innerText()).includes("SYSTEM_ADMIN"));
  await T(p, "acc-type").selectOption("TENANT_ADMIN"); await T(p, "acc-tenant").selectOption("t1");
  await T(p, "acc-submit").click(); await T(p, "account-created").waitFor({ timeout: 5000 }).catch(() => undefined);
  // the activation link box comes first
  const linkShown = (await p.getByText("Liên kết kích hoạt").count()) > 0; await p.getByRole("button", { name: "Xong" }).click(); await T(p, "account-created").waitFor();
  const sent = await calls(p);
  check("PUI09 success: ONE call (createUser) with the normalised body, the activation link shown once, then the account / status / workspace / role / tenant summary", linkShown && sent.length === 1 && sent[0].name === "createUser" && JSON.stringify(Object.keys(sent[0].args[0]).sort()) === JSON.stringify(["displayName", "role", "username", "workspaceId"]) && sent[0].args[0].role === "WORKSPACE_ADMIN" && /bao.nguyen/.test(await T(p, "res-account").innerText()) && /Chờ kích hoạt/.test(await T(p, "res-status").innerText()) && /Kinh doanh/.test(await T(p, "res-workspace").innerText()), JSON.stringify(sent[0]?.args[0]));
  const pend = await T(p, "res-pending").innerText();
  check("PUI09b a tenant admin's company role is reported as the NEXT step (after activation), never as done; no tenant id was sent", /kích hoạt/.test(pend) && /Quản trị công ty/.test(pend) && /Acme/.test(pend) && !JSON.stringify(sent).includes("t1") && /Chưa gán/.test(await T(p, "res-tenant").innerText()));
  await p.close(); }
{ const p = await open("platform-notready"); await fill(p);
  check("PUI06 provisioning NOT_READY: a notice, the submit is DISABLED with the reason, and a forced click sends nothing", (await T(p, "prov-not-ready").count()) === 1 && (await T(p, "acc-submit").isDisabled()) && /Backend provisioning chưa sẵn sàng/.test(await T(p, "prov-not-ready").innerText()) && (await T(p, "acc-submit").click({ force: true, timeout: 800 }).catch(() => "x"), (await calls(p)).length === 0));
  await p.close(); }
for (const [s, name, kind, re] of [["platform-403", "PUI07 403", "forbidden", /chỉ dành cho quản trị hệ thống/], ["platform-dup", "PUI08 409 USERNAME_TAKEN", "duplicate", /đã tồn tại/], ["platform-dupmail", "PUI08b 409 EMAIL_TAKEN", "duplicate", /Email này đã được dùng/], ["platform-ws404", "workspace mismatch (404 WORKSPACE_NOT_FOUND)", "mismatch", /Không tìm thấy workspace/], ["platform-down", "backend unreachable", "unavailable", /Chưa rõ thao tác đã được ghi/], ["platform-503", "503", "unavailable", /Máy chủ chưa sẵn sàng/], ["platform-invalid", "400 INVALID_USERNAME from the server", "validation", /3–40 ký tự/]]) {
  const p = await open(s); await T(p, "acc-type").selectOption("USER"); await fill(p); await T(p, "acc-submit").click(); await p.waitForTimeout(400);
  const dupField = ["platform-dup", "platform-invalid"].includes(s) ? await T(p, "acc-username-error").innerText().catch(() => "") : s === "platform-dupmail" ? await T(p, "acc-email-error").innerText().catch(() => "") : null;
  const box = dupField !== null && dupField !== "" ? dupField : (await T(p, "prov-problem").getAttribute("data-kind") === kind ? await T(p, "prov-problem").innerText() : "");
  const onField = dupField !== null && dupField !== "";
  check(`${name}: shown by its code${onField ? " on the field" : ""}, not a generic message; the form stays editable`, re.test(box) && !/Something went wrong/i.test(box) && (await T(p, "acc-submit").isEnabled()) && ((onField) || (await T(p, "prov-problem").getAttribute("data-kind")) === kind), box.slice(0, 120));
  await p.close(); }

// ===================================================================================================================== ADMIN
{ const p = await open("admin-tenant");
  check("AUI01 Tenant Admin: the Người dùng screen shows the tenant, the create-account action and add-existing", (await T(p, "people").count()) === 1 && (await T(p, "people-create").count()) === 1 && (await T(p, "add-existing").count()) === 1);
  check("AUI02 the tenant is the session's: read-only, not editable, named", (await T(p, "people-tenant").getAttribute("readonly")) !== null && (await T(p, "people-tenant").inputValue()) === "Acme");
  check("AUI01b create is NOT_READY today: the button is disabled and the reason is shown (no fake success)", (await T(p, "people-create").isDisabled()) && /Backend provisioning chưa sẵn sàng/.test(await T(p, "people-not-ready").innerText()));
  await p.close(); }
{ const p = await open("admin-tenant-ready");
  await T(p, "people-create").click(); await T(p, "create-account").waitFor();
  const types = (await opts(p, "acc-type")).map((o) => o[0]); const ws = (await opts(p, "acc-workspace")).map((o) => o[0]);
  check("AUI03 workspace selection: only the caller's own workspaces, no 'create workspace' option", JSON.stringify(ws.sort()) === JSON.stringify(["", "w1", "w2"]) && !ws.includes("__new"));
  check("AUI04 no cross-tenant option: the tenant is a read-only field with the caller's tenant, there is no tenant selector at all (and Beta is nowhere)", (await T(p, "acc-tenant").count()) === 0 && (await T(p, "acc-tenant-fixed").inputValue()) === "Acme" && !(await p.locator("body").innerText()).includes("Beta"));
  check("AUI05 no SYSTEM_ADMIN option, and no tenant-admin type for a tenant admin: types = Quản trị workspace / Người dùng", JSON.stringify(types) === JSON.stringify(["WORKSPACE_ADMIN", "USER"]) && !(await p.locator("body").innerText()).includes("SYSTEM_ADMIN"));
  await fill(p, { username: "tom.le", display: "Tom Lê", ws: "w2" }); await T(p, "acc-type").selectOption("USER"); await T(p, "acc-role").selectOption("PUBLISHER");
  await T(p, "acc-submit").click(); await p.getByRole("button", { name: "Xong" }).click(); await T(p, "account-created").waitFor();
  const c = await calls(p);
  check("AUI01c with a (simulated) tenant contract READY the form is complete: one call, workspace + role from the form, the tenant id is NOT sent (the server derives it)", c.length === 1 && c[0].args[0].workspaceId === "w2" && c[0].args[0].role === "PUBLISHER" && !JSON.stringify(c).includes("t1") && !JSON.stringify(c).includes("tenant"), JSON.stringify(c[0]?.args));
  await p.close(); }
{ const p = await open("admin-wsadmin");
  check("AUI06 Workspace Admin (MEMBER_MANAGE only): no create-account action (the reason is shown), only add-existing", (await T(p, "people-create").count()) === 0 && /không có quyền tạo tài khoản mới/.test(await T(p, "people-forbidden").innerText()) && (await T(p, "add-existing").count()) === 1 && (await T(p, "people-no-tenant").count()) === 1);
  await T(p, "ae-who").fill("tom.le"); await T(p, "ae-role").selectOption("VIEWER"); await T(p, "ae-submit").click(); await T(p, "ae-msg").waitFor();
  const ok = (await T(p, "ae-msg").innerText()); const c = await calls(p);
  check("AUI07 add an existing member still works: ONE call to the member route with the username and role, success shown", /Đã thêm vào workspace/.test(ok) && c.length === 1 && c[0].name === "addWorkspaceMember" && c[0].args[0] === "w1" && c[0].args[1].username === "tom.le" && c[0].args[2] === "VIEWER");
  await T(p, "ae-who").fill("taken"); await T(p, "ae-submit").click(); await p.waitForTimeout(300);
  const dup = await T(p, "ae-msg").getAttribute("data-kind"); await T(p, "ae-who").fill("ghost"); await T(p, "ae-submit").click(); await p.waitForTimeout(300);
  check("AUI07b the member route's refusals are shown by code: ALREADY_MEMBER (duplicate), USER_NOT_FOUND (mismatch)", dup === "duplicate" && (await T(p, "ae-msg").getAttribute("data-kind")) === "mismatch");
  await p.close(); }
{ const p = await open("admin-claims-role-only");
  check("AUI06b a person whose workspace row says role WORKSPACE_ADMIN but whose server permissions lack MEMBER_MANAGE gets NOTHING (a role name grants nothing)", (await T(p, "people-create").count()) === 0 && (await T(p, "add-existing").count()) === 0 && (await T(p, "add-existing-unavailable").count()) === 1);
  await p.close(); }
{ const p = await open("admin-tenant"); const n0 = (await calls(p)).length;
  await T(p, "people-create").click({ force: true, timeout: 800 }).catch(() => undefined); await p.waitForTimeout(300);
  check("a disabled create button opens nothing and sends nothing (NOT_READY is never a silent success)", (await T(p, "create-account").count()) === 0 && (await calls(p)).length === n0);
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
