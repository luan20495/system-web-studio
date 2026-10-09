/**
 * ONE place that turns a failure into words a person can act on (M-075). Vietnamese only.
 *
 * Rules (each is pinned by tests/builder/error-text.test.ts):
 *  - A non-ApiError is NEVER printed: `Error.message` of a SyntaxError / TypeError / DOMException is an implementation detail ("Unexpected token <", "Failed to fetch").
 *    The caller's fallback (or the generic sentence) is shown instead.
 *  - An ApiError is mapped by its stable `code` first (exact table, then name patterns), then by HTTP status class.
 *    The server's own `message` is shown ONLY when it is already written for people in Vietnamese (the backend's messages are English, so they are not shown).
 *  - The words "backend" and "CSRF" never reach a person.
 *  - A reference code (the request id) is attached when it helps support find the failure: server errors and codes this table does not know.
 */

/** structural view of ApiError (kept structural so this file has no import cycle and also reads the plain objects the tests build) */
export type ErrorLike = { status: number; code: string; message?: string; requestId?: string; retryAfterSeconds?: number; retryable?: boolean };

export type ErrorKind = "network" | "timeout" | "aborted" | "auth" | "forbidden" | "notfound" | "conflict" | "validation" | "ratelimit" | "busy" | "server" | "unknown";
export type ErrorParts = {
  /** the sentence to show */
  message: string;
  kind: ErrorKind;
  /** request id for support; only set when it is useful to show (see above) */
  reference?: string;
  code?: string;
  status?: number;
  /** true when repeating the SAME request is reasonable (network, timeout, busy, 5xx) */
  retryable: boolean;
};

export const GENERIC_ERROR = "Đã có lỗi xảy ra. Hãy thử lại; nếu lỗi lặp lại, liên hệ quản trị viên.";

