/**
 * Publish / site / rollback / unpublish — pure helpers of the C2 HTTP contract (docs/parallel/c2/PUBLISH_API_CONTRACT.md @ fix/c2-v3 8d40218, pinned by C2's PublishApiContractTests).
 * Nothing here is invented: every status, field, header and error code below is a line of that document. The browser is never the authority: SiteInfo.operation / the buttons are UX,
 * the server answers 409 SCOPE_BUSY / ROLLBACK_STALE regardless.
 */

// ---- deployment status (§5) ----------------------------------------------------------------------------------------------------------------------------------
export const DEPLOYMENT_STATUSES = ["QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "ROLLING_BACK", "RUNNING", "FAILED", "ROLLED_BACK"] as const;
export type DeploymentStatusName = (typeof DEPLOYMENT_STATUSES)[number];
/** terminal = {RUNNING, FAILED, ROLLED_BACK}: unchanged by V30, so a poller stops on exactly this set. */
export const TERMINAL_DEPLOYMENT_STATUSES: readonly string[] = ["RUNNING", "FAILED", "ROLLED_BACK"];
export const isTerminalDeployment = (status: string | null | undefined): boolean => !!status && TERMINAL_DEPLOYMENT_STATUSES.includes(status);
/** busy = non-terminal: includes ROLLING_BACK (a publish whose switch is being undone). An UNKNOWN status is treated as busy (keep polling), never as success. */
export const isBusyDeployment = (status: string | null | undefined): boolean => !!status && !isTerminalDeployment(status);
/** the only success a deployment can end with. ROLLING_BACK is not success; ROLLED_BACK is a terminal result of a release that was rolled away from, not a success of a publish. */
export const isDeploymentSuccess = (status: string | null | undefined): boolean => status === "RUNNING";
/** polling stops on a terminal status (same set as before V30) */
export const shouldStopPolling = isTerminalDeployment;

export const DEPLOYMENT_STATUS_LABEL: Readonly<Record<string, string>> = {
  QUEUED: "Đang chờ", POLICY_CHECK: "Kiểm tra chính sách", SECURITY_CHECK: "Kiểm tra bảo mật", BUILDING: "Đang build", DEPLOYING: "Đang triển khai",
  ROLLING_BACK: "Đang hoàn tác (chưa phải thành công)", RUNNING: "Đang chạy", FAILED: "Thất bại", ROLLED_BACK: "Đã hoàn tác (đã chuyển sang bản khác)",
  // event-only names that appear in deployments[].events[] (never statuses)
  SWITCH: "Chuyển sang bản mới", ROLLBACK_OK: "Hoàn tác xong", ROLLBACK_FAILED: "Hoàn tác không thành công", ROLLBACK_OFFLINE: "Hoàn tác: trang đang ngoại tuyến", SCOPE_BUSY: "Đang chờ một thao tác phát hành khác", STALE_PUBLISH: "Bản này đã cũ hơn bản đang chạy",
  /** event name written by the build step when the release has public queries (C2 c1e0df5); never a status */
  PUBLIC_QUERIES: "Truy vấn công khai của bản này",
};
export const deploymentLabel = (status: string): string => DEPLOYMENT_STATUS_LABEL[status] ?? status;

// ---- failure of a deployment: `error` is "[CODE] reason" (§1/§5); STALE_PUBLISH and SCOPE_BUSY(publish) are deployment FAILURES, never HTTP errors (§6) --------------------
export function failureCode(error: string | null | undefined): string | null { const m = /^\[([A-Z_]+)\]/.exec(error ?? ""); return m ? m[1] : null; }
export type FailureView = { code: string | null; title: string; detail: string; retryByPublishingAgain: boolean };
const FAILURE_TEXT: Readonly<Record<string, { title: string; detail: string }>> = {
  STALE_PUBLISH: { title: "Bản xuất bản này đã cũ hơn bản đang chạy", detail: "Một thao tác phát hành mới hơn (xuất bản hoặc hoàn tác) đã đổi bản đang chạy trước khi bản này kịp lên. Bản đang chạy không bị ảnh hưởng. Xuất bản lại nếu bạn vẫn muốn bản này." },
  SCOPE_BUSY: { title: "Một thao tác phát hành khác giữ ứng dụng quá lâu", detail: "Bản này chờ quá 300 giây vì một thao tác khác chưa xong, nên bị hủy. Xuất bản lại sau." },
  POLICY_REJECTED: { title: "Bị từ chối bởi chính sách", detail: "Kết quả kiểm tra chính sách là vĩnh viễn; sửa nội dung rồi xuất bản lại." },
  SECURITY_REJECTED: { title: "Bị từ chối bởi kiểm tra bảo mật", detail: "Kết quả kiểm tra bảo mật là vĩnh viễn; sửa nội dung rồi xuất bản lại." },
  BUILD_FAILED: { title: "Build không thành công", detail: "" }, DEPLOY_FAILED: { title: "Triển khai không thành công", detail: "" }, DEPLOY_TIMEOUT: { title: "Triển khai quá thời gian", detail: "" },
  DEPLOY_STATE_UNKNOWN: { title: "Không xác định được kết quả triển khai", detail: "Bản đang chạy trước đó được giữ nguyên." }, VERIFICATION_FAILED: { title: "Kiểm tra sau triển khai không đạt", detail: "" },
  ARTIFACT_STORE_UNAVAILABLE: { title: "Kho lưu trữ tạm thời không dùng được", detail: "" }, RENDER_UNAVAILABLE: { title: "Dịch vụ dựng trang tạm thời không dùng được", detail: "" }, DATABASE_UNAVAILABLE: { title: "Cơ sở dữ liệu tạm thời không dùng được", detail: "" },
};
/** how a FAILED deployment is shown. Never success; the reason after "[CODE]" is shown verbatim (it is the server's own text). */
export function explainFailedDeployment(d: { status: string; error: string | null }): FailureView {
  const code = failureCode(d.error); const reason = (d.error ?? "").replace(/^\[[A-Z_]+\]\s*/, "");
  const known = code ? FAILURE_TEXT[code] : undefined;
  return { code, title: known?.title ?? "Không xuất bản được", detail: [known?.detail, reason].filter(Boolean).join(" — ") || "Máy chủ không nêu lý do.", retryByPublishingAgain: true };
}

// ---- requests: EXACTLY the contract's fields -------------------------------------------------------------------------------------------------------------------
/** Idempotency-Key: 8–120 chars of A-Z a-z 0-9 _ . : - (§1). Required by publish, optional by rollback / unpublish. */
export const RELEASE_KEY = /^[A-Za-z0-9_.:-]{8,120}$/;
export const isValidReleaseKey = (k: unknown): k is string => typeof k === "string" && RELEASE_KEY.test(k);
export type PublishVisibility = "PRIVATE" | "PUBLIC";
/** `{visibility, expectedRevision}` and nothing else: no tenantId, no pointerVersion, no invented concurrency field */
export const publishBody = (visibility: PublishVisibility, expectedRevision: number): { visibility: PublishVisibility; expectedRevision: number } => ({ visibility, expectedRevision });
/** `{deploymentId}` + optional `expectedActiveDeploymentId` (omitted, never null, when unknown). Never pointerVersion. */
export const rollbackBody = (r: { deploymentId: string; expectedActiveDeploymentId?: string | null }): { deploymentId: string; expectedActiveDeploymentId?: string } =>
  r.expectedActiveDeploymentId ? { deploymentId: r.deploymentId, expectedActiveDeploymentId: r.expectedActiveDeploymentId } : { deploymentId: r.deploymentId };
/** unpublish has NO body: the optional expectation is the QUERY parameter of the same name */
export const unpublishQuery = (expectedActiveDeploymentId?: string | null): string => (expectedActiveDeploymentId ? `?expectedActiveDeploymentId=${encodeURIComponent(expectedActiveDeploymentId)}` : "");

// ---- idempotency key lifecycle ---------------------------------------------------------------------------------------------------------------------------------
/**
 * One key per LOGICAL request. Same key + same payload = a replay of the SAME operation (a lost answer can be retried safely); same key + a different payload = 409 IDEMPOTENCY_KEY_REUSED.
 * So the key follows the payload: `keyFor(payload)` returns the same key for an equal payload and a fresh one as soon as the payload differs. After an operation reached an outcome the user
 * wants to repeat (a FAILED publish, a refused rollback) the caller MUST `rotate()`: replaying the old key would only return the old outcome.
 */
export class ReleaseKeyBook {
  private last: { fingerprint: string; key: string } | null = null;
  constructor(private readonly make: () => string = newReleaseKey) {}
  keyFor(payload: unknown): string {
    const fp = JSON.stringify(payload);
    if (!this.last || this.last.fingerprint !== fp) this.last = { fingerprint: fp, key: this.make() };
    return this.last.key;
  }
  /** forget the current key: the next request, even with the same payload, is a NEW operation */
  rotate(): void { this.last = null; }
}
export function newReleaseKey(prefix = "ui"): string {
  const id = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `${prefix}-${id}`;
}

// ---- SiteInfo.operation (§4): UX only, the server stays the authority --------------------------------------------------------------------------------------------
export type SiteOperationLike = { kind: string; deploymentId: string | null; since: string; leaseUntil: string } | null | undefined;
export const OPERATION_LABEL: Readonly<Record<string, string>> = { PUBLISH: "đang xuất bản", ROLLBACK: "đang hoàn tác về bản cũ", UNPUBLISH: "đang gỡ trang xuống" };
export const isReleaseBusy = (site: { operation?: SiteOperationLike } | null | undefined): boolean => !!site?.operation;
export function operationBanner(op: SiteOperationLike): string | null {
  return op ? `Ứng dụng này ${OPERATION_LABEL[op.kind] ?? `đang có thao tác phát hành (${op.kind})`}. Các nút xuất bản / hoàn tác / gỡ xuống tạm khóa cho tới khi xong.` : null;
}
/** polling interval for SiteInfo while an operation is shown (the lease is 90 s, a normal operation seconds) */
export const SITE_POLL_MS = 2500;
/** while the scope is idle and the dialog is open, SiteInfo is refreshed this slowly so an operation started by someone else (or another tab) is noticed */
export const SITE_IDLE_POLL_MS = 8000;

// ---- errors (§1–§3, §6) ----------------------------------------------------------------------------------------------------------------------------------------
export type ReleaseErrorLike = { status?: number; code?: string; message?: string; details?: unknown; retryAfterSeconds?: number } | null | undefined;
export type ReleaseErrorView = {
  kind: "scope-busy" | "stale" | "key-reused" | "in-progress" | "revision" | "not-restorable" | "rollback-failed" | "no-site" | "forbidden" | "no-version" | "public-disabled" | "rate-limited" | "invalid" | "unreachable" | "other";
  title: string; detail: string;
  /** true only where the contract says a retry can succeed as is (SCOPE_BUSY: Retry-After 5; in-progress; unreachable with a key) */
  retry: boolean; retryAfterSeconds?: number;
  /** the screen state is stale: reload SiteInfo / deployments before deciding again (ROLLBACK_STALE, REVISION_CONFLICT) */
  reload: boolean;
  /** the Idempotency-Key must be replaced before the next attempt (IDEMPOTENCY_KEY_REUSED) */
  newKey: boolean;
};
const holder = (details: unknown): string | null => { const op = (details as { operation?: { kind?: string } | null } | null)?.operation; return op?.kind ? (OPERATION_LABEL[op.kind] ?? op.kind) : null; };
export function explainReleaseError(e: ReleaseErrorLike): ReleaseErrorView {
  const base = { retry: false, reload: false, newKey: false } as const;
  const code = e?.code ?? "", status = e?.status ?? 0;
  if (status === 0) return { ...base, kind: "unreachable", title: "Chưa rõ thao tác đã được máy chủ nhận hay chưa", detail: "Kết nối bị gián đoạn. Máy chủ vẫn là nơi quyết định: tải lại trạng thái trước khi thử lại. Thử lại với cùng khóa là an toàn (không tạo bản thứ hai).", retry: true, reload: true };
  switch (code) {
    case "SCOPE_BUSY": { const h = holder(e?.details); return { ...base, kind: "scope-busy", title: "Ứng dụng đang có một thao tác phát hành khác", detail: `${h ? `Thao tác đang chạy: ${h}. ` : ""}Thử lại sau ${e?.retryAfterSeconds ?? 5} giây khi thao tác đó xong.`, retry: true, retryAfterSeconds: e?.retryAfterSeconds ?? 5, reload: true }; }
    case "ROLLBACK_STALE": return { ...base, kind: "stale", title: "Bản đang chạy đã thay đổi", detail: "Bản đang chạy không còn là bản bạn thấy khi chọn. Không có gì bị thay đổi. Hãy tải lại trạng thái rồi quyết định lại.", reload: true };
    case "IDEMPOTENCY_KEY_REUSED": return { ...base, kind: "key-reused", title: "Khóa chống ghi trùng đã được dùng cho một yêu cầu khác", detail: "Không thử lại với khóa này. Một yêu cầu mới sẽ dùng khóa mới.", newKey: true };
    case "IDEMPOTENCY_IN_PROGRESS": return { ...base, kind: "in-progress", title: "Yêu cầu giống hệt đang được xử lý", detail: "Đợi vài giây rồi kiểm tra lại trạng thái.", retry: true, reload: true };
    case "REVISION_CONFLICT": return { ...base, kind: "revision", title: "Ứng dụng đã thay đổi", detail: "Đóng hộp thoại, kiểm tra bản mới rồi xuất bản lại.", reload: true };
    case "DEPLOYMENT_NOT_RESTORABLE": return { ...base, kind: "not-restorable", title: "Không thể phục vụ lại bản này", detail: "Bản này không còn chạy được (đã hoàn tác hoặc thiếu tệp). Xuất bản lại phiên bản đó nếu cần.", reload: true };
    case "ROLLBACK_FAILED": return { ...base, kind: "rollback-failed", title: "Không khôi phục được bản đã chọn", detail: `${e?.message ?? ""} Không có gì bị thay đổi.`.trim() };
    case "SITE_NOT_FOUND": return { ...base, kind: "no-site", title: "Ứng dụng chưa có trang nào", detail: "Chưa có lần xuất bản nào để hoàn tác.", reload: true };
    case "NO_VERSION": return { ...base, kind: "no-version", title: "Chưa có phiên bản để xuất bản", detail: "Hãy lưu thay đổi để tạo phiên bản." };
    case "PUBLIC_PUBLISH_DISABLED": case "CODE_APP_PUBLIC_DISABLED": return { ...base, kind: "public-disabled", title: "Quản trị viên đang tắt xuất bản công khai", detail: "Chỉ xuất bản riêng tư được phép.", reload: true };
    case "MISSING_HEADER": case "INVALID_IDEMPOTENCY_KEY": case "VALIDATION_FAILED": case "MALFORMED_REQUEST": return { ...base, kind: "invalid", title: "Yêu cầu không hợp lệ", detail: e?.message ?? "Đây là lỗi của trình duyệt, không phải của bạn." };
  }
  if (status === 403) return { ...base, kind: "forbidden", title: "Bạn không có quyền xuất bản", detail: "Cần quyền xuất bản (APP_PUBLISH). Máy chủ kiểm tra quyền ở mọi lệnh gọi." };
  if (status === 404) return { ...base, kind: "other", title: "Không tìm thấy ứng dụng", detail: "Ứng dụng không còn hoặc bạn không có quyền truy cập.", reload: true };
  if (status === 429) return { ...base, kind: "rate-limited", title: "Quá nhiều lần xuất bản", detail: `Thử lại sau ${e?.retryAfterSeconds ?? 60} giây.`, retry: true, retryAfterSeconds: e?.retryAfterSeconds ?? 60 };
  if (status >= 500) return { ...base, kind: "unreachable", title: "Chưa rõ kết quả: máy chủ gặp sự cố khi xử lý", detail: "Chưa rõ thao tác đã được nhận hay chưa. Tải lại trạng thái trước khi thử lại; thử lại với cùng khóa là an toàn.", retry: true, reload: true };
  return { ...base, kind: "other", title: "Không thực hiện được", detail: e?.message ?? "Lỗi không xác định." };
}

// ---- rollback candidates ---------------------------------------------------------------------------------------------------------------------------------------
/** The restorable releases: RUNNING (not mock) and not the one being served. A ROLLED_BACK release is NOT restorable (publish that version again). The server re-checks (400 DEPLOYMENT_NOT_RESTORABLE). */
export function rollbackCandidates<T extends { id: string; status: string; mock?: boolean }>(history: readonly T[], currentDeploymentId: string | null | undefined): T[] {
  return history.filter((d) => d.status === "RUNNING" && !d.mock && d.id !== currentDeploymentId);
}
