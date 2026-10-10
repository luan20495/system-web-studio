// @class: real-backend — Studio access follows the RESOLVED permission set (C1 contract), never a role name.
//   case A  a person whose resolved permissions include APP_VIEW (the project VIEWER): Studio opens, project/page visible, NOT /auth/no-access, read-only, nothing editable, no publish, no data-source management.
//   case B  a person with no APP_VIEW anywhere (a workspace member with no project access): redirected to /auth/no-access, no project data.
//   case C  an editor-capable person (adminA): Studio opens and the project is editable (the read-only gate must not break the editor/admin).
// The expectation of every UI state is computed from what the server returned (`lib/permissions.mjs`). If the server resolves APP_VIEW for the project but the portal still refuses the person,
// that is a CONTRACT MISMATCH (owner C1/C0): the flow ends FAIL with the block, and the frontend does NOT inject the permission.
import { Mismatch } from "../lib/report.mjs";
import { resolvedFor, expectations } from "../lib/permissions.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-04", title = "Authorized user reaches a valid Studio resource (permission-driven)";
const j = (x) => JSON.stringify(x);
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id;
  if (fx.notes.viewerOnProject !== "member") throw new Error(`fixture: the project VIEWER could not be added (${fx.notes.viewerOnProject})`);
  let mismatch = null;

  // ---- case C: admin/editor: not broken by the read-only gate ------------------------------------------------------------------------------------------
  {
    const r = await resolvedFor(fx.sessions.adminA, w, p); const e = expectations(r.resolvedProject);
    check.ok("adminA: the server resolves APP_VIEW and APP_EDIT for the project", e.viewStudio && e.edit, j(r.project.permissions), "http");
    const page = await newPage(browser);
    await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
    check.ok("adminA: after login the Studio shell is reachable (not /auth/no-access)", !/no-access/.test(page.url()), page.url());
    const { schemaResponse, canvas } = await openBuilder(page, cfg, p);
    check.ok("adminA: direct URL → schema 200 and the Builder canvas", schemaResponse?.status() === 200 && canvas, `status=${schemaResponse?.status()}`);
    const t = await bodyText(page);
    check.ok("adminA: the editor is NOT read-only (no read-only notice)", !t.includes("Bạn chỉ có quyền xem"), t.slice(0, 100));
    await page.reload({ waitUntil: "domcontentloaded" }); await page.waitForSelector("iframe", { timeout: 15_000 }).catch(() => undefined);
    check.ok("adminA: refresh keeps the project open", page.url().includes(p));
    check.ok("adminA: no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
    await page.context().close();
  }

  // ---- case A: APP_VIEW without APP_EDIT --------------------------------------------------------------------------------------------------------------
  {
    const r = await resolvedFor(fx.sessions.viewerA, w, p); const e = expectations(r.resolvedProject);
    fx.notes.viewerResolved = { me: r.me, project: r.project };
    check.ok("viewerA: GET …/projects/{p} resolves APP_VIEW (contract: a VIEWER holds APP_VIEW) — canonical or its storage alias", e.viewStudio, j(r.project), "http");
    check.ok("viewerA: …and does not resolve APP_EDIT / APP_PUBLISH / DATA_SOURCE_MANAGE", !e.edit && !e.publish && !e.manageDataSources, j(r.project.permissions), "http");
    const api = await fx.sessions.viewerA.get(`/workspaces/${w}/projects/${p}/schema`);
    check.ok("viewerA: the API lets them READ the schema (200)", api.status === 200, `status=${api.status}`, "http");
    const page = await newPage(browser);
    await loginUi(page, cfg, fx.users.viewerA.username, fx.users.viewerA.password);
    const gated = /no-access/.test(page.url());
    if (gated && e.viewStudio) {
      const t0 = await bodyText(page);
      check.ok("viewerA: the refusal page shows no project data", !t0.includes(fx.projects.A.name));
      mismatch = { me: r.me, project: r.project };
      await page.context().close();
    } else if (!gated) {
      check.ok("viewerA: Studio opens (not /auth/no-access)", true, page.url());
      const { schemaResponse, canvas } = await openBuilder(page, cfg, p);
      check.ok("viewerA: direct URL → schema 200 and the Builder canvas (project/page visible)", schemaResponse?.status() === 200 && canvas, `status=${schemaResponse?.status()} canvas=${canvas}`);
      const t = await bodyText(page);
      check.ok("viewerA: the read-only state is explicit (notice), not an apparent error", t.includes("Bạn chỉ có quyền xem"), t.slice(0, 100));
      check.ok("viewerA: project name is visible", t.includes(fx.projects.A.name));
      check.ok("viewerA: no page-level redirect after a refresh", await (async () => { await page.reload({ waitUntil: "domcontentloaded" }); await page.waitForSelector("iframe", { timeout: 15_000 }).catch(() => undefined); return !/no-access/.test(page.url()); })());
      await page.locator("[role=treeitem]").nth(1).click().catch(() => undefined); await page.waitForTimeout(400);
      const inputs = page.locator(".bx-right input:not([type=hidden]), .bx-right textarea");
      check.ok("viewerA: property inputs are disabled", (await inputs.count()) > 0 && (await inputs.evaluateAll((els) => els.every((x) => x.disabled))), `inputs=${await inputs.count()}`);
      check.ok("viewerA: no enabled save control", (await page.getByRole("button", { name: /Lưu thay đổi/ }).evaluateAll((els) => els.every((x) => x.disabled))));
      const pub = page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ });
      // the top bar's GuardedButton: an unavailable action is aria-disabled (still focusable); pressing it says WHY in a toast and sends nothing
      const ariaOff = (await pub.getAttribute("aria-disabled")) === "true" || (await pub.isDisabled());
      await pub.click({ force: true }).catch(() => undefined);
      const said = await page.getByText(/không có quyền xuất bản/).first().waitFor({ timeout: 4000 }).then(() => true).catch(() => false);
      check.ok("viewerA: Publish is unavailable (no APP_PUBLISH): aria-disabled, and pressing it explains why", ariaOff && (said || (await pub.isDisabled())), `aria=${await pub.getAttribute("aria-disabled")} said=${said}`);
      await page.locator(".bx-left").getByRole("tab", { name: "Dữ liệu" }).click().catch(() => undefined); await page.waitForTimeout(500);
      if (await page.getByTestId("ds-panel").count()) check.ok("viewerA: data-source management controls are disabled", await page.getByTestId("ds-create").isDisabled().catch(() => true));
      else check.ok("viewerA: no data-source management panel is offered (no DATA_SOURCE_VIEW/MANAGE)", true);
      await page.getByRole("button", { name: "Dùng thử" }).click().catch(() => undefined);
      if (await page.locator('[data-testid="test-panel"]').count()) {
        const run = page.getByTestId(`run-action:${fx.ids.actionId}`), wf = page.getByTestId(`run-workflow:${fx.ids.workflowId}`);
        if (await run.count()) check.ok(`viewerA: TEST action control is ${e.testNavigateAction ? "enabled" : "disabled"} as the resolved set dictates`, (await run.isEnabled()) === e.testNavigateAction && (e.testNavigateAction || !!(await run.getAttribute("title"))), await run.getAttribute("title"));
        if (await wf.count()) check.ok(`viewerA: TEST workflow control is ${e.testWorkflow ? "enabled" : "disabled"} as the resolved set dictates`, (await wf.isEnabled()) === e.testWorkflow, await wf.getAttribute("title"));
      }
      check.ok("viewerA: no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
      await page.context().close();
    } else {
      await page.context().close();
    }
  }

  // ---- case B: no APP_VIEW anywhere → /auth/no-access ---------------------------------------------------------------------------------------------------
  {
    const r = await resolvedFor(fx.sessions.lonelyA, w, p); const e = expectations(new Set([...r.resolvedMe, ...r.resolvedProject]));
    check.ok("lonelyA: no APP_VIEW is resolved anywhere (/auth/me, project payload)", !e.viewStudio, j({ me: r.me, project: r.project }), "http");
    const page = await newPage(browser); const bodies = [];
    page.on("response", async (x) => { if (/\/api\/v1\//.test(x.url())) { try { bodies.push(await x.text()); } catch { /* aborted */ } } });
    await loginUi(page, cfg, fx.users.lonelyA.username, fx.users.lonelyA.password);
    check.ok("lonelyA: after login the person is sent to /auth/no-access", /\/auth\/no-access/.test(page.url()), page.url());
    await page.goto(`${cfg.studio}${cfg.studioPrefix}/projects/${p}/design`, { waitUntil: "domcontentloaded" }); await page.waitForTimeout(1200);
    const t = await bodyText(page);
    check.ok("lonelyA: a direct project URL also ends on /auth/no-access (no Builder)", /\/auth\/no-access/.test(page.url()) && (await page.locator("iframe").count()) === 0, page.url());
    check.ok("lonelyA: no project name or description on screen or in any API answer", !t.includes(fx.projects.A.name) && !bodies.join("\n").includes(fx.projects.A.name) && !bodies.join("\n").includes(`secret-description-${fx.runId}`));
    check.ok("lonelyA: no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
    await page.context().close();
  }

  if (mismatch) throw new Mismatch("C1", {
    expected: "VIEWER resolved permissions include APP_VIEW, so the Studio portal opens read-only",
    actual: `GET /auth/me → permissions=${j(mismatch.me.permissions)}, workspaces[].permissions=${j(mismatch.me.workspace)}; GET …/projects/{p} → permissions=${j(mismatch.project.permissions)} (PROJECT_READ is the storage name of APP_VIEW)`,
    impact: "the Studio gate (reads /auth/me) redirects the VIEWER to /auth/no-access; /auth/me lists WORKSPACE-level codes only, so any person whose rights come from a PROJECT membership (VIEWER, EDITOR, PUBLISHER) is refused",
  }, "H-C1-04");
}
