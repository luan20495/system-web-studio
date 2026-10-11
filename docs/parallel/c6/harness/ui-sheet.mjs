#!/usr/bin/env node
// C6 — contact sheet for MANUAL inspection: the same route at several viewports side by side (each scaled to HEIGHT px, top part only), one PNG per route.
// Usage: TOOLS=<dir with pngjs> node ui-sheet.mjs <evidenceDir> <portal/role/route_slug> [more…]   (e.g. platform/super/platform_ai)    env HEIGHT=720 VPS=1440,1024,768,430,390
import { createRequire } from "node:module"; import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs"; import { join } from "node:path";
const { PNG } = createRequire(process.env.TOOLS + "/package.json")("pngjs"); const [dir, ...rels] = process.argv.slice(2); const H = Number(process.env.HEIGHT ?? 720); const VPS = (process.env.VPS ?? "1440,1024,768,430,390").split(",");
mkdirSync(join(dir, "sheets"), { recursive: true });
for (const rel of rels) {
  const imgs = VPS.map((v) => join(dir, rel, v + ".png")).filter(existsSync).map((f) => PNG.sync.read(readFileSync(f))); if (!imgs.length) { console.log("no images for", rel); continue; }
  const tiles = imgs.map((im) => { const sc = Math.min(1, H / Math.min(im.height, 2400)) * (im.width > 900 ? 0.55 : 1); const w = Math.max(1, Math.round(im.width * sc)), h = Math.min(H, Math.round(im.height * sc)); const out = Buffer.alloc(w * h * 4, 255);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const sx0 = Math.floor(x / sc), sx1 = Math.max(sx0 + 1, Math.floor((x + 1) / sc)), sy0 = Math.floor(y / sc), sy1 = Math.max(sy0 + 1, Math.floor((y + 1) / sc)); let r = 0, g = 0, b = 0, n = 0; for (let yy = sy0; yy < sy1 && yy < im.height; yy++) for (let xx = sx0; xx < sx1 && xx < im.width; xx++) { const i = (yy * im.width + xx) * 4; r += im.data[i]; g += im.data[i + 1]; b += im.data[i + 2]; n++; } const o = (y * w + x) * 4; if (n) { out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n; out[o + 3] = 255; } } return { w, h, data: out }; });
  const gap = 10, W = tiles.reduce((a, t) => a + t.w + gap, gap), Hh = Math.max(...tiles.map((t) => t.h)) + 2 * gap; const sheet = new PNG({ width: W, height: Hh }); sheet.data.fill(120); let x0 = gap;
  for (const t of tiles) { for (let y = 0; y < t.h; y++) for (let x = 0; x < t.w; x++) { const i = (y * t.w + x) * 4, o = ((y + gap) * W + x0 + x) * 4; sheet.data[o] = t.data[i]; sheet.data[o + 1] = t.data[i + 1]; sheet.data[o + 2] = t.data[i + 2]; sheet.data[o + 3] = 255; } x0 += t.w + gap; }
  const out = join(dir, "sheets", rel.replace(/\//g, "__") + ".png"); writeFileSync(out, PNG.sync.write(sheet)); console.log(out, `${W}x${Hh}`);
}
