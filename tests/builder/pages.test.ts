// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import * as P from "../../features/studio/builder/core/pages";
import { preflight, blockers } from "../../features/studio/builder/core/preflight";
import { doc } from "./fixtures";

const withPages = () => doc({
  pages: [{ id: "p1", slug: "gioi-thieu", title: "Giới thiệu", sections: [] }, { id: "p2", slug: "lien-he", title: "Liên hệ", sections: [{ id: "c1", type: "ContactForm", props: {} } as never] }],
  site: { navigation: [{ id: "n1", label: "Giới thiệu", pageId: "p1" }, { id: "n2", label: "Liên hệ", pageId: "p2" }] },
} as never);

test("page tree: home first, then pages in document order, with routes", () => {
  const l = P.listPages(withPages());
  assert.deepEqual(l.map((p) => p.route), ["/", "/gioi-thieu/", "/lien-he/"]);
  assert.equal(l[0].home, true);
  assert.equal(l[1].navIndex, 0);
});

test("slugify strips Vietnamese diacritics; reserved and duplicate slugs are refused", () => {
  assert.equal(P.slugify("Đặt hàng nhanh"), "dat-hang-nhanh");
  assert.equal(P.checkSlug(withPages(), "api").ok, false);
  assert.equal(P.checkSlug(withPages(), "lien-he").ok, false);
  assert.equal(P.checkSlug(withPages(), "lien-he", "p2").ok, true);
  assert.equal(P.checkSlug(withPages(), "Sai Slug").ok, false);
});

test("add page: unique slug, ADD_PAGE op; empty name and the page cap are refused", () => {
  const r = P.opsAddPage(withPages(), "Liên hệ", "p3");
  assert.ok("ops" in r);
  if ("ops" in r) assert.deepEqual(r.ops[0], { type: "ADD_PAGE", pageId: "p3", props: { slug: "lien-he-2", title: "Liên hệ" } });
  assert.ok("error" in P.opsAddPage(withPages(), "  ", "p3"));
  const many = doc({ pages: Array.from({ length: P.MAX_PAGES }, (_, i) => ({ id: `p${i}`, slug: `s${i}`, title: "t", sections: [] })) } as never);
  assert.ok("error" in P.opsAddPage(many, "x", "z"));
});

test("rename: home keeps '/', other page can change slug if valid", () => {
  const d = withPages();
  const home = P.opsRenamePage(d, "home", "Nhà");
  assert.ok("ops" in home && home.ops[0].type === "UPDATE_PAGE");
  assert.ok("error" in P.opsRenamePage(d, "p1", "X", "lien-he"));
  assert.ok("ops" in P.opsRenamePage(d, "p1", "X", "x-moi"));
});

test("delete: home cannot be deleted; impact names sections, menu links and NAVIGATE actions", () => {
  const d = withPages();
  assert.ok("error" in P.opsRemovePage(d, "home"));
  const imp = P.removeImpact(d, "p2", [{ id: "a1", name: "Đi liên hệ", type: "NAVIGATE", pageRef: "p2" }])!;
  assert.equal(imp.sections, 1); assert.equal(imp.navLinks, 1);
  assert.deepEqual(imp.actionsPointingHere, ["Đi liên hệ"]);
  assert.match(imp.message, /Đi liên hệ/);
});

test("set-home and page reorder are NOT_READY (no op in the contract) with a reason; menu reorder is real", () => {
  assert.equal(P.setHomeReadiness().state, "NOT_READY");
  assert.equal(P.reorderPagesReadiness().state, "NOT_READY");
  const links = [{ id: "a", label: "A" }, { id: "b", label: "B" }, { id: "c", label: "C" }];
  assert.deepEqual(P.moveNavLink(links, 2, 0).map((l) => l.id), ["c", "a", "b"]);
  const r = P.opsSetNavigation(P.moveNavLink(links, 0, 1));
  assert.ok("ops" in r && r.ops[0].type === "SET_NAVIGATION");
});

test("404: unknown route resolves to the not-found page, known routes to pages", () => {
  const d = withPages();
  assert.equal(P.resolveRoute(d, "/").kind, "page");
  assert.equal(P.resolveRoute(d, "/gioi-thieu/").kind, "page");
  const nf = P.resolveRoute(d, "/khong-co/");
  assert.equal(nf.kind, "notfound");
  const custom = P.resolveRoute({ ...d, site: { ...d.site, notFound: { title: "Lạc đường", message: "Về trang chủ nhé" } } } as never, "/x");
  assert.ok(custom.kind === "notfound" && custom.title === "Lạc đường");
});

