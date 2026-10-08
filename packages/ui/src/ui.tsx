"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { errorText } from "@xweb/api-client";
import { ArrowLeft, ArrowRight } from "./icons";
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

export * from "./States";
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

export { Pill, pillTone, PILL_TONE, type PillTone } from "./Pill";
import { Pill } from "./Pill";
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
