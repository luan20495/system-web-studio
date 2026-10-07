/**
 * Test mode (Edit != Test). The browser never simulates a backend: this module only DESCRIBES what Test mode would do per action kind
 * (the rules from the C4 action contract) and turns a server answer into a displayable outcome. SUCCESS exists only as something a server
 * result can carry; nothing in here can produce it on its own.
 *
 *   mutation (SUBMIT/CREATE/UPDATE/DELETE) -> WOULD_RUN (shown as "would run"; not executed in test)
 *   CALL_API with a connector that has no dry-run -> UNSUPPORTED (shown, never pretended)
 *   NOTIFY -> NOT_SENT (nothing leaves the system in test)
 *   publish -> NOT_RUN
 *   workflow -> a test run may still create a workflow_run row (stated up front)
 */
import type { ActionDef, ActionType, AppDefinitionV2, WorkflowDef, WorkflowRunView } from "@xweb/types";
import { explainError, type UserMessage } from "./errors";
import { WORKFLOW_TERMINAL_STATUSES } from "./contract";
import { available, type Readiness } from "./readiness";

export type AppMode = "EDIT" | "TEST";

export type TestOutcome =
  | { state: "WOULD_RUN"; note: string }
  | { state: "UNSUPPORTED"; note: string }
  | { state: "NOT_SENT"; note: string }
  | { state: "NOT_RUN"; note: string }
  | { state: "READ_ONLY"; note: string }
  | { state: "NOT_READY"; note: string }
  /** a request is in flight (or a workflow run is not finished yet). Never shown as a result. */
  | { state: "RUNNING"; note: string }
  | { state: "REJECTED" | "UNKNOWN" | "ERROR"; message: UserMessage }
  /** only ever built from a server answer that says it succeeded */
  | { state: "SUCCESS"; note: string; fromServer: true };

export const OUTCOME_LABEL: Readonly<Record<TestOutcome["state"], string>> = {
  WOULD_RUN: "Sẽ chạy (chưa thực hiện)", UNSUPPORTED: "Không hỗ trợ chạy thử", NOT_SENT: "Không gửi trong chế độ thử", NOT_RUN: "Không chạy trong chế độ thử", RUNNING: "Đang chạy",
  READ_ONLY: "Chỉ đọc", NOT_READY: "Chưa sẵn sàng", REJECTED: "Bị từ chối", UNKNOWN: "Chưa rõ kết quả", ERROR: "Lỗi", SUCCESS: "Thành công (theo máy chủ)",
};

export const TEST_RULES: readonly string[] = [
  "Thao tác ghi dữ liệu chỉ được mô tả là “sẽ chạy”, không thực hiện.",
  "Thao tác gọi nguồn dữ liệu chưa hỗ trợ chạy thử sẽ được ghi rõ là không hỗ trợ.",
  "Thông báo không được gửi thật.",
  "Xuất bản không chạy trong chế độ thử.",
  "Chạy thử workflow vẫn có thể tạo một bản ghi lượt chạy (workflow_run) trên máy chủ.",
];

/**
 * Test mode has routes now (runtime-api.md R1–R3), but they sit behind server flags (`app.data-platform.enabled`, `app.workflow.enabled`). There is
 * no capability probe in the contract, so the panel is AVAILABLE and the FIRST answer decides: a 404 without a domain code becomes NOT_READY
 * (`outcomeFromError` → "unavailable"), never a fake result.
 */
export const testModeReadiness = (): Readiness => available();

