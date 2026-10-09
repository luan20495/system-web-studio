import {
  createContext, useCallback, useContext, useEffect, useId, useRef, useState,
  type ButtonHTMLAttributes, type FormHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes
} from "react";
import { sp, type Space, type Tone } from "./tokens";
export { spacing, tones, type Space, type Tone } from "./tokens";

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

// ---------------------------------------------------------------- Layout
export type StackProps = { children?: ReactNode; gap?: Space; padding?: Space; direction?: "row" | "column"; align?: "start" | "center" | "end" | "stretch";
  wrap?: boolean; hidden?: boolean; className?: string };
/** Flex layout with token spacing. */
export function Stack({ children, gap = "md", padding, direction = "column", align, wrap, hidden, className }: StackProps) {
  return <div hidden={hidden} className={cx("cu-stack", className)} style={{ display: hidden ? undefined : "flex", flexDirection: direction, gap: sp(gap), padding: sp(padding),
    alignItems: align === "start" ? "flex-start" : align === "end" ? "flex-end" : align, flexWrap: wrap ? "wrap" : undefined }}>{children}</div>;
}
export type LayoutProps = { header?: ReactNode; sidebar?: ReactNode; children?: ReactNode; padding?: Space };
/** Page shell: optional header and sidebar around a main region. */
export function Layout({ header, sidebar, children, padding = "lg" }: LayoutProps) {
  return <div className={cx("cu-layout", sidebar ? "cu-layout-side" : undefined)}>
    {header ? <header className="cu-layout-header">{header}</header> : null}
    {sidebar ? <aside className="cu-layout-sidebar">{sidebar}</aside> : null}
    <main className="cu-layout-main" style={{ padding: sp(padding) }}>{children}</main>
  </div>;
}

// ---------------------------------------------------------------- Navigation
export type NavItem = { label: string; href: string; current?: boolean };
export type NavbarProps = { brand: string; items?: NavItem[]; actions?: ReactNode; hidden?: boolean };
export function Navbar({ brand, items = [], actions, hidden }: NavbarProps) {
  return <nav hidden={hidden} className="cu-navbar" aria-label="Chính">
    <span className="cu-brand">{brand}</span>
    <ul className="cu-nav-items">{items.map((i) => <li key={i.href}><a href={i.href} aria-current={i.current ? "page" : undefined}>{i.label}</a></li>)}</ul>
    {actions ? <div className="cu-nav-actions">{actions}</div> : null}
  </nav>;
}
export type SidebarProps = { title?: string; items: NavItem[]; hidden?: boolean };
export function Sidebar({ title, items, hidden }: SidebarProps) {
  return <nav hidden={hidden} className="cu-sidebar" aria-label={title ?? "Điều hướng"}>
    {title ? <p className="cu-sidebar-title">{title}</p> : null}
    <ul>{items.map((i) => <li key={i.href}><a href={i.href} aria-current={i.current ? "page" : undefined}>{i.label}</a></li>)}</ul>
  </nav>;
}

// ---------------------------------------------------------------- Actions & surfaces
export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone; variant?: "solid" | "outline" | "ghost"; size?: "sm" | "md" | "lg" };
export function Button({ tone = "primary", variant = "solid", size = "md", className, type = "button", ...rest }: ButtonProps) {
  return <button type={type} className={cx("cu-btn", `cu-btn-${variant}`, `cu-tone-${tone}`, `cu-size-${size}`, className)} {...rest}/>;
}
export type CardProps = { title?: string; children?: ReactNode; footer?: ReactNode; padding?: Space; hidden?: boolean };
export function Card({ title, children, footer, padding = "lg", hidden }: CardProps) {
  return <section hidden={hidden} className="cu-card" style={{ padding: sp(padding) }} aria-label={title}>
    {title ? <h2 className="cu-card-title">{title}</h2> : null}<div>{children}</div>{footer ? <div className="cu-card-footer">{footer}</div> : null}
  </section>;
}

