// @class: unit — PAGE_SCHEMA public data V1: slots, QueryDef.public, bindings, publish approval, runtime-state vocabulary, security UX
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { AppDefinitionV2, QueryDef } from "@xweb/types";
import * as P from "../../features/studio/builder/core/publicData";
import { validateDefinition, usersOf } from "../../features/studio/builder/core/definition";

const q = (id: string, extra: Partial<QueryDef> = {}): QueryDef => ({ id, name: id, dataSourceRef: "orders", mode: "READ", operationKey: "k", ...extra });
const base = (extra: Record<string, unknown> = {}): AppDefinitionV2 => ({
  page: "p", sections: [{ id: "hero", type: "Hero", props: {} }, { id: "grid", type: "ProductGrid", props: {} }, { id: "txt", type: "TextBlock", props: {} }],
  dataSources: [{ id: "orders", name: "Đơn hàng", type: "postgres" }], ...extra,
} as unknown as AppDefinitionV2);

// ------------------------------------------------------------------------------------------------------ conformance with C2's resolveBindings
type Fx = { ref: string; maxDistinctQueries: number; results: { name: string; c2: { ok: boolean; message?: string } }[] };
const fixture = JSON.parse(readFileSync(join(process.cwd(), "tests/builder/fixtures-data/publicdata-conformance.json"), "utf8")) as Fx;
const cases = JSON.parse(readFileSync(join(process.cwd(), "tests/builder/publicdata-cases.json"), "utf8")) as { name: string; expect: "ok" | "fail"; code?: string; doc: Record<string, unknown> }[];

test("mirror = C2 resolveBindings: constants and every case of the fixture (generated from C2's own code)", () => {
  assert.equal(fixture.ref, "c1e0df5");
  assert.equal(fixture.maxDistinctQueries, P.MAX_DISTINCT_QUERIES);
  assert.equal(fixture.results.length, cases.length);
  for (const c of cases) {
    const c2 = fixture.results.find((r) => r.name === c.name)!.c2;
    const { __extraSections, ...rest } = c.doc as { __extraSections?: unknown[] } & Record<string, unknown>;
    const doc = { page: "p", pages: [], ...rest, sections: [...((rest.sections as unknown[]) ?? []), ...(__extraSections ?? [])] } as unknown as AppDefinitionV2;
    // the slots exist (C2 checks a query's slot in the schema validator, not in resolveBindings)
    doc.dataSources = [...new Set((doc.queries ?? []).map((x) => x.dataSourceRef))].map((id) => ({ id, type: "postgres" }));
    const issues = P.validateBindings(doc).flatMap((v) => v.issues);
    assert.equal(c2.ok, c.expect === "ok", `${c.name}: fixture and case disagree`);
    if (c.expect === "ok") assert.deepEqual(issues, [], `${c.name}: C2 accepts, the mirror refuses: ${JSON.stringify(issues)}`);
    else assert.ok(issues.some((i) => i.code === c.code), `${c.name}: C2 refuses (${c2.message}); the mirror says ${JSON.stringify(issues.map((i) => i.code))}, expected ${c.code}`);
  }
});

test("the bindable table: exactly the V1 props of C2", () => {
  assert.deepEqual(P.bindablePropsOf("Hero").map((p) => p.prop), ["eyebrow", "title", "description"]);
  assert.deepEqual(P.bindablePropsOf("ProductGrid"), [{ prop: "heading", kind: "text" }, { prop: "items", kind: "list" }]);
  assert.deepEqual(P.bindablePropsOf("Testimonials").map((p) => p.prop), ["heading", "items"]);
  assert.deepEqual(P.bindablePropsOf("TextBlock"), []);
  assert.equal(P.isBindable("Hero", "ctaLabel"), false);
  assert.equal(P.isBindable("ComparisonBlock", "rows"), false);
});

