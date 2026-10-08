#!/usr/bin/env node
// @class: unit
// Guard 6 (D-C0-44): a test that runs on a fake / in-memory / intercepted transport must never be classified (or reported) as a real-backend E2E.
// Built on the repository's own tag convention instead of text-policing: `// @class: unit | mock | harness | integration | real-backend` in the first lines
//   unit          pure logic, no browser, no network            mock          fetch / backend stubbed, proves what the client sends
//   harness       real browser on a test-only host (fake host)  integration  a real component (nginx, PostgreSQL, a script) with its neighbours stubbed; NOT the full path
//   real-backend  real browser / client -> the real stack, nothing intercepted. HARNESS / INTEGRATION / REAL_E2E in reports map to harness / integration / real-backend.
// scripts/test-classify.mjs (C5) keeps classifying tests/** and e2e/**; this guard adds what it cannot see: the C0-owned tests it skips, the scripts that talk to a live stack,
// and the SEMANTIC rule (fake-transport marker in code <=> not real-backend). It never reads comments for markers, so "no fake transport here" in a comment is harmless.
import { read, walk, stripComments, lineOf, report, REPO } from "./lib.mjs";

export const CLASSES = ["unit", "mock", "harness", "integration", "real-backend"];
const CODE = /\.(mjs|js|ts|tsx)$/;
// files this guard requires a tag on (C0-owned, skipped by the C5 classifier) + everything it can semantically check
const MUST_TAG = (f) => /^tests\/(gateway|guards)\/[^/]+\.mjs$/.test(f) || /^scripts\/[^/]*(?:smoke|e2e|verify|browser)[^/]*\.mjs$/.test(f) && !/stability-summary/.test(f) || /^tests\/builder\/[^/]*(?:fail-closed|depth)[^/]*\.test\.ts$/.test(f);
const SCANNED = (f) => CODE.test(f) && (/^(tests|e2e)\//.test(f) || /^scripts\/[^/]*(?:smoke|e2e|verify|browser)[^/]*\.mjs$/.test(f) && !/stability-summary/.test(f));
// a transport that is not the real stack
const FAKE = [
  /\b(?:fake|mock|stub|in-?memory)[\s_-]*(?:transport|backend|api|server|adapter|host)\b/i, /\bcreateFake\w*|\bfakeTransport\b|\bmockTransport\b|\bmockApi\b|\bMockApi\b|\bFAKE_\w+/,
  /\bpage\.route\(|\bcontext\.route\(|\broute\.fulfill\b|\bsetRequestInterception\b|\bmsw\b|\bnock\(|\bvi\.mock\(|\bjest\.mock\(|\bsinon\.stub\b/,
];
const NOT_REAL_CLAIM = /\bREAL[ _]BACKEND\b|\bREAL_E2E\b/;

export function guardTestLabeling(root = REPO) {
  const out = [];
  for (const f of walk(root).filter(SCANNED)) {
    const raw = read(root, f); const head = raw.split("\n").slice(0, 8).join("\n"); const tag = /^\/\/ @class:\s*([a-z-]+)/m.exec(head)?.[1];
    if (MUST_TAG(f) && !tag) { out.push({ rule: "TEST-LABEL-MISSING", file: f, line: 1, message: "no `// @class:` tag in the first lines" }); continue; }
    if (!tag) continue;                                                                    // untagged files outside MUST_TAG are the C5 classifier's business (npm run test:classify)
    if (!CLASSES.includes(tag)) { out.push({ rule: "TEST-LABEL-INVALID", file: f, line: 1, message: `unknown class '${tag}' (allowed: ${CLASSES.join(", ")})` }); continue; }
    const code = stripComments(raw);
    if (tag === "real-backend") {
      for (const re of FAKE) { const m = re.exec(code); if (m) { out.push({ rule: "TEST-LABEL-FAKE-AS-REAL", file: f, line: lineOf(code, m.index), message: `tagged real-backend but uses a fake / in-memory / intercepted transport ('${m[0]}'): it is a mock or a harness, not a real E2E`, excerpt: raw.split("\n")[lineOf(code, m.index) - 1] }); break; } }
    } else if (f !== "tests/e2e-real/selftest.mjs" && !f.startsWith("tests/guards/")) {
      const m = NOT_REAL_CLAIM.exec(code.replace(/\/\*[\s\S]*?\*\//g, ""));
      if (m) out.push({ rule: "TEST-LABEL-CLAIMS-REAL", file: f, line: lineOf(code, m.index), message: `tagged ${tag} but its code reports / names itself '${m[0]}'`, excerpt: raw.split("\n")[lineOf(code, m.index) - 1] });
    }
    if (f.startsWith("tests/e2e-real/") && tag !== "real-backend" && f !== "tests/e2e-real/selftest.mjs") out.push({ rule: "TEST-LABEL-WRONG-HOME", file: f, line: 1, message: `only real-backend tests live in tests/e2e-real/ (this one is ${tag})` });
  }
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const a = process.argv.slice(2); const ri = a.indexOf("--root"); process.exit(report("TEST-LABELING (a fake transport is never called a real E2E)", guardTestLabeling(ri >= 0 ? a[ri + 1] : REPO), { json: a.includes("--json") }));
}
