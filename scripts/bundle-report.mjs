// @class: tooling (S4 audit). Reads a finished `next build` (Turbopack) and prints raw / gzip / brotli sizes. Measures; changes nothing.
// Turbopack's build output no longer prints "First Load JS", so it is derived from the manifests: rootMainFiles + the chunks the route's client-reference-manifest lists.
//
//   NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 (cd apps/platform && npx next build)   # private dist dir, no backend
//   node scripts/bundle-report.mjs apps/platform/.next-check-s4 apps/admin/.next-check-s4 apps/studio/.next-check-s4 .next-check-s4
//   node scripts/bundle-report.mjs --modules apps/studio/app/entry.tsx      # esbuild PROXY of module composition (esbuild at /tmp/esb, not a Next build)
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { gzipSync, brotliCompressSync } from "node:zlib";
import { createRequire } from "node:module";
import { join, resolve, relative } from "node:path";

const root = resolve(new URL("..", import.meta.url).pathname);
const kb = (n) => (n / 1024).toFixed(1);
const size = (f) => { const b = readFileSync(f); return { raw: b.length, gz: gzipSync(b, { level: 6 }).length, br: brotliCompressSync(b).length }; };

function chunkSet(dist) {
  const bm = JSON.parse(readFileSync(join(dist, "build-manifest.json"), "utf8"));
  const dir = join(dist, "server/app/[[...slug]]");
  const m = readFileSync(join(dir, "page_client-reference-manifest.js"), "utf8");
  const json = m.slice(m.indexOf("= {") + 2, m.lastIndexOf("}") + 1);
  const man = JSON.parse(json);
  const entry = new Set();
  for (const v of Object.values(man.clientModules)) for (const c of v.chunks) entry.add(c.replace(/^\/_next\//, ""));
  const root = new Set(bm.rootMainFiles);
  const css = new Set([...(man.entryCSSFiles ? Object.values(man.entryCSSFiles).flat().map((x) => x.path) : [])]);
  return { entry, root, css, polyfill: bm.polyfillFiles ?? [] };
}

function reportDist(dist) {
  const abs = resolve(root, dist);
  if (!existsSync(join(abs, "build-manifest.json"))) { console.log(`${dist}: no build manifest`); return null; }
  const { entry, root: rootFiles, css, polyfill } = chunkSet(abs);
  const all = []; const walk = (d) => { for (const n of readdirSync(d)) { const p = join(d, n); statSync(p).isDirectory() ? walk(p) : all.push(p); } };
  walk(join(abs, "static/chunks"));
  const rows = all.map((f) => ({ file: relative(join(abs, "static"), f), ...size(f) })).sort((a, b) => b.raw - a.raw);
  const firstLoad = new Set([...rootFiles, ...entry]);
  const sum = (set) => rows.filter((r) => set.has(r.file.replace(/^/, "")) || set.has("static/" + r.file)).reduce((a, r) => ({ raw: a.raw + r.raw, gz: a.gz + r.gz, br: a.br + r.br }), { raw: 0, gz: 0, br: 0 });
  const js = rows.filter((r) => r.file.endsWith(".js")); const cssRows = rows.filter((r) => r.file.endsWith(".css"));
  const fl = sum(new Set([...firstLoad].filter((f) => f.endsWith(".js"))));
  const poly = sum(new Set(polyfill));
  const tot = (rs) => rs.reduce((a, r) => ({ raw: a.raw + r.raw, gz: a.gz + r.gz, br: a.br + r.br }), { raw: 0, gz: 0, br: 0 });
  console.log(`\n### ${dist}`);
  console.log(`First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw ${kb(fl.raw)} KB | gzip ${kb(fl.gz)} KB | brotli ${kb(fl.br)} KB`);
  console.log(`polyfill (nomodule, not loaded by modern browsers): raw ${kb(poly.raw)} KB | gzip ${kb(poly.gz)} KB`);
  const t = tot(js); const tc = tot(cssRows);
  console.log(`all JS on disk: ${js.length} files, raw ${kb(t.raw)} KB | gzip ${kb(t.gz)} KB | brotli ${kb(t.br)} KB     all CSS: ${cssRows.length} files raw ${kb(tc.raw)} KB | gzip ${kb(tc.gz)} KB`);
  console.log(`dynamically loaded (not in first load) JS: ${js.filter((r) => !firstLoad.has("static/" + r.file) && !polyfill.includes("static/" + r.file)).length} files`);
  console.log("| chunk | raw KB | gzip KB | brotli KB | in first load |\n|---|---:|---:|---:|---|");
  for (const r of rows.slice(0, 12)) console.log(`| ${r.file} | ${kb(r.raw)} | ${kb(r.gz)} | ${kb(r.br)} | ${firstLoad.has("static/" + r.file) ? "yes" : polyfill.includes("static/" + r.file) ? "nomodule" : r.file.endsWith(".css") ? "css" : "no"} |`);
  return { dist, firstLoad: fl, js: t, css: tc };
}

async function modules(entryFile) {
  const esbuild = createRequire(join(process.env.ESBUILD_DIR ?? "/tmp/esb", "package.json"))("esbuild");
  const alias = { "@": root, "@xweb/types": join(root, "packages/types/src/index.ts"), "@xweb/permissions": join(root, "packages/permissions/src/index.ts"), "@xweb/ui": join(root, "packages/ui/src/index.ts"),
    "@xweb/i18n": join(root, "packages/i18n/src/index.ts"), "@xweb/api-client": join(root, "packages/api-client/src/index.ts"), "@xweb/auth": join(root, "packages/auth/src/index.ts") };
  const r = await esbuild.build({ entryPoints: [resolve(root, entryFile)], bundle: true, write: false, metafile: true, minify: true, format: "esm", platform: "browser", jsx: "automatic", alias, external: ["next/*"],
    define: { "process.env.NODE_ENV": '"production"', "process.env": "{}" }, loader: { ".css": "empty" }, logLevel: "error", outdir: "/dev/null" });
  const out = Object.values(r.metafile.outputs)[0];
  const byPkg = new Map(); let total = 0;
  for (const [file, v] of Object.entries(out.inputs)) {
    let key; const m = file.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/);
    if (m) key = m[1]; else if (file.startsWith("packages/")) key = file.split("/").slice(0, 2).join("/"); else key = file.split("/").slice(0, 2).join("/");
    byPkg.set(key, (byPkg.get(key) ?? 0) + v.bytesInOutput); total += v.bytesInOutput;
  }
  const files = Object.entries(out.inputs).sort((a, b) => b[1].bytesInOutput - a[1].bytesInOutput).slice(0, 25);
  const bundle = r.outputFiles[0].contents;
  console.log(`\n### esbuild PROXY for ${entryFile} (minified, tree-shaken, next/* external; NOT the Next bundle) total ${kb(total)} KB min, gzip ${kb(gzipSync(bundle).length)} KB`);
  console.log("| package / dir | KB (minified, in output) | % |\n|---|---:|---:|");
  for (const [k, v] of [...byPkg.entries()].sort((a, b) => b[1] - a[1]).slice(0, 18)) console.log(`| ${k} | ${kb(v)} | ${((v / total) * 100).toFixed(1)} |`);
  console.log("\n| largest source files | KB |\n|---|---:|");
  for (const [f, v] of files) console.log(`| ${f} | ${kb(v.bytesInOutput)} |`);
}

const args = process.argv.slice(2);
if (args[0] === "--modules") for (const e of args.slice(1)) await modules(e);
else for (const d of args) reportDist(d);
