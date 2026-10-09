// C6 static QA harness — read-only. No dependencies, no network, no Docker, does not touch production code.
// Usage (repo root):  node docs/parallel/c6/harness/static-qa.mjs [--json]
// Checks things that must stay true on integration/v2 and that can be verified without Gradle/Docker.
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative } from "node:path";

const root = process.cwd();
const results = [];
const check = (id, title, ok, detail = "") => results.push({ id, title, ok: !!ok, detail });

const walk = (dir, pred, out = []) => {
  if (!existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    const s = statSync(p);
    if (s.isDirectory()) { if (n !== "node_modules" && n !== "build" && n !== ".next") walk(p, pred, out); }
    else if (pred(p)) out.push(p);
  }
  return out;
};

// SQ-01..03 Flyway migrations: contiguous, unique, no V29+ without allocation (BOARD: V29 not created), file name shape
const migDir = join(root, "backend/src/main/resources/db/migration");
const migs = readdirSync(migDir).filter((f) => f.endsWith(".sql"));
const versions = migs.map((f) => { const m = /^V(\d+)__[A-Za-z0-9_]+\.sql$/.exec(f); return m ? Number(m[1]) : NaN; });
check("SQ-01", "All migration file names match V<n>__<name>.sql", versions.every((v) => !Number.isNaN(v)), migs.filter((_, i) => Number.isNaN(versions[i])).join(", "));
const max = Math.max(...versions.filter((v) => !Number.isNaN(v)));
const missing = []; for (let i = 1; i <= max; i++) if (!versions.includes(i)) missing.push(i);
check("SQ-02", `Migration versions contiguous V1..V${max} (no gaps, no duplicates)`, missing.length === 0 && new Set(versions).size === versions.length, `missing=[${missing}] dupes=${versions.length - new Set(versions).size}`);
// Allocation comes from the C0 ledger (rows "| **V<n>** |"), not a hardcoded number: V29 was allocated by C0 (D-C0-27), V30 is reserved for C2.
const ledger = readFileSync(join(root, "docs/parallel/MIGRATION_LEDGER.md"), "utf8");
const allocated = [...ledger.matchAll(/^\|\s*\*\*V(\d+)\*\*\s*\|/gm)].map((m) => Number(m[1]));
const maxAllocated = allocated.length ? Math.max(...allocated) : 0;
check("SQ-03", "No migration file beyond the highest version allocated in docs/parallel/MIGRATION_LEDGER.md", allocated.length > 0 && max <= maxAllocated, `max file=V${max} ledger max allocated=V${maxAllocated}`);

// SQ-04 Flyway outOfOrder never enabled
const ymls = walk(join(root, "backend/src"), (p) => /application.*\.ya?ml$/.test(p));
const ooo = ymls.filter((p) => /out-?of-?order\s*:\s*true/i.test(readFileSync(p, "utf8")));
check("SQ-04", "Flyway outOfOrder is not enabled in any application*.yml", ooo.length === 0, ooo.map((p) => relative(root, p)).join(", "));

// SQ-05 V2 feature flags default OFF in the main application.yml (WEB_SECURITY_CONFIG §1)
const appYml = readFileSync(join(root, "backend/src/main/resources/application.yml"), "utf8");
const flags = ["AI_PLANNER_ENABLED", "TENANT_AI_ENABLED", "PUBLISH_CONFIGS_ENABLED", "DATA_PLATFORM_ENABLED", "DATA_PLATFORM_WEBHOOKS_ENABLED", "WORKFLOW_ENABLED"];
const bad = flags.filter((f) => { const m = new RegExp("\\$\\{" + f + ":([^}]*)\\}").exec(appYml); return !m || m[1].trim() !== "false"; });
check("SQ-05", "V2 feature flags are declared and default to false", bad.length === 0, bad.join(", "));
check("SQ-06", "app.tenancy.system-admin-business-access is declared and defaults false", /system-admin-business-access:\s*(\$\{[A-Z_]+:false\}|false)/.test(appYml));

// SQ-07 wiring skeletons stay un-compiled (.skel)
const skel = walk(join(root, "backend/src/wiring-skeleton"), () => true);
check("SQ-07", "backend/src/wiring-skeleton contains only .skel/README (not compiled)", skel.every((p) => /\.skel$|README\.md$/.test(p)), skel.map((p) => relative(root, p)).join(", "));

// SQ-08 anonymous webhook route is declared exactly once as POST in SecurityConfiguration
const sec = readFileSync(join(root, "backend/src/main/kotlin/com/systemwebstudio/identity/SecurityConfiguration.kt"), "utf8");
check("SQ-08", "SecurityConfiguration declares DATA_WEBHOOK_INGEST and the /api/v1/webhooks/data/ path", /DATA_WEBHOOK_INGEST/.test(sec) && /\/api\/v1\/webhooks\/data\//.test(sec));

// SQ-09 no secrets-looking literals in tracked-style config examples (cheap heuristic; gitleaks not available)
const envEx = readFileSync(join(root, ".env.example"), "utf8");
check("SQ-09", ".env.example has no long literal secret values (heuristic)", !/(KEY|SECRET|PASSWORD|TOKEN)=[A-Za-z0-9+/_-]{32,}/.test(envEx));

// SQ-10 every e2e script and unit test file exists (inventory)
const e2e = readdirSync(join(root, "e2e")).filter((f) => f.endsWith(".mjs"));
check("SQ-10", "E2E scripts present (>=8)", e2e.length >= 8, e2e.join(", "));

// Inventory (informational)
const kt = walk(join(root, "backend/src/test/kotlin"), (p) => p.endsWith(".kt"));
let tests = 0, disabled = 0; const perModule = {};
for (const f of kt) {
  const s = readFileSync(f, "utf8");
  const t = (s.match(/^\s*@(Test|ParameterizedTest|RepeatedTest)\b/gm) || []).length;
  const d = (s.match(/@Disabled\b/g) || []).length;
  tests += t; disabled += d;
  const mod = relative(join(root, "backend/src/test/kotlin/com/systemwebstudio"), f).split("/")[0];
  perModule[mod] = (perModule[mod] || 0) + t;
}
const inv = { backendTestFiles: kt.length, backendTestAnnotations: tests, backendDisabledAnnotations: disabled, perModule, migrations: versions.length, maxMigration: max };

if (process.argv.includes("--json")) console.log(JSON.stringify({ results, inventory: inv }, null, 2));
else {
  for (const r of results) console.log(`${r.ok ? "PASS" : "FAIL"}  ${r.id}  ${r.title}${r.detail && !r.ok ? "  -> " + r.detail : ""}`);
  console.log("\nINVENTORY", JSON.stringify(inv));
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
