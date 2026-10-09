// @class: tooling (S4 audit). HARNESS, NOT REAL BACKEND. Repeatable performance measurements of the REAL screens (OrganizationView, EmployeesView, BuilderWorkspace) mounted by the test harnesses in tests/browser
// with in-page FAKE data and GENERATED fixtures. Numbers are this machine's render / interaction cost in headless Chrome; they say nothing about a backend, a network or production Core Web Vitals.
//
//   HARNESS_NODE_ENV=production HARNESS_PROFILING=1 node tests/browser/build-harness.mjs        # -> .test-build/browser-prof (minified production React on react-dom/profiling: real <Profiler> durations)
//   node scripts/perf-harness.mjs [--runs 5] [--cpu 1|4] [--dir .test-build/browser-prof] [--only org,emp,builder,attrib,scroll,memory] [--json out.json]
//
// Process safety: the static server and Chrome are started and stopped ONLY through tests/lib/owned-process.mjs (scripts/perf-env.mjs): free ports, identity recorded, stopped by identity in `finally`.
// Method: every action is timed in-page (performance.now) from just before the DOM click / input event to the SECOND requestAnimationFrame after it (= the frame that shows the result). Real pointer / key input is
// also timed with the Event Timing API (duration = input -> next paint, the INP basis). Each scenario runs --runs times in a fresh page; the tables show median / p95 / max over all samples.
import { writeFileSync } from "node:fs";
import { withEnv } from "./perf-env.mjs";

const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : d; };
const RUNS = Number(opt("runs", "5")), CPU = Number(opt("cpu", "1")), DIR = opt("dir", ".test-build/browser-prof"), ONLY = new Set(opt("only", "org,emp,builder,attrib,scroll,memory").split(",")), JSON_OUT = opt("json", null);
const ROUNDS = Number(opt("rounds", "30"));

const INIT = () => {
  window.__raf2 = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  window.__t = async (fn) => { const t = performance.now(); await fn(); window.__sync = performance.now() - t; await window.__raf2(); return performance.now() - t; };   // __sync = main-thread time of the action itself (React flushes a discrete event synchronously)
  window.__evt = []; window.__long = []; window.__cls = 0; window.__lcp = 0;
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__evt.push({ n: e.name, d: e.duration, wait: e.processingStart - e.startTime, proc: e.processingEnd - e.processingStart }); }).observe({ type: "event", durationThreshold: 16, buffered: true }); } catch { /* unsupported */ }
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push({ d: e.duration, at: e.startTime }); }).observe({ type: "longtask", buffered: true }); } catch { /* unsupported */ }
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__cls += e.value; }).observe({ type: "layout-shift", buffered: true }); } catch { /* unsupported */ }
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lcp = e.startTime; }).observe({ type: "largest-contentful-paint", buffered: true }); } catch { /* unsupported */ }
};

const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)] : NaN; };
const stat = (a) => ({ n: a.length, med: pct(a, 50), p95: pct(a, 95), max: Math.max(...a) });
const f = (x, d = 1) => (Number.isFinite(x) ? x.toFixed(d) : "n/a");
const results = []; // { group, name, unit, samples[] , note }
const add = (group, name, samples, unit = "ms", note = "") => { const s = samples.filter(Number.isFinite); results.push({ group, name, unit, ...stat(s), note }); };

