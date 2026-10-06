/**
 * Workflow editor V1: an ORDERED list of steps (ACTION, WAIT, APPROVAL, BRANCH, END) with their retry, timeout, condition, approval and
 * compensation shown as readable chips. No BPMN canvas. The model is the canonical C4 `workflows[]` entry; everything here is pure and
 * returns new objects.
 */
import type { ActionDef, AppDefinitionV2, CompareOp, ConditionDef, DefinitionOperation, StepKind, ValueRefDef, WorkflowDef, WorkflowStepDef } from "@xweb/types";
import { COMPARE_OPS, STEP_KINDS } from "./contract";
import { defOps, validateDefinition, type RefIssue } from "./definition";
import { uniqueId } from "./dataFlow";

export const STEP_LABEL: Readonly<Record<StepKind, string>> = { ACTION: "Hành động", WAIT: "Chờ", APPROVAL: "Phê duyệt", BRANCH: "Rẽ nhánh", END: "Kết thúc" };
export const STEP_HELP: Readonly<Record<StepKind, string>> = {
  ACTION: "Chạy một hành động đã khai báo.", WAIT: "Chờ một khoảng thời gian rồi đi tiếp.", APPROVAL: "Dừng lại cho tới khi người được chỉ định phê duyệt hoặc từ chối.",
  BRANCH: "Chọn bước tiếp theo theo điều kiện; nhánh đầu tiên khớp được chọn.", END: "Kết thúc workflow.",
};
export const OP_LABEL: Readonly<Record<CompareOp, string>> = { EQ: "bằng", NE: "khác", GT: "lớn hơn", GTE: "lớn hơn hoặc bằng", LT: "nhỏ hơn", LTE: "nhỏ hơn hoặc bằng", IN: "nằm trong", CONTAINS: "chứa" };
export const kindOf = (s: WorkflowStepDef): StepKind => s.kind ?? (s.actionRef !== undefined ? "ACTION" : "END");
export const isStepKind = (v: string): v is StepKind => (STEP_KINDS as readonly string[]).includes(v);

export function newWorkflow(doc: AppDefinitionV2, name: string): WorkflowDef {
  const id = uniqueId("wf", (doc.workflows ?? []).map((w) => w.id));
  return { id, name: name.trim() || "Workflow mới", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] };
}

const nextId = (wf: WorkflowDef) => uniqueId("s", wf.steps.map((s) => s.id));

/** insert a step after `afterId` (or at the end, before a trailing END). The default flow is the array order, so no explicit `next` is needed. */
export function addStep(wf: WorkflowDef, kind: StepKind, afterId?: string | null): { workflow: WorkflowDef; stepId: string } {
  const id = kind === "END" ? uniqueId("end", wf.steps.map((s) => s.id)) : nextId(wf);
  const step: WorkflowStepDef = { id, kind, ...(kind === "WAIT" ? { waitSeconds: 60 } : {}), ...(kind === "APPROVAL" ? { approval: { title: "Cần phê duyệt", requiredApprovals: 1 } } : {}), ...(kind === "BRANCH" ? { branches: [] } : {}) };
  const steps = wf.steps.slice();
  let at: number;
  if (afterId) at = steps.findIndex((s) => s.id === afterId) + 1;
  else { const last = steps.length - 1; at = last >= 0 && kindOf(steps[last]) === "END" && kind !== "END" ? last : steps.length; }
  steps.splice(Math.max(0, at), 0, step);
  return { workflow: { ...wf, steps }, stepId: id };
}

/** removes a step and re-points everything that led to it at what followed it; startStepId follows too */
export function removeStep(wf: WorkflowDef, id: string): WorkflowDef {
  const i = wf.steps.findIndex((s) => s.id === id);
  if (i < 0) return wf;
  const gone = wf.steps[i];
  const successor = gone.next ?? gone.defaultNext ?? wf.steps[i + 1]?.id;
  const fix = (ref?: string) => (ref === id ? successor : ref);
  const steps = wf.steps.filter((s) => s.id !== id).map((s): WorkflowStepDef => {
    const out: WorkflowStepDef = { ...s };
    for (const k of ["next", "onError", "defaultNext"] as const) { if (s[k] === id) { const v = fix(s[k]); if (v === undefined) delete out[k]; else out[k] = v; } }
    if (s.branches) out.branches = s.branches.map((b) => ({ ...b, next: fix(b.next) ?? b.next })).filter((b) => b.next !== id);
    if (s.approval) out.approval = { ...s.approval, ...(s.approval.onReject === id ? { onReject: successor } : {}), ...(s.approval.onExpire === id ? { onExpire: successor } : {}) };
    return out;
  });
  return { ...wf, steps, ...(wf.startStepId === id ? { startStepId: successor } : {}) };
}

