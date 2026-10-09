"use client";
import { useCallback, useState } from "react";
import { useAction, errorText } from "@xweb/ui";

/**
 * The one way an Admin screen runs a command (a button, a form submit, a row action) (M-020): single-flight from a ref (a double click / Enter + click sends ONE request), `busy`,
 * the failure as words (`err`, through the shared errorText: never an Error.message of a non-ApiError), and the success text (`msg`). Replaces the hand-written
 * `async function act(fn) { setBusy(true); try { await fn(); reload(); } catch { setErr(errText(...)) } finally { setBusy(false) } }` copies.
 *
 *   const { act, busy, msg, err } = useAdminAction("Không lưu được.", reload);
 *   <button disabled={busy} onClick={() => void act(() => api.x(), "Đã lưu.")}>
 *
 * `act` resolves `{ status: "ok" | "error" | "skipped" }` and never rejects, so `.then((r) => r.status === "ok" && close())` is the way to act on success.
 * `onDone` runs after a success (reload the list).
 */
/**
 * Single-flight for a handler that already reports its own result (a dialog that shows its own error): `once(async () => { ... })` runs the body unless a previous run is still going
 * (decided from a ref, so two clicks / Enter + click in the same tick run it ONCE). Built on the shared useAction.
 */
export function useSingleFlight() {
  const a = useAction(async (_ctx, fn: () => Promise<unknown>) => { await fn(); });
  return a.run;
}

/** the success sentence, or a function of what the call answered */
export type OkText = string | ((value: any) => string);
export function useAdminAction(fallback: string, onDone?: () => void) {
  const [msg, setMsg] = useState<string | null>(null);
  const a = useAction(async (_ctx, fn: () => Promise<unknown>, ok?: OkText) => { const value = await fn(); if (ok) setMsg(typeof ok === "function" ? ok(value) : ok); onDone?.(); });
  const { run, reset } = a;
  const act = useCallback((fn: () => Promise<unknown>, ok?: OkText) => { setMsg(null); reset(); return run(fn, ok); }, [run, reset]);
  const err = a.error ? errorText(a.error, fallback) : null;
  return { act, busy: a.busy, msg, err, clear: () => { setMsg(null); reset(); } };
}
