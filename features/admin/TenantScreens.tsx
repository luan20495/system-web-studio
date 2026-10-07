"use client";
/**
 * Platform / Admin console screens that run on the T2 tenant API and the workspace member API (integration/v2 @ 1a9995c):
 *   Platform  → Công ty (tenant list, create, suspend / restore, members)               `/api/v1/admin/tenants/**`            SYSTEM_ADMIN
 *   Admin     → Công ty của tôi (tenant info + members)                                 `/api/v1/admin/tenants/{id}/**`       TENANT_MEMBERS (TENANT_ADMIN)
 *             → Workspace của tôi (workspace members)                                   `/api/v1/workspaces/{w}/members/**`   MEMBER_MANAGE (WORKSPACE_ADMIN)
 *             → Nguồn dữ liệu (data sources of a workspace)                             C3 Management API                      DATA_SOURCE_MANAGE
 * Everything shown is what the server answered; the console never invents a row. Rules that the server enforces are explained before the click (adminModel.ts), and the server's refusal is shown in words.
 */
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { Member, TenantMemberView, TenantView } from "@/lib/http-types";
import { useSession } from "../session";
import { useLoad } from "../useLoad";
import { Card, ErrorState, fmtDate, Kpi, Pill, StateView } from "../ui";
import { DataSourcesPanel } from "../studio/builder/DataSourcesPanel";
import type { DataManagementCalls } from "../studio/builder/core/dataManagement";
import { Modal } from "./Modal";
import { PageHead } from "./PageHead";
import { A } from "./base";
import {
  TENANT_ROLES, TENANT_STATUS_LABEL, WORKSPACE_ROLES, addableTenantPeople, adminErrorText, adminScope, checkTenantForm, memberChangeBlock, personLabel, personOf, tenantActions, tenantMemberRows,
  workspaceMemberBlock, workspaceRoleLabel, type Person,
} from "./adminModel";

const say = (e: unknown, fallback: string) => adminErrorText(e instanceof ApiError ? e : { message: e instanceof Error ? e.message : undefined }, fallback);
const statusPill = (s: string) => <Pill value={s} label={TENANT_STATUS_LABEL[s] ?? s}/>;

// ------------------------------------------------------------------------------------------------------------------------- people
/** Who the console can name. A SYSTEM_ADMIN searches every account (`/admin/users`); anyone else only knows the members of the workspaces they administer (the tenant API returns bare ids). */
function usePeople(extra: Person[] = []) {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const [q, setQ] = useState("");
  const wsIds = scope.workspaces.map((w) => w.id).join(",");
  const found = useLoad(async (): Promise<Person[]> => {
    if (scope.platform) return (await api.admin.users(0, q)).items.map((u) => personOf(u));
    const lists = await Promise.all(scope.workspaces.map((w) => api.listWorkspaceMembers(w.id).catch(() => [] as Member[])));
    const all = lists.flat().map((m) => personOf(m));
    const needle = q.trim().toLowerCase();
    return needle ? all.filter((p) => `${p.username} ${p.displayName ?? ""}`.toLowerCase().includes(needle)) : all;
  }, [scope.platform, wsIds, q]);
  const people = useMemo(() => { const m = new Map<string, Person>(); [...extra, ...(found.data ?? [])].forEach((p) => m.set(p.id, p)); return m; }, [found.data, extra]);
  return { people, q, setQ, loading: found.loading, platform: scope.platform, nobody: !scope.platform && scope.workspaces.length === 0 };
}

