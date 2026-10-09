#!/usr/bin/env node
// @class: unit
// CLI of the explicit public deployment pinning (scripts/lib/public-release.mjs, D-C0-49). Used through scripts/public-portals.sh.
//   status [--json]            portal pid port health | running / approved / integration source | build + config fingerprint | state. Exit 0 only when every portal is CURRENT_APPROVED.
//   up                         start the APPROVED release where it is not running (never builds, never uses HEAD). No approved release => NO_APPROVED_RELEASE (exit 5), nothing started.
//   restart                    stop and start the SAME approved release
//   down                       stop the public portals this tool started
//   deploy <sha|ref>           the ONLY way to change what is public: build a candidate, prove it healthy on temporary ports, move the approved pointer, replace the running portals, roll back on failure
//   rollback [release-id]      explicit return to the previous known-good (or the given) retained release
//   init --from-running [--dry-run]   pin the release that runs RIGHT NOW (evidence based; restarts nothing; --dry-run only shows the evidence)
//   releases | prune | verify  list / drop unreferenced releases (keeps approved + previous known-good) / deep-verify the approved artifact
// Exit codes: 0 ok · 1 failed / not current · 3 FOREIGN_PROCESS · 5 NO_APPROVED_RELEASE · 6 APPROVED_BUILD_MISSING · 7 CANDIDATE_* / ACTIVATION_* · 8 LOCKED · 64 usage.
import { context, deploy, rollback, up, restart, down, statusWithHealth, formatStatus, okStatus, initFromRunning, listReleases, readApproved, readRelease, prune, verifyRelease, ReleaseError } from "./lib/public-release.mjs";

const argv = process.argv.slice(2); const cmd = argv[0]; const rest = argv.slice(1); const has = (f) => rest.includes(f);
const c = context(process.env, new URL("..", import.meta.url).pathname.replace(/\/$/, ""));
const say = (m) => console.log(m);
const CODES = { FOREIGN_PROCESS: 3, NO_APPROVED_RELEASE: 5, APPROVED_BUILD_MISSING: 6, CANDIDATE_BUILD_FAILED: 7, CANDIDATE_VALIDATION_FAILED: 7, ACTIVATION_FAILED_ROLLED_BACK: 7, ACTIVATION_FAILED: 7, DEPS_FAILED: 7, SNAPSHOT_FAILED: 7, LOCKED: 8, USAGE: 64, UNKNOWN_SOURCE: 64, NO_PREVIOUS_KNOWN_GOOD: 1, ADOPT_REFUSED: 1, RELEASE_TAMPERED: 6 };
try {
  if (cmd === "status") { const s = await statusWithHealth(c); if (has("--json")) say(JSON.stringify({ approved: s.approved, head: s.head, behind: s.behind, crash: s.crash, verify: s.verify, rows: s.rows })); else say(formatStatus(s)); process.exit(okStatus(s) ? 0 : (s.rows.some((r) => r.state === "APPROVED_BUILD_MISSING") ? 6 : s.rows.some((r) => r.state === "NO_APPROVED_RELEASE") ? 5 : 1)); }
  else if (cmd === "up") { const r = await up(c, say); say(`approved release ${r.releaseId}: ${r.started.length ? "started " + r.started.join(", ") : "nothing to start"}`); }
  else if (cmd === "restart") { const r = await restart(c, say); say(`restarted the approved release ${r.releaseId} (no build, no source change)`); }
  else if (cmd === "down") { const r = await down(c, say); say(JSON.stringify(r)); }
  else if (cmd === "deploy") { const r = await deploy(c, rest.find((x) => !x.startsWith("--")), say); say(r.noop ? `release ${r.releaseId} is already approved: nothing deployed` : `DEPLOYED ${r.releaseId} (previous known-good: ${r.previous ?? "none"}); pruned: ${(r.pruned ?? []).join(", ") || "-"}. Portals were restarted one after the other: NOT zero-downtime.`); }
  else if (cmd === "rollback") { const r = await rollback(c, rest.find((x) => !x.startsWith("--")), say); say(`ROLLED BACK to ${r.releaseId} (from ${r.previous})`); }
  else if (cmd === "init") { if (!has("--from-running")) throw new ReleaseError("USAGE", "init needs --from-running (pin what runs now) - or use deploy <sha>"); const r = await initFromRunning(c, say, { dryRun: has("--dry-run") }); if (r.dryRun) { say(`DRY RUN (nothing written, nothing restarted): the running public portals can be pinned as release ${r.releaseId} (source ${r.sourceSha})`); for (const e of r.evidence) say(`  ${e.portal}: pid ${e.pid}  dist ${e.dist}  BUILD_ID ${e.buildId}  commit ${e.commit} (clean tree)  served assets in dist ${e.servedAssetsInDist}  fingerprint recorded ${e.recordedFingerprint} == snapshot ${e.snapshotFingerprint}`); process.exit(0); } say(`PINNED ${r.releaseId} as the approved public release (source ${r.sourceSha}); nothing was restarted`); }
  else if (cmd === "releases") { const a = readApproved(c); for (const id of listReleases(c)) { const rel = readRelease(c, id); say(`${id === a?.releaseId ? "approved " : id === a?.previousKnownGood ? "previous " : "retained "} ${id}  ${rel.kind}  source ${rel.sourceShort}  config ${rel.configFingerprint}  ${rel.createdAt}`); } if (!listReleases(c).length) say("(no releases)"); }
  else if (cmd === "prune") { const r = prune(c, say); say(`removed: ${r.removed.join(", ") || "-"}  kept: ${r.kept.join(", ") || "-"}${r.skipped ? "  (" + r.skipped + ")" : ""}`); }
  else if (cmd === "verify") { const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved release"); const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); say(v.ok ? `approved release ${a.releaseId}: artifact verified (BUILD_ID + content digest of every portal)` : v.problems.join("\n")); process.exit(v.ok ? 0 : 6); }
  else if (cmd === "build") { throw new ReleaseError("USAGE", "public portals are not built from the working tree any more: use `deploy <sha>` (candidate build + proof + approval)"); }
  else throw new ReleaseError("USAGE", "usage: public-portals.sh status|up|restart|down|deploy <sha>|rollback [id]|init --from-running|releases|prune|verify");
} catch (e) {
  const code = e instanceof ReleaseError ? e.code : (e.code ?? "ERROR"); console.error(`${code}: ${e.message}`); process.exit(CODES[code] ?? 1);
}
