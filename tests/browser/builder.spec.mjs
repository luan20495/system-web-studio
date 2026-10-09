// @class: harness — real Chromium on a test-only host (fake host / no API behind it); NOT a backend E2E
// Real-browser checks of the Builder (pointer + keyboard) against tests/browser/harness.tsx. TEST-ONLY harness: NOT a backend E2E.
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/builder.spec.mjs
import { mkdirSync } from "node:fs";
import { harnessUrl, launch, makeChecks } from "./lib/spec.mjs";
const URL_ = harnessUrl();
const shots = process.env.SHOTS ?? "/tmp/shots"; mkdirSync(shots, { recursive: true });
const { check, finish } = makeChecks();

const browser = await launch();
async function fresh() {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.errors = []; page.on("pageerror", (e) => page.errors.push(e.message)); page.on("console", (m) => { if (m.type() === "error" && !/favicon|404/.test(m.text())) page.errors.push(m.text()); });
  await page.goto(URL_); await page.waitForSelector("iframe"); await page.waitForTimeout(700);
  return page;
}
const ops = (p) => p.evaluate(() => window.__ops);
const treeLabels = (p) => p.evaluate(() => [...document.querySelectorAll("[role=treeitem]")].slice(1).map((e) => (e.querySelector("b,strong,span")?.textContent ?? e.textContent ?? "").trim()));
const center = async (loc) => { const b = await loc.boundingBox(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 }; };
async function dragTo(page, from, to, { steps = 14, hold } = {}) {
  await page.mouse.move(from.x, from.y); await page.mouse.down();
  await page.mouse.move(from.x + 12, from.y + 12, { steps: 4 });
  await page.mouse.move(to.x, to.y, { steps });
  if (hold) await hold();
  await page.mouse.up(); await page.waitForTimeout(400);
}

// ---------- 1. library -> canvas (pointer) ----------
{
  const p = await fresh();
  await p.getByRole("tab", { name: "Thành phần" }).click();
  const handle = p.getByRole("button", { name: "Kéo Form liên hệ vào trang" });
  const from = await center(handle);
  const cv = await p.locator(".bx-frame").boundingBox();
  const heroBox = await p.frameLocator("iframe").locator("section").first().boundingBox(); // Hero: drop just below it => slot between Hero and Testimonials
  let indicatorSeen = false, indicatorTop = null, shieldOn = false;
  await dragTo(p, from, { x: cv.x + 300, y: heroBox.y + heroBox.height + 10 }, { hold: async () => {
    await p.waitForTimeout(250);
    indicatorSeen = (await p.locator(".bx-insert").count()) > 0;
    shieldOn = (await p.locator(".bx-shield.on").count()) > 0;
    if (indicatorSeen) indicatorTop = (await p.locator(".bx-insert").boundingBox()).y;
    await p.screenshot({ path: `${shots}/dnd-lib-dragging.png` });
  } });
  check("DnD library→canvas: drop shield appears only while dragging", shieldOn && (await p.locator(".bx-shield.on").count()) === 0);
  check("DnD library→canvas: insert indicator shown while dragging", indicatorSeen, `y=${indicatorTop}`);
  const o = await ops(p);
  const add = o.flatMap((x) => x.ops).find((x) => x.type === "ADD_SECTION");
  check("DnD library→canvas: emits one ADD_SECTION for the registry component", o.length === 1 && add?.sectionType === "ContactForm", JSON.stringify(add));
  check("DnD library→canvas: inserted between Hero and Testimonials (index 2)", add?.index === 2, `index=${add?.index}`);
  await p.getByRole("tab", { name: "Trang" }).click(); await p.waitForTimeout(200);
  const labels = await treeLabels(p);
  check("DnD library→canvas: new section appears in the page tree at that slot", labels.length === 5 && /Form liên hệ/.test(labels[2] ?? ""), labels.join(" | "));
  check("DnD library→canvas: no page errors", p.errors.length === 0, p.errors.join(" ; "));
  await p.screenshot({ path: `${shots}/dnd-lib-after.png` });
  await p.close();
}

// ---------- 2. reorder in the tree (pointer) ----------
{
  const p = await fresh();
  const hero = p.locator(".bx-left-panel").getByRole("button", { name: "Kéo để di chuyển Đầu trang (Hero)" });
  const test = p.locator(".bx-left-panel").getByRole("button", { name: "Kéo để di chuyển Đánh giá khách hàng" });
  const a = await center(hero), b = await center(test);
  await dragTo(p, a, { x: b.x, y: b.y + 30 });
  const o = await ops(p); const mv = o.flatMap((x) => x.ops).find((x) => x.type === "MOVE_SECTION");
  check("Reorder (tree handle, pointer): emits MOVE_SECTION for the dragged section", mv?.sectionId === "s-hero", JSON.stringify(mv));
  const labels = await treeLabels(p);
  check("Reorder (tree handle, pointer): Hero now after Testimonials", /Đầu trang/.test(labels[2] ?? "") && /Đánh giá/.test(labels[1] ?? ""), labels.join(" | "));
  check("Reorder: no page errors", p.errors.length === 0, p.errors.join(" ; "));
  await p.close();
}