/** swap with the neighbour. A step whose explicit `next` pointed at its old neighbour goes back to the default flow, so order stays the single truth. */
export function moveStep(wf: WorkflowDef, id: string, delta: -1 | 1): WorkflowDef {
  const i = wf.steps.findIndex((s) => s.id === id), j = i + delta;
  if (i < 0 || j < 0 || j >= wf.steps.length) return wf;
  const steps = wf.steps.slice(); [steps[i], steps[j]] = [steps[j], steps[i]];
  const a = wf.steps[i].id, b = wf.steps[j].id;
  const clean = steps.map((s) => ((s.id === a && s.next === b) || (s.id === b && s.next === a) ? (({ next: _n, ...rest }) => { void _n; return rest; })(s) : s));
  return { ...wf, steps: clean };
}

export function updateStep(wf: WorkflowDef, id: string, patch: Partial<WorkflowStepDef>): WorkflowDef {
  return { ...wf, steps: wf.steps.map((s) => (s.id === id ? { ...s, ...patch } : s)) };
}

/** execution order from the start step following next / defaultNext / branches (breadth-first); steps nothing leads to come last, flagged */
export function orderedSteps(wf: WorkflowDef): { step: WorkflowStepDef; reachable: boolean }[] {
  const byId = new Map(wf.steps.map((s, i) => [s.id, { s, i }]));
  const start = wf.startStepId ?? wf.steps[0]?.id;
  const seen = new Set<string>(); const order: string[] = []; const queue: string[] = start ? [start] : [];
  while (queue.length) {
    const id = queue.shift()!;
    if (seen.has(id) || !byId.has(id)) continue;
    seen.add(id); order.push(id);
    const { s, i } = byId.get(id)!;
    const follow = [s.next ?? (kindOf(s) === "BRANCH" ? undefined : wf.steps[i + 1]?.id), ...(s.branches ?? []).map((b) => b.next), s.defaultNext, s.approval?.onReject, s.approval?.onExpire, s.onError]
      .filter((x): x is string => !!x);
    if (kindOf(s) === "END") continue;
    queue.push(...follow);
  }
  const rest = wf.steps.filter((s) => !seen.has(s.id)).map((s) => s.id);
  return [...order.map((id) => ({ step: byId.get(id)!.s, reachable: true })), ...rest.map((id) => ({ step: byId.get(id)!.s, reachable: false }))];
}

// ------------------------------------------------------------------------------------------------------------------ conditions
export function describeRef(r: ValueRefDef): string {
  if (r.from === "LITERAL") return r.value === undefined ? "?" : typeof r.value === "string" ? `“${r.value}”` : JSON.stringify(r.value);
  if (r.from === "INPUT") return `đầu vào ${r.path ?? ""}`.trim();
  return `kết quả bước ${r.stepId ?? "?"}${r.path ? `.${r.path}` : ""}`;
}
export function describeCondition(c: ConditionDef): string {
  if ("op" in c) return `${describeRef(c.left)} ${OP_LABEL[c.op]} ${describeRef(c.right)}`;
  if ("all" in c) return c.all.map(describeCondition).map((x) => `(${x})`).join(" và ");
  if ("any" in c) return c.any.map(describeCondition).map((x) => `(${x})`).join(" hoặc ");
  if ("not" in c) return `không (${describeCondition(c.not)})`;
  return `có ${describeRef(c.exists)}`;
}
export const isCompareOp = (v: string): v is CompareOp => (COMPARE_OPS as readonly string[]).includes(v);
export function compare(left: ValueRefDef, op: CompareOp, right: ValueRefDef): ConditionDef { return { op, left, right }; }

// ------------------------------------------------------------------------------------------------------------------ summaries
export type StepChip = { kind: "retry" | "timeout" | "wait" | "approval" | "condition" | "compensation" | "error-route" | "action"; text: string };

