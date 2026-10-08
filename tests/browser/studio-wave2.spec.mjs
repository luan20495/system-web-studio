// @class: harness — real Chromium on the REAL Studio app with an in-test FAKE of /api/v1 (tests/browser/studio-app/); NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Regression checks of Studio wave-2 issues (docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md): M-015, M-006 (Builder boundary), ...
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/studio-wave2.spec.mjs
import { open, check, wait, finish, newState } from "./studio-app/lib.mjs";
import { launch } from "./lib/spec.mjs";
import { newCodeState } from "./studio-app/fake-api.mjs";
const b = await launch();

// ---------- M-015: the code editor must not trap the keyboard (WCAG 2.1.2) ----------
{
  const s = newCodeState(); const p = await open(b, "/studio/projects/p1/code", { state: s }); await p.waitForSelector(".codeEditor"); await wait(600);
  const ed = p.locator(".codeEditor");
  const inEditor = () => p.evaluate(() => document.activeElement?.classList.contains("codeEditor") ?? false);
  const val = () => ed.inputValue();
  await ed.focus(); const v0 = await val();
  await p.keyboard.press("Tab"); await wait(100);
  check("M-015: Tab leaves the editor by default (focus moves on) and changes no text", !(await inEditor()) && (await val()) === v0);
  await ed.focus(); await p.keyboard.press("Shift+Tab"); await wait(100);
  check("M-015: Shift+Tab leaves the editor", !(await inEditor()));
  const hint = p.getByText(/Tab/, { exact: false }).filter({ hasText: /thụt lề/ });
  check("M-015: a visible hint explains how Tab works in the editor", (await hint.count()) > 0);
  const toggle = p.getByLabel(/Dùng phím Tab để thụt lề/);
  check("M-015: indenting with Tab is an explicit, visible, OFF-by-default option", (await toggle.count()) === 1 && !(await toggle.isChecked()));
  await toggle.check(); await ed.focus(); await ed.evaluate((e) => { e.setSelectionRange(0, 0); });
  await p.keyboard.press("Tab"); await wait(150);
  check("M-015: with the option ON, Tab indents (two spaces) and focus stays in the editor", (await inEditor()) && (await val()).startsWith("  ") && (await val()).length === v0.length + 2);
  await p.keyboard.press("Escape"); await p.keyboard.press("Tab"); await wait(150);
  check("M-015: with the option ON, Esc then Tab is the escape hatch (focus leaves, no extra indent)", !(await inEditor()) && (await val()).length === v0.length + 2);
  await ed.focus(); await p.keyboard.press("Shift+Tab"); await wait(100);
  check("M-015: with the option ON, Shift+Tab still leaves", !(await inEditor()));
  check("M-015: no uncaught error", p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
// ---------- M-048: StudioApp was split into one file per screen; every route still renders its screen (behaviour-preserving) ----------
for (const [path, heading] of [["/studio", /Bạn muốn xây dựng gì/], ["/studio/projects", /^Ứng dụng$/], ["/studio/new", /Tạo ứng dụng/], ["/studio/templates", /^Templates$/], ["/studio/components", /Company Components/], ["/studio/activity", /Hoạt động của tôi/], ["/studio/nope", /Không tìm thấy/]]) {
  const p = await open(b, path); await p.waitForSelector("main h1, main h2", { timeout: 8000 }).catch(() => undefined); await wait(500);
  const h = await p.locator("main h1").first().innerText().catch(() => "");
  check(`M-048: ${path} renders its screen (${heading})`, heading.test(h) || heading.test(await p.locator("main").innerText()), h);
  check(`M-048: ${path} has no uncaught error`, p.errors.length === 0, p.errors.join(" | "));
  await p.close();
}
// ---------- M-006 (Builder boundary): a throwing panel keeps the project chrome ----------
{
  // a saved document with a malformed prop: the Inspector's form throws (`items.map is not a function`) as soon as that section is selected
  const s = newState();
  s.registry = (await import("./studio-app/fake-api.mjs")).registry.map((c) => c.id !== "Hero" ? c : { ...c, versions: [{ ...c.versions[0], propsSchema: { ...c.versions[0].propsSchema, properties: { ...c.versions[0].propsSchema.properties, badges: { type: "array", itemProperties: { name: { type: "string" } }, itemRequired: ["id", "name"] } } } }] });
  s.schema.sections = s.schema.sections.map((x) => x.id === "s-hero" ? { ...x, props: { ...x.props, badges: "oops" } } : x);
  const p = await open(b, "/studio/projects/p1/design", { state: s }); await p.waitForSelector("iframe"); await wait(900);
  const row = (n) => p.locator("[role=treeitem][aria-level='2']").filter({ hasText: n }).first();
  await row("Đầu trang").click(); await wait(700);
  const bodyText = await p.locator("body").innerText();
  check("M-006: a throwing Inspector does NOT blank the page: the project name, mode tabs and Chia sẻ / Xuất bản are still there", /Website máy lọc nước/.test(bodyText) && (await p.getByRole("button", { name: "Xuất bản" }).count()) > 0 && (await p.locator(".modeTabs").count()) === 1, bodyText.slice(0, 120).replace(/\n/g, " | "));
  check("M-006: the failed panel shows a Vietnamese fallback with 'Thử lại', never the exception text", (await p.getByTestId("error-fallback").count()) === 1 && /Thử lại/.test(await p.getByTestId("error-fallback").innerText()) && !/is not a function|TypeError/.test(bodyText));
  check("M-006: the page tree and the preview still work next to the failed panel", (await p.locator("[role=treeitem][aria-level='2']").count()) >= 5 && (await p.locator("iframe").count()) === 1);
  check("M-006: focus moved to the fallback heading", await p.evaluate(() => !!document.activeElement?.closest("[data-testid=error-fallback]")));
  await row("Chân trang").click(); await wait(600);
  check("M-006: selecting another section clears the error (resetKeys) and its Inspector works", (await p.getByTestId("error-fallback").count()) === 0 && (await p.locator(".bx-inspector").count()) === 1);
  await row("Đầu trang").click(); await wait(500);
  check("M-006: selecting the broken section again shows the fallback again, still without losing the chrome", (await p.getByTestId("error-fallback").count()) === 1 && (await p.locator(".modeTabs").count()) === 1);
  await p.getByTestId("error-fallback").getByRole("button", { name: "Thử lại" }).click(); await wait(500);
  check("M-006: 'Thử lại' retries (the same data fails again, the fallback returns, the page stays up)", (await p.locator(".modeTabs").count()) === 1);
  await p.close();
}

// ---------- M-048: the error -> notice mapping of the save machine is unchanged ----------
{
  const s = newState(); const p = await open(b, "/studio/projects/p1/design", { state: s }); await p.waitForSelector("iframe"); await wait(800);
  const row = (n) => p.locator("[role=treeitem][aria-level='2']").filter({ hasText: n }).first();
  const attempt = async (fail) => { s.fail = { "PATCH /workspaces/w1/projects/p1/schema": { ...fail, once: true } }; await row("Đánh giá").click(); await p.getByRole("button", { name: "↑ Lên" }).first().click(); await wait(900); return (await p.locator(".toast").innerText().catch(() => "")); };
  let t = await attempt({ status: 409, code: "REVISION_CONFLICT", message: "x" });
  check("M-048: REVISION_CONFLICT -> 'Project vừa được thay đổi ở nơi khác…' and the document is reloaded", /thay đổi ở nơi khác/.test(t) && s.log.filter((l) => l.method === "GET" && l.path.endsWith("/schema")).length >= 2, t);
  await p.close();
  const s2 = newState(); const q = await open(b, "/studio/projects/p1/design", { state: s2 }); await q.waitForSelector("iframe"); await wait(800);
  s2.fail = { "PATCH /workspaces/w1/projects/p1/schema": { status: 503, code: "DEPENDENCY_UNAVAILABLE", message: "down", once: true } };
  await q.locator("[role=treeitem][aria-level='2']").filter({ hasText: "Đánh giá" }).first().click(); await q.getByRole("button", { name: "↑ Lên" }).first().click(); await wait(900);
  check("M-048: a 5xx save failure is retryable: top bar says 'Lưu thất bại' and offers 'Thử lại'", /Lưu thất bại/.test(await q.locator(".bx-top").innerText()) && (await q.getByTestId("retry-save").count()) === 1);
  await q.close();
}
await b.close(); finish();
