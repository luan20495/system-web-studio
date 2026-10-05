// k6 load test for the API (backend directly, so the Next.js proxy is not what is being measured).
//   k6 run -e SCENARIO=reads -e VUS=50 -e DURATION=30s infra/load/studio.k6.js
// Scenarios: reads (list/get project/get schema), prompt (mock LLM, writes a version each time), publish (async pipeline),
// login (Argon2id, deliberately low arrival rate: it is meant to be expensive).
import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE = __ENV.BASE || "http://127.0.0.1:8080/api/v1";
const USER = __ENV.LOAD_USER || "local.admin";
const PASS = __ENV.LOAD_PASSWORD;
const WS = __ENV.WS || "00000000-0000-0000-0000-000000000001";
const SCENARIO = __ENV.SCENARIO || "reads";
const VUS = parseInt(__ENV.VUS || "10");
const DURATION = __ENV.DURATION || "30s";
const publishLatency = new Trend("publish_to_running_ms", true);
const publishOk = new Rate("publish_reached_running");

const scenarios = {
  reads: { executor: "constant-vus", vus: VUS, duration: DURATION, exec: "reads" },
  prompt: { executor: "constant-vus", vus: VUS, duration: DURATION, exec: "prompt" },
  publish: { executor: "constant-vus", vus: VUS, duration: DURATION, exec: "publish" },
  login: { executor: "constant-arrival-rate", rate: parseInt(__ENV.LOGIN_RATE || "5"), timeUnit: "1s", duration: DURATION, preAllocatedVUs: 20, maxVUs: 50, exec: "login" }
};
export const options = {
  noCookiesReset: true,          // keep the logged-in session across iterations (k6 clears cookies per iteration by default)
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: { "http_req_failed{kind:api}": ["rate<0.01"], "http_req_duration{kind:api}": ["p(95)<500"] },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"]
};

// Requests are tagged kind=setup (login, project creation: once per VU) or kind=api (the steady-state work being measured),
// so the thresholds and the report are not polluted by one-off Argon2 logins.
let kind = "api";
const tag = (name) => ({ kind, ...(name ? { name } : {}) });
function csrf() { return http.get(`${BASE}/auth/csrf`, { tags: tag("csrf") }).json("token"); }
function post(path, body, extra = {}) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrf(), ...extra }, tags: tag() });
}
const state = {};   // per-VU
function session() {
  if (state.ready) return state;
  kind = "setup";
  let r;
  for (let attempt = 0; attempt < 30; attempt++) {                       // a real client retries when told the sign-in is busy
    r = post("/auth/login", { username: USER, password: PASS });
    if (r.status !== 503 && r.status !== 429) break;
    sleep(1);
  }
  if (r.status !== 200) fail(`login failed: ${r.status}`);
  const p = post(`/workspaces/${WS}/projects`, { name: `load-${__VU}-${Date.now()}` });
  if (p.status !== 201) fail(`create project failed: ${p.status} ${p.body}`);
  state.project = p.json("id"); state.rev = p.json("revision"); state.ready = true; state.toggle = false;
  kind = "api";
  return state;
}

export function reads() {
  const s = session();
  const a = http.get(`${BASE}/workspaces/${WS}/projects`, { tags: tag("list_projects") });
  const b = http.get(`${BASE}/workspaces/${WS}/projects/${s.project}`, { tags: tag("get_project") });
  const c = http.get(`${BASE}/workspaces/${WS}/projects/${s.project}/schema`, { tags: tag("get_schema") });
  check(a, { "list 200": (r) => r.status === 200 }); check(b, { "get 200": (r) => r.status === 200 }); check(c, { "schema 200": (r) => r.status === 200 });
}

export function prompt() {
  const s = session();
  s.toggle = !s.toggle;
  const r = post(`/workspaces/${WS}/projects/${s.project}/prompts`, { prompt: s.toggle ? "bỏ phần đánh giá" : "hiện phần đánh giá", expectedRevision: s.rev }, {});
  if (check(r, { "prompt 200": (x) => x.status === 200 })) s.rev = r.json("revision");
}

export function publish() {
  const s = session();
  const t0 = Date.now();
  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const proj = http.get(`${BASE}/workspaces/${WS}/projects/${s.project}`, { tags: tag("get_project") }).json();
  const r = post(`/workspaces/${WS}/projects/${s.project}/publish`, { visibility: "PRIVATE", expectedRevision: proj.revision }, { "Idempotency-Key": key });
  if (!check(r, { "publish 202": (x) => x.status === 202 })) return;
  const id = r.json("id");
  for (let i = 0; i < 60; i++) {
    const d = http.get(`${BASE}/workspaces/${WS}/projects/${s.project}/deployments/${id}`, { tags: tag("poll_deployment") }).json();
    if (d.status === "RUNNING" || d.status === "FAILED") { publishOk.add(d.status === "RUNNING"); publishLatency.add(Date.now() - t0); return; }
    sleep(0.3);
  }
  publishOk.add(false);
}

export function login() {
  const jar = http.cookieJar();
  const r = http.post(`${BASE}/auth/login`, JSON.stringify({ username: USER, password: PASS }), { headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrf() }, tags: { name: "login" } });
  check(r, { "login 200": (x) => x.status === 200 });
}
