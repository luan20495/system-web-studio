// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * M-071 phase 0 / M-103: locale constants, locale-aware formatters with IDENTICAL output for `vi`, the provider, `<html lang dir>` from the locale constant, and the RTL-readiness
 * ratchet (physical CSS properties may not grow). The old helpers of packages/ui/src/ui.tsx are kept below as the ORACLE.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { DEFAULT_LOCALE, LOCALE_META, SUPPORTED_LOCALES, dirOf, htmlAttrs, isLocale, langOf } from "../../packages/i18n/src/locale";
import { activeLocale, createFormatters, getFormatters, setActiveLocale } from "../../packages/i18n/src/format";
import { I18nProvider, useFormat, useI18n, useLocale } from "../../packages/i18n/src/provider";

// ---- the pre-migration implementations (packages/ui/src/ui.tsx before wave 2), verbatim
const oFmtDate = (iso?: string | null) => (iso ? new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso)) : "—");
function oAgo(iso?: string | null, now = Date.now()): string {
  if (!iso) return "—";
  const s = Math.round((now - new Date(iso).getTime()) / 1000);
  if (s < 60) return "vừa xong"; if (s < 3600) return `${Math.floor(s / 60)} phút trước`; if (s < 86400) return `${Math.floor(s / 3600)} giờ trước`;
  if (s < 86400 * 30) return `${Math.floor(s / 86400)} ngày trước`; return oFmtDate(iso);
}
const oNum = (n?: number | null) => (n ?? 0).toLocaleString("vi-VN");
const oUsd = (n?: number | null) => (n == null ? "—" : n === 0 ? "$0" : `$${n < 0.01 ? n.toPrecision(2) : n.toLocaleString("en-US", { maximumFractionDigits: 4 })}`);
const oTok = (n?: number | null) => (n == null ? "—" : n.toLocaleString("vi-VN"));

test("locale constants: only vi, ltr, the vi-VN tags; usd keeps the en-US grouping on purpose", () => {
  assert.deepEqual([...SUPPORTED_LOCALES], ["vi"]); assert.equal(DEFAULT_LOCALE, "vi");
  assert.equal(langOf(), "vi"); assert.equal(dirOf(), "ltr"); assert.deepEqual(htmlAttrs(), { lang: "vi", dir: "ltr" });
  assert.deepEqual(LOCALE_META.vi.intl, { date: "vi-VN", number: "vi-VN", currency: "en-US", collator: "vi" });
  assert.equal(isLocale("vi"), true); assert.equal(isLocale("en"), false); assert.equal(isLocale(undefined), false);
});

test("vi formatters: output is IDENTICAL to the pre-migration helpers (dates, relative time, numbers, USD, tokens, nulls)", () => {
  const f = createFormatters("vi"); const now = Date.UTC(2026, 9, 9, 9, 0, 0);
  const isos = [null, undefined, "", "2026-10-09T08:59:30Z", "2026-10-09T08:15:00Z", "2026-10-09T03:00:00Z", "2026-10-02T09:00:00Z", "2026-08-01T09:00:00Z", "2020-01-01T00:00:00Z"];
  for (const iso of isos) { assert.equal(f.fmtDate(iso), oFmtDate(iso), `fmtDate ${iso}`); assert.equal(f.ago(iso, now), oAgo(iso, now), `ago ${iso}`); }
  const nums = [null, undefined, 0, 1, -5, 999, 1000, 1234.5, 1234567.891, 0.004, 0.0099, 0.01, 0.5, 12.3456789, 1e9, NaN];
  for (const n of nums) { assert.equal(f.num(n), oNum(n), `num ${n}`); assert.equal(f.usd(n), oUsd(n), `usd ${n}`); assert.equal(f.tok(n), oTok(n), `tok ${n}`); }
  assert.equal(f.usd(null), "—"); assert.equal(f.usd(0), "$0"); assert.equal(f.usd(1234.5), "$1,234.5"); assert.equal(f.num(1234.5), oNum(1234.5)); assert.equal(f.tok(null), "—");
});

test("formatters are created ONCE per locale: 1000 cells build no new Intl.DateTimeFormat / NumberFormat (it was one per cell)", () => {
  const F = getFormatters("vi"); assert.equal(getFormatters("vi"), F, "cached");
  const D = Intl.DateTimeFormat, N = Intl.NumberFormat; let made = 0;
  (Intl as { DateTimeFormat: unknown }).DateTimeFormat = function (...a: ConstructorParameters<typeof Intl.DateTimeFormat>) { made++; return new D(...a); };
  (Intl as { NumberFormat: unknown }).NumberFormat = function (...a: ConstructorParameters<typeof Intl.NumberFormat>) { made++; return new N(...a); };
  try { for (let i = 0; i < 1000; i++) { F.fmtDate("2026-10-09T08:00:00Z"); F.num(i); F.usd(i / 7); F.tok(i); F.ago("2026-10-01T00:00:00Z"); } }
  finally { (Intl as { DateTimeFormat: unknown }).DateTimeFormat = D; (Intl as { NumberFormat: unknown }).NumberFormat = N; }
  assert.equal(made, 0);
});

