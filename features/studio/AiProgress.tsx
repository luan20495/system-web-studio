"use client";
/** Progress of one AI request: steps, what is happening now, how long, when it stops by itself, and a Cancel that works before the server has even answered. */
import { useEffect, useState } from "react";
import { Circle, CircleCheck, Loader } from "../../packages/ui/src/icons";
import { clock, progressView, type AiLive } from "./aiProgressModel";

export function AiProgress({ live, model, onCancel, now: fixedNow }: { live: AiLive; model: string; onCancel: () => void; now?: number }) {
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => { if (fixedNow !== undefined) return; const t = setInterval(() => setTick(Date.now()), 1000); return () => clearInterval(t); }, [fixedNow]);
  const v = progressView(live, fixedNow ?? tick, model);
  return (
    <div className="message assistant"><div className="bubble typing liveStream aiProgress" data-testid="ai-progress" data-stalled={v.stalled} data-started={live.id !== null}>
      <div data-testid="ai-headline"><span className="dots" aria-hidden="true"><i/><i/><i/></span> {v.headline}</div>
      {/* the live region is a separate, visually hidden line that changes only when the PHASE changes (M-004): the visible headline counts characters on every chunk */}
      <span className="srOnly" role="status" aria-live="polite" data-testid="ai-announce">{v.announce}</span>
      <ol className="aiSteps" aria-label="Các bước">{v.steps.map((s) => <li key={s.id} data-state={s.state}><span aria-hidden="true" className="xp-checkIcon">{s.state === "done" ? <CircleCheck size={14}/> : s.state === "active" ? <Loader size={14} className="xp-spin"/> : <Circle size={14}/>}</span> {s.label}</li>)}</ol>
      <div className="aiMeta" aria-hidden="true" data-testid="ai-clock">Đã chạy {clock(v.elapsedSeconds)}{v.remainingSeconds !== null ? ` · tự dừng sau ${clock(v.remainingSeconds)}` : ""}</div>
      {v.detail ? <p className={v.stalled ? "aiWarn" : "hint"} role={v.stalled ? "status" : undefined} data-testid="ai-detail">{v.detail}</p> : null}
      {live.text ? <details className="aiRaw"><summary>Xem phần AI đang trả về</summary><pre className="streamTail">{live.text.slice(-240)}</pre></details> : null}
      <button type="button" className="smallButton" data-testid="ai-cancel" onClick={onCancel}>Huỷ</button>
    </div></div>
  );
}
