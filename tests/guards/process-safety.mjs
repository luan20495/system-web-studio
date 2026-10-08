#!/usr/bin/env node
// @class: unit
// Guard 8 (D-C0-48): no MACHINE-WIDE process killing in committed, executable project code. Several agents share one Mac; `pkill -f "next start"` (2026-10-08) and `pkill -f gradlew`
// each killed other agents' stacks. Process control is done with the OWNED-PROCESS helper (scripts/owned-process.mjs, scripts/lib/owned-process.{mjs,sh}) or with a pid this code itself started.
// Scanned: scripts/ tests/ e2e/ workers/ infra/ tooling/ (shell, JS / TS, Python), comments blanked first.
// Strings ARE scanned (a JS command is a string: execSync("pkill ...")), so an echo / message that merely NAMES a forbidden command is flagged as well: rephrase it, or add the pragma.
// Not scanned: /tmp, shell history, agent transcripts, other worktrees, docs (*.md), and tests/guards/ (the guard's own sources and fixtures).
//   PROCESS-SAFETY-PKILL        pkill without -P (parent-scoped):  pkill -f "next start" · pkill -f gradlew · pkill -x node · spawnSync("pkill", ["-f", ...])
//   PROCESS-SAFETY-KILLALL      killall <name>
//   PROCESS-SAFETY-PGREP-KILL   kill $(pgrep ...) · kill `pgrep ...` · pgrep|pidof | xargs kill · ps | grep | xargs kill · for p in $(pgrep ..); do kill
//   PROCESS-SAFETY-KILL-BY-PORT kill $(lsof -ti ...) · lsof -ti ... | xargs kill · fuser -k   - unless an ownership check is in the same statement (±4 lines): the owned-process helper
//                               (stop-port / op_stop_port / cwd-under), or an identity comparison (lstart / pidstart / identify)
//   PROCESS-SAFETY-GROUP-ZERO   kill 0 / process.kill(0, ...)  (signals the caller's own process group, i.e. whatever else the same shell started)  ·  kill -1  (every process)
// ALLOWED (on purpose): kill "$pid" / process.kill(pid) of a pid this code started · pkill -TERM -P "$pid" (children of a known parent) · kill -0 (existence test) · kill "$(cat file.pid)"
// · kill -- -"$pgid" of a group this code created · docker stop|kill <exact name> · the helper API. One justified exception: `# guard-allow: PROCESS-SAFETY — <reason>` on / above the line.
import { read, walk, lineOf, allowedByPragma, report, REPO } from "./lib.mjs";

