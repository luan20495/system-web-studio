"use client";

import { AuditTable } from "./AuditTable";
import { useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/http-api";
import type { AdminApp as App } from "@/lib/http-types";
import { ArrowLeft, confirm, LoadGate, Tabs, TabPanel } from "@xweb/ui";
import { useSession } from "../../session";
import { canActInWorkspace } from "../adminModel";
import { useA } from "../console/context";
import { useLoad } from "../../useLoad";
import { ago, Card, ComingSoon, ErrorState, fmtDate, Kpi, Pager, Pill, StateView } from "../../ui";
import { LoadNote } from "../LoadNote";
import { PageHead } from "../PageHead";
import { useAdminAction } from "../useAdminAction";

// ------------------------------------------------------------------ application inventory
export function publishPill(a: App) {
  if (!a.publishStatus) return <span className="muted">Chưa xuất bản</span>;
  return <span title={fmtDate(a.publishedAt)}><Pill value={a.publishStatus}/> <small className="muted">demo</small></span>;
}
export function AppTable({ rows }: { rows: App[] }) {
  const A = useA();
  const router = useRouter();
  if (!rows.length) return <StateView kind="empty" title="Chưa có ứng dụng"/>;
  return (
    <table className="table"><thead><tr><th>Ứng dụng</th><th>Chủ sở hữu</th><th>Thành viên</th><th>Truy cập</th><th>Phiên bản</th><th>Cập nhật</th><th>Xuất bản</th></tr></thead>
      <tbody>{rows.map((a) => <tr key={a.id} className="clickRow" onClick={() => router.push(A(`/applications/${a.id}`))}>
        <td><Link href={A(`/applications/${a.id}`)}><b>{a.name}</b></Link><small>{a.workspaceName}{a.active ? "" : " · đã xóa"}</small></td><td>{a.owner}</td><td>{a.members}</td>
        <td><Pill value={a.visibility} label={a.visibility === "PUBLIC" ? "Công khai" : "Riêng tư"}/></td><td>v{a.latestVersion ?? "—"} <small className="muted">r{a.revision}</small></td><td>{ago(a.updatedAt)}</td><td>{publishPill(a)}</td></tr>)}</tbody></table>
  );
}

export function AppsPage() {
  const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState(""); const [visibility, setVisibility] = useState(""); const [status, setStatus] = useState("active");
  const { data, error, loading, reload } = useLoad(() => api.admin.applications({ page, q: query, visibility, status }), [page, query, visibility, status]);
  return (<>
    <PageHead title="Ứng dụng" sub="Danh mục mọi ứng dụng trong công ty. Chi phí AI/hosting và điểm rủi ro chưa được ghi nhận nên không hiển thị."/>
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}>
        <input aria-label="Tìm ứng dụng" placeholder="Tìm theo tên ứng dụng hoặc chủ sở hữu" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Quyền truy cập" value={visibility} onChange={(e) => { setPage(0); setVisibility(e.target.value); }}><option value="">Mọi quyền truy cập</option><option value="PRIVATE">Riêng tư</option><option value="PUBLIC">Công khai</option></select>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}><option value="active">Đang hoạt động</option><option value="archived">Đã lưu trữ</option><option value="deleted">Đã xóa</option><option value="all">Tất cả</option></select>
        <button className="btn">Tìm</button>
      </form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (<><AppTable rows={data!.items}/><Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>)}
    </Card>
  </>);
}

