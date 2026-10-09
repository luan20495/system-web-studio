"use client";
// Project drawers/modals shared by the Studio workspace (settings, assets, members, publish).

import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { X, confirm, useBackdropClose } from "@xweb/ui";
import { api, ApiError } from "@/lib/http-api";
import { renderSchemaDocument } from "@/lib/schema-preview";
import type {
  RegistryComponent, AiStatus, ApiProject, AssetDto, AuthConfig, Member, Deployment, Me, PageSchema, PromptHistoryItem, SchemaOperation, Section, SiteInfo, VersionSummary
} from "@/lib/http-types";
import { PROJECT_ROLES, WORKSPACE_ROLES } from "@/lib/http-types";
import type { DeviceMode } from "@/lib/types";
import { useOverlayDialog } from "./useOverlayDialog";
import { errText } from "../ui";


export const suggestions = (ai: boolean) => ai
  ? ["Thêm bảng so sánh sản phẩm", "Viết lại tiêu đề hero hấp dẫn hơn", "Ẩn phần đánh giá", "Thêm một sản phẩm mới", "Rút gọn nội dung hero"]
  : ["Thêm bảng so sánh sản phẩm", "Ẩn phần đánh giá", "Thêm một sản phẩm mới", "Rút gọn tiêu đề hero"];

export function DeviceIcon({ kind }: { kind: DeviceMode }) {
  const common = { width: 14, height: 14, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };
  if (kind === "desktop") return <svg {...common}><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>;
  if (kind === "tablet") return <svg {...common}><rect x="5" y="2" width="14" height="20" rx="2"/><path d="M11 18h2"/></svg>;
  return <svg {...common}><rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/></svg>;
}

export function Drawer({ title, sub, onClose, children, wide }: { title: string; sub?: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const dialog = useOverlayDialog(title, onClose);
  const backdrop = useBackdropClose(onClose, true);   // closes only when the press started AND ended on the backdrop (selecting text in a field and releasing outside no longer dismisses it)
  return (
    <div className="overlay" {...backdrop}>
      <div className={`drawer${wide ? " wide" : ""}`} {...dialog.props}>
        <div className="drawerHeader"><div><h2 id={dialog.titleId}>{title}</h2>{sub ? <p>{sub}</p> : null}</div><button className="button icon" aria-label="Đóng" onClick={onClose}><X size={16} aria-hidden="true"/></button></div>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="settingField"><span>{label}</span>{children}</label>;
}

export function SettingsDrawer({ project, busy, onClose, onSave, extra }: { project: ApiProject; busy: boolean; onClose: () => void; onSave: (patch: Partial<ApiProject>) => Promise<void>; extra?: ReactNode }) {
  const [d, setD] = useState({ name: project.name, description: project.description ?? "", authMode: project.authMode, domain: project.domain ?? "", customDomain: project.customDomain ?? "", deploymentMode: project.deploymentMode, deploymentTarget: project.deploymentTarget ?? "" });
  const save = () => onSave({
    name: d.name.trim(), description: d.description, authMode: d.authMode, deploymentMode: d.deploymentMode,
    ...(d.domain ? { domain: d.domain.trim() } : {}), ...(d.customDomain ? { customDomain: d.customDomain.trim() } : {}),
    ...(d.deploymentTarget ? { deploymentTarget: d.deploymentTarget.trim() } : {})
  });
  return (
    <Drawer title="Cài đặt project" sub="Lưu vào backend; xung đột phiên bản sẽ được báo." onClose={onClose}>
      <section className="settingGroup"><h3>Chung</h3>
        <Field label="Tên project"><input maxLength={160} value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })}/></Field>
        <Field label="Mô tả"><input maxLength={1000} value={d.description} onChange={(e) => setD({ ...d, description: e.target.value })}/></Field></section>
      <section className="settingGroup"><h3>Truy cập</h3>
        <Field label="Xác thực"><select value={d.authMode} onChange={(e) => setD({ ...d, authMode: e.target.value as ApiProject["authMode"] })}><option value="NONE">Không</option><option value="LOCAL">Tài khoản / Mật khẩu</option><option value="OIDC">SSO (OIDC)</option></select></Field>
        <p className="hint">Chế độ riêng tư/công khai được đặt khi xuất bản.</p></section>
      <section className="settingGroup"><h3>Tên miền</h3>
        <Field label="Tên miền xem trước"><input value={d.domain} placeholder="web.example.com" onChange={(e) => setD({ ...d, domain: e.target.value })}/></Field>
        <Field label="Tên miền riêng"><input value={d.customDomain} placeholder="example.com" onChange={(e) => setD({ ...d, customDomain: e.target.value })}/></Field></section>
      <section className="settingGroup"><h3>Triển khai</h3>
        <Field label="Chế độ"><select value={d.deploymentMode} onChange={(e) => setD({ ...d, deploymentMode: e.target.value as ApiProject["deploymentMode"] })}><option value="MOCK">Mock (cục bộ)</option><option value="SELF_HOSTED">Self-host</option><option value="CLOUD">Cloud</option></select></Field>
        <Field label="Đích triển khai"><input maxLength={120} value={d.deploymentTarget} onChange={(e) => setD({ ...d, deploymentTarget: e.target.value })}/></Field></section>
      <div className="drawerActions"><button className="button ghost" onClick={onClose}>Hủy</button><button className="button primary" disabled={busy || !d.name.trim()} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</button></div>
      {extra}
    </Drawer>
  );
}

