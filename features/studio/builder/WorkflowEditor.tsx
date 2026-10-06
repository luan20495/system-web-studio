"use client";
/** Workflow V1 editor: an ordered list of steps (ACTION / WAIT / APPROVAL / BRANCH / END). No BPMN canvas: cards with readable retry, timeout, condition, approval and compensation. */
import { useState } from "react";
import type { CompareOp, ConditionDef, StepKind, WorkflowDef, WorkflowStepDef } from "@xweb/types";
import { COMPARE_OPS, PRINCIPAL_KINDS, STEP_KINDS, WORKFLOW_TRIGGERS } from "./core/contract";
import { OP_LABEL, STEP_HELP, STEP_LABEL, addStep, checkWorkflow, describeCondition, formatSeconds, kindOf, moveStep, newWorkflow, orderedSteps, removeStep, stepChips, updateStep, workflowOps } from "./core/workflow";
import { Field } from "./ui/primitives";
import type { DefCtx } from "./ctx";

const TRIGGER_LABEL: Record<string, string> = { MANUAL: "Chạy thủ công", SCHEDULE: "Chạy theo lịch", ACTION: "Chạy từ hành động" };

export function WorkflowEditor({ ctx, initial, onDone, onCancel }: { ctx: DefCtx; initial?: WorkflowDef; onDone: (id: string) => void; onCancel: () => void }) {
  const { doc } = ctx;
  const isNew = !initial;
  const [wf, setWf] = useState<WorkflowDef>(() => initial ?? newWorkflow(doc, ""));
  const [open, setOpen] = useState<string | null>(null);
  const issues = checkWorkflow(wf, doc);
  const disabled = !ctx.canEdit || ctx.busy;
  const actions = doc.actions ?? [];
  const ids = wf.steps.map((s) => s.id);
  const patchStep = (id: string, p: Partial<WorkflowStepDef>) => setWf((w) => updateStep(w, id, p));
  const stepOptions = (self: string) => ids.filter((x) => x !== self);

  async function save() {
    if (issues.length) return;
    const ok = await ctx.commit([isNew ? workflowOps.add(wf) : workflowOps.update(wf.id, wf as never)], isNew ? `Thêm workflow ${wf.name ?? ""}` : `Sửa workflow ${wf.name ?? ""}`);
    if (ok) onDone(wf.id);
  }

  return (
    <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void save(); }} aria-label={isNew ? "Thêm workflow" : `Sửa workflow ${wf.name ?? wf.id}`}>
      <Field label="Tên workflow">{(id) => <input id={id} value={wf.name ?? ""} maxLength={80} disabled={disabled} onChange={(e) => setWf({ ...wf, name: e.target.value })}/>}</Field>
      <Field label="Cách bắt đầu">{(id) => <select id={id} disabled={disabled} value={wf.trigger ?? "MANUAL"} onChange={(e) => { const t = e.target.value as WorkflowDef["trigger"]; const { schedule: _s, timezone: _z, ...rest } = wf; void _s; void _z; setWf({ ...rest, trigger: t, ...(t === "SCHEDULE" ? { schedule: wf.schedule ?? "0 9 * * 1", timezone: wf.timezone ?? "Asia/Ho_Chi_Minh" } : {}) }); }}>
        {WORKFLOW_TRIGGERS.map((t) => <option key={t} value={t}>{TRIGGER_LABEL[t]}</option>)}</select>}</Field>
      {wf.trigger === "SCHEDULE" ? (<>
        <Field label="Lịch (5 trường: phút giờ ngày tháng thứ)" hint="Ví dụ “0 9 * * 1” là 9 giờ sáng thứ Hai.">{(id) => <input id={id} disabled={disabled} value={wf.schedule ?? ""} onChange={(e) => setWf({ ...wf, schedule: e.target.value })}/>}</Field>
        <Field label="Múi giờ">{(id) => <input id={id} disabled={disabled} value={wf.timezone ?? ""} onChange={(e) => setWf({ ...wf, timezone: e.target.value })}/>}</Field>
      </>) : null}
      <label className="checkRow"><input type="checkbox" disabled={disabled} checked={wf.compensateOnCancel === true} onChange={(e) => setWf({ ...wf, compensateOnCancel: e.target.checked || undefined })}/><span>Hoàn tác các bước đã chạy khi bị hủy</span></label>

      <h3 className="bx-h3">Các bước</h3>
      <ol className="bx-steps" aria-label="Các bước của workflow">
        {orderedSteps(wf).map(({ step: s, reachable }, n) => {
          const k = kindOf(s);
          const chips = stepChips(s, doc);
          const expanded = open === s.id;
          const panelId = `step-${s.id}`;
          return (
            <li key={s.id} className={`bx-step kind-${k.toLowerCase()}${reachable ? "" : " orphan"}`}>
              <div className="bx-step-head">
                <span className="bx-step-n" aria-hidden="true">{n + 1}</span>
                <button type="button" className="bx-step-title" aria-expanded={expanded} aria-controls={panelId} onClick={() => setOpen(expanded ? null : s.id)}>
                  <b>{STEP_LABEL[k]}</b><small>{s.id}{reachable ? "" : " · không có đường tới"}</small></button>
                {!disabled ? <span className="bx-step-tools">
                  <button type="button" className="smallButton" aria-label={`Đưa bước ${s.id} lên`} disabled={wf.steps[0]?.id === s.id} onClick={() => setWf(moveStep(wf, s.id, -1))}>↑</button>
                  <button type="button" className="smallButton" aria-label={`Đưa bước ${s.id} xuống`} disabled={wf.steps[wf.steps.length - 1]?.id === s.id} onClick={() => setWf(moveStep(wf, s.id, 1))}>↓</button>
                  <button type="button" className="smallButton danger" aria-label={`Xóa bước ${s.id}`} onClick={() => setWf(removeStep(wf, s.id))}>Xóa</button></span> : null}
              </div>
              {chips.length ? <ul className="bx-chips" aria-label={`Chi tiết bước ${s.id}`}>{chips.map((c, i) => <li key={i} className={`chip chip-${c.kind}`}>{c.text}</li>)}</ul> : <p className="hint">{STEP_HELP[k]}</p>}
              {expanded ? (
                <div id={panelId} className="bx-step-body">
                  {k === "ACTION" ? (<>
                    <Field label="Hành động">{(id) => <select id={id} disabled={disabled} value={s.actionRef ?? ""} onChange={(e) => patchStep(s.id, { actionRef: e.target.value || undefined })}><option value="">— chọn —</option>{actions.map((a) => <option key={a.id} value={a.id}>{a.name || a.id}</option>)}</select>}</Field>
                    <fieldset className="bx-group"><legend>Thử lại khi lỗi</legend>
                      <label className="checkRow"><input type="checkbox" disabled={disabled} checked={!!s.retry} onChange={(e) => patchStep(s.id, { retry: e.target.checked ? { maxAttempts: 3, initialBackoffMillis: 1000, multiplier: 2 } : undefined })}/><span>Tự thử lại</span></label>
                      {s.retry ? <div className="bx-row">
                        <Field label="Số lần tối đa">{(id) => <input id={id} type="number" min={1} max={10} disabled={disabled} value={s.retry!.maxAttempts ?? ""} onChange={(e) => patchStep(s.id, { retry: { ...s.retry, maxAttempts: Number(e.target.value) || undefined } })}/>}</Field>
                        <Field label="Chờ đầu (ms)">{(id) => <input id={id} type="number" min={0} disabled={disabled} value={s.retry!.initialBackoffMillis ?? ""} onChange={(e) => patchStep(s.id, { retry: { ...s.retry, initialBackoffMillis: Number(e.target.value) || undefined } })}/>}</Field>
                        <Field label="Hệ số nhân">{(id) => <input id={id} type="number" min={1} step={0.5} disabled={disabled} value={s.retry!.multiplier ?? ""} onChange={(e) => patchStep(s.id, { retry: { ...s.retry, multiplier: Number(e.target.value) || undefined } })}/>}</Field></div> : null}
                    </fieldset>
                    <Field label="Giới hạn thời gian (giây)">{(id) => <input id={id} type="number" min={0} disabled={disabled} value={s.timeoutMillis ? s.timeoutMillis / 1000 : ""} onChange={(e) => patchStep(s.id, { timeoutMillis: e.target.value ? Number(e.target.value) * 1000 : undefined })}/>}</Field>
                    <Field label="Hoàn tác bằng hành động" hint="Chạy khi workflow bị hủy hoặc lỗi sau bước này.">{(id) => <select id={id} disabled={disabled} value={s.compensationActionRef ?? ""} onChange={(e) => patchStep(s.id, { compensationActionRef: e.target.value || undefined })}><option value="">Không có</option>{actions.map((a) => <option key={a.id} value={a.id}>{a.name || a.id}</option>)}</select>}</Field>
                    <Field label="Khi lỗi, đi tới bước">{(id) => <select id={id} disabled={disabled} value={s.onError ?? ""} onChange={(e) => patchStep(s.id, { onError: e.target.value || undefined })}><option value="">Dừng workflow</option>{stepOptions(s.id).map((x) => <option key={x} value={x}>{x}</option>)}</select>}</Field>
                  </>) : null}
                  {k === "WAIT" ? <Field label="Chờ (giây)" hint={s.waitSeconds ? formatSeconds(s.waitSeconds) : undefined}>{(id) => <input id={id} type="number" min={1} disabled={disabled} value={s.waitSeconds ?? ""} onChange={(e) => patchStep(s.id, { waitSeconds: Number(e.target.value) || undefined })}/>}</Field> : null}
                  {k === "APPROVAL" ? <ApprovalFields s={s} disabled={disabled} patch={(p) => patchStep(s.id, { approval: { ...s.approval, ...p } })} stepOptions={stepOptions(s.id)}/> : null}
                  {k === "BRANCH" ? <BranchFields s={s} disabled={disabled} patch={(p) => patchStep(s.id, p)} stepOptions={stepOptions(s.id)}/> : null}
                  {k !== "END" && k !== "BRANCH" ? <Field label="Bước tiếp theo" hint="Để trống: đi theo thứ tự trong danh sách.">{(id) => <select id={id} disabled={disabled} value={s.next ?? ""} onChange={(e) => patchStep(s.id, { next: e.target.value || undefined })}><option value="">Theo thứ tự</option>{stepOptions(s.id).map((x) => <option key={x} value={x}>{x}</option>)}</select>}</Field> : null}
                </div>) : null}
            </li>
          );
        })}
      </ol>
      {!disabled ? <div className="bx-addrow" role="group" aria-label="Thêm bước">{STEP_KINDS.filter((k) => k !== "END").map((k) => (
        <button type="button" key={k} className="smallButton" title={STEP_HELP[k]} onClick={() => { const r = addStep(wf, k as StepKind); setWf(r.workflow); setOpen(r.stepId); }}>+ {STEP_LABEL[k]}</button>))}</div> : null}

      {issues.length ? <ul className="bx-issues" role="alert">{issues.slice(0, 6).map((i) => <li key={i.path + i.message}>{i.message}</li>)}</ul> : null}
      <div className="bx-actions">
        <button type="button" className="bx-btn" onClick={onCancel}>Hủy</button>
        <button type="submit" className="bx-btn primary" disabled={disabled || issues.length > 0}>{ctx.busy ? "Đang lưu…" : "Lưu workflow"}</button>
      </div>
    </form>
  );
}

