"use client";
/**
 * The save / busy state machine of the project workspace (extracted from `ProjectWorkspace`, M-048; behaviour unchanged).
 * `run` wraps every request that writes (or asks the AI): it sets `busy`, drives the "Đang lưu / Đã lưu / Lưu thất bại" state, maps failures to a notice
 * (`describeRunFailure`) and remembers whether a failed edit may be retried. Leaving while an edit is in flight or failed would lose it silently, so
 * `beforeunload` is armed meanwhile.
 */
import { useEffect, useRef, useState } from "react";
import { toast } from "@xweb/ui";
import type { SchemaOperation } from "@/lib/http-types";
import type { DefinitionOperation } from "@xweb/types";
import { describeRunFailure } from "./runFailure";

export type SaveState = { state: "saved" | "saving" | "error"; at: Date | null };
export type FailedEdit = { ops: (SchemaOperation | DefinitionOperation)[]; summary: string; blockId?: string };

export function useSaveMachine() {
  const [busy, setBusy] = useState<string | null>(null);
  const [save, setSave] = useState<SaveState>({ state: "saved", at: null });
  /** the last edit that could not be saved (network / 5xx / timeout), kept so the user can retry it instead of redoing the work */
  const [failedEdit, setFailedEdit] = useState<FailedEdit | null>(null);
  const saveFailureRef = useRef<"none" | "retryable">("none");
  /** M-044: what the person was last told about a failed save (the toast text), so a dialog can show the same reason itself */
  const lastNoticeRef = useRef<string | null>(null);
  /** set by the host: how to reload the document after a REVISION_CONFLICT */
  const onConflict = useRef<() => Promise<unknown>>(async () => undefined);

  /** M-020: ONE write / AI request at a time, decided from a ref (a `busy` state only disables the buttons after the next render, so a double click or Enter + click in the same tick sent two). */
  const flight = useRef<string | null>(null);

  async function run<T>(label: string, fn: () => Promise<T>, fallback: string): Promise<T | undefined> {
    if (flight.current !== null) return undefined;      // callers that must tell "skipped" from "failed" test `flight.current` first (applyOps does)
    flight.current = label;
    // An AI request is not a save: while it waits for the model nothing is being written, so the top bar keeps saying what is true ("saved") and a failed/cancelled request is not "Lưu thất bại".
    const isSave = label !== "prompt";
    setBusy(label); if (isSave) setSave((x) => ({ ...x, state: "saving" }));
    saveFailureRef.current = "none"; lastNoticeRef.current = null;
    try { const out = await fn(); setSave((x) => (isSave || (out as { version?: unknown } | undefined)?.version ? { state: "saved", at: new Date() } : x)); return out; }
    catch (e) {
      if (isSave) setSave((x) => ({ ...x, state: "error" }));
      const f = describeRunFailure(e, fallback); lastNoticeRef.current = f.notice;
      if (f.aborted) { if (f.notice) toast.info(f.notice); return undefined; }
      saveFailureRef.current = f.retryable ? "retryable" : "none";
      if (f.notice) toast.error(f.notice);
      if (f.reload) await onConflict.current().catch(() => undefined);
      return undefined;
    } finally { flight.current = null; setBusy(null); }
  }

  // leaving while an edit is in flight or failed would lose it silently
  useEffect(() => {
    if (busy !== "edit" && !failedEdit) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", h); return () => window.removeEventListener("beforeunload", h);
  }, [busy, failedEdit]);

  return { busy, save, setSave, failedEdit, setFailedEdit, saveFailureRef, lastNoticeRef, onConflict, run, flight };
}