async function open(ctx, url) {
  const page = await ctx.newPage(); page.setDefaultTimeout(20000);
  // the 'Blocked script execution in about:srcdoc' message is an artifact of THIS script: addInitScript is also injected into the preview iframe, which is sandboxed without allow-scripts in Test mode (not seen without the init script)
  const errs = []; page.on("pageerror", (e) => errs.push(e.message)); page.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404|Blocked script execution in 'about:srcdoc'/.test(m.text())) errs.push(m.text().slice(0, 120)); });
  await page.addInitScript(INIT);
  const cdp = await ctx.newCDPSession(page); await cdp.send("Memory.enable").catch(() => undefined);
  if (CPU > 1) await cdp.send("Emulation.setCPUThrottlingRate", { rate: CPU });
  await page.goto(url); page.__errs = errs; page.__cdp = cdp; return page;
}
let lastSync = NaN;
const T = async (page, expr, arg) => { const [total, sync] = await page.evaluate(async ([src, a]) => [await window.__t(() => new Function("a", src)(a)), window.__sync], [expr, arg]); lastSync = sync; return total; };   // timed DOM action; total = to the 2nd frame, lastSync = main-thread part
const click = (page, id) => T(page, 'document.querySelector(`[data-testid="${a}"]`).click()', id);
const prof = (page) => page.evaluate(() => window.__prof ?? []);
// the commit that did the work: the update with the largest actualDuration since the Profiler buffer was cleared
const lastUpdate = (p) => { const u = p.filter((x) => x.phase !== "mount"); const m = u.reduce((m, x) => (x.actual > m.actual ? x : m), { actual: -1, base: NaN }); return m.actual < 0 ? { actual: NaN, base: NaN } : m; };
async function counters(page) {
  const reads = []; for (let k = 0; k < 3; k++) { await page.__cdp.send("HeapProfiler.collectGarbage").catch(() => undefined); await page.waitForTimeout(120); reads.push(await page.__cdp.send("Memory.getDOMCounters")); }
  const med = (key) => reads.map((r) => r[key]).sort((a, b) => a - b)[1];
  return { nodes: med("nodes"), listeners: med("jsEventListeners"), docs: med("documents"), heapMB: (await page.evaluate(() => performance.memory?.usedJSHeapSize ?? 0)) / 1048576 };
}
async function loadInfo(page) {
  return page.evaluate(() => { const nav = performance.getEntriesByType("navigation")[0]; const fcp = performance.getEntriesByType("paint").find((e) => e.name === "first-contentful-paint");
    return { dcl: nav.domContentLoadedEventEnd, load: nav.loadEventEnd, fcp: fcp?.startTime ?? NaN, longSum: window.__long.reduce((a, x) => a + x.d, 0), longMax: Math.max(0, ...window.__long.map((x) => x.d)), longN: window.__long.length, cls: window.__cls, nodes: document.querySelectorAll("*").length }; });
}
const evtMax = async (page) => (await page.evaluate(() => { const e = window.__evt; const r = e.length ? Math.max(...e.map((x) => x.d)) : 0; window.__evt = []; return r; }));

