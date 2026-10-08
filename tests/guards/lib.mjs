// @class: unit
// Shared helpers of the C0 contract-independent guards (D-C0-44). Node only, no dependency.
// A guard is a function (root) -> Finding[]; every guard also has a CLI (exit 0 = clean, 1 = findings) and is exercised against deliberately broken fixtures by guards.test.mjs.
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative, sep } from "node:path";

export const REPO = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
export const SKIP_DIRS = new Set(["node_modules", ".git", ".run", ".test-build", "build", "dist", "coverage", "out"]);

/** all files under `dir` (repo-relative, forward slashes), skipping build output; `.next*` directories are skipped */
export function walk(root, dir = root, acc = []) {
  let names; try { names = readdirSync(dir); } catch { return acc; }
  for (const n of names) {
    if (SKIP_DIRS.has(n) || n.startsWith(".next")) continue;
    const p = join(dir, n); let s; try { s = statSync(p); } catch { continue; }
    if (s.isDirectory()) walk(root, p, acc); else acc.push(relative(root, p).split(sep).join("/"));
  }
  return acc;
}

export const read = (root, rel) => readFileSync(join(root, rel), "utf8");

/** is this a TEST / FIXTURE / DOC path (never production code)? */
export const isNonProduction = (rel) => /(^|\/)(tests?|__tests__|e2e|e2e-real|fixtures?|docs?|\.test-build)(\/|$)/.test(rel) || /\.(test|spec)\.[cm]?[jt]sx?$/.test(rel) || /\.md$/.test(rel) || /(^|\/)src\/test\//.test(rel);

/**
 * Blank out comments (// , /* *\/ and, for Kotlin, nested block comments are not needed) while keeping string / template literals and every line break,
 * so a match on the result has the original line number and a doc comment can never trigger a rule. A tiny state machine, not a parser: good enough for TS / TSX / MJS / Kotlin.
 */
export function stripComments(src) {
  let out = ""; let i = 0; const n = src.length; let q = null; // q: current quote char
  while (i < n) {
    const c = src[i], d = src[i + 1];
    if (q) {
      out += c;
      if (c === "\\") { out += src[i + 1] ?? ""; i += 2; continue; }
      if (c === q) q = null;
      i++; continue;
    }
    if (c === '"' || c === "'" || c === "`") { q = c; out += c; i++; continue; }
    if (c === "/" && d === "/") { while (i < n && src[i] !== "\n") { out += " "; i++; } continue; }
    if (c === "/" && d === "*") { i += 2; out += "  "; while (i < n && !(src[i] === "*" && src[i + 1] === "/")) { out += src[i] === "\n" ? "\n" : " "; i++; } out += "  "; i += 2; continue; }
    out += c; i++;
  }
  return out;
}

export const lineOf = (text, index) => text.slice(0, index).split("\n").length;

/** `// guard-allow: <rule> — <reason>` on the same line (or the line above) silences ONE finding of that rule; a reason is mandatory */
export function allowedByPragma(rawLines, line, rule) {
  const re = new RegExp(`guard-allow:\\s*${rule}\\s*[—-]\\s*\\S+`);
  return re.test(rawLines[line - 1] ?? "") || re.test(rawLines[line - 2] ?? "");
}

export function report(name, findings, { json = false } = {}) {
  if (json) { console.log(JSON.stringify(findings)); return findings.length ? 1 : 0; }
  if (!findings.length) { console.log(`PASS ${name}`); return 0; }
  console.log(`FAIL ${name}: ${findings.length} finding(s)`);
  for (const f of findings) console.log(`  [${f.rule}] ${f.file}${f.line ? ":" + f.line : ""}  ${f.message}${f.excerpt ? "\n      > " + f.excerpt.trim().slice(0, 160) : ""}`);
  return 1;
}

/** CLI plumbing: `node guard.mjs [--root DIR] [--json]` */
export function cli(name, fn, argv = process.argv.slice(2)) {
  const ri = argv.indexOf("--root"); const root = ri >= 0 ? argv[ri + 1] : REPO;
  process.exit(report(name, fn(root), { json: argv.includes("--json") }));
}

export { existsSync, join };
