"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Fragment, useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { api } from "@/lib/http-api";
import type { Connector, CostLine, Department, SecurityFinding, AccessRule, AiBudget, AdminAlert, EffectiveModel, AdminApp as App, AiProbe, PackageView, RepoRow, SettingView, BlockDto, TemplateDto, AiUsageReport, AuditRow, HealthItem, UsageBucket, UsageTotals } from "@/lib/http-types";
import { useSession } from "../session";
import { rememberPortal } from "../routing";
import { useLoad } from "../useLoad";
import { BlockStatus, blockPage, CheckList, ReviewTimeline, SchemaThumb } from "../library";
import { actionLabel, ago, Card, ComingSoon, ErrorState, errText, fmtDate, Kpi, NavLink, num, Pager, Pill, StateView, tok, usd } from "../ui";

const NAV: [string, string, string][] = [
  ["", "Tổng quan", "▦"], ["users", "Người dùng & Workspace", "◎"], ["applications", "Ứng dụng", "▤"], ["ai", "AI Control", "✦"], ["ai-governance", "Quản trị AI", "⚖"], ["alerts", "Cảnh báo", "!"], ["security", "Bảo mật", "⛨"], ["costs", "Chi phí", "$"], ["departments", "Phòng ban", "⌘"], ["identity", "Định danh (SSO/SCIM)", "⚿"], ["connectors", "Connector", "⇄"], ["backups", "Sao lưu", "⛁"],
  ["components", "Components", "◇"], ["templates", "Templates", "▧"], ["audit", "Nhật ký kiểm toán", "≡"], ["builds", "Build & lưu trữ", "⬢"], ["packages", "Packages", "▣"], ["system", "Sức khỏe hệ thống", "♥"], ["settings", "Cài đặt", "⚙"]
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
    case "ai-governance": return <AiGovernancePage/>;
    case "alerts": return <AlertsPage/>;
    case "security": return <SecurityPage/>;
    case "costs": return <CostsPage/>;
    case "departments": return <DepartmentsPage/>;
    case "identity": return <IdentityPage/>;
    case "connectors": return <ConnectorsPage/>;
    case "backups": return <BackupsPage/>;
    case "components": return <ComponentsPage/>;
    case "templates": return <TemplatesAdmin/>;
    case "builds": return <BuildsPage/>;
    case "packages": return <PackagesPage/>;
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
      <AiMonthCard/>
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
              <td><Link href={`/admin/users/${u.id}`}><b>{u.displayName ?? u.username}</b></Link><small>{u.username}{u.email ? ` · ${u.email}` : ""} · {u.authSource === "OIDC" ? "SSO" : u.authSource === "SCIM" ? "SCIM" : "Mật khẩu"}</small></td>
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
      <Kpi label="Đăng nhập" value={u.authSource === "OIDC" ? "SSO" : u.authSource === "SCIM" ? "SSO (cấp qua SCIM)" : "Mật khẩu"} hint={`Gần nhất: ${ago(u.lastLoginAt)}`}/>
      <Kpi label="MFA" value={u.authSource === "LOCAL" ? "Không áp dụng" : "Do IdP quản lý"} hint={u.authSource === "LOCAL" ? "Tài khoản cục bộ: dùng cho quản trị khẩn cấp / môi trường thử" : "MFA managed by Identity Provider"}/>
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
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}><option value="active">Đang hoạt động</option><option value="archived">Đã lưu trữ</option><option value="deleted">Đã xóa</option><option value="all">Tất cả</option></select>
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
    <PageHead title={a.name} sub={`${a.workspaceName} · chủ sở hữu ${a.owner} · ${!a.active ? "đã xóa" : a.lifecycle === "ARCHIVED" ? "đã lưu trữ (chỉ xem, ngoại tuyến)" : "đang hoạt động"}`}
      actions={a.active ? <div className="row">
        {a.lifecycle === "ARCHIVED" ? <button className="btn" disabled={busy} onClick={() => void act(() => api.admin.restoreApp(a.id), "Đã khôi phục ứng dụng (website vẫn ngoại tuyến tới khi xuất bản lại).")}>Khôi phục</button>
          : <button className="btn" disabled={busy} onClick={() => { if (window.confirm(`Lưu trữ "${a.name}"? Ứng dụng chỉ còn xem được và website bị gỡ khỏi mạng.`)) void act(() => api.admin.archiveApp(a.id), "Đã lưu trữ ứng dụng."); }}>Lưu trữ</button>}
        <button className="btn danger" disabled={busy} onClick={() => { if (window.confirm(`Xóa ứng dụng "${a.name}"? (xóa mềm, có ghi nhật ký)`)) void act(() => api.deleteProject(a.workspaceId, a.id, a.revision), "Đã xóa ứng dụng."); }}>Xóa</button></div> : undefined}/>
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
    {tab === "deployments" ? <Card>{d.deployments.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Phiên bản</th><th>Truy cập</th><th>Trạng thái</th><th>Môi trường</th><th>Lỗi</th></tr></thead><tbody>{d.deployments.map((x) => <tr key={x.id}><td>{fmtDate(x.createdAt)}</td><td>v{x.versionNumber ?? "—"}</td><td>{x.visibility}</td><td><Pill value={x.status}/></td><td>{x.provider === "mock" ? "Demo deployment (mô phỏng)" : x.provider === "static" ? "Trang tĩnh (thật)" : x.provider}</td><td>{x.error ?? "—"}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Chưa xuất bản lần nào"/>}</Card> : null}
    {tab === "audit" ? <Card><AuditTable rows={d.audit}/></Card> : null}
    <p><button className="btn ghost" onClick={() => router.push("/admin/applications")}>← Danh sách ứng dụng</button></p>
  </>);
}

// ------------------------------------------------------------------ AI control
const OUTCOME_LABEL: Record<string, string> = { OK: "Thành công", BAD_OUTPUT: "Trả lời không dùng được", ERROR: "Lỗi (không có trả lời)" };

/** How complete a provider-reported total is: calls with no reported usage are counted, never estimated. */
function coverage(t: UsageTotals) {
  if (!t.calls) return "Chưa có lượt gọi model nào";
  return `${num(t.calls - t.callsWithoutUsage)}/${num(t.calls)} lượt gọi có số token do nhà cung cấp báo`;
}

function UsageTable({ rows, keyLabel }: { rows: UsageBucket[]; keyLabel: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có dữ liệu" detail="Chưa có lượt gọi model thật nào trong khoảng thời gian này."/>;
  return <table className="table"><thead><tr><th>{keyLabel}</th><th>Lượt gọi</th><th>Lỗi</th><th>Token vào / ra</th><th>Tổng token</th><th>Chi phí</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ? <><b>{r.label}</b>{r.label !== r.key ? <small className="code">{r.key}</small> : null}</> : <span className="code">{r.key}</span>}</td>
      <td>{num(r.totals.calls)}</td><td>{num(r.totals.failedCalls)}</td><td>{num(r.totals.promptTokens)} / {num(r.totals.completionTokens)}</td>
      <td><b>{num(r.totals.totalTokens)}</b>{r.totals.callsWithoutUsage ? <small>{num(r.totals.callsWithoutUsage)} lượt không có số liệu</small> : null}</td>
      <td>{usd(r.totals.costUsd)}</td></tr>)}</tbody></table>;
}

function DailyBars({ daily }: { daily: AiUsageReport["daily"] }) {
  const max = Math.max(1, ...daily.map((d) => d.totalTokens));
  const day = (iso: string) => new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit" }).format(new Date(`${iso}T00:00:00`));
  return <figure className="barsFig">
    <div className="bars" role="img" aria-label={`Token theo ngày từ ${day(daily[0]?.day ?? "")} đến hôm nay, cao nhất ${num(max)} token`}>
      {daily.map((d) => <div key={d.day} className="bar" title={`${day(d.day)}: ${num(d.totalTokens)} token, ${num(d.calls)} lượt gọi${d.failedCalls ? `, ${num(d.failedCalls)} lỗi` : ""}`}>
        <span style={{ height: `${Math.round((d.totalTokens / max) * 100)}%` }}/></div>)}
    </div>
    <figcaption className="barsAxis"><span>{daily.length ? day(daily[0].day) : ""}</span><span>Token mỗi ngày · cao nhất {num(max === 1 && !daily.some((d) => d.totalTokens) ? 0 : max)}</span><span>Hôm nay</span></figcaption>
  </figure>;
}

