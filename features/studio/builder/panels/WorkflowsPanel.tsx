"use client";
import { Plus } from "../../../../packages/ui/src/icons";
import { useState } from "react";
import type { WorkflowDef } from "@xweb/types";
import { usersOf } from "../core/definition";
import { kindOf, workflowOps } from "../core/workflow";
import { WorkflowEditor } from "../WorkflowEditor";
import type { DefCtx } from "../ctx";
import { Dialog, Gate } from "../ui/primitives";

const TRIGGER: Record<string, string> = { MANUAL: "Thủ công", SCHEDULE: "Theo lịch", ACTION: "Từ hành động" };

export function WorkflowsPanel({ ctx }: { ctx: DefCtx }) {
  const [editing, setEditing] = useState<WorkflowDef | "new" | null>(null);
  const [removing, setRemoving] = useState<WorkflowDef | null>(null);
  const list = ctx.doc.workflows ?? [];
  const disabled = !ctx.canEdit || ctx.busy;
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Workflow</h2>
        {ctx.canEdit && ctx.readiness.state === "AVAILABLE" && !editing ? <button type="button" className="bx-btn sm" onClick={() => setEditing("new")}><Plus size={14} aria-hidden="true"/> Workflow</button> : null}</div>
      <p className="hint">Chuỗi các bước: hành động, chờ, phê duyệt, rẽ nhánh, kết thúc. Mỗi bước hiển thị thử lại, giới hạn thời gian, điều kiện, phê duyệt và hoàn tác.</p>
      <Gate state={ctx.readiness}>
        {editing ? <WorkflowEditor ctx={ctx} initial={editing === "new" ? undefined : editing} onDone={() => setEditing(null)} onCancel={() => setEditing(null)}/> : (
          list.length === 0 ? <p className="hint">Chưa có workflow nào.</p> : (
            <ul className="bx-list" aria-label="Danh sách workflow">{list.map((w) => (
              <li key={w.id}><div><b>{w.name || w.id}</b><small>{TRIGGER[w.trigger ?? "MANUAL"]} · {w.steps.length} bước ({w.steps.filter((s) => kindOf(s) === "APPROVAL").length} phê duyệt)</small></div>
                {!disabled ? <span className="bx-row-tools"><button type="button" className="smallButton" onClick={() => setEditing(w)}>Sửa</button>
                  <button type="button" className="smallButton danger" aria-label={`Xóa workflow ${w.name || w.id}`} onClick={() => setRemoving(w)}>Xóa</button></span> : null}</li>))}</ul>))}
      </Gate>
      <p className="hint">Chạy thử workflow: chuyển sang chế độ “Dùng thử”.</p>
      {removing ? (
        <Dialog title={`Xóa workflow “${removing.name || removing.id}”?`} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="bx-btn danger" disabled={ctx.busy} onClick={() => void ctx.commit([workflowOps.remove(removing.id)], `Xóa workflow ${removing.name ?? removing.id}`).then((ok) => { if (ok) setRemoving(null); })}>Xóa</button></>}>
          {(() => { const u = usersOf(ctx.doc, "workflows", removing.id); return u.length ? <p>Đang được dùng bởi: {u.join(", ")}.</p> : <p>Không có hành động nào chạy workflow này.</p>; })()}
        </Dialog>) : null}
    </div>
  );
}