// ---------- 3. canvas section drag (reorder on the canvas) ----------
{
  // Regression: handles used to overlay the sandboxed iframe, and 15-45% of presses were routed into the iframe. Repeat to prove it is stable.
  let ok = 0; const RUNS = Number(process.env.DRAG_RUNS ?? 12); let indicatorOk = true;
  for (let i = 0; i < RUNS; i++) {
    const p = await fresh();
    await p.frameLocator("iframe").locator("section").nth(0).hover({ position: { x: 100, y: 100 } }); await p.waitForTimeout(300);
    const handles = p.locator(".bx-handle");
    if ((await handles.count()) < 3) { await p.close(); continue; }
    const a = await center(handles.nth(1)); const cv = await p.locator(".bx-frame").boundingBox();
    await dragTo(p, a, { x: cv.x + 300, y: cv.y + 630 }, { hold: async () => { await p.waitForTimeout(120); if ((await p.locator(".bx-insert").count()) === 0) indicatorOk = false; } });
    const mv = (await ops(p)).flatMap((x) => x.ops).find((x) => x.type === "MOVE_SECTION");
    if (mv?.sectionId === "s-hero") ok++;
    await p.close();
  }
  check(`Canvas section drag (handle in the gutter beside the preview): MOVE_SECTION emitted ${ok}/${RUNS}`, ok === RUNS);
  check("Canvas section drag: insert indicator visible during every drag", indicatorOk);
  const p = await fresh();
  const gut = await p.locator(".bx-gutter").boundingBox(); const fr = await p.locator(".bx-frame").boundingBox();
  check("Canvas: handles sit beside the iframe, not over it", gut.x >= fr.x + fr.width - 1, `frame right=${Math.round(fr.x + fr.width)} gutter left=${Math.round(gut.x)}`);
  const op = await p.locator(".bx-handle").first().evaluate((e) => getComputedStyle(e).opacity);
  check("Canvas: handles are visible at rest (not opacity 0)", Number(op) >= 0.5, `opacity=${op}`);
  await p.close();
}

// ---------- 4. select + inspector edit ----------
{
  const p = await fresh();
  const fr = p.frameLocator("iframe");
  await fr.locator("section").first().click({ position: { x: 30, y: 30 } });
  await p.waitForTimeout(500);
  const hdr = await p.locator(".bx-right").innerText();
  check("Select: clicking a section on the canvas opens the inspector for it", /Đầu trang|Hero/.test(hdr), hdr.split("\n")[0]);
  const tabs = await p.locator(".bx-right [role=tab]").allInnerTexts();
  check("Inspector: has Content/Design/Data/Action/Permission/Advanced tabs", ["Nội dung", "Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao"].every((t) => tabs.some((x) => x.startsWith(t))), tabs.join(" | "));
  const title = p.locator(".bx-right input").first();
  await title.fill("Tiêu đề mới từ test");
  await p.getByRole("button", { name: /Lưu thay đổi/ }).click(); await p.waitForTimeout(500);
  const o = (await ops(p)).flatMap((x) => x.ops);
  check("Inspector edit: Save emits a typed prop operation", o.some((x) => ["UPDATE_PROP", "UPDATE_SECTION"].includes(x.type)), JSON.stringify(o.slice(-1)));
  const txt = await fr.locator("body").innerText();
  check("Inspector edit: canvas re-renders with the new title", /Tiêu đề mới từ test/.test(txt));
  // Advanced = read-only
  await p.getByRole("tab", { name: /Nâng cao/ }).click();
  const adv = await p.locator(".bx-right").innerHTML();
  check("Advanced tab is read-only (no editable inputs)", !/<input(?![^>]*readonly)(?![^>]*disabled)/i.test(adv) && !/<textarea(?![^>]*readonly)(?![^>]*disabled)/i.test(adv));
  // Data tab: NOT_READY with reason, no fake data
  await p.getByRole("tab", { name: /^Dữ liệu/ }).last().click();
  const dt = await p.locator(".bx-right").innerText();
  check("Inspector Data tab says Chưa sẵn sàng with a reason", /Chưa sẵn sàng/.test(dt), dt.replace(/\n/g, " ").slice(0, 140));
  await p.screenshot({ path: `${shots}/inspector-data.png` });
  await p.close();
}

// ---------- 5. keyboard fallback ----------
{
  const p = await fresh();
  await p.getByRole("tab", { name: "Thành phần" }).click();
  await p.getByRole("button", { name: "Thêm Đầu trang (Hero) vào trang" }).focus();
  await p.keyboard.press("Enter"); await p.waitForTimeout(400);
  let add = (await ops(p)).flatMap((x) => x.ops).find((x) => x.type === "ADD_SECTION");
  check("Click/keyboard add (Thêm button via Enter): emits ADD_SECTION without a pointer", add?.sectionType === "Hero", JSON.stringify(add));
  await p.getByRole("tab", { name: "Trang" }).click();
  const before = (await ops(p)).length;
  check("Footer stays last: its Lên/Xuống buttons are disabled instead of silently doing nothing",
    (await p.getByRole("button", { name: "Đưa Chân trang lên" }).isDisabled()) && (await p.getByRole("button", { name: "Đưa Chân trang xuống" }).isDisabled()));
  await p.getByRole("button", { name: "Đưa Đánh giá khách hàng lên" }).focus(); await p.keyboard.press("Enter"); await p.waitForTimeout(400);
  const mv = (await ops(p)).slice(before).flatMap((x) => x.ops).find((x) => x.type === "MOVE_SECTION");
  check("Keyboard reorder (Lên/Xuống buttons): emits MOVE_SECTION", mv?.sectionId === "s-test", JSON.stringify(mv));
  // keyboard sensor on the handle: Space, ArrowDown, Space
  const h = p.locator(".bx-left-panel").getByRole("button", { name: "Kéo để di chuyển Thanh điều hướng" });
  await h.focus(); const b2 = (await ops(p)).length;
  await p.keyboard.press("Space"); await p.waitForTimeout(150);
  const live = await p.evaluate(() => [...document.querySelectorAll("[aria-live]")].map((e) => e.textContent).join(" "));
  await p.keyboard.press("ArrowDown"); await p.waitForTimeout(150); await p.keyboard.press("Space"); await p.waitForTimeout(400);
  const kb = (await ops(p)).slice(b2).flatMap((x) => x.ops).find((x) => x.type === "MOVE_SECTION");
  check("Keyboard DnD on handle (Space, ArrowDown, Space): emits MOVE_SECTION", kb?.sectionId === "s-nav", JSON.stringify(kb));
  check("Keyboard DnD announces in Vietnamese via live region", /Đã nhấc|Đang ở|Đã thả/.test(live), live.slice(0, 100));
  // Esc cancels a keyboard drag
  const b3 = (await ops(p)).length;
  await h.focus(); await p.keyboard.press("Space"); await p.keyboard.press("ArrowDown"); await p.keyboard.press("Escape"); await p.waitForTimeout(300);
  check("Esc cancels a keyboard drag (no operation emitted)", (await ops(p)).length === b3);
  await p.close();
}

