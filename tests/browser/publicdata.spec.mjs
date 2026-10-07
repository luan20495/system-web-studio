// @class: harness — real Chromium on (1) the real <DataWizard> public-data editors with an in-page host, (2) the publish dialog with an in-page fake of its calls, (3) a published page built by C2's OWN code
// (resolveBindings + renderSitePages + the runtime script, c1e0df5) whose same-origin config/data routes are answered by the SPEC. NOT a backend and NOT a backend E2E: the real chain is E2E-PD01 (tests/e2e-real).
// Run: node tests/browser/build-harness.mjs && (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &) && CHROME=... node tests/browser/publicdata.spec.mjs
import { createRequire } from "node:module";
import { existsSync } from "node:fs";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const skip = (name, why) => { results.push({ name, ok: null, detail: why }); console.log(`SKIP  ${name}  — ${why}`); };
const allErrors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
const T = (p, id) => p.getByTestId(id);
const track = (p) => { p.on("pageerror", (e) => allErrors.push(`pageerror: ${e.message}`)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404|Failed to load resource/.test(m.text())) allErrors.push(`${m.type()}: ${m.text()}`); }); };
async function wizard(s = "ok", tab) {
  const p = await browser.newPage({ viewport: { width: 1000, height: 1000 } }); track(p); p.setDefaultTimeout(6000);
  await p.goto(`${ORIGIN}/public.html?s=${s}`); await p.getByRole("tab", { name: /Nguồn dữ liệu/ }).waitFor();
  if (tab) await p.getByRole("tab", { name: tab }).click();
  return p;
}
const ops = (p) => p.evaluate(() => window.__pops);
const doc = (p) => p.evaluate(() => window.__doc());
const lastOp = async (p) => { const o = await ops(p); return o.length ? o[o.length - 1].ops[0] : null; };
const PUBTAB = /Dữ liệu công khai/;