// ============================================================================================ ORGANIZATION: 2 000 units
async function orgBig(ctx, base) {
  const S = { load: [], fcp: [], first: [], longSum: [], expandSync: [], selSync: [], expandAll: [], expandNodes: [], select: [], selActual: [], selBase: [], chev: [], collapseAll: [], realClick: [], key: [], rows: [] };
  for (let r = 0; r < RUNS; r++) {
    const p = await open(ctx, `${base}/org.html?v=org&s=big`); await p.getByTestId("org-tree").waitFor();
    const li = await loadInfo(p); S.load.push(li.load); S.fcp.push(li.fcp); S.longSum.push(li.longSum); S.first.push(Math.max(0, ...(await prof(p)).map((x) => x.actual)));
    S.expandAll.push(await click(p, "org-expand-all")); S.expandSync.push(lastSync); const n = await p.evaluate(() => ({ rows: document.querySelectorAll('[role=treeitem]').length, nodes: document.querySelectorAll("*").length })); S.rows.push(n.rows); S.expandNodes.push(n.nodes);
    for (const id of [100, 300, 500, 700, 900, 1100, 1300, 1500, 1700, 1900]) {
      await p.evaluate(() => { window.__prof.length = 0; }); S.select.push(await click(p, `node:g${id}`)); S.selSync.push(lastSync); const u = lastUpdate(await prof(p)); S.selActual.push(u.actual); S.selBase.push(u.base);
    }
    for (let i = 0; i < 4; i++) S.chev.push(await T(p, 'document.querySelector(\'[data-testid="node:g3"] .xp-chev\').click()'));
    await p.evaluate(() => { window.__evt = []; });
    for (const id of [200, 1000, 1800]) { await p.getByTestId(`node:g${id}`).scrollIntoViewIfNeeded(); await p.getByTestId(`node:g${id}`).click(); await p.waitForTimeout(80); S.realClick.push(await evtMax(p)); }
    await p.getByTestId("node:g0").focus(); await p.evaluate(() => { window.__evt = []; }); for (let i = 0; i < 20; i++) await p.keyboard.press("ArrowDown"); await p.waitForTimeout(200);
    S.key.push(...(await p.evaluate(() => window.__evt.filter((e) => e.n === "keydown").map((e) => e.d))));
    S.collapseAll.push(await click(p, "org-collapse-all"));
    if (p.__errs.length) console.error("org page errors:", p.__errs.slice(0, 2)); await p.close();
  }
  const g = "ORG 2 000 units (s=big)";
  add(g, "load event (navigation, bundle exec incl.)", S.load); add(g, "first React commit (Profiler actual)", S.first); add(g, "long-task time during load (sum)", S.longSum);
  add(g, "expand all -> 2 000 treeitems painted", S.expandAll, "ms", `rows=${S.rows[0]}, DOM nodes after=${S.expandNodes[0]}`); add(g, "  expand all: main-thread time of the click (sync React commit)", S.expandSync); add(g, "select 1 row of 2 000 -> painted", S.select); add(g, "  select: main-thread time of the click (sync React commit)", S.selSync);
  add(g, "  select: Profiler actualDuration of the update", S.selActual, "ms", "profiling bundle"); add(g, "  select: Profiler baseDuration (cost if everything re-rendered)", S.selBase, "ms", "profiling bundle");
  add(g, "toggle one node (chevron) in the expanded tree -> painted", S.chev); add(g, "REAL pointer click on a row: Event Timing duration (input->paint)", S.realClick); add(g, "REAL ArrowDown keydown: Event Timing duration", S.key);
  add(g, "collapse all -> painted", S.collapseAll);
}

