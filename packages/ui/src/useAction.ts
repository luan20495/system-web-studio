"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/**
 * useAction: one user action (a button, a form submit, a row command) that talks to the server, with the three guarantees every such control needs and that were hand-written (or missing) per screen:
 *  1. SINGLE-FLIGHT per key, decided from a REF, not from React state: a double click, Enter + click or a second tab key press in the same tick starts ONE call. (A `busy` state only
 *     disables the button after the next render; two events can arrive before it.) The second call is answered `{ status: "skipped" }` and sends nothing.
 *  2. busy / error state that always comes back: a rejected call clears `busy`, stores the error, and the control is enabled again. `run` NEVER rejects, so `onClick={() => void act.run(id)}` cannot
 *     produce an unhandled rejection; read the outcome from the returned result or from `error`.
 *  3. optional IDEMPOTENCY KEY: with `idempotencyKey: true` each key gets a stable id that is passed to the function and is REUSED by a retry after a failure (the server may have written it:
 *     "unknown whether it was saved") and replaced only after a success. The caller sends it as the Idempotency-Key header / body field; a server that ignores it is unaffected.
 * Unmount-safe: after unmount nothing is set; a call in flight is not cancelled (a write that was sent still happens), its result is just not stored.
 *
 * Usage:  const del = useAction((ctx, id: string) => api.remove(id, ctx.idempotencyKey), { keyOf: (id) => id, idempotencyKey: true });
 *         <button disabled={del.isBusy(row.id)} onClick={() => void del.run(row.id)}>      // the first argument of the function is the context, `run` takes the rest
 */
export type ActionContext = { /** the single-flight key of this call ("" when `keyOf` is not given) */ key: string; /** stable across retries of the same key until a success; undefined unless `idempotencyKey` is on */ idempotencyKey: string | undefined; /** 1 for the first try of a key, 2 for a retry after a failure... */ attempt: number };
export type ActionResult<R> = { status: "ok"; value: R } | { status: "error"; error: unknown } | { status: "skipped" };
export type UseActionOptions<A extends unknown[]> = {
  /** which calls are "the same one": default every call (one flight at a time). Give the target id to allow different rows in parallel. */
  keyOf?: (...args: A) => string;
  /** generate and reuse an idempotency key per key (see above); a function lets the caller choose the generator (default `crypto.randomUUID`, with a fallback) */
  idempotencyKey?: boolean | (() => string);
};
export type ActionState = { busy: boolean; busyKeys: readonly string[]; error: unknown; errorKey: string | null };

const uuid = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`);

// ------------------------------------------------------------------------------------------------------------------------------ the runner (no React: unit-tested in node)
export type ActionRunner<A extends unknown[], R> = {
  run(...args: A): Promise<ActionResult<R>>;
  isBusy(key?: string): boolean;
  state(): ActionState;
  reset(): void;
  /** stop / resume reporting state changes (unmount / StrictMode re-mount) */
  detach(): void;
  attach(): void;
};
export function createActionRunner<A extends unknown[], R>(fn: (ctx: ActionContext, ...args: A) => Promise<R>, options: UseActionOptions<A> = {}, onChange: (s: ActionState) => void = () => undefined): ActionRunner<A, R> {
  const flights = new Set<string>();                         // the REF: synchronous, updated before anything can run again
  const attempts = new Map<string, { idem: string | undefined; n: number }>();
  let error: unknown = null; let errorKey: string | null = null; let attached = true;
  const snapshot = (): ActionState => ({ busy: flights.size > 0, busyKeys: [...flights], error, errorKey });
  const emit = () => { if (attached) onChange(snapshot()); };
  const gen = typeof options.idempotencyKey === "function" ? options.idempotencyKey : options.idempotencyKey ? uuid : null;
  return {
    async run(...args: A): Promise<ActionResult<R>> {
      const key = options.keyOf ? options.keyOf(...args) : "";
      if (flights.has(key)) return { status: "skipped" };
      flights.add(key);
      const prev = attempts.get(key); const slot = prev ?? { idem: gen ? gen() : undefined, n: 0 };
      slot.n += 1; attempts.set(key, slot); error = null; errorKey = null; emit();
      try {
        const value = await fn({ key, idempotencyKey: slot.idem, attempt: slot.n }, ...args);
        attempts.delete(key);                                // a success ends the retry series: the next call is a new operation with a new key
        return { status: "ok", value };
      } catch (e) {
        error = e; errorKey = key;                           // the idempotency key is kept for the retry
        return { status: "error", error: e };
      } finally {
        flights.delete(key); emit();
      }
    },
    isBusy: (key) => (key === undefined ? flights.size > 0 : flights.has(key)),
    state: snapshot,
    reset() { error = null; errorKey = null; emit(); },
    detach() { attached = false; },
    attach() { attached = true; },
  };
}

// ------------------------------------------------------------------------------------------------------------------------------ the hook
export function useAction<A extends unknown[], R>(fn: (ctx: ActionContext, ...args: A) => Promise<R>, options: UseActionOptions<A> = {}) {
  const fnRef = useRef(fn); fnRef.current = fn;                                 // always the latest closure, without changing `run`'s identity
  const optRef = useRef(options); optRef.current = options;
  const [state, setState] = useState<ActionState>({ busy: false, busyKeys: [], error: null, errorKey: null });
  const runner = useMemo(() => createActionRunner<A, R>((ctx, ...a) => fnRef.current(ctx, ...a), {
    keyOf: (...a: A) => (optRef.current.keyOf ? optRef.current.keyOf(...a) : ""),
    idempotencyKey: options.idempotencyKey,
  }, setState), []);          // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { runner.attach(); return () => runner.detach(); }, [runner]);
  const run = useCallback((...a: A) => runner.run(...a), [runner]);
  const isBusy = useCallback((key?: string) => (key === undefined ? state.busy : state.busyKeys.includes(key)), [state]);
  return { run, busy: state.busy, isBusy, error: state.error, errorKey: state.errorKey, reset: runner.reset };
}
