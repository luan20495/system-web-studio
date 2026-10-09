"use client";
/**
 * Create-account flows. `CreateAccountDialog` is shared by the Platform portal (SYSTEM_ADMIN) and the Admin portal (tenant admin); it only talks to a `ProvisioningApi`
 * (provisioning.ts): the production adapter maps routes that exist today and throws NOT_READY for the rest, so the form is complete and honest before C1's contract is final.
 * The tenant of an Admin-portal caller is shown, never typed. Account types come from the plan (never SYSTEM_ADMIN). Every refusal is shown by its code (provisioningProblem).
 */
import { FormError } from "./FormError";
import { CircleCheck, ModalHeader, UserRound } from "@xweb/ui";
import { useId, useMemo, useState, type FormEvent } from "react";
import type { ActivationLink } from "@/lib/http-types";
import { Modal } from "./Modal";
import { useSingleFlight } from "./useAdminAction";
import { Card, StateView } from "../ui";
import { LinkBox } from "./UserDialogs";
import type { ProvisioningApi, ProvisionResult, WorkspaceRoleId } from "./provisioning";
import {
  ACCOUNT_TYPES, emptyAccountForm, provisioningProblem, validateAccountForm, type AccountForm, type AccountTypeId, type ProvisioningPlan, type ProvisioningProblem,
} from "./provisioningModel";
import { tenantRoleLabel, workspaceRoleLabel } from "./adminModel";

export type Option = { id: string; name: string };
const asProblem = (e: unknown): ProvisioningProblem => provisioningProblem(e as { code?: string; status?: number; message?: string; reason?: string });

