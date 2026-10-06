/**
 * What the user reads for each backend error (Vietnamese, no raw codes). The codes come from the contract mirror (API_ERROR); 409
 * IDEMPOTENCY_OUTCOME_UNKNOWN and 422 MUTATION_REJECTED are the C3 write-path errors the Builder's Test mode has to explain.
 */
import { API_ERROR } from "./contract";

type ErrLike = { status?: number; code?: string; message?: string; details?: unknown } | null | undefined;

export type UserMessage = {
  /** machine-readable class for the UI (colour, icon, retry button) */
  kind: "conflict" | "invalid" | "unknown-outcome" | "rejected" | "forbidden" | "suspended" | "unavailable" | "error";
  title: string;
  detail: string;
  /** safe to press the same button again (false for an ambiguous write: pressing again may write twice) */
  retrySafe: boolean;
};

export type Violation = { path: string; message: string };

export function violationsOf(e: unknown): Violation[] {
  const d = (e as ErrLike)?.details as { violations?: unknown } | undefined;
  if (!d || !Array.isArray(d.violations)) return [];
  return d.violations.filter((v): v is Violation => !!v && typeof (v as Violation).path === "string" && typeof (v as Violation).message === "string");
}

export function explainError(e: unknown): UserMessage {
  const x = e as ErrLike;
  const code = x?.code;
  if (code === API_ERROR.IDEMPOTENCY_OUTCOME_UNKNOWN || (x?.status === 409 && code === "IDEMPOTENCY_OUTCOME_UNKNOWN")) {
    return { kind: "unknown-outcome", title: "Chưa rõ thao tác đã được ghi hay chưa",
      detail: "Kết nối tới nguồn dữ liệu bị gián đoạn đúng lúc ghi. Đừng bấm lại ngay: hãy kiểm tra dữ liệu trong nguồn trước, vì bấm lại có thể ghi hai lần.", retrySafe: false };
  }
  if (code === API_ERROR.MUTATION_REJECTED) {
    return { kind: "rejected", title: "Nguồn dữ liệu từ chối thay đổi",
      detail: x?.message ? `${x.message}` : "Dữ liệu gửi đi không hợp lệ với nguồn dữ liệu. Kiểm tra giá trị các trường rồi thử lại.", retrySafe: true };
  }
  if (code === API_ERROR.REVISION_CONFLICT || x?.status === 409) {
    return { kind: "conflict", title: "Ứng dụng vừa được thay đổi ở nơi khác", detail: "Đã tải lại bản mới nhất. Hãy xem lại rồi thực hiện lại thao tác.", retrySafe: true };
  }
  if (code === API_ERROR.SCHEMA_INVALID || x?.status === 422) {
    const v = violationsOf(e);
    return { kind: "invalid", title: "Thay đổi chưa hợp lệ",
      detail: v.length ? v.slice(0, 3).map((i) => i.message).join(" · ") : x?.message || "Máy chủ từ chối vì định nghĩa ứng dụng không hợp lệ.", retrySafe: true };
  }
  if (code === API_ERROR.TENANT_SUSPENDED) return { kind: "suspended", title: "Công ty đang bị tạm khóa", detail: "Liên hệ quản trị viên công ty.", retrySafe: false };
  if (x?.status === 401 || x?.status === 403) return { kind: "forbidden", title: "Bạn không có quyền thực hiện thao tác này", detail: "Quyền được kiểm tra ở máy chủ. Liên hệ chủ ứng dụng nếu cần.", retrySafe: false };
  if (x?.status === 404 || x?.status === 501) return { kind: "unavailable", title: "Chức năng này chưa sẵn sàng", detail: "Máy chủ chưa có chức năng tương ứng.", retrySafe: false };
  return { kind: "error", title: "Không thực hiện được", detail: x?.message || "Đã có lỗi. Hãy thử lại.", retrySafe: true };
}
