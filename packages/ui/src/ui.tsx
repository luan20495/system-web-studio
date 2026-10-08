"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { ApiError, errorText } from "@xweb/api-client";
import { ArrowLeft, ArrowRight, Ban, Inbox, SearchX, TriangleAlert } from "./icons";
import { ScrollRegion } from "./ScrollRegion";

export const fmtDate = (iso?: string | null) => (iso ? new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso)) : "—");
export function ago(iso?: string | null): string {
  if (!iso) return "—";
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "vừa xong"; if (s < 3600) return `${Math.floor(s / 60)} phút trước`; if (s < 86400) return `${Math.floor(s / 3600)} giờ trước`;
  if (s < 86400 * 30) return `${Math.floor(s / 86400)} ngày trước`; return fmtDate(iso);
}
export const num = (n?: number | null) => (n ?? 0).toLocaleString("vi-VN");
/** Provider-reported USD. null = not reported (shown as "—", never as $0). Free models report exactly 0. */
export const usd = (n?: number | null) => (n == null ? "—" : n === 0 ? "$0" : `$${n < 0.01 ? n.toPrecision(2) : n.toLocaleString("en-US", { maximumFractionDigits: 4 })}`);
/** Token count; null = not reported. */
export const tok = (n?: number | null) => (n == null ? "—" : n.toLocaleString("vi-VN"));
/** the one error mapper (packages/api-client/src/errorText.ts): Vietnamese by error code, never an Error.message of a non-ApiError. `errText` is the historical name. */
export const errText = (e: unknown, fallback?: string) => errorText(e, fallback);
export { errorText, errorParts, type ErrorParts, type ErrorKind } from "@xweb/api-client";

export type StateKind = "loading" | "empty" | "forbidden" | "notfound" | "error" | "network" | "conflict" | "expired" | "ai-unavailable" | "publish-failed";
const STATE_TEXT: Record<StateKind, [string, string]> = {
  loading: ["Đang tải…", ""], empty: ["Chưa có dữ liệu", ""], forbidden: ["Bạn không có quyền xem nội dung này", "Quyền được kiểm tra ở máy chủ. Liên hệ quản trị viên nếu cần truy cập."],
  notfound: ["Không tìm thấy", "Mục này không tồn tại hoặc bạn không có quyền xem."], error: ["Đã có lỗi xảy ra", "Hãy thử lại. Nếu lỗi lặp lại, gửi mã yêu cầu cho quản trị viên."],
  network: ["Không kết nối được máy chủ", "Kiểm tra mạng rồi thử lại."], conflict: ["Dữ liệu vừa được thay đổi ở nơi khác", "Đã tải lại bản mới nhất. Hãy thực hiện lại thao tác."],
  expired: ["Phiên đăng nhập đã hết hạn", "Đăng nhập lại để tiếp tục."], "ai-unavailable": ["AI tạm thời không khả dụng", "Thử lại sau hoặc chọn model khác."],
  "publish-failed": ["Xuất bản thất bại", "Xem chi tiết lỗi bên dưới và thử lại."]
};
export function stateOf(e: unknown): StateKind {
  if (e instanceof ApiError) { if (e.status === 0) return "network"; if (e.status === 403) return "forbidden"; if (e.status === 404) return "notfound"; if (e.status === 409) return "conflict"; if (e.status === 401) return "expired"; }
  return "error";
}
/** `level`: the heading level of the title (1 when the state IS the whole page: a page needs exactly one h1; 2 inside a card / section) */
export function StateView({ kind, title, detail, action, level = 2 }: { kind: StateKind; title?: string; detail?: ReactNode; action?: ReactNode; level?: 1 | 2 }) {
  const [t, d] = STATE_TEXT[kind]; const H = level === 1 ? "h1" : "h2";
  return (
    <div className={`stateView state-${kind}`} role={kind === "loading" ? "status" : kind === "empty" ? undefined : "alert"}>
      {kind === "loading" ? <div className="spinner" aria-hidden="true"/> : <div className="stateIcon" aria-hidden="true">{kind === "empty" ? <Inbox size={20}/> : kind === "forbidden" ? <Ban size={20}/> : kind === "notfound" ? <SearchX size={20}/> : <TriangleAlert size={20}/>}</div>}
      <H>{title ?? t}</H>{detail ?? (d ? <p>{d}</p> : null)}{action}
    </div>
  );
}
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const kind = stateOf(error);
  return <StateView kind={kind} detail={<p>{errText(error, STATE_TEXT[kind][1])}</p>} action={retry ? <button className="btn" onClick={retry}>Thử lại</button> : undefined}/>;
}

export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <nav className="pager" aria-label="Phân trang">
      <span>{total === 0 ? "0" : `${page * size + 1}–${Math.min(total, (page + 1) * size)}`} / {num(total)}</span>
      <button className="btn sm" disabled={page <= 0} onClick={() => onPage(page - 1)}><ArrowLeft size={14} aria-hidden="true"/> Trước</button>
      <span aria-current="page">Trang {page + 1}/{pages}</span>
      <button className="btn sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>Sau <ArrowRight size={14} aria-hidden="true"/></button>
    </nav>
  );
}

const TONE: Record<string, string> = {
  PENDING: "warn",
  HEALTHY: "ok", OK: "ok", ERROR: "bad", BAD_OUTPUT: "warn", RUNNING: "ok", ACTIVE: "ok", UPDATED: "ok", READY: "ok", true: "ok", PUBLIC: "info",
  DEGRADED: "warn", QUEUED: "warn", POLICY_CHECK: "warn", SECURITY_CHECK: "warn", BUILDING: "warn", DEPLOYING: "warn", NO_CHANGE: "muted", PRIVATE: "muted",
  UNAVAILABLE: "bad", FAILED: "bad", DISABLED: "bad", false: "bad", UNSUPPORTED: "warn", NOT_CONFIGURED: "muted", UNKNOWN: "muted", NOT_IMPLEMENTED: "muted", COMING_SOON: "muted",
  SUSPENDED: "warn", DELETED: "bad", REVIEW: "warn", APPROVED: "ok", DEPRECATED: "bad", DRAFT: "muted", REJECTED: "bad", SUPERSEDED: "muted", COMPANY: "info", ARCHIVED: "muted"
};
export function Pill({ value, label }: { value: string; label?: string }) { return <span className={`pill pill-${TONE[value] ?? "muted"}`}>{label ?? value}</span>; }
export function Kpi({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return <div className="kpi"><div className="kpiLabel">{label}</div><div className="kpiValue">{value}</div>{hint ? <div className="kpiHint">{hint}</div> : null}</div>;
}
export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return <ScrollRegion className={`card ${className ?? ""}`}>{title || actions ? <div className="cardHead">{title ? <h2>{title}</h2> : <span/>}{actions}</div> : null}{children}</ScrollRegion>;
}
export function ComingSoon({ title, children }: { title: string; children: ReactNode }) {
  return <div className="comingSoon"><Pill value="COMING_SOON" label="Chưa triển khai"/><h3>{title}</h3><div>{children}</div></div>;
}
export function NavLink({ href, active, children, icon }: { href: string; active: boolean; children: ReactNode; icon?: ReactNode }) {
  return <Link href={href} className={`navLink${active ? " active" : ""}`} aria-current={active ? "page" : undefined}>{icon ? <span className="navIcon" aria-hidden="true">{icon}</span> : null}<span>{children}</span></Link>;
}
export { ACTION_LABEL, actionLabel } from "@xweb/i18n";
