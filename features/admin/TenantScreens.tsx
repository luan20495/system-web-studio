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
import { useEffect, useId, useMemo, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { Member, TenantMemberCandidate, TenantMemberView, TenantView } from "@/lib/http-types";
import { useSession } from "../session";
import { useLoad } from "../useLoad";
import { Card, ErrorState, fmtDate, Kpi, Pager, Pill, StateView } from "../ui";
import { isTenantAdminRole, isWorkspaceAdminRole } from "@xweb/permissions";
import { ArrowLeft, Building2, CircleCheck, ModalHeader, ShieldCheck, UserRound, confirm, LoadGate, useAction } from "@xweb/ui";
import { LoadNote } from "./LoadNote";
import { useSingleFlight } from "./useAdminAction";
import { PersonPicker } from "./PersonPicker";
import { PlatformCreateAccount } from "./ProvisioningLive";
import { DataSourcesPanel } from "../studio/builder/DataSourcesPanel";
import type { DataManagementCalls } from "../studio/builder/core/dataManagement";
import { Modal } from "./Modal";
import { PageHead } from "./PageHead";
import { useA } from "./console/context";
import {
  CANDIDATE_MAX_RESULTS, TENANT_ROLES, TENANT_STATUS_LABEL, WORKSPACE_ROLES, adminErrorText, candidateLabel, candidateQuery, adminScope, slugify, canManageWorkspaceMembers, checkTenantForm, memberChangeBlock, personLabel, personOf, tenantActions, tenantMemberRows,
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
  return { people, q, setQ, loading: found.loading, load: found, platform: scope.platform, nobody: !scope.platform && scope.workspaces.length === 0 };
}

// ------------------------------------------------------------------------------------------------------------------ tenant members
/** `onCreateAdmin` (Platform, an ACTIVE company): the way to give a company its first administrator when nobody exists yet; `rev` bumps after an account was created so the list reloads */
function TenantMembers({ tenantId, tenantName, onCreateAdmin, rev = 0 }: { tenantId: string; tenantName: string; onCreateAdmin?: () => void; rev?: number }) {
  const { me } = useSession();
  const members = useLoad(() => api.admin.tenantMembers(tenantId), [tenantId, rev]);
  const [q, setQ] = useState(""); const [applied, setApplied] = useState("");
  useEffect(() => { const t = setTimeout(() => setApplied(q), 300); return () => clearTimeout(t); }, [q]);
  const ask = candidateQuery(applied);
  // the directory is tenant-scoped on the server: only people already related to THIS tenant (a workspace of it, or a former membership) are offered
  const candidates = useLoad(async () => (ask.ask ? api.admin.tenantMemberCandidates(tenantId, ask.q) : []), [tenantId, ask.ask, ask.q, members.data]);
  const [pick, setPick] = useState(""); const [role, setRole] = useState<string>("MEMBER");
  const [busy, setBusy] = useState<string | null>(null); const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const list: TenantMemberView[] = members.data ?? [];
  const rows = tenantMemberRows(list);
  // a role is CHOSEN in the select and APPLIED by "Lưu" (a select must not commit a privilege change on a stray click / key press)
  const [draft, setDraft] = useState<Record<string, string>>({});
  const options: TenantMemberCandidate[] = candidates.data ?? [];
  useEffect(() => { if (pick && !options.some((c) => c.userId === pick)) setPick(""); }, [options, pick]);
  // single-flight: a double click on "Thêm" / "Gỡ" runs ONE request (a second call while one is running is skipped) (M-020)
  const flight = useAction(async (_c, fn: () => Promise<unknown>) => { await fn(); });
  async function act(key: string, fn: () => Promise<unknown>, ok: string) {
    if (flight.busy) return;
    setBusy(key); setMsg(null);
    const r = await flight.run(fn);
    if (r.status === "skipped") return;
    if (r.status === "ok") { setMsg({ kind: "ok", text: ok }); members.reload(); } else if (r.status === "error") setMsg({ kind: "err", text: say(r.error, "Chưa thực hiện được.") });
    setBusy(null);
  }
  async function change(m: { userId: string; role: string }, next: "TENANT_ADMIN" | "MEMBER" | "REMOVE") {
    const block = memberChangeBlock(m, { id: me!.id }, list, next);
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (next === "REMOVE") { const who = rows.find((r) => r.userId === m.userId)?.label ?? "người này"; if (!(await confirm({ title: `Gỡ ${who} khỏi công ty?`, message: `Họ không còn là thành viên của công ty “${tenantName}”. Tài khoản của họ không bị xóa.`, confirmLabel: "Gỡ khỏi công ty", danger: true }))) return; void act(`rm:${m.userId}`, () => api.admin.removeTenantMember(tenantId, m.userId), "Đã gỡ khỏi công ty."); }
    else void act(`role:${m.userId}`, () => api.admin.setTenantMember(tenantId, m.userId, next), "Đã đổi vai trò.");
  }
  async function saveRole(r: { userId: string; label: string; role: string }) {
    const next = draft[r.userId]; if (!next || next === r.role) return;
    const block = memberChangeBlock(r, { id: me!.id }, list, next as "TENANT_ADMIN" | "MEMBER");
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (isTenantAdminRole(next) || isTenantAdminRole(r.role)) {
      const up = isTenantAdminRole(next);
      if (!(await confirm({ title: up ? `Cho ${r.label} làm quản trị công ty?` : `Bỏ quyền quản trị công ty của ${r.label}?`, message: up ? `Người này sẽ quản lý được thành viên và workspace của công ty “${tenantName}”.` : `Người này không còn quản lý được thành viên và workspace của công ty “${tenantName}”.`, confirmLabel: up ? "Cho làm quản trị" : "Bỏ quyền quản trị", danger: !up }))) return;
    }
    await act(`role:${r.userId}`, () => api.admin.setTenantMember(tenantId, r.userId, next), "Đã đổi vai trò.");
    setDraft((d) => { const n = { ...d }; delete n[r.userId]; return n; });
  }
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!pick) { setMsg({ kind: "err", text: "Hãy chọn một người dùng." }); return; }
    await act("add", () => api.admin.setTenantMember(tenantId, pick, role), "Đã thêm vào công ty."); setPick("");
  }
  return (
    <Card title={`Thành viên của ${tenantName}${members.data ? ` (${rows.length})` : ""}`}>
      {members.error ? <ErrorState error={members.error} retry={members.reload}/> : members.loading && !members.data ? <StateView kind="loading"/> : rows.length === 0 ? <StateView kind="empty" title="Công ty chưa có thành viên" detail={onCreateAdmin ? <p>Công ty chưa có quản trị viên: chưa ai đăng nhập để quản lý công ty này. Tạo tài khoản đầu tiên; người đó nhận một liên kết kích hoạt và tự đặt mật khẩu.</p> : undefined} action={onCreateAdmin ? <button className="btn primary" data-testid="tenant-create-admin-empty" onClick={onCreateAdmin}>Tạo tài khoản quản trị công ty</button> : undefined}/> : (
        <table className="table" data-testid="tenant-members">
          <thead><tr><th>Người dùng</th><th>Vai trò</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{rows.map((r) => (
            <tr key={r.userId} data-testid={`tm:${r.userId}`}>
              <td>{r.known ? <b>{r.label}</b> : <span title="Máy chủ không trả tên cho người này">{r.label}</span>}{r.userId === me?.id ? <small> · bạn</small> : null}{r.email ? <small>{r.email}</small> : null}</td>
              <td><span className="row"><select aria-label={`Vai trò của ${r.label}`} value={draft[r.userId] ?? r.role} disabled={busy !== null || r.userId === me?.id} onChange={(e) => setDraft((d) => ({ ...d, [r.userId]: e.target.value }))}>
                {TENANT_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select>
                {draft[r.userId] && draft[r.userId] !== r.role ? <><button className="btn sm primary" data-testid={`tm-save:${r.userId}`} disabled={busy !== null} onClick={() => void saveRole(r)}>Lưu</button><button className="btn sm" disabled={busy !== null} onClick={() => setDraft((d) => { const n = { ...d }; delete n[r.userId]; return n; })}>Hủy</button></> : null}</span></td>
              <td><button className="btn sm danger" disabled={busy !== null || r.userId === me?.id} title={r.userId === me?.id ? "Bạn không thể tự gỡ mình" : undefined} onClick={() => change(r, "REMOVE")}>Gỡ</button></td>
            </tr>))}</tbody>
        </table>)}
      <form className="stack" onSubmit={(e) => void add(e)} data-testid="tenant-member-add">
        <h3 className="bx-h3">Thêm thành viên</h3>
        <label className="field"><span>Tìm người dùng</span><input data-testid="tm-search" placeholder="Tên, tên đăng nhập hoặc email (ít nhất 2 ký tự)" value={q} autoComplete="off" onChange={(e) => setQ(e.target.value)}/></label>
        {ask.hint ? <p className="hint" data-testid="tm-hint">{ask.hint}</p> : null}
        <label className="field"><span>Người dùng</span>
          <select data-testid="tm-person" size={Math.min(6, Math.max(2, options.length))} value={pick} onChange={(e) => setPick(e.target.value)} disabled={!ask.ask}>
            {options.length === 0 ? <option value="" disabled>{candidates.loading ? "Đang tìm…" : "Không có người phù hợp"}</option> : null}
            {options.map((c) => <option key={c.userId} value={c.userId}>{candidateLabel(c)}</option>)}
          </select>
          <small className="hint">Chỉ hiện người đã thuộc một workspace của công ty này (hoặc từng là thành viên), đã kích hoạt và không phải quản trị hệ thống. Tối đa {CANDIDATE_MAX_RESULTS} kết quả.</small></label>
        {candidates.error ? <p className="formError" role="alert">{say(candidates.error, "Chưa tìm được người dùng.")}</p> : null}
        <label className="field"><span>Vai trò</span><select data-testid="tm-role" value={role} onChange={(e) => setRole(e.target.value)}>{TENANT_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select></label>
        <div className="row"><button className="btn primary" data-testid="tm-add" disabled={busy !== null || !pick}>{busy === "add" ? "Đang thêm…" : "Thêm vào công ty"}</button></div>
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
  const [creating, setCreating] = useState(false); const [rev, setRev] = useState(0);
  const statusFlight = useAction(async (_c, fn: () => Promise<unknown>) => { await fn(); });
  if (tenant.error) return <ErrorState error={tenant.error} retry={tenant.reload}/>;
  if (tenant.loading && !tenant.data) return <StateView kind="loading"/>;
  const t = tenant.data as TenantView;
  const actions = scope.platform ? tenantActions(t) : [];
  // the first administrator of a company is made HERE (existing route: POST /admin/tenants/{t}/users). Only a Platform operator, only while the company is usable.
  const canCreateAdmin = scope.platform && t.status === "ACTIVE";
  async function setStatus(to: "ACTIVE" | "SUSPENDED" | "DELETED", a: { label: string; danger: boolean; message: string }) {
    if (!(await confirm({ title: `${a.label} công ty “${t.name}”?`, message: a.message, confirmLabel: a.label, danger: a.danger }))) return;
    if (statusFlight.busy) return;
    setBusy(true); setMsg(null);
    const r = await statusFlight.run(() => api.admin.setTenantStatus(t.id, to));
    if (r.status === "skipped") return;
    if (r.status === "ok") { setMsg("Đã đổi trạng thái công ty."); tenant.reload(); onChanged?.(); } else if (r.status === "error") setMsg(say(r.error, "Chưa đổi được trạng thái."));
    setBusy(false);
  }
  return (<>
    <PageHead title={t.name} sub={`${t.slug} · tạo ${fmtDate(t.createdAt)}`} actions={actions.length || canCreateAdmin ? <div className="row">
      {canCreateAdmin ? <button className="btn primary" data-testid="tenant-create-admin" onClick={() => setCreating(true)}>Tạo tài khoản quản trị công ty</button> : null}
      {actions.map((a) => (
      <button key={a.to} className={`btn ${a.danger ? "danger" : ""}`} disabled={busy} data-testid={`tenant-${a.to}`} onClick={() => void setStatus(a.to, a)}>{a.label}</button>))}</div> : undefined}/>
    {msg ? <p className="notice" role="status">{msg}</p> : null}
    <div className="kpiGrid"><Kpi label="Trạng thái" value={statusPill(t.status)}/><Kpi label="Mã công ty" value={t.slug}/></div>
    {t.status !== "ACTIVE" ? <p className="hint" role="note">Công ty đang {TENANT_STATUS_LABEL[t.status]?.toLowerCase() ?? t.status}: người dùng của công ty không vào được cho tới khi mở khóa.</p> : null}
    <TenantMembers tenantId={t.id} tenantName={t.name} rev={rev} onCreateAdmin={canCreateAdmin ? () => setCreating(true) : undefined}/>
    {creating ? <PlatformCreateAccount tenant={{ id: t.id, name: t.name }} onClose={() => setCreating(false)} onCreated={() => setRev((r) => r + 1)}/> : null}
  </>);
}

// ------------------------------------------------------------------------------------------------------------------------- platform
const TENANT_PAGE = 25;
export function TenantsPage() {
  const A = useA();
  const router = useRouter();
  const list = useLoad(() => api.admin.tenants(), []);
  const [adding, setAdding] = useState(false);
  // the API answers the whole list; searching and paging happen here (a tenant filter / page route is a C1 handoff)
  const [q, setQ] = useState(""); const [page, setPage] = useState(0);
  const needle = q.trim().toLowerCase();
  const shown = useMemo(() => (list.data ?? []).filter((t) => !needle || `${t.name} ${t.slug}`.toLowerCase().includes(needle)), [list.data, needle]);
  const pageRows = shown.slice(page * TENANT_PAGE, (page + 1) * TENANT_PAGE);
  return (<>
    <PageHead title="Công ty (tenant)" sub="Các công ty dùng nền tảng. Tạo công ty mới, tạm khóa hoặc khôi phục, và quản lý quản trị viên của từng công ty."
      actions={<button className="btn primary" onClick={() => setAdding(true)}>+ Tạo công ty</button>}/>
    {adding ? <CreateTenantDialog onClose={() => setAdding(false)} onCreated={(t) => { setAdding(false); list.reload(); router.push(A(`/tenants/${t.id}`)); }}/> : null}
    <Card>
      <LoadGate load={list} label="danh sách công ty" isEmpty={(d) => d.length === 0} empty={{ title: "Chưa có công ty nào" }}>{() => (<>
        <form className="filters" role="search" onSubmit={(e) => e.preventDefault()}>
          <input aria-label="Tìm công ty" placeholder="Tìm theo tên hoặc mã công ty" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }}/>
        </form>
        {shown.length === 0 ? <StateView kind="empty" title="Không có công ty phù hợp" detail={<p>Thử tên hoặc mã khác.</p>}/> : <>
        <table className="table" data-testid="tenant-list">
          <thead><tr><th>Công ty</th><th>Trạng thái</th><th>Tạo lúc</th></tr></thead>
          <tbody>{pageRows.map((t) => (
            <tr key={t.id} className="clickRow" data-testid={`tenant:${t.slug}`} onClick={() => router.push(A(`/tenants/${t.id}`))}>
              <td><Link href={A(`/tenants/${t.id}`)}><b>{t.name}</b></Link><small>{t.slug}</small></td><td>{statusPill(t.status)}</td><td>{fmtDate(t.createdAt)}</td>
            </tr>))}</tbody>
        </table>
        {shown.length > TENANT_PAGE ? <Pager page={page} size={TENANT_PAGE} total={shown.length} onPage={setPage}/> : null}</>}
      </>)}</LoadGate>
    </Card>
  </>);
}

