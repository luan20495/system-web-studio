import test from "node:test";
import assert from "node:assert/strict";
import { renderSchemaDocument, renderSitePages } from "../../lib/schema-preview";
import { BindingError, MAX_DISTINCT_QUERIES, PAGE_RUNTIME_JS, PAGE_RUNTIME_PATH, resolveBindings } from "../../workers/render/page-runtime";

/** a published page with one binding of every kind the V1 runtime supports */
const schema = (over: Record<string, unknown> = {}) => ({
  schemaVersion: 2,
  sections: [
    { id: "hero-1", type: "Hero", componentVersion: "1.0.0", props: { eyebrow: "Eb", title: "Authored title", description: "Authored description", ctaLabel: "Go" } },
    { id: "grid-1", type: "ProductGrid", componentVersion: "1.0.0", props: { heading: "Products", items: [{ id: "i1", name: "Authored item", description: "d" }] } },
    { id: "talk-1", type: "Testimonials", componentVersion: "1.0.0", props: { heading: "Voices", items: [{ id: "t1", quote: "Q", author: "A" }] } },
    { id: "foot-1", type: "Footer", componentVersion: "1.0.0", props: { text: "Footer" } },
  ],
  pages: [{ id: "about", slug: "about", title: "About", sections: [{ id: "tech-1", type: "TechnologySection", componentVersion: "1.0.0", props: { heading: "Tech", body: "Body" } }] }],
  dataSources: [{ id: "ds", type: "postgres" }],
  queries: [
    { id: "q-title", dataSourceRef: "ds", mode: "READ", operationKey: "k.title", public: true },
    { id: "q-items", dataSourceRef: "ds", mode: "READ", operationKey: "k.items", public: true },
    { id: "q-private", dataSourceRef: "ds", mode: "READ", operationKey: "k.private" },
    { id: "q-write", dataSourceRef: "ds", mode: "WRITE", operationKey: "k.write", public: true },
  ],
  viewModels: [{ id: "vm-items", queryRef: "q-items", cardinality: "LIST" }, { id: "vm-map", mappingRef: "map-title" }],
  mappings: [{ id: "map-title", queryRef: "q-title", fields: [] }],
  dataBindings: [
    { id: "b-title", sectionId: "hero-1", prop: "title", queryRef: "q-title" },
    { id: "b-items", sectionId: "grid-1", prop: "items", viewModelRef: "vm-items" },
    { id: "b-body", sectionId: "tech-1", prop: "body", viewModelRef: "vm-map" },
  ],
  ...over,
}) as never;

const fails = (s: unknown, re: RegExp) => assert.throws(() => resolveBindings(s as never), (e: unknown) => e instanceof BindingError && re.test(e.message));

test("a binding resolves to the LOCAL id of the query it calls - directly, through a view model, through a view model's mapping", () => {
  assert.deepEqual(resolveBindings(schema()), [
    { id: "b-title", sectionId: "hero-1", prop: "title", query: "q-title", kind: "text" },
    { id: "b-items", sectionId: "grid-1", prop: "items", query: "q-items", kind: "list" },
    { id: "b-body", sectionId: "tech-1", prop: "body", query: "q-title", kind: "text" },
  ]);
  assert.deepEqual(resolveBindings(schema({ dataBindings: [] })), []);
  assert.deepEqual(resolveBindings({ sections: [] } as never), []);
});

test("a binding that cannot work is refused with the reason, never skipped", () => {
  const one = (b: Record<string, unknown>) => schema({ dataBindings: [{ id: "b1", sectionId: "hero-1", prop: "title", queryRef: "q-title", ...b }] });
  fails(one({ queryRef: "q-private" }), /not public/);                                  // exists in the draft, but not listed as public: never trusted
  fails(one({ queryRef: "q-write" }), /not a READ query/);
  fails(one({ queryRef: "q-missing" }), /does not exist/);
  fails(one({ queryRef: undefined }), /names no query/);
  fails(one({ queryRef: undefined, viewModelRef: "vm-missing" }), /view model 'vm-missing' does not exist/);
  fails(one({ sectionId: "nope" }), /section 'nope' does not exist/);
  fails(one({ prop: "ctaLabel" }), /Hero\.ctaLabel cannot be bound/);                  // not every prop is bindable
  fails(one({ prop: "items" }), /Hero\.items cannot be bound/);
  fails(one({ sectionId: "foot-1", prop: "items" }), /Footer\.items cannot be bound/);
  fails(one({ id: "B 1" }), /not a valid id/);                                          // ids end up in attributes and request paths
  fails(one({ queryRef: "q\"><script>" }), /not a valid id|does not exist/);
  fails(one({ prop: "ti tle" }), /prop 'ti tle' is not valid/);
});

test("a page can use at most eight different queries", () => {
  const queries = Array.from({ length: MAX_DISTINCT_QUERIES + 1 }, (_, i) => ({ id: `q${i}`, dataSourceRef: "ds", mode: "READ", public: true }));
  const dataBindings = queries.map((q, i) => ({ id: `b${i}`, sectionId: "hero-1", prop: i % 2 ? "title" : "description", queryRef: q.id }));
  fails(schema({ queries, dataBindings }), /at most 8/);
  assert.equal(resolveBindings(schema({ queries, dataBindings: dataBindings.slice(0, MAX_DISTINCT_QUERIES) })).length, MAX_DISTINCT_QUERIES);
});

