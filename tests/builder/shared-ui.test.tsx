// @class: unit — shared UI added / fixed in packages/ui for M-006 (ErrorBoundary), M-010 (overlay stack), M-012 / M-013 / M-014 (CSS), M-016 (Toast). Server-side render + pure functions + guards over the wiring files.
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { ErrorBoundary, ErrorFallback, errorReference } from "../../packages/ui/src/ErrorBoundary";
import { DEFAULT_DURATIONS, MAX_VISIBLE, ToastHost, toast, toastReducer, type Toast, type ToastState } from "../../packages/ui/src/Toast";
import { confirm, prompt } from "../../packages/ui/src/dialogs";
import { acquireOverlay, overlayDepth } from "../../packages/ui/src/overlay";
import { a11yProblems } from "./a11y";

const root = process.cwd();
const read = (p: string) => readFileSync(join(root, p), "utf8");

// ------------------------------------------------------------------------------------------------------------------------------------------------ Toast (M-016)
const T = (id: string, kind: Toast["kind"] = "success", seq = 1): Toast => ({ id, kind, message: id, duration: DEFAULT_DURATIONS[kind], seq });
const empty: ToastState = { visible: [], queue: [] };
test("Toast reducer: at most MAX_VISIBLE are shown, the rest wait in FIFO order and are promoted as visible ones go — nothing is replaced silently", () => {
  let s = empty; for (let i = 1; i <= 5; i++) s = toastReducer(s, { type: "add", toast: T(`m${i}`) });
  assert.deepEqual(s.visible.map((t) => t.id), ["m1", "m2", "m3"]); assert.deepEqual(s.queue.map((t) => t.id), ["m4", "m5"]); assert.equal(MAX_VISIBLE, 3);
  s = toastReducer(s, { type: "dismiss", id: "m2" });
  assert.deepEqual(s.visible.map((t) => t.id), ["m1", "m3", "m4"]); assert.deepEqual(s.queue.map((t) => t.id), ["m5"]);
  s = toastReducer(s, { type: "dismiss", id: "m5" }); assert.deepEqual(s.queue, []);                       // a queued toast can be dismissed before it ever shows
  assert.deepEqual(toastReducer(s, { type: "clear" }), empty);
});
test("Toast reducer: re-using an id updates the toast in place (visible or queued), never duplicates it", () => {
  let s = toastReducer(empty, { type: "add", toast: T("a") });
  s = toastReducer(s, { type: "add", toast: { ...T("a"), message: "new", seq: 2 } });
  assert.equal(s.visible.length, 1); assert.equal(s.visible[0].message, "new");
  s = toastReducer(empty, { type: "add", toast: T("x") }); for (const id of ["y", "z", "q"]) s = toastReducer(s, { type: "add", toast: T(id) });
  s = toastReducer(s, { type: "add", toast: { ...T("q"), message: "again" } });
  assert.equal(s.queue.length, 1); assert.equal(s.queue[0].message, "again");
});
test("Toast defaults: errors never time out, success / info / warning do (the timer pauses on hover / focus: browser spec)", () => {
  assert.equal(DEFAULT_DURATIONS.error, null); assert.ok((DEFAULT_DURATIONS.success ?? 0) > 0); assert.ok((DEFAULT_DURATIONS.warning ?? 0) >= (DEFAULT_DURATIONS.success ?? 0));
});
test("ToastHost renders both live regions from the first render (an aria-live region only announces content INSERTED into an existing one); nothing else is visible", () => {
  const h = renderToStaticMarkup(<ToastHost/>);
  assert.match(h, /<div class="xp-toastList" role="alert">/); assert.match(h, /<div class="xp-toastList" role="status" aria-live="polite">/);
  assert.doesNotMatch(h, /xp-toast /); assert.deepEqual(a11yProblems(h), []);
});
test("Toast API is imperative (no provider) and safe without a DOM; confirm() / prompt() settle immediately on the server with false / null", async () => {
  assert.equal(typeof toast.success, "function"); assert.equal(typeof toast.error, "function"); assert.equal(typeof toast.dismiss, "function");
  assert.equal(await confirm("Xóa?"), false); assert.equal(await prompt({ title: "x", label: "y" }), null);
});

