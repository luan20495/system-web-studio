"use client";

import { AuditTable } from "./AuditTable";
import { AppTable } from "./ApplicationsPages";
import { UserAiCard } from "../AiSetup";
import { PlatformCreateAccount } from "../ProvisioningLive";
import { WorkspaceMembers } from "../TenantScreens";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/http-api";
import type { ActivationLink } from "@/lib/http-types";
import { LinkBox } from "../UserDialogs";
import { useSession } from "../../session";
import { adminScope } from "../adminModel";
import { useA } from "../console/context";
import { confirm } from "@xweb/ui";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, errText, fmtDate, Kpi, num, Pager, Pill, StateView } from "../../ui";
import { PageHead } from "../PageHead";

// ------------------------------------------------------------------ users & workspaces
export function UsersPage({ tab = "users" }: { tab?: "users" | "workspaces" }) {
  const A = useA();
  return (<>
    <PageHead title="Người dùng & Workspace" sub="Workspace là đơn vị tổ chức hiện tại (chưa có phòng ban/đồng bộ HR)."/>
    <div className="tabs" role="tablist">
      <Link role="tab" aria-selected={tab === "users"} className={tab === "users" ? "active" : ""} href={A("/users")}>Người dùng</Link>
      <Link role="tab" aria-selected={tab === "workspaces"} className={tab === "workspaces" ? "active" : ""} href={A("/workspaces")}>Workspace</Link>
    </div>
    {tab === "users" ? <UserList/> : <WorkspaceList/>}
  </>);
}

export function UserList() {
  const A = useA();
  const router = useRouter();
  const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState(""); const [status, setStatus] = useState("all");
  const { data, error, loading, reload } = useLoad(() => api.admin.users(page, query, status), [page, query, status]);
  const [adding, setAdding] = useState(false);
  return (
    <Card actions={<button className="btn primary" data-testid="users-create" onClick={() => setAdding(true)}>+ Tạo tài khoản</button>}>
      {adding ? <PlatformCreateAccount onClose={() => setAdding(false)} onCreated={reload}/> : null}
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}>
        <input aria-label="Tìm người dùng" placeholder="Tìm theo tên, tên đăng nhập, email" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="all">Tất cả</option><option value="active">Đang hoạt động</option><option value="disabled">Bị khóa</option><option value="admin">Quản trị hệ thống</option>
        </select>
        <button className="btn">Tìm</button>
      </form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty" title="Không có người dùng phù hợp"/> : (<>
        <table className="table">
          <thead><tr><th>Người dùng</th><th>Vai trò hệ thống</th><th>Workspace</th><th>Ứng dụng</th><th>Đăng nhập gần nhất</th><th>Trạng thái</th></tr></thead>
          <tbody>{data!.items.map((u) => (
            <tr key={u.id} className="clickRow" onClick={() => router.push(A(`/users/${u.id}`))}>
              <td><Link href={A(`/users/${u.id}`)}><b>{u.displayName ?? u.username}</b></Link><small>{u.username}{u.email ? ` · ${u.email}` : ""} · {u.authSource === "OIDC" ? "SSO" : u.authSource === "SCIM" ? "SCIM" : "Mật khẩu"}</small></td>
              <td>{u.systemAdmin ? <Pill value="PUBLIC" label="Quản trị hệ thống"/> : <span className="muted">Thành viên</span>}</td>
              <td>{u.workspaces}</td><td>{u.projects}</td><td>{ago(u.lastLoginAt)}</td>
              <td>{!u.enabled ? <Pill value="DISABLED" label="Bị khóa"/> : u.pending ? <Pill value="PENDING" label="Chờ kích hoạt"/> : <Pill value="ACTIVE" label="Hoạt động"/>}</td>
            </tr>))}</tbody>
        </table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/>
      </>)}
    </Card>
  );
}

