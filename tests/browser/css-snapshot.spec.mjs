// @class: harness — real Chromium, in-page harness pages, NO backend: HARNESS, NOT REAL BACKEND.
// CSS before/after tool (M-068 / M-069 / M-070): screenshots the shared screens at two widths and writes a pixel hash per screenshot.
//   capture:  SNAP_OUT=/path/before.json SHOT_DIR=/path/before/ node tests/browser/harness-server.mjs run -- node tests/browser/css-snapshot.spec.mjs
//   compare:  SNAP_BASE=/path/before.json SNAP_OUT=/path/after.json SHOT_DIR=/path/after/ [EXPECT_IDENTICAL=1] ...   (lists the screens that changed; exit 1 when EXPECT_IDENTICAL=1 and any did)
// A token-for-same-value change or dead-declaration removal must be pixel-identical (EXPECT_IDENTICAL=1); a deliberate visual change is reviewed from the two screenshot folders.
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { harnessOrigin, launch } from "./lib/spec.mjs";
const BASE = harnessOrigin() + "/";
const SHOTS = process.env.SHOT_DIR; if (SHOTS) mkdirSync(SHOTS, { recursive: true });
const PAGES = [
  ["admin-tenants", "admin.html?portal=platform&me=sys&start=/platform/tenants"], ["admin-users", "admin.html?portal=platform&me=sys&start=/platform/users"], ["admin-user", "admin.html?portal=platform&me=sys&start=/platform/users/u2"],
  ["admin-people", "admin.html?portal=admin&me=tadmin&start=/admin/people"], ["ai-providers", "ai.html?s=ok"], ["org", "org.html?v=org&s=ok"], ["employees", "org.html?v=emp&s=ok"],
  ["release-dialog", "release.html?s=ok"], ["datasources", "ds.html?s=ok"], ["builder", "index.html?v2=1"], ["studio-home", "studio.html"], ["widgets", "ui-widgets.html"], ["widgets-dark", "ui-widgets.html?dark"],
  ["nav", "ui-nav.html"], ["route-shell", "ui-route.html"], ["toast", "ui-toast.html"], ["admin-ds", "admin-ds.html"]
];
const SIZES = [["desktop", 1280, 900], ["phone", 390, 844]];
const browser = await launch({ headless: true });
const out = {};
for (const [name, path] of PAGES) for (const [sz, w, h] of SIZES) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, reducedMotion: "reduce" }); const p = await ctx.newPage(); p.setDefaultTimeout(4000);
  try {
    await p.goto(BASE + path); await p.waitForSelector("#root > *", { timeout: 6000 }).catch(() => undefined); await p.waitForTimeout(700);
    const buf = await p.screenshot({ animations: "disabled", caret: "hide" });
    out[`${name}@${sz}`] = createHash("sha256").update(buf).digest("hex").slice(0, 16);
    if (SHOTS) writeFileSync(join(SHOTS, `${name}@${sz}.png`), buf);
  } catch (e) { out[`${name}@${sz}`] = `ERROR ${String(e.message).slice(0, 60)}`; }
  await ctx.close();
}
await browser.close();
if (process.env.SNAP_OUT) writeFileSync(process.env.SNAP_OUT, JSON.stringify(out, null, 1));
const keys = Object.keys(out); const errs = keys.filter((k) => String(out[k]).startsWith("ERROR"));
console.log(`css-snapshot: ${keys.length} screenshots, ${errs.length} could not be taken  (HARNESS, NOT REAL BACKEND)`);
for (const k of errs) console.log(`  ERROR ${k}: ${out[k]}`);
let changed = [];
if (process.env.SNAP_BASE) { const base = JSON.parse(readFileSync(process.env.SNAP_BASE, "utf8")); changed = keys.filter((k) => base[k] !== out[k]); console.log(`compared with ${process.env.SNAP_BASE}: ${changed.length} changed`); for (const k of changed) console.log(`  CHANGED ${k}`); }
process.exit(errs.length || (process.env.EXPECT_IDENTICAL && changed.length) ? 1 : 0);