export function stepChips(s: WorkflowStepDef, doc: AppDefinitionV2): StepChip[] {
  const chips: StepChip[] = [];
  const action = (id?: string): ActionDef | undefined => (doc.actions ?? []).find((a) => a.id === id);
  if (s.actionRef) chips.push({ kind: "action", text: action(s.actionRef)?.name ?? `Hành động ${s.actionRef} (không còn)` });
  if (s.retry) {
    const r = s.retry;
    chips.push({ kind: "retry", text: `Thử lại tối đa ${r.maxAttempts ?? "?"} lần${r.initialBackoffMillis ? `, chờ ${r.initialBackoffMillis / 1000}s` : ""}${r.multiplier ? ` (x${r.multiplier})` : ""}` });
  }
  if (s.timeoutMillis) chips.push({ kind: "timeout", text: `Giới hạn ${s.timeoutMillis / 1000}s` });
  if (s.waitSeconds) chips.push({ kind: "wait", text: `Chờ ${formatSeconds(s.waitSeconds)}` });
  if (s.approval) {
    const a = s.approval;
    chips.push({ kind: "approval", text: `Cần ${a.requiredApprovals ?? 1} người duyệt${a.approvers?.length ? ` trong ${a.approvers.length} người được chỉ định` : ""}${a.expiresInSeconds ? `, hết hạn sau ${formatSeconds(a.expiresInSeconds)}` : ""}${a.allowSelfApproval === false ? ", không tự duyệt" : ""}` });
  }
  for (const b of s.branches ?? []) chips.push({ kind: "condition", text: `Nếu ${describeCondition(b.condition)} → ${b.next}` });
  if (s.defaultNext) chips.push({ kind: "condition", text: `Ngược lại → ${s.defaultNext}` });
  if (s.compensationActionRef) chips.push({ kind: "compensation", text: `Hoàn tác bằng: ${action(s.compensationActionRef)?.name ?? s.compensationActionRef}` });
  if (s.onError) chips.push({ kind: "error-route", text: `Khi lỗi → ${s.onError}` });
  return chips;
}
export function formatSeconds(n: number): string {
  if (n % 86400 === 0) return `${n / 86400} ngày`;
  if (n % 3600 === 0) return `${n / 3600} giờ`;
  if (n % 60 === 0) return `${n / 60} phút`;
  return `${n} giây`;
}

// ------------------------------------------------------------------------------------------------------------------ validation
const CRON5 = /^\s*\S+(\s+\S+){4}\s*$/;
export function checkWorkflow(wf: WorkflowDef, doc: AppDefinitionV2): RefIssue[] {
  const probe: AppDefinitionV2 = { ...doc, workflows: [...(doc.workflows ?? []).filter((x) => x.id !== wf.id), wf] };
  const idx = probe.workflows!.length - 1;
  const out = validateDefinition(probe).filter((i) => i.path.startsWith(`workflows[${idx}]`) || i.path === "actions");
  if (wf.trigger === "SCHEDULE" && !(wf.schedule && CRON5.test(wf.schedule))) out.push({ path: "schedule", message: "Workflow theo lịch cần biểu thức lịch 5 trường." });
  if (wf.trigger !== "SCHEDULE" && wf.schedule) out.push({ path: "schedule", message: "Lịch chỉ dùng cho workflow chạy theo lịch." });
  if (!wf.steps.some((s) => kindOf(s) === "END")) out.push({ path: "steps", message: "Workflow nên có một bước Kết thúc." });
  for (const { step, reachable } of orderedSteps(wf)) if (!reachable) out.push({ path: `steps.${step.id}`, message: `Bước “${step.id}” không có đường dẫn tới, sẽ không bao giờ chạy.` });
  for (const s of wf.steps) {
    if (kindOf(s) === "WAIT" && !(s.waitSeconds && s.waitSeconds > 0)) out.push({ path: `steps.${s.id}.waitSeconds`, message: `Bước chờ “${s.id}” cần thời gian chờ lớn hơn 0.` });
    if (kindOf(s) === "APPROVAL" && !(s.approval?.approvers?.length)) out.push({ path: `steps.${s.id}.approval.approvers`, message: `Bước phê duyệt “${s.id}” chưa chỉ định người duyệt.` });
    if (kindOf(s) === "BRANCH" && !(s.branches?.length)) out.push({ path: `steps.${s.id}.branches`, message: `Bước rẽ nhánh “${s.id}” chưa có điều kiện nào.` });
  }
  return out;
}

export const workflowOps = {
  add: (w: WorkflowDef): DefinitionOperation => defOps.add("workflows", w),
  update: (id: string, patch: Partial<Record<keyof WorkflowDef, unknown>>): DefinitionOperation => defOps.update("workflows", id, patch),
  remove: (id: string): DefinitionOperation => defOps.remove("workflows", id),
};
