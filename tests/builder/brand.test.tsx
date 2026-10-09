// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * XWEB brand system (C5-S3, docs/BRAND_GUIDELINE.md): one token foundation, one logo family.
 *  - every semantic colour pair the brief names passes WCAG in light AND dark (packages/ui/brand/contrast.mjs, the measured numbers);
 *  - the legacy tokens (--f-*, --bg/--panel/--dk-*, --sp-*, --f-r*) stay value-identical to their semantic twin (no second, drifting system);
 *  - the React logo (Brand.tsx) draws exactly the geometry of the canonical SVG files; the per-app favicons are byte copies of the canonical exports;
 *  - assets stay small; no web font; the three portal shells and the auth page use the shared lockup / background.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { BrandLockup, BrandLogo, BrandMark, MARK_PATHS, MARK_SMALL_PATHS, WORDMARK_PATHS } from "../../packages/ui/src/Brand";
import { Banner } from "../../packages/ui/src/Banner";
import { BRAND } from "../../packages/i18n/src/brand";

const root = process.cwd();
const read = (p: string) => readFileSync(join(root, p), "utf8");
const BRAND_DIR = "packages/ui/brand";
const factory = read("packages/ui/src/styles/factory.css").replace(/\/\*[\s\S]*?\*\//g, "");

test("contrast: every named pair passes in light and dark (measured by packages/ui/brand/contrast.mjs from the shipped tokens)", () => {
  let out = "";
  try { out = execFileSync(process.execPath, [join(root, BRAND_DIR, "contrast.mjs"), "--json"], { encoding: "utf8" }); } catch (e) { out = String((e as { stdout?: string }).stdout ?? ""); }
  const rows = JSON.parse(out) as { theme: string; label: string; ratio: number; min: number; pass: boolean }[];
  assert.ok(rows.length >= 50, `pairs measured: ${rows.length}`);
  for (const theme of ["light", "dark"]) assert.ok(rows.filter((r) => r.theme === theme).length >= 25, theme);
  assert.deepEqual(rows.filter((r) => !r.pass).map((r) => `${r.theme} ${r.label} ${r.ratio} < ${r.min}`), []);
});

// ---------------------------------------------------------------------------------------------------------------- one foundation
const firstDefs = (text: string) => { const m = new Map<string, string>(); for (const x of text.matchAll(/(--[\w-]+)\s*:\s*([^;}]+)/g)) if (!m.has(x[1])) m.set(x[1], x[2].trim().toLowerCase()); return m; };
const lightSemantic = firstDefs(factory.slice(0, factory.indexOf(".studio,.modal,.drawer,.sidebar.dark,[data-theme=dark]{")));
const darkBlock = /\.studio,\.modal,\.drawer,\.sidebar\.dark,\[data-theme=dark\]\{([^}]*)\}/.exec(factory);
const darkSemantic = firstDefs(darkBlock ? darkBlock[1] : "");
const legacy = new Map([...firstDefs(read("packages/ui/src/styles/globals.css")), ...firstDefs(factory), ...firstDefs(read("packages/ui/src/styles/ui.css").replace(/\/\*[\s\S]*?\*\//g, ""))].reverse());

test("the semantic layer exists in both themes and covers the brief's categories", () => {
  assert.ok(darkBlock, "dark semantic scope");
  for (const n of ["primary", "primary-hover", "primary-active", "secondary", "accent", "bg", "bg-subtle", "surface", "surface-raised", "border", "border-strong", "text", "text-secondary", "text-muted", "text-inverse",
    "success", "warning", "danger", "info", "focus", "selected", "hover", "disabled-bg", "disabled-ink"]) {
    assert.ok(lightSemantic.has(`--color-${n}`), `light --color-${n}`); assert.ok(darkSemantic.has(`--color-${n}`), `dark --color-${n}`);
    if (!["success", "warning", "danger", "info", "text-inverse", "primary-ink"].includes(n)) assert.notEqual(lightSemantic.get(`--color-${n}`), darkSemantic.get(`--color-${n}`), `dark --color-${n} is designed, not copied`);
  }
  for (const n of ["--font-sans", "--font-mono", "--text-h1", "--text-h2", "--text-h3", "--text-h4", "--text-body-lg", "--text-body", "--text-body-sm", "--text-label", "--text-caption", "--text-code",
    "--space-0", "--space-1", "--space-2", "--space-3", "--space-4", "--space-5", "--space-6", "--space-8", "--space-10", "--space-12", "--space-16", "--radius-sm", "--radius-md", "--radius-lg", "--radius-xl", "--radius-pill",
    "--shadow-sm", "--shadow-md", "--shadow-lg", "--motion-fast", "--motion-base"]) assert.ok(lightSemantic.has(n), n);
  assert.deepEqual(["--space-0", "--space-1", "--space-2", "--space-3", "--space-4", "--space-5", "--space-6", "--space-8", "--space-10", "--space-12", "--space-16"].map((n) => lightSemantic.get(n)), ["2px", "4px", "8px", "12px", "16px", "20px", "24px", "32px", "40px", "48px", "64px"]);
});

test("legacy tokens are value-identical to their semantic twin (light --f-*, dark --bg/--panel/--dk-*, spacing, radius): one system, two spellings", () => {
  const L: [string, string][] = [["--f-accent", "primary"], ["--f-accent-ink", "primary-ink"], ["--f-accent-soft", "primary-soft"], ["--f-bg", "bg"], ["--f-panel", "surface"], ["--f-line", "border"], ["--f-line2", "bg-subtle"],
    ["--f-text", "text"], ["--f-muted", "text-muted"], ["--f-ok", "success"], ["--f-ok-bg", "success-bg"], ["--f-warn", "warning"], ["--f-warn-bg", "warning-bg"], ["--f-bad", "danger"], ["--f-bad-bg", "danger-bg"],
    ["--f-info", "info"], ["--f-info-bg", "info-bg"], ["--f-focus", "focus"], ["--f-control-border", "border-strong"], ["--f-disabled-bg", "disabled-bg"], ["--f-disabled-ink", "disabled-ink"],
    ["--f-accent-tint", "selected"], ["--f-accent-line", "selected-line"], ["--f-accent-chip", "glow"], ["--ui-inverse-bg", "text"]];
  for (const [old, sem] of L) assert.equal(legacy.get(old), lightSemantic.get(`--color-${sem}`), `${old} = --color-${sem}`);
  const D: [string, string][] = [["--bg", "bg"], ["--panel", "surface"], ["--panel2", "surface-raised"], ["--line", "border"], ["--text", "text"], ["--muted", "text-muted"], ["--muted-strong", "text-secondary"],
    ["--dk-link", "link"], ["--dk-error-ink", "danger"], ["--dk-chrome", "hover"], ["--dk-select-line", "selected-line"], ["--ui-ring", "focus"], ["--f-side-active", "selected"]];
  for (const [old, sem] of D) assert.equal(legacy.get(old), darkSemantic.get(`--color-${sem}`), `${old} = dark --color-${sem}`);
  for (const [old, sem] of [["--sp-1", "--space-1"], ["--sp-2", "--space-2"], ["--sp-3", "--space-3"], ["--sp-4", "--space-4"], ["--sp-5", "--space-5"], ["--sp-6", "--space-6"], ["--sp-8", "--space-8"],
    ["--f-r-xs", "--radius-sm"], ["--f-r-sm", "--radius-md"], ["--f-r", "--radius-lg"], ["--f-r-pill", "--radius-pill"], ["--f-shadow", "--shadow-sm"]]) assert.equal(legacy.get(old), lightSemantic.get(sem), `${old} = ${sem}`);
});

test("no generic AI-purple left: the old indigo accent literals are gone from the shared stylesheets", () => {
  const dir = join(root, "packages/ui/src/styles");
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".css"))) {
    const t = readFileSync(join(dir, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    assert.doesNotMatch(t, /#(4f46e5|4338ca|3730a3|6366f1|262a5c|eef0ff|c7d2fe|e0e7ff|a5a8f0|f5f3ff|e0e3ff)\b/i, f);
  }
});

test("typography: system fonts only, no web-font request anywhere in the shared stylesheets or the app layouts", () => {
  const dir = join(root, "packages/ui/src/styles");
  for (const f of readdirSync(dir)) assert.doesNotMatch(readFileSync(join(dir, f), "utf8"), /@font-face|@import\s+url|fonts\.googleapis|fonts\.gstatic/, f);
  for (const app of ["platform", "admin", "studio"]) assert.doesNotMatch(read(`apps/${app}/app/layout.tsx`), /next\/font|fonts\.googleapis/, app);
  assert.match(lightSemantic.get("--font-sans") ?? "", /system-ui/);
});

// ---------------------------------------------------------------------------------------------------------------- logo family
const svgPaths = (file: string) => Array.from(read(join(BRAND_DIR, file)).matchAll(/ d="([^"]+)"/g), (m) => m[1]);
test("logo: Brand.tsx draws the geometry of the canonical SVGs (mark, small mark, wordmark) and every family member exists", () => {
  for (const f of ["xweb-mark.svg", "xweb-mark-small.svg", "xweb-mark-mono.svg", "xweb-logo-light.svg", "xweb-logo-dark.svg", "xweb-logo-mono.svg"]) assert.match(read(join(BRAND_DIR, f)), /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 (32 32|136 32)"[^>]* role="img" aria-label="Xweb">/, f);
  assert.deepEqual(svgPaths("xweb-mark.svg"), [MARK_PATHS.left, MARK_PATHS.right]);
  assert.deepEqual(svgPaths("xweb-mark-small.svg"), [MARK_SMALL_PATHS.left, MARK_SMALL_PATHS.right]);
  assert.deepEqual(svgPaths("xweb-mark-mono.svg"), [MARK_PATHS.left, MARK_PATHS.right]);
  for (const f of ["xweb-logo-light.svg", "xweb-logo-dark.svg", "xweb-logo-mono.svg"]) assert.deepEqual(svgPaths(f), [MARK_PATHS.left, MARK_PATHS.right, ...WORDMARK_PATHS], f);
  assert.match(read(join(BRAND_DIR, "xweb-logo-light.svg")), /fill="#1d5bd8"/); assert.match(read(join(BRAND_DIR, "xweb-logo-dark.svg")), /fill="#2a66e0"/);
  assert.match(read(join(BRAND_DIR, "xweb-logo-mono.svg")), /currentColor/); assert.doesNotMatch(read(join(BRAND_DIR, "xweb-logo-mono.svg")), /#1d5bd8|#9ae6df/);
  assert.equal(lightSemantic.get("--color-brand-tile"), "#1d5bd8"); assert.equal(darkSemantic.get("--color-brand-tile"), "#2a66e0");
});

test("assets: favicons of the three apps are byte copies of the canonical exports; every asset is small (SVG < 2 KB, PNG < 12 KB)", () => {
  for (const app of ["platform", "admin", "studio"]) {
    assert.equal(read(`apps/${app}/app/icon.svg`), read(join(BRAND_DIR, "xweb-mark-small.svg")), `${app} icon.svg`);
    assert.ok(readFileSync(join(root, `apps/${app}/app/apple-icon.png`)).equals(readFileSync(join(root, BRAND_DIR, "png/apple-touch-icon-180.png"))), `${app} apple-icon.png`);
  }
  for (const f of readdirSync(join(root, BRAND_DIR)).filter((x) => x.endsWith(".svg"))) assert.ok(statSync(join(root, BRAND_DIR, f)).size < 2048, f);
  const pngs = readdirSync(join(root, BRAND_DIR, "png")).filter((x) => x.endsWith(".png"));
  for (const n of ["xweb-mark-16.png", "xweb-mark-24.png", "xweb-mark-32.png", "xweb-mark-48.png", "xweb-mark-192.png", "xweb-mark-512.png", "apple-touch-icon-180.png", "xweb-logo-light@2x.png", "xweb-logo-dark@2x.png"]) assert.ok(pngs.includes(n), n);
  for (const f of pngs) assert.ok(statSync(join(root, BRAND_DIR, "png", f)).size < 12 * 1024, f);
});

test("BrandMark / BrandLogo / BrandLockup render: the logo is ONE image named 'Xweb', the mark is decorative unless titled, colours come from tokens (no light-only fill)", () => {
  const logo = renderToStaticMarkup(<BrandLogo/>);
  assert.match(logo, /^<svg class="xp-brandLogo" viewBox="0 0 136 32" height="24" width="102" role="img" aria-label="Xweb"/);
  assert.doesNotMatch(logo, /fill="#|stroke="#/, "no hard-coded colour on the coloured logo");
  assert.match(logo, /class="xp-brandTile"/); assert.match(logo, /class="xp-brandInk"/);
  assert.match(renderToStaticMarkup(<BrandMark/>), /aria-hidden="true"/);
  assert.match(renderToStaticMarkup(<BrandMark size={48} title="Xweb"/>), /role="img" aria-label="Xweb"/);
  assert.ok(renderToStaticMarkup(<BrandMark size={16}/>).includes(MARK_SMALL_PATHS.left), "16 px uses the small geometry");
  assert.ok(renderToStaticMarkup(<BrandMark size={32}/>).includes(MARK_PATHS.left), "32 px uses the full geometry");
  const mono = renderToStaticMarkup(<><BrandMark mono/><BrandMark mono/></>);
  const ids = Array.from(mono.matchAll(/<mask id="([^"]+)"/g), (m) => m[1]); assert.equal(ids.length, 2); assert.notEqual(ids[0], ids[1], "two mono marks on a page do not share a mask id");
  for (const p of ["platform", "admin", "studio"] as const) {
    const l = renderToStaticMarkup(<BrandLockup portal={p}/>);
    assert.match(l, new RegExp(`data-portal="${p}"`)); assert.ok(l.includes(`>${BRAND.context[p]}</small>`)); assert.match(l, /aria-label="Xweb"/);
  }
});

test("Banner: a labelled section, heading level 2 by default, decorative media hidden from assistive technology, three compositions on the shared backgrounds", () => {
  const b = renderToStaticMarkup(<Banner title="Tiêu đề" eyebrow="Nhãn" actions={<button>Đi</button>}>Nội dung</Banner>);
  assert.match(b, /^<section class="xp-banner xp-banner-intro xp-bg-hero" aria-labelledby="([^"]+)" data-variant="intro"><div class="xp-bannerText"><p class="xp-bannerEyebrow">Nhãn<\/p><h2 class="xp-bannerTitle" id="\1">Tiêu đề<\/h2>/);
  assert.match(b, /<div class="xp-bannerMedia" aria-hidden="true">/);
  assert.match(renderToStaticMarkup(<Banner variant="docs" title="x" level={3}/>), /xp-bg-pattern[\s\S]*<h3/);
  assert.match(renderToStaticMarkup(<Banner variant="onboarding" title="x"/>), /xp-bg-auth/);
  const ui = read("packages/ui/src/styles/ui.css");
  for (const c of [".xp-bg-auth{", ".xp-bg-dashboard{", ".xp-bg-hero{", ".xp-bg-pattern{", ".xp-banner{", ".xp-bannerText{", ".xp-bannerMedia{"]) assert.ok(ui.includes(c), c);
  assert.doesNotMatch(ui, /url\(/, "backgrounds are CSS gradients: no raster, no request");
});

test("portal shells: Platform / Admin sidebar, Studio sidebar and the auth card show the XWEB logo (one family); the auth page sits on the shared auth background", () => {
  assert.match(read("features/admin/console/chrome.tsx"), /<div className="sideBrand"><BrandLockup portal=\{portal === "platform" \? "platform" : "admin"\}\/><\/div>/);
  assert.match(read("features/studio/StudioApp.tsx"), /<div className="sideBrand"><BrandLockup portal="studio"\/><\/div>/);
  const auth = read("packages/auth/src/AuthPages.tsx");
  assert.match(auth, /<main className="authPage xp-bg-auth">/); assert.match(auth, /<div className="authBrand"><BrandLogo height=\{28\}\/><\/div>/);
  for (const f of ["features/admin/console/chrome.tsx", "features/studio/StudioApp.tsx", "packages/auth/src/AuthPages.tsx"]) assert.doesNotMatch(read(f), /logoMark|<Diamond/, f);
});