function AiCallsLog({ models }: { models: string[] }) {
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState(""); const [model, setModel] = useState("");
  const params = useMemo(() => ({ page, outcome: outcome || undefined, model: model || undefined }), [page, outcome, model]);
  const { data, error, loading, reload } = useLoad(() => api.admin.aiCalls(params), [params]);
  return <Card title="Nhật ký lượt gọi model">
    <div className="filters">
      <select aria-label="Kết quả" value={outcome} onChange={(e) => { setPage(0); setOutcome(e.target.value); }}><option value="">Mọi kết quả</option>{Object.entries(OUTCOME_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <select aria-label="Model" value={model} onChange={(e) => { setPage(0); setModel(e.target.value); }}><option value="">Mọi model</option>{models.map((m) => <option key={m} value={m}>{m}</option>)}</select>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.total ? <StateView kind="empty" title="Chưa có lượt gọi" detail="Bộ mô phỏng không gọi model nên không có dòng nào ở đây."/> : <>
      <table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Ứng dụng</th><th>Model</th><th>Kết quả</th><th>Token vào / ra</th><th>Chi phí</th><th>Thời gian chạy</th></tr></thead>
        <tbody>{data!.items.map((c) => <tr key={c.id}><td>{ago(c.createdAt)}<small>{fmtDate(c.createdAt)}</small></td>
          <td><Link href={`/admin/users/${c.userId}`}>{c.user ?? c.userId.slice(0, 8)}</Link></td>
          <td><Link href={`/admin/applications/${c.projectId}`}>{c.project ?? "—"}</Link><small>{c.workspace}</small></td>
          <td className="code">{c.model}</td><td><Pill value={c.outcome} label={OUTCOME_LABEL[c.outcome]}/>{c.httpStatus && c.httpStatus !== 200 ? <small>HTTP {c.httpStatus}</small> : null}</td>
          <td>{c.totalTokens == null ? <span className="muted">không báo</span> : <>{tok(c.promptTokens)} / {tok(c.completionTokens)}</>}</td>
          <td>{usd(c.costUsd)}{c.costSource ? <small>{c.costSource === "CATALOG" ? "theo bảng giá" : "nhà cung cấp báo"}</small> : null}</td><td>{(c.latencyMs / 1000).toFixed(1)} s{c.requestId ? <small className="code">{c.requestId.slice(0, 8)}</small> : null}</td></tr>)}</tbody></table>
      <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>}
  </Card>;
}

/** Configured providers and per-model switches. "Configured" = key + model list present; reachability only after a live check. */
/** Overview: this month's provider-reported AI usage (same source as AI Control). */
function AiMonthCard() {
  const { data, error, reload } = useLoad(() => api.admin.aiUsage(new Date().getDate()), []);
  const t = data?.totals;
  return <Card title="AI tháng này" actions={<Link className="btn sm" href="/admin/ai">AI Control</Link>}>
    {error ? <ErrorState error={error} retry={reload}/> : !t ? <StateView kind="loading"/> : <div className="kpiGrid">
      <Kpi label="Lượt gọi model" value={num(t.calls)} hint={`${num(t.failedCalls)} lỗi`}/>
      <Kpi label="Token" value={num(t.totalTokens)} hint={t.callsWithoutUsage ? `${num(t.callsWithoutUsage)} lượt không có số liệu` : "số liệu nhà cung cấp"}/>
      <Kpi label="Chi phí" value={usd(t.costUsd)} hint={t.calls ? `${num(t.costReportedCalls)}/${num(t.calls)} lượt có chi phí` : "Chưa có lượt gọi"}/>
    </div>}
    <p className="hint">Ngân sách theo tổ chức/dự án và ngưỡng cảnh báo: chưa triển khai (đang có giới hạn token theo người dùng và workspace).</p>
  </Card>;
}

function ProvidersCard() {
  const { data, error, loading, reload } = useLoad(() => api.admin.aiProviders(), []);
  const [probes, setProbes] = useState<Record<string, AiProbe | "running">>({}); const [err, setErr] = useState<string | null>(null);
  async function probe(id: string) { setProbes((p) => ({ ...p, [id]: "running" })); try { const r = await api.admin.aiProbe(id); setProbes((p) => ({ ...p, [id]: r })); } catch (x) { setErr(errText(x, "Không kiểm tra được.")); setProbes((p) => { const n = { ...p }; delete n[id]; return n; }); } }
  // optimistic: the switch moves at once and is put back if the server refuses
  const [over, setOver] = useState<Record<string, boolean>>({});
  async function toggle(modelId: string, enabled: boolean) {
    setErr(null); setOver((o) => ({ ...o, [modelId]: enabled }));
    try { await api.admin.aiModelPolicy(modelId, enabled); } catch (x) { setErr(errText(x, "Không đổi được.")); setOver((o) => { const n = { ...o }; delete n[modelId]; return n; }); }
  }
  return <Card title="Nhà cung cấp AI">
    <p className="hint">Khóa API chỉ đọc từ biến môi trường của máy chủ, không bao giờ hiển thị. Model của nhà cung cấp tính phí mặc định TẮT cho tới khi quản trị viên bật; “Tự động” chỉ dùng model miễn phí của OpenRouter. Chi phí: số nhà cung cấp báo, hoặc tính từ bảng giá bên dưới.</p>
    {err ? <p className="formError" role="alert">{err}</p> : null}
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : <div className="providerList">{data!.map((p) => {
      const pr = probes[p.id];
      return <section key={p.id} className="providerItem" aria-label={p.name}>
        <div className="row between"><div><b>{p.name}</b>{p.paid ? <Pill value="UNKNOWN" label="Tính phí"/> : <Pill value="ACTIVE" label="Không tính phí"/>}
          <small>{p.configured ? `Đã cấu hình${p.endpointHost ? ` · ${p.endpointHost}` : ""}` : `Chưa cấu hình — ${p.configHint}`}</small></div>
          <div className="row">{pr && pr !== "running" ? <Pill value={pr.ok ? "HEALTHY" : "UNAVAILABLE"} label={pr.ok ? `Kết nối được · ${pr.latencyMs} ms · ${pr.detail}` : `Lỗi: ${pr.detail}`}/> : null}
            {p.configured ? <button className="btn sm" disabled={pr === "running"} onClick={() => void probe(p.id)}>{pr === "running" ? "Đang kiểm tra…" : "Kiểm tra kết nối"}</button> : null}</div></div>
        {p.models.length ? <table className="table"><thead><tr><th>Model</th><th>Giá hiện hành (USD / 1 triệu token)</th><th>Cho phép dùng</th></tr></thead>
          <tbody>{p.models.map((m) => <tr key={m.id}><td><b>{m.name}</b>{m.name !== m.id ? <small className="code">{m.id}</small> : null}</td>
            <td>{m.price ? `vào ${m.price.inputUsdPerMTok} · ra ${m.price.outputUsdPerMTok}` : <span className="muted">{p.id === "openrouter" ? "OpenRouter báo chi phí" : "Chưa có giá — chi phí sẽ là “không rõ”"}</span>}</td>
            <td><label className="switch"><input type="checkbox" checked={over[m.id] ?? m.enabled} onChange={(e) => void toggle(m.id, e.target.checked)} aria-label={`Cho phép ${m.id}`}/> {(over[m.id] ?? m.enabled) ? "Bật" : "Tắt"}</label></td></tr>)}</tbody></table> : null}
      </section>;
    })}</div>}
  </Card>;
}

/** Explicit prices, never built in. Rows are immutable: a change is a new row from now on, so past costs stay reproducible. */
function PricingCard() {
  const providers = useLoad(() => api.admin.aiProviders(), []);
  const { data, error, loading, reload } = useLoad(() => api.admin.aiPricing(), []);
  const [f, setF] = useState({ modelId: "", input: "", output: "", note: "" }); const [err, setErr] = useState<string | null>(null); const [ok, setOk] = useState<string | null>(null);
  const models = (providers.data ?? []).flatMap((p) => p.models.map((m) => m.id));
  async function add(e: FormEvent) {
    e.preventDefault(); setErr(null); setOk(null);
    try { await api.admin.aiAddPrice({ modelId: f.modelId, inputUsdPerMTok: Number(f.input), outputUsdPerMTok: Number(f.output), note: f.note.trim() || undefined });
      setOk(`Đã thêm giá cho ${f.modelId}, áp dụng từ bây giờ.`); setF({ modelId: "", input: "", output: "", note: "" }); reload(); }
    catch (x) { setErr(errText(x, "Không thêm được giá.")); }
  }
  return <Card title="Bảng giá model">
    <p className="hint">Dùng để tính chi phí khi nhà cung cấp không báo (OpenAI, Anthropic, Gemini, model nội bộ). Hệ thống không có sẵn giá nào. Giá không sửa được: thay đổi = thêm dòng mới áp dụng từ thời điểm thêm; chi phí đã ghi không bị tính lại.</p>
    <form className="filters wrap" onSubmit={(e) => void add(e)}>
      <select aria-label="Model" value={f.modelId} onChange={(e) => setF({ ...f, modelId: e.target.value })} required><option value="">Chọn model</option>{models.map((m) => <option key={m} value={m}>{m}</option>)}</select>
      <input aria-label="Giá token vào (USD / 1 triệu)" type="number" min="0" max="10000" step="0.000001" placeholder="Vào USD/1M" value={f.input} onChange={(e) => setF({ ...f, input: e.target.value })} required/>
      <input aria-label="Giá token ra (USD / 1 triệu)" type="number" min="0" max="10000" step="0.000001" placeholder="Ra USD/1M" value={f.output} onChange={(e) => setF({ ...f, output: e.target.value })} required/>
      <input aria-label="Ghi chú (nguồn giá)" placeholder="Ghi chú, ví dụ: theo hợp đồng 2026" maxLength={200} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
      <button className="btn primary" disabled={!f.modelId}>Thêm giá</button>
    </form>
    {ok ? <p className="hint" role="status">{ok}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.length ? <StateView kind="empty" title="Chưa có giá nào" detail="Chi phí của model trả phí sẽ hiển thị “không rõ” cho tới khi có giá."/> :
      <table className="table"><thead><tr><th>Model</th><th>Vào / Ra (USD / 1M token)</th><th>Áp dụng từ</th><th>Ghi chú</th><th>Người thêm</th></tr></thead>
        <tbody>{data!.map((r) => <tr key={r.id}><td className="code">{r.modelId}</td><td>{r.inputUsdPerMTok} / {r.outputUsdPerMTok}</td><td>{fmtDate(r.effectiveFrom)}</td><td>{r.note || "—"}</td><td>{r.createdBy ?? "—"}</td></tr>)}</tbody></table>}
  </Card>;
}

function AiPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.ai(), []);
  const [days, setDays] = useState(30);
  const usage = useLoad(() => api.admin.aiUsage(days), [days]);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const a = data!;
  const u = usage.data;
  const t = u?.totals;
  return (<>
    <PageHead title="AI Control" sub="Mọi yêu cầu AI đi qua máy chủ; nhân viên không giữ API key. Token và chi phí lấy đúng từ số liệu nhà cung cấp trả về, không ước lượng."/>
    <div className="kpiGrid">
      <Kpi label="Nhà cung cấp" value={a.provider === "openrouter" ? "OpenRouter" : "Mô phỏng"} hint={a.configured ? "Đã cấu hình key" : "Chưa có OPENROUTER_API_KEY"}/>
      <Kpi label="Lượt AI hôm nay" value={num(a.requestsToday)} hint={`${num(a.externalToday)} qua OpenRouter · ${num(a.requestsMonth)} trong tháng`}/>
      <Kpi label="Giới hạn" value={`${a.dailyLimitPerUser} lượt/ngày/người`}
        hint={u ? [u.limits.dailyTokensPerUser ? `${num(u.limits.dailyTokensPerUser)} token/ngày/người` : "Không giới hạn token/người",
          u.limits.monthlyTokensPerWorkspace ? `${num(u.limits.monthlyTokensPerWorkspace)} token/tháng/workspace` : "không giới hạn token/workspace"].join(" · ") : `${a.promptsPerMinute} prompt/phút`}/>
    </div>

    <Card title="Mức sử dụng model" actions={<div className="seg" role="group" aria-label="Khoảng thời gian">{[1, 7, 30, 90].map((d) =>
      <button key={d} className={`btn sm ${d === days ? "primary" : ""}`} aria-pressed={d === days} onClick={() => setDays(d)}>{d === 1 ? "Hôm nay" : `${d} ngày`}</button>)}</div>}>
      {usage.error ? <ErrorState error={usage.error} retry={usage.reload}/> : !u || !t ? <StateView kind="loading"/> : <>
        <div className="kpiGrid">
          <Kpi label="Lượt gọi model" value={num(t.calls)} hint={t.calls ? `${num(t.failedCalls)} lỗi / không dùng được (gồm lượt thử lại)` : "Chưa có"}/>
          <Kpi label="Tổng token" value={num(t.totalTokens)} hint={`${num(t.promptTokens)} vào · ${num(t.completionTokens)} ra`}/>
          <Kpi label="Chi phí (nhà cung cấp báo)" value={usd(t.costUsd)} hint={t.calls ? `${num(t.costReportedCalls)}/${num(t.calls)} lượt có báo chi phí` : "Model miễn phí báo $0"}/>
          <Kpi label="Thời gian trả lời TB" value={t.avgLatencyMs == null ? "—" : `${(t.avgLatencyMs / 1000).toFixed(1)} s`}/>
        </div>
        <p className="hint">{coverage(t)}. Lượt lỗi (HTTP 429, hết thời gian chờ) không có số token và không được tính là 0 ước lượng. Bộ mô phỏng không gọi model nên không xuất hiện ở đây.</p>
        <DailyBars daily={u.daily}/>
      </>}
    </Card>
    {u ? <>
      <Card title="Theo model"><UsageTable rows={u.byModel} keyLabel="Model"/></Card>
      <div className="grid2">
        <Card title="Theo người dùng (top 20)"><UsageTable rows={u.byUser} keyLabel="Người dùng"/></Card>
        <Card title="Theo workspace (top 20)"><UsageTable rows={u.byWorkspace} keyLabel="Workspace"/></Card>
      </div>
      <AiCallsLog models={u.byModel.map((m) => m.key)}/>
    </> : null}

    <ProvidersCard/>
    <PricingCard/>
    <Card title="Prompt gần đây"><table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Prompt</th><th>Model</th><th>Kết quả</th></tr></thead><tbody>{a.recent.map((p) => <tr key={p.id}><td>{ago(p.createdAt)}</td><td>{p.user ?? "—"}</td><td>{p.text}</td><td className="code">{p.model ?? p.provider}</td><td>{p.outcome ? <Pill value={p.outcome}/> : "—"}</td></tr>)}</tbody></table></Card>
  </>);
}

