#!/usr/bin/env node
// C5-R2 audit helper (read-only, no servers): counts user-visible hard-coded strings with the TypeScript AST.
//   node scripts/r2-i18n-count.mjs [rootDir] [--json]
// Method (documented in docs/parallel/c5/audit/R2-i18n-theme.md part A):
//   JSX_TEXT   JsxText nodes with at least one letter (one per node, trimmed, whitespace collapsed)
//   ATTR       string-literal values of aria-label|aria-description|title|placeholder|alt|aria-placeholder|aria-roledescription (JSX attributes)
//   PROP       string-literal values of copy-carrying props on custom components (label,sub,subtitle,detail,hint,description,heading,buttonLabel,tooltip,emptyText,why,needs,action)
//   JSX_EXPR   string / template literals with a letter, inside a JSX expression container that is NOT an attribute (ternaries, && chains)
//   TS_VI      string / template literals (outside JSX) that contain a Vietnamese-only letter (maps, toasts, errors, validation messages)
//   TS_EN      string / template literals (outside JSX) with >= 3 Latin words that sit under a copy-carrying key or call
//              (message|title|detail|label|hint|text|note|reason|description|error|setError|setMsg|setNotice|ApiError|Error)
// A literal is counted once, in the first category that matches. Template literals with ${} are also tallied in "interpolated"
// (they need ICU-style arguments). Comments are not nodes, so they are never counted. Tests, docs and build output are skipped.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { createRequire } from "node:module";

const ROOT = process.argv[2] && !process.argv[2].startsWith("--") ? process.argv[2] : new URL("..", import.meta.url).pathname;
const JSON_OUT = process.argv.includes("--json");
const DUMP = process.argv.includes("--dump"); // prints every counted literal: CATEGORY<TAB>file:line<TAB>text (to audit precision)
const ts = createRequire(join(ROOT, "package.json"))("typescript");

const DIRS = ["app", "apps", "components", "features", "lib", "packages"];
const SKIP = new Set(["node_modules", ".next", "dist", ".git"]);
const files = [];
(function walk(d) {
  let es; try { es = readdirSync(d); } catch { return; }
  for (const e of es) {
    if (SKIP.has(e) || e.startsWith(".next")) continue;
    const p = join(d, e); const s = statSync(p);
    if (s.isDirectory()) walk(p); else if (/\.(ts|tsx)$/.test(e) && !/\.d\.ts$/.test(e)) files.push(p);
  }
})(ROOT);
const scoped = files.filter((f) => DIRS.includes(relative(ROOT, f).split(sep)[0]));

const VI = /[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ]/;
const LETTER = /\p{L}/u;
const ATTRS = new Set(["aria-label", "aria-description", "title", "placeholder", "alt", "aria-placeholder", "aria-roledescription"]);
const PROPS = new Set(["label", "sub", "subtitle", "detail", "hint", "description", "heading", "buttonLabel", "tooltip", "emptyText", "why", "needs", "action", "legend"]);
const COPY_KEYS = /^(message|title|detail|label|hint|text|note|reason|description|error|sub|why|needs|placeholder|name|kicker|desc)$/;
const COPY_CALLS = /^(setError|setMsg|setNotice|setErr|ApiError|Error|invalid|badKey|alert|confirm)$/;

const portalOf = (rel) => {
  const [top, second] = rel.split(sep);
  if (top === "features" && second === "studio") return "studio";
  if (top === "features" && second === "admin") return "admin+platform";
  if (top === "packages" && ["auth", "ui", "i18n", "permissions", "api-client", "types"].includes(second)) return "shared-all-portals";
  if (top === "packages") return "other-packages";
  if (top === "features" && second === "library.tsx") return "shared-studio+admin";
  if (top === "features") return "legacy-shared-helpers";
  if (top === "lib") return "lib(preview/legacy)";
  return "legacy-root-app";
};

