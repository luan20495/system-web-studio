// Safe visual edits of TSX for source-code apps (Design mode V1, ADR 0012). The source is PARSED (TypeScript compiler API, no type
// checking, nothing executed); edits replace exact AST spans, then the result is re-parsed. Anything not safely editable is "code only".
import ts from "typescript";

const SPACE = ["none", "xs", "sm", "md", "lg", "xl"], TONE = ["neutral", "primary", "success", "warning", "danger"];
/** prop -> allowed literal values (null = any short text). Only these props can be edited visually. */
const COMPANY_PROPS: Record<string, string[] | null> = {
  gap: SPACE, padding: SPACE, tone: [...TONE, "default", "muted"], variant: ["solid", "outline", "ghost"], size: ["sm", "md", "lg"], level: ["1", "2", "3"],
  title: null, label: null, placeholder: null, brand: null, hint: null, direction: ["row", "column"], align: ["start", "center", "end", "stretch"]
};
const HTML_PROPS: Record<string, string[] | null> = { title: null, alt: null, placeholder: null, "aria-label": null };

export type PropView = { name: string; kind: "string" | "number" | "boolean" | "expression" | "absent"; value: string | null; allowed: string[] | null };
export type NodeView = { id: string; tag: string; line: number; depth: number; company: boolean; text: string | null; textEditable: boolean;
  hidden: boolean; hiddenEditable: boolean; props: PropView[]; codeOnly: string | null };

