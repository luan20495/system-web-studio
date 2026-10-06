// @class: real-backend
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-05", title = "Forbidden path UX, no data leak";
export async function run({ cfg, fx, browser, check }) {
  // (a) a user of ANOTHER workspace opens A's project by URL
  const b = await newPage(browser); const bodies = [];
  b.on("response", async (r) => { if (/\/api\/v1\//.test(r.url())) { try { bodies.push(await r.text()); } catch { /* aborted */ } } });
  await loginUi(b, cfg, fx.users.adminB.username, fx.users.adminB.password);
  await openBuilder(b, cfg, fx.projects.A.id); await b.waitForTimeout(1200);
  const t = await bodyText(b);
  check.ok("outsider: no Builder canvas is shown", (await b.locator("iframe").count()) === 0);
  check.ok("outsider: the page says something went wrong / not found (not a blank screen)", t.length > 20 && /(không tìm thấy|không có quyền|không truy cập|lỗi|thử lại)/i.test(t), t.slice(0, 160));
  check.ok("outsider: A's project name is nowhere on screen", !t.includes(fx.projects.A.name));
  const all = bodies.join("\n");
  check.ok("outsider: no API response of that page load contains A's project name or description", !all.includes(fx.projects.A.name) && !all.includes(`secret-description-${fx.runId}`));
  check.ok("outsider: no unhandled page errors", pageProblems(b).length === 0, pageProblems(b).join(" | "));
  await b.context().close();

  // (b) a VIEWER of A cannot change A
  const w = fx.workspaces.A, p = fx.projects.A.id;
  const rev = (await fx.sessions.adminA.get(`/workspaces/${w}/projects/${p}`)).body.revision;
  const write = await fx.sessions.viewerA.patch(`/workspaces/${w}/projects/${p}/schema`, { expectedRevision: rev, operations: [], summary: "viewer probe" });
  check.ok("viewer: PATCH …/schema → 403", write.status === 403, `status=${write.status} code=${write.body?.code}`);
  check.ok("viewer: A's revision is unchanged", (await fx.sessions.adminA.get(`/workspaces/${w}/projects/${p}`)).body.revision === rev);
  const v = await newPage(browser);
  await loginUi(v, cfg, fx.users.viewerA.username, fx.users.viewerA.password);
  await openBuilder(v, cfg, p);
  const edit = v.getByRole("button", { name: /Lưu thay đổi/ });
  const vt = await bodyText(v);
  check.ok("viewer: read-only notice shown", vt.includes("Bạn chỉ có quyền xem"));
  check.ok("viewer: no save control is enabled", (await edit.count()) === 0 || (await edit.evaluateAll((els) => els.every((e) => e.disabled))));
  if (fx.notes.definitionOps?.ok) {
    await v.getByRole("button", { name: "Dùng thử" }).click();
    await v.waitForSelector('[data-testid="test-panel"]');
    const run = v.getByTestId(`run-action:${fx.ids.actionId}`);
    check.ok("viewer: Test mode 'Chạy thử' is disabled with a reason", (await run.count()) === 1 && await run.isDisabled() && !!(await run.getAttribute("title")), await run.getAttribute("title"));
  }
  check.ok("viewer: no unhandled page errors", pageProblems(v).length === 0, pageProblems(v).join(" | "));
  await v.context().close();
}