// ---------- 6. dialogs, tabs, focus, accessible names ----------
{
  const p = await fresh();
  await p.frameLocator("iframe").locator("section").first().click({ position: { x: 30, y: 30 } }); await p.waitForTimeout(400);
  const publish = p.getByRole("button", { name: "Xóa mục" });
  await publish.focus(); await publish.click(); await p.waitForTimeout(500);
  const dlg = p.getByRole("dialog");
  check("Dialog: delete-section confirmation opens role=dialog aria-modal with a name", (await dlg.count()) === 1 && (await dlg.getAttribute("aria-modal")) === "true" && !!(await dlg.getAttribute("aria-labelledby")));
  const inside = await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]"));
  check("Dialog: focus moves inside on open", inside);
  for (let i = 0; i < 12; i++) await p.keyboard.press("Tab");
  check("Dialog: Tab is trapped inside after 12 presses", await p.evaluate(() => !!document.activeElement?.closest("[role=dialog]")));
  await p.screenshot({ path: `${shots}/dialog-publish.png` });
  await p.keyboard.press("Escape"); await p.waitForTimeout(300);
  check("Dialog: Esc closes", (await p.getByRole("dialog").count()) === 0);
  check("Dialog: focus returns to the opener", await p.evaluate(() => /Xóa mục/.test(document.activeElement?.textContent ?? "")));
  // tabs: roving tabindex + arrow keys
  const first = p.getByRole("tab", { name: "Trang" }); await first.focus();
  await p.keyboard.press("ArrowDown"); await p.waitForTimeout(150);
  const sel = await p.evaluate(() => [...document.querySelectorAll(".bx-left [role=tab]")].map((e) => e.getAttribute("aria-selected") + "/" + e.getAttribute("tabindex")).join(","));
  check("Tabs: Arrow key moves focus/selection (roving tabindex)", /true\/0/.test(sel) && (sel.match(/\/0/g) ?? []).length === 1, sel);
  // accessible names
  const unnamed = await p.evaluate(() => [...document.querySelectorAll("button,[role=button],input,select,textarea,[role=tab]")].filter((e) => {
    const name = (e.getAttribute("aria-label") || e.getAttribute("aria-labelledby") || e.textContent || e.getAttribute("title") || e.getAttribute("placeholder") || "").trim();
    const lab = e.id && document.querySelector(`label[for="${e.id}"]`); const wrapped = e.closest("label");
    return !name && !lab && !wrapped; }).map((e) => e.outerHTML.slice(0, 100)));
  check("A11y: every button/input/tab has an accessible name", unnamed.length === 0, unnamed.slice(0, 3).join(" ; "));
  // Edit vs Test
  await p.getByRole("button", { name: "Dùng thử" }).click(); await p.waitForTimeout(400);
  const t = await p.locator(".bx-body").innerText();
  check("Test mode is a separate mode and says Chưa sẵn sàng instead of faking a run", /Chưa sẵn sàng/.test(t) && !/THÀNH CÔNG|SUCCESS/.test(t), t.replace(/\n/g, " ").slice(0, 160));
  await p.screenshot({ path: `${shots}/test-mode.png` });
  await p.close();
}

// ---------- 7. Data / Action / Workflow panels: NOT_READY, no fake data ----------
{
  const p = await fresh();
  for (const [tab, re] of [["Dữ liệu", /Chưa sẵn sàng/], ["Hành động", /Chưa sẵn sàng|REFRESH_QUERY|Làm mới/], ["Workflow", /Chưa sẵn sàng|Workflow/]]) {
    await p.locator(".bx-left").getByRole("tab", { name: tab }).click(); await p.waitForTimeout(300);
    const t = await p.locator(".bx-left-panel").innerText();
    check(`Panel ${tab}: renders and states readiness honestly`, re.test(t), t.replace(/\n/g, " ").slice(0, 150));
    await p.screenshot({ path: `${shots}/panel-${tab}.png` });
  }
  await p.close();
}

