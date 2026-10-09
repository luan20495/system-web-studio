/**
 * Label maps for every enum the portals show (M-061). Each map is `satisfies Record<Union, string>`: a code added to the union without a label does not typecheck.
 * Unions that the backend contract owns (@xweb/types) are imported; the others are listed here as `as const` arrays, which are the single list the UI and the tests share.
 * A value the server sends that is not in the list gets "Khác" through `labelOf` (and is reported through `onUnmappedLabel`), never the raw code.
 *
 * Wording follows docs/parallel/c5/audit/S3-glossary.md (Vietnamese, modern tone marks, no English except the allow-list).
 */
import type { AiProviderKind, AppKind, DeploymentStatus, ReleaseOperationKind, TenantStatusName } from "@xweb/types";
import { labelOf } from "./text";

// -------------------------------------------------------------------------------------------------------------------------------------------- accounts, companies
export const TENANT_STATUS_LABEL = { ACTIVE: "Hoạt động", SUSPENDED: "Tạm khóa", DELETED: "Đã xóa" } as const satisfies Record<TenantStatusName, string>;
export const ACCOUNT_STATES = ["ACTIVE", "DISABLED", "PENDING"] as const; export type AccountState = (typeof ACCOUNT_STATES)[number];
export const ACCOUNT_STATE_LABEL = { ACTIVE: "Hoạt động", DISABLED: "Bị khóa", PENDING: "Chờ kích hoạt" } as const satisfies Record<AccountState, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- apps, versions, release
export const APP_KIND_LABEL = {
  WEBSITE_STATIC: "Website", SOURCE_WEB_APP: "Ứng dụng web (mã nguồn)", DASHBOARD: "Bảng điều khiển", INTERNAL_TOOL: "Công cụ nội bộ", WORKFLOW: "Workflow", SERVER_APP: "Ứng dụng có máy chủ"
} as const satisfies Record<AppKind, string>;
export const VISIBILITIES = ["PRIVATE", "PUBLIC", "COMPANY"] as const; export type Visibility = (typeof VISIBILITIES)[number];
export const VISIBILITY_LABEL = { PRIVATE: "Riêng tư", PUBLIC: "Công khai", COMPANY: "Cả công ty" } as const satisfies Record<Visibility, string>;
export const VERSION_KINDS = ["AI", "EDIT", "RESTORE"] as const; export type VersionKind = (typeof VERSION_KINDS)[number];
export const VERSION_KIND_LABEL = { AI: "Do AI tạo", EDIT: "Chỉnh sửa tay", RESTORE: "Khôi phục từ bản cũ" } as const satisfies Record<VersionKind, string>;
export const DEPLOYMENT_STATUS_TEXT = {
  QUEUED: "Đang chờ", POLICY_CHECK: "Kiểm tra chính sách", SECURITY_CHECK: "Kiểm tra bảo mật", BUILDING: "Đang build", DEPLOYING: "Đang triển khai",
  ROLLING_BACK: "Đang quay về bản cũ", RUNNING: "Đang chạy", FAILED: "Thất bại", ROLLED_BACK: "Đã quay về bản khác"
} as const satisfies Record<DeploymentStatus, string>;
export const RELEASE_OPERATION_LABEL = { PUBLISH: "Xuất bản", ROLLBACK: "Quay về bản cũ", UNPUBLISH: "Gỡ trang xuống" } as const satisfies Record<ReleaseOperationKind, string>;
export const RELEASE_OPERATION_PROGRESS = { PUBLISH: "đang xuất bản", ROLLBACK: "đang quay về bản cũ", UNPUBLISH: "đang gỡ trang xuống" } as const satisfies Record<ReleaseOperationKind, string>;
export const REPO_STATES = ["ACTIVE", "ARCHIVED", "PENDING_DELETE", "DELETED"] as const; export type RepoState = (typeof REPO_STATES)[number];
export const REPO_STATE_LABEL = { ACTIVE: "Đang dùng", ARCHIVED: "Đã lưu trữ", PENDING_DELETE: "Chờ xóa", DELETED: "Đã xóa" } as const satisfies Record<RepoState, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- AI
export const AI_PROVIDER_KIND_LABEL = {
  OPENROUTER: "OpenRouter", OPENAI: "OpenAI", ANTHROPIC: "Anthropic", GEMINI: "Gemini", OPENAI_COMPATIBLE: "Dịch vụ tương thích OpenAI", LOCAL: "AI nội bộ"
} as const satisfies Record<AiProviderKind, string>;
export const PROMPT_OUTCOMES = ["UPDATED", "NO_CHANGE", "UNSUPPORTED", "CANCELLED", "TIMEOUT", "REJECTED", "BAD_OUTPUT", "ERROR", "OK", "UNKNOWN"] as const; export type PromptOutcome = (typeof PROMPT_OUTCOMES)[number];
export const PROMPT_OUTCOME_LABEL = {
  UPDATED: "Đã cập nhật trang", NO_CHANGE: "Không có thay đổi", UNSUPPORTED: "Chưa hỗ trợ yêu cầu này", CANCELLED: "Đã hủy", TIMEOUT: "Quá thời gian chờ",
  REJECTED: "Bị từ chối", BAD_OUTPUT: "Trả lời không dùng được", ERROR: "Lỗi (không có trả lời)", OK: "Thành công", UNKNOWN: "Không rõ"
} as const satisfies Record<PromptOutcome, string>;
export const LIMIT_SCOPES = ["ORG", "WORKSPACE", "ROLE", "USER", "PROJECT"] as const; export type LimitScope = (typeof LIMIT_SCOPES)[number];
export const LIMIT_SCOPE_LABEL = { ORG: "Toàn công ty", WORKSPACE: "Workspace", ROLE: "Vai trò trong workspace", USER: "Người dùng", PROJECT: "Ứng dụng" } as const satisfies Record<LimitScope, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- operations (system health, alerts, backups, security)
export const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "WARNING", "INFO"] as const; export type Severity = (typeof SEVERITIES)[number];
export const SEVERITY_LABEL = { CRITICAL: "Nghiêm trọng", HIGH: "Cao", MEDIUM: "Trung bình", LOW: "Thấp", WARNING: "Cảnh báo", INFO: "Thông tin" } as const satisfies Record<Severity, string>;
export const HEALTH_STATUSES = ["HEALTHY", "DEGRADED", "UNAVAILABLE", "UNKNOWN", "NOT_CONFIGURED"] as const; export type HealthStatus = (typeof HEALTH_STATUSES)[number];
export const HEALTH_LABEL = { HEALTHY: "Khỏe", DEGRADED: "Suy giảm", UNAVAILABLE: "Không khả dụng", UNKNOWN: "Không rõ", NOT_CONFIGURED: "Chưa cấu hình" } as const satisfies Record<HealthStatus, string>;
export const DRILL_RESULTS = ["PASS", "SKIPPED", "FAIL", "FAILED"] as const; export type DrillResult = (typeof DRILL_RESULTS)[number];
export const DRILL_RESULT_LABEL = { PASS: "Đạt", SKIPPED: "Bỏ qua", FAIL: "Không đạt", FAILED: "Không đạt" } as const satisfies Record<DrillResult, string>;
export const BACKUP_STATES = ["OK", "WARN", "FAILED", "SKIPPED"] as const; export type BackupState = (typeof BACKUP_STATES)[number];
export const BACKUP_STATE_LABEL = { OK: "Ổn", WARN: "Cần chú ý", FAILED: "Lỗi", SKIPPED: "Bỏ qua" } as const satisfies Record<BackupState, string>;
export const ENVIRONMENTS = ["production", "staging", "development", "local"] as const; export type Environment = (typeof ENVIRONMENTS)[number];
export const ENVIRONMENT_LABEL = { production: "Sản xuất", staging: "Thử nghiệm", development: "Phát triển", local: "Máy cục bộ" } as const satisfies Record<Environment, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- library (components, templates, blocks, packages), code changes
export const LIBRARY_STATUSES = ["PRIVATE", "SUBMITTED", "VALIDATING", "REVIEW", "APPROVED", "DEPRECATED", "DRAFT", "REJECTED", "SUPERSEDED", "ACTIVE", "ARCHIVED", "DISABLED"] as const; export type LibraryStatus = (typeof LIBRARY_STATUSES)[number];
export const LIBRARY_STATUS_LABEL = {
  PRIVATE: "Riêng tư", SUBMITTED: "Đã gửi", VALIDATING: "Đang kiểm tra", REVIEW: "Chờ duyệt", APPROVED: "Đã duyệt", DEPRECATED: "Ngừng dùng", DRAFT: "Bản nháp",
  REJECTED: "Bị từ chối", SUPERSEDED: "Đã thay thế", ACTIVE: "Đã duyệt", ARCHIVED: "Đã lưu trữ", DISABLED: "Đang tắt"
} as const satisfies Record<LibraryStatus, string>;
export const PACKAGE_STATUSES = ["PENDING", "RESOLVING", "ALLOWED", "DENIED"] as const; export type PackageStatus = (typeof PACKAGE_STATUSES)[number];
export const PACKAGE_STATUS_LABEL = { PENDING: "Chờ duyệt", RESOLVING: "Đang kiểm tra", ALLOWED: "Cho phép", DENIED: "Từ chối" } as const satisfies Record<PackageStatus, string>;
export const CODE_CHANGE_STATUSES = ["BUILDING", "READY", "FAILED", "MERGED", "DISCARDED"] as const; export type CodeChangeStatus = (typeof CODE_CHANGE_STATUSES)[number];
export const CODE_CHANGE_STATUS_LABEL = { BUILDING: "Đang build thử", READY: "Sẵn sàng", FAILED: "Lỗi", MERGED: "Đã hợp nhất", DISCARDED: "Đã bỏ" } as const satisfies Record<CodeChangeStatus, string>;
export const SERVER_APP_STATUSES = ["PENDING", "STARTING", "RUNNING", "FAILED", "SUPERSEDED", "STOPPED"] as const; export type ServerAppStatus = (typeof SERVER_APP_STATUSES)[number];
export const SERVER_APP_STATUS_LABEL = { PENDING: "Chờ khởi động", STARTING: "Đang khởi động", RUNNING: "Đang chạy", FAILED: "Lỗi", SUPERSEDED: "Đã thay thế", STOPPED: "Đã dừng" } as const satisfies Record<ServerAppStatus, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- builder vocabulary the admin / studio screens share
export const PARAM_TYPES = ["STRING", "NUMBER", "BOOLEAN", "DATE", "DATETIME", "OBJECT", "ARRAY"] as const; export type ParamType = (typeof PARAM_TYPES)[number];
export const PARAM_TYPE_LABEL = { STRING: "Chuỗi", NUMBER: "Số", BOOLEAN: "Đúng / sai", DATE: "Ngày", DATETIME: "Ngày giờ", OBJECT: "Đối tượng", ARRAY: "Danh sách" } as const satisfies Record<ParamType, string>;
export const DATA_OPERATIONS = ["READ", "WRITE", "LIST", "GET"] as const; export type DataOperation = (typeof DATA_OPERATIONS)[number];
export const DATA_OPERATION_LABEL = { READ: "Đọc", WRITE: "Ghi", LIST: "Danh sách", GET: "Một bản ghi" } as const satisfies Record<DataOperation, string>;
export const APPROVER_KINDS = ["USER", "GROUP", "ROLE", "DEPARTMENT_MANAGER"] as const; export type ApproverKind = (typeof APPROVER_KINDS)[number];
export const APPROVER_KIND_LABEL = { USER: "Người dùng", GROUP: "Nhóm", ROLE: "Vai trò", DEPARTMENT_MANAGER: "Trưởng phòng ban" } as const satisfies Record<ApproverKind, string>;