test("preflight blocks publish on broken routes (menu page removed, NAVIGATE to missing page, bad slug)", () => {
  const d = withPages();
  assert.equal(blockers(preflight(d)).length, 0);
  const brokenMenu = { ...d, pages: d.pages!.filter((p) => p.id !== "p1") };
  assert.ok(blockers(preflight(brokenMenu)).some((i) => i.code === "ROUTE_NAV_PAGE_MISSING"));
  const brokenAction = { ...d, actions: [{ id: "a1", name: "go", type: "NAVIGATE", pageRef: "ghost", trigger: { sectionId: "s-hero", event: "onClick" } }] } as never;
  assert.ok(blockers(preflight(brokenAction)).some((i) => i.code === "ROUTE_ACTION_PAGE_MISSING"));
  const badSlug = { ...d, pages: [{ id: "p1", slug: "Bad Slug", title: "x", sections: [] }] } as never;
  assert.ok(blockers(preflight(badSlug)).some((i) => i.code === "ROUTE_SLUG_INVALID"));
  const insecure = { ...d, site: { navigation: [{ id: "n", label: "x", url: "http://x.test" }] } } as never;
  assert.ok(blockers(preflight(insecure)).some((i) => i.code === "ROUTE_NAV_URL_INSECURE"));
});

test("preflight: static + public + data binding is BLOCK; dynamic is WARN", () => {
  const base = { ...withPages(), dataBindings: [{ id: "b1", sectionId: "s-text", prop: "body", viewModelRef: "vm" }] };
  const staticPublic = { ...base, publishConfig: { mode: "STATIC", visibility: "PUBLIC" } } as never;
  assert.ok(blockers(preflight(staticPublic)).some((i) => i.code === "STATIC_PUBLIC_DATA"));
  const dynamicPublic = { ...base, publishConfig: { mode: "DYNAMIC", visibility: "PUBLIC" } } as never;
  assert.ok(!blockers(preflight(dynamicPublic)).some((i) => i.code === "STATIC_PUBLIC_DATA"));
  assert.ok(preflight(dynamicPublic).some((i) => i.code === "PUBLIC_DATA_NEEDS_APPROVAL"));
});

test("M-047: the Website drawer and the builder share ONE rule set (core/pages): SEO rides on the same UPDATE_PAGE, the 404 op is shared, the drawer has no own slug / op code", () => {
  const d = withPages();
  const r = P.opsRenamePage(d, "p1", "Giới thiệu", "gioi-thieu", { title: "SEO", noindex: true });
  assert.ok("ops" in r); if ("ops" in r) assert.deepEqual(r.ops[0], { type: "UPDATE_PAGE", pageId: "p1", props: { title: "Giới thiệu", seo: { title: "SEO", noindex: true }, slug: "gioi-thieu" } });
  assert.ok("error" in P.opsRenamePage(d, "p1", "X", "api", {}), "the drawer now refuses reserved slugs too");
  const homeSeo = P.opsRenamePage(d, "home", "", undefined, { noindex: false });
  assert.ok("ops" in homeSeo && JSON.stringify(homeSeo.ops[0]) === JSON.stringify({ type: "UPDATE_PAGE", pageId: "home", props: { seo: { noindex: false } } }), "home SEO saves without a title, as the drawer always allowed");
  assert.ok("error" in P.opsRenamePage(d, "home", ""), "the builder rename still needs a title");
  assert.deepEqual(P.opsSetNotFound("  Mất ", "").ops[0], { type: "UPDATE_SITE", props: { notFound: { title: "Mất" } } });
  const src = require("node:fs").readFileSync(require("node:path").join(process.cwd(), "features/studio/SitePanels.tsx"), "utf8");
  assert.doesNotMatch(src, /const slugify|"ADD_PAGE"|"REMOVE_PAGE"|"UPDATE_PAGE"|"SET_NAVIGATION"|"UPDATE_SITE"|PageBar/, "no second implementation in SitePanels");
  assert.match(src, /from "\.\/builder\/core\/pages"/);
});

test("M-077: the add-page hint is uniqueSlug (the slug really saved), not slugify", () => {
  const d = withPages();
  assert.equal(P.uniqueSlug(d, "API"), "api-2");
  assert.equal(P.uniqueSlug(d, "Liên hệ"), "lien-he-2");
  const src = require("node:fs").readFileSync(require("node:path").join(process.cwd(), "features/studio/builder/panels/PagesPanel.tsx"), "utf8");
  assert.match(src, /const slug = title\.trim\(\) \? uniqueSlug\(doc, title\)/);
});
