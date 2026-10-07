// @class: real-backend — authorization invariant: UI permission state is NOT backend enforcement.
//   same scope + missing permission → 403        (the project VIEWER forges every write the UI would have disabled)
//   foreign / out-of-scope resource   → 404        (a person of ANOTHER workspace; existence is not disclosed)
// Every forged request is VALID (a real operation / body), so it can only be refused by authorization, never by validation (an empty `operations` list is a 400 before any permission check).
// The UI half: where the person can reach Studio, the controls are disabled AND the forged call is refused; where they cannot (CONTRACT MISMATCH, see E2E-04) the flow ends FAIL for C1 after the API evidence.
import { Mismatch } from "../lib/report.mjs";
import { resolvedFor, expectations } from "../lib/permissions.mjs";
import { management, bogusConfigFor } from "../lib/management.mjs";
import { deniedWriteProbe } from "../lib/fixtures.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-05", title = "Forbidden path: same scope + missing permission → 403, foreign scope → 404, UI state ≠ enforcement";
const j = (x) => JSON.stringify(x);
const code = (r) => r.body?.error?.code ?? r.body?.code ?? "";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, base = `/workspaces/${w}/projects/${p}`, rt = `${base}/app-runtime`;
  const A = fx.sessions.adminA, V = fx.sessions.viewerA, B = fx.sessions.adminB;
  const cur = (await A.get(base)).body;
  const rev0 = cur.revision, deps0 = j(((await A.get(`${base}/deployments`)).body ?? []).map((d) => d.id));
  const cat = await management(fx, "adminA", "A").connectors();
  const pg = (cat.body?.items ?? []).find((c) => c.type === "postgres");
  const dsBody = (name) => ({ name, type: "postgres", config: pg ? bogusConfigFor(pg, fx.runId).config : { host: "e2e.invalid", database: "x" } });

  // the forged requests: VALID operations a UI would have disabled (the viewer holds none of the permissions they need)
  const forged = [
    ["PATCH schema (valid ADD_ACTION operation)", (S, ws = w) => S.patch(`/workspaces/${ws}/projects/${p}/schema`, { expectedRevision: rev0, ...deniedWriteProbe("forged write") })],
    ["POST publish (APP_PUBLISH)", (S, ws = w) => S.post(`/workspaces/${ws}/projects/${p}/publish`, { visibility: "PRIVATE", expectedRevision: rev0 }, { headers: { "Idempotency-Key": `e2e:${fx.runId}:forged-publish` } })],
    ["POST TEST action execute (APP_USE + ACTION_EXECUTE + APP_EDIT)", (S, ws = w) => S.post(`/workspaces/${ws}/projects/${p}/app-runtime/actions/${fx.ids.actionId}/execute`, { mode: "TEST", idempotencyKey: `e2e:${fx.runId}:forged-action` })],
    ["POST TEST workflow start (APP_USE + WORKFLOW_EXECUTE + APP_EDIT)", (S, ws = w) => S.post(`/workspaces/${ws}/projects/${p}/app-runtime/workflows/${fx.ids.workflowId}/runs`, { mode: "TEST", idempotencyKey: `e2e:${fx.runId}:forged-wf` })],
    ["POST data source create (DATA_SOURCE_MANAGE)", (S, ws = w) => S.post(`/workspaces/${ws}/data-sources`, dsBody(`e2e-${fx.runId}-forged`))],
  ];

  // ---- same scope, missing permission → 403 ------------------------------------------------------------------------------------------------------------
  const r = await resolvedFor(V, w, p);
  fx.notes.viewerResolved = { me: r.me, project: r.project };
  check.ok("viewerA resolved set (evidence): no APP_EDIT / APP_PUBLISH / DATA_SOURCE_MANAGE / WORKFLOW_EXECUTE / ACTION_EXECUTE", !["APP_EDIT", "APP_PUBLISH", "DATA_SOURCE_MANAGE", "WORKFLOW_EXECUTE", "ACTION_EXECUTE"].some((c) => r.resolvedProject.has(c)), j(r.project.permissions), "http");
  const viewerStatus = [];
  for (const [label, call] of forged) {
    const x = await call(V); viewerStatus.push(x.status);
    check.ok(`same scope, missing permission: ${label} → 403`, x.status === 403, `status=${x.status} code=${code(x)}`, "http");
  }
  // ---- foreign scope → 404 ------------------------------------------------------------------------------------------------------------------------------
  for (const [label, call] of forged) {
    const x = await call(B);
    check.ok(`foreign scope (another workspace's admin): ${label} → 404`, x.status === 404, `status=${x.status} code=${code(x)}`, "http");
  }
  const unknown = await B.get(`/workspaces/${w}/projects/00000000-0000-0000-0000-000000000000`), foreign = await B.get(base);
  check.ok("foreign and unknown projects answer identically (no existence oracle)", unknown.status === foreign.status && code(unknown) === code(foreign), `${foreign.status}/${code(foreign)} vs ${unknown.status}/${code(unknown)}`, "http");
  // ---- nothing happened ---------------------------------------------------------------------------------------------------------------------------------
  check.ok("A's revision is unchanged by every forged write", (await A.get(base)).body.revision === rev0, "", "persistence");
  check.ok("no deployment was created by the forged publishes", j(((await A.get(`${base}/deployments`)).body ?? []).map((d) => d.id)) === deps0, "", "persistence");
  const names = ((await management(fx, "adminA", "A").list()).body?.items ?? []).map((d) => d.name);
  check.ok("no data source was created by the forged requests", !names.some((n) => n.includes("forged")), j(names), "persistence");

  // ---- UI: a foreign person opening A's project by URL ----------------------------------------------------------------------------------------------
  {
    const b = await newPage(browser); const bodies = [];
    b.on("response", async (x) => { if (/\/api\/v1\//.test(x.url())) { try { bodies.push(await x.text()); } catch { /* aborted */ } } });
    await loginUi(b, cfg, fx.users.adminB.username, fx.users.adminB.password);
    await openBuilder(b, cfg, p); await b.waitForTimeout(1200);
    const t = await bodyText(b);
    check.ok("foreign person: no Builder canvas", (await b.locator("iframe").count()) === 0);
    check.ok("foreign person: the page says something went wrong / not found (not a blank screen)", t.length > 20 && /(không tìm thấy|không có quyền|không truy cập|lỗi|thử lại)/i.test(t), t.slice(0, 160));
    const all = bodies.join("\n");
    check.ok("foreign person: A's project name / description are nowhere on screen or in any API answer", !t.includes(fx.projects.A.name) && !all.includes(fx.projects.A.name) && !all.includes(`secret-description-${fx.runId}`));
    check.ok("foreign person: no unhandled page errors", pageProblems(b).length === 0, pageProblems(b).join(" | "));
    await b.context().close();
  }

  // ---- UI of the person who is in scope but lacks permissions: the state must match what the server enforces -----------------------------------------
  let mismatch = null;
  {
    const e = expectations(r.resolvedProject);
    const v = await newPage(browser);
    await loginUi(v, cfg, fx.users.viewerA.username, fx.users.viewerA.password);
    if (/no-access/.test(v.url())) { mismatch = { me: r.me, project: r.project }; await v.context().close(); }
    else {
      await openBuilder(v, cfg, p); await v.waitForTimeout(800);
      const t = await bodyText(v);
      check.ok("in-scope person without permissions: no editable Builder (read-only notice) and no project data leak beyond what the server allows", t.includes("Bạn chỉ có quyền xem"));
      const pub = v.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ });
      check.ok("in-scope person: Publish is disabled in the UI AND the forged publish was refused (403) — UI state matches enforcement", (await pub.isDisabled()) && viewerStatus[1] === 403);
      await v.getByRole("button", { name: "Dùng thử" }).click();
      await v.locator('[data-testid="test-panel"]').waitFor({ timeout: 10_000 });
      const run = v.getByTestId(`run-action:${fx.ids.actionId}`), wf = v.getByTestId(`run-workflow:${fx.ids.workflowId}`);
      check.ok("in-scope person: TEST controls are disabled in the UI AND the forged TEST calls were refused (403)", (await run.isDisabled()) === !e.testNavigateAction && (await wf.isDisabled()) === !e.testWorkflow && viewerStatus[2] === 403 && viewerStatus[3] === 403);
      check.ok("in-scope person: no unhandled page errors", pageProblems(v).length === 0, pageProblems(v).join(" | "));
      await v.context().close();
    }
  }
  if (mismatch) throw new Mismatch("C1", {
    expected: "VIEWER resolved permissions include APP_VIEW, so Studio opens read-only and the UI permission state can be compared with the forged-call denials",
    actual: `GET /auth/me → permissions=${j(mismatch.me.permissions)}, workspaces[].permissions=${j(mismatch.me.workspace)}; GET …/projects/{p} → permissions=${j(mismatch.project.permissions)}`,
    impact: "Studio redirects the VIEWER to /auth/no-access, so the UI half of 'UI permission state ≠ enforcement' cannot be observed for an in-scope person without permissions. The API half PASSED above (403 same scope, 404 foreign scope).",
  }, "H-C1-04");
}