// -------------------------------------------------------------------------------------------------------------------------------------------- the lookup functions (unknown value -> "Khác", reported)
export const tenantStatusLabel = (v: string | null | undefined) => labelOf(TENANT_STATUS_LABEL, v, undefined, "tenantStatus");
export const accountStateLabel = (v: string | null | undefined) => labelOf(ACCOUNT_STATE_LABEL, v, undefined, "accountState");
export const appKindLabel = (v: string | null | undefined) => labelOf(APP_KIND_LABEL, v, undefined, "appKind");
export const visibilityLabel = (v: string | null | undefined) => labelOf(VISIBILITY_LABEL, v, undefined, "visibility");
export const versionKindLabel = (v: string | null | undefined) => labelOf(VERSION_KIND_LABEL, v, undefined, "versionKind");
export const deploymentStatusText = (v: string | null | undefined) => labelOf(DEPLOYMENT_STATUS_TEXT, v, undefined, "deploymentStatus");
export const releaseOperationLabel = (v: string | null | undefined) => labelOf(RELEASE_OPERATION_LABEL, v, undefined, "releaseOperation");
export const repoStateLabel = (v: string | null | undefined) => labelOf(REPO_STATE_LABEL, v, undefined, "repoState");
export const aiProviderKindLabel = (v: string | null | undefined) => labelOf(AI_PROVIDER_KIND_LABEL, v, undefined, "aiProviderKind");
export const promptOutcomeLabel = (v: string | null | undefined) => labelOf(PROMPT_OUTCOME_LABEL, v, undefined, "promptOutcome");
export const limitScopeLabel = (v: string | null | undefined) => labelOf(LIMIT_SCOPE_LABEL, v, undefined, "limitScope");
export const severityLabel = (v: string | null | undefined) => labelOf(SEVERITY_LABEL, v, undefined, "severity");
export const healthLabel = (v: string | null | undefined) => labelOf(HEALTH_LABEL, v, undefined, "health");
export const drillResultLabel = (v: string | null | undefined) => labelOf(DRILL_RESULT_LABEL, v, undefined, "drillResult");
export const backupStateLabel = (v: string | null | undefined) => labelOf(BACKUP_STATE_LABEL, v, undefined, "backupState");
export const environmentLabel = (v: string | null | undefined) => labelOf(ENVIRONMENT_LABEL, v, undefined, "environment");
export const libraryStatusLabel = (v: string | null | undefined) => labelOf(LIBRARY_STATUS_LABEL, v, undefined, "libraryStatus");
export const packageStatusLabel = (v: string | null | undefined) => labelOf(PACKAGE_STATUS_LABEL, v, undefined, "packageStatus");
export const codeChangeStatusLabel = (v: string | null | undefined) => labelOf(CODE_CHANGE_STATUS_LABEL, v, undefined, "codeChangeStatus");
export const serverAppStatusLabel = (v: string | null | undefined) => labelOf(SERVER_APP_STATUS_LABEL, v, undefined, "serverAppStatus");
export const paramTypeLabel = (v: string | null | undefined) => labelOf(PARAM_TYPE_LABEL, v, undefined, "paramType");
export const dataOperationLabel = (v: string | null | undefined) => labelOf(DATA_OPERATION_LABEL, v, undefined, "dataOperation");
export const approverKindLabel = (v: string | null | undefined) => labelOf(APPROVER_KIND_LABEL, v, undefined, "approverKind");
