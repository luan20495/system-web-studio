"use client";
/**
 * Test mode (Edit != Test). This panel never simulates a backend. It states, per action and workflow, what Test mode does (mutation: would run,
 * connector without dry-run: unsupported, notification: not sent, publish: not run, workflow: may still create a workflow_run) and shows a result
 * only when a real server answer exists. SUCCESS is shown only when the server says so; 409 IDEMPOTENCY_OUTCOME_UNKNOWN and 422 MUTATION_REJECTED
 * have their own messages. With no Test API the "Chạy thử" buttons are disabled with the reason.
 */
import { useState } from "react";
import type { AppDefinitionV2 } from "@xweb/types";
import { ACTION_LABEL, permissionToRun } from "./core/actions";
import { permissionLabel } from "./core/inspector";
import { capabilitiesFor, type BuilderCapabilities } from "./core/permissions";
import { OUTCOME_LABEL, TEST_RULES, describeTestEffect, describeWorkflowTest, outcomeFromError, outcomeFromServer, publishInTest, testModeReadiness, type TestOutcome } from "./core/testMode";
import { StateBox } from "./ui/primitives";

export function OutcomeView({ outcome }: { outcome: TestOutcome }) {
  const tone = outcome.state === "SUCCESS" ? "ok" : outcome.state === "UNKNOWN" || outcome.state === "REJECTED" || outcome.state === "ERROR" ? "bad" : outcome.state === "NOT_READY" || outcome.state === "UNSUPPORTED" ? "warn" : "info";
  const text = "message" in outcome ? `${outcome.message.title}. ${outcome.message.detail}` : outcome.note;
  return (
    <div className={`bx-outcome tone-${tone}`} data-outcome={outcome.state} role={tone === "bad" ? "alert" : "status"}>
      <b>{OUTCOME_LABEL[outcome.state]}</b><p>{text}</p>
      {"message" in outcome && !outcome.message.retrySafe ? <p className="hint">Không nên bấm chạy lại ngay.</p> : null}
    </div>
  );
}

/** `runAction` is provided only when a server Test API exists. Without it nothing can be run, and nothing is faked. */
export function TestPanel({ doc, rawPermissions, runAction, runWorkflow }: {
  doc: AppDefinitionV2; rawPermissions: readonly string[];
  runAction?: (actionId: string) => Promise<unknown>; runWorkflow?: (workflowId: string) => Promise<unknown>;
}) {
  const cap = capabilitiesFor(rawPermissions);
  const ready = testModeReadiness();
  const [results, setResults] = useState<Record<string, TestOutcome>>({});
  const actions = doc.actions ?? [], workflows = doc.workflows ?? [];
  const canRun = ready.state === "AVAILABLE" && !!runAction;

  async function run(key: string, fn: () => Promise<unknown>) {
    setResults((r) => ({ ...r, [key]: { state: "NOT_READY", note: "Đang chạy…" } }));
    try { const out = await fn(); setResults((r) => ({ ...r, [key]: outcomeFromServer(out) })); }
    catch (e) { setResults((r) => ({ ...r, [key]: outcomeFromError(e, { write: true }) })); }
  }
  const allowed = (k: keyof BuilderCapabilities) => cap[k];

  return (
    <section className="bx-test" aria-label="Chế độ dùng thử">
      <div className="bx-test-banner" role="note"><b>Chế độ dùng thử</b><p>Không lưu thay đổi vào dữ liệu thật. Muốn sửa ứng dụng, chuyển về “Chỉnh sửa”.</p></div>
      <ul className="bx-rules" aria-label="Quy tắc chế độ dùng thử">{TEST_RULES.map((r) => <li key={r}>{r}</li>)}</ul>
      <StateBox state={ready}/>

      <h3 className="bx-h3">Hành động ({actions.length})</h3>
      {actions.length === 0 ? <p className="hint">Chưa có hành động nào để thử.</p> : (
        <ul className="bx-list" aria-label="Hành động có thể thử">{actions.map((a) => {
          const eff = describeTestEffect(a);
          const need = permissionToRun(a.type);
          const key = `a:${a.id}`;
          const lacks = (need === "DATA_MUTATE" || need === "ACTION_EXECUTE") && !allowed("canRunActions") ? "Bạn chưa có quyền chạy hành động." : need === "WORKFLOW_EXECUTE" && !allowed("canStartWorkflows") ? "Bạn chưa có quyền chạy workflow." : need === "APP_USE" && !allowed("canView") ? "Bạn chưa có quyền dùng ứng dụng." : null;
          return (
            <li key={a.id} className="bx-test-row">
              <div><b>{a.name || a.id}</b><small>{ACTION_LABEL[a.type]} · cần quyền “{permissionLabel[need]}”</small><OutcomeView outcome={results[key] ?? eff}/></div>
              <button type="button" className="smallButton" disabled={!canRun || !!lacks} title={!canRun ? ready.state === "NOT_READY" ? ready.reason : "Chưa sẵn sàng" : lacks ?? undefined}
                onClick={() => runAction && void run(key, () => runAction(a.id))}>Chạy thử</button>
            </li>);
        })}</ul>)}

      <h3 className="bx-h3">Workflow ({workflows.length})</h3>
      {workflows.length === 0 ? <p className="hint">Chưa có workflow nào.</p> : workflows.map((w) => (
        <div key={w.id} className="bx-test-wf">
          <div className="bx-test-row"><div><b>{w.name || w.id}</b><small>Chạy thử vẫn có thể tạo một bản ghi lượt chạy (workflow_run).</small></div>
            <button type="button" className="smallButton" disabled={!ready || !runWorkflow || ready.state !== "AVAILABLE" || !allowed("canStartWorkflows")} title={ready.state === "NOT_READY" ? ready.reason : undefined}
              onClick={() => runWorkflow && void run(`w:${w.id}`, () => runWorkflow(w.id))}>Chạy thử</button></div>
          {results[`w:${w.id}`] ? <OutcomeView outcome={results[`w:${w.id}`]}/> : null}
          <ol className="bx-list">{describeWorkflowTest(w, doc).map((r) => <li key={r.stepId}><div><b>{r.label}</b><OutcomeView outcome={r.outcome}/></div></li>)}</ol>
        </div>))}

      <h3 className="bx-h3">Xuất bản</h3>
      <OutcomeView outcome={publishInTest()}/>
    </section>
  );
}
