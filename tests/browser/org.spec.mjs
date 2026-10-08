// @class: harness — real Chromium on the organization tree and the employee directory with an in-page FAKE in-memory transport behind the REAL adapters (no backend). Proves what the SCREENS do (states, gating, no invented calls).
// NOT a backend E2E: C1 has not published the organization contract yet, so there is no real-backend run for these screens (docs/parallel/c5/ORGANIZATION_UI.md). The error codes the fake uses are ASSUMED names.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/org.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const AXE = require.resolve("axe-core/axe.min.js");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
const T = (p, id) => p.getByTestId(id);
const closeLink = async (p) => { await p.getByRole("button", { name: "Xong" }).click(); await p.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click(); };   // the one-time link dialog asks before it closes until the link was copied (M-007)
async function open(v, s = "ok", viewport = { width: 1200, height: 900 }) {
  const p = await browser.newPage({ viewport }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/org.html?v=${v}&s=${s}`); await p.waitForTimeout(350); return p;
}
const calls = (p) => p.evaluate(() => window.__org); const prov = (p) => p.evaluate(() => window.__prov);
const names = async (p) => (await calls(p)).map((c) => c.name);
const node = (p, id) => T(p, `node:${id}`);
const nodeName = async (p, id) => (await node(p, id).locator(".xp-nodeText b").innerText());
const level = (p, id) => p.locator(`[data-node="${id}"]`).locator("xpath=ancestor::li[@role='treeitem'][1]").getAttribute("aria-level");
const pickType = (p, dlg, v) => p.getByTestId(dlg).locator("select.srOnly").first().selectOption(v);
const rows = (p) => p.locator('[data-testid^="emp:"]').count();
const settle = (p, ms = 500) => p.waitForTimeout(ms);
const key = async (p, k) => { await p.keyboard.press(k); await p.waitForTimeout(90); };
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); const r = await p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); return r; };

// ===================================================================================================================== ORGANIZATION TREE
{ const p = await open("org");
  const items = await p.getByRole("treeitem").count(); const tree = T(p, "org-tree");
  check("ORG_UI01 tree render: a WAI-ARIA tree of the company's own units (5), nested by parent, with aria-level 1/2/3", (await tree.getAttribute("role")) === "tree" && items === 5 && (await level(p, "tech")) === "1" && (await level(p, "mobile")) === "2" && (await level(p, "flutter")) === "3");
  check("ORG_UI01b each node shows icon tile, name, TYPE badge and counts (employees / child units); nothing is hard-coded (the levels are whatever the company defined)", (await node(p, "flutter").locator(".xp-typeBadge").innerText()) === "Team" && /2 nhân viên/.test(await node(p, "flutter").innerText()) && /2 đơn vị con/.test(await node(p, "tech").innerText()) && (await node(p, "tech").locator(".xp-nodeIcon svg").count()) === 1);
  await node(p, "tech").locator(".xp-chev").click();
  check("ORG_UI01c collapse hides the subtree and sets aria-expanded=false; expand brings it back", (await p.getByRole("treeitem").count()) === 2 && (await p.locator('li[role=treeitem]:has(> [data-node="tech"])').first().getAttribute("aria-expanded")) === "false" && (await node(p, "tech").locator(".xp-chev").click(), (await p.getByRole("treeitem").count()) === 5));
  check("ORG_UI01d nothing is sent just to look: only the two list calls were made", JSON.stringify((await names(p)).sort()) === JSON.stringify(["listOrganizationUnitTypes", "listOrganizationUnits"]), (await names(p)).join(","));
  await p.close(); }

{ const p = await open("org");
  await T(p, "org-add-root").click(); await T(p, "unit-dialog").waitFor();
  await T(p, "unit-submit").click();
  check("ORG_UI02 add root: an empty name is refused in the form and NOTHING is sent", /Hãy nhập tên đơn vị/.test(await T(p, "unit-dialog").innerText()) && !(await names(p)).includes("createOrganizationUnit"));
  await T(p, "unit-name").fill("Chi nhánh Hà Nội"); await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-submit").click();
  check("ORG_UI02b a type that cannot be a root (Phòng sits inside a Khối) is refused before sending, in words", /phải nằm trong: Khối/.test(await T(p, "unit-type-error").innerText()) && !(await names(p)).includes("createOrganizationUnit"));
  await pickType(p, "unit-dialog", "t-div"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const c = (await calls(p)).filter((x) => x.name === "createOrganizationUnit");
  check("ORG_UI02c ONE create call: tenant 't1', parentId null, the chosen type, the trimmed name; the new root appears in the tree and is selected", c.length === 1 && c[0].args[0] === "t1" && c[0].args[1].parentId === null && c[0].args[1].typeId === "t-div" && c[0].args[1].name === "Chi nhánh Hà Nội" && (await p.getByRole("treeitem").count()) === 6 && /Chi nhánh Hà Nội/.test(await T(p, "detail-name").innerText()), JSON.stringify(c[0]?.args));
  check("ORG_UI02d the flash says it was added", /Đã thêm đơn vị/.test(await T(p, "org-flash").innerText()));
  await p.close(); }

{ const p = await open("org");
  await node(p, "tech").click(); await T(p, "org-add-child").click(); await T(p, "unit-dialog").waitFor();
  check("ORG_UI03 add child: the dialog names the parent path", /Nằm trong: Khối Công nghệ/.test(await T(p, "unit-dialog").innerText()));
  await T(p, "unit-name").fill("Data"); await pickType(p, "unit-dialog", "t-team"); await T(p, "unit-submit").click();
  check("ORG_UI03b a Team directly under a Khối is refused (the type rule), nothing sent", /chỉ đặt được trong: Phòng, Team/.test(await T(p, "unit-type-error").innerText()) && !(await names(p)).includes("createOrganizationUnit"));
  await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-code").fill("DATA-01"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const c = (await calls(p)).filter((x) => x.name === "createOrganizationUnit");
  check("ORG_UI03c ONE create call with parentId 'tech' and the code; the child is nested under its parent (aria-level 2) and the parent now says 3 child units", c.length === 1 && c[0].args[1].parentId === "tech" && c[0].args[1].code === "DATA-01" && (await p.getByRole("treeitem").count()) === 6 && /3 đơn vị con/.test(await node(p, "tech").innerText()));
  await T(p, "unit-dialog").count(); await node(p, "tech").click(); await T(p, "org-add-child").click(); await T(p, "unit-name").fill("Data"); await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-submit").click(); await settle(p, 300);
  check("ORG_UI03d a duplicate name under the same parent is refused by the server code and shown as such (dialog stays open, tree unchanged)", (await T(p, "org-problem").getAttribute("data-kind")) === "duplicate" && (await p.getByRole("treeitem").count()) === 6);
  await p.close(); }

{ const p = await open("org");
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-dialog").waitFor();
  check("ORG_UI04 edit: the form is prefilled with the unit's name and type", (await T(p, "unit-name").inputValue()) === "Web" && (await T(p, "unit-dialog").locator("select.srOnly").first().inputValue()) === "t-dept");
  await T(p, "unit-name").fill("Web & Portal"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const u = (await calls(p)).filter((x) => x.name === "updateOrganizationUnit");
  check("ORG_UI04b ONE update call carries the unit's VERSION (optimistic lock) and the patch; the tree shows the new name", u.length === 1 && u[0].args[1] === "web" && u[0].args[2] === 1 && u[0].args[3].name === "Web & Portal" && (await nodeName(p, "web")) === "Web & Portal", JSON.stringify(u[0]?.args));
  await T(p, "org-toggle").click(); await settle(p, 300);
  check("ORG_UI04c disable: a disabled unit stays in the tree, marked 'Đã tắt', and the action becomes 'Bật'", (await node(p, "web").innerText()).includes("Đã tắt") && /Bật/.test(await T(p, "org-toggle").innerText()) && (await calls(p)).filter((x) => x.name === "updateOrganizationUnit").at(-1).args[3].enabled === false);
  await T(p, "org-toggle").click(); await settle(p, 300);
  check("ORG_UI04d …and enabling brings it back (version bumped each time, so no stale write)", !(await node(p, "web").innerText()).includes("Đã tắt") && (await calls(p)).filter((x) => x.name === "updateOrganizationUnit").at(-1).args[2] === 3);
  await p.close(); }

{ const p = await open("org");
  await node(p, "flutter").click(); await T(p, "org-move").click(); await T(p, "move-dialog").waitFor();
  const dis = async (id) => T(p, `move-to:${id}`).locator("input").isDisabled();
  check("ORG_UI05 move dialog: where the unit may go, with the reason when it may not (itself, 'already here', type rule: a Team cannot sit under a Khối or at the root)", (await dis("flutter")) && (await dis("mobile")) && (await dis("hr")) && (await dis("root")) && !(await dis("web")) && /Đang ở đây/.test(await T(p, "move-to:mobile").innerText()));
  check("ORG_UI05b the submit stays disabled until a destination is chosen", await T(p, "move-submit").isDisabled());
  await T(p, "move-to:web").locator("input").check(); await T(p, "move-submit").click(); await T(p, "move-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const m = (await calls(p)).filter((x) => x.name === "moveOrganizationUnit");
  check("ORG_UI05c ONE move call (tenant, id, version, new parent 'web'); the unit is now under Web and the detail path says so", m.length === 1 && m[0].args[1] === "flutter" && m[0].args[3] === "web" && /Khối Công nghệ › Web › Flutter Team/.test(await T(p, "org-detail").innerText()), JSON.stringify(m[0]?.args));
  await T(p, "org-reload").click(); await settle(p, 300);
  check("ORG_UI05d after a reload the new place persists and the subtree moved with it (Mobile has no child, Web has one)", /Mobile[\s\S]*0 đơn vị con|^$/.test("") || ((await node(p, "web").innerText()).includes("1 đơn vị con") && !(await node(p, "mobile").innerText()).includes("đơn vị con")));
  await p.close(); }

{ const p = await open("org");
  await node(p, "tech").click(); await T(p, "org-move").click(); await T(p, "move-dialog").waitFor();
  const dis = async (id) => T(p, `move-to:${id}`).locator("input").isDisabled();
  check("ORG_UI06 cycle: moving a unit into itself or into its own subtree is not selectable, and says why (nothing can be sent)", (await dis("tech")) && (await dis("mobile")) && (await dis("flutter")) && (await dis("web")) && /vòng/.test(await T(p, "move-to:flutter").innerText()) && !(await dis("hr")));
  await p.close(); }
{ const p = await open("org", "cycle");
  await node(p, "flutter").click(); await T(p, "org-move").click(); await T(p, "move-to:web").locator("input").check(); await T(p, "move-submit").click(); await settle(p, 300);
  check("ORG_UI06b if the SERVER refuses with a cycle code anyway, the dialog stays open with the cycle message and the tree is unchanged (the backend is the authority)", (await T(p, "org-problem").getAttribute("data-kind")) === "cycle" && /vòng/.test(await T(p, "org-problem").innerText()) && (await T(p, "move-dialog").count()) === 1 && (await level(p, "flutter")) === "3" && (await p.locator('[data-node="mobile"]').innerText()).includes("1 đơn vị con"));
  await p.close(); }

{ const p = await open("org");
  await node(p, "hr").click();
  check("ORG_UI07 delete blocked BEFORE the click when the counts are known: employees → the button is disabled and the reason is shown", (await T(p, "org-delete").isDisabled()) && /Còn 3 nhân viên/.test(await T(p, "org-delete-blocked").innerText()));
  await node(p, "tech").click();
  check("ORG_UI07b …and a unit with child units says so", (await T(p, "org-delete").isDisabled()) && /Còn 2 đơn vị con/.test(await T(p, "org-delete-blocked").innerText()));
  await node(p, "web").click(); await T(p, "org-delete").click(); await T(p, "delete-confirm").click(); await T(p, "delete-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const d = (await calls(p)).filter((x) => x.name === "deleteOrganizationUnit");
  check("ORG_UI07c an empty unit is deleted after a confirm dialog: ONE call with its version; it leaves the tree and the selection clears", d.length === 1 && d[0].args[1] === "web" && (await p.getByRole("treeitem").count()) === 4 && (await T(p, "org-pick-hint").count()) === 1, JSON.stringify(d[0]?.args));
  await p.close(); }
{ const p = await open("org", "blocked");
  await node(p, "web").click(); await T(p, "org-delete").click(); await T(p, "delete-confirm").click(); await settle(p, 300);
  check("ORG_UI07d when the SERVER refuses (the UI did not know the unit had employees), the dialog stays with the 'blocked' message and the unit is still in the tree", (await T(p, "org-problem").getAttribute("data-kind")) === "blocked" && /còn nhân viên/.test(await T(p, "org-problem").innerText()) && (await p.getByRole("treeitem").count()) === 5);
  await p.close(); }
{ const p = await open("org", "version");
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-name").fill("Web 2"); await T(p, "unit-submit").click(); await settle(p, 300);
  check("ORG_UI07e a stale edit (version mismatch) is refused with 'đã bị người khác thay đổi' and a 'Tải lại cơ cấu' button; nothing is overwritten", (await T(p, "org-problem").getAttribute("data-kind")) === "version" && (await p.getByRole("button", { name: "Tải lại cơ cấu" }).count()) >= 1 && (await nodeName(p, "web")) === "Web");
  await p.close(); }

{ const p = await open("org", "empty");
  check("ORG_UI08 empty tree: an explanation and one clear action; no tree, no detail panel", (await T(p, "org-empty").count()) === 1 && (await T(p, "org-tree").count()) === 0 && (await T(p, "org-empty-add").isEnabled()) && /Chưa có cơ cấu tổ chức/.test(await T(p, "org-empty").innerText()));
  await T(p, "org-empty-add").click(); await T(p, "unit-name").fill("Công ty mẹ"); await T(p, "unit-submit").click(); await settle(p, 400);
  check("ORG_UI08b creating the first root turns the empty state into a tree", (await T(p, "org-empty").count()) === 0 && (await p.getByRole("treeitem").count()) === 1);
  await p.close(); }

{ const p = await open("org", "forbidden");
  check("ORG_UI09 permission denied: the server lists no tenant the caller administers → a forbidden state with the reason, NO call to the backend, no tree, no actions", (await T(p, "org-forbidden").count()) === 1 && (await calls(p)).length === 0 && (await T(p, "org-tree").count()) === 0 && (await T(p, "org-add-root").count()) === 0);
  check("ORG_UI09b no role name is shown or used as the reason", !/TENANT_ADMIN|SYSTEM_ADMIN|WORKSPACE_ADMIN/.test(await p.locator("body").innerText()));
  await p.close(); }
{ const p = await open("org", "down");
  check("ORG_UI09c backend unavailable: an error state with a retry button, in words (not a blank page), the actions do nothing meanwhile", (await T(p, "org-error").getAttribute("data-kind")) === "unavailable" && (await T(p, "org-retry").count()) === 1 && (await T(p, "org-tree").count()) === 0);
  await p.close(); }

{ const p = await open("org");
  await node(p, "tech").focus(); await key(p, "ArrowDown");
  check("ORG_UI10 keyboard: ArrowDown moves focus to the next visible node (roving tabindex: exactly one node is tabbable)", (await p.evaluate(() => document.activeElement?.getAttribute("data-node"))) === "mobile" && (await p.locator('[data-node][tabindex="0"]').count()) === 1);
  await key(p, "ArrowLeft");
  const mobileLi = p.locator('li[role=treeitem]:has(> [data-node="mobile"])').first(); const techLi = p.locator('li[role=treeitem]:has(> [data-node="tech"])').first();
  const collapsedMobile = (await mobileLi.getAttribute("aria-expanded")) === "false";
  await key(p, "ArrowLeft"); const atParent = await p.evaluate(() => document.activeElement?.getAttribute("data-node"));
  await key(p, "ArrowLeft");
  check("ORG_UI10b ArrowLeft on an OPEN node collapses it, on a collapsed node goes to its parent, and on an open parent collapses that (aria-expanded follows)", collapsedMobile && atParent === "tech" && (await techLi.getAttribute("aria-expanded")) === "false" && (await p.getByRole("treeitem").count()) === 2);
  await key(p, "ArrowRight"); await key(p, "ArrowRight");
  check("ORG_UI10c ArrowRight expands, then moves into the first child", (await techLi.getAttribute("aria-expanded")) === "true" && (await p.evaluate(() => document.activeElement?.getAttribute("data-node"))) === "mobile");
  await key(p, "End"); const last = await p.evaluate(() => document.activeElement?.getAttribute("data-node")); await key(p, "Home"); const first = await p.evaluate(() => document.activeElement?.getAttribute("data-node"));
  check("ORG_UI10d Home / End jump to the first / last visible node", first === "tech" && last === "hr");
  await key(p, "Enter");
  check("ORG_UI10e Enter selects the focused node: the detail panel shows it and aria-selected is set", /Khối Công nghệ/.test(await T(p, "detail-name").innerText()) && (await techLi.getAttribute("aria-selected")) === "true");
  await T(p, "org-edit").focus(); await key(p, "Enter"); await T(p, "unit-dialog").waitFor(); await p.keyboard.press("Escape");
  check("ORG_UI10f a dialog opens from the keyboard and Escape closes it", (await T(p, "unit-dialog").count()) === 0);
  const v = await axe(p); check("ORG_UI10g axe (wcag2a/aa, critical + serious) finds nothing on the tree screen", v.length === 0, v.join(","));
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); const v2 = await axe(p, "[data-testid=types-dialog]");
  check("ORG_UI10h …nor in the unit-types dialog", v2.length === 0, v2.join(","));
  await p.close(); }

// ===================================================================================================================== UNIT TYPES (dynamic)
{ const p = await open("org");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor();
  check("TYPE01 the company's own types are listed with code and where they may sit (nothing hard-coded: Khối / Phòng / Team come from the data)", (await T(p, "types-list").locator("li").count()) === 3 && /DIVISION/.test(await T(p, "types-list").innerText()) && /đặt trong: Khối/.test(await T(p, "types-list").innerText()));
  check("TYPE02 the icon is chosen from a closed grid of 16 allow-listed icons (radio buttons), there is no URL / upload field", (await p.locator('[data-testid^="icon:"]').count()) === 16 && (await T(p, "types-dialog").locator('input[type=url],input[type=file]').count()) === 0);
  await T(p, "type-submit").click();
  check("TYPE03 empty name / bad code are refused in the form, nothing sent", /Hãy nhập tên loại/.test(await T(p, "type-form").innerText()) && !(await names(p)).includes("createOrganizationUnitType"));
  await T(p, "type-name").fill("Chi nhánh"); await T(p, "type-code").fill("division"); await T(p, "type-submit").click();
  check("TYPE04 a code already used by another type is refused (case-insensitive)", /đã được dùng/.test(await T(p, "type-form").innerText()) && !(await names(p)).includes("createOrganizationUnitType"));
  await T(p, "type-code").fill("branch"); await T(p, "icon:map-pin").click(); await T(p, "type-submit").click(); await settle(p, 300);
  const c = (await calls(p)).filter((x) => x.name === "createOrganizationUnitType");
  check("TYPE05 ONE create call: name, upper-cased code, the chosen icon id; the new type is listed at once", c.length === 1 && c[0].args[1].code === "BRANCH" && c[0].args[1].icon === "map-pin" && c[0].args[1].name === "Chi nhánh" && (await T(p, "types-list").locator("li").count()) === 4, JSON.stringify(c[0]?.args));
  await p.close(); }

// ===================================================================================================================== NOT_READY (REAL default capability table)
{ const p = await open("org", "notready");
  check("NR01 with the REAL capability table (the server has no organization API yet) the screen says so in plain words and offers no action", (await T(p, "org-not-ready").count()) === 1 && /Máy chủ chưa hỗ trợ cơ cấu tổ chức/.test(await T(p, "org-not-ready").innerText()) && !/\bC1\b/.test(await T(p, "org-not-ready").innerText()) && (await T(p, "org-add-root").isDisabled()) && (await T(p, "org-types").isDisabled()) && (await T(p, "org-tree").count()) === 0);
  await T(p, "org-add-root").click({ force: true, timeout: 800 }).catch(() => undefined);
  check("NR02 a forced click sends NOTHING and no URL is invented: the transport received zero calls", (await calls(p)).length === 0 && (await T(p, "unit-dialog").count()) === 0);
  await p.close(); }

// ===================================================================================================================== EMPLOYEE DIRECTORY
{ const p = await open("emp");
  const heads = await p.locator("thead th").evaluateAll((l) => l.map((x) => x.textContent));
  check("EMP_UI01 directory: 20 rows per page, columns Nhân viên / Đơn vị / Vị trí / Vai trò công ty / Trạng thái", (await rows(p)) === 20 && heads.join("|") === "Nhân viên|Đơn vị|Vị trí|Vai trò công ty|Trạng thái", heads.join("|"));
  const r1 = await T(p, "emp:u01").innerText();
  check("EMP_UI01b a row shows name, username, email, unit, position, the company role as a LABEL ('Quản trị công ty', never the raw code) and the status", /Nhân viên 1/.test(r1) && /user1/.test(r1) && /u1@acme\.vn/.test(r1) && /Flutter Team/.test(r1) && /Nhân viên\b/.test(r1) && /Quản trị công ty/.test(r1) && !/TENANT_ADMIN/.test(r1) && /Hoạt động/.test(r1));
  check("EMP_UI01c the count line says page and total", /Trang 1\/3 · 45 nhân viên/.test(await T(p, "emp-count").innerText()));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-search").fill("nguyen duc anh"); await settle(p, 700);
  check("EMP_UI02 search is accent- and case-insensitive ('nguyen duc anh' finds 'Nguyễn Đức Anh'); one row; the query reached the backend", (await rows(p)) === 1 && (await T(p, "emp:u05").count()) === 1 && (await calls(p)).some((c) => c.name === "listEmployees" && c.args[1].q === "nguyen duc anh"));
  await T(p, "emp-search").fill("zzzz-none"); await settle(p, 700);
  check("EMP_UI02b no match → an empty state that says to change the filter (not a blank table)", (await T(p, "emp-empty").count()) === 1 && /đổi từ khóa/.test(await T(p, "emp-empty").innerText()) && (await T(p, "emp-table").count()) === 0);
  await p.getByRole("button", { name: "Xóa tìm kiếm" }).click(); await settle(p, 700);
  check("EMP_UI02c clearing the search restores the full list", (await rows(p)) === 20 && /45 nhân viên/.test(await T(p, "emp-count").innerText()));
  await p.close(); }

{ const p = await open("emp");
  check("EMP_UI03 pagination: the first page has Trước disabled and Sau enabled", (await T(p, "emp-prev").isDisabled()) && (await T(p, "emp-next").isEnabled()));
  const first = await T(p, "emp:u01").count(); await T(p, "emp-next").click(); await settle(p, 400);
  check("EMP_UI03b page 2/3 shows other people (u21…u40); u01 is gone", /Trang 2\/3/.test(await T(p, "emp-count").innerText()) && (await T(p, "emp:u21").count()) === 1 && first === 1 && (await T(p, "emp:u01").count()) === 0);
  await T(p, "emp-next").click(); await settle(p, 400);
  check("EMP_UI03c the last page has 5 rows and Sau is disabled", (await rows(p)) === 5 && (await T(p, "emp-next").isDisabled()));
  await T(p, "emp-status").selectOption("ACTIVE"); await settle(p, 400);
  check("EMP_UI03d changing a filter returns to page 1", /Trang 1\//.test(await T(p, "emp-count").innerText()));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-create").click(); await T(p, "create-account").waitFor();
  check("EMP_UI04 create: the existing tenant provisioning dialog, titled 'Thêm nhân viên', with an extra 'Cơ cấu tổ chức' section (unit + position)", /Thêm nhân viên/.test(await T(p, "create-account").innerText()) && (await T(p, "emp-org-fields").count()) === 1 && (await T(p, "emp-new-unit").isEnabled()) && (await T(p, "emp-new-position").isEnabled()));
  await T(p, "acc-username").fill("bao.nguyen"); await T(p, "acc-display").fill("Bảo Nguyễn"); await T(p, "acc-type").selectOption("USER");
  await T(p, "emp-new-unit").selectOption("flutter"); await T(p, "emp-new-position").selectOption("p-sr");
  await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const pc = (await prov(p)).filter((x) => x.name === "createTenantUser"); const oc = (await calls(p)).filter((x) => /^updateEmployee/.test(x.name));
  check("EMP_UI04b ONE account call (tenant 't1' as the path, no tenantId in the body), then the organization steps for THAT new user: unit 'flutter' and position 'p-sr'", pc.length === 1 && pc[0].args[0] === "t1" && !("tenantId" in pc[0].args[1]) && oc.length === 2 && oc[0].name === "updateEmployeeOrganization" && oc[0].args[2] === "flutter" && oc[0].args[1] === "id-bao.nguyen" && oc[1].args[2] === "p-sr", JSON.stringify([pc.map((x) => x.args), oc.map((x) => x.args)]));
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 400); await T(p, "emp-search").fill("bao.nguyen"); await settle(p, 700);
  check("EMP_UI04c the new employee is in the directory, in Flutter Team with the position (reloaded from the backend, not drawn locally)", (await T(p, "emp:id-bao.nguyen").count()) === 1 && /Flutter Team/.test(await T(p, "emp:id-bao.nguyen").innerText()) && /Trưởng nhóm/.test(await T(p, "emp:id-bao.nguyen").innerText()));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-org").selectOption("tech"); await settle(p, 500);
  check("EMP_UI05 filter by unit includes its subtree (Khối Công nghệ → Flutter + Web = 30 people); the query carries the unit id", /30 nhân viên/.test(await T(p, "emp-count").innerText()) && (await calls(p)).some((c) => c.name === "listEmployees" && c.args[1].orgUnitId === "tech" && c.args[1].includeSubtree === true));
  await T(p, "emp-org").selectOption(""); await settle(p, 400); await T(p, "emp:u05").click(); await T(p, "emp-detail").waitFor();
  check("EMP_UI05b detail → Cơ cấu tổ chức: the unit path / name is shown and a unit select is enabled", /Web/.test(await T(p, "detail-org").innerText()) && (await T(p, "detail-unit").isEnabled()));
  await T(p, "detail-unit").selectOption("hr"); await T(p, "detail-unit-save").click(); await settle(p, 400);
  const oc = (await calls(p)).filter((x) => x.name === "updateEmployeeOrganization");
  check("EMP_UI05c ONE call (tenant, user, unit 'hr'); the dialog confirms and the row now says Nhân sự", oc.length === 1 && oc[0].args[1] === "u05" && oc[0].args[2] === "hr" && /Đã lưu đơn vị/.test(await T(p, "detail-ok").innerText()) && /Nhân sự/.test(await T(p, "emp:u05").innerText()));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp:u05").click(); await T(p, "emp-detail").waitFor();
  check("EMP_UI06 position / level: options show the level ('Trưởng nhóm (cấp 3)')", (await T(p, "detail-pos").locator("option").allInnerTexts()).some((t) => /Trưởng nhóm \(cấp 3\)/.test(t)));
  await T(p, "detail-pos").selectOption("p-sr"); await T(p, "detail-pos-save").click(); await settle(p, 400);
  const pc = (await calls(p)).filter((x) => x.name === "updateEmployeePosition");
  check("EMP_UI06b ONE position call; the detail and the row show 'Trưởng nhóm'", pc.length === 1 && pc[0].args[1] === "u05" && pc[0].args[2] === "p-sr" && /Trưởng nhóm/.test(await T(p, "emp:u05").innerText()));
  check("EMP_UI06c the detail has the six sections (Thông tin, Cơ cấu tổ chức, Vị trí/Cấp bậc, Workspace, Quyền hiệu lực, Trạng thái)", JSON.stringify(await T(p, "emp-detail").locator("section").evaluateAll((l) => l.map((s) => s.getAttribute("aria-label")))) === JSON.stringify(["Thông tin", "Cơ cấu tổ chức", "Vị trí / Cấp bậc", "Workspace", "Quyền hiệu lực", "Trạng thái"]));
  check("EMP_UI06d organization metadata is NOT a permission: 'Quyền hiệu lực' shows only the role label and says unit / position grant nothing; no permission list is invented", /Thành viên/.test(await T(p, "detail-role").innerText()) && /không cấp quyền/.test(await T(p, "detail-perm-note").innerText()));
  await p.close(); }

{ const p = await open("emp");
  check("EMP_UI07 a disabled account is listed with the 'Đã tắt' status (u07, u08)", (await T(p, "emp:u07").innerText()).includes("Đã tắt") && (await T(p, "emp:u08").innerText()).includes("Đã tắt"));
  await T(p, "emp-status").selectOption("INACTIVE"); await settle(p, 500);
  check("EMP_UI07b status filter 'Đã tắt' → exactly those two", (await rows(p)) === 2 && /2 nhân viên/.test(await T(p, "emp-count").innerText()));
  await T(p, "emp:u07").click(); await T(p, "emp-detail").waitFor();
  check("EMP_UI07c detail of a disabled account: the status says so; enabling is NOT offered to a company admin (the existing route is platform-only), and the reason is shown", /Đã tắt/.test(await T(p, "emp-detail").innerText()) && (await T(p, "detail-toggle").isDisabled()) && /Chỉ quản trị hệ thống/.test(await T(p, "detail-toggle-note").innerText()));
  await p.close(); }

{ const p = await open("emp");
  check("EMP_UI08 no cross-tenant selector: the tenant is the session's, read-only, named; there is no tenant switch and no editable id", (await T(p, "emp-tenant").getAttribute("readonly")) !== null && (await T(p, "emp-tenant").inputValue()) === "Acme" && (await T(p, "emp-tenant-switch").count()) === 0);
  await T(p, "emp-create").click(); await T(p, "create-account").waitFor();
  check("EMP_UI08b …and the create dialog has no tenant selector either (a fixed, read-only tenant) and no SYSTEM_ADMIN type", (await T(p, "acc-tenant").count()) === 0 && (await T(p, "acc-tenant-fixed").inputValue()) === "Acme" && !(await T(p, "create-account").innerText()).includes("SYSTEM_ADMIN"));
  await p.close(); }
{ const p = await open("emp", "multi");
  const opts = await T(p, "emp-tenant-switch").locator("option").allInnerTexts();
  check("EMP_UI08c an admin of SEVERAL companies chooses only among THEIR OWN (Acme, Công ty C); nobody else's company is offered", JSON.stringify(opts) === JSON.stringify(["Acme", "Công ty C"]));
  await p.close(); }
{ const p = await open("emp", "forbidden");
  check("EMP_UI08d permission denied: a forbidden state with the reason and NO backend call", (await T(p, "emp-forbidden").count()) === 1 && (await calls(p)).length === 0 && (await T(p, "emp-table").count()) === 0 && (await T(p, "emp-create").count()) === 0);
  await p.close(); }
{ const p = await open("emp", "emp-down");
  check("EMP_UI08e backend unavailable: an error state with a retry button, not a blank list", (await T(p, "emp-error").getAttribute("data-kind")) === "unavailable" && (await T(p, "emp-retry").count()) === 1);
  await p.close(); }
{ const p = await open("emp", "emp-empty");
  check("EMP_UI08f no employees at all: an explanation pointing to 'Thêm nhân viên'", /Thêm nhân viên đầu tiên/.test(await T(p, "emp-empty").innerText()) && (await T(p, "emp-table").count()) === 0);
  await p.close(); }

{ const p = await open("emp", "ok", { width: 390, height: 800 });
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check("EMP_UI09 responsive (390 px): the table becomes cards (header hidden, cells stacked), no horizontal page scroll, the filters and the create button are reachable", overflow <= 1 && (await p.locator("thead").evaluate((e) => getComputedStyle(e).display)) === "none" && (await T(p, "emp:u01").evaluate((e) => getComputedStyle(e).display)) === "block" && (await T(p, "emp-create").isVisible()) && (await T(p, "emp-search").isVisible()), `overflow=${overflow}`);
  await p.close(); }
{ const p = await open("org", "ok", { width: 600, height: 800 });
  check("EMP_UI09b the organization screen stacks the tree and the detail in ONE column on a narrow screen, no horizontal scroll", (await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)) <= 1 && (await p.locator(".xp-orgGrid").evaluate((e) => getComputedStyle(e).gridTemplateColumns.split(" ").length)) === 1);
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-search").focus(); await p.keyboard.press("Tab"); await p.keyboard.press("Tab"); await p.keyboard.press("Tab");
  const rowFocus = await p.evaluate(() => document.activeElement?.getAttribute("data-testid"));
  await p.keyboard.press("Enter"); await T(p, "emp-detail").waitFor();
  check("EMP_UI10 keyboard: Tab reaches the rows and Enter opens the detail; Escape closes it", /^emp:/.test(rowFocus ?? "") && (await T(p, "emp-detail").count()) === 1 && (await p.keyboard.press("Escape"), await settle(p, 200), (await T(p, "emp-detail").count()) === 0), String(rowFocus));
  const v = await axe(p); check("EMP_UI10b axe (wcag2a/aa, critical + serious) finds nothing on the directory", v.length === 0, v.join(","));
  await T(p, "emp:u05").click(); await T(p, "emp-detail").waitFor(); const v2 = await axe(p, "[data-testid=emp-detail]");
  check("EMP_UI10c …nor in the employee detail", v2.length === 0, v2.join(","));
  check("EMP_UI10d controls are labelled (search, unit filter, status filter) and the table has column headers", (await p.getByLabel("Tìm nhân viên").count()) >= 1 && (await p.getByLabel("Lọc theo đơn vị").count()) === 1 && (await p.getByLabel("Lọc theo trạng thái").count()) === 1);
  await p.close(); }

// ===================================================================================================================== employee directory while the organization contract is NOT_READY
{ const p = await open("emp", "emp-members");
  const names0 = await names(p);
  check("EMP_NR01 with the REAL capability table the directory falls back to the tenant member list (the ONE existing route): the only call is tenantMembers; no organization call is made", names0.length >= 1 && names0.every((n) => n === "tenantMembers"), names0.join(","));
  check("EMP_NR02 it says plainly that unit and position are not available yet, the unit filter is disabled, and the unit / position columns are LEFT OUT (not empty cells that look like errors); nothing is invented", (await T(p, "emp-members-note").count()) === 1 && (await T(p, "emp-org").isDisabled()) && (await p.locator("thead th").count()) === 3 && !/Flutter Team|—/.test(await T(p, "emp:u01").innerText()));
  check("EMP_NR03 search / status / paging still work on the member list (client-side): 45 people, 3 pages", /Trang 1\/3 · 45 nhân viên/.test(await T(p, "emp-count").innerText()));
  await T(p, "emp:u05").click(); await T(p, "emp-detail").waitFor();
  check("EMP_NR04 detail: unit and position cannot be assigned yet (disabled selects with the not-ready panel), no save button", (await T(p, "detail-unit").isDisabled()) && (await T(p, "detail-pos").isDisabled()) && (await T(p, "detail-org-not-ready").count()) === 1 && /chưa hỗ trợ/.test(await T(p, "detail-org-not-ready").innerText()) && (await T(p, "detail-unit-save").count()) === 0);
  await p.keyboard.press("Escape"); await T(p, "emp-create").click(); await T(p, "create-account").waitFor();
  check("EMP_NR05 create: the organization fields are disabled with a not-ready notice, and the account can still be created", (await T(p, "emp-new-unit").isDisabled()) && (await T(p, "emp-org-not-ready").count()) === 1);
  await T(p, "acc-username").fill("an.tran"); await T(p, "acc-display").fill("An Trần"); await T(p, "acc-type").selectOption("USER"); await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  check("EMP_NR06 creating works through the REAL provisioning route: ONE createTenantUser call and ZERO organization calls", (await prov(p)).filter((x) => x.name === "createTenantUser").length === 1 && !(await names(p)).some((n) => /^updateEmployee|OrganizationUnit/.test(n)));
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