// ------------------------------------------------------------------ components
function ComponentsPage() {
  const [tab, setTab] = useState<"registry" | "blocks">("registry");
  return (<>
    <PageHead title="Component Registry" sub="Component đã duyệt (AI và trình chỉnh sửa chỉ dùng những component này) và khối do nhân viên đóng góp chờ duyệt."/>
    <div className="tabs" role="tablist">
      <button role="tab" aria-selected={tab === "registry"} className={tab === "registry" ? "active" : ""} onClick={() => setTab("registry")}>Registry</button>
      <button role="tab" aria-selected={tab === "blocks"} className={tab === "blocks" ? "active" : ""} onClick={() => setTab("blocks")}>Khối đóng góp</button>
    </div>
    {tab === "registry" ? <RegistryTable/> : <BlocksAdmin/>}
  </>);
}

function RegistryTable() {
  const { data, error, loading, reload } = useLoad(() => api.admin.components(), []);
  const [open, setOpen] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  return (<>
    <Card>
      <table className="table"><thead><tr><th>Component</th><th>Nhóm</th><th>Phiên bản</th><th>Trạng thái</th><th>Dùng trong</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.map((c) => (<Fragment key={c.id}>
          <tr><td><b>{c.name}</b><small className="code">{c.id}</small><small>{c.description}</small></td><td>{c.category}</td><td>{c.latestVersion}</td><td><Pill value={c.status === "ACTIVE" ? "ACTIVE" : c.status} label={c.status === "ACTIVE" ? "Đã duyệt" : c.status}/></td>
            <td>{num(c.usedInProjects)} ứng dụng<small>{num(c.sections)} mục</small></td><td><button className="btn sm" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>Schema</button></td></tr>
          {open === c.id ? <tr className="detailRow"><td colSpan={6}><pre>{c.propsSchema ? JSON.stringify(JSON.parse(c.propsSchema), null, 2) : "—"}</pre></td></tr> : null}
        </Fragment>))}</tbody></table>
    </Card>
    <Card title="Thêm component gốc mới"><ComingSoon title="Component có renderer mới">Thêm một loại component gốc mới cần viết renderer trong mã nguồn và được review như mọi thay đổi mã. Hệ thống không chạy HTML/JS do người dùng tải lên. Nhân viên đóng góp “khối” (cấu hình sẵn của component đã duyệt) ở tab bên cạnh.</ComingSoon></Card>
  </>);
}

const BLOCK_FILTERS: [string, string][] = [["REVIEW", "Chờ duyệt"], ["APPROVED", "Đã duyệt"], ["PRIVATE", "Riêng tư"], ["DEPRECATED", "Ngừng dùng"], ["ALL", "Tất cả"]];

function BlocksAdmin() {
  const [status, setStatus] = useState("REVIEW"); const [page, setPage] = useState(0); const [open, setOpen] = useState<string | null>(null);
  const { data, error, loading, reload } = useLoad(() => api.admin.blocks(status, page), [status, page]);
  return <Card>
    <div className="filters">{BLOCK_FILTERS.map(([k, l]) => <button key={k} className={`btn sm ${status === k ? "primary" : ""}`} aria-pressed={status === k}
      onClick={() => { setStatus(k); setPage(0); setOpen(null); }}>{l}{k !== "ALL" && data?.counts[k] ? ` (${num(data.counts[k])})` : ""}</button>)}</div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.page.items.length === 0
      ? <StateView kind="empty" title={status === "REVIEW" ? "Không có khối nào chờ duyệt" : "Không có khối"}/> : <>
      <table className="table"><thead><tr><th>Khối</th><th>Component gốc</th><th>Người đóng góp</th><th>Trạng thái</th><th>Phiên bản</th><th>Cập nhật</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.page.items.map((b) => <Fragment key={b.id}>
          <tr><td><b>{b.name}</b>{b.description ? <small>{b.description}</small> : null}</td><td className="code">{b.baseComponent}</td><td>{b.owner ?? "—"}</td>
            <td><BlockStatus status={b.status}/></td><td>v{b.latestVersion}{b.approvedVersion ? <small>đang dùng: v{b.approvedVersion}</small> : null}</td><td>{ago(b.updatedAt)}</td>
            <td><button className="btn sm" aria-expanded={open === b.id} onClick={() => setOpen(open === b.id ? null : b.id)}>{open === b.id ? "Đóng" : "Xem xét"}</button></td></tr>
          {open === b.id ? <tr className="detailRow"><td colSpan={7}><BlockReviewPanel id={b.id} onDone={() => { reload(); }}/></td></tr> : null}
        </Fragment>)}</tbody></table>
      <Pager page={page} size={data!.page.size} total={data!.page.total} onPage={setPage}/></>}
  </Card>;
}

function BlockReviewPanel({ id, onDone }: { id: string; onDone: () => void }) {
  const { data, error, loading, reload } = useLoad(() => api.admin.block(id), [id]);
  const [comment, setComment] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const b: BlockDto = data!;
  const latest = b.versions.find((v) => v.version === b.latestVersion);
  const page = blockPage(b);
  async function act(fn: () => Promise<unknown>) { setBusy(true); setErr(null); try { await fn(); setComment(""); reload(); onDone(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } finally { setBusy(false); } }
  return <div className="grid2">
    <div>{page ? <SchemaThumb schema={page} title={`Xem trước khối ${b.name}`} tall/> : null}
      <details><summary>Thuộc tính (JSON, v{latest?.version})</summary><pre>{JSON.stringify(latest?.props ?? {}, null, 2)}</pre></details></div>
    <div>
      <h2 className="subHead">Kiểm tra tự động</h2>{latest?.validation ? <CheckList checks={latest.validation}/> : <p className="muted">Chưa chạy (khối chưa được gửi).</p>}
      <h2 className="subHead">Lịch sử</h2><ReviewTimeline reviews={b.reviews}/>
      {b.status === "REVIEW" ? (b.canReview ? <div className="inlineForm">
        <label htmlFor={`c-${b.id}`}>Nhận xét (bắt buộc khi từ chối)</label>
        <textarea id={`c-${b.id}`} rows={2} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)}/>
        <div className="row"><button className="btn primary" disabled={busy} onClick={() => void act(() => api.admin.reviewBlock(b.id, "APPROVE", b.latestVersion, comment.trim() || undefined))}>Phê duyệt v{b.latestVersion}</button>
          <button className="btn danger" disabled={busy || !comment.trim()} onClick={() => void act(() => api.admin.reviewBlock(b.id, "REJECT", b.latestVersion, comment.trim()))}>Từ chối</button></div>
      </div> : <p className="notice">Bạn là người đóng góp khối này nên không thể tự duyệt; cần một quản trị viên khác.</p>) : null}
      <div className="row">
        {b.status !== "DEPRECATED" && b.approvedVersion != null ? <button className="btn sm" disabled={busy} onClick={() => { if (confirm("Ngừng dùng khối này? Khối biến khỏi thư viện; các trang đang dùng không bị thay đổi.")) void act(() => api.admin.deprecateBlock(b.id, comment.trim() || undefined)); }}>Ngừng dùng</button> : null}
        {b.status === "DEPRECATED" ? <button className="btn sm" disabled={busy} onClick={() => void act(() => api.admin.restoreBlock(b.id))}>Khôi phục</button> : null}
      </div>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </div>
  </div>;
}