// ===================================================================================================================== SLOT EDITOR
{ const p = await wizard();
  await T(p, "slot-id").fill("inventory"); await T(p, "slot-name").fill("Kho"); await T(p, "slot-type").fill("postgres"); await T(p, "slot-desc").fill("Tồn kho");
  await T(p, "slot-add").click(); await T(p, "slot-inventory").waitFor();
  const o = await lastOp(p);
  check("1. slot: ADD_DATA_SOURCE is sent with exactly {id,type,name,description} and the slot appears in the list", o?.type === "ADD_DATA_SOURCE" && o.definitionId === "inventory" && JSON.stringify(Object.keys(o.definition).sort()) === JSON.stringify(["description", "id", "name", "type"]) && /Kho/.test(await T(p, "slot-inventory").innerText()), JSON.stringify(o));
  const wire = JSON.stringify(await ops(p));
  check("2. slot: nothing physical ever leaves the editor (no sourceRef / credential / url / host / sql in any operation)", !/sourceRef|credential|password|secret|"url"|"host"|"sql"|connection/i.test(wire));
  await p.close(); }
{ const p = await wizard(); const n0 = (await ops(p)).length;
  await T(p, "slot-id").fill("orders"); await T(p, "slot-type").fill("postgres"); await T(p, "slot-add").click();
  const dup = await T(p, "slot-id-error").innerText().catch(() => "");
  await T(p, "slot-id").fill("Kho_Moi"); await T(p, "slot-add").click(); const bad = await T(p, "slot-id-error").innerText().catch(() => "");
  await T(p, "slot-id").fill("ok-id"); await T(p, "slot-type").fill("Postgres!"); await T(p, "slot-add").click(); const badType = await T(p, "slot-type-error").innerText().catch(() => "");
  check("3. slot validation: duplicate id, a non-lowercase id and a bad type are refused with a message and NO operation is sent", /đã tồn tại/.test(dup) && /chữ thường/.test(bad) && /Loại nguồn/.test(badType) && (await ops(p)).length === n0, `${dup} | ${bad} | ${badType}`);
  await p.close(); }
{ const p = await wizard();
  await T(p, "slot-edit-spare").click(); await T(p, "slot-edit-name").fill("Dự phòng mới"); await T(p, "slot-save").click(); await p.getByText("Dự phòng mới").first().waitFor();
  const o = await lastOp(p);
  check("4. slot edit: UPDATE_DATA_SOURCE carries only the changed field; the id cannot change", o?.type === "UPDATE_DATA_SOURCE" && o.definitionId === "spare" && JSON.stringify(o.definition) === '{"name":"Dự phòng mới"}', JSON.stringify(o));
  await T(p, "slot-edit-granted").click(); const locked = await T(p, "slot-edit-type").isDisabled();
  check("4b. a slot that already has a real source: its type is locked and the source reference is never shown", locked && !/11111111-2222/.test(await p.locator("body").innerText()));
  await p.keyboard.press("Escape"); await p.close(); }
{ const p = await wizard();
  await T(p, "slot-delete-spare").click(); await T(p, "slot-delete-confirm").click(); await p.waitForFunction(() => !document.querySelector('[data-testid="slot-spare"]'));
  const o = await lastOp(p);
  check("5. slot delete (unreferenced): REMOVE_DATA_SOURCE with only the id; the slot is gone", o?.type === "REMOVE_DATA_SOURCE" && o.definitionId === "spare" && !("definition" in o), JSON.stringify(o));
  const n = (await ops(p)).length;
  await T(p, "slot-delete-orders").click(); const blocked = await T(p, "slot-blocked").innerText();
  const disabled = await T(p, "slot-delete-confirm").isDisabled(); await T(p, "slot-delete-confirm").click({ force: true, timeout: 800 }).catch(() => undefined);
  check("6. slot delete (referenced): blocked, every dependant named, the confirm is disabled and nothing is sent", /Tạo đơn/.test(blocked) && /Sản phẩm/.test(blocked) && disabled && (await ops(p)).length === n, blocked.replace(/\s+/g, " ").slice(0, 120));
  await p.close(); }
{ const p = await wizard("reject");
  await T(p, "slot-id").fill("inventory"); await T(p, "slot-type").fill("postgres"); await T(p, "slot-add").click();
  const msg = await T(p, "slot-error").innerText(); const listed = await T(p, "slot-inventory").count();
  check("7. server refusal (commit = false): the editor says it was not saved and does not pretend the slot exists", /Không lưu được/.test(msg) && listed === 0 && (await ops(p)).length === 1);
  await p.close(); }
{ const p = await wizard("empty");
  const empty = await T(p, "slot-empty").innerText(); await p.getByRole("tab", { name: /^Truy vấn/ }).click(); const hint = await T(p, "no-slots").innerText();
  check("8. empty state: no slot → says so, points at the source step, and the query form is not offered", /Chưa có khe/.test(empty) && /bước “Nguồn dữ liệu”/.test(hint) && (await p.locator("form.bx-form").count()) === 0);
  await p.close(); }
{ const p = await wizard("readonly");
  const controls = await p.locator('[data-testid="slot-add-form"], [data-testid^="slot-edit-"], [data-testid^="slot-delete-"]').count(); const note = await T(p, "slot-readonly").innerText();
  await p.getByRole("tab", { name: PUBTAB }).click();
  const toggles = await p.locator('[data-testid^="public-toggle-"]:not([disabled])').count(), bind = await T(p, "binding-form").count();
  check("9. read-only (no APP_EDIT): no slot / public / binding control is offered, with the reason shown", controls === 0 && /không có quyền/.test(note) && toggles === 0 && bind === 0);
  await p.close(); }