// ============================================================================================ EMPLOYEES: 10 000
async function empBig(ctx, base) {
  const S = { loadBig: [], firstBig: [], keyBig: [], selOptBig: [], optionsBig: [], load: [], first: [], keyDir: [], pageDir: [], statusDir: [], reqs: [], keyMem: [], pageMem: [], statusMem: [], fetchesMem: [], realClick: [] };
  const type = (p, text) => p.evaluate(async (t) => { const el = document.querySelector('[data-testid="emp-search"]'); const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set; const out = []; let cur = ""; for (const c of t) { cur += c; const s = performance.now(); set.call(el, cur); el.dispatchEvent(new Event("input", { bubbles: true })); await window.__raf2(); out.push(performance.now() - s); } return out; }, text);
  for (let r = 0; r < RUNS; r++) {
    let p = await open(ctx, `${base}/org.html?v=emp&s=emp-10k`); await p.getByTestId("emp-table").waitFor(); S.load.push((await loadInfo(p)).load); S.first.push(Math.max(0, ...(await prof(p)).map((x) => x.actual)));
    const before = (await p.evaluate(() => window.__org)).filter((c) => c.name === "listEmployees").length;
    S.keyDir.push(...(await type(p, "nhan vien 99"))); await p.waitForTimeout(700); S.reqs.push((await p.evaluate(() => window.__org)).filter((c) => c.name === "listEmployees").length - before);
    for (let i = 0; i < 3; i++) { S.pageDir.push(await click(p, "emp-next")); await p.waitForTimeout(150); }
    S.statusDir.push(await T(p, 'const s=document.querySelector(\'[data-testid="emp-status"]\'); s.value="INACTIVE"; s.dispatchEvent(new Event("change",{bubbles:true}))')); await p.waitForTimeout(200);
    await p.getByTestId("emp-search").fill(""); await p.waitForTimeout(500); await p.getByTestId("emp-status").selectOption("ALL"); await p.waitForTimeout(400);
    await p.evaluate(() => { window.__evt = []; }); await p.locator('[data-testid^="emp:"]').nth(3).click(); await p.waitForTimeout(100); S.realClick.push(await evtMax(p)); await p.close();
    p = await open(ctx, `${base}/org.html?v=emp&s=emp-10k-members`); await p.getByTestId("emp-table").waitFor();
    S.keyMem.push(...(await type(p, "nhan vien 9"))); await p.waitForTimeout(600);
    for (let i = 0; i < 3; i++) { S.pageMem.push(await click(p, "emp-next")); await p.waitForTimeout(120); }
    S.statusMem.push(await T(p, 'const s=document.querySelector(\'[data-testid="emp-status"]\'); s.value="INACTIVE"; s.dispatchEvent(new Event("change",{bubbles:true}))')); await p.waitForTimeout(200);
    S.fetchesMem.push((await p.evaluate(() => window.__org)).filter((c) => c.name === "tenantMembers").length); await p.close();
    p = await open(ctx, `${base}/org.html?v=emp&s=emp-10k-bigorg`); await p.getByTestId("emp-table").waitFor(); S.loadBig.push((await loadInfo(p)).load); S.firstBig.push(Math.max(0, ...(await prof(p)).map((x) => x.actual)));
    S.optionsBig.push(await p.evaluate(() => document.querySelectorAll('[data-testid="emp-org"] option').length)); await p.evaluate(() => { window.__prof.length = 0; });
    S.keyBig.push(...(await type(p, "nhan vien 99"))); await p.waitForTimeout(700); const pr = (await prof(p)).filter((x) => x.phase !== "mount"); S.selOptBig.push(...pr.map((x) => x.actual)); await p.close();
  }
  const g = "EMPLOYEES 10 000 (generated)";
  add(g, "directory (server-paged fake): load event", S.load); add(g, "directory: first React commit (Profiler actual)", S.first);
  add(g, "directory: keystroke -> frame (12 keys of a search)", S.keyDir); add(g, "directory: search requests for 12 keystrokes", S.reqs, "count"); add(g, "directory: next page -> painted", S.pageDir); add(g, "directory: status filter -> painted", S.statusDir);
  add(g, "directory: REAL click on a row, Event Timing duration", S.realClick);
  add(g, "10 000 employees + 2 000-unit org (unit <select> has 2 000 options): first React commit (Profiler actual)", S.firstBig, "ms", `options in the unit filter: ${S.optionsBig[0]}`); add(g, "  same: keystroke -> frame (12 keys)", S.keyBig); add(g, "  same: Profiler actualDuration of every commit while typing 12 keys + the debounced reload", S.selOptBig, "ms", "profiling bundle");
  add(g, "members fallback (client-side filter of 10 000): keystroke -> frame", S.keyMem); add(g, "members fallback: next page -> painted", S.pageMem); add(g, "members fallback: status filter -> painted", S.statusMem); add(g, "members fallback: member-list fetches (expect 1)", S.fetchesMem, "count");
}

