// @class: unit — what the user is told while an AI request runs (facts of the stream only; no invented phases)
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { STALL_SECONDS, clock, describeStatus, progressView, type AiLive } from "../../features/studio/aiProgressModel";
import { AiProgress } from "../../features/studio/AiProgress";
import { deadlineMs } from "../../packages/api-client/src/core";
import { a11yProblems } from "./a11y";

const T0 = 1_000_000;
const live = (o: Partial<AiLive> = {}): AiLive => ({ id: "s1", text: "", status: "", startedAt: T0, lastAt: T0, deadline: T0 + 120_000, ...o });
const at = (s: number) => T0 + s * 1000;
const states = (v: ReturnType<typeof progressView>) => v.steps.map((x) => x.state).join(",");

test("before `start`: the request is still leaving; no stream id, cancel is still possible", () => {
  const v = progressView(live({ id: null, deadline: null }), at(2), "auto");
  assert.equal(v.headline, "Đang gửi yêu cầu tới máy chủ…"); assert.equal(states(v), "active,pending,pending,pending"); assert.equal(v.canCancel, true); assert.equal(v.detail, "");
  assert.match(progressView(live({ id: null, deadline: null }), at(6), "auto").detail, /chưa xác nhận/);
});
test("after `start`, before any output: waiting for the model, says which model and how long, never 'done'", () => {
  const v = progressView(live(), at(4), "auto");
  assert.equal(states(v), "done,active,pending,pending"); assert.match(v.headline, /chờ model/); assert.match(v.steps[1].label, /Tự động: thử lần lượt/);
  assert.equal(v.stalled, false); assert.equal(v.elapsedSeconds, 4);
  assert.match(progressView(live(), at(5), "openrouter/free").steps[1].label, /openrouter\/free/);
});
test("silence is reported as silence, with the number of seconds (the case of the endless 'Đang chờ AI')", () => {
  const v = progressView(live(), at(STALL_SECONDS + 5), "auto");
  assert.equal(v.stalled, true); assert.match(v.detail, /Chưa có phản hồi nào từ model sau 20 giây/); assert.match(v.detail, /huỷ rồi thử lại/i);
  assert.equal(progressView(live(), at(STALL_SECONDS - 1), "auto").stalled, false);
});
test("output arriving: characters are counted; no stall while it flows", () => {
  const v = progressView(live({ text: "x".repeat(120), lastAt: at(9) }), at(10), "auto");
  assert.match(v.headline, /120 ký tự/); assert.equal(states(v), "done,done,active,pending"); assert.equal(v.stalled, false);
});
test("output stopped: we say the server MAY be checking/saving (a hedge, not a claim)", () => {
  const v = progressView(live({ text: "x".repeat(300), lastAt: at(10) }), at(20), "auto");
  assert.match(v.headline, /có thể đang kiểm tra và lưu/); assert.equal(states(v), "done,done,done,active"); assert.equal(v.stalled, false);
});
test("status vocabulary: known ones are explained, unknown text is never printed raw", () => {
  assert.equal(describeStatus("tool:search_template"), "AI đang tra cứu template…");
  assert.equal(describeStatus("tool:unknown_x"), "AI đang tra cứu dữ liệu của hệ thống…");
  assert.equal(describeStatus("model:openrouter/free"), "Đang hỏi model openrouter/free…");
  assert.match(describeStatus("fallback:b")!, /thử b/);
  assert.equal(describeStatus("validating"), "Đang kiểm tra thay đổi theo registry…"); assert.equal(describeStatus("saving"), "Đang lưu thành phiên bản mới…");
  assert.equal(describeStatus("<script>alert(1)</script>"), "Máy chủ đang xử lý…"); assert.equal(describeStatus(""), null);
  const v = progressView(live({ status: "saving", text: "{}" }), at(30), "auto"); assert.equal(v.headline, "Đang lưu thành phiên bản mới…"); assert.equal(states(v), "done,done,done,active");
});
test("deadline: shown as a countdown and as a final warning; 0 = waiting for the server to end it", () => {
  assert.equal(progressView(live(), at(30), "auto").remainingSeconds, 90);
  assert.match(progressView(live({ lastAt: at(105) }), at(110), "auto").detail, /tự dừng sau 10 giây/);
  assert.match(progressView(live({ lastAt: at(121) }), at(121), "auto").detail, /tới hạn thời gian/);
  assert.equal(progressView(live({ deadline: null }), at(30), "auto").remainingSeconds, null);
  assert.equal(clock(83), "1:23"); assert.equal(clock(5), "0:05");
});
test("`start` deadline: ISO string, epoch seconds (with fraction) or epoch ms are all read; garbage is null", () => {
  assert.equal(deadlineMs("2026-10-07T10:00:00Z"), Date.parse("2026-10-07T10:00:00Z"));
  assert.equal(deadlineMs(1791194400.5), 1791194400500); assert.equal(deadlineMs(1791194400500), 1791194400500);
  assert.equal(deadlineMs("soon"), null); assert.equal(deadlineMs(undefined), null);
});
test("component: headline in a polite live region, steps listed, clock hidden from screen readers, a cancel button, accessible", () => {
  const html = renderToStaticMarkup(<AiProgress live={live()} model="auto" now={at(30)} onCancel={() => undefined}/>);
  assert.match(html, /data-testid="ai-headline"/); assert.match(html, /aria-live="polite"/); assert.match(html, /Đã chạy 0:30 · tự dừng sau 1:30/); assert.match(html, /aria-hidden="true" data-testid="ai-clock"/);
  assert.match(html, /data-testid="ai-cancel"/); assert.match(html, /data-stalled="true"/); assert.match(html, /class="aiWarn" role="status"/);
  assert.deepEqual(a11yProblems(html), []);
  const pre = renderToStaticMarkup(<AiProgress live={live({ id: null, deadline: null })} model="auto" now={at(1)} onCancel={() => undefined}/>);
  assert.match(pre, /data-started="false"/); assert.match(pre, /data-testid="ai-cancel"/);
});
