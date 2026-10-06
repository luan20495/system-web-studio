/**
 * Pure logic of the data-source management UI (C3 MANAGEMENT_API.md @ e606465). No React, no fetch: the panel and the unit tests share it.
 * Rules this file enforces for the whole UI:
 *  - no secret is ever read back: the only credential state is CredentialMetadata (key NAMES);
 *  - a 404 on a management route means "not there OR not yours" (identical on purpose), except a 404 with NO domain code, which means the controller is not mounted (flag off);
 *  - a failed connection test is a RESULT (HTTP 200, `ok:false`), not an error;
 *  - an ambiguous write is never retried by the UI on its own.
 */
import type { AppDefinitionV2, BindingMode, ConnectionTestResult, ConnectorDescriptor, CredentialMetadata, DataBinding, DataSourceView } from "@xweb/types";
import type { Readiness } from "./readiness";
import { notReady } from "./readiness";
import type { UserMessage } from "./errors";

/** The calls the panel needs, already bound to the workspace and project by the host. No host = NOT_READY (nothing is simulated). */
export type DataManagementCalls = {
  connectors: () => Promise<ConnectorDescriptor[]>;
  list: () => Promise<DataSourceView[]>;
  create: (body: { name: string; type: string; config?: Record<string, string>; credential?: Record<string, string> }) => Promise<DataSourceView>;
  update: (id: string, body: { name?: string; config?: Record<string, string>; status?: "ACTIVE" | "DISABLED" }) => Promise<DataSourceView>;
  remove: (id: string) => Promise<void>;
  credential: (id: string) => Promise<CredentialMetadata>;
  setCredential: (id: string, credential: Record<string, string>) => Promise<CredentialMetadata>;
  removeCredential: (id: string) => Promise<void>;
  test: (id: string) => Promise<ConnectionTestResult>;
  listBindings: () => Promise<DataBinding[]>;
  bind: (mode: BindingMode, slotId: string, dataSourceId: string) => Promise<DataBinding>;
  unbind: (mode: BindingMode, slotId: string) => Promise<void>;
};

type ErrLike = { status?: number; code?: string; message?: string; retryable?: boolean } | null | undefined;
const noDomainCode = (code: string | undefined) => !code || /^HTTP_\d+$/.test(code);

/**
 * 404 without a domain code, or 501 on a LIST / CONNECTORS probe: the controller is not mounted (`app.data-platform.enabled` is off) → NOT_READY.
 * 404 WITH a code (NOT_FOUND, WORKSPACE_NOT_FOUND, PROJECT_NOT_FOUND) is "not there or not yours", never "feature off".
 */
export function managementReadinessFromError(e: unknown): Readiness | null {
  const x = e as ErrLike;
  if ((x?.status === 404 && noDomainCode(x.code)) || x?.status === 501 && noDomainCode(x.code)) {
    return notReady("Máy chủ chưa bật quản lý nguồn dữ liệu (app.data-platform.enabled đang tắt hoặc chưa được nối).");
  }
  return null;
}

export type ManagementMessage = UserMessage & { field?: "name" | "config" | "credential" };

/**
 * User-facing text for a management failure. `write` = the failed request changed (or may have changed) server state: after a network failure or a timeout the
 * outcome is unknown, so the UI asks the user to reload the list first instead of pressing again. Server messages are fixed text (never contain a request value) and are safe to show.
 */
