// @class: tooling (S4 audit). STATIC browser-feature scan: greps the C5-visible sources (CSS + TS/TSX + the preview/renderer templates) for features that need a recent engine and prints hits with the first location.
// It executes nothing in any browser. The minimum versions in the table are reference data (MDN / caniuse as known to the author) and must be re-checked before a support promise is made.
//   node scripts/compat-scan.mjs
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const root = resolve(new URL("..", import.meta.url).pathname);
const dirs = ["packages/ui/src", "packages/auth/src", "packages/api-client/src", "packages/company-ui/src", "packages/app-sdk/src", "packages/i18n/src", "packages/permissions/src", "features", "components", "lib", "workers/render", "apps/platform/app", "apps/admin/app", "apps/studio/app", "app"];
const files = [];
const walk = (d) => { let es; try { es = readdirSync(d); } catch { return; } for (const n of es) { if (n === "node_modules" || n.startsWith(".next")) continue; const p = join(d, n); const s = statSync(p); if (s.isDirectory()) walk(p); else if (/\.(css|ts|tsx|mjs)$/.test(n)) files.push(p); } };
for (const d of dirs) walk(join(root, d));
const text = new Map(files.map((f) => [f, readFileSync(f, "utf8")]));

// [name, kind, regex, chrome, safari, firefox]
const F = [
  [":has()", "css", /:has\(/, "105", "15.4", "121"], ["dvh / svh / lvh units", "css", /\d(dvh|svh|lvh)\b/, "108", "15.4", "101"], ["@container queries", "css", /@container/, "105", "16", "110"],
  ["color-mix()", "css", /color-mix\(/, "111", "16.2", "113"], ["subgrid", "css", /subgrid/, "117", "16", "71"], [":is() / :where()", "css", /:(is|where)\(/, "88", "14", "78"],
  [":focus-visible", "css", /:focus-visible/, "86", "15.4", "85"], [":focus-within", "css", /:focus-within/, "60", "10.1", "52"], ["scroll-padding / scroll-margin", "css", /scroll-(padding|margin)/, "69", "14.1", "68"],
  ["aspect-ratio", "css", /aspect-ratio/, "88", "15", "89"], ["inset shorthand", "css", /(^|[\s;{])inset:/, "87", "14.1", "66"], ["flex/grid gap", "css", /(^|[\s;{])gap:/, "84 (flex)", "14.1 (flex)", "63 (flex)"],
  ["backdrop-filter", "css", /backdrop-filter/, "76", "9 (-webkit- until 18)", "103"], ["min() / max() / clamp()", "css", /(\s|\(|:)(min|max|clamp)\(/, "79", "11.1", "75"], ["env(safe-area-inset-*)", "css", /env\(/, "69", "11.1", "65"],
  ["overscroll-behavior", "css", /overscroll-behavior/, "63", "16", "59"], ["accent-color", "css", /accent-color/, "93", "15.4", "92"], ["position: sticky", "css", /position:\s*sticky/, "56", "13", "32"],
  ["-webkit-line-clamp", "css", /line-clamp/, "6", "5", "68"], ["(pointer: coarse) / (hover: hover)", "css", /\((pointer|hover|any-pointer):/, "41", "9", "64"], ["logical properties (padding-inline...)", "css", /(padding|margin)-(inline|block)/, "87", "14.1", "66"],
  ["@layer", "css", /@layer/, "99", "15.4", "97"], ["CSS nesting (a line starting with &)", "css", /^\s*&[\s.:\[#]/, "112", "16.5", "117"], ["text-wrap: balance|pretty", "css", /text-wrap/, "114", "17.5", "121"],
  ["color-scheme", "css", /color-scheme/, "81", "13", "96"], ["prefers-color-scheme", "css", /prefers-color-scheme/, "76", "12.1", "67"], ["prefers-reduced-motion", "css", /prefers-reduced-motion/, "74", "10.1", "63"], ["forced-colors", "css", /forced-colors/, "89", "16", "89"],
  ["scrollbar-gutter / scrollbar-width", "css", /scrollbar-(gutter|width)/, "94 / 121", "18.2", "97 / 64"], ["content-visibility", "css", /content-visibility/, "85", "18", "125"], ["grid auto-fit / minmax()", "css", /(auto-fit|auto-fill|minmax\()/, "57", "10.1", "52"],
  ["translate / rotate / scale properties", "css", /(^|[\s;{])(translate|rotate|scale):/, "104", "14.1", "72"], ["light-dark()", "css", /light-dark\(/, "123", "17.5", "120"], ["@starting-style / anchor positioning", "css", /(@starting-style|anchor-name|position-anchor)/, "117 / 125", "17.5 / 26", "129 / no"],
  ["structuredClone", "js", /structuredClone\(/, "98", "15.4", "94"], ["ResizeObserver", "js", /ResizeObserver/, "64", "13.1", "69"], ["IntersectionObserver", "js", /IntersectionObserver/, "58", "12.1", "55"], ["MutationObserver", "js", /MutationObserver/, "26", "7", "14"],
  ["Intl.Collator / localeCompare(locale)", "js", /(Intl\.Collator|localeCompare\()/, "24 (ICU data: full-icu in Node, browsers ship it)", "10", "29"], ["Intl.RelativeTimeFormat / ListFormat / DateTimeFormat / NumberFormat", "js", /Intl\.(RelativeTimeFormat|ListFormat|DateTimeFormat|NumberFormat|PluralRules)/, "71 / 72", "14 / 14.1", "65 / 78"],
  ["Intl.Segmenter", "js", /Intl\.Segmenter/, "87", "14.1", "125"], ["String.normalize('NFD') + \\u0300 range (accent folding)", "js", /normalize\(["']NFD["']\)/, "34", "10", "31"],
  ["Array.prototype.at()", "js", /\.at\(-?\d/, "92", "15.4", "90"], ["Object.hasOwn", "js", /Object\.hasOwn\(/, "93", "15.4", "92"], ["Array findLast / findLastIndex", "js", /\.findLast(Index)?\(/, "97", "15.4", "104"],
  ["toSorted / toReversed / toSpliced / with()", "js", /\.(toSorted|toReversed|toSpliced)\(/, "110", "16", "115"], ["Object.groupBy / Map.groupBy", "js", /(Object|Map)\.groupBy\(/, "117", "17.4", "119"], ["String.replaceAll", "js", /\.replaceAll\(/, "85", "13.1", "77"],
  ["Object.fromEntries", "js", /Object\.fromEntries\(/, "73", "12.1", "63"], ["Array.flat / flatMap", "js", /\.(flat|flatMap)\(/, "69", "12", "62"], ["AbortSignal.timeout / any", "js", /AbortSignal\.(timeout|any)\(/, "103 / 116", "16 / 17.4", "100 / 124"],
  ["crypto.randomUUID (secure context only)", "js", /crypto\.randomUUID\(/, "92", "15.4", "95"], ["navigator.clipboard (secure context only)", "js", /navigator\.clipboard/, "66", "13.1", "63"], ["requestIdleCallback", "js", /requestIdleCallback/, "47", "NOT SUPPORTED (until 26)", "55"],
  ["<dialog> / showModal()", "js", /(showModal\(|<dialog)/, "37", "15.4", "98"], ["inert attribute / property", "js", /(\binert\b\s*[=:{]|\.inert\b)/, "102", "15.5", "112"], ["Promise.withResolvers", "js", /Promise\.withResolvers/, "119", "17.4", "121"],
  ["URL.canParse", "js", /URL\.canParse/, "120", "17", "115"], ["Set methods (union, intersection...)", "js", /\.(union|intersection|difference|isSubsetOf)\(/, "122", "17", "127"], ["Array.fromAsync", "js", /Array\.fromAsync/, "121", "16.4", "115"],
  ["window.visualViewport", "js", /visualViewport/, "61", "13", "91"], ["matchMedia", "js", /matchMedia\(/, "9", "5.1", "6"], ["CSS.escape", "js", /CSS\.escape\(/, "46", "10", "31"], ["IntersectionObserver v2 / ElementInternals / popover API", "js", /(\.showPopover\(|popover=|ElementInternals)/, "114", "17", "125"],
  ["RegExp lookbehind (?<= (?<!", "js", /\(\?<[=!]/, "62", "16.4", "78"], ["Event Timing / PerformanceObserver (only if used in product code)", "js", /PerformanceObserver/, "76", "no (event timing)", "no"],
  ["optional chaining ?. / nullish ??", "js", /\?\.[A-Za-z_(\[]|\?\?/, "80", "13.1", "74"], ["Array.prototype.includes / Object.entries", "js", /Object\.entries\(/, "54", "10.1", "47"],
  ["fetch + AbortController + ReadableStream body", "js", /(getReader\(|\.body\b.*stream|text\/event-stream|EventSource)/, "43 / 52", "10.1 / 11", "65 / 6"], ["IndexedDB / localStorage / sessionStorage", "js", /(localStorage|sessionStorage|indexedDB)/, "4", "4", "3.5"], ["BroadcastChannel / SharedWorker / Web Locks", "js", /(BroadcastChannel|SharedWorker|navigator\.locks)/, "54", "15.4", "38"],
];

const rows = [];
for (const [name, kind, re, c, s, ff] of F) {
  let hits = 0; let first = ""; const per = new Set();
  for (const [f, t] of text) {
    if (kind === "css" ? !f.endsWith(".css") && !/schema-preview|workers\/render|company-ui/.test(f) : f.endsWith(".css")) continue;
    const lines = t.split("\n");
    for (let i = 0; i < lines.length; i++) if (re.test(lines[i])) { hits++; per.add(relative(root, f)); if (!first) first = `${relative(root, f)}:${i + 1}`; }
  }
  rows.push({ name, kind, hits, first, files: per.size, c, s, ff });
}
console.log("STATIC scan (nothing executed in any browser). hits = matching lines; versions = first release with the feature (reference data, re-verify before promising support).");
console.log("| feature | kind | hit lines | files | first location | Chrome | Safari | Firefox |\n|---|---|---:|---:|---|---|---|---|");
for (const r of rows.filter((x) => x.hits > 0)) console.log(`| ${r.name} | ${r.kind} | ${r.hits} | ${r.files} | ${r.first} | ${r.c} | ${r.s} | ${r.ff} |`);
console.log(`\nChecked and NOT found in the scanned sources: ${rows.filter((x) => x.hits === 0).map((x) => x.name).join("; ")}`);
console.log(`\nscanned ${files.length} files in ${dirs.join(", ")}`);