// ============================================================================================ BUILDER: large pages
const RAIL = ["Trang", "Thành phần", "Dữ liệu", "Biểu mẫu", "Hành động", "Workflow", "Giao diện", "AI"];
const readyCanvas = (p, n) => p.waitForFunction((k) => document.querySelectorAll(".bx-handle").length >= k, n, { timeout: 60000 });
async function builder(ctx, base) {
  // contract maximum: PageSchemaValidator.kt MAX_SECTIONS = 50 per page, pages.ts MAX_PAGES = 20 => [46 extra sections + the 4 seeded, 19 extra pages + home]; the larger sizes are STRESS beyond the contract
  for (const [sections, pages] of [[0, 0], [46, 19], [100, 20], [400, 50], [1000, 100]]) {
    const S = { railSync: [], railBy: {}, railFirst: {}, save: [], saveSync: [], ready: [], rail: [], sel: [], selReload: [], selActual: [], selBase: [], insp: [], edit: [], editOps: [], real: [], domNodes: [], srcKB: [], longSum: [], editActual: [] };
    for (let r = 0; r < Math.max(2, sections >= 1000 ? Math.min(RUNS, 3) : RUNS); r++) {
      const t0 = Date.now(); const p = await open(ctx, `${base}/index.html${sections ? `?sections=${sections}&pages=${pages}` : ""}`); await p.waitForSelector("iframe"); await readyCanvas(p, 4 + sections);
      S.ready.push(Date.now() - t0 - 0);                                                         // goto + canvas rects reported (wall clock incl. Playwright round trips of ~10 ms)
      const li = await loadInfo(p); S.domNodes.push(li.nodes); S.longSum.push(li.longSum); S.srcKB.push(await p.evaluate(() => document.querySelector("iframe").srcdoc.length / 1024));
      for (const pass of [0, 1]) for (const t of RAIL) { const v = await T(p, 'const t=[...document.querySelectorAll(".bx-left [role=tab]")].find((x)=>x.textContent.trim().startsWith(a)); t.click()', t); S.rail.push(v); S.railSync.push(lastSync); (S.railBy[t] ??= []).push(lastSync); if (pass === 0) (S.railFirst[t] ??= []).push(lastSync); }
      await p.getByRole("tab", { name: "Trang" }).click();
      const total = 4 + sections;
      for (const idx of [1, Math.floor(total / 4), Math.floor(total / 2), Math.floor((3 * total) / 4), total - 2].filter((x, i, a) => x >= 0 && a.indexOf(x) === i)) {
        const sid = idx === 1 ? "s-hero" : `s-g${idx - 4}`; if (idx - 4 < 0 && idx !== 1) continue;
        await p.evaluate(() => { window.__prof.length = 0; });
        const res = await p.evaluate(async (id) => { const fr = document.querySelector("iframe"); const loaded = new Promise((r) => fr.addEventListener("load", r, { once: true })); const t = performance.now();
          window.dispatchEvent(new MessageEvent("message", { data: { type: "studio:select", sectionId: id }, source: fr.contentWindow })); await window.__raf2(); const insp = performance.now() - t;
          const ok = await Promise.race([loaded.then(() => true), new Promise((r) => setTimeout(() => r(false), 8000))]); await window.__raf2(); return { insp, reload: ok ? performance.now() - t : NaN }; }, sid);
        S.sel.push(res.insp); S.selReload.push(res.reload); const u = lastUpdate(await prof(p)); S.selActual.push(u.actual); S.selBase.push(u.base); await p.waitForTimeout(150);
      }
      for (const t of ["Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao", "Nội dung"]) S.insp.push(await T(p, 'const t=[...document.querySelectorAll(".bx-right [role=tab]")].find((x)=>x.textContent.trim().startsWith(a)); t.click()', t));
      // the Inspector edits a local DRAFT (typing does not send ops); "Lưu thay đổi" commits one UPDATE_PROP -> new document -> the whole preview html is rebuilt and the iframe reloads
      await p.evaluate(async () => { const fr = document.querySelector("iframe"); window.dispatchEvent(new MessageEvent("message", { data: { type: "studio:select", sectionId: "s-hero" }, source: fr.contentWindow })); await window.__raf2(); }); await p.waitForTimeout(400);   // a Hero: its title is rendered in the preview
      const field = p.locator(".bx-right input").first(); await field.click(); await p.evaluate(() => { window.__ops.length = 0; window.__prof.length = 0; });
      await p.keyboard.type("abcde"); S.editOps.push(await p.evaluate(() => window.__ops.length)); S.editActual.push(...(await prof(p)).filter((x) => x.phase !== "mount").map((x) => x.actual));
      await p.evaluate(() => { const fr = document.querySelector("iframe"); window.__loaded = new Promise((r) => fr.addEventListener("load", r, { once: true })); window.__t0 = performance.now(); window.__prof.length = 0; });
      await p.getByRole("button", { name: "Lưu thay đổi" }).click();
      S.edit.push(await p.evaluate(async () => { const ok = await Promise.race([window.__loaded.then(() => true), new Promise((r) => setTimeout(() => r(false), 8000))]); await window.__raf2(); return ok ? performance.now() - window.__t0 : NaN; }));
      S.saveSync.push(lastUpdate(await prof(p)).actual); if (process.env.PERF_DEBUG) console.error("edit", S.edit.at(-1), await p.evaluate(() => JSON.stringify(window.__ops.slice(-1)).slice(0, 200)));
      await p.evaluate(() => { window.__evt = []; }); const box = await p.locator(".bx-handle").nth(Math.min(3, total - 1)).boundingBox(); if (box) { await p.mouse.click(box.x + 4, box.y + 4); await p.waitForTimeout(150); S.real.push(await evtMax(p)); }
      if (p.__errs.length) console.error("builder page errors:", p.__errs.slice(0, 2)); await p.close();
    }
    const g = `BUILDER home page = ${4 + sections} sections, +${pages} pages`;
    add(g, "load -> every section handle present (wall clock, goto..canvas laid out)", S.ready); add(g, "main-document DOM nodes (outside the preview iframe)", S.domNodes, "count"); add(g, "preview document (iframe srcdoc) size", S.srcKB, "KB");
    add(g, "long-task time during load (sum)", S.longSum); add(g, "rail switch (8 panels, 2 passes) -> painted", S.rail); add(g, "inspector tab switch -> painted", S.insp);
    add(g, "canvas selection: inspector shown (message -> 2 frames)", S.sel); add(g, "canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc)", S.selReload);
    add(g, "  selection: Profiler actualDuration", S.selActual, "ms", "profiling bundle"); add(g, "  selection: Profiler baseDuration", S.selBase, "ms", "profiling bundle");
    add(g, "inspector: Profiler actual per typed character (local draft)", S.editActual, "ms", "profiling bundle"); add(g, "  ops sent while typing 5 characters (0 = draft, nothing sent)", S.editOps, "count");
    add(g, "save one field ('Lưu thay đổi') -> preview iframe reloaded", S.edit); add(g, "  save: Profiler actualDuration of the commit", S.saveSync, "ms", "profiling bundle");
    add(g, "rail switch: main-thread time of the click (sync React commit)", S.railSync); for (const [t, a] of Object.entries(S.railBy)) add(g, `  rail "${t}" sync main-thread (2 passes)`, a, "ms", `first open: ${f(pct(S.railFirst[t], 50))} ms`);
    add(g, "REAL pointer click on a drag handle: Event Timing duration", S.real);
  }
}


