"use client";
/**
 * Shared toast (M-016) — an IMPERATIVE API, no provider needed. Replaces the per-screen `<button class="toast">` that had no live region, no timeout,
 * no dismiss control and replaced the previous message silently.
 *
 *   import { toast } from "@xweb/ui";                (or `const toast = useToast()` — the same object)
 *   toast.success("Đã lưu cài đặt.");                 auto-dismisses after 6 s (paused while hovered / focused)
 *   toast.error("Không lưu được.", { title: "Lỗi" }); persists until dismissed, announced assertively
 *   const id = toast.show({ kind: "info", message, duration: null });  toast.dismiss(id);
 *
 * Contract
 *  - the first call (or `ensureToastHost()`, also scheduled when the module loads in a browser) mounts a host in its OWN React root on `document.body`: it lives outside every
 *    portal's tree, above every overlay (`--ui-z-toast`), and is never made `inert` by a modal / drawer. The very first message is added ~120 ms after the host appears,
 *    so the live regions already exist when it arrives (an `aria-live` region only announces content INSERTED into an existing region);
 *  - live regions: `role="status"` (polite) for success / info / warning, `role="alert"` (assertive) for errors;
 *  - a message is never lost: at most 3 are visible, the rest wait in a queue ("+n thông báo đang chờ") and appear as the visible ones go; re-using an `id` updates that toast in place;
 *  - errors never time out; success / info 6 s, warning 9 s; the timer pauses while the pointer or keyboard focus is on the toast (WCAG 2.2.1); Escape on a focused toast dismisses it;
 *  - every toast has a "Đóng thông báo" button (32 px target) and an optional single `action`.
 */
import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from "./icons";

export type ToastKind = "success" | "info" | "warning" | "error";
export type ToastInput = {
  /** re-using an id updates that toast (and restarts its timer) instead of adding another */
  id?: string; kind?: ToastKind; message: ReactNode; title?: string;
  /** ms; `null` = stays until dismissed. Default: error null, warning 9000, others 6000 */
  duration?: number | null; action?: { label: string; onClick: () => void };
};
export type Toast = { id: string; kind: ToastKind; message: ReactNode; title?: string; duration: number | null; action?: ToastInput["action"]; seq: number };
export type ToastState = { visible: Toast[]; queue: Toast[] };
export type ToastAction = { type: "add"; toast: Toast } | { type: "dismiss"; id: string } | { type: "clear" };

export const DEFAULT_DURATIONS: Record<ToastKind, number | null> = { success: 6000, info: 6000, warning: 9000, error: null };
export const MAX_VISIBLE = 3;

/** pure state machine (unit-tested): visible list + FIFO queue */
export function toastReducer(state: ToastState, a: ToastAction, max = MAX_VISIBLE): ToastState {
  if (a.type === "clear") return { visible: [], queue: [] };
  if (a.type === "dismiss") {
    const visible = state.visible.filter((t) => t.id !== a.id); const queue = state.queue.filter((t) => t.id !== a.id);
    while (visible.length < max && queue.length) visible.push(queue.shift()!);
    return { visible, queue };
  }
  const t = a.toast;
  if (state.visible.some((x) => x.id === t.id)) return { ...state, visible: state.visible.map((x) => (x.id === t.id ? t : x)) };
  if (state.queue.some((x) => x.id === t.id)) return { ...state, queue: state.queue.map((x) => (x.id === t.id ? t : x)) };
  if (state.visible.length < max) return { ...state, visible: [...state.visible, t] };
  return { ...state, queue: [...state.queue, t] };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ the store (module singleton)
const EMPTY: ToastState = { visible: [], queue: [] };
let state: ToastState = EMPTY;
const listeners = new Set<() => void>();
let durations: Record<ToastKind, number | null> = { ...DEFAULT_DURATIONS };
let seq = 0, auto = 0, hostRoot: ReturnType<typeof createRoot> | null = null, hostMountedAt = 0;
const emit = () => listeners.forEach((l) => l());
const dispatch = (a: ToastAction) => { state = toastReducer(state, a); emit(); };
const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };

/** mounts the host once, in its own root on document.body (browser only); safe to call any number of times */
export function ensureToastHost(): void {
  if (hostRoot || typeof document === "undefined") return;
  const el = document.createElement("div"); el.setAttribute("data-xp-toast-host", ""); document.body.appendChild(el);
  hostRoot = createRoot(el); hostMountedAt = Date.now();
  hostRoot.render(<ToastHost/>);
}
if (typeof document !== "undefined") setTimeout(ensureToastHost, 0);