/** exact code → sentence. `{n}` is replaced by the Retry-After seconds when the server sent them. */
export const ERROR_CODE_TEXT: Record<string, string> = {
  // the client itself (packages/api-client/core.ts)
  NETWORK: "Không kết nối được tới máy chủ. Kiểm tra mạng rồi thử lại.",
  TIMEOUT: "Máy chủ không phản hồi kịp. Nếu bạn vừa lưu hoặc gửi dữ liệu, hãy kiểm tra lại kết quả trước khi làm lại.",
  ABORTED: "Đã hủy yêu cầu.",
  STREAM_ENDED: "Kết nối AI bị ngắt trước khi có kết quả. Hãy thử lại.",
  AI_STREAM_FAILED: "AI không trả được kết quả. Hãy thử lại hoặc chọn mô hình khác.",
  CSRF_UNAVAILABLE: "Phiên làm việc chưa sẵn sàng. Tải lại trang rồi thử lại.",
  BAD_RESPONSE: "Máy chủ trả về dữ liệu không đọc được. Hãy thử lại.",
  // session / permission
  AUTHENTICATION_REQUIRED: "Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.",
  SESSION_EXPIRED: "Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.",
  INVALID_CREDENTIALS: "Sai tên đăng nhập hoặc mật khẩu.",
  ACCOUNT_DISABLED: "Tài khoản đã bị khóa. Liên hệ quản trị viên.",
  USER_DISABLED: "Tài khoản đã bị khóa. Liên hệ quản trị viên.",
  LOCAL_LOGIN_DISABLED: "Công ty này chỉ cho đăng nhập qua SSO.",
  CSRF_INVALID: "Phiên làm việc không còn hợp lệ. Tải lại trang rồi thử lại.",
  FORBIDDEN: "Bạn không có quyền thực hiện thao tác này.",
  ADMIN_REQUIRED: "Màn hình này chỉ dành cho quản trị hệ thống.",
  NOT_WORKSPACE_MEMBER: "Bạn không thuộc workspace này.",
  SELF_GRANT_FORBIDDEN: "Bạn không thể tự cấp quyền hoặc tự đổi vai trò của chính mình.",
  SELF_REVIEW: "Bạn không thể tự duyệt nội dung do chính mình gửi.",
  // request shape
  VALIDATION_FAILED: "Dữ liệu nhập chưa hợp lệ. Kiểm tra lại các trường rồi thử lại.",
  MALFORMED_REQUEST: "Yêu cầu không hợp lệ. Tải lại trang rồi thử lại.",
  MISSING_PARAMETER: "Yêu cầu thiếu thông tin. Tải lại trang rồi thử lại.",
  MISSING_HEADER: "Yêu cầu không hợp lệ. Tải lại trang rồi thử lại.",
  INVALID_PARAMETER: "Giá trị không hợp lệ.",
  METHOD_NOT_ALLOWED: "Thao tác này không được hỗ trợ.",
  UNSUPPORTED_MEDIA_TYPE: "Định dạng tệp không được hỗ trợ.",
  WEAK_PASSWORD: "Mật khẩu chưa đủ mạnh: tối thiểu 8 ký tự, gồm cả chữ và số, không chứa tên đăng nhập.",
  INVALID_USERNAME: "Tên đăng nhập không hợp lệ.",
  INVALID_EMAIL: "Email không hợp lệ.",
  INVALID_VALUE: "Giá trị không hợp lệ.",
  INVALID_ROLE: "Vai trò không hợp lệ.",
  INVALID_SCOPE: "Phạm vi không hợp lệ.",
  INVALID_PATH: "Đường dẫn không hợp lệ.",
  INVALID_BASE_URL: "Địa chỉ máy chủ không hợp lệ.",
  INVALID_HOSTNAME: "Tên miền không hợp lệ.",
  INVALID_MODEL: "Mô hình không hợp lệ.",
  INVALID_UPLOAD: "Tệp tải lên không hợp lệ.",
  KEY_REQUIRED: "Cần nhập khóa API.",
  NAME_REQUIRED: "Cần nhập tên.",
  COMMENT_REQUIRED: "Cần nhập lý do.",
  CONFIRMATION_REQUIRED: "Cần xác nhận trước khi thực hiện.",
  EXCHANGE_RATE_REQUIRED: "Cần nhập tỷ giá.",
  OUT_OF_RANGE: "Giá trị nằm ngoài khoảng cho phép.",
  FILE_TOO_LARGE: "Tệp quá lớn.",
  BODY_TOO_LARGE: "Dữ liệu gửi lên quá lớn.",
  OUTPUT_TOO_LARGE: "Kết quả quá lớn.",
  QUERY_TOO_SHORT: "Từ khóa tìm kiếm quá ngắn.",
  SCHEMA_INVALID: "Trang chưa hợp lệ. Xem danh sách lỗi và sửa trước khi lưu.",
  MUTATION_REJECTED: "Thay đổi bị từ chối vì chưa hợp lệ.",
  // uniqueness / state
  USERNAME_TAKEN: "Tên đăng nhập đã được dùng.",
  EMAIL_TAKEN: "Email đã được dùng.",
  NAME_TAKEN: "Tên này đã được dùng.",
  DOMAIN_TAKEN: "Tên miền này đã được dùng.",
  TENANT_SLUG_TAKEN: "Mã công ty này đã được dùng.",
  ALREADY_MEMBER: "Người này đã là thành viên.",
  PROVIDER_EXISTS: "Nhà cung cấp này đã tồn tại.",
  RULE_EXISTS: "Quy tắc này đã tồn tại.",
  CONFLICT: "Thao tác xung đột với dữ liệu hiện có. Kiểm tra lại rồi thử lại.",
  REVISION_CONFLICT: "Dữ liệu vừa được thay đổi ở nơi khác. Tải lại bản mới nhất rồi làm lại.",
  IDEMPOTENCY_OUTCOME_UNKNOWN: "Chưa biết thao tác đã được thực hiện hay chưa. Kiểm tra dữ liệu trước khi làm lại.",
  IDEMPOTENCY_IN_PROGRESS: "Thao tác này đang được xử lý. Đợi vài giây rồi kiểm tra lại.",
  IDEMPOTENCY_KEY_REUSED: "Thao tác này đã được gửi với nội dung khác. Tải lại trang rồi thử lại.",
  LAST_OWNER: "Ứng dụng phải còn ít nhất một chủ sở hữu.",
  LAST_ADMIN: "Workspace phải còn ít nhất một quản trị viên.",
  LAST_SYSTEM_ADMIN: "Không thể gỡ quản trị hệ thống cuối cùng.",
  LAST_TENANT_ADMIN: "Công ty phải còn ít nhất một quản trị viên.",
  TENANT_SLUG_INVALID: "Mã công ty không hợp lệ.",
  TENANT_NAME_INVALID: "Tên công ty không hợp lệ.",
  TENANT_NOT_FOUND: "Không tìm thấy công ty.",
  TENANT_MEMBER_NOT_FOUND: "Người này không còn là thành viên của công ty.",
  USER_NOT_FOUND: "Không tìm thấy người dùng này.",
  MEMBER_NOT_FOUND: "Không tìm thấy thành viên.",
  PERMISSION_DENIED: "Bạn không có quyền thực hiện thao tác này.",
  LINK_INVALID: "Liên kết kích hoạt đã dùng hoặc hết hạn: hãy tạo liên kết mới.",
  CANNOT_DISABLE_SELF: "Bạn không thể khóa chính tài khoản của mình.",
  CANNOT_CHANGE_SELF: "Bạn không thể đổi quyền của chính mình.",
  MANAGED_BY_SYSTEM: "Mục này do hệ thống quản lý, không sửa được.",
  DEFAULT_TENANT_PROTECTED: "Công ty mặc định không thể bị tạm khóa hoặc xóa.",
  BUILT_IN_TEMPLATE: "Mẫu có sẵn không sửa được.",
  NOT_CONFIGURED: "Tính năng này chưa được cấu hình.",
  NOT_IMPLEMENTED: "Tính năng này chưa được triển khai.",
  // quota / AI
  AI_DAILY_LIMIT: "Đã hết lượt AI trong ngày. Thử lại vào ngày mai hoặc liên hệ quản trị viên.",
  AI_TOKEN_LIMIT: "Đã hết hạn mức token AI. Liên hệ quản trị viên.",
  AI_BUDGET_EXCEEDED: "Đã hết ngân sách AI. Liên hệ quản trị viên.",
  AI_NO_PAID_BUDGET: "Mô hình trả phí chưa có ngân sách. Chọn mô hình miễn phí hoặc liên hệ quản trị viên.",
  AI_COST_UNKNOWN: "Chưa tính được chi phí của mô hình này. Chọn mô hình khác.",
  AI_STREAMS_PER_USER: "Bạn đang chạy quá nhiều yêu cầu AI cùng lúc. Đợi một yêu cầu xong rồi thử lại.",
  AI_BUSY: "AI đang bận. Thử lại sau vài giây.",
  MODEL_NOT_ALLOWED: "Bạn không được dùng mô hình này.",
  MODEL_NOT_ENABLED: "Mô hình này chưa được bật.",
  PROJECT_LIMIT: "Đã đạt số ứng dụng tối đa.",
  STORAGE_QUOTA: "Đã hết dung lượng lưu trữ.",
  ARTIFACT_STORAGE_QUOTA: "Đã hết dung lượng lưu trữ.",
  RATE_LIMITED: "Bạn thao tác quá nhanh. Thử lại sau {n}.",
  // availability
  LOGIN_BUSY: "Hệ thống đang bận. Thử lại sau vài giây.",
  SCOPE_BUSY: "Hệ thống đang bận. Thử lại sau vài giây.",
  SERVER_APPS_UNAVAILABLE: "Tính năng ứng dụng máy chủ chưa sẵn sàng.",
  CODE_PROJECTS_UNAVAILABLE: "Tính năng dự án mã nguồn chưa sẵn sàng.",
  SECRETS_UNAVAILABLE: "Kho khóa bí mật chưa sẵn sàng.",
  ENCRYPTION_UNAVAILABLE: "Mã hóa chưa sẵn sàng.",
  GIT_SERVER_ERROR: "Máy chủ mã nguồn gặp lỗi. Thử lại sau.",
  INTERNAL_ERROR: "Máy chủ gặp lỗi. Hãy thử lại; nếu lỗi lặp lại, gửi mã tham chiếu cho quản trị viên.",
  NOT_FOUND: "Không tìm thấy mục này hoặc bạn không có quyền xem."
};

