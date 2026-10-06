/**
 * What the user reads for each backend error (Vietnamese, no raw codes). The codes come from the contract mirror (API_ERROR); 409
 * IDEMPOTENCY_OUTCOME_UNKNOWN and 422 MUTATION_REJECTED are the C3 write-path errors the Builder's Test mode has to explain.
 */
import { API_ERROR, NOT_EXECUTABLE_CODES, RUNTIME_NOT_EXECUTABLE_CODES, RUNTIME_NOT_FOUND_CODES } from "./contract";

type ErrLike = { status?: number; code?: string; message?: string; details?: unknown; retryable?: boolean } | null | undefined;

export type UserMessage = {
  /** machine-readable class for the UI (colour, icon, retry button) */
  kind: "conflict" | "not-found" | "timeout" | "invalid" | "unknown-outcome" | "rejected" | "in-progress" | "key-conflict" | "not-executable" | "rate-limited" | "forbidden" | "suspended" | "unavailable" | "error";
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

/**
 * `opts.write`: the failed request was a data WRITE (mutation / action that writes). Then a 500/502/504 on the first attempt means the outcome is
 * UNKNOWN (data-runtime.md §4b: "it may have been applied"), never "failed, press again". A plain read keeps the generic error.
 */
export function explainError(e: unknown, opts: { write?: boolean } = {}): UserMessage {
  const x = e as ErrLike;
  const code = x?.code;
  // C4 action runtime codes (logic/action/ActionResult.kt ActionErrorCodes): same user-facing classes as the data-layer ones
  if (code === "ACTION_IN_PROGRESS") return explainError({ ...x, code: API_ERROR.IDEMPOTENCY_IN_PROGRESS }, opts);
  if (code === "IDEMPOTENCY_KEY_REUSED" || code === "IDEMPOTENCY_KEY_INVALID" || code === "IDEMPOTENCY_KEY_REQUIRED") return explainError({ ...x, code: API_ERROR.IDEMPOTENCY_CONFLICT }, opts);
  if (code === "RUNTIME_STORES_VOLATILE") {
    return { kind: "unavailable", title: "Máy chủ chưa lưu bền được lượt chạy", detail: "Môi trường này chưa cấu hình kho lưu trữ bền cho thao tác ghi/workflow. Không có gì đã được thực hiện.", retrySafe: false };
  }
  if (code === "INVALID_REQUEST") return { kind: "invalid", title: "Yêu cầu không hợp lệ", detail: x?.message || "Máy chủ từ chối yêu cầu (trường không hợp lệ).", retrySafe: false };
  if (code === "INVALID_PARAMS") return { kind: "invalid", title: "Tham số truy vấn chưa hợp lệ", detail: x?.message || "Kiểm tra lại tham số rồi thử lại.", retrySafe: true };
  if ((RUNTIME_NOT_EXECUTABLE_CODES as readonly string[]).includes(code ?? "")) {
    return { kind: "not-executable", title: "Cấu hình hiện tại chưa chạy được", detail: x?.message || "Chưa có dữ liệu nào bị thay đổi. Kiểm tra liên kết nguồn dữ liệu / ánh xạ của ứng dụng rồi lưu lại.", retrySafe: false };
  }
  if ((RUNTIME_NOT_FOUND_CODES as readonly string[]).includes(code ?? "")) {
    return { kind: "not-found", title: "Không tìm thấy mục cần chạy", detail: "Mục này chưa được lưu vào ứng dụng hoặc đã bị xóa (chế độ thử dùng bản nháp đã lưu; chế độ thật dùng bản đã xuất bản).", retrySafe: false };
  }
  if (code === "TIMEOUT" && !opts.write) return { kind: "timeout", title: "Hết thời gian chờ", detail: "Máy chủ không phản hồi kịp. Thử lại sau.", retrySafe: true };
  if (code === "TENANT_DISABLED") return { kind: "suspended", title: "Công ty đang bị tạm khóa", detail: "Liên hệ quản trị viên công ty.", retrySafe: false };
  if (code === "DEPENDENCY_UNAVAILABLE" || code === "AUDIT_UNAVAILABLE" || x?.status === 503) {
    return { kind: "unavailable", title: "Hệ thống tạm thời chưa phục vụ được", detail: "Một dịch vụ phía sau đang không sẵn sàng. Thử lại sau ít phút.", retrySafe: !opts.write };
  }
  if (code === "INVALID_INPUT" || code === "INVALID_DEFINITION" || code === "LIMIT_EXCEEDED") {
    return { kind: "invalid", title: code === "LIMIT_EXCEEDED" ? "Vượt giới hạn cho phép" : "Dữ liệu nhập chưa hợp lệ", detail: x?.message || "Kiểm tra lại giá trị nhập vào rồi thử lại.", retrySafe: true };
  }
  if (code === "IDEMPOTENCY_OUTCOME_UNKNOWN" && x?.status !== 409) return explainError({ ...x, status: 409 }, opts);
  if (code === API_ERROR.IDEMPOTENCY_IN_PROGRESS) {
    return { kind: "in-progress", title: "Thao tác này đang được xử lý", detail: "Yêu cầu trước với cùng khóa vẫn đang chạy. Đợi vài giây rồi kiểm tra kết quả; đừng gửi thao tác mới.", retrySafe: true };
  }
  if (code === API_ERROR.IDEMPOTENCY_CONFLICT) {
    return { kind: "key-conflict", title: "Khóa chống ghi trùng bị dùng lại với dữ liệu khác", detail: "Đây là lỗi của ứng dụng, không phải của bạn. Hãy tải lại trang rồi thử lại; nếu vẫn lỗi, gửi mã yêu cầu cho quản trị viên.", retrySafe: false };
  }
  if ((NOT_EXECUTABLE_CODES as readonly string[]).includes(code ?? "")) {
    return { kind: "not-executable", title: "Thao tác không thực hiện được với cấu hình hiện tại", detail: "Không có dữ liệu nào bị thay đổi. Sửa cấu hình truy vấn/ánh xạ của ứng dụng rồi thử lại.", retrySafe: false };
  }
  if (x?.status === 429 || code === API_ERROR.RATE_LIMITED) {
    return { kind: "rate-limited", title: "Thao tác quá nhanh", detail: x?.message || "Đợi một chút rồi thử lại.", retrySafe: true };
  }
  if (opts.write && (x?.status === 500 || x?.status === 502 || x?.status === 504 || x?.status === 0)) {
    return { kind: "unknown-outcome", title: "Chưa rõ thao tác đã được ghi hay chưa",
      detail: "Máy chủ không phản hồi kịp lúc ghi. Đừng bấm lại ngay: hãy kiểm tra dữ liệu trong nguồn trước, vì bấm lại có thể ghi hai lần.", retrySafe: false };
  }
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
  return { kind: "error", title: "Không thực hiện được", detail: x?.message || "Đã có lỗi. Hãy thử lại.", retrySafe: x?.retryable !== false };
}