// ============================================================================================ ATTRIBUTION: who pays for one selection on a big page
async function attribution(ctx, base) {
  for (const [sections, pages] of [[46, 19], [400, 50], [1000, 100]]) {
    const A = { pages: [], comps: [], pagesBase: [], compsBase: [], pagesSync: [], compsSync: [] };
    for (let r = 0; r < Math.min(RUNS, 4); r++) {
      const p = await open(ctx, `${base}/index.html?sections=${sections}&pages=${pages}`); await p.waitForSelector("iframe"); await readyCanvas(p, 4 + sections);
      for (const [rail, key] of [["Thành phần", "comps"], ["Trang", "pages"]]) {
        await p.locator(".bx-left").getByRole("tab", { name: rail }).click(); await p.waitForTimeout(300);
        for (let k = 0; k < 6; k++) {
          await p.evaluate(() => { window.__prof.length = 0; });
          const res = await p.evaluate(async (id) => { const fr = document.querySelector("iframe"); const t = performance.now(); window.dispatchEvent(new MessageEvent("message", { data: { type: "studio:select", sectionId: id }, source: fr.contentWindow })); await window.__raf2(); return performance.now() - t; }, `s-g${(20 + k * 37) % sections}`);
          const u = lastUpdate(await prof(p)); A[key].push(u.actual); A[key + "Base"].push(u.base); A[key + "Sync"].push(res); await p.waitForTimeout(250);
        }
      }
      await p.close();
    }
    const g = `ATTRIBUTION (builder, ${4 + sections} sections): Profiler actualDuration of ONE canvas selection, by the rail that is open`;
    add(g, "rail 'Thành phần' open (the page tree is NOT mounted)", A.comps, "ms", "profiling bundle"); add(g, "rail 'Trang' open (page tree with every section row mounted)", A.pages, "ms", "profiling bundle");
    add(g, "  difference attributable to the page-tree rows (median)", [pct(A.pages, 50) - pct(A.comps, 50)], "ms", "profiling bundle");
  }
}

