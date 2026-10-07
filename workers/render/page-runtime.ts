// The published PAGE_SCHEMA client runtime and the resolution of a page's data bindings (C2, V1 "option b", D-C0-35).
//
// A published page that has data bindings ships ONE small static script (`_runtime/page-runtime.js`, the same bytes in every release) which
//   1. reads the SAME-ORIGIN runtime config  <site root>/__factory/config.json
//   2. needs `apiBase` from it: absent / not an absolute same-origin http(s) URL  =>  NOT_READY (no localhost, no guessing, no internal host)
//   3. for every distinct query the page binds: POST {apiBase}/queries/{queryId}/run  with body {"params":{}}  (no cookies, no identifiers)
//   4. writes the result into the annotated elements with textContent only (never innerHTML, never eval, no dynamic script)
// Which query a binding calls is decided HERE, at publish time, from the immutable schema snapshot of the release; the browser never names a
// release, tenant, workspace or project, and the server checks the query against the release's public allow-list on every call.
import type { PageSchema } from "../../lib/http-types";
import type { RuntimeBinding } from "../../lib/schema-preview";

export const PAGE_RUNTIME_PATH = "_runtime/page-runtime.js";

/** Props of the registered components that a published page can bind (V1). Everything else is refused at publish time, never silently ignored. */
const SCALAR: Record<string, string[]> = {
  Navbar: ["brand"], Hero: ["eyebrow", "title", "description"], ProductGrid: ["heading"], TechnologySection: ["heading", "body"],
  ComparisonBlock: ["heading"], Testimonials: ["heading"], ContactForm: ["heading"], Footer: ["text"],
};
const LIST: Record<string, string[]> = { ProductGrid: ["items"], Testimonials: ["items"] };
export const MAX_DISTINCT_QUERIES = 8;
/** the shape of a definition id / section id (PageSchemaValidator.SECTION_ID) and of a prop name */
const ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const PROP = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

export class BindingError extends Error {}

type Doc = PageSchema & { dataBindings?: unknown; queries?: unknown; viewModels?: unknown; mappings?: unknown };
type Rec = Record<string, unknown>;
const recs = (v: unknown): Rec[] => (Array.isArray(v) ? v.filter((x): x is Rec => typeof x === "object" && x !== null) : []);
const text = (v: unknown) => (typeof v === "string" ? v : "");

/**
 * The bindings of a page document, each resolved to the LOCAL id of the query it calls. A binding is refused (BindingError, the build fails with
 * the reason) when its section does not exist, its component/prop cannot be bound, it names no resolvable query, or the query is not a public
 * READ query. The allow-list itself is enforced again by the server on every request.
 */
export function resolveBindings(schema: PageSchema): RuntimeBinding[] {
  const doc = schema as Doc;
  const bindings = recs(doc.dataBindings);
  if (bindings.length === 0) return [];
  const queries = new Map(recs(doc.queries).map((q) => [text(q.id), q]));
  const viewModels = new Map(recs(doc.viewModels).map((v) => [text(v.id), v]));
  const mappings = new Map(recs(doc.mappings).map((m) => [text(m.id), m]));
  const sections = new Map<string, { type: string }>();
  for (const s of schema.sections ?? []) sections.set(s.id, { type: s.type });
  for (const p of schema.pages ?? []) for (const s of p.sections ?? []) sections.set(s.id, { type: s.type });

  const out: RuntimeBinding[] = [];
  const distinct = new Set<string>();
  for (const b of bindings) {
    const id = text(b.id), sectionId = text(b.sectionId), prop = text(b.prop);
    const fail = (why: string): never => { throw new BindingError(`binding '${id}': ${why}`); };
    // ids are written into attributes and into a request path: only the shape the app definition itself allows
    for (const [name, v] of [["id", id], ["sectionId", sectionId]] as const) if (!ID.test(v)) fail(`${name} '${v}' is not a valid id`);
    if (!PROP.test(prop)) fail(`prop '${prop}' is not valid`);
    const section = sections.get(sectionId) ?? fail(`section '${sectionId}' does not exist`);
    const list = (LIST[section.type] ?? []).includes(prop), scalar = (SCALAR[section.type] ?? []).includes(prop);
    if (!list && !scalar) fail(`${section.type}.${prop} cannot be bound to data`);
    let query = text(b.queryRef);
    if (!query && text(b.viewModelRef)) {
      const vm = viewModels.get(text(b.viewModelRef)) ?? fail(`view model '${text(b.viewModelRef)}' does not exist`);
      query = text(vm.queryRef) || text(mappings.get(text(vm.mappingRef))?.queryRef);
    }
    if (!query) fail("it names no query (queryRef, or a view model that has one)");
    if (!ID.test(query)) fail(`query id '${query}' is not a valid id`);
    const q = queries.get(query) ?? fail(`query '${query}' does not exist`);
    if (q.public !== true) fail(`query '${query}' is not public: mark it public in the app definition or remove the binding`);
    if (q.mode !== undefined && q.mode !== "READ") fail(`query '${query}' is not a READ query`);
    distinct.add(query);
    out.push({ id, sectionId, prop, query, kind: list ? "list" : "text" });
  }
  if (distinct.size > MAX_DISTINCT_QUERIES) throw new BindingError(`a page can use at most ${MAX_DISTINCT_QUERIES} different queries, it uses ${distinct.size}`);
  return out;
}