// ---------------------------------------------------------------- Forms
type FieldShell = { label: string; hint?: string; error?: string };
function Field({ id, label, hint, error, children }: FieldShell & { id: string; children: ReactNode }) {
  return <div className={cx("cu-field", error && "cu-field-error")}>
    <label htmlFor={id}>{label}</label>{children}
    {hint && !error ? <small id={`${id}-hint`}>{hint}</small> : null}{error ? <small id={`${id}-error`} role="alert">{error}</small> : null}
  </div>;
}
export type InputProps = Omit<InputHTMLAttributes<HTMLInputElement>, "id"> & FieldShell;
export function Input({ label, hint, error, ...rest }: InputProps) {
  const id = useId();
  return <Field id={id} label={label} hint={hint} error={error}><input id={id} aria-invalid={!!error} aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined} {...rest}/></Field>;
}
export type SelectProps = Omit<SelectHTMLAttributes<HTMLSelectElement>, "id"> & FieldShell & { options: { value: string; label: string }[] };
export function Select({ label, hint, error, options, ...rest }: SelectProps) {
  const id = useId();
  return <Field id={id} label={label} hint={hint} error={error}><select id={id} aria-invalid={!!error} {...rest}>{options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select></Field>;
}
export type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "id"> & { label: string };
export function Checkbox({ label, ...rest }: CheckboxProps) {
  const id = useId();
  return <div className="cu-check"><input id={id} type="checkbox" {...rest}/><label htmlFor={id}>{label}</label></div>;
}
export type FormProps = FormHTMLAttributes<HTMLFormElement> & { gap?: Space };
/** Form with token spacing; submit handlers get native validation for free. */
export function Form({ gap = "md", children, className, ...rest }: FormProps) {
  return <form className={cx("cu-form", className)} style={{ display: "grid", gap: sp(gap) }} {...rest}>{children}</form>;
}

// ---------------------------------------------------------------- Data
export type Column<T> = { key: keyof T & string; header: string; render?: (row: T) => ReactNode };
export type TableProps<T> = { caption: string; columns: Column<T>[]; rows: T[]; rowKey: (row: T) => string; empty?: string };
export function Table<T>({ caption, columns, rows, rowKey, empty = "Không có dữ liệu" }: TableProps<T>) {
  return <div className="cu-table-wrap"><table className="cu-table"><caption>{caption}</caption>
    <thead><tr>{columns.map((c) => <th key={c.key} scope="col">{c.header}</th>)}</tr></thead>
    <tbody>{rows.length === 0 ? <tr><td colSpan={columns.length}>{empty}</td></tr> : rows.map((r) => <tr key={rowKey(r)}>{columns.map((c) => <td key={c.key}>{c.render ? c.render(r) : String(r[c.key] ?? "")}</td>)}</tr>)}</tbody>
  </table></div>;
}

// ---------------------------------------------------------------- Tabs
export type TabsProps = { label: string; tabs: { id: string; title: string; content: ReactNode }[]; initial?: string };
/** WAI-ARIA tabs: arrow keys move between tabs. */
export function Tabs({ label, tabs, initial }: TabsProps) {
  const [active, setActive] = useState(initial ?? tabs[0]?.id); const base = useId();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const move = (i: number) => { const n = (i + tabs.length) % tabs.length; setActive(tabs[n].id); refs.current[n]?.focus(); };
  return <div className="cu-tabs">
    <div role="tablist" aria-label={label}>{tabs.map((t, i) => <button key={t.id} ref={(el) => { refs.current[i] = el; }} role="tab" id={`${base}-${t.id}`} aria-selected={active === t.id}
      aria-controls={`${base}-${t.id}-panel`} tabIndex={active === t.id ? 0 : -1} onClick={() => setActive(t.id)}
      onKeyDown={(e) => { if (e.key === "ArrowRight") move(i + 1); if (e.key === "ArrowLeft") move(i - 1); }}>{t.title}</button>)}</div>
    {tabs.map((t) => <div key={t.id} role="tabpanel" id={`${base}-${t.id}-panel`} aria-labelledby={`${base}-${t.id}`} hidden={active !== t.id}>{t.content}</div>)}
  </div>;
}

// ---------------------------------------------------------------- Dialog / Modal
export type DialogProps = { open: boolean; title: string; onClose: () => void; children?: ReactNode; footer?: ReactNode };
/** Modal dialog: focus moves in and is trapped, Escape and the backdrop close it, focus returns to the opener. */
export function Dialog({ open, title, onClose, children, footer }: DialogProps) {
  const ref = useRef<HTMLDivElement>(null); const titleId = useId();
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    const focusables = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ?? []);
    (focusables()[0] ?? ref.current)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); onClose(); }
      if (e.key === "Tab") { const f = focusables(); if (!f.length) return; const first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); } else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); } }
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); opener?.focus(); };
  }, [open, onClose]);
  if (!open) return null;
  return <div className="cu-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
    <div ref={ref} className="cu-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
      <h2 id={titleId}>{title}</h2><div className="cu-dialog-body">{children}</div>{footer ? <div className="cu-dialog-footer">{footer}</div> : null}
    </div>
  </div>;
}
export type ModalProps = Omit<DialogProps, "footer"> & { confirmLabel?: string; cancelLabel?: string; onConfirm?: () => void; tone?: Tone };
/** Confirm-style dialog built on Dialog. */
export function Modal({ confirmLabel = "Đồng ý", cancelLabel = "Hủy", onConfirm, tone = "primary", ...rest }: ModalProps) {
  return <Dialog {...rest} footer={<><Button variant="ghost" tone="neutral" onClick={rest.onClose}>{cancelLabel}</Button>
    {onConfirm ? <Button tone={tone} onClick={onConfirm}>{confirmLabel}</Button> : null}</>}/>;
}