export function explainManagementError(e: unknown, opts: { write?: boolean } = {}): ManagementMessage {
  const x = e as ErrLike; const code = x?.code ?? ""; const status = x?.status;
  const server = typeof x?.message === "string" && x.message ? x.message : "";
  if (status === 0) {
    const timeout = code === "TIMEOUT";
    return opts.write
      ? { kind: timeout ? "timeout" : "unknown-outcome", title: timeout ? "Máy chủ không phản hồi kịp" : "Mất kết nối khi đang lưu",
          detail: "Chưa rõ thay đổi đã được ghi hay chưa. Hãy tải lại danh sách để kiểm tra trước khi thử lại.", retrySafe: false }
      : { kind: timeout ? "timeout" : "unavailable", title: timeout ? "Hết thời gian chờ" : "Không kết nối được máy chủ", detail: "Kiểm tra kết nối mạng rồi thử lại.", retrySafe: true };
  }
  if (status === 401) return { kind: "forbidden", title: "Phiên đăng nhập đã hết hạn", detail: "Đăng nhập lại rồi thử lại.", retrySafe: false };
  if (status === 403 || code === "PERMISSION_DENIED") {
    return { kind: "forbidden", title: "Bạn không có quyền quản lý nguồn dữ liệu", detail: "Cần quyền quản lý nguồn dữ liệu của không gian làm việc (quản trị viên). Quyền được kiểm tra ở máy chủ.", retrySafe: false };
  }
  if (status === 404) {
    if (noDomainCode(code)) return { kind: "unavailable", title: "Chức năng chưa bật trên máy chủ", detail: "Máy chủ chưa bật quản lý nguồn dữ liệu.", retrySafe: false };
    return { kind: "not-found", title: "Không tìm thấy", detail: "Mục này không tồn tại hoặc không thuộc không gian làm việc của bạn. Tải lại danh sách.", retrySafe: false };
  }
  if (status === 409 && code === "DISABLED") return { kind: "unavailable", title: "Nguồn dữ liệu đang tắt", detail: "Bật lại nguồn rồi kiểm tra kết nối.", retrySafe: false };
  if (status === 409) return { kind: "conflict", title: "Xung đột", detail: server || "Tên đã được dùng trong công ty, hoặc nguồn đang được liên kết với một ứng dụng (hãy bỏ liên kết trước khi xóa).", retrySafe: false };
  if (status === 400 || code === "INVALID_PARAMS" || code === "INVALID_CONFIG" || code === "INVALID_CREDENTIAL") {
    const field = code === "INVALID_CREDENTIAL" ? "credential" : code === "INVALID_CONFIG" ? "config" : undefined;
    return { kind: "invalid", title: "Thông tin chưa hợp lệ", detail: server || "Kiểm tra lại các trường rồi thử lại.", retrySafe: true, field };
  }
  if (status === 422 || code === "UNSUPPORTED_TYPE") return { kind: "invalid", title: "Loại nguồn dữ liệu không được hỗ trợ", detail: "Chọn một loại có trong danh mục.", retrySafe: false };
  if (status === 429 || code === "RATE_LIMITED") return { kind: "rate-limited", title: "Thao tác quá nhanh", detail: server || "Đợi một chút rồi thử lại.", retrySafe: true };
  if (status === 501 || code === "NOT_IMPLEMENTED") return { kind: "unavailable", title: "Loại nguồn này chưa được hỗ trợ", detail: "Connector này mới nằm trong kế hoạch.", retrySafe: false };
  if (status && status >= 500) {
    return opts.write
      ? { kind: "unknown-outcome", title: "Máy chủ gặp lỗi khi lưu", detail: "Chưa rõ thay đổi đã được ghi hay chưa. Tải lại danh sách để kiểm tra trước khi thử lại.", retrySafe: false }
      : { kind: "error", title: "Máy chủ gặp lỗi", detail: "Thử lại sau ít phút.", retrySafe: true };
  }
  return { kind: "error", title: "Không thực hiện được", detail: server || "Đã có lỗi. Hãy thử lại.", retrySafe: x?.retryable !== false };
}

// ------------------------------------------------------------------------------------------------------------------- connection test

const FAIL_TEXT: Record<string, string> = {
  AUTH_REJECTED: "Nguồn từ chối tên đăng nhập hoặc mật khẩu. Cập nhật khóa kết nối rồi thử lại.",
  CONNECT_FAILED: "Không kết nối được tới máy chủ dữ liệu. Kiểm tra địa chỉ, cổng và tường lửa.",
  HOST_UNRESOLVED: "Không phân giải được tên máy chủ. Kiểm tra lại địa chỉ.",
  ADDRESS_BLOCKED: "Địa chỉ bị chặn (địa chỉ nội bộ, loopback hoặc của chính nền tảng). Chỉ dùng máy chủ công khai.",
  TLS_FAILED: "Không xác thực được chứng chỉ TLS của máy chủ dữ liệu.",
  TIMEOUT: "Máy chủ dữ liệu không phản hồi kịp.",
  ROLE_TOO_PRIVILEGED: "Tài khoản kết nối có quyền quá cao (siêu quản trị). Dùng một vai trò chỉ có quyền cần thiết.",
  INVALID_CREDENTIAL: "Khóa kết nối thiếu trường bắt buộc. Cập nhật khóa kết nối đầy đủ.",
  NOT_IMPLEMENTED: "Loại nguồn này chưa hỗ trợ kiểm tra kết nối.",
  INTERNAL: "Máy chủ gặp lỗi nội bộ khi kiểm tra kết nối.",
};

export type TestView = { state: "OK" | "WARN" | "FAILED"; title: string; detail: string; warnings: string[]; code?: string; latencyMs?: number };

/** A test that ran is a RESULT. Warnings (fixed-text advisories from the server) always appear next to a green result and turn it amber; they never hide it. */
export function testResultView(r: ConnectionTestResult): TestView {
  if (r.ok) {
    const warnings = Array.isArray(r.warnings) ? r.warnings.filter((w): w is string => typeof w === "string") : [];
    return warnings.length
      ? { state: "WARN", title: "Kết nối được, có cảnh báo", detail: `Phản hồi sau ${r.latencyMs} ms.`, warnings, latencyMs: r.latencyMs }
      : { state: "OK", title: "Kết nối thành công", detail: `Phản hồi sau ${r.latencyMs} ms.`, warnings: [], latencyMs: r.latencyMs };
  }
  return { state: "FAILED", title: "Kết nối thất bại", detail: FAIL_TEXT[r.code] ?? "Không kết nối được tới nguồn dữ liệu.", warnings: [], code: r.code };
}

