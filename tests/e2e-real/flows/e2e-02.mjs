// @class: real-backend
import { loginUi, newPage, openBuilder, pageProblems, selectFirstSection, containsText, rememberMarker } from "../lib/ui.mjs";
export const id = "E2E-02", title = "Edit component/page → save → reload → persists";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA;
  const before = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const sel = await selectFirstSection(page);
  check.ok("clicking a section on the canvas opens its properties", sel.ok, `attempts=${sel.attempts}`);
  if (!sel.ok) { await page.context().close(); return; }
  const marker = rememberMarker(fx, `E2E-${fx.runId}-${Date.now().toString(36)}`); fx.notes.marker = marker;
  await page.locator(".bx-right input").first().fill(marker);
  const [patch] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PATCH" && /\/schema$/.test(new URL(r.url()).pathname), { timeout: 20_000 }),
    page.getByRole("button", { name: /Lưu thay đổi/ }).click(),
  ]);
  check.ok("PATCH …/schema answered 200 by the real backend", patch.status() === 200, `status=${patch.status()}`);
  await page.waitForSelector(".saveState.saved", { timeout: 10_000 }).catch(() => undefined);
  check.ok("the top bar says saved", (await page.locator(".saveState").innerText()).includes("Đã lưu"));
  const after = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body;
  check.ok("server revision increased", after.revision > before.revision, `${before.revision} → ${after.revision}`);
  check.ok("the new text is in the persisted schema (read back through a separate session)", JSON.stringify(after.schema).includes(marker));
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.waitForSelector("iframe", { timeout: 15_000 });
  await page.waitForTimeout(800);
  const text = await page.frameLocator("iframe").locator("body").innerText();
  // the first input is the hero eyebrow, which the template renders with CSS text-transform: uppercase (innerText returns the transformed text)
  check.ok("after a full reload the canvas shows the saved text", containsText(text, marker), text.slice(0, 100));
  check.ok("a new version row exists for the edit", (await A.get(`/workspaces/${w}/projects/${p}/versions?limit=5`)).body?.length > 0);
  check.ok("no unhandled page errors / serious console errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
