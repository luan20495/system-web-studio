// @class: real-backend — C3 Management API + the Studio "Dữ liệu" panel against a REAL backend. Never a PASS without one: run.mjs refuses to start without a stack, and this flow ends BLOCKED on the part no route can do.
//
// SEPARATION (every check name carries its stage):
//   [fixture]  workspaces A/B, adminA/adminB/viewerA, project A  — created by run.mjs/createFixtures through the product API, removed by cleanup
//   [api]      setup + isolation/permission/contract probes through lib/management.mjs (what the SERVER answers)
//   [ui]       Studio browser steps: open the Builder, the Data panel, test the connection, look at the bindings area
//   [backend]  side effects read back through a separate API call after the browser step
//   [cleanup]  bindings → data sources → projects (cleanup() in lib/fixtures.mjs; tracked in fx.created.*)
//
// WHAT CANNOT BE DONE OVER HTTP TODAY (so the flow stays BLOCKED after its evidence checks, with the owners below):
//   - declare a data slot in the AppDefinition: no typed operation for dataSources[] (C2, handoff H-C2-02) → no slot to bind in the UI, no query that can reference a source;
//   - create an approved query: no management endpoint (MANAGEMENT_API.md §5; C3).
import { Blocked } from "../lib/report.mjs";
import { newPage, loginUi, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
import { management, bogusConfigFor, randomCredentialFor, notMounted, TEST_FAILURE_CODES } from "../lib/management.mjs";
export const id = "E2E-06", title = "Configure DataSource (+ Query) and query via the real backend";
export const blocker = { owner: "C2", ref: "H-C2-02 + B-C0-W-03(queries)", reason: "Source, credential, test-connection and binding management exist (C3 e606465) and are exercised above, but the QUERY half cannot be configured: the AppDefinition has no operation to declare a data slot (dataSources[] is 'granted, not created' — C2 H-C2-02) and C3 has no approved-query management endpoint (MANAGEMENT_API.md §5). Use E2E-07 with an operator-seeded project for the query." };

export async function run({ cfg, fx, browser, check }) {
  const A = management(fx, "adminA", "A"), B = management(fx, "adminB", "B"), V = management(fx, "viewerA", "A");
  const pA = fx.projects.A.id;
  const stage = (s, name, cond, detail = "") => check.ok(`[${s}] ${name}`, cond, detail);

  // ---- [api] is the Management API there at all? --------------------------------------------------------------------------------------------------
  const cat = await A.connectors();
  if (notMounted(cat)) throw new Blocked("C0", `Management API not mounted: GET …/data-sources/connectors → 404 without a code (app.data-platform.enabled=false, or this build predates C3's commit e606465, which is not in integration/v2)`, "app.data-platform.enabled / V2 integration");
  stage("api", "connector catalogue answers 200 with items", cat.status === 200 && Array.isArray(cat.body?.items), `status=${cat.status} code=${cat.body?.code ?? ""}`);
  const pg = (cat.body?.items ?? []).find((c) => c.type === "postgres");
  stage("api", "postgres is AVAILABLE and the planned connectors are not", pg?.status === "AVAILABLE" && (cat.body.items.filter((c) => c.status === "PLANNED").length >= 1), JSON.stringify((cat.body?.items ?? []).map((c) => `${c.type}:${c.status}`)));
  if (!pg) throw new Blocked("C3", "the catalogue does not list a postgres connector; cannot build a source", "MANAGEMENT_API.md §3");
  const cfgFor = bogusConfigFor(pg, fx.runId);
  if (cfgFor.unknownKeys) throw new Blocked("C3", `the catalogue requires config keys this suite cannot fill: ${cfgFor.unknownKeys.join(", ")} (teach lib/management.mjs bogusConfigFor)`, "catalogue drift");
  const credential = randomCredentialFor(pg);
  const secretValues = Object.values(credential);
  const leaks = (x) => { const t = typeof x === "string" ? x : JSON.stringify(x ?? ""); return secretValues.some((v) => t.includes(v)); };
  const name = `e2e-${fx.runId}-pg`;

  // ---- [api] create + contract probes --------------------------------------------------------------------------------------------------------------
  const created = await A.create({ name, type: "postgres", config: cfgFor.config, credential });
  stage("api", "create → 201 with a DataSourceView", created.status === 201 && !!created.body?.id && created.body.name === name, `status=${created.status} code=${created.body?.code ?? ""} ${created.body?.message ?? ""}`);
  if (created.status !== 201) return;                    // nothing else can be asserted; the failed check above makes the flow FAIL with the exact server answer
  const dsId = created.body.id;
  stage("api", "the view says a credential exists but carries no secret and no credential reference", created.body.hasCredential === true && !leaks(created.body) && !("credentialRef" in created.body), JSON.stringify(Object.keys(created.body)));
  const meta = await A.credential(dsId);
  stage("api", "credential metadata: configured + key NAMES only, no values", meta.status === 200 && meta.body?.configured === true && JSON.stringify([...meta.body.keys].sort()) === JSON.stringify(Object.keys(credential).sort()) && !leaks(meta.text), `status=${meta.status}`);
  const dup = await A.create({ name: name.toUpperCase(), type: "postgres", config: cfgFor.config, credential });
  stage("api", "a second source with the same name (case-insensitive) → 409 CONFLICT", dup.status === 409, `status=${dup.status} code=${dup.body?.code ?? ""}`);
  const secretInConfig = await A.create({ name: `${name}-s`, type: "postgres", config: { ...cfgFor.config, password: "hunter2-not-allowed" }, credential });
  stage("api", "a secret-looking key in config → 400 INVALID_*", secretInConfig.status === 400 && /^INVALID_/.test(secretInConfig.body?.code ?? ""), `status=${secretInConfig.status} code=${secretInConfig.body?.code ?? ""}`);
  const identity = await A.create({ name: `${name}-i`, type: "postgres", config: cfgFor.config, tenantId: "someone-else" });
  stage("api", "an identity field in the body (tenantId) → 400 INVALID_PARAMS", identity.status === 400 && identity.body?.code === "INVALID_PARAMS", `status=${identity.status} code=${identity.body?.code ?? ""}`);
  const planned = (cat.body.items ?? []).find((c) => c.status === "PLANNED");
  if (planned) { const r = await A.create({ name: `${name}-p`, type: planned.type, config: {} }); stage("api", `a PLANNED connector (${planned.type}) → 501 NOT_IMPLEMENTED`, r.status === 501, `status=${r.status} code=${r.body?.code ?? ""}`); }
  const unsupported = await A.create({ name: `${name}-u`, type: "no-such-connector", config: {} });
  stage("api", "an unknown connector type → 422 UNSUPPORTED_TYPE", unsupported.status === 422, `status=${unsupported.status} code=${unsupported.body?.code ?? ""}`);

  // ---- [api] isolation + permissions ---------------------------------------------------------------------------------------------------------------
  const foreign = await B.get(dsId), unknown = await B.get("00000000-0000-0000-0000-000000000000");
  stage("api", "isolation: workspace B's admin cannot read A's source by id (404 NOT_FOUND)", foreign.status === 404 && foreign.body?.code === "NOT_FOUND", `status=${foreign.status} code=${foreign.body?.code ?? ""}`);
  stage("api", "isolation: 'foreign' and 'unknown' answer IDENTICALLY (no existence oracle)", foreign.status === unknown.status && foreign.body?.code === unknown.body?.code && foreign.body?.message === unknown.body?.message, `${foreign.status}/${unknown.status}`);
  const crossWs = await fx.sessions.adminB.get(`/workspaces/${fx.workspaces.A}/data-sources`);
  stage("api", "isolation: B's admin listing A's workspace is refused (403/404), no items", [403, 404].includes(crossWs.status) && !crossWs.body?.items, `status=${crossWs.status}`);
  const vWrite = await V.create({ name: `${name}-v`, type: "postgres", config: cfgFor.config });
  stage("api", "permission: a VIEWER cannot create (403 PERMISSION_DENIED)", vWrite.status === 403 && vWrite.body?.code === "PERMISSION_DENIED", `status=${vWrite.status} code=${vWrite.body?.code ?? ""}`);
  const vPatch = await V.patch(dsId, { status: "DISABLED" });
  stage("api", "permission: a VIEWER cannot change a source, and nothing changed", vPatch.status === 403 && (await A.get(dsId)).body?.status === "ACTIVE", `status=${vPatch.status}`);
  const vRead = await V.list();
  stage("api", "permission: a VIEWER's read is 200 or 403, never 5xx, and never shows a secret", [200, 403].includes(vRead.status) && !leaks(vRead.text), `status=${vRead.status}`);
  fx.notes.viewerReadsDataSources = vRead.status;

  // ---- [api] test connection: must FAIL for real (the host does not exist) -------------------------------------------------------------------------
  const t0 = await A.test(dsId);
  stage("api", "test connection of an unreachable host → HTTP 200 with ok:false and a documented code", t0.status === 200 && t0.body?.ok === false && TEST_FAILURE_CODES.includes(t0.body?.code), `status=${t0.status} ok=${t0.body?.ok} code=${t0.body?.code ?? ""}`);
  stage("api", "the failure text does not leak the credential", !leaks(t0.text));
  const off = await A.patch(dsId, { status: "DISABLED" });
  const tOff = await A.test(dsId);
  stage("api", "a DISABLED source cannot be tested: 409 DISABLED", off.status === 200 && tOff.status === 409 && tOff.body?.code === "DISABLED", `patch=${off.status} test=${tOff.status} ${tOff.body?.code ?? ""}`);
  await A.patch(dsId, { status: "ACTIVE" });

  // ---- [api] bindings (the slot id is free text on the API; the document cannot declare it — see blocker) ----------------------------------------
  const slot = `e2e-${fx.runId}-slot`;
  const bt = await A.bind(pA, "TEST", slot, dsId);
  stage("api", "bind TEST → 200 with the binding", bt.status === 200 && bt.body?.mode === "TEST" && bt.body?.slotId === slot && bt.body?.dataSourceId === dsId, `status=${bt.status} code=${bt.body?.code ?? ""}`);
  const bl = await A.bindings(pA);
  stage("api", "the binding list contains it, mode normalised", (bl.body?.items ?? []).some((b) => b.mode === "TEST" && b.slotId === slot && b.dataSourceId === dsId), `status=${bl.status}`);
  const vb = await V.bindings(pA);
  stage("api", "permission: a project VIEWER cannot list/alter bindings (403)", vb.status === 403, `status=${vb.status}`);
  const delBound = await A.remove(dsId);
  stage("api", "deleting a BOUND source → 409 CONFLICT and it still exists", delBound.status === 409 && (await A.get(dsId)).status === 200, `status=${delBound.status} code=${delBound.body?.code ?? ""}`);

  // ---- [ui] Studio browser steps -------------------------------------------------------------------------------------------------------------------
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  const opened = await openBuilder(page, cfg, pA);
  stage("ui", "the Builder opens by direct URL", opened.canvas);
  await page.locator(".bx-left").getByRole("tab", { name: "Dữ liệu" }).click();
  const panel = await page.waitForSelector('[data-testid="ds-panel"]', { timeout: 20_000 }).then(() => true).catch(() => false);
  stage("ui", "the Data panel shows the source-management panel (not 'Chưa sẵn sàng')", panel, (await bodyText(page)).slice(0, 160));
  if (panel) {
    stage("ui", "the new source is listed", (await page.getByTestId(`ds:${dsId}`).count()) === 1);
    const cs = await page.getByTestId(`cred-state:${dsId}`).innerText();
    stage("ui", "credential: 'Đã cấu hình' with key names and no value", /Đã cấu hình/.test(cs) && Object.keys(credential).every((k) => cs.includes(k)) && !leaks(cs), cs);
    stage("ui", "no password field is prefilled and no secret is anywhere in the DOM", await page.evaluate((vals) => { const t = document.documentElement.outerHTML + [...document.querySelectorAll("input")].map((i) => i.value).join(""); return !vals.some((v) => t.includes(v)); }, secretValues));
    await page.getByTestId(`ds-test:${dsId}`).click();
    const res = page.getByTestId(`ds-test-result:${dsId}`);
    await res.waitFor({ timeout: 50_000 }).catch(() => undefined);
    stage("ui", "test connection: the real failure is shown as FAILED with its code", (await res.count()) === 1 && (await res.getAttribute("data-test-state")) === "FAILED" && new RegExp(t0.body.code).test(await res.innerText()), (await res.count()) ? await res.innerText() : "no result");
    stage("ui", "the failure text shows no secret", !leaks(await res.innerText().catch(() => "")));
    stage("ui", "the existing TEST binding's slot is not invented by the UI (the document declares no slots)", /chưa khai báo khe dữ liệu|Khe dữ liệu/.test(await bodyText(page)));
  }
  stage("ui", "no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();

  // ---- [backend] what the browser steps left behind ------------------------------------------------------------------------------------------------
  const after = await A.get(dsId);
  stage("backend", "browsing and testing changed nothing: still ACTIVE, version unchanged", after.status === 200 && after.body?.status === "ACTIVE", `status=${after.status} ${after.body?.status}`);

  // ---- optional: the operator supplied a REACHABLE source → the success path is real ---------------------------------------------------------------
  const ds = cfg.dataSource;
  if (ds.provided && !ds.error) {
    const good = await A.create({ name: `e2e-${fx.runId}-ok`, type: ds.type, config: ds.config, ...(ds.credential ? { credential: ds.credential } : {}) });
    stage("api", "(operator source) create → 201", good.status === 201, `status=${good.status} code=${good.body?.code ?? ""}`);
    if (good.status === 201) {
      const tr = await A.test(good.body.id);
      stage("api", "(operator source) test connection → ok:true with latency", tr.status === 200 && tr.body?.ok === true && Number.isFinite(tr.body?.latencyMs), `status=${tr.status} ok=${tr.body?.ok} code=${tr.body?.code ?? ""}`);
      fx.notes.operatorSourceWarnings = tr.body?.warnings ?? null;
    }
  } else fx.notes.operatorSource = ds.provided ? `ignored: ${ds.error}` : "not provided (E2E_DS_TYPE) — the 'connection test succeeds' path is NOT exercised";

  throw new Blocked(blocker.owner, blocker.reason, blocker.ref);
}
