// @class: unit — GUARD (M-017): no native window.confirm / window.prompt / alert in the Platform and Admin consoles; confirmations are @xweb/ui confirm() / prompt()
import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(__dirname, "..", "..", "..");
const NATIVE = [/\bwindow\.(confirm|prompt|alert)\s*\(/, /(^|[^\w.])alert\s*\(/];
// a bare `confirm(` / `prompt(` is the shared dialog only when the file imports it from @xweb/ui
const BARE = /(^|[^\w.$])(confirm|prompt)\s*\(/;

function scan(root: string): string[] {
  const bad: string[] = [];
  const walk = (d: string) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n); if (statSync(p).isDirectory()) { walk(p); continue; }
      if (!/\.(ts|tsx)$/.test(n)) continue;
      const src = readFileSync(p, "utf8"); const shared = /import\s*\{[^}]*\b(confirm|prompt)\b[^}]*\}\s*from\s*"@xweb\/ui"/.test(src);
      src.split("\n").forEach((line, i) => {
        if (/^\s*(\*|\/\/|\/\*)/.test(line)) return;
        if (NATIVE.some((re) => re.test(line)) || (!shared && BARE.test(line))) bad.push(`${relative(ROOT, p)}:${i + 1}: ${line.trim().slice(0, 100)}`);
      });
    }
  };
  walk(join(ROOT, root));
  return bad;
}

test("GUARD: features/admin has no native confirm / prompt / alert", () => {
  assert.deepEqual(scan("features/admin"), [], "use confirm() / prompt() from @xweb/ui (consequence text, danger styling, focus handling)");
});
test("GUARD self-check: the scanner sees the native calls", () => {
  for (const l of ["if (window.confirm('x')) y();", "const n = window.prompt('n')", "alert('x')", "if (confirm('x')) y();"]) assert.ok(NATIVE.some((re) => re.test(l)) || BARE.test(l), l);
  for (const l of ["await confirm({ title: 'x' })", "const confirm = 1", "setConfirm(true)"]) assert.ok(!NATIVE.some((re) => re.test(l)), l);
});
