// @class: unit — C0 import gate (D-C0-43): the organization tree is driven by DATA, never by a level index or a type name.
// Own file on purpose (C5 owns organization.test.ts). Pure logic, no browser, no backend.
import test from "node:test";
import assert from "node:assert/strict";
import type { OrgUnit, OrgUnitType } from "../../features/admin/organization";
import * as M from "../../features/admin/organizationModel";

const u = (id: string, parentId: string | null, name = id, typeId: string | null = null): OrgUnit => ({ id, parentId, typeId, name, enabled: true, version: 1 });
const chain = (names: string[]): OrgUnit[] => names.map((n, i) => u(`n${i}`, i === 0 ? null : `n${i - 1}`, n));

test("a 7-level tenant-defined hierarchy (Root > Region > Branch > Division > Department > Team > Squad) renders with depth = data position", () => {
  const units = chain(["Root", "Region", "Branch", "Division", "Department", "Team", "Squad"]);
  const flat = M.flattenTree(M.buildTree(units));
  assert.deepEqual(flat.map((n) => [n.unit.name, n.depth]), [["Root", 0], ["Region", 1], ["Branch", 2], ["Division", 3], ["Department", 4], ["Team", 5], ["Squad", 6]]);
  assert.ok(flat.every((n) => !n.orphan));
  assert.equal(M.unitPath(units, "n6"), "Root › Region › Branch › Division › Department › Team › Squad");
});

test("there is no depth limit: a 60-level chain keeps every node, in order, once", () => {
  const units = chain(Array.from({ length: 60 }, (_, i) => `L${i}`));
  const flat = M.flattenTree(M.buildTree(units));
  assert.equal(flat.length, 60); assert.equal(flat[59].depth, 59); assert.equal(new Set(flat.map((n) => n.unit.id)).size, 60);
  assert.equal(M.unitPath(units, "n59").split(" › ").length, 60);
});

test("names and types carry no meaning: the same shape with completely different labels gives the same tree", () => {
  const shape = (names: string[]) => M.flattenTree(M.buildTree(chain(names))).map((n) => [n.depth, n.children.length]);
  assert.deepEqual(shape(["Root", "Region", "Branch", "Division", "Department", "Team", "Squad"]), shape(["Galaxy", "Star", "Planet", "Continent", "Country", "City", "Street"]));
  assert.deepEqual(shape(["Phòng", "Phòng", "Phòng"]), shape(["A", "B", "C"]));      // a "Phòng" under a "Phòng" is allowed: nothing is keyed by name
});

test("siblings at different depths, several roots (multi-root forest) and lazy collapse follow the data", () => {
  const units = [u("r1", null, "Alpha"), u("r2", null, "Beta"), u("a", "r1", "A"), u("a1", "a", "A1"), u("b", "r2", "B")];
  const tree = M.buildTree(units);
  assert.deepEqual(tree.map((n) => n.unit.id), ["r1", "r2"]);
  assert.deepEqual(M.flattenTree(tree).map((n) => `${n.unit.id}:${n.depth}`), ["r1:0", "a:1", "a1:2", "r2:0", "b:1"]);
  assert.deepEqual(M.flattenTree(tree, new Set(["r2"])).map((n) => n.unit.id), ["r1", "r2", "b"], "only an OPEN node shows its children");
});

test("moving a node offers no target inside its own subtree, whatever the depth (cycle is impossible to pick)", () => {
  const units = chain(["Root", "Region", "Branch", "Division", "Department", "Team", "Squad"]);
  const types: OrgUnitType[] = [];
  const ids = M.moveTargets(units, types, "n2").filter((t) => t.id !== null && !t.disabled).map((t) => t.id);
  for (const below of ["n2", "n3", "n4", "n5", "n6"]) assert.ok(!ids.includes(below), `${below} must not be a target of n2`);
  const dis = M.moveTargets(units, types, "n2").filter((t) => ["n3", "n4", "n5", "n6"].includes(t.id ?? ""));
  assert.ok(dis.every((t) => t.disabled && !!t.reason), "descendants are listed disabled WITH a reason");
});

test("a broken or looping parent link never hides a unit (orphans surface at the root)", () => {
  const units = [u("x", "y", "X"), u("y", "x", "Y"), u("z", "ghost", "Z")];
  const flat = M.flattenTree(M.buildTree(units));
  assert.deepEqual(flat.map((n) => n.unit.id).sort(), ["x", "y", "z"]);
});
