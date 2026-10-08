// @class: unit — values the server stores as JSON text but does not guarantee to be JSON never throw in the Admin screens (M-076)
import test from "node:test";
import assert from "node:assert/strict";
import { parseJsonOr, prettyJson } from "../../features/admin/safeJson";

test("parseJsonOr: JSON is parsed, anything else comes back as the text, null / empty stay as they are, nothing throws", () => {
  assert.deepEqual(parseJsonOr('{"a":1}'), { a: 1 }); assert.equal(parseJsonOr("not json at all"), "not json at all"); assert.equal(parseJsonOr("{broken"), "{broken");
  assert.equal(parseJsonOr(null), null); assert.equal(parseJsonOr(undefined), undefined); assert.equal(parseJsonOr(""), "");
  assert.equal(parseJsonOr("123"), 123, "a JSON number is JSON");
});
test("prettyJson: indented JSON, or the raw text when it is not JSON, or the placeholder", () => {
  assert.equal(prettyJson('{"a":[1,2]}'), '{\n  "a": [\n    1,\n    2\n  ]\n}'); assert.equal(prettyJson("nope"), "nope");
  assert.equal(prettyJson(null), "—"); assert.equal(prettyJson(""), "—"); assert.equal(prettyJson(undefined, "n/a"), "n/a");
});
