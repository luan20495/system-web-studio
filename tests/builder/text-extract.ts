// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * Extracts the USER-VISIBLE candidate strings of the portals from the TypeScript sources (parsed with the TypeScript compiler API, not a regex over text):
 *   - every string / template literal that contains a Vietnamese diacritic (the text of the product is Vietnamese),
 *   - every JSX text node,
 *   - the string value of the accessibility / label attributes (aria-label, title, placeholder, alt, label, aria-description).
 * Module specifiers, object-property NAMES, type positions, comments and tests are not strings a person reads and are skipped.
 * Also finds the native `confirm` / `prompt` / `alert` calls. Used by text-guard.test.ts, whose guards only ratchet.
 */
import * as ts from "typescript";
import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";

export type Found = { file: string; line: number; text: string };
export const HAS_VI = /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i;
const VISIBLE_ATTRS = new Set(["aria-label", "title", "placeholder", "alt", "label", "aria-description", "aria-placeholder", "aria-roledescription"]);

const root = process.cwd();
const SCAN_ROOTS = ["features", "apps", "packages"];
const SKIP_DIR = new Set(["node_modules", ".next", ".test-build", "dist"]);

const filesUnder = (roots: string[]) => {
  const out: string[] = [];
  const walk = (dir: string) => { for (const e of readdirSync(dir, { withFileTypes: true })) { if (SKIP_DIR.has(e.name) || e.name.startsWith(".")) continue; const p = join(dir, e.name); if (e.isDirectory()) walk(p); else if (/\.(ts|tsx)$/.test(e.name) && !/\.d\.ts$/.test(e.name)) out.push(p); } };
  for (const r of roots) { try { walk(join(root, r)); } catch { /* optional root */ } }
  return out.sort();
};

const isPropertyName = (n: ts.Node) => !!n.parent && (ts.isPropertyAssignment(n.parent) || ts.isPropertySignature(n.parent) || ts.isMethodDeclaration(n.parent) || ts.isEnumMember(n.parent)) && (n.parent as { name?: ts.Node }).name === n;
const inTypePosition = (n: ts.Node) => { for (let p: ts.Node | undefined = n.parent; p; p = p.parent) { if (ts.isTypeNode(p) || ts.isTypeAliasDeclaration(p) || ts.isInterfaceDeclaration(p)) return true; if (ts.isStatement(p)) return false; } return false; };
const isModuleSpec = (n: ts.Node) => !!n.parent && (ts.isImportDeclaration(n.parent) || ts.isExportDeclaration(n.parent) || ts.isExternalModuleReference(n.parent) || ts.isImportTypeNode(n.parent) || (ts.isCallExpression(n.parent) && (n.parent.expression.kind === ts.SyntaxKind.ImportKeyword || (ts.isIdentifier(n.parent.expression) && n.parent.expression.text === "require"))));

export type Scan = { strings: Found[]; dialogs: Found[] };
export function scan(roots: string[] = SCAN_ROOTS, skip: (rel: string) => boolean = () => false): Scan {
  const strings: Found[] = []; const dialogs: Found[] = [];
  for (const file of filesUnder(roots)) {
    const rel = relative(root, file); if (skip(rel)) continue;
    const src = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true, file.endsWith("x") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const line = (n: ts.Node) => src.getLineAndCharacterOfPosition(n.getStart(src)).line + 1;
    const add = (n: ts.Node, text: string) => { const t = text.replace(/\s+/g, " ").trim(); if (t) strings.push({ file: rel, line: line(n), text: t }); };
    // the identifiers imported from the shared dialogs: `confirm` bound to them is NOT the native one
    const sharedDialogs = new Set<string>();
    src.forEachChild((n) => { if (ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier) && /(^@xweb\/ui$|\/dialogs$|packages\/ui\/src\/index$)/.test(n.moduleSpecifier.text)) n.importClause?.namedBindings && ts.isNamedImports(n.importClause.namedBindings) && n.importClause.namedBindings.elements.forEach((e) => sharedDialogs.add(e.name.text)); });
    const visit = (n: ts.Node) => {
      if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) {
        if (!isModuleSpec(n) && !isPropertyName(n) && !inTypePosition(n)) {
          const attr = ts.isJsxAttribute(n.parent) ? n.parent.name.getText(src) : undefined;
          if (HAS_VI.test(n.text) || (attr && VISIBLE_ATTRS.has(attr) && /[A-Za-zÀ-ỹ]/.test(n.text))) add(n, n.text);
        }
      } else if (ts.isTemplateExpression(n)) {
        if (!inTypePosition(n)) { const text = n.head.text + n.templateSpans.map((s) => "${…}" + s.literal.text).join(""); if (HAS_VI.test(text)) add(n, text); }
      } else if (ts.isJsxText(n)) {
        const t = n.text.replace(/\s+/g, " ").trim(); if (t && /[A-Za-zÀ-ỹ]/.test(t)) add(n, t);
      } else if (ts.isCallExpression(n)) {
        const c = n.expression;
        const nm = ts.isIdentifier(c) ? c.text : ts.isPropertyAccessExpression(c) && ts.isIdentifier(c.expression) && (c.expression.text === "window" || c.expression.text === "globalThis") ? c.name.text : undefined;
        const viaWindow = ts.isPropertyAccessExpression(c);
        if (nm && ["confirm", "prompt", "alert"].includes(nm) && (viaWindow || !sharedDialogs.has(nm)) && !/packages\/ui\/src\/dialogs\./.test(rel)) dialogs.push({ file: rel, line: line(n), text: n.getText(src).slice(0, 80) });
      }
      ts.forEachChild(n, visit);
    };
    visit(src);
  }
  return { strings, dialogs };
}
