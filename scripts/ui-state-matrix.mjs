#!/usr/bin/env node
// HARNESS, NOT REAL BACKEND. STATE MATRIX: for every screen of the Platform / Admin consoles (tests/browser/admin-harness.tsx, S2's personas) and of the Studio (tests/browser/studio-app, fake API) the runner
// drives the screen into default / loading / empty / error / permission-denied / populated / long-content and writes PASS / FAIL / NOT-REACHABLE per cell, with the reason. The screen list comes from the SOURCE
// (scripts/audit/inventory.mjs); the run FAILS when a route in the source has no row, and when any cell is FAIL.
//   HARNESS: the data and the failures are injected, so nothing here says what a real server answers.
//     Admin: a `fetch` wrapper (installed before the harness assigns its own) delays, empties, fails (500 with a leaky Java message), denies (403) or lengthens every data GET of the screen; permission-denied also tries the
//            persona `me=wsadmin` on system-only sections. Studio: the fake API state (`hold`, `fail`, empty lists, long names).
//   NOT-REACHABLE = the state cannot be produced for that screen in the harness (the screen makes no data request, or a fixture is constant), said in words; it is never counted as a pass.
//   HARNESS_NODE_ENV=development node tests/browser/build-harness.mjs
//   node scripts/ui-state-matrix.mjs [--out /tmp/ui-state-matrix] [--only platform,admin,studio] [--viewports 1440,390] [--states default,loading,...]
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { withEnv } from "./perf-env.mjs";
import { MEASURE, viewportOf } from "./audit/measure.mjs";
import { inventory, visitPaths, missingRoutes } from "./audit/inventory.mjs";
import { installFake, newState } from "../tests/browser/studio-app/fake-api.mjs";

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-state-matrix"); const ONLY = arg("only", "platform,admin,studio").split(","); const VPS = arg("viewports", "1440,390").split(",").map(Number);
const ALL_STATES = ["default", "loading", "empty", "error", "permission-denied", "populated", "long-content"]; const STATES = arg("states", ALL_STATES.join(",")).split(",");
const IDS = { tenants: "t1", users: "u2", workspaces: "w1", applications: "a1", project: "p1" };
const LEAK = /NullPointerException|java\.|com\.systemwebstudio|SQL|Exception|stack trace|INTERNAL_ERROR|at \S+\.kt/i;
const RAW = /\bundefined\b|\bNaN\b|\[object Object\]|\bnull\b/;

// ------------------------------------------------------------------------------------------------------------------------------- Admin: the in-page fetch wrapper
const WRAP = (mode) => {
  let inner = null; window.__apiGets = []; window.__apiAll = [];
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const LONG = (s) => `${s} — ${"Nội dung rất dài của trường này ".repeat(6)}${"KhongNgatDongNao".repeat(6)}`;
  const longify = (v, k) => (typeof v === "string" ? (/(name|title|label|description|summary|email|username|slug|message|detail|note|owner|actor)/i.test(k ?? "") && !/(id|at|date|token|status|role|kind|type)$/i.test(k ?? "") && !/^\d{4}-\d\d-\d\d/.test(v) ? LONG(v) : v) : Array.isArray(v) ? v.map((x) => longify(x, k)) : v && typeof v === "object" ? Object.fromEntries(Object.entries(v).map(([kk, x]) => [kk, longify(x, kk)])) : v);
  const emptify = (v, depth = 0) => (Array.isArray(v) ? [] : v && typeof v === "object" && depth < 3 ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, k === "total" ? 0 : Array.isArray(x) ? [] : emptify(x, depth + 1)])) : v);
  const wrapped = async (input, init) => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href);
    const api = url.pathname.startsWith("/api/v1/"); const path = url.pathname.slice(7); const method = (init?.method ?? "GET").toUpperCase();
    const data = api && method === "GET" && !/^\/auth\//.test(path);
    if (api) window.__apiAll.push(`${method} ${path}`); if (data) window.__apiGets.push(path);
    const j = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (data && mode === "loading") await sleep(4000);
    if (data && mode === "error") return j(500, { code: "INTERNAL", message: "java.lang.NullPointerException at com.systemwebstudio.Foo.bar(Foo.kt:42)", requestId: "req-123" });
    if (data && mode === "forbidden") return j(403, { code: "FORBIDDEN", message: "Access Denied", requestId: "req-403" });
    const res = await inner(input, init);
    if (data && (mode === "empty" || mode === "long") && res.ok && res.status !== 204) { const body = await res.clone().json().catch(() => undefined); if (body !== undefined) return j(res.status, mode === "empty" ? emptify(body) : longify(body)); }
    return res;
  };
  Object.defineProperty(window, "fetch", { configurable: true, get: () => wrapped, set: (f) => { inner = f; } });
};

