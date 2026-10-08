// @class: unit — UX-001 (C6 QA @ 5cc230e): Studio Data crashed when a stored mapping field had no `transforms[]`. A field may be stored without it (undefined / null / the legacy `transform`); reading must never throw and never rewrite what was stored.
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import type { FieldMappingDef, MappingDef } from "@xweb/types";
import { addTransform, checkMapping, fieldTypeOf, fieldsOf, mappingNote, moveTransform, normalizeMapping, removeTransform, transformsOf, viewModelFromMapping } from "../../features/studio/builder/core/dataFlow";
import { DataWizard } from "../../features/studio/builder/DataWizard";
import { available } from "../../features/studio/builder/core/readiness";
import type { DefCtx } from "../../features/studio/builder/ctx";
import { doc as baseDoc } from "./fixtures";

const ctx = (over: Partial<DefCtx> = {}): DefCtx => ({ doc: baseDoc({}), commit: async () => true, readiness: available(), canEdit: true, busy: false, metadata: new Map(), registry: [], labelOf: (t) => t, ...over });
const f = (o: Record<string, unknown>) => o as unknown as FieldMappingDef;

test("transformsOf: undefined, null, [], valid, legacy object / array and garbage all read as a list (never throw)", () => {
  assert.deepEqual(transformsOf(f({ to: "a" })), [], "transforms undefined");
  assert.deepEqual(transformsOf(f({ to: "a", transforms: null })), [], "transforms null");
  assert.deepEqual(transformsOf(f({ to: "a", transforms: [] })), [], "transforms []");
  assert.deepEqual(transformsOf(f({ to: "a", transforms: [{ type: "trim" }, { type: "upper" }] })), [{ type: "trim" }, { type: "upper" }], "valid transforms");
  assert.deepEqual(transformsOf(f({ to: "a", transform: { type: "toNumber" } })), [{ type: "toNumber" }], "legacy single object");
  assert.deepEqual(transformsOf(f({ to: "a", transform: [{ type: "trim" }] })), [{ type: "trim" }], "legacy array");
  assert.deepEqual(transformsOf(f({ to: "a", transforms: "trim" })), [], "a string is not a list");
  assert.deepEqual(transformsOf(f({ to: "a", transforms: [null, 3, {}, { type: "trim" }] })), [{ type: "trim" }], "garbage entries are skipped");
  assert.deepEqual(transformsOf(null), []); assert.deepEqual(transformsOf(undefined), []);
});

test("every helper that used f.transforms works on a field stored without it", () => {
  const bare = f({ from: "price", to: "price" });
  assert.deepEqual(checkMapping([bare]), [], "a source column is enough");
  assert.equal(fieldTypeOf(bare), "STRING");
  assert.deepEqual((addTransform(bare, "toNumber") as FieldMappingDef).transforms, [{ type: "toNumber" }], "adding builds the canonical transforms[]");
  assert.deepEqual(removeTransform(bare, 0).transforms, []); assert.deepEqual(moveTransform(bare, 0, 1).to, "price");
  const m = { id: "m1", queryRef: "q1", errorPolicy: "NULL_FIELD", fields: [bare, f({ from: "n", to: "n", transforms: [{ type: "trim" }] })] } as unknown as MappingDef;
  assert.equal(mappingNote(m), "price, n [Bỏ khoảng trắng đầu/cuối]");
  assert.deepEqual(viewModelFromMapping(m, baseDoc({}), "vm").fields.map((x) => x.name), ["price", "n"]);
  assert.equal(mappingNote({ id: "m2" } as never), "", "a mapping without fields reads as having none");
  assert.deepEqual(fieldsOf({ fields: "nope" }), []); assert.deepEqual(fieldsOf(null), []);
});

test("reading never rewrites what was stored (no silent corruption)", () => {
  const stored = { id: "m1", queryRef: "q1", fields: [{ from: "a", to: "a" }, { from: "b", to: "b", transform: { type: "trim" } }] };
  const copy = JSON.parse(JSON.stringify(stored));
  transformsOf(stored.fields[0] as never); mappingNote(stored as never); fieldsOf(stored as never); viewModelFromMapping(stored as never, baseDoc({}), "x");
  assert.deepEqual(stored, copy, "the stored object is untouched");
  const n = normalizeMapping(stored as never); assert.deepEqual(n.mapping.fields.map((x) => x.transforms), [[], [{ type: "trim" }]], "the explicit normaliser builds the canonical shape on a COPY");
});

test("Studio Data panel (SSR) renders a document whose mappings have fields without transforms, null transforms, a mapping without fields — no crash, names shown", () => {
  const d = baseDoc({
    dataSources: [{ id: "erp", name: "ERP", type: "CONNECTOR" }], queries: [{ id: "q1", name: "Sản phẩm", dataSourceRef: "erp", operationKey: "list" }],
    mappings: [
      { id: "m-bare", name: "Không biến đổi", queryRef: "q1", errorPolicy: "NULL_FIELD", fields: [{ from: "price", to: "price" }, { from: "name", to: "name", transforms: null }] },
      { id: "m-empty", name: "Không có trường", queryRef: "q1", errorPolicy: "NULL_FIELD" },
      { id: "m-ok", name: "Có biến đổi", queryRef: "q1", errorPolicy: "NULL_FIELD", fields: [{ from: "n", to: "n", transforms: [{ type: "toNumber" }] }] },
    ],
  } as never);
  let html = ""; assert.doesNotThrow(() => { html = renderToStaticMarkup(<DataWizard ctx={ctx({ doc: d })}/>); });
  assert.match(html, /Không biến đổi/); assert.match(html, /Không có trường/); assert.match(html, /price, name/); assert.match(html, /n \[Chuyển thành số\]/);
  assert.doesNotMatch(html, /undefined|\[object/);
});
