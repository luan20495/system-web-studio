"use client";
/**
 * Employee directory over the real organization API: search (at least 2 characters), filters by unit (with or without the sub-units), position, grade and status, a pager that never asks the server for more than
 * it accepts (size <= 100, page * size <= 10 000: beyond that the person is told to narrow the search), create (the tenant provisioning dialog + the organization fields, sent in ONE request), and a detail view
 * with the memberships (an employee may belong to several units, exactly one is primary), the positions held WITHIN each membership (with an optional grade), and enable / disable.
 * Presentational: it talks to an `OrganizationApi` and a `ProvisioningApi` only. The tenant is the session's (fixed, read-only) or one of the caller's OWN tenants: there is never a free tenant id.
 * A relation (MEMBER / MANAGER / HEAD), a position and a grade are business labels: the screen says so and nothing here derives a permission from them.
 */
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Info, ModalHeader, Search, UserRound, Users, X, ReasonButton, Briefcase } from "@xweb/ui";
import type { ActivationLink } from "@xweb/types";
import { Modal } from "./Modal";
import { Card, StateView } from "../ui";
import { useLoad } from "../useLoad";
import { CreateAccountDialog, type Option } from "./ProvisioningScreens";
import { NotReadyPanel, type Tenant } from "./OrganizationScreens";
import { CatalogDialog } from "./OrganizationCatalog";
import { OrgConfirm, Problem, useOrgAction } from "./orgParts";
import type { ProvisioningApi, ProvisionResult } from "./provisioning";
import type { ProvisioningPlan } from "./provisioningModel";
import { tenantRoleLabel, initials } from "./adminModel";
import type { Employee, EmployeePage, EmployeeQuery, EmployeeStatus, Grade, OrganizationApi, OrgUnit, Position } from "./organization";
import {
  EMPLOYEE_PAGE_SIZE, PAGING_LIMITS, buildTree, employeeName, flattenTree, lastReachablePage, membershipRows, normalizeRelation, orgProblem, pageCount, pagingCapped, pathResolver, relationError, searchState,
  type MembershipRow, type OrgProblem, type OrganizationPlan,
} from "./organizationModel";
import { TenantSwitch } from "./shared/TenantSwitch";

export type EmployeeProvisioning = { api: ProvisioningApi; plan: ProvisioningPlan; workspacesOf: (tenantId: string) => Option[]; tenants: Option[] };

const Avatar = ({ e }: { e: Pick<Employee, "username" | "displayName"> }) => <span className="xp-avatar" aria-hidden="true">{initials(e)}</span>;
/** `active` is the company membership; `accountActivated` says whether the person has set a password yet */
const StatusPill = ({ e }: { e: Pick<Employee, "active" | "accountActivated"> }) => (!e.active ? <span className="pill pill-muted" data-testid="status-off">Đã tắt</span> : !e.accountActivated ? <span className="pill pill-warn" data-testid="status-pending">Chờ kích hoạt</span> : <span className="pill pill-ok" data-testid="status-on">Hoạt động</span>);

/** active units as an indented, flat list for a <select> (archived units are not offered: nobody can be put in one) */
function unitOptions(units: readonly OrgUnit[]): { id: string; label: string }[] {
  return flattenTree(buildTree(units.filter((u) => u.active))).map((n) => ({ id: n.unit.id, label: `${"  ".repeat(n.depth)}${n.depth ? "└ " : ""}${n.unit.name}` }));
}
const NAME_HIDDEN = "(không xem được tên đơn vị)";