export function UserDetail({ id }: { id: string }) {
  const A = useA();
  const { me } = useSession();
  const { data, error, loading, reload, setData } = useLoad(() => api.admin.user(id), [id]);
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<string | null>(null); const [link, setLink] = useState<ActivationLink | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const d = data!; const u = d.user; const self = me?.id === u.id;
  async function newLink() {
    setBusy(true); setMsg(null);
    try { setLink(await api.admin.activationLink(u.id)); } catch (e) { setMsg(errText(e, "Chưa tạo được liên kết.")); } finally { setBusy(false); }
  }
  async function grantAdmin() {
    const grant = !u.systemAdmin;
    if (!(await confirm({ title: grant ? `Cấp quyền Quản trị hệ thống cho ${u.username}?` : `Gỡ quyền Quản trị hệ thống của ${u.username}?`, message: grant ? "Người này sẽ quản lý được toàn bộ người dùng, AI và cài đặt của công ty." : "Người này không còn quản lý được người dùng, AI và cài đặt của công ty.", confirmLabel: grant ? "Cấp quyền" : "Gỡ quyền", danger: true }))) return;
    setBusy(true); setMsg(null);
    try { await api.admin.setSystemAdmin(u.id, grant); setMsg(grant ? "Đã cấp quyền Quản trị hệ thống." : "Đã gỡ quyền Quản trị hệ thống."); reload(); } catch (e) { setMsg(errText(e, "Chưa đổi được quyền.")); } finally { setBusy(false); }
  }
  async function toggle() {
    if (!(await confirm({ title: u.enabled ? `Khóa tài khoản ${u.username}?` : `Mở khóa tài khoản ${u.username}?`, message: u.enabled ? "Mọi phiên đăng nhập của người này sẽ bị thu hồi ngay." : "Người này đăng nhập lại được.", confirmLabel: u.enabled ? "Khóa tài khoản" : "Mở khóa", danger: u.enabled }))) return;
    setBusy(true); setMsg(null);
    try { await api.admin.setUserStatus(u.id, !u.enabled); setMsg(u.enabled ? "Đã khóa tài khoản và thu hồi phiên." : "Đã mở khóa tài khoản."); reload(); } catch (e) { setMsg(errText(e, "Không đổi được trạng thái.")); } finally { setBusy(false); }
  }
  async function revoke() {
    if (!(await confirm({ title: `Thu hồi mọi phiên đăng nhập của ${u.username}?`, message: "Người này phải đăng nhập lại trên mọi thiết bị.", confirmLabel: "Thu hồi phiên", danger: true }))) return;
    setBusy(true); try { const r = await api.admin.revokeSessions(u.id); setMsg(`Đã thu hồi ${r.revoked} phiên.`); setData({ ...d, activeSessions: 0 }); } catch (e) { setMsg(errText(e, "Không thu hồi được phiên.")); } finally { setBusy(false); }
  }
  return (<>
    <PageHead title={u.displayName ?? u.username} sub={`${u.username}${u.email ? ` · ${u.email}` : ""} · tạo ${fmtDate(u.createdAt)}`}
      actions={<div className="row">
        {u.authSource === "LOCAL" && u.enabled ? <button className="btn" disabled={busy} onClick={() => void newLink()}>{u.pending ? "Tạo lại liên kết kích hoạt" : "Đặt lại mật khẩu"}</button> : null}
        {!self && u.enabled && !u.pending ? <button className="btn" disabled={busy} onClick={() => void grantAdmin()}>{u.systemAdmin ? "Gỡ quyền Quản trị hệ thống" : "Cấp quyền Quản trị hệ thống"}</button> : null}
        <button className="btn" disabled={busy || d.activeSessions === 0} onClick={() => void revoke()}>Thu hồi phiên ({d.activeSessions})</button>
        <button className={`btn ${u.enabled ? "danger" : "primary"}`} disabled={busy || self} title={self ? "Bạn không thể tự khóa tài khoản của mình" : undefined} onClick={() => void toggle()}>{u.enabled ? "Khóa tài khoản" : "Mở khóa"}</button>
      </div>}/>
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    {link ? <LinkBox link={link} onClose={() => { setLink(null); reload(); }}/> : null}
    <div className="kpiGrid">
      <Kpi label="Trạng thái" value={u.pending ? <Pill value="PENDING" label="Chờ kích hoạt"/> : u.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}/>
      <Kpi label="Vai trò hệ thống" value={u.systemAdmin ? "Quản trị hệ thống" : "Thành viên"}/>
      <Kpi label="Đăng nhập" value={u.authSource === "OIDC" ? "SSO" : u.authSource === "SCIM" ? "SSO (cấp qua SCIM)" : "Mật khẩu"} hint={`Gần nhất: ${ago(u.lastLoginAt)}`}/>
      <Kpi label="MFA" value={u.authSource === "LOCAL" ? "Không áp dụng" : "Do IdP quản lý"} hint={u.authSource === "LOCAL" ? "Tài khoản cục bộ: dùng cho quản trị khẩn cấp / môi trường thử" : "MFA managed by Identity Provider"}/>
      <Kpi label="Phiên đang mở" value={num(d.activeSessions)}/>
    </div>
    <div className="grid2">
      <Card title={`Workspace (${d.workspaces.length})`}>{d.workspaces.length ? <table className="table"><tbody>{d.workspaces.map((w) => <tr key={w.id}><td><Link href={A(`/workspaces/${w.id}`)}>{w.name}</Link></td><td><Pill value="PRIVATE" label={w.role}/></td></tr>)}</tbody></table> : <StateView kind="empty" title="Không thuộc workspace nào"/>}</Card>
      <Card title={`Ứng dụng (${d.projects.length})`}>{d.projects.length ? <table className="table"><tbody>{d.projects.map((p) => <tr key={p.id}><td><Link href={A(`/applications/${p.id}`)}>{p.name}</Link><small>{p.workspaceName}</small></td><td>{p.owner ? <Pill value="ACTIVE" label="Chủ sở hữu"/> : <Pill value="PRIVATE" label={p.role}/>}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa tham gia ứng dụng nào"/>}</Card>
    </div>
    <UserAiCard userId={u.id} name={u.displayName ?? u.username}/>
    <Card title="Hoạt động gần đây"><AuditTable rows={d.recentActivity} compact/></Card>
  </>);
}

export function WorkspaceList() {
  const A = useA();
  const router = useRouter(); const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState("");
  const { data, error, loading, reload } = useLoad(() => api.admin.workspaces(page, query), [page, query]);
  return (
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}><input aria-label="Tìm workspace" placeholder="Tìm workspace" value={q} onChange={(e) => setQ(e.target.value)}/><button className="btn">Tìm</button></form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty"/> : (<>
        <table className="table"><thead><tr><th>Workspace</th><th>Thành viên</th><th>Ứng dụng</th><th>Tạo</th><th>Hoạt động gần nhất</th></tr></thead>
          <tbody>{data!.items.map((w) => <tr key={w.id} className="clickRow" onClick={() => router.push(A(`/workspaces/${w.id}`))}><td><Link href={A(`/workspaces/${w.id}`)}><b>{w.name}</b></Link><small>{w.slug}</small></td><td>{w.members}</td><td>{w.projects}</td><td>{fmtDate(w.createdAt)}</td><td>{ago(w.lastActivityAt)}</td></tr>)}</tbody></table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/>
      </>)}
    </Card>
  );
}

export function WorkspaceDetail({ id }: { id: string }) {
  const A = useA();
  const { me } = useSession();
  const { data, error, loading, reload } = useLoad(() => api.admin.workspace(id), [id]);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const d = data!;
  return (<>
    <PageHead title={d.workspace.name} sub={`${d.workspace.slug} · tạo ${fmtDate(d.workspace.createdAt)}`}/>
    <div className="grid2">
      <Card title={`Thành viên (${d.members.length})`}><table className="table"><thead><tr><th>Người dùng</th><th>Vai trò</th><th>Trạng thái</th></tr></thead><tbody>{d.members.map((m) => <tr key={m.userId}><td><Link href={A(`/users/${m.userId}`)}>{m.displayName ?? m.username}</Link><small>{m.username}</small></td><td>{m.role}</td><td>{m.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}</td></tr>)}</tbody></table></Card>
      <Card title={`Ứng dụng (${d.projects.length})`}><AppTable rows={d.projects}/></Card>
    </div>
    {adminScope(me).platform ? <WorkspaceMembers workspaceId={d.workspace.id} name={d.workspace.name}/> : null}
    <Card title="Hoạt động"><AuditTable rows={d.recentActivity} compact/></Card>
  </>);
}