// ------------------------------------------------------------------------------------------------------------------------------------------------ ErrorBoundary (M-006)
test("ErrorFallback: heading (focus target), 'Thử lại', the reference code, never the message or the stack", () => {
  const err = Object.assign(new Error("secret internal detail at Bomb.tsx:12"), { requestId: "req-7f3a", stack: "Error: secret\n    at Bomb (Bomb.tsx:12)" });
  const h = renderToStaticMarkup(<ErrorFallback error={err} onRetry={() => undefined} homeHref="/admin"/>);
  assert.match(h, /<main class="xp-errorPage"/); assert.match(h, /<h1 tabindex="-1" class="xp-errorTitle">Đã có lỗi xảy ra<\/h1>/);
  assert.match(h, /Thử lại/); assert.match(h, /<code>req-7f3a<\/code>/); assert.match(h, /href="\/admin"/);
  assert.doesNotMatch(h, /secret|Bomb\.tsx|at Bomb/); assert.deepEqual(a11yProblems(h), []);
  const inline = renderToStaticMarkup(<ErrorFallback error={err} onRetry={() => undefined} variant="inline"/>);
  assert.match(inline, /<section class="xp-errorInline" role="alert"/); assert.match(inline, /<h2 tabindex="-1"/);
});
test("errorReference: ApiError.requestId first, Next's digest second, nothing otherwise; capped", () => {
  assert.equal(errorReference({ requestId: "r1", digest: "d1" }), "r1"); assert.equal(errorReference({ digest: "d1" }), "d1");
  assert.equal(errorReference(new Error("x")), null); assert.equal(errorReference(null), null); assert.equal(errorReference({ requestId: "z".repeat(200) })!.length, 64);
});
test("ErrorBoundary: derives the error state from a thrown value and renders the fallback for it, not the children", () => {
  assert.equal(ErrorBoundary.getDerivedStateFromError(new Error("boom")).error?.message, "boom");
  const b = new ErrorBoundary({ children: <p>child</p> }); b.state = { error: new Error("boom") };
  assert.match(renderToStaticMarkup(b.render() as never), /xp-errorTitle/);
});
test("apps: every portal has error.tsx + global-error.tsx using the shared fallback, and wraps its route render in ErrorBoundary", () => {
  for (const app of ["platform", "admin", "studio"]) {
    for (const f of ["error.tsx", "global-error.tsx"]) { assert.ok(existsSync(join(root, `apps/${app}/app/${f}`)), `${app}/${f}`); assert.match(read(`apps/${app}/app/${f}`), /ErrorFallback/); assert.match(read(`apps/${app}/app/${f}`), /"use client"/); }
    assert.match(read(`apps/${app}/app/global-error.tsx`), /<html/); assert.match(read(`apps/${app}/app/entry.tsx`), /<ErrorBoundary resetKeys=\{\[seg\.join\("\/"\)\]\}/);
  }
});

// ------------------------------------------------------------------------------------------------------------------------------------------------ overlay stack (M-010)
test("overlay stack: only the top overlay is 'top'; the scroll lock is taken by the first and restored by the last, in any closing order", () => {
  const g = globalThis as unknown as { document?: unknown }; const before = g.document;
  const body = { style: { overflow: "auto" } }; g.document = { body };
  try {
    const a = acquireOverlay(); assert.equal(body.style.overflow, "hidden"); const b = acquireOverlay();
    assert.equal(a.isTop(), false); assert.equal(b.isTop(), true); assert.equal(overlayDepth(), 2);
    a.release(); assert.equal(body.style.overflow, "hidden", "closing the LOWER overlay first must keep the lock"); assert.equal(b.isTop(), true);
    b.release(); assert.equal(body.style.overflow, "auto", "the value before the first overlay is restored"); assert.equal(overlayDepth(), 0);
    b.release(); assert.equal(body.style.overflow, "auto", "releasing twice is harmless");
  } finally { g.document = before; }
});

// ------------------------------------------------------------------------------------------------------------------------------------------------ CSS wiring (M-012 / M-013 / M-014)
const importsOf = (src: string) => Array.from(src.matchAll(/import "(?:@xweb\/ui\/styles|\.\.\/\.\.\/packages\/ui\/src\/styles)\/([a-z]+)\.css";/g), (m) => m[1]);
test("apps: Platform and Admin load the shared stylesheet and NOT builder.css (dark, global); Studio loads both; the harness CSS set equals the Admin layout", () => {
  assert.deepEqual(importsOf(read("apps/admin/app/layout.tsx")), ["globals", "responsive", "http", "factory", "ui"]);
  assert.deepEqual(importsOf(read("apps/platform/app/layout.tsx")), ["globals", "responsive", "http", "factory", "ui"]);
  assert.deepEqual(importsOf(read("apps/studio/app/layout.tsx")), ["globals", "responsive", "http", "factory", "builder", "ui"]);
  assert.deepEqual(importsOf(read("tests/browser/admin-css.ts")), importsOf(read("apps/admin/app/layout.tsx")));
});
test("ui.css: every rule is either a token block, an `.xp-*` component, or scoped under `.shell` (cannot leak into the dark builder); colours only as tokens", () => {
  const css = read("packages/ui/src/styles/ui.css").replace(/\/\*[\s\S]*?\*\//g, "");
  const rules = Array.from(css.matchAll(/(?<=^|\})\s*([^{}@]+)\{([^{}]*)\}/g), (m) => ({ sel: m[1].trim(), body: m[2] }));
  assert.ok(rules.length > 40);
  for (const r of rules) for (const s of r.sel.split(",").map((x) => x.trim())) assert.match(s, /^(:root|\.xp-[\w-]+|\.shell\s)/, `unscoped selector: ${s}`);
  for (const r of rules.filter((x) => x.sel !== ":root")) assert.doesNotMatch(r.body, /#[0-9a-fA-F]{3,8}\b/, `hard-coded hex outside the token block in: ${r.sel}`);
  assert.match(css, /--ui-z-toast:100/);
});
test("factory.css: .grid2 never forces a track wider than its container (WCAG 1.4.10); a closed drawer is visibility:hidden and only opens with data-nav=open", () => {
  const css = read("packages/ui/src/styles/factory.css");
  assert.match(css, /\.grid2\{[^}]*minmax\(min\(380px,100%\),1fr\)/); assert.doesNotMatch(css, /minmax\(380px,1fr\)/);
  assert.match(css, /\.shell>\.sidebar\{[^}]*visibility:hidden/); assert.match(css, /\.shell\[data-nav=open\]>\.sidebar\{[^}]*visibility:visible/);
});