// ------------------------------------------------------------------------------------------------------------------------------- what a page looks like (in-page snapshot)
const SNAP = () => {
  const main = document.querySelector("main") ?? document.body; const text = document.body.innerText;
  const kinds = [...document.querySelectorAll(".stateView")].map((e) => (/state-([a-z-]+)/.exec(e.className)?.[1] ?? "?"));
  const alerts = [...document.querySelectorAll("[role=alert],.formError,.notice.warn,[data-kind]")].map((e) => e.textContent.trim().slice(0, 160)).filter(Boolean);
  return { kinds, alerts, mainLen: main.innerText.trim().length, bodyLen: text.trim().length, text: text.slice(0, 4000), retry: [...document.querySelectorAll("button")].some((b) => /Thử lại/.test(b.textContent)),
    loading: !!document.querySelector(".state-loading,.spinner,[aria-busy=true],[role=status]"), emptyText: /Chưa có|Không có|trống|Chưa thêm|Chưa chọn/i.test(text), emptyTextHidden: /Chưa có|Không có|trống|Chưa thêm|Chưa chọn/i.test(document.body.textContent ?? ""), forbiddenText: /không có quyền|chỉ dành cho|Bạn chưa quản trị|không thuộc|Máy chủ không liệt kê/i.test(text) };
};

// ------------------------------------------------------------------------------------------------------------------------------- judging
const bad = (v) => ({ status: "FAIL", note: v });
const ok = (note = "") => ({ status: "PASS", note });
const nr = (note) => ({ status: "NOT-REACHABLE", note });
function judge(state, s, m, errs, ctx) {
  const common = [];
  if (errs.page.length) common.push(`uncaught: ${errs.page[0].slice(0, 80)}`);
  if (state !== "loading" && s.bodyLen < 30) common.push("blank page");
  if (RAW.test(s.text.replace(/\bnull\b(?=\s*$)/g, "")) && /\b(undefined|NaN|\[object Object\])\b/.test(s.text)) common.push("raw undefined / NaN / [object Object] on screen");
  if (state !== "error" && errs.console.length) common.push(`console error: ${errs.console[0].slice(0, 80)}`);
  if (common.length) return bad(common.join("; "));
  switch (state) {
    case "default": case "populated": { const e = s.kinds.filter((k) => ["error", "network", "conflict", "expired"].includes(k)); if (e.length) return bad(`shows a ${e.join("/")} state with healthy fixture data`); const sub = s.kinds.filter((k) => ["forbidden", "notfound"].includes(k)); return ok(sub.length ? `a ${sub.join("/")} sub-state is shown (check the fixture or the permission)` : ""); }
    case "loading": return s.loading ? ok("loading indicator within 450 ms") : bad("no loading indicator (status / spinner / aria-busy) while the request is pending");
    case "empty": return s.kinds.includes("error") ? bad("empty data shows an ERROR state") : (s.kinds.includes("empty") || s.emptyText || s.emptyTextHidden) ? ok(s.kinds.includes("empty") ? "empty state" : s.emptyText ? "empty text" : "empty text exists in a panel that is hidden at this width (phone: one workspace at a time)") : bad("empty data shows neither an empty state nor an empty text (looks broken)");
    case "error": if (LEAK.test(s.text)) { const m = LEAK.exec(s.text); return bad(`raw backend text on screen: ...${s.text.slice(Math.max(0, m.index - 30), m.index + 40).replace(/\s+/g, " ")}...`); }
      return s.kinds.some((k) => ["error", "network"].includes(k)) || s.alerts.length ? ok(`error shown${s.retry ? " with a retry button" : " (no retry button)"}`) : bad("a failing request shows no error state (silent or blank)");
    case "permission-denied": { const m = /\b403\b|FORBIDDEN|Access Denied/.exec(s.text); if (m) return bad(`raw 403 / FORBIDDEN text on screen: ...${s.text.slice(Math.max(0, m.index - 30), m.index + 40).replace(/\s+/g, " ")}...`); }
      return s.kinds.includes("forbidden") || s.forbiddenText ? ok("forbidden state in words") : bad("denied request shows no 'no permission' state");
    case "long-content": return m.overflowX > 1 ? bad(`horizontal overflow +${m.overflowX}px`) : m.spill?.length ? bad(`text spills out of the viewport: ${m.spill[0]}`) : m.clipped?.length ? bad(`clipped text without ellipsis: ${m.clipped[0]}`) : m.clippedCtl?.length ? bad(`control unreachable: ${m.clippedCtl[0]}`) : ok("no overflow / spill / clipping");
  }
  return bad("unknown state");
}

