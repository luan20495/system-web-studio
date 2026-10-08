// @class: tooling (S4 audit). SYNTHETIC lab Web Vitals (PerformanceObserver: FCP, LCP, CLS, long tasks -> TBT-like, resource bytes) in an OWNED headless Chrome against URLs the caller already serves.
// NOT field data, NOT production Core Web Vitals: one machine, localhost, no network latency unless --cpu is used. Lighthouse (scripts/perf-lighthouse.mjs) is the simulated-throttling counterpart.
//   node scripts/perf-vitals.mjs [--runs 5] [--cpu 1|4] url...
import { withEnv } from "./perf-env.mjs";

const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf(`--${k}`); if (i < 0) return d; const v = argv[i + 1]; argv.splice(i, 2); return v; };
const RUNS = Number(opt("runs", "5")), CPU = Number(opt("cpu", "1")); const urls = argv;
if (!urls.length) { console.error("usage: perf-vitals.mjs [--runs N] [--cpu X] url..."); process.exit(1); }
const INIT = () => {
  window.__v = { lcp: 0, cls: 0, long: [], lcpEl: "" };
  new PerformanceObserver((l) => { for (const e of l.getEntries()) { window.__v.lcp = e.startTime; window.__v.lcpEl = e.element ? e.element.tagName + "." + (e.element.className || "") : e.url; } }).observe({ type: "largest-contentful-paint", buffered: true });
  new PerformanceObserver((l) => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__v.cls += e.value; }).observe({ type: "layout-shift", buffered: true });
  new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__v.long.push({ at: e.startTime, d: e.duration }); }).observe({ type: "longtask", buffered: true });
};
const med = (a) => { const s = a.filter(Number.isFinite).sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
const mx = (a) => Math.max(...a.filter(Number.isFinite)); const f = (x, d = 0) => (Number.isFinite(x) ? x.toFixed(d) : "n/a");
const rows = [];
await withEnv({ dir: null, tag: "perfv" }, async ({ browser, chromeVersion }) => {
  console.log(`SYNTHETIC lab run (PerformanceObserver) in headless Chrome ${chromeVersion}; CPU throttle x${CPU}; runs=${RUNS}; NOT production Core Web Vitals.`);
  for (const url of urls) {
    const S = { fcp: [], lcp: [], cls: [], tbt: [], longMax: [], dcl: [], load: [], jsKB: [], reqs: [], lcpEl: "" };
    for (let i = 0; i < RUNS; i++) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const page = await ctx.newPage(); await page.addInitScript(INIT);
      const cdp = await ctx.newCDPSession(page); if (CPU > 1) await cdp.send("Emulation.setCPUThrottlingRate", { rate: CPU });
      await page.goto(url, { waitUntil: "load" }); await page.waitForTimeout(1500);
      const r = await page.evaluate(() => { const nav = performance.getEntriesByType("navigation")[0]; const fcp = performance.getEntriesByType("paint").find((e) => e.name === "first-contentful-paint");
        const res = performance.getEntriesByType("resource"); return { fcp: fcp?.startTime ?? NaN, lcp: window.__v.lcp, lcpEl: window.__v.lcpEl, cls: window.__v.cls, tbt: window.__v.long.filter((x) => x.at >= (fcp?.startTime ?? 0)).reduce((a, x) => a + Math.max(0, x.d - 50), 0), longMax: Math.max(0, ...window.__v.long.map((x) => x.d)),
          dcl: nav.domContentLoadedEventEnd, load: nav.loadEventEnd, jsKB: res.filter((x) => x.initiatorType === "script").reduce((a, x) => a + (x.encodedBodySize || 0), 0) / 1024, reqs: res.length + 1 }; });
      for (const k of Object.keys(S)) if (k !== "lcpEl") S[k].push(r[k]); S.lcpEl = r.lcpEl; await ctx.close();
    }
    rows.push({ url, ...Object.fromEntries(Object.entries(S).map(([k, v]) => [k, Array.isArray(v) ? med(v) : v])), lcpMax: mx(S.lcp) });
  }
});
console.log("| URL | FCP ms | LCP ms (max) | LCP element | CLS | TBT-like ms | longest task ms | DCL ms | load ms | script bytes on wire KB | requests |\n|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|");
for (const r of rows) console.log(`| ${r.url} | ${f(r.fcp)} | ${f(r.lcp)} (${f(r.lcpMax)}) | ${r.lcpEl} | ${f(r.cls, 3)} | ${f(r.tbt)} | ${f(r.longMax)} | ${f(r.dcl)} | ${f(r.load)} | ${f(r.jsKB, 1)} | ${f(r.reqs)} |`);
