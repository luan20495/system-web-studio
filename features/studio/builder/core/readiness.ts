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
  | "COMPONENT_METADATA" | "DEFINITION_OPERATIONS" | "DATA_SOURCES" | "SCHEMA_DISCOVERY" | "QUERY_PREVIEW"
  | "SHARING" | "PAGE_REORDER" | "SET_HOME_PAGE";

/**
 * Features that have NO endpoint or operation in the frozen contract / the current backend. The reason names what is missing so the user (and C0)
 * knows what unblocks it. Features that DO have a contract endpoint (COMPONENT_METADATA) are probed at runtime instead, see `probeReadiness`.
 */
export const STATIC_NOT_READY: Partial<Record<FeatureKey, string>> = {
  DEFINITION_OPERATIONS: "Máy chủ chưa nhận thao tác dữ liệu/hành động (ADD_QUERY, ADD_ACTION…): máy chủ chưa hỗ trợ định nghĩa ứng dụng phiên bản 2.",
  DATA_SOURCES: "Chưa kết nối máy chủ để quản lý nguồn dữ liệu (không có nguồn nào được giả lập ở trình duyệt).",
  SCHEMA_DISCOVERY: "Chưa có API khám phá cấu trúc dữ liệu (DataGateway.discoverSchema chưa có đường HTTP).",
  QUERY_PREVIEW: "Xem trước dữ liệu ngay trong trình soạn chưa được nối. Dùng chế độ “Dùng thử” → Truy vấn để chạy truy vấn thật qua máy chủ; dữ liệu mẫu không được tạo ở trình duyệt.",
  SHARING: "Chưa có API chia sẻ (T15). Chia sẻ khác với Xuất bản.",
  PAGE_REORDER: "Máy chủ chưa có thao tác đổi thứ tự trang. Thứ tự menu điều hướng thì đổi được.",
  SET_HOME_PAGE: "Máy chủ chưa có thao tác đặt trang chủ (trang chủ là phần gốc của tài liệu).",
};

/**
 * R1–R3 (docs/contracts/v2/runtime-api.md) sit behind server flags. A flag that is OFF means the controller is not mounted: 404 with NO domain code.
 * A 404 WITH a domain code (QUERY_NOT_FOUND, UNKNOWN_ACTION…) is a missing id, not a missing feature. 503 DATA_RUNTIME_UNAVAILABLE is "wired off".
 */
export function runtimeReadinessFromError(e: unknown, feature: "queries" | "actions" | "workflows"): Readiness | null {
  const x = e as ErrLike;
  const code = x?.code ?? "";
  const flag = feature === "queries" ? "app.data-platform.enabled" : "app.workflow.enabled";
  const noDomainCode = code === "" || /^HTTP_\d+$/.test(code);
  if ((x?.status === 404 && noDomainCode) || x?.status === 501) return notReady(`Máy chủ chưa bật tính năng này (${flag} đang tắt hoặc chưa được nối).`);
  if (code === "DATA_RUNTIME_UNAVAILABLE") return notReady("Máy chủ chưa có DataGateway cho môi trường này (DATA_RUNTIME_UNAVAILABLE).");
  return null;
}

export function staticReadiness(key: FeatureKey): Readiness {
  const reason = STATIC_NOT_READY[key];
  return reason ? notReady(reason) : available();
}
