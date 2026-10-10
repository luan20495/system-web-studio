"use client";
/** Small shared pieces of the organization screens: the not-ready note and the one place a refusal is shown (with its retry / reload actions). */
import { useCallback, useRef, useState } from "react";
import { Info } from "@xweb/ui";
import { Modal } from "./Modal";
import { needsReload, orgProblem, type OrgProblem } from "./organizationModel";

export function NotReadyPanel({ title, reason, testid, children, level = 3 }: { title: string; reason: string; testid: string; children?: React.ReactNode; level?: 2 | 3 }) {
  const H = level === 2 ? "h2" : "h3"; // 2 when the panel sits directly under the page's h1 (no skipped heading level)
  return (
    <div className="xp-orgNotReady" role="note" data-testid={testid}>
      <span className="xp-orgNotReadyIcon" aria-hidden="true"><Info size={20}/></span>
      <div><H>{title}</H><p>{reason}</p>{children}</div>
    </div>
  );
}

/**
 * One refusal, said once, with what the person can do about it:
 *  - `busy` (503 ORG_STRUCTURE_BUSY): nothing was executed; "Thử lại" sends the SAME request again (the caller keeps the person's input and the same expectedVersion);
 *  - a stale / missing / conflicting copy: "Tải lại" reloads the data (the caller decides what is kept);
 *  - everything else is plain text. A refusal is never shown as success and never turned into a different message.
 */
export function Problem({ p, onReload, onRetry, testid = "org-problem", reloadLabel = "Tải lại cơ cấu", retrying = false }: { p: OrgProblem; onReload?: () => void; onRetry?: () => void; testid?: string; reloadLabel?: string; retrying?: boolean }) {
  const soft = p.kind === "not-ready" || p.kind === "busy" || p.kind === "unavailable-feature";
  return (
    <div className={soft ? "notice" : "formError"} role="alert" data-testid={testid} data-kind={p.kind} data-code={p.code} data-retry-after={p.kind === "busy" && p.retryAfterSeconds ? p.retryAfterSeconds : undefined}>
      <span>{p.text}</span>
      {onRetry && p.retryable ? <> <button type="button" className="btn sm" data-testid="org-retry-busy" disabled={retrying} onClick={onRetry}>{retrying ? "Đang thử lại…" : "Thử lại"}</button></> : null}
      {onReload && needsReload(p) ? <> <button type="button" className="btn sm" data-testid="org-reload-stale" onClick={onReload}>{reloadLabel}</button></> : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- one write at a time, with "again"
/**
 * Runs ONE write at a time and keeps what the person needs when it is refused: the problem (mapped from the server's code), and the SAME call to send again (`retry`; a 503 ORG_STRUCTURE_BUSY changed nothing,
 * so the same request with the same expectedVersion is the right retry). `run` resolves true only on a real success: a refusal never looks like one.
 */
export function useOrgAction() {
  const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null); const again = useRef<(() => Promise<void>) | null>(null);
  const run = useCallback(async (fn: () => Promise<void>): Promise<boolean> => {
    setBusy(true); setProblem(null); again.current = fn;
    try { await fn(); return true; } catch (err) { setProblem(orgProblem(err)); return false; } finally { setBusy(false); }
  }, []);
  const retry = useCallback(() => (again.current ? run(again.current) : Promise.resolve(false)), [run]);
  const clear = useCallback(() => setProblem(null), []);
  return { busy, problem, run, retry, clear };
}

/** An in-app confirmation of one write (never a native dialog): it stays open on a refusal (reason + "Thử lại" right there) and closes only after the write succeeded. Cancel has the initial focus. */
export function OrgConfirm({ title, message, confirmLabel, busyLabel, danger = true, testid, go, onClose, onDone, onReload }: {
  title: string; message?: React.ReactNode; confirmLabel: string; busyLabel: string; danger?: boolean; testid: string; go: () => Promise<void>; onClose: () => void; onDone: () => void; onReload?: () => void;
}) {
  const a = useOrgAction();
  return (
    <Modal label={title} onClose={onClose}>
      <div className="modalBody" data-testid={testid}>
        <h2>{title}</h2>
        {message ? <div className="xp-dialogMsg">{message}</div> : null}
        {a.problem ? <Problem p={a.problem} onReload={onReload} onRetry={() => void a.retry().then((ok) => { if (ok) onDone(); })} retrying={a.busy} testid={`${testid}-problem`}/> : null}
        <div className="xp-footer"><button type="button" className="btn" data-autofocus="" onClick={onClose}>Hủy</button>
          <button type="button" className={`btn ${danger ? "danger" : "primary"}`} data-testid={`${testid}-confirm`} disabled={a.busy} onClick={() => void a.run(go).then((ok) => { if (ok) onDone(); })}>{a.busy ? busyLabel : confirmLabel}</button></div>
      </div>
    </Modal>
  );
}
