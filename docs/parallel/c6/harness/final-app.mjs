#!/usr/bin/env node
// C6 FINAL RC — JOURNEY 04: build an app from zero (API level, real c0rc backend, real PostgreSQL). Every write is confirmed with a SEPARATE fresh-session GET (never the 200 alone).
// Fixtures: product routes only (final-jcommon.getFixtures). No SQL, no test hook. Evidence class REAL_BACKEND_E2E.
import { getFixtures, recorder, S, st, eq, canon, sleep, call429, tag, activations } from "./final-jcommon.mjs";
const { rec, save } = recorder("journey04-app");
const F = await getFixtures(); const { f, wa, ed, vw, ta2 } = F;
const W = f.W1, BASE = `/api/v1/workspaces/${W}/projects`;
const fresh = async (u) => { const s = new S(); const l = await s.login(u.name, f[u.key ?? "ed"].p); if (l.status !== 200) throw new Error("relogin " + st(l)); return s; };
const edFresh = () => fresh({ name: ed.name, key: "ed" });
const get = async (s, P) => (await s.call("GET", `${BASE}/${P}/schema`));
const vers = async (s, P) => (await s.call("GET", `${BASE}/${P}/versions`)).json ?? [];
const patch = (s, P, rev, operations, summary = "c6 j04") => s.call("PATCH", `${BASE}/${P}/schema`, { expectedRevision: rev, operations, summary });
const secs = (doc, page = "home") => (page === "home" ? doc?.sections : doc?.pages?.find((p) => p.id === page)?.sections) ?? [];
try {
  // ---------------------------------------------------------------- 1. create the app (project, PAGE_SCHEMA) as an EDITOR
  let r = await ed.s.call("POST", BASE, { name: `c6f-j04-${tag}`, appType: "PAGE_SCHEMA" }); const P = r.json?.id;
  rec("J04-01", "create", "workspace EDITOR creates an app (project, appType PAGE_SCHEMA)", "201 with id, appType PAGE_SCHEMA", `${st(r)} appType=${r.json?.appType}`, r.status === 201 && !!P && r.json?.appType === "PAGE_SCHEMA");
  let s2 = await edFresh(); r = await s2.call("GET", `${BASE}/${P}`); const listed = await s2.call("GET", BASE);
  rec("J04-01b", "create", "fresh session reads the project and finds it in the workspace list (separate GET, not the 201)", "200 and listed", `${st(r)} listed=${JSON.stringify(listed.json).includes(P)}`, r.status === 200 && r.json?.name === `c6f-j04-${tag}` && JSON.stringify(listed.json).includes(P));
  let g = await get(s2, P); const rev0 = g.json?.revision; const doc0 = g.json?.schema; const v0 = (await vers(s2, P)).length;
  rec("J04-02", "schema", "initial schema exists (revision, sections, version 1)", "200, revision number, >=1 sections, >=1 version", `${st(g)} rev=${rev0} sections=${secs(doc0).length} versions=${v0}`, g.status === 200 && Number.isInteger(rev0) && secs(doc0).length >= 1 && v0 >= 1);

  // ---------------------------------------------------------------- 2. page + Hero + Form + Table + List via typed patch operations
  const reg = await ed.s.call("GET", "/api/v1/components"); const regIds = (reg.json?.items ?? reg.json ?? []).map?.((c) => c.id) ?? [];
  rec("J04-03a", "registry", "component registry lists Hero, ContactForm, ProductGrid (list), ComparisonBlock (table)", "all four ACTIVE in the registry", `${st(reg)} ids=${regIds.join(",").slice(0, 120)}`, ["Hero", "ContactForm", "ProductGrid", "ComparisonBlock"].every((x) => regIds.includes(x)));
  let rev = rev0;
  r = await patch(ed.s, P, rev, [{ type: "ADD_PAGE", pageId: "offers", props: { slug: "offers", title: "Offers" } }]);
  let gg = await get(await edFresh(), P);
  rec("J04-04", "page", "ADD_PAGE: new page is persisted (fresh GET shows pages[offers], revision +1)", "200; GET pages has offers; revision = old+1", `${st(r)} rev ${rev}->${gg.json?.revision} pages=${(gg.json?.schema?.pages ?? []).map((p) => p.id)}`, r.status === 200 && gg.json?.revision === rev + 1 && !!gg.json?.schema?.pages?.some((p) => p.id === "offers"));
  rev = gg.json?.revision;
  r = await patch(ed.s, P, rev, [
    { type: "ADD_SECTION", pageId: "offers", sectionId: "hero-j", sectionType: "Hero", props: { title: "Welcome", description: "From zero", ctaLabel: "Go" } },
    { type: "ADD_SECTION", pageId: "offers", sectionId: "grid-j", sectionType: "ProductGrid", props: { heading: "Items", items: [{ id: "p1", name: "One" }] } },
    { type: "ADD_SECTION", pageId: "offers", sectionId: "table-j", sectionType: "ComparisonBlock", props: { heading: "Compare", columns: ["A", "B"], rows: [{ id: "r1", label: "Price", values: ["1", "2"] }] } },
    { type: "ADD_SECTION", pageId: "offers", sectionId: "form-j", sectionType: "ContactForm", props: { heading: "Request", submitLabel: "Send" } },
  ], "c6 add components");
  gg = await get(await edFresh(), P); const off = secs(gg.json?.schema, "offers");
  rec("J04-05", "components", "Hero + List(ProductGrid) + Table(ComparisonBlock) + Form(ContactForm) added in ONE patch; order and registry componentVersion persisted", "200; offers sections = hero-j,grid-j,table-j,form-j with componentVersion", `${st(r)} ${r.status === 200 ? "" : r.text.slice(0, 200)} sections=${off.map((x) => x.id + ":" + x.componentVersion)}`, r.status === 200 && off.map((x) => x.id).join() === "hero-j,grid-j,table-j,form-j" && off.every((x) => x.componentVersion) && gg.json?.revision === rev + 1);
  rev = gg.json?.revision;
  // content
  r = await patch(ed.s, P, rev, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "Hello C6" }, { type: "UPDATE_SECTION", sectionId: "form-j", props: { submitLabel: "Submit request" } }], "c6 content");
  gg = await get(await edFresh(), P);
  rec("J04-06", "content", "edit content (UPDATE_PROP / UPDATE_SECTION) is really stored", "fresh GET: hero title 'Hello C6', form submitLabel 'Submit request'", `${st(r)} title=${secs(gg.json?.schema, "offers")[0]?.props?.title} submit=${secs(gg.json?.schema, "offers")[3]?.props?.submitLabel}`, r.status === 200 && secs(gg.json?.schema, "offers")[0]?.props?.title === "Hello C6" && secs(gg.json?.schema, "offers")[3]?.props?.submitLabel === "Submit request");
  rev = gg.json?.revision;
  // design
  r = await patch(ed.s, P, rev, [{ type: "UPDATE_THEME", definition: { colors: { primary: "#1A73E8" }, fontFamily: "ROUNDED", radius: "LG" } }], "c6 design");
  gg = await get(await edFresh(), P);
  rec("J04-07", "design", "edit design (UPDATE_THEME: colour, font, radius) is stored", "fresh GET theme.colors.primary #1A73E8, fontFamily ROUNDED", `${st(r)} theme=${JSON.stringify(gg.json?.schema?.theme)}`, r.status === 200 && gg.json?.schema?.theme?.fontFamily === "ROUNDED" && gg.json?.schema?.theme?.colors?.primary === "#1A73E8");
  rev = gg.json?.revision;
  const rThemeBad = await patch(ed.s, P, rev, [{ type: "UPDATE_THEME", definition: { colors: { primary: "url(javascript:x)" }, fontFamily: "COMIC" } }], "c6 bad theme");
  const gAfterBad = await get(await edFresh(), P);
  rec("J04-07b", "design", "a theme carrying CSS/script-like values or an unknown font is refused and nothing is written", "4xx; revision unchanged; theme unchanged", `${st(rThemeBad)} rev=${gAfterBad.json?.revision}`, rThemeBad.status >= 400 && rThemeBad.status < 500 && gAfterBad.json?.revision === rev && eq(gAfterBad.json?.schema?.theme, gg.json?.schema?.theme));
  // data binding (declarative: slot, READ query, mapping, view model, binding to the List section)
  r = await patch(ed.s, P, rev, [
    { type: "ADD_DATA_SOURCE", definition: { id: "main", name: "Main", type: "postgres" } },
    { type: "ADD_QUERY", definition: { id: "items-q", name: "Items", dataSourceRef: "main", mode: "READ", operationKey: "shop.items", params: [], maxRows: 50 } },
    { type: "ADD_MAPPING", definition: { id: "items-m", queryRef: "items-q", fields: [{ from: "name", to: "name" }, { from: "description", to: "description" }] } },
    { type: "ADD_VIEW_MODEL", definition: { id: "items-vm", name: "Items", queryRef: "items-q", mappingRef: "items-m", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }, { name: "description", type: "STRING" }] } },
    { type: "ADD_DATA_BINDING", definition: { id: "grid-bind", sectionId: "grid-j", prop: "items", viewModelRef: "items-vm" } },
  ], "c6 bind data");
  gg = await get(await edFresh(), P); const d = gg.json?.schema;
  rec("J04-08", "data-binding", "bind data: slot + READ query + mapping + view model + dataBinding(grid-j.items) persisted", "200; GET has dataSources/queries/mappings/viewModels/dataBindings", `${st(r)} ${r.status === 200 ? "" : r.text.slice(0, 240)} ds=${d?.dataSources?.length} q=${d?.queries?.length} m=${d?.mappings?.length} vm=${d?.viewModels?.length} b=${d?.dataBindings?.length}`, r.status === 200 && d?.dataBindings?.[0]?.sectionId === "grid-j" && d?.queries?.length === 1 && d?.viewModels?.length === 1);
  rev = gg.json?.revision;
  // dangling binding refused
  const dang = await patch(ed.s, P, rev, [{ type: "ADD_DATA_BINDING", definition: { id: "bad-bind", sectionId: "no-such-section", prop: "items", viewModelRef: "items-vm" } }], "c6 dangling");
  const gd = await get(await edFresh(), P);
  rec("J04-08b", "data-binding", "binding to a section that does not exist is refused (dangling reference), nothing written", "4xx; revision unchanged; bindings still 1", `${st(dang)} rev=${gd.json?.revision} bindings=${gd.json?.schema?.dataBindings?.length}`, dang.status >= 400 && dang.status < 500 && gd.json?.revision === rev && gd.json?.schema?.dataBindings?.length === 1);
  // action
  r = await patch(ed.s, P, rev, [
    { type: "ADD_DATA_SOURCE", definition: { id: "main-rw", name: "Main RW", type: "postgres" } },
    { type: "ADD_QUERY", definition: { id: "req-create", name: "Create request", dataSourceRef: "main-rw", mode: "WRITE", operationKey: "requests.create", params: [{ name: "title", type: "STRING" }] } },
    { type: "ADD_ACTION", definition: { id: "send-request", name: "Send request", type: "CREATE_RECORD", queryRef: "req-create", trigger: { sectionId: "form-j", event: "onSubmit" }, inputs: [{ name: "title", type: "STRING", required: true }], inputMapping: { title: { source: "FORM_FIELD", name: "title" } } } },
  ], "c6 action");
  gg = await get(await edFresh(), P);
  rec("J04-09", "action", "configure an action (CREATE_RECORD on form-j.onSubmit, FORM_FIELD mapping) is persisted", "200; GET actions[send-request] with trigger form-j.onSubmit", `${st(r)} ${r.status === 200 ? "" : r.text.slice(0, 240)} actions=${JSON.stringify(gg.json?.schema?.actions?.map((a) => a.id + ":" + a.type))}`, r.status === 200 && gg.json?.schema?.actions?.[0]?.trigger?.sectionId === "form-j");
  const actBad = await patch(ed.s, P, gg.json.revision, [{ type: "ADD_ACTION", definition: { id: "bad-act", name: "x", type: "CREATE_RECORD", queryRef: "req-create", trigger: { sectionId: "form-j", event: "onClick" } } }], "c6 action on an event the component does not offer");
  const gBad = await get(await edFresh(), P);
  rec("J04-09b", "action", "an action on an event the component does not declare (ContactForm has onSubmit only) is refused or stored as unusable - no half write", "4xx and revision unchanged (PASS) / 200 = accepted although the component offers no onClick (reported)", `${st(actBad)} rev ${gg.json.revision}->${gBad.json?.revision}`, actBad.status >= 400 && actBad.status < 500 && gBad.json?.revision === gg.json.revision, { note: actBad.status === 200 ? "accepted: validator does not check trigger.event against the component overlay" : "" });
  rev = gBad.json.revision;

  // ---------------------------------------------------------------- 3. save / reload / persistence
  const sA = await edFresh(); const A = await get(sA, P); const vl = await vers(sA, P);
  const lastPatchResp = (await patch(ed.s, P, rev, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "description", value: "Final copy" }], "c6 final save"));
  const B = await get(await edFresh(), P);
  rec("J04-10", "persistence", "final save returns the document that a FRESH session later reads (deep-equal), revision incremented by exactly 1", "response.schema == fresh GET schema; revision = prev+1", `${st(lastPatchResp)} rev ${rev}->${lastPatchResp.json?.revision} / fresh ${B.json?.revision}`, lastPatchResp.status === 200 && eq(lastPatchResp.json?.schema, B.json?.schema) && B.json?.revision === rev + 1 && lastPatchResp.json?.revision === B.json?.revision);
  const vl2 = await vers(await edFresh(), P); const nums = vl2.map((v) => v.versionNumber);
  rec("J04-11", "versions", "version list: strictly one immutable version per accepted edit, newest flagged latest, all kinds EDIT/INITIAL", `versions grew from ${vl.length} to ${vl.length + 1}; numbers unique & consecutive`, `before=${vl.length} after=${vl2.length} nums=${nums.slice(0, 15)}`, vl2.length === vl.length + 1 && new Set(nums).size === nums.length && vl2[0]?.current === true && vl2.filter((v) => v.current).length === 1 && Math.max(...nums) - Math.min(...nums) + 1 === nums.length);
  // immutability of a version snapshot
  const vInit = vl2[vl2.length - 1]; const vi1 = (await (await edFresh()).call("GET", `${BASE}/${P}/versions/${vInit.id}`)).json;
  rec("J04-11b", "versions", "the INITIAL version's snapshot still equals the original document (immutable, unaffected by 10 later edits)", "snapshot == doc0", `v${vInit.versionNumber} kind=${vInit.kind}`, eq(vi1?.schema ?? vi1?.schemaSnapshot ?? vi1?.snapshot, doc0), { note: Object.keys(vi1 ?? {}).join(",") });
  // device preview payloads
  rec("J04-12", "preview", "preview payloads for desktop/tablet/mobile", "a preview route exists, else stated", "no server route: backend has only published-site routes (/sites/_preview/{token}, tied to a publish) and template/package preview images; the device viewports are a CLIENT-SIDE Studio concern", null, { note: "BLOCKED: nothing to call at the API; the UI half (viewport switch) is MANUAL territory of the UI workstream" });

  // ---------------------------------------------------------------- 4. concurrent edit: same expectedRevision from two sessions
  const sa1 = await edFresh(), sb1 = await edFresh(); const cur = await get(sa1, P); const cr = cur.json.revision; const vBefore = (await vers(sa1, P)).length;
  const [ra, rb] = await Promise.all([patch(sa1, P, cr, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "WRITER-A" }], "c6 A"), patch(sb1, P, cr, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "WRITER-B" }], "c6 B")]);
  const codes = [ra, rb].map((x) => x.status).sort(); const loser = [ra, rb].find((x) => x.status !== 200); const winner = [ra, rb].find((x) => x.status === 200);
  const fin = await get(await edFresh(), P); const vAfter = (await vers(sa1, P)).length; const ttl = secs(fin.json?.schema, "offers")[0]?.props?.title;
  rec("J04-13", "concurrency", "two sessions patch with the SAME expectedRevision: exactly one 200, the other 409 REVISION_CONFLICT (with currentRevision)", "[200,409]; loser code REVISION_CONFLICT", `${codes} loser=${loser?.json?.code} currentRevision=${loser?.json?.details?.currentRevision}`, codes.join() === "200,409" && loser?.json?.code === "REVISION_CONFLICT");
  rec("J04-13b", "concurrency", "stored document is intact: exactly the winner's value, revision +1, ONE new version (no lost update / torn write), loser's change absent", "title == winner's value; rev = cr+1; versions +1", `title=${ttl} rev ${cr}->${fin.json?.revision} versions ${vBefore}->${vAfter}`, ttl === (winner === ra ? "WRITER-A" : "WRITER-B") && fin.json?.revision === cr + 1 && vAfter === vBefore + 1 && JSON.stringify(fin.json?.schema).includes(winner === ra ? "WRITER-A" : "WRITER-B") && !JSON.stringify(fin.json?.schema).includes(winner === ra ? "WRITER-B" : "WRITER-A"));
  // a burst of 8 concurrent writers
  const cr2 = fin.json.revision; const burst = await Promise.all(Array.from({ length: 8 }, (_, i) => patch([sa1, sb1][i % 2], P, cr2, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "description", value: `burst-${i}` }], "c6 burst")));
  const win = burst.filter((x) => x.status === 200).length, conf = burst.filter((x) => x.status === 409).length; const fb = await get(await edFresh(), P);
  rec("J04-13c", "concurrency", "8 simultaneous writers on one revision: exactly 1 wins, 7 get 409, revision +1, no 5xx", "1x200, 7x409", `200:${win} 409:${conf} other:${burst.length - win - conf}; rev ${cr2}->${fb.json?.revision}`, win === 1 && conf === 7 && fb.json?.revision === cr2 + 1);
  rev = fb.json.revision;

  // ---------------------------------------------------------------- 5. restore an old version
  const vl3 = await vers(await edFresh(), P); const target = vl3.find((v) => v.kind === "EDIT" && v.summary === "c6 add components") ?? vl3[vl3.length - 2];
  const tDetail = (await (await edFresh()).call("GET", `${BASE}/${P}/versions/${target.id}`)).json; const snap = tDetail?.schema ?? tDetail?.schemaSnapshot ?? tDetail?.snapshot;
  const stale = await ed.s.call("POST", `${BASE}/${P}/versions/${target.id}/restore`, { expectedRevision: rev - 1 }); const gs = await get(await edFresh(), P);
  rec("J04-14a", "restore", "restore with a stale expectedRevision is refused (409) and changes nothing", "409; revision unchanged", `${st(stale)} rev=${gs.json?.revision}`, stale.status === 409 && gs.json?.revision === rev);
  const rr = await ed.s.call("POST", `${BASE}/${P}/versions/${target.id}/restore`, { expectedRevision: rev }); const gr = await get(await edFresh(), P); const vl4 = await vers(await edFresh(), P);
  rec("J04-14", "restore", "restore version v" + target.versionNumber + ": document == that version's snapshot (fresh GET), revision +1", "200; GET schema deep-equals the old snapshot", `${st(rr)} rev ${rev}->${gr.json?.revision}`, rr.status === 200 && eq(gr.json?.schema, snap) && gr.json?.revision === rev + 1);
  rec("J04-14b", "restore", "history keeps BOTH: the old version untouched and a NEW RESTORE version pointing at it (no history rewrite)", `versions ${vl3.length}->${vl3.length + 1}; newest kind RESTORE, restoredFromVersionId == old id`, `${vl3.length}->${vl4.length} newest=${vl4[0]?.kind} from=${vl4[0]?.restoredFromVersionId === target.id}`, vl4.length === vl3.length + 1 && vl4[0]?.kind === "RESTORE" && vl4[0]?.restoredFromVersionId === target.id && vl4.some((v) => v.id === target.id));
  const again = (await (await edFresh()).call("GET", `${BASE}/${P}/versions/${target.id}`)).json;
  rec("J04-14c", "restore", "the restored (old) version snapshot is byte-identical after the restore (immutable)", "equal", "", eq(again?.schema ?? again?.schemaSnapshot ?? again?.snapshot, snap));
  const nov = await ed.s.call("POST", `${BASE}/${P}/versions/00000000-0000-0000-0000-000000000000/restore`, { expectedRevision: gr.json.revision });
  rec("J04-14d", "restore", "restore of a non-existent version", "404 VERSION_NOT_FOUND", st(nov), nov.status === 404);
  rev = gr.json.revision;

  // ---------------------------------------------------------------- 6. invalid operations: 4xx and NO partial write
  const baseDoc = (await get(await edFresh(), P)).json; const bad = async (id, desc, ops, accept = (x) => x.status >= 400 && x.status < 500, extra = {}) => {
    const body = { expectedRevision: baseDoc.revision, operations: ops, summary: "c6 invalid", ...extra };
    const x = await ed.s.call("PATCH", `${BASE}/${P}/schema`, body); const after = (await get(await edFresh(), P)).json; const vN = (await vers(ed.s, P)).length;
    rec(id, "invalid-ops", desc, "4xx and document/revision/version-count unchanged", `${st(x)} ${x.json?.message ? String(x.json.message).slice(0, 90) : ""} | rev ${baseDoc.revision}->${after.revision}`, accept(x) && after.revision === baseDoc.revision && eq(after.schema, baseDoc.schema) && vN === vl4.length + 0, { note: `versions=${vN}` });
  };
  await bad("J04-15a", "unknown operation type", [{ type: "DROP_EVERYTHING" }]);
  await bad("J04-15b", "ADD_SECTION with a component that is not in the registry", [{ type: "ADD_SECTION", sectionType: "EvilWidget", props: {} }]);
  await bad("J04-15c", "ADD_SECTION of a component that is not ACTIVE / does not exist at all with a forged componentVersion field on the operation", [{ type: "ADD_SECTION", sectionType: "Hero", props: {}, componentVersion: "99.99.99" }]);
  await bad("J04-15d", "UPDATE_PROP on a section that does not exist", [{ type: "UPDATE_PROP", sectionId: "nope", path: "title", value: "x" }]);
  await bad("J04-15e", "UPDATE_PROP with a prototype-pollution path", [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "__proto__.x", value: "x" }]);
  await bad("J04-15f", "prop violating the component's propsSchema (title as number / object)", [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: { a: 1 } }]);
  await bad("J04-15g", "prop value that is a script / javascript: URL in a link-like prop", [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "ctaHref", value: "javascript:alert(1)" }]);
  await bad("J04-15h", "more than 50 operations in one request", Array.from({ length: 51 }, (_, i) => ({ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "v" + i })));
  await bad("J04-15i", "empty operation list", []);
  await bad("J04-15j", "duplicate section id", [{ type: "ADD_SECTION", pageId: "offers", sectionId: "hero-j", sectionType: "Hero", props: {} }]);
  { // unknown field inside an operation / body: Jackson drops it. Accepted-and-ignored is only acceptable if nothing of it is stored.
    const before = (await get(await edFresh(), P)).json;
    const x = await ed.s.call("PATCH", `${BASE}/${P}/schema`, { expectedRevision: before.revision, operations: [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "unk-field-ok", script: "alert(1)", tenantId: f.T2 }], tenantId: f.T2, userId: wa.id, summary: "c6 unknown fields" });
    const after = (await get(await edFresh(), P)).json; const txt = JSON.stringify(after.schema);
    rec("J04-15k", "invalid-ops", "unknown fields (script, tenantId, userId) inside the operation / body are NOT honoured: nothing of them stored, tenant/user not taken from the body", "rejected 4xx, or 200 where only the legitimate op is applied and no foreign field is stored", `${st(x)} rev ${before.revision}->${after.revision} stored=${/alert\(1\)|script/.test(txt.replace(/"description":"[^"]*"/g, ""))}`, (x.status >= 400 && x.status < 500 && after.revision === before.revision) || (x.status === 200 && after.revision === before.revision + 1 && !/alert\(1\)/.test(txt) && !txt.includes(f.T2)), { note: x.status === 200 ? "P3 observation: strict parsing is NOT applied to PATCH /schema (unlike app-runtime routes): unknown operation fields are silently dropped; the title op WAS applied" : "" });
  }
  baseDoc.revision = (await get(await edFresh(), P)).json.revision; baseDoc.schema = (await get(await edFresh(), P)).json.schema; vl4.length = (await vers(ed.s, P)).length;
  // mixed: a valid first operation + invalid second -> neither may persist
  { const x = await ed.s.call("PATCH", `${BASE}/${P}/schema`, { expectedRevision: baseDoc.revision, operations: [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "PARTIAL-WRITE-MARK" }, { type: "ADD_SECTION", sectionType: "EvilWidget", props: {} }], summary: "c6 mixed" });
    const after = (await get(await edFresh(), P)).json;
    rec("J04-16", "invalid-ops", "atomicity: valid op + invalid op in one request -> 4xx and the valid op is NOT persisted", "4xx; no PARTIAL-WRITE-MARK in the stored document", `${st(x)} mark=${JSON.stringify(after.schema).includes("PARTIAL-WRITE-MARK")} rev ${baseDoc.revision}->${after.revision}`, x.status >= 400 && x.status < 500 && !JSON.stringify(after.schema).includes("PARTIAL-WRITE-MARK") && after.revision === baseDoc.revision); }
  { const x = await ed.s.call("PATCH", `${BASE}/${P}/schema`, { operations: [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "x" }] });
    rec("J04-16b", "invalid-ops", "missing expectedRevision", "400", st(x), x.status === 400); }
  { const cur = secs(baseDoc.schema, "offers")[0]?.props?.title; const x = await patch(ed.s, P, baseDoc.revision, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: cur }], "noop"); const after = (await get(await edFresh(), P)).json;
    rec("J04-16c", "invalid-ops", "a no-op patch (nothing changes) is refused, no empty version", "400 NO_CHANGE; revision unchanged", `${st(x)} rev ${baseDoc.revision}->${after.revision}`, x.status === 400 && x.json?.code === "NO_CHANGE" && after.revision === baseDoc.revision); }

  // ---------------------------------------------------------------- 7. authorization on the app routes (allowed + denied at the API)
  r = await wa.s.call("POST", `${BASE}/${P}/members`, { username: vw.name, role: "VIEWER" }); const vwSess = vw.s;
  const vwPatch = await patch(vwSess, P, baseDoc.revision, [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "VIEWER-WRITE" }]); const vwGet = await vwSess.call("GET", `${BASE}/${P}/schema`);
  rec("J04-17a", "authz", "project VIEWER can read the schema (200) but cannot patch (403)", "GET 200; PATCH 403", `member add ${r.status}; GET ${st(vwGet)} PATCH ${st(vwPatch)}`, vwGet.status === 200 && vwPatch.status === 403);
  const nonMember = await vw.s.call("GET", `/api/v1/workspaces/${W}/projects/${P}/schema`); // vw is now a member; use ta2 for the foreign tenant
  const foreign = await ta2.s.call("GET", `/api/v1/workspaces/${f.W2}/projects/${P}/schema`); const foreign2 = await ta2.s.call("GET", `${BASE}/${P}/schema`); const foreignPatch = await ta2.s.call("PATCH", `${BASE}/${P}/schema`, { expectedRevision: baseDoc.revision, operations: [{ type: "UPDATE_PROP", sectionId: "hero-j", path: "title", value: "X-TENANT" }] });
  rec("J04-17b", "authz", "other tenant: GET via its own workspace id and via the owner's workspace id, and PATCH -> 404 (no existence leak)", "404/404/404", `${st(foreign)} / ${st(foreign2)} / ${st(foreignPatch)}`, [foreign, foreign2, foreignPatch].every((x) => x.status === 404));
  const ws2 = await wa.s.call("GET", `/api/v1/workspaces/${f.W1b}/projects/${P}/schema`); rec("J04-17c", "authz", "a workspace admin of workspace A reading the project through ANOTHER workspace (same tenant) -> 404/403", "404 or 403", st(ws2), [403, 404].includes(ws2.status));
  const anon = await new S().call("GET", `${BASE}/${P}/schema`); rec("J04-17d", "authz", "anonymous", "401", st(anon), anon.status === 401);
  const afterAuth = (await get(await edFresh(), P)).json; rec("J04-17e", "authz", "none of the denied writes changed the document", "revision unchanged", `rev ${baseDoc.revision}->${afterAuth.revision}`, afterAuth.revision === baseDoc.revision && !JSON.stringify(afterAuth.schema).includes("X-TENANT") && !JSON.stringify(afterAuth.schema).includes("VIEWER-WRITE"));
  rec("J04-18", "meta", "activations used by this workstream (shared fixture set)", "<= 8", String(F.activations ?? activations), true, { cls: "REAL_BACKEND_E2E" });
} catch (e) { rec("J04-ERR", "script", "unexpected exception", "none", String(e?.stack ?? e).slice(0, 500), false); }
process.exit(save() ? 1 : 0);
