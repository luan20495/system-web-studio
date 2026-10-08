// @class: unit — M-005: the guided "Hiển thị dữ liệu trong trang" flow is one commit built from operations that already exist (ADD slot / query / dataBinding). No network, no backend.
import test from "node:test";
import assert from "node:assert/strict";
import type { AppDefinitionV2 } from "@xweb/types";
import { connectedRows, deriveSlotId, emptyGuidedDraft, planGuidedBinding, slotTypeOf, type GuidedDraft } from "../../features/studio/builder/core/guidedBinding";
import { validateBindings } from "../../features/studio/builder/core/publicData";
import { DATA_WORDS } from "../../features/studio/builder/core/dataWording";
import { doc, sec } from "./fixtures";

const base = (extra: Partial<AppDefinitionV2> = {}) => doc({ sections: [sec("s-hero", "Hero", { title: "Xin chào" }), sec("s-grid", "ProductGrid", { heading: "Sản phẩm", items: [] }), sec("s-foot", "Footer", { text: "x" })], ...extra });
const draft = (o: Partial<GuidedDraft> = {}): GuidedDraft => ({ ...emptyGuidedDraft("s-grid", "items"), source: { kind: "new", name: "Kho đơn hàng", type: "PostgreSQL" }, datasetName: "Sản phẩm", operationKey: "products.list", ...o });
const ok = (d: GuidedDraft, document = base()) => { const p = planGuidedBinding(d, document, (t) => t); assert.ok(p.ok, JSON.stringify(!p.ok && p.problems)); return p; };

test("a new source + dataset + binding = ONE commit: ADD slot, ADD query, ADD binding, in dependency order, existing operation types only", () => {
  const p = ok(draft());
  assert.deepEqual(p.ops.map((o) => o.type), ["ADD_DATA_SOURCE", "ADD_QUERY", "ADD_DATA_BINDING"]);
  assert.equal(p.slotCreated, true); assert.equal(p.slotId, "kho-don-hang");
  const [slot, query, binding] = p.ops.map((o) => (o as { definition: Record<string, unknown> }).definition);
  assert.deepEqual(slot, { id: "kho-don-hang", type: "postgresql", name: "Kho đơn hàng" });
  assert.deepEqual(query, { id: "q1", name: "Sản phẩm", dataSourceRef: "kho-don-hang", mode: "READ", operationKey: "products.list", params: [] });
  assert.deepEqual(binding, { id: "b1", sectionId: "s-grid", prop: "items", queryRef: "q1" });
  assert.match(p.summary, /Sản phẩm/);
});

test("the slot never carries a sourceRef / credential / URL, and the query is always READ", () => {
  const p = ok(draft());
  const slot = (p.ops[0] as { definition: Record<string, unknown> }).definition;
  assert.deepEqual(Object.keys(slot).sort(), ["id", "name", "type"]);
  assert.equal(((p.ops[1] as unknown as { definition: { mode: string } }).definition).mode, "READ");
});

test("an existing slot is reused: no slot is created", () => {
  const d = base({ dataSources: [{ id: "orders", name: "Đơn hàng", type: "postgres" }] } as Partial<AppDefinitionV2>);
  const p = ok(draft({ source: { kind: "slot", slotId: "orders" } }), d);
  assert.deepEqual(p.ops.map((o) => o.type), ["ADD_QUERY", "ADD_DATA_BINDING"]); assert.equal(p.slotCreated, false);
});

test("public is OFF unless asked: the query carries no `public`; ON writes public:true", () => {
  assert.equal("public" in ((ok(draft()).ops[1] as { definition: object }).definition), false);
  assert.equal(((ok(draft({ public: true })).ops[1] as { definition: { public?: boolean } }).definition).public, true);
});

test("a private dataset is allowed (the row says 'Chưa công khai'), every other refusal of the publish step is found now", () => {
  const p = ok(draft({ public: false }));
  const next = base();
  next.dataSources = [{ id: "kho-don-hang", type: "postgresql" }] as never; next.queries = [{ id: "q1", name: "Sản phẩm", dataSourceRef: "kho-don-hang", mode: "READ", operationKey: "products.list", params: [] }] as never;
  next.dataBindings = [{ id: "b1", sectionId: "s-grid", prop: "items", queryRef: "q1" }] as never;
  assert.deepEqual(validateBindings(next)[0].issues.map((i) => i.code), ["QUERY_NOT_PUBLIC"]);
  assert.ok(p.ok);
});

