// @class: real-backend — shared steps of the Test panel flows.
import { loginUi, newPage, openBuilder } from "./ui.mjs";

export async function openTestPanel(page, cfg, user, projectId) {
  await loginUi(page, cfg, user.username, user.password);
  await openBuilder(page, cfg, projectId);
  await page.getByRole("button", { name: "Dùng thử" }).click();
  await page.waitForSelector('[data-testid="test-panel"]', { timeout: 10_000 });
}

/** the outcome box inside a row, once it left RUNNING; returns {state, text} */
export async function settledOutcome(page, rowTestId, timeoutMs = 30_000) {
  const row = page.getByTestId(rowTestId);
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const boxes = row.locator("[data-outcome]");
    const n = await boxes.count();
    if (n) { const first = boxes.first(); const state = await first.getAttribute("data-outcome"); if (state && state !== "RUNNING") return { state, text: (await first.innerText()).replace(/\s+/g, " ") }; }
    if (Date.now() > deadline) return { state: "TIMEOUT", text: "" };
    await page.waitForTimeout(250);
  }
}

/** the UI's honest "this server does not serve it" states: 404 without a domain code (flag off / not mounted), 501, 503 DATA_RUNTIME_UNAVAILABLE */
export const flagOff = (text) => /chưa có chức năng tương ứng|chưa bật tính năng này|đang không sẵn sàng/.test(text);
/** 503 RUNTIME_STORES_VOLATILE: workflow/LIVE-write stores are in memory (V29 not integrated) and allow-volatile-stores is off */
export const volatileStores = (text) => /chưa cấu hình kho lưu trữ bền|chưa lưu bền được lượt chạy/.test(text);
