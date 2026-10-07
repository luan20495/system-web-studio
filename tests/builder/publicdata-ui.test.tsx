// @class: unit — server-side render of the public-data editors (the clicking is in tests/browser/publicdata.spec.mjs)
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import type { AppDefinitionV2 } from "@xweb/types";
import { PublicBindingsPanel, PublicDataTab, PublicQueriesPanel, PublicReadinessPanel, SlotEditor } from "../../features/studio/builder/PublicDataPanels";
import type { DefCtx } from "../../features/studio/builder/ctx";
import { available, notReady } from "../../features/studio/builder/core/readiness";
import { a11yProblems } from "./a11y";

const D = (extra: Record<string, unknown> = {}): AppDefinitionV2 => ({ page: "p", sections: [{ id: "hero", type: "Hero", props: {} }, { id: "txt", type: "TextBlock", props: {} }], dataSources: [{ id: "orders", name: "Đơn hàng", type: "postgres" }], ...extra } as unknown as AppDefinitionV2);
const ctx = (doc: AppDefinitionV2, over: Partial<DefCtx> = {}): DefCtx => ({ doc, commit: async () => true, readiness: available(), canEdit: true, busy: false, metadata: new Map(), registry: [], labelOf: (t) => t, ...over });

test("slot editor: list, add form, empty and read-only states; nothing physical is offered", () => {
  const html = renderToStaticMarkup(<SlotEditor ctx={ctx(D())}/>);
  assert.match(html, /data-testid="slot-list"/); assert.match(html, /Đơn hàng/); assert.match(html, /data-testid="slot-add-form"/);
  assert.doesNotMatch(html, /sourceRef|credential|password|connection string|jdbc|https?:\/\//i);
  assert.deepEqual(a11yProblems(html), []);
  assert.match(renderToStaticMarkup(<SlotEditor ctx={ctx(D({ dataSources: [] }))}/>), /data-testid="slot-empty"/);
  const ro = renderToStaticMarkup(<SlotEditor ctx={ctx(D(), { canEdit: false })}/>);
  assert.match(ro, /data-testid="slot-readonly"/); assert.doesNotMatch(ro, /slot-add-form|slot-delete-|slot-edit-/);
  assert.match(renderToStaticMarkup(<SlotEditor ctx={ctx(D(), { readiness: notReady("C2 chưa tích hợp") })}/>), /Chưa sẵn sàng/);
});
test("slot editor: a slot that already has a real source says so without printing the source", () => {
  const html = renderToStaticMarkup(<SlotEditor ctx={ctx(D({ dataSources: [{ id: "orders", type: "postgres", sourceRef: "11111111-2222-3333-4444-555555555555" }] }))}/>);
  assert.match(html, /đã gắn nguồn thật/); assert.doesNotMatch(html, /11111111-2222/);
});
test("public queries: READ has the switch; WRITE has none and says why; an invalid persisted WRITE+public is shown invalid with an explicit way out", () => {
  const doc = D({ queries: [{ id: "r", dataSourceRef: "orders", mode: "READ", public: true }, { id: "w", dataSourceRef: "orders", mode: "WRITE" }, { id: "bad", dataSourceRef: "orders", mode: "WRITE", public: true }] });
  const html = renderToStaticMarkup(<PublicQueriesPanel ctx={ctx(doc)}/>);
  assert.match(html, /data-testid="public-toggle-r"[^>]*checked/); assert.doesNotMatch(html, /data-testid="public-toggle-w"/); assert.match(html, /data-testid="pq-write-w"/);
  assert.match(html, /data-testid="pq-invalid-bad"/); assert.match(html, /data-testid="public-off-bad"/); assert.doesNotMatch(html, /data-testid="public-toggle-bad"/);
  assert.deepEqual(a11yProblems(html), []);
  assert.doesNotMatch(renderToStaticMarkup(<PublicQueriesPanel ctx={ctx(doc, { canEdit: false })}/>), /public-off-bad/);
});
test("bindings: mapping section.prop → query → slot is shown; problems are shown on the row; non-public queries are disabled in the picker", () => {
  const doc = D({ queries: [{ id: "q1", name: "Tiêu đề", dataSourceRef: "orders", public: true }, { id: "q2", dataSourceRef: "orders" }], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q1" }, { id: "b2", sectionId: "hero", prop: "ctaLabel", queryRef: "q2" }] });
  const html = renderToStaticMarkup(<PublicBindingsPanel ctx={ctx(doc)}/>);
  assert.match(html, /data-testid="binding-map-b1"[^>]*>hero → truy vấn Tiêu đề → khe Đơn hàng/);
  assert.match(html, /data-testid="binding-issue-b2"/); assert.match(html, /ctaLabel/); assert.match(html, /chưa công khai/i);
  assert.match(html, /<option value="q2" disabled="">q2 — Chưa công khai<\/option>/);
  assert.doesNotMatch(html, /TextBlock/, "a component with no bindable prop is not offered");
  assert.deepEqual(a11yProblems(html), []);
});
test("readiness panel: none / not-ready (reasons) / ready; the five runtime states are listed, no rows anywhere", () => {
  assert.match(renderToStaticMarkup(<PublicReadinessPanel ctx={ctx(D())}/>), /data-state="none"/);
  const bad = renderToStaticMarkup(<PublicReadinessPanel ctx={ctx(D({ queries: [{ id: "q", dataSourceRef: "orders" }], dataBindings: [{ id: "b", sectionId: "hero", prop: "title", queryRef: "q" }] }), { siteVisibility: "PUBLIC" })}/>);
  assert.match(bad, /data-state="not-ready"/); assert.match(bad, /public-readiness-reasons/);
  const ok = renderToStaticMarkup(<PublicDataTab ctx={ctx(D({ queries: [{ id: "q", dataSourceRef: "orders", public: true }], dataBindings: [{ id: "b", sectionId: "hero", prop: "title", queryRef: "q" }] }), { siteVisibility: "PUBLIC" })}/>);
  assert.match(ok, /data-state="ready-to-publish"/);
  for (const s of ["loading-config", "not-ready", "loading-data", "ready", "error"]) assert.match(ok, new RegExp(`data-state="${s}"`));
  assert.doesNotMatch(ok, /<table|data-testid="rows"/);
  assert.deepEqual(a11yProblems(ok), []);
});