const cells = []; const visitedIds = new Set();
const add = (screen, state, vp, r, extra = {}) => cells.push({ screen, state, vp, ...r, ...extra });

// ------------------------------------------------------------------------------------------------------------------------------- the run
await withEnv({ dir: ".test-build/browser", tag: "statem" }, async ({ base, browser, chromeVersion }) => {
  const inv = inventory();
  const rel = (c, p) => p.slice(`/${c}`.length) || "/";
  const adminUrl = (portal, me, path) => `${base}/admin.html?portal=${portal}&me=${me}&start=${encodeURIComponent(path)}`;
  const capture = (page) => { const errs = { console: [], page: [] }; page.on("pageerror", (e) => errs.page.push(e.message)); page.on("console", (m) => { if (m.type() === "error" && !/favicon|Failed to load resource|404|403|500/.test(m.text())) errs.console.push(m.text()); }); return errs; };
  const snap = async (page) => { const s = await page.evaluate(SNAP); const m = await page.evaluate(MEASURE).catch(() => ({})); return [s, m]; };

  for (const vp of VPS) {
    const ctx = await browser.newContext({ viewport: viewportOf(vp) });
    // ---------------- Platform / Admin consoles
    for (const consoleName of ["platform", "admin"].filter((c) => ONLY.includes(c))) {
      const routes = visitPaths(inv, consoleName, { ids: IDS }).filter(({ route }) => route.owned || (consoleName === "admin" && ["scoped", "people", "coming"].includes(route.surface)));
      for (const { route, path } of routes) {
        const sub = rel(consoleName, path); const vpath = `/${consoleName}${sub === "/" ? "" : sub}`; const screen = `${consoleName}${sub}`; visitedIds.add(route.id);
        const scoped = ["scoped", "people"].includes(route.surface); const persona = scoped ? "tadmin" : "sys";
        const systemOnly = ["standard", "platform-only"].includes(route.surface) && route.key !== "";           // a company / workspace admin must be refused
        const open = async (mode, me = persona, extra = "") => { const page = await ctx.newPage(); const errs = capture(page); await page.addInitScript(WRAP, mode); await page.goto(`${adminUrl(consoleName, me, vpath)}${extra}`, { waitUntil: "domcontentloaded" }); return { page, errs }; };
        const settle = async (page, st) => { if (st === "loading") await page.waitForTimeout(450); else { await page.waitForLoadState("networkidle").catch(() => undefined); await page.waitForTimeout(600); } };
        // the default state first: it also says whether the screen makes any data request at all
        let gets = [];
        { const o = await open("default"); await settle(o.page, "default"); gets = await o.page.evaluate(() => window.__apiGets); if (STATES.includes("default")) { const [s, m] = await snap(o.page); add(screen, "default", vp, judge("default", s, m, o.errs, {}), { dataRequests: gets.length }); } await o.page.close(); }
        for (const st of STATES.filter((x) => x !== "default")) {
          let page; try {
            if (["loading", "empty", "error", "long-content"].includes(st) && !gets.length) { add(screen, st, vp, nr("the screen makes no data request in the harness (nothing to delay / empty / fail / lengthen)")); continue; }
            if (st === "permission-denied" && !systemOnly && !gets.length) { add(screen, st, vp, nr("an open screen with no data request: nothing can be denied")); continue; }
            let o, via = "";
            if (st === "populated") { o = await open("default", persona, route.key === "tenants" ? "&big=1" : ""); via = route.key === "tenants" ? "big=1 (63 companies)" : "default fixture"; }
            else if (st === "permission-denied") { if (systemOnly) { o = await open("default", "wsadmin"); via = "persona me=wsadmin"; } else { o = await open("forbidden"); via = "API 403"; } }
            else o = await open(st === "long-content" ? "long" : st);
            page = o.page; await settle(page, st); const [s, m] = await snap(page);
            add(screen, st, vp, judge(st, s, m, o.errs, {}), via ? { via } : {});
          } catch (e) { add(screen, st, vp, bad(`runner: ${String(e).slice(0, 100)}`)); } finally { await page?.close().catch(() => undefined); }
        }
      }
    }
    // ---------------- Studio
    if (ONLY.includes("studio")) {
      const routes = visitPaths(inv, "studio", { ids: IDS });
      for (const { route, path } of routes) {
        const screen = `studio${rel("studio", path) === "/" ? "/" : rel("studio", path)}`; visitedIds.add(route.id);
        const isProject = route.id.startsWith("studio/projects/:id");
        let hasData = true;
        for (const st of ALL_STATES.filter((x) => STATES.includes(x))) {
          let page; try {
            const s0 = newState(); const hold = { release: () => undefined };
            if (st === "loading") { let rel_; const p = new Promise((r) => { rel_ = r; }); hold.release = rel_; s0.hold["^GET /(workspaces|projects|components|templates|component-packages|me/|library)"] = { promise: p }; }
            if (st === "error") s0.fail["^GET /(workspaces|projects|components|templates|component-packages|me/|library)"] = { code: "INTERNAL_ERROR", message: "java.lang.NullPointerException at com.systemwebstudio.Foo.bar(Foo.kt:42)", status: 500 };
            if (st === "permission-denied") s0.fail["^GET /(workspaces|projects|components|templates|component-packages|me/|library)"] = { code: "FORBIDDEN", message: "Access Denied", status: 403 };
            if (st === "empty") { if (!isProject) s0.projects = []; s0.templates = []; s0.blocks = []; s0.versions = []; s0.prompts = []; s0.assets = []; s0.members = []; }   // a project screen stays on its project: emptying the project list would make it "not found", a different state
            if (st === "long-content") { const L = (x) => `${x} — ${"Nội dung rất dài của trường này ".repeat(6)}${"KhongNgatDongNao".repeat(6)}`; s0.me.displayName = L(s0.me.displayName); s0.me.workspaces[0].name = L(s0.me.workspaces[0].name); s0.project.name = L(s0.project.name); s0.projects = s0.projects.map((p) => ({ ...p, name: L(p.name), description: L("mô tả") })); s0.versions = s0.versions.map((v) => ({ ...v, summary: L(v.summary) })); }
            if (st === "populated") { s0.projects = Array.from({ length: 30 }, (_, i) => ({ ...s0.project, id: `px${i}`, name: `Ứng dụng số ${i}` })); s0.projects[0] = s0.project; }
            page = await ctx.newPage(); const errs = capture(page); await installFake(page, s0);
            await page.goto(`${base}/studio.html?start=${encodeURIComponent(`/studio${rel("studio", path) === "/" ? "" : rel("studio", path)}`)}`, { waitUntil: "domcontentloaded" });
            if (st === "loading") await page.waitForTimeout(450); else { await page.waitForLoadState("networkidle").catch(() => undefined); await page.waitForTimeout(800); }
            const gets = s0.log.filter((l) => l.method === "GET" && !/^\/auth\//.test(l.path));
            if (st === "default") hasData = gets.length > 0;
            const [s, m] = await snap(page);
            if (st === "loading") hold.release();
            if (!hasData && ["loading", "error", "permission-denied", "empty"].includes(st)) add(screen, st, vp, nr("the screen makes no data request in the harness"));
            else if (st === "empty" && ["components"].includes(route.key)) add(screen, st, vp, nr("the harness registry (/components) is a constant fixture, it cannot be emptied"));
            else if (st === "populated" && !["projects", ""].includes(route.key)) add(screen, st, vp, ok("populated = the default fixture (30 projects only on the project list)"), { note2: "default fixture" });
            else add(screen, st, vp, judge(st, s, m, errs, { expectState: false }));
          } catch (e) { add(screen, st, vp, bad(`runner: ${String(e).slice(0, 100)}`)); } finally { await page?.close().catch(() => undefined); }
        }
      }
    }
    await ctx.close();
  }

  const missing = missingRoutes(inv, visitedIds, { consoles: ONLY }).filter((id) => { const r = inv.routes.find((x) => x.id === id); return r && (r.owned || (r.console === "admin" && ["scoped", "people", "coming"].includes(r.surface)) || r.console === "studio"); });
  const count = (s) => cells.filter((c) => c.status === s).length;
  const summary = { cells: cells.length, PASS: count("PASS"), FAIL: count("FAIL"), "NOT-REACHABLE": count("NOT-REACHABLE"), screens: new Set(cells.map((c) => c.screen)).size, viewports: VPS, states: STATES };
  mkdirSync(OUT, { recursive: true });
  writeFileSync(join(OUT, "state-matrix.json"), JSON.stringify({ mode: "HARNESS, NOT REAL BACKEND", chrome: chromeVersion, inventory: { source: inv.source, routes: inv.routes.length, consoles: ONLY, missing }, summary, cells }, null, 1));
  // table: one row per screen and viewport, one column per state
  const key = (c) => `${c.screen}|${c.vp}`; const byScreen = new Map(); for (const c of cells) { const k = key(c); (byScreen.get(k) ?? byScreen.set(k, { screen: c.screen, vp: c.vp, st: {} }).get(k)).st[c.state] = c; }
  const sym = (c) => (!c ? "-" : c.status === "PASS" ? "PASS" : c.status === "FAIL" ? "FAIL" : "N/R");
  const md = [`HARNESS, NOT REAL BACKEND. Chrome ${chromeVersion}. N/R = NOT-REACHABLE.`, "", `| screen | vp | ${STATES.join(" | ")} |`, `|---|---:|${STATES.map(() => "---").join("|")}|`, ...[...byScreen.values()].map((r) => `| ${r.screen} | ${r.vp} | ${STATES.map((s) => sym(r.st[s])).join(" | ")} |`),
    "", "### FAIL cells", "", "| screen | vp | state | reason |", "|---|---:|---|---|", ...cells.filter((c) => c.status === "FAIL").map((c) => `| ${c.screen} | ${c.vp} | ${c.state} | ${c.note} |`),
    "", "### NOT-REACHABLE reasons (distinct)", "", ...[...new Set(cells.filter((c) => c.status === "NOT-REACHABLE").map((c) => c.note))].map((n) => `* ${n}`)].join("\n");
  writeFileSync(join(OUT, "state-matrix.md"), md + "\n");
  console.log(md.split("\n").slice(0, 3 + byScreen.size + 2).join("\n")); console.log(`\nsummary ${JSON.stringify(summary)}\ninventory (${inv.source}): ${missing.length} runnable routes without a row${missing.length ? ": " + missing.join(", ") : ""}\nreport: ${OUT}/state-matrix.md json: ${OUT}/state-matrix.json`);
  if (missing.length || summary.FAIL) process.exitCode = 1;
});