// ------------------------------------------------------------------ templates
function TemplatesAdmin() {
  const [page, setPage] = useState(0); const [visibility, setVisibility] = useState(""); const [status, setStatus] = useState("ACTIVE"); const [q, setQ] = useState(""); const [applied, setApplied] = useState("");
  const [open, setOpen] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null);
  const params = useMemo(() => ({ page, visibility: visibility || undefined, status: status || undefined, q: applied || undefined }), [page, visibility, status, applied]);
  const { data, error, loading, reload } = useLoad(() => api.admin.templates(params), [params]);
  async function act(fn: () => Promise<TemplateDto>) { setErr(null); try { await fn(); reload(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  return (<>
    <PageHead title="Templates" sub="Mẫu là cấu trúc trang (Page Schema) do nhân viên lưu từ ứng dụng. Tác giả gửi duyệt → kiểm tra tự động (component, nội dung, render an toàn) → quản trị viên khác tác giả duyệt hoặc từ chối."/>
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(q.trim()); }}>
        <input aria-label="Tìm mẫu" placeholder="Tìm theo tên" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Phạm vi" value={visibility} onChange={(e) => { setPage(0); setVisibility(e.target.value); }}><option value="">Mọi phạm vi</option><option value="COMPANY">Công ty</option><option value="PRIVATE">Riêng tư</option></select>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}><option value="">Mọi trạng thái</option><option value="REVIEW">Chờ duyệt</option>
          <option value="ACTIVE">Đang dùng</option><option value="APPROVED">Đã duyệt</option><option value="PRIVATE">Riêng tư</option><option value="ARCHIVED">Đã lưu trữ</option></select>
        <button className="btn primary">Lọc</button>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty" title="Không có mẫu"/> : <>
        <table className="table"><thead><tr><th>Mẫu</th><th>Tác giả</th><th>Duyệt</th><th>Trạng thái</th><th>Mục · lượt dùng</th><th>Cập nhật</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{data!.items.map((t) => <Fragment key={t.id}>
            <tr><td><b>{t.name}</b><small>v{t.version}{t.description ? ` · ${t.description}` : ""}</small></td><td>{t.author ?? "—"}</td>
              <td><Pill value={{ PRIVATE: "PRIVATE", SUBMITTED: "QUEUED", REVIEW: "UNKNOWN", APPROVED: "COMPANY", ARCHIVED: "ARCHIVED" }[t.reviewStatus]}
                label={{ PRIVATE: "Riêng tư", SUBMITTED: "Đang kiểm tra", REVIEW: "Chờ duyệt", APPROVED: "Đã duyệt (công ty)", ARCHIVED: "Đã lưu trữ" }[t.reviewStatus]}/></td>
              <td>{t.status === "ACTIVE" ? <Pill value="ACTIVE" label="Đang dùng"/> : <Pill value="ARCHIVED" label="Đã lưu trữ"/>}</td><td>{t.sections} · {t.usageCount}</td><td>{ago(t.updatedAt)}</td>
              <td><div className="row">
                <button className="btn sm" aria-expanded={open === t.id} onClick={() => setOpen(open === t.id ? null : t.id)}>Xem</button>
                {t.canReview ? <><button className="btn sm primary" onClick={() => void act(() => api.admin.reviewTemplate(t.id, "APPROVE"))}>Duyệt</button>
                  <button className="btn sm" onClick={() => { const c = window.prompt("Lý do từ chối (gửi cho tác giả):")?.trim(); if (c) void act(() => api.admin.reviewTemplate(t.id, "REJECT", c)); }}>Từ chối</button></> : null}
                {t.status === "ACTIVE" && t.reviewStatus !== "REVIEW" ? (t.visibility === "PRIVATE"
                  ? <button className="btn sm primary" onClick={() => void act(() => api.admin.templateVisibility(t.id, "COMPANY"))}>Chia sẻ toàn công ty</button>
                  : <button className="btn sm" onClick={() => void act(() => api.admin.templateVisibility(t.id, "PRIVATE"))}>Thu hồi về riêng tư</button>) : null}
                {t.status === "ACTIVE" ? <button className="btn sm ghost" onClick={() => { if (confirm(`Lưu trữ mẫu “${t.name}”?`)) void act(() => api.admin.templateStatus(t.id, "ARCHIVED")); }}>Lưu trữ</button>
                  : <button className="btn sm" onClick={() => void act(() => api.admin.templateStatus(t.id, "ACTIVE"))}>Khôi phục</button>}
              </div></td></tr>
            {open === t.id ? <tr className="detailRow"><td colSpan={7}><div className="grid2">{t.previewStatus === "READY"
                ? <img className="previewImg" src={`/api/v1/templates/${t.id}/preview`} alt={`Ảnh xem trước mẫu ${t.name}`}/> : <SchemaThumb schema={t.schema} title={`Xem trước mẫu ${t.name}`} tall/>}
              <div><p>Component: {t.componentTypes.map((c) => <span key={c} className="tag code">{c}</span>)}</p>
                <p>Danh mục: {t.category}{t.tags.length ? ` · ${t.tags.map((x) => `#${x}`).join(" ")}` : ""} · ảnh xem trước: {t.previewStatus}
                  {" "}<button className="btn sm ghost" onClick={() => void act(async () => { await api.admin.templatePreview(t.id); return t; })}>Tạo lại ảnh</button></p>
                {t.reviewComment ? <p>Nhận xét duyệt: {t.reviewComment}{t.reviewedBy ? ` — ${t.reviewedBy}` : ""}</p> : null}{t.sourceProjectId ? <p><Link href={`/admin/applications/${t.sourceProjectId}`}>Ứng dụng nguồn</Link></p> : null}</div></div></td></tr> : null}
          </Fragment>)}</tbody></table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>}
    </Card>
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
const mib = (b?: number | null) => (b == null ? "—" : b < 1048576 ? `${(b / 1024).toFixed(0)} KiB` : `${(b / 1048576).toFixed(1)} MiB`);
const secs = (ms: number) => (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + " s";

function UsageRows({ rows, label }: { rows: { key: string; label: string | null; builds: number; succeeded: number; failed: number; cpuMs: number; durationMs: number; artifactBytes: number }[]; label: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có build"/>;
  return <table className="table"><thead><tr><th>{label}</th><th>Build</th><th>Thành công</th><th>Lỗi</th><th>CPU</th><th>Thời gian</th><th>Kết quả</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ?? <span className="code">{r.key.slice(0, 8)}</span>}</td><td>{num(r.builds)}</td><td>{num(r.succeeded)}</td><td>{num(r.failed)}</td>
      <td>{secs(r.cpuMs)}</td><td>{secs(r.durationMs)}</td><td>{mib(r.artifactBytes)}</td></tr>)}</tbody></table>;
}

/** Build quotas and storage: measured usage (runner cgroup CPU, wall clock, artifact bytes), refusals, retention preview, repository lifecycle. */
function BuildsPage() {
  const rep = useLoad(() => api.admin.builds(), []);
  const preview = useLoad(() => api.admin.retentionPreview(), []);
  const repos = useLoad(() => api.admin.repositories(), []);
  const [msg, setMsg] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  async function runCleanup() { setBusy(true); setErr(null); try { const r = await api.admin.retentionRun(); setMsg(`Đã dọn: ${r.retention?.artifactsDeleted ?? 0} artifact (${mib(r.retention?.artifactBytesFreed)}), ${r.retention?.previewsExpired ?? 0} bản xem trước hết hạn.`); preview.reload(); rep.reload(); repos.reload(); } catch (x) { setErr(errText(x, "Không dọn được.")); } finally { setBusy(false); } }
  async function hardDelete(r: RepoRow) { if (!confirm(`Xoá vĩnh viễn kho mã “${r.name}”? Không thể hoàn tác.`)) return; setErr(null); try { await api.admin.deleteRepository(r.projectId); repos.reload(); } catch (x) { setErr(errText(x, "Không xoá được.")); } }
  const d = rep.data; const p = preview.data;
  return (<>
    <PageHead title="Build & lưu trữ" sub="Số liệu đo thật từ runner (CPU của container, thời gian, kích thước kết quả). Giới hạn chỉnh trong Cài đặt → Build / Lưu trữ / Lưu giữ."/>
    {rep.error ? <ErrorState error={rep.error} retry={rep.reload}/> : !d ? <StateView kind="loading"/> : <>
      <div className="kpiGrid">
        <Kpi label="Build 30 ngày" value={num(d.totals.builds)} hint={`${num(d.totals.succeeded)} thành công · ${num(d.totals.failed)} lỗi`}/>
        <Kpi label="CPU đã dùng" value={secs(d.totals.cpuMs)} hint={`thời gian chạy ${secs(d.totals.durationMs)}`}/>
        <Kpi label="Đang chạy / chờ" value={`${num(d.running)} / ${num(d.queued)}`}/>
        <Kpi label="Lưu trữ" value={mib(d.storage.artifactsBytes)} hint={`${num(d.storage.artifactsCount)} artifact · repo ${mib(d.storage.repositoriesBytes)} · tệp ${mib(d.storage.assetsBytes)}`}/>
      </div>
      <div className="grid2"><Card title="Theo workspace"><UsageRows rows={d.byWorkspace} label="Workspace"/></Card><Card title="Theo người dùng"><UsageRows rows={d.byUser} label="Người dùng"/></Card></div>
      <Card title="Theo ứng dụng"><UsageRows rows={d.byProject} label="Ứng dụng"/></Card>
      <Card title="Build bị từ chối">{d.rejections.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Người</th><th>Ứng dụng</th><th>Lý do</th><th>Chi tiết</th></tr></thead>
        <tbody>{d.rejections.map((r, i) => <tr key={i}><td>{ago(r.createdAt)}</td><td>{r.user ?? "—"}</td><td>{r.project ?? "—"}</td><td className="code">{r.reason}</td><td>{r.detail}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Không có build nào bị từ chối"/>}</Card>
    </>}
    <Card title="Dọn dẹp (lưu giữ)" actions={<button className="btn sm primary" disabled={busy} onClick={() => void runCleanup()}>{busy ? "Đang dọn…" : "Chạy dọn dẹp ngay"}</button>}>
      <p className="hint">Luôn giữ: bản đang phục vụ, N bản xuất bản gần nhất để quay lại, bản xem trước còn hạn, build đang chạy. Job tự chạy mỗi giờ; mỗi lần xoá đều ghi audit.</p>
      {p ? <ul className="plainList"><li>Sẽ xoá {num(p.retention?.artifactsDeleted ?? 0)} artifact ({mib(p.retention?.artifactBytesFreed)})</li><li>{num(p.retention?.previewsExpired ?? 0)} bản xem trước đã hết hạn</li>
        <li>{num(p.retention?.failedBuildLogsCleared ?? 0)} log build lỗi cũ</li><li>{num(p.retention?.repositoriesPendingDelete ?? 0)} kho mã hết hạn lưu trữ</li></ul> : <StateView kind="loading"/>}
      {msg ? <p className="hint" role="status">{msg}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
    <Card title="Kho mã nguồn">{!repos.data ? <StateView kind="loading"/> : repos.data.length === 0 ? <StateView kind="empty" title="Chưa có kho mã"/> :
      <table className="table"><thead><tr><th>Kho</th><th>Ứng dụng</th><th>Trạng thái</th><th>Kích thước</th><th>Lưu trữ đến</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{repos.data.map((r) => <tr key={r.projectId}><td className="code">{r.name}</td><td>{r.project ?? "—"}</td>
          <td><Pill value={r.state === "ACTIVE" ? "ACTIVE" : r.state === "DELETED" ? "DISABLED" : "UNKNOWN"} label={{ ACTIVE: "Đang dùng", ARCHIVED: "Đã lưu trữ", PENDING_DELETE: "Chờ xoá", DELETED: "Đã xoá" }[r.state]}/></td>
          <td>{mib(r.sizeBytes)}</td><td>{r.deleteAfter ? fmtDate(r.deleteAfter) : "—"}</td>
          <td>{r.state === "PENDING_DELETE" ? <button className="btn sm danger" onClick={() => void hardDelete(r)}>Xoá vĩnh viễn</button> : null}</td></tr>)}</tbody></table>}</Card>
  </>);
}

const PKG_STATUS: Record<string, [string, string]> = { PENDING: ["UNKNOWN", "Chờ duyệt"], RESOLVING: ["QUEUED", "Đang kiểm tra"], ALLOWED: ["ACTIVE", "Cho phép"], DENIED: ["DISABLED", "Từ chối"] };
/** Approved npm package catalog (ADR 0013): approve → closure resolved in the sandbox + OSV scan; HIGH/CRITICAL denied unless the risk is accepted. */
function PackagesPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.packages(), []);
  const [f, setF] = useState({ name: "", range: "", pin: "", note: "" }); const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { if (!data?.some((p) => p.status === "RESOLVING")) return; const t = setInterval(reload, 3000); return () => clearInterval(t); }, [data, reload]);
  async function approve(name: string, range?: string, pin?: string, note?: string) {
    setErr(null); setMsg(null);
    try { await api.admin.approvePackage({ name, versionRange: range || undefined, pinnedVersion: pin || undefined, note: note || undefined }); setMsg(`Đang kiểm tra ${name} (giải phụ thuộc trong sandbox + quét OSV).`); setF({ name: "", range: "", pin: "", note: "" }); reload(); }
    catch (x) { setErr(errText(x, "Không gửi được.")); }
  }
  async function decide(p: PackageView, status: "ALLOWED" | "DENIED") {
    setErr(null);
    const risky = (p.findings ?? []).some((x) => x.severity === "HIGH" || x.severity === "CRITICAL");
    let accept = false, note: string | undefined;
    if (status === "ALLOWED" && risky) { note = window.prompt("Package có lỗ hổng HIGH/CRITICAL. Ghi lý do chấp nhận rủi ro (bắt buộc):") ?? ""; if (!note.trim()) return; accept = true; }
    try { await api.admin.decidePackage(p.name, status, accept, note); reload(); } catch (x) { setErr(errText(x, "Không đổi được.")); }
  }
  return (<>
    <PageHead title="Packages" sub="Chỉ package trong danh mục mới vào được mirror và lockfile của ứng dụng mã nguồn. Duyệt = giải cây phụ thuộc (không chạy mã package) + quét lỗ hổng OSV."/>
    <Card title="Duyệt package">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (f.name.trim()) void approve(f.name.trim(), f.range.trim(), f.pin.trim(), f.note.trim()); }}>
        <input aria-label="Tên package" placeholder="Tên (ví dụ date-fns)" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })}/>
        <input aria-label="Khoảng phiên bản" placeholder="Khoảng phiên bản (^3.0.0)" value={f.range} onChange={(e) => setF({ ...f, range: e.target.value })}/>
        <input aria-label="Ghim phiên bản" placeholder="Hoặc ghim (3.6.0)" value={f.pin} onChange={(e) => setF({ ...f, pin: e.target.value })}/>
        <input aria-label="Ghi chú" placeholder="Ghi chú" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
        <button className="btn primary" disabled={!f.name.trim()}>Kiểm tra & duyệt</button>
      </form>
      {msg ? <p className="hint" role="status">{msg}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
    <Card title="Danh mục">{error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.length ? <StateView kind="empty" title="Chưa có package nào ngoài khung mẫu"/> :
      <table className="table"><thead><tr><th>Package</th><th>Phiên bản</th><th>Trạng thái</th><th>Phụ thuộc</th><th>Lỗ hổng</th><th>Người yêu cầu / quyết định</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.map((p) => { const sev = (p.findings ?? []).reduce<Record<string, number>>((a, x) => { a[x.severity] = (a[x.severity] ?? 0) + 1; return a; }, {});
          return <tr key={p.name}><td className="code">{p.name}{p.note ? <small>{p.note}</small> : null}</td><td>{p.pinnedVersion ?? p.versionRange}</td>
            <td><Pill value={PKG_STATUS[p.status][0]} label={PKG_STATUS[p.status][1]}/>{p.riskAccepted ? <small>rủi ro đã chấp nhận</small> : null}</td>
            <td>{p.dependencies ?? "—"}</td><td>{p.findings == null ? "—" : Object.keys(sev).length ? Object.entries(sev).map(([k, v]) => `${v} ${k}`).join(", ") : "0"}</td>
            <td>{p.requestedBy ?? "—"}<small>{p.decidedBy ? `${p.decidedBy} · ${p.decidedAt ? ago(p.decidedAt) : ""}` : ""}</small></td>
            <td><div className="row">{p.status === "PENDING" ? <button className="btn sm primary" onClick={() => void approve(p.name)}>Kiểm tra & duyệt</button> : null}
              {p.status === "DENIED" && p.dependencies != null ? <button className="btn sm" onClick={() => void decide(p, "ALLOWED")}>Cho phép</button> : null}
              {p.status === "ALLOWED" ? <button className="btn sm ghost" onClick={() => void decide(p, "DENIED")}>Từ chối</button> : null}</div></td></tr>; })}</tbody></table>}</Card>
  </>);
}