export function CreateAccountDialog({ api, plan, tenants, workspacesOf, onClose, onCreated, title = "Tạo tài khoản", submitLabel = "Tạo tài khoản", extraSection, afterCreate }: {
  api: ProvisioningApi; plan: ProvisioningPlan;
  /** dialog wording (the employee directory says "Thêm nhân viên") */ title?: string; submitLabel?: string;
  /** extra fieldset(s) rendered before the activation note (e.g. the organization unit / position of an employee); the host owns their state */ extraSection?: React.ReactNode;
  /** runs AFTER the account exists; returns a warning text when a follow-up step failed (the account is NOT rolled back: it is shown as a pending step) */ afterCreate?: (r: ProvisionResult) => Promise<string | null>;
  /** the tenants the caller may pick (all for a SYSTEM_ADMIN; only their own for a tenant admin of several). Unused when the plan has a fixed tenant. */
  tenants: Option[];
  /** workspaces already known for a tenant (the host decides where they come from); the ones created in this dialog are added here */
  workspacesOf: (tenantId: string) => Option[];
  onClose: () => void; onCreated?: (r: ProvisionResult) => void;
}) {
  const uid = useId(); const [form, setForm] = useState<AccountForm>(() => emptyAccountForm(plan.accountTypes[0]?.id ?? "USER"));
  const [newWs, setNewWs] = useState(""); const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<ProvisioningProblem | null>(null);
  const [result, setResult] = useState<ProvisionResult | null>(null); const [made, setMade] = useState<(Option & { tenantId: string })[]>([]);
  const tenantId = plan.fixedTenant?.id ?? form.tenantId;
  const tenantName = plan.fixedTenant?.name ?? tenants.find((t) => t.id === tenantId)?.name ?? "";
  const wsOptions = tenantId ? [...workspacesOf(tenantId), ...made.filter((m) => m.tenantId === tenantId)] : [];
  const type = ACCOUNT_TYPES[form.type];
  const notReady = plan.create.state !== "ready";
  const problems = validateAccountForm(form, plan, { newWorkspaceName: newWs });
  const field = (k: keyof typeof problems) => (touched && problems[k] ? problems[k] : undefined);
  const set = (patch: Partial<AccountForm>) => setForm((f) => ({ ...f, ...patch }));
  const setType = (t: AccountTypeId) => set({ type: t, role: ACCOUNT_TYPES[t].roles[0].id, ...(ACCOUNT_TYPES[t].workspace === "required" && !form.workspaceId ? {} : {}) });

  const once = useSingleFlight();
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setProblem(null);
    if (notReady || Object.keys(problems).length) return;
    await once(async () => {
    setBusy(true);
    try {
      let workspaceId = form.workspaceId;
      if (workspaceId === "__new") {
        // a workspace OF this tenant (the legacy route would put it in the DEFAULT tenant); kept in the options if the account step then fails, so a retry does not create a second one
        const w = await api.createTenantWorkspace(tenantId, newWs); setMade((x) => [...x, { id: w.id, name: w.name, tenantId }]); workspaceId = w.id; set({ workspaceId: w.id });
      }
      const r = await api.createTenantUser(tenantId, { username: form.username, displayName: form.displayName, email: form.email, tenantRole: type.tenantRole, ...(workspaceId ? { workspace: { id: workspaceId, role: form.role } } : {}) });
      const warn = afterCreate ? await afterCreate(r).catch(() => "Bước gán cơ cấu tổ chức chưa thực hiện được.") : null;
      const done = warn ? { ...r, pending: [...r.pending, { id: "organization" as const, label: warn }] } : r;
      setResult(done); onCreated?.(done);
    } catch (err) { setProblem(asProblem(err)); } finally { setBusy(false); }
    });
  }

  if (result) return <CreatedAccount result={result} tenantName={tenantName} workspaceName={result.workspace ? wsOptions.find((w) => w.id === result.workspace!.id)?.name ?? result.workspace.id : null} onClose={onClose} onLinkDone={() => setResult((r) => (r ? { ...r, activation: { ...r.activation, token: "" } } : r))} onAnother={() => { setResult(null); setForm(emptyAccountForm(plan.accountTypes[0]?.id ?? "USER")); setTouched(false); setNewWs(""); }}/>;
  const dupUser = problem?.field === "username" ? problem.text : undefined, dupMail = problem?.field === "email" ? problem.text : undefined;
  return (
    <Modal label={title} onClose={onClose}>
      <form className="modalBody" noValidate onSubmit={(e) => void submit(e)} data-testid="create-account">
        <ModalHeader icon={<UserRound size={22}/>} title={title} subtitle="Người dùng tự đặt mật khẩu khi mở liên kết kích hoạt."/>
        {plan.create.state === "not-ready" ? <p className="notice" role="note" data-testid="prov-not-ready">Backend provisioning chưa sẵn sàng: {plan.create.reason}</p> : null}
        {plan.create.state === "forbidden" ? <p className="notice" role="note" data-testid="prov-forbidden">{plan.create.reason}</p> : null}

        <fieldset className="stack" aria-label="Thông tin"><legend className="bx-h4">1 · Thông tin</legend>
          <label className="field"><span>Tên đăng nhập</span><input data-testid="acc-username" autoComplete="off" value={form.username} aria-invalid={!!(field("username") || dupUser)} aria-describedby={field("username") || dupUser ? `${uid}-user-err` : undefined} onChange={(e) => set({ username: e.target.value })}/></label>
          {field("username") || dupUser ? <p className="formError" role="alert" id={`${uid}-user-err`} data-testid="acc-username-error">{dupUser ?? field("username")}</p> : null}
          <label className="field"><span>Tên hiển thị</span><input data-testid="acc-display" value={form.displayName} maxLength={160} aria-invalid={!!field("displayName")} aria-describedby={field("displayName") ? `${uid}-display-err` : undefined} onChange={(e) => set({ displayName: e.target.value })}/></label>
          {field("displayName") ? <p className="formError" role="alert" id={`${uid}-display-err`}>{field("displayName")}</p> : null}
          <label className="field"><span>Email (không bắt buộc)</span><input data-testid="acc-email" type="email" autoComplete="off" value={form.email} aria-invalid={!!(field("email") || dupMail)} aria-describedby={field("email") || dupMail ? `${uid}-email-err` : undefined} onChange={(e) => set({ email: e.target.value })}/></label>
          {field("email") || dupMail ? <p className="formError" role="alert" id={`${uid}-email-err`} data-testid="acc-email-error">{dupMail ?? field("email")}</p> : null}
        </fieldset>

        <fieldset className="stack" aria-label="Phạm vi"><legend className="bx-h4">2 · Công ty và workspace</legend>
          {plan.fixedTenant ? <label className="field"><span>Công ty</span><input data-testid="acc-tenant-fixed" readOnly value={plan.fixedTenant.name} aria-readonly="true"/><small className="hint">Lấy từ phiên đăng nhập của bạn; không đổi được.</small></label>
            : <label className="field"><span>Công ty</span>
              <select data-testid="acc-tenant" value={form.tenantId} aria-invalid={!!field("tenant")} aria-describedby={field("tenant") ? `${uid}-tenant-err` : undefined} onChange={(e) => set({ tenantId: e.target.value, workspaceId: "" })}><option value="">— chọn —</option>{tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>}
          {field("tenant") ? <p className="formError" role="alert" id={`${uid}-tenant-err`}>{field("tenant")}</p> : null}
          <label className="field"><span>Loại tài khoản</span>
            <select data-testid="acc-type" value={form.type} onChange={(e) => setType(e.target.value as AccountTypeId)}>{plan.accountTypes.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</select>
            <small className="hint">{type.hint}</small></label>
          <label className="field"><span>Workspace{type.workspace === "optional" ? " (không bắt buộc)" : ""}</span>
            <select data-testid="acc-workspace" value={form.workspaceId} disabled={!tenantId} aria-invalid={!!(field("workspace") || problem?.field === "workspace")} aria-describedby={field("workspace") || problem?.field === "workspace" ? `${uid}-ws-err` : undefined} onChange={(e) => set({ workspaceId: e.target.value })}>
              <option value="">{type.workspace === "optional" ? "Không gán workspace" : "— chọn —"}</option>{wsOptions.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}{plan.canCreateWorkspace && tenantId ? <option value="__new">+ Tạo workspace mới của công ty này</option> : null}</select>
            {!tenantId ? <small className="hint">Chọn công ty trước.</small> : null}</label>
          {form.workspaceId === "__new" ? <label className="field"><span>Tên workspace mới</span><input data-testid="acc-new-ws" value={newWs} onChange={(e) => setNewWs(e.target.value)}/></label> : null}
          {field("workspace") || problem?.field === "workspace" ? <p className="formError" role="alert" id={`${uid}-ws-err`}>{problem?.field === "workspace" ? problem.text : field("workspace")}</p> : null}
          {plan.tenantChoice && !plan.fixedTenant ? <p className="hint" data-testid="acc-ws-note">Máy chủ kiểm tra workspace thuộc đúng công ty đã chọn khi tạo; danh sách này chưa lọc theo công ty.</p> : null}
        </fieldset>

        <fieldset className="stack" aria-label="Vai trò"><legend className="bx-h4">3 · Vai trò</legend>
          <p className="hint" data-testid="acc-tenant-role">Vai trò công ty: <b>{tenantRoleLabel(type.tenantRole)}</b></p>
          <label className="field"><span>Vai trò trong workspace</span>
            <select data-testid="acc-role" value={form.role} disabled={!form.workspaceId} onChange={(e) => set({ role: e.target.value as WorkspaceRoleId })}>{type.roles.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select>
            {!form.workspaceId ? <small className="hint">Chọn workspace để chọn vai trò (workspace và vai trò đi cùng nhau).</small> : null}</label>
        </fieldset>

        {extraSection}

        <fieldset className="stack" aria-label="Kích hoạt"><legend className="bx-h4">4 · Kích hoạt</legend>
          <p className="hint">Sau khi tạo, bạn nhận một liên kết kích hoạt dùng một lần (hết hạn sau 24 giờ), chỉ hiển thị một lần. Người dùng tự đặt mật khẩu khi kích hoạt (tối thiểu 8 ký tự); bạn không bao giờ biết mật khẩu.</p>
        </fieldset>

        {problem ? <FormError className={problem.kind === "not-ready" ? "notice" : "formError"} data-testid="prov-problem" data-kind={problem.kind}>{problem.text}</FormError> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" data-testid="acc-submit" disabled={busy || notReady} aria-busy={busy || undefined} title={notReady ? "Backend provisioning chưa sẵn sàng" : undefined}>{busy ? "Đang tạo…" : submitLabel}</button></div>
      </form>
    </Modal>
  );
}

function CreatedAccount({ result, tenantName, workspaceName, onClose, onAnother, onLinkDone }: { result: ProvisionResult; tenantName: string; workspaceName: string | null; onClose: () => void; onAnother: () => void; onLinkDone: () => void }) {
  // the link lives in this state only (never stored, logged or put in a URL) and only until it was copied or the person confirmed they saved it: the link dialog cannot close before that
  // (M-007), and when it closes the token is dropped here AND from the parent's copy of the result (M-088). It is not shown again.
  const [link, setLink] = useState<ActivationLink | null>(result.activation as ActivationLink);
  if (link) return <LinkBox link={link} onClose={() => { setLink(null); onLinkDone(); }}/>;
  return (
    <Modal label="Đã tạo tài khoản" onClose={onClose}>
      <div className="modalBody" data-testid="account-created">
        <ModalHeader icon={<CircleCheck size={22}/>} title="Đã tạo tài khoản"/>
        <dl className="kv">
          <div><dt>Tài khoản</dt><dd data-testid="res-account"><b>{result.user.displayName}</b> ({result.user.username})</dd></div>
          <div><dt>Trạng thái</dt><dd data-testid="res-status">Chờ kích hoạt</dd></div>
          <div><dt>Công ty</dt><dd data-testid="res-tenant">{tenantName} · {tenantRoleLabel(result.tenantRole)}</dd></div>
          <div><dt>Workspace</dt><dd data-testid="res-workspace">{workspaceName ? `${workspaceName} · ${workspaceRoleLabel(result.workspace!.role)}` : "Chưa gán workspace"}</dd></div>
        </dl>
        <h3 className="bx-h4">Bước tiếp theo</h3>
        <ol data-testid="res-pending">{result.pending.map((p) => <li key={p.id}>{p.label}</li>)}</ol>
        <div className="xp-footer"><button className="btn" onClick={onClose}>Xong</button><button className="btn primary" onClick={onAnother}>Tạo tài khoản khác</button></div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------------------------------ Admin portal page
/** "Người dùng" of someone who is not a SYSTEM_ADMIN: their own tenant (read-only), create account (when the backend has it), add an existing account to a workspace (MEMBER_MANAGE) */
export function PeopleView({ api, plan, tenants, workspacesOf, memberWorkspaces, onCreated }: {
  api: ProvisioningApi; plan: ProvisioningPlan; tenants: Option[]; workspacesOf: (tenantId: string) => Option[]; memberWorkspaces: Option[]; onCreated?: (r: ProvisionResult) => void;
}) {
  const [creating, setCreating] = useState(false);
  const canTry = plan.create.state !== "forbidden";
  return (
    <div className="stack" data-testid="people">
      <Card title="Công ty" className="">
        {plan.fixedTenant ? <label className="field"><span>Công ty của bạn</span><input data-testid="people-tenant" readOnly aria-readonly="true" value={plan.fixedTenant.name}/><small className="hint">Công ty lấy từ phiên đăng nhập; không nhập hay đổi được.</small></label>
          : <p className="hint" data-testid="people-no-tenant">Bạn không quản trị công ty nào.</p>}
      </Card>
      <Card title="Tạo tài khoản mới" actions={canTry ? <button className="btn primary" data-testid="people-create" disabled={plan.create.state !== "ready"} title={plan.create.state === "not-ready" ? "Backend provisioning chưa sẵn sàng" : undefined} onClick={() => setCreating(true)}>+ Tạo tài khoản</button> : undefined}>
        {plan.create.state === "ready" ? <p className="hint">Tạo tài khoản trong công ty của bạn: nhận một liên kết kích hoạt, tùy chọn gán workspace và vai trò.</p> : null}
        {plan.create.state === "not-ready" ? <p className="notice" role="note" data-testid="people-not-ready">Backend provisioning chưa sẵn sàng: {plan.create.reason}</p> : null}
        {plan.create.state === "forbidden" ? <p className="hint" data-testid="people-forbidden">{plan.create.reason}</p> : null}
      </Card>
      <AddExisting api={api} plan={plan} workspaces={memberWorkspaces}/>
      {creating ? <CreateAccountDialog api={api} plan={plan} tenants={tenants} workspacesOf={workspacesOf} onClose={() => setCreating(false)} onCreated={onCreated}/> : null}
    </div>
  );
}

function AddExisting({ api, plan, workspaces }: { api: ProvisioningApi; plan: ProvisioningPlan; workspaces: Option[] }) {
  const [ws, setWs] = useState(workspaces[0]?.id ?? ""); const [who, setWho] = useState(""); const [role, setRole] = useState<WorkspaceRoleId>("EDITOR");
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ ok: boolean; text: string; kind?: string } | null>(null);
  const roles = useMemo(() => [...ACCOUNT_TYPES.WORKSPACE_ADMIN.roles, ...ACCOUNT_TYPES.USER.roles], []);
  const once = useSingleFlight();
  if (!plan.addExisting.available) return <Card title="Thêm người đã có tài khoản vào workspace"><StateView kind="forbidden" title="Chưa dùng được" detail={<p data-testid="add-existing-unavailable">{plan.addExisting.reason ?? "Không có quyền."}</p>}/></Card>;
  async function add(e: FormEvent) {
    e.preventDefault(); setMsg(null);
    const v = who.trim(); if (!v || !ws) { setMsg({ ok: false, text: "Hãy chọn workspace và nhập tên đăng nhập hoặc email.", kind: "validation" }); return; }
    await once(async () => {
    setBusy(true);
    try { await api.addWorkspaceMember(ws, v.includes("@") ? { email: v } : { username: v }, role); setMsg({ ok: true, text: "Đã thêm vào workspace." }); setWho(""); }
    catch (err) { const p = asProblem(err); setMsg({ ok: false, text: p.text, kind: p.kind }); } finally { setBusy(false); }
    });
  }
  return (
    <Card title="Thêm người đã có tài khoản vào workspace">
      <form className="stack" onSubmit={(e) => void add(e)} data-testid="add-existing">
        <label className="field"><span>Workspace</span><select data-testid="ae-workspace" value={ws} onChange={(e) => setWs(e.target.value)}>{workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label>
        <label className="field"><span>Tên đăng nhập hoặc email</span><input data-testid="ae-who" autoComplete="off" value={who} onChange={(e) => setWho(e.target.value)}/></label>
        <label className="field"><span>Vai trò</span><select data-testid="ae-role" value={role} onChange={(e) => setRole(e.target.value as WorkspaceRoleId)}>{roles.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
        <div className="row"><button className="btn primary" data-testid="ae-submit" disabled={busy}>{busy ? "Đang thêm…" : "Thêm vào workspace"}</button></div>
        {msg ? <p className={msg.ok ? "notice" : "formError"} role={msg.ok ? "status" : "alert"} data-testid="ae-msg" data-kind={msg.kind ?? "ok"}>{msg.text}</p> : null}
      </form>
    </Card>
  );
}