test("published markup: bound props and lists are annotated, the authored content stays as the fallback, the runtime script is the only script", () => {
  const s = schema(); const bindings = resolveBindings(s);
  const files = renderSitePages(s, {}, bindings);
  const home = files["index.html"];
  assert.match(home, /<main class="site" data-xw-runtime data-xw-root="\.\/">/);
  assert.match(home, /<h1 data-xw-bind="b-title" data-xw-q="q-title" data-xw-prop="title">Authored title<\/h1>/);
  assert.match(home, /<div class="product-grid" data-xw-list="b-items" data-xw-q="q-items">/);
  assert.match(home, /Authored item/);                                                  // the authored rows are still there when the runtime cannot run
  assert.match(home, /<template data-xw-row="b-items"><article class="product-card">.*data-xw-field="name".*data-xw-field="description".*<\/template>/s);
  assert.equal((home.match(/<script/g) ?? []).length, 1);
  assert.match(home, /<script src="\.\/_runtime\/page-runtime\.js" defer><\/script><\/body>/);
  assert.doesNotMatch(home, /data-xw-bind="b-body"/);                                   // that binding lives on the other page
  const about = files["about/index.html"];                                              // one level down: paths are relative to the site root
  assert.match(about, /data-xw-root="\.\.\/"/); assert.match(about, /<script src="\.\.\/_runtime\/page-runtime\.js" defer>/);
  assert.match(about, /<p data-xw-bind="b-body" data-xw-q="q-title" data-xw-prop="body">Body<\/p>/);
  assert.equal(PAGE_RUNTIME_PATH, "_runtime/page-runtime.js");
  assert.ok(!("404.html" in files && /data-xw|<script/.test(files["404.html"])));
  // the testimonials template is complete (stars, quote, author, location with its separator)
  const t = renderSitePages(schema({ dataBindings: [{ id: "b-voices", sectionId: "talk-1", prop: "items", queryRef: "q-items" }] }), {}, [{ id: "b-voices", sectionId: "talk-1", prop: "items", query: "q-items", kind: "list" }])["index.html"];
  assert.match(t, /data-xw-field="rating" data-xw-kind="stars"/); assert.match(t, /data-xw-field="quote"/); assert.match(t, /data-xw-field="location" data-xw-prefix=" · "/);
});

test("without bindings the published output has no trace of the runtime - and the Studio preview never gets one", () => {
  const s = schema();
  const plain = renderSitePages(s, {});
  for (const html of Object.values(plain)) assert.doesNotMatch(html, /data-xw|<script|<template/);
  assert.deepEqual(renderSitePages(s, {}, []), plain);
  const preview = renderSchemaDocument(s, { selectedId: null, interactive: false, bindings: resolveBindings(s) });      // not `published`: bindings are ignored
  assert.doesNotMatch(preview, /data-xw|<script|<template/);
  assert.equal(preview, renderSchemaDocument(s, { selectedId: null, interactive: false }));
});

test("authored text is escaped in the annotated markup (no attribute or markup injection through ids or content)", () => {
  const s = schema(); (s as { sections: { props: Record<string, string> }[] }).sections[0].props.title = `</h1><img src=x onerror=alert(1)>`;
  const home = renderSitePages(s, {}, resolveBindings(s))["index.html"];
  assert.doesNotMatch(home, /<img src=x/); assert.match(home, /&lt;\/h1&gt;&lt;img src=x onerror=alert\(1\)&gt;/);
});

test("the runtime script: same bytes every time, no eval, no HTML injection, no storage, no address of its own, no credentials, one attempt", () => {
  assert.equal(PAGE_RUNTIME_JS, PAGE_RUNTIME_JS);
  assert.ok(PAGE_RUNTIME_JS.length > 1500 && PAGE_RUNTIME_JS.length < 9000, `size ${PAGE_RUNTIME_JS.length}`);
  for (const bad of [/\beval\b/, /new Function/, /\bFunction\(/, /innerHTML/, /outerHTML/, /insertAdjacentHTML/, /document\.write/, /createContextualFragment/, /localStorage/, /sessionStorage/, /indexedDB/,
    /localhost/, /127\.0\.0\.1/, /host\.docker\.internal/, /\.internal\b/, /credentials:\s*"include"/, /document\.cookie/, /importScripts/, /\bimport\(/, /setInterval/, /XMLHttpRequest/, /WebSocket/, /sendBeacon/])
    assert.doesNotMatch(PAGE_RUNTIME_JS, bad, String(bad));
  assert.match(PAGE_RUNTIME_JS, /credentials: "omit"/);
  assert.ok(!PAGE_RUNTIME_JS.includes("`"));                                            // no template literal: nothing is interpolated
  assert.match(PAGE_RUNTIME_JS, /"use strict"/);
  assert.equal((PAGE_RUNTIME_JS.match(/\bfetch\(/g) ?? []).length, 1);                  // one place that talks to the network
  assert.equal((PAGE_RUNTIME_JS.match(/\.textContent\s*=/g) ?? []).length >= 3, true);
  assert.doesNotMatch(PAGE_RUNTIME_JS, /setTimeout\([^)]*retry/i);
});
