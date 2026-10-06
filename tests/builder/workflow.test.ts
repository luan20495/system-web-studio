import test from "node:test";
import assert from "node:assert/strict";
import * as W from "../../features/studio/builder/core/workflow";
import { doc } from "./fixtures";

const base = () => doc({ actions: [{ id: "a1", name: "Báo", type: "NOTIFY", channel: "IN_APP", templateRef: "tpl-1" }, { id: "undo", name: "Hoàn", type: "NOTIFY", channel: "IN_APP", templateRef: "tpl-1" }] } as never);
const wf = () => W.newWorkflow(base(), "Duyệt đơn");

test("v1 step kinds are exactly ACTION/WAIT/APPROVAL/BRANCH/END", () => {
  assert.deepEqual(Object.keys(W.STEP_LABEL).sort(), ["ACTION", "APPROVAL", "BRANCH", "END", "WAIT"]);
});

test("new workflow is MANUAL with only an END step", () => {
  const w = wf();
  assert.equal(w.trigger, "MANUAL"); assert.deepEqual(w.steps.map((s) => s.kind), ["END"]);
});

test("add steps: new ones go before the trailing END; ids are unique", () => {
  let w = wf();
  const a = W.addStep(w, "ACTION"); w = a.workflow;
  const b = W.addStep(w, "WAIT"); w = b.workflow;
  assert.deepEqual(w.steps.map((s) => s.kind), ["ACTION", "WAIT", "END"]);
  assert.equal(new Set(w.steps.map((s) => s.id)).size, 3);
  assert.equal(w.steps.find((s) => s.id === b.stepId)!.waitSeconds, 60);
  const ap = W.addStep(w, "APPROVAL", a.stepId).workflow;
  assert.equal(ap.steps[1].kind, "APPROVAL");
  assert.ok(ap.steps[1].approval);
});

test("remove step re-points references at its successor", () => {
  let w = wf();
  w = W.addStep(w, "ACTION").workflow; w = W.addStep(w, "WAIT").workflow;
  const [s1, s2] = w.steps;
  w = W.updateStep(w, s1.id, { next: s2.id, onError: s2.id });
  const r = W.removeStep(w, s2.id);
  assert.equal(r.steps.length, 2);
  assert.equal(r.steps[0].next, "end"); assert.equal(r.steps[0].onError, "end");
});

test("move step swaps neighbours and clears a stale explicit next", () => {
  let w = wf();
  w = W.addStep(w, "ACTION").workflow; w = W.addStep(w, "WAIT").workflow;
  const [s1, s2] = w.steps;
  w = W.updateStep(w, s1.id, { next: s2.id });
  const m = W.moveStep(w, s1.id, 1);
  assert.deepEqual(m.steps.map((s) => s.id).slice(0, 2), [s2.id, s1.id]);
  assert.equal(m.steps[1].next, undefined);
  assert.equal(W.moveStep(w, s1.id, -1), w);
});

test("conditions read in Vietnamese incl. nested all/any/not/exists", () => {
  const c = { all: [{ op: "GT", left: { from: "INPUT", path: "amount" }, right: { from: "LITERAL", value: 100 } }, { not: { exists: { from: "STEP_OUTPUT", stepId: "s1", path: "ok" } } }] } as never;
  const text = W.describeCondition(c);
  assert.match(text, /đầu vào amount lớn hơn 100/); assert.match(text, /không/); assert.match(text, / và /);
});

test("chips show retry, timeout, wait, approval, condition, compensation, error route", () => {
  const d = base();
  const chips = W.stepChips({ id: "s", kind: "ACTION", actionRef: "a1", retry: { maxAttempts: 3, initialBackoffMillis: 2000, multiplier: 2 }, timeoutMillis: 30000, compensationActionRef: "undo", onError: "end" } as never, d);
  const kinds = chips.map((c) => c.kind);
  for (const k of ["action", "retry", "timeout", "compensation", "error-route"]) assert.ok(kinds.includes(k as never), k);
  assert.match(chips.find((c) => c.kind === "retry")!.text, /3 lần/);
  assert.match(chips.find((c) => c.kind === "compensation")!.text, /Hoàn/);
  const ap = W.stepChips({ id: "p", kind: "APPROVAL", approval: { requiredApprovals: 2, approvers: [{ kind: "USER", ref: "u" }], expiresInSeconds: 86400, allowSelfApproval: false } } as never, d);
  assert.match(ap.find((c) => c.kind === "approval")!.text, /2 người duyệt.*1 người.*1 ngày.*không tự duyệt/);
  const br = W.stepChips({ id: "b", kind: "BRANCH", branches: [{ condition: { exists: { from: "INPUT", path: "x" } }, next: "end" }], defaultNext: "end" } as never, d);
  assert.equal(br.filter((c) => c.kind === "condition").length, 2);
  assert.equal(W.formatSeconds(90), "90 giây"); assert.equal(W.formatSeconds(7200), "2 giờ");
});

test("validation: schedule needs a cron, approvals need approvers, branches need conditions, WAIT needs a duration, unreachable steps flagged", () => {
  const d = base();
  let w = wf();
  w = W.addStep(w, "APPROVAL").workflow; w = W.addStep(w, "BRANCH").workflow;
  const msgs = W.checkWorkflow(w, d).map((i) => i.message).join("\n");
  assert.match(msgs, /chưa chỉ định người duyệt/); assert.match(msgs, /chưa có điều kiện/);
  assert.ok(W.checkWorkflow({ ...wf(), trigger: "SCHEDULE" } as never, d).some((i) => i.path === "schedule"));
  assert.ok(W.checkWorkflow({ ...wf(), schedule: "* * * * *" } as never, d).some((i) => i.path === "schedule"));
  assert.equal(W.checkWorkflow({ ...wf(), trigger: "SCHEDULE", schedule: "0 9 * * 1" } as never, d).filter((i) => i.path === "schedule").length, 0);
  const wait0 = W.addStep(wf(), "WAIT");
  assert.ok(W.checkWorkflow(W.updateStep(wait0.workflow, wait0.stepId, { waitSeconds: 0 }), d).some((i) => /thời gian chờ/.test(i.message)));
  const orphan = { ...wf(), steps: [{ id: "s1", kind: "END" }, { id: "x", kind: "WAIT", waitSeconds: 5 }] } as never;
  assert.ok(W.checkWorkflow(orphan, d).some((i) => /không có đường dẫn/.test(i.message)));
});

test("a workflow step pointing at a missing action is a reference issue", () => {
  const d = base();
  const a = W.addStep(wf(), "ACTION"); const w = W.updateStep(a.workflow, a.stepId, { actionRef: "ghost" });
  assert.ok(W.checkWorkflow(w, d).length > 0);
});

test("ordered steps follow the flow; branch targets reachable", () => {
  const w = { ...wf(), steps: [
    { id: "b", kind: "BRANCH", branches: [{ condition: { exists: { from: "INPUT", path: "x" } }, next: "yes" }], defaultNext: "no" },
    { id: "yes", kind: "WAIT", waitSeconds: 1, next: "end" }, { id: "no", kind: "WAIT", waitSeconds: 1, next: "end" }, { id: "end", kind: "END" },
  ] } as never;
  const o = W.orderedSteps(w);
  assert.deepEqual(o.map((x) => x.step.id), ["b", "yes", "no", "end"]);
  assert.ok(o.every((x) => x.reachable));
});

test("workflow ops are canonical definition operations", () => {
  assert.equal(W.workflowOps.add(wf()).type, "ADD_WORKFLOW_REF");
  assert.equal(W.workflowOps.remove("w").type, "REMOVE_WORKFLOW_REF");
});