export function EmployeesView({ api, plan, tenant, onTenant, prov }: { api: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; onTenant?: (id: string) => void; prov: EmployeeProvisioning }) {
  const access = plan.employeeAccess.granted;
  const [qText, setQText] = useState(""); const [q, setQ] = useState(""); const [unit, setUnit] = useState(""); const [sub, setSub] = useState(true); const [posId, setPosId] = useState(""); const [gradeId, setGradeId] = useState("");
  const [status, setStatus] = useState<EmployeeStatus | "ALL">("ALL"); const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false); const [catalog, setCatalog] = useState(false); const [detail, setDetail] = useState<Employee | null>(null); const [rev, setRev] = useState(0);
  // debounce: apply the text 250 ms after the last keystroke, and only if it CHANGED (otherwise the first tick after mount would reset the page the person just chose)
  useEffect(() => { if (qText.trim() === q) return; const t = setTimeout(() => { setQ(qText.trim()); setPage(0); }, 250); return () => clearTimeout(t); }, [qText, q]);
  useEffect(() => { setPage(0); setUnit(""); setPosId(""); setGradeId(""); }, [tenant.id]);
  // the unit names need ORG_STRUCTURE_VIEW, positions and grades need POSITION_GRADE_VIEW: without them the directory still works and says what it cannot show
  const unitsReady = access && plan.access.granted && plan.units.state === "ready";
  const catalogReady = access && plan.positions.state === "ready";
  const units = useLoad(async (): Promise<OrgUnit[]> => (unitsReady ? api.listOrganizationUnits(tenant.id) : []), [tenant.id, unitsReady]);
  const catalogData = useLoad(async (): Promise<{ positions: Position[]; grades: Grade[] }> => (catalogReady ? { positions: await api.listPositions(tenant.id), grades: await api.listGrades(tenant.id) } : { positions: [], grades: [] }), [tenant.id, catalogReady]);
  const search = searchState(qText); const sent = searchState(q).send;
  const query: EmployeeQuery = useMemo(() => ({ q: sent, orgUnitId: unit || null, includeSubtree: sub, positionId: posId || null, gradeId: gradeId || null, status, page, size: EMPLOYEE_PAGE_SIZE, sort: "name", dir: "asc" }), [sent, unit, sub, posId, gradeId, status, page]);
  const list = useLoad(async (): Promise<EmployeePage | null> => (access ? api.listEmployees(tenant.id, query) : null), [tenant.id, access, query.q, query.orgUnitId, query.includeSubtree, query.positionId, query.gradeId, query.status, query.page, rev]);
  const data = list.data; const unitList = units.data ?? []; const positions = catalogData.data?.positions ?? []; const grades = catalogData.data?.grades ?? [];
  const problem = list.error ? orgProblem(list.error) : null;
  const options = useMemo(() => unitOptions(unitList), [unitList]);
  const pathOf = useMemo(() => pathResolver(unitList), [unitList]);
  const filtered = !!(sent || unit || posId || gradeId || status !== "ALL");
  if (!access) return <StateView kind="forbidden" title="Bạn chưa có quyền xem danh bạ nhân viên" detail={<p data-testid="emp-forbidden">{(plan.employeeAccess as { reason: string }).reason}</p>}/>;
  const canCreate = prov.plan.create.state === "ready" && plan.employeeCreate.state === "ready";
  const createReason = plan.employeeCreate.state !== "ready" ? (plan.employeeCreate as { reason: string }).reason : (prov.plan.create as { reason?: string }).reason;
  return (
    <div className="xp-emp" data-testid="emp">
      <div className="xp-orgBar">
        {plan.tenantChoice.length > 1 && onTenant
          ? <TenantSwitch tenants={plan.tenantChoice} value={tenant.id} onChange={onTenant} testId="emp-tenant-switch"/>
          : <label className="field xp-tenantSwitch"><span>Công ty của bạn</span><input data-testid="emp-tenant" readOnly aria-readonly="true" value={tenant.name}/></label>}
        <div className="xp-orgBarActions">
          {plan.positions.state !== "no-permission" ? <button className="btn xp-btnIcon" data-testid="emp-catalog" onClick={() => setCatalog(true)}><Briefcase size={16} aria-hidden="true"/> Vị trí &amp; cấp bậc</button> : null}
          <ReasonButton className="btn primary xp-btnIcon" data-testid="emp-create" unavailable={!canCreate} reason={createReason} onClick={() => setCreating(true)}><UserRound size={16} aria-hidden="true"/> Thêm nhân viên</ReasonButton>
        </div>
      </div>

      {!canCreate ? <p className="notice" role="note" data-testid="emp-create-not-ready">Chưa thêm được nhân viên: {createReason ?? "bạn không có quyền tạo tài khoản."}</p> : null}

      <div className="xp-empFilters" role="search">
        <span className="xp-search"><Search size={16} aria-hidden="true"/><input data-testid="emp-search" aria-label="Tìm nhân viên" aria-describedby={search.hint ? "emp-search-hint" : undefined} placeholder="Tìm theo tên, tên đăng nhập hoặc email" autoComplete="off" value={qText} onChange={(e) => setQText(e.target.value)}/>
          {qText ? <button type="button" className="xp-clear" aria-label="Xóa tìm kiếm" onClick={() => setQText("")}><X size={14} aria-hidden="true"/></button> : null}</span>
        <label className="field"><span>Đơn vị</span>
          <select data-testid="emp-org" aria-label="Lọc theo đơn vị" value={unit} disabled={!unitsReady} onChange={(e) => { setUnit(e.target.value); setPage(0); }}>
            <option value="">{unitsReady ? "Tất cả đơn vị" : "Không xem được danh sách đơn vị"}</option>{options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
        <label className="xp-check"><input type="checkbox" data-testid="emp-subtree" checked={sub} disabled={!unit} onChange={(e) => { setSub(e.target.checked); setPage(0); }}/> Gồm đơn vị con</label>
        <label className="field"><span>Vị trí</span>
          <select data-testid="emp-position" aria-label="Lọc theo vị trí" value={posId} disabled={!catalogReady} onChange={(e) => { setPosId(e.target.value); setPage(0); }}>
            <option value="">{catalogReady ? "Tất cả vị trí" : "Không xem được danh sách"}</option>{positions.map((p) => <option key={p.id} value={p.id}>{p.name}{p.active ? "" : " (đã tắt)"}</option>)}</select></label>
        <label className="field"><span>Cấp bậc</span>
          <select data-testid="emp-grade" aria-label="Lọc theo cấp bậc" value={gradeId} disabled={!catalogReady} onChange={(e) => { setGradeId(e.target.value); setPage(0); }}>
            <option value="">{catalogReady ? "Tất cả cấp bậc" : "Không xem được danh sách"}</option>{grades.map((g) => <option key={g.id} value={g.id}>{g.name}{g.active ? "" : " (đã tắt)"}</option>)}</select></label>
        <label className="field"><span>Trạng thái</span>
          <select data-testid="emp-status" aria-label="Lọc theo trạng thái" value={status} onChange={(e) => { setStatus(e.target.value as EmployeeStatus | "ALL"); setPage(0); }}><option value="ALL">Tất cả</option><option value="ACTIVE">Hoạt động</option><option value="INACTIVE">Đã tắt</option></select></label>
      </div>
      {search.hint ? <p className="hint" id="emp-search-hint" role="status" data-testid="emp-search-hint">{search.hint}</p> : null}

      <Card className="xp-empCard">
        {problem ? <StateView kind={problem.kind === "forbidden" ? "forbidden" : problem.kind === "unavailable-feature" ? "empty" : "error"} title={problem.kind === "forbidden" ? "Không có quyền" : problem.kind === "unavailable-feature" ? "Danh bạ nhân viên chưa khả dụng" : problem.kind === "busy" ? "Hệ thống đang bận" : "Không tải được danh sách"} detail={<p data-testid="emp-error" data-kind={problem.kind} data-code={problem.code}>{problem.text}</p>} action={<button className="btn" data-testid="emp-retry" onClick={list.reload}>{problem.kind === "unavailable-feature" ? "Kiểm tra lại" : "Thử lại"}</button>}/>
          : list.loading && !data ? <StateView kind="loading"/>
          : data && data.items.length === 0 && data.total > 0 ? <StateView kind="empty" title="Trang này không có nhân viên" detail={<p data-testid="emp-page-empty">Danh sách đã thay đổi nên trang {data.page + 1} không còn dữ liệu (còn {data.total} nhân viên).</p>} action={<button className="btn" data-testid="emp-first-page" onClick={() => setPage(0)}>Về trang đầu</button>}/>
          : !data || data.items.length === 0 ? <StateView kind="empty" title={filtered ? "Không có nhân viên phù hợp" : "Chưa có nhân viên"} detail={<p data-testid="emp-empty">{filtered ? "Thử đổi từ khóa hoặc bộ lọc." : "Thêm nhân viên đầu tiên bằng nút “Thêm nhân viên”."}</p>}/>
          : (
            <>
              <table className="table xp-empTable" data-testid="emp-table">
                <thead><tr><th>Nhân viên</th><th>Đơn vị</th><th>Vị trí</th><th>Vai trò công ty</th><th>Trạng thái</th></tr></thead>
                <tbody>{data.items.map((e) => <EmployeeRow key={e.userId} e={e} units={unitList} positions={positions} grades={grades} pathOf={pathOf} unitsKnown={unitsReady} onOpen={() => setDetail(e)}/>)}</tbody>
              </table>
              <div className="xp-pager" data-testid="emp-pager">
                <span data-testid="emp-count">Trang {data.page + 1}/{pageCount(data)} · {data.total} nhân viên</span>
                <div className="row"><button className="btn sm" data-testid="emp-prev" disabled={data.page <= 0} onClick={() => setPage(data.page - 1)}>Trước</button><button className="btn sm" data-testid="emp-next" disabled={data.page >= lastReachablePage(data)} onClick={() => setPage(data.page + 1)}>Sau</button></div>
              </div>
              {pagingCapped(data) ? <p className="notice" role="note" data-testid="emp-paging-capped">Chỉ xem được {PAGING_LIMITS.maxOffset.toLocaleString("vi-VN")} kết quả đầu tiên. Hãy thu hẹp tìm kiếm (từ khóa, đơn vị, vị trí, cấp bậc) thay vì chuyển trang sâu hơn.</p> : null}
            </>)}
      </Card>

      {creating ? <CreateEmployeeDialog org={api} plan={plan} tenant={tenant} units={unitList} positions={positions} grades={grades} prov={prov} onClose={() => setCreating(false)} onCreated={() => setRev((r) => r + 1)}/> : null}
      {detail ? <EmployeeDetail org={api} plan={plan} tenant={tenant} employee={detail} units={unitList} positions={positions} grades={grades} unitsKnown={unitsReady} onClose={() => setDetail(null)} onChanged={() => setRev((r) => r + 1)}/> : null}
      {catalog ? <CatalogDialog tenantId={tenant.id} api={api} plan={plan} onClose={() => { setCatalog(false); catalogData.reload(); }}/> : null}
    </div>
  );
}