test("refusals, each on its field: component / property / source / name / operation key / limits", () => {
  const fields = (d: GuidedDraft, document = base()) => { const p = planGuidedBinding(d, document); return p.ok ? [] : p.problems.map((x) => x.field); };
  assert.deepEqual(fields(draft({ sectionId: "nope" })), ["section"]);
  assert.deepEqual(fields(draft({ prop: "nope" })), ["prop"]);
  assert.deepEqual(fields(draft({ prop: "" })), ["prop"]);
  assert.ok(fields(draft({ source: { kind: "slot", slotId: "" } })).includes("source"));
  assert.ok(fields(draft({ source: { kind: "new", name: "", type: "postgres" } })).includes("sourceName"));
  assert.ok(fields(draft({ source: { kind: "new", name: "Kho", type: "!!!" } })).includes("sourceType"));
  assert.deepEqual(fields(draft({ datasetName: " " })), ["datasetName"]);
  assert.deepEqual(fields(draft({ operationKey: "SELECT * FROM x" })), ["operationKey"]);
  assert.deepEqual(fields(draft({ maxRows: 0 })), ["maxRows"]);
});

test("a property that already shows data is refused with a plain message", () => {
  const d = base({ dataBindings: [{ id: "b1", sectionId: "s-grid", prop: "items", queryRef: "q9" }] } as Partial<AppDefinitionV2>);
  const p = planGuidedBinding(draft(), d); assert.ok(!p.ok);
  assert.match(!p.ok ? p.problems[0].message : "", /đã được gắn dữ liệu/);
});

test("a property the published page cannot bind is refused (allow-list of the page runtime)", () => {
  const p = planGuidedBinding(draft({ sectionId: "s-foot", prop: "links" }), base()); assert.ok(!p.ok);
  assert.equal(!p.ok ? p.problems[0].message : "", DATA_WORDS.where.notBindable);
});

test("slot ids: derived from the name, valid for the runtime, unique", () => {
  assert.equal(deriveSlotId("Kho đơn hàng", []), "kho-don-hang");
  assert.equal(deriveSlotId("Kho đơn hàng", ["kho-don-hang"]), "kho-don-hang-2");
  assert.equal(deriveSlotId("!!!", []), "nguon");
  assert.match(deriveSlotId("x".repeat(200), []), /^[a-z0-9][a-z0-9-]{0,63}$/);
  assert.equal(slotTypeOf("Rest API"), "rest-api"); assert.equal(slotTypeOf("POSTGRES"), "postgres");
});

test("a second new source with the same name gets another slot id (no ADD collision)", () => {
  const d = base({ dataSources: [{ id: "kho-don-hang", type: "postgres" }] } as Partial<AppDefinitionV2>);
  const p = ok(draft(), d); assert.equal(p.slotId, "kho-don-hang-2");
});

test("at most 8 different datasets per page", () => {
  const queries = Array.from({ length: 8 }, (_, i) => ({ id: `q${i + 1}`, dataSourceRef: "s", mode: "READ", operationKey: "a.b", params: [], public: true }));
  const bindings = Array.from({ length: 8 }, (_, i) => ({ id: `b${i + 1}`, sectionId: "s-hero", prop: `p${i}`, queryRef: `q${i + 1}` }));
  const d = base({ dataSources: [{ id: "s", type: "postgres" }], queries, dataBindings: bindings } as unknown as Partial<AppDefinitionV2>);
  const p = planGuidedBinding(draft({ source: { kind: "slot", slotId: "s" } }), d); assert.ok(!p.ok);
  assert.ok(!p.ok && p.problems.some((x) => /tối đa 8/.test(x.message)));
});

test("the 'Đã kết nối' sentences resolve both the direct and the view-model path", () => {
  const d = base({
    dataSources: [{ id: "s", name: "Kho", type: "postgres" }],
    queries: [{ id: "q1", name: "Sản phẩm", dataSourceRef: "s", mode: "READ", operationKey: "a.b", params: [], public: true }],
    viewModels: [{ id: "vm1", queryRef: "q1", fields: [], cardinality: "LIST" }],
    dataBindings: [{ id: "b1", sectionId: "s-grid", prop: "items", queryRef: "q1" }, { id: "b2", sectionId: "s-hero", prop: "title", viewModelRef: "vm1" }],
  } as unknown as Partial<AppDefinitionV2>);
  const rows = connectedRows(d);
  assert.deepEqual(rows.map((r) => [r.component, r.prop, r.dataset, r.public, r.viaViewModel]), [["ProductGrid", "items", "Sản phẩm", true, false], ["Hero", "title", "Sản phẩm", true, true]]);
});
