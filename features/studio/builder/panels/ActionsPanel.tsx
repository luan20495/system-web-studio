"use client";
/** Actions list + editor. Every action is a typed declaration; roles: bound to a component, child (workflow / chain) or unattached. */
import { Plus } from "../../../../packages/ui/src/icons";
import { useState } from "react";
import type { ActionDef, ActionType } from "@xweb/types";
import { ACTION_LABEL, ROLE_LABEL, actionOps, describeAction, roleOf } from "../core/actions";
import { usersOf } from "../core/definition";
import { ACTION_TYPES } from "../core/contract";
import { ActionEditor } from "../ActionEditor";
import type { DefCtx } from "../ctx";
import { Dialog, Gate } from "../ui/primitives";

export function ActionsPanel({ ctx, preset }: { ctx: DefCtx; preset?: { type?: ActionType; sectionId?: string } }) {
  const [editing, setEditing] = useState<ActionDef | "new" | null>(preset ? "new" : null);
  const [removing, setRemoving] = useState<ActionDef | null>(null);
  const actions = ctx.doc.actions ?? [];
  const disabled = !ctx.canEdit || ctx.busy;
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Hành động</h2>
        {ctx.canEdit && ctx.readiness.state === "AVAILABLE" && !editing ? <button type="button" className="bx-btn sm" onClick={() => setEditing("new")}><Plus size={14} aria-hidden="true"/> Hành động</button> : null}</div>
      <p className="hint">Hành động là khai báo có kiểu: chuyển trang, làm mới dữ liệu, gửi biểu mẫu, ghi bản ghi, gọi thao tác đã duyệt, gửi thông báo, chạy workflow. Không có ô nhập mã.</p>
      <Gate state={ctx.readiness}>
        {editing ? <ActionEditor ctx={ctx} initial={editing === "new" ? undefined : editing} preset={editing === "new" ? preset : undefined} onDone={() => setEditing(null)} onCancel={() => setEditing(null)}/> : (
          actions.length === 0 ? <p className="hint">Chưa có hành động nào.</p> : (
            <ul className="bx-list" aria-label="Danh sách hành động">{actions.map((a) => {
              const role = roleOf(a, ctx.doc);
              return (
                <li key={a.id}>
                  <div><b>{a.name || a.id}</b><small>{ACTION_LABEL[a.type]} · <span className={`chip chip-${role.toLowerCase()}`}>{ROLE_LABEL[role]}</span></small><small>{describeAction(a, ctx.doc)}</small></div>
                  {!disabled ? <span className="bx-row-tools"><button type="button" className="smallButton" onClick={() => setEditing(a)}>Sửa</button>
                    <button type="button" className="smallButton danger" aria-label={`Xóa hành động ${a.name || a.id}`} onClick={() => setRemoving(a)}>Xóa</button></span> : null}
                </li>);
            })}</ul>))}
      </Gate>
      <p className="hint">Chạy thử hành động: chuyển sang chế độ “Dùng thử”. Chạy thật chỉ sau khi xuất bản.</p>
      <p className="hint">Các loại: {ACTION_TYPES.map((t) => ACTION_LABEL[t]).join(", ")}.</p>
      {removing ? (
        <Dialog title={`Xóa hành động “${removing.name || removing.id}”?`} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="bx-btn danger" disabled={ctx.busy} onClick={() => void ctx.commit([actionOps.remove(removing.id)], `Xóa hành động ${removing.name ?? removing.id}`).then((ok) => { if (ok) setRemoving(null); })}>Xóa</button></>}>
          {(() => { const u = usersOf(ctx.doc, "actions", removing.id); return u.length ? <p>Đang được dùng bởi: {u.join(", ")}. Máy chủ sẽ từ chối lưu cho tới khi các tham chiếu đó được sửa.</p> : <p>Không có mục nào khác dùng hành động này.</p>; })()}
        </Dialog>) : null}
    </div>
  );
}
