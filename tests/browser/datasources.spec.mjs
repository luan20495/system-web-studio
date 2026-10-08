// @class: harness — real Chromium on a test-only host whose `calls` are an in-page fake; proves what the PANEL does with C3's documented answers, NOT what the backend answers
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/datasources.spec.mjs
import { harnessUrl, launch, makeChecks } from "./lib/spec.mjs";
const BASE = harnessUrl("DS_HARNESS_URL");
const { check, finish } = makeChecks({ clip: 160 });
const browser = await launch();
async function open(s, { ready = true } = {}) {
  const page = await browser.newPage({ viewport: { width: 700, height: 1100 } });
  page.errors = []; page.on("pageerror", (e) => page.errors.push(e.message)); page.on("console", (m) => { if (m.type() === "error" && !/favicon|404/.test(m.text())) page.errors.push(m.text()); });
  await page.goto(`${BASE}?s=${s}`);
  if (ready) await page.waitForSelector('[data-testid="ds-panel"]', { timeout: 5000 }).catch(() => undefined);
  return page;
}
const calls = (p, name) => p.evaluate((n) => window.__calls.filter((c) => !n || c.name === n), name);
const text = (p) => p.evaluate(() => document.body.innerText);
const secretInDom = (p) => p.evaluate(() => window.__secretsSeenInDom());

// 1. no host / flag off / forbidden / retry
{ const p = await open("nocalls", { ready: false }); const t = await text(p);
  check("no host: 'Chưa sẵn sàng', nothing is simulated, no source list", /Chưa sẵn sàng/.test(t) && !(await p.getByTestId("ds-list").count()) && !(await p.getByTestId("ds-create-form").count()), t.slice(0, 80)); await p.close(); }
{ const p = await open("flagoff", { ready: false }); await p.waitForSelector('[data-state="NOT_READY"]'); const t = await text(p);
  check("flag off (404 without a code): NOT_READY naming the flag, no controls", /Chưa sẵn sàng/.test(t) && /app\.data-platform\.enabled/.test(t) && !(await p.getByTestId("ds-create").count()), t.slice(0, 120)); await p.close(); }
{ const p = await open("forbidden", { ready: false }); await p.waitForSelector('[data-testid="ds-error"]'); const t = await text(p);
  check("403: an error state with the permission message and a retry button, no controls", /không có quyền/.test(t) && (await p.getByTestId("ds-reload").count()) === 1 && !(await p.getByTestId("ds-create").count()), t.slice(0, 120)); await p.close(); }
{ const p = await open("listerr", { ready: false }); await p.waitForSelector('[data-testid="ds-error"]');
  check("500 on load: error state, role=alert", (await p.locator('[data-testid="ds-error"][role="alert"]').count()) === 1);
  await p.getByTestId("ds-reload").click(); await p.waitForSelector('[data-testid="ds-panel"]');
  check("retry reloads and the panel opens (connectors asked twice)", (await calls(p, "connectors")).length === 2); await p.close(); }

