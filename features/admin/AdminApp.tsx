"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Fragment, useMemo, useState, type ReactNode } from "react";
import { api } from "@/lib/http-api";
import type { AdminApp as App, AuditRow, HealthItem } from "@/lib/http-types";
import { useSession } from "../session";
import { rememberPortal } from "../routing";
import { useLoad } from "../useLoad";
import { actionLabel, ago, Card, ComingSoon, ErrorState, errText, fmtDate, Kpi, NavLink, num, Pager, Pill, StateView } from "../ui";

const NAV: [string, string, string][] = [
  ["", "Tổng quan", "▦"], ["users", "Người dùng & Workspace", "◎"], ["applications", "Ứng dụng", "▤"], ["ai", "AI Control", "✦"],
  ["components", "Components", "◇"], ["audit", "Nhật ký kiểm toán", "≡"], ["system", "Sức khỏe hệ thống", "♥"], ["settings", "Cài đặt", "⚙"]
];

export function AdminApp({ seg }: { seg: string[] }) {
  const section = seg[0] ?? "";
  const active = section === "workspaces" ? "users" : section;
  return (
    <div className="shell admin">
      <AdminSidebar active={active}/>
      <div className="shellMain">
        <AdminHeader/>
        <main className="page" id="main" tabIndex={0}>{route(seg)}</main>
      </div>
    </div>
  );
}

function route(seg: string[]): ReactNode {
  switch (seg[0] ?? "") {
    case "": return <Overview/>;
    case "users": return seg[1] ? <UserDetail id={seg[1]}/> : <UsersPage/>;
    case "workspaces": return seg[1] ? <WorkspaceDetail id={seg[1]}/> : <UsersPage tab="workspaces"/>;
    case "applications": return seg[1] ? <AppDetail id={seg[1]}/> : <AppsPage/>;
    case "ai": return <AiPage/>;
    case "components": return <ComponentsPage/>;
    case "audit": return <AuditPage/>;
    case "system": return <HealthPage/>;
    case "settings": return <SettingsPage/>;
    default: return <StateView kind="notfound"/>;
  }
}

function AdminSidebar({ active }: { active: string }) {
  const { me } = useSession();
  return (
    <aside className="sidebar dark" aria-label="Điều hướng quản trị">
      <div className="sideBrand"><span className="logoMark" aria-hidden="true">◆</span><div><b>AI Software Factory</b><small>Admin Console</small></div></div>
      <nav>{NAV.map(([key, label, icon]) => <NavLink key={key} href={`/admin${key ? `/${key}` : ""}`} active={active === key} icon={icon}>{label}</NavLink>)}</nav>
      <div className="sideFoot"><div className="avatar" aria-hidden="true">{(me?.displayName ?? "?").slice(0, 2).toUpperCase()}</div><div><b>{me?.displayName}</b><small>System admin</small></div></div>
    </aside>
  );
}

function AdminHeader() {
  const { me, logout } = useSession();
  return (
    <header className="topHeader">
      <div className="crumb">Admin Console</div>
      <div className="row">
        {me && me.workspaces.length > 0 ? <Link className="btn sm" href="/studio" onClick={() => rememberPortal("builder")}>Mở Builder Studio</Link> : null}
        <button className="btn sm ghost" onClick={() => void logout()}>Đăng xuất</button>
      </div>
    </header>
  );
}