function ApprovalFields({ s, disabled, patch, stepOptions }: { s: WorkflowStepDef; disabled: boolean; patch: (p: NonNullable<WorkflowStepDef["approval"]>) => void; stepOptions: string[] }) {
  const a = s.approval ?? {};
  const approvers = a.approvers ?? [];
  return (
    <>
      <Field label="Tiêu đề phê duyệt">{(id) => <input id={id} disabled={disabled} maxLength={120} value={a.title ?? ""} onChange={(e) => patch({ title: e.target.value })}/>}</Field>
      <fieldset className="bx-group"><legend>Người phê duyệt</legend>
        {approvers.map((p, i) => (
          <div className="bx-row" key={i}>
            <select aria-label={`Loại người duyệt ${i + 1}`} disabled={disabled} value={p.kind} onChange={(e) => patch({ approvers: approvers.map((x, j) => (j === i ? { kind: e.target.value as never } : x)) })}>{PRINCIPAL_KINDS.map((k) => <option key={k} value={k}>{k}</option>)}</select>
            {p.kind !== "DEPARTMENT_MANAGER" ? <input aria-label={`Mã người duyệt ${i + 1}`} disabled={disabled} placeholder={p.kind === "ROLE" ? "vai trò" : "mã"} value={p.userId ?? p.groupId ?? p.role ?? ""}
              onChange={(e) => patch({ approvers: approvers.map((x, j) => (j === i ? { kind: x.kind, ...(x.kind === "USER" ? { userId: e.target.value } : x.kind === "GROUP" ? { groupId: e.target.value } : { role: e.target.value }) } : x)) })}/> : null}
            {!disabled ? <button type="button" className="smallButton danger" aria-label={`Xóa người duyệt ${i + 1}`} onClick={() => patch({ approvers: approvers.filter((_, j) => j !== i) })}>Xóa</button> : null}
          </div>))}
        {!disabled ? <button type="button" className="smallButton" onClick={() => patch({ approvers: [...approvers, { kind: "USER" }] })}>+ Thêm người duyệt</button> : null}
      </fieldset>
      <Field label="Số người cần duyệt">{(id) => <input id={id} type="number" min={1} disabled={disabled} value={a.requiredApprovals ?? 1} onChange={(e) => patch({ requiredApprovals: Number(e.target.value) || 1 })}/>}</Field>
      <Field label="Hết hạn sau (giây)" hint={a.expiresInSeconds ? formatSeconds(a.expiresInSeconds) : "Để trống: không hết hạn."}>{(id) => <input id={id} type="number" min={0} disabled={disabled} value={a.expiresInSeconds ?? ""} onChange={(e) => patch({ expiresInSeconds: e.target.value ? Number(e.target.value) : undefined })}/>}</Field>
      <label className="checkRow"><input type="checkbox" disabled={disabled} checked={a.allowSelfApproval !== false} onChange={(e) => patch({ allowSelfApproval: e.target.checked ? undefined : false })}/><span>Cho phép người khởi chạy tự duyệt</span></label>
      <Field label="Khi bị từ chối, đi tới bước">{(id) => <select id={id} disabled={disabled} value={a.onReject ?? ""} onChange={(e) => patch({ onReject: e.target.value || undefined })}><option value="">Kết thúc workflow</option>{stepOptions.map((x) => <option key={x} value={x}>{x}</option>)}</select>}</Field>
      <Field label="Khi hết hạn, đi tới bước">{(id) => <select id={id} disabled={disabled} value={a.onExpire ?? ""} onChange={(e) => patch({ onExpire: e.target.value || undefined })}><option value="">Kết thúc workflow</option>{stepOptions.map((x) => <option key={x} value={x}>{x}</option>)}</select>}</Field>
    </>
  );
}