// ------------------------------------------------------------------------------------------------------------------- credential

export type CredentialView = { configured: boolean; text: string; keys: string[] };
/** Key NAMES only. There is no value to show and the UI never offers to reveal one; "edit" means "replace". */
export function credentialView(m: CredentialMetadata | null | undefined, hasCredential?: boolean): CredentialView {
  const configured = m ? m.configured : !!hasCredential;
  const keys = m && Array.isArray(m.keys) ? m.keys.filter((k): k is string => typeof k === "string") : [];
  return configured
    ? { configured, keys, text: `Đã cấu hình${keys.length ? ` (${keys.join(", ")})` : ""}. Giá trị được giấu và không thể xem lại; nhập lại để thay thế.` }
    : { configured, keys, text: "Chưa có khóa kết nối." };
}

// ------------------------------------------------------------------------------------------------------------------- forms

/** a key or value that looks like a secret is refused by the server (400 INVALID_CONFIG): secrets go in the credential, not in the configuration */
const SECRETISH = /(pass(word|wd)?|secret|token|api[-_]?key|private[-_]?key|credential|authorization)/i;
export const looksLikeSecretKey = (k: string) => SECRETISH.test(k);
const URL_WITH_CREDENTIALS = /[a-z][a-z0-9+.-]*:\/\/[^/\s:@]+:[^/\s@]+@/i;

const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$/;

export type SourceForm = { name: string; type: string; config: Record<string, string>; credential: Record<string, string> };

export function checkSourceForm(f: SourceForm, d: ConnectorDescriptor | undefined, opts: { requireCredential?: boolean } = {}): { field: "name" | "type" | "config" | "credential"; message: string }[] {
  const e: { field: "name" | "type" | "config" | "credential"; message: string }[] = [];
  if (!NAME_RE.test(f.name)) e.push({ field: "name", message: "Tên chỉ gồm chữ, số, khoảng trắng và . _ - (tối đa 80 ký tự, bắt đầu bằng chữ hoặc số)." });
  if (!d) { e.push({ field: "type", message: "Hãy chọn loại nguồn dữ liệu." }); return e; }
  if (d.status !== "AVAILABLE") { e.push({ field: "type", message: "Loại nguồn này chưa được hỗ trợ (đang trong kế hoạch)." }); return e; }
  for (const k of d.configKeys) if (k.required && !(f.config[k.name] ?? "").trim()) e.push({ field: "config", message: `Thiếu cấu hình bắt buộc: ${k.name}.` });
  for (const [k, v] of Object.entries(f.config)) {
    if (looksLikeSecretKey(k) || URL_WITH_CREDENTIALS.test(v)) e.push({ field: "config", message: `“${k}” trông như thông tin bí mật. Hãy nhập vào ô khóa kết nối, không nhập vào cấu hình.` });
  }
  const wanted = d.credentialKeys;
  if (wanted.length && (opts.requireCredential || Object.keys(f.credential).length)) {
    for (const k of wanted) if (!(f.credential[k] ?? "").length) e.push({ field: "credential", message: `Thiếu trường khóa kết nối: ${k}.` });
  }
  return e;
}

/** Only fields that have a value are sent. Credential values are sent as typed (never trimmed or logged) and the host clears its inputs right after the request. */
export function compactMap(m: Record<string, string>, trim: boolean): Record<string, string> {
  return Object.fromEntries(Object.entries(m).filter(([, v]) => (trim ? v.trim() : v).length > 0).map(([k, v]) => [k, trim ? v.trim() : v]));
}

// ------------------------------------------------------------------------------------------------------------------- bindings

export const BINDING_LABEL: Record<BindingMode, string> = { TEST: "Chạy thử (TEST)", LIVE: "Chạy thật (LIVE)" };
export const bindingFor = (bs: readonly DataBinding[], mode: BindingMode, slotId: string): DataBinding | undefined => bs.find((b) => b.mode === mode && b.slotId === slotId);
/** The slots the document declares (`dataSources[]`). The typed operations cannot add one: a document without slots cannot be bound (see HANDOFF_C2 H-C2-02). */
export const slotsOf = (doc: AppDefinitionV2) => (doc.dataSources ?? []).map((d) => ({ id: d.id, name: d.name || d.id, type: d.type }));
/** a LIVE call with no LIVE binding answers 422 DATA_SOURCE_UNBOUND: tell the author before they publish */
export const unboundLive = (doc: AppDefinitionV2, bs: readonly DataBinding[]): string[] => slotsOf(doc).filter((s) => !bindingFor(bs, "LIVE", s.id)).map((s) => s.id);

/** After an error on a write, the list may or may not have changed: the panel reloads it instead of guessing. */
export const needsReload = (m: ManagementMessage) => m.kind === "unknown-outcome" || m.kind === "timeout" || m.kind === "conflict" || m.kind === "not-found";
