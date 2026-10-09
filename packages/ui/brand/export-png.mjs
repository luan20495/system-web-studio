#!/usr/bin/env node
// XWEB brand: PNG exports are DERIVED from the canonical SVGs in this folder (never edit a PNG by hand).
//   node packages/ui/brand/export-png.mjs            (needs Chrome: CHROME=/path/to/chrome, default the macOS install)
// Writes packages/ui/brand/png/*.png and the per-app favicon copies (apps/<app>/app/icon.svg + apple-icon.png). Re-run after an SVG edit;
// tests/builder/brand.test.ts fails when a copy differs from its source.
import { copyFileSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";

const HERE = new URL(".", import.meta.url).pathname;
const ROOT = join(HERE, "../../..");
const require = createRequire(join(ROOT, "package.json"));
const { chromium } = require("playwright-core");
const CHROME = process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

const svg = (f) => readFileSync(join(HERE, f), "utf8");
/** [output, source svg, width, height, page background (null = transparent)] */
const JOBS = [
  ["xweb-mark-16.png", "xweb-mark-small.svg", 16, 16, null],
  ["xweb-mark-24.png", "xweb-mark-small.svg", 24, 24, null],
  ["xweb-mark-32.png", "xweb-mark.svg", 32, 32, null],
  ["xweb-mark-48.png", "xweb-mark.svg", 48, 48, null],
  ["xweb-mark-192.png", "xweb-mark.svg", 192, 192, null],
  ["xweb-mark-512.png", "xweb-mark.svg", 512, 512, null],
  ["apple-touch-icon-180.png", "xweb-mark.svg", 180, 180, "#1d5bd8"],   // iOS masks the corners itself: full-bleed tile, no transparent corners
  ["xweb-logo-light@2x.png", "xweb-logo-light.svg", 272, 64, null],
  ["xweb-logo-dark@2x.png", "xweb-logo-dark.svg", 272, 64, null],
];

const browser = await chromium.launch({ executablePath: CHROME });
try {
  for (const [out, src, w, h, bg] of JOBS) {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    const body = svg(src).replace(/width="\d+" height="\d+"/, `width="${w}" height="${h}"`);
    await page.setContent(`<!doctype html><html><body style="margin:0;background:${bg ?? "transparent"}">${body}</body></html>`);
    await page.screenshot({ path: join(HERE, "png", out), omitBackground: !bg, clip: { x: 0, y: 0, width: w, height: h } });
    await page.close();
  }
} finally { await browser.close(); }

for (const app of ["platform", "admin", "studio"]) {
  copyFileSync(join(HERE, "xweb-mark-small.svg"), join(ROOT, "apps", app, "app", "icon.svg"));
  copyFileSync(join(HERE, "png", "apple-touch-icon-180.png"), join(ROOT, "apps", app, "app", "apple-icon.png"));
}
const sizes = JOBS.map(([out]) => `${out} ${statSync(join(HERE, "png", out)).size} B`);
writeFileSync(join(HERE, "png", "SIZES.txt"), sizes.join("\n") + "\n");
console.log(sizes.join("\n"));
