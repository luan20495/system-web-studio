// @class: real-backend — shared chain of the PAGE_SCHEMA public-data flows (E2E-PD01 with a real data source, E2E-PD02 without one). Every call goes through the real Studio and the product API; fault injection and stubs do not exist here.
import { Blocked, Mismatch } from "./report.mjs";
import { releaseApi, showRelease, closeRelease, until, TERMINAL, KEY_RE } from "./release.mjs";
import { management } from "./management.mjs";
import { newPage, loginUi, openBuilder, pageProblems } from "./ui.mjs";
export const SLOT = "pd-orders", Q_PRIVATE = "pd-private", Q_WRITE = "pd-write";

/**
 * withData = true  → E2E-PD01: a REAL operator-provided data source is created (Management API), the slot is LIVE-bound to it and the visitor must receive its real data.
 * withData = false → E2E-PD02: the DOCUMENT side only (slot, query, public, binding, publish approval, PUBLIC_QUERIES, runtime config, the page's one anonymous request). No data is claimed.
 */
export async function chain({ cfg, fx, browser, check }, { withData }) {
  const pd = cfg.publicData, ds = cfg.dataSource;
  const api = releaseApi(fx), mg = management(fx, "adminA"), S = api.S, base = api.base, w = api.w, pid = api.p;
  const patch = async (operations, summary) => { const sc = (await S.get(`${base}/schema`)).body; return S.patch(`${base}/schema`, { expectedRevision: sc.revision, summary, operations }); };
  const schema = async () => (await S.get(`${base}/schema`)).body;

  // ---- prerequisites that are not C5's ----------------------------------------------------------------------------------------------------------------------
  const probe = await patch([{ type: "ADD_DATA_SOURCE", definition: { id: "pd-probe", type: "postgres" } }], "e2e-pd probe");
  if (probe.status >= 400 && probe.status < 500)
    throw new Blocked("C0", `the stack does not accept ADD_DATA_SOURCE (${probe.status} ${probe.body?.code} ${String(probe.body?.message ?? "").slice(0, 80)}): C2's PAGE_SCHEMA data runtime (fix/c2-v3 c1e0df5: slots, QueryDef.public, page runtime, config apiBase) is not in this build`, "H-C0-10");
  if (probe.status === 200) await patch([{ type: "REMOVE_DATA_SOURCE", definitionId: "pd-probe" }], "e2e-pd probe cleanup");
  else throw new Mismatch("C2", { expected: "ADD_DATA_SOURCE {id,type} → 200", actual: `${probe.status} ${probe.body?.code} ${probe.body?.message}`, impact: "no slot can be authored" }, "DATA_SOURCE_SLOT");
  const cfgAuth = await (await fetch(`${cfg.studio}/api/v1/auth/config`)).json().catch(() => ({}));
  if (cfgAuth.publicPublish === false) throw new Blocked("C0", "public publishing is disabled on this stack (auth config publicPublish=false); the visitor flow needs a PUBLIC release", "app.publish.public-enabled");

  // ---- [fixture] the real source (PD01 only) ------------------------------------------------------------------------------------------------------------------
  let created = null, operationKey = "e2e.pd.read";
  if (withData) {
    if (!ds.provided || ds.error || !(pd.operationKey || pd.sql) || !pd.expectText)
      throw new Blocked("C0", `no real data to read: set E2E_DS_TYPE + E2E_DS_CONFIG_JSON (+ E2E_DS_CREDENTIAL_JSON) for a real source, E2E_PD_OPERATION_KEY = an approved READ operation of it (or E2E_PD_SQL = a read-only SELECT to register as one) and E2E_PD_EXPECT_TEXT = the text its first row returns (it is shown in the Navbar brand). ${ds.error ?? ""}`.trim(), "operator input (README: E2E-PD01)");
    created = await mg.create({ name: `e2e-pd01-${fx.runId}`, type: ds.type, config: ds.config, ...(ds.credential ? { credential: ds.credential } : {}) });
    if (created.status === 404 && !created.body?.code) throw new Blocked("C3", "the Management API is not mounted (app.data-platform.enabled is off)", "B-C5-03");
    if (created.status !== 201) throw new Blocked("C3", `the operator's data source was refused by the Management API (${created.status} ${created.body?.code ?? ""} ${String(created.body?.message ?? "").slice(0, 160)}): C3 requires a public DNS host with verify-full TLS; fix E2E_DS_TYPE / E2E_DS_CONFIG_JSON / E2E_DS_CREDENTIAL_JSON`, "operator input / C3 address policy");
    check.ok("[fixture] the operator's data source was created through the Management API", true, `status=${created.status}`, "http");
    const tested = await mg.test(created.body.id);
    if (!(tested.status === 200 && tested.body?.ok === true)) throw new Blocked("C3", `the operator's data source does not connect (${tested.status} ${tested.body?.code ?? ""} ${tested.body?.message ?? ""})`, "operator input");
    operationKey = pd.operationKey;
    if (!operationKey) {
      // the operator gave a read-only SELECT: it becomes an APPROVED operation the way C3 defines one (a query definition of the source, written by an administrator) — never typed into the page document
      operationKey = `e2e-pd-${fx.runId}`.slice(0, 60);
      const def = await S.post(`/workspaces/${w}/data-sources/${created.body.id}/queries`, { queryId: operationKey, kind: "SQL", definition: { sql: pd.sql } });
      if (def.status === 404 && !def.body?.code) throw new Blocked("C3", "query definition routes are not mounted on this build (404 without a code)", "management-api.md §3.5");
      check.ok("[fixture] the SELECT was registered as an approved query definition (Management API)", def.status === 201, `status=${def.status} ${def.body?.code ?? ""}`, "http");
    }
  }

  // ---- [ui] Studio: slot, query, public, binding -----------------------------------------------------------------------------------------------------------------
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, pid);
  await page.locator(".bx-left").getByRole("tab", { name: "Dữ liệu" }).click();
  await page.getByTestId("slot-editor").waitFor({ timeout: 20_000 });
  await page.getByTestId("slot-id").fill(SLOT); await page.getByTestId("slot-name").fill("Đơn hàng"); await page.getByTestId("slot-type").fill(withData ? ds.type.toLowerCase() : "postgres");
  await page.getByTestId("slot-add").click(); await page.getByTestId(`slot-${SLOT}`).waitFor({ timeout: 15_000 });
  let sc = await schema(); const slot = sc.schema.dataSources?.find((d) => d.id === SLOT);
  check.ok("[ui] the slot was added and the SERVER stored exactly {id,name,type} — no sourceRef, no credential", !!slot && !("sourceRef" in slot && slot.sourceRef) && JSON.stringify(Object.keys(slot).sort()) === JSON.stringify(["id", "name", "type"]), JSON.stringify(slot), "persistence");
  if (withData) {
    const bind = await mg.bind(pid, "LIVE", SLOT, created.body.id);
    check.ok("[fixture] the logical slot is LIVE-bound to the real source (Management API)", bind.status === 200, `status=${bind.status} ${bind.body?.code ?? ""}`, "http");
  }

  await page.getByRole("tab", { name: /^Truy vấn/ }).click();
  await page.getByLabel("Tên truy vấn").fill("Tiêu đề công khai"); await page.getByLabel("Mã thao tác đã duyệt").fill(operationKey);
  await page.getByTestId("query-public").check(); await page.getByRole("button", { name: "Lưu truy vấn" }).click();
  await until(async () => (await schema()).schema.queries?.length ?? 0, (n) => n >= 1, 15_000);
  sc = await schema(); const qs = sc.schema.queries ?? []; const qid = qs.find((q) => q.operationKey === operationKey)?.id;
  check.ok("[ui] a READ query with public=true was stored by the server (mode READ, operationKey = the approved one, local slot ref)", !!qid && qs.find((q) => q.id === qid).public === true && (qs.find((q) => q.id === qid).mode ?? "READ") === "READ" && qs.find((q) => q.id === qid).dataSourceRef === SLOT, JSON.stringify(qs), "persistence");
  // the stable ids the flow talks about: rename is not an operation, so the generated id is used; two more definitions are made through the API as the NEGATIVE fixtures
  const neg = await patch([{ type: "ADD_QUERY", definition: { id: Q_PRIVATE, name: "Riêng tư", dataSourceRef: SLOT, mode: "READ", operationKey } }], "e2e-pd01 private query");
  check.ok("[api] a READ query WITHOUT public is accepted (it is private by default)", neg.status === 200, `status=${neg.status} ${neg.body?.code ?? ""}`, "http");
  {
    const wr = await patch([{ type: "ADD_QUERY", definition: { id: Q_WRITE, name: "Ghi", dataSourceRef: SLOT, mode: "WRITE", operationKey: pd.writeOperationKey || operationKey } }], "e2e-pd01 write query");
    check.ok("[api] a WRITE query with public=false is accepted", wr.status === 200, `status=${wr.status}`, "http");
  }
  const bad = await patch([{ type: "ADD_QUERY", definition: { id: "pd-bad", dataSourceRef: SLOT, mode: "WRITE", operationKey: operationKey, public: true } }], "e2e-pd01 write+public");
  check.ok("[api] a WRITE query with public=true is REJECTED by the server (422, path queries[i].public) — the UI never offers it", bad.status === 422 && /queries\[\d+\]\.public/.test(JSON.stringify(bad.body)), `status=${bad.status} ${JSON.stringify(bad.body).slice(0, 160)}`, "http");

  await page.reload(); await page.locator(".bx-left").getByRole("tab", { name: "Dữ liệu" }).click();
  await page.getByRole("tab", { name: /Dữ liệu công khai/ }).click();
  const first = (await schema()).schema.sections[0];
  await page.getByTestId("binding-section").selectOption(first.id); await page.getByTestId("binding-prop").selectOption("brand").catch(() => undefined);
  const propChosen = await page.getByTestId("binding-prop").inputValue();
  if (propChosen !== "brand") throw new Blocked("C5", `the fixture project's first section is ${first.type}, which has no bindable 'brand' prop: this flow binds Navbar.brand (adjust fixtures.mjs)`, "fixture");
  await page.getByTestId("binding-query").selectOption(qid); await page.getByTestId("binding-add").click(); await page.getByTestId("binding-list").waitFor({ timeout: 15_000 });
  sc = await schema(); const b = (sc.schema.dataBindings ?? [])[0];
  check.ok("[ui] the binding was stored: {sectionId, prop, queryRef} with the LOCAL public READ query id — never a slot, sourceRef or credential", !!b && b.queryRef === qid && b.prop === "brand" && !("dataSourceRef" in b) && !JSON.stringify(b).match(/sourceRef|credential/), JSON.stringify(b), "persistence");
  // the site has never been published PUBLIC: the draft is complete, but the visitor cannot get data yet (a PRIVATE site has no apiBase) — the Studio says exactly that and nothing else
  const reasons = await page.getByTestId("public-readiness-reasons").innerText().catch(() => "");
  check.ok("[ui] readiness: not-ready for ONE reason only — the site is not public yet, so it has no data address (apiBase); the draft itself has no problem", (await page.getByTestId("public-readiness-state").getAttribute("data-state")) === "not-ready" && /apiBase/.test(reasons) && reasons.trim().split("\n").filter(Boolean).length === 1, reasons.replace(/\s+/g, " ").slice(0, 160));

  // ---- [ui] publish review ----------------------------------------------------------------------------------------------------------------------------------
  const modal = await showRelease(page);
  await page.getByRole("button", { name: /^Công khai/ }).click();
  const box = page.getByTestId("public-queries-box"); await box.waitFor({ timeout: 10_000 });
  const listed = await page.getByTestId("public-queries-list").locator("li").evaluateAll((l) => l.map((x) => x.getAttribute("data-testid")));
  check.ok("[ui] the publish review lists exactly the public READ query (not the private one, not the WRITE one)", JSON.stringify(listed) === JSON.stringify([`public-query:${qid}`]), listed.join(","));
  check.ok("[ui] Publish is disabled until the acknowledgement is ticked", await page.getByTestId("publish").isDisabled());
  await page.getByTestId("publish-ack").check();
  const rev = await api.revision();
  const start = page.waitForResponse((r) => r.request().method() === "POST" && /\/publish$/.test(new URL(r.url()).pathname), { timeout: 20_000 });
  await page.getByTestId("publish").click(); const r = await start; const reqBody = JSON.parse(r.request().postData() ?? "{}");
  check.ok("[ui] POST …/publish is the unchanged release contract: body EXACTLY {visibility:'PUBLIC', expectedRevision}, Idempotency-Key present — no acknowledgement field", r.status() === 202 && JSON.stringify(Object.keys(reqBody).sort()) === JSON.stringify(["expectedRevision", "visibility"]) && reqBody.visibility === "PUBLIC" && reqBody.expectedRevision === rev && KEY_RE.test(r.request().headers()["idempotency-key"] ?? ""), `status=${r.status()} ${JSON.stringify(reqBody)}`, "http");
  const dep0 = await r.json(); const dep = await api.settle(dep0.id, 120_000);
  if (dep?.status !== "RUNNING") {
    if (/BUILD_FAILED.*binding|cannot be bound|not public|does not exist/i.test(dep?.error ?? "")) throw new Mismatch("C2", { expected: "a valid public READ binding publishes", actual: `${dep?.status} ${dep?.error}`, impact: "the C5 mirror of resolveBindings accepted what the render worker refused" }, "COMPONENT_BINDING");
    throw new Blocked("C2", `the release did not reach RUNNING (${dep?.status} ${dep?.error})`, "publish");
  }
  const ev = (dep.events ?? []).find((e) => e.status === "PUBLIC_QUERIES");
  check.ok("[backend] the server wrote the PUBLIC_QUERIES event naming exactly the public query", !!ev && ev.message.includes(qid) && !ev.message.includes(Q_PRIVATE) && !ev.message.includes(Q_WRITE), ev?.message ?? "no PUBLIC_QUERIES event", "persistence");
  await page.getByTestId("public-queries-event").waitFor({ timeout: 10_000 }).catch(() => undefined);
  check.ok("[ui] the dialog shows the server's PUBLIC_QUERIES and no mismatch with what it announced", (await page.getByTestId("public-queries-event").count()) === 1 && (await page.getByTestId("public-queries-mismatch").count()) === 0, "");
  await closeRelease(page).catch(() => undefined);
  const site = await api.site();
  check.ok("[backend] the release is live and PUBLIC, operation idle", site.online === true && site.visibility === "PUBLIC" && site.operation === null && site.currentDeploymentId === dep.id, JSON.stringify({ v: site.visibility, op: site.operation }), "persistence");
  check.ok("no unhandled page errors in Studio", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();

  // ---- [visitor] an anonymous browser context -----------------------------------------------------------------------------------------------------------------
  const ctx = await browser.newContext(); const visitor = await ctx.newPage(); const seen = [];
  visitor.on("request", (q) => seen.push({ method: q.method(), url: q.url(), body: q.postData(), headers: q.headers() }));
  const pageUrl = site.url ?? dep.url;
  await visitor.goto(pageUrl, { waitUntil: "domcontentloaded" });
  const root = visitor.locator("[data-xw-runtime]");
  await root.waitFor({ timeout: 15_000 });
  const final = await until(async () => root.getAttribute("data-xw-state"), (s) => s && !/^loading/.test(s), 30_000, 250);
  const detail = await root.getAttribute("data-xw-detail");
  const cfgReq = seen.find((x) => /__factory\/config\.json$/.test(new URL(x.url).pathname));
  check.ok("[visitor] the page asked for its runtime config (GET __factory/config.json) before any data request, with no cookie and no credentials", !!cfgReq && cfgReq.method === "GET" && !cfgReq.headers.cookie && !cfgReq.headers.authorization && (await ctx.cookies()).length === 0, JSON.stringify(cfgReq?.headers ?? {}).slice(0, 120));
  const cfgJson = await visitor.evaluate(async () => { const u = new URL("__factory/config.json", location.href); const r = await fetch(u, { cache: "no-store" }); return { status: r.status, body: await r.json().catch(() => null) }; });
  const apiBase = cfgJson.body?.apiBase;
  check.ok("[visitor] config.json is no-store JSON with a same-origin apiBase for THIS slug (…/{slug}/_data) and no tenant / workspace / credential", cfgJson.status === 200 && typeof apiBase === "string" && new URL(apiBase).origin === new URL(pageUrl).origin && /\/_data\/?$/.test(apiBase) && !/tenant|workspace|credential|secret|token/i.test(JSON.stringify(cfgJson.body)), JSON.stringify(cfgJson.body).slice(0, 200), "http");
  if (final === "not-ready") {
    if (detail === "api-base-cross-origin") throw new Blocked("C0", `apiBase is not on the site's origin (${apiBase}): app.sites.data-api-base must be the sites origin + /{slug}/_data (the runtime refuses cross-origin)`, "app.sites.data-api-base");
    if (detail === "api-base-missing") throw new Blocked("C0", "config.json has apiBase = null: app.sites.data-api-base is not set on this stack", "app.sites.data-api-base");
    throw new Mismatch("C2", { expected: "ready", actual: `not-ready:${detail}`, impact: "the visitor never gets data" }, "RUNTIME STATES");
  }
  if (final === "error" && withData) {
    if (/not-found|unavailable/.test(detail ?? "")) throw new Blocked("C0", `the public data route did not serve the query (${detail}): the Public Runtime controller (D-C0-36, behind its flag) / PUBLIC_SITE (C1) / LIVE binding (C3) is not wired on this stack`, "D-C0-36");
    throw new Mismatch("C2", { expected: "ready", actual: `error:${detail}`, impact: "the visitor sees the authored fallback" }, "RUNTIME STATES");
  }
  if (!withData) {
    // no real source behind this run: what C2 documents is the page asking exactly once and ending in error:<id>:not-found|unavailable (route/flag/binding absent), the authored text staying; READY is accepted too (a stack that can serve it)
    const asked = seen.filter((x) => new URL(x.url).pathname.includes("/_data/"));
    check.ok("[visitor] the page asked ONLY the public query once (POST …/_data/queries/{id}/run, body {\"params\":{}}), anonymously", asked.length === 1 && asked[0].method === "POST" && asked[0].url === `${apiBase.replace(/\/+$/, "")}/queries/${qid}/run` && asked[0].body === '{"params":{}}' && !asked[0].headers.cookie && !asked[0].headers.authorization, JSON.stringify(asked.map((c) => [c.method, c.url, c.body])), "http");
    check.ok("[visitor] the runtime ended in a DEFINED state (ready, or error:<id>:not-found|unavailable when no data route/binding exists), never stuck loading", final === "ready" || (final === "error" && new RegExp(`^${qid}:(not-found|unavailable|forbidden)`).test(detail ?? "")), `state=${final} detail=${detail}`);
    if (final === "error") check.ok("[visitor] on error the AUTHORED page stays visible (nothing blanked, no fake data)", /Navbar|brand|e2e/i.test(await visitor.locator("main").innerText()) || (await visitor.locator("[data-xw-bind]").first().innerText()).trim().length > 0 && (await visitor.locator("[data-xw-bind]").first().getAttribute("data-xw-state")) === "error");
    check.ok("[visitor] recorded outcome without a real source (fact, not a claim about data)", true, `state=${final} detail=${detail}`);
    await ctx.close();
    return;
  }
  check.ok("[visitor] the page reached runtime state READY", final === "ready", `state=${final} detail=${detail}`);
  const calls = seen.filter((x) => new URL(x.url).pathname.includes("/_data/"));
  check.ok("[visitor] the data request is EXACTLY POST {apiBase}/queries/{publicQueryId}/run, body {\"params\":{}}, once, no cookie / authorization", calls.length === 1 && calls[0].method === "POST" && calls[0].url === `${apiBase.replace(/\/+$/, "")}/queries/${qid}/run` && calls[0].body === '{"params":{}}' && !calls[0].headers.cookie && !calls[0].headers.authorization, JSON.stringify(calls.map((c) => [c.method, c.url, c.body])), "http");
  const shown = (await visitor.locator('[data-xw-bind]').first().innerText().catch(() => "")).trim();
  check.ok("[visitor] the rendered text is the REAL data returned through PUBLIC_SITE → LIVE → DataGateway (not the authored fallback)", shown === pd.expectText.trim() && (await visitor.locator("[data-xw-bind]").first().getAttribute("data-xw-state")) === "ready", `shown='${shown}' expected='${pd.expectText}'`);
  check.ok("[visitor] only public READ queries ran: no request to any other query, action, mutation or workflow", calls.every((c) => new URL(c.url).pathname.endsWith(`/queries/${qid}/run`)) && !seen.some((x) => /\/(actions|workflows|mutations)\b/.test(new URL(x.url).pathname)));
  await ctx.close();

  if (withData) {
  // ---- [api] forged requests, from node: no cookie, no session ---------------------------------------------------------------------------------------------------
  const run = (id, init = {}) => fetch(`${apiBase.replace(/\/+$/, "")}/queries/${encodeURIComponent(id)}/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"params":{}}', ...init, signal: AbortSignal.timeout(15_000) });
  const priv = await run(Q_PRIVATE), ghost = await run("pd-ghost"), pPriv = await priv.text(), pGhost = await ghost.text();
  check.ok("[api] a forged call of a NON-public query is refused (404) and is indistinguishable from an unknown id (no existence oracle)", priv.status === 404 && ghost.status === 404 && pPriv === pGhost && !/rows/.test(pPriv), `${priv.status}/${ghost.status} ${pPriv.slice(0, 80)}`, "http");
  if (pd.writeOperationKey) { const wr = await run(Q_WRITE); check.ok("[api] a forged call of the WRITE query is refused (404)", wr.status === 404, `status=${wr.status}`, "http"); }
  const g = await fetch(`${apiBase.replace(/\/+$/, "")}/queries/${qid}/run`, { method: "GET", signal: AbortSignal.timeout(15_000) });
  check.ok("[api] the public query route accepts POST only", [404, 405].includes(g.status), `GET → ${g.status}`, "http");
  const extra = await Promise.all([["actions/a1/execute"], ["workflows/w1/start"], ["mutations/m1/run"], ["queries/%2e%2e/run"]].map(async ([p]) => (await fetch(`${apiBase.replace(/\/+$/, "")}/${p}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}", signal: AbortSignal.timeout(15_000) })).status));
  check.ok("[api] there is NO public action / workflow / mutation route and no path traversal (every one is 404/405, never 2xx)", extra.every((s) => [400, 404, 405].includes(s)), extra.join(","), "http");
  const forged = await run(qid, { headers: { "Content-Type": "application/json", "X-Tenant-Id": "00000000-0000-0000-0000-000000000000", Authorization: "Bearer forged" } });
  check.ok("[api] forged identity headers change nothing: the public query still answers as PUBLIC_SITE (200) or is refused — never as another tenant's data", [200, 401, 403].includes(forged.status), `status=${forged.status}`, "http");


  }
  // ---- release regression on this stack -------------------------------------------------------------------------------------------------------------------------
  check.ok("[backend] the release deployment reached a TERMINAL status and the site pointer agrees (release contract unchanged)", TERMINAL.includes(dep.status) && (await api.site()).currentDeploymentId === dep.id, "", "persistence");
}