/** The runtime itself. Plain script: no template placeholders, no backticks. Behaviour is pinned by tests/browser/page-runtime.spec.mjs. */
export const PAGE_RUNTIME_JS = String.raw`(function () {
  "use strict";
  var root = document.querySelector("[data-xw-runtime]");
  if (!root) return;
  var MAX_QUERIES = 8, MAX_ROWS = 200, TIMEOUT_MS = 15000;
  var up = root.getAttribute("data-xw-root") || "./";

  function state(s, detail) {
    root.setAttribute("data-xw-state", s);
    if (detail) root.setAttribute("data-xw-detail", detail); else root.removeAttribute("data-xw-detail");
    root.setAttribute("aria-busy", s === "loading-config" || s === "loading-data" ? "true" : "false");
    try { root.dispatchEvent(new CustomEvent("xw:state", { detail: { state: s, detail: detail || null } })); } catch (e) {}
  }
  function mark(el, s, code) {
    el.setAttribute("data-xw-state", s);
    if (code) el.setAttribute("data-xw-error", code); else el.removeAttribute("data-xw-error");
  }
  function own(o, k) { return o !== null && typeof o === "object" && Object.prototype.hasOwnProperty.call(o, k); }
  function str(v) { return v === null || v === undefined ? "" : (typeof v === "string" ? v : (typeof v === "number" || typeof v === "boolean" ? String(v) : "")); }

  // one attempt, bounded in time, never throws; resolves { status, body } or { error: "timeout" | "network" }
  function request(url, init) {
    return new Promise(function (resolve) {
      var done = false, ctl = typeof AbortController === "function" ? new AbortController() : null;
      function finish(x) { if (done) return; done = true; clearTimeout(timer); resolve(x); }
      var timer = setTimeout(function () { if (ctl) ctl.abort(); finish({ error: "timeout" }); }, TIMEOUT_MS);
      if (ctl) init.signal = ctl.signal;
      fetch(url, init).then(function (r) {
        return r.text().then(function (t) {
          var b = null;
          try { b = t ? JSON.parse(t) : null; } catch (e) { b = undefined; }
          finish({ status: r.status, body: b });
        });
      }, function () { finish({ error: "network" }); });
    });
  }
  function category(x) {
    if (x.error) return x.error;
    var s = x.status;
    if (s === 401 || s === 403) return "forbidden";
    if (s === 404) return "not-found";
    if (s === 429) return "rate-limited";
    if (s >= 500) return "unavailable";
    if (s >= 200 && s < 300) return x.body && x.body.result && Array.isArray(x.body.result.rows) ? "ok" : "invalid-response";
    return "http-" + s;
  }
  // an absolute http(s) URL on THIS origin, without credentials or fragment; anything else is not usable
  function parseBase(v) {
    if (typeof v !== "string" || v === "") return { error: "api-base-missing" };
    var u;
    try { u = new URL(v); } catch (e) { return { error: "api-base-invalid" }; }
    if ((u.protocol !== "http:" && u.protocol !== "https:") || u.username || u.password || u.hash) return { error: "api-base-invalid" };
    if (u.origin !== location.origin) return { error: "api-base-cross-origin" };
    return { base: v.replace(/\/+$/, "") };
  }
  function findTemplate(id) {
    var all = document.querySelectorAll("template[data-xw-row]");
    for (var i = 0; i < all.length; i++) if (all[i].getAttribute("data-xw-row") === id) return all[i];
    return null;
  }
  function applyText(b, result) {
    var row = result.rows[0];
    if (row === undefined) { mark(b.el, "empty"); return ""; }
    var prop = b.el.getAttribute("data-xw-prop"), keys = row !== null && typeof row === "object" ? Object.keys(row) : [], v;
    if (own(row, prop)) v = row[prop];
    else if (keys.length === 1) v = row[keys[0]];
    else { mark(b.el, "error", "field-missing"); return "field-missing"; }
    b.el.textContent = str(v);
    mark(b.el, "ready");
    return "";
  }
  function applyList(b, result) {
    var tpl = findTemplate(b.el.getAttribute("data-xw-list"));
    if (!tpl) { mark(b.el, "error", "template-missing"); return "template-missing"; }
    var rows = result.rows.slice(0, MAX_ROWS), frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      var item = tpl.content.cloneNode(true), fields = item.querySelectorAll("[data-xw-field]");
      for (var i = 0; i < fields.length; i++) {
        var f = fields[i], v = own(row, f.getAttribute("data-xw-field")) ? row[f.getAttribute("data-xw-field")] : "";
        if (f.getAttribute("data-xw-kind") === "stars") { var n = Math.max(0, Math.min(5, Math.round(Number(v) || 0))); f.textContent = new Array(n + 1).join("★"); }
        else { var t = str(v); f.textContent = t !== "" && f.getAttribute("data-xw-prefix") ? f.getAttribute("data-xw-prefix") + t : t; }
      }
      frag.appendChild(item);
    });
    b.el.textContent = "";
    b.el.appendChild(frag);
    if (result.rows.length > MAX_ROWS) b.el.setAttribute("data-xw-truncated", "1"); else b.el.removeAttribute("data-xw-truncated");
    mark(b.el, rows.length === 0 ? "empty" : "ready");
    return "";
  }

  function run(base) {
    var groups = {}, order = [], found = document.querySelectorAll("[data-xw-bind],[data-xw-list]");
    for (var i = 0; i < found.length; i++) {
      var el = found[i], q = el.getAttribute("data-xw-q");
      if (!q) { mark(el, "error", "query-missing"); continue; }
      if (!groups[q]) { groups[q] = []; order.push(q); }
      groups[q].push({ el: el, list: el.hasAttribute("data-xw-list") });
    }
    if (order.length === 0) { state("ready"); return; }
    if (order.length > MAX_QUERIES) { state("error", "too-many-queries"); return; }
    state("loading-data");
    var failed = [];
    Promise.all(order.map(function (q) {
      return request(base + "/queries/" + encodeURIComponent(q) + "/run",
        { method: "POST", cache: "no-store", credentials: "omit", headers: { "Content-Type": "application/json", "Accept": "application/json" }, body: "{\"params\":{}}" })
        .then(function (x) {
          var cat = category(x);
          var problem = cat === "ok" ? "" : cat;
          groups[q].forEach(function (b) {
            if (cat !== "ok") { mark(b.el, "error", cat); return; }
            var code = "";
            try { code = b.list ? applyList(b, x.body.result) : applyText(b, x.body.result); } catch (e) { mark(b.el, "error", "render-failed"); code = "render-failed"; }
            if (code && !problem) problem = code;
          });
          if (problem) failed.push(q + ":" + problem);
        });
    })).then(function () { if (failed.length) state("error", failed.join(",")); else state("ready"); });
  }

  state("loading-config");
  request(new URL(up + "__factory/config.json", location.href).href, { cache: "no-store", headers: { "Accept": "application/json" } }).then(function (c) {
    var cfg = c && c.status === 200 && c.body && typeof c.body === "object" ? c.body : null;
    if (!cfg) { state("not-ready", "config-unavailable"); return; }
    var p = parseBase(cfg.apiBase);
    if (p.error) { state("not-ready", p.error); return; }
    run(p.base);
  });
})();
`;
