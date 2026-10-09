// Line diff of the code-change "Mã thay đổi" tab (pure, unit-tested). M-082: the LCS is computed once per DiffFile object (WeakMap cache),
// not on every render of CodeWorkspace (each keystroke in the prompt box or the editor used to recompute every file).
import type { DiffFile } from "@xweb/types";

export type DiffRow = { t: " " | "-" | "+"; s: string };
/** a row to show: a changed line, or a context line within 2 lines of a change; `gap` marks the first hidden line after a shown one (rendered as "⋯") */
export type ShownRow = { row: DiffRow; index: number; gap: false } | { row: null; index: number; gap: true };

/** Line diff (LCS) for small source files; above 4M cells everything is shown as removed + added. */
export function lineDiff(a: string, b: string): DiffRow[] {
  const x = a.split("\n"), y = b.split("\n");
  if (x.length * y.length > 4_000_000) return [...x.map((s) => ({ t: "-" as const, s })), ...y.map((s) => ({ t: "+" as const, s }))];
  const m = Array.from({ length: x.length + 1 }, () => new Int32Array(y.length + 1));
  for (let i = x.length - 1; i >= 0; i--) for (let j = y.length - 1; j >= 0; j--) m[i][j] = x[i] === y[j] ? m[i + 1][j + 1] + 1 : Math.max(m[i + 1][j], m[i][j + 1]);
  const out: DiffRow[] = []; let i = 0, j = 0;
  while (i < x.length && j < y.length) { if (x[i] === y[j]) { out.push({ t: " ", s: x[i] }); i++; j++; } else if (m[i + 1][j] >= m[i][j + 1]) out.push({ t: "-", s: x[i++] }); else out.push({ t: "+", s: y[j++] }); }
  while (i < x.length) out.push({ t: "-", s: x[i++] }); while (j < y.length) out.push({ t: "+", s: y[j++] });
  return out;
}

/** changed lines with 2 lines of context (same rule as before, in O(n)); a run of hidden lines becomes one gap marker */
export function shownRows(rows: readonly DiffRow[]): ShownRow[] {
  const keep = new Array<boolean>(rows.length).fill(false);
  rows.forEach((r, i) => { if (r.t !== " ") for (let k = Math.max(0, i - 2); k <= Math.min(rows.length - 1, i + 2); k++) keep[k] = true; });
  const out: ShownRow[] = [];
  rows.forEach((row, index) => { if (keep[index]) out.push({ row, index, gap: false }); else if (keep[index - 1]) out.push({ row: null, index, gap: true }); });
  return out;
}

const cache = new WeakMap<DiffFile, ShownRow[]>();
/** the shown rows of one file, computed once per DiffFile object (a new diff response = new objects = recomputed) */
export function diffFileRows(f: DiffFile): ShownRow[] {
  let r = cache.get(f);
  if (!r) { r = shownRows(lineDiff(f.before ?? "", f.after ?? "")); cache.set(f, r); }
  return r;
}
