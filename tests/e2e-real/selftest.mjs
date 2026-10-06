// @class: mock — checks ONLY the plumbing of the real-backend suite (its guard and its exit codes) against throw-away local HTTP servers.
// It proves that the suite can never report success without a real backend. It says NOTHING about the product.
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { summarise, formatEvidence, kindOf } from "./lib/report.mjs";
import { loadDataSourceFacts, viewerPolicyOf, shuffled } from "./lib/env.mjs";
import { builderUrl, boundedRetry, containsText, rememberMarker, liveMarkers } from "./lib/ui.mjs";
import { deniedWriteProbe, e2eActionDefinition } from "./lib/fixtures.mjs";

// async on purpose: the throw-away servers live in THIS process and must keep answering while the suite runs
const run = (env) => new Promise((res) => execFile(process.execPath, [new URL("./run.mjs", import.meta.url).pathname], { env: { PATH: process.env.PATH, ...env }, encoding: "utf8", timeout: 60_000 }, (err, stdout, stderr) => res({ status: err ? (typeof err.code === "number" ? err.code : 99) : 0, stdout, stderr })));
const results = []; const check = (n, ok, d = "") => { results.push(ok); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${!ok && d ? " — " + d : ""}`); };
const serve = (handler) => new Promise((res) => { const s = createServer(handler).listen(0, "127.0.0.1", () => res(s)); });
const creds = { E2E_ADMIN_USER: "u", E2E_ADMIN_PASSWORD: "p" };

let r = await run({});
check("no environment → exit 2 and says NOT RUN", r.status === 2 && /NOT RUN/.test(r.stderr), `status=${r.status}`);
r = await run({ ...creds, E2E_STUDIO_URL: "http://127.0.0.1:1" });
check("unreachable backend → exit 2", r.status === 2 && /cannot reach/.test(r.stderr), r.stderr.slice(0, 120));
let s = await serve((_, res) => { res.statusCode = 200; res.setHeader("content-type", "application/json"); res.end('{"hello":"world"}'); });
r = await run({ ...creds, E2E_STUDIO_URL: `http://127.0.0.1:${s.address().port}` });
check("a server that is not the platform API → exit 2", r.status === 2 && /not the backend/.test(r.stderr), r.stderr.slice(0, 160)); s.close();
s = await serve((_, res) => { res.statusCode = 200; res.setHeader("content-type", "application/json"); res.end('{"localLogin":false,"oidc":true}'); });
r = await run({ ...creds, E2E_STUDIO_URL: `http://127.0.0.1:${s.address().port}` });
check("local login disabled → exit 2 (fixtures impossible)", r.status === 2 && /local login is disabled/.test(r.stderr), r.stderr.slice(0, 160)); s.close();
s = await serve((req, res) => { res.statusCode = req.url.includes("auth/config") ? 200 : 401; res.setHeader("content-type", "application/json"); res.end(req.url.includes("auth/config") ? '{"localLogin":true,"oidc":false}' : '{"code":"BAD_CREDENTIALS"}'); });
r = await run({ ...creds, E2E_STUDIO_URL: `http://127.0.0.1:${s.address().port}` });
check("platform-like server but admin login refused → exit 2 (setup failed), never 0", r.status === 2 && /fixtures could not be created/.test(r.stderr), `status=${r.status} ${r.stderr.slice(0, 160)}`); s.close();
check("summary counts BLOCKED/SKIP separately from PASS", JSON.stringify(summarise([{ status: "PASS" }, { status: "BLOCKED" }, { status: "SKIP" }, { status: "FAIL" }])) === '{"total":4,"PASS":1,"FAIL":1,"BLOCKED":1,"SKIP":1}');
check("E2E_DS_* absent → not provided", loadDataSourceFacts({}).provided === false);
check("E2E_DS_* needs a JSON-object config", loadDataSourceFacts({ E2E_DS_TYPE: "postgres" }).error === "E2E_DS_CONFIG_JSON is required with E2E_DS_TYPE");
check("malformed E2E_DS_CONFIG_JSON → an error that does NOT contain the value", (() => { const f = loadDataSourceFacts({ E2E_DS_TYPE: "postgres", E2E_DS_CONFIG_JSON: "{host: secret-host" }); return f.error === "E2E_DS_CONFIG_JSON is not valid JSON" && !JSON.stringify(f).includes("secret-host"); })());
check("malformed credential JSON → a reason without the value", (() => { const f = loadDataSourceFacts({ E2E_DS_TYPE: "postgres", E2E_DS_CONFIG_JSON: "{}", E2E_DS_CREDENTIAL_JSON: "[1]" }); return f.error === "E2E_DS_CREDENTIAL_JSON must be a JSON object"; })());
check("valid E2E_DS_* → provided with parsed values", (() => { const f = loadDataSourceFacts({ E2E_DS_TYPE: "postgres", E2E_DS_CONFIG_JSON: '{"host":"h"}', E2E_DS_CREDENTIAL_JSON: '{"username":"u"}' }); return f.provided && !f.error && f.config.host === "h" && f.credential.username === "u"; })());
check("evidence kinds: restart/persistence/http/ui are told apart; an explicit kind wins", kindOf("the backend is reachable again after the restart") === "recovery" && kindOf("the run was accepted (202) before the restart") === "http" && kindOf("server revision increased") === "persistence" && kindOf("PATCH …/schema answered 200") === "http" && kindOf("the canvas shows the section") === "ui" && kindOf("whatever", "recovery") === "recovery");
check("evidence block has every field C6 asks for, and shows a blocker only for BLOCKED/FAIL", (() => {
  const meta = { frontendHead: "abc", backend: { url: null, head: null }, studio: "http://s" };
  const pass = formatEvidence(meta, { id: "E2E-X", title: "t", status: "PASS", start: "a", end: "b", checks: [{ name: "PATCH answered 200", ok: true, kind: "http" }] });
  const blocked = formatEvidence(meta, { id: "E2E-Y", title: "t", status: "BLOCKED", owner: "C1", reason: "because", ref: "r", checks: [] });
  const fields = ["FLOW:", "RESULT:", "START:", "END:", "FRONTEND_HEAD:", "BACKEND_HEAD:", "BACKEND_URL:", "PROJECT:", "WORKSPACE:", "HTTP EVIDENCE:", "UI ASSERTION:", "PERSISTENCE ASSERTION:", "RESTART/RECOVERY:", "BLOCKER:", "OWNER:"];
  return fields.every((f) => pass.includes(f) && blocked.includes(f)) && /BLOCKER: none/.test(pass) && /BLOCKER: because/.test(blocked) && /OWNER: C1/.test(blocked);
})());
// ---- regressions of the bugs the first real run found (each one failed once against the real backend) ----
check("REGRESSION builder route: the Builder URL is /studio/projects/<id>/design, never the AI-only route", builderUrl({ studio: "http://s", studioPrefix: "/studio" }, "p1") === "http://s/studio/projects/p1/design");
check("REGRESSION authorization probe: the denied write is a VALID operation (never an empty list, which is a 400 before any permission check)", (() => {
  const w = deniedWriteProbe("x"); const op = w.operations[0];
  return Array.isArray(w.operations) && w.operations.length === 1 && op.type === "ADD_ACTION" && !!op.definition?.id && !!op.definition?.type && w.summary === "x";
})());
check("REGRESSION action without trigger: the fixture action DECLARES a trigger (UI-bound runs need one, D-C4-10); without a section it has none and the flows are told", (() => {
  const a = e2eActionDefinition("navbar-1"), b = e2eActionDefinition(undefined);
  return a.trigger?.sectionId === "navbar-1" && a.trigger?.event === "onClick" && a.type === "START_WORKFLOW" && b.trigger === undefined;
})());
await (async () => {
  let calls = 0; const never = await boundedRetry(4, async () => { calls++; return false; });
  check("REGRESSION bounded retry: an always-failing step is tried exactly N times, then reported as failed (no endless loop)", calls === 4 && never.ok === false && never.attempts === 4);
  calls = 0; const third = await boundedRetry(4, async (n) => { calls++; return n === 3; });
  check("bounded retry: stops at the first success and says how many attempts it took", calls === 3 && third.ok === true && third.attempts === 3);
  calls = 0; const zero = await boundedRetry(0, async () => { calls++; return false; });
  check("bounded retry: 0 or negative means one attempt, never zero and never unbounded", calls === 1 && zero.attempts === 1);
})();
check("REGRESSION CSS case: a marker is found when the page shows it upper-cased (text-transform)", containsText("Sản phẩm E2E-ABC-XYZ Liên hệ", "e2e-abc-xyz") && containsText("E2E-ABC-XYZ", "E2E-abc-xyz"));
check("CSS case: a DIFFERENT, truncated or empty marker is still caught", !containsText("E2E-ABC-1", "e2e-abc-2") && !containsText("E2E-ABC", "e2e-abc-1") && !containsText("anything", "") && !containsText("anything", undefined));
check("viewer policy from the environment: only the two decided values are accepted, anything else is treated as undecided", viewerPolicyOf({ E2E_VIEWER_POLICY: "app-view" }) === "app-view" && viewerPolicyOf({ E2E_VIEWER_POLICY: "no-studio" }) === "no-studio" && viewerPolicyOf({ E2E_VIEWER_POLICY: "yes" }) === null && viewerPolicyOf({}) === null);
check("shuffle: deterministic per seed, a permutation (nothing lost or duplicated), different seeds differ", (() => { const a = ["a", "b", "c", "d", "e", "f", "g"]; const x = shuffled(a, 7), y = shuffled(a, 7), z = shuffled(a, 8); return JSON.stringify(x) === JSON.stringify(y) && [...x].sort().join() === a.join() && JSON.stringify(x) !== JSON.stringify(z); })());
check("REGRESSION order dependency: the expected public text is the latest marker the server STILL holds (an overwritten earlier marker is ignored, whatever the flow order)", (() => {
  const fx = { notes: {} }; rememberMarker(fx, "E2E-A"); rememberMarker(fx, "E2E-B"); rememberMarker(fx, "E2E-C");
  const draft = { sections: [{ props: { eyebrow: "E2E-B" } }] };            // B was saved last; A was overwritten; C was never saved
  return JSON.stringify(liveMarkers(fx, draft)) === '["E2E-B"]' && liveMarkers({ notes: {} }, draft).length === 0;
})());
const bad = results.filter((x) => !x).length; console.log(`\n${results.length - bad}/${results.length} passed`); process.exit(bad ? 1 : 0);