// ------------------------------------------------------------------------------------------------------------------------------------ slots
test("slot: valid add; id/type shape is C2's (lowercase), not the builder's looser ID_RE", () => {
  const d = base();
  assert.deepEqual(P.validateSlot({ id: "orders-2", type: "postgres", name: "Đơn", description: "x" }, d), []);
  assert.deepEqual(P.validateSlot({ id: "a", type: "rest" }, d), []);
  for (const id of ["Orders", "a.b", "a_b", "-a", "", "x".repeat(65)]) assert.ok(P.validateSlot({ id, type: "postgres" }, d).some((i) => i.code === "SLOT_ID_INVALID"), `id ${id}`);
  for (const type of ["", "Postgres", "1pg", "a_b", "x".repeat(33)]) assert.ok(P.validateSlot({ id: "ok", type }, d).some((i) => i.code === "SLOT_TYPE_INVALID"), `type ${type}`);
});
test("slot: duplicate id rejected (add), editing the same id is fine", () => {
  const d = base();
  assert.ok(P.validateSlot({ id: "orders", type: "postgres" }, d).some((i) => i.code === "SLOT_ID_DUPLICATE"));
  assert.deepEqual(P.validateSlot({ id: "orders", type: "postgres", name: "Mới" }, d, d.dataSources![0]), []);
});
test("slot: never carries sourceRef / credential / url / sql — refused by name", () => {
  const d = base();
  for (const k of ["sourceRef", "credential", "secret", "url", "host", "sql", "connection"]) {
    const issues = P.validateSlot({ id: "ok", type: "postgres", [k]: k === "sourceRef" ? null : "x" } as never, d);
    assert.ok(issues.some((i) => i.code === "SLOT_FIELD_NOT_ALLOWED" && i.path === k), k);
  }
  const op = P.slotOps.add({ id: "ok", type: "postgres", sourceRef: "uuid", credential: "x", url: "http://h" } as never);
  assert.deepEqual(op, { type: "ADD_DATA_SOURCE", definitionId: "ok", definition: { id: "ok", type: "postgres" } });
});
test("slot: a slot that already has a sourceRef can be renamed but its type is locked", () => {
  const d = base({ dataSources: [{ id: "orders", type: "postgres", sourceRef: "11111111-1111-1111-1111-111111111111" }] });
  assert.deepEqual(P.validateSlot({ id: "orders", type: "postgres", name: "n" }, d, d.dataSources![0]), []);
  assert.ok(P.validateSlot({ id: "orders", type: "mysql" }, d, d.dataSources![0]).some((i) => i.code === "SLOT_TYPE_LOCKED"));
  assert.deepEqual(P.slotPatch(d.dataSources![0], { name: "Tên", type: "mysql" }), { name: "Tên" });   // the type change is not even sent
});
test("slot operations: ADD / UPDATE (null clears) / REMOVE have the C2 wire shape", () => {
  assert.deepEqual(P.slotOps.add({ id: "orders", name: " Đơn ", type: "postgres", description: " " }), { type: "ADD_DATA_SOURCE", definitionId: "orders", definition: { id: "orders", type: "postgres", name: "Đơn" } });
  assert.deepEqual(P.slotOps.update("orders", { name: null }), { type: "UPDATE_DATA_SOURCE", definitionId: "orders", definition: { name: null } });
  assert.deepEqual(P.slotOps.remove("orders"), { type: "REMOVE_DATA_SOURCE", definitionId: "orders" });
  const before = { id: "orders", name: "A", type: "postgres", description: "d" };
  assert.deepEqual(P.slotPatch(before, { name: "A", description: "d" }), {});
  assert.deepEqual(P.slotPatch(before, { name: "", description: "d2" }), { name: null, description: "d2" });
});
test("slot removal: a referenced slot is blocked with every dependant named (the server would answer 422 and not cascade)", () => {
  const d = base({ queries: [q("q1"), q("q2", { dataSourceRef: "other" })], actions: [{ id: "a1", type: "CALL_API", dataSourceRef: "orders" }], permissions: [{ id: "p1", permission: "DATA_VIEW", resourceType: "DATA_SOURCE", resourceRef: "orders" }] });
  const issues = P.validateSlotRemoval(d, "orders");
  assert.equal(issues.length, 3); assert.ok(issues.every((i) => i.code === "SLOT_IN_USE"));
  assert.deepEqual(P.validateSlotRemoval(d, "unused"), []);
  assert.deepEqual(usersOf(d, "dataSources", "orders"), ["truy vấn q1", "hành động a1", "quyền p1"]);
});