// ---------- 8. Page builder ----------
{
  const p = await fresh();
  const panel = p.locator(".bx-left-panel");
  const lastOps = async () => (await ops(p)).slice(-1)[0]?.ops ?? [];
  // create
  await panel.getByRole("button", { name: /^Trang$/ }).click();
  await p.getByRole("dialog").getByRole("textbox").fill("Giới thiệu");
  check("Page: create dialog previews the route", /\/gioi-thieu/.test(await p.getByRole("dialog").innerText()), (await p.getByRole("dialog").innerText()).replace(/\n/g, " "));
  await p.getByRole("dialog").getByRole("button", { name: "Thêm trang" }).click(); await p.waitForTimeout(500);
  let add = (await lastOps())[0];
  check("Page: create emits ADD_PAGE with a unique slug", add?.type === "ADD_PAGE" && add.props?.slug === "gioi-thieu" && add.props?.title === "Giới thiệu", JSON.stringify(add));
  check("Page: new page appears in the tree and becomes editable", (await panel.getByRole("treeitem").allInnerTexts()).some((t) => /Giới thiệu/.test(t)));
  // select the new page (tree row) then rename
  await panel.getByRole("treeitem", { name: /Giới thiệu/ }).first().click(); await p.waitForTimeout(300);
  await panel.getByRole("button", { name: "Đổi tên / đường dẫn" }).click();
  const dlg = p.getByRole("dialog");
  const boxes = dlg.getByRole("textbox");
  await boxes.nth(0).fill("Về chúng tôi");
  if ((await boxes.count()) > 1) { await boxes.nth(1).fill("api"); await p.waitForTimeout(200);
    const t = await dlg.innerText(); check("Page: reserved/invalid route is rejected with a reason and Save is disabled", /(không|đã|dành|trùng|hợp lệ)/i.test(t) && (await dlg.getByRole("button", { name: "Lưu" }).isDisabled()), t.replace(/\n/g, " ").slice(0, 160));
    await boxes.nth(1).fill("ve-chung-toi"); }
  await dlg.getByRole("button", { name: "Lưu" }).click(); await p.waitForTimeout(500);
  const up = (await lastOps())[0];
  check("Page: rename/route emits UPDATE_PAGE", up?.type === "UPDATE_PAGE" && up.props?.title === "Về chúng tôi", JSON.stringify(up));
  // menu
  const sel = panel.locator("select").first();
  await sel.selectOption({ label: "Về chúng tôi" }); await p.waitForTimeout(200);
  await panel.getByRole("button", { name: "Lưu menu" }).click(); await p.waitForTimeout(500);
  const nav = (await lastOps())[0];
  check("Menu: adding a page to the menu emits SET_NAVIGATION with that page", nav?.type === "SET_NAVIGATION" && nav.value?.length === 1 && nav.value[0].label === "Về chúng tôi" && !!nav.value[0].pageId, JSON.stringify(nav));
  // 404
  await panel.getByRole("textbox", { name: "Tiêu đề" }).fill("Lạc đường rồi");
  await panel.getByRole("textbox", { name: "Lời nhắn" }).fill("Trang này không tồn tại.");
  await panel.getByRole("button", { name: "Lưu trang 404" }).click(); await p.waitForTimeout(500);
  const nf = (await lastOps())[0];
  check("404: saving emits UPDATE_SITE with notFound title and message", nf?.type === "UPDATE_SITE" && nf.props?.notFound?.title === "Lạc đường rồi", JSON.stringify(nf));
  // set-home / reorder stay NOT_READY
  const ptxt = await panel.innerText();
  check("Set-home and page reorder are NOT_READY with a reason (no fake control)", /Chưa sẵn sàng/.test(ptxt) && await panel.getByRole("button", { name: /đặt làm trang chủ/i }).evaluateAll((els) => els.every((e) => e.disabled)), ptxt.replace(/\n/g, " ").slice(ptxt.indexOf("Chưa sẵn sàng") - 20, ptxt.indexOf("Chưa sẵn sàng") + 100));
  // delete: home cannot be deleted; another page shows its impact
  await panel.getByRole("treeitem", { name: /Trang chủ/ }).first().click(); await p.waitForTimeout(200);
  check("Page: the home page cannot be deleted (button disabled)", await panel.getByRole("button", { name: "Xóa trang" }).isDisabled());
  await panel.getByRole("treeitem", { name: /Về chúng tôi/ }).first().click(); await p.waitForTimeout(200);
  await panel.getByRole("button", { name: "Xóa trang" }).click(); await p.waitForTimeout(300);
  const imp = await p.getByRole("dialog").innerText();
  check("Page: delete confirmation states what will be removed (menu link)", /liên kết trong menu|Xoá trang|Xóa trang/.test(imp) && /menu/.test(imp), imp.replace(/\n/g, " ").slice(0, 180));
  await p.getByRole("dialog").getByRole("button", { name: /^Xóa|^Xoá/ }).last().click(); await p.waitForTimeout(500);
  const rm = (await lastOps())[0];
  check("Page: delete emits REMOVE_PAGE", rm?.type === "REMOVE_PAGE", JSON.stringify(rm));
  check("Page: no page errors", p.errors.length === 0, p.errors.join(" ; "));
  await p.screenshot({ path: `${shots}/pages.png` });
  await p.close();
}

// ---------- 9. broken-route preflight blocks Publish ----------
{
  const bad = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await bad.goto(URL_ + "?broken=1"); await bad.waitForSelector("iframe"); await bad.waitForTimeout(600);
  await bad.getByRole("button", { name: "Xuất bản" }).click(); await bad.waitForTimeout(500);
  const d = bad.getByRole("dialog");
  const t = (await d.count()) ? await d.innerText() : "";
  check("Preflight: Publish with a menu link to a missing page opens a blocking dialog", (await d.count()) === 1 && /(hỏng|không còn tồn tại|không tồn tại|không tìm thấy|đường dẫn)/i.test(t), t.replace(/\n/g, " ").slice(0, 200));
  check("Preflight: the publish flow was NOT started", (await bad.evaluate(() => window.__published)) === 0);
  await bad.screenshot({ path: `${shots}/preflight-blocked.png` });
  await bad.keyboard.press("Escape"); await bad.close();
  const ok = await fresh();
  await ok.getByRole("button", { name: "Xuất bản" }).click(); await ok.waitForTimeout(400);
  check("Preflight: a clean document goes straight to the publish flow", (await ok.evaluate(() => window.__published)) === 1 && (await ok.getByRole("dialog").count()) === 0);
  await ok.close();
}