function parse(source: string) {
  const sf = ts.createSourceFile("App.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const diags = (sf as unknown as { parseDiagnostics?: ts.Diagnostic[] }).parseDiagnostics ?? [];
  return { sf, ok: diags.length === 0 };
}
function companyImports(sf: ts.SourceFile): Set<string> {
  const names = new Set<string>();
  for (const st of sf.statements) if (ts.isImportDeclaration(st) && ts.isStringLiteral(st.moduleSpecifier) && st.moduleSpecifier.text === "@company/ui")
    st.importClause?.namedBindings && ts.isNamedImports(st.importClause.namedBindings) && st.importClause.namedBindings.elements.forEach((e) => names.add(e.name.text));
  return names;
}
type El = { node: ts.JsxElement | ts.JsxSelfClosingElement; open: ts.JsxOpeningElement | ts.JsxSelfClosingElement; depth: number; inExpression: boolean };
function elements(sf: ts.SourceFile): El[] {
  const out: El[] = [];
  const visit = (n: ts.Node, depth: number, inExpr: boolean) => {
    let d = depth, e = inExpr;
    if (ts.isJsxElement(n)) { out.push({ node: n, open: n.openingElement, depth, inExpression: inExpr }); d = depth + 1; }
    else if (ts.isJsxSelfClosingElement(n)) { out.push({ node: n, open: n, depth, inExpression: inExpr }); d = depth + 1; }
    // a callback passed to a call (e.g. items.map(i => <X/>)) renders repeated elements: code only
    else if ((ts.isArrowFunction(n) || ts.isFunctionExpression(n)) && n.parent && ts.isCallExpression(n.parent) && out.length) e = true;
    ts.forEachChild(n, (c) => visit(c, d, e));
  };
  visit(sf, 0, false);
  return out;
}
const idOf = (el: El) => `${el.node.getStart()}-${el.node.getEnd()}`;
function attr(open: El["open"], name: string) {
  return open.attributes.properties.find((p): p is ts.JsxAttribute => ts.isJsxAttribute(p) && p.name.getText() === name);
}
function propView(open: El["open"], name: string, allowed: string[] | null): PropView {
  const a = attr(open, name);
  if (!a) return { name, kind: "absent", value: null, allowed };
  if (!a.initializer) return { name, kind: "boolean", value: "true", allowed };
  if (ts.isStringLiteral(a.initializer)) return { name, kind: "string", value: a.initializer.text, allowed };
  if (ts.isJsxExpression(a.initializer) && a.initializer.expression && ts.isNumericLiteral(a.initializer.expression)) return { name, kind: "number", value: a.initializer.expression.text, allowed };
  return { name, kind: "expression", value: null, allowed };
}
function textChild(el: El): ts.JsxText | null {
  if (!ts.isJsxElement(el.node)) return null;
  const kids = el.node.children.filter((c) => !(ts.isJsxText(c) && c.containsOnlyTriviaWhiteSpaces));
  return kids.length === 1 && ts.isJsxText(kids[0]) ? kids[0] : null;
}

export function tree(source: string): { ok: boolean; nodes: NodeView[] } {
  const { sf, ok } = parse(source);
  if (!ok) return { ok: false, nodes: [] };
  const company = companyImports(sf);
  return { ok: true, nodes: elements(sf).map((el) => {
    const tag = el.open.tagName.getText(); const isCompany = company.has(tag); const isHtml = /^[a-z][a-z0-9]*$/.test(tag);
    const spread = el.open.attributes.properties.some((p) => ts.isJsxSpreadAttribute(p));
    const allowed = isCompany ? COMPANY_PROPS : isHtml ? HTML_PROPS : {};
    const t = textChild(el); const hiddenProp = propView(el.open, "hidden", null);
    const codeOnly = el.inExpression ? "Lặp trong biểu thức (ví dụ .map) — sửa trong Code" : spread ? "Có thuộc tính trải ({...props}) — sửa trong Code"
      : !isCompany && !isHtml ? "Component riêng của ứng dụng — sửa trong Code" : null;
    return { id: idOf(el), tag, line: sf.getLineAndCharacterOfPosition(el.node.getStart()).line + 1, depth: el.depth, company: isCompany,
      text: t ? t.getText().trim() : null, textEditable: !codeOnly && !!t, hidden: hiddenProp.kind === "boolean" || hiddenProp.value === "true",
      hiddenEditable: !codeOnly && (hiddenProp.kind === "absent" || hiddenProp.kind === "boolean"),
      props: codeOnly ? [] : Object.entries(allowed).map(([n, a]) => propView(el.open, n, a)), codeOnly };
  }) };
}

const jsxText = (s: string) => s.replace(/[{}<>]/g, (c) => ({ "{": "&#123;", "}": "&#125;", "<": "&lt;", ">": "&gt;" }[c]!));
const strLit = (s: string) => JSON.stringify(s);

export type Edit = { nodeId: string; text?: string; hidden?: boolean; props?: Record<string, string | null> };
/** Returns the edited source, or throws with a reason. Edits are applied back-to-front so positions stay valid. */
export function edit(source: string, e: Edit): string {
  const t = tree(source); if (!t.ok) throw new Error("source does not parse");
  const view = t.nodes.find((n) => n.id === e.nodeId); if (!view) throw new Error("element not found (the file changed)");
  if (view.codeOnly) throw new Error(`code only: ${view.codeOnly}`);
  const { sf } = parse(source);
  const el = elements(sf).find((x) => idOf(x) === e.nodeId)!;
  const repl: { start: number; end: number; text: string }[] = [];
  if (e.text !== undefined) {
    if (!view.textEditable) throw new Error("text of this element is not editable");
    const tc = textChild(el)!; const v = e.text.trim(); if (!v || v.length > 500 || /\n/.test(v)) throw new Error("text must be one line, 1-500 characters");
    repl.push({ start: tc.getStart(), end: tc.getEnd(), text: jsxText(v) });
  }
  const setAttr = (name: string, value: string | true | null) => {
    const a = attr(el.open, name);
    const text = value === null ? "" : value === true ? name : name === "level" ? `${name}={${value}}` : `${name}=${strLit(value)}`;
    if (a) repl.push({ start: a.getFullStart(), end: a.getEnd(), text: text ? " " + text : "" });
    else if (value !== null) { const at = el.open.tagName.getEnd(); repl.push({ start: at, end: at, text: " " + text }); }
  };
  if (e.hidden !== undefined) { if (!view.hiddenEditable) throw new Error("visibility is not editable here"); setAttr("hidden", e.hidden ? true : null); }
  for (const [name, value] of Object.entries(e.props ?? {})) {
    const p = view.props.find((x) => x.name === name); if (!p) throw new Error(`property ${name} is not editable on ${view.tag}`);
    if (p.kind === "expression") throw new Error(`property ${name} is an expression — edit it in Code`);
    if (value !== null) {
      if (p.allowed && !p.allowed.includes(value)) throw new Error(`${name} must be one of ${p.allowed.join(", ")}`);
      if (value.length > 200 || /[\n\r]/.test(value)) throw new Error(`${name} is too long`);
    }
    setAttr(name, value);
  }
  if (!repl.length) throw new Error("nothing to change");
  let out = source;
  // back to front; at the same position apply the replacement (larger end) before an insertion, so spans stay valid
  for (const r of repl.sort((a, b) => b.start - a.start || b.end - a.end)) out = out.slice(0, r.start) + r.text + out.slice(r.end);
  if (!parse(out).ok) throw new Error("the edit would break the file");
  return out;
}
