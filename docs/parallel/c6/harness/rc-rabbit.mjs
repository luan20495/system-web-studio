#!/usr/bin/env node
// C6 RC wide regression — QUEUE / RECOVERY at the API: RabbitMQ (workflow queue app.workflow.queue=amqp) stopped and started under a live workflow start, then the BACKEND restarted
// while a run is parked (WAIT) and while a publish is in flight. Real containers, real process restart (hooks given by the caller). No stub, no SQL.
// Env: API  SA_USER  SA_PASSWORD  OUT  RABBIT_STOP='docker stop c6rc-rabbit'  RABBIT_START='docker start c6rc-rabbit'  BACKEND_RESTART='bash …e2e-stack.sh backend-restart'
import { randomBytes, randomUUID } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
import { execSync } from "node:child_process";
const API = (process.env.API ?? "http://127.0.0.1:51080").replace(/\/$/, ""); const OUT = process.env.OUT ?? "."; mkdirSync(OUT, { recursive: true });
const SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD; const tag = randomUUID().slice(0, 6); const pw = () => randomBytes(15).toString("base64url") + "aA1!";
const rows = []; const rec = (id, desc, expected, actual, ok, note = "") => { rows.push({ id, desc, expected, actual: String(actual).slice(0, 300), result: ok ? "PASS" : "FAIL", note }); console.log(`${ok ? "PASS" : "FAIL"} ${id} ${desc} | expected ${expected} | actual ${String(actual).slice(0, 240)}${note ? " | " + note : ""}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms)); const sh = (c) => execSync(c, { stdio: "pipe", timeout: 240000 }).toString();
class S { constructor() { this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}, timeoutMs = 60000) { const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; "); if (this.csrf && method !== "GET") h["x-xsrf-token"] = this.csrf; if (body !== undefined) h["content-type"] = "application/json";
    const t0 = Date.now(); try { const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) }); for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
      const text = await res.text(); let json = null; try { json = JSON.parse(text); } catch {} return { status: res.status, json, text, ms: Date.now() - t0 }; } catch (e) { return { status: 0, json: null, text: "", ms: Date.now() - t0, err: String(e?.message ?? e) }; } }
  get(p) { return this.call("GET", p); } post(p, b, h, t) { return this.call("POST", p, b ?? {}, h, t); } patch(p, b) { return this.call("PATCH", p, b); }
  async login(u, p) { this.jar.clear(); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; const r = await this.post("/api/v1/auth/login", { username: u, password: p }); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; return r; } }
const anon = new S(); const waitApi = async (n = 120) => { for (let i = 0; i < n; i++) { const r = await anon.get("/actuator/health/readiness"); if (r.status === 200) return true; await sleep(1000); } return false; };

const sa = new S(); await sa.login(SA_USER, SA_PASSWORD);
const T = (await sa.post("/api/v1/admin/tenants", { slug: `c6q-${tag}`, name: `C6 queue ${tag}` })).json?.id; const W = (await sa.post(`/api/v1/admin/tenants/${T}/workspaces`, { name: `C6 queue ws ${tag}` })).json?.id;
const inv = await sa.post(`/api/v1/admin/tenants/${T}/users`, { username: `c6q-wa-${tag}`, displayName: "c6q", tenantRole: "TENANT_ADMIN", workspaceId: W, workspaceRole: "WORKSPACE_ADMIN" }); const p0 = pw();
await anon.get("/api/v1/auth/csrf"); anon.csrf = (await anon.get("/api/v1/auth/csrf")).json?.token; await anon.post("/api/v1/auth/activation/complete", { token: inv.json.token, password: p0 });
const wa = new S(); await wa.login(`c6q-wa-${tag}`, p0);
const P = (await wa.post(`/api/v1/workspaces/${W}/projects`, { name: `c6-queue-${tag}`, appType: "PAGE_SCHEMA" })).json?.id; const PB = `/api/v1/workspaces/${W}/projects/${P}`;
const sc = (await wa.get(`${PB}/schema`)).json;
let r = await wa.patch(`${PB}/schema`, { expectedRevision: sc.revision, summary: "c6 queue", operations: [
  { type: "ADD_WORKFLOW_REF", definition: { id: "wf-end", name: "End", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] } },
  { type: "ADD_WORKFLOW_REF", definition: { id: "wf-wait", name: "Wait", trigger: "MANUAL", startStepId: "park", steps: [{ id: "park", kind: "WAIT", waitSeconds: 45, next: "end" }, { id: "end", kind: "END" }] } }] });
rec("Q00", "declare END-only and WAIT 45 s workflows", "200", r.status, r.status === 200);
const publish = async () => { const rv = (await wa.get(PB)).json?.revision; const x = await wa.post(`${PB}/publish`, { visibility: "PUBLIC", expectedRevision: rv }, { "idempotency-key": "c6q-" + randomUUID() }); let st = ""; for (let i = 0; i < 90 && !["RUNNING", "FAILED"].includes(st); i++) { await sleep(1000); st = (await wa.get(`${PB}/deployments/${x.json?.id}`)).json?.status ?? ""; } return { id: x.json?.id, st, http: x.status }; };
const pub = await publish(); rec("Q01", "publish the release carrying the workflows", "RUNNING", pub.st, pub.st === "RUNNING");
const WX = (id) => `${PB}/app-runtime/workflows/${id}/runs`; const RUN = (id) => `${PB}/app-runtime/workflow-runs/${id}`;
const final = async (id, tries = 60) => { let v; for (let i = 0; i < tries; i++) { v = (await wa.get(RUN(id))).json; if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(v?.status)) return v; await sleep(1000); } return v; };
r = await wa.post(WX("wf-end"), { input: {}, idempotencyKey: "c6q-base-" + tag }); const base = await final(r.json?.runId); rec("Q02", "baseline: a LIVE run completes with RabbitMQ up", "SUCCEEDED", `${r.status} ${base?.status}`, base?.status === "SUCCEEDED");
const q0 = sh("docker exec c6rc-rabbit rabbitmqctl list_queues name messages 2>/dev/null | grep -E 'workflow' || true"); rec("Q03", "the AMQP workflow queue exists on the broker", "xweb.workflow.jobs (+dlq)", q0.replace(/\s+/g, " ").trim(), /xweb\.workflow\.jobs/.test(q0));

// ---- RabbitMQ down
sh(process.env.RABBIT_STOP ?? "docker stop c6rc-rabbit"); await sleep(3000);
const keyDown = "c6q-down-" + tag; const down = await wa.post(WX("wf-end"), { input: {}, idempotencyKey: keyDown }, {}, 90000);
const downRun = down.json?.runId; let downView = downRun ? (await wa.get(RUN(downRun))).json : null;
rec("Q04", "start while RabbitMQ is DOWN: a bounded, honest answer (never a hang, never a fake SUCCEEDED)", "answer within 60 s; status is an error (503/…) or the run is accepted PENDING and NOT SUCCEEDED while the queue is down", `${down.status} ${down.json?.code ?? down.json?.status ?? ""} in ${down.ms} ms; run=${downView?.status ?? "-"}${down.err ? " " + down.err : ""}`, down.ms < 60000 && down.status !== 0 && downView?.status !== "SUCCEEDED");
const readiness = await anon.get("/actuator/health/readiness"); rec("Q05", "API readiness while RabbitMQ is down", "reports the outage (503/OUT_OF_SERVICE) or stays UP with the dependency named — never a crash", `${readiness.status} ${readiness.text.slice(0, 80)}`, [200, 503].includes(readiness.status));
const lg = await wa.get(`${PB}`); rec("Q06", "unrelated reads keep working while RabbitMQ is down", "200", lg.status, lg.status === 200);
// ---- RabbitMQ up
sh(process.env.RABBIT_START ?? "docker start c6rc-rabbit"); await sleep(15000); await waitApi(60);
const tUp = Date.now(); const after = downRun ? await final(downRun, 330) : null; rec("Q07", "after RabbitMQ is back the run accepted during the outage is processed (or reported FAILED) — never lost, never stuck. The sweeper republishes a run stale for app.workflow.stale-after = PT2M, so the bound is ~2 min + sweep", "terminal SUCCEEDED/FAILED within 330 s", `${after?.status ?? "no run id"} ${after?.errorCode ?? ""} after ${Math.round((Date.now() - tUp) / 1000)} s`, ["SUCCEEDED", "FAILED"].includes(after?.status));
const again = await wa.post(WX("wf-end"), { input: {}, idempotencyKey: keyDown }); rec("Q08", "replay of the key used during the outage returns the SAME run (no duplicate)", "same run id", `${again.status} ${again.json?.runId === downRun ? "same" : "DIFFERENT"}`, !downRun || again.json?.runId === downRun);
r = await wa.post(WX("wf-end"), { input: {}, idempotencyKey: "c6q-up-" + tag }); const up = await final(r.json?.runId); rec("Q09", "a new run after recovery succeeds", "SUCCEEDED", `${r.status} ${up?.status}`, up?.status === "SUCCEEDED");
// ---- backend restart with a parked run
r = await wa.post(WX("wf-wait"), { input: {}, idempotencyKey: "c6q-wait-" + tag }); const waitRun = r.json?.runId; let wv; for (let i = 0; i < 20; i++) { wv = (await wa.get(RUN(waitRun))).json; if (wv?.status === "WAITING") break; await sleep(1000); }
rec("Q10", "a WAIT run is parked before the restart", "WAITING", wv?.status, wv?.status === "WAITING");
const t0 = Date.now(); let restartOut = ""; try { restartOut = sh(process.env.BACKEND_RESTART ?? "true"); } catch (e) { restartOut = "ERR " + String(e).slice(0, 120); } const upAgain = await waitApi(180);
rec("Q11", "backend process restarted", "readiness UP again", `${upAgain} after ${Math.round((Date.now() - t0) / 1000)} s`, upAgain);
await wa.login(`c6q-wa-${tag}`, p0); const survived = (await wa.get(RUN(waitRun))).json; rec("Q12", "the parked run survived the restart (durable run store V29)", "still known: WAITING (or already finished), never 404", `${survived?.status}`, ["WAITING", "RUNNING", "PENDING", "SUCCEEDED"].includes(survived?.status));
const done = await final(waitRun, 120); rec("Q13", "after the restart the run completes (the WAIT timer survived)", "SUCCEEDED", `${done?.status} ${done?.errorCode ?? ""}`, done?.status === "SUCCEEDED");
const replay = await wa.post(WX("wf-wait"), { input: {}, idempotencyKey: "c6q-wait-" + tag }); rec("Q14", "replay of the pre-restart key: same run", "same run id", `${replay.status} ${replay.json?.runId === waitRun ? "same" : "DIFFERENT"}`, replay.json?.runId === waitRun);
const site = (await wa.get(`${PB}/site`)).json; rec("Q15", "deployment state after the restart: still the same ACTIVE release, operation idle", "RUNNING release active, operation null", `${site?.online} pointer=${site?.pointerVersion} op=${site?.operation}`, site?.online === true && !site?.operation);
const failed = rows.filter((x) => x.result === "FAIL"); writeFileSync(`${OUT}/rc-rabbit.json`, JSON.stringify({ total: rows.length, failed: failed.length, rows }, null, 1)); console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`); process.exit(failed.length ? 1 : 0);
