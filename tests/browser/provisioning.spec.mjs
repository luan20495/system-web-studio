// @class: harness — real Chromium on the create-account screens with an in-page FAKE transport behind the REAL adapter (no backend). Proves what the SCREENS do (gating, validation, states, no invented calls).
// NOT a backend E2E: the real chain is tests/e2e-real SUPER01 / ADMIN01 / USER01 / SEC01-03.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/provisioning.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
const T = (p, id) => p.getByTestId(id);
const closeLink = async (p) => { await p.getByRole("button", { name: "Xong" }).click(); await p.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click(); };   // the one-time link dialog asks before it closes until the link was copied (M-007)
async function open(s) {
  const p = await browser.newPage({ viewport: { width: 900, height: 1100 } }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/prov.html?s=${s}`); await p.waitForTimeout(300); return p;
}
const calls = (p) => p.evaluate(() => window.__prov);
const fill = async (p, o = {}) => { await T(p, "acc-username").fill(o.username ?? "bao.nguyen"); await T(p, "acc-display").fill(o.display ?? "Bảo Nguyễn"); await T(p, "acc-email").fill(o.email ?? ""); await T(p, "acc-workspace").selectOption(o.ws ?? "w1"); };
const opts = (p, id) => T(p, id).locator("option").evaluateAll((l) => l.map((o) => [o.value, o.textContent, o.disabled]));

// ===================================================================================================================== PLATFORM (SYSTEM_ADMIN)
const pick = async (p, o = {}) => { await T(p, "acc-tenant").selectOption(o.tenant ?? "t1"); await T(p, "acc-type").selectOption(o.type ?? "USER"); await fill(p, o); };
{ const p = await open("platform");
  check("PUI01 SYSTEM_ADMIN opens the create-account dialog: four sections, a tenant selector, no NOT_READY notice, submit enabled", (await T(p, "create-account").count()) === 1 && (await p.locator("fieldset legend").allInnerTexts()).length === 4 && (await T(p, "acc-tenant").count()) === 1 && (await T(p, "prov-not-ready").count()) === 0 && (await T(p, "acc-submit").isEnabled()));
  await T(p, "acc-submit").click();
  const u = await T(p, "acc-username-error").innerText(); const t0 = await p.getByText("Hãy chọn công ty.").count();
  await T(p, "acc-username").fill("Bad Name!"); await T(p, "acc-email").fill("nope"); await T(p, "acc-submit").click();
  check("PUI02 validation: username / display name / email / tenant are refused in the form and NOTHING is sent", /3–40 ký tự/.test(u) && t0 === 1 && (await p.getByText("Email không hợp lệ.").count()) === 1 && (await calls(p)).length === 0);
  const wsDisabled = await T(p, "acc-workspace").isDisabled(); const tenantOpts = await opts(p, "acc-tenant");
  check("PUI03 tenant selector: the real tenants, required for every type; the workspace list stays disabled until a tenant is chosen", tenantOpts.some((o) => o[0] === "t1") && tenantOpts.some((o) => o[0] === "t2") && wsDisabled);
  await T(p, "acc-tenant").selectOption("t1");
  const wsOpts = await opts(p, "acc-workspace");
  check("PUI04 workspace: optional for a user ('Không gán workspace'), the known workspaces, and '+ Tạo workspace mới của công ty này'; the role is disabled while there is no workspace", wsOpts[0][0] === "" && /Không gán workspace/.test(wsOpts[0][1]) && wsOpts.some((o) => o[0] === "w1") && wsOpts.some((o) => o[0] === "__new") && (await T(p, "acc-role").isDisabled()));
  await T(p, "acc-workspace").selectOption("__new"); check("PUI04b the new-workspace name field appears", (await T(p, "acc-new-ws").count()) === 1);
  await T(p, "acc-submit").click(); check("PUI04c a new workspace without a name is refused", (await p.getByText("Hãy nhập tên workspace mới.").count()) === 1 && (await calls(p)).length === 0);
  await T(p, "acc-type").selectOption("WORKSPACE_ADMIN"); await T(p, "acc-workspace").selectOption("");
  await fill(p, { ws: "" }); await T(p, "acc-submit").click();
  check("PUI04d the workspace-admin type REQUIRES a workspace (message, nothing sent)", (await p.getByText("Quản trị workspace cần một workspace.").count()) === 1 && (await calls(p)).length === 0);
  const typeOpts = (await opts(p, "acc-type")).map((o) => o[0]);
  await T(p, "acc-type").selectOption("USER"); await T(p, "acc-workspace").selectOption("w1"); const userRoles = (await opts(p, "acc-role")).map((o) => o[0]);
  await T(p, "acc-type").selectOption("WORKSPACE_ADMIN"); const wsaRoles = (await opts(p, "acc-role")).map((o) => o[0]);
  await T(p, "acc-type").selectOption("TENANT_ADMIN"); const taRole = await T(p, "acc-tenant-role").innerText();
  check("PUI05 types / roles: three types and NO SYSTEM_ADMIN; user → Biên tập viên / Người xuất bản / Người xem; workspace admin → only the admin role; tenant admin shows 'Quản trị công ty'", JSON.stringify(typeOpts) === JSON.stringify(["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]) && JSON.stringify(userRoles) === JSON.stringify(["EDITOR", "PUBLISHER", "VIEWER"]) && JSON.stringify(wsaRoles) === JSON.stringify(["WORKSPACE_ADMIN"]) && /Quản trị công ty/.test(taRole) && !(await p.locator("body").innerText()).includes("SYSTEM_ADMIN"));
  await p.close(); }
{ const p = await open("platform"); await pick(p, { type: "TENANT_ADMIN", ws: "" });
  await T(p, "acc-submit").click(); await T(p, "acc-submit").waitFor({ state: "detached", timeout: 3000 }).catch(() => undefined);
  const linkShown = (await p.getByText("Liên kết kích hoạt").count()) > 0; const linkInput = await p.locator('input[aria-label="Liên kết"]').inputValue();
  await closeLink(p); await T(p, "account-created").waitFor();
  const sent = await calls(p);
  check("PUI09 success (tenant admin, no workspace): ONE call createTenantUser(t1, {username, displayName, tenantRole}) — the tenant is the PATH, the body has no tenant, no workspaceId, no workspaceRole", sent.length === 1 && sent[0].name === "createTenantUser" && sent[0].args[0] === "t1" && JSON.stringify(Object.keys(sent[0].args[1]).sort()) === JSON.stringify(["displayName", "tenantRole", "username"]) && sent[0].args[1].tenantRole === "TENANT_ADMIN", JSON.stringify(sent));
  check("PUI09b the activation link is shown ONCE (dialog state only) and then gone: the summary has no token, the page URL and storage hold none", linkShown && /\/auth\/activate#/.test(linkInput) && !(await p.locator("body").innerHTML()).includes("x".repeat(43)) && !(await p.evaluate(() => JSON.stringify([localStorage, sessionStorage, location.href]))).includes("x".repeat(43)));
  check("PUI09c the summary: account, Chờ kích hoạt, the company AND its role (already done), 'Chưa gán workspace'; the only next step is the person's activation", /bao.nguyen/.test(await T(p, "res-account").innerText()) && /Chờ kích hoạt/.test(await T(p, "res-status").innerText()) && /Acme · Quản trị công ty/.test(await T(p, "res-tenant").innerText()) && /Chưa gán workspace/.test(await T(p, "res-workspace").innerText()) && (await T(p, "res-pending").locator("li").count()) === 1);
  await p.close(); }
{ const p = await open("platform"); await pick(p, { type: "USER", ws: "w1" }); await T(p, "acc-role").selectOption("PUBLISHER");
  await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const c = await calls(p);
  check("WORKSPACE ASSIGNMENT: workspaceId and workspaceRole travel TOGETHER in the body (here w1 + PUBLISHER); the summary names both", c.length === 1 && c[0].args[1].workspaceId === "w1" && c[0].args[1].workspaceRole === "PUBLISHER" && c[0].args[1].tenantRole === "MEMBER" && /Kinh doanh · Người xuất bản/.test(await T(p, "res-workspace").innerText()), JSON.stringify(c[0]?.args));
  await p.close(); }
{ const p = await open("platform"); await pick(p, { type: "WORKSPACE_ADMIN", ws: "" }); await T(p, "acc-workspace").selectOption("__new"); await T(p, "acc-new-ws").fill("Phòng mới");
  await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const c = await calls(p);
  check("CREATE WORKSPACE: the TENANT route first — createTenantWorkspace('t1', name) — then createTenantUser with that workspace and WORKSPACE_ADMIN; there is no call to the legacy workspace route", c.length === 2 && c[0].name === "createTenantWorkspace" && c[0].args[0] === "t1" && c[0].args[1] === "Phòng mới" && c[1].name === "createTenantUser" && c[1].args[1].workspaceId === "w-new" && c[1].args[1].workspaceRole === "WORKSPACE_ADMIN" && !c.some((x) => /createWorkspace$/.test(x.name)), JSON.stringify(c.map((x) => x.name)));
  await p.close(); }
{ const p = await open("platform-wsfail"); await pick(p, { type: "WORKSPACE_ADMIN", ws: "", username: "taken" }); await T(p, "acc-workspace").selectOption("__new"); await T(p, "acc-new-ws").fill("Phòng X");
  await T(p, "acc-submit").click(); await T(p, "acc-username-error").waitFor();
  const kept = await T(p, "acc-workspace").inputValue(); await T(p, "acc-username").fill("another.one"); await T(p, "acc-submit").click(); await closeLink(p).catch(() => undefined);
  const c = await calls(p);
  check("RETRY SAFETY: when the account step fails after the workspace was created, the workspace is kept selected and a retry does NOT create a second one", kept === "w-new" && c.filter((x) => x.name === "createTenantWorkspace").length === 1 && c.filter((x) => x.name === "createTenantUser").length === 2, JSON.stringify(c.map((x) => x.name)));
  await p.close(); }
{ const p = await open("platform-notready"); await pick(p, { ws: "" });
  check("PUI06 the NOT_READY mechanism is still honoured for a capability the backend lacks: notice, submit DISABLED, a forced click sends nothing", (await T(p, "prov-not-ready").count()) === 1 && (await T(p, "acc-submit").isDisabled()) && (await T(p, "acc-submit").click({ force: true, timeout: 800 }).catch(() => "x"), (await calls(p)).length === 0));
  await p.close(); }
const ERR = [["platform-403", "PUI07 403 FORBIDDEN", "forbidden", /không có quyền/], ["platform-dup", "PUI08 409 USERNAME_TAKEN", "duplicate", /đã tồn tại/], ["platform-dupmail", "PUI08b 409 EMAIL_TAKEN", "duplicate", /Email này đã được dùng/],
  ["platform-ws404", "404 WORKSPACE_NOT_FOUND (workspace of another tenant)", "mismatch", /không thuộc công ty này/], ["platform-tenant404", "404 TENANT_NOT_FOUND", "mismatch", /Không tìm thấy công ty/], ["platform-down", "backend unreachable", "unavailable", /Chưa rõ thao tác đã được ghi/],
  ["platform-503", "503", "unavailable", /Máy chủ chưa sẵn sàng/], ["platform-invalid", "400 INVALID_USERNAME", "validation", /3–40 ký tự/], ["platform-disabled", "422 USER_DISABLED", "disabled", /đang bị khóa/],
  ["platform-validation", "400 VALIDATION_FAILED", "validation", /workspace phải đi cùng vai trò/], ["platform-role", "400 TENANT_ROLE_INVALID", "validation", /Thành viên hoặc Quản trị công ty/], ["platform-selfgrant", "403 SELF_GRANT_FORBIDDEN", "protection", /tự cấp quyền/]];
for (const [s, name, kind, re] of ERR) {
  const p = await open(s); await pick(p, { ws: "" }); await T(p, "acc-submit").click(); await p.waitForTimeout(400);
  const onField = ["platform-dup", "platform-invalid"].includes(s) ? await T(p, "acc-username-error").innerText().catch(() => "") : s === "platform-dupmail" ? await T(p, "acc-email-error").innerText().catch(() => "") : "";
  const wsField = s === "platform-ws404" ? await p.locator('[role="alert"]').filter({ hasText: re }).count() : 0;
  const box = onField || (await T(p, "prov-problem").innerText().catch(() => ""));
  check(`${name}: shown by its code${onField ? " on the field" : ""}, in Vietnamese, not a generic message; the form stays editable`, re.test(box || "") && !/Something went wrong|english/i.test(box) && (await T(p, "acc-submit").isEnabled()) && (onField || (await T(p, "prov-problem").getAttribute("data-kind")) === kind) && (s !== "platform-ws404" || wsField >= 1), (box || "").slice(0, 110));
  await p.close(); }

// ===================================================================================================================== ADMIN (tenant admin / workspace admin)
{ const p = await open("admin-tenant");
  check("AUI01 Tenant Admin: Người dùng shows the tenant, the create-account action (ENABLED) and add-existing", (await T(p, "people").count()) === 1 && (await T(p, "people-create").isEnabled()) && (await T(p, "add-existing").count()) === 1 && (await T(p, "people-not-ready").count()) === 0);
  check("AUI02 the tenant is the session's: read-only, named, not editable", (await T(p, "people-tenant").getAttribute("readonly")) !== null && (await T(p, "people-tenant").inputValue()) === "Acme");
  await T(p, "people-create").click(); await T(p, "create-account").waitFor();
  const types = (await opts(p, "acc-type")).map((o) => o[0]); const ws = (await opts(p, "acc-workspace")).map((o) => o[0]);
  check("AUI03 workspace selection: only the caller's OWN workspaces of THIS tenant (w1, w2 — never the other tenant's wx), 'none', and 'create a workspace of the company'", JSON.stringify(ws.sort()) === JSON.stringify(["", "__new", "w1", "w2"]) && !ws.includes("wx"));
  check("AUI04 no cross-tenant option: no tenant selector, the fixed read-only tenant is Acme, Beta is nowhere", (await T(p, "acc-tenant").count()) === 0 && (await T(p, "acc-tenant-fixed").inputValue()) === "Acme" && !(await p.locator("body").innerText()).includes("Beta"));
  check("AUI05 no SYSTEM_ADMIN option: types = Quản trị công ty / Quản trị workspace / Người dùng (a tenant admin may create tenant admins)", JSON.stringify(types) === JSON.stringify(["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]) && !(await p.locator("body").innerText()).includes("SYSTEM_ADMIN"));
  await fill(p, { username: "tom.le", display: "Tom Lê", ws: "w2", email: "tom@example.com" }); await T(p, "acc-type").selectOption("USER"); await T(p, "acc-workspace").selectOption("w2"); await T(p, "acc-role").selectOption("PUBLISHER");
  await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const c = await calls(p);
  check("AUI01c create user: ONE call createTenantUser('t1' = path, {username, displayName, email, tenantRole MEMBER, workspaceId w2, workspaceRole PUBLISHER}); the tenant id is NOT in the body", c.length === 1 && c[0].args[0] === "t1" && JSON.stringify(Object.keys(c[0].args[1]).sort()) === JSON.stringify(["displayName", "email", "tenantRole", "username", "workspaceId", "workspaceRole"]) && c[0].args[1].tenantRole === "MEMBER", JSON.stringify(c[0]?.args));
  await p.close(); }
{ const p = await open("admin-tenant2"); await T(p, "people-create").click(); await T(p, "create-account").waitFor();
  const t = (await opts(p, "acc-tenant")).map((o) => o[0]);
  check("AUI04b a tenant admin of SEVERAL tenants chooses among THEIR OWN tenants only (Acme, Công ty C) — the tenant where they are a plain member (Beta) is not offered", JSON.stringify(t.sort()) === JSON.stringify(["", "t1", "t3"]) && !(await p.locator("body").innerText()).includes("Beta"));
  await p.close(); }
{ const p = await open("admin-wsadmin");
  check("AUI06 Workspace Admin (MEMBER_MANAGE only): no create-account action (the reason is shown), only add-existing", (await T(p, "people-create").count()) === 0 && /không có quyền tạo tài khoản mới/.test(await T(p, "people-forbidden").innerText()) && (await T(p, "add-existing").count()) === 1 && (await T(p, "people-no-tenant").count()) === 1);
  await T(p, "ae-who").fill("tom.le"); await T(p, "ae-role").selectOption("VIEWER"); await T(p, "ae-submit").click(); await T(p, "ae-msg").waitFor();
  const ok = (await T(p, "ae-msg").innerText()); const c = await calls(p);
  check("AUI07 add an existing member still works: ONE call to the member route with the username and role, success shown", /Đã thêm vào workspace/.test(ok) && c.length === 1 && c[0].name === "addWorkspaceMember" && c[0].args[0] === "w1" && c[0].args[1].username === "tom.le" && c[0].args[2] === "VIEWER");
  const kinds = [];
  for (const who of ["taken", "ghost", "off"]) { await T(p, "ae-who").fill(who); await T(p, "ae-submit").click(); await p.waitForTimeout(300); kinds.push(await T(p, "ae-msg").getAttribute("data-kind")); }
  check("AUI07b the member route's refusals are shown by code: ALREADY_MEMBER (duplicate), USER_NOT_FOUND (mismatch), USER_DISABLED (disabled)", JSON.stringify(kinds) === JSON.stringify(["duplicate", "mismatch", "disabled"]), kinds.join(","));
  await p.close(); }
{ const p = await open("admin-claims-role-only");
  check("AUI06b a person whose workspace row says role WORKSPACE_ADMIN but whose server permissions lack MEMBER_MANAGE gets NOTHING (a role name grants nothing)", (await T(p, "people-create").count()) === 0 && (await T(p, "add-existing").count()) === 0 && (await T(p, "add-existing-unavailable").count()) === 1);
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
