"use client";
/**
 * Create-account flows. `CreateAccountDialog` is shared by the Platform portal (SYSTEM_ADMIN) and the Admin portal (tenant admin); it only talks to a `ProvisioningApi`
 * (provisioning.ts): the production adapter maps routes that exist today and throws NOT_READY for the rest, so the form is complete and honest before C1's contract is final.
 * The tenant of an Admin-portal caller is shown, never typed. Account types come from the plan (never SYSTEM_ADMIN). Every refusal is shown by its code (provisioningProblem).
 */
import { useMemo, useState, type FormEvent } from "react";
import type { ActivationLink } from "@/lib/http-types";
import { Modal } from "./Modal";
import { Card, StateView } from "../ui";
import { LinkBox } from "./UserDialogs";
import type { ProvisioningApi, ProvisionResult, WorkspaceRoleId } from "./provisioning";
import {
  ACCOUNT_TYPES, emptyAccountForm, provisioningProblem, validateAccountForm, type AccountForm, type AccountTypeId, type ProvisioningPlan, type ProvisioningProblem,
} from "./provisioningModel";
import { workspaceRoleLabel } from "./adminModel";

export type Option = { id: string; name: string };
const asProblem = (e: unknown): ProvisioningProblem => provisioningProblem(e as { code?: string; status?: number; message?: string; reason?: string });