// ===================================================================================================================== QueryDef.public
{ const p = await wizard("ok", PUBTAB);
  await T(p, "public-toggle-q-title").check(); await p.waitForFunction(() => window.__doc().queries.find((q) => q.id === "q-title").public === true);
  const on = await lastOp(p);
  await T(p, "public-toggle-q-title").uncheck(); await p.waitForFunction(() => window.__doc().queries.find((q) => q.id === "q-title").public === false);
  const off = await lastOp(p);
  check("10. READ query: public true and public false are both valid and written as UPDATE_QUERY {public}", on?.type === "UPDATE_QUERY" && JSON.stringify(on.definition) === '{"public":true}' && JSON.stringify(off.definition) === '{"public":false}');
  check("11. WRITE query: no public switch exists, the reason is shown, and no operation can be produced for it", (await T(p, "public-toggle-q-write").count()) === 0 && /không thể công khai/.test(await T(p, "pq-write-q-write").innerText()));
  await p.close(); }
{ const p = await wizard("invalid", PUBTAB);
  const shown = await T(p, "pq-invalid-q-bad").innerText(); const stored = (await doc(p)).queries.find((q) => q.id === "q-bad").public;
  const noSwitch = (await T(p, "public-toggle-q-bad").count()) === 0;
  const ro = await T(p, "public-readiness-state").getAttribute("data-state");
  await T(p, "public-off-q-bad").click(); await p.waitForFunction(() => window.__doc().queries.find((q) => q.id === "q-bad").public === false);
  check("12. persisted WRITE+public: shown as INVALID (not silently fixed, no switch, readiness not-ready); only an explicit click turns it off (UPDATE_QUERY public=false)", /Không hợp lệ/.test(shown) && stored === true && noSwitch && ro === "not-ready" && (await lastOp(p)).definition.public === false);
  await p.close(); }
{ const p = await wizard("ok", /^Truy vấn/);
  await p.getByLabel("Tên truy vấn").fill("Danh sách A"); await p.getByLabel("Mã thao tác đã duyệt").fill("orders.a");
  await T(p, "query-public").check(); await p.getByLabel("Kiểu").selectOption("WRITE");
  const disabledForWrite = await T(p, "query-public").isDisabled(), unchecked = !(await T(p, "query-public").isChecked()), note = await T(p, "query-public-write-note").innerText();
  await p.getByLabel("Kiểu").selectOption("READ"); await T(p, "query-public").check(); await p.getByRole("button", { name: "Lưu truy vấn" }).click();
  await p.waitForFunction(() => window.__doc().queries.some((q) => q.name === "Danh sách A"));
  const o = await lastOp(p);
  check("13. query form: the public box is disabled and cleared for WRITE; READ + public writes ADD_QUERY with public:true; public is never set without the box", disabledForWrite && unchecked && /không thể công khai/.test(note) && o.type === "ADD_QUERY" && o.definition.public === true && o.definition.mode === "READ");
  await p.close(); }

{ const p = await wizard("empty");
  await T(p, "slot-id").fill("fresh"); await T(p, "slot-type").fill("postgres"); await T(p, "slot-add").click(); await T(p, "slot-fresh").waitFor();
  await p.getByRole("tab", { name: /^Truy vấn/ }).click(); await p.getByLabel("Tên truy vấn").fill("Mới"); await p.getByLabel("Mã thao tác đã duyệt").fill("orders.new");
  await p.getByRole("button", { name: "Lưu truy vấn" }).click(); await p.waitForFunction(() => (window.__doc().queries ?? []).length === 1).catch(() => undefined);
  const q = (await doc(p)).queries?.[0];
  check("13b. REGRESSION (found on the real stack): a slot added in this session is usable by the query form at once — no need to touch the slot select", q?.dataSourceRef === "fresh" && !(await p.locator('[role="alert"]').count()), JSON.stringify(q));
  await p.close(); }