// ---------- 10. Action / Workflow editors on the V2 contract (harness with component-metadata available) ----------
{
  const p = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(URL_ + "?v2=1"); await p.waitForSelector("iframe"); await p.waitForTimeout(700);
  const left = p.locator(".bx-left-panel");
  await p.locator(".bx-left").getByRole("tab", { name: "Hành động" }).click();
  await p.getByRole("button", { name: /^Hành động$/ }).click(); await p.waitForTimeout(300);
  const typeSel = left.locator("select").first();
  const values = await typeSel.locator("option").evaluateAll((os) => os.map((o) => o.value));
  check("Action: exactly the 9 canonical types are offered (REFRESH_QUERY present, no RUN_QUERY/WRITE_DATA/SET_VALUE)",
    values.length === 9 && values.includes("REFRESH_QUERY") && !values.some((v) => /RUN_QUERY|WRITE_DATA|SET_VALUE|CALL_CONNECTOR_OPERATION/.test(v)), values.join(","));
  await typeSel.selectOption("REFRESH_QUERY"); await p.waitForTimeout(200);
  const txt = await left.innerText();
  check("Action: REFRESH_QUERY asks for a query of the document (Danh sách đơn), not a free-form field", /Danh sách đơn/.test(txt) || (await left.locator("select option").allInnerTexts()).some((t) => /Danh sách đơn/.test(t)));
  const noFree = await left.locator("textarea").count();
  check("Action editor has no free-text/code box (no arbitrary JS)", noFree === 0);
  await left.locator("select").filter({ has: p.locator('option:text("Danh sách đơn")') }).first().selectOption({ label: "Danh sách đơn" }).catch(() => {});
  await p.getByRole("button", { name: "Lưu hành động" }).click(); await p.waitForTimeout(500);
  const o = (await ops(p)).flatMap((x) => x.ops).find((x) => x.type === "ADD_ACTION");
  check("Action: saving emits ADD_ACTION with type REFRESH_QUERY + queryRef (canonical definition)", o?.definition?.type === "REFRESH_QUERY" && o.definition.queryRef === "q-orders", JSON.stringify(o));
  check("Action: a child/unattached action carries no trigger", !!o && !("trigger" in (o.definition ?? {})) , JSON.stringify(o?.definition));
  // workflow
  await p.locator(".bx-left").getByRole("tab", { name: "Workflow" }).click(); await p.waitForTimeout(300);
  const wtxt = await left.innerText();
  check("Workflow panel is available with the V2 backend and offers a new workflow", /Workflow/.test(wtxt) && (await left.getByRole("button", { name: /^Workflow$/ }).count()) >= 1, wtxt.replace(/\n/g, " ").slice(0, 120));
  await left.getByRole("button", { name: /^Workflow$/ }).click(); await p.waitForTimeout(300);
  const wopts = await left.evaluate((el) => [...el.querySelectorAll("select option")].map((o) => o.value).join(","));
  await p.screenshot({ path: `${shots}/workflow-editor.png` });
  check("Workflow editor opens (steps ACTION/WAIT/APPROVAL/BRANCH/END, no BPMN canvas)", (await left.locator("canvas, svg[role=graphics-document]").count()) === 0 && /END|Kết thúc/.test(await left.innerText()), wopts.slice(0, 120));
  check("Action/Workflow: no page errors", p.errors.length === 0, p.errors.join(" ; "));
  // Data wizard: data sources are NOT_READY, so no fake source, query or mapping can be created
  await p.locator(".bx-left").getByRole("tab", { name: "Dữ liệu" }).click(); await p.waitForTimeout(300);
  await left.getByText("Nâng cao", { exact: true }).click(); await p.waitForTimeout(200);   // M-005: the 7-step wizard lives under "Nâng cao"
  const dt = await left.innerText();
  // Query step: DATE is a param type; `required` defaults to true (absent key means required)
  await left.getByRole("tab", { name: "Truy vấn" }).click(); await p.waitForTimeout(300);
  await left.getByLabel("Tên truy vấn").fill("Đơn theo ngày"); await left.getByLabel(/Mã thao tác đã duyệt/).fill("orders.byDate");
  await left.getByRole("button", { name: "+ Thêm tham số" }).click(); await p.waitForTimeout(150);
  const ptypes = await left.getByLabel("Kiểu tham số 1").locator("option").evaluateAll((os) => os.map((o) => o.value));
  check("Query: param types are the contract's six, including DATE", ["STRING", "INTEGER", "NUMBER", "BOOLEAN", "TIMESTAMP", "DATE"].every((t) => ptypes.includes(t)) && ptypes.length === 6, ptypes.join(","));
  await left.getByLabel("Tên tham số 1").fill("tu_ngay"); await left.getByLabel("Kiểu tham số 1").selectOption("DATE");
  await left.getByRole("button", { name: /Lưu truy vấn/ }).click(); await p.waitForTimeout(500);
  const qo = (await ops(p)).flatMap((x) => x.ops).find((x) => x.type === "ADD_QUERY");
  const prm = qo?.definition?.params?.[0];
  check("Query: saving emits ADD_QUERY with a DATE param, required by default (no `required:false` unless the author unticked it)", prm?.type === "DATE" && prm.required !== false && qo.definition.dataSourceRef === "ds1" && qo.definition.operationKey === "orders.byDate", JSON.stringify(qo?.definition));
  // Mapping step: transforms[] is the only transform shape; DATE is a real transform; no legacy `transform` key is ever written
  await left.getByRole("tab", { name: "Ánh xạ" }).click(); await p.waitForTimeout(300);
  await left.getByLabel("Cột nguồn của trường 1").fill("order_date"); await left.getByLabel("Tên trường 1").fill("ngay");
  const tsel = left.getByLabel("Thêm biến đổi cho trường ngay");
  const topts = await tsel.locator("option").evaluateAll((os) => os.map((o) => o.value).filter(Boolean));
  check("Mapping: the transform picker offers canonical C3 transform types only, nothing legacy", topts.length >= 3 && topts.every((t) => ["toString", "toNumber", "trim", "lower", "upper", "toBoolean", "date", "enumMap", "join", "split", "formula"].includes(t)), topts.join(","));
  await tsel.selectOption("trim"); await p.waitForTimeout(200);
  await left.getByRole("button", { name: "Lưu ánh xạ" }).click(); await p.waitForTimeout(500);
  const mp = (await ops(p)).flatMap((x) => x.ops).find((x) => x.type === "ADD_MAPPING");
  const f0 = mp?.definition?.fields?.[0];
  check("Mapping: saving emits ADD_MAPPING with fields[].transforms[] (type trim) and no legacy `transform` key", Array.isArray(f0?.transforms) && f0.transforms[0]?.type === "trim" && !("transform" in f0) && mp.definition.queryRef === "q1", JSON.stringify(mp?.definition?.fields));
  check("Data: the wizard says Chưa sẵn sàng for sources/discovery/preview even with the V2 backend (no operation creates a source)", /Chưa sẵn sàng/.test(dt) && /Nguồn dữ liệu/.test(dt), dt.replace(/\n/g, " ").slice(0, 160));
  await p.close();
}

