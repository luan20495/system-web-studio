"use client";
/**
 * Test mode (Edit != Test). This panel never simulates a backend. It states, per action and workflow, what Test mode does (mutation: would run,
 * connector without dry-run: unsupported, notification: not sent, publish: not run, workflow: may still create a workflow_run) and shows a result
 * only when a real server answer exists. SUCCESS is shown only when the server says so; 409 IDEMPOTENCY_OUTCOME_UNKNOWN and 422 MUTATION_REJECTED
 * have their own messages. With no Test API the "Chạy thử" buttons are disabled with the reason.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import type { ActionEnvelope, AppDefinitionV2, RunQueryResponse, WorkflowRunView } from "@xweb/types";
import { ACTION_LABEL, permissionToRun } from "./core/actions";
import { permissionLabel } from "./core/inspector";
import { explainError } from "./core/errors";
import { capabilitiesFor, type BuilderCapabilities } from "./core/permissions";
import { OUTCOME_LABEL, TEST_RULES, describeTestEffect, describeWorkflowTest, isRunFinished, outcomeFromError, outcomeFromRun, outcomeFromServer, publishInTest, type TestOutcome } from "./core/testMode";
import { notReady, runtimeReadinessFromError, type Readiness } from "./core/readiness";
import { StateBox } from "./ui/primitives";

export function OutcomeView({ outcome }: { outcome: TestOutcome }) {
  const tone = outcome.state === "SUCCESS" ? "ok" : outcome.state === "RUNNING" ? "info" : outcome.state === "UNKNOWN" || outcome.state === "REJECTED" || outcome.state === "ERROR" ? "bad" : outcome.state === "NOT_READY" || outcome.state === "UNSUPPORTED" ? "warn" : "info";
  const text = "message" in outcome ? `${outcome.message.title}. ${outcome.message.detail}` : outcome.note;
  return (
    <div className={`bx-outcome tone-${tone}`} data-outcome={outcome.state} role={tone === "bad" ? "alert" : "status"}>
      <b>{OUTCOME_LABEL[outcome.state]}</b><p>{text}</p>
      {"message" in outcome && !outcome.message.retrySafe ? <p className="hint">Không nên bấm chạy lại ngay.</p> : null}
    </div>
  );
}

/**
 * Server calls the panel may make. Provided ONLY by a host that talks to a real backend (ProjectWorkspace → api.runtime, TEST mode, saved draft).
 * Without it nothing can be run and nothing is faked. `key` is a fresh idempotency key per click.
 */
export type RuntimeCalls = {
  runQuery: (queryId: string) => Promise<RunQueryResponse>;
  runAction: (actionId: string, key: string) => Promise<ActionEnvelope>;
  startWorkflow: (workflowId: string, key: string) => Promise<WorkflowRunView>;
  getRun: (runId: string) => Promise<WorkflowRunView>;
  cancelRun: (runId: string) => Promise<WorkflowRunView>;
  newKey: () => string;
};

const POLL_MS = 1500, POLL_MAX_MS = 120_000;
type QueryResult = { kind: "rows"; res: RunQueryResponse } | { kind: "outcome"; outcome: TestOutcome };
type Feature = "queries" | "actions" | "workflows";

