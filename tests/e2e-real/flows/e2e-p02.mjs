// @class: real-backend — C2 contract §7 D/E: two publishes with different keys are two deployments, both 202; they activate in activation-number order and the older one, if the newer already activated, ends
// FAILED [STALE_PUBLISH]. Same key + same payload = 202 + `Idempotent-Replay: true` and the ORIGINAL deployment.
import { releaseApi, sleep, until } from "../lib/release.mjs";
export const id = "E2E-P02", title = "Concurrent publish on the same scope + idempotent replay";
export async function run({ fx, check }) {
  const api = releaseApi(fx);
  await api.edit(`p02-${fx.runId}`); const rev = await api.revision(), pv0 = (await api.site()).pointerVersion;
  const [a, b] = await Promise.all([api.publish(`e2e:${fx.runId}:p02a`, "PRIVATE", rev), api.publish(`e2e:${fx.runId}:p02b`, "PRIVATE", rev)]);
  check.ok("both concurrent publishes are accepted (202) as two DIFFERENT deployments", a.status === 202 && b.status === 202 && a.body.id !== b.body.id, `${a.status}/${b.status}`, "http");
  const [da, db] = await Promise.all([api.settle(a.body.id), api.settle(b.body.id)]);
  const okEnd = (d) => d.status === "RUNNING" || (d.status === "FAILED" && /^\[STALE_PUBLISH\]/.test(d.error ?? ""));
  check.ok("each ends RUNNING or FAILED [STALE_PUBLISH] — nothing stuck, no other failure", okEnd(da) && okEnd(db), `${da.status} ${da.error ?? ""} | ${db.status} ${db.error ?? ""}`, "persistence");
  check.ok("at least one of them is RUNNING and it is the one the site now serves", [da, db].some((d) => d.status === "RUNNING"), `${da.status} | ${db.status}`, "persistence");
  const site = await until(() => api.site(), (s) => s.operation === null, 15_000);
  check.ok("the site serves a RUNNING one of the two and is idle", [da, db].some((d) => d.status === "RUNNING" && d.id === site.currentDeploymentId) && site.operation === null, JSON.stringify({ cur: site.currentDeploymentId, op: site.operation }), "http");
  check.ok("pointerVersion moved forward (by 1 per activation, so +1 or +2)", site.pointerVersion >= pv0 + 1 && site.pointerVersion <= pv0 + 2, `${pv0} → ${site.pointerVersion}`, "persistence");
  // replay: same key + same payload → the ORIGINAL deployment, flagged
  const r1 = await api.S.post(`${api.base}/publish`, { visibility: "PRIVATE", expectedRevision: rev }, { headers: { "Idempotency-Key": `e2e:${fx.runId}:p02a` } });
  check.ok("same key + same payload → 202 with Idempotent-Replay: true and the ORIGINAL deployment id", r1.status === 202 && r1.headers.get("idempotent-replay") === "true" && r1.body.id === a.body.id, `status=${r1.status} replay=${r1.headers.get("idempotent-replay")}`, "http");
  const fresh = await api.S.post(`${api.base}/publish`, { visibility: "PRIVATE", expectedRevision: rev }, { headers: { "Idempotency-Key": `e2e:${fx.runId}:p02c-${Date.now()}` } });
  check.ok("a NEW key with the same payload is a NEW deployment (not a replay)", fresh.status === 202 && fresh.headers.get("idempotent-replay") === null && fresh.body.id !== a.body.id && fresh.body.id !== b.body.id, `status=${fresh.status}`, "http");
  await api.settle(fresh.body.id); await sleep(300);
}