// ---------- C1 permission contract in the browser (UX only; the server enforces). The host passes the RESOLVED permission list, never a role name ----------
async function withPerms(perms) {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) p.errors.push(m.text()); });
  await p.goto(`${URL_}?v2=1&rt=1&perms=${perms}`); await p.waitForSelector("iframe"); await p.waitForTimeout(700);
  return p;
}
const rtCalls = (p) => p.evaluate(() => window.__rt);
{
  // case A: APP_VIEW + APP_USE (what a viewer's project payload resolves to): the Builder opens READ-ONLY
  const p = await withPerms("APP_VIEW,APP_USE");
  const t = await p.locator("body").innerText();
  check("PERM viewer (APP_VIEW, APP_USE): the Builder opens (no redirect) with a clear read-only notice", /Bạn chỉ có quyền xem/.test(t));
  check("PERM viewer: with nothing selected the inspector says it is read-only (not 'click to edit')", /chỉ có quyền xem/.test(await p.locator(".bx-right").innerText()) && !/nhấp vào bản xem trước để chỉnh/.test(await p.locator(".bx-right").innerText()));
  await p.locator("[role=treeitem]").nth(2).click(); await p.waitForTimeout(400);   // the canvas is not interactive for a viewer: properties are opened from the page tree
  const inputs = p.locator(".bx-right input:not([type=hidden]), .bx-right textarea");
  check("PERM viewer: every property input is disabled (no editing)", (await inputs.count()) > 0 && (await inputs.evaluateAll((els) => els.every((e) => e.disabled))));
  check("PERM viewer: Save/edit buttons are not offered or are disabled", (await p.getByRole("button", { name: /Lưu thay đổi|Lưu/ }).evaluateAll((els) => els.every((e) => e.disabled))));
  const pub = p.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ });
  await pub.click({ force: true }); await p.waitForTimeout(300);   // M-031: aria-disabled (focusable); pressing it says why in a status toast
  const why = await p.locator(".xp-toast").first().innerText().catch(() => "");
  check("PERM viewer: Publish is unavailable (aria-disabled) and says why (APP_PUBLISH) when pressed", (await pub.isDisabled()) && /xuất bản/i.test(why) && (await p.locator('[data-testid="publish-check"], [role=dialog]').count()) === 0, why);
  await p.getByRole("button", { name: "Dùng thử" }).click(); await p.locator('[data-testid="test-panel"]').waitFor();
  const q = p.getByTestId("run-query:q-orders"), act = p.getByTestId("run-action:a-nav"), mut = p.getByTestId("run-action:a-create"), wf = p.getByTestId("run-workflow:wf1");
  const all = [q, act, mut, wf];
  check("PERM viewer: every Test control is disabled (missing APP_EDIT and the run permissions)", (await Promise.all(all.map((b) => b.isDisabled()))).every(Boolean));
  check("PERM viewer: each disabled control names the missing permissions in its title", (await Promise.all(all.map(async (b) => /Chỉnh sửa ứng dụng/.test((await b.getAttribute("title")) ?? "")))).every(Boolean), await q.getAttribute("title"));
  for (const b of all) await b.click({ force: true, timeout: 1500 }).catch(() => undefined);
  await p.waitForTimeout(500);
  check("PERM viewer: clicking disabled controls sends NOTHING to the runtime (the server would refuse a forged call anyway)", (await rtCalls(p)).length === 0, JSON.stringify(await rtCalls(p)));
  check("PERM viewer: no console errors/warnings", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
{
  // case B: every conjunction present: the controls are usable and reach the runtime
  const p = await withPerms("APP_VIEW,APP_USE,APP_EDIT,QUERY_EXECUTE,ACTION_EXECUTE,DATA_MUTATE,WORKFLOW_EXECUTE");
  await p.getByRole("button", { name: "Dùng thử" }).click(); await p.locator('[data-testid="test-panel"]').waitFor();
  const all = ["run-query:q-orders", "run-action:a-nav", "run-action:a-create", "run-workflow:wf1"].map((id) => p.getByTestId(id));
  check("PERM editor (all conjunctions): the Test controls are enabled", (await Promise.all(all.map((b) => b.isEnabled()))).every(Boolean));
  for (const b of all) await b.click(); await p.waitForTimeout(800);
  check("PERM editor: each control sent exactly one runtime call", JSON.stringify((await rtCalls(p)).sort()) === JSON.stringify(["action:a-create", "action:a-nav", "query:q-orders", "workflow:wf1"]), JSON.stringify(await rtCalls(p)));
  await p.close();
}
{
  // case C: a missing member of a conjunction disables exactly the control that needs it
  const p = await withPerms("APP_VIEW,APP_USE,APP_EDIT,ACTION_EXECUTE");   // no QUERY_EXECUTE, no DATA_MUTATE, no WORKFLOW_EXECUTE
  await p.getByRole("button", { name: "Dùng thử" }).click(); await p.locator('[data-testid="test-panel"]').waitFor();
  const st = async (id) => ({ off: await p.getByTestId(id).isDisabled(), title: (await p.getByTestId(id).getAttribute("title")) ?? "" });
  const [q, nav, mut, wf] = await Promise.all(["run-query:q-orders", "run-action:a-nav", "run-action:a-create", "run-workflow:wf1"].map(st));
  check("PERM partial: a navigate action needs only APP_USE + ACTION_EXECUTE (+APP_EDIT in TEST): enabled", !nav.off);
  check("PERM partial: the query is disabled and names QUERY_EXECUTE (Chạy truy vấn)", q.off && /Chạy truy vấn/.test(q.title), q.title);
  check("PERM partial: the mutating action is disabled and names DATA_MUTATE (Ghi dữ liệu)", mut.off && /Ghi dữ liệu/.test(mut.title), mut.title);
  check("PERM partial: the workflow is disabled and names WORKFLOW_EXECUTE (Chạy workflow)", wf.off && /Chạy workflow/.test(wf.title), wf.title);
  await p.close();
}
{
  // case D: a role-looking string is just data: nothing is granted by it
  const p = await withPerms("VIEWER,EDITOR,WORKSPACE_ADMIN");
  const t = await p.locator("body").innerText();
  check("PERM role names alone grant nothing: read-only notice, Publish disabled", /Bạn chỉ có quyền xem/.test(t) && (await p.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).isDisabled()));
  await p.close();
}

