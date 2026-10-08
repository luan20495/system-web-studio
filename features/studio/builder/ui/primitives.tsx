"use client";
/** Small accessible building blocks of the Builder. Relative imports only (these are SSR-tested). */
import { useEffect, useId, useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from "react";
import type { Readiness } from "../core/readiness";

/** An icon-only control MUST have an accessible name: `label` is required and becomes aria-label + title. */
export function IconButton({ label, children, className = "", ...rest }: { label: string; children: ReactNode } & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label" | "title">) {
  return <button type="button" className={`bx-icon ${className}`} aria-label={label} title={label} {...rest}>{children}</button>;
}

/** NOT_READY / LOADING / ERROR panel with a short reason. AVAILABLE renders nothing (the caller renders the real UI). */
export function StateBox({ state, compact = false }: { state: Readiness; compact?: boolean }) {
  if (state.state === "AVAILABLE") return null;
  const cls = `bx-state bx-state-${state.state.toLowerCase().replace("_", "-")}${compact ? " compact" : ""}`;
  if (state.state === "LOADING") return <div className={cls} role="status" aria-live="polite"><b>Đang tải…</b></div>;
  if (state.state === "ERROR") return <div className={cls} role="alert"><b>Có lỗi</b><p>{state.message}</p></div>;
  return <div className={cls} role="note" data-state="NOT_READY"><b>Chưa sẵn sàng</b><p>{state.reason}</p></div>;
}

/** Renders `children` only when AVAILABLE; otherwise the state box. Used for every backend-dependent panel. */
export function Gate({ state, children, compact }: { state: Readiness; children: ReactNode; compact?: boolean }) {
  return state.state === "AVAILABLE" ? <>{children}</> : <StateBox state={state} compact={compact} />;
}

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Modal dialog: role=dialog, aria-modal, labelled, Esc closes, Tab is trapped, focus returns to the opener. */
export function Dialog({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  const id = useId(); const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>("[data-autofocus]") ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE);
    first?.focus();
    return () => opener?.focus?.();
  }, []);
  function onKey(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === "Escape") { e.stopPropagation(); onClose(); return; }
    if (e.key !== "Tab") return;
    const items = Array.from(ref.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    if (!items.length) return;
    const first = items[0], last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  return (
    <div className="bx-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={ref} className="bx-dialog" role="dialog" aria-modal="true" aria-labelledby={id} onKeyDown={onKey}>
        <h2 id={id}>{title}</h2>
        <div className="bx-dialog-body">{children}</div>
        <div className="bx-dialog-foot">{footer ?? <button type="button" className="bx-btn" onClick={onClose}>Đóng</button>}</div>
      </div>
    </div>
  );
}

export type TabItem = { id: string; label: string; badge?: string; badgeLabel?: string };

/** ARIA tabs (automatic activation): arrow keys / Home / End move focus and selection; only the selected tab is in the tab order. */
export function Tabs({ label, items, value, onChange, orientation = "horizontal", idPrefix }: {
  label: string; items: TabItem[]; value: string; onChange: (id: string) => void; orientation?: "horizontal" | "vertical"; idPrefix: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  function onKey(e: KeyboardEvent<HTMLDivElement>) {
    const prev = orientation === "vertical" ? "ArrowUp" : "ArrowLeft", next = orientation === "vertical" ? "ArrowDown" : "ArrowRight";
    const i = items.findIndex((t) => t.id === value);
    let to = -1;
    if (e.key === next) to = (i + 1) % items.length; else if (e.key === prev) to = (i - 1 + items.length) % items.length;
    else if (e.key === "Home") to = 0; else if (e.key === "End") to = items.length - 1;
    if (to < 0) return;
    e.preventDefault(); onChange(items[to].id);
    requestAnimationFrame(() => ref.current?.querySelector<HTMLElement>(`#${CSS.escape(`${idPrefix}-tab-${items[to].id}`)}`)?.focus());
  }
  return (
    <div ref={ref} role="tablist" aria-label={label} aria-orientation={orientation} className={`bx-tabs bx-tabs-${orientation}`} onKeyDown={onKey}>
      {items.map((t) => (
        <button key={t.id} type="button" role="tab" id={`${idPrefix}-tab-${t.id}`} aria-selected={t.id === value} aria-controls={`${idPrefix}-panel-${t.id}`}
          tabIndex={t.id === value ? 0 : -1} className={t.id === value ? "active" : ""} onClick={() => onChange(t.id)}>
          {t.label}{t.badge ? <><span className="bx-badge" {...(t.badgeLabel ? { "aria-hidden": true as const } : {})}>{t.badge}</span>{t.badgeLabel ? <span className="srOnly"> ({t.badgeLabel})</span> : null}</> : null}
        </button>
      ))}
    </div>
  );
}
export const tabPanelProps = (idPrefix: string, id: string) => ({ role: "tabpanel" as const, id: `${idPrefix}-panel-${id}`, "aria-labelledby": `${idPrefix}-tab-${id}`, tabIndex: 0 });

export function Field({ label, hint, children }: { label: string; hint?: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return <div className="bx-field"><label htmlFor={id}>{label}</label>{children(id)}{hint ? <small>{hint}</small> : null}</div>;
}
