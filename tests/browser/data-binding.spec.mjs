// @class: harness — real Chromium on the REAL Studio app with an in-test FAKE of /api/v1 (tests/browser/studio-app/); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// M-005: the guided "Hiển thị dữ liệu trong trang" flow of the Builder's Dữ liệu rail. It proves what the UI sends and shows for the answers a fake gives.
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/data-binding.spec.mjs
import { launch, open, check, wait, finish } from "./studio-app/lib.mjs";
import { newDataState } from "./studio-app/fake-api.mjs";
const b = await launch();
const patches = (p) => p.state.log.filter((l) => l.method === "PATCH" && /\/schema$/.test(l.path));
const opsOf = (p) => patches(p).flatMap((l) => l.body.operations ?? []);
async function design(state = newDataState(), viewport) {
  const p = await open(b, "/studio/projects/p1/design", { state, viewport }); await p.waitForSelector("iframe"); await wait(900);
  await p.locator(".bx-left").getByRole("tab", { name: "Dữ liệu", exact: true }).click(); await wait(500);
  return p;
}

// ---------- the panel ----------
{
  const p = await design();
  const panel = p.getByTestId("data-panel");
  check("panel: 'Đã kết nối' with an empty sentence and ONE primary button", (await panel.getByText("Đã kết nối").count()) > 0 && (await p.getByTestId("data-connected-empty").count()) === 1 && (await p.getByTestId("data-add").count()) === 1);
  check("panel: the 7-step wizard is under a collapsed 'Nâng cao'", (await p.getByTestId("data-advanced").getAttribute("open")) === null && !(await panel.getByRole("tab", { name: "Truy vấn" }).isVisible()));
  await panel.getByText("Nâng cao", { exact: true }).click(); await wait(300);
  const tabs = await panel.getByRole("tab").allInnerTexts();
  check("panel: 'Nâng cao' opens the complete wizard unchanged (7 steps)", ["Nguồn dữ liệu", "Khám phá cấu trúc", "Truy vấn", "Ánh xạ", "ViewModel", "Gắn vào thành phần", "Dữ liệu công khai"].every((t) => tabs.some((x) => x.startsWith(t))), tabs.join(" | "));
  await p.close();
}

// ---------- S1-027: no contradictory slot sentence in the Builder ----------
{
  const p = await design(); await p.getByTestId("data-panel").getByText("Nâng cao", { exact: true }).click(); await wait(800);
  const t = await p.getByTestId("data-advanced").innerText();
  check("S1-027: the Builder never says there is 'no operation to add a slot' next to the working slot form", !/chưa có thao tác nào để thêm khe/.test(t) && /Thêm khe/.test(t), t.replace(/\n/g, " ").slice(0, 200));
  check("S1-027: the empty slot list says what to do", /chưa có kết nối dữ liệu nào/i.test(t));
  await p.close();
}