export function describeTestEffect(a: ActionDef, opts: { connectorSupportsDryRun?: boolean } = {}): TestOutcome {
  const t: ActionType = a.type;
  switch (t) {
    case "NAVIGATE": return { state: "READ_ONLY", note: "Chỉ chuyển trang, không có tác dụng phụ." };
    case "REFRESH_QUERY": return { state: "READ_ONLY", note: "Chạy lại truy vấn đọc. Cần API chạy thử truy vấn của máy chủ." };
    case "SUBMIT_FORM": case "CREATE_RECORD": case "UPDATE_RECORD": case "DELETE_RECORD": return { state: "WOULD_RUN", note: "Sẽ ghi dữ liệu khi chạy thật. Ở chế độ thử không ghi." };
    case "CALL_API": return opts.connectorSupportsDryRun === true
      ? { state: "WOULD_RUN", note: "Nguồn dữ liệu hỗ trợ chạy thử; kết quả do máy chủ trả." }
      : { state: "UNSUPPORTED", note: "Nguồn dữ liệu này chưa hỗ trợ chạy thử (dry-run). Thao tác không được chạy." };
    case "NOTIFY": return { state: "NOT_SENT", note: "Thông báo không được gửi thật ở chế độ thử." };
    case "START_WORKFLOW": return { state: "WOULD_RUN", note: "Có thể tạo một lượt chạy workflow (workflow_run) trên máy chủ, dù ở chế độ thử." };
  }
}

export const publishInTest = (): TestOutcome => ({ state: "NOT_RUN", note: "Xuất bản không chạy trong chế độ thử. Chuyển về Chỉnh sửa để xuất bản." });

/** plan for a whole workflow in test mode: one row per step that carries an action, plus the standing workflow_run note */
export function describeWorkflowTest(wf: WorkflowDef, doc: AppDefinitionV2): { stepId: string; label: string; outcome: TestOutcome }[] {
  return wf.steps.map((s) => {
    const a = s.actionRef ? (doc.actions ?? []).find((x) => x.id === s.actionRef) : undefined;
    const kind = s.kind ?? (s.actionRef ? "ACTION" : "END");
    const outcome: TestOutcome = a ? describeTestEffect(a)
      : kind === "WAIT" ? { state: "READ_ONLY", note: "Bước chờ." } : kind === "APPROVAL" ? { state: "READ_ONLY", note: "Dừng chờ phê duyệt." }
      : kind === "BRANCH" ? { state: "READ_ONLY", note: "Chọn nhánh theo điều kiện." } : { state: "READ_ONLY", note: "Kết thúc." };
    return { stepId: s.id, label: a?.name ?? s.id, outcome };
  });
}

/** a server failure -> the outcome the panel shows. 409 IDEMPOTENCY_OUTCOME_UNKNOWN and 422 MUTATION_REJECTED get their own states. */
export function outcomeFromError(e: unknown, opts: { write?: boolean } = {}): TestOutcome {
  const message = explainError(e, opts);
  if (message.kind === "unknown-outcome") return { state: "UNKNOWN", message };
  if (message.kind === "rejected") return { state: "REJECTED", message };
  if (message.kind === "unavailable") return { state: "NOT_READY", note: message.detail };
  return { state: "ERROR", message };
}

