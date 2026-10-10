// @class: harness — real Chromium on the employee directory (search, filters, paging limits, create, memberships, positions with grades, enable / disable) with an in-page FAKE in-memory transport behind the REAL service (no backend).
// NOT a backend E2E: the fake is tests/browser/org-fake-server.ts (it answers the way docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md says). The real-backend proof is tests/e2e-real/flows/e2e-org01.mjs against a flag-ON stack.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/org-employees.spec.mjs   (the structure screens: org.spec.mjs)
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
import { harnessOrigin, launch, makeChecks } from "./lib/spec.mjs";
const AXE = require.resolve("axe-core/axe.min.js");
const ORIGIN = harnessOrigin();
const { check, finish } = makeChecks();
const errors = [];
const browser = await launch();
const T = (p, id) => p.getByTestId(id);
const closeLink = async (p) => { await p.getByRole("button", { name: "Xong" }).click(); await p.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click(); };   // the one-time link dialog asks before it closes until the link was copied (M-007)
async function open(v, s = "ok", viewport = { width: 1200, height: 900 }) {
  const p = await browser.newPage({ viewport }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/org.html?v=${v}&s=${s}`); await p.waitForTimeout(450); return p;
}
const calls = (p) => p.evaluate(() => window.__org); const prov = (p) => p.evaluate(() => window.__prov);
const names = async (p) => (await calls(p)).map((c) => c.name);
const of = async (p, name) => (await calls(p)).filter((c) => c.name === name);
const lists = async (p) => (await of(p, "listEmployees")).map((c) => c.args[0]);
const server = (p) => p.evaluate(() => { const s = window.__fake.state(); return { units: s.units, employees: s.employees, refused: s.refusedLimits, requests: s.requests }; });
const rows = (p) => p.locator('[data-testid^="emp:"]').count();
const settle = (p, ms = 400) => p.waitForTimeout(ms);
const focusedId = (p) => p.evaluate(() => document.activeElement?.getAttribute("data-testid") ?? document.activeElement?.tagName);
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); };
const emp = async (p, id) => (await p.evaluate(() => window.__fake.state().employees)).find((e) => e.userId === id);
const openDetail = async (p, id) => { await T(p, `emp:${id}`).click(); await T(p, "emp-detail").waitFor(); await settle(p, 250); };

// ===================================================================================================================== DIRECTORY
{ const p = await open("emp");
  const heads = await p.locator("thead th").evaluateAll((l) => l.map((x) => x.textContent));
  check("EMP_UI01 directory: 20 rows per page, columns Nhân viên / Đơn vị / Vị trí / Vai trò công ty / Trạng thái", (await rows(p)) === 20 && heads.join("|") === "Nhân viên|Đơn vị|Vị trí|Vai trò công ty|Trạng thái", heads.join("|"));
  const r1 = await T(p, "emp:u01").innerText();
  check("EMP_UI01b a row shows name, username, email, the primary unit as a PATH (+ how many other units), the position with its grade, the company role as a LABEL ('Quản trị công ty', never the raw code) and the status", /Nhân viên 1/.test(r1) && /user1/.test(r1) && /u1@acme\.vn/.test(r1) && /Khối Công nghệ › Mobile › Flutter Team/.test(r1) && /\+1 đơn vị khác/.test(r1) && /Trưởng nhóm/.test(r1) && /Senior/.test(r1) && /Quản trị công ty/.test(r1) && !/TENANT_ADMIN/.test(r1) && /Hoạt động/.test(r1), r1);
  check("EMP_UI01c the count line says page and total", /Trang 1\/3 · 45 nhân viên/.test(await T(p, "emp-count").innerText()));
  await T(p, "emp-search").fill("Nhân viên 9"); await settle(p, 700);
  check("EMP_UI01d an account that was created but not yet activated says so ('Chờ kích hoạt')", /Chờ kích hoạt/.test(await T(p, "emp:u09").innerText()));
  await p.close(); }
{ const p = await open("emp");
  const first = (await lists(p))[0]; const calls0 = await names(p);
  check("EMP_UI01e the first request asks for exactly what the screen shows: page 0, size 20, sorted by name ascending, no search, no filter; the unit / position / grade lists were loaded for the names and filters", first.page === 0 && first.size === 20 && first.sort === "name" && first.dir === "asc" && first.q === undefined && first.organizationUnitId === undefined && ["unitTree", "listPositions", "listGrades"].every((n) => (calls0).includes(n)), JSON.stringify(first));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-search").fill("a"); await settle(p, 700);
  check("EMP_UI02 a one-character search is NOT sent (the server needs 2): the screen says so, the list is unchanged, no listEmployees call carries it", /ít nhất 2 ký tự/.test(await T(p, "emp-search-hint").innerText()) && (await lists(p)).every((q) => q.q === undefined) && (await rows(p)) === 20);
  await T(p, "emp-search").fill("nguyen duc anh"); await settle(p, 700);
  check("EMP_UI02b search is accent- and case-insensitive ('nguyen duc anh' finds 'Nguyễn Đức Anh'); one row; the query reached the backend", (await rows(p)) === 1 && (await T(p, "emp:u05").count()) === 1 && (await lists(p)).some((q) => q.q === "nguyen duc anh") && (await T(p, "emp-search-hint").count()) === 0);
  await T(p, "emp-search").fill("zzzz-none"); await settle(p, 700);
  check("EMP_UI02c no match → an empty state that says to change the filter (not a blank table)", (await T(p, "emp-empty").count()) === 1 && /đổi từ khóa/.test(await T(p, "emp-empty").innerText()) && (await T(p, "emp-table").count()) === 0);
  await p.getByRole("button", { name: "Xóa tìm kiếm" }).click(); await settle(p, 700);
  check("EMP_UI02d clearing the search restores the full list; the server never had to refuse a request (no QUERY_TOO_SHORT)", (await rows(p)) === 20 && /45 nhân viên/.test(await T(p, "emp-count").innerText()) && (await server(p)).refused.length === 0);
  await p.close(); }

{ const p = await open("emp");
  check("EMP_UI03 pagination: the first page has Trước disabled and Sau enabled", (await T(p, "emp-prev").isDisabled()) && (await T(p, "emp-next").isEnabled()));
  const page1 = await p.locator('[data-testid^="emp:"]').evaluateAll((l) => l.map((x) => x.getAttribute("data-testid")));
  await T(p, "emp-next").click(); await settle(p, 400);
  const page2 = await p.locator('[data-testid^="emp:"]').evaluateAll((l) => l.map((x) => x.getAttribute("data-testid")));
  check("EMP_UI03b page 2/3 shows OTHER people (no overlap with page 1); the request carries page 1", /Trang 2\/3/.test(await T(p, "emp-count").innerText()) && page2.length === 20 && page2.every((x) => !page1.includes(x)) && (await lists(p)).at(-1).page === 1);
  await T(p, "emp-next").click(); await settle(p, 400);
  check("EMP_UI03c the last page has 5 rows and Sau is disabled", (await rows(p)) === 5 && (await T(p, "emp-next").isDisabled()));
  await T(p, "emp-status").selectOption("ACTIVE"); await settle(p, 400);
  check("EMP_UI03d changing a filter returns to page 1 and the status reaches the backend as active=true", /Trang 1\//.test(await T(p, "emp-count").innerText()) && (await lists(p)).at(-1).active === true && /43 nhân viên/.test(await T(p, "emp-count").innerText()));
  await p.close(); }

{ const p = await open("emp");
  await T(p, "emp-org").selectOption("tech"); await settle(p, 500);
  const q = (await lists(p)).at(-1);
  check("EMP_UI05 filter by unit includes its subtree by default (Khối Công nghệ → Flutter + Web = 30 people, the one in two sub-units counted once); the request carries the unit id and includeDescendants=true", /30 nhân viên/.test(await T(p, "emp-count").innerText()) && q.organizationUnitId === "tech" && q.includeDescendants === true, JSON.stringify(q));
  await T(p, "emp-subtree").uncheck(); await settle(p, 500);
  check("EMP_UI05b 'Gồm đơn vị con' off → only the unit's own members (Khối Công nghệ has none directly): includeDescendants=false and an empty state", (await lists(p)).at(-1).includeDescendants === false && (await T(p, "emp-empty").count()) === 1);
  await T(p, "emp-org").selectOption("mobile"); await settle(p, 500);
  check("EMP_UI05c Mobile without sub-units → exactly its one direct member (u01, who is a manager there)", (await rows(p)) === 1 && (await T(p, "emp:u01").count()) === 1);
  await p.close(); }
{ const p = await open("emp");
  await T(p, "emp-position").selectOption("p-lead"); await T(p, "emp-grade").selectOption("g-sr"); await settle(p, 500);
  const q = (await lists(p)).at(-1);
  check("EMP_UI06 filter by position AND grade (people who hold 'Trưởng nhóm' at grade 'Senior'): the query carries both ids; 8 people", q.positionId === "p-lead" && q.gradeId === "g-sr" && /8 nhân viên/.test(await T(p, "emp-count").innerText()), JSON.stringify(q));
  await p.close(); }

// ===================================================================================================================== CREATE (ONE request: account + memberships + positions)
{ const p = await open("emp");
  await T(p, "emp-create").click(); await T(p, "create-account").waitFor();
  check("EMP_UI04 create: the tenant provisioning dialog titled 'Thêm nhân viên' with an 'Cơ cấu tổ chức' section: unit, then position (needs a unit), then grade (needs a position)", /Thêm nhân viên/.test(await T(p, "create-account").innerText()) && (await T(p, "emp-org-fields").count()) === 1 && await T(p, "emp-new-unit").isEnabled() && await T(p, "emp-new-position").isDisabled() && await T(p, "emp-new-grade").isDisabled());
  await T(p, "acc-username").fill("bao.nguyen"); await T(p, "acc-display").fill("Bảo Nguyễn"); await T(p, "acc-type").selectOption("USER");
  await T(p, "emp-new-unit").selectOption("flutter"); await T(p, "emp-new-position").selectOption("p-lead"); await T(p, "emp-new-grade").selectOption("g-sr");
  await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const cr = await of(p, "createEmployee"); const body = cr[0]?.args[0];
  check("EMP_UI04b ONE createEmployee call: the account fields, tenant as the PATH (no tenantId in the body), and the membership with its position and grade NESTED inside it (one transaction on the server)", cr.length === 1 && body.username === "bao.nguyen" && !("tenantId" in body) && body.organizationMemberships.length === 1 && body.organizationMemberships[0].organizationUnitId === "flutter" && body.organizationMemberships[0].primary === true && JSON.stringify(body.organizationMemberships[0].positions) === JSON.stringify([{ positionId: "p-lead", gradeId: "g-sr", primary: true }]), JSON.stringify(body));
  check("EMP_UI04c NO follow-up organization calls (no addMembership / addEmployeePosition) and the old provisioning route was not used for the account", !(await names(p)).some((n) => /^add(Membership|EmployeePosition)$/.test(n)) && (await prov(p)).filter((x) => x.name === "createTenantUser").length === 0);
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 400); await T(p, "emp-search").fill("bao.nguyen"); await settle(p, 700);
  const row = await T(p, "emp:" + (await server(p)).employees.find((e) => e.username === "bao.nguyen").userId).innerText();
  check("EMP_UI04d the new employee is in the directory (reloaded from the server, not drawn locally) with the unit path, the position, the grade and 'Chờ kích hoạt'", /Khối Công nghệ › Mobile › Flutter Team/.test(row) && /Trưởng nhóm/.test(row) && /Senior/.test(row) && /Chờ kích hoạt/.test(row), row);
  await p.close(); }
{ const p = await open("emp");
  await T(p, "emp-create").click(); await T(p, "create-account").waitFor(); await T(p, "acc-username").fill("user2"); await T(p, "acc-display").fill("Trùng tên"); await T(p, "acc-type").selectOption("USER"); await T(p, "emp-new-unit").selectOption("web");
  const before = (await server(p)).employees.length; await T(p, "acc-submit").click(); await settle(p, 500);
  check("EMP_UI04e a refused create (the username is taken) is shown in the dialog, which stays open with the input; NOTHING was created (the account and its memberships are one transaction)", (await T(p, "create-account").count()) === 1 && (await T(p, "account-created").count()) === 0 && (await server(p)).employees.length === before && (await T(p, "emp-new-unit").inputValue()) === "web");
  await p.close(); }
{ const p = await open("emp", "emp-only");
  check("EMP_UI04f without POSITION_GRADE_VIEW / ORG_STRUCTURE_VIEW the directory still works, but it does not pretend: units are 'không xem được tên', the unit / position / grade filters are off, no catalog button", (await rows(p)) === 20 && /không xem được tên đơn vị/.test(await T(p, "emp:u01").innerText()) && await T(p, "emp-org").isDisabled() && await T(p, "emp-position").isDisabled() && await T(p, "emp-grade").isDisabled() && (await T(p, "emp-catalog").count()) === 0);
  await T(p, "emp-create").click(); await T(p, "create-account").waitFor();
  check("EMP_UI04g …and an account can still be created without a unit: the unit select is disabled with the explanation, the call has NO memberships key", await T(p, "emp-new-unit").isDisabled() && (await T(p, "emp-org-not-ready").count()) === 1);
  await T(p, "acc-username").fill("khong.don.vi"); await T(p, "acc-display").fill("Không đơn vị"); await T(p, "acc-type").selectOption("USER"); await T(p, "acc-submit").click(); await closeLink(p); await T(p, "account-created").waitFor();
  const cr = (await of(p, "createEmployee"))[0]?.args[0];
  check("EMP_UI04h the create call carries no organizationMemberships (nothing was invented) and succeeds", cr && !("organizationMemberships" in cr));
  await p.close(); }

// ===================================================================================================================== DETAIL: MEMBERSHIPS, RELATION, POSITIONS WITH GRADES
{ const p = await open("emp");
  await openDetail(p, "u01");
  const ms = await T(p, "detail-memberships").locator("li.xp-memberItem").count(); const first = await T(p, "detail-memberships").locator("li.xp-memberItem").first();
  const text = await T(p, "detail-memberships").innerText();
  check("MEM01 an employee belongs to SEVERAL units: u01 has two memberships (Flutter Team primary, Mobile as MANAGER); the primary one is first and marked 'Đơn vị chính', each shows its path and its relation label", ms === 2 && (await first.getAttribute("data-primary")) === "true" && /Khối Công nghệ › Mobile › Flutter Team/.test(text) && /Đơn vị chính/.test(text) && /MEMBER/.test(text) && /MANAGER/.test(text), text);
  check("MEM02 the position held in Flutter Team is shown INSIDE that membership with its grade ('Trưởng nhóm', 'Senior'); Mobile holds none", /Trưởng nhóm/.test(await first.innerText()) && /Senior/.test(await first.innerText()));
  check("MEM03 the screen says plainly that a relation and a position grant NO permission, and 'Quyền hiệu lực' shows only the company role label", /không cấp quyền/.test(await T(p, "detail-relation-note").innerText()) && /Quản trị công ty/.test(await T(p, "detail-role").innerText()) && !/MANAGER|HEAD/.test(await T(p, "detail-perm-note").innerText()));
  const mobile = (await server(p)).employees.find((e) => e.userId === "u01").organizationMemberships.find((m) => m.organizationUnitId === "mobile");
  await T(p, `membership-make-primary:${mobile.id}`).click(); await settle(p, 500);
  const um = (await of(p, "updateMembership")).at(-1);
  check("MEM04 'Đặt làm đơn vị chính' on Mobile: ONE update with {primary: true} and the membership's version; the server demoted Flutter Team; the screen and the directory row follow", um.args[2].primary === true && um.args[2].expectedVersion === mobile.version && (await T(p, `membership:${mobile.id}`).getAttribute("data-primary")) === "true" && (await T(p, "emp-unit:u01").innerText()).startsWith("Khối Công nghệ › Mobile") && (await emp(p, "u01")).primaryOrganizationUnitId === "mobile");
  await T(p, `membership-edit-relation:${mobile.id}`).click(); await T(p, `membership-relation-input:${mobile.id}`).fill("head"); await T(p, `membership-relation-save:${mobile.id}`).click(); await settle(p, 500);
  const ur = (await of(p, "updateMembership")).at(-1);
  check("MEM05 changing the relation: 'head' is sent as 'HEAD' (upper-case like the server), the pill shows it, and NOTHING else changes (the permission note and the role are the same: a relation is a label)", ur.args[2].relationType === "HEAD" && (await T(p, `membership-relation:${mobile.id}`).innerText()) === "HEAD" && /Quản trị công ty/.test(await T(p, "detail-role").innerText()));
  await T(p, `membership-edit-relation:${mobile.id}`).click(); await T(p, `membership-relation-input:${mobile.id}`).fill("1x");
  check("MEM06 a malformed relation is refused in the form and the save stays disabled (nothing is sent)", /Quan hệ gồm/.test(await T(p, `membership:${mobile.id}`).innerText()) && await T(p, `membership-relation-save:${mobile.id}`).isDisabled());
  await p.close(); }
{ const p = await open("emp");
  await openDetail(p, "u05");
  const opts = await T(p, "membership-add-unit").locator("option").allInnerTexts();
  check("MEM07 adding a unit: only ACTIVE units the person is not in already are offered (not 'Web', not the archived 'Phòng cũ'); the relation defaults to MEMBER and is free text with suggestions", opts.some((o) => /Nhân sự/.test(o)) && !opts.some((o) => /Web|Phòng cũ/.test(o)) && (await T(p, "membership-add-relation").inputValue()) === "MEMBER");
  await T(p, "membership-add-unit").selectOption("hr"); await T(p, "membership-add-relation").fill("manager"); await T(p, "membership-add-submit").click(); await settle(p, 500);
  const am = (await of(p, "addMembership"))[0];
  check("MEM08 ONE addMembership call: the unit id, the relation normalised to 'MANAGER', no primary flag (the person already has a primary); the new membership is listed", am && am.args[1].organizationUnitId === "hr" && am.args[1].relationType === "MANAGER" && !("primary" in am.args[1]) && (await T(p, "detail-memberships").locator("li.xp-memberItem").count()) === 2 && /Nhân sự/.test(await T(p, "detail-memberships").innerText()));
  const wm = (await server(p)).employees.find((e) => e.userId === "u05").organizationMemberships.find((m) => m.organizationUnitId === "web");
  await T(p, `position-add-select:${wm.id}`).selectOption("p-eng"); await T(p, `position-add-grade:${wm.id}`).selectOption("g-jr"); await T(p, `position-add-submit:${wm.id}`).click(); await settle(p, 500);
  const ap = (await of(p, "addEmployeePosition"))[0];
  check("MEM09 a position is held WITHIN a membership: ONE addEmployeePosition with that membership's id, the position and the grade; it is listed under Web with its grade", ap && ap.args[1].membershipId === wm.id && ap.args[1].positionId === "p-eng" && ap.args[1].gradeId === "g-jr" && /Kỹ sư/.test(await T(p, `membership:${wm.id}`).innerText()) && /Junior/.test(await T(p, `membership:${wm.id}`).innerText()));
  const held = (await server(p)).employees.find((e) => e.userId === "u05").positions.find((x) => x.positionId === "p-eng");
  await T(p, `position-grade:${held.id}`).selectOption("g-sr"); await settle(p, 500);
  const ug = (await of(p, "updateEmployeePosition")).at(-1);
  check("MEM10 changing the grade of a held position: {gradeId} with the holding's version; the new grade shows", ug.args[2].gradeId === "g-sr" && ug.args[2].expectedVersion === held.version && /Senior/.test(await T(p, `membership:${wm.id}`).innerText()));
  await T(p, `position-grade:${held.id}`).selectOption(""); await settle(p, 500);
  const cg = (await of(p, "updateEmployeePosition")).at(-1);
  check("MEM11 'Không cấp bậc' sends {clearGrade: true} (not a null grade)", cg.args[2].clearGrade === true && !("gradeId" in cg.args[2]));
  const heldOpts = await T(p, `position-add-select:${wm.id}`).locator("option").allInnerTexts();
  check("MEM12 a position already held in that unit is not offered again; a disabled position is never offered", !heldOpts.some((o) => /^Kỹ sư$/.test(o)));
  await p.close(); }
{ const p = await open("emp");
  await openDetail(p, "u01");
  const fl = (await server(p)).employees.find((e) => e.userId === "u01").organizationMemberships.find((m) => m.organizationUnitId === "flutter");
  await T(p, `membership-remove:${fl.id}`).click(); const dlg = p.getByRole("dialog", { name: /Gỡ khỏi/ }); await dlg.waitFor();
  check("MEM13 removing a membership needs an IN-APP confirmation (a dialog named after the unit) — never a native confirm", (await dlg.count()) === 1 && /Gỡ khỏi “Flutter Team”/.test(await dlg.innerText()));
  await T(p, "emp-membership-dialog-confirm").click(); await settle(p, 450);
  check("MEM14 the SERVER refuses while the person still holds a position there (EMPLOYEE_ORG_HAS_POSITIONS): the confirmation stays with that reason, the membership is still on the server, nothing says it was removed", (await T(p, "emp-membership-dialog-problem").getAttribute("data-kind")) === "blocked" && /vị trí/.test(await T(p, "emp-membership-dialog-problem").innerText()) && (await emp(p, "u01")).organizationMemberships.some((m) => m.id === fl.id) && (await T(p, "detail-ok").count()) === 0);
  await p.getByRole("button", { name: "Hủy" }).click(); await dlg.waitFor({ state: "detached" });
  check("MEM15 cancelling sends nothing more (one removeMembership attempt in total) and focus returns to the button that opened the dialog", (await of(p, "removeMembership")).length === 1 && (await focusedId(p)) === `membership-remove:${fl.id}`);
  const pos = (await emp(p, "u01")).positions[0];
  await T(p, `position-remove:${pos.id}`).click(); await T(p, "emp-position-dialog").waitFor(); await T(p, "emp-position-dialog-confirm").click(); await T(p, "emp-position-dialog").waitFor({ state: "detached" }); await settle(p, 500);
  check("MEM16 removing the position first (in-app confirmation, ONE call with its version) leaves the membership with no position", (await of(p, "removeEmployeePosition")).length === 1 && (await emp(p, "u01")).positions.length === 0);
  await T(p, `membership-remove:${fl.id}`).click(); await T(p, "emp-membership-dialog-confirm").click(); await T(p, "emp-membership-dialog").waitFor({ state: "detached" }); await settle(p, 500);
  const e1 = await emp(p, "u01");
  check("MEM17 now the membership is removed (ONE more call, with its version): the server holds only Mobile, which became the primary one on its own; the screen shows exactly that", (await of(p, "removeMembership")).length === 2 && e1.organizationMemberships.length === 1 && e1.organizationMemberships[0].organizationUnitId === "mobile" && e1.organizationMemberships[0].primary === true && (await T(p, "detail-memberships").locator("li.xp-memberItem").count()) === 1);
  await p.close(); }
{ const p = await open("emp");
  await openDetail(p, "u01");
  const mobile = (await emp(p, "u01")).organizationMemberships.find((m) => m.organizationUnitId === "mobile");
  await p.evaluate((id) => window.__fake.touch("membership", id), mobile.id);       // someone else changed that membership
  await T(p, `membership-make-primary:${mobile.id}`).click(); await settle(p, 450);
  check("MEM18 a stale membership write (someone else changed it) is refused as a version conflict with a reload button; the person is not told it worked", (await T(p, "emp-problem").getAttribute("data-kind")) === "version" && (await T(p, "emp-problem").innerText()).includes("chưa được lưu") && (await T(p, "detail-ok").count()) === 0 && (await T(p, `membership:${mobile.id}`).getAttribute("data-primary")) === "false");
  await T(p, "org-reload-stale").click(); await settle(p, 500); await T(p, `membership-make-primary:${mobile.id}`).click(); await settle(p, 500);
  const u = (await of(p, "updateMembership")).at(-1);
  check("MEM19 after a reload the retry carries the server's CURRENT version and succeeds", u.args[2].expectedVersion === mobile.version + 1 && (await T(p, `membership:${mobile.id}`).getAttribute("data-primary")) === "true");
  await p.close(); }

// ===================================================================================================================== ENABLE / DISABLE
{ const p = await open("emp");
  await openDetail(p, "u02");
  check("EMP_UI07 the account switch is a real operation now: 'Tắt tài khoản' is available (EMPLOYEE_MANAGE + TENANT_MEMBERS), no 'not connected' wording", (await T(p, "detail-toggle").getAttribute("aria-disabled")) === null && /Tắt tài khoản/.test(await T(p, "detail-toggle").innerText()) && !/Chưa sẵn sàng/.test(await T(p, "emp-detail").innerText()));
  await T(p, "detail-toggle").click(); const dlg = p.getByRole("dialog", { name: /Tắt tài khoản/ }); await dlg.waitFor();
  await p.getByRole("button", { name: "Hủy" }).click(); await dlg.waitFor({ state: "detached" }); await settle(p, 200);
  check("EMP_UI07b CANCEL sends nothing: no disable call, the employee is still active on the server, and focus returns to the switch", (await names(p)).filter((n) => n === "disableEmployee").length === 0 && (await emp(p, "u02")).active === true && (await focusedId(p)) === "detail-toggle");
  await T(p, "detail-toggle").click(); await T(p, "emp-status-dialog-confirm").click(); await T(p, "emp-status-dialog").waitFor({ state: "detached" }); await settle(p, 500);
  check("EMP_UI07c CONFIRM disables it: ONE call, the server holds active=false, the pill says 'Đã tắt', the button now offers 'Bật tài khoản', the directory row follows", (await of(p, "disableEmployee")).length === 1 && (await emp(p, "u02")).active === false && /Đã tắt/.test(await T(p, "emp-detail").innerText()) && /Bật tài khoản/.test(await T(p, "detail-toggle").innerText()) && /Đã tắt/.test(await T(p, "emp:u02").innerText()));
  await T(p, "detail-toggle").click(); await T(p, "emp-status-dialog-confirm").click(); await T(p, "emp-status-dialog").waitFor({ state: "detached" }); await settle(p, 500);
  check("EMP_UI07d enabling it again works the same way (ONE enable call, active=true on the server)", (await of(p, "enableEmployee")).length === 1 && (await emp(p, "u02")).active === true && /Hoạt động/.test(await T(p, "emp:u02").innerText()));
  await p.close(); }
{ const p = await open("emp");
  await T(p, "emp-status").selectOption("INACTIVE"); await settle(p, 500);
  check("EMP_UI07e a disabled account is listed with 'Đã tắt': the status filter 'Đã tắt' lists exactly u07 and u08", (await rows(p)) === 2 && /2 nhân viên/.test(await T(p, "emp-count").innerText()) && /Đã tắt/.test(await T(p, "emp:u07").innerText()) && /Đã tắt/.test(await T(p, "emp:u08").innerText()));
  await p.close(); }
{ const p = await open("emp", "viewer");
  await openDetail(p, "u01");
  check("EMP_UI07f a viewer reads the detail (memberships, positions, relation labels) but cannot change anything: the switch is unavailable with its reason, no add / remove / make-primary controls, and TENANT_MEMBERS alone did not unlock it", (await T(p, "detail-toggle").getAttribute("aria-disabled")) === "true" && /quyền quản lý nhân viên/.test(await T(p, "emp-detail").locator(".xp-reason").innerText()) && (await T(p, "membership-add-form").count()) === 0 && (await p.locator('[data-testid^="membership-remove:"], [data-testid^="membership-make-primary:"], [data-testid^="position-remove:"]').count()) === 0 && /MANAGER/.test(await T(p, "detail-memberships").innerText()) && (await T(p, "detail-org-not-ready").count()) === 1);
  await p.close(); }

// ===================================================================================================================== LIMITS, 501, 5xx, EMPTY, PAGE EMPTY
{ const p = await open("emp", "emp-huge");
  check("LIM01 5 000 000 rows: the pager shows page 1/250000, and says that only the first 10 000 results can be browsed (narrow the search), instead of offering unlimited paging", /Trang 1\/250000 · 5000000 nhân viên/.test(await T(p, "emp-count").innerText()) && (await T(p, "emp-paging-capped").count()) === 1 && /10\.000 kết quả đầu tiên/.test(await T(p, "emp-paging-capped").innerText()) && /thu hẹp tìm kiếm/.test(await T(p, "emp-paging-capped").innerText()));
  // click "Sau" until it is disabled: the last page the server accepts is page 500 (offset 10 000)
  const reached = await p.evaluate(async () => {
    const label = () => document.querySelector('[data-testid="emp-count"]')?.textContent ?? ""; let clicks = 0;
    for (let i = 0; i < 700; i++) {
      const next = document.querySelector('[data-testid="emp-next"]'); if (!next || next.disabled) break;
      const before = label(); next.click(); clicks++;
      for (let w = 0; w < 200 && label() === before; w++) await new Promise((r) => setTimeout(r, 10));
    }
    await new Promise((r) => setTimeout(r, 200)); return { clicks, label: label(), disabled: document.querySelector('[data-testid="emp-next"]')?.disabled };
  });
  const srv = await server(p); const reqs = (await lists(p));
  check("LIM02 'Sau' stops at page 501 (index 500, offset 10 000): 500 clicks, then the button is disabled — the UI never walks into the server's offset limit", reached.clicks === 500 && reached.disabled === true && /Trang 501\/250000/.test(reached.label), JSON.stringify(reached));
  check("LIM03 across all those requests the client NEVER asked for more than the server accepts: every request has size <= 100 and page * size <= 10 000, and the (stricter) fake server never had to refuse one", reqs.length >= 501 && reqs.every((q) => q.size >= 1 && q.size <= 100 && q.page >= 0 && q.page * q.size <= 10000) && srv.refused.length === 0, `requests=${reqs.length} refused=${srv.refused.join(",")}`);
  await p.close(); }
{ const p = await open("emp", "off");
  check("OFF05 the directory with the store OFF (501): 'chưa khả dụng' in words, NO table, NO rows, the only action is 'Kiểm tra lại'", (await T(p, "emp-error").getAttribute("data-kind")) === "unavailable-feature" && /chưa được bật trên máy chủ/.test(await T(p, "emp-error").innerText()) && (await T(p, "emp-table").count()) === 0 && /Kiểm tra lại/.test(await T(p, "emp-retry").innerText()));
  check("OFF06 no silent fallback: no call to any other route (no member list), no provisioning call, and no 'Chưa có nhân viên' empty state that would look like an empty company", (await prov(p)).length === 0 && (await names(p)).every((n) => ["listEmployees", "unitTree", "listPositions", "listGrades"].includes(n)) && (await T(p, "emp-empty").count()) === 0);
  await p.close(); }
{ const p = await open("emp", "emp-down");
  check("EMP_UI08e backend error (500): an error state with a retry button, not a blank list", (await T(p, "emp-error").getAttribute("data-kind")) === "unavailable" && (await T(p, "emp-retry").count()) === 1);
  await p.close(); }
{ const p = await open("emp", "emp-empty");
  check("EMP_UI08f no employees at all: an explanation pointing to 'Thêm nhân viên'", /Thêm nhân viên đầu tiên/.test(await T(p, "emp-empty").innerText()) && (await T(p, "emp-table").count()) === 0);
  await p.close(); }
{ const p = await open("emp", "emp-pageempty");
  await T(p, "emp-next").click(); await settle(p, 500);
  check("EMP_UI08g a page that no longer exists (the list shrank) is said, with the total and a way back, not an empty table", (await T(p, "emp-page-empty").count()) === 1 && (await T(p, "emp-first-page").count()) === 1);
  await T(p, "emp-first-page").click(); await settle(p, 500);
  check("EMP_UI08h 'Về trang đầu' reloads page 1", (await rows(p)) === 20);
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
{ const p = await open("emp", "viewer");
  check("ORG_CODE03 a viewer opens the directory but cannot add an employee: unavailable with the reason (EMPLOYEE_MANAGE + TENANT_MEMBERS needed)", (await T(p, "emp-table").count()) === 1 && (await T(p, "emp-create").getAttribute("aria-disabled")) === "true");
  await p.close(); }
{ const p = await open("emp", "emp-nocreate");
  check("EMP_NR01 when the account route is not available the create button is unavailable with the reason; the directory still works", (await T(p, "emp-create").getAttribute("aria-disabled")) === "true" && (await rows(p)) === 20);
  await p.close(); }

// ===================================================================================================================== RESPONSIVE, KEYBOARD, A11Y
{ const p = await open("emp", "ok", { width: 390, height: 800 });
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check("EMP_UI09 responsive (390 px): the table becomes cards (header hidden, cells stacked), no horizontal page scroll, the filters and the create button are reachable", overflow <= 1 && (await p.locator("thead").evaluate((e) => getComputedStyle(e).display)) === "none" && (await T(p, "emp:u01").evaluate((e) => getComputedStyle(e).display)) === "block" && (await T(p, "emp-create").isVisible()) && (await T(p, "emp-search").isVisible()), `overflow=${overflow}`);
  await openDetail(p, "u01");
  const ov2 = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth); const box = await T(p, "emp-detail").boundingBox();
  check("EMP_UI09b the employee detail with memberships and positions fits 390 px: no horizontal scroll, the dialog stays inside the viewport", ov2 <= 1 && box !== null && box.x >= -1 && box.x + box.width <= 391, `overflow=${ov2} box=${JSON.stringify(box)}`);
  await p.close(); }
{ const p = await open("emp");
  await T(p, "emp-search").focus(); for (let i = 0; i < 6; i++) { if (/^emp:/.test((await focusedId(p)) ?? "")) break; await p.keyboard.press("Tab"); }
  const rowFocus = await focusedId(p);
  await p.keyboard.press("Enter"); await T(p, "emp-detail").waitFor();
  const inside = async () => p.evaluate(() => !!document.activeElement?.closest("[data-testid=emp-detail]"));
  let trapped = await inside(); for (let i = 0; i < 8; i++) { await p.keyboard.press("Tab"); trapped = trapped && (await inside()); } for (let i = 0; i < 8; i++) { await p.keyboard.press("Shift+Tab"); trapped = trapped && (await inside()); }
  check("EMP_UI10 keyboard: Tab reaches the rows, Enter opens the detail; focus stays inside the dialog through 16 Tab / Shift+Tab presses (trap)", /^emp:/.test(rowFocus ?? "") && (await T(p, "emp-detail").count()) === 1 && trapped, String(rowFocus));
  await p.keyboard.press("Escape"); await settle(p, 250);
  check("EMP_UI10a Escape closes the detail and focus is back on the row that opened it", (await T(p, "emp-detail").count()) === 0 && /^emp:/.test((await focusedId(p)) ?? ""), String(await focusedId(p)));
  const v = await axe(p); check("EMP_UI10b axe (wcag2a/aa, critical + serious) finds nothing on the directory", v.length === 0, v.join(","));
  await T(p, "emp:u01").click(); await T(p, "emp-detail").waitFor(); await settle(p, 300); const v2 = await axe(p, "[data-testid=emp-detail]");
  check("EMP_UI10c …nor in the employee detail with memberships, relation labels and positions", v2.length === 0, v2.join(","));
  check("EMP_UI10d controls are labelled (search, unit / position / grade / status filters) and the table has column headers", (await p.getByLabel("Tìm nhân viên").count()) >= 1 && (await p.getByLabel("Lọc theo đơn vị").count()) === 1 && (await p.getByLabel("Lọc theo vị trí").count()) === 1 && (await p.getByLabel("Lọc theo cấp bậc").count()) === 1 && (await p.getByLabel("Lọc theo trạng thái").count()) === 1);
  await p.keyboard.press("Escape"); await T(p, "emp-create").click(); await T(p, "create-account").waitFor(); const v3 = await axe(p, "[data-testid=create-account]");
  check("EMP_UI10e the create dialog with the organization fields is accessible (axe)", v3.length === 0, v3.join(","));
  await p.close(); }
{ const p = await open("emp");
  await T(p, "emp-catalog").click(); await T(p, "catalog-dialog").waitFor(); await settle(p, 300);
  check("EMP_UI11 the catalog of positions and grades is reachable from the directory (POSITION_GRADE_VIEW) and lists both", (await T(p, "catalog-position-list").locator("li").count()) === 2 && (await T(p, "catalog-grade-list").locator("li").count()) === 2);
  await p.keyboard.press("Escape"); await settle(p, 200);
  check("EMP_UI11b closing it returns focus to the button that opened it", (await focusedId(p)) === "emp-catalog");
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
finish();
