// @class: harness — real Chromium on the organization screens (tree, units, types, positions / grades) with an in-page FAKE in-memory transport behind the REAL service (no backend). Proves what the SCREENS do with the contract's answers.
// NOT a backend E2E: the fake is tests/browser/org-fake-server.ts (it answers the way docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md says). The real-backend proof is tests/e2e-real/flows/e2e-org01.mjs against a flag-ON stack.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/org.spec.mjs   (the employee directory: org-employees.spec.mjs)
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
import { harnessOrigin, launch, makeChecks } from "./lib/spec.mjs";
const AXE = require.resolve("axe-core/axe.min.js");
const ORIGIN = harnessOrigin();
const { check, finish } = makeChecks();
const errors = [];
const browser = await launch();
const T = (p, id) => p.getByTestId(id);
async function open(v, s = "ok", viewport = { width: 1200, height: 900 }) {
  const p = await browser.newPage({ viewport }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/org.html?v=${v}&s=${s}`); await p.waitForTimeout(350); return p;
}
const calls = (p) => p.evaluate(() => window.__org); const names = async (p) => (await calls(p)).map((c) => c.name);
const of = async (p, name) => (await calls(p)).filter((c) => c.name === name);
const server = (p) => p.evaluate(() => { const s = window.__fake.state(); return { units: s.units, types: s.types, positions: s.positions, grades: s.grades, refused: s.refusedLimits }; });
const unitOnServer = async (p, id) => (await server(p)).units.find((u) => u.id === id);
const node = (p, id) => T(p, `node:${id}`);
const nodeName = async (p, id) => (await node(p, id).locator(".xp-nodeText b").innerText());
const level = (p, id) => p.locator(`[data-node="${id}"]`).locator("xpath=ancestor::li[@role='treeitem'][1]").getAttribute("aria-level");
const pickType = (p, dlg, v) => p.getByTestId(dlg).locator("select.srOnly").first().selectOption(v);
const settle = (p, ms = 400) => p.waitForTimeout(ms);
const key = async (p, k) => { await p.keyboard.press(k); await p.waitForTimeout(90); };
const focusedId = (p) => p.evaluate(() => document.activeElement?.getAttribute("data-testid") ?? document.activeElement?.tagName);
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); };
const addUnit = async (p, { parent, name, code, type }) => { if (parent) { await node(p, parent).click(); await T(p, "org-add-child").click(); } else await T(p, "org-add-root").click(); await T(p, "unit-dialog").waitFor(); await T(p, "unit-name").fill(name); await T(p, "unit-code").fill(code); await pickType(p, "unit-dialog", type); await T(p, "unit-submit").click(); };

// ===================================================================================================================== TREE AND THE TWO COUNTS
{ const p = await open("org");
  const items = await p.getByRole("treeitem").count(); const tree = T(p, "org-tree");
  check("ORG_UI01 tree render: a WAI-ARIA tree of the company's own active units (5; the archived 'Phòng cũ' is not shown), nested by parent, with aria-level 1/2/3", (await tree.getAttribute("role")) === "tree" && items === 5 && (await level(p, "tech")) === "1" && (await level(p, "mobile")) === "2" && (await level(p, "flutter")) === "3");
  check("ORG_UI01b each node shows icon tile, name, TYPE badge and BOTH counts with their own words ('trực tiếp' / 'cả nhánh') plus the number of child units; nothing about the levels is hard-coded", (await node(p, "flutter").locator(".xp-typeBadge").innerText()) === "Team" && /14 trực tiếp · 14 cả nhánh/.test(await node(p, "flutter").innerText()) && /2 đơn vị con/.test(await node(p, "tech").innerText()) && (await node(p, "tech").locator(".xp-nodeIcon svg").count()) === 1);
  const mobile = await T(p, "counts:mobile").innerText(); const tech = await T(p, "counts:tech").innerText();
  check("COUNT01 the subtree count is DISTINCT people, not a sum: Mobile has 1 direct member who is also in Flutter Team → 14 in the branch (not 15); Khối Công nghệ → 28 (not 0+1+14+14 = 29)", /^1 trực tiếp · 14 cả nhánh · 1 đơn vị con$/.test(mobile) && /^0 trực tiếp · 28 cả nhánh · 2 đơn vị con$/.test(tech), `${mobile} | ${tech}`);
  await node(p, "tech").locator(".xp-chev").click();
  check("ORG_UI01c collapse hides the subtree and sets aria-expanded=false; expand brings it back", (await p.getByRole("treeitem").count()) === 2 && (await p.locator('li[role=treeitem]:has(> [data-node="tech"])').first().getAttribute("aria-expanded")) === "false" && (await node(p, "tech").locator(".xp-chev").click(), (await p.getByRole("treeitem").count()) === 5));
  check("ORG_UI01d nothing is sent just to look: only the two list calls (tree + types), no write, no other route", JSON.stringify((await names(p)).sort()) === JSON.stringify(["listUnitTypes", "unitTree"]), (await names(p)).join(","));
  await p.close(); }

{ const p = await open("org");
  await node(p, "mobile").click(); await settle(p, 250);
  const dt = await T(p, "org-detail").locator("dt").allInnerTexts(); const direct = await T(p, "detail-direct-count").innerText(); const sub = await T(p, "detail-subtree-count").innerText();
  check("COUNT02 the detail shows the two counts as two DIFFERENT labelled facts: 'Thành viên trực tiếp' = 1 and 'Nhân viên cả nhánh' = 14; only the branch count is called 'nhân viên'", dt.includes("Thành viên trực tiếp") && dt.includes("Nhân viên cả nhánh") && /^1\b/.test(direct) && /^14\b/.test(sub) && !dt.some((t) => /nhân viên/i.test(t) && t !== "Nhân viên cả nhánh"), dt.join("|"));
  const titles = await p.locator("dt[title]").evaluateAll((l) => l.map((x) => x.getAttribute("title")).join(" "));
  check("COUNT03 each count explains itself in visible text AND a tooltip: direct = exactly this unit (child units not counted); branch = distinct people incl. child units, a person in two units counts once", /đúng đơn vị này/.test(direct) && /không trùng người/.test(sub) && /không tính đơn vị con/.test(titles) && /chỉ tính một lần/.test(titles), titles);
  check("COUNT04 the detail's breadcrumb is the SERVER's path (getUnit), and the detail call was made for the selected unit", /Khối Công nghệ › Mobile/.test(await T(p, "detail-path").innerText()) && (await of(p, "getUnit")).some((c) => c.args[0] === "mobile"));
  await p.close(); }

// ===================================================================================================================== ADD / EDIT / MOVE
{ const p = await open("org");
  await T(p, "org-add-root").click(); await T(p, "unit-dialog").waitFor();
  await T(p, "unit-submit").click();
  const t = await T(p, "unit-dialog").innerText();
  check("ORG_UI02 add root: empty name, empty code and no type are each refused in the form, in words, and NOTHING is sent", /Hãy nhập tên đơn vị/.test(t) && /mã đơn vị/i.test(t) && /chọn loại/.test(t) && !(await names(p)).includes("createUnit"));
  await T(p, "unit-name").fill("Chi nhánh Hà Nội"); await T(p, "unit-code").fill("hn"); await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-submit").click();
  check("ORG_UI02b a type that cannot be a root (Phòng sits inside a Khối) is refused before sending, in words", /phải nằm trong: Khối/.test(await T(p, "unit-type-error").innerText()) && !(await names(p)).includes("createUnit"));
  await pickType(p, "unit-dialog", "t-div"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const c = await of(p, "createUnit");
  check("ORG_UI02c ONE create call: parentId null, the chosen type, the trimmed name and the code; the new root appears in the tree and is selected", c.length === 1 && c[0].args[0].parentId === null && c[0].args[0].typeId === "t-div" && c[0].args[0].name === "Chi nhánh Hà Nội" && c[0].args[0].code === "hn" && (await p.getByRole("treeitem").count()) === 6 && /Chi nhánh Hà Nội/.test(await T(p, "detail-name").innerText()), JSON.stringify(c[0]?.args));
  check("ORG_UI02d the server canonicalised the code (upper-case) and the screen shows what the server holds; the flash says it was added", (await T(p, "detail-code").innerText()) === "HN" && /Đã thêm đơn vị/.test(await T(p, "org-flash").innerText()));
  await p.close(); }

{ const p = await open("org");
  await node(p, "tech").click(); await T(p, "org-add-child").click(); await T(p, "unit-dialog").waitFor();
  check("ORG_UI03 add child: the dialog names the parent path", /Nằm trong: Khối Công nghệ/.test(await T(p, "unit-dialog").innerText()));
  await T(p, "unit-name").fill("Data"); await T(p, "unit-code").fill("DATA"); await pickType(p, "unit-dialog", "t-team"); await T(p, "unit-submit").click();
  check("ORG_UI03b a Team directly under a Khối is refused (the type rule), nothing sent", /chỉ đặt được trong: Phòng, Team/.test(await T(p, "unit-type-error").innerText()) && !(await names(p)).includes("createUnit"));
  await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const c = await of(p, "createUnit");
  check("ORG_UI03c ONE create call with parentId 'tech' and the code; the child is nested under its parent and the parent now says 3 child units", c.length === 1 && c[0].args[0].parentId === "tech" && c[0].args[0].code === "DATA" && (await p.getByRole("treeitem").count()) === 6 && /3 đơn vị con/.test(await node(p, "tech").innerText()));
  await node(p, "tech").click(); await T(p, "org-add-child").click(); await T(p, "unit-name").fill("Data 2"); await T(p, "unit-code").fill("data"); await pickType(p, "unit-dialog", "t-dept"); await T(p, "unit-submit").click(); await settle(p, 300);
  check("ORG_UI03d a code already used by a sibling (compared upper-case) is refused by the SERVER's code and shown as such: the dialog stays open with what was typed, the tree is unchanged", (await T(p, "org-problem").getAttribute("data-kind")) === "duplicate" && (await T(p, "unit-name").inputValue()) === "Data 2" && (await p.getByRole("treeitem").count()) === 6);
  await p.close(); }

{ const p = await open("org");
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-dialog").waitFor();
  check("ORG_UI04 edit: prefilled with the unit's name and code; the TYPE is shown read-only (it never changes after creation)", (await T(p, "unit-name").inputValue()) === "Web" && (await T(p, "unit-code").inputValue()) === "WEB" && /Phòng/.test(await T(p, "unit-type-fixed").innerText()) && (await T(p, "unit-dialog").locator("select.srOnly").count()) === 0);
  await T(p, "unit-name").fill("Web & Portal"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const u = await of(p, "updateUnit");
  check("ORG_UI04b ONE update call carries the unit's VERSION (optimistic lock, starts at 0) and the patch; the tree shows the new name", u.length === 1 && u[0].args[0] === "web" && u[0].args[1].expectedVersion === 0 && u[0].args[1].name === "Web & Portal" && (await nodeName(p, "web")) === "Web & Portal", JSON.stringify(u[0]?.args));
  check("ORG_UI04c there is no enable / disable switch any more: a unit is archived and restored, never 'tắt'", (await T(p, "org-toggle").count()) === 0 && (await T(p, "org-delete").count()) === 0 && (await T(p, "org-archive").count()) === 1);
  await p.close(); }

{ const p = await open("org");
  await node(p, "flutter").click(); await T(p, "org-move").click(); await T(p, "move-dialog").waitFor();
  const dis = async (id) => T(p, `move-to:${id}`).locator("input").isDisabled();
  check("ORG_UI05 move dialog: where the unit may go, with the reason when it may not (itself, 'already here', type rule: a Team cannot sit under a Khối or at the root)", (await dis("flutter")) && (await dis("mobile")) && (await dis("hr")) && (await dis("root")) && !(await dis("web")) && /Đang ở đây/.test(await T(p, "move-to:mobile").innerText()));
  check("ORG_UI05b the submit stays disabled until a destination is chosen", await T(p, "move-submit").isDisabled());
  await T(p, "move-to:web").locator("input").check(); await T(p, "move-submit").click(); await T(p, "move-dialog").waitFor({ state: "detached" }); await settle(p, 400);
  const m = await of(p, "moveUnit");
  check("ORG_UI05c ONE move call (id, newParentId 'web', expectedVersion); the unit is now under Web and the detail path (from the server) says so", m.length === 1 && m[0].args[0] === "flutter" && m[0].args[1].newParentId === "web" && m[0].args[1].expectedVersion === 0 && /Khối Công nghệ › Web › Flutter Team/.test(await T(p, "org-detail").innerText()), JSON.stringify(m[0]?.args));
  await T(p, "org-reload").click(); await settle(p, 300);
  check("ORG_UI05d after a reload the new place persists and the subtree moved with it (Mobile has no child, Web has one) and the counts followed (Web: 14 direct… branch 14 → still the same people)", (await node(p, "web").innerText()).includes("1 đơn vị con") && !(await node(p, "mobile").innerText()).includes("đơn vị con"));
  await p.close(); }

{ // moving to the ROOT sends an explicit null: first make a Khối under Công nghệ (a Khối may sit anywhere), then move it to the root
  const p = await open("org");
  await addUnit(p, { parent: "tech", name: "Khối con", code: "SUB", type: "t-div" }); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  await T(p, "org-move").click(); await T(p, "move-dialog").waitFor(); await T(p, "move-to:root").locator("input").check(); await T(p, "move-submit").click(); await T(p, "move-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const m = (await of(p, "moveUnit")).at(-1); const sent = JSON.stringify(m?.args[1]);
  check("ORG_UI05e moving to the root sends `newParentId: null` EXPLICITLY (the key is present; an absent key would be a 400 on the server, never a silent move)", m && "newParentId" in m.args[1] && m.args[1].newParentId === null && sent.includes('"newParentId":null'), sent);
  check("ORG_UI05f the unit is a root now (aria-level 1)", (await level(p, m.args[0])) === "1");
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

// ===================================================================================================================== MAX DEPTH: THE BACKEND DECIDES
{ const p = await open("org");
  // the company's own limit lives on the type: create a type "Cấp 2" with maxDepth 2 through the types dialog
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); await T(p, "type-name").fill("Cấp hai"); await T(p, "type-code").fill("lvl2"); await T(p, "rule-maxdepth").fill("2"); await T(p, "type-submit").click(); await settle(p, 400); await p.keyboard.press("Escape"); await settle(p, 200);
  await node(p, "mobile").click(); await T(p, "org-add-child").click(); await T(p, "unit-dialog").waitFor(); await T(p, "unit-name").fill("Quá sâu"); await T(p, "unit-code").fill("DEEP"); await pickType(p, "unit-dialog", (await server(p)).types.find((t) => t.code === "lvl2").id); await settle(p, 200);
  const adv = await T(p, "unit-depth-advisory").innerText();
  check("DEPTH01 the company's own limit is an ADVISORY note, in words, coming from the type's data (maxDepth 2 from the server): 'tối đa 2 cấp … cấp 3 … Máy chủ sẽ kiểm tra'", /tối đa 2 cấp/.test(adv) && /cấp 3/.test(adv) && /Máy chủ sẽ kiểm tra/.test(adv), adv);
  await T(p, "unit-submit").click(); await settle(p, 400);
  const c = await of(p, "createUnit");
  check("DEPTH02 the advisory did NOT block the request (the form sent it) and the SERVER's ORG_TYPE_RULE_VIOLATION (MAX_DEPTH) is what the person sees: 'rule' state, 'độ sâu tối đa', dialog stays, nothing created", c.length === 1 && (await T(p, "org-problem").getAttribute("data-kind")) === "rule" && /độ sâu tối đa/.test(await T(p, "org-problem").innerText()) && (await T(p, "unit-name").inputValue()) === "Quá sâu" && !(await server(p)).units.some((u) => u.code === "DEEP"));
  await p.close(); }
{ const p = await open("org", "rule");
  await addUnit(p, { parent: "tech", name: "Phòng mới", code: "NEW1", type: "t-dept" }); await settle(p, 400);
  check("DEPTH03 a depth refusal on create or move is shown as a rule violation whatever the UI thought (no local maximum is built in)", (await T(p, "org-problem").getAttribute("data-kind")) === "rule" && /độ sâu tối đa/.test(await T(p, "org-problem").innerText()));
  await p.keyboard.press("Escape"); await node(p, "flutter").click(); await T(p, "org-move").click(); await T(p, "move-to:web").locator("input").check(); await T(p, "move-submit").click(); await settle(p, 300);
  check("DEPTH04 …and on a move (the server checks the subtree): the dialog stays, the unit is where it was", (await T(p, "org-problem").getAttribute("data-kind")) === "rule" && (await level(p, "flutter")) === "3");
  await p.close(); }

// ===================================================================================================================== ARCHIVE / RESTORE (no hard delete)
{ const p = await open("org");
  await node(p, "hr").click();
  check("ARCH01 archiving is ADVISORY-hinted from the counts the screen knows (15 direct members) but the button stays available: the server decides", /15 thành viên trực tiếp/.test(await T(p, "org-archive-hint").innerText()) && (await T(p, "org-archive").isEnabled()));
  await T(p, "org-archive").click(); const dlg = p.getByRole("dialog", { name: "Lưu trữ Nhân sự" }); await dlg.waitFor();
  check("ARCH02 an IN-APP confirmation dialog (role=dialog, named after the unit) — never a native confirm — says it is reversible and shows the advisory hint", (await dlg.count()) === 1 && /không bị xóa/.test(await dlg.innerText()) && /15 thành viên/.test(await T(p, "archive-hint").innerText()));
  await T(p, "archive-confirm").click(); await settle(p, 350);
  check("ARCH03 the SERVER refuses (the unit still has members): the dialog STAYS with the 'blocked' reason, the unit is still active on the server and in the tree, no success flash", (await T(p, "archive-problem").getAttribute("data-kind")) === "blocked" && /thành viên/.test(await T(p, "archive-problem").innerText()) && (await unitOnServer(p, "hr")).active === true && (await T(p, "org-flash").count()) === 0 && (await p.getByRole("treeitem").count()) === 5);
  await p.getByRole("button", { name: "Hủy" }).click(); await dlg.waitFor({ state: "detached" });
  check("ARCH04 Cancel closes the dialog and focus returns to the control that opened it (the 'Lưu trữ' button)", (await focusedId(p)) === "org-archive");
  await node(p, "tech").click(); await T(p, "org-archive").click(); await T(p, "archive-confirm").click(); await settle(p, 350);
  check("ARCH05 a unit with active children is refused with its own reason (ORG_UNIT_HAS_CHILDREN) — said in words, nothing archived", (await T(p, "archive-problem").getAttribute("data-kind")) === "blocked" && /đơn vị con/.test(await T(p, "archive-problem").innerText()) && (await unitOnServer(p, "tech")).active === true);
  await p.close(); }

{ const p = await open("org");
  await addUnit(p, { parent: "tech", name: "Tạm thời", code: "TMP", type: "t-dept" }); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const id = (await of(p, "createUnit")).length ? (await server(p)).units.find((u) => u.code === "TMP").id : null;
  await T(p, "org-archive").click(); const dlg = p.getByRole("dialog", { name: /Lưu trữ Tạm thời/ }); await dlg.waitFor();
  await p.getByRole("button", { name: "Hủy" }).click(); await dlg.waitFor({ state: "detached" }); await settle(p, 200);
  check("ARCH06 CANCEL sends nothing: no archive call, the unit is unchanged on the server and still in the tree", (await names(p)).filter((n) => n === "archiveUnit").length === 0 && (await unitOnServer(p, id)).active === true && (await node(p, id).count()) === 1);
  await T(p, "org-archive").click(); await T(p, "archive-confirm").click(); await T(p, "archive-dialog").waitFor({ state: "detached" }); await settle(p, 400);
  const a = await of(p, "archiveUnit"); const srv = await unitOnServer(p, id);
  check("ARCH07 CONFIRM archives it: ONE call carrying the version; the server holds active=false with an archivedAt; the unit leaves the tree, the selection clears, the flash says so", a.length === 1 && a[0].args[0] === id && a[0].args[1] === 0 && srv.active === false && !!srv.archivedAt && (await node(p, id).count()) === 0 && (await T(p, "org-pick-hint").count()) === 1 && /Đã lưu trữ đơn vị/.test(await T(p, "org-flash").innerText()), JSON.stringify(a[0]?.args));
  await T(p, "org-show-archived").check(); await settle(p, 400);
  check("ARCH08 'Hiện cả đơn vị đã lưu trữ' reloads WITH includeArchived: the archived units (Tạm thời, Phòng cũ) are back in the tree marked 'Đã lưu trữ'", (await of(p, "unitTree")).at(-1).args[0] === true && (await T(p, `archived:${id}`).count()) === 1 && (await T(p, "archived:old").count()) === 1);
  await node(p, id).click();
  check("ARCH09 an archived unit offers ONLY 'Khôi phục' (no edit / move / add child / archive), the status says 'Đã lưu trữ', and there is no permanent delete anywhere", (await T(p, "org-restore").count()) === 1 && (await T(p, "org-edit").count()) === 0 && (await T(p, "org-move").count()) === 0 && (await T(p, "org-add-child").count()) === 0 && (await T(p, "org-archive").count()) === 0 && (await T(p, "org-delete").count()) === 0 && /Đã lưu trữ/.test(await T(p, "detail-status").innerText()));
  await T(p, "org-restore").click(); const rd = p.getByRole("dialog", { name: /Khôi phục Tạm thời/ }); await rd.waitFor(); await p.getByRole("button", { name: "Hủy" }).click(); await rd.waitFor({ state: "detached" }); await settle(p, 200);
  check("ARCH10 cancelling the restore sends nothing and the unit stays archived on the server", (await names(p)).filter((n) => n === "restoreUnit").length === 0 && (await unitOnServer(p, id)).active === false);
  await T(p, "org-restore").click(); await T(p, "restore-confirm").click(); await T(p, "restore-dialog").waitFor({ state: "detached" }); await settle(p, 400);
  const r = await of(p, "restoreUnit");
  check("ARCH11 confirming the restore: ONE call with the version the person saw (the archive bumped it to 1); the server holds the unit active again, the pill is gone and the actions are back", r.length === 1 && r[0].args[1] === 1 && (await unitOnServer(p, id)).active === true && (await T(p, `archived:${id}`).count()) === 0 && (await T(p, "org-edit").count()) === 1 && /Đã khôi phục/.test(await T(p, "org-flash").innerText()), JSON.stringify(r[0]?.args));
  await p.close(); }
{ const p = await open("org", "restore-conflict");
  await T(p, "org-show-archived").check(); await settle(p, 400); await node(p, "old").click(); await T(p, "org-restore").click(); await T(p, "restore-confirm").click(); await settle(p, 350);
  check("ARCH12 a restore the SERVER refuses (RESTORE_CONFLICT, reason PARENT_ARCHIVED) is said with that reason, the dialog stays and the unit stays archived on the server", (await T(p, "restore-problem").getAttribute("data-kind")) === "conflict" && /Khôi phục đơn vị cha/.test(await T(p, "restore-problem").innerText()) && (await unitOnServer(p, "old")).active === false);
  await p.close(); }
{ const p = await open("org");
  await T(p, "org-show-archived").check(); await settle(p, 400); await node(p, "old").click();
  check("ARCH13 the restore hint is advisory and only appears when the parent is archived (here the parent is active: no hint)", (await T(p, "org-restore-hint").count()) === 0);
  await p.close(); }

// ===================================================================================================================== STALE VERSION
{ const p = await open("org", "version");
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-name").fill("Web 2"); await T(p, "unit-submit").click(); await settle(p, 300);
  check("STALE01 a stale edit (409 VERSION_CONFLICT) is refused with 'chưa được lưu', a 'Tải lại cơ cấu' button, and the typed name stays in the form; nothing is overwritten", (await T(p, "org-problem").getAttribute("data-kind")) === "version" && /chưa được lưu/.test(await T(p, "org-problem").innerText()) && (await p.getByRole("button", { name: "Tải lại cơ cấu" }).count()) >= 1 && (await nodeName(p, "web")) === "Web");
  await p.close(); }
{ const p = await open("org");
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-name").fill("Web v2");
  await p.evaluate(() => window.__fake.touch("unit", "web"));      // someone else saved the unit meanwhile
  await T(p, "unit-submit").click(); await settle(p, 300);
  check("STALE02 someone else changed the unit while the dialog was open: the write is refused (409), the server's name is untouched and the person's input is still there", (await T(p, "org-problem").getAttribute("data-kind")) === "version" && (await unitOnServer(p, "web")).name === "Web" && (await T(p, "unit-name").inputValue()) === "Web v2");
  await T(p, "org-reload-stale").click(); await settle(p, 600);
  await node(p, "web").click(); await T(p, "org-edit").click(); await T(p, "unit-name").fill("Web v2"); await T(p, "unit-submit").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const u = (await of(p, "updateUnit")).at(-1);
  check("STALE03 after a reload the retry carries the server's CURRENT version (1) and succeeds", u.args[1].expectedVersion === 1 && (await unitOnServer(p, "web")).name === "Web v2" && (await nodeName(p, "web")) === "Web v2", JSON.stringify(u.args));
  await p.close(); }
{ const p = await open("org");
  await node(p, "web").click(); await T(p, "org-archive").click();
  await p.evaluate(() => window.__fake.touch("unit", "web")); await T(p, "archive-confirm").click(); await settle(p, 300);
  check("STALE04 a stale archive is refused as a version conflict with a reload button (never reported as archived)", (await T(p, "archive-problem").getAttribute("data-kind")) === "version" && (await unitOnServer(p, "web")).active === true && (await T(p, "org-flash").count()) === 0);
  await p.close(); }

// ===================================================================================================================== 503 ORG_STRUCTURE_BUSY: RETRY, KEEP THE INPUT, NEVER SUCCESS
{ const p = await open("org", "busy");
  await addUnit(p, { parent: "tech", name: "Phòng bận", code: "BUSY", type: "t-dept" }); await settle(p, 400);
  const pr = T(p, "org-problem");
  check("BUSY01 the busy structure (503 ORG_STRUCTURE_BUSY) is a RETRY state: its own kind, the Retry-After seconds, 'chưa có gì được lưu', a 'Thử lại' button — and NOT the success flash", (await pr.getAttribute("data-kind")) === "busy" && (await pr.getAttribute("data-retry-after")) === "1" && /chưa có gì được lưu/.test(await pr.innerText()) && (await T(p, "org-retry-busy").count()) === 1 && (await T(p, "org-flash").count()) === 0);
  check("BUSY02 the dialog stays open with exactly what the person typed (name, code, type); the tree has no new unit and the server holds none", (await T(p, "unit-dialog").count()) === 1 && (await T(p, "unit-name").inputValue()) === "Phòng bận" && (await T(p, "unit-code").inputValue()) === "BUSY" && (await p.getByRole("treeitem").count()) === 5 && !(await server(p)).units.some((u) => u.code === "BUSY"));
  await T(p, "org-retry-busy").click(); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 400);
  const c = await of(p, "createUnit");
  check("BUSY03 'Thử lại' sends the SAME request again (identical body) and now succeeds: exactly ONE unit exists on the server, the tree shows it, the flash says it was added", c.length === 2 && JSON.stringify(c[0].args) === JSON.stringify(c[1].args) && (await server(p)).units.filter((u) => u.code === "BUSY").length === 1 && (await p.getByRole("treeitem").count()) === 6 && /Đã thêm đơn vị/.test(await T(p, "org-flash").innerText()), JSON.stringify(c.map((x) => x.args)));
  await p.close(); }
{ const p = await open("org", "busy-always");
  await addUnit(p, { parent: "tech", name: "Luôn bận", code: "ALWAYS", type: "t-dept" }); await settle(p, 350);
  await T(p, "org-retry-busy").click(); await settle(p, 350); await T(p, "org-retry-busy").click(); await settle(p, 350);
  check("BUSY04 a server that stays busy is never turned into success: three identical attempts, three busy answers, no unit created, no flash, the dialog and the retry button are still there", (await of(p, "createUnit")).length === 3 && (await T(p, "org-problem").getAttribute("data-kind")) === "busy" && (await T(p, "unit-dialog").count()) === 1 && (await T(p, "org-flash").count()) === 0 && !(await server(p)).units.some((u) => u.code === "ALWAYS"));
  await p.close(); }
{ const p = await open("org", "busy");
  await node(p, "flutter").click(); await T(p, "org-move").click(); await T(p, "move-to:web").locator("input").check(); await T(p, "move-submit").click(); await settle(p, 350);
  check("BUSY05 a busy MOVE keeps the dialog and the chosen destination; 'Thử lại' resends the same move (same expectedVersion) and then it succeeds", (await T(p, "org-problem").getAttribute("data-kind")) === "busy" && await T(p, "move-to:web").locator("input").isChecked() && (await unitOnServer(p, "flutter")).parentId === "mobile");
  await T(p, "org-retry-busy").click(); await T(p, "move-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  const m = await of(p, "moveUnit");
  check("BUSY06 …the retry is identical (parent and version), and the unit is now under Web on the server", m.length === 2 && JSON.stringify(m[0].args) === JSON.stringify(m[1].args) && (await unitOnServer(p, "flutter")).parentId === "web");
  await p.close(); }
{ const p = await open("org", "busy");
  await node(p, "mobile").click(); await T(p, "org-add-child").click(); await T(p, "unit-name").fill("x");
  await p.keyboard.press("Escape"); await settle(p, 150);
  check("BUSY07 (control) nothing busy has happened before a write: no 503 state is shown just by opening a dialog", (await T(p, "org-problem").count()) === 0);
  await p.close(); }

// ===================================================================================================================== 501 ORG_PERSISTENCE_NOT_AVAILABLE: FAIL CLOSED
{ const p = await open("org", "off");
  const er = T(p, "org-error");
  check("OFF01 a server without the organization store (501): an explanation in plain words ('chưa được bật trên máy chủ', 'không có bản sao tạm'), its own state, NO tree, NO data", (await er.getAttribute("data-kind")) === "unavailable-feature" && /chưa được bật trên máy chủ/.test(await er.innerText()) && /Không có bản sao tạm/.test(await er.innerText()) && (await T(p, "org-tree").count()) === 0 && (await T(p, "org-empty").count()) === 0);
  check("OFF02 no silent fallback: only the two list calls were attempted (nothing else was asked of any other route), and the only action is 'Kiểm tra lại'", JSON.stringify((await names(p)).sort()) === JSON.stringify(["listUnitTypes", "unitTree"]) && /Kiểm tra lại/.test(await T(p, "org-retry").innerText()));
  check("OFF03 the screen never pretends: no 'Chưa có cơ cấu' empty state, no success text, no flash", !/Chưa có cơ cấu tổ chức|Đã thêm|Đã lưu/.test(await p.locator("main").innerText()));
  await p.close(); }
{ const p = await open("org");
  await p.evaluate(() => window.__fake.off(true));       // the flag is switched off under a running session
  await addUnit(p, { parent: "tech", name: "Không lưu được", code: "OFFW", type: "t-dept" }); await settle(p, 350);
  check("OFF04 a WRITE answered with 501: 'unavailable-feature' in the dialog, NOT retryable (no 'Thử lại'), no success, the tree is unchanged and the server holds nothing new", (await T(p, "unit-dialog").getByTestId("org-problem").getAttribute("data-kind")) === "unavailable-feature" && (await T(p, "org-retry-busy").count()) === 0 && (await T(p, "org-flash").count()) === 0 && (await p.getByRole("treeitem").count()) === 5 && !(await server(p)).units.some((u) => u.code === "OFFW"));
  await p.close(); }
{ const p = await open("org", "suspended");
  await addUnit(p, { parent: "tech", name: "Công ty khóa", code: "SUSP", type: "t-dept" }); await settle(p, 350);
  check("SUSP01 a suspended company (403 TENANT_SUSPENDED) may be READ but not changed: the write is refused with that reason, the tree stays", (await T(p, "org-problem").getAttribute("data-kind")) === "forbidden" && /tạm khóa/.test(await T(p, "org-problem").innerText()) && (await p.getByRole("treeitem").count()) === 5);
  await p.close(); }

// ===================================================================================================================== EMPTY / DENIED / DOWN
{ const p = await open("org", "empty");
  check("ORG_UI08 empty tree: an explanation and one clear action; no tree, no detail panel", (await T(p, "org-empty").count()) === 1 && (await T(p, "org-tree").count()) === 0 && (await T(p, "org-empty-add").isDisabled()) === false && /Chưa có cơ cấu tổ chức/.test(await T(p, "org-empty").innerText()));
  await T(p, "org-empty-add").click(); await T(p, "unit-dialog").waitFor();
  check("ORG_UI08a with no types yet the form says so and points to the types dialog (a unit needs a type)", /Chưa có loại đơn vị nào đang bật/.test(await T(p, "unit-dialog").innerText()));
  await p.keyboard.press("Escape"); await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); await T(p, "type-name").fill("Công ty"); await T(p, "type-code").fill("company"); await T(p, "type-submit").click(); await settle(p, 400); await p.keyboard.press("Escape"); await settle(p, 200);
  await T(p, "org-empty-add").click(); await T(p, "unit-name").fill("Công ty mẹ"); await T(p, "unit-code").fill("HQ"); await pickType(p, "unit-dialog", (await server(p)).types[0].id); await T(p, "unit-submit").click(); await settle(p, 500);
  check("ORG_UI08b creating the first root turns the empty state into a tree", (await T(p, "org-empty").count()) === 0 && (await p.getByRole("treeitem").count()) === 1);
  await p.close(); }

{ const p = await open("org", "forbidden");
  check("ORG_UI09 permission denied: the server lists no organization code → a forbidden state with the reason, NO call to the backend, no tree, no actions", (await T(p, "org-forbidden").count()) === 1 && (await calls(p)).length === 0 && (await T(p, "org-tree").count()) === 0 && (await T(p, "org-add-root").count()) === 0);
  check("ORG_UI09b no role name is shown or used as the reason", !/TENANT_ADMIN|SYSTEM_ADMIN|WORKSPACE_ADMIN/.test(await p.locator("body").innerText()));
  await p.close(); }
// D-C0-51: the screens read the organization CODES only. A SYSTEM_ADMIN lists exactly TENANT_MANAGE + TENANT_MEMBERS (platform scope): TENANT_MEMBERS and platformScope are NOT stand-ins, so both screens are refused without any backend call
for (const [v, tid] of [["org", "org-forbidden"], ["emp", "emp-forbidden"]]) { const p = await open(v, "sysadmin");
  check(`ORG_CODE01 ${v}: a platform operator (TENANT_MANAGE + TENANT_MEMBERS + platformScope, no organization code) is refused: forbidden state, NO backend call`, (await T(p, tid).count()) === 1 && (await calls(p)).length === 0 && !/TENANT_MEMBERS|ORG_STRUCTURE|platform/i.test(await T(p, tid).innerText()));
  await p.close(); }
// a viewer holds ORG_STRUCTURE_VIEW / EMPLOYEE_VIEW / POSITION_GRADE_VIEW only: the screens open, every change is unavailable WITH the reason (the codes are not hierarchical: *_VIEW never implies *_MANAGE)
{ const p = await open("org", "viewer");
  check("ORG_CODE02 a viewer opens the tree, but cannot add a unit: the button is unavailable (aria-disabled) with the permission reason", (await T(p, "org-tree").count()) === 1 && (await T(p, "org-add-root").getAttribute("aria-disabled")) === "true" && /quyền thay đổi/.test(await p.locator("body").innerText()));
  await node(p, "flutter").click();
  check("ORG_CODE02b …and on a selected unit every change (add child, edit, move, archive) is disabled; reading the counts still works", await T(p, "org-add-child").isDisabled() && await T(p, "org-edit").isDisabled() && await T(p, "org-move").isDisabled() && await T(p, "org-archive").isDisabled() && /14/.test(await T(p, "detail-direct-count").innerText()));
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor();
  check("ORG_CODE02c the types dialog is read-only for a viewer: the list is shown, no edit / toggle button, the form is disabled with the reason", (await T(p, "types-list").locator("li").count()) === 3 && (await p.locator('[data-testid^="type-edit:"]').count()) === 0 && (await T(p, "type-submit").isDisabled()) && (await T(p, "type-create-not-ready").count()) === 1);
  await p.close(); }
{ const p = await open("org", "emp-only");
  check("ORG_CODE04 EMPLOYEE_* codes alone do not open the structure screen (ORG_STRUCTURE_VIEW is the code for it): forbidden, no backend call", (await T(p, "org-forbidden").count()) === 1 && (await calls(p)).length === 0);
  await p.close(); }
{ const p = await open("org", "down");
  check("ORG_UI09c backend unavailable (503, no code): an error state with a retry button, in words (not a blank page)", (await T(p, "org-error").getAttribute("data-kind")) === "unavailable" && (await T(p, "org-retry").count()) === 1 && (await T(p, "org-tree").count()) === 0);
  await p.close(); }
{ const p = await open("org", "notfound");
  await node(p, "web").click(); await settle(p, 300);
  check("ORG_UI09d a unit the server does not find (or the caller may not see) is said once, disclosure-safe, with a reload; the tree is not blamed", /không có quyền xem/.test(await T(p, "org-problem").innerText()) && (await T(p, "org-problem").getAttribute("data-kind")) === "notfound");
  await p.close(); }

// ===================================================================================================================== KEYBOARD / FOCUS / A11Y
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
  check("ORG_UI10f a dialog opens from the keyboard and Escape closes it; focus returns to the button that opened it", (await T(p, "unit-dialog").count()) === 0 && (await focusedId(p)) === "org-edit");
  const v = await axe(p); check("ORG_UI10g axe (wcag2a/aa, critical + serious) finds nothing on the tree screen", v.length === 0, v.join(","));
  await p.close(); }
{ const p = await open("org");
  await node(p, "web").click(); await T(p, "org-archive").focus(); await key(p, "Enter"); await T(p, "archive-dialog").waitFor();
  const inside = async () => p.evaluate(() => !!document.activeElement?.closest("[data-testid=archive-dialog]"));
  check("A11Y01 the archive confirmation takes the focus (on 'Hủy', the safe choice) and keeps it inside the dialog while Tab / Shift+Tab are pressed (focus trap)", (await inside()) && /Hủy/.test(await p.evaluate(() => document.activeElement?.textContent ?? "")));
  let trapped = true; for (let i = 0; i < 6; i++) { await key(p, "Tab"); trapped = trapped && (await inside()); } for (let i = 0; i < 6; i++) { await key(p, "Shift+Tab"); trapped = trapped && (await inside()); }
  check("A11Y02 twelve Tab / Shift+Tab presses never leave the dialog", trapped);
  const v = await axe(p, "[data-testid=archive-dialog]"); check("A11Y03 axe on the archive dialog finds nothing", v.length === 0, v.join(","));
  await p.keyboard.press("Escape"); await settle(p, 150);
  check("A11Y04 Escape closes the dialog, sends nothing, and focus is back on 'Lưu trữ'", (await T(p, "archive-dialog").count()) === 0 && (await names(p)).filter((n) => n === "archiveUnit").length === 0 && (await focusedId(p)) === "org-archive");
  await T(p, "org-move").focus(); await key(p, "Enter"); await T(p, "move-dialog").waitFor(); const vm = await axe(p, "[data-testid=move-dialog]");
  check("A11Y05 the move dialog is accessible too (axe) and its destinations are radios reachable from the keyboard", vm.length === 0 && (await T(p, "move-dialog").locator('input[type=radio]:not([disabled])').count()) >= 1, vm.join(","));
  await p.keyboard.press("Escape");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); const v2 = await axe(p, "[data-testid=types-dialog]"); check("A11Y06 axe finds nothing in the unit-types dialog (rules editor included)", v2.length === 0, v2.join(","));
  await p.keyboard.press("Escape"); await T(p, "org-catalog").click(); await T(p, "catalog-dialog").waitFor(); const v3 = await axe(p, "[data-testid=catalog-dialog]"); check("A11Y07 …nor in the positions & grades dialog", v3.length === 0, v3.join(","));
  await p.close(); }

// ===================================================================================================================== UNIT TYPES (dynamic, with placement rules)
{ const p = await open("org");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor();
  const list = await T(p, "types-list").innerText();
  check("TYPE01 the company's own types are listed with code and their placement rules in words (nothing hard-coded: Khối / Phòng / Team come from the data)", (await T(p, "types-list").locator("li").count()) === 3 && /division/.test(list) && /đặt dưới: Khối/.test(await T(p, "type-rules:dept").innerText()) && /không giới hạn nơi đặt/.test(await T(p, "type-rules:division").innerText()));
  check("TYPE02 the icon is chosen from a closed grid of 16 allow-listed icons (radio buttons), there is no URL / upload field", (await p.locator('[data-testid^="icon:"]').count()) === 16 && (await T(p, "types-dialog").locator('input[type=url],input[type=file]').count()) === 0);
  await T(p, "type-submit").click();
  check("TYPE03 empty name / bad code are refused in the form, nothing sent", /Hãy nhập tên loại/.test(await T(p, "type-form").innerText()) && !(await names(p)).includes("createUnitType"));
  await T(p, "type-name").fill("Chi nhánh"); await T(p, "type-code").fill("division"); await T(p, "type-submit").click();
  check("TYPE04 a code already used by another type is refused (case-insensitive)", /đã được dùng/.test(await T(p, "type-form").innerText()) && !(await names(p)).includes("createUnitType"));
  await T(p, "type-code").fill("branch"); await T(p, "icon:map-pin").click(); await T(p, "type-submit").click(); await settle(p, 400);
  const c = await of(p, "createUnitType");
  check("TYPE05 ONE create call: name, LOWER-case code, the chosen icon id and the (empty) rules object; the new type is listed at once and the flash says so", c.length === 1 && c[0].args[0].code === "branch" && c[0].args[0].icon === "map-pin" && c[0].args[0].name === "Chi nhánh" && c[0].args[0].rules.allowedParentTypeIds === null && (await T(p, "types-list").locator("li").count()) === 4 && /Đã thêm loại/.test(await T(p, "types-flash").innerText()), JSON.stringify(c[0]?.args));
  await p.close(); }
{ const p = await open("org");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); await T(p, "type-edit:dept").click();
  check("TYPE06 edit: the form is prefilled (name, icon, rules); the CODE is read-only", (await T(p, "type-name").inputValue()) === "Phòng" && await T(p, "type-code").isDisabled() && (await T(p, "rule-parents").inputValue()) === "listed" && await T(p, "rule-parent:division").isChecked());
  await T(p, "type-name").fill("Phòng ban"); await T(p, "type-submit").click(); await settle(p, 400);
  const u = await of(p, "updateUnitType");
  check("TYPE07 ONE update: id, name, icon, the WHOLE rules object (a rules patch replaces the object on the server) and the type's expectedVersion", u.length === 1 && u[0].args[0] === "t-dept" && u[0].args[1].name === "Phòng ban" && u[0].args[1].expectedVersion === 0 && JSON.stringify(u[0].args[1].rules.allowedParentTypeIds) === JSON.stringify(["t-div"]) && "allowRoot" in u[0].args[1].rules && "maxDepth" in u[0].args[1].rules, JSON.stringify(u[0]?.args));
  await T(p, "type-toggle:team").click(); await settle(p, 400);
  check("TYPE08 disabling a type: ONE call with its version; the type is marked 'đang tắt' and the unit dialog stops offering it (the server refuses units of a disabled type)", (await of(p, "disableUnitType")).length === 1 && /đang tắt/.test(await T(p, "type:team").innerText()) && (await server(p)).types.find((t) => t.code === "team").active === false);
  await p.keyboard.press("Escape"); await node(p, "mobile").click(); await T(p, "org-add-child").click(); await T(p, "unit-dialog").waitFor();
  const opts = await T(p, "unit-dialog").locator("select.srOnly option").allInnerTexts();
  check("TYPE09 the unit dialog offers only ACTIVE types (Khối, Phòng ban) — not the disabled Team", opts.some((o) => /Phòng ban/.test(o)) && !opts.some((o) => /^Team/.test(o)), opts.join("|"));
  await p.keyboard.press("Escape"); await T(p, "org-types").click(); await T(p, "type-toggle:team").click(); await settle(p, 400);
  check("TYPE10 enabling it again: ONE call, the pill is gone", (await of(p, "enableUnitType")).length === 1 && !/đang tắt/.test(await T(p, "type:team").innerText()));
  await p.close(); }
{ const p = await open("org");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); await T(p, "type-edit:dept").click();
  await T(p, "rule-parent:division").uncheck(); await T(p, "rule-parent:team").check(); await T(p, "type-submit").click(); await settle(p, 400);
  check("TYPE11 a rules change the existing tree would violate is refused by the SERVER (ORG_TYPE_RULE_VIOLATION, PARENT_TYPE_NOT_ALLOWED): said as a rule problem, the type is unchanged on the server, the form keeps the person's choice", (await T(p, "types-problem").getAttribute("data-kind")) === "rule" && /dưới đơn vị cha/.test(await T(p, "types-problem").innerText()) && JSON.stringify((await server(p)).types.find((t) => t.code === "dept").rules.allowedParentTypeIds) === JSON.stringify(["t-div"]) && await T(p, "rule-parent:team").isChecked());
  await p.close(); }
{ const p = await open("org");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor();
  await T(p, "type-name").fill("Lá"); await T(p, "type-code").fill("leaf"); await T(p, "rule-children").selectOption("listed"); await T(p, "type-submit").click(); await settle(p, 400);
  const lt = (await server(p)).types.find((t) => t.code === "leaf");
  check("TYPE12 'Chứa các loại: chỉ các loại được chọn' with none ticked = a LEAF type: the rules sent are allowedChildTypeIds [] (not null)", lt && JSON.stringify(lt.rules.allowedChildTypeIds) === "[]" && lt.rules.allowedParentTypeIds === null);
  await p.keyboard.press("Escape"); await addUnit(p, { name: "Đơn vị lá", code: "LEAF1", type: lt.id }); await T(p, "unit-dialog").waitFor({ state: "detached" }); await settle(p, 300);
  await T(p, "org-add-child").click(); await T(p, "unit-name").fill("Con của lá"); await T(p, "unit-code").fill("KID"); await pickType(p, "unit-dialog", "t-div"); await T(p, "unit-submit").click();
  check("TYPE13 a child under a leaf type is refused in the form ('không chứa đơn vị con') and nothing is sent", /không chứa đơn vị con/.test(await T(p, "unit-type-error").innerText()) && (await of(p, "createUnit")).length === 1);
  await p.close(); }
{ const p = await open("org", "busy");
  await T(p, "org-types").click(); await T(p, "types-dialog").waitFor(); await T(p, "type-edit:dept").click(); await T(p, "rule-maxdepth").fill("5"); await T(p, "type-submit").click(); await settle(p, 400);
  check("TYPE14 a rules change is a STRUCTURAL write: the busy answer keeps the dialog and the edit, shows 'Thử lại'; the retry sends the same update and then it is saved", (await T(p, "types-problem").getAttribute("data-kind")) === "busy" && (await T(p, "rule-maxdepth").inputValue()) === "5" && (await server(p)).types.find((t) => t.code === "dept").rules.maxDepth === null);
  await T(p, "org-retry-busy").click(); await settle(p, 500);
  const u = await of(p, "updateUnitType");
  check("TYPE15 …the retry is identical and the type now holds maxDepth 5 on the server", u.length === 2 && JSON.stringify(u[0].args) === JSON.stringify(u[1].args) && (await server(p)).types.find((t) => t.code === "dept").rules.maxDepth === 5);
  await p.close(); }

// ===================================================================================================================== POSITIONS AND GRADES (separate catalogs, no permission)
{ const p = await open("org");
  await T(p, "org-catalog").click(); await T(p, "catalog-dialog").waitFor(); await settle(p, 300);
  check("CAT01 the dialog lists the two catalogs separately (positions, grades with their rank) and says they grant no permission", (await T(p, "catalog-position-list").locator("li").count()) === 2 && (await T(p, "catalog-grade-list").locator("li").count()) === 2 && /bậc 3/.test(await T(p, "grade:SR").innerText()) && /không cấp quyền/.test(await T(p, "catalog-dialog").innerText()));
  await T(p, "position-code").fill("eng"); await T(p, "position-name").fill("Kỹ sư phần mềm"); await T(p, "position-submit").click();
  check("CAT02 a position code already in use (case-insensitive, even for a disabled record) is refused in the form; nothing sent", /đã được dùng/.test(await T(p, "position-form").innerText()) && !(await names(p)).includes("createPosition"));
  await T(p, "position-code").fill("QA"); await T(p, "position-name").fill("  Kiểm thử  "); await T(p, "position-description").fill("Kiểm thử sản phẩm"); await T(p, "position-submit").click(); await settle(p, 400);
  const c = await of(p, "createPosition");
  check("CAT03 ONE create: trimmed name, code, description; the list shows it", c.length === 1 && c[0].args[0].name === "Kiểm thử" && c[0].args[0].code === "QA" && c[0].args[0].description === "Kiểm thử sản phẩm" && (await T(p, "position:QA").count()) === 1);
  await T(p, "position-edit:QA").click(); await T(p, "position-name").fill("Kiểm thử viên"); await T(p, "position-submit").click(); await settle(p, 400);
  const u = await of(p, "updatePosition");
  check("CAT04 editing sends the version; the code is read-only while editing", u.length === 1 && u[0].args[1].expectedVersion === 0 && u[0].args[1].name === "Kiểm thử viên" && /Kiểm thử viên/.test(await T(p, "position:QA").innerText()));
  await T(p, "position-toggle:QA").click(); await settle(p, 400);
  check("CAT05 disabling a position: ONE call, the row says 'đang tắt'; it stays in the list for the people who hold it", (await of(p, "disablePosition")).length === 1 && /đang tắt/.test(await T(p, "position:QA").innerText()));
  await T(p, "grade-code").fill("LD"); await T(p, "grade-name").fill("Lead"); await T(p, "grade-rank").fill("5"); await T(p, "grade-submit").click(); await settle(p, 400);
  const g = await of(p, "createGrade");
  check("CAT06 a grade has an optional numeric rank: ONE create with rank 5; a rank above 10000 is refused in the form", g.length === 1 && g[0].args[0].rank === 5 && /bậc 5/.test(await T(p, "grade:LD").innerText()));
  await T(p, "grade-code").fill("X1"); await T(p, "grade-name").fill("X"); await T(p, "grade-rank").fill("10001"); await T(p, "grade-submit").click();
  check("CAT07 rank 10001 → 'Bậc là số nguyên từ 0 đến 10000', nothing sent", /0 đến 10000/.test(await T(p, "grade-form").innerText()) && (await of(p, "createGrade")).length === 1);
  await T(p, "grade-cancel-edit").count(); await T(p, "grade-edit:LD").click(); await T(p, "grade-rank").fill(""); await T(p, "grade-submit").click(); await settle(p, 400);
  const ug = (await of(p, "updateGrade")).at(-1);
  check("CAT08 clearing a grade's rank sends `clearRank: true` (not rank null) with the version", ug.args[1].clearRank === true && !("rank" in ug.args[1]) && ug.args[1].expectedVersion === 0 && !/bậc/.test(await T(p, "grade:LD").innerText()), JSON.stringify(ug.args));
  await p.close(); }
{ const p = await open("org", "viewer");
  await T(p, "org-catalog").click(); await T(p, "catalog-dialog").waitFor(); await settle(p, 300);
  check("CAT09 a viewer (POSITION_GRADE_VIEW only) reads both catalogs: no form, no edit / toggle, the reason is shown", (await T(p, "catalog-position-list").locator("li").count()) === 2 && (await T(p, "position-form").count()) === 0 && (await p.locator('[data-testid^="position-edit:"]').count()) === 0 && (await T(p, "catalog-readonly").count()) === 1);
  await p.close(); }
{ const p = await open("org", "off");
  check("CAT10 with the store off, the toolbar still opens the dialog but each catalog says it cannot load (501), no data, no fallback", (await p.getByTestId("org-catalog").count()) === 1 && await (async () => { await T(p, "org-catalog").click(); await T(p, "catalog-dialog").waitFor(); await settle(p, 300); return (await T(p, "catalog-position-error").getAttribute("data-kind")) === "unavailable-feature" && (await T(p, "catalog-position-list").count()) === 0; })());
  await p.close(); }

// ===================================================================================================================== RESPONSIVE
{ const p = await open("org", "ok", { width: 600, height: 800 });
  check("ORG_UI11 the organization screen stacks the tree and the detail in ONE column on a narrow screen, no horizontal scroll", (await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)) <= 1 && (await p.locator(".xp-orgGrid").evaluate((e) => getComputedStyle(e).gridTemplateColumns.split(" ").length)) === 1);
  await node(p, "flutter").click(); await T(p, "org-archive").click(); await T(p, "archive-dialog").waitFor();
  const box = await T(p, "archive-dialog").boundingBox();
  check("ORG_UI11b a confirmation dialog fits a 390 px-class screen without horizontal scroll (the buttons stay reachable)", box !== null && box.x >= 0 && box.x + box.width <= 600 + 1 && (await T(p, "archive-confirm").isVisible()));
  await p.close(); }
{ const p = await open("org", "ok", { width: 390, height: 800 });
  check("ORG_UI11c at 390 px: no horizontal page scroll; the toolbar actions and the tree are reachable", (await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)) <= 1 && (await T(p, "org-add-root").isVisible()) && (await T(p, "org-tree").isVisible()));
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
finish();