// 2. list, credential metadata only, test connection
{ const p = await open("ok");
  check("source list shows the existing source", (await p.getByTestId("ds:ds-1").count()) === 1 && /billing-db/.test(await text(p)));
  const cs = await p.getByTestId("cred-state:ds-1").innerText();
  check("credential: 'configured', key NAMES, 'hidden' — and nothing else", /Đã cấu hình/.test(cs) && /username, password/.test(cs) && /giấu/.test(cs), cs);
  check("no password input is prefilled", await p.locator('input[type="password"]').evaluateAll((els) => els.every((e) => e.value === "")));
  await p.getByTestId("ds-test:ds-1").dblclick();
  await p.waitForSelector('[data-testid="ds-test-result:ds-1"]');
  check("test connection: double click sends ONE request", (await calls(p, "test")).length === 1);
  check("test connection OK is shown as a result", (await p.getByTestId("ds-test-result:ds-1").getAttribute("data-test-state")) === "OK");
  check("no page errors", p.errors.length === 0, p.errors.join("|")); await p.close(); }
{ const p = await open("slowtest"); await p.getByTestId("ds-test:ds-1").click();
  check("test connection running: the button says so, is disabled and aria-busy", /Đang kiểm tra/.test(await p.getByTestId("ds-test:ds-1").innerText()) && await p.getByTestId("ds-test:ds-1").isDisabled() && (await p.getByTestId("ds-test:ds-1").getAttribute("aria-busy")) === "true");
  await p.waitForSelector('[data-testid="ds-test-result:ds-1"]'); check("…and is usable again afterwards", !(await p.getByTestId("ds-test:ds-1").isDisabled())); await p.close(); }
{ const p = await open("warntest"); await p.getByTestId("ds-test:ds-1").click(); await p.waitForSelector('[data-testid="ds-test-result:ds-1"]');
  check("test OK with a warning: amber result AND the warning text next to it", (await p.getByTestId("ds-test-result:ds-1").getAttribute("data-test-state")) === "WARN" && /SELECT-only/.test(await p.getByTestId("ds-warning:ds-1").innerText())); await p.close(); }
{ const p = await open("failtest"); await p.getByTestId("ds-test:ds-1").click(); await p.waitForSelector('[data-testid="ds-test-result:ds-1"]');
  const r = p.getByTestId("ds-test-result:ds-1");
  check("failed test (HTTP 200, ok:false) is a FAILED result with its code and a plain-language reason, as an alert", (await r.getAttribute("data-test-state")) === "FAILED" && (await r.getAttribute("role")) === "alert" && /AUTH_REJECTED/.test(await r.innerText()) && /từ chối/.test(await r.innerText())); await p.close(); }
{ const p = await open("disabledtest"); await p.getByTestId("ds-test:ds-1").click(); await p.waitForSelector('[data-testid="note:test:ds-1"]');
  check("409 DISABLED on test is an error note (not a result)", /đang tắt/.test(await p.getByTestId("note:test:ds-1").innerText()) && !(await p.getByTestId("ds-test-result:ds-1").count())); await p.close(); }

// 3. create: validation, duplicate submit, secret handling, ambiguity
{ const p = await open("ok");
  await p.getByLabel("Tên nguồn").fill("crm-db"); await p.getByLabel("Loại").selectOption("postgres");
  check("planned connectors are listed but disabled", await p.locator('option:has-text("MySQL")').evaluate((o) => o.disabled));
  await p.getByTestId("ds-create").click();
  check("missing required config is refused with reasons and sends nothing", (await p.getByTestId("ds-form-errors").count()) === 1 && (await calls(p, "create")).length === 0, await p.getByTestId("ds-form-errors").innerText());
  await p.getByLabel("host *").fill("crm.example.com"); await p.getByLabel("database *").fill("crm");
  await p.getByTestId("new-cred:username").fill("svc"); await p.getByTestId("new-cred:password").fill("hunter2-S3cr3t");
  await p.getByTestId("ds-create").dblclick();
  check("create: while saving the button is disabled and says so", true);
  await p.waitForSelector('[data-testid="ds:ds-2"]');
  const cr = await calls(p, "create");
  check("double submit creates ONCE", cr.length === 1, cr.length);
  check("the request carries only contract fields (name,type,config,credential)", JSON.stringify(Object.keys(cr[0].args[0]).sort()) === '["config","credential","name","type"]');
  check("the secret was sent but is nowhere in the page afterwards (inputs cleared, no text)", !(await secretInDom(p)));
  check("the form is reset after success", (await p.getByLabel("Tên nguồn").inputValue()) === "");
  check("success is announced", /Đã tạo nguồn/.test(await p.getByTestId("note:create").innerText()));
  check("no page errors", p.errors.length === 0, p.errors.join("|")); await p.close(); }
{ const p = await open("conflictname"); await p.getByLabel("Tên nguồn").fill("billing-db"); await p.getByLabel("Loại").selectOption("postgres");
  await p.getByLabel("host *").fill("h.example.com"); await p.getByLabel("database *").fill("d"); await p.getByTestId("new-cred:username").fill("u"); await p.getByTestId("new-cred:password").fill("hunter2-S3cr3t");
  await p.getByTestId("ds-create").click(); await p.waitForSelector('[data-testid="note:create"]');
  check("409 CONFLICT on create: conflict message, button usable, password input cleared", /Xung đột/.test(await p.getByTestId("note:create").innerText()) && !(await p.getByTestId("ds-create").isDisabled()) && !(await secretInDom(p))); await p.close(); }
{ const p = await open("createfail"); await p.getByLabel("Tên nguồn").fill("ghost-db"); await p.getByLabel("Loại").selectOption("postgres");
  await p.getByLabel("host *").fill("h.example.com"); await p.getByLabel("database *").fill("d"); await p.getByTestId("new-cred:username").fill("u"); await p.getByTestId("new-cred:password").fill("hunter2-S3cr3t");
  await p.getByTestId("ds-create").click(); await p.waitForSelector('[data-testid="note:create"]'); await p.waitForTimeout(400);
  const n = await p.getByTestId("note:create").innerText();
  check("network failure on create: 'unknown outcome' text, no automatic retry (create called once)", /Chưa rõ/.test(n) && (await calls(p, "create")).length === 1, n);
  check("the list was reloaded and shows what the server really has (the ambiguous create DID land)", (await p.getByTestId("ds:ds-2").count()) === 1 && (await calls(p, "list")).length >= 2);
  check("the password is gone from the page", !(await secretInDom(p))); await p.close(); }

