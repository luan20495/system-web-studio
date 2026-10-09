// @class: unit — pure logic / server-side render of components; no browser, no network
/** M-067: the shared <Field> renders the markup the screens hand-write today (adoption is pixel-identical) and wires hint / error to the control. */
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { Field } from "../../packages/ui/src/Field";
import { a11yProblems } from "./a11y";

test("plain children: exactly <label class=field><span>label</span>control</label>, no ids invented", () => {
  const h = renderToStaticMarkup(<Field label="Tên công ty"><input value="" readOnly/></Field>);
  assert.equal(h, '<label class="field"><span>Tên công ty</span><input readOnly="" value=""/></label>');
  assert.deepEqual(a11yProblems(h), []);
});

test("render-function children get an id, the label points at it, hint and error are described-by and the control is invalid", () => {
  const h = renderToStaticMarkup(<Field label="Mã công ty" hint="Chữ thường và số" error="Mã đã được dùng">{(a) => <input {...a}/>}</Field>);
  const id = /<input id="([^"]+)"/.exec(h)?.[1]; assert.ok(id, h);
  assert.match(h, new RegExp(`<label class="field" for="${id}">`));
  assert.match(h, new RegExp(`aria-describedby="${id}-hint ${id}-err"`)); assert.match(h, /aria-invalid="true"/);
  assert.match(h, new RegExp(`<small id="${id}-hint">Chữ thường và số</small></label><p class="formError" role="alert" id="${id}-err">Mã đã được dùng</p>`));
  assert.deepEqual(a11yProblems(h), []);
});

test("no hint / error: no dangling aria-describedby, not invalid; an extra class is appended", () => {
  const h = renderToStaticMarkup(<Field label="Email" className="wide">{(a) => <input type="email" {...a}/>}</Field>);
  assert.doesNotMatch(h, /aria-describedby|aria-invalid|formError|<small/);
  assert.match(h, /^<label class="field wide" for="/);
});