function PageHead({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return <div className="pageHead"><div><h1>{title}</h1>{sub ? <p>{sub}</p> : null}</div>{actions}</div>;
}

function AuditTable({ rows, compact }: { rows: AuditRow[]; compact?: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  if (!rows.length) return <StateView kind="empty" title="Chưa có hoạt động"/>;
  return (
    <table className="table">
      <thead><tr><th>Thời gian</th><th>Người thực hiện</th><th>Hành động</th><th>Đối tượng</th>{compact ? null : <><th>IP</th><th>Request ID</th></>}</tr></thead>
      <tbody>{rows.map((r) => (<Fragment key={r.id}>
        <tr className="clickRow" onClick={() => setOpen(open === r.id ? null : r.id)}>
          <td title={fmtDate(r.createdAt)}>{ago(r.createdAt)}</td><td>{r.actor ?? (r.actorId ? r.actorId.slice(0, 8) : <span className="muted">Hệ thống</span>)}</td>
          <td><b>{actionLabel(r.action)}</b><small className="code">{r.action}</small></td>
          <td>{r.projectId ? <Link href={`/admin/applications/${r.projectId}`} onClick={(e) => e.stopPropagation()}>{r.resourceType}</Link> : r.resourceType}</td>
          {compact ? null : <><td>{r.ipAddress ?? "—"}</td><td className="code">{r.requestId ?? "—"}</td></>}
        </tr>
        {open === r.id ? <tr className="detailRow"><td colSpan={compact ? 4 : 6}><pre>{JSON.stringify({ resourceId: r.resourceId, old: r.oldValue && JSON.parse(r.oldValue), new: r.newValue && JSON.parse(r.newValue) }, null, 2)}</pre></td></tr> : null}
      </Fragment>))}</tbody>
    </table>
  );
}

// ------------------------------------------------------------------ overview
function Overview() {
  const { data, error, loading, reload } = useLoad(() => api.admin.overview(), []);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const o = data!;
  return (<>
    <PageHead title="Tổng quan" sub="Số liệu thật từ cơ sở dữ liệu của nền tảng."/>
    <div className="kpiGrid">
      <Kpi label="Người dùng" value={num(o.users)} hint={`${num(o.activeUsers)} đang hoạt động · ${num(o.disabledUsers)} bị khóa`}/>
      <Kpi label="Đăng nhập 30 ngày" value={num(o.usersLoggedIn30d)}/>
      <Kpi label="Workspace" value={num(o.workspaces)}/>
      <Kpi label="Ứng dụng" value={num(o.projects)} hint={`${num(o.publishedProjects)} đã xuất bản (demo)`}/>
      <Kpi label="Lượt AI hôm nay" value={num(o.aiRequestsToday)} hint={`${num(o.aiRequestsMonth)} trong tháng`}/>
      <Kpi label="Phiên bản tạo hôm nay" value={num(o.versionsToday)}/>
    </div>
    <div className="grid2">
      <Card title="Hoạt động gần đây" actions={<Link className="btn sm" href="/admin/audit">Xem tất cả</Link>}><AuditTable rows={o.recentActivity} compact/></Card>
      <Card title="Chi phí AI">
        <ComingSoon title="Token, chi phí và ngân sách AI">Hệ thống chưa ghi nhận số token và giá model, nên chưa hiển thị chi phí. Sẽ có sau khi triển khai ghi nhận sử dụng AI (Phase 4).</ComingSoon>
      </Card>
    </div>
  </>);
}

// ------------------------------------------------------------------ users & workspaces
function UsersPage({ tab = "users" }: { tab?: "users" | "workspaces" }) {
  return (<>
    <PageHead title="Người dùng & Workspace" sub="Workspace là đơn vị tổ chức hiện tại (chưa có phòng ban/đồng bộ HR)."/>
    <div className="tabs" role="tablist">
      <Link role="tab" aria-selected={tab === "users"} className={tab === "users" ? "active" : ""} href="/admin/users">Người dùng</Link>
      <Link role="tab" aria-selected={tab === "workspaces"} className={tab === "workspaces" ? "active" : ""} href="/admin/workspaces">Workspace</Link>
    </div>
    {tab === "users" ? <UserList/> : <WorkspaceList/>}
  </>);
}

function UserList() {
  const router = useRouter();
  const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState(""); const [status, setStatus] = useState("all");
  const { data, error, loading, reload } = useLoad(() => api.admin.users(page, query, status), [page, query, status]);
  return (
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}>
        <input aria-label="Tìm người dùng" placeholder="Tìm theo tên, tên đăng nhập, email" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="all">Tất cả</option><option value="active">Đang hoạt động</option><option value="disabled">Bị khóa</option><option value="admin">System admin</option>
        </select>
        <button className="btn">Tìm</button>
      </form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty" title="Không có người dùng phù hợp"/> : (<>
        <table className="table">
          <thead><tr><th>Người dùng</th><th>Vai trò hệ thống</th><th>Workspace</th><th>Ứng dụng</th><th>Đăng nhập gần nhất</th><th>Trạng thái</th></tr></thead>
          <tbody>{data!.items.map((u) => (
            <tr key={u.id} className="clickRow" onClick={() => router.push(`/admin/users/${u.id}`)}>
              <td><Link href={`/admin/users/${u.id}`}><b>{u.displayName ?? u.username}</b></Link><small>{u.username}{u.email ? ` · ${u.email}` : ""} · {u.authSource === "OIDC" ? "SSO" : "Mật khẩu"}</small></td>
              <td>{u.systemAdmin ? <Pill value="PUBLIC" label="System admin"/> : <span className="muted">Thành viên</span>}</td>
              <td>{u.workspaces}</td><td>{u.projects}</td><td>{ago(u.lastLoginAt)}</td>
              <td>{u.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}</td>
            </tr>))}</tbody>
        </table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/>
      </>)}
    </Card>
  );
}