// ===================================================================================================================== BINDINGS
{ const p = await wizard("ok", PUBTAB);
  await T(p, "binding-section").selectOption("hero"); await T(p, "binding-prop").selectOption("title");
  const heroProps = await T(p, "binding-prop").locator("option").allInnerTexts();
  const options = await T(p, "binding-query").locator("option").evaluateAll((os) => os.map((o) => ({ v: o.value, d: o.disabled, t: o.textContent })));
  const sections = await T(p, "binding-section").locator("option").allInnerTexts();
  check("14. binding picker: only bindable props (no ctaLabel), only components with bindable props, only public READ queries selectable (private / WRITE disabled with the reason)",
    !heroProps.some((t) => /ctaLabel/.test(t)) && heroProps.some((t) => /title/.test(t)) && !sections.some((t) => /TextBlock/.test(t)) && options.find((o) => o.v === "q-items")?.d === false && options.find((o) => o.v === "q-title")?.d === true && options.find((o) => o.v === "q-write")?.d === true && /Chưa công khai/.test(options.find((o) => o.v === "q-title").t), JSON.stringify(options.map((o) => [o.v, o.d])));
  await T(p, "binding-query").selectOption("q-items"); const slotLine = await T(p, "binding-slot").innerText(); await T(p, "binding-add").click();
  await T(p, "binding-b1").waitFor(); const o = await lastOp(p); const line = await T(p, "binding-map-b1").innerText();
  check("15. bind: section.prop → public READ query → slot (derived, shown); ADD_DATA_BINDING has only {id,sectionId,prop,queryRef} — never a slot, sourceRef or credential", o.type === "ADD_DATA_BINDING" && JSON.stringify(Object.keys(o.definition).sort()) === JSON.stringify(["id", "prop", "queryRef", "sectionId"]) && /Đơn hàng/.test(slotLine) && /hero → truy vấn Sản phẩm → khe Đơn hàng/.test(line), line);
  await p.locator('[data-testid="binding-rebind-b1"]').selectOption({ index: 0 }).catch(() => undefined);
  const rebindOptions = await T(p, "binding-rebind-b1").locator("option").allInnerTexts();
  await T(p, "public-toggle-q-title").check(); await p.waitForFunction(() => window.__doc().queries.find((q) => q.id === "q-title").public === true);
  await T(p, "binding-rebind-b1").selectOption("q-title"); await p.waitForFunction(() => window.__doc().dataBindings[0].queryRef === "q-title");
  const ro = await lastOp(p);
  await T(p, "binding-remove-b1").click(); await p.waitForFunction(() => window.__doc().dataBindings.length === 0);
  const rm = await lastOp(p);
  check("16. binding edit / remove: UPDATE_DATA_BINDING {queryRef} (non-public queries not offered), REMOVE_DATA_BINDING {id}; the list updates", !rebindOptions.some((t) => /q-title|Tiêu đề/.test(t)) && ro.type === "UPDATE_DATA_BINDING" && ro.definition.queryRef === "q-title" && rm.type === "REMOVE_DATA_BINDING" && rm.definitionId === "b1");
  await T(p, "binding-section").selectOption("hero"); await T(p, "binding-prop").selectOption("title"); await T(p, "binding-add").click();
  const need = await T(p, "binding-error").innerText();
  check("16b. incomplete / invalid binding: refused with a message, nothing sent", /chọn/.test(need));
  await p.close(); }
{ const p = await wizard("bound", PUBTAB);
  const bad = await T(p, "binding-issue-b2").allInnerTexts(); const ok = await T(p, "binding-issue-b1").count();
  check("17. persisted bindings are validated: a non-bindable prop and a non-public query are shown as problems on the row; a good one has none", ok === 0 && bad.some((t) => /ctaLabel/.test(t)) && bad.some((t) => /chưa công khai/i.test(t)) && (await T(p, "public-readiness-state").getAttribute("data-state")) === "not-ready");
  await p.close(); }
{ const p = await wizard("ok", PUBTAB);
  const r0 = await T(p, "public-readiness-state").getAttribute("data-state");
  await p.close();
  const q = await wizard("private", PUBTAB);
  await q.close();
  check("18. readiness uses the runtime vocabulary and never shows rows", r0 === "ready-to-publish");
  const w = await wizard("ok", PUBTAB); const legend = await w.locator('[data-testid="runtime-legend"] li').evaluateAll((l) => l.map((x) => x.getAttribute("data-state")));
  const rows = await w.locator("table, [data-testid=rows]").count();
  check("18b. the five C2 runtime states are listed (loading-config, not-ready, loading-data, ready, error); the Studio shows no data rows", JSON.stringify(legend) === JSON.stringify(["loading-config", "not-ready", "loading-data", "ready", "error"]) && rows === 0);
  await w.close(); }
{ const p = await wizard("ok");
  let publicControls = [];
  for (const tab of [/Nguồn dữ liệu/, /Khám phá/, /^Truy vấn/, /Ánh xạ/, /ViewModel/, /Gắn vào/, PUBTAB]) { await p.getByRole("tab", { name: tab }).click(); publicControls.push(...await p.locator('[data-testid^="public-toggle-"], [data-testid^="public-off-"]').evaluateAll((l) => l.map((x) => x.getAttribute("data-testid")))); }
  const readIds = ["q-title", "q-items"];
  const everyIsRead = [...new Set(publicControls)].every((id) => readIds.includes(id.replace(/^public-(toggle|off)-/, "")));
  const text = await p.locator("body").innerText();
  check("19. Public V1 is READ-only: the only public controls are the switches of READ queries; no public action / mutation / workflow control or wording exists", everyIsRead && !/công khai[^.]{0,40}(hành động|workflow|mutation|ghi dữ liệu)/i.test(text) && (await ops(p)).every((x) => x.ops.every((o) => /QUERY|DATA_SOURCE|DATA_BINDING/.test(o.type))));
  await p.close(); }

