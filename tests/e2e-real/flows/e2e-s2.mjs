// @class: real-backend — supplementary: a save that fails because the NETWORK is down (link-level emulation only) can be retried safely against the real backend.
import { loginUi, newPage, openBuilder, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-S2", title = "(supplementary) Save fails offline, retry after reconnect saves exactly once";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const rev0 = (await A.get(`/workspaces/${w}/projects/${p}`)).body.revision;
  await page.frameLocator("iframe").locator("section").first().click({ position: { x: 30, y: 30 } });
  await page.waitForSelector(".bx-right input");
  const marker = `E2E-OFFLINE-${fx.runId}`;
  await page.locator(".bx-right input").first().fill(marker);
  await page.context().setOffline(true);                                       // the link drops; every answer the backend would give is still the real one
  await page.getByRole("button", { name: /Lưu thay đổi/ }).click();
  await page.waitForSelector(".saveState.error", { timeout: 20_000 }).catch(() => undefined);
  check.ok("the top bar says the save failed", (await page.locator(".saveState").innerText()).includes("Lưu thất bại"));
  check.ok("a retry button is offered", (await page.getByTestId("retry-save").count()) === 1);
  const publish = page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ });
  check.ok("Publish is disabled while an edit is unsaved", await publish.isDisabled());
  await page.context().setOffline(false);
  const [patch] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PATCH" && /\/schema$/.test(new URL(r.url()).pathname), { timeout: 20_000 }),
    page.getByTestId("retry-save").click(),
  ]);
  check.ok("the retry reached the real backend and was accepted (200)", patch.status() === 200, `status=${patch.status()}`);
  await page.waitForSelector(".saveState.saved", { timeout: 10_000 }).catch(() => undefined);
  check.ok("the top bar says saved again and the retry button is gone", (await page.locator(".saveState").innerText()).includes("Đã lưu") && (await page.getByTestId("retry-save").count()) === 0);
  const after = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body;
  check.ok("the edit is persisted", JSON.stringify(after.schema).includes(marker));
  check.ok("exactly ONE revision was added (the failed attempt never reached the server; no double apply)", after.revision === rev0 + 1, `${rev0} → ${after.revision}`);
  check.ok("no unhandled page errors", pageProblems(page).filter((e) => !/Failed to fetch|NetworkError|ERR_INTERNET_DISCONNECTED|net::/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