// ------------------------------------------------------------------------------------------------------------------------------- QueryDef.public
test("QueryDef.public: READ true/false valid; WRITE false valid; WRITE true INVALID; absent = private", () => {
  assert.deepEqual(P.validatePublicQuery(q("a", { public: true })), []);
  assert.deepEqual(P.validatePublicQuery(q("a", { public: false })), []);
  assert.deepEqual(P.validatePublicQuery(q("a", { mode: "WRITE", public: false })), []);
  assert.deepEqual(P.validatePublicQuery(q("a", { mode: "WRITE" })), []);
  const bad = P.validatePublicQuery(q("a", { mode: "WRITE", public: true }), "queries[3]");
  assert.deepEqual(bad.map((i) => [i.path, i.code]), [["queries[3].public", "PUBLIC_WRITE_QUERY"]]);
  assert.ok(P.validatePublicQuery({ ...q("a"), public: "yes" } as never).some((i) => i.code === "PUBLIC_NOT_BOOLEAN"));
  assert.equal(P.isPublicQuery(q("a")), false); assert.equal(P.isPublicQuery(q("a", { public: true })), true);
  assert.equal(P.isPublicQuery(q("a", { mode: "WRITE", public: true })), false, "never publicly runnable, even if persisted");
  assert.equal(P.isPublicEligibleQuery({}), true, "mode absent = READ");
});
test("QueryDef.public: toggle ops — READ only; the stored invalid WRITE+public is reported (not fixed) by both validators", () => {
  assert.deepEqual(P.setPublicOp(q("a"), true), { type: "UPDATE_QUERY", definitionId: "a", definition: { public: true } });
  assert.deepEqual(P.setPublicOp(q("a", { public: true }), false), { type: "UPDATE_QUERY", definitionId: "a", definition: { public: false } });
  assert.ok("error" in P.setPublicOp(q("w", { mode: "WRITE" }), true));
  assert.deepEqual(P.setPublicOp(q("w", { mode: "WRITE", public: true }), false), { type: "UPDATE_QUERY", definitionId: "w", definition: { public: false } }, "turning it OFF is the way out");
  const d = base({ queries: [q("w", { mode: "WRITE", public: true })] });
  assert.ok(validateDefinition(d).some((i) => i.path === "queries[0].public"));
  assert.ok(P.validatePublicQueries(d).length === 1);
  assert.equal((d.queries![0] as QueryDef).public, true, "validation does not mutate");
});
test("public is never inferred from visibility, a LIVE binding or the datasource type", () => {
  const d = base({ publishConfig: { visibility: "PUBLIC" }, queries: [q("a")], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "a" }] });
  assert.deepEqual(P.collectPublicQueries(d), []);
  assert.equal(P.publishApproval(d).required, false);
  assert.ok(P.validateBinding(d.dataBindings![0], d).issues.some((i) => i.code === "QUERY_NOT_PUBLIC"), "a bound but non-public query is a problem, not an implicit publication");
  assert.equal(P.setPublicOp(q("a"), false) && true, true);
});