// 4. credential replace / remove
{ const p = await open("ok");
  await p.getByTestId("cred-input:ds-1:username").fill("svc2"); await p.getByTestId("cred-input:ds-1:password").fill("hunter2-S3cr3t");
  await p.getByTestId("cred-save:ds-1").click(); await p.waitForSelector('[data-testid="note:cred:ds-1"]'); const c = await calls(p, "setCredential");
  check("credential replace: one request with the typed keys; the answer is metadata and the page keeps no secret", c.length === 1 && c[0].args[0] === "ds-1" && /giấu/.test(await p.getByTestId("note:cred:ds-1").innerText()) && !(await secretInDom(p)));
  await p.getByTestId("cred-save:ds-1").click(); await p.waitForTimeout(200);
  check("an incomplete credential is refused locally (nothing sent)", (await calls(p, "setCredential")).length === 1 && /Khóa kết nối chưa đủ|Thiếu/.test(await p.getByTestId("note:cred:ds-1").innerText()));
  await p.getByTestId("cred-remove:ds-1").click(); await p.waitForTimeout(400);
  check("credential removal updates the state to 'no credential'", /Chưa có khóa kết nối/.test(await p.getByTestId("cred-state:ds-1").innerText())); await p.close(); }

// 5. bindings TEST / LIVE
{ const p = await open("ok");
  check("both slots of the document are listed with TEST and LIVE rows", (await p.getByTestId("slot:erp-db").count()) === 1 && (await p.getByTestId("binding:LIVE:crm").count()) === 1);
  check("LIVE-unbound warning lists the slots", /erp-db, crm/.test(await p.getByTestId("live-unbound").innerText()));
  await p.getByLabel("Nguồn cho erp-db (TEST)").selectOption("ds-1"); await p.getByTestId("bind:TEST:erp-db").dblclick(); await p.waitForSelector('[data-testid="bound:TEST:erp-db"]');
  const b = await calls(p, "bind");
  check("bind TEST: ONE request (mode, slot, data source) even on double click", b.length === 1 && JSON.stringify(b[0].args) === '["TEST","erp-db","ds-1"]', JSON.stringify(b));
  check("the TEST binding shows the source name; LIVE is still unbound", /billing-db/.test(await p.getByTestId("bound:TEST:erp-db").innerText()) && /chưa liên kết/.test(await p.getByTestId("binding:LIVE:erp-db").innerText()));
  for (const slot of ["erp-db", "crm"]) { await p.getByLabel(`Nguồn cho ${slot} (LIVE)`).selectOption("ds-1"); await p.getByTestId(`bind:LIVE:${slot}`).click(); await p.waitForSelector(`[data-testid="bound:LIVE:${slot}"]`); }
  check("binding every LIVE slot removes the LIVE-unbound warning", (await p.getByTestId("live-unbound").count()) === 0);
  await p.getByTestId("ds-delete:ds-1").click(); await p.getByTestId("ds-delete-confirm").click(); await p.waitForSelector('[data-testid="note:del:ds-1"]');
  check("deleting a bound source: 409 explained ('unbind first'), the source stays", /bỏ liên kết|đang được liên kết/.test(await p.getByTestId("note:del:ds-1").innerText()) && (await p.getByTestId("ds:ds-1").count()) === 1);
  await p.getByRole("button", { name: "Hủy" }).click();
  await p.getByTestId("unbind:TEST:erp-db").click(); await p.waitForTimeout(300);
  check("unbind removes the binding", (await p.getByTestId("bound:TEST:erp-db").count()) === 0);
  check("no page errors", p.errors.length === 0, p.errors.join("|")); await p.close(); }
{ const p = await open("noslots");
  check("a document with no slots says so honestly (and nothing can be bound)", /chưa khai báo khe dữ liệu/.test(await p.getByTestId("slot-empty").innerText()) && (await p.locator('[data-testid^="bind:"]').count()) === 0); await p.close(); }