/** Editable policies (audited; high-risk ones ask for confirmation) above the read-only effective configuration. */
function SettingsPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.settings(), []);
  const pol = useLoad(() => api.admin.policies(), []);
  const [draft, setDraft] = useState<Record<string, string>>({}); const [err, setErr] = useState<string | null>(null); const [ok, setOk] = useState<string | null>(null);
  async function save(s: SettingView, value: string) {
    setErr(null); setOk(null);
    if (s.risk === "HIGH" && !confirm(`“${s.label}” là cài đặt rủi ro cao. Đổi thành ${value}?`)) return;
    try { await api.admin.setPolicy(s.key, value, s.risk === "HIGH"); setOk(`Đã lưu: ${s.label}`); setDraft((d) => { const n = { ...d }; delete n[s.key]; return n; }); pol.reload(); }
    catch (x) { setErr(errText(x, "Không lưu được.")); }
  }
  async function reset(s: SettingView) { setErr(null); try { await api.admin.resetPolicy(s.key); pol.reload(); } catch (x) { setErr(errText(x, "Không đặt lại được.")); } }
  const groups = (pol.data ?? []).reduce<Record<string, SettingView[]>>((acc, s) => { (acc[s.group] ??= []).push(s); return acc; }, {});
  return (<>
    <PageHead title="Cài đặt" sub="Chính sách chỉnh được (ghi audit; mục rủi ro cao cần xác nhận). Giá trị mặc định lấy từ cấu hình máy chủ."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{ok ? <p className="hint" role="status">{ok}</p> : null}
    {pol.error ? <ErrorState error={pol.error} retry={pol.reload}/> : !pol.data ? <StateView kind="loading"/> : <div className="grid2">{Object.entries(groups).map(([g, items]) =>
      <Card key={g} title={g}><table className="table settingsTable"><tbody>{items.map((s) => {
        const v = draft[s.key] ?? s.value;
        return <tr key={s.key}><td><b>{s.label}</b>{s.risk === "HIGH" ? <Pill value="UNKNOWN" label="Rủi ro cao"/> : null}<small className="code">{s.key}</small>
          <small>{s.overridden ? `Đã đổi bởi ${s.updatedBy ?? "—"} ${s.updatedAt ? ago(s.updatedAt) : ""} · mặc định ${s.defaultValue}` : "Mặc định từ cấu hình"}</small></td>
          <td className="settingCtl">{s.type === "BOOL"
            ? <label className="switch"><input type="checkbox" checked={s.value === "true"} aria-label={s.label} onChange={(e) => void save(s, String(e.target.checked))}/> {s.value === "true" ? "Bật" : "Tắt"}</label>
            : <form className="row" onSubmit={(e) => { e.preventDefault(); void save(s, v); }}>{s.type === "DOMAINS" ? <input aria-label={s.label} type="text" placeholder="example.com, docs.example.org" value={v} onChange={(e) => setDraft((d) => ({ ...d, [s.key]: e.target.value }))}/>
              : <input aria-label={s.label} type="number" min={s.min} max={s.max} value={v} onChange={(e) => setDraft((d) => ({ ...d, [s.key]: e.target.value }))}/>}
              <span className="hint">{s.unit}</span>{draft[s.key] !== undefined && draft[s.key] !== s.value ? <button className="btn sm primary">Lưu</button> : null}</form>}
            {s.overridden ? <button className="btn sm ghost" onClick={() => void reset(s)}>Mặc định</button> : null}</td></tr>;
      })}</tbody></table></Card>)}</div>}
    <h2 className="subHead">Cấu hình đang hiệu lực (chỉ xem)</h2>
    {loading && !data ? <StateView kind="loading"/> : error ? <ErrorState error={error} retry={reload}/> :
      <div className="grid2">{Object.entries(data!).map(([group, values]) => (
        <Card key={group} title={SETTING_GROUP[group] ?? group}><dl className="kv">{Object.entries(values).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v === null || v === undefined ? "—" : String(v)}</dd></div>)}</dl></Card>
      ))}</div>}
  </>);
}

// ---------------------------------------------------------------- Stage E: AI governance

const SCOPE_LABEL: Record<string, string> = { ORG: "Toàn tổ chức", WORKSPACE: "Workspace", ROLE: "Vai trò trong workspace", USER: "Người dùng", PROJECT: "Ứng dụng" };