/** why "Xóa" and "Khôi phục vN" are missing for a platform admin who is not in the application's workspace (see canActInWorkspace) */
const noWorkspaceAccess = (workspace: string) => `Bạn là quản trị hệ thống nhưng không phải thành viên workspace “${workspace}”, nên không xóa ứng dụng hoặc khôi phục phiên bản được ở đây (quản trị hệ thống không tự có quyền trong workspace của công ty). Lưu trữ, khôi phục ứng dụng và chuyển chủ sở hữu vẫn dùng được. Cần xóa hoặc khôi phục phiên bản: nhờ quản trị viên workspace này.`;
export function AppDetail({ id }: { id: string }) {
  const A = useA();
  const router = useRouter(); const { me } = useSession();
  const { data, error, loading, reload } = useLoad(() => api.admin.application(id), [id]);
  const ws = useLoad(() => (data ? api.admin.workspace(data.app.workspaceId) : Promise.resolve(null)), [data?.app.workspaceId]);
  const [tab, setTab] = useState<"overview" | "members" | "versions" | "prompts" | "deployments" | "audit">("overview"); const tid = useId();
  const [newOwner, setNewOwner] = useState("");
  const { act: run, busy, msg: okMsg, err: failMsg } = useAdminAction("Thao tác thất bại.", reload);
  const msg = failMsg ?? okMsg;
  if (!data) return <LoadGate load={{ data, error, loading, reload }} level={1} label="thông tin ứng dụng">{() => null}</LoadGate>;
  const d = data!; const a = d.app; const inWorkspace = canActInWorkspace(me, a.workspaceId);
  const act = (fn: () => Promise<unknown>, ok: string) => run(fn, ok);
  const tabs: { value: typeof tab; label: string; count?: number }[] = [{ value: "overview", label: "Tổng quan" }, { value: "members", label: "Thành viên", count: d.members.length }, { value: "versions", label: "Phiên bản" }, { value: "prompts", label: "Hoạt động AI" }, { value: "deployments", label: "Xuất bản" }, { value: "audit", label: "Nhật ký" }];
  return (<>
    <PageHead title={a.name} sub={`${a.workspaceName} · chủ sở hữu ${a.owner} · ${!a.active ? "đã xóa" : a.lifecycle === "ARCHIVED" ? "đã lưu trữ (chỉ xem, ngoại tuyến)" : "đang hoạt động"}`}
      actions={a.active ? <div className="row">
        {a.lifecycle === "ARCHIVED" ? <button className="btn" disabled={busy} onClick={() => void act(() => api.admin.restoreApp(a.id), "Đã khôi phục ứng dụng (website vẫn ngoại tuyến tới khi xuất bản lại).")}>Khôi phục</button>
          : <button className="btn" disabled={busy} onClick={async () => { if (await confirm({ title: `Lưu trữ “${a.name}”?`, message: "Ứng dụng chỉ còn xem được và website bị gỡ khỏi mạng. Có thể khôi phục sau.", confirmLabel: "Lưu trữ" })) void act(() => api.admin.archiveApp(a.id), "Đã lưu trữ ứng dụng."); }}>Lưu trữ</button>}
        {inWorkspace ? <button className="btn danger" disabled={busy} onClick={async () => { if (await confirm({ title: `Xóa ứng dụng “${a.name}”?`, message: "Xóa mềm: ứng dụng biến khỏi danh sách và việc này được ghi nhật ký.", confirmLabel: "Xóa ứng dụng", danger: true })) void act(() => api.deleteProject(a.workspaceId, a.id, a.revision), "Đã xóa ứng dụng."); }}>Xóa</button> : null}</div> : undefined}/>
    {a.active && !inWorkspace ? <p className="notice" role="note" data-testid="app-no-workspace-access">{noWorkspaceAccess(a.workspaceName)}</p> : null}
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    <Tabs label="Chi tiết ứng dụng" idBase={tid} value={tab} onChange={setTab} tabs={tabs}/>
    <TabPanel idBase={tid} value={tab}>
    {tab === "overview" ? (<>
      <div className="kpiGrid">
        <Kpi label="Quyền truy cập" value={a.visibility === "PUBLIC" ? "Công khai" : "Riêng tư"}/><Kpi label="Phiên bản mới nhất" value={`v${a.latestVersion ?? "—"}`} hint={`revision ${a.revision}`}/>
        <Kpi label="Tạo" value={fmtDate(a.createdAt)}/><Kpi label="Xuất bản gần nhất" value={publishPill(a)}/>
      </div>
      <div className="grid2">
        <Card title="Chuyển chủ sở hữu">
          {a.active ? (<form className="filters" onSubmit={(e) => { e.preventDefault(); if (newOwner) void act(() => api.admin.transferOwnership(a.id, newOwner), "Đã chuyển chủ sở hữu."); }}>
            <select aria-label="Chủ sở hữu mới" value={newOwner} onChange={(e) => setNewOwner(e.target.value)}>
              <option value="">Chọn thành viên workspace…</option>
              {(ws.data?.members ?? []).filter((m) => m.enabled && m.userId !== a.ownerId).map((m) => <option key={m.userId} value={m.userId}>{m.displayName ?? m.username} ({m.username})</option>)}
            </select>
            <button className="btn primary" disabled={!newOwner || busy}>Chuyển</button>
            <LoadNote load={ws} what="danh sách thành viên workspace"/>
          </form>) : <p className="muted">Ứng dụng đã bị xóa.</p>}
          <p className="hint">Chủ cũ ở lại ứng dụng với vai trò Biên tập. Mọi thay đổi được ghi nhật ký.</p>
        </Card>
        <Card title="Chưa triển khai"><ComingSoon title="Chặn xuất bản công khai, chi phí, điểm bảo mật">Các chức năng này cần dữ liệu và chính sách chưa có trong hệ thống.</ComingSoon></Card>
      </div>
    </>) : null}
    {tab === "members" ? <Card><table className="table"><thead><tr><th>Người dùng</th><th>Vai trò</th><th>Trạng thái</th></tr></thead><tbody>{d.members.map((m) => <tr key={m.userId}><td><Link href={A(`/users/${m.userId}`)}>{m.displayName ?? m.username}</Link><small>{m.username}</small></td><td>{m.role}</td><td>{m.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}</td></tr>)}</tbody></table></Card> : null}
    {tab === "versions" ? <Card>{a.active && !inWorkspace ? <p className="hint">{noWorkspaceAccess(a.workspaceName)}</p> : null}<table className="table"><thead><tr><th>Phiên bản</th><th>Loại</th><th>Mô tả</th><th>Tác giả</th><th>Thời gian</th><th><span className="srOnly">Thao tác</span></th></tr></thead><tbody>{d.versions.map((v, i) => <tr key={v.id}><td><b>v{v.versionNumber}</b>{i === 0 ? <small>hiện tại</small> : null}</td><td>{v.kind}</td><td>{v.summary}</td><td>{v.createdBy ?? "—"}</td><td>{fmtDate(v.createdAt)}</td>
      <td>{i > 0 && a.active && inWorkspace ? <button className="btn sm" disabled={busy} onClick={async () => { if (await confirm({ title: `Khôi phục v${v.versionNumber}?`, message: "Một phiên bản mới sẽ được tạo từ nội dung của bản này.", confirmLabel: `Khôi phục v${v.versionNumber}` })) void act(() => api.restoreVersion(a.workspaceId, a.id, v.id, a.revision), `Đã khôi phục v${v.versionNumber}.`); }}>Khôi phục</button> : null}</td></tr>)}</tbody></table></Card> : null}
    {tab === "prompts" ? <Card>{d.prompts.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Prompt</th><th>Model</th><th>Kết quả</th></tr></thead><tbody>{d.prompts.map((p) => <tr key={p.id}><td>{ago(p.createdAt)}</td><td>{p.user ?? "—"}</td><td>{p.text}</td><td className="code">{p.model ?? p.provider ?? "—"}</td><td>{p.outcome ? <Pill value={p.outcome}/> : "—"}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa có prompt"/>}</Card> : null}
    {tab === "deployments" ? <Card>{d.deployments.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Phiên bản</th><th>Truy cập</th><th>Trạng thái</th><th>Môi trường</th><th>Lỗi</th></tr></thead><tbody>{d.deployments.map((x) => <tr key={x.id}><td>{fmtDate(x.createdAt)}</td><td>v{x.versionNumber ?? "—"}</td><td>{x.visibility}</td><td><Pill value={x.status}/></td><td>{x.provider === "mock" ? "Demo deployment (mô phỏng)" : x.provider === "static" ? "Trang tĩnh (thật)" : x.provider}</td><td>{x.error ?? "—"}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa xuất bản lần nào"/>}</Card> : null}
    {tab === "audit" ? <Card><AuditTable rows={d.audit}/></Card> : null}
    </TabPanel>
    <p><button className="btn ghost xp-btnIcon" onClick={() => router.push(A("/applications"))}><ArrowLeft size={14} aria-hidden="true"/> Danh sách ứng dụng</button></p>
  </>);
}