// ---------------------------------------------------------------------------------------------------------------------------------- binding
test("binding: view carries component, kind, query, slot (derived, never stored)", () => {
  const d = base({ queries: [q("q-title", { public: true })], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-title" }] });
  const v = P.validateBinding(d.dataBindings![0], d);
  assert.deepEqual(v.issues, []); assert.equal(v.component, "Hero"); assert.equal(v.kind, "text"); assert.equal(v.slotId, "orders"); assert.equal(v.slot?.name, "Đơn hàng");
  assert.equal(Object.keys(d.dataBindings![0]).includes("dataSourceRef"), false);
});
test("binding: missing slot / missing query / WRITE / non-public / not bindable are all reported with the reason", () => {
  const d = base({ dataSources: [], queries: [q("a", { public: true }), q("w", { mode: "WRITE", public: true }), q("p")] });
  const codes = (b: { prop: string; queryRef?: string; sectionId?: string }) => P.validateBinding({ id: "b1", sectionId: b.sectionId ?? "hero", prop: b.prop, queryRef: b.queryRef }, d).issues.map((i) => i.code);
  assert.deepEqual(codes({ prop: "title", queryRef: "a" }), ["SLOT_MISSING"]);
  assert.ok(codes({ prop: "title", queryRef: "ghost" }).includes("QUERY_MISSING"));
  assert.ok(codes({ prop: "title", queryRef: "w" }).includes("QUERY_NOT_READ"));
  assert.ok(codes({ prop: "title", queryRef: "p" }).includes("QUERY_NOT_PUBLIC"));
  assert.ok(codes({ prop: "ctaLabel", queryRef: "a" }).includes("PROP_NOT_BINDABLE"));
  assert.ok(codes({ prop: "title", queryRef: "a", sectionId: "gone" }).includes("SECTION_MISSING"));
  assert.ok(codes({ prop: "title" }).includes("QUERY_NONE"));
});
test("binding: two bindings on one prop, and more than 8 distinct queries, are reported", () => {
  const d = base({ queries: [q("a", { public: true })], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "a" }, { id: "b2", sectionId: "hero", prop: "title", queryRef: "a" }] });
  assert.ok(P.validateBindings(d)[1].issues.some((i) => i.code === "BINDING_DUPLICATE"));
});
test("binding: never accepts a physical source — a binding has no slot/sourceRef/credential field in what the UI writes", () => {
  const d = base({ queries: [q("a", { public: true })] });
  const b = { id: "b1", sectionId: "hero", prop: "title", queryRef: "a" };
  assert.deepEqual(Object.keys(b), ["id", "sectionId", "prop", "queryRef"]);
  assert.equal(P.queryOfBinding({ viewModelRef: "vm" }, { ...d, viewModels: [{ id: "vm", mappingRef: "m", fields: [] }], mappings: [{ id: "m", queryRef: "a", fields: [] }] } as never), "a");
});
test("query picker offers the reason why a query cannot be bound", () => {
  const d = base({ queries: [q("a", { public: true }), q("p"), q("w", { mode: "WRITE" })] });
  const c = P.queryChoicesFor(d);
  assert.deepEqual(c.map((x) => [x.query.id, x.reason]), [["a", undefined], ["p", "Chưa công khai"], ["w", "Truy vấn ghi không thể công khai"]]);
});

// ----------------------------------------------------------------------------------------------------------------------------- publish approval
test("publish approval: nothing public → not required, no warning", () => {
  const a = P.publishApproval(base({ queries: [q("a"), q("w", { mode: "WRITE" })] }));
  assert.deepEqual(a, { required: false, queries: [] });
  assert.equal(P.publishApproval(base()).required, false);
});
test("publish approval: lists id, name, slot and the places that use each public query, in document order; WRITE and private queries are excluded", () => {
  const d = base({
    queries: [q("q-items", { name: "Sản phẩm", public: true }), q("q-private"), q("q-title", { public: true }), q("q-w", { mode: "WRITE", public: true })],
    dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-title" }, { id: "b2", sectionId: "grid", prop: "items", queryRef: "q-items" }],
  });
  const a = P.publishApproval(d);
  assert.ok(a.required);
  if (!a.required) return;
  assert.deepEqual(a.queries.map((x) => x.id), ["q-items", "q-title"]);
  assert.deepEqual(a.queries[0], { id: "q-items", name: "Sản phẩm", slotId: "orders", slotName: "Đơn hàng", boundBy: ["grid.items"] });
  assert.deepEqual(a.slots, ["Đơn hàng"]); assert.deepEqual(a.unboundQueries, []);
  assert.match(a.warning, /không cần đăng nhập/);
  assert.deepEqual(P.publicQueryIds(d), ["q-items", "q-title"]);
});
test("publish approval: a public query nobody binds is surfaced as unbound", () => {
  const a = P.publishApproval(base({ queries: [q("lonely", { public: true })] }));
  assert.ok(a.required && a.unboundQueries[0] === "lonely");
});
test("PUBLIC_QUERIES event: C2's exact text is parsed; a difference with the announcement is shown", () => {
  const text = "Public queries of this release (anyone who can open the site may run them, read-only): q-title, q-items";
  assert.deepEqual(P.parsePublicQueriesEvent(text), ["q-title", "q-items"]);
  assert.deepEqual(P.parsePublicQueriesEvent(undefined), []); assert.deepEqual(P.parsePublicQueriesEvent("nothing here"), []);
  assert.deepEqual(P.diffAnnounced(["q-title", "q-items"], ["q-title", "q-items"]), { onlyAnnounced: [], onlyFrozen: [] });
  assert.deepEqual(P.diffAnnounced(["a", "b"], ["b", "c"]), { onlyAnnounced: ["a"], onlyFrozen: ["c"] });
});
test("blockers: a persisted WRITE+public query and a binding the render worker would refuse are both listed before publish", () => {
  const d = base({ queries: [q("w", { mode: "WRITE", public: true }), q("p")], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "p" }] });
  const codes = P.publicDataBlockers(d).map((i) => i.code);
  assert.ok(codes.includes("PUBLIC_WRITE_QUERY") && codes.includes("QUERY_NOT_PUBLIC"));
  assert.deepEqual(P.publicDataBlockers(base()), []);
});