/** Picks a scope id: workspaces/users/applications are searched through the existing admin lists. */
function ScopePicker({ types, value, onChange }: { types: string[]; value: { type: string; id: string }; onChange: (v: { type: string; id: string }) => void }) {
  const [q, setQ] = useState(""); const [opts, setOpts] = useState<{ id: string; label: string }[]>([]); const [ws, setWs] = useState(""); const [role, setRole] = useState("EDITOR");
  useEffect(() => {
    const t = setTimeout(() => {
      const kind = value.type === "ROLE" ? "WORKSPACE" : value.type;
      const load = kind === "WORKSPACE" ? api.admin.workspaces(0, q).then((p) => p.items.map((w) => ({ id: w.id, label: w.name })))
        : kind === "USER" ? api.admin.users(0, q).then((p) => p.items.map((u) => ({ id: u.id, label: `${u.displayName ?? u.username} (${u.username})` })))
        : kind === "PROJECT" ? api.admin.applications({ page: 0, q }).then((p) => p.items.map((a) => ({ id: a.id, label: `${a.name} · ${a.workspaceName}` })))
        : Promise.resolve([]);
      load.then(setOpts).catch(() => setOpts([]));
    }, 250);
    return () => clearTimeout(t);
  }, [value.type, q]);
  useEffect(() => { if (value.type === "ROLE" && ws) onChange({ type: "ROLE", id: `${ws}:${role}` }); }, [ws, role]); // eslint-disable-line react-hooks/exhaustive-deps
  return <>
    <select aria-label="Phạm vi" value={value.type} onChange={(e) => { setQ(""); setWs(""); onChange({ type: e.target.value, id: "" }); }}>
      {types.map((t) => <option key={t} value={t}>{SCOPE_LABEL[t]}</option>)}</select>
    {value.type !== "ORG" ? <>
      <input aria-label="Tìm phạm vi" placeholder="Tìm…" value={q} onChange={(e) => setQ(e.target.value)}/>
      <select aria-label="Chọn phạm vi" value={value.type === "ROLE" ? ws : value.id} onChange={(e) => value.type === "ROLE" ? setWs(e.target.value) : onChange({ type: value.type, id: e.target.value })}>
        <option value="">— chọn —</option>{opts.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select>
      {value.type === "ROLE" ? <select aria-label="Vai trò" value={role} onChange={(e) => setRole(e.target.value)}>
        {["WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER"].map((r) => <option key={r} value={r}>{r}</option>)}</select> : null}
    </> : null}
  </>;
}

/** Model access (deny rules below the global enable flag; most restrictive wins) and money budgets. */
function AiGovernancePage() {
  const rules = useLoad(() => api.admin.accessRules(), []);
  const budgets = useLoad(() => api.admin.budgets(), []);
  const [rule, setRule] = useState({ type: "ORG", id: "" }); const [model, setModel] = useState("paid:*");
  const [b, setB] = useState({ type: "ORG", id: "" }); const [bf, setBf] = useState({ period: "MONTHLY", amount: "", currency: "USD", rate: "", soft: "80", hard: true });
  const [check, setCheck] = useState({ type: "USER", id: "" }); const [checkWs, setCheckWs] = useState({ type: "WORKSPACE", id: "" }); const [eff, setEff] = useState<EffectiveModel[] | null>(null);
  const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  async function addRule(e: FormEvent) {
    e.preventDefault(); setErr(null); setMsg(null);
    try { await api.admin.addAccessRule({ scopeType: rule.type, scopeId: rule.type === "ORG" ? undefined : rule.id, modelId: model.trim() }); setMsg("Đã thêm quy tắc chặn."); rules.reload(); }
    catch (x) { setErr(errText(x, "Không thêm được.")); }
  }
  async function delRule(r: AccessRule) { setErr(null); try { await api.admin.deleteAccessRule(r.id); rules.reload(); } catch (x) { setErr(errText(x, "Không xoá được.")); } }
  async function saveBudget(e: FormEvent) {
    e.preventDefault(); setErr(null); setMsg(null);
    try {
      await api.admin.setBudget({ scopeType: b.type, scopeId: b.type === "ORG" ? undefined : b.id, period: bf.period, amount: Number(bf.amount), currency: bf.currency.toUpperCase(),
        usdPerUnit: bf.currency.toUpperCase() === "USD" ? undefined : Number(bf.rate), softPercent: Number(bf.soft), hard: bf.hard });
      setMsg("Đã lưu ngân sách."); budgets.reload();
    } catch (x) { setErr(errText(x, "Không lưu được.")); }
  }
  async function delBudget(x: AiBudget) { if (!confirm("Xoá ngân sách này?")) return; try { await api.admin.deleteBudget(x.id); budgets.reload(); } catch (e) { setErr(errText(e, "Không xoá được.")); } }
  async function runCheck(e: FormEvent) { e.preventDefault(); setErr(null); try { setEff(await api.admin.effectiveModels(check.id, checkWs.id || undefined)); } catch (x) { setErr(errText(x, "Không kiểm tra được.")); } }
  const money = (v: number, c: string) => `${num(Math.round(v * 10000) / 10000)} ${c}`;
  return (<>
    <PageHead title="Quản trị AI" sub="Quyền dùng model theo tổ chức → workspace → vai trò → người dùng (chặn ở bất kỳ mức nào là chặn), và ngân sách tiền trên chi phí đã biết."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Quyền dùng model (Model Access)">
      <p className="hint">Model phải được bật ở AI Control trước. Quy tắc ở đây chỉ CHẶN thêm. Mã model: chính xác (ví dụ <code>openai:gpt-…</code>), <code>paid:*</code> (mọi model trả phí) hoặc <code>*</code> (mọi model thật; bộ mô phỏng luôn dùng được).</p>
      <form className="filters wrap" onSubmit={(e) => void addRule(e)}>
        <ScopePicker types={["ORG", "WORKSPACE", "ROLE", "USER"]} value={rule} onChange={setRule}/>
        <input aria-label="Model bị chặn" value={model} onChange={(e) => setModel(e.target.value)} placeholder="paid:* | * | provider:model"/>
        <button className="btn primary" disabled={!model.trim() || (rule.type !== "ORG" && !rule.id)}>Thêm quy tắc chặn</button>
      </form>
      {rules.error ? <ErrorState error={rules.error} retry={rules.reload}/> : !rules.data ? <StateView kind="loading"/> : !rules.data.length ? <StateView kind="empty" title="Chưa có quy tắc chặn nào"/> :
        <table className="table"><thead><tr><th>Phạm vi</th><th>Đối tượng</th><th>Model bị chặn</th><th>Tạo</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{rules.data.map((r) => <tr key={r.id}><td>{SCOPE_LABEL[r.scopeType]}</td><td>{r.scopeLabel ?? r.scopeId}</td><td className="code">{r.modelId}</td><td>{ago(r.createdAt)}</td>
            <td><button className="btn sm ghost" onClick={() => void delRule(r)}>Bỏ chặn</button></td></tr>)}</tbody></table>}
      <h3 className="subHead">Kiểm tra quyền hiệu lực</h3>
      <form className="filters wrap" onSubmit={(e) => void runCheck(e)}>
        <ScopePicker types={["USER"]} value={check} onChange={setCheck}/><ScopePicker types={["WORKSPACE"]} value={checkWs} onChange={setCheckWs}/>
        <button className="btn" disabled={!check.id}>Kiểm tra</button>
      </form>
      {eff ? eff.length === 0 ? <StateView kind="empty" title="Chưa có model nào được cấu hình"/> :
        <table className="table"><thead><tr><th>Model</th><th>Nhà cung cấp</th><th>Trả phí</th><th>Kết quả</th></tr></thead>
          <tbody>{eff.map((m) => <tr key={m.id}><td className="code">{m.id}</td><td>{m.provider}</td><td>{m.paid ? "Có" : "Không"}</td>
            <td><Pill value={m.allowed ? "ACTIVE" : "DISABLED"} label={m.reason}/></td></tr>)}</tbody></table> : null}
    </Card>
    <Card title="Ngân sách AI (tiền)">
      <p className="hint">Chỉ tính chi phí đã biết: chi phí nhà cung cấp báo, hoặc token × bảng giá ở AI Control. Cuộc gọi không rõ chi phí được đếm riêng, không ước đoán.
        Ngân sách cứng chặn model TRẢ PHÍ khi đã tiêu đủ (model miễn phí vẫn dùng được); model trả phí chưa có giá sẽ bị từ chối khi có ngân sách cứng. Tiền tệ khác USD cần tỷ giá do bạn nhập.</p>
      <form className="filters wrap" onSubmit={(e) => void saveBudget(e)}>
        <ScopePicker types={["ORG", "WORKSPACE", "USER", "PROJECT"]} value={b} onChange={setB}/>
        <select aria-label="Chu kỳ" value={bf.period} onChange={(e) => setBf({ ...bf, period: e.target.value })}><option value="DAILY">Ngày</option><option value="MONTHLY">Tháng</option></select>
        <input aria-label="Số tiền" type="number" min="0" step="any" placeholder="Số tiền" value={bf.amount} onChange={(e) => setBf({ ...bf, amount: e.target.value })}/>
        <input aria-label="Tiền tệ" maxLength={3} value={bf.currency} onChange={(e) => setBf({ ...bf, currency: e.target.value })} style={{ width: 70 }}/>
        {bf.currency.toUpperCase() !== "USD" ? <input aria-label="USD cho 1 đơn vị" type="number" step="any" placeholder={`USD / 1 ${bf.currency.toUpperCase()}`} value={bf.rate} onChange={(e) => setBf({ ...bf, rate: e.target.value })}/> : null}
        <label className="row">Cảnh báo ở <input aria-label="Ngưỡng cảnh báo %" type="number" min="1" max="100" value={bf.soft} onChange={(e) => setBf({ ...bf, soft: e.target.value })} style={{ width: 64 }}/>%</label>
        <label className="switch"><input type="checkbox" checked={bf.hard} onChange={(e) => setBf({ ...bf, hard: e.target.checked })}/> Chặn khi vượt</label>
        <button className="btn primary" disabled={!bf.amount || (b.type !== "ORG" && !b.id)}>Lưu ngân sách</button>
      </form>
      {budgets.error ? <ErrorState error={budgets.error} retry={budgets.reload}/> : !budgets.data ? <StateView kind="loading"/> : !budgets.data.length ? <StateView kind="empty" title="Chưa có ngân sách nào"/> :
        <table className="table"><thead><tr><th>Phạm vi</th><th>Chu kỳ</th><th>Ngân sách</th><th>Đã dùng</th><th>Mức dùng</th><th>Không rõ chi phí</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{budgets.data.map((x) => <tr key={x.id}><td>{SCOPE_LABEL[x.scopeType]}<small>{x.scopeLabel ?? x.scopeId}</small></td><td>{x.period === "DAILY" ? "Ngày" : "Tháng"}</td>
            <td>{money(x.amount, x.currency)}<small>{x.hard ? "chặn khi vượt" : "chỉ cảnh báo"} · cảnh báo {x.softPercent}%</small></td>
            <td>{money(x.spent, x.currency)}{x.currency !== "USD" ? <small>{usd(x.spentUsd)}</small> : null}</td>
            <td><div className={`budgetBar ${x.percent >= 100 ? "over" : x.percent >= x.softPercent ? "warn" : ""}`} role="img" aria-label={`${x.percent}%`}><i style={{ width: `${Math.min(100, x.percent)}%` }}/></div><small>{x.percent}%</small></td>
            <td>{num(x.unknownCostCalls)}</td><td><button className="btn sm ghost" onClick={() => void delBudget(x)}>Xoá</button></td></tr>)}</tbody></table>}
    </Card>
  </>);
}

const ALERT_KIND: Record<string, string> = { AI_BUDGET_SOFT: "Ngân sách AI sắp hết", AI_BUDGET_EXCEEDED: "Vượt ngân sách AI", AI_PROVIDER_FAILURE: "Nhà cung cấp AI từ chối" };
/** Admin alerts: budget thresholds, provider failures. One open alert per condition and period. */
function AlertsPage() {
  const [all, setAll] = useState(false);
  const { data, error, reload } = useLoad(() => api.admin.alerts(all), [all]);
  const [err, setErr] = useState<string | null>(null);
  async function ack(a: AdminAlert) { setErr(null); try { await api.admin.ackAlert(a.id); reload(); } catch (x) { setErr(errText(x, "Không xác nhận được.")); } }
  return (<>
    <PageHead title="Cảnh báo" sub="Sinh tự động khi ngân sách chạm ngưỡng hoặc nhà cung cấp AI từ chối khoá / hết tín dụng." actions={<label className="switch"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)}/> Hiện cả đã xử lý</label>}/>
    {err ? <p className="formError" role="alert">{err}</p> : null}
    <Card title={data ? `${num(data.open)} cảnh báo đang mở` : "Cảnh báo"}>{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : !data.items.length ? <StateView kind="empty" title="Không có cảnh báo"/> :
      <table className="table"><thead><tr><th>Mức</th><th>Loại</th><th>Nội dung</th><th>Thời gian</th><th>Xử lý</th></tr></thead>
        <tbody>{data.items.map((a) => <tr key={a.id} className={`alertRow ${a.severity}`}><td><Pill value={a.severity === "CRITICAL" ? "DISABLED" : a.severity === "WARNING" ? "UNKNOWN" : "ACTIVE"} label={a.severity}/></td>
          <td>{ALERT_KIND[a.kind] ?? a.kind}</td><td>{a.message}</td><td>{ago(a.createdAt)}</td>
          <td>{a.acknowledgedAt ? <small>{a.acknowledgedBy ?? "—"} · {ago(a.acknowledgedAt)}</small> : <button className="btn sm" onClick={() => void ack(a)}>Đã xử lý</button>}</td></tr>)}</tbody></table>}</Card>
  </>);
}

// ---------------------------------------------------------------- Stage H: security findings, hosting cost, departments

