"use client";
import { useCallback, useEffect, useRef, useState } from "react";

/**
 * useLoad: fetch-on-mount with explicit loading / error state; `deps` re-run the loader. Stale responses are ignored.
 *
 * Backwards compatible: `useLoad(() => api.x(), [dep])` behaves exactly as before. Two additions, both opt-in:
 *  - the loader receives `{ signal }` (an AbortSignal): a run that is superseded (deps changed, `reload()`, unmount) is aborted, so a loader that passes the signal to `fetch` stops the request.
 *    A loader that ignores the argument is unaffected (its late result is still dropped, as before).
 *  - `options.key` turns on a small keyed stale-while-revalidate cache shared by every `useLoad` with the same key: a second screen that asks for the same list gets the last data at once
 *    (`data` is set on the first render) and revalidates it; concurrent requests for one key are de-duplicated (one network call). The key MUST contain every input of the request
 *    (workspace id, page, query...) and the signed-in identity must never be shared: call `clearLoadCache()` on sign-out / session change, `clearLoadCache("prefix")` after a write.
 *
 * `loading` stays true while a (re)validation is in flight, as before; screens that guard on `loading && !data` keep working and now skip the spinner when cached data exists.
 * `validating` is true while a request is in flight (also when data is already shown); `fromCache` says the current data came from the cache and has not been revalidated yet.
 */
export type LoadContext = { signal: AbortSignal };
export type UseLoadOptions = {
  /** opt in to the shared cache; include every request input in the key */
  key?: string;
  /** a cached entry younger than this (ms) is served WITHOUT a request (default 0: always revalidate, i.e. stale-while-revalidate). `reload()` always goes to the network. */
  freshMs?: number;
};

// ------------------------------------------------------------------------------------------------------------------------------ the cache (no React: unit-tested in node)
type Flight = { p: Promise<unknown>; ctl: AbortController; subs: number };
type Entry = { data: unknown; at: number; has: boolean; flight: Flight | null };
export type LoadCache = {
  peek<T>(key: string): { data: T; at: number } | undefined;
  /** the data for `key`: joins the request in flight (unless `force`), or starts `loader`. `signal` = this caller's abort: the shared request is aborted only when EVERY caller of it has aborted. */
  fetch<T>(key: string, loader: (ctx: LoadContext) => Promise<T>, signal: AbortSignal, opts?: { force?: boolean }): Promise<T>;
  set<T>(key: string, data: T): void;
  /** drop one key, or every key that starts with `prefix` (use `invalidate("projects:")` after a write), or everything. A request in flight for a dropped key is not stored when it lands. */
  invalidate(prefixOrKey?: string): void;
  size(): number;
};
const abortError = () => Object.assign(new Error("The load was aborted"), { name: "AbortError" });
export function createLoadCache(max = 200, now: () => number = Date.now): LoadCache {
  const map = new Map<string, Entry>();
  const entry = (k: string): Entry => {
    const e = map.get(k) ?? { data: undefined, at: 0, has: false, flight: null };
    map.delete(k); map.set(k, e);                                                       // most recently used last
    for (const [ok, oe] of map) { if (map.size <= max) break; if (!oe.flight && ok !== k) map.delete(ok); }
    return e;
  };
  return {
    peek<T>(key: string) { const e = map.get(key); return e?.has ? { data: e.data as T, at: e.at } : undefined; },
    fetch<T>(key: string, loader: (ctx: LoadContext) => Promise<T>, signal: AbortSignal, opts: { force?: boolean } = {}): Promise<T> {
      if (signal.aborted) return Promise.reject(abortError());                         // a caller that is already gone starts nothing
      const e = entry(key); let fl = e.flight;
      if (!fl || opts.force) {
        const ctl = new AbortController(); const nf: Flight = { p: Promise.resolve(), ctl, subs: 0 };
        nf.p = Promise.resolve().then(() => loader({ signal: ctl.signal })).then((d) => { if (e.flight === nf && map.get(key) === e) { e.data = d; e.has = true; e.at = now(); } return d; })
          .finally(() => { if (e.flight === nf) e.flight = null; });
        nf.p.catch(() => undefined);                                                    // every waiter handles its own copy; this only prevents an unhandled rejection after all waiters aborted
        e.flight = nf; fl = nf;
      }
      const flight = fl; flight.subs += 1;
      return new Promise<T>((resolve, reject) => {
        let done = false;
        const leave = () => { done = true; signal.removeEventListener("abort", onAbort); flight.subs = Math.max(0, flight.subs - 1); };
        const onAbort = () => { if (done) return; leave(); if (flight.subs === 0) flight.ctl.abort(); reject(abortError()); };
        if (signal.aborted) { onAbort(); return; }
        signal.addEventListener("abort", onAbort);
        (flight.p as Promise<T>).then((d) => { if (!done) { leave(); resolve(d); } }, (err) => { if (!done) { leave(); reject(err); } });
      });
    },
    set<T>(key: string, data: T) { const e = entry(key); e.data = data; e.has = true; e.at = now(); },
    invalidate(prefixOrKey?: string) { for (const k of [...map.keys()]) if (prefixOrKey === undefined || k.startsWith(prefixOrKey)) map.delete(k); },
    size() { return map.size; },
  };
}
const shared = createLoadCache();
/** forget every cached list (sign-out, tenant / workspace switch) or the keys starting with `prefix` (after a write) */
export const clearLoadCache = (prefix?: string): void => shared.invalidate(prefix);
/** the shared cache, for tests and for code that wants to prime it */
export const loadCache: LoadCache = shared;

// ------------------------------------------------------------------------------------------------------------------------------ the hook
export function useLoad<T>(loader: (ctx: LoadContext) => Promise<T>, deps: unknown[], options: UseLoadOptions = {}) {
  const { key, freshMs = 0 } = options;
  const first = key ? shared.peek<T>(key) : undefined;
  const [data, setData] = useState<T | null>(first ? first.data : null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [validating, setValidating] = useState(true);
  const [fromCache, setFromCache] = useState(!!first);
  const seq = useRef(0);
  const ctlRef = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const setDataAndCache = useCallback((v: T | null | ((p: T | null) => T | null)) => {
    setData((prev) => { const next = typeof v === "function" ? (v as (p: T | null) => T | null)(prev) : v; if (key && next !== null) shared.set(key, next); return next; });
  }, [key]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback((force: boolean) => {
    const id = ++seq.current; setError(null);
    ctlRef.current?.abort(); const ctl = new AbortController(); ctlRef.current = ctl;
    const cached = key ? shared.peek<T>(key) : undefined;
    if (cached) { setData(cached.data); setFromCache(true); }
    if (cached && !force && freshMs > 0 && Date.now() - cached.at < freshMs) { setLoading(false); setValidating(false); return; }
    setLoading(true); setValidating(true);
    const request = key ? shared.fetch<T>(key, loader, ctl.signal, { force }) : Promise.resolve().then(() => loader({ signal: ctl.signal }));
    request.then((d) => { if (id === seq.current && mounted.current) { setData(d); setFromCache(false); } })
      .catch((e) => { if (id === seq.current && mounted.current) setError(e); })
      .finally(() => { if (id === seq.current && mounted.current) { setLoading(false); setValidating(false); } });
  }, deps);
  useEffect(() => { run(false); }, [run]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; ctlRef.current?.abort(); }; }, []);
  const reload = useCallback(() => run(true), [run]);
  return { data, error, loading, validating, fromCache, reload, setData: setDataAndCache };
}