const isSimple = (c: ConditionDef): c is Extract<ConditionDef, { op: CompareOp }> => "op" in c && c.left.from === "INPUT" && c.right.from === "LITERAL";

function BranchFields({ s, disabled, patch, stepOptions }: { s: WorkflowStepDef; disabled: boolean; patch: (p: Partial<WorkflowStepDef>) => void; stepOptions: string[] }) {
  const branches = s.branches ?? [];
  const set = (i: number, b: (typeof branches)[number]) => patch({ branches: branches.map((x, j) => (j === i ? b : x)) });
  return (
    <>
      <fieldset className="bx-group"><legend>Điều kiện (nhánh đầu tiên khớp được chọn)</legend>
        {branches.map((b, i) => (
          <div className="bx-branch" key={i}>
            {isSimple(b.condition) ? (
              <div className="bx-row">
                <input aria-label={`Đầu vào của điều kiện ${i + 1}`} placeholder="tên đầu vào" disabled={disabled} value={b.condition.left.path ?? ""} onChange={(e) => set(i, { ...b, condition: { ...b.condition, left: { from: "INPUT", path: e.target.value } } as ConditionDef })}/>
                <select aria-label={`Phép so sánh ${i + 1}`} disabled={disabled} value={b.condition.op} onChange={(e) => set(i, { ...b, condition: { ...b.condition, op: e.target.value as CompareOp } as ConditionDef })}>{COMPARE_OPS.map((o) => <option key={o} value={o}>{OP_LABEL[o]}</option>)}</select>
                <input aria-label={`Giá trị so sánh ${i + 1}`} disabled={disabled} value={b.condition.right.value === undefined ? "" : String(b.condition.right.value)} onChange={(e) => set(i, { ...b, condition: { ...b.condition, right: { from: "LITERAL", value: e.target.value } } as ConditionDef })}/>
              </div>
            ) : <p className="hint">{describeCondition(b.condition)}</p>}
            <div className="bx-row">
              <label htmlFor={`bn-${s.id}-${i}`}>thì đi tới</label>
              <select id={`bn-${s.id}-${i}`} disabled={disabled} value={b.next} onChange={(e) => set(i, { ...b, next: e.target.value })}><option value="">— chọn bước —</option>{stepOptions.map((x) => <option key={x} value={x}>{x}</option>)}</select>
              {!disabled ? <button type="button" className="smallButton danger" aria-label={`Xóa điều kiện ${i + 1}`} onClick={() => patch({ branches: branches.filter((_, j) => j !== i) })}>Xóa</button> : null}
            </div>
          </div>))}
        {!disabled ? <button type="button" className="smallButton" onClick={() => patch({ branches: [...branches, { condition: { op: "EQ", left: { from: "INPUT", path: "" }, right: { from: "LITERAL", value: "" } }, next: stepOptions[0] ?? "" }] })}>+ Thêm điều kiện</button> : null}
      </fieldset>
      <Field label="Nếu không điều kiện nào khớp, đi tới">{(id) => <select id={id} disabled={disabled} value={s.defaultNext ?? ""} onChange={(e) => patch({ defaultNext: e.target.value || undefined })}><option value="">Kết thúc workflow</option>{stepOptions.map((x) => <option key={x} value={x}>{x}</option>)}</select>}</Field>
    </>
  );
}
