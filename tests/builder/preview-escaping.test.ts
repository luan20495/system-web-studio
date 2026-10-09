// @class: unit — M-089: the Studio canvas document carries the page's CSP nonce, so the renderer's escaping is the real guard. Fuzz every renderer with hostile text; no browser, no network.
import test from "node:test";
import assert from "node:assert/strict";
import { renderSchemaDocument, renderSitePages } from "../../lib/schema-preview";
import type { PageSchema } from "@xweb/types";

const TYPES = ["Navbar", "Hero", "ProductGrid", "Testimonials", "ContactForm", "ComparisonBlock", "TechnologySection", "Footer"];
const NASTY = [
  `</script><script>alert(1)</script>`, `"><img src=x onerror=alert(1)>`, `' onmouseover='alert(1)`, `<svg/onload=alert(1)>`, `javascript:alert(1)`,
  `</style><script nonce="n0nce">x()</script>`, `<!--`, `]]>`, `<iframe srcdoc="<script>1</script>">`, "`${alert(1)}`", `&lt;script&gt;`, `  `, `<a href="javascript:1">x</a>`,
];
// deterministic PRNG so a failure is reproducible
function rng(seed: number) { return () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff); }
const KEYS = ["title", "subtitle", "heading", "brand", "text", "ctaLabel", "ctaHref", "submitLabel", "description", "name", "quote", "author", "label", "href", "image", "imageAssetId", "theme", "align"];
function nastyValue(r: () => number, depth = 0): unknown {
  const pick = NASTY[Math.floor(r() * NASTY.length)];
  if (depth < 2 && r() < 0.25) return Array.from({ length: 1 + Math.floor(r() * 3) }, () => Object.fromEntries(KEYS.map((k) => [k, nastyValue(r, depth + 1)])));
  return r() < 0.15 ? pick + "x".repeat(Math.floor(r() * 5)) : pick;
}
function nastySchema(seed: number): PageSchema {
  const r = rng(seed);
  const sections = TYPES.map((type, i) => ({ id: `s${i}`, type, props: Object.fromEntries(KEYS.map((k) => [k, nastyValue(r)]).concat([["items", nastyValue(r, 1)], ["links", nastyValue(r, 1)], ["features", nastyValue(r, 1)]])) }));
  return { page: NASTY[seed % NASTY.length], site: { title: NASTY[(seed + 1) % NASTY.length], navigation: [{ id: "n1", label: NASTY[(seed + 2) % NASTY.length], pageId: "p2" }] }, sections,
    pages: [{ id: "p2", slug: "trang-2", title: NASTY[(seed + 3) % NASTY.length], sections: sections.slice(0, 3) }] } as unknown as PageSchema;
}
const count = (s: string, re: RegExp) => (s.match(re) ?? []).length;
// real markup only: every tag with its quoted attribute VALUES removed (escaped text inside a value or between tags is data, not markup)
const tagsOf = (html: string) => (html.match(/<[a-z][^<>]*>/gi) ?? []).map((t) => t.replace(/"[^"]*"|'[^']*'/g, '""'));
const nonceAttrs = (html: string) => tagsOf(html).filter((t) => /\snonce=/i.test(t)).length;
// markup outside the one trusted editor script: no script element, no event-handler attribute, no javascript: URL, no extra nonce
function hostileMarkup(html: string): string[] {
  const out: string[] = [];
  if (count(html, /<script\b/gi) !== 1) out.push(`script elements: ${count(html, /<script\b/gi)}`);
  if (nonceAttrs(html) !== 1) out.push(`nonce attributes: ${nonceAttrs(html)}`);
  const withoutScript = html.replace(/<script nonce="N0NCE-TRUSTED">[\s\S]*?<\/script>/, "");
  const tags = tagsOf(withoutScript);
  if (tags.some((t) => /\son[a-z]+\s*=/i.test(t))) out.push("event handler attribute");
  if (/(href|src|action)\s*=\s*["']?\s*javascript:/i.test(withoutScript)) out.push("javascript: URL");
  if (tags.some((t) => /^<(iframe|svg|object|embed)\b/i.test(t))) out.push("raw iframe/svg/object");
  return out;
}

test("M-089: fuzz - hostile text in every prop of every renderer never becomes markup in the interactive (nonce-carrying) canvas document", () => {
  for (let seed = 1; seed <= 150; seed++) {
    for (const pageId of ["home", "p2"]) {
      const html = renderSchemaDocument(nastySchema(seed), { selectedId: null, interactive: true, nonce: "N0NCE-TRUSTED", assets: {}, pageId, parentOrigin: "https://studio.example.vn" });
      assert.deepEqual(hostileMarkup(html), [], `seed ${seed} page ${pageId}`);
    }
  }
});

test("M-089: published output of hostile text has no script and no nonce at all", () => {
  for (let seed = 1; seed <= 40; seed++) {
    const files = renderSitePages(nastySchema(seed), {});
    for (const [name, html] of Object.entries(files)) {
      if (!name.endsWith(".html")) continue;
      assert.equal(count(html, /<script\b/gi), 0, `${name} seed ${seed}`); assert.equal(nonceAttrs(html), 0, name);
      assert.ok(!tagsOf(html).some((t) => /\son[a-z]+\s*=/i.test(t)), `${name} seed ${seed}: event handler`);
    }
  }
});

test("M-089: the canvas script posts to the editor origin when it is given, '*' only as the fallback; an odd origin cannot break out", () => {
  const base = { selectedId: null, interactive: true, nonce: "n", assets: {}, pageId: "home" };
  const s = nastySchema(1);
  const pinned = renderSchemaDocument(s, { ...base, parentOrigin: "https://studio.example.vn:3003" });
  assert.equal(count(pinned, /,"https:\/\/studio\.example\.vn:3003"\)/g), 2); assert.equal(count(pinned, /,"\*"\)/g), 0);
  assert.equal(count(renderSchemaDocument(s, base), /,"\*"\)/g), 2, "no origin given: unchanged behaviour");
  for (const bad of [`https://x"</script><script>alert(1)//`, "javascript:alert(1)", "null", "https://a.vn/path"]) {
    const html = renderSchemaDocument(s, { ...base, parentOrigin: bad });
    assert.equal(count(html, /,"\*"\)/g), 2, bad); assert.equal(count(html, /<script\b/gi), 1, bad);
  }
});
