"use client";

import { AuditTable } from "./AuditTable";
import { useMemo, useState } from "react";
import { api } from "@/lib/http-api";
import { useLoad } from "../../useLoad";
import { actionLabel, Card, ErrorState, Pager, StateView } from "../../ui";
import { PageHead } from "../PageHead";

// ------------------------------------------------------------------ audit
export function AuditPage() {
  const actions = useLoad(() => api.admin.auditActions(), []);
  const [page, setPage] = useState(0);
  const [f, setF] = useState({ actor: "", action: "", projectId: "", workspaceId: "", requestId: "", from: "", to: "" });
  const [applied, setApplied] = useState(f);
  const params = useMemo(() => ({ page, actor: applied.actor || undefined, action: applied.action || undefined, projectId: applied.projectId || undefined,
    workspaceId: applied.workspaceId || undefined, requestId: applied.requestId || undefined,
    from: applied.from ? new Date(applied.from).toISOString() : undefined, to: applied.to ? new Date(applied.to).toISOString() : undefined }), [page, applied]);
  const { data, error, loading, reload } = useLoad(() => api.admin.audit(params), [params]);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (<>
    <PageHead title="Nhật ký kiểm toán" sub="Chỉ ghi thêm; cơ sở dữ liệu chặn sửa/xóa. Mỗi dòng có request ID khớp với log vận hành."/>
    <Card>
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(f); }}>
        <input aria-label="Người thực hiện" placeholder="Người thực hiện (tên hoặc ID)" value={f.actor} onChange={set("actor")}/>
        <select aria-label="Hành động" value={f.action} onChange={set("action")}><option value="">Mọi hành động</option>{(actions.data ?? []).map((a) => <option key={a} value={a}>{actionLabel(a)} ({a})</option>)}</select>
        <input aria-label="Project ID" placeholder="Project ID" value={f.projectId} onChange={set("projectId")}/>
        <input aria-label="Workspace ID" placeholder="Workspace ID" value={f.workspaceId} onChange={set("workspaceId")}/>
        <input aria-label="Request ID" placeholder="Request ID" value={f.requestId} onChange={set("requestId")}/>
        <label className="inlineLabel">Từ <input type="datetime-local" value={f.from} onChange={set("from")}/></label>
        <label className="inlineLabel">Đến <input type="datetime-local" value={f.to} onChange={set("to")}/></label>
        <button className="btn primary">Lọc</button>
      </form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (<><AuditTable rows={data!.items}/><Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>)}
    </Card>
  </>);
}
