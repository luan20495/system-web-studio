"use client";
/**
 * Employee directory: search, filter by organization unit and status, pagination, create (the existing tenant provisioning dialog + organization fields), and a detail view with the
 * organization / position / workspace / effective-permission / status sections. Presentational: it talks to an `OrganizationApi` and a `ProvisioningApi` only.
 * The tenant is the session's (fixed, read-only) or one of the caller's OWN tenants — there is never a free tenant id. Organization metadata is not a permission and the screen says so.
 */
import { useEffect, useMemo, useState } from "react";
import { Info, ModalHeader, Search, UserRound, Users, X, ReasonButton } from "@xweb/ui";
import { Modal } from "./Modal";
import { Card, StateView } from "../ui";
import { useLoad } from "../useLoad";
import { CreateAccountDialog, type Option } from "./ProvisioningScreens";
import { NotReadyPanel, type Tenant } from "./OrganizationScreens";
import type { ProvisioningApi, ProvisionResult } from "./provisioning";
import type { ProvisioningPlan } from "./provisioningModel";
import { tenantRoleLabel, initials } from "./adminModel";
import type { Employee, EmployeePage, EmployeeQuery, EmployeeStatus, OrganizationApi, OrgUnit, Position } from "./organization";
import { EMPLOYEE_PAGE_SIZE, buildTree, employeeName, flattenTree, orgProblem, pageCount, pathResolver, unitPath, type OrgProblem, type OrganizationPlan } from "./organizationModel";
import { TenantSwitch } from "./shared/TenantSwitch";

export type EmployeeProvisioning = { api: ProvisioningApi; plan: ProvisioningPlan; workspacesOf: (tenantId: string) => Option[]; tenants: Option[] };

const Avatar = ({ e }: { e: Employee }) => <span className="xp-avatar" aria-hidden="true">{initials(e)}</span>;
const StatusPill = ({ active }: { active: boolean }) => (active ? <span className="pill pill-ok">Hoạt động</span> : <span className="pill pill-muted" data-testid="status-off">Đã tắt</span>);
function Problem({ p }: { p: OrgProblem }) { return <p className={p.kind === "not-ready" ? "notice" : "formError"} role="alert" data-testid="emp-problem" data-kind={p.kind}>{p.text}</p>; }

/** units as an indented, flat list for a <select> */
function unitOptions(units: readonly OrgUnit[]): { id: string; label: string }[] {
  return flattenTree(buildTree(units)).map((n) => ({ id: n.unit.id, label: `${"  ".repeat(n.depth)}${n.depth ? "└ " : ""}${n.unit.name}${n.unit.enabled ? "" : " (đã tắt)"}` }));
}