export function CreateAccountDialog({ api, plan, tenants, workspaces, onClose, onCreated, createWorkspace }: {
  api: ProvisioningApi; plan: ProvisioningPlan; tenants: Option[]; workspaces: Option[];
  onClose: () => void; onCreated?: (r: ProvisionResult) => void;
  /** SYSTEM_ADMIN only: make a workspace on the spot (POST /admin/workspaces) */
  createWorkspace?: (name: string) => Promise<Option>;
}) {
  const [form, setForm] = useState<AccountForm>(() => emptyAccountForm(plan.accountTypes[0]?.id ?? "USER"));
  const [newWs, setNewWs] = useState(""); const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<ProvisioningProblem | null>(null);
  const [result, setResult] = useState<ProvisionResult | null>(null); const [extraWs, setExtraWs] = useState<Option[]>([]);
  const wsOptions = [...workspaces, ...extraWs];
  const type = ACCOUNT_TYPES[form.type];
  const notReady = plan.create.state !== "ready";
  const problems = validateAccountForm({ ...form, workspaceId: form.workspaceId === "__new" ? (newWs.trim() ? "new" : "") : form.workspaceId }, plan);
  const field = (k: keyof typeof problems) => (touched && problems[k] ? problems[k] : undefined);
  const set = (patch: Partial<AccountForm>) => setForm((f) => ({ ...f, ...patch }));
  const setType = (t: AccountTypeId) => set({ type: t, role: ACCOUNT_TYPES[t].roles[0].id });

  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setProblem(null);
    if (notReady || Object.keys(problems).length) return;
    setBusy(true);
    try {
      let workspaceId = form.workspaceId;
      if (workspaceId === "__new") { if (!createWorkspace) throw Object.assign(new Error("no"), { code: "PROVISIONING_NOT_READY", reason: "Tạo workspace mới không khả dụng ở đây." }); const w = await createWorkspace(newWs.trim()); setExtraWs((x) => [...x, w]); workspaceId = w.id; }
      const account = { username: form.username, displayName: form.displayName, email: form.email, workspaceId, workspaceRole: form.role as WorkspaceRoleId };
      const tenant = form.type === "TENANT_ADMIN" ? (plan.fixedTenant ?? tenants.find((t) => t.id === form.tenantId)) : undefined;
      const r = plan.fixedTenant ? await api.createTenantUser(account) : await api.createPlatformUser(account, tenant ? { tenantAdminOf: tenant } : undefined);
      setResult(r); onCreated?.(r);
    } catch (err) { setProblem(asProblem(err)); } finally { setBusy(false); }
  }

  if (result) return <CreatedAccount result={result} workspaceName={wsOptions.find((w) => w.id === result.workspaceId)?.name ?? result.workspaceId} onClose={onClose} onAnother={() => { setResult(null); setForm(emptyAccountForm(plan.accountTypes[0]?.id ?? "USER")); setTouched(false); }}/>;
  const dupUser = problem?.field === "username" ? problem.text : undefined, dupMail = problem?.field === "email" ? problem.text : undefined;
  return (
    <Modal label="Tạo tài khoản" onClose={onClose}>
      <form className="modalBody" noValidate onSubmit={(e) => void submit(e)} data-testid="create-account">
        <h2>Tạo tài khoản</h2>
        {plan.create.state === "not-ready" ? <p className="notice" role="note" data-testid="prov-not-ready">Backend provisioning chưa sẵn sàng: {plan.create.reason}</p> : null}
        {plan.create.state === "forbidden" ? <p className="notice" role="note" data-testid="prov-forbidden">{plan.create.reason}</p> : null}

        <fieldset className="stack" aria-label="Thông tin"><legend className="bx-h4">1 · Thông tin</legend>
          <label className="field"><span>Tên đăng nhập</span><input data-testid="acc-username" autoComplete="off" value={form.username} aria-invalid={!!(field("username") || dupUser)} onChange={(e) => set({ username: e.target.value })}/></label>
          {field("username") || dupUser ? <p className="formError" role="alert" data-testid="acc-username-error">{dupUser ?? field("username")}</p> : null}
          <label className="field"><span>Tên hiển thị</span><input data-testid="acc-display" value={form.displayName} maxLength={160} aria-invalid={!!field("displayName")} onChange={(e) => set({ displayName: e.target.value })}/></label>
          {field("displayName") ? <p className="formError" role="alert">{field("displayName")}</p> : null}
          <label className="field"><span>Email (không bắt buộc)</span><input data-testid="acc-email" type="email" autoComplete="off" value={form.email} aria-invalid={!!(field("email") || dupMail)} onChange={(e) => set({ email: e.target.value })}/></label>
          {field("email") || dupMail ? <p className="formError" role="alert" data-testid="acc-email-error">{dupMail ?? field("email")}</p> : null}
        </fieldset>

        <fieldset className="stack" aria-label="Phạm vi"><legend className="bx-h4">2 · Phạm vi</legend>
          <label className="field"><span>Loại tài khoản</span>
            <select data-testid="acc-type" value={form.type} onChange={(e) => setType(e.target.value as AccountTypeId)}>{plan.accountTypes.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</select>
            <small className="hint">{type.hint}</small></label>
          {plan.fixedTenant ? <label className="field"><span>Công ty</span><input data-testid="acc-tenant-fixed" readOnly value={plan.fixedTenant.name} aria-readonly="true"/><small className="hint">Lấy từ phiên đăng nhập của bạn; không đổi được.</small></label>
            : form.type === "TENANT_ADMIN" ? <label className="field"><span>Công ty</span>
              <select data-testid="acc-tenant" value={form.tenantId} aria-invalid={!!field("tenant")} onChange={(e) => set({ tenantId: e.target.value })}><option value="">— chọn —</option>{tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label> : null}
          {field("tenant") ? <p className="formError" role="alert">{field("tenant")}</p> : null}
          <label className="field"><span>Workspace</span>
            <select data-testid="acc-workspace" value={form.workspaceId} aria-invalid={!!(field("workspace") || problem?.field === "workspace")} onChange={(e) => set({ workspaceId: e.target.value })}>
              <option value="">— chọn —</option>{wsOptions.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}{createWorkspace ? <option value="__new">+ Tạo workspace mới</option> : null}</select></label>
          {form.workspaceId === "__new" ? <label className="field"><span>Tên workspace mới</span><input data-testid="acc-new-ws" value={newWs} onChange={(e) => setNewWs(e.target.value)}/></label> : null}
          {field("workspace") || problem?.field === "workspace" ? <p className="formError" role="alert">{problem?.field === "workspace" ? problem.text : field("workspace")}</p> : null}
          {plan.tenantChoice ? <p className="hint" data-testid="acc-ws-note">Danh sách workspace chưa cho biết workspace thuộc công ty nào (máy chủ chưa trả tenant của workspace, H-C1-14); máy chủ kiểm tra khi gán.</p> : null}
        </fieldset>

        <fieldset className="stack" aria-label="Vai trò"><legend className="bx-h4">3 · Vai trò</legend>
          <label className="field"><span>Vai trò trong workspace</span>
            <select data-testid="acc-role" value={form.role} onChange={(e) => set({ role: e.target.value as WorkspaceRoleId })}>{type.roles.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
          {form.type === "TENANT_ADMIN" ? <p className="hint">Vai trò “Quản trị công ty” được gán sau khi người này kích hoạt tài khoản (máy chủ không cho gán cho tài khoản chưa kích hoạt).</p> : null}
        </fieldset>

        <fieldset className="stack" aria-label="Kích hoạt"><legend className="bx-h4">4 · Kích hoạt</legend>
          <p className="hint">Sau khi tạo, bạn nhận một liên kết kích hoạt dùng một lần (hết hạn sau 24 giờ). Người dùng tự đặt mật khẩu; bạn không bao giờ biết mật khẩu.</p>
        </fieldset>

        {problem ? <p className={problem.kind === "not-ready" ? "notice" : "formError"} role="alert" data-testid="prov-problem" data-kind={problem.kind}>{problem.text}</p> : null}
        <div className="row"><button className="btn primary" data-testid="acc-submit" disabled={busy || notReady} title={notReady ? "Backend provisioning chưa sẵn sàng" : undefined}>{busy ? "Đang tạo…" : "Tạo tài khoản"}</button><button type="button" className="btn" onClick={onClose}>Hủy</button></div>
      </form>
    </Modal>
  );
}

function CreatedAccount({ result, workspaceName, onClose, onAnother }: { result: ProvisionResult; workspaceName: string; onClose: () => void; onAnother: () => void }) {
  const [showLink, setShowLink] = useState(true);
  if (showLink) return <LinkBox link={result.activation as ActivationLink} onClose={() => setShowLink(false)}/>;
  return (
    <Modal label="Đã tạo tài khoản" onClose={onClose}>
      <div className="modalBody" data-testid="account-created">
        <h2>Đã tạo tài khoản</h2>
        <dl className="kv">
          <dt>Tài khoản</dt><dd data-testid="res-account"><b>{result.user.displayName}</b> ({result.user.username})</dd>
          <dt>Trạng thái</dt><dd data-testid="res-status">Chờ kích hoạt</dd>
          <dt>Workspace</dt><dd data-testid="res-workspace">{workspaceName}</dd>
          <dt>Vai trò</dt><dd data-testid="res-role">{workspaceRoleLabel(result.workspaceRole)}</dd>
          <dt>Công ty</dt><dd data-testid="res-tenant">{result.pending.some((p) => p.id === "assignTenantRole") ? "Chưa gán (xem bước tiếp theo)" : "Theo workspace"}</dd>
        </dl>
        <h3 className="bx-h4">Bước tiếp theo</h3>
        <ol data-testid="res-pending">{result.pending.map((p) => <li key={p.id}>{p.label}</li>)}</ol>
        <div className="row"><button className="btn primary" onClick={onAnother}>Tạo tài khoản khác</button><button className="btn" onClick={onClose}>Xong</button></div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------------------------------ Admin portal page
/** "Người dùng" of someone who is not a SYSTEM_ADMIN: their own tenant (read-only), create account (when the backend has it), add an existing account to a workspace (MEMBER_MANAGE) */
export function PeopleView({ api, plan, tenants, workspaces, memberWorkspaces, onCreated }: {
  api: ProvisioningApi; plan: ProvisioningPlan; tenants: Option[]; workspaces: Option[]; memberWorkspaces: Option[]; onCreated?: (r: ProvisionResult) => void;
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
        {plan.create.state === "ready" ? <p className="hint">Tạo tài khoản trong công ty của bạn, gán workspace và vai trò.</p> : null}
        {plan.create.state === "not-ready" ? <p className="notice" role="note" data-testid="people-not-ready">Backend provisioning chưa sẵn sàng: {plan.create.reason}</p> : null}
        {plan.create.state === "forbidden" ? <p className="hint" data-testid="people-forbidden">{plan.create.reason}</p> : null}
      </Card>
      <AddExisting api={api} plan={plan} workspaces={memberWorkspaces}/>
      {creating ? <CreateAccountDialog api={api} plan={plan} tenants={tenants} workspaces={workspaces} onClose={() => setCreating(false)} onCreated={onCreated}/> : null}
    </div>
  );
}

function AddExisting({ api, plan, workspaces }: { api: ProvisioningApi; plan: ProvisioningPlan; workspaces: Option[] }) {
  const [ws, setWs] = useState(workspaces[0]?.id ?? ""); const [who, setWho] = useState(""); const [role, setRole] = useState<WorkspaceRoleId>("EDITOR");
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ ok: boolean; text: string; kind?: string } | null>(null);
  const roles = useMemo(() => [...ACCOUNT_TYPES.WORKSPACE_ADMIN.roles, ...ACCOUNT_TYPES.USER.roles], []);
  if (!plan.addExisting.available) return <Card title="Thêm người đã có tài khoản vào workspace"><StateView kind="forbidden" title="Chưa dùng được" detail={<p data-testid="add-existing-unavailable">{plan.addExisting.reason ?? "Không có quyền."}</p>}/></Card>;
  async function add(e: FormEvent) {
    e.preventDefault(); setMsg(null);
    const v = who.trim(); if (!v || !ws) { setMsg({ ok: false, text: "Hãy chọn workspace và nhập tên đăng nhập hoặc email.", kind: "validation" }); return; }
    setBusy(true);
    try { await api.addWorkspaceMember(ws, v.includes("@") ? { email: v } : { username: v }, role); setMsg({ ok: true, text: "Đã thêm vào workspace." }); setWho(""); }
    catch (err) { const p = asProblem(err); setMsg({ ok: false, text: p.text, kind: p.kind }); } finally { setBusy(false); }
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