export function AssetsDrawer({ workspaceId, projectId, canEdit, onClose, onError }: { workspaceId: string; projectId: string; canEdit: boolean; onClose: () => void; onError: (e: unknown) => void }) {
  const [assets, setAssets] = useState<AssetDto[] | null>(null);
  const [uploading, setUploading] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const load = useCallback(() => api.listAssets(workspaceId, projectId).then(setAssets).catch(onError), [workspaceId, projectId, onError]);
  useEffect(() => { void load(); }, [load]);

  async function upload(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    try { await api.uploadAsset(workspaceId, projectId, file); await load(); } catch (e) { onError(e); }
    finally { setUploading(false); if (input.current) input.current.value = ""; }
  }

  async function removeAsset(a: AssetDto) {
    if (!(await confirm({ title: `Xóa tệp “${a.name}”?`, message: "Tệp bị xóa khỏi ứng dụng và không thể lấy lại. Các trang đang dùng tệp này sẽ không còn hiển thị nó.", confirmLabel: "Xóa tệp", danger: true }))) return;
    try { await api.deleteAsset(workspaceId, projectId, a.id); await load(); } catch (e) { onError(e); }
  }

  return (
    <Drawer title="Tệp của project" sub="PNG, JPEG, WebP, GIF hoặc PDF. Lưu trong MinIO qua URL ký sẵn." onClose={onClose}>
      {canEdit ? <label className="uploadBox">{uploading ? "Đang tải lên…" : "Chọn tệp để tải lên"}
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp,image/gif,application/pdf" disabled={uploading} onChange={(e) => void upload(e.target.files?.[0])}/></label> : null}
      <div className="versionList">{assets === null ? <div role="status">Đang tải…</div> : assets.length === 0 ? <p className="hint">Chưa có tệp nào.</p> : assets.map((a) => (
        <article className="versionItem" key={a.id}>
          <div><b>{a.name}</b><span>{(a.size / 1024).toFixed(1)} KB</span></div>
          {a.contentType.startsWith("image/") && a.downloadUrl ? /* eslint-disable-next-line @next/next/no-img-element */ <img className="assetThumb" src={a.downloadUrl} alt={a.name}/> : null}
          {a.downloadUrl ? <a href={a.downloadUrl} target="_blank" rel="noreferrer noopener">Mở tệp</a> : null}
          {canEdit ? <button className="smallButton danger" onClick={() => void removeAsset(a)}>Xóa</button> : null}
        </article>))}</div>
    </Drawer>
  );
}

export function roleLabel(role: string) {
  return ({ WORKSPACE_ADMIN: "Quản trị workspace", OWNER: "Chủ sở hữu", EDITOR: "Biên tập", PUBLISHER: "Xuất bản", VIEWER: "Chỉ xem" } as Record<string, string>)[role] ?? role;
}

export function MemberTable({ title, members, roles, currentUserId, onChange, onRemove, onAdd, busy, error }: {
  title: string; members: Member[] | null; roles: readonly string[]; currentUserId: string; busy: boolean; error: string | null;
  onChange: (m: Member, role: string) => void; onRemove: (m: Member) => void; onAdd: (who: string, role: string) => Promise<boolean>;
}) {
  const [who, setWho] = useState("");
  const [role, setRole] = useState(roles[roles.length - 1]);
  const id = useId();
  return (
    <section className="settingGroup" aria-labelledby={`${id}-h`}>
      <h3 id={`${id}-h`}>{title}</h3>
      {members === null ? <div role="status">Đang tải…</div> : (
        <table className="memberTable">
          <caption className="srOnly">{title}</caption>
          <thead><tr><th scope="col">Người dùng</th><th scope="col">Vai trò</th><th scope="col"><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{members.map((m) => (
            <tr key={m.userId}>
              <td><b>{m.displayName ?? m.username}{m.userId === currentUserId ? <em className="you">Bạn</em> : null}</b><small>{m.username}{m.email ? ` · ${m.email}` : ""}</small></td>
              <td><select aria-label={`Vai trò của ${m.username}`} value={m.role} disabled={busy || m.userId === currentUserId} onChange={(e) => onChange(m, e.target.value)}>
                {roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}</select></td>
              <td><button className="smallButton danger" disabled={busy} onClick={() => onRemove(m)} aria-label={`${m.userId === currentUserId ? "Rời" : "Xóa"} ${m.username}`}>{m.userId === currentUserId ? "Rời" : "Xóa"}</button></td>
            </tr>))}</tbody>
        </table>)}
      <form className="inlineForm" onSubmit={(e) => { e.preventDefault(); void onAdd(who, role).then((ok) => { if (ok) setWho(""); }); }}>
        <input aria-label={`Tên đăng nhập hoặc email để thêm vào: ${title}`} placeholder="Tên đăng nhập hoặc email" value={who} onChange={(e) => setWho(e.target.value)} maxLength={254}/>
        <select aria-label="Vai trò khi thêm" value={role} onChange={(e) => setRole(e.target.value)}>{roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}</select>
        <button className="button" disabled={busy || !who.trim()}>Thêm</button>
      </form>
      {error ? <p className="formError" role="alert">{error}</p> : null}
    </section>
  );
}

export function MembersDrawer({ workspaceId, projectId, me, onClose, onError }: { workspaceId: string; projectId: string; me: Me; onClose: () => void; onError: (e: unknown) => void }) {
  // No role name decides anything here (MEMBER_MANAGE is a server-internal constant that is not exposed to the client): the workspace member list is requested and the SERVER's answer decides.
  // A 403/404 simply means "not visible to you" and the section is not shown (handoff H-C1-05).
  const [wsVisible, setWsVisible] = useState(false);
  const [project, setProject] = useState<Member[] | null>(null);
  const [workspace, setWorkspace] = useState<Member[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<{ project: string | null; workspace: string | null }>({ project: null, workspace: null });

  const load = useCallback(() => {
    api.listProjectMembers(workspaceId, projectId).then(setProject).catch(onError);
    api.listWorkspaceMembers(workspaceId).then((m) => { setWorkspace(m); setWsVisible(true); })
      .catch((e) => { if (e instanceof ApiError && (e.status === 403 || e.status === 404)) { setWorkspace(null); setWsVisible(false); } else onError(e); });
  }, [workspaceId, projectId, onError]);
  useEffect(() => { load(); }, [load]);

  const who = (v: string) => (v.includes("@") ? { email: v.trim() } : { username: v.trim() });
  async function act(scope: "project" | "workspace", fn: () => Promise<unknown>): Promise<boolean> {
    setBusy(true); setErrors((e) => ({ ...e, [scope]: null }));
    try { await fn(); load(); return true; }
    catch (e) { if (e instanceof ApiError && e.status !== 401) setErrors((x) => ({ ...x, [scope]: errText(e, "Thao tác thất bại.") })); else onError(e); return false; }
    finally { setBusy(false); }
  }

  return (
    <Drawer wide title="Thành viên và quyền" sub="Quyền được kiểm tra ở máy chủ; thay đổi được ghi vào nhật ký kiểm toán." onClose={onClose}>
      <MemberTable title="Thành viên project" members={project} roles={PROJECT_ROLES} currentUserId={me.id} busy={busy} error={errors.project}
        onChange={(m, r) => void act("project", () => api.changeProjectMember(workspaceId, projectId, m.userId, r))}
        onRemove={(m) => void (async () => { const self = m.userId === me.id; if (await confirm({ title: self ? "Rời khỏi ứng dụng này?" : `Xóa ${m.username} khỏi ứng dụng?`, message: self ? "Bạn sẽ mất quyền truy cập ứng dụng này, trừ khi bạn còn quyền ở cấp workspace." : `${m.username} sẽ không còn truy cập được ứng dụng này.`, confirmLabel: self ? "Rời ứng dụng" : "Xóa khỏi ứng dụng", danger: true })) await act("project", () => api.removeProjectMember(workspaceId, projectId, m.userId)); })()}
        onAdd={(v, r) => act("project", () => api.addProjectMember(workspaceId, projectId, who(v), r))}/>
      {wsVisible ? <MemberTable title="Thành viên workspace" members={workspace} roles={WORKSPACE_ROLES} currentUserId={me.id} busy={busy} error={errors.workspace}
        onChange={(m, r) => void act("workspace", () => api.changeWorkspaceMember(workspaceId, m.userId, r))}
        onRemove={(m) => void (async () => { const self = m.userId === me.id; if (await confirm({ title: self ? "Rời khỏi workspace này?" : `Xóa ${m.username} khỏi workspace?`, message: self ? "Bạn sẽ mất quyền ở mọi ứng dụng của workspace này." : `${m.username} cũng mất quyền ở mọi ứng dụng của workspace.`, confirmLabel: self ? "Rời workspace" : "Xóa khỏi workspace", danger: true })) await act("workspace", () => api.removeWorkspaceMember(workspaceId, m.userId)); })()}
        onAdd={(v, r) => act("workspace", () => api.addWorkspaceMember(workspaceId, who(v), r))}/> : null}
    </Drawer>
  );
}
