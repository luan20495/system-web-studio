"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { errorText } from "@xweb/api-client";
import { ArrowLeft, ArrowRight } from "./icons";
import { ScrollRegion } from "./ScrollRegion";

// Formatting lives in @xweb/i18n (locale-aware, one Intl object per locale, output identical to the vi-VN helpers that were here). These names are kept: ~113 call sites use them.
export const fmtDate = (iso?: string | null) => getFormatters(activeLocale()).fmtDate(iso);
export const ago = (iso?: string | null) => getFormatters(activeLocale()).ago(iso);
export const num = (n?: number | null) => getFormatters(activeLocale()).num(n);
/** Provider-reported USD. null = not reported (shown as "—", never as $0). Free models report exactly 0. */
export const usd = (n?: number | null) => getFormatters(activeLocale()).usd(n);
/** Token count; null = not reported. */
export const tok = (n?: number | null) => getFormatters(activeLocale()).tok(n);
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
import { activeLocale, getFormatters } from "@xweb/i18n";
