// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import * as I from "../../features/studio/builder/core/inspector";
import * as L from "../../features/studio/builder/core/library";
import { canonicalPermissions, capabilitiesFor } from "../../features/studio/builder/core/permissions";
import { available, notReady } from "../../features/studio/builder/core/readiness";
import { sec } from "./fixtures";

const comp = (id: string, props: Record<string, unknown>, required: string[] = []) => ({
  id, name: id, category: "layout", status: "ACTIVE", latestVersion: "1.0.0",
  versions: [{ version: "1.0.0", propsSchema: { required, properties: props } }],
}) as never;

const hero = comp("Hero", { title: { type: "string" }, subtitle: { type: "string" }, align: { type: "string" }, visible: { type: "boolean" }, items: { type: "array", itemProperties: { id: { type: "string" }, label: { type: "string" } } } }, ["title"]);

test("inspector has the six tabs in order", () => {
  assert.deepEqual(I.INSPECTOR_TABS.map((t) => t.id), ["content", "design", "data", "action", "permission", "advanced"]);
});

test("props are grouped: content / design / visibility", () => {
  const g = I.groupProps(hero);
  assert.deepEqual(g.content.map(([k]) => k), ["title", "subtitle", "items"]);
  assert.deepEqual(g.design.map(([k]) => k), ["align"]);
  assert.equal(g.visibility?.[0], "visible");
});

test("Design tab is NOT_READY (with reason) for a component with no design props; Data/Action follow definition-ops readiness", () => {
  const plain = comp("TextBlock", { body: { type: "string" } });
  const s = I.tabStates({ section: sec("a", "TextBlock"), component: plain, meta: undefined, canEdit: true, definitionOps: notReady("C2 chưa tích hợp") });
  const by = Object.fromEntries(s.map((t) => [t.id, t.readiness.state]));
  assert.equal(by.design, "NOT_READY"); assert.equal(by.data, "NOT_READY"); assert.equal(by.action, "NOT_READY");
  assert.equal(by.content, "AVAILABLE"); assert.equal(by.permission, "AVAILABLE"); assert.equal(by.advanced, "AVAILABLE");
  const ok = I.tabStates({ section: sec("a", "Hero"), component: hero, meta: undefined, canEdit: true, definitionOps: available() });
  assert.equal(ok.find((t) => t.id === "design")!.readiness.state, "AVAILABLE");
  assert.equal(ok.find((t) => t.id === "data")!.readiness.state, "AVAILABLE");
});

test("content edits compile to UPDATE_PROP / ADD_ITEM / REMOVE_ITEM, nothing for unchanged props", () => {
  const defs = (hero as { versions: { propsSchema: { properties: never } }[] }).versions[0].propsSchema.properties;
  const section = sec("s1", "Hero", { title: "A", items: [{ id: "i1", label: "x" }, { id: "i2", label: "y" }] });
  const ops = I.propOperations(section, defs, { title: "B", items: [{ id: "i1", label: "x2" }, { id: "i3", label: "z" }] });
  assert.deepEqual(ops.map((o) => o.type).sort(), ["ADD_ITEM", "REMOVE_ITEM", "UPDATE_PROP", "UPDATE_PROP"]);
  assert.equal(I.propOperations(section, defs, { ...section.props }).length, 0);
  assert.equal(I.propOperations(section, defs, { title: "B" }, new Set(["subtitle"])).length, 0);
});

test("bindable props: derived from the schema when no metadata; from metadata when present", () => {
  const d = I.bindableProps(hero, undefined);
  assert.ok(d.every((b) => b.derived));
  assert.ok(d.some((b) => b.prop === "items" && b.cardinality === "LIST" && b.itemFields.includes("label")));
  const m = I.bindableProps(hero, { bindableProps: [{ prop: "items", cardinality: "LIST", itemFields: ["id"] }] } as never);
  assert.deepEqual(m, [{ prop: "items", cardinality: "LIST", itemFields: ["id"], derived: false }]);
});

test("permission labels cover all 14 canonical codes", () => {
  assert.equal(Object.keys(I.permissionLabel).length, 14);
});

test("legacy PROJECT_* permissions map to canonical; unknown codes are dropped (never invented)", () => {
  const p = canonicalPermissions(["PROJECT_READ", "PROJECT_EDIT", "PROJECT_PUBLISH", "PROJECT_MEMBERS", "NOT_A_CODE", "QUERY_EXECUTE"]);
  for (const c of ["APP_VIEW", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "QUERY_EXECUTE"]) assert.ok(p.has(c as never), c);
  assert.equal(p.size, 5);
});

test("permission-driven UI: viewer cannot edit or publish; new codes are deny-by-default", () => {
  const viewer = capabilitiesFor(["PROJECT_READ"]);
  assert.equal(viewer.canView, true); assert.equal(viewer.canEdit, false); assert.equal(viewer.canPublish, false);
  assert.equal(viewer.canRunQueries, false); assert.equal(viewer.canStartWorkflows, false);
  const editor = capabilitiesFor(["PROJECT_READ", "PROJECT_EDIT"]);
  assert.equal(editor.canEdit, true); assert.equal(editor.canPublish, false);
  assert.equal(capabilitiesFor(undefined).canView, false);
  assert.equal(capabilitiesFor(["APP_EDIT", "APP_PUBLISH", "APP_SHARE"]).canShare, true);
});

test("library: only registry components; non-renderable ones are disabled with a reason; data components list NOT_READY for missing ones", () => {
  const reg = [comp("Hero", {}), comp("ProductGrid", {}), comp("ProductCard", {})];
  const e = L.libraryEntries(reg);
  assert.deepEqual(e.map((x) => x.id), ["Hero", "ProductGrid", "ProductCard"]);
  assert.equal(e.find((x) => x.id === "ProductCard")!.disabled, true);
  assert.ok(e.find((x) => x.id === "ProductCard")!.reason);
  const dc = L.dataComponents(reg);
  assert.equal(dc.length, 8);
  assert.equal(dc.find((x) => x.id === "ProductGrid")!.available, true);
  for (const id of ["DataTable", "DataList", "KPI", "Chart", "Form", "Select", "Detail"]) {
    const x = dc.find((y) => y.id === id)!;
    assert.equal(x.available, false, id); assert.ok(x.reason, id);
  }
});

test("new sections start with only required props filled (no fake content)", () => {
  assert.deepEqual(L.defaultProps(hero), { title: "Tiêu đề mới", visible: true });
});
