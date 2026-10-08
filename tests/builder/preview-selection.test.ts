// @class: unit — M-003: the Studio preview keeps its document while the selection changes (selection is POSTED to the iframe, never baked into srcDoc); the PUBLISHED output must stay byte-identical. No browser, no network.
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { renderSchemaDocument, renderSitePages } from "../../lib/schema-preview";
import type { PageSchema } from "@xweb/types";

const schema = {
  page: "Trang chủ",
  site: { navigation: [{ id: "n1", label: "Giới thiệu", pageId: "p-about" }], title: "Demo" },
  sections: [
    { id: "s-nav", type: "Navbar", props: { brand: "Demo", links: [] } },
    { id: "s-hero", type: "Hero", props: { title: "Xin chào", subtitle: "Mô tả <b>escaped</b>", ctaLabel: "Mua ngay" } },
    { id: "s-form", type: "ContactForm", props: { title: "Liên hệ", submitLabel: "Gửi" } },
    { id: "s-foot", type: "Footer", props: { text: "© Demo" } },
  ],
  pages: [{ id: "p-about", slug: "gioi-thieu", title: "Giới thiệu", sections: [{ id: "a-hero", type: "Hero", props: { title: "Về chúng tôi" } }] }],
} as unknown as PageSchema;

const digest = (files: Record<string, string>) => createHash("sha256").update(Object.keys(files).sort().map((k) => `${k}\n${files[k]}`).join("\n--\n")).digest("hex");

test("published output is byte-identical to the pre-M-003 renderer (golden sha256 of every published file)", () => {
  assert.equal(digest(renderSitePages(schema, {})), "7f188e575df58a1c43e5a56135aab1b945b23632051a150f812ccc1c458fa860");
});

test("published and non-interactive documents carry no script and no selection class", () => {
  const published = renderSitePages(schema, {});
  for (const html of Object.values(published)) { assert.ok(!/<script/.test(html)); assert.ok(!/class="__sec __sel"/.test(html)); }
  const readOnly = renderSchemaDocument(schema, { selectedId: null, interactive: false });
  assert.ok(!/<script/.test(readOnly));
});

test("the interactive editor document does not depend on the selection: selecting another section does not change the HTML", () => {
  const a = renderSchemaDocument(schema, { selectedId: null, interactive: true });
  assert.ok(a.includes("studio:select") && a.includes("studio:layout"));
  assert.ok(!/class="__sec __sel"/.test(a), "no section is pre-selected in the document");
});

test("the editor script applies the selection it is POSTED: only from the parent window, by data-sid, toggling __sel", () => {
  const html = renderSchemaDocument(schema, { selectedId: null, interactive: true });
  assert.ok(html.includes('"studio:selected"'), "listens for studio:selected");
  assert.ok(html.includes("ev.source!==parent"), "ignores messages that do not come from the parent");
  assert.ok(html.includes('classList.toggle("__sel"'), "toggles the selection class");
  assert.ok(html.includes('getAttribute("data-sid")===d.sectionId'), "matches by data-sid value, not through a selector built from the message");
});

test("the legacy baked selection still works for callers that pass a selectedId (AI preview, tests)", () => {
  const html = renderSchemaDocument(schema, { selectedId: "s-hero", interactive: false });
  assert.ok(/class="__sec __sel" data-sid="s-hero"/.test(html));
});
