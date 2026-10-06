/**
 * UI state of every feature whose backend is not integrated yet: AVAILABLE | LOADING | ERROR | NOT_READY.
 * Rule of the whole Builder: a missing backend is "Chưa sẵn sàng" with a short reason. Nothing here returns data, and nothing pretends a call worked.
 */
export type Readiness =
  | { state: "AVAILABLE" }
  | { state: "LOADING" }
  | { state: "ERROR"; message: string }
  | { state: "NOT_READY"; reason: string };

export const available = (): Readiness => ({ state: "AVAILABLE" });
export const loading = (): Readiness => ({ state: "LOADING" });
export const notReady = (reason: string): Readiness => ({ state: "NOT_READY", reason });
export const failed = (message: string): Readiness => ({ state: "ERROR", message });
export const isReady = (r: Readiness): boolean => r.state === "AVAILABLE";

/** shape of ApiError that matters here (kept structural so this module does not import the API client) */
type ErrLike = { status?: number; code?: string; message?: string } | null | undefined;

/**
 * A probe answered with an error. 404 / 501 on an endpoint the contract promises means "not wired on this server yet" → NOT_READY;
 * 401/403 are authorisation (ERROR with the server's message); anything else is a real error to retry.
 */
export function readinessFromError(e: unknown, notReadyReason: string): Readiness {
  const x = e as ErrLike;
  if (x && (x.status === 404 || x.status === 501)) return notReady(notReadyReason);
  return failed(typeof x?.message === "string" && x.message ? x.message : "Không tải được. Hãy thử lại.");
}

export type FeatureKey =
  | "COMPONENT_METADATA" | "DEFINITION_OPERATIONS" | "DATA_SOURCES" | "SCHEMA_DISCOVERY" | "QUERY_PREVIEW" | "ACTION_RUNTIME"
  | "WORKFLOW_RUNTIME" | "TEST_MODE" | "SHARING" | "PAGE_REORDER" | "SET_HOME_PAGE";

/**
 * Features that have NO endpoint or operation in the frozen contract / the current backend. The reason names what is missing so the user (and C0)
 * knows what unblocks it. Features that DO have a contract endpoint (COMPONENT_METADATA) are probed at runtime instead, see `probeReadiness`.
 */
export const STATIC_NOT_READY: Partial<Record<FeatureKey, string>> = {
  DEFINITION_OPERATIONS: "Máy chủ chưa nhận thao tác dữ liệu/hành động (ADD_QUERY, ADD_ACTION…): AppDefinition V2 của C2 chưa được tích hợp.",
  DATA_SOURCES: "Chưa có API liệt kê nguồn dữ liệu đã được cấp quyền cho ứng dụng (Data Platform của C3 chưa nối vào máy chủ).",
  SCHEMA_DISCOVERY: "Chưa có API khám phá cấu trúc dữ liệu (DataGateway.discoverSchema chưa có đường HTTP).",
  QUERY_PREVIEW: "Chưa có API chạy thử truy vấn. Dữ liệu mẫu không được tạo ở trình duyệt.",
  ACTION_RUNTIME: "Chưa có adapter chạy hành động (ActionDataPort của C4 chưa được nối vào máy chủ).",
  WORKFLOW_RUNTIME: "Chưa có API khởi chạy và theo dõi workflow (WorkflowRuntime của C4 chưa nối vào máy chủ).",
  TEST_MODE: "Chưa có API Test Mode. “Dùng thử” sẽ chạy thật qua máy chủ, không giả lập thành công ở trình duyệt.",
  SHARING: "Chưa có API chia sẻ (T15). Chia sẻ khác với Xuất bản.",
  PAGE_REORDER: "Máy chủ chưa có thao tác đổi thứ tự trang. Thứ tự menu điều hướng thì đổi được.",
  SET_HOME_PAGE: "Máy chủ chưa có thao tác đặt trang chủ (trang chủ là phần gốc của tài liệu).",
};

export function staticReadiness(key: FeatureKey): Readiness {
  const reason = STATIC_NOT_READY[key];
  return reason ? notReady(reason) : available();
}