test("I18nProvider: default value outside a provider; inside, locale / lang / dir / formatters, and the active locale of the free functions follows", () => {
  function Probe() { const i = useI18n(); const l = useLocale(); const f = useFormat(); return <p data-x={`${i.lang}|${i.dir}|${l}|${f.num(1234.5)}`}/>; }
  assert.equal(renderToStaticMarkup(<Probe/>), `<p data-x="vi|ltr|vi|${oNum(1234.5)}"></p>`);
  assert.equal(renderToStaticMarkup(<I18nProvider locale="vi"><Probe/></I18nProvider>), `<p data-x="vi|ltr|vi|${oNum(1234.5)}"></p>`);
  assert.equal(activeLocale(), "vi"); setActiveLocale("vi");
});

test("every `<html>` takes lang and dir from the locale constant (no literal lang=\"vi\"); the three portals wrap the tree in <I18nProvider>", () => {
  const root = process.cwd();
  for (const f of ["apps/admin/app/layout.tsx", "apps/platform/app/layout.tsx", "apps/studio/app/layout.tsx", "app/layout.tsx"]) {
    const t = readFileSync(join(root, f), "utf8");
    assert.doesNotMatch(t, /<html lang=/, `${f} hard-codes lang`); assert.match(t, /<html \{\.\.\.htmlAttrs\(\)\}>/, f);
    if (f.startsWith("apps/")) assert.match(t, /<I18nProvider>(<SessionProvider>)?\{children\}(<\/SessionProvider>)?<\/I18nProvider>/, f);   // the session provider may sit inside it (FQ-PERF-01: the session lives in the layout)
  }
});

// ---------------------------------------------------------------------------------------------------------------------------------------------- RTL readiness (M-103)
const dir = join(process.cwd(), "packages");
const cssFiles: string[] = [];
(function walk(d: string) { for (const e of readdirSync(d, { withFileTypes: true })) { if (e.name === "node_modules") continue; const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (e.name.endsWith(".css")) cssFiles.push(p); } })(dir);
const css = cssFiles.map((f) => readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "")).join("\n");
const count = (re: RegExp) => (css.match(re) ?? []).length;
const PHYSICAL: Record<string, [RegExp, number]> = {
  "margin-left/right": [/\bmargin-(?:left|right)\s*:/g, 11], "padding-left/right": [/\bpadding-(?:left|right)\s*:/g, 11], "border-left/right": [/\bborder-(?:left|right)(?:-[a-z]+)?\s*:/g, 30],
  "left/right offset": [/(?:^|[;{\s])(?:left|right)\s*:/g, 19], "text-align left/right": [/\btext-align\s*:\s*(?:left|right)\b/g, 21], "float left/right": [/\bfloat\s*:\s*(?:left|right)\b/g, 0],
  "translateX": [/translateX\(/g, 4], "background-position x": [/\bbackground-position\s*:\s*(?:left|right)/g, 1]
};
test("RTL ratchet: direction-sensitive CSS declarations may not grow; new CSS uses the logical properties (margin-inline-start, padding-inline, inset-inline, border-inline-*, text-align:start|end)", () => {
  let total = 0; const lines: string[] = [];
  for (const [k, [re, max]] of Object.entries(PHYSICAL)) { const n = count(re); total += n; lines.push(`${k}=${n}`); if (n > max) assert.fail(`${k}: ${n} > baseline ${max}`); }
  const TOTAL_BASELINE = 97;
  if (total > TOTAL_BASELINE) assert.fail(`physical declarations ${total} > ${TOTAL_BASELINE} (${lines.join(", ")})`);
  console.log(`physical CSS declarations: ${total} (${lines.join(", ")})`);
});
test("RTL ratchet: inline physical style props in TSX (paddingLeft / marginLeft ...) may not grow", () => {
  let n = 0; const hits: string[] = [];
  const walk = (d: string) => { for (const e of readdirSync(d, { withFileTypes: true })) { if (["node_modules", ".next", ".test-build", "tests"].includes(e.name) || e.name.startsWith(".")) continue; const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.tsx$/.test(e.name)) { const t = readFileSync(p, "utf8"); for (const m of t.matchAll(/\b(?:padding|margin)(?:Left|Right)\s*:/g)) { n++; hits.push(p.replace(process.cwd() + "/", "")); void m; } } } };
  for (const d of ["features", "apps", "packages"]) walk(join(process.cwd(), d));
  const BASELINE = 5;
  assert.ok(n <= BASELINE, `inline physical props ${n} > ${BASELINE}: ${hits.join(", ")}`); console.log(`inline physical props: ${n}`);
});
