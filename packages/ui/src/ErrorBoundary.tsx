"use client";
/**
 * Shared error boundary (M-006). A render exception used to unmount the whole portal (no `error.tsx`, no boundary anywhere).
 *
 *   <ErrorBoundary resetKeys={[route]}>…screen…</ErrorBoundary>      route-level: a new route clears the error
 *   <ErrorBoundary variant="inline" onReset={reload}>…panel…</ErrorBoundary>      a boundary around one panel (Builder, a table…)
 *   <ErrorFallback error={e} onRetry={reset}/>                         the same UI for Next's `error.tsx` / `global-error.tsx`
 *
 * The fallback never prints the message or the stack of the exception (they can hold internal details). It shows a plain Vietnamese explanation,
 * a "Thử lại" button, an optional link home and — when the error carries one — a reference code the person can quote to an administrator:
 * `ApiError.requestId`, else Next's `error.digest`. Focus moves to the fallback's heading when it appears (so keyboard / screen-reader users land on it).
 */
import { Component, useEffect, useRef, type ErrorInfo, type ReactNode } from "react";
// relative on purpose: the unit-test build resolves only relative runtime imports (same file as @xweb/api-client re-exports, so one reporter)
import { reportClientError } from "../../api-client/src/errorText";

/** a short reference code for support, from whatever the error carries; never the message / stack */
export function errorReference(error: unknown): string | null {
  const e = error as { requestId?: unknown; digest?: unknown } | null | undefined;
  const id = typeof e?.requestId === "string" && e.requestId ? e.requestId : typeof e?.digest === "string" && e.digest ? e.digest : null;
  return id ? id.slice(0, 64) : null;
}

export type FallbackProps = {
  error?: unknown; onRetry?: () => void;
  /** "page" = a whole page (<main>, centred); "inline" = a panel inside an existing page */
  variant?: "page" | "inline"; title?: string; homeHref?: string;
};

export function ErrorFallback({ error, onRetry, variant = "page", title = "Đã có lỗi xảy ra", homeHref }: FallbackProps) {
  const heading = useRef<HTMLHeadingElement>(null); const H = variant === "page" ? "h1" : "h2";
  useEffect(() => { heading.current?.focus(); }, []);
  const ref = errorReference(error);
  const body = (
    <div className="xp-errorBox">
      <div className="xp-errorIcon" aria-hidden="true">!</div>
      <H ref={heading} tabIndex={-1} className="xp-errorTitle">{title}</H>
      <p className="xp-errorText">Màn hình này gặp sự cố và không hiển thị được. Các thay đổi chưa lưu có thể đã mất. Hãy thử lại; nếu lỗi lặp lại, hãy gửi mã tham chiếu bên dưới cho quản trị viên.</p>
      {ref ? <p className="xp-errorRef">Mã tham chiếu: <code>{ref}</code></p> : null}
      <div className="xp-errorActions">
        {onRetry ? <button type="button" className="btn primary" onClick={onRetry}>Thử lại</button> : null}
        {homeHref ? <a className="btn" href={homeHref}>Về trang chính</a> : null}
      </div>
    </div>
  );
  return variant === "page" ? <main className="xp-errorPage" data-testid="error-fallback">{body}</main> : <section className="xp-errorInline" role="alert" data-testid="error-fallback">{body}</section>;
}

type Props = FallbackProps & {
  children: ReactNode;
  /** when any of these change the error is cleared (e.g. the route) */ resetKeys?: readonly unknown[];
  /** called after "Thử lại" cleared the error (reload data here) */ onReset?: () => void;
  /** report to logging; receives the real error (the UI never shows it) */ onError?: (error: Error, info: ErrorInfo) => void;
};
type State = { error: Error | null };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };
  static getDerivedStateFromError(error: Error): State { return { error: error ?? new Error("unknown") }; }
  componentDidCatch(error: Error, info: ErrorInfo) { reportClientError(error, "ErrorBoundary"); this.props.onError?.(error, info); }
  componentDidUpdate(prev: Props) {
    const a = prev.resetKeys, b = this.props.resetKeys;
    if (this.state.error && a && b && (a.length !== b.length || a.some((v, i) => !Object.is(v, b[i])))) this.setState({ error: null });
  }
  reset = () => { this.setState({ error: null }); this.props.onReset?.(); };
  render() {
    if (this.state.error) return <ErrorFallback error={this.state.error} onRetry={this.reset} variant={this.props.variant} title={this.props.title} homeHref={this.props.homeHref}/>;
    return this.props.children;
  }
}
