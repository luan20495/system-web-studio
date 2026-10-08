// @class: harness — real Chromium on the Builder component harness (no backend). Light sanity, NOT a performance project: opening/closing panels many times must not leak DOM nodes, listeners or JS heap,
// must not produce console errors/warnings (React warnings included) and must not make any network request after the page has loaded.
// Run: node tests/browser/build-harness.mjs && (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &) && CHROME=... node tests/browser/sanity.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const URL_ = process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html";
const ROUNDS = Number(process.env.SANITY_ROUNDS ?? 40), WARMUP = 10;
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };

const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome", args: ["--enable-precise-memory-info"] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(5000);                                              // a missing control must fail fast, not wait 30 s per click
const console_ = [], errors = [], requestsAfterLoad = []; let loaded = false;
page.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon/.test(`${m.text()} ${m.location().url}`)) console_.push(`${m.type()}: ${m.text().slice(0, 120)} @ ${m.location().url}`); });
page.on("pageerror", (e) => errors.push(e.message));
page.on("request", (r) => { if (loaded) requestsAfterLoad.push(`${r.method()} ${r.url()}`); });
await page.goto(URL_); await page.waitForSelector("iframe"); await page.waitForTimeout(1000); loaded = true;
const cdp = await page.context().newCDPSession(page);
await cdp.send("Memory.enable").catch(() => undefined);
async function counters() {
  // Chrome's jsEventListeners counter jumps between a few values (a 26-listener quantum: 200 / 226 / 252) even on an idle page, with the DOM node count and the heap unchanged. A single reading is therefore noise
  // (measured: the same build fails the ≤ 10 % check 2 runs out of 5). Take the MEDIAN of 5 readings, each after a forced GC: a real leak still shows (it grows every round), the quantum jitter does not.
  const reads = [];
  for (let k = 0; k < 5; k++) { await cdp.send("HeapProfiler.collectGarbage").catch(() => undefined); await page.waitForTimeout(150); reads.push(await cdp.send("Memory.getDOMCounters")); }   // nodes, jsEventListeners, documents
  const med = (key) => reads.map((r) => r[key]).sort((a, b) => a - b)[2];
  const heap = await page.evaluate(() => performance.memory?.usedJSHeapSize ?? 0);
  return { nodes: med("nodes"), listeners: med("jsEventListeners"), documents: med("documents"), heap };
}

const RAIL = ["Trang", "Thành phần", "Dữ liệu", "Biểu mẫu", "Hành động", "Workflow", "Giao diện", "AI"];
async function oneRound() {
  for (const t of RAIL) { await page.locator(".bx-left").getByRole("tab", { name: t }).click(); }
  await page.frameLocator("iframe").locator("section").first().click({ position: { x: 30, y: 30 } }).catch(() => undefined);
  for (const t of [/^Nội dung/, /^Thiết kế/, /^Dữ liệu/, /^Hành động/, /^Quyền/, /^Nâng cao/]) await page.locator(".bx-right").getByRole("tab", { name: t }).first().click().catch(() => undefined);
  // "Dùng thử" / "Chỉnh sửa" is the mode switch: open the Test panel, then go back to editing
  await page.getByRole("button", { name: "Dùng thử" }).click(); await page.locator('[data-testid="test-panel"]').waitFor();   // NOT waitForSelector: it returns an ElementHandle that the DevTools protocol keeps alive and would look like a DOM leak
  await page.getByRole("button", { name: "Chỉnh sửa" }).click();
  await page.keyboard.press("Escape");
}
const samples = [];
const trend = [];
for (let i = 1; i <= ROUNDS; i++) { await oneRound(); if (i === WARMUP || i === ROUNDS) samples.push(await counters()); else if (i % 5 === 0) trend.push(`${i}:${(await counters()).nodes}`); }
console.log(`DOM node trend (round:nodes) ${trend.join(" ")}`);
const [a, b] = samples;
const grow = (x, y) => (y - x) / Math.max(1, x);
check(`${ROUNDS} rounds of rail/inspector/Test-panel switching completed`, samples.length === 2, JSON.stringify(b));
check("DOM nodes do not grow (≤ 10% between round 10 and the last)", grow(a.nodes, b.nodes) <= 0.10, `${a.nodes} → ${b.nodes}`);
check("JS event listeners do not grow (≤ 10%)", grow(a.listeners, b.listeners) <= 0.10, `${a.listeners} → ${b.listeners}`);
check("documents (iframes) do not accumulate", b.documents <= a.documents + 1, `${a.documents} → ${b.documents}`);
check("JS heap after GC does not grow by more than 30% and 15 MB", b.heap === 0 || (grow(a.heap, b.heap) <= 0.30 && b.heap - a.heap <= 15 * 1024 * 1024), `${(a.heap / 1048576).toFixed(1)} MB → ${(b.heap / 1048576).toFixed(1)} MB`);
check("no console errors or warnings (React warnings included)", console_.length === 0, console_.slice(0, 3).join(" | "));
check("no uncaught exception / unhandled rejection", errors.length === 0, errors.slice(0, 3).join(" | "));
check("no network request after the page loaded (nothing polls, nothing storms)", requestsAfterLoad.length === 0, requestsAfterLoad.slice(0, 3).join(" | "));
await browser.close();
const bad = results.filter((r) => !r.ok).length; console.log(`\n${results.length - bad}/${results.length} passed`); process.exit(bad ? 1 : 0);
