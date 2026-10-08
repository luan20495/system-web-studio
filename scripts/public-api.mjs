#!/usr/bin/env node
// @class: unit
// CLI of the explicit public API release pinning (scripts/lib/public-api-release.mjs, D-C0-50). Used through scripts/public-api.sh.  API RECOVERY != API DEPLOYMENT.
//   status [--json]              port pid health | running / approved / integration source | jar digest | config fingerprint | state. Exit 0 only when CURRENT_APPROVED.
//   up                           start the APPROVED jar if the API is not running (java -jar <release>/app.jar: no Gradle, no compile, no source). No approved release => NO_APPROVED_RELEASE (exit 5), nothing started.
//   restart                      stop and start the SAME approved jar
//   down                         stop the API this tool started (a foreign listener is left alone)
//   deploy-api <sha|ref> [--skip-tests] [--expand-only]
//                                the ONLY way to change what is public: snapshot of that SHA -> backend tests -> jar -> candidate on a temporary port + scratch DB -> schema verdict -> pointer moves -> replace -> rollback on failure.
//                                --skip-tests is recorded in the release; --expand-only declares that the migrations this release adds keep the previous release working (allows a later rollback-api).
//   rollback-api [release-id]    explicit return to the previous known-good (retained) artifact; refused when the database is ahead of it (ROLLBACK_SCHEMA_INCOMPATIBLE)
//   init-api --from-running [--source <sha>] [--dry-run]   pin the API that runs RIGHT NOW (evidence based, rebuilds the candidate source to PROVE it, restarts nothing)
//   releases-api | prune-api | verify-api
// Exit codes: 0 ok · 1 failed / not current · 3 FOREIGN_PROCESS · 5 NO_APPROVED_RELEASE · 6 APPROVED_API_BUILD_MISSING / API_RELEASE_TAMPERED · 7 CANDIDATE_* / ACTIVATION_* · 9 schema (ROLLBACK_SCHEMA_INCOMPATIBLE, FLYWAY_CHECKSUM_MISMATCH, SCHEMA_DIRTY, SCHEMA_CHECK_UNAVAILABLE) · 8 LOCKED · 64 usage.
import { apiContext, deployApi, rollbackApi, upApi, restartApi, downApi, statusWithHealth, formatStatus, okStatus, initApiFromRunning, listReleases, readApproved, readRelease, prune, verifyRelease, ReleaseError } from "./lib/public-api-release.mjs";

const argv = process.argv.slice(2); const cmd = argv[0]; const rest = argv.slice(1); const has = (f) => rest.includes(f); const val = (f) => { const i = rest.indexOf(f); return i >= 0 ? rest[i + 1] : null; };
const c = apiContext(process.env, new URL("..", import.meta.url).pathname.replace(/\/$/, ""));
const say = (m) => console.log(m);
const CODES = { FOREIGN_PROCESS: 3, NO_APPROVED_RELEASE: 5, APPROVED_API_BUILD_MISSING: 6, API_RELEASE_TAMPERED: 6, CANDIDATE_BUILD_FAILED: 7, CANDIDATE_TESTS_FAILED: 7, CANDIDATE_VALIDATION_FAILED: 7, ACTIVATION_FAILED_ROLLED_BACK: 7, ACTIVATION_FAILED_ROLLBACK_BLOCKED: 7, ACTIVATION_FAILED: 7, SNAPSHOT_FAILED: 7, REHEARSAL_FAILED: 7, START_FAILED: 7,
  ROLLBACK_SCHEMA_INCOMPATIBLE: 9, FLYWAY_CHECKSUM_MISMATCH: 9, SCHEMA_DIRTY: 9, SCHEMA_CHECK_UNAVAILABLE: 9, DB_UNAVAILABLE: 9, LOCKED: 8, USAGE: 64, UNKNOWN_SOURCE: 64, ADOPT_REFUSED: 1, NO_PREVIOUS_KNOWN_GOOD: 1 };
try {
  if (cmd === "status") { const s = await statusWithHealth(c); if (has("--json")) say(JSON.stringify({ approved: s.approved, head: s.head, behind: s.behind, behindTotal: s.behindTotal, crash: s.crash, incident: s.incident, verify: s.verify, drift: s.drift, rollback: s.rollback, row: s.row })); else say(formatStatus(s)); process.exit(okStatus(s) ? 0 : 1); }
  else if (cmd === "up") { const r = await upApi(c, say); say(`approved API release ${r.releaseId}: ${r.started ? "started" : "already running"}`); }
  else if (cmd === "restart") { const r = await restartApi(c, say); say(`restarted the approved API release ${r.releaseId} (no build, no source change)`); }
  else if (cmd === "down") say(JSON.stringify(await downApi(c, say)));
  else if (cmd === "deploy-api") { const r = await deployApi(c, rest.find((x) => !x.startsWith("--")), { skipTests: has("--skip-tests"), expandOnly: has("--expand-only"), log: say }); say(r.noop ? `API release ${r.releaseId} is already approved: nothing deployed` : `DEPLOYED API ${r.releaseId} (previous known-good: ${r.previous ?? "none"}); pruned: ${r.pruned?.join(", ") || "-"}`); }
  else if (cmd === "rollback-api") { const r = await rollbackApi(c, rest.find((x) => !x.startsWith("--")), say); say(`ROLLED BACK the API to ${r.releaseId} (from ${r.previous})`); }
  else if (cmd === "init-api") {
    if (!has("--from-running")) throw new ReleaseError("USAGE", "init-api needs --from-running (pin what runs now) - or use deploy-api <sha>");
    const r = await initApiFromRunning(c, { source: val("--source"), dryRun: has("--dry-run"), log: say });
    if (r.dryRun) { say(`DRY RUN (nothing written, nothing restarted): the running API can be pinned as release ${r.releaseId} (source ${r.sourceSha})`); say(JSON.stringify(r.evidence, null, 2)); process.exit(0); }
    say(`PINNED API ${r.releaseId} as the approved public API release (source ${r.sourceSha}); nothing was restarted`);
  }
  else if (cmd === "releases-api") { const a = readApproved(c); for (const id of listReleases(c)) { const rel = readRelease(c, id); say(`${id === a?.releaseId ? "approved " : id === a?.previousKnownGood ? "previous " : "retained "} ${id}  ${rel.kind}  source ${rel.sourceShort}  jar ${rel.jar.sha256.slice(0, 12)}  schema ${rel.schema.maxVersion}  ${verifyRelease(c, id, { deep: false, approved: a }).ok ? "ok" : "MISSING/CORRUPT"}`); } }
  else if (cmd === "prune-api") { const r = prune(c, say); say(`removed: ${r.removed.join(", ") || "-"}  kept: ${r.kept.join(", ") || "-"}${r.skipped ? "  (" + r.skipped + ")" : ""}`); }
  else if (cmd === "verify-api") { const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved API release"); const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); say(v.ok ? `approved API release ${a.releaseId}: jar sha256 verified` : v.problems.join("\n")); process.exit(v.ok ? 0 : 6); }
  else throw new ReleaseError("USAGE", "usage: public-api.sh status|up|restart|down|deploy-api <sha> [--skip-tests] [--expand-only]|rollback-api [id]|init-api --from-running [--source <sha>] [--dry-run]|releases-api|prune-api|verify-api");
} catch (e) { const code = e instanceof ReleaseError ? e.code : (e.code ?? "ERROR"); console.error(`${code}: ${e.message}`); process.exit(CODES[code] ?? 1); }
