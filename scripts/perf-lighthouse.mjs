// @class: tooling (S4 audit). SYNTHETIC local Lighthouse runs (lab data on this machine; NOT production Core Web Vitals, no field data). Measures; changes nothing.
// Lighthouse is NOT a repo dependency:  npm i --prefix /tmp/lh lighthouse
// Chrome is started by the caller THROUGH the owned-process CLI (never by Lighthouse itself), so it is stopped by identity:
//   node tests/lib/owned-process-cli.mjs start --state .run/owned/s4-chrome-lh.json --port 19200 --name chrome-lh -- "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
//        --headless=new --remote-debugging-port=19200 --user-data-dir=<private dir> --no-first-run --no-default-browser-check about:blank
//   node scripts/perf-lighthouse.mjs --port 19200 --runs 3 --out <dir> http://127.0.0.1:19101/login ...
//   node tests/lib/owned-process-cli.mjs stop --state .run/owned/s4-chrome-lh.json
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf(`--${k}`); if (i < 0) return d; const v = argv[i + 1]; argv.splice(i, 2); return v; };
const port = opt("port"); const runs = Number(opt("runs", "3")); const out = opt("out", "."); const lh = opt("lh", "/tmp/lh/node_modules/lighthouse/cli/index.js");
const urls = argv; if (!port || !urls.length) { console.error("usage: perf-lighthouse.mjs --port CDP_PORT [--runs 3] [--out DIR] url..."); process.exit(1); }
mkdirSync(out, { recursive: true });
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const rows = [];
for (const url of urls) for (const preset of ["mobile", "desktop"]) {
  const r = { lcp: [], cls: [], tbt: [], fcp: [], si: [], tti: [], perf: [], a11y: [], bp: [], bytes: [], reqs: [], mainThread: [] };
  let failed = null;
  for (let i = 0; i < runs; i++) {
    const file = join(out, `${url.replace(/[^a-z0-9]+/gi, "_")}-${preset}-${i}.json`);
    try {
      execFileSync(process.execPath, [lh, url, `--port=${port}`, "--output=json", `--output-path=${file}`, "--quiet", "--only-categories=performance,accessibility,best-practices", ...(preset === "desktop" ? ["--preset=desktop"] : [])], { stdio: ["ignore", "ignore", "pipe"], timeout: 180000 });
      const j = JSON.parse(readFileSync(file, "utf8")); const a = j.audits;
      if (j.runtimeError) { failed = j.runtimeError.code; continue; }
      r.lcp.push(a["largest-contentful-paint"].numericValue); r.cls.push(a["cumulative-layout-shift"].numericValue); r.tbt.push(a["total-blocking-time"].numericValue);
      r.fcp.push(a["first-contentful-paint"].numericValue); r.si.push(a["speed-index"].numericValue); r.tti.push(a["interactive"].numericValue);
      r.perf.push(j.categories.performance.score * 100); r.a11y.push(j.categories.accessibility.score * 100); r.bp.push(j.categories["best-practices"].score * 100);
      r.bytes.push(a["total-byte-weight"].numericValue); r.reqs.push(a["network-requests"].details.items.length); r.mainThread.push(a["mainthread-work-breakdown"]?.numericValue ?? 0);
    } catch (e) { failed = String(e.stderr ?? e.message).slice(0, 200); }
  }
  rows.push({ url, preset, n: r.perf.length, failed, ...Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v.length ? med(v) : null])) });
}
console.log("SYNTHETIC lab results, local machine, median of N runs; not production Core Web Vitals.");
console.log("| URL | preset | N | perf | a11y | best-pr | FCP ms | LCP ms | SI ms | TBT ms | CLS | TTI ms | transfer KB | requests |\n|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
const f = (n, d = 0) => (n == null ? "n/a" : n.toFixed(d));
for (const x of rows) console.log(`| ${x.url} | ${x.preset} | ${x.n}${x.failed ? ` (err ${x.failed})` : ""} | ${f(x.perf)} | ${f(x.a11y)} | ${f(x.bp)} | ${f(x.fcp)} | ${f(x.lcp)} | ${f(x.si)} | ${f(x.tbt)} | ${f(x.cls, 3)} | ${f(x.tti)} | ${f(x.bytes / 1024)} | ${f(x.reqs)} |`);