/** name patterns, tried in order when the exact table has no entry */
const CODE_PATTERNS: Array<[RegExp, string]> = [
  [/_NOT_FOUND$/, "Không tìm thấy mục này hoặc bạn không có quyền xem."],
  [/_TAKEN$|_EXISTS$/, "Giá trị này đã tồn tại."],
  [/^INVALID_|_INVALID$/, "Giá trị không hợp lệ."],
  [/_REQUIRED$/, "Còn thiếu thông tin bắt buộc."],
  [/^TOO_MANY_/, "Đã vượt quá số lượng cho phép."],
  [/_UNAVAILABLE$/, "Tính năng này chưa sẵn sàng."],
  [/^NOT_|_NOT_/, "Thao tác này không áp dụng được ở trạng thái hiện tại."],
  [/_TOO_LARGE$/, "Dữ liệu quá lớn."]
];

const HAS_VIETNAMESE = /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i;
/** true for text the backend / client wrote FOR PEOPLE in Vietnamese (the backend's English messages are not shown) */
export const isVietnamese = (s: string | undefined): boolean => !!s && HAS_VIETNAMESE.test(s);

export function isErrorLike(e: unknown): e is ErrorLike {
  return typeof e === "object" && e !== null && typeof (e as ErrorLike).status === "number" && typeof (e as ErrorLike).code === "string";
}