const rows = [];
const T = () => ({ JSX_TEXT: 0, ATTR: 0, PROP: 0, JSX_EXPR: 0, TS_VI: 0, TS_EN: 0, interpolated: 0, total: 0, unique: new Set() });
const byFile = new Map();
for (const f of scoped) {
  const rel = relative(ROOT, f);
  const src = readFileSync(f, "utf8");
  const sf = ts.createSourceFile(f, src, ts.ScriptTarget.Latest, true, f.endsWith("x") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const t = T(); byFile.set(rel, t);
  const seen = new Set();
  const add = (cat, node, text) => {
    if (seen.has(node.pos + ":" + node.end)) return; seen.add(node.pos + ":" + node.end);
    if (DUMP) console.log(`${cat}\t${rel}:${sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1}\t${text.replace(/\s+/g, " ").trim().slice(0, 100)}`);
    t[cat]++; t.total++; t.unique.add(text.replace(/\s+/g, " ").trim());
    if (ts.isTemplateExpression(node)) t.interpolated++;
  };
  const textOf = (n) => (ts.isTemplateExpression(n) ? n.head.text + n.templateSpans.map((s) => "{}" + s.literal.text).join("") : n.text ?? "");
  const isStr = (n) => ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n) || ts.isTemplateExpression(n);
  const inJsxExpr = (n) => { for (let p = n.parent; p; p = p.parent) { if (ts.isJsxExpression(p)) return !(p.parent && ts.isJsxAttribute(p.parent)); if (ts.isJsxAttribute(p)) return false; if (ts.isFunctionLike(p) && !ts.isJsxExpression(p.parent ?? p)) { /* keep climbing: callbacks inside JSX still render */ } } return false; };
  const inJsx = (n) => { for (let p = n.parent; p; p = p.parent) if (ts.isJsxElement(p) || ts.isJsxSelfClosingElement(p) || ts.isJsxFragment(p)) return true; return false; };
  const keyOf = (n) => {
    const p = n.parent;
    if (p && ts.isPropertyAssignment(p) && p.initializer === n) return p.name.getText(sf).replace(/["']/g, "");
    if (p && ts.isConditionalExpression(p)) return keyOf(p);
    if (p && ts.isParenthesizedExpression(p)) return keyOf(p);
    if (p && ts.isBinaryExpression(p)) return keyOf(p);
    return null;
  };
  const callOf = (n) => { for (let p = n.parent, d = 0; p && d < 4; p = p.parent, d++) { if (ts.isCallExpression(p) || ts.isNewExpression(p)) { const e = p.expression; return ts.isIdentifier(e) ? e.text : ts.isPropertyAccessExpression(e) ? e.name.text : null; } } return null; };
  (function visit(n) {
    if (ts.isImportDeclaration(n) || ts.isExportDeclaration(n) || ts.isLiteralTypeNode(n) || ts.isTypeAliasDeclaration(n) || ts.isInterfaceDeclaration(n)) return;
    if (ts.isJsxText(n)) {
      const s = n.text.replace(/\s+/g, " ").trim();
      if (s && LETTER.test(s)) add("JSX_TEXT", n, s);
    } else if (ts.isJsxAttribute(n) && n.initializer) {
      const name = n.name.getText(sf);
      let lit = null;
      if (isStr(n.initializer)) lit = n.initializer;
      else if (ts.isJsxExpression(n.initializer) && n.initializer.expression && isStr(n.initializer.expression)) lit = n.initializer.expression;
      if (lit && (ATTRS.has(name) || PROPS.has(name)) && LETTER.test(textOf(lit))) add(ATTRS.has(name) ? "ATTR" : "PROP", lit, textOf(lit));
      else if (lit == null && n.initializer && ts.isJsxExpression(n.initializer) && n.initializer.expression && (ATTRS.has(name) || PROPS.has(name))) {
        // ternary / && with string branches in a copy attribute
        (function inner(x) { if (isStr(x)) { if (LETTER.test(textOf(x))) add(ATTRS.has(name) ? "ATTR" : "PROP", x, textOf(x)); return; } ts.forEachChild(x, inner); })(n.initializer.expression);
      }
    } else if (isStr(n)) {
      const text = textOf(n);
      if (LETTER.test(text)) {
        const parentIsAttr = n.parent && (ts.isJsxAttribute(n.parent) || (ts.isJsxExpression(n.parent) && ts.isJsxAttribute(n.parent.parent)));
        if (!parentIsAttr && !ts.isPropertyAccessExpression(n.parent) && !ts.isElementAccessExpression(n.parent) && !(ts.isCaseClause(n.parent)) && !(ts.isBinaryExpression(n.parent) && [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(n.parent.operatorToken.kind))) {
          if (inJsx(n) && inJsxExpr(n) && !ts.isTemplateSpan(n.parent) && (VI.test(text) || /[A-Za-z]{3,}\s+[A-Za-z]{2,}/.test(text))) add("JSX_EXPR", n, text);
          else if (VI.test(text)) add("TS_VI", n, text);
          else if (/(?:[A-Za-z]{2,}[ ,.'’-]+){2,}[A-Za-z]{2,}/.test(text) && !/^[./#@]|:\/\/|^[a-z0-9_.-]+$/.test(text) && (COPY_KEYS.test(keyOf(n) ?? "") || COPY_CALLS.test(callOf(n) ?? ""))) add("TS_EN", n, text);
        }
      }
    }
    ts.forEachChild(n, visit);
  })(sf);
}
const groups = new Map();
for (const [rel, t] of byFile) {
  const g = portalOf(rel); const a = groups.get(g) ?? { ...T(), files: 0 };
  for (const k of ["JSX_TEXT", "ATTR", "PROP", "JSX_EXPR", "TS_VI", "TS_EN", "interpolated", "total"]) a[k] += t[k];
  t.unique.forEach((u) => a.unique.add(u)); a.files++; groups.set(g, a);
}
const out = { root: ROOT, files: scoped.length, groups: Object.fromEntries([...groups].map(([k, v]) => [k, { files: v.files, JSX_TEXT: v.JSX_TEXT, ATTR: v.ATTR, PROP: v.PROP, JSX_EXPR: v.JSX_EXPR, TS_VI: v.TS_VI, TS_EN: v.TS_EN, total: v.total, unique: v.unique.size, interpolated: v.interpolated }])),
  topFiles: [...byFile].map(([f, t]) => [f, t.total]).sort((a, b) => b[1] - a[1]).slice(0, 25) };
const all = [...byFile.values()]; const allUnique = new Set(); all.forEach((t) => t.unique.forEach((u) => allUnique.add(u)));
out.grand = { total: all.reduce((s, t) => s + t.total, 0), unique: allUnique.size, interpolated: all.reduce((s, t) => s + t.interpolated, 0) };
if (JSON_OUT) console.log(JSON.stringify(out, null, 1));
else {
  console.log(`root ${ROOT}  files scanned ${out.files}`);
  console.log("group".padEnd(24) + ["files", "JSX_TEXT", "ATTR", "PROP", "JSX_EXPR", "TS_VI", "TS_EN", "TOTAL", "unique", "interp"].map((h) => h.padStart(9)).join(""));
  for (const [g, v] of Object.entries(out.groups)) console.log(g.padEnd(24) + [v.files, v.JSX_TEXT, v.ATTR, v.PROP, v.JSX_EXPR, v.TS_VI, v.TS_EN, v.total, v.unique, v.interpolated].map((x) => String(x).padStart(9)).join(""));
  console.log("GRAND total", out.grand.total, "unique", out.grand.unique, "interpolated templates", out.grand.interpolated);
  console.log("top files:"); out.topFiles.forEach(([f, n]) => console.log("  " + String(n).padStart(5) + "  " + f));
}