const SEV_TONE: Record<string, string> = { CRITICAL: "DISABLED", HIGH: "DISABLED", MEDIUM: "UNKNOWN", LOW: "QUEUED", INFO: "ACTIVE" };
const SOURCE_LABEL: Record<string, string> = { DEPENDENCY: "Phụ thuộc (OSV)", SECRET: "Quét bí mật", PACKAGE: "Danh mục package", CONFIG: "Cấu hình" };
function SecurityPage() {
  const { data, error, reload } = useLoad(() => api.admin.securityFindings(), []);
  const [sev, setSev] = useState("");
  const rows: SecurityFinding[] = (data?.findings ?? []).filter((f) => !sev || f.severity === sev);
  return (<>
    <PageHead title="Phát hiện bảo mật" sub="Từ dữ liệu thật: quét phụ thuộc và bí mật của bản build gần nhất mỗi ứng dụng mã nguồn, rủi ro package đã chấp nhận, cấu hình nguy cơ. Không có điểm bảo mật tổng hợp."/>
    {error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : <>
      <div className="kpiGrid">{["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((k) => <button key={k} className={`kpi kpiButton${sev === k ? " active" : ""}`} onClick={() => setSev(sev === k ? "" : k)} aria-pressed={sev === k}>
        <div className="kpiLabel">{k}</div><div className="kpiValue">{num(data.counts[k] ?? 0)}</div></button>)}</div>
      <Card title={sev ? `Mức ${sev}` : "Tất cả phát hiện"}>{rows.length === 0 ? <StateView kind="empty" title="Không có phát hiện"/> :
        <table className="table"><thead><tr><th>Mức</th><th>Nguồn</th><th>Phát hiện</th><th>Đối tượng</th><th>Thời điểm</th></tr></thead>
          <tbody>{rows.map((f, i) => <tr key={i}><td><Pill value={SEV_TONE[f.severity] ?? "UNKNOWN"} label={f.severity}/></td><td>{SOURCE_LABEL[f.source] ?? f.source}</td>
            <td><b>{f.title}</b><small>{f.detail}</small></td>
            <td>{f.resourceType === "PROJECT" && f.resourceId ? <Link href={`/admin/applications/${f.resourceId}`}>{f.resourceName ?? f.resourceId}</Link> : <span className="code">{f.resourceName ?? f.resourceId ?? "—"}</span>}</td>
            <td>{f.detectedAt ? ago(f.detectedAt) : "hiện tại"}</td></tr>)}</tbody></table>}</Card>
      <p className="hint">{data.note}</p></>}
  </>);
}

const COST_ITEM: Record<string, string> = { STORAGE_GIB_MONTH: "Lưu trữ (GiB · tháng)", BUILD_CPU_HOUR: "CPU build (giờ)", BUILD_MINUTE: "Thời gian build (phút)", EGRESS_GIB: "Băng thông ra (GiB, chưa đo)" };
const gib = (b: number) => `${(b / 1073741824).toFixed(b >= 1073741824 ? 1 : 3)} GiB`;
const money = (v: number | null) => (v == null ? "chưa có giá" : usd(v));
function CostTable({ rows, first }: { rows: CostLine[]; first: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có số liệu"/>;
  return <table className="table"><thead><tr><th>{first}</th><th>Lưu trữ</th><th>CPU build</th><th>Thời gian build</th><th>AI</th><th>Tổng đã biết</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ?? r.key}{r.complete ? null : <small>chưa đủ giá → tổng chưa đầy đủ</small>}</td>
      <td>{gib(r.storageBytes)}<small>{money(r.storageUsd)}</small></td><td>{(r.buildCpuMs / 3600000).toFixed(2)} h<small>{money(r.cpuUsd)}</small></td>
      <td>{(r.buildMs / 60000).toFixed(1)} phút<small>{money(r.buildUsd)}</small></td><td>{usd(r.aiUsd)}{r.aiUnknownCalls ? <small>{r.aiUnknownCalls} lượt chưa rõ chi phí</small> : null}</td>
      <td><b>{usd(r.totalKnownUsd)}</b></td></tr>)}</tbody></table>;
}
function CostsPage() {
  const [days, setDays] = useState(30);
  const { data, error, reload } = useLoad(() => api.admin.costs(days), [days]);
  const [f, setF] = useState({ item: "STORAGE_GIB_MONTH", price: "", currency: "USD", rate: "", note: "" }); const [err, setErr] = useState<string | null>(null);
  async function add(e: FormEvent) {
    e.preventDefault(); setErr(null);
    try { await api.admin.addCostPrice({ item: f.item, unitPrice: Number(f.price), currency: f.currency.toUpperCase(), usdPerUnit: f.currency.toUpperCase() === "USD" ? undefined : Number(f.rate), note: f.note || undefined }); setF({ ...f, price: "", note: "" }); reload(); }
    catch (x) { setErr(errText(x, "Không lưu được.")); }
  }
  return (<>
    <PageHead title="Chi phí hosting" sub="Số đo thật × đơn giá do quản trị viên nhập. Thiếu đơn giá thì hiện “chưa có giá”, không ước đoán. Băng thông ra chưa được đo."
      actions={<select aria-label="Khoảng thời gian" value={days} onChange={(e) => setDays(Number(e.target.value))}><option value={7}>7 ngày</option><option value={30}>30 ngày</option><option value={90}>90 ngày</option></select>}/>
    <Card title="Đơn giá">
      <form className="filters wrap" onSubmit={(e) => void add(e)}>
        <select aria-label="Hạng mục" value={f.item} onChange={(e) => setF({ ...f, item: e.target.value })}>{Object.entries(COST_ITEM).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        <input aria-label="Đơn giá" type="number" min="0" step="any" placeholder="Đơn giá" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })}/>
        <input aria-label="Tiền tệ" maxLength={3} value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })} style={{ width: 70 }}/>
        {f.currency.toUpperCase() !== "USD" ? <input aria-label="USD cho 1 đơn vị tiền" type="number" step="any" placeholder="USD / 1 đơn vị" value={f.rate} onChange={(e) => setF({ ...f, rate: e.target.value })}/> : null}
        <input aria-label="Ghi chú" placeholder="Nguồn giá (hợp đồng, bảng giá…)" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
        <button className="btn primary" disabled={!f.price}>Thêm đơn giá</button>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      {data ? <ul className="plainList">{data.prices.map((p) => <li key={p.id}>{COST_ITEM[p.item] ?? p.item}: {p.unitPrice} {p.currency}{p.currency !== "USD" ? ` (×${p.usdPerUnit} USD)` : ""} · từ {fmtDate(p.effectiveFrom)}{p.note ? ` · ${p.note}` : ""}</li>)}
        {data.missingPrices.map((m) => <li key={m} className="hint">{COST_ITEM[m]}: chưa có đơn giá</li>)}</ul> : null}
    </Card>
    {error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : <>
      <div className="kpiGrid"><Kpi label={`Tổng đã biết (${data.days} ngày)`} value={usd(data.total.totalKnownUsd)} hint={data.total.complete ? "đủ đơn giá" : "chưa đầy đủ: thiếu đơn giá hoặc AI chưa rõ chi phí"}/>
        <Kpi label="Lưu trữ hiện tại" value={gib(data.total.storageBytes)}/><Kpi label="CPU build" value={`${(data.total.buildCpuMs / 3600000).toFixed(2)} h`}/><Kpi label="AI" value={usd(data.total.aiUsd)}/></div>
      <Card title="Theo phòng ban"><CostTable rows={data.byDepartment} first="Phòng ban"/></Card>
      <Card title="Theo workspace"><CostTable rows={data.byWorkspace} first="Workspace"/></Card>
      <Card title="Theo ứng dụng"><CostTable rows={data.byApplication} first="Ứng dụng"/></Card>
      <p className="hint">{data.egress}</p></>}
  </>);
}