// ============================================================================================ CANVAS SCROLL: the preview posts every section rect to the host on each scroll frame
async function canvasScroll(ctx, base) {
  for (const [sections, pages] of [[0, 0], [46, 19], [100, 20], [400, 50], [1000, 100]]) {
    const A = { msgs: [], commits: [], actualSum: [], worstGap: [], slow: [], frames: [] };
    for (let r = 0; r < Math.min(RUNS, 3); r++) {
      const p = await open(ctx, `${base}/index.html${sections ? `?sections=${sections}&pages=${pages}` : ""}`); await p.waitForSelector("iframe"); await readyCanvas(p, 4 + sections); await p.waitForTimeout(500);
      await p.evaluate(() => { window.__layoutMsgs = 0; window.addEventListener("message", (e) => { if (e.data && e.data.type === "studio:layout") window.__layoutMsgs++; });
        window.__gaps = []; let last = performance.now(); const tick = () => { const n = performance.now(); window.__gaps.push(n - last); last = n; window.__raf = requestAnimationFrame(tick); }; window.__raf = requestAnimationFrame(tick); window.__prof.length = 0; });
      const box = await p.locator(".bx-frame").boundingBox(); await p.mouse.move(box.x + box.width / 2, box.y + 200);
      for (let i = 0; i < 25; i++) { await p.mouse.wheel(0, 140); await p.waitForTimeout(40); }
      await p.waitForTimeout(300);
      const res = await p.evaluate(() => { cancelAnimationFrame(window.__raf); const g = window.__gaps.slice(2); return { msgs: window.__layoutMsgs, commits: window.__prof.length, actual: window.__prof.reduce((a, x) => a + x.actual, 0), worst: Math.max(...g), slow: g.filter((x) => x > 34).length, frames: g.length }; });
      A.msgs.push(res.msgs); A.commits.push(res.commits); A.actualSum.push(res.actual); A.worstGap.push(res.worst); A.slow.push(res.slow); A.frames.push(res.frames); await p.close();
    }
    const g = `CANVAS SCROLL (builder, ${4 + sections} sections): 25 wheel steps over the preview in ~1.3 s`;
    add(g, "layout messages the preview posted to the host", A.msgs, "count"); add(g, "React commits of the host during the scroll", A.commits, "count"); add(g, "Profiler actualDuration summed over those commits", A.actualSum, "ms", "profiling bundle");
    add(g, "worst frame gap (rAF to rAF)", A.worstGap); add(g, "frames slower than 34 ms (a dropped frame at 60 Hz)", A.slow, "count", `of ~${Math.round(pct(A.frames, 50))} frames`);
  }
}