// ---------- the guided form ----------
{
  const s = newDataState(); const p = await design(s);
  await p.getByTestId("data-add").click(); await wait(300);
  const form = p.getByTestId("guided-binding");
  const text = await form.innerText();
  check("form: four numbered sections in plain Vietnamese", ["1. Hiển thị ở đâu?", "2. Lấy dữ liệu từ đâu?", "3. Hiển thị cột nào?", "4. Ai xem được?"].every((t) => text.includes(t)));
  check("form: no internal vocabulary in the guided form (khe, ViewModel, ánh xạ, truy vấn, binding, slot)", !/\bkhe\b|ViewModel|ánh xạ|truy vấn|binding|\bslot\b/i.test(text), (text.match(/\bkhe\b|ViewModel|ánh xạ|truy vấn|binding|\bslot\b/gi) ?? []).join(","));
  check("form: columns section says the source's columns are used as returned (v1)", /Dùng nguyên các cột nguồn trả về/.test(text));
  check("form: focus is inside the form when it opens", await p.evaluate(() => !!document.activeElement?.closest("[data-testid=guided-binding]")));
  // validation first: nothing is sent
  await p.getByTestId("gb-save").click(); await wait(300);
  check("form: pressing Lưu on an empty form shows the problems next to their fields and sends NOTHING", (await form.getByRole("alert").count()) >= 3 && patches(p).length === 0);
  await p.getByTestId("gb-section").selectOption({ label: "Danh sách sản phẩm" });
  const propOptions = await p.getByTestId("gb-prop").locator("option").allInnerTexts();
  check("form: only the properties a published page can show are offered, with plain labels", propOptions.some((o) => /Danh sách/.test(o)) && !propOptions.some((o) => /^items/.test(o)), propOptions.join(" | "));
  await p.getByTestId("gb-prop").selectOption({ index: propOptions.findIndex((o) => /Danh sách/.test(o)) });
  const srcOptions = await p.getByTestId("gb-source").locator("option").allInnerTexts();
  check("form: workspace sources the person may view are offered by name", srcOptions.includes("Kho đơn hàng") && srcOptions.includes("Nguồn mới…"), srcOptions.join(" | "));
  await p.getByTestId("gb-source").selectOption({ label: "Kho đơn hàng" });
  await p.getByTestId("gb-dataset").fill("Sản phẩm bán chạy");
  await p.getByTestId("gb-operation").fill("SELECT * FROM x"); await p.getByTestId("gb-save").click(); await wait(300);
  check("form: an operation that is not an approved code is refused next to its field, nothing sent", (await p.getByTestId("gb-error:operationKey").count()) === 1 && patches(p).length === 0);
  await p.getByTestId("gb-operation").fill("products.list");
  const pub = p.getByTestId("gb-public");
  check("form: 'Khách chưa đăng nhập cũng xem được' is OFF by default", !(await pub.isChecked()));
  await pub.check();
  await p.getByTestId("gb-save").dblclick(); await wait(900);
  const ops = opsOf(p);
  check("form: Lưu sends ONE PATCH with ADD source, ADD dataset (READ, public), ADD binding in that order, existing operation types only", patches(p).length === 1 && ops.map((o) => o.type).join() === "ADD_DATA_SOURCE,ADD_QUERY,ADD_DATA_BINDING", JSON.stringify(ops.map((o) => o.type)));
  const [slot, query, binding] = ops.map((o) => o.definition);
  check("form: the hidden connection is {id,name,type} only (no sourceRef / URL / credential)", JSON.stringify(Object.keys(slot).sort()) === '["id","name","type"]' && slot.name === "Kho đơn hàng", JSON.stringify(slot));
  check("form: the dataset is READ + public with the approved operation", query.mode === "READ" && query.public === true && query.operationKey === "products.list" && query.dataSourceRef === slot.id, JSON.stringify(query));
  check("form: the binding is direct (queryRef), one property", binding.sectionId === "s-grid" && binding.prop === "items" && binding.queryRef === query.id && !("viewModelRef" in binding), JSON.stringify(binding));
  check("form: the summary says what was done in plain words", /Sản phẩm bán chạy/.test(patches(p)[0].body.summary), patches(p)[0].body.summary);
  await wait(500);
  check("form: after saving the form closes and the row says what shows where, from which data, and who sees it", (await p.getByTestId("guided-binding").count()) === 0 && /Danh sách sản phẩm › Danh sách/.test(await p.getByTestId("data-connected").innerText()) && /Khách xem được/.test(await p.getByTestId("data-connected").innerText()), await p.getByTestId("data-connected").innerText().catch(() => "(no list)"));
  check("form: the success message tells the one remaining step (link the real source)", /Nâng cao > Nguồn dữ liệu/.test(await p.getByTestId("data-message").innerText()));
  check("form: focus lands on the 'Đã kết nối' list (not <body>)", await p.evaluate(() => !!document.activeElement?.closest("[data-testid=data-connected]") || document.activeElement?.getAttribute("data-testid") === "data-connected"));
  // the same property cannot be bound twice
  await p.getByTestId("data-add").click(); await wait(200);
  await p.getByTestId("gb-section").selectOption({ label: "Danh sách sản phẩm" }); await p.getByTestId("gb-prop").selectOption({ index: propOptions.findIndex((o) => /Danh sách/.test(o)) });
  await p.getByTestId("gb-dataset").fill("Khác"); await p.getByTestId("gb-operation").fill("a.b"); await p.getByTestId("gb-save").click(); await wait(300);
  check("form: a property that already shows data is refused in plain words", /đã được gắn dữ liệu/.test(await p.getByTestId("guided-binding").innerText()) && patches(p).length === 1);
  await p.getByRole("button", { name: "Hủy" }).click(); await wait(200);
  check("form: Hủy closes the form and sends nothing", (await p.getByTestId("guided-binding").count()) === 0 && patches(p).length === 1);
  // public toggle + unlink
  await p.getByRole("button", { name: "Ngừng công khai" }).click(); await wait(600);
  check("row: 'Ngừng công khai' sends UPDATE_QUERY {public:false}", opsOf(p).slice(-1)[0].type === "UPDATE_QUERY" && opsOf(p).slice(-1)[0].definition.public === false);
  check("row: the badge now says it is not public", /Chưa công khai/.test(await p.getByTestId("data-connected").innerText()));
  await p.getByRole("button", { name: /Gỡ dữ liệu khỏi/ }).click(); await wait(300);
  check("unlink: asks first, naming the property, and says the dataset stays", /Danh sách/.test(await p.getByRole("dialog").innerText()) && /vẫn còn/.test(await p.getByRole("dialog").innerText()));
  await p.getByTestId("data-unlink-confirm").click(); await wait(700);
  check("unlink: ONE REMOVE_DATA_BINDING and the sentence goes away", opsOf(p).slice(-1)[0].type === "REMOVE_DATA_BINDING" && (await p.getByTestId("data-connected-empty").count()) === 1);
  check("no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}

// ---------- a source typed by hand (no workspace source), existing connection reused ----------
{
  const s = newDataState(); s.sources = []; const p = await design(s);
  await p.getByTestId("data-add").click(); await wait(300);
  await p.getByTestId("gb-section").selectOption({ label: "Danh sách sản phẩm" }); await p.getByTestId("gb-prop").selectOption({ index: 1 });
  check("new source: with no workspace source the form asks for a name and a type (the connection is created for the person)", (await p.getByTestId("gb-source-name").count()) === 1 && (await p.getByTestId("gb-source-type").count()) === 1);
  await p.getByTestId("gb-source-name").fill("Kho hàng"); await p.getByTestId("gb-dataset").fill("Hàng"); await p.getByTestId("gb-operation").fill("hang.list");
  await p.getByTestId("gb-save").click(); await wait(800);
  check("new source: private by default -> the dataset has no `public` and the row says 'Chưa công khai'", !("public" in opsOf(p)[1].definition) && /Chưa công khai/.test(await p.getByTestId("data-connected").innerText()));
  await p.close();
}

// ---------- permissions ----------
{
  const s = newDataState(); s.project = { ...s.project, permissions: ["APP_VIEW", "DATA_SOURCE_VIEW"] }; s.projects = [s.project];
  const p = await design(s);
  check("read-only: no add button, no form, a plain sentence instead", (await p.getByTestId("data-add").count()) === 0 && (await p.getByTestId("data-readonly").count()) === 1);
  await p.close();
}

// ---------- Inspector entry ----------
{
  const s = newDataState(); const p = await open(b, "/studio/projects/p1/design", { state: s }); await p.waitForSelector("iframe"); await wait(900);
  await p.locator("[role=treeitem][aria-level='2']").filter({ hasText: "Danh sách sản phẩm" }).first().click(); await wait(300);
  await p.locator(".bx-inspector").getByRole("tab", { name: /Dữ liệu/ }).click(); await wait(300);
  const t = await p.locator(".bx-tabpanel").innerText();
  check("Inspector: the property is named in Vietnamese (not the raw key) and says it has no data yet", /Danh sách/.test(t) && !/^items/m.test(t) && /Chưa gắn dữ liệu/.test(t), t.replace(/\n/g, " | "));
  await p.locator(".bx-inspector").getByRole("button", { name: /Hiển thị dữ liệu… cho Danh sách/ }).click(); await wait(500);
  check("Inspector: 'Hiển thị dữ liệu…' opens the guided form with the component and property already chosen", (await p.getByTestId("guided-binding").count()) === 1 && (await p.getByTestId("gb-section").inputValue()) === "s-grid" && (await p.getByTestId("gb-prop").inputValue()) === "items");
  await p.close();
}
await b.close(); finish();
