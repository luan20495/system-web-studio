import test from "node:test";
import assert from "node:assert/strict";
import * as F from "../../features/studio/builder/core/dataFlow";
import { checkAction } from "../../features/studio/builder/core/actions";
import { doc } from "./fixtures";
import { available, notReady } from "../../features/studio/builder/core/readiness";

const base = () => doc({ dataSources: [{ id: "ds1", name: "Kho" }] } as never);

test("transforms[]: legacy `transform` (object or array) normalises to transforms[]; both keys is an error", () => {
  assert.deepEqual(F.normalizeField({ to: "a", from: "x", transform: { type: "trim" } }).field.transforms, [{ type: "trim" }]);
  assert.deepEqual(F.normalizeField({ to: "a", from: "x", transform: [{ type: "trim" }, { type: "upper" }] }).field.transforms.length, 2);
  assert.deepEqual(F.normalizeField({ to: "a", from: "x" }).field.transforms, []);
  const both = F.normalizeField({ to: "a", from: "x", transform: { type: "trim" }, transforms: [{ type: "upper" }] });
  assert.ok(both.error);
  const f = F.normalizeField({ to: "a", from: "x", transform: { type: "trim" } }).field as unknown as Record<string, unknown>;
  assert.equal("transform" in f, false);
});

test("UI can only create the simple (parameterless) transforms; stored complex ones are kept", () => {
  const f = { to: "a", from: "x", transforms: [{ type: "enumMap", map: { 1: "A" } }] } as never;
  const added = F.addTransform(f, "trim");
  assert.ok(!("error" in added) && added.transforms.length === 2 && (added.transforms[0] as { map?: unknown }).map);
  assert.ok("error" in (F.addTransform(f, "formula")));
  assert.ok("error" in (F.addTransform(f, "javascript")));
  assert.equal(F.isSimpleTransform({ type: "trim" }), true);
  assert.equal(F.isSimpleTransform({ type: "enumMap", map: {} } as never), false);
});

test("transforms reorder / remove", () => {
  const f = { to: "a", from: "x", transforms: [{ type: "trim" }, { type: "upper" }, { type: "toString" }] } as never;
  assert.deepEqual(F.moveTransform(f, 2, 0).transforms.map((t) => t.type), ["toString", "trim", "upper"]);
  assert.deepEqual(F.removeTransform(f, 1).transforms.map((t) => t.type), ["trim", "toString"]);
});

test("ParamDef.required defaults to true: only an explicit false is written", () => {
  assert.deepEqual(F.setParamRequired({ name: "a", type: "STRING", required: false }, true), { name: "a", type: "STRING" });
  assert.deepEqual(F.setParamRequired({ name: "a", type: "STRING" }, false), { name: "a", type: "STRING", required: false });
  assert.equal(F.paramRequired(F.newParam("a")), true);
  assert.ok(["STRING", "INTEGER", "NUMBER", "BOOLEAN", "TIMESTAMP", "DATE"].includes(F.newParam().type));
});

test("query: operation must be an approved key, never SQL or a URL; source must exist", () => {
  const d = base();
  const ok = { name: "Danh sách", dataSourceRef: "ds1", mode: "READ" as const, operationKey: "products.list", params: [] };
  assert.deepEqual(F.checkQuery(ok, d), []);
  assert.ok(F.checkQuery({ ...ok, operationKey: "select * from t" }, d).length);
  assert.ok(F.checkQuery({ ...ok, operationKey: "https://x.test/y" }, d).length);
  assert.ok(F.checkQuery({ ...ok, dataSourceRef: "nope" }, d).length);
  assert.ok(F.checkQuery({ ...ok, params: [{ name: "a", type: "STRING" }, { name: "a", type: "DATE" }] }, d).length);
});