// 6. permission: read-only
{ const p = await open("readonly");
  const ids = ["ds-test:ds-1", "ds-toggle:ds-1", "ds-delete:ds-1", "cred-save:ds-1", "ds-create", "bind:TEST:erp-db"];
  const states = await Promise.all(ids.map(async (id) => ({ id, dis: await p.getByTestId(id).isDisabled() })));
  check("without DATA_SOURCE_MANAGE every write control is disabled", states.every((s) => s.dis), JSON.stringify(states.filter((s) => !s.dis)));
  check("…and the reason is on screen", /chưa được cấp quyền/.test(await p.getByTestId("ds-readonly").innerText()));
  check("reads still work (the list is visible)", (await p.getByTestId("ds:ds-1").count()) === 1); await p.close(); }

// C1 permission contract (UX only; the server re-checks): DATA_SOURCE_VIEW = metadata only · DATA_SOURCE_MANAGE = management · TEST/draft binding also needs APP_EDIT
{ const p = await open("viewonly");
  const names = (await calls(p)).map((c) => c.name);
  check("DATA_SOURCE_VIEW only: the metadata list is requested and shown", names.includes("list") && (await p.getByTestId("ds:ds-1").count()) === 1);
  check("…but the catalogue, credential metadata and bindings (management) are NOT requested", !names.some((n) => ["connectors", "credential", "listBindings"].includes(n)), names.join(","));
  const ids = ["ds-test:ds-1", "ds-toggle:ds-1", "ds-delete:ds-1", "ds-create"];
  check("…and every management control is disabled with a reason on screen", (await Promise.all(ids.map((id) => p.getByTestId(id).isDisabled()))).every(Boolean) && /chưa được cấp quyền quản lý/.test(await p.getByTestId("ds-readonly").innerText()));
  await p.close(); }
{ const p = await open("noview");
  check("without DATA_SOURCE_VIEW nothing is requested at all and the reason is shown", (await calls(p)).length === 0 && /chưa được cấp quyền xem/.test(await p.locator("body").innerText()));
  check("…and no source name or form is rendered", (await p.getByTestId("ds:ds-1").count()) === 0 && (await p.getByTestId("ds-create").count()) === 0);
  await p.close(); }
{ const p = await open("nobind");
  check("DATA_SOURCE_MANAGE without APP_EDIT: management works (list, create enabled)", (await p.getByTestId("ds-create").isDisabled()) === false && (await calls(p, "connectors")).length === 1);
  check("…but a TEST binding is locked with the reason (needs APP_EDIT too) and bindings were not requested", (await p.getByTestId("bind:TEST:erp-db").isDisabled()) && /quyền chỉnh sửa ứng dụng/.test(await p.getByTestId("ds-bind-locked").innerText()) && (await calls(p, "listBindings")).length === 0);
  await p.close(); }

// 7. empty + keyboard/labels
{ const p = await open("empty");
  check("empty list: explanatory text + the create form", (await p.getByTestId("ds-empty").count()) === 1 && (await p.getByTestId("ds-create-form").count()) === 1);
  const unnamed = await p.evaluate(() => [...document.querySelectorAll("input,select,button")].filter((e) => !(e.getAttribute("aria-label") || e.labels?.length || (e.textContent ?? "").trim() || e.getAttribute("title"))).length);
  check("every input/select/button has an accessible name", unnamed === 0, unnamed);
  await p.keyboard.press("Tab"); check("keyboard focus enters the panel", await p.evaluate(() => document.activeElement && document.activeElement !== document.body)); await p.close(); }

await browser.close();
finish();