export function TenantDetailPage({ id }: { id: string }) {
  const A = useA();
  return (<>
    <p><Link className="btn sm ghost xp-btnIcon" href={A("/tenants")}><ArrowLeft size={14} aria-hidden="true"/> Danh sách công ty</Link></p>
    <TenantBody id={id}/>
  </>);
}

function CreateTenantDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (t: TenantView) => void }) {
  const uid = useId(); const [slug, setSlug] = useState(""); const [name, setName] = useState(""); const [admin, setAdmin] = useState("");
  // the code follows the name until the person edits it by hand
  const [slugEdited, setSlugEdited] = useState(false);
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  const { people, q, setQ, loading, load: peopleLoad } = usePeople();
  const problems = checkTenantForm({ slug, name });
  const slugOk = !problems.slug && slug.trim() !== "";
  function onName(v: string) { setName(v); if (!slugEdited) setSlug(slugify(v)); }
  const once = useSingleFlight();
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setError(null);
    if (problems.slug || problems.name) return;
    await once(async () => {
    setBusy(true);
    try { onCreated(await api.admin.createTenant({ slug: slug.trim().toLowerCase(), name: name.trim(), ...(admin ? { firstAdminUserId: admin } : {}) })); } catch (err) { setError(say(err, "Chưa tạo được công ty.")); } finally { setBusy(false); }
    });
  }
  return (
    <Modal label="Tạo công ty" onClose={onClose} dismissible={!busy}>
      <form className="modalBody xp-tenantForm" noValidate onSubmit={(e) => void submit(e)} data-testid="tenant-create">
        <ModalHeader icon={<Building2 size={22}/>} title="Tạo công ty" subtitle="Mỗi công ty là một không gian riêng: người dùng, workspace và dữ liệu tách biệt với công ty khác."/>

        <section className="xp-section" aria-label="Thông tin công ty">
          <h3><Building2 size={14} aria-hidden="true"/> Thông tin công ty</h3>
          <label className="field"><span>Tên công ty</span>
            <input data-testid="tenant-name" value={name} maxLength={160} placeholder="Ví dụ: Công ty Cổ phần Ánh Dương" autoComplete="off" aria-invalid={touched && !!problems.name} aria-describedby={touched && problems.name ? `${uid}-name-err` : undefined} onChange={(e) => onName(e.target.value)}/></label>
          {touched && problems.name ? <p className="formError" role="alert" id={`${uid}-name-err`}>{problems.name}</p> : null}
          <label className="field"><span>Mã công ty</span>
            <span className={`xp-slugInput${touched && problems.slug ? " bad" : ""}`}>
              <input data-testid="tenant-slug" value={slug} autoComplete="off" spellCheck={false} placeholder="anh-duong" aria-invalid={touched && !!problems.slug} aria-describedby={`${uid}-slug-help${touched && problems.slug ? ` ${uid}-slug-err` : ""}`} onChange={(e) => { setSlugEdited(true); setSlug(e.target.value); }}/>
              {slugOk ? <CircleCheck size={16} className="xp-ok" aria-label="Mã hợp lệ"/> : null}
            </span>
            <small id={`${uid}-slug-help`}>{slugEdited ? "Chữ thường, số và dấu “-”, 2–120 ký tự." : "Tự tạo từ tên công ty; bạn có thể sửa."}</small></label>
          {touched && problems.slug ? <p className="formError" role="alert" id={`${uid}-slug-err`}>{problems.slug}</p> : null}
        </section>

        <section className="xp-section" aria-label="Quản trị viên đầu tiên">
          <h3><UserRound size={14} aria-hidden="true"/> Quản trị viên đầu tiên <span className="xp-opt-tag">Không bắt buộc</span></h3>
          <PersonPicker id="tenant-first-admin" label="Tìm người dùng" people={[...people.values()]} q={q} setQ={setQ} value={admin} onChange={setAdmin} loading={loading}
            placeholder="Tìm theo tên hoặc tên đăng nhập" emptyText={q ? "Không có người phù hợp" : "Chưa có tài khoản nào để chọn"}/>
          <LoadNote load={peopleLoad} what="danh sách tài khoản"/>
          <p className="xp-note" role="note"><ShieldCheck size={16} aria-hidden="true"/><span>Chỉ chọn được tài khoản đã có. Muốn tạo người mới: bỏ trống ở đây, rồi dùng nút “Tạo tài khoản quản trị công ty” ở trang công ty vừa tạo (người đó nhận một liên kết kích hoạt).</span></p>
        </section>

        {error ? <p className="formError" role="alert">{error}</p> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" disabled={busy}>{busy ? "Đang tạo…" : "Tạo công ty"}</button></div>
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
  const A = useA();
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

/** also used by the SYSTEM_ADMIN's workspace page: the same calls, the same rules (`MemberController`). The panel opens only when the server lists MEMBER_MANAGE for this workspace. */
export function WorkspaceMembers({ workspaceId, name }: { workspaceId: string; name: string }) {
  const { me } = useSession();
  if (!canManageWorkspaceMembers(me, workspaceId)) return (
    <Card title={`Thành viên của ${name}`}>
      <StateView kind="forbidden" title="Bạn không quản lý thành viên workspace này" detail={<p data-testid="ws-members-forbidden">Quản trị hệ thống và quản trị công ty không tự có quyền quản lý thành viên workspace. Hãy chọn workspace và vai trò khi <b>tạo tài khoản</b>, hoặc nhờ quản trị viên của workspace thêm người.</p>}/>
    </Card>);
  return <WorkspaceMembersPanel workspaceId={workspaceId} name={name}/>;
}

function WorkspaceMembersPanel({ workspaceId, name }: { workspaceId: string; name: string }) {
  const { me } = useSession();
  const members = useLoad(() => api.listWorkspaceMembers(workspaceId), [workspaceId]);
  const [who, setWho] = useState(""); const [role, setRole] = useState("EDITOR");
  const [busy, setBusy] = useState<string | null>(null); const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const list = members.data ?? [];
  const [draft, setDraft] = useState<Record<string, string>>({});   // chosen in the select, applied by "Lưu"
  // single-flight: a double click on "Thêm" / "Gỡ" runs ONE request (a second call while one is running is skipped) (M-020)
  const flight = useAction(async (_c, fn: () => Promise<unknown>) => { await fn(); });
  async function act(key: string, fn: () => Promise<unknown>, ok: string) {
    if (flight.busy) return;
    setBusy(key); setMsg(null);
    const r = await flight.run(fn);
    if (r.status === "skipped") return;
    if (r.status === "ok") { setMsg({ kind: "ok", text: ok }); members.reload(); } else if (r.status === "error") setMsg({ kind: "err", text: say(r.error, "Chưa thực hiện được.") });
    setBusy(null);
  }
  async function saveRole(m: Member) {
    const next = draft[m.userId]; if (!next || next === m.role) return;
    const block = workspaceMemberBlock(m, { id: me!.id }, list, next);
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (isWorkspaceAdminRole(next) || isWorkspaceAdminRole(m.role)) {
      const up = isWorkspaceAdminRole(next); const who = m.displayName ?? m.username;
      if (!(await confirm({ title: up ? `Cho ${who} làm quản trị workspace?` : `Bỏ quyền quản trị workspace của ${who}?`, message: up ? `Người này sẽ quản lý được thành viên và nguồn dữ liệu của workspace “${name}”.` : `Người này không còn quản lý được thành viên và nguồn dữ liệu của workspace “${name}”.`, confirmLabel: up ? "Cho làm quản trị" : "Bỏ quyền quản trị", danger: !up }))) return;
    }
    await act(`role:${m.userId}`, () => api.changeWorkspaceMember(workspaceId, m.userId, next), "Đã đổi vai trò.");
    setDraft((d) => { const n = { ...d }; delete n[m.userId]; return n; });
  }
  async function change(m: Member, next: string) {
    const block = workspaceMemberBlock(m, { id: me!.id }, list, next);
    if (block) { setMsg({ kind: "err", text: block }); return; }
    if (next === "REMOVE") { if (!(await confirm({ title: `Gỡ ${m.displayName ?? m.username} khỏi workspace?`, message: `Họ cũng mất quyền ở mọi ứng dụng của workspace “${name}”.`, confirmLabel: "Gỡ khỏi workspace", danger: true }))) return; void act(`rm:${m.userId}`, () => api.removeWorkspaceMember(workspaceId, m.userId), "Đã gỡ khỏi workspace."); }
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
          <thead><tr><th>Người dùng</th><th>Vai trò</th><th>Tham gia</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{list.map((m) => (
            <tr key={m.userId} data-testid={`wm:${m.username}`}>
              <td><b>{m.displayName ?? m.username}</b><small>{m.username}{m.email ? ` · ${m.email}` : ""}{m.userId === me?.id ? " · bạn" : ""}</small></td>
              <td><span className="row"><select aria-label={`Vai trò của ${m.username}`} value={draft[m.userId] ?? m.role} disabled={busy !== null || m.userId === me?.id} onChange={(e) => setDraft((d) => ({ ...d, [m.userId]: e.target.value }))}>
                {WORKSPACE_ROLES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}{WORKSPACE_ROLES.some((x) => x.id === m.role) ? null : <option value={m.role}>{workspaceRoleLabel(m.role)}</option>}</select>
                {draft[m.userId] && draft[m.userId] !== m.role ? <><button className="btn sm primary" data-testid={`wm-save:${m.username}`} disabled={busy !== null} onClick={() => void saveRole(m)}>Lưu</button><button className="btn sm" disabled={busy !== null} onClick={() => setDraft((d) => { const n = { ...d }; delete n[m.userId]; return n; })}>Hủy</button></> : null}</span></td>
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
      <DataSourcesPanel key={id} headingLevel={2} doc={{ page: "", sections: [] } as never} calls={calls} canView={perms.includes("DATA_SOURCE_VIEW") || perms.includes("DATA_SOURCE_MANAGE")} viewReason="Bạn chưa được cấp quyền xem nguồn dữ liệu."
        canManage={perms.includes("DATA_SOURCE_MANAGE")} manageReason="Bạn chưa được cấp quyền quản lý nguồn dữ liệu." canBind={false} bindReason="Liên kết khe dữ liệu được thực hiện trong Studio."/>
    </Card>
  </>);
}
