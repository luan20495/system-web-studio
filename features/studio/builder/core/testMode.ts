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
import type { ActionDef, ActionType, AppDefinitionV2, WorkflowDef } from "@xweb/types";
import { explainError, type UserMessage } from "./errors";
import { staticReadiness, type Readiness } from "./readiness";

export type AppMode = "EDIT" | "TEST";

export type TestOutcome =
  | { state: "WOULD_RUN"; note: string }
  | { state: "UNSUPPORTED"; note: string }
  | { state: "NOT_SENT"; note: string }
  | { state: "NOT_RUN"; note: string }
  | { state: "READ_ONLY"; note: string }
  | { state: "NOT_READY"; note: string }
  | { state: "REJECTED" | "UNKNOWN" | "ERROR"; message: UserMessage }
  /** only ever built from a server answer that says it succeeded */
  | { state: "SUCCESS"; note: string; fromServer: true };

export const OUTCOME_LABEL: Readonly<Record<TestOutcome["state"], string>> = {
  WOULD_RUN: "Sẽ chạy (chưa thực hiện)", UNSUPPORTED: "Không hỗ trợ chạy thử", NOT_SENT: "Không gửi trong chế độ thử", NOT_RUN: "Không chạy trong chế độ thử",
  READ_ONLY: "Chỉ đọc", NOT_READY: "Chưa sẵn sàng", REJECTED: "Bị từ chối", UNKNOWN: "Chưa rõ kết quả", ERROR: "Lỗi", SUCCESS: "Thành công (theo máy chủ)",
};

export const TEST_RULES: readonly string[] = [
  "Thao tác ghi dữ liệu chỉ được mô tả là “sẽ chạy”, không thực hiện.",
  "Thao tác gọi nguồn dữ liệu chưa hỗ trợ chạy thử sẽ được ghi rõ là không hỗ trợ.",
  "Thông báo không được gửi thật.",
  "Xuất bản không chạy trong chế độ thử.",
  "Chạy thử workflow vẫn có thể tạo một bản ghi lượt chạy (workflow_run) trên máy chủ.",
];

export const testModeReadiness = (): Readiness => staticReadiness("TEST_MODE");

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
export function outcomeFromError(e: unknown): TestOutcome {
  const message = explainError(e);
  if (message.kind === "unknown-outcome") return { state: "UNKNOWN", message };
  if (message.kind === "rejected") return { state: "REJECTED", message };
  if (message.kind === "unavailable") return { state: "NOT_READY", note: message.detail };
  return { state: "ERROR", message };
}

/** a server answer -> outcome. SUCCESS needs an explicit success marker in the answer; anything else is shown as received, never promoted. */
export function outcomeFromServer(result: unknown): TestOutcome {
  const r = result as { status?: unknown; outcome?: unknown; dryRun?: unknown } | null;
  const status = typeof r?.status === "string" ? r.status.toUpperCase() : typeof r?.outcome === "string" ? r.outcome.toUpperCase() : "";
  if (status === "SUCCESS" || status === "SUCCEEDED") return { state: "SUCCESS", note: r?.dryRun === true ? "Chạy thử thành công (dry-run, theo máy chủ)." : "Máy chủ báo thành công.", fromServer: true };
  if (status === "UNSUPPORTED" || status === "DRY_RUN_UNSUPPORTED") return { state: "UNSUPPORTED", note: "Máy chủ báo nguồn dữ liệu không hỗ trợ chạy thử." };
  if (status === "REJECTED") return { state: "REJECTED", message: explainError({ code: "MUTATION_REJECTED" }) };
  if (status === "UNKNOWN" || status === "OUTCOME_UNKNOWN") return { state: "UNKNOWN", message: explainError({ code: "IDEMPOTENCY_OUTCOME_UNKNOWN", status: 409 }) };
  return { state: "NOT_READY", note: "Máy chủ trả về kết quả không nhận ra được, không hiển thị là thành công." };
}