function kindOf(e: ErrorLike): ErrorKind {
  if (e.code === "TIMEOUT") return "timeout";
  if (e.code === "ABORTED") return "aborted";
  if (e.status === 0) return "network";
  if (e.status === 401) return "auth";
  if (e.status === 403) return "forbidden";
  if (e.status === 404) return "notfound";
  if (e.status === 409) return "conflict";
  if (e.status === 429) return "ratelimit";
  if (e.status === 503 || /_BUSY$/.test(e.code)) return "busy";
  if (e.status >= 500) return "server";
  if (e.status >= 400) return "validation";
  return "unknown";
}

const secondsText = (n: number) => `${n} giây`;

/** the sentence + kind + reference for any thrown value. `fallback` is the caller's own words for "an unknown thing failed" (never an Error.message). */
export function errorParts(e: unknown, fallback: string = GENERIC_ERROR): ErrorParts {
  if (!isErrorLike(e)) return { message: fallback, kind: "unknown", retryable: false };
  const kind = kindOf(e);
  let message: string | undefined = ERROR_CODE_TEXT[e.code];
  let known = message !== undefined;
  // a Vietnamese message written by the server for a rejected request (400/404/409/422…) is more specific than the table; session / permission / server failures stay on the table
  const specific = !e.code.startsWith("HTTP_") && isVietnamese(e.message) && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 403 && e.status !== 429 ? e.message : undefined;
  if (specific) message = specific;
  if (message === undefined) { message = CODE_PATTERNS.find(([re]) => re.test(e.code))?.[1]; known = message !== undefined; }
  if (message === undefined && !e.code.startsWith("HTTP_") && isVietnamese(e.message)) message = e.message;
  if (message === undefined) {
    message = kind === "network" ? ERROR_CODE_TEXT.NETWORK
      : kind === "auth" ? ERROR_CODE_TEXT.AUTHENTICATION_REQUIRED
      : kind === "forbidden" ? ERROR_CODE_TEXT.FORBIDDEN
      : kind === "notfound" ? ERROR_CODE_TEXT.NOT_FOUND
      : kind === "conflict" ? ERROR_CODE_TEXT.CONFLICT
      : kind === "ratelimit" ? ERROR_CODE_TEXT.RATE_LIMITED
      : kind === "busy" ? ERROR_CODE_TEXT.SCOPE_BUSY
      : kind === "server" ? ERROR_CODE_TEXT.INTERNAL_ERROR
      : kind === "validation" ? ERROR_CODE_TEXT.VALIDATION_FAILED
      : fallback;
  }
  if (message.includes("{n}")) message = message.replace("{n}", e.retryAfterSeconds ? secondsText(e.retryAfterSeconds) : "ít phút");
  const retryable = e.retryable ?? (kind === "network" || kind === "timeout" || kind === "busy" || kind === "ratelimit" || kind === "server");
  const showRef = !!e.requestId && (e.status >= 500 || !known);
  return { message, kind, reference: showRef ? e.requestId : undefined, code: e.code, status: e.status, retryable };
}

/** one string for a banner / toast: the sentence, plus "(Mã tham chiếu: …)" when support will need it */
export function errorText(e: unknown, fallback: string = GENERIC_ERROR): string {
  const p = errorParts(e, fallback);
  return p.reference ? `${p.message} (Mã tham chiếu: ${p.reference})` : p.message;
}

/** Where a client-side error is sent (an error boundary, a global handler). No endpoint exists yet: the app wires one with setErrorReporter, the default is a no-op. */
export type ErrorReport = { error: unknown; where: string; reference?: string };
let reporter: ((r: ErrorReport) => void) | null = null;
export const setErrorReporter = (fn: ((r: ErrorReport) => void) | null) => { reporter = fn; };
export function reportClientError(error: unknown, where: string): void {
  try { reporter?.({ error, where, reference: isErrorLike(error) ? error.requestId : undefined }); } catch { /* reporting must never throw */ }
}