export function EmployeesView({ api, plan, tenant, onTenant, prov, canToggleStatus = false }: { api: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; onTenant?: (id: string) => void; prov: EmployeeProvisioning; canToggleStatus?: boolean }) {
  const access = plan.employeeAccess.granted;
  const [qText, setQText] = useState(""); const [q, setQ] = useState(""); const [unit, setUnit] = useState(""); const [status, setStatus] = useState<EmployeeStatus | "ALL">("ALL"); const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false); const [detail, setDetail] = useState<Employee | null>(null); const [rev, setRev] = useState(0);
  // debounce: apply the text 250 ms after the last keystroke, and only if it CHANGED (otherwise the first tick after mount would reset the page the person just chose)
  useEffect(() => { if (qText.trim() === q) return; const t = setTimeout(() => { setQ(qText.trim()); setPage(0); }, 250); return () => clearTimeout(t); }, [qText, q]);
  useEffect(() => { setPage(0); setUnit(""); }, [tenant.id]);
  const orgAware = plan.directory === "directory" && plan.units.state === "ready";
  const units = useLoad(async (): Promise<OrgUnit[]> => (access && orgAware ? api.listOrganizationUnits(tenant.id) : []), [tenant.id, access, orgAware]);
  const positions = useLoad(async (): Promise<Position[]> => (access && plan.positions.state === "ready" ? api.listPositions(tenant.id) : []), [tenant.id, access, plan.positions.state]);
  const query: EmployeeQuery = useMemo(() => ({ q: q || undefined, orgUnitId: unit || null, includeSubtree: true, status, page, size: EMPLOYEE_PAGE_SIZE, fresh: rev }), [q, unit, status, page, rev]);
  const list = useLoad(async (): Promise<EmployeePage | null> => (access ? api.listEmployees(tenant.id, query) : null), [tenant.id, access, query.q, query.orgUnitId, query.status, query.page, query.fresh]);
  const data = list.data; const unitList = units.data ?? [];
  // while the organization-aware list is not available the member list has no unit / position: those columns are left out instead of showing empty cells that look like errors
  const showOrg = data?.source !== "members";
  const problem = list.error ? orgProblem(list.error) : null;
  const options = useMemo(() => unitOptions(unitList), [unitList]);
  const pathOf = useMemo(() => pathResolver(unitList), [unitList]);
  if (!access) return <StateView kind="forbidden" title="Bạn chưa có quyền xem danh bạ nhân viên" detail={<p data-testid="emp-forbidden">{(plan.employeeAccess as { reason: string }).reason}</p>}/>;
  const canCreate = prov.plan.create.state === "ready";
  return (
    <div className="xp-emp" data-testid="emp">
      <div className="xp-orgBar">
        {plan.tenantChoice.length > 1 && onTenant
          ? <TenantSwitch tenants={plan.tenantChoice} value={tenant.id} onChange={onTenant} testId="emp-tenant-switch"/>
          : <label className="field xp-tenantSwitch"><span>Công ty của bạn</span><input data-testid="emp-tenant" readOnly aria-readonly="true" value={tenant.name}/></label>}
        <div className="xp-orgBarActions">
          <ReasonButton className="btn primary xp-btnIcon" data-testid="emp-create" unavailable={!canCreate} reason={(prov.plan.create as { reason?: string }).reason} onClick={() => setCreating(true)}><UserRound size={16} aria-hidden="true"/> Thêm nhân viên</ReasonButton>
        </div>
      </div>

      {!canCreate ? <p className="notice" role="note" data-testid="emp-create-not-ready">Chưa thêm được nhân viên: {(prov.plan.create as { reason?: string }).reason ?? "bạn không có quyền tạo tài khoản."}</p> : null}

      <div className="xp-empFilters" role="search">
        <span className="xp-search"><Search size={16} aria-hidden="true"/><input data-testid="emp-search" aria-label="Tìm nhân viên" placeholder="Tìm theo tên, tên đăng nhập hoặc email" autoComplete="off" value={qText} onChange={(e) => setQText(e.target.value)}/>
          {qText ? <button type="button" className="xp-clear" aria-label="Xóa tìm kiếm" onClick={() => setQText("")}><X size={14} aria-hidden="true"/></button> : null}</span>
        <label className="field"><span>Đơn vị</span>
          <select data-testid="emp-org" aria-label="Lọc theo đơn vị" value={unit} disabled={!orgAware} onChange={(e) => { setUnit(e.target.value); setPage(0); }}>
            <option value="">{orgAware ? "Tất cả đơn vị" : "Chưa sẵn sàng"}</option>{options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
        <label className="field"><span>Trạng thái</span>
          <select data-testid="emp-status" aria-label="Lọc theo trạng thái" value={status} onChange={(e) => { setStatus(e.target.value as EmployeeStatus | "ALL"); setPage(0); }}><option value="ALL">Tất cả</option><option value="ACTIVE">Hoạt động</option><option value="INACTIVE">Đã tắt</option></select></label>
      </div>

      {data?.source === "members" || (!data && plan.directory === "members") ? (
        <p className="xp-note" role="note" data-testid="emp-members-note"><Info size={16} aria-hidden="true"/><span>Danh sách lấy từ thành viên công ty. Đơn vị và vị trí chưa có. {(plan.units as { reason?: string }).reason ?? "Cơ cấu tổ chức chưa sẵn sàng."}</span></p>) : null}

      <Card className="xp-empCard">
        {problem ? <StateView kind={problem.kind === "forbidden" ? "forbidden" : problem.kind === "not-ready" ? "empty" : "error"} title={problem.kind === "forbidden" ? "Không có quyền" : problem.kind === "not-ready" ? "Danh sách nhân viên chưa sẵn sàng" : "Không tải được danh sách"} detail={<p data-testid="emp-error" data-kind={problem.kind}>{problem.text}</p>} action={problem.kind === "not-ready" ? undefined : <button className="btn" data-testid="emp-retry" onClick={list.reload}>Thử lại</button>}/>
          : list.loading && !data ? <StateView kind="loading"/>
          : data && data.items.length === 0 && data.total > 0 ? <StateView kind="empty" title="Trang này không có nhân viên" detail={<p data-testid="emp-page-empty">Danh sách đã thay đổi nên trang {data.page + 1} không còn dữ liệu (còn {data.total} nhân viên).</p>} action={<button className="btn" data-testid="emp-first-page" onClick={() => setPage(0)}>Về trang đầu</button>}/>
          : !data || data.items.length === 0 ? <StateView kind="empty" title={q || unit || status !== "ALL" ? "Không có nhân viên phù hợp" : "Chưa có nhân viên"} detail={<p data-testid="emp-empty">{q || unit || status !== "ALL" ? "Thử đổi từ khóa hoặc bộ lọc." : "Thêm nhân viên đầu tiên bằng nút “Thêm nhân viên”."}</p>}/>
          : (
            <>
              <table className="table xp-empTable" data-testid="emp-table">
                <thead><tr><th>Nhân viên</th>{showOrg ? <><th>Đơn vị</th><th>Vị trí</th></> : null}<th>Vai trò công ty</th><th>Trạng thái</th></tr></thead>
                <tbody>{data.items.map((e) => (
                  <tr key={e.userId} className="clickRow" data-testid={`emp:${e.userId}`} tabIndex={0} onClick={() => setDetail(e)} onKeyDown={(k) => { if (k.key === "Enter") setDetail(e); }}>
                    <td data-label="Nhân viên"><span className="xp-empName"><Avatar e={e}/><span className="xp-personText"><b>{employeeName(e)}</b><small>{e.username}{e.email ? ` · ${e.email}` : ""}</small></span></span></td>
                    {showOrg ? <><td data-label="Đơn vị">{e.orgUnitName ?? (e.orgUnitId ? pathOf(e.orgUnitId) : <span className="hint">Chưa gán</span>)}</td>
                    <td data-label="Vị trí">{e.positionName ?? <span className="hint">Chưa gán</span>}</td></> : null}
                    <td data-label="Vai trò công ty">{tenantRoleLabel(e.tenantRole)}</td>
                    <td data-label="Trạng thái"><StatusPill active={e.active}/></td>
                  </tr>))}</tbody>
              </table>
              <div className="xp-pager" data-testid="emp-pager">
                <span data-testid="emp-count">Trang {data.page + 1}/{pageCount(data)} · {data.total} nhân viên</span>
                <div className="row"><button className="btn sm" data-testid="emp-prev" disabled={data.page <= 0} onClick={() => setPage(data.page - 1)}>Trước</button><button className="btn sm" data-testid="emp-next" disabled={data.page + 1 >= pageCount(data)} onClick={() => setPage(data.page + 1)}>Sau</button></div>
              </div>
            </>)}
      </Card>

      {creating ? <CreateEmployeeDialog org={api} plan={plan} tenant={tenant} units={unitList} positions={positions.data ?? []} prov={prov} onClose={() => setCreating(false)} onCreated={() => setRev((r) => r + 1)}/> : null}
      {detail ? <EmployeeDetail org={api} plan={plan} tenant={tenant} employee={detail} units={unitList} positions={positions.data ?? []} canToggleStatus={canToggleStatus} onClose={() => setDetail(null)} onChanged={(e) => { setDetail(e); setRev((r) => r + 1); }}/> : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- create
function CreateEmployeeDialog({ org, plan, tenant, units, positions, prov, onClose, onCreated }: { org: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; units: OrgUnit[]; positions: Position[]; prov: EmployeeProvisioning; onClose: () => void; onCreated: () => void }) {
  const [unit, setUnit] = useState(""); const [pos, setPos] = useState("");
  const options = useMemo(() => unitOptions(units), [units]);
  const orgReady = plan.assignOrg.state === "ready" && plan.units.state === "ready"; const posReady = plan.assignPosition.state === "ready" && plan.positions.state === "ready";
  const reason = !orgReady ? (plan.assignOrg as { reason?: string }).reason ?? (plan.units as { reason?: string }).reason : !posReady ? (plan.assignPosition as { reason?: string }).reason ?? (plan.positions as { reason?: string }).reason : null;
  async function afterCreate(r: ProvisionResult): Promise<string | null> {
    const failed: string[] = [];
    if (unit && orgReady) { try { await org.updateEmployeeOrganization(tenant.id, r.user.id, unit); } catch (e) { failed.push(`đơn vị: ${orgProblem(e).text}`); } }
    if (pos && posReady) { try { await org.updateEmployeePosition(tenant.id, r.user.id, pos); } catch (e) { failed.push(`vị trí: ${orgProblem(e).text}`); } }
    return failed.length ? `Tài khoản đã tạo nhưng chưa gán ${failed.join("; ")}. Gán lại trong chi tiết nhân viên.` : null;
  }
  const extra = (
    <fieldset className="stack" aria-label="Cơ cấu tổ chức" data-testid="emp-org-fields"><legend className="bx-h4">Cơ cấu tổ chức (không bắt buộc)</legend>
      <label className="field"><span>Đơn vị</span>
        <select data-testid="emp-new-unit" value={unit} disabled={!orgReady} onChange={(e) => setUnit(e.target.value)}><option value="">Chưa gán đơn vị</option>{options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
      <label className="field"><span>Vị trí / cấp bậc</span>
        <select data-testid="emp-new-position" value={pos} disabled={!posReady} onChange={(e) => setPos(e.target.value)}><option value="">Chưa gán vị trí</option>{positions.map((p) => <option key={p.id} value={p.id}>{p.name}{p.level != null ? ` (cấp ${p.level})` : ""}</option>)}</select></label>
      {reason ? <p className="notice" role="note" data-testid="emp-org-not-ready">Chưa sẵn sàng: {reason} Tài khoản vẫn tạo được; gán đơn vị và vị trí sau.</p> : null}
    </fieldset>);
  return <CreateAccountDialog api={prov.api} plan={prov.plan} tenants={prov.tenants} workspacesOf={prov.workspacesOf} onClose={onClose} onCreated={onCreated} title="Thêm nhân viên" submitLabel="Thêm nhân viên" extraSection={extra} afterCreate={afterCreate}/>;
}

// ------------------------------------------------------------------------------------------------------------------------------- detail
function EmployeeDetail({ org, plan, tenant, employee, units, positions, canToggleStatus, onClose, onChanged }: { org: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; employee: Employee; units: OrgUnit[]; positions: Position[]; canToggleStatus: boolean; onClose: () => void; onChanged: (e: Employee) => void }) {
  const [unit, setUnit] = useState(employee.orgUnitId ?? ""); const [pos, setPos] = useState(employee.positionId ?? "");
  const [busy, setBusy] = useState<string | null>(null); const [problem, setProblem] = useState<OrgProblem | null>(null); const [ok, setOk] = useState<string | null>(null);
  const options = useMemo(() => unitOptions(units), [units]);
  const orgReady = plan.assignOrg.state === "ready" && plan.units.state === "ready"; const posReady = plan.assignPosition.state === "ready" && plan.positions.state === "ready";
  const orgReason = (plan.assignOrg as { reason?: string }).reason ?? (plan.units as { reason?: string }).reason ?? "Chưa sẵn sàng."; const posReason = (plan.assignPosition as { reason?: string }).reason ?? (plan.positions as { reason?: string }).reason ?? "Chưa sẵn sàng.";
  /** the same reason twice in one dialog is noise (M-095): one note covers both */
  const bothNotReady = !orgReady && !posReady && orgReason === posReason;
  async function save(key: "org" | "pos") {
    setBusy(key); setProblem(null); setOk(null);
    try { const e = key === "org" ? await org.updateEmployeeOrganization(tenant.id, employee.userId, unit || null) : await org.updateEmployeePosition(tenant.id, employee.userId, pos || null); setOk(key === "org" ? "Đã lưu đơn vị." : "Đã lưu vị trí."); onChanged(e); } catch (e) { setProblem(orgProblem(e)); } finally { setBusy(null); }
  }
  return (
    <Modal label="Chi tiết nhân viên" onClose={onClose}>
      <div className="modalBody xp-empDetail" data-testid="emp-detail">
        <ModalHeader icon={<Users size={22}/>} title={employeeName(employee)} subtitle={`${employee.username}${employee.email ? ` · ${employee.email}` : ""}`}/>
        <section className="xp-section" aria-label="Thông tin"><h3>Thông tin</h3>
          <dl className="kv"><div><dt>Tên đăng nhập</dt><dd>{employee.username}</dd></div><div><dt>Tên hiển thị</dt><dd>{employee.displayName ?? "—"}</dd></div><div><dt>Email</dt><dd>{employee.email ?? "—"}</dd></div></dl></section>
        <section className="xp-section" aria-label="Cơ cấu tổ chức"><h3>Cơ cấu tổ chức</h3>
          <p data-testid="detail-org">{employee.orgUnitId ? unitPath(units, employee.orgUnitId) || employee.orgUnitName : "Chưa gán đơn vị"}</p>
          <label className="field"><span>Đơn vị</span><select data-testid="detail-unit" value={unit} disabled={!orgReady || busy !== null} onChange={(e) => setUnit(e.target.value)}><option value="">Chưa gán đơn vị</option>{options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
          {orgReady ? <div className="row"><button className="btn sm primary" data-testid="detail-unit-save" disabled={busy !== null || unit === (employee.orgUnitId ?? "")} onClick={() => void save("org")}>{busy === "org" ? "Đang lưu…" : "Lưu đơn vị"}</button></div>
            : bothNotReady ? null : <NotReadyPanel testid="detail-org-not-ready" title="Chưa gán được đơn vị" reason={orgReason}/>}</section>
        <section className="xp-section" aria-label="Vị trí / Cấp bậc"><h3>Vị trí / Cấp bậc</h3>
          <p data-testid="detail-position">{employee.positionName ?? "Chưa gán vị trí"}</p>
          <label className="field"><span>Vị trí</span><select data-testid="detail-pos" value={pos} disabled={!posReady || busy !== null} onChange={(e) => setPos(e.target.value)}><option value="">Chưa gán vị trí</option>{positions.map((p) => <option key={p.id} value={p.id}>{p.name}{p.level != null ? ` (cấp ${p.level})` : ""}</option>)}</select></label>
          {posReady ? <div className="row"><button className="btn sm primary" data-testid="detail-pos-save" disabled={busy !== null || pos === (employee.positionId ?? "")} onClick={() => void save("pos")}>{busy === "pos" ? "Đang lưu…" : "Lưu vị trí"}</button></div>
            : bothNotReady ? <NotReadyPanel testid="detail-assign-not-ready" title="Chưa gán được đơn vị và vị trí" reason={orgReason}/> : <NotReadyPanel testid="detail-pos-not-ready" title="Chưa gán được vị trí" reason={(plan.assignPosition as { reason?: string }).reason ?? (plan.positions as { reason?: string }).reason ?? "Chưa sẵn sàng."}/>}</section>
        <section className="xp-section" aria-label="Workspace"><h3>Workspace</h3>
          {employee.workspaces?.length ? <ul className="xp-wsList" data-testid="detail-workspaces">{employee.workspaces.map((w) => <li key={w.id}><b>{w.name}</b> <small>{w.role}</small></li>)}</ul>
            : <p className="hint" data-testid="detail-workspaces-na">Danh sách workspace của nhân viên chưa có trong dữ liệu máy chủ. Quản lý thành viên workspace ở mục “Workspace của tôi”.</p>}</section>
        <section className="xp-section" aria-label="Quyền hiệu lực"><h3>Quyền hiệu lực</h3>
          <dl className="kv"><div><dt>Vai trò công ty</dt><dd data-testid="detail-role">{tenantRoleLabel(employee.tenantRole)}</dd></div></dl>
          <p className="xp-note" role="note" data-testid="detail-perm-note"><Info size={16} aria-hidden="true"/><span>Đơn vị và vị trí chỉ để tổ chức, <b>không cấp quyền</b>. Quyền do máy chủ quyết định theo vai trò công ty và vai trò trong từng workspace.</span></p></section>
        <section className="xp-section" aria-label="Trạng thái"><h3>Trạng thái</h3>
          <div className="row"><StatusPill active={employee.active}/>
            {/* the account switch is an account-level action (Platform → Người dùng); this list is the company's MEMBER list, so it is never offered here, and never as a dead button */}
            <ReasonButton className="btn sm" data-testid="detail-toggle" unavailable reason={canToggleStatus ? "Chưa sẵn sàng: bật hoặc tắt tài khoản nhân viên sẽ có khi danh bạ được kết nối với máy chủ." : "Bạn cần quyền quản lý nhân viên và quản lý thành viên công ty để bật hoặc tắt tài khoản nhân viên."}>{employee.active ? "Tắt tài khoản" : "Bật tài khoản"}</ReasonButton></div>
          </section>
        {ok ? <p className="notice" role="status" data-testid="detail-ok">{ok}</p> : null}
        {problem ? <Problem p={problem}/> : null}
        <div className="xp-footer"><button className="btn" onClick={onClose}>Đóng</button></div>
      </div>
    </Modal>
  );
}