function UserDetail({ id }: { id: string }) {
  const { me } = useSession();
  const { data, error, loading, reload, setData } = useLoad(() => api.admin.user(id), [id]);
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const d = data!; const u = d.user; const self = me?.id === u.id;
  async function toggle() {
    if (!window.confirm(u.enabled ? `Vô hiệu hóa ${u.username}? Mọi phiên đăng nhập của người này sẽ bị thu hồi ngay.` : `Kích hoạt lại ${u.username}?`)) return;
    setBusy(true); setMsg(null);
    try { await api.admin.setUserStatus(u.id, !u.enabled); setMsg(u.enabled ? "Đã vô hiệu hóa và thu hồi phiên." : "Đã kích hoạt lại."); reload(); } catch (e) { setMsg(errText(e, "Không đổi được trạng thái.")); } finally { setBusy(false); }
  }
  async function revoke() {
    if (!window.confirm(`Thu hồi mọi phiên đăng nhập của ${u.username}?`)) return;
    setBusy(true); try { const r = await api.admin.revokeSessions(u.id); setMsg(`Đã thu hồi ${r.revoked} phiên.`); setData({ ...d, activeSessions: 0 }); } catch (e) { setMsg(errText(e, "Không thu hồi được phiên.")); } finally { setBusy(false); }
  }
  return (<>
    <PageHead title={u.displayName ?? u.username} sub={`${u.username}${u.email ? ` · ${u.email}` : ""} · tạo ${fmtDate(u.createdAt)}`}
      actions={<div className="row">
        <button className="btn" disabled={busy || d.activeSessions === 0} onClick={() => void revoke()}>Thu hồi phiên ({d.activeSessions})</button>
        <button className={`btn ${u.enabled ? "danger" : "primary"}`} disabled={busy || self} title={self ? "Bạn không thể tự khóa tài khoản của mình" : undefined} onClick={() => void toggle()}>{u.enabled ? "Vô hiệu hóa" : "Kích hoạt"}</button>
      </div>}/>
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    <div className="kpiGrid">
      <Kpi label="Trạng thái" value={u.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}/>
      <Kpi label="Vai trò hệ thống" value={u.systemAdmin ? "System admin" : "Thành viên"}/>
      <Kpi label="Đăng nhập" value={u.authSource === "OIDC" ? "SSO" : "Mật khẩu"} hint={`Gần nhất: ${ago(u.lastLoginAt)}`}/>
      <Kpi label="Phiên đang mở" value={num(d.activeSessions)}/>
    </div>
    <div className="grid2">
      <Card title={`Workspace (${d.workspaces.length})`}>{d.workspaces.length ? <table className="table"><tbody>{d.workspaces.map((w) => <tr key={w.id}><td><Link href={`/admin/workspaces/${w.id}`}>{w.name}</Link></td><td><Pill value="PRIVATE" label={w.role}/></td></tr>)}</tbody></table> : <StateView kind="empty" title="Không thuộc workspace nào"/>}</Card>
      <Card title={`Ứng dụng (${d.projects.length})`}>{d.projects.length ? <table className="table"><tbody>{d.projects.map((p) => <tr key={p.id}><td><Link href={`/admin/applications/${p.id}`}>{p.name}</Link><small>{p.workspaceName}</small></td><td>{p.owner ? <Pill value="ACTIVE" label="Chủ sở hữu"/> : <Pill value="PRIVATE" label={p.role}/>}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa tham gia ứng dụng nào"/>}</Card>
    </div>
    <Card title="Hoạt động gần đây"><AuditTable rows={d.recentActivity} compact/></Card>
  </>);
}

function WorkspaceList() {
  const router = useRouter(); const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState("");
  const { data, error, loading, reload } = useLoad(() => api.admin.workspaces(page, query), [page, query]);
  return (
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}><input aria-label="Tìm workspace" placeholder="Tìm workspace" value={q} onChange={(e) => setQ(e.target.value)}/><button className="btn">Tìm</button></form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty"/> : (<>
        <table className="table"><thead><tr><th>Workspace</th><th>Thành viên</th><th>Ứng dụng</th><th>Tạo</th><th>Hoạt động gần nhất</th></tr></thead>
          <tbody>{data!.items.map((w) => <tr key={w.id} className="clickRow" onClick={() => router.push(`/admin/workspaces/${w.id}`)}><td><Link href={`/admin/workspaces/${w.id}`}><b>{w.name}</b></Link><small>{w.slug}</small></td><td>{w.members}</td><td>{w.projects}</td><td>{fmtDate(w.createdAt)}</td><td>{ago(w.lastActivityAt)}</td></tr>)}</tbody></table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/>
      </>)}
    </Card>
  );
}

