"use client";
/** Small accessible building blocks of the Builder. Relative imports only (these are SSR-tested). */
import { useEffect, useId, useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from "react";
import type { Readiness } from "../core/readiness";
import { acquireOverlay } from "../../../../packages/ui/src/overlay";
import { tabbables, trapTab } from "../../../../packages/ui/src/focus";

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

/** Modal dialog on the SHARED overlay stack (M-021 / M-023): role=dialog, aria-modal, labelled; Escape (document level, only the TOP overlay: a nested dialog closes alone), Tab trapped to what a person can use,
 *  page scroll locked and restored in any closing order, focus returns to the opener; a press that starts inside and ends on the backdrop never closes it. */
export function Dialog({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  const id = useId(); const ref = useRef<HTMLDivElement>(null); const closeRef = useRef(onClose); closeRef.current = onClose;
  useEffect(() => {
    const node = ref.current; if (!node) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overlay = acquireOverlay();
    (node.querySelector<HTMLElement>("[data-autofocus]") ?? tabbables(node)[0] ?? node).focus();
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (!overlay.isTop()) return;
      if (e.key === "Escape") { if (e.defaultPrevented) return; e.preventDefault(); closeRef.current(); return; }
      trapTab(e, node);
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); overlay.release(); if (opener?.isConnected) opener.focus?.(); };
  }, []);
  const down = useRef(false);
  return (
    <div className="bx-overlay" onMouseDown={(e) => { down.current = e.target === e.currentTarget; }} onMouseUp={(e) => { const hit = down.current && e.target === e.currentTarget; down.current = false; if (hit) onClose(); }}>
      <div ref={ref} className="bx-dialog" role="dialog" aria-modal="true" aria-labelledby={id} tabIndex={-1}>
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
