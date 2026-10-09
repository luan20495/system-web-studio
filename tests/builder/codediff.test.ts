// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import { diffFileRows, lineDiff, shownRows, type DiffRow } from "../../features/studio/codeDiff";

// the rule DiffView used before M-082 (O(n * 5) slices per row), kept here as the oracle
function oldShown(rows: DiffRow[]): string[] {
  const keep = rows.map((r, i) => r.t !== " " || rows.slice(Math.max(0, i - 2), i + 3).some((x) => x.t !== " "));
  const out: string[] = [];
  rows.forEach((r, i) => { if (keep[i]) out.push(`${i}:${r.t}${r.s}`); else if (keep[i - 1]) out.push(`${i}:gap`); });
  return out;
}
const asText = (rows: ReturnType<typeof shownRows>) => rows.map((r) => r.gap ? `${r.index}:gap` : `${r.index}:${r.row.t}${r.row.s}`);

test("M-082: lineDiff marks removed/added lines and keeps equal ones", () => {
  assert.deepEqual(lineDiff("a\nb\nc", "a\nx\nc"), [{ t: " ", s: "a" }, { t: "-", s: "b" }, { t: "+", s: "x" }, { t: " ", s: "c" }]);
  assert.deepEqual(lineDiff("", "n"), [{ t: "-", s: "" }, { t: "+", s: "n" }]);
});

test("M-082: shownRows equals the previous context/gap rule for many edits", () => {
  const base = Array.from({ length: 40 }, (_, i) => `l${i}`);
  for (let seed = 0; seed < 60; seed++) {
    const after = base.slice();
    for (let k = 0; k < (seed % 5) + 1; k++) { const at = (seed * 7 + k * 13) % after.length; if (k % 2) after.splice(at, 1); else after[at] = `x${seed}-${k}`; }
    const rows = lineDiff(base.join("\n"), after.join("\n"));
    assert.deepEqual(asText(shownRows(rows)), oldShown(rows), `seed ${seed}`);
  }
  assert.deepEqual(shownRows([]), []);
});

test("M-082: diffFileRows is computed once per DiffFile object (cache), and again for a new response", () => {
  const f = { path: "src/App.tsx", before: "a\nb", after: "a\nc" };
  const r1 = diffFileRows(f);
  assert.equal(diffFileRows(f), r1);
  assert.notEqual(diffFileRows({ ...f }), r1);
  assert.deepEqual(diffFileRows({ ...f }), r1);
  assert.deepEqual(asText(diffFileRows({ path: "n", before: null, after: "x" })), ["0:-", "1:+x"]);
});