// ---------------------------------------------------------------- Toast
type ToastItem = { id: number; message: string; tone: Tone };
const ToastCtx = createContext<((message: string, tone?: Tone) => void) | null>(null);
/** Wrap the app once; then `useToast()(message)` shows a polite, auto-dismissing notification. */
export function ToastProvider({ children, duration = 4000 }: { children?: ReactNode; duration?: number }) {
  const [items, setItems] = useState<ToastItem[]>([]); const next = useRef(1);
  const push = useCallback((message: string, tone: Tone = "neutral") => {
    const id = next.current++; setItems((x) => [...x, { id, message, tone }]);
    setTimeout(() => setItems((x) => x.filter((t) => t.id !== id)), duration);
  }, [duration]);
  return <ToastCtx.Provider value={push}>{children}
    <div className="cu-toasts" role="status" aria-live="polite">{items.map((t) => <div key={t.id} className={cx("cu-toast", `cu-tone-${t.tone}`)}>{t.message}</div>)}</div>
  </ToastCtx.Provider>;
}
export function useToast() { const c = useContext(ToastCtx); if (!c) throw new Error("useToast needs <ToastProvider>"); return c; }

// ---------------------------------------------------------------- Text
export type HeadingProps = { children?: ReactNode; level?: 1 | 2 | 3; hidden?: boolean };
export function Heading({ children, level = 1, hidden }: HeadingProps) {
  const T = (`h${level}`) as "h1" | "h2" | "h3"; return <T hidden={hidden} className={`cu-h${level}`}>{children}</T>;
}
export type TextProps = { children?: ReactNode; tone?: "default" | "muted"; hidden?: boolean };
export function Text({ children, tone = "default", hidden }: TextProps) { return <p hidden={hidden} className={cx("cu-text", tone === "muted" && "cu-muted")}>{children}</p>; }

export const version = "1.0.0";