const DIRS = /^(scripts|tests|e2e|workers|infra|tooling)\//;
const SHELL = /\.(sh|bash|zsh)$/; const JS = /\.(mjs|cjs|js|ts|tsx)$/; const PY = /\.py$/;
const OWNERSHIP = /owned-process|op_stop_port|stop-port|stopOwnedPort|stopOwned\b|cwd-under|cwdUnder|identify\(|\blstart\b|pidstart|startTime|own_pid/;

/** blank comments, keep strings and line breaks (shell / python: `#`; JS: `//` and block comments); shell line continuations are joined so a command is one line */
function blank(src, kind) {
  let out = ""; let q = null;
  for (let i = 0; i < src.length; i++) {
    const c = src[i], d = src[i + 1];
    if (q) { out += c; if (c === "\\" && kind !== "shell") { out += src[++i] ?? ""; continue; } if (c === q) q = null; continue; }
    if (c === '"' || c === "'" || (c === "`" && kind === "js")) { q = c; out += c; continue; }
    if (kind === "js" && c === "/" && d === "/") { while (i < src.length && src[i] !== "\n") { out += " "; i++; } i--; continue; }
    if (kind === "js" && c === "/" && d === "*") { i += 2; out += "  "; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) { out += src[i] === "\n" ? "\n" : " "; i++; } out += "  "; i++; continue; }
    if (kind !== "js" && c === "#" && (i === 0 || /[\s;|&(]/.test(src[i - 1]))) { while (i < src.length && src[i] !== "\n") { out += " "; i++; } i--; continue; }
    out += c;
  }
  return kind === "shell" ? out.replace(/\\\n/g, " \n").replace(/\\\r?\n/g, " \n") : out;
}
const PARENT_SCOPED = /(^|[\s"'\[,])-[A-Za-z]*P(?=[\s"'\],]|$)/;

const RULES = [
  { rule: "PROCESS-SAFETY-PKILL", msg: "pkill without -P kills every process whose name / command line matches, on the whole machine (other agents' stacks included): use a pid you started, `pkill -TERM -P \"$pid\"`, or scripts/owned-process.mjs",
    re: /\bpkill\b([^\n;|&)]*)/g, when: (m) => !PARENT_SCOPED.test(m[1]) },
  { rule: "PROCESS-SAFETY-KILLALL", msg: "killall kills by process name on the whole machine: use a pid you started or scripts/owned-process.mjs", re: /\bkillall\b/g },
  { rule: "PROCESS-SAFETY-PGREP-KILL", msg: "killing the result of a name search (pgrep / ps | grep) hits whatever matches, other agents' processes included",
    re: /\bkill\b[^\n;|&]*(?:\$\(|`)\s*(?:pgrep|pidof|ps\b)|\b(?:pgrep|pidof)\b[^\n]*\|\s*xargs\s+(?:-\S+\s+)*kill|\bps\b[^\n|]*\|[^\n]*\b(?:grep|awk)\b[^\n]*\|\s*xargs\s+(?:-\S+\s+)*kill|\bfor\s+\w+\s+in\s+\$\(\s*(?:pgrep|pidof)[^)]*\)[^\n]*\bkill\b/g },
  { rule: "PROCESS-SAFETY-KILL-BY-PORT", msg: "killing whoever listens on a port proves nothing about who owns it: use `scripts/owned-process.mjs stop-port --port P --state FILE | --cwd-under DIR` (a foreign listener is refused)",
    re: /\bkill\b[^\n;|&]*(?:\$\(|`)[^)`\n]*\blsof\b|\blsof\b[^\n|]*\|\s*(?:xargs\s+(?:-\S+\s+)*kill|while\b[^\n]*\bkill\b)|\bfuser\b[^\n]*\s-[A-Za-z]*k\b/g,
    exempt: (text, idx) => { const lines = text.split("\n"); const ln = text.slice(0, idx).split("\n").length; return OWNERSHIP.test(lines.slice(Math.max(0, ln - 5), ln + 4).join("\n")); } },
  { rule: "PROCESS-SAFETY-GROUP-ZERO", msg: "signalling pid 0 hits the caller's whole process group (whatever else the same shell / test runner started); kill -1 hits every process",
    re: /\bprocess\.kill\(\s*0\s*[,)]|\bkill\s+(?:-[A-Za-z0-9]+\s+)*(?:--\s+)?0(?=\s|;|$)|\bkill\s+(?:-[A-Za-z0-9]+\s+)*-1(?=\s|;|$)/g },
];

export function guardProcessSafety(root = REPO) {
  const out = [];
  for (const f of walk(root).filter((x) => DIRS.test(x) && !x.startsWith("tests/guards/") && (SHELL.test(x) || JS.test(x) || PY.test(x)))) {
    const raw = read(root, f); const kind = SHELL.test(f) ? "shell" : PY.test(f) ? "py" : "js"; const text = blank(raw, kind); const lines = raw.split("\n");
    for (const r of RULES) {
      const re = new RegExp(r.re.source, "g"); let m;
      while ((m = re.exec(text))) {
        if (r.when && !r.when(m)) continue; if (r.exempt && r.exempt(text, m.index)) continue;
        const line = lineOf(text, m.index); if (allowedByPragma(lines, line, "PROCESS-SAFETY")) continue;
        out.push({ rule: r.rule, file: f, line, message: r.msg, excerpt: lines[line - 1] });
      }
    }
  }
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const a = process.argv.slice(2); const ri = a.indexOf("--root");
  process.exit(report("PROCESS-SAFETY (no machine-wide kill by name or by port alone)", guardProcessSafety(ri >= 0 ? a[ri + 1] : REPO), { json: a.includes("--json") }));
}
