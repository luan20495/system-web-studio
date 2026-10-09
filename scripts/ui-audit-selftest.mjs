#!/usr/bin/env node
// SELF-TEST of the audit detectors (scripts/audit/measure.mjs): a fixture page WITH each defect must be flagged, a clean control page must not. HARNESS (inline fixtures, no server, no backend).
// An audit that stays quiet proves nothing unless it is known to speak when something is wrong: run this before trusting a clean audit.
//   node scripts/ui-audit-selftest.mjs        (exit 1 when a detector misses its defect or flags the clean page)
import { withEnv } from "./perf-env.mjs";
import { MEASURE, focusProbe, axe } from "./audit/measure.mjs";
import { flag } from "./audit/engine.mjs";

const page = (body, css = "") => `<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>fixture</title><style>*{box-sizing:border-box}body{margin:0;font:16px system-ui}button,a{font:inherit}${css}</style></head><body>${body}</body></html>`;
const CLEAN = page(`<header><a href="#main" class="skip" style="position:absolute;left:-999px">Bỏ qua</a></header><main id="main"><h1>Trang sạch</h1><p>Nội dung ngắn.</p><label>Tên <input id="n" style="min-height:28px"></label><button style="min-height:32px;min-width:80px">Lưu</button><a href="#x" style="display:inline-block;min-height:32px;padding:4px">Liên kết</a></main>`,
  "button:focus-visible,a:focus-visible,input:focus-visible{outline:2px solid #05f;outline-offset:2px}.skip:focus{left:8px!important;top:8px}");
const CASES = [
  { name: "horizontal overflow", html: page(`<main><h1>T</h1><div style="width:2000px;height:20px;background:#ccc">rộng</div></main>`), expect: (m) => m.overflowX > 1 },
  { name: "control outside the viewport", html: page(`<main><h1>T</h1><button style="position:absolute;left:-400px;top:100px">Ẩn bên trái</button></main>`), expect: (m) => m.clippedCtl.some((x) => /offscreen|clipped/.test(x)) },
  { name: "control clipped by overflow:hidden", html: page(`<main><h1>T</h1><div style="width:100px;height:40px;overflow:hidden"><button style="margin-left:150px">Bị cắt</button></div></main>`), expect: (m) => m.clippedCtl.some((x) => /clipped/.test(x)) },
  { name: "control covered by another element", html: page(`<main><h1>T</h1><button style="position:absolute;left:20px;top:80px;width:120px;height:40px">Bị che</button><div style="position:absolute;left:0;top:70px;width:300px;height:80px;background:#fff"></div></main>`), expect: (m) => m.covered.length > 0 },
  { name: "NOT covered: a control under a sticky footer that scrolling it into view clears (M-128, dialog with scroll-padding)", html: page(`<main><h1>T</h1><div style="position:absolute;left:0;top:60px;width:300px;height:200px;overflow:auto;scroll-padding-bottom:70px"><div style="height:560px;position:relative"><button style="position:absolute;left:20px;top:150px;width:120px;height:40px">Nâng cao</button></div><div style="position:sticky;bottom:0;height:60px;background:#fff">Hủy Lưu</div></div></main>`), expect: (m) => m.covered.length === 0 },
  { name: "target smaller than 24 px", html: page(`<main><h1>T</h1><button style="width:16px;height:16px;padding:0">x</button></main>`), expect: (m) => m.small.length > 0 },
  { name: "visible label not in the accessible name", html: page(`<main><h1>T</h1><button aria-label="Hành động khác">Gửi đơn</button></main>`), expect: (m) => m.nameMismatch.length > 0 },
  { name: "icon-only button without a name", html: page(`<main><h1>T</h1><button style="width:32px;height:32px"><svg width="12" height="12"></svg></button></main>`), expect: (m) => m.noName.length > 0 },
  { name: "field without a label", html: page(`<main><h1>T</h1><input style="height:32px"></main>`), expect: (m) => m.noLabel.length > 0 },
  { name: "text clipped without an ellipsis", html: page(`<main><h1>T</h1><div style="width:60px;overflow:hidden;white-space:nowrap">Một chuỗi rất dài bị cắt cụt</div></main>`), expect: (m) => m.clipped.length > 0 },
  { name: "mojibake text", html: page(`<main><h1>T</h1><p>Ã¡ â€” áº¡</p></main>`), expect: (m) => m.mojibake },
];
const KEY_CASES = [
  { name: "no focus indicator", html: page(`<main><h1>T</h1><button style="outline:none;min-height:32px">A</button><button style="outline:none;min-height:32px">B</button></main>`), expect: (f) => f.noIndicator.length >= 2 },
  { name: "sticky header covers the focused element (WCAG 2.4.11)", html: page(`<header style="position:fixed;top:0;left:0;right:0;height:3000px;background:#fff;z-index:9"></header><main><button style="min-height:32px;position:relative;top:50px">Bị che bởi header</button></main>`, "button:focus-visible{outline:2px solid #05f}"), expect: (f) => f.obscuredFully.length > 0 },
  { name: "focus indicator carried by a wrapper (:focus-within) counts", html: page(`<main><div class="w" style="display:inline-block;padding:4px"><input aria-label="Mã" style="outline:none"></div></main>`, ".w:focus-within{outline:2px solid #05f}"), expect: (f) => f.noIndicator.length === 0 && f.stops >= 1 },
];

let fail = 0; const out = [];
const record = (name, ok, detail = "") => { out.push(`| ${name} | ${ok ? "PASS" : "FAIL"} | ${detail} |`); if (!ok) fail++; };
await withEnv({ dir: null, tag: "audself" }, async ({ browser, chromeVersion }) => {
  const ctx = await browser.newContext({ viewport: { width: 800, height: 600 } }); const p = await ctx.newPage();
  await p.setContent(CLEAN); const clean = await p.evaluate(MEASURE); const cleanFocus = await focusProbe(p, 6); const cleanAxe = await axe(p);
  const cleanFlag = flag({ ...clean, focus: cleanFocus, axe: cleanAxe, errs: [], bad: [] });
  record("control: the CLEAN page raises no finding", cleanFlag === "", cleanFlag || "no finding");
  for (const c of CASES) { await p.setContent(c.html); const m = await p.evaluate(MEASURE); const ok = !!c.expect(m); record(`detects: ${c.name}`, ok, ok ? flag({ ...m, errs: [], bad: [] }).slice(0, 80) : "NOT detected: " + JSON.stringify({ o: m.overflowX, c: m.clippedCtl, v: m.covered, s: m.small, n: m.nameMismatch }).slice(0, 120)); }
  for (const c of KEY_CASES) { await p.setContent(c.html); const f = await focusProbe(p, 4); const ok = !!c.expect(f); record(`keyboard probe: ${c.name}`, ok, JSON.stringify({ stops: f.stops, noInd: f.noIndicator.length, obs: f.obscuredFully.length }).slice(0, 100)); }
  await p.setContent(page(`<main><button style="min-height:32px;outline:none"><span style="display:none">x</span></button></main>`)); const ax = await axe(p);
  record("axe wiring: the engine returns a list (violations are listed with impact)", Array.isArray(ax), `${ax.length} violations on a minimal page`);
  await ctx.close();
  console.log(`HARNESS (inline fixtures) · Chrome ${chromeVersion}\n\n| check | result | detail |\n|---|---|---|\n${out.join("\n")}\n\n${out.length - fail}/${out.length} detectors behave${fail ? ` · ${fail} FAIL` : ""}`);
});
process.exit(fail ? 1 : 0);