/** a server answer -> outcome. SUCCESS needs an explicit success marker in the answer; anything else is shown as received, never promoted. */
export function outcomeFromServer(result: unknown): TestOutcome {
  const r = result as { status?: unknown; outcome?: unknown; dryRun?: unknown; level?: unknown; reason?: unknown; mode?: unknown; error?: { code?: string; message?: string; retryable?: boolean; details?: unknown }; followUps?: { status?: string }[] } | null;
  // R2 envelope (runtime-api.md §3): status OK | FAILED | WOULD_RUN. FAILED normally arrives as a non-2xx (ApiError), but a 2xx envelope must not be promoted either.
  if (r?.status === "FAILED") return outcomeFromError({ code: r.error?.code, message: r.error?.message, details: r.error?.details, retryable: r.error?.retryable }, { write: true });
  if (r?.status === "OK") {
    const failedFollowUps = (r.followUps ?? []).filter((f) => f.status === "FAILED").length;
    const base = r.mode === "TEST" ? "Máy chủ báo thành công ở chế độ thử." : "Máy chủ báo thành công.";
    return { state: "SUCCESS", note: failedFollowUps ? `${base} Nhưng ${failedFollowUps} hành động nối tiếp thất bại.` : base, fromServer: true };
  }
  // C4 `ActionResult.WouldRun` (B-C4-09: "TEST response is WouldRun, C5 shows level/reason"). It is NEVER a success: nothing was written in any level.
  if (typeof r?.level === "string") {
    const why = typeof r.reason === "string" && r.reason ? ` ${r.reason}` : "";
    if (r.level === "NOT_EXECUTED") return { state: "WOULD_RUN", note: `Chưa gửi tới nguồn dữ liệu nào; kế hoạch chỉ suy ra từ định nghĩa và dữ liệu nhập (theo máy chủ).${why}` };
    if (r.level === "VALIDATED") return { state: "WOULD_RUN", note: `Nguồn dữ liệu đã kiểm tra yêu cầu hợp lệ, không có tác dụng phụ (theo máy chủ).${why}` };
    if (r.level === "SANDBOX") return { state: "WOULD_RUN", note: `Đã chạy trên môi trường thử do nguồn dữ liệu cung cấp, dữ liệu thật không đổi (theo máy chủ).${why}` };
  }
  const status = typeof r?.status === "string" ? r.status.toUpperCase() : typeof r?.outcome === "string" ? r.outcome.toUpperCase() : "";
  if (status === "SUCCESS" || status === "SUCCEEDED") return { state: "SUCCESS", note: r?.dryRun === true ? "Chạy thử thành công (dry-run, theo máy chủ)." : "Máy chủ báo thành công.", fromServer: true };
  if (status === "UNSUPPORTED" || status === "DRY_RUN_UNSUPPORTED") return { state: "UNSUPPORTED", note: "Máy chủ báo nguồn dữ liệu không hỗ trợ chạy thử." };
  if (status === "REJECTED") return { state: "REJECTED", message: explainError({ code: "MUTATION_REJECTED" }) };
  if (status === "UNKNOWN" || status === "OUTCOME_UNKNOWN") return { state: "UNKNOWN", message: explainError({ code: "IDEMPOTENCY_OUTCOME_UNKNOWN", status: 409 }) };
  return { state: "NOT_READY", note: "Máy chủ trả về kết quả không nhận ra được, không hiển thị là thành công." };
}

const isTerminal = (status: string) => (WORKFLOW_TERMINAL_STATUSES as readonly string[]).includes(status.toUpperCase());
export const isRunFinished = (v: Pick<WorkflowRunView, "status"> | null | undefined): boolean => !!v && typeof v.status === "string" && isTerminal(v.status);

/**
 * A workflow run view (R3) -> outcome. Not finished = RUNNING. Finished OK = SUCCESS only when the run was LIVE and no step was simulated; a TEST run
 * or a run with simulated steps is WOULD_RUN (nothing real happened). Failed / cancelled keep the server's error code.
 */
export function outcomeFromRun(v: WorkflowRunView): TestOutcome {
  const status = String(v.status ?? "").toUpperCase();
  if (!isTerminal(status)) return { state: "RUNNING", note: `Trạng thái: ${v.status}${v.currentStepId ? ` · bước ${v.currentStepId}` : ""}` };
  if (status === "FAILED" || status === "TIMED_OUT") {
    return outcomeFromError({ code: v.errorCode ?? undefined, message: v.errorMessage ?? undefined, status: v.errorCode === "IDEMPOTENCY_OUTCOME_UNKNOWN" ? 409 : undefined }, { write: true });
  }
  if (status.startsWith("CANCEL")) return { state: "NOT_RUN", note: "Lượt chạy đã bị hủy." };
  const simulated = v.mode === "TEST" || (v.steps ?? []).some((s) => s.simulated === true);
  return simulated ? { state: "WOULD_RUN", note: "Lượt chạy thử đã kết thúc; các bước được mô phỏng, dữ liệu thật không đổi (theo máy chủ)." }
    : { state: "SUCCESS", note: "Workflow đã chạy xong (theo máy chủ).", fromServer: true };
}