test("mapping -> viewModel -> binding chain builds canonical definitions with local ids", () => {
  const d = base();
  const q = F.buildQuery({ name: "Q", dataSourceRef: "ds1", mode: "READ", operationKey: "products.list", params: [] }, d);
  const m = F.buildMapping("M", q.id, [{ to: "name", from: "title", transforms: [{ type: "trim" }] }, { to: "price", from: "amount", transforms: [{ type: "toNumber" }] }], d);
  const vm = F.viewModelFromMapping(m, d, "VM");
  assert.deepEqual(vm.fields.map((f) => [f.name, f.type]), [["name", "STRING"], ["price", "NUMBER"]]);
  assert.equal(vm.mappingRef, m.id);
  const b = F.buildBinding("s-text", "body", vm.id, d);
  assert.ok(!("error" in b));
  const plan = F.planDataFlow({ query: q, mapping: m, viewModel: vm, binding: b as never });
  assert.deepEqual(plan.ops.map((o) => o.type), ["ADD_QUERY", "ADD_MAPPING", "ADD_VIEW_MODEL", "ADD_DATA_BINDING"]);
  assert.ok(plan.ops.every((o) => o.definitionId && o.definition));
  assert.ok(!JSON.stringify(plan.ops).includes("\"transform\":"), "no legacy key in ops");
  const dup = F.buildBinding("s-text", "body", vm.id, { ...d, dataBindings: [b as never] });
  assert.ok("error" in dup);
});

test("mapping checks: duplicate targets, missing source", () => {
  assert.ok(F.checkMapping([]).length);
  assert.ok(F.checkMapping([{ to: "a", from: "x", transforms: [] }, { to: "a", from: "y", transforms: [] }] as never).length);
  assert.ok(F.checkMapping([{ to: "a", transforms: [] }] as never).length);
  assert.deepEqual(F.checkMapping([{ to: "a", from: "x", transforms: [] }] as never), []);
});

test("binding compatibility explains missing item fields", () => {
  const vm = { id: "v", queryRef: "q", cardinality: "LIST", fields: [{ name: "id", type: "STRING" }] } as never;
  const r = F.bindingCompatibility({ prop: "items", cardinality: "LIST", itemFields: ["id", "name"] }, vm);
  assert.equal(r.ok, false); assert.deepEqual(r.missing, ["name"]);
  assert.equal(F.bindingCompatibility({ prop: "items", cardinality: "SINGLE", itemFields: [] }, vm).ok, false);
});

test("backend missing: data sources and discovery are NOT_READY, later steps follow definition ops; no mock rows", () => {
  assert.equal(F.stepReadiness("source", available()).state, "NOT_READY");
  assert.equal(F.stepReadiness("discovery", available()).state, "NOT_READY");
  assert.equal(F.stepReadiness("query", available()).state, "AVAILABLE");
  assert.equal(F.stepReadiness("query", notReady("x")).state, "NOT_READY");
});

test("component states: loading / empty / error / success / not-ready", () => {
  assert.equal(F.viewStateOf(available(), { loading: true }).kind, "loading");
  assert.equal(F.viewStateOf(available(), { rows: [] }).kind, "empty");
  assert.equal(F.viewStateOf(available(), { error: "boom" }).kind, "error");
  assert.equal(F.viewStateOf(available(), { rows: [1, 2] }).kind, "success");
  assert.equal(F.viewStateOf(notReady("không có máy chủ"), null).kind, "not-ready");
  assert.equal(F.viewStateOf(available(), null).kind, "not-ready");
});

test("action referencing a mapped query passes ref check (REFRESH_QUERY)", () => {
  const d = doc({ queries: [{ id: "q1", name: "Q", dataSourceRef: "ds1", mode: "READ", operationKey: "k", params: [] }], dataSources: [{ id: "ds1", name: "d" }] } as never);
  assert.deepEqual(checkAction({ id: "a1", name: "r", type: "REFRESH_QUERY", queryRef: "q1", trigger: { sectionId: "s-hero", event: "onClick" } } as never, d), []);
});