function DepartmentsPage() {
  const { data, error, reload } = useLoad(() => api.admin.departments(), []);
  const [name, setName] = useState(""); const [parent, setParent] = useState(""); const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  const [who, setWho] = useState({ type: "USER", id: "" }); const [target, setTarget] = useState("");
  async function act(fn: () => Promise<unknown>, ok?: string) { setErr(null); setMsg(null); try { await fn(); if (ok) setMsg(ok); reload(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  const deps = (data ?? []).filter((d) => d.kind === "DEPARTMENT");
  const teamsOf = (id: string) => (data ?? []).filter((d) => d.parentId === id);
  function row(d: Department) {
    return <li key={d.id} className="deptRow"><b>{d.name}</b> <small>{d.kind === "TEAM" ? "nhóm" : "phòng ban"} · {d.users} người · {d.workspaces} workspace</small>
      <button className="btn sm ghost" onClick={() => { const n = window.prompt("Tên mới:", d.name)?.trim(); if (n) void act(() => api.admin.renameDepartment(d.id, { name: n })); }}>Đổi tên</button>
      <button className="btn sm ghost" onClick={() => { if (confirm(`Xoá “${d.name}”?`)) void act(() => api.admin.deleteDepartment(d.id)); }}>Xoá</button></li>;
  }
  return (<>
    <PageHead title="Phòng ban & nhóm" sub="Nhóm tổ chức để báo cáo chi phí và sử dụng. Không cấp quyền: quyền truy cập vẫn theo thành viên workspace/ứng dụng."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Thêm">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (name.trim()) void act(() => api.admin.createDepartment({ name: name.trim(), kind: parent ? "TEAM" : "DEPARTMENT", parentId: parent || undefined }).then(() => setName(""))); }}>
        <input aria-label="Tên" placeholder="Tên phòng ban hoặc nhóm" maxLength={120} value={name} onChange={(e) => setName(e.target.value)}/>
        <select aria-label="Thuộc phòng ban" value={parent} onChange={(e) => setParent(e.target.value)}><option value="">— phòng ban cấp cao nhất —</option>{deps.map((d) => <option key={d.id} value={d.id}>Nhóm trong: {d.name}</option>)}</select>
        <button className="btn primary" disabled={!name.trim()}>Thêm</button>
      </form>
    </Card>
    <Card title="Cơ cấu">{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : deps.length === 0 ? <StateView kind="empty" title="Chưa có phòng ban"/> :
      <ul className="plainList">{deps.map((d) => <Fragment key={d.id}>{row(d)}{teamsOf(d.id).length ? <ul className="plainList nested">{teamsOf(d.id).map(row)}</ul> : null}</Fragment>)}</ul>}</Card>
    <Card title="Gán người dùng hoặc workspace">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (who.id) void act(() => who.type === "USER" ? api.admin.assignUserDepartment(who.id, target || null) : api.admin.assignWorkspaceDepartment(who.id, target || null), "Đã gán."); }}>
        <ScopePicker types={["USER", "WORKSPACE"]} value={who} onChange={setWho}/>
        <select aria-label="Phòng ban / nhóm" value={target} onChange={(e) => setTarget(e.target.value)}><option value="">— bỏ gán —</option>{(data ?? []).map((d) => <option key={d.id} value={d.id}>{d.kind === "TEAM" ? "  · " : ""}{d.name}</option>)}</select>
        <button className="btn primary" disabled={!who.id}>Gán</button>
      </form>
    </Card>
  </>);
}

/** SSO / SAML / SCIM status and SCIM group → workspace role mappings (never system admin). */
function IdentityPage() {
  const cfg = useLoad(() => api.authConfig(), []);
  const scim = useLoad(() => api.adminScim(), []);
  const [m, setM] = useState({ groupId: "", ws: { type: "WORKSPACE", id: "" }, role: "VIEWER" }); const [err, setErr] = useState<string | null>(null);
  async function act(fn: () => Promise<unknown>) { setErr(null); try { await fn(); scim.reload(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  const c = cfg.data; const s = scim.data;
  return (<>
    <PageHead title="Định danh" sub="Đăng nhập một lần (OIDC), SAML qua nhà cung cấp OIDC (identity brokering), cấp tài khoản tự động (SCIM 2.0). MFA do nhà cung cấp danh tính quản lý."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}
    <div className="kpiGrid">
      <Kpi label="OIDC (SSO)" value={c ? (c.oidc ? "Bật" : "Tắt") : "…"} hint="OIDC_ENABLED · đăng xuất cũng kết thúc phiên ở IdP"/>
      <Kpi label="SAML" value={c ? (c.saml ? "Bật (qua IdP broker)" : "Tắt") : "…"} hint="SAML_ENABLED + SAML_IDP_HINT"/>
      <Kpi label="SCIM 2.0" value={s ? (s.enabled ? "Bật" : "Tắt") : "…"} hint={s ? `${s.users} tài khoản do SCIM cấp · SCIM_ENABLED + SCIM_TOKEN` : ""}/>
      <Kpi label="MFA" value="Do IdP quản lý" hint="MFA managed by Identity Provider"/>
    </div>
    <Card title="Nhóm SCIM → quyền workspace">
      <p className="hint">Nhóm từ IdP chỉ tạo quyền khi được ánh xạ ở đây; không có ánh xạ nào tới quản trị hệ thống. Thành viên do SCIM thêm sẽ được SCIM gỡ; thành viên thêm tay không bị đụng tới.</p>
      {!s ? <StateView kind="loading"/> : <>
        <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (m.groupId && m.ws.id) void act(() => api.addScimMapping(m.groupId, m.ws.id, m.role)); }}>
          <select aria-label="Nhóm SCIM" value={m.groupId} onChange={(e) => setM({ ...m, groupId: e.target.value })}><option value="">— nhóm —</option>{s.groups.map((g) => <option key={g.id} value={g.id}>{g.displayName} ({g.members})</option>)}</select>
          <ScopePicker types={["WORKSPACE"]} value={m.ws} onChange={(v) => setM({ ...m, ws: v })}/>
          <select aria-label="Vai trò" value={m.role} onChange={(e) => setM({ ...m, role: e.target.value })}>{["VIEWER", "PUBLISHER", "EDITOR", "WORKSPACE_ADMIN"].map((r) => <option key={r} value={r}>{r}</option>)}</select>
          <button className="btn primary" disabled={!m.groupId || !m.ws.id}>Ánh xạ</button>
        </form>
        {s.mappings.length === 0 ? <StateView kind="empty" title="Chưa có ánh xạ"/> : <table className="table"><thead><tr><th>Nhóm</th><th>Workspace</th><th>Vai trò</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{s.mappings.map((x) => <tr key={x.id}><td>{x.group}</td><td>{x.workspace}</td><td className="code">{x.role}</td><td><button className="btn sm ghost" onClick={() => void act(() => api.deleteScimMapping(x.id))}>Gỡ</button></td></tr>)}</tbody></table>}
      </>}
    </Card>
  </>);
}

/** Approved HTTPS connectors for server apps: the credential is stored encrypted and added by the platform; apps never see it. */
function ConnectorsPage() {
  const { data, error, reload } = useLoad(() => api.admin.connectors(), []);
  const [f, setF] = useState({ key: "", name: "", description: "", baseUrl: "https://", authHeader: "", authValue: "", ops: "GET /items" });
  const [grant, setGrant] = useState<{ key: string; app: { type: string; id: string } } | null>(null);
  const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  async function act(fn: () => Promise<unknown>, ok?: string) { setErr(null); setMsg(null); try { await fn(); if (ok) setMsg(ok); reload(); } catch (x) { setErr(errText(x, "Không lưu được.")); } }
  function edit(c: Connector) { setF({ key: c.key, name: c.name, description: c.description, baseUrl: c.baseUrl, authHeader: c.authHeader ?? "", authValue: "", ops: c.operations.map((o) => `${o.method} ${o.path}`).join("\n") }); }
  const operations = f.ops.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const [m, ...p] = l.split(/\s+/); return { method: (m ?? "").toUpperCase(), path: p.join("") }; });
  return (<>
    <PageHead title="Connector" sub="API HTTPS đã duyệt mà ứng dụng có máy chủ được gọi qua cổng runtime. Thông tin xác thực mã hóa, chỉ ghi; ứng dụng và AI không bao giờ thấy."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Thêm / sửa connector">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); void act(() => api.admin.saveConnector({ key: f.key.trim(), name: f.name.trim(), description: f.description.trim() || undefined, baseUrl: f.baseUrl.trim(),
        authHeader: f.authHeader.trim() || undefined, authValue: f.authValue || undefined, operations }), "Đã lưu connector."); }}>
        <input aria-label="Mã" placeholder="ma-connector" value={f.key} onChange={(e) => setF({ ...f, key: e.target.value.toLowerCase() })}/>
        <input aria-label="Tên" placeholder="Tên" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })}/>
        <input aria-label="Base URL" placeholder="https://api.example.com/v1" value={f.baseUrl} onChange={(e) => setF({ ...f, baseUrl: e.target.value })}/>
        <input aria-label="Header xác thực" placeholder="Authorization" value={f.authHeader} onChange={(e) => setF({ ...f, authHeader: e.target.value })}/>
        <input aria-label="Giá trị xác thực" type="password" autoComplete="off" placeholder="Giá trị (để trống = giữ nguyên)" value={f.authValue} onChange={(e) => setF({ ...f, authValue: e.target.value })}/>
        <input aria-label="Mô tả" placeholder="Mô tả (AI đọc được)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })}/>
        <textarea aria-label="Thao tác cho phép" rows={3} placeholder={"GET /contacts\nPOST /contacts"} value={f.ops} onChange={(e) => setF({ ...f, ops: e.target.value })}/>
        <button className="btn primary" disabled={!f.key || !f.name || !f.baseUrl}>Lưu</button>
      </form>
    </Card>
    <Card title="Danh mục">{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : data.length === 0 ? <StateView kind="empty" title="Chưa có connector"/> :
      <table className="table"><thead><tr><th>Connector</th><th>Base URL</th><th>Thao tác</th><th>Xác thực</th><th>Ứng dụng</th><th>Trạng thái</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data.map((c) => <tr key={c.key}><td><b>{c.name}</b><small className="code">{c.key}</small></td><td className="code">{c.baseUrl}</td>
          <td>{c.operations.map((o) => <small key={o.method + o.path} className="code">{o.method} {o.path}</small>)}</td><td>{c.hasSecret ? `${c.authHeader ?? "—"}: ••••` : "không"}</td><td>{c.grants}</td>
          <td><Pill value={c.status === "APPROVED" ? "ACTIVE" : "DISABLED"} label={c.status === "APPROVED" ? "Đã duyệt" : "Tắt"}/></td>
          <td><div className="row"><button className="btn sm" onClick={() => edit(c)}>Sửa</button>
            <button className="btn sm ghost" onClick={() => void act(() => api.admin.connectorStatus(c.key, c.status === "APPROVED" ? "DISABLED" : "APPROVED"))}>{c.status === "APPROVED" ? "Tắt" : "Bật"}</button>
            <button className="btn sm ghost" onClick={() => setGrant({ key: c.key, app: { type: "PROJECT", id: "" } })}>Cấp cho ứng dụng</button></div></td></tr>)}</tbody></table>}</Card>
    {grant ? <Card title={`Cấp “${grant.key}” cho ứng dụng có máy chủ`}>
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (grant.app.id) void act(() => api.admin.grantConnector(grant.key, grant.app.id), "Đã cấp.").then(() => setGrant(null)); }}>
        <ScopePicker types={["PROJECT"]} value={grant.app} onChange={(v) => setGrant({ ...grant, app: v })}/><button className="btn primary" disabled={!grant.app.id}>Cấp</button>
        <button type="button" className="btn ghost" onClick={() => setGrant(null)}>Đóng</button></form></Card> : null}
  </>);
}

const BK_NAME: Record<string, string> = { postgres: "CSDL nền tảng (PostgreSQL)", appdb: "CSDL của ứng dụng có máy chủ", minio: "Tệp & artifact (MinIO)", forgejo: "Kho mã (Forgejo)", offsite: "Bản sao ngoài máy (S3)" };
/** Backup monitoring: last successful backup per component, the latest restore drill, problems (also raised as alerts). */
function BackupsPage() {
  const { data, error, reload } = useLoad(() => api.admin.backups(), []);
  const size = (b: number | null) => (b == null ? "—" : b > 1048576 ? `${(b / 1048576).toFixed(1)} MiB` : `${Math.round(b / 1024)} KiB`);
  return (<>
    <PageHead title="Sao lưu" sub="Lịch: hằng ngày (scripts/backup-daemon.sh), diễn tập khôi phục hằng tuần từ chính file sao lưu vào máy chủ tạm. Cảnh báo khi bản sao lưu quá 26 giờ, lỗi hoặc diễn tập không đạt."/>
    {error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : data.length === 0 ? <StateView kind="empty" title="Chưa cấu hình thư mục sao lưu (BACKUP_STATUS_DIRS)"/> :
      data.map((e) => <Card key={e.environment} title={`Môi trường: ${e.environment}`} actions={<Pill value={e.healthy ? "ACTIVE" : "DISABLED"} label={e.healthy ? "Ổn" : "Có vấn đề"}/>}>
        {e.problems.length ? <ul className="plainList">{e.problems.map((p) => <li key={p} className="formError">{p}</li>)}</ul> : null}
        <table className="table"><thead><tr><th>Thành phần</th><th>Lần thành công gần nhất</th><th>Kích thước</th><th>Lần chạy gần nhất</th></tr></thead>
          <tbody>{e.components.map((c) => <tr key={c.name}><td>{BK_NAME[c.name] ?? c.name}</td>
            <td>{c.lastSuccess ? <>{ago(c.lastSuccess)} {c.stale ? <Pill value="DISABLED" label="quá hạn"/> : null}</> : c.state === "SKIPPED" ? (c.name === "offsite" ? "CHƯA CẤU HÌNH — sao lưu chỉ nằm trên máy này" : "không triển khai ở đây") : "chưa có"}</td>
            <td>{size(c.sizeBytes)}</td><td>{c.state}{c.error ? <small className="formError">{c.error}</small> : null}</td></tr>)}</tbody></table>
        <h3 className="subHead">Diễn tập khôi phục {e.drillAt ? `· ${ago(e.drillAt)}` : ""}</h3>
        {e.drill.length === 0 ? <p className="hint">Chưa diễn tập.</p> : <ul className="plainList">{e.drill.map((d) => <li key={d.component}><Pill value={d.result === "PASS" ? "ACTIVE" : d.result === "SKIPPED" ? "UNKNOWN" : "DISABLED"} label={d.result}/> {BK_NAME[d.component] ?? d.component}: {d.detail}</li>)}</ul>}
      </Card>)}
  </>);
}
