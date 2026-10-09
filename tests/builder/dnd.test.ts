// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import * as D from "../../features/studio/builder/core/dnd";
import { sec } from "./fixtures";

const list = () => [sec("a", "Hero"), sec("b", "TextBlock"), sec("c", "ProductGrid"), sec("f", "Footer")];

test("insert indicator: slot comes from the pointer position against section midpoints", () => {
  const rects = [{ id: "a", top: 0, height: 100 }, { id: "b", top: 100, height: 100 }, { id: "c", top: 200, height: 100 }];
  assert.equal(D.slotFromPoint(rects, 10), 0);
  assert.equal(D.slotFromPoint(rects, 60), 1);
  assert.equal(D.slotFromPoint(rects, 160), 2);
  assert.equal(D.slotFromPoint(rects, 999), 3);
  assert.equal(D.slotFromPoint([], 5), 0);
});

test("library -> canvas: ADD_SECTION at the slot; nothing goes after a trailing Footer", () => {
  const p = D.planAdd(list(), "Hero", "Hero", 0, { id: "n1", props: { title: "t" } });
  assert.deepEqual(p.ops[0], { type: "ADD_SECTION", sectionType: "Hero", sectionId: "n1", props: { title: "t" }, index: 0 });
  assert.equal(D.planAdd(list(), "TextBlock", "x", 99, { id: "n", props: {} }).slot, 3);
  assert.equal(D.planAdd(list(), "Footer", "x", 99, { id: "n", props: {} }).slot, 4);
});

test("adding to an inner page carries pageId; home does not", () => {
  const inner = D.planAdd([], "Hero", "h", 0, { id: "n", props: {}, pageId: "p1" });
  assert.equal((inner.ops[0] as { pageId?: string }).pageId, "p1");
  const home = D.planAdd([], "Hero", "h", 0, { id: "n", props: {}, pageId: "home" });
  assert.equal("pageId" in home.ops[0], false);
});

test("reorder: dragging down accounts for the removed item (arrayMove)", () => {
  const m = D.planMove(list(), "a", 2); // drop between b and c
  assert.ok(m.kind === "move" && m.to === 1);
  assert.equal(D.planMove(list(), "a", 0).kind, "noop");
  assert.equal(D.planMove(list(), "a", 1).kind, "noop");
  const up = D.planMove(list(), "c", 0);
  assert.ok(up.kind === "move" && up.to === 0 && up.ops[0].type === "MOVE_SECTION");
});

test("Footer stays last; others cannot pass it", () => {
  const down = D.planMove(list(), "c", 4);
  assert.ok(down.kind === "noop" || (down.kind === "move" && down.to <= 2));
  const f = D.planMove(list(), "f", 0);
  assert.equal(f.kind, "noop");
});

test("keyboard fallback: step up/down; ends are no-ops", () => {
  const dn = D.planStep(list(), "a", 1);
  assert.ok(dn.kind === "move" && dn.to === 1);
  assert.equal(D.planStep(list(), "a", -1).kind, "noop");
  const up = D.planStep(list(), "b", -1);
  assert.ok(up.kind === "move" && up.to === 0);
});

test("click-to-add fallback: after the selection, else at the end before the Footer", () => {
  assert.equal(D.slotForClickAdd(list(), "a", "TextBlock"), 1);
  assert.equal(D.slotForClickAdd(list(), null, "TextBlock"), 3);
  assert.equal(D.slotForClickAdd([], null, "Hero"), 0);
});

test("slot over rendered rectangles maps back to a section index even when a section has no renderer", () => {
  const sections = [sec("a", "Hero"), sec("x", "ProductCard"), sec("b", "TextBlock")];
  const rects = [{ id: "a", top: 0, height: 100 }, { id: "b", top: 100, height: 100 }];
  assert.equal(D.sectionIndexForSlot(sections, rects, 0), 0);
  assert.equal(D.sectionIndexForSlot(sections, rects, 1), 2);
  assert.equal(D.sectionIndexForSlot(sections, rects, 2), 3);
  assert.equal(D.sectionIndexForSlot(sections, [], 0), 3);
  assert.equal(D.indicatorY(rects, 1), 100);
  assert.equal(D.indicatorY(rects, 2), 200);
});

test("canStep: a step button is enabled only when it would really move (trailing Footer never moves, ends cannot step off the list)", () => {
  assert.equal(D.canStep(list(), "a", -1), false);
  assert.equal(D.canStep(list(), "a", 1), true);
  assert.equal(D.canStep(list(), "c", 1), false, "moving below the Footer is clamped to a no-op");
  assert.equal(D.canStep(list(), "f", -1), false, "Footer stays last");
  assert.equal(D.canStep(list(), "f", 1), false);
  assert.equal(D.canStep(list(), "missing", 1), false);
});

test("M-046 scrollShift: a pure scroll is one common offset; any layout change (ids, order, height, uneven move) is not", () => {
  const a = [{ id: "a", top: 0, height: 100 }, { id: "b", top: 100, height: 50 }];
  assert.equal(D.scrollShift(a, a.map((r) => ({ ...r, top: r.top - 140 }))), -140);
  assert.equal(D.scrollShift(a, a), 0);
  assert.equal(D.scrollShift([], []), null, "nothing rendered yet: take the layout");
  assert.equal(D.scrollShift(a, [a[1], a[0]]), null);
  assert.equal(D.scrollShift(a, [a[0]]), null);
  assert.equal(D.scrollShift(a, [a[0], { ...a[1], height: 80 }]), null);
  assert.equal(D.scrollShift(a, [{ ...a[0], top: -10 }, { ...a[1], top: 80 }]), null);
});
