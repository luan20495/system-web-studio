// @class: mock — checks ONLY the plumbing of the real-backend suite (its guard and its exit codes) against throw-away local HTTP servers.
// It proves that the suite can never report success without a real backend. It says NOTHING about the product.
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { summarise } from "./lib/report.mjs";

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
const bad = results.filter((x) => !x).length; console.log(`\n${results.length - bad}/${results.length} passed`); process.exit(bad ? 1 : 0);
