"use client";
// The state vocabulary of every screen: loading / empty / forbidden / not found / error ... said once, in Vietnamese. Relative imports on purpose (unit tests render this file).
import type { ReactNode } from "react";
import { ApiError } from "../../api-client/src/core";
import { ERROR_CODE_TEXT, errorParts } from "../../api-client/src/errorText";
import { Ban, Inbox, SearchX, TriangleAlert } from "./icons";

export type StateKind = "loading" | "empty" | "forbidden" | "notfound" | "error" | "network" | "conflict" | "expired" | "ai-unavailable" | "publish-failed";
const STATE_TEXT: Record<StateKind, [string, string]> = {
  loading: ["Đang tải…", ""], empty: ["Chưa có dữ liệu", ""], forbidden: ["Bạn không có quyền xem nội dung này", "Quyền được kiểm tra ở máy chủ. Liên hệ quản trị viên nếu cần truy cập."],
  notfound: ["Không tìm thấy", "Mục này không tồn tại hoặc bạn không có quyền xem."], error: ["Đã có lỗi xảy ra", "Hãy thử lại. Nếu lỗi lặp lại, gửi mã yêu cầu cho quản trị viên."],
  network: ["Không kết nối được máy chủ", "Kiểm tra mạng rồi thử lại."], conflict: ["Dữ liệu vừa được thay đổi ở nơi khác", "Đã tải lại bản mới nhất. Hãy thực hiện lại thao tác."],
  expired: ["Phiên đăng nhập đã hết hạn", "Đăng nhập lại để tiếp tục."], "ai-unavailable": ["AI tạm thời không khả dụng", "Thử lại sau hoặc chọn mô hình khác."],
  "publish-failed": ["Xuất bản thất bại", "Xem chi tiết lỗi bên dưới và thử lại."]
};
export function stateOf(e: unknown): StateKind {
  if (e instanceof ApiError) { if (e.status === 0) return "network"; if (e.status === 403) return "forbidden"; if (e.status === 404) return "notfound"; if (e.status === 409) return "conflict"; if (e.status === 401) return "expired"; }
  return "error";
}
/** `level`: the heading level of the title (1 when the state IS the whole page: a page needs exactly one h1; 2 inside a card / section). `compact`: a one-line state for a KPI / chip / small panel. */
export function StateView({ kind, title, detail, action, level = 2, compact = false }: { kind: StateKind; title?: string; detail?: ReactNode; action?: ReactNode; level?: 1 | 2; compact?: boolean }) {
  const [t, d] = STATE_TEXT[kind]; const H = level === 1 ? "h1" : "h2"; const shown = title ?? t;
  const body = detail ?? (d && d !== shown ? <p>{d}</p> : null);
  return (
    <div className={`stateView state-${kind}${compact ? " compact" : ""}`} role={kind === "loading" ? "status" : kind === "empty" ? undefined : "alert"}>
      {kind === "loading" ? <div className="spinner" aria-hidden="true"/> : <div className="stateIcon" aria-hidden="true">{kind === "empty" ? <Inbox size={20}/> : kind === "forbidden" ? <Ban size={20}/> : kind === "notfound" ? <SearchX size={20}/> : <TriangleAlert size={20}/>}</div>}
      {compact ? <p className="stateTitle"><b>{shown}</b></p> : <H>{shown}</H>}{body}{action}
    </div>
  );
}

/** the sentences a kind already says in its own title/detail; an error whose mapped text is just one of these adds nothing and must not be printed a second time */
const KIND_DEFAULT_SENTENCE = new Set(["FORBIDDEN", "NOT_FOUND", "AUTHENTICATION_REQUIRED", "NETWORK", "INTERNAL_ERROR", "VALIDATION_FAILED", "REVISION_CONFLICT", "CONFLICT", "SCOPE_BUSY"].map((c) => ERROR_CODE_TEXT[c]));
/** a retry cannot help when the answer is "no", "gone" or "sign in again"; it can when the failure was transient */
export const retryHelps = (kind: StateKind) => kind !== "notfound" && kind !== "forbidden" && kind !== "expired";
/**
 * One failure, said once (M-079): the title comes from the state kind, the detail only when the error carries something the title does not already say,
 * a reference code when support will need it, and a retry button only when retrying can change the outcome (never on 404 / 403 / expired session).
 */
export function ErrorState({ error, retry, level = 2, title, compact = false, retryLabel }: { error: unknown; retry?: () => void; level?: 1 | 2; title?: string; compact?: boolean; retryLabel?: string }) {
  const kind = stateOf(error); const p = errorParts(error);
  const shown = title ?? STATE_TEXT[kind][0];
  const specific = p.message !== shown && !KIND_DEFAULT_SENTENCE.has(p.message) && p.message !== STATE_TEXT[kind][1];
  const detail = (
    <>
      {specific ? <p>{p.message}</p> : STATE_TEXT[kind][1] && STATE_TEXT[kind][1] !== shown ? <p>{STATE_TEXT[kind][1]}</p> : null}
      {p.reference ? <p className="xp-stateRef">Mã tham chiếu: <code>{p.reference}</code></p> : null}
    </>
  );
  return <StateView kind={kind} level={level} compact={compact} title={title} detail={detail} action={retry && retryHelps(kind) ? <button className="btn" type="button" onClick={retry}>{retryLabel ?? (kind === "conflict" ? "Tải lại" : "Thử lại")}</button> : undefined}/>;
}