// ============================================================================================ MEMORY over repeated actions
async function memory(ctx, base) {
  const rows = [];
  async function run(label, url, ready, round, rounds = ROUNDS) {
    const p = await open(ctx, url); await ready(p); await p.waitForTimeout(500); const trace = []; let a, b;
    for (let i = 1; i <= rounds; i++) { await round(p, i); if (i === 5) a = await counters(p); if (i % 10 === 0) trace.push(`${i}:${(await counters(p)).nodes}`); }
    b = await counters(p); const errs = p.__errs.length; if (errs) console.error(`[${label}] page errors/warnings, first 3 distinct:`, [...new Set(p.__errs)].slice(0, 3)); await p.close();
    rows.push({ label, rounds, a, b, trace: trace.join(" "), errs });
  }
  await run("ORG 2 000: expand all + 3 selections + collapse all", `${base}/org.html?v=org&s=big`, (p) => p.getByTestId("org-tree").waitFor(), async (p) => { await click(p, "org-expand-all"); for (const id of [100, 900, 1700]) await click(p, `node:g${id}`); await click(p, "org-collapse-all"); });
  await run("EMP 10 000 directory: type search + next page + clear", `${base}/org.html?v=emp&s=emp-10k`, (p) => p.getByTestId("emp-table").waitFor(), async (p) => {
    await p.evaluate(async () => { const el = document.querySelector('[data-testid="emp-search"]'); const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set; for (const v of ["n", "nh", "nha", ""]) { set.call(el, v); el.dispatchEvent(new Event("input", { bubbles: true })); await window.__raf2(); } });
    await p.waitForTimeout(450); await click(p, "emp-next"); await p.waitForTimeout(80); });
  await run("BUILDER 100 sections: rail x8 + select + inspector tabs + Test panel", `${base}/index.html?sections=100&pages=20`, async (p) => { await p.waitForSelector("iframe"); await readyCanvas(p, 104); }, async (p, i) => {
    for (const t of RAIL) await p.locator(".bx-left").getByRole("tab", { name: t }).click();
    await p.evaluate(async (k) => { const fr = document.querySelector("iframe"); window.dispatchEvent(new MessageEvent("message", { data: { type: "studio:select", sectionId: `s-g${k}` }, source: fr.contentWindow })); await window.__raf2(); }, i % 90);
    for (const t of [/^Nội dung/, /^Thiết kế/, /^Dữ liệu/]) await p.locator(".bx-right").getByRole("tab", { name: t }).first().click().catch(() => undefined);
    await p.getByRole("button", { name: "Dùng thử" }).click(); await p.locator('[data-testid="test-panel"]').waitFor(); await p.getByRole("button", { name: "Chỉnh sửa" }).click(); await p.waitForTimeout(60); }, Math.min(ROUNDS, 25));
  return rows;
}

const t0 = Date.now(); let envInfo; let memRows = [];
await withEnv({ dir: DIR, tag: "perfh" }, async ({ base, browser, chromeVersion }) => {
  envInfo = { chromeVersion, dir: DIR, runs: RUNS, cpuThrottle: CPU, node: process.version, os: `${process.platform} ${process.arch}` };
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  if (ONLY.has("org")) await orgBig(ctx, base);
  if (ONLY.has("emp")) await empBig(ctx, base);
  if (ONLY.has("builder")) await builder(ctx, base);
  if (ONLY.has("attrib")) await attribution(ctx, base);
  if (ONLY.has("scroll")) await canvasScroll(ctx, base);
  if (ONLY.has("memory")) memRows = await memory(ctx, base);
  await ctx.close();
});

console.log(`\nHARNESS, NOT REAL BACKEND. Generated fixtures + in-page fakes; headless Chrome ${envInfo.chromeVersion} on ${envInfo.os}, Node ${envInfo.node}; bundle ${envInfo.dir}; runs=${envInfo.runs}; CPU throttle x${envInfo.cpuThrottle}; wall ${((Date.now() - t0) / 1000).toFixed(0)} s`);
let cur = "";
for (const r of results) {
  if (r.group !== cur) { cur = r.group; console.log(`\n### ${cur}\n| measure | unit | n | median | p95 | max | note |\n|---|---|---:|---:|---:|---:|---|`); }
  console.log(`| ${r.name} | ${r.unit} | ${r.n} | ${f(r.med)} | ${f(r.p95)} | ${f(r.max)} | ${r.note} |`);
}
if (memRows.length) {
  console.log(`\n### MEMORY over repeated actions (counters after forced GC; round 5 vs last round)\n| scenario | rounds | DOM nodes 5 -> last | listeners 5 -> last | heap MB 5 -> last | documents | node trend | page errors |\n|---|---:|---|---|---|---|---|---:|`);
  for (const m of memRows) console.log(`| ${m.label} | ${m.rounds} | ${m.a.nodes} -> ${m.b.nodes} | ${m.a.listeners} -> ${m.b.listeners} | ${f(m.a.heapMB)} -> ${f(m.b.heapMB)} | ${m.a.docs} -> ${m.b.docs} | ${m.trace} | ${m.errs} |`);
}
if (JSON_OUT) writeFileSync(JSON_OUT, JSON.stringify({ env: envInfo, results, memory: memRows }, null, 1));
