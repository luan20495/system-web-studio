"use client";
/**
 * The loading / empty / error / ready ladder every data screen needs (M-056 / M-041: a failed secondary load used to leave a spinner or "…" forever).
 *
 *   const rows = useLoad(() => api.x(), []);
 *   <LoadGate load={rows} label="danh sách người dùng" isEmpty={(d) => d.length === 0} empty={{ title: "Chưa có người dùng" }}>
 *     {(d) => <Table rows={d}/>}
 *   </LoadGate>
 *
 * Rungs, in order:
 *   1. an error and nothing to show      -> ErrorState with a retry that calls `load.reload` (never a retry on 404 / 403 / expired session)
 *   2. nothing yet                        -> a loading status that says WHAT is loading
 *   3. data, but empty                    -> the empty state (title, optional detail and action)
 *   4. data                               -> children(data); if a later refresh failed, the data stays and a banner says so with a retry (stale data is not hidden)
 * `level={1}` when the gate IS the whole page (a page needs exactly one h1); `compact` for a KPI / chip / small panel (a one-line state, no heading).
 * Only `{data, error, loading, reload}` of useLoad are read, so any loader that returns that shape works.
 */
import type { ReactNode } from "react";
import { ErrorState, StateView, retryHelps, stateOf } from "./States";
import { errorParts } from "../../api-client/src/errorText";

export type LoadState<T> = { data: T | null; error: unknown; loading: boolean; reload: () => void };
export type EmptyState = { title?: string; detail?: ReactNode; action?: ReactNode };

export function LoadGate<T>({ load, children, isEmpty, empty, label, level = 2, compact = false, errorTitle }: {
  load: LoadState<T>; children: (data: T) => ReactNode;
  isEmpty?: (data: T) => boolean; empty?: EmptyState;
  /** what is being loaded, in the words of the screen: "Đang tải danh sách người dùng…" */
  label?: string; level?: 1 | 2; compact?: boolean; errorTitle?: string;
}) {
  const { data, error, loading, reload } = load;
  const failed = error !== null && error !== undefined;
  if (data == null) {
    if (failed) return <ErrorState error={error} retry={reload} level={level} compact={compact} title={errorTitle}/>;
    return <StateView kind="loading" level={level} compact={compact} title={label ? `Đang tải ${label}…` : undefined}/>;
  }
  if (isEmpty?.(data)) {
    return <StateView kind="empty" level={level} compact={compact} title={empty?.title} detail={empty?.detail} action={empty?.action}/>;
  }
  return (
    <>
      {failed ? <StaleBanner error={error} onRetry={reload} busy={loading}/> : null}
      {children(data)}
    </>
  );
}

/** the data on screen is the last good copy and the refresh failed: say so, keep the data, offer the retry */
export function StaleBanner({ error, onRetry, busy }: { error: unknown; onRetry?: () => void; busy?: boolean }) {
  const p = errorParts(error); const kind = stateOf(error);
  return (
    <div className="xp-stateBanner" role="alert">
      <span>Chưa cập nhật được dữ liệu mới, đang hiển thị bản đã tải trước đó. {p.message}{p.reference ? ` (Mã tham chiếu: ${p.reference})` : ""}</span>
      {onRetry && retryHelps(kind) ? <button className="btn sm" type="button" onClick={onRetry} disabled={busy} aria-busy={busy || undefined}>Thử lại</button> : null}
    </div>
  );
}
