/**
 * What the user is told while an AI request runs. Pure (no React, no clock of its own): the component feeds it `now`.
 * It only reports FACTS the stream gives us: the request left (no `start` yet), `start` (+ deadline), `delta` (characters so far), `status`, and how long ago the last event was.
 * It never invents a phase: when the server is silent the text says it is silent and for how long.
 *
 * `status` vocabulary: `tool:<name>` exists today (the model asked for a lookup). `model:<id>`, `fallback:<id>`, `validating`, `saving` are what the UI understands IF the server sends them (handoff H-C2-10);
 * any other text is shown as a neutral "đang xử lý" and never printed raw.
 */
export type AiLive = { id: string | null; text: string; status: string; startedAt: number; lastAt: number; deadline: number | null };
export type StepState = "done" | "active" | "pending";
export type AiStep = { id: "send" | "model" | "answer" | "save"; label: string; state: StepState };
export type AiProgressView = { steps: AiStep[]; headline: string; detail: string; elapsedSeconds: number; remainingSeconds: number | null; silentSeconds: number; stalled: boolean; canCancel: boolean };

/** no event for this long = say so (free models are often queued; the server fails over to the next one on its own) */
export const STALL_SECONDS = 15;
/** after output stopped arriving for this long, the likely reason is the server checking and saving */
export const SETTLE_SECONDS = 3;

const TOOL_LABEL: Record<string, string> = { search_component: "component trong registry", search_template: "template", read_project_schema: "nội dung trang hiện tại", read_project_file: "tệp mã nguồn", propose_file_change: "đề xuất thay đổi tệp", search_connector_metadata: "thông tin nguồn dữ liệu" };
export function describeStatus(status: string): string | null {
  if (!status) return null;
  if (status.startsWith("tool:")) { const t = status.slice(5); return `AI đang tra cứu ${TOOL_LABEL[t] ?? "dữ liệu của hệ thống"}…`; }
  if (status.startsWith("model:")) return `Đang hỏi model ${status.slice(6)}…`;
  if (status.startsWith("fallback:")) return `Model trước chưa trả lời được, đang thử ${status.slice(9)}…`;
  if (status === "validating") return "Đang kiểm tra thay đổi theo registry…";
  if (status === "saving") return "Đang lưu thành phiên bản mới…";
  return "Máy chủ đang xử lý…";
}

export const modelLabel = (model: string): string => (model === "auto" ? "Tự động: thử lần lượt các model miễn phí" : model);

export function progressView(live: AiLive, now: number, model: string): AiProgressView {
  const started = live.id !== null;
  const elapsed = Math.max(0, Math.floor((now - live.startedAt) / 1000));
  const silent = Math.max(0, Math.floor((now - live.lastAt) / 1000));
  const chars = live.text.length;
  const saving = live.status === "saving" || live.status === "validating";
  const remaining = live.deadline === null ? null : Math.max(0, Math.ceil((live.deadline - now) / 1000));
  const answering = chars > 0 && !saving;
  const settled = chars > 0 && silent >= SETTLE_SECONDS;
  const state = (done: boolean, active: boolean): StepState => (done ? "done" : active ? "active" : "pending");
  const steps: AiStep[] = [
    { id: "send", label: "Gửi yêu cầu tới máy chủ", state: state(started, !started) },
    { id: "model", label: `Chờ model trả lời (${modelLabel(model)})`, state: state(chars > 0 || saving, started && chars === 0 && !saving) },
    { id: "answer", label: "Nhận câu trả lời của AI", state: state(saving || settled, answering && !settled) },
    { id: "save", label: "Kiểm tra theo registry và lưu phiên bản", state: state(false, saving || settled) },
  ];
  const tool = describeStatus(live.status);
  let headline: string;
  if (!started) headline = "Đang gửi yêu cầu tới máy chủ…";
  else if (live.status === "saving" || live.status === "validating") headline = tool!;
  else if (live.status.startsWith("tool:") || live.status.startsWith("model:") || live.status.startsWith("fallback:")) headline = tool!;
  else if (settled) headline = `Đã nhận ${chars} ký tự; máy chủ có thể đang kiểm tra và lưu…`;
  else if (chars > 0) headline = `Đang nhận câu trả lời… ${chars} ký tự`;
  else headline = "Đã gửi, đang chờ model bắt đầu trả lời…";
  const stalled = started && silent >= STALL_SECONDS && !settled;
  let detail = "";
  if (!started && elapsed >= 5) detail = "Máy chủ chưa xác nhận đã nhận yêu cầu. Kiểm tra kết nối rồi huỷ và gửi lại nếu quá lâu.";
  else if (stalled && chars === 0) detail = `Chưa có phản hồi nào từ model sau ${silent} giây. Model miễn phí thường bận; với “Tự động”, máy chủ tự chuyển sang model khác khi một model lỗi. Bạn có thể huỷ rồi thử lại hoặc chọn model khác.`;
  else if (stalled) detail = `Model ngừng gửi dữ liệu ${silent} giây. Có thể huỷ và thử lại.`;
  else if (remaining !== null && remaining <= 20 && remaining > 0) detail = `Yêu cầu sẽ tự dừng sau ${remaining} giây nếu AI chưa xong.`;
  else if (remaining === 0) detail = "Đã tới hạn thời gian; đang chờ máy chủ kết thúc.";
  return { steps, headline, detail, elapsedSeconds: elapsed, remainingSeconds: remaining, silentSeconds: silent, stalled, canCancel: true };
}

/** 83 → "1:23" */
export const clock = (seconds: number): string => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