function PersonSelect({ id, people, q, setQ, value, onChange, platform }: { id: string; people: Person[]; q: string; setQ: (q: string) => void; value: string; onChange: (id: string) => void; platform: boolean }) {
  return (
    <div className="stack">
      <input aria-label="Tìm người dùng" placeholder={platform ? "Tìm theo tên hoặc tên đăng nhập" : "Lọc trong số thành viên các workspace bạn quản trị"} value={q} onChange={(e) => setQ(e.target.value)}/>
      <select id={id} aria-label="Người dùng" value={value} onChange={(e) => onChange(e.target.value)} size={Math.min(6, Math.max(2, people.length))}>
        {people.length === 0 ? <option value="" disabled>Không có người phù hợp</option> : null}
        {people.map((p) => <option key={p.id} value={p.id}>{personLabel(p, p.id)}</option>)}
      </select>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------------------------ tenant members
function TenantMembers({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const { me } = useSession();
  const members = useLoad(() => api.admin.tenantMembers(tenantId), [tenantId]);
  // the person at the keyboard is always nameable (the tenant API returns bare ids)
  const self = useMemo(() => (me ? [{ id: me.id, username: me.username, displayName: me.displayName }] : []), [me]);
  const { people, q, setQ, platform, nobody } = usePeople(self);
  const [pick, setPick] = useState(""); const [role, setRole] = useState<string>("MEMBER");
  const [busy, setBusy] = useState<string | null>(null); const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const list: TenantMemberView[] = members.data ?? [];
  const rows = tenantMemberRows(list, people);
  const addable = addableTenantPeople(people, list);
  async function act(key: string, fn: () => Promise<unknown>, ok: string) {
    setBusy(key); setMsg(null);
    try { await fn(); setMsg({ kind: "ok", text: ok }); members.reload(); } catch (e) { setMsg({ kind: "err", text: say(e, "Chưa thực hiện được.") }); } finally { setBusy(null); }
  }
  function change(m: { userId: string; role: string }, next: "TENANT_ADMIN" | "MEMBER" | "REMOVE") {
    const block = memberChangeBlock(m, { id: me!.id }, list, next);
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (next === "REMOVE") { if (!window.confirm("Gỡ người này khỏi công ty?")) return; void act(`rm:${m.userId}`, () => api.admin.removeTenantMember(tenantId, m.userId), "Đã gỡ khỏi công ty."); }
    else void act(`role:${m.userId}`, () => api.admin.setTenantMember(tenantId, m.userId, next), "Đã đổi vai trò.");
  }
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!pick) { setMsg({ kind: "err", text: "Hãy chọn một người dùng." }); return; }
    if (pick === me!.id) { setMsg({ kind: "err", text: "Bạn không thể tự thêm mình vào công ty." }); return; }
    await act("add", () => api.admin.setTenantMember(tenantId, pick, role), "Đã thêm vào công ty."); setPick("");
  }
  return (
    <Card title={`Thành viên của ${tenantName}${members.data ? ` (${rows.length})` : ""}`}>
      {members.error ? <ErrorState error={members.error} retry={members.reload}/> : members.loading && !members.data ? <StateView kind="loading"/> : rows.length === 0 ? <StateView kind="empty" title="Công ty chưa có thành viên"/> : (
        <table className="table" data-testid="tenant-members">
          <thead><tr><th>Người dùng</th><th>Vai trò</th><th/></tr></thead>
          <tbody>{rows.map((r) => (
            <tr key={r.userId} data-testid={`tm:${r.userId}`}>
              <td>{r.known ? <b>{r.label}</b> : <span title="Tên chưa tra cứu được: API công ty chỉ trả mã người dùng">{r.label}</span>}{r.userId === me?.id ? <small> · bạn</small> : null}</td>
              <td><select aria-label={`Vai trò của ${r.label}`} value={r.role} disabled={busy !== null || r.userId === me?.id} onChange={(e) => change(r, e.target.value as "TENANT_ADMIN" | "MEMBER")}>
                {TENANT_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select></td>
              <td><button className="btn sm danger" disabled={busy !== null || r.userId === me?.id} title={r.userId === me?.id ? "Bạn không thể tự gỡ mình" : undefined} onClick={() => change(r, "REMOVE")}>Gỡ</button></td>
            </tr>))}</tbody>
        </table>)}
      <form className="stack" onSubmit={(e) => void add(e)} data-testid="tenant-member-add">
        <h3 className="bx-h3">Thêm thành viên</h3>
        {nobody ? <p className="hint" data-testid="tenant-add-blocked">Chưa thêm được người mới từ màn hình này: tài khoản của bạn chỉ tra cứu được người trong các workspace bạn quản trị, và bạn chưa quản trị workspace nào. (Cần API tra cứu người dùng theo công ty: handoff H-C1-11.)</p> : (<>
          <PersonSelect id="tm-person" people={addable} q={q} setQ={setQ} value={pick} onChange={setPick} platform={platform}/>
          <label className="field"><span>Vai trò</span><select data-testid="tm-role" value={role} onChange={(e) => setRole(e.target.value)}>{TENANT_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select></label>
          <div className="row"><button className="btn primary" disabled={busy !== null || !pick}>{busy === "add" ? "Đang thêm…" : "Thêm vào công ty"}</button></div></>)}
      </form>
      {msg ? <p className={msg.kind === "ok" ? "notice" : "formError"} role={msg.kind === "ok" ? "status" : "alert"} data-testid="tenant-msg">{msg.text}</p> : null}
    </Card>
  );
}

// ---------------------------------------------------------------------------------------------------------------------- tenant detail
function TenantBody({ id, onChanged }: { id: string; onChanged?: () => void }) {
  const tenant = useLoad(() => api.admin.tenant(id), [id]);
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<string | null>(null);
  if (tenant.error) return <ErrorState error={tenant.error} retry={tenant.reload}/>;
  if (tenant.loading && !tenant.data) return <StateView kind="loading"/>;
  const t = tenant.data as TenantView;
  const actions = scope.platform ? tenantActions(t) : [];
  async function setStatus(to: "ACTIVE" | "SUSPENDED" | "DELETED", confirm: string) {
    if (!window.confirm(confirm)) return;
    setBusy(true); setMsg(null);
    try { await api.admin.setTenantStatus(t.id, to); setMsg("Đã đổi trạng thái công ty."); tenant.reload(); onChanged?.(); } catch (e) { setMsg(say(e, "Chưa đổi được trạng thái.")); } finally { setBusy(false); }
  }
  return (<>
    <PageHead title={t.name} sub={`${t.slug} · tạo ${fmtDate(t.createdAt)}`} actions={actions.length ? <div className="row">{actions.map((a) => (
      <button key={a.to} className={`btn ${a.danger ? "danger" : ""}`} disabled={busy} data-testid={`tenant-${a.to}`} onClick={() => void setStatus(a.to, a.confirm)}>{a.label}</button>))}</div> : undefined}/>
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    <div className="kpiGrid"><Kpi label="Trạng thái" value={statusPill(t.status)}/><Kpi label="Mã công ty" value={t.slug}/></div>
    {t.status !== "ACTIVE" ? <p className="hint" role="note">Công ty đang {TENANT_STATUS_LABEL[t.status]?.toLowerCase() ?? t.status}: người dùng của công ty không vào được cho tới khi mở khóa.</p> : null}
    <TenantMembers tenantId={t.id} tenantName={t.name}/>
  </>);
}

// ------------------------------------------------------------------------------------------------------------------------- platform
export function TenantsPage() {
  const router = useRouter();
  const list = useLoad(() => api.admin.tenants(), []);
  const [adding, setAdding] = useState(false);
  return (<>
    <PageHead title="Công ty (tenant)" sub="Các công ty dùng nền tảng. Tạo công ty mới, tạm khóa hoặc khôi phục, và quản lý quản trị viên của từng công ty."
      actions={<button className="btn primary" onClick={() => setAdding(true)}>+ Tạo công ty</button>}/>
    {adding ? <CreateTenantDialog onClose={() => setAdding(false)} onCreated={(t) => { setAdding(false); list.reload(); router.push(A(`/tenants/${t.id}`)); }}/> : null}
    <Card>
      {list.error ? <ErrorState error={list.error} retry={list.reload}/> : list.loading && !list.data ? <StateView kind="loading"/> : (list.data ?? []).length === 0 ? <StateView kind="empty" title="Chưa có công ty nào"/> : (
        <table className="table" data-testid="tenant-list">
          <thead><tr><th>Công ty</th><th>Trạng thái</th><th>Tạo lúc</th></tr></thead>
          <tbody>{list.data!.map((t) => (
            <tr key={t.id} className="clickRow" data-testid={`tenant:${t.slug}`} onClick={() => router.push(A(`/tenants/${t.id}`))}>
              <td><Link href={A(`/tenants/${t.id}`)}><b>{t.name}</b></Link><small>{t.slug}</small></td><td>{statusPill(t.status)}</td><td>{fmtDate(t.createdAt)}</td>
            </tr>))}</tbody>
        </table>)}
    </Card>
  </>);
}

export function TenantDetailPage({ id }: { id: string }) {
  return (<>
    <p><Link className="btn sm ghost" href={A("/tenants")}>← Danh sách công ty</Link></p>
    <TenantBody id={id}/>
  </>);
}

function CreateTenantDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (t: TenantView) => void }) {
  const [slug, setSlug] = useState(""); const [name, setName] = useState(""); const [admin, setAdmin] = useState("");
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  const { people, q, setQ } = usePeople();
  const problems = checkTenantForm({ slug, name });
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setError(null);
    if (problems.slug || problems.name) return;
    setBusy(true);
    try { onCreated(await api.admin.createTenant({ slug: slug.trim().toLowerCase(), name: name.trim(), ...(admin ? { firstAdminUserId: admin } : {}) })); } catch (err) { setError(say(err, "Chưa tạo được công ty.")); } finally { setBusy(false); }
  }
  return (
    <Modal label="Tạo công ty" onClose={onClose}>
      <form className="modalBody" noValidate onSubmit={(e) => void submit(e)} data-testid="tenant-create">
        <h2>Tạo công ty</h2>
        <label className="field"><span>Tên công ty</span><input data-testid="tenant-name" value={name} maxLength={160} aria-invalid={touched && !!problems.name} onChange={(e) => setName(e.target.value)}/></label>
        {touched && problems.name ? <p className="formError" role="alert">{problems.name}</p> : null}
        <label className="field"><span>Mã công ty</span><input data-testid="tenant-slug" value={slug} autoComplete="off" aria-invalid={touched && !!problems.slug} onChange={(e) => setSlug(e.target.value)}/></label>
        {touched && problems.slug ? <p className="formError" role="alert">{problems.slug}</p> : null}
        <div className="field"><span>Quản trị viên đầu tiên (không bắt buộc)</span>
          <PersonSelect id="tenant-first-admin" people={[...people.values()]} q={q} setQ={setQ} value={admin} onChange={setAdmin} platform/>
          <small className="hint">Nếu không chọn, công ty được tạo trống; hãy thêm quản trị viên ở trang công ty ngay sau đó.</small></div>
        {error ? <p className="formError" role="alert">{error}</p> : null}
        <div className="row"><button className="btn primary" disabled={busy}>{busy ? "Đang tạo…" : "Tạo công ty"}</button><button type="button" className="btn" onClick={onClose}>Hủy</button></div>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------------------------------ admin: company
export function CompanyPage() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const [id, setId] = useState(scope.tenants[0]?.id ?? "");
  useEffect(() => { if (!id && scope.tenants[0]) setId(scope.tenants[0].id); }, [id, scope.tenants]);
  if (!scope.tenants.length) return (<>
    <PageHead title="Công ty của tôi"/>
    <StateView kind="forbidden" title="Bạn chưa quản trị công ty nào" detail={<p>Máy chủ không liệt kê quyền quản lý thành viên công ty cho tài khoản này.</p>}/>
  </>);
  return (<>
    {scope.tenants.length > 1 ? <label className="field"><span>Công ty</span><select value={id} onChange={(e) => setId(e.target.value)}>{scope.tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label> : null}
    {id ? <TenantBody id={id}/> : null}
  </>);
}

/** the landing page of someone who is not a SYSTEM_ADMIN: only what the server lists for them */
export function ScopedHome() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  return (<>
    <PageHead title="Tổng quan" sub={`Xin chào ${me?.displayName ?? ""}. Đây là những gì bạn quản trị.`}/>
    <div className="grid2">
      <Card title="Công ty">{scope.tenants.length === 0 ? <p className="hint">Bạn không quản trị công ty nào.</p> : <ul className="plainList">{scope.tenants.map((t) => <li key={t.id}><Link href={A("/company")}><b>{t.name}</b></Link> {statusPill(t.status)}</li>)}</ul>}</Card>
      <Card title="Workspace">{scope.workspaces.length === 0 ? <p className="hint">Bạn không quản trị workspace nào.</p> : <ul className="plainList">{scope.workspaces.map((w) => <li key={w.id}><Link href={A("/my-workspaces")}><b>{w.name}</b></Link></li>)}</ul>}</Card>
    </div>
    <p className="hint">Các mục quản trị toàn hệ thống (người dùng, nhật ký, AI…) chỉ dành cho quản trị hệ thống.</p>
  </>);
}

// ------------------------------------------------------------------------------------------------------------------ admin: workspaces
export function MyWorkspacesPage() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const [id, setId] = useState(scope.workspaces[0]?.id ?? "");
  useEffect(() => { if (!id && scope.workspaces[0]) setId(scope.workspaces[0].id); }, [id, scope.workspaces]);
  if (!scope.workspaces.length) return (<>
    <PageHead title="Workspace của tôi"/>
    <StateView kind="forbidden" title="Bạn chưa quản trị workspace nào" detail={<p>Máy chủ không liệt kê quyền quản lý thành viên workspace cho tài khoản này.</p>}/>
  </>);
  return (<>
    <PageHead title="Workspace của tôi" sub="Thành viên và vai trò trong các workspace bạn quản trị."/>
    {scope.workspaces.length > 1 ? <label className="field"><span>Workspace</span><select data-testid="ws-pick" value={id} onChange={(e) => setId(e.target.value)}>{scope.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label> : null}
    {id ? <WorkspaceMembers workspaceId={id} name={scope.workspaces.find((w) => w.id === id)?.name ?? ""}/> : null}
  </>);
}

/** also used by the SYSTEM_ADMIN's workspace page: the same calls, the same rules (`MemberController`) */
export function WorkspaceMembers({ workspaceId, name }: { workspaceId: string; name: string }) {
  const { me } = useSession();
  const members = useLoad(() => api.listWorkspaceMembers(workspaceId), [workspaceId]);
  const [who, setWho] = useState(""); const [role, setRole] = useState("EDITOR");
  const [busy, setBusy] = useState<string | null>(null); const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const list = members.data ?? [];
  async function act(key: string, fn: () => Promise<unknown>, ok: string) {
    setBusy(key); setMsg(null);
    try { await fn(); setMsg({ kind: "ok", text: ok }); members.reload(); } catch (e) { setMsg({ kind: "err", text: say(e, "Chưa thực hiện được.") }); } finally { setBusy(null); }
  }
  function change(m: Member, next: string) {
    const block = workspaceMemberBlock(m, { id: me!.id }, list, next);
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (next === "REMOVE") { if (!window.confirm(`Gỡ ${m.displayName ?? m.username} khỏi workspace? Họ cũng mất quyền ở mọi ứng dụng của workspace.`)) return; void act(`rm:${m.userId}`, () => api.removeWorkspaceMember(workspaceId, m.userId), "Đã gỡ khỏi workspace."); }
    else void act(`role:${m.userId}`, () => api.changeWorkspaceMember(workspaceId, m.userId, next), "Đã đổi vai trò.");
  }
  async function add(e: FormEvent) {
    e.preventDefault();
    const v = who.trim(); if (!v) { setMsg({ kind: "err", text: "Hãy nhập tên đăng nhập hoặc email." }); return; }
    await act("add", () => api.addWorkspaceMember(workspaceId, v.includes("@") ? { email: v } : { username: v }, role), "Đã thêm vào workspace."); setWho("");
  }
  return (
    <Card title={`Thành viên của ${name}${members.data ? ` (${list.length})` : ""}`}>
      {members.error ? <ErrorState error={members.error} retry={members.reload}/> : members.loading && !members.data ? <StateView kind="loading"/> : list.length === 0 ? <StateView kind="empty" title="Workspace chưa có thành viên"/> : (
        <table className="table" data-testid="ws-members">
          <thead><tr><th>Người dùng</th><th>Vai trò</th><th>Tham gia</th><th/></tr></thead>
          <tbody>{list.map((m) => (
            <tr key={m.userId} data-testid={`wm:${m.username}`}>
              <td><b>{m.displayName ?? m.username}</b><small>{m.username}{m.email ? ` · ${m.email}` : ""}{m.userId === me?.id ? " · bạn" : ""}</small></td>
              <td><select aria-label={`Vai trò của ${m.username}`} value={m.role} disabled={busy !== null || m.userId === me?.id} onChange={(e) => change(m, e.target.value)}>
                {WORKSPACE_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}{WORKSPACE_ROLES.some((x) => x.id === m.role) ? null : <option value={m.role}>{workspaceRoleLabel(m.role)}</option>}</select></td>
              <td>{fmtDate(m.joinedAt)}</td>
              <td><button className="btn sm danger" disabled={busy !== null || m.userId === me?.id} title={m.userId === me?.id ? "Bạn không thể tự gỡ mình" : undefined} onClick={() => change(m, "REMOVE")}>Gỡ</button></td>
            </tr>))}</tbody>
        </table>)}
      <form className="stack" onSubmit={(e) => void add(e)} data-testid="ws-member-add">
        <h3 className="bx-h3">Thêm thành viên</h3>
        <label className="field"><span>Tên đăng nhập hoặc email</span><input data-testid="ws-add-who" value={who} autoComplete="off" onChange={(e) => setWho(e.target.value)}/></label>
        <label className="field"><span>Vai trò</span><select data-testid="ws-add-role" value={role} onChange={(e) => setRole(e.target.value)}>{WORKSPACE_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select></label>
        <p className="hint">Người này phải đã có tài khoản. Tạo tài khoản mới do quản trị hệ thống thực hiện.</p>
        <div className="row"><button className="btn primary" disabled={busy !== null}>{busy === "add" ? "Đang thêm…" : "Thêm vào workspace"}</button></div>
      </form>
      {msg ? <p className={msg.kind === "ok" ? "notice" : "formError"} role={msg.kind === "ok" ? "status" : "alert"} data-testid="ws-msg">{msg.text}</p> : null}
    </Card>
  );
}

// ------------------------------------------------------------------------------------------------------------------- admin: data sources
export function DataSourcesAdminPage() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const [id, setId] = useState(scope.dataWorkspaces[0]?.id ?? "");
  useEffect(() => { if (!id && scope.dataWorkspaces[0]) setId(scope.dataWorkspaces[0].id); }, [id, scope.dataWorkspaces]);
  const ws = scope.dataWorkspaces.find((w) => w.id === id);
  const perms = ws?.permissions ?? [];
  const calls = useMemo<DataManagementCalls | undefined>(() => !id ? undefined : ({
    connectors: async () => (await api.dataManagement.connectors(id)).items,
    list: async () => (await api.dataManagement.list(id)).items,
    create: (b) => api.dataManagement.create(id, b),
    update: (sid, b) => api.dataManagement.update(id, sid, b),
    remove: (sid) => api.dataManagement.remove(id, sid),
    credential: (sid) => api.dataManagement.credential(id, sid),
    setCredential: (sid, c) => api.dataManagement.setCredential(id, sid, c),
    removeCredential: (sid) => api.dataManagement.removeCredential(id, sid),
    test: (sid) => api.dataManagement.testConnection(id, sid),
    // slots are bound per application, in the Studio: nothing to list or change here
    listBindings: async () => [],
    bind: async () => { throw new Error("Liên kết khe dữ liệu được thực hiện trong Studio, theo từng ứng dụng."); },
    unbind: async () => { throw new Error("Liên kết khe dữ liệu được thực hiện trong Studio, theo từng ứng dụng."); },
  }), [id]);
  if (!scope.dataWorkspaces.length) return (<>
    <PageHead title="Nguồn dữ liệu"/>
    <StateView kind="forbidden" title="Bạn chưa có quyền với nguồn dữ liệu" detail={<p>Máy chủ không liệt kê quyền xem hoặc quản lý nguồn dữ liệu cho bạn ở workspace nào. Quản trị hệ thống không tự có quyền này.</p>}/>
  </>);
  return (<>
    <PageHead title="Nguồn dữ liệu" sub="Kết nối cơ sở dữ liệu / API của workspace. Khóa kết nối chỉ ghi, không bao giờ hiển thị lại. Liên kết khe dữ liệu của từng ứng dụng nằm trong Studio."/>
    {scope.dataWorkspaces.length > 1 ? <label className="field"><span>Workspace</span><select value={id} onChange={(e) => setId(e.target.value)}>{scope.dataWorkspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label> : null}
    <Card>
      <DataSourcesPanel key={id} doc={{ page: "", sections: [] } as never} calls={calls} canView={perms.includes("DATA_SOURCE_VIEW") || perms.includes("DATA_SOURCE_MANAGE")} viewReason="Bạn chưa được cấp quyền xem nguồn dữ liệu."
        canManage={perms.includes("DATA_SOURCE_MANAGE")} manageReason="Bạn chưa được cấp quyền quản lý nguồn dữ liệu." canBind={false} bindReason="Liên kết khe dữ liệu được thực hiện trong Studio."/>
    </Card>
  </>);
}