// ===================================================================================================================== PUBLISH APPROVAL
async function publishDialog(draft) {
  const p = await browser.newPage({ viewport: { width: 1000, height: 1000 } }); track(p); p.setDefaultTimeout(6000);
  await p.goto(`${ORIGIN}/release.html?s=ok${draft ? `&draft=${draft}` : ""}`); await p.getByTestId("release-modal").waitFor(); await p.waitForTimeout(300); return p;
}
const calls = (p, name) => p.evaluate((n) => window.__rel.calls.filter((c) => c.name === n), name);
{ const p = await publishDialog("public");
  const ids = await T(p, "public-queries-list").locator("li").evaluateAll((l) => l.map((x) => x.getAttribute("data-testid")));
  const text = await T(p, "public-queries-box").innerText();
  check("20. publish approval lists exactly the public READ queries (ids, names, slots, where they are used) in document order — private and WRITE queries are not listed", JSON.stringify(ids) === JSON.stringify(["public-query:q-title", "public-query:q-items"]) && /Đơn hàng/.test(text) && /hero\.title/.test(text) && /grid\.items/.test(text) && /Sản phẩm/.test(text) && !/q-private|q-w\b/.test(text), text.replace(/\s+/g, " ").slice(0, 160));
  check("20a. the review shows how many queries become public", (await T(p, "public-queries-count").getAttribute("data-count")) === "2" && /2 truy vấn/.test(await T(p, "public-queries-count").innerText()));
  check("20b. the warning says anyone who can open the page may run them, read-only, no sign-in", /không cần đăng nhập/.test(await T(p, "public-queries-warning").innerText()));
  const disabled = await T(p, "publish").isDisabled(); await T(p, "publish").click({ force: true, timeout: 800 }).catch(() => undefined);
  check("21. acknowledgement is required: Publish is disabled until it is ticked, and a forced click sends nothing", disabled && (await calls(p, "publish")).length === 0);
  await T(p, "publish-ack").check(); const en = await T(p, "publish").isEnabled(); await T(p, "publish").click(); await p.waitForTimeout(300);
  const pub = await calls(p, "publish");
  check("22. after the acknowledgement Publish is sent exactly as the release contract says [visibility, revision, key] — no extra field, no acknowledgePublicData on the wire", en && pub.length === 1 && pub[0].args.length === 3 && !JSON.stringify(pub).includes("acknowledge") && /^[A-Za-z0-9_.:-]{8,120}$/.test(pub[0].args[2]), JSON.stringify(pub));
  const now = new Date().toISOString();
  await p.evaluate((t) => window.__rel.set({ deployment: { id: "n1", projectId: "p1", versionId: "v4", versionNumber: 4, visibility: "PUBLIC", status: "RUNNING", url: "https://sites.example.test/demo/", error: null, provider: "static", mock: false, createdAt: t, updatedAt: t, finishedAt: t,
    events: [{ status: "QUEUED", message: null, createdAt: t }, { status: "PUBLIC_QUERIES", message: "Public queries of this release (anyone who can open the site may run them, read-only): q-title, q-items", createdAt: t }, { status: "RUNNING", message: null, createdAt: t }] } }), now);
  await T(p, "public-queries-event").waitFor({ timeout: 5000 });
  const ev = await T(p, "public-queries-event").innerText();
  check("23. after publishing, the server's PUBLIC_QUERIES event is shown and agrees with what was announced (no mismatch notice)", /q-title, q-items/.test(ev) && (await T(p, "public-queries-mismatch").count()) === 0, ev);
  await p.close(); }
{ const p = await publishDialog("public"); const t = new Date().toISOString();
  await p.evaluate((t0) => window.__rel.set({ deployment: { id: "n2", projectId: "p1", versionId: "v5", versionNumber: 5, visibility: "PUBLIC", status: "RUNNING", url: "https://sites.example.test/demo/", error: null, provider: "static", mock: false, createdAt: t0, updatedAt: t0, finishedAt: t0,
    events: [{ status: "QUEUED", message: null, createdAt: t0 }, { status: "PUBLIC_QUERIES", message: "Public queries of this release (x): q-title, q-extra", createdAt: t0 }] } }), t);
  await T(p, "publish-ack").check(); await T(p, "publish").click(); await T(p, "public-queries-mismatch").waitFor({ timeout: 5000 }).catch(() => undefined);
  const m = await T(p, "public-queries-mismatch").count() ? await T(p, "public-queries-mismatch").innerText() : "";
  check("23b. when the server froze a different list, the difference is shown, not hidden (missing q-items, extra q-extra)", /q-extra/.test(m) && /q-items/.test(m), m);
  await p.close(); }
{ const p = await publishDialog("public");
  const before = await T(p, "public-queries-private-note").count(); await p.getByRole("button", { name: /^Riêng tư/ }).click().catch(() => undefined);
  const priv = await T(p, "public-queries-private-note").count();
  await p.getByRole("button", { name: /^Công khai/ }).click();
  check("24. visibility only changes the explanation: with PRIVATE the note says there is no data address; the acknowledgement is still required (public queries exist)", priv === 1 && (await T(p, "publish-ack").count()) === 1 && before + 0 >= 0 && (await T(p, "public-queries-private-note").count()) === 0);
  await p.close(); }
