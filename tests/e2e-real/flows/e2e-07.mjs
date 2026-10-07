// @class: real-backend — TEST-binding query.
// SEPARATION: [fixture] operator-seeded project/query (E2E_DATA_*) · [api] independent run of the same query · [ui] Test panel run · [backend] row count read back · [cleanup] nothing created here.
// Why operator-seeded: a source and its TEST binding CAN now be created over HTTP (C3 Management API e606465 — see E2E-06), but an approved QUERY cannot (no management endpoint, MANAGEMENT_API.md §5)
// and the AppDefinition cannot declare the slot the query refers to (no typed operation for dataSources[] — C2 H-C2-02).
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
import { openTestPanel, settledOutcome, flagOff } from "../lib/testpanel.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-07", title = "TEST-binding query runs and shows results";
export const blocker = { owner: "C3", ref: "H-C2-02 + H-C0-07", reason: "Sources, TEST bindings and query definitions can be created over HTTP (management-api.md §3.5/§3.6), but (1) the document cannot declare the data slot a binding names (C2 H-C2-02) and (2) a local stack has no source the gateway may reach (public address required, H-C0-07). Set E2E_DATA_WORKSPACE_ID, E2E_DATA_PROJECT_ID, E2E_DATA_QUERY_ID, E2E_DATA_USER, E2E_DATA_PASSWORD for a project the operator seeded (source + slot + TEST binding + query; user needs PROJECT_EDIT + QUERY_EXECUTE)." };
export async function run({ cfg, browser, check }) {
  const s = cfg.preseeded;
  if (![s.workspaceId, s.projectId, s.queryId, s.user, s.password].every(Boolean)) throw new Blocked(blocker.owner, blocker.reason, blocker.ref);
  const user = { username: s.user, password: s.password };
  const page = await newPage(browser);
  await openTestPanel(page, cfg, user, s.projectId);
  const run = page.getByTestId(`run-query:${s.queryId}`);
  check.ok("the pre-seeded query is listed in the Test panel", (await run.count()) === 1);
  if (await run.isDisabled()) throw new Blocked("C1", `the Test panel refuses to run: ${await run.getAttribute("title")}`, "permissions");
  await run.click();
  const out = await settledOutcome(page, `query-row:${s.queryId}`);
  if (out.state === "NOT_READY" && flagOff(out.text)) throw new Blocked("C0", `server answered "not mounted": ${out.text.slice(0, 160)} (app.data-platform.enabled must be true in the test stack)`, "app.data-platform.enabled");
  check.ok("the real backend answered with rows (outcome SUCCESS from the server)", out.state === "SUCCESS", `${out.state}: ${out.text.slice(0, 200)}`);
  check.ok("a result table is rendered", (await page.getByTestId("query-result").locator("table").count()) === 1 || /Truy vấn không trả dòng nào/.test(out.text));
  // cross-check against the server through a separate session: same mode, same query, same row count
  const api = new Session(cfg.studio, "preseeded"); await api.login(s.user, s.password);
  const r = await api.post(`/workspaces/${s.workspaceId}/projects/${s.projectId}/app-runtime/queries/${encodeURIComponent(s.queryId)}/run`, { mode: "TEST" });
  check.ok("an independent API call returns 200 with the same query", r.status === 200 && r.body?.queryId === s.queryId, `status=${r.status}`);
  const uiRows = Number(/(\d+) dòng/.exec(out.text)?.[1] ?? NaN);
  check.ok("UI row count equals the server's", uiRows === (r.body?.result?.rows?.length ?? -1), `ui=${uiRows} api=${r.body?.result?.rows?.length}`);
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