// -------------------------------------------------------------------------------------------------------------------------------- runtime states
test("runtime states: the five C2 names, busy flags, and the detail codes", () => {
  assert.deepEqual([...P.PAGE_RUNTIME_STATES], ["loading-config", "not-ready", "loading-data", "ready", "error"]);
  const s = (x: string, d?: string) => P.describeRuntimeState(x, d)!;
  assert.deepEqual([s("loading-config").busy, s("loading-data").busy, s("ready").busy, s("not-ready").busy, s("error").busy], [true, true, false, false, false]);
  assert.equal(s("not-ready", "api-base-missing").details[0].code, "api-base-missing");
  assert.match(s("not-ready", "api-base-missing").details[0].text, /apiBase/);
  const e = s("error", "q-title:not-found,q-items:http-418,q-x:weird");
  assert.deepEqual(e.details.map((d) => [d.query, d.code]), [["q-title", "not-found"], ["q-items", "http-418"], ["q-x", "weird"]]);
  assert.match(e.details[1].text, /418/); assert.equal(e.details[2].text, "Lỗi không xác định.");
  assert.equal(P.describeRuntimeState("made-up"), null); assert.equal(P.describeRuntimeState(null), null);
});
test("Studio readiness uses the same vocabulary and never produces rows or fake data", () => {
  assert.equal(P.publicDataReadiness(base()).state, "none");
  const ok = base({ queries: [q("a", { public: true })], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "a" }] });
  assert.deepEqual(P.publicDataReadiness(ok, { visibility: "PUBLIC" }), { state: "ready-to-publish", label: "Sẵn sàng xuất bản", queries: ["a"] });
  const priv = P.publicDataReadiness(ok, { visibility: "PRIVATE" });
  assert.equal(priv.state, "not-ready"); assert.ok(priv.state === "not-ready" && priv.reasons.some((r) => /apiBase/.test(r)));
  const bad = P.publicDataReadiness(base({ queries: [q("p")], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "p" }] }), { visibility: "PUBLIC" });
  assert.equal(bad.state, "not-ready");
  assert.equal(JSON.stringify(ok).includes("rows"), false);
});

// ------------------------------------------------------------------------------------------------------------------------------ security UX
test("Public V1 is READ-only: the module has no public action / mutation / workflow concept, and the editors for them have no `public` control", () => {
  const exported = Object.keys(P).join(" ");
  assert.doesNotMatch(exported, /Action|Workflow|Mutation/i);
  for (const f of ["features/studio/builder/ActionEditor.tsx", "features/studio/builder/WorkflowEditor.tsx", "features/studio/builder/core/actions.ts", "features/studio/builder/core/workflow.ts"]) {
    const src = readFileSync(join(process.cwd(), f), "utf8");
    assert.doesNotMatch(src, /\bpublic\s*:|\.public\b|setPublicOp|acknowledgePublicData/, `${f} must not expose a public toggle`);
  }
  // the only operation the public toggle can produce is UPDATE_QUERY with a boolean
  const op = P.setPublicOp(q("a"), true) as { type: string; definition: Record<string, unknown> };
  assert.equal(op.type, "UPDATE_QUERY"); assert.deepEqual(Object.keys(op.definition), ["public"]);
});
test("types: the three slot operations exist and QueryDef.public is typed", () => {
  const ops: import("@xweb/types").DefinitionOperationType[] = ["ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE"];
  assert.equal(ops.length, 3);
  const x: QueryDef = { id: "a", dataSourceRef: "o", public: true };
  assert.equal(x.public, true);
});
