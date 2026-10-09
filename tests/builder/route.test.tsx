// @class: unit — pure logic / server-side render of components; no browser, no network
/** M-025: SkipLink markup, the focus target of a route change, and the guard that the shells use them. Keyboard / focus behaviour: tests/browser/ui-route.spec.mjs. */
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { SkipLink } from "../../packages/ui/src/SkipLink";
import { focusTargetFor } from "../../packages/ui/src/useMain";
import { a11yProblems } from "./a11y";

test("SkipLink: a real link to #main, first-class text in Vietnamese, overridable target / text", () => {
  const h = renderToStaticMarkup(<SkipLink/>);
  assert.match(h, /^<a class="xp-skip" href="#main">Bỏ qua điều hướng, tới nội dung chính<\/a>$/);
  assert.match(renderToStaticMarkup(<SkipLink target="content">Tới nội dung</SkipLink>), /href="#content">Tới nội dung</);
  assert.deepEqual(a11yProblems(h), []);
});

test("focusTargetFor: the page's first VISIBLE <h1>, else <main> itself (a hidden previous page of a loading lazy section is skipped)", () => {
  const fake = (hs: unknown[]) => ({ querySelectorAll: (s: string) => (s === "h1" ? hs : []) }) as unknown as HTMLElement;
  const h1 = {} as HTMLElement;
  assert.equal(focusTargetFor(fake([h1])), h1);                                                        // no checkVisibility (old engine / SSR): the first heading
  const hidden = { checkVisibility: () => false } as unknown as HTMLElement, shown = { checkVisibility: () => true } as unknown as HTMLElement;
  assert.equal(focusTargetFor(fake([hidden, shown])), shown);                                          // React keeps the previous page hidden while a lazy section loads
  const bare = fake([hidden]); assert.equal(focusTargetFor(bare), bare);
  const none = fake([]); assert.equal(focusTargetFor(none), none);
});