function EmployeeRow({ e, units, positions, grades, pathOf, unitsKnown, onOpen }: { e: Employee; units: OrgUnit[]; positions: Position[]; grades: Grade[]; pathOf: (id: string) => string; unitsKnown: boolean; onOpen: () => void }) {
  const rows = membershipRows(e, units, positions, grades, pathOf); const first = rows[0];
  const unitText = !first ? null : unitsKnown ? first.unitPath || NAME_HIDDEN : NAME_HIDDEN;
  const all = rows.map((r) => (unitsKnown ? r.unitPath : "") || NAME_HIDDEN).join("; ");
  const held = rows.flatMap((r) => r.positions); const firstPos = held[0];
  return (
    <tr className="clickRow" data-testid={`emp:${e.userId}`} tabIndex={0} onClick={onOpen} onKeyDown={(k) => { if (k.key === "Enter") onOpen(); }}>
      <td data-label="Nhân viên"><span className="xp-empName"><Avatar e={e}/><span className="xp-personText"><b>{employeeName(e)}</b><small>{e.username}{e.email ? ` · ${e.email}` : ""}</small></span></span></td>
      <td data-label="Đơn vị" data-testid={`emp-unit:${e.userId}`} title={all || undefined}>{unitText ? <>{unitText}{rows.length > 1 ? <small className="hint"> +{rows.length - 1} đơn vị khác</small> : null}</> : <span className="hint">Chưa gán</span>}</td>
      <td data-label="Vị trí" data-testid={`emp-position:${e.userId}`}>{firstPos ? <>{firstPos.positionName}{firstPos.gradeName ? <small className="hint"> · {firstPos.gradeName}</small> : null}{held.length > 1 ? <small className="hint"> +{held.length - 1}</small> : null}</> : <span className="hint">Chưa gán</span>}</td>
      <td data-label="Vai trò công ty">{tenantRoleLabel(e.tenantRole)}</td>
      <td data-label="Trạng thái"><StatusPill e={e}/></td>
    </tr>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- create
function CreateEmployeeDialog({ org, plan, tenant, units, positions, grades, prov, onClose, onCreated }: { org: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; units: OrgUnit[]; positions: Position[]; grades: Grade[]; prov: EmployeeProvisioning; onClose: () => void; onCreated: () => void }) {
  const [unit, setUnit] = useState(""); const [pos, setPos] = useState(""); const [grade, setGrade] = useState("");
  const options = useMemo(() => unitOptions(units), [units]);
  const unitsOk = options.length > 0; const posOk = positions.some((p) => p.active) && plan.positions.state === "ready";
  /** the organization fields go in the SAME request as the account (one transaction on the server): either the employee exists with them, or nothing was created */
  const api: ProvisioningApi = {
    ...prov.api,
    async createTenantUser(tenantId, a) {
      const made = await org.createEmployee(tenantId, {
        username: a.username, displayName: a.displayName, ...(a.email ? { email: a.email } : {}), tenantRole: a.tenantRole, ...(a.workspace ? { workspace: a.workspace } : {}),
        ...(unit ? { memberships: [{ unitId: unit, primary: true, ...(pos ? { positions: [{ positionId: pos, ...(grade ? { gradeId: grade } : {}), primary: true }] } : {}) }] } : {}),
      });
      const e = made.employee;
      return { tenantId, user: { id: e.userId, username: e.username, displayName: e.displayName ?? e.username }, tenantRole: a.tenantRole, workspace: a.workspace ?? null, activation: made.activation as ActivationLink | null, pending: [{ id: "activate", label: "Người dùng mở liên kết kích hoạt và đặt mật khẩu." }] };
    },
  };
  const extra = (
    <fieldset className="stack" aria-label="Cơ cấu tổ chức" data-testid="emp-org-fields"><legend className="bx-h4">Cơ cấu tổ chức (không bắt buộc)</legend>
      <label className="field"><span>Đơn vị</span>
        <select data-testid="emp-new-unit" value={unit} disabled={!unitsOk} onChange={(e) => { setUnit(e.target.value); if (!e.target.value) { setPos(""); setGrade(""); } }}><option value="">Chưa gán đơn vị</option>{options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
      <label className="field"><span>Vị trí (trong đơn vị đã chọn)</span>
        <select data-testid="emp-new-position" value={pos} disabled={!unit || !posOk} onChange={(e) => { setPos(e.target.value); if (!e.target.value) setGrade(""); }}><option value="">Chưa gán vị trí</option>{positions.filter((p) => p.active).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
      <label className="field"><span>Cấp bậc</span>
        <select data-testid="emp-new-grade" value={grade} disabled={!pos} onChange={(e) => setGrade(e.target.value)}><option value="">Chưa gán cấp bậc</option>{grades.filter((g) => g.active).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
      {!unitsOk ? <p className="notice" role="note" data-testid="emp-org-not-ready">Chưa chọn được đơn vị: công ty chưa có đơn vị đang hoạt động, hoặc bạn không có quyền xem danh sách đơn vị. Tài khoản vẫn tạo được; gán đơn vị sau.</p> : null}
      <p className="hint">Đơn vị và vị trí chỉ để tổ chức, không cấp quyền.</p>
    </fieldset>);
  return <CreateAccountDialog api={api} plan={prov.plan} tenants={prov.tenants.length ? prov.tenants : [tenant]} workspacesOf={prov.workspacesOf} onClose={onClose} onCreated={onCreated} title="Thêm nhân viên" submitLabel="Thêm nhân viên" extraSection={extra}/>;
}

// ------------------------------------------------------------------------------------------------------------------------------- detail
type Confirm =
  | { kind: "status"; active: boolean }
  | { kind: "membership"; row: MembershipRow }
  | { kind: "position"; membershipId: string; id: string; version: number; name: string };

function EmployeeDetail({ org, plan, tenant, employee, units, positions, grades, unitsKnown, onClose, onChanged }: { org: OrganizationApi; plan: OrganizationPlan; tenant: Tenant; employee: Employee; units: OrgUnit[]; positions: Position[]; grades: Grade[]; unitsKnown: boolean; onClose: () => void; onChanged: () => void }) {
  const [tick, setTick] = useState(0);
  // the row of the list may be stale: the employee is read again (and after every write), so the versions sent back are the server's current ones
  const fresh = useLoad(async () => org.getEmployee(tenant.id, employee.userId), [tenant.id, employee.userId, tick]);
  const e = fresh.data ?? employee; const loadProblem = fresh.error ? orgProblem(fresh.error) : null;
  const pathOf = useMemo(() => pathResolver(units), [units]);
  const rows = useMemo(() => membershipRows(e, units, positions, grades, pathOf), [e, units, positions, grades, pathOf]);
  const a = useOrgAction(); const [ok, setOk] = useState<string | null>(null); const [confirm, setConfirm] = useState<Confirm | null>(null);
  const orgOn = plan.assignOrg.state === "ready"; const posOn = plan.assignPosition.state === "ready"; const statusOn = plan.employeeStatus.state === "ready";
  const refresh = () => { setTick((t) => t + 1); onChanged(); };
  const write = async (words: string, fn: () => Promise<unknown>) => { setOk(null); if (await a.run(async () => { await fn(); })) { setOk(words); refresh(); } };
  const taken = new Set(rows.map((r) => r.membership.organizationUnitId));
  const free = unitOptions(units).filter((o) => !taken.has(o.id));
  return (
    <Modal label="Chi tiết nhân viên" onClose={onClose}>
      <div className="modalBody xp-empDetail" data-testid="emp-detail">
        <ModalHeader icon={<Users size={22}/>} title={employeeName(e)} subtitle={`${e.username}${e.email ? ` · ${e.email}` : ""}`}/>
        {loadProblem ? <Problem p={loadProblem} onReload={fresh.reload} onRetry={fresh.reload} reloadLabel="Tải lại nhân viên" testid="emp-detail-load-problem"/> : null}
        <section className="xp-section" aria-label="Thông tin"><h3>Thông tin</h3>
          <dl className="kv"><div><dt>Tên đăng nhập</dt><dd>{e.username}</dd></div><div><dt>Tên hiển thị</dt><dd>{e.displayName ?? "—"}</dd></div><div><dt>Email</dt><dd>{e.email ?? "—"}</dd></div></dl></section>

        <section className="xp-section" aria-label="Cơ cấu tổ chức"><h3>Đơn vị và vị trí</h3>
          {rows.length === 0 ? <p data-testid="detail-org">Chưa gán đơn vị</p> : <ul className="xp-memberList" data-testid="detail-memberships">{rows.map((r) => (
            <MembershipItem key={r.membership.id} row={r} e={e} tenantId={tenant.id} org={org} unitsKnown={unitsKnown} positions={positions} grades={grades} orgOn={orgOn} posOn={posOn} busy={a.busy} write={write} onRemove={() => setConfirm({ kind: "membership", row: r })} onRemovePosition={(p) => setConfirm({ kind: "position", membershipId: r.membership.id, id: p.id, version: p.version, name: p.positionName })}/>))}</ul>}
          {orgOn ? <AddMembership e={e} tenantId={tenant.id} org={org} free={free} unitsKnown={unitsKnown} hasPrimary={rows.some((r) => r.membership.primary)} busy={a.busy} write={write}/>
            : <NotReadyPanel testid="detail-org-not-ready" title="Chưa thay đổi được đơn vị" reason={(plan.assignOrg as { reason: string }).reason}/>}
          <p className="xp-note" role="note" data-testid="detail-relation-note"><Info size={16} aria-hidden="true"/><span>“Quan hệ” (thành viên, quản lý, trưởng đơn vị…) và vị trí chỉ mô tả công việc. Chúng <b>không cấp quyền</b>.</span></p>
        </section>

        <section className="xp-section" aria-label="Quyền hiệu lực"><h3>Quyền hiệu lực</h3>
          <dl className="kv"><div><dt>Vai trò công ty</dt><dd data-testid="detail-role">{tenantRoleLabel(e.tenantRole)}</dd></div></dl>
          <p className="xp-note" role="note" data-testid="detail-perm-note"><Info size={16} aria-hidden="true"/><span>Quyền do máy chủ quyết định theo vai trò công ty và vai trò trong từng workspace, không theo đơn vị, vị trí hay cấp bậc.</span></p></section>
        <section className="xp-section" aria-label="Trạng thái"><h3>Trạng thái</h3>
          <div className="row"><StatusPill e={e}/>
            <ReasonButton className="btn sm" data-testid="detail-toggle" unavailable={!statusOn} reason={statusOn ? undefined : (plan.employeeStatus as { reason: string }).reason} onClick={() => setConfirm({ kind: "status", active: !e.active })}>{e.active ? "Tắt tài khoản" : "Bật tài khoản"}</ReasonButton></div>
        </section>
        {ok ? <p className="notice" role="status" data-testid="detail-ok">{ok}</p> : null}
        {a.problem ? <Problem p={a.problem} onReload={refresh} onRetry={() => void a.retry().then((done) => { if (done) { setOk("Đã lưu."); refresh(); } })} retrying={a.busy} reloadLabel="Tải lại nhân viên" testid="emp-problem"/> : null}
        <div className="xp-footer"><button className="btn" onClick={onClose}>Đóng</button></div>
      </div>
      {confirm?.kind === "status" ? <OrgConfirm testid="emp-status-dialog" title={confirm.active ? `Bật lại tài khoản “${employeeName(e)}”?` : `Tắt tài khoản “${employeeName(e)}”?`} danger={!confirm.active}
        message={confirm.active ? "Nhân viên đăng nhập được trở lại và xuất hiện lại trong số liệu của đơn vị." : "Nhân viên không đăng nhập được và không còn được tính vào số liệu của đơn vị. Đơn vị và vị trí của họ được giữ nguyên."}
        confirmLabel={confirm.active ? "Bật tài khoản" : "Tắt tài khoản"} busyLabel="Đang lưu…" go={async () => { await org.setEmployeeActive(tenant.id, e.userId, confirm.active); }} onClose={() => setConfirm(null)} onReload={refresh} onDone={() => { setConfirm(null); setOk(confirm.active ? "Đã bật tài khoản." : "Đã tắt tài khoản."); refresh(); }}/> : null}
      {confirm?.kind === "membership" ? <OrgConfirm testid="emp-membership-dialog" title={`Gỡ khỏi “${confirm.row.unitName || NAME_HIDDEN}”?`} message="Nhân viên không còn thuộc đơn vị này. Nếu còn giữ vị trí trong đơn vị, hãy gỡ các vị trí đó trước (máy chủ sẽ từ chối nếu còn)."
        confirmLabel="Gỡ khỏi đơn vị" busyLabel="Đang gỡ…" go={async () => { await org.removeMembership(tenant.id, e.userId, confirm.row.membership.id, confirm.row.membership.version); }} onClose={() => setConfirm(null)} onReload={refresh} onDone={() => { setConfirm(null); setOk("Đã gỡ khỏi đơn vị."); refresh(); }}/> : null}
      {confirm?.kind === "position" ? <OrgConfirm testid="emp-position-dialog" title={`Gỡ vị trí “${confirm.name}”?`} message="Nhân viên không còn giữ vị trí này trong đơn vị."
        confirmLabel="Gỡ vị trí" busyLabel="Đang gỡ…" go={async () => { await org.removeEmployeePosition(tenant.id, e.userId, confirm.id, confirm.version); }} onClose={() => setConfirm(null)} onReload={refresh} onDone={() => { setConfirm(null); setOk("Đã gỡ vị trí."); refresh(); }}/> : null}
    </Modal>
  );
}

type Write = (words: string, fn: () => Promise<unknown>) => Promise<void>;

/** one membership of the employee: its unit, its relation label, the primary flag, and the positions held within it */
function MembershipItem({ row, e, tenantId, org, unitsKnown, positions, grades, orgOn, posOn, busy, write, onRemove, onRemovePosition }: {
  row: MembershipRow; e: Employee; tenantId: string; org: OrganizationApi; unitsKnown: boolean; positions: Position[]; grades: Grade[]; orgOn: boolean; posOn: boolean; busy: boolean; write: Write; onRemove: () => void; onRemovePosition: (p: MembershipRow["positions"][number]) => void;
}) {
  const m = row.membership; const [relation, setRelation] = useState(m.relationType); const [editRel, setEditRel] = useState(false);
  const [pos, setPos] = useState(""); const [grade, setGrade] = useState(""); const [prim, setPrim] = useState(false);
  const relErr = relationError(relation); const heldIds = new Set(row.positions.map((p) => p.positionId));
  const name = unitsKnown ? row.unitPath || NAME_HIDDEN : NAME_HIDDEN;
  return (
    <li data-testid={`membership:${m.id}`} data-primary={m.primary} className="xp-memberItem">
      <div className="row"><b data-testid={`membership-unit:${m.id}`} title={name}>{name}</b>{row.unitArchived ? <span className="pill pill-muted">Đơn vị đã lưu trữ</span> : null}
        <span className="pill" data-testid={`membership-relation:${m.id}`} title="Chỉ là nhãn mô tả, không cấp quyền">{m.relationType}</span>{m.primary ? <span className="pill pill-ok" data-testid={`membership-primary:${m.id}`}>Đơn vị chính</span> : null}</div>
      {orgOn ? <div className="row">
        {m.primary ? null : <button type="button" className="btn sm" data-testid={`membership-make-primary:${m.id}`} disabled={busy} onClick={() => void write("Đã đặt làm đơn vị chính.", () => org.updateMembership(tenantId, e.userId, m.id, m.version, { primary: true }))}>Đặt làm đơn vị chính</button>}
        <button type="button" className="btn sm" data-testid={`membership-edit-relation:${m.id}`} disabled={busy} onClick={() => setEditRel(!editRel)} aria-expanded={editRel}>Đổi quan hệ</button>
        <button type="button" className="btn sm danger" data-testid={`membership-remove:${m.id}`} disabled={busy} onClick={onRemove}>Gỡ khỏi đơn vị</button></div> : null}
      {editRel && orgOn ? <form className="row" noValidate onSubmit={(ev: FormEvent) => { ev.preventDefault(); if (relErr || !relation.trim()) return; void write("Đã đổi quan hệ.", () => org.updateMembership(tenantId, e.userId, m.id, m.version, { relationType: normalizeRelation(relation) })).then(() => setEditRel(false)); }}>
        <label className="field"><span>Quan hệ</span><input data-testid={`membership-relation-input:${m.id}`} list="org-relations" value={relation} maxLength={32} autoComplete="off" aria-invalid={!!relErr} onChange={(ev) => setRelation(ev.target.value)}/></label>
        <button className="btn sm primary" data-testid={`membership-relation-save:${m.id}`} disabled={busy || !!relErr || !relation.trim()}>Lưu</button>{relErr ? <p className="formError" role="alert">{relErr}</p> : null}</form> : null}
      <ul className="xp-posList" aria-label="Vị trí trong đơn vị này">{row.positions.map((p) => (
        <li key={p.id} data-testid={`position-held:${p.id}`}><span>{p.positionName}{p.gradeName ? <small className="hint"> · {p.gradeName}</small> : null}{p.primary ? <span className="pill pill-ok"> Chính</span> : null}</span>
          {posOn ? <span className="row">
            <select aria-label={`Cấp bậc của ${p.positionName}`} data-testid={`position-grade:${p.id}`} value={p.gradeId ?? ""} disabled={busy} onChange={(ev) => void write("Đã đổi cấp bậc.", () => org.updateEmployeePosition(tenantId, e.userId, p.id, p.version, ev.target.value ? { gradeId: ev.target.value } : { clearGrade: true }))}>
              <option value="">Không cấp bậc</option>{grades.filter((g) => g.active || g.id === p.gradeId).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}</select>
            {p.primary ? null : <button type="button" className="btn sm" data-testid={`position-make-primary:${p.id}`} disabled={busy} onClick={() => void write("Đã đặt làm vị trí chính.", () => org.updateEmployeePosition(tenantId, e.userId, p.id, p.version, { primary: true }))}>Đặt làm chính</button>}
            <button type="button" className="btn sm danger" data-testid={`position-remove:${p.id}`} disabled={busy} onClick={() => onRemovePosition(p)}>Gỡ</button></span> : null}</li>))}</ul>
      {posOn ? <form className="row" noValidate data-testid={`position-add-form:${m.id}`} onSubmit={(ev: FormEvent) => { ev.preventDefault(); if (!pos) return; void write("Đã thêm vị trí.", () => org.addEmployeePosition(tenantId, e.userId, { membershipId: m.id, positionId: pos, ...(grade ? { gradeId: grade } : {}), ...(prim ? { primary: true } : {}) })).then(() => { setPos(""); setGrade(""); setPrim(false); }); }}>
        <label className="field"><span>Thêm vị trí</span><select data-testid={`position-add-select:${m.id}`} value={pos} disabled={busy || row.unitArchived} onChange={(ev) => setPos(ev.target.value)}><option value="">Chọn vị trí</option>{positions.filter((p) => p.active && !heldIds.has(p.id)).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="field"><span>Cấp bậc</span><select data-testid={`position-add-grade:${m.id}`} value={grade} disabled={busy || !pos} onChange={(ev) => setGrade(ev.target.value)}><option value="">Không cấp bậc</option>{grades.filter((g) => g.active).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
        <label className="xp-check"><input type="checkbox" checked={prim} disabled={busy || !pos} onChange={(ev) => setPrim(ev.target.checked)}/> Vị trí chính</label>
        <button className="btn sm primary" data-testid={`position-add-submit:${m.id}`} disabled={busy || !pos}>Thêm vị trí</button></form> : null}
    </li>
  );
}

/** add the employee to one more unit (they may belong to several); the relation is a free label (MEMBER by default) and the first membership becomes primary on its own */
function AddMembership({ e, tenantId, org, free, unitsKnown, hasPrimary, busy, write }: { e: Employee; tenantId: string; org: OrganizationApi; free: { id: string; label: string }[]; unitsKnown: boolean; hasPrimary: boolean; busy: boolean; write: Write }) {
  const [unit, setUnit] = useState(""); const [relation, setRelation] = useState("MEMBER"); const [primary, setPrimary] = useState(false);
  const relErr = relationError(relation);
  return (
    <form className="row xp-addMember" noValidate data-testid="membership-add-form" onSubmit={(ev: FormEvent) => { ev.preventDefault(); if (!unit || relErr) return; void write("Đã thêm vào đơn vị.", () => org.addMembership(tenantId, e.userId, { unitId: unit, relationType: normalizeRelation(relation) || "MEMBER", ...(primary ? { primary: true } : {}) })).then(() => { setUnit(""); setPrimary(false); }); }}>
      <label className="field"><span>Thêm vào đơn vị</span><select data-testid="membership-add-unit" value={unit} disabled={busy || !unitsKnown || free.length === 0} onChange={(ev) => setUnit(ev.target.value)}>
        <option value="">{unitsKnown ? (free.length ? "Chọn đơn vị" : "Không còn đơn vị để thêm") : "Không xem được danh sách đơn vị"}</option>{free.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
      <label className="field"><span>Quan hệ</span><input data-testid="membership-add-relation" list="org-relations" value={relation} maxLength={32} autoComplete="off" aria-invalid={!!relErr} onChange={(ev) => setRelation(ev.target.value)}/></label>
      <datalist id="org-relations"><option value="MEMBER"/><option value="MANAGER"/><option value="HEAD"/></datalist>
      {hasPrimary ? <label className="xp-check"><input type="checkbox" data-testid="membership-add-primary" checked={primary} disabled={busy} onChange={(ev) => setPrimary(ev.target.checked)}/> Đặt làm đơn vị chính</label> : null}
      <button className="btn sm primary" data-testid="membership-add-submit" disabled={busy || !unit || !!relErr}>Thêm</button>{relErr ? <p className="formError" role="alert">{relErr}</p> : null}
    </form>
  );
}