function WorkspaceDetail({ id }: { id: string }) {
  const { data, error, loading, reload } = useLoad(() => api.admin.workspace(id), [id]);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const d = data!;
  return (<>
    <PageHead title={d.workspace.name} sub={`${d.workspace.slug} · tạo ${fmtDate(d.workspace.createdAt)}`}/>
    <div className="grid2">
      <Card title={`Thành viên (${d.members.length})`}><table className="table"><thead><tr><th>Người dùng</th><th>Vai trò</th><th>Trạng thái</th></tr></thead><tbody>{d.members.map((m) => <tr key={m.userId}><td><Link href={`/admin/users/${m.userId}`}>{m.displayName ?? m.username}</Link><small>{m.username}</small></td><td>{m.role}</td><td>{m.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}</td></tr>)}</tbody></table></Card>
      <Card title={`Ứng dụng (${d.projects.length})`}><AppTable rows={d.projects}/></Card>
    </div>
    <Card title="Hoạt động"><AuditTable rows={d.recentActivity} compact/></Card>
  </>);
}

// ------------------------------------------------------------------ application inventory
function publishPill(a: App) {
  if (!a.publishStatus) return <span className="muted">Chưa xuất bản</span>;
  return <span title={fmtDate(a.publishedAt)}><Pill value={a.publishStatus}/> <small className="muted">demo</small></span>;
}
function AppTable({ rows }: { rows: App[] }) {
  const router = useRouter();
  if (!rows.length) return <StateView kind="empty" title="Chưa có ứng dụng"/>;
  return (
    <table className="table"><thead><tr><th>Ứng dụng</th><th>Chủ sở hữu</th><th>Thành viên</th><th>Truy cập</th><th>Phiên bản</th><th>Cập nhật</th><th>Xuất bản</th></tr></thead>
      <tbody>{rows.map((a) => <tr key={a.id} className="clickRow" onClick={() => router.push(`/admin/applications/${a.id}`)}>
        <td><Link href={`/admin/applications/${a.id}`}><b>{a.name}</b></Link><small>{a.workspaceName}{a.active ? "" : " · đã xóa"}</small></td><td>{a.owner}</td><td>{a.members}</td>
        <td><Pill value={a.visibility} label={a.visibility === "PUBLIC" ? "Công khai" : "Riêng tư"}/></td><td>v{a.latestVersion ?? "—"} <small className="muted">r{a.revision}</small></td><td>{ago(a.updatedAt)}</td><td>{publishPill(a)}</td></tr>)}</tbody></table>
  );
}

function AppsPage() {
  const [page, setPage] = useState(0); const [q, setQ] = useState(""); const [query, setQuery] = useState(""); const [visibility, setVisibility] = useState(""); const [status, setStatus] = useState("active");
  const { data, error, loading, reload } = useLoad(() => api.admin.applications({ page, q: query, visibility, status }), [page, query, visibility, status]);
  return (<>
    <PageHead title="Ứng dụng" sub="Danh mục mọi ứng dụng trong công ty. Chi phí AI/hosting và điểm rủi ro chưa được ghi nhận nên không hiển thị."/>
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}>
        <input aria-label="Tìm ứng dụng" placeholder="Tìm theo tên ứng dụng hoặc chủ sở hữu" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Quyền truy cập" value={visibility} onChange={(e) => { setPage(0); setVisibility(e.target.value); }}><option value="">Mọi quyền truy cập</option><option value="PRIVATE">Riêng tư</option><option value="PUBLIC">Công khai</option></select>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}><option value="active">Đang hoạt động</option><option value="deleted">Đã xóa</option><option value="all">Tất cả</option></select>
        <button className="btn">Tìm</button>
      </form>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (<><AppTable rows={data!.items}/><Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>)}
    </Card>
  </>);
}