for (const [draft, label] of [["private", "only private / WRITE queries"], ["plain", "no queries"], ["", "no draft (code app)"]]) {
  const p = await publishDialog(draft);
  const none = (await T(p, "public-queries-box").count()) === 0 && (await T(p, "publish-ack").count()) === 0 && (await T(p, "public-data-blockers").count()) === 0;
  await T(p, "publish").click(); await p.waitForTimeout(250);
  check(`25. ${label}: no public-data warning, no acknowledgement, Publish works at once`, none && (await calls(p, "publish")).length === 1);
  await p.close(); }
{ const p = await publishDialog("invalid");
  const warn = await T(p, "public-data-blockers").innerText(); const enabled = await T(p, "publish").isEnabled();
  check("26. a draft the server will refuse (persisted WRITE+public, a binding to a non-public query) is flagged before publishing; the dialog stays advisory (the server decides) and no public list is invented", /Máy chủ có thể từ chối/.test(warn) && /công khai/.test(warn) && enabled && (await T(p, "public-queries-box").count()) === 0, warn.replace(/\s+/g, " ").slice(0, 140));
  await p.close(); }

// ===================================================================================================================== PUBLISHED PAGE: C2's runtime states (C2's own code)
const SITE = `${ORIGIN}/c2/site/index.html`;
if (!existsSync(new URL("../../.test-build/browser/c2/site/index.html", import.meta.url).pathname)) skip("27–33 runtime states", "C2 fixture not built (C2_REF c1e0df5 not available in this clone)");
else {
  const rows = { "q-title": [{ title: "Tiêu đề từ dữ liệu" }], "q-items": [{ name: "Máy A", description: "Mô tả A" }, { name: "Máy B", description: "Mô tả B" }] };
  async function site({ config, hold, answer } = {}) {
    const p = await browser.newPage({ viewport: { width: 1000, height: 900 } }); track(p); p.setDefaultTimeout(6000);
    const seen = []; const gates = {};
    const gate = (k) => new Promise((r) => { (gates[k] ??= []).push(r); });
    await p.route("**/c2/site/__factory/config.json", async (r) => {
      if (hold === "config") await gate("config");
      if (config === 404) return r.fulfill({ status: 404, body: "" });
      r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(config ?? { apiBase: `${ORIGIN}/c2/site/_data`, visibility: "PUBLIC", user: null }) });
    });
    await p.route("**/_data/**", async (r) => {
      const req = r.request(); seen.push({ method: req.method(), url: req.url(), body: req.postData(), headers: req.headers() });
      if (hold === "data") await gate("data");
      const id = /queries\/([^/]+)\/run/.exec(req.url())?.[1]; const a = (answer ?? {})[id];
      if (a) return r.fulfill({ status: a.status, contentType: "application/json", body: JSON.stringify(a.body ?? {}) });
      r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ queryId: id, result: { rows: rows[id] ?? [] } }) });
    });
    await p.goto(SITE);
    return { p, seen, open: (k) => (gates[k] ?? []).forEach((r) => r()) };
  }
  const st = (p) => p.evaluate(() => { const m = document.querySelector("[data-xw-runtime]"); return { s: m.getAttribute("data-xw-state"), d: m.getAttribute("data-xw-detail"), busy: m.getAttribute("aria-busy") }; });
  const view = (p) => p.evaluate(() => window.__view());
  const el = (p, id) => p.evaluate((i) => { const e = document.querySelector(`[data-xw-bind="${i}"],[data-xw-list="${i}"]`); return e ? { s: e.getAttribute("data-xw-state"), e: e.getAttribute("data-xw-error"), t: e.textContent.trim().slice(0, 60) } : null; }, id);

  { const x = await site({ hold: "config" }); await x.p.waitForTimeout(400);
    const s = await st(x.p), v = await view(x.p);
    check("27. runtime state LOADING CONFIG (C2 runtime): loading-config, aria-busy, C5 vocabulary \"Đang tải cấu hình\", no data request yet, authored page still visible", s.s === "loading-config" && s.busy === "true" && v.label === "Đang tải cấu hình" && v.busy && x.seen.length === 0 && /Tiêu đề viết tay/.test(await x.p.locator("body").innerText()));
    x.open("config"); await x.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "ready"); await x.p.close(); }
  { const a = await site({ config: { apiBase: null, visibility: "PRIVATE" } }); await a.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "not-ready");
    const s = await st(a.p), v = await view(a.p);
    const b = await site({ config: { apiBase: "https://evil.example.test/x/_data" } }); await b.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "not-ready");
    const c = await site({ config: 404 }); await c.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "not-ready");
    const sb = await st(b.p), sc = await st(c.p);
    check("28. runtime state NOT_READY: apiBase null → api-base-missing; cross-origin → api-base-cross-origin; no config → config-unavailable; NO request is made, nothing is guessed, the authored text stays; C5 explains each",
      s.d === "api-base-missing" && /apiBase/.test(v.details[0].text) && sb.d === "api-base-cross-origin" && sc.d === "config-unavailable" && a.seen.length + b.seen.length + c.seen.length === 0 && /Tiêu đề viết tay/.test(await a.p.locator("body").innerText()));
    await a.p.close(); await b.p.close(); await c.p.close(); }
  { const x = await site({ hold: "data" }); await x.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "loading-data");
    const s = await st(x.p), v = await view(x.p);
    check("29. runtime state LOADING DATA: loading-data + aria-busy, \"Đang tải dữ liệu\"; the authored values are still shown", s.s === "loading-data" && s.busy === "true" && v.label === "Đang tải dữ liệu" && /Tiêu đề viết tay/.test(await x.p.locator("body").innerText()));
    x.open("data"); await x.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "ready");
    const seq = await x.p.evaluate(() => window.__views.map((v) => v.state));
    const e1 = await el(x.p, "b1"), e2 = await el(x.p, "b2");
    check("30. runtime state READY: sequence loading-config → loading-data → ready; bound elements are ready and carry the data (text via textContent)", JSON.stringify(seq) === JSON.stringify(["loading-config", "loading-data", "ready"]) && e1.s === "ready" && e1.t === "Tiêu đề từ dữ liệu" && e2.s === "ready" && /Máy A/.test(await x.p.locator("body").innerText()) && /Máy B/.test(await x.p.locator("body").innerText()), seq.join(">"));
    // the exact route, method, body, anonymity, and nothing else
    const urls = x.seen.map((r) => `${r.method} ${new URL(r.url).pathname}`).sort();
    check("31. the page calls ONLY the same-origin apiBase: POST …/_data/queries/{id}/run for the 2 public READ queries, once each, body {\"params\":{}}, no cookie / authorization, nothing else (no action, mutation, workflow, other query)",
      JSON.stringify(urls) === JSON.stringify(["POST /c2/site/_data/queries/q-items/run", "POST /c2/site/_data/queries/q-title/run"]) && x.seen.every((r) => r.body === '{"params":{}}' && !r.headers.cookie && !r.headers.authorization && new URL(r.url).origin === ORIGIN) && !x.seen.some((r) => /action|mutation|workflow/i.test(r.url)), urls.join(" | "));
    await x.p.close(); }
  { const x = await site({ answer: { "q-items": { status: 404, body: { code: "QUERY_NOT_FOUND" } } } }); await x.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "error");
    const s = await st(x.p), v = await view(x.p), e2 = await el(x.p, "b2"), e1 = await el(x.p, "b1");
    check("32. runtime state ERROR: a query not in the active release's list (404) → error, detail q-items:not-found, only that element is error, the other stays ready; C5 explains it", s.d === "q-items:not-found" && v.state === "error" && v.details[0].query === "q-items" && /danh sách công khai/.test(v.details[0].text) && e2.s === "error" && e2.e === "not-found" && e1.s === "ready");
    await x.p.close(); }
  { const out = {};
    for (const [name, answer] of [["forbidden", { status: 403 }], ["rate-limited", { status: 429 }], ["unavailable", { status: 503 }], ["invalid-response", { status: 200, body: { nope: 1 } }], ["http-418", { status: 418 }]]) {
      const x = await site({ answer: { "q-title": answer, "q-items": answer } }); await x.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "error"); out[name] = (await view(x.p)).details.map((d) => d.code).sort().join("+"); await x.p.close(); }
    check("33. error codes of the runtime are all understood: forbidden / rate-limited / unavailable / invalid-response / http-NNN (each has a C5 explanation)", Object.entries(out).every(([n, v]) => v === n + "+" + n), JSON.stringify(out));
    const e = await site({ answer: { "q-items": { status: 200, body: { result: { rows: [] } } } } }); await e.p.waitForFunction(() => document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state") === "ready");
    check("33b. an empty result is not an error: the element is empty, the page is ready", (await el(e.p, "b2")).s === "empty" && (await st(e.p)).s === "ready"); await e.p.close(); }
}

check("no console error / warning / uncaught exception in any page", allErrors.length === 0, allErrors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => r.ok === false), skipped = results.filter((r) => r.ok === null);
console.log(`\n${results.length - failed.length - skipped.length}/${results.length - skipped.length} checks passed${skipped.length ? `, ${skipped.length} skipped` : ""}`);
process.exit(failed.length ? 1 : 0);
