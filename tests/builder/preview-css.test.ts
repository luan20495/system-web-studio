// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import { previewStyles } from "../../lib/preview-document";

test("M-113: the preview / published CSS scrolls smoothly ONLY under prefers-reduced-motion: no-preference", () => {
  assert.match(previewStyles, /@media\(prefers-reduced-motion:no-preference\)\{html\{scroll-behavior:smooth\}\}/);
  assert.doesNotMatch(previewStyles.replace(/@media\(prefers-reduced-motion:no-preference\)\{html\{scroll-behavior:smooth\}\}/, ""), /scroll-behavior:smooth/);
});