function show(i: ToastInput): string {
  const kind = i.kind ?? "info"; const id = i.id ?? `t${++auto}`;
  const t: Toast = { id, kind, message: i.message, title: i.title, action: i.action, duration: i.duration === undefined ? durations[kind] : i.duration, seq: ++seq };
  ensureToastHost();
  // right after the host appeared its live regions are not registered by assistive technology yet: hold the very first message back a moment
  const wait = typeof document === "undefined" ? 0 : Math.max(0, 120 - (Date.now() - hostMountedAt));
  if (wait > 0) setTimeout(() => dispatch({ type: "add", toast: t }), wait); else dispatch({ type: "add", toast: t });
  return id;
}
type Opts = Omit<ToastInput, "message" | "kind">;
export const toast = {
  show, dismiss: (id: string) => dispatch({ type: "dismiss", id }), clear: () => dispatch({ type: "clear" }),
  success: (message: ReactNode, o?: Opts) => show({ ...o, message, kind: "success" }), info: (message: ReactNode, o?: Opts) => show({ ...o, message, kind: "info" }),
  warning: (message: ReactNode, o?: Opts) => show({ ...o, message, kind: "warning" }), error: (message: ReactNode, o?: Opts) => show({ ...o, message, kind: "error" }),
  /** override the auto-dismiss times (tests, kiosks); `configure()` restores the defaults */
  configure: (d?: Partial<Record<ToastKind, number | null>>) => { durations = { ...DEFAULT_DURATIONS, ...d }; },
  /** the current state (tests) */
  snapshot: (): ToastState => state,
};
export type ToastApi = typeof toast;
/** the same imperative object, for components that prefer a hook; no provider is needed */
export const useToast = (): ToastApi => toast;

// ------------------------------------------------------------------------------------------------------------------------------------------------ rendering
const KIND_LABEL: Record<ToastKind, string> = { success: "Thành công", info: "Thông báo", warning: "Cảnh báo", error: "Lỗi" };
const ICON = { success: CircleCheck, info: Info, warning: TriangleAlert, error: CircleAlert } as const;

function ToastItem({ t, onDismiss }: { t: Toast; onDismiss: (id: string) => void }) {
  const [paused, setPaused] = useState(false);
  const left = useRef<number | null>(t.duration); const started = useRef(0);
  useEffect(() => { left.current = t.duration; }, [t.duration, t.seq]);       // an update restarts the timer
  useEffect(() => {
    if (left.current === null || paused) return;
    started.current = Date.now();
    const timer = setTimeout(() => onDismiss(t.id), left.current);
    return () => { clearTimeout(timer); if (left.current !== null) left.current = Math.max(0, left.current - (Date.now() - started.current)); };
  }, [paused, t.id, t.seq, onDismiss]);
  const Icon = ICON[t.kind];
  return (
    <div className={`xp-toast xp-toast-${t.kind}`} data-kind={t.kind} data-testid={`toast:${t.id}`}
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setPaused(false); }}
      onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); onDismiss(t.id); } }}>
      <Icon size={18} aria-hidden="true" className="xp-toastIcon"/>
      <div className="xp-toastBody"><span className="srOnly">{KIND_LABEL[t.kind]}: </span>{t.title ? <b className="xp-toastTitle">{t.title}</b> : null}<span className="xp-toastMsg">{t.message}</span></div>
      {t.action ? <button type="button" className="xp-toastAction" onClick={() => { t.action!.onClick(); onDismiss(t.id); }}>{t.action.label}</button> : null}
      <button type="button" className="xp-toastClose" aria-label="Đóng thông báo" onClick={() => onDismiss(t.id)}><X size={16} aria-hidden="true"/></button>
    </div>
  );
}

/** the two live regions + the stack; exported for tests */
export function ToastViewport({ state: s, onDismiss }: { state: ToastState; onDismiss: (id: string) => void }) {
  const errors = s.visible.filter((t) => t.kind === "error"); const others = s.visible.filter((t) => t.kind !== "error");
  return (
    <div className="xp-toasts" data-testid="toasts">
      <div className="xp-toastList" role="alert">{errors.map((t) => <ToastItem key={t.id} t={t} onDismiss={onDismiss}/>)}</div>
      <div className="xp-toastList" role="status" aria-live="polite">
        {others.map((t) => <ToastItem key={t.id} t={t} onDismiss={onDismiss}/>)}
        {s.queue.length ? <div className="xp-toastMore">{`+${s.queue.length} thông báo đang chờ`}</div> : null}
      </div>
    </div>
  );
}

/** the host connected to the store (mounted by `ensureToastHost`; also usable in a test or SSR) */
export function ToastHost() {
  const s = useSyncExternalStore(subscribe, () => state, () => EMPTY);
  return <ToastViewport state={s} onDismiss={toast.dismiss}/>;
}