function AppDetail({ id }: { id: string }) {
  const router = useRouter();
  const { data, error, loading, reload } = useLoad(() => api.admin.application(id), [id]);
  const ws = useLoad(() => (data ? api.admin.workspace(data.app.workspaceId) : Promise.resolve(null)), [data?.app.workspaceId]);
  const [tab, setTab] = useState<"overview" | "members" | "versions" | "prompts" | "deployments" | "audit">("overview");
  const [newOwner, setNewOwner] = useState(""); const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const d = data!; const a = d.app;
  async function act(fn: () => Promise<unknown>, ok: string) { setBusy(true); setMsg(null); try { await fn(); setMsg(ok); reload(); } catch (e) { setMsg(errText(e, "Thao tác thất bại.")); } finally { setBusy(false); } }
  const tabs: [typeof tab, string][] = [["overview", "Tổng quan"], ["members", `Thành viên (${d.members.length})`], ["versions", "Phiên bản"], ["prompts", "Hoạt động AI"], ["deployments", "Xuất bản"], ["audit", "Nhật ký"]];
  return (<>
    <PageHead title={a.name} sub={`${a.workspaceName} · chủ sở hữu ${a.owner} · ${a.active ? "đang hoạt động" : "đã xóa"}`}
      actions={a.active ? <button className="btn danger" disabled={busy} onClick={() => { if (window.confirm(`Xóa ứng dụng "${a.name}"? (xóa mềm, có ghi nhật ký)`)) void act(() => api.deleteProject(a.workspaceId, a.id, a.revision), "Đã xóa ứng dụng."); }}>Xóa</button> : undefined}/>
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    <div className="tabs" role="tablist">{tabs.map(([k, l]) => <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? "active" : ""} onClick={() => setTab(k)}>{l}</button>)}</div>
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
          </form>) : <p className="muted">Ứng dụng đã bị xóa.</p>}
          <p className="hint">Chủ cũ ở lại dự án với vai trò Editor. Mọi thay đổi được ghi nhật ký.</p>
        </Card>
        <Card title="Chưa triển khai"><ComingSoon title="Lưu trữ (archive), chặn xuất bản công khai, chi phí, điểm bảo mật">Các chức năng này cần dữ liệu và chính sách chưa có trong hệ thống.</ComingSoon></Card>
      </div>
    </>) : null}
    {tab === "members" ? <Card><table className="table"><thead><tr><th>Người dùng</th><th>Vai trò</th><th>Trạng thái</th></tr></thead><tbody>{d.members.map((m) => <tr key={m.userId}><td><Link href={`/admin/users/${m.userId}`}>{m.displayName ?? m.username}</Link><small>{m.username}</small></td><td>{m.role}</td><td>{m.enabled ? <Pill value="ACTIVE" label="Hoạt động"/> : <Pill value="DISABLED" label="Bị khóa"/>}</td></tr>)}</tbody></table></Card> : null}
    {tab === "versions" ? <Card><table className="table"><thead><tr><th>Phiên bản</th><th>Loại</th><th>Mô tả</th><th>Tác giả</th><th>Thời gian</th><th/></tr></thead><tbody>{d.versions.map((v, i) => <tr key={v.id}><td><b>v{v.versionNumber}</b>{i === 0 ? <small>hiện tại</small> : null}</td><td>{v.kind}</td><td>{v.summary}</td><td>{v.createdBy ?? "—"}</td><td>{fmtDate(v.createdAt)}</td>
      <td>{i > 0 && a.active ? <button className="btn sm" disabled={busy} onClick={() => { if (window.confirm(`Khôi phục v${v.versionNumber}? Một phiên bản mới sẽ được tạo.`)) void act(() => api.restoreVersion(a.workspaceId, a.id, v.id, a.revision), `Đã khôi phục v${v.versionNumber}.`); }}>Khôi phục</button> : null}</td></tr>)}</tbody></table></Card> : null}
    {tab === "prompts" ? <Card>{d.prompts.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Prompt</th><th>Model</th><th>Kết quả</th></tr></thead><tbody>{d.prompts.map((p) => <tr key={p.id}><td>{ago(p.createdAt)}</td><td>{p.user ?? "—"}</td><td>{p.text}</td><td className="code">{p.model ?? p.provider ?? "—"}</td><td>{p.outcome ? <Pill value={p.outcome}/> : "—"}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa có prompt"/>}</Card> : null}
    {tab === "deployments" ? <Card>{d.deployments.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Phiên bản</th><th>Truy cập</th><th>Trạng thái</th><th>Môi trường</th><th>Lỗi</th></tr></thead><tbody>{d.deployments.map((x) => <tr key={x.id}><td>{fmtDate(x.createdAt)}</td><td>v{x.versionNumber ?? "—"}</td><td>{x.visibility}</td><td><Pill value={x.status}/></td><td>{x.provider === "mock" ? "Demo deployment (mô phỏng)" : x.provider}</td><td>{x.error ?? "—"}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa xuất bản lần nào"/>}</Card> : null}
    {tab === "audit" ? <Card><AuditTable rows={d.audit}/></Card> : null}
    <p><button className="btn ghost" onClick={() => router.push("/admin/applications")}>← Danh sách ứng dụng</button></p>
  </>);
}

// ------------------------------------------------------------------ AI control
function AiPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.ai(), []);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const a = data!;
  return (<>
    <PageHead title="AI Control" sub="Mọi yêu cầu AI đi qua máy chủ; nhân viên không giữ API key."/>
    <div className="kpiGrid">
      <Kpi label="Nhà cung cấp" value={a.provider === "openrouter" ? "OpenRouter" : "Mô phỏng"} hint={a.configured ? "Đã cấu hình key" : "Chưa có OPENROUTER_API_KEY"}/>
      <Kpi label="Chế độ" value={a.mode === "auto" ? "Tự động (model miễn phí)" : "Mô phỏng"}/>
      <Kpi label="Lượt AI hôm nay" value={num(a.requestsToday)} hint={`${num(a.externalToday)} qua OpenRouter`}/>
      <Kpi label="Lượt AI trong tháng" value={num(a.requestsMonth)}/>
      <Kpi label="Giới hạn" value={`${a.dailyLimitPerUser}/ngày/người`} hint={`${a.promptsPerMinute} prompt/phút`}/>
    </div>
    <div className="grid2">
      <Card title="Theo model (tháng này)">{a.byModelMonth.length ? <table className="table"><thead><tr><th>Nhà cung cấp</th><th>Model</th><th>Kết quả</th><th>Số lượt</th></tr></thead><tbody>{a.byModelMonth.map((m, i) => <tr key={i}><td>{m.provider}</td><td className="code">{m.model ?? "—"}</td><td><Pill value={m.outcome}/></td><td>{num(m.count)}</td></tr>)}</tbody></table> : <StateView kind="empty"/>}</Card>
      <Card title={`Model được phép (${a.models.length})`}>{a.models.length ? <ul className="plainList">{a.models.map((m) => <li key={m.id}><b>{m.name}</b><small className="code">{m.id}</small></li>)}</ul> : <p className="muted">Không có model ngoài: đang dùng bộ mô phỏng.</p>}</Card>
    </div>
    <div className="grid2">
      <Card title="Ghi nhận token & chi phí"><ComingSoon title="Chưa ghi nhận token/chi phí">Bảng ai_usage_events và bảng giá model sẽ được thêm ở Phase 4. Không hiển thị số ước lượng.</ComingSoon></Card>
      <Card title="Nhiều nhà cung cấp & quyền theo model"><ComingSoon title="OpenAI, Anthropic, Gemini, model nội bộ">Chưa tích hợp. Kiến trúc hiện có port LLMProvider và AiService để mở rộng (Phase 6).</ComingSoon></Card>
    </div>
    <Card title="Lượt AI gần đây"><table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Prompt</th><th>Model</th><th>Kết quả</th></tr></thead><tbody>{a.recent.map((p) => <tr key={p.id}><td>{ago(p.createdAt)}</td><td>{p.user ?? "—"}</td><td>{p.text}</td><td className="code">{p.model ?? p.provider}</td><td>{p.outcome ? <Pill value={p.outcome}/> : "—"}</td></tr>)}</tbody></table></Card>
  </>);
}

// ------------------------------------------------------------------ components
function ComponentsPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.components(), []);
  const [open, setOpen] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  return (<>
    <PageHead title="Component Registry" sub="Danh sách component đã được duyệt. AI và trình chỉnh sửa chỉ được dùng các component này."/>
    <Card>
      <table className="table"><thead><tr><th>Component</th><th>Nhóm</th><th>Phiên bản</th><th>Trạng thái</th><th>Dùng trong</th><th/></tr></thead>
        <tbody>{data!.map((c) => (<Fragment key={c.id}>
          <tr><td><b>{c.name}</b><small className="code">{c.id}</small><small>{c.description}</small></td><td>{c.category}</td><td>{c.latestVersion}</td><td><Pill value={c.status === "ACTIVE" ? "ACTIVE" : c.status} label={c.status === "ACTIVE" ? "Đã duyệt" : c.status}/></td>
            <td>{num(c.usedInProjects)} ứng dụng<small>{num(c.sections)} mục</small></td><td><button className="btn sm" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>Schema</button></td></tr>
          {open === c.id ? <tr className="detailRow"><td colSpan={6}><pre>{c.propsSchema ? JSON.stringify(JSON.parse(c.propsSchema), null, 2) : "—"}</pre></td></tr> : null}
        </Fragment>))}</tbody></table>
    </Card>
    <Card title="Đóng góp component"><ComingSoon title="Gửi duyệt → kiểm tra → phê duyệt → ngừng dùng">Quy trình đóng góp component (PRIVATE → SUBMITTED → REVIEW → APPROVED) chưa triển khai (Phase 5). Hiện registry cố định, không có ảnh chụp hay đánh giá.</ComingSoon></Card>
  </>);
}

// ------------------------------------------------------------------ audit
function AuditPage() {
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

// ------------------------------------------------------------------ health
const HEALTH_LABEL: Record<HealthItem["status"], string> = { HEALTHY: "Khỏe", DEGRADED: "Suy giảm", UNAVAILABLE: "Không khả dụng", UNKNOWN: "Không rõ", NOT_CONFIGURED: "Chưa cấu hình" };
function HealthPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.health(), []);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const h = data!;
  return (<>
    <PageHead title="Sức khỏe hệ thống" sub={`Kiểm tra trực tiếp lúc ${fmtDate(h.checkedAt)}`} actions={<button className="btn" onClick={reload} disabled={loading}>{loading ? "Đang kiểm tra…" : "Kiểm tra lại"}</button>}/>
    <div className="healthGrid">{h.items.map((i) => <div key={i.name} className={`healthCard h-${i.status}`}><div className="row between"><b>{i.name}</b><Pill value={i.status} label={HEALTH_LABEL[i.status]}/></div><small>{i.detail ?? ""}</small>{i.latencyMs !== null ? <small className="muted">{i.latencyMs} ms</small> : null}</div>)}</div>
    <div className="kpiGrid">
      <Kpi label="Uptime API" value={`${Math.floor(h.uptimeSeconds / 3600)} giờ ${Math.floor((h.uptimeSeconds % 3600) / 60)} phút`}/><Kpi label="Phiên bản schema DB" value={`V${h.schemaVersion ?? "?"}`}/>
      <Kpi label="Hàng đợi xuất bản" value={h.publishQueueDepth ?? "—"} hint={`Dead-letter: ${h.deadLetterDepth ?? "—"}`}/><Kpi label="Runtime" value={`Java ${h.javaVersion}`} hint={h.profiles.join(", ") || "default"}/>
    </div>
  </>);
}

// ------------------------------------------------------------------ settings (read-only)
const SETTING_GROUP: Record<string, string> = { authentication: "Xác thực", limits: "Giới hạn", ai: "AI", retention: "Lưu trữ & dọn dẹp", deployment: "Triển khai", network: "Mạng" };
function SettingsPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.settings(), []);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  return (<>
    <PageHead title="Cài đặt" sub="Cấu hình đang hiệu lực (chỉ xem). Thay đổi bằng biến môi trường rồi khởi động lại; không hiển thị secret."/>
    <div className="grid2">{Object.entries(data!).map(([group, values]) => (
      <Card key={group} title={SETTING_GROUP[group] ?? group}><dl className="kv">{Object.entries(values).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v === null || v === undefined ? "—" : String(v)}</dd></div>)}</dl></Card>
    ))}</div>
  </>);
}