export function TestPanel({ doc, rawPermissions, runtime, dirty = false }: {
  doc: AppDefinitionV2; rawPermissions: readonly string[]; runtime?: RuntimeCalls;
  /** the draft has changes that are not saved yet: TEST runs the SAVED draft, so running now would test something else */
  dirty?: boolean;
}) {
  const cap = capabilitiesFor(rawPermissions);
  const [results, setResults] = useState<Record<string, TestOutcome>>({});
  const [queryRes, setQueryRes] = useState<Record<string, QueryResult>>({});
  const [unavailable, setUnavailable] = useState<Partial<Record<Feature, Readiness>>>({});
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  /** items whose last write ended with an UNKNOWN outcome: pressing again may write twice, so they stay locked until the user confirms */
  const [locked, setLocked] = useState<ReadonlySet<string>>(new Set());
  const [runs, setRuns] = useState<Record<string, WorkflowRunView>>({});
  const [pollErr, setPollErr] = useState<Record<string, string>>({});
  const timers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const alive = useRef(true);
  useEffect(() => { alive.current = true; const t = timers.current; return () => { alive.current = false; t.forEach(clearTimeout); t.clear(); }; }, []);

  /** runtime-api.md §5: TEST needs the edit right on the project (the server checks PROJECT_EDIT = APP_EDIT before anything else) */
  const noEdit = !cap.canEdit ? "Chế độ thử cần quyền chỉnh sửa ứng dụng." : null;
  const queries = (doc.queries ?? []).filter((q) => (q.mode ?? "READ") === "READ");
  const actions = doc.actions ?? [], workflows = doc.workflows ?? [];
  const base: Readiness = runtime ? { state: "AVAILABLE" } : notReady("Chưa kết nối máy chủ để chạy thử. Không có kết quả nào được giả lập ở trình duyệt.");
  const featureState = (f: Feature): Readiness => (dirty && runtime ? notReady("Có thay đổi chưa lưu. Chế độ thử chạy bản đã lưu, hãy đợi lưu xong rồi chạy lại.") : unavailable[f] ?? base);
  const setP = (key: string, on: boolean) => setPending((p) => { const n = new Set(p); if (on) n.add(key); else n.delete(key); return n; });
  const mark = useCallback((f: Feature, e: unknown) => { const r = runtimeReadinessFromError(e, f); if (r) setUnavailable((u) => ({ ...u, [f]: r })); }, []);

  async function runQuery(q: string) {
    if (!runtime || pending.has(`q:${q}`)) return;
    setP(`q:${q}`, true); setQueryRes((r) => ({ ...r, [q]: { kind: "outcome", outcome: { state: "RUNNING", note: "Đang chạy truy vấn…" } } }));
    try { const res = await runtime.runQuery(q); if (alive.current) setQueryRes((r) => ({ ...r, [q]: { kind: "rows", res } })); }
    catch (e) { mark("queries", e); if (alive.current) setQueryRes((r) => ({ ...r, [q]: { kind: "outcome", outcome: outcomeFromError(e) } })); }
    finally { if (alive.current) setP(`q:${q}`, false); }
  }

  async function runAction(id: string) {
    const key = `a:${id}`;
    if (!runtime || pending.has(key) || locked.has(key)) return;
    setP(key, true); setResults((r) => ({ ...r, [key]: { state: "RUNNING", note: "Đang chạy…" } }));
    try { const out = outcomeFromServer(await runtime.runAction(id, runtime.newKey())); if (alive.current) setResults((r) => ({ ...r, [key]: out })); }
    catch (e) {
      mark("actions", e); const out = outcomeFromError(e, { write: true });
      if (alive.current) { setResults((r) => ({ ...r, [key]: out })); if (out.state === "UNKNOWN") setLocked((l) => new Set(l).add(key)); }
    } finally { if (alive.current) setP(key, false); }
  }

  const schedulePoll = useCallback((wf: string, runId: string, startedAt: number) => {
    if (!runtime) return;
    timers.current.set(wf, setTimeout(async () => {
      try {
        const v = await runtime.getRun(runId);
        if (!alive.current) return;
        setRuns((r) => ({ ...r, [wf]: v })); setPollErr(({ [wf]: _x, ...rest }) => rest);
        setResults((r) => ({ ...r, [`w:${wf}`]: outcomeFromRun(v) }));
        if (isRunFinished(v)) { timers.current.delete(wf); setP(`w:${wf}`, false); return; }
        if (Date.now() - startedAt > POLL_MAX_MS) { timers.current.delete(wf); setP(`w:${wf}`, false); setPollErr((m) => ({ ...m, [wf]: "Lượt chạy vẫn đang tiếp diễn. Bấm “Tải lại trạng thái” để xem tiếp." })); return; }
      } catch (e) {
        if (!alive.current) return;
        // a failed POLL says nothing about the run itself: keep the last known state and offer a manual reconnect
        timers.current.delete(wf); setP(`w:${wf}`, false); setPollErr((m) => ({ ...m, [wf]: `Mất kết nối khi theo dõi lượt chạy (${explainErr(e)}). Lượt chạy có thể vẫn đang chạy trên máy chủ.` })); return;
      }
      schedulePoll(wf, runId, startedAt);
    }, POLL_MS));
  }, [runtime]);

  async function startWorkflow(id: string) {
    const key = `w:${id}`;
    if (!runtime || pending.has(key) || locked.has(key)) return;
    setP(key, true); setPollErr(({ [id]: _x, ...rest }) => rest); setResults((r) => ({ ...r, [key]: { state: "RUNNING", note: "Đang khởi chạy…" } }));
    try {
      const v = await runtime.startWorkflow(id, runtime.newKey());
      if (!alive.current) return;
      setRuns((r) => ({ ...r, [id]: v })); setResults((r) => ({ ...r, [key]: outcomeFromRun(v) }));
      if (isRunFinished(v)) setP(key, false); else schedulePoll(id, v.runId, Date.now());
    } catch (e) {
      mark("workflows", e); const out = outcomeFromError(e, { write: true });
      if (alive.current) { setResults((r) => ({ ...r, [key]: out })); if (out.state === "UNKNOWN") setLocked((l) => new Set(l).add(key)); setP(key, false); }
    }
  }
  function reconnect(id: string) { const v = runs[id]; if (!v || !runtime) return; setPollErr(({ [id]: _x, ...rest }) => rest); setP(`w:${id}`, true); schedulePoll(id, v.runId, Date.now()); }
  async function cancel(id: string) {
    const v = runs[id]; if (!v || !runtime) return;
    try { const n = await runtime.cancelRun(v.runId); if (alive.current) { setRuns((r) => ({ ...r, [id]: n })); setResults((r) => ({ ...r, [`w:${id}`]: outcomeFromRun(n) })); if (isRunFinished(n)) setP(`w:${id}`, false); } }
    catch (e) { if (alive.current) setResults((r) => ({ ...r, [`w:${id}`]: outcomeFromError(e) })); }
  }
  const unlock = (key: string) => setLocked((l) => { const n = new Set(l); n.delete(key); return n; });
  const reason = (f: Feature) => { const s = featureState(f); return s.state === "NOT_READY" ? s.reason : s.state === "ERROR" ? s.message : undefined; };

  return (
    <section className="bx-test" aria-label="Chế độ dùng thử" data-testid="test-panel">
      <div className="bx-test-banner" role="note"><b>Chế độ dùng thử</b><p>Không lưu thay đổi vào dữ liệu thật. Muốn sửa ứng dụng, chuyển về “Chỉnh sửa”.</p></div>
      <ul className="bx-rules" aria-label="Quy tắc chế độ dùng thử">{TEST_RULES.map((r) => <li key={r}>{r}</li>)}</ul>
      <StateBox state={base}/>
      {dirty && runtime ? <StateBox state={featureState("actions")}/> : null}

      <h3 className="bx-h3">Truy vấn ({queries.length})</h3>
      {queries.length === 0 ? <p className="hint">Chưa có truy vấn đọc nào để thử.</p> : (
        <ul className="bx-list" aria-label="Truy vấn có thể thử">{queries.map((q) => {
          const st = featureState("queries"), res = queryRes[q.id];
          const lacks = noEdit ?? (!cap.canRunQueries ? "Bạn chưa được cấp quyền chạy truy vấn." : null);
          return (
            <li key={q.id} className="bx-test-row" data-testid={`query-row:${q.id}`}>
              <div><b>{q.name || q.id}</b><small>Chạy ở chế độ thử trên bản nháp đã lưu</small>
                {res?.kind === "rows" ? <QueryRows res={res.res}/> : res ? <OutcomeView outcome={res.outcome}/> : null}</div>
              <button type="button" className="smallButton" data-testid={`run-query:${q.id}`} disabled={st.state !== "AVAILABLE" || !!lacks || pending.has(`q:${q.id}`)} aria-busy={pending.has(`q:${q.id}`)}
                title={reason("queries") ?? lacks ?? undefined} onClick={() => void runQuery(q.id)}>{pending.has(`q:${q.id}`) ? "Đang chạy…" : "Chạy thử"}</button>
            </li>);
        })}</ul>)}

      <h3 className="bx-h3">Hành động ({actions.length})</h3>
      {actions.length === 0 ? <p className="hint">Chưa có hành động nào để thử.</p> : (
        <ul className="bx-list" aria-label="Hành động có thể thử">{actions.map((a) => {
          const eff = describeTestEffect(a);
          const need = permissionToRun(a.type);
          const key = `a:${a.id}`;
          const st = featureState("actions");
          const lacks = noEdit ?? (((need === "DATA_MUTATE" || need === "ACTION_EXECUTE") && !cap.canRunActions) ? "Bạn chưa có quyền chạy hành động." : need === "WORKFLOW_EXECUTE" && !cap.canStartWorkflows ? "Bạn chưa có quyền chạy workflow." : need === "APP_USE" && !cap.canView ? "Bạn chưa có quyền dùng ứng dụng." : null);
          const busy = pending.has(key);
          return (
            <li key={a.id} className="bx-test-row" data-testid={`action-row:${a.id}`}>
              <div><b>{a.name || a.id}</b><small>{ACTION_LABEL[a.type]} · cần quyền “{permissionLabel[need]}”</small><OutcomeView outcome={results[key] ?? eff}/>
                {locked.has(key) ? <p className="hint"><button type="button" className="smallButton" data-testid={`unlock:${key}`} onClick={() => unlock(key)}>Tôi đã kiểm tra dữ liệu, cho phép chạy lại</button></p> : null}</div>
              <button type="button" className="smallButton" data-testid={`run-action:${a.id}`} disabled={st.state !== "AVAILABLE" || !!lacks || busy || locked.has(key)} aria-busy={busy}
                title={reason("actions") ?? lacks ?? (locked.has(key) ? "Kết quả lần chạy trước chưa rõ." : undefined)} onClick={() => void runAction(a.id)}>{busy ? "Đang chạy…" : "Chạy thử"}</button>
            </li>);
        })}</ul>)}

      <h3 className="bx-h3">Workflow ({workflows.length})</h3>
      {workflows.length === 0 ? <p className="hint">Chưa có workflow nào.</p> : workflows.map((w) => {
        const key = `w:${w.id}`, st = featureState("workflows"), run = runs[w.id], busy = pending.has(key);
        return (
          <div key={w.id} className="bx-test-wf" data-testid={`workflow-row:${w.id}`}>
            <div className="bx-test-row"><div><b>{w.name || w.id}</b><small>Chạy thử vẫn có thể tạo một bản ghi lượt chạy (workflow_run).</small></div>
              <span className="bx-row-tools">
                <button type="button" className="smallButton" data-testid={`run-workflow:${w.id}`} disabled={st.state !== "AVAILABLE" || !!noEdit || !cap.canStartWorkflows || busy || locked.has(key)} aria-busy={busy}
                  title={reason("workflows") ?? noEdit ?? (!cap.canStartWorkflows ? "Bạn chưa có quyền chạy workflow." : undefined)} onClick={() => void startWorkflow(w.id)}>{busy ? "Đang chạy…" : "Chạy thử"}</button>
                {run && busy && !isRunFinished(run) ? <button type="button" className="smallButton danger" data-testid={`cancel-workflow:${w.id}`} onClick={() => void cancel(w.id)}>Hủy lượt chạy</button> : null}</span></div>
            {results[key] ? <OutcomeView outcome={results[key]}/> : null}
            {run ? <p className="hint" data-testid={`run-status:${w.id}`}>Lượt chạy {run.runId} · {run.status}{run.steps?.length ? ` · ${run.steps.map((s) => `${s.stepId}:${s.status}`).join(", ")}` : ""}</p> : null}
            {pollErr[w.id] ? <p role="alert" className="hint">{pollErr[w.id]} <button type="button" className="smallButton" data-testid={`reconnect:${w.id}`} onClick={() => reconnect(w.id)}>Tải lại trạng thái</button></p> : null}
            {locked.has(key) ? <p className="hint"><button type="button" className="smallButton" onClick={() => unlock(key)}>Tôi đã kiểm tra, cho phép chạy lại</button></p> : null}
            <ol className="bx-list">{describeWorkflowTest(w, doc).map((r) => <li key={r.stepId}><div><b>{r.label}</b><OutcomeView outcome={r.outcome}/></div></li>)}</ol>
          </div>);
      })}

      <h3 className="bx-h3">Xuất bản</h3>
      <OutcomeView outcome={publishInTest()}/>
    </section>
  );
}

const explainErr = (e: unknown) => explainError(e).title;

function QueryRows({ res }: { res: RunQueryResponse }) {
  const rows = res.result.rows ?? [], cols = rows[0] ? Object.keys(rows[0]).slice(0, 6) : [];
  return (
    <div className="bx-outcome tone-ok" data-outcome="SUCCESS" data-testid="query-result" role="status">
      <b>{OUTCOME_LABEL.SUCCESS}</b>
      <p>{rows.length} dòng{res.result.truncated ? " (đã cắt bớt)" : ""} · bộ nhớ đệm: {res.cache} · chế độ {res.mode}{res.result.skippedRows ? ` · bỏ qua ${res.result.skippedRows} dòng lỗi` : ""}</p>
      {rows.length === 0 ? <p className="hint">Truy vấn không trả dòng nào.</p> : (
        <table className="bx-table"><caption className="sr-only">Kết quả truy vấn {res.queryId}</caption>
          <thead><tr>{cols.map((c) => <th key={c} scope="col">{c}</th>)}</tr></thead>
          <tbody>{rows.slice(0, 5).map((r, i) => <tr key={i}>{cols.map((c) => <td key={c}>{String(r[c] ?? "")}</td>)}</tr>)}</tbody></table>)}
    </div>
  );
}