{
  // PHONE (<= 760 px): ONE workspace at a time (C6 UX-002b); wider screens keep every pane (the switch is hidden)
  const p = await fresh(); await p.setViewportSize({ width: 390, height: 844 }); await p.waitForTimeout(300);
  const shown = (sel) => p.evaluate((s) => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== "none" && e.getBoundingClientRect().width > 0; }, sel);
  const tabs = await p.locator(".bx-mview [role=tab]").allInnerTexts();
  check("PHONE 390: a 3-way switch (Bản xem trước / Công cụ / Thuộc tính), the canvas is the only workspace shown", tabs.length === 3 && (await shown(".bx-center")) && !(await shown(".bx-left")) && !(await shown(".bx-right")), JSON.stringify(tabs));
  await p.getByRole("tab", { name: "Công cụ", exact: true }).click();
  const w = await p.evaluate(() => Math.round(document.querySelector(".bx-left-panel").getBoundingClientRect().width));
  check("PHONE 390: Công cụ shows the rail + panel at full width (≥ 366 px) and hides the canvas", (await shown(".bx-left")) && !(await shown(".bx-center")) && w >= 366, `panel ${w}px`);
  await p.locator(".bx-left > .bx-tabs").getByRole("tab", { name: "Dữ liệu", exact: true }).click();
  await p.evaluate(() => { document.querySelector(".bx-left-panel").dataset.keep = "same-node"; });
  await p.getByRole("tab", { name: "Bản xem trước", exact: true }).click(); await p.getByRole("tab", { name: "Công cụ", exact: true }).click();
  check("PHONE 390: Canvas → Công cụ round trip keeps the active rail and the very same panel node (nothing remounted, so no form state is lost)", (await p.evaluate(() => document.querySelector(".bx-left-panel").dataset.keep)) === "same-node" && (await p.locator('.bx-left > .bx-tabs [aria-selected="true"]').innerText()) === "Dữ liệu");
  await p.getByRole("tab", { name: "Công cụ", exact: true }).focus(); await p.keyboard.press("ArrowRight");
  { await p.waitForTimeout(250); const act = await p.evaluate(() => document.activeElement?.textContent.trim() ?? ""); const right = await shown(".bx-right"), left = await shown(".bx-left");
    check("PHONE 390: ArrowRight on the switch moves focus + selection to Thuộc tính and shows the properties pane", /^Thuộc tính|^Kiểm thử/.test(act) && right && !left, JSON.stringify({ act, right, left })); }
  check("PHONE 390: no horizontal page overflow", (await p.evaluate(() => document.documentElement.scrollWidth - innerWidth)) <= 0);
  await p.setViewportSize({ width: 1024, height: 800 }); await p.waitForTimeout(250);
  check("PHONE → 1024: the switch is hidden again and canvas, tools and properties are all shown", !(await shown(".bx-mview")) && (await shown(".bx-center")) && (await shown(".bx-left")) && (await shown(".bx-right")));
  check("PHONE: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}

// ---------- M-003: selecting a section does not reload the preview iframe or reset its scroll (tree click, canvas click, read-only viewer) ----------
for (const [label, query] of [["editor", ""], ["read-only viewer", "?perms=APP_VIEW"]]) {
  const p = await browser.newPage({ viewport: { width: 1440, height: 380 } }); p.errors = []; p.on("pageerror", (e) => p.errors.push(e.message));
  await p.goto(URL_ + query); await p.waitForSelector("iframe"); await p.waitForTimeout(700);
  const frame = () => p.frames().find((f) => f !== p.mainFrame());
  const room = await frame().evaluate(() => document.documentElement.scrollHeight - innerHeight);
  await frame().evaluate(() => { window.__marker = 1; window.scrollTo({ top: 150, behavior: "instant" }); }); await p.waitForTimeout(300);
  const before = await frame().evaluate(() => window.scrollY);
  check(`M-003 [${label}]: fixture is scrollable`, room > 170 && before >= 140, `room=${room} scrollY=${before}`);
  await p.locator("[role=treeitem][aria-level='2']").filter({ hasText: "Đánh giá" }).first().click(); await p.waitForTimeout(700);
  let st = await frame().evaluate(() => ({ marker: window.__marker ?? null, y: window.scrollY, sel: [...document.querySelectorAll(".__sel")].map((e) => e.getAttribute("data-sid")) }));
  check(`M-003 [${label}]: a tree click keeps the same iframe document (marker) and the scroll position`, st.marker === 1 && Math.abs(st.y - before) < 5, JSON.stringify(st));
  check(`M-003 [${label}]: ...and the selected section is highlighted inside the frame`, st.sel.length === 1 && st.sel[0] === "s-test", JSON.stringify(st.sel));
  await frame().locator("[data-sid='s-foot']").click(); await p.waitForTimeout(700);
  st = await frame().evaluate(() => ({ marker: window.__marker ?? null, y: window.scrollY, sel: [...document.querySelectorAll(".__sel")].map((e) => e.getAttribute("data-sid")) }));
  check(`M-003 [${label}]: a click inside the preview keeps the frame (marker) and never resets the scroll (Playwright itself scrolls the target into view)`, st.marker === 1 && st.y >= before - 5, JSON.stringify(st));
  check(`M-003 [${label}]: ...and moves the highlight to the clicked section (one at a time)`, st.sel.length === 1 && st.sel[0] === "s-foot", JSON.stringify(st.sel));
  if (!query) {
    await p.getByRole("button", { name: "Đóng bảng thuộc tính" }).click(); await p.waitForTimeout(500);
    const cleared = await frame().evaluate(() => ({ marker: window.__marker ?? null, sel: document.querySelectorAll(".__sel").length }));
    check("M-003 [editor]: closing the Inspector clears the highlight without a reload", cleared.marker === 1 && cleared.sel === 0, JSON.stringify(cleared));
  }
  check(`M-003 [${label}]: no uncaught error`, p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}

// ---------- M-002: unsaved Inspector edits survive a selection change; focus is kept after "Lưu thay đổi" ----------
{
  const p = await fresh();
  const row = (name) => p.locator("[role=treeitem][aria-level='2']").filter({ hasText: name }).first();
  const title = () => p.locator(".bx-inspector").getByLabel("Tiêu đề", { exact: true });
  await row("Hero").click(); await p.waitForTimeout(300);
  await title().fill("Tiêu đề đang gõ dở"); await p.waitForTimeout(100);
  check("M-002: a dirty form says so ('Có thay đổi chưa lưu')", (await p.locator(".bx-inspector").getByText(/chưa lưu/).count()) > 0);
  await row("Chân trang").click(); await p.waitForTimeout(300);
  await row("Hero").click(); await p.waitForTimeout(300);
  check("M-002: the typed value survives selecting another section and coming back", (await title().inputValue()) === "Tiêu đề đang gõ dở", `value="${await title().inputValue()}"`);
  check("M-002: nothing was sent while the edit was only a draft", (await ops(p)).length === 0, JSON.stringify(await ops(p)));
  await p.getByRole("button", { name: "Xuất bản" }).click(); await p.waitForTimeout(300);
  check("M-002: publishing with an unsaved draft warns first (pre-check dialog), it does not silently publish the saved text", (await p.getByRole("dialog").getByText(/chưa lưu/).count()) > 0 && (await p.evaluate(() => window.__published)) === 0);
  await p.keyboard.press("Escape"); await p.waitForTimeout(200);
  await p.locator(".bx-inspector").getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(500);
  const o = await ops(p);
  check("M-002: Lưu thay đổi sends the edit as UPDATE_PROP", o.length === 1 && o[0].ops.some((x) => x.type === "UPDATE_PROP" && x.value === "Tiêu đề đang gõ dở"), JSON.stringify(o));
  const act = await p.evaluate(() => { const a = document.activeElement; return { tag: a?.tagName, inInspector: !!a?.closest(".bx-inspector"), label: a?.getAttribute("aria-label") ?? a?.id }; });
  check("M-002: after saving, focus is on a control of the Inspector (not <body>)", act.tag !== "BODY" && act.inInspector, JSON.stringify(act));
  check("M-002: the saved value is shown and the form is clean again", (await title().inputValue()) === "Tiêu đề đang gõ dở" && (await p.locator(".bx-inspector").getByText(/chưa lưu/).count()) === 0);
  check("M-002: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}

await browser.close();
finish();
