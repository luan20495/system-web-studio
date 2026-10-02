"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { api, ApiError } from "@/lib/http-api";
import { renderSchemaDocument } from "@/lib/schema-preview";
import type {
  AiStatus, ApiProject, AssetDto, AuthConfig, Member, Deployment, Me, PageSchema, PromptHistoryItem, SchemaOperation, Section, VersionSummary
} from "@/lib/http-types";
import { PROJECT_ROLES, WORKSPACE_ROLES } from "@/lib/http-types";
import type { DeviceMode } from "@/lib/types";
import { useDialog } from "./useDialog";

const fmt = (iso: string) => new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
const errText = (e: unknown, fallback: string) => (e instanceof ApiError ? `${e.message}${e.requestId ? ` (mã ${e.requestId})` : ""}` : e instanceof Error ? e.message : fallback);
const SSO_ERRORS: Record<string, string> = {
  not_provisioned: "Tài khoản SSO của bạn chưa được cấp quyền. Hãy liên hệ quản trị viên.",
  disabled: "Tài khoản đã bị vô hiệu hóa.", failed: "Đăng nhập SSO thất bại. Hãy thử lại.", no_identity: "Nhà cung cấp SSO không trả về danh tính hợp lệ."
};
const TERMINAL = ["RUNNING", "FAILED", "ROLLED_BACK"];

export default function HttpStudio() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [bootError, setBootError] = useState<string | null>(null);
  const [workspaceId, setWorkspaceId] = useState<string | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);

  useEffect(() => {
    api.me().then((m) => { setMe(m); setWorkspaceId(m.workspaces[0]?.id ?? null); })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) setMe(null);
        else { setBootError(errText(e, "Không tải được phiên đăng nhập.")); setMe(null); }
      });
  }, []);

  const logout = useCallback(async () => {
    await api.logout().catch(() => undefined);
    setMe(null); setProjectId(null); setWorkspaceId(null);
  }, []);

  // Any expired session surfaces here so the user is sent back to login instead of seeing broken panels.
  const onUnauthorized = useCallback(() => { setMe(null); setProjectId(null); }, []);

  if (me === undefined) return <div className="boot" role="status"><div className="spinner"/><div>Đang kiểm tra phiên đăng nhập…</div></div>;
  if (me === null) return <LoginScreen initialError={bootError} onLoggedIn={(m) => { setMe(m); setWorkspaceId(m.workspaces[0]?.id ?? null); setBootError(null); }}/>;
  if (!workspaceId) return <div className="boot" role="alert"><p>Tài khoản chưa thuộc workspace nào. Hãy nhờ quản trị viên thêm bạn vào workspace.</p><button className="button" onClick={() => void logout()}>Đăng xuất</button></div>;
  if (!projectId) return <ProjectList me={me} workspaceId={workspaceId} onOpen={setProjectId} onLogout={logout} onUnauthorized={onUnauthorized}/>;
  return <StudioView key={projectId} me={me} workspaceId={workspaceId} projectId={projectId} onBack={() => setProjectId(null)} onLogout={logout} onUnauthorized={onUnauthorized}/>;
}

function LoginScreen({ onLoggedIn, initialError }: { onLoggedIn: (me: Me) => void; initialError: string | null }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const ssoError = typeof window !== "undefined" ? new URLSearchParams(window.location.search).get("sso_error") : null;
  const [error, setError] = useState<string | null>(initialError ?? (ssoError ? SSO_ERRORS[ssoError] ?? "Đăng nhập SSO thất bại." : null));
  const [config, setConfig] = useState<AuthConfig>({ localLogin: true, oidc: false, oidcLoginUrl: "/oauth2/authorization/oidc" });
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [displayName, setDisplayName] = useState("");
  const [inviteCode, setInviteCode] = useState("");
  useEffect(() => { api.authConfig().then(setConfig).catch(() => undefined); }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(null);
    try {
      if (mode === "signup") await api.register(username.trim().toLowerCase(), password, displayName.trim(), inviteCode.trim());
      onLoggedIn(await api.login(mode === "signup" ? username.trim().toLowerCase() : username.trim(), password));
    }
    catch (e) { setError(e instanceof ApiError && e.status === 401 ? "Sai tên đăng nhập hoặc mật khẩu." : errText(e, mode === "signup" ? "Đăng ký thất bại." : "Đăng nhập thất bại.")); setPassword(""); }
    finally { setBusy(false); }
  }

  return (
    <main className="boot">
      <form className="authCard" onSubmit={(e) => void submit(e)}>
        <h1>System Web Studio</h1>
        <p>{mode === "signup" ? "Tạo tài khoản mới. Mỗi tài khoản có workspace riêng." : "Đăng nhập để tiếp tục."}</p>
        {config.oidc ? <a className="button primary ssoButton" href={config.oidcLoginUrl}>Đăng nhập bằng SSO</a> : null}
        {config.localLogin ? <>
        <Field label="Tên đăng nhập"><input autoFocus autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required/></Field>
        {mode === "signup" ? <Field label="Tên hiển thị"><input autoComplete="name" maxLength={80} value={displayName} onChange={(e) => setDisplayName(e.target.value)}/></Field> : null}
        <Field label="Mật khẩu"><input type="password" autoComplete={mode === "signup" ? "new-password" : "current-password"} minLength={mode === "signup" ? 6 : undefined} value={password} onChange={(e) => setPassword(e.target.value)} required/></Field>
        {mode === "signup" ? <p className="hint">Tên đăng nhập 3–40 ký tự (a–z, 0–9, . _ -). Mật khẩu tối thiểu 6 ký tự, gồm cả chữ và số.</p> : null}
        {mode === "signup" && config.signupInviteRequired ? <Field label="Mã mời"><input autoComplete="off" value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} required/></Field> : null}
        {error ? <p className="formError" role="alert">{error}</p> : null}
        <button className="button primary" disabled={busy || !username || !password}>{busy ? (mode === "signup" ? "Đang tạo tài khoản…" : "Đang đăng nhập…") : (mode === "signup" ? "Tạo tài khoản" : "Đăng nhập")}</button>
        {config.signup ? <button type="button" className="button ghost" onClick={() => { setMode(mode === "login" ? "signup" : "login"); setError(null); }}>{mode === "login" ? "Chưa có tài khoản? Đăng ký" : "Đã có tài khoản? Đăng nhập"}</button> : null}
        </> : (error ? <p className="formError" role="alert">{error}</p> : null)}
      </form>
    </main>
  );
}

function ProjectList({ me, workspaceId, onOpen, onLogout, onUnauthorized }: {
  me: Me; workspaceId: string; onOpen: (id: string) => void; onLogout: () => void; onUnauthorized: () => void;
}) {
  const [projects, setProjects] = useState<ApiProject[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const role = me.workspaces.find((w) => w.id === workspaceId)?.role;
  const canCreate = role === "WORKSPACE_ADMIN" || role === "EDITOR" || me.roles.includes("ADMIN");

  const load = useCallback(() => api.listProjects(workspaceId).then(setProjects).catch((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) onUnauthorized(); else setError(errText(e, "Không tải được danh sách project."));
  }), [workspaceId, onUnauthorized]);
  useEffect(() => { void load(); }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true); setError(null);
    try { const p = await api.createProject(workspaceId, name.trim()); onOpen(p.id); }
    catch (e) { setError(errText(e, "Không tạo được project.")); setBusy(false); }
  }

  return (
    <main className="boot listPage">
      <div className="authCard wide">
        <div className="listHeader"><h1>Project</h1><div><span className="savedPill">{me.displayName}</span> <button className="button ghost" onClick={() => void onLogout()}>Đăng xuất</button></div></div>
        {error ? <p className="formError" role="alert">{error}</p> : null}
        {projects === null ? <div role="status">Đang tải…</div> : projects.length === 0 ? <p>Chưa có project nào{canCreate ? ". Hãy tạo project đầu tiên." : "."}</p> : (
          <ul className="projectList">{projects.map((p) => (
            <li key={p.id}><button onClick={() => onOpen(p.id)}><b>{p.name}</b><span>{p.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"} · cập nhật {fmt(p.updatedAt)}</span></button></li>
          ))}</ul>
        )}
        {canCreate ? (
          <form className="inlineForm" onSubmit={(e) => void create(e)}>
            <input placeholder="Tên project mới" maxLength={160} value={name} onChange={(e) => setName(e.target.value)}/>
            <button className="button primary" disabled={busy || !name.trim()}>{busy ? "Đang tạo…" : "Tạo project"}</button>
          </form>
        ) : null}
      </div>
    </main>
  );
}

type Panel = null | "history" | "settings" | "content" | "assets" | "publish" | "members";
type Msg = { id: string; role: "user" | "assistant"; content: string; meta?: string[] };

function StudioView({ me, workspaceId, projectId, onBack, onLogout, onUnauthorized }: {
  me: Me; workspaceId: string; projectId: string; onBack: () => void; onLogout: () => void; onUnauthorized: () => void;
}) {
  const [project, setProject] = useState<ApiProject | null>(null);
  const [schema, setSchema] = useState<PageSchema | null>(null);
  const [revision, setRevision] = useState(0);
  const [versions, setVersions] = useState<VersionSummary[]>([]);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [prompt, setPrompt] = useState("");
  const [device, setDevice] = useState<DeviceMode>("desktop");
  const [pane, setPane] = useState<"conversation" | "preview">("conversation");
  const [panel, setPanel] = useState<Panel>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [ai, setAi] = useState<AiStatus | null>(null);
  const [model, setModel] = useState<string>(() => { try { return localStorage.getItem("studio-ai-model") ?? "auto"; } catch { return "auto"; } });

  const can = (permission: string) => project?.permissions.includes(permission) ?? false;
  const guard = useCallback((e: unknown, fallback: string) => {
    if (e instanceof ApiError && e.status === 401) { onUnauthorized(); return; }
    setNotice(errText(e, fallback));
  }, [onUnauthorized]);

  const toMessages = (items: PromptHistoryItem[]): Msg[] => [...items].reverse().flatMap((p) => [
    { id: `${p.id}-u`, role: "user" as const, content: p.text },
    { id: `${p.id}-a`, role: "assistant" as const, content: p.assistantMessage, meta: [p.outcome] }
  ]);

  const reload = useCallback(async () => {
    const [p, s, v, h] = await Promise.all([api.getProject(workspaceId, projectId), api.getSchema(workspaceId, projectId), api.listVersions(workspaceId, projectId), api.listPrompts(workspaceId, projectId)]);
    setProject(p); setSchema(s.schema); setRevision(s.revision); setVersions(v); setMessages(toMessages(h));
  }, [workspaceId, projectId]);

  useEffect(() => { api.aiStatus().then(setAi).catch(() => undefined); }, []);
  useEffect(() => { try { localStorage.setItem("studio-ai-model", model); } catch { /* per-viewer convenience only */ } }, [model]);
  // a remembered model that is no longer offered falls back to "auto"
  const effectiveModel = ai?.configured ? (model === "auto" || model === "mock" || ai.models.some((m) => m.id === model) ? model : "auto") : "mock";

  useEffect(() => {
    reload().catch((e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onUnauthorized(); else setLoadError(errText(e, "Không tải được project."));
    });
  }, [reload, onUnauthorized]);

  /** Conflicts mean someone else changed the project: show the server's state instead of overwriting it. */
  async function run<T>(label: string, fn: () => Promise<T>, fallback: string): Promise<T | undefined> {
    setBusy(label);
    try { return await fn(); }
    catch (e) {
      if (e instanceof ApiError && e.isConflict && e.code === "REVISION_CONFLICT") {
        setNotice("Project vừa được thay đổi ở nơi khác. Đã tải lại bản mới nhất, hãy thử lại thao tác.");
        await reload().catch(() => undefined);
      } else guard(e, fallback);
      return undefined;
    } finally { setBusy(null); }
  }

  async function submitPrompt() {
    const text = prompt.trim();
    if (!text || busy || !can("PROJECT_EDIT")) return;
    setMessages((m) => [...m, { id: `local-${Date.now()}`, role: "user", content: text }]);
    setPrompt("");
    const r = await run("prompt", () => api.sendPrompt(workspaceId, projectId, text, revision, ai?.configured ? effectiveModel : undefined), "Không thể cập nhật website.");
    if (!r) return;
    setSchema(r.pageSchema); setRevision(r.revision);
    setMessages((m) => [...m, { id: r.promptId, role: "assistant", content: r.message.content, meta: [r.outcome, ...(r.model && r.model !== "mock" ? [r.model] : r.provider === "mock" ? ["mô phỏng"] : [])] }]);
    if (r.version) setVersions(await api.listVersions(workspaceId, projectId).catch(() => versions));
  }

  async function applyOps(ops: SchemaOperation[], summary: string): Promise<boolean> {
    const r = await run("edit", () => api.patchSchema(workspaceId, projectId, revision, ops, summary), "Không lưu được thay đổi.");
    if (!r) return false;
    setSchema(r.schema); setRevision(r.revision);
    setVersions(await api.listVersions(workspaceId, projectId).catch(() => versions));
    setNotice("Đã lưu thay đổi và tạo phiên bản mới.");
    return true;
  }

  async function restore(v: VersionSummary) {
    if (!window.confirm(`Khôi phục phiên bản ${v.versionNumber}? Một phiên bản mới sẽ được tạo; lịch sử cũ được giữ nguyên.`)) return;
    const r = await run("restore", () => api.restoreVersion(workspaceId, projectId, v.id, revision), "Không khôi phục được phiên bản.");
    if (!r) return;
    setSchema(r.schema); setRevision(r.revision);
    setVersions(await api.listVersions(workspaceId, projectId).catch(() => versions));
    setNotice(`Đã khôi phục phiên bản ${v.versionNumber} thành phiên bản ${r.version.versionNumber}.`);
  }

  async function saveSettings(patch: Partial<ApiProject>) {
    const p = await run("settings", () => api.updateProject(workspaceId, projectId, revision, patch), "Không lưu được cài đặt.");
    if (!p) return;
    setProject(p); setRevision(p.revision); setPanel(null); setNotice("Đã lưu cài đặt project.");
  }

  const previewDocument = useMemo(() => (schema ? renderSchemaDocument(schema) : ""), [schema]);

  if (loadError) return <div className="boot" role="alert"><p>{loadError}</p><button className="button" onClick={onBack}>Quay lại danh sách</button></div>;
  if (!project || !schema) return <div className="boot" role="status"><div className="spinner"/><div>Đang tải project…</div></div>;
  const readOnly = !can("PROJECT_EDIT");

  return (
    <div className="studio">
      <header className="topbar">
        <div className="brand">
          <button className="button icon" aria-label="Danh sách project" onClick={onBack}>←</button>
          <div><div className="projectName">{project.name}</div><div className="projectMeta">r{revision} • {me.displayName}{readOnly ? " • chỉ xem" : ""}</div></div>
        </div>
        <div className="topActions">
          <button className="button ghost" onClick={() => setPanel("history")}>Lịch sử</button>
          <button className="button ghost" disabled={readOnly} onClick={() => setPanel("content")}>Chỉnh sửa</button>
          <button className="button ghost" onClick={() => setPanel("assets")}>Tệp</button>
          {can("PROJECT_MEMBERS") ? <button className="button ghost" onClick={() => setPanel("members")}>Thành viên</button> : null}
          <button className="button primary" disabled={!can("PROJECT_PUBLISH") || busy !== null} title={can("PROJECT_PUBLISH") ? undefined : "Bạn không có quyền xuất bản"} onClick={() => setPanel("publish")}>Xuất bản</button>
          <button className="button icon" aria-label="Cài đặt" disabled={!can("PROJECT_SETTINGS")} onClick={() => setPanel("settings")}>⚙</button>
          <button className="button ghost" onClick={() => void onLogout()}>Đăng xuất</button>
        </div>
      </header>

      <nav className="mobilePaneTabs" aria-label="Studio view">
        <button className={pane === "conversation" ? "active" : ""} onClick={() => setPane("conversation")}>Hội thoại</button>
        <button className={pane === "preview" ? "active" : ""} onClick={() => setPane("preview")}>Xem trước</button>
      </nav>

      <main className={`workspace pane-${pane}`}>
        <section className="promptPane">
          <div className="conversation">
            <div className="intro"><h1>Bạn muốn thay đổi điều gì?</h1><p>Mỗi thay đổi được kiểm tra theo registry và lưu thành một phiên bản.</p></div>
            {messages.map((m) => (
              <div className={`message ${m.role}`} key={m.id}><div className="bubble"><div>{m.content}</div>
                {m.meta?.length ? <div className="chips">{m.meta.map((x) => <span className="chip" key={x}>{x}</span>)}</div> : null}</div></div>
            ))}
            {busy === "prompt" ? <div className="message assistant"><div className="bubble typing" role="status">Đang cập nhật bản xem trước…</div></div> : null}
          </div>
          <div className="composer"><div className="composerBox">
            <textarea value={prompt} disabled={readOnly} onChange={(e) => setPrompt(e.target.value)} maxLength={2000}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void submitPrompt(); } }}
              placeholder={readOnly ? "Bạn chỉ có quyền xem project này." : "Ví dụ: Thêm bảng so sánh 3 sản phẩm…"}/>
            <div className="composerFooter">
              {ai?.configured ? (
                <label className="aiPicker"><span className="srOnly">Model AI</span>
                  <select aria-label="Model AI" value={effectiveModel} onChange={(e) => setModel(e.target.value)} disabled={busy !== null}>
                    <option value="auto">AI · tự động (các model miễn phí)</option>
                    {ai.models.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.id}</option>)}
                    <option value="mock">Mô phỏng (không gọi AI)</option>
                  </select></label>
              ) : <span title={ai?.dataNotice}>AI: mô phỏng (chưa có key OpenRouter)</span>}
              <button className="sendButton" disabled={busy !== null || !prompt.trim() || readOnly} onClick={() => void submitPrompt()}>{busy === "prompt" ? "Đang xử lý…" : "Gửi ↑"}</button></div>
            {ai?.configured && effectiveModel !== "mock" ? <p className="aiNotice">{ai.dataNotice} Giới hạn {ai.dailyLimitPerUser} lượt AI/ngày/người dùng.</p> : null}
          </div></div>
        </section>

        <section className="previewPane">
          <div className="previewToolbar">
            <div className="toolbarGroup"><span className="toolbarLabel">Xem trước</span>
              <div className="segmented">{(["desktop", "tablet", "mobile"] as DeviceMode[]).map((m) => (
                <button key={m} className={device === m ? "active" : ""} onClick={() => setDevice(m)}>{m === "desktop" ? "Máy tính" : m === "tablet" ? "Máy tính bảng" : "Điện thoại"}</button>))}</div></div>
            <div className="toolbarGroup"><span className="environmentBadge">Backend</span></div>
          </div>
          <div className={`canvasViewport viewport-${device}`}><iframe className="previewFrame" title="Website preview" sandbox="" srcDoc={previewDocument}/></div>
        </section>
      </main>

      {notice ? <button className="toast" onClick={() => setNotice(null)}>{notice}</button> : null}

      {panel === "history" ? <Drawer title="Lịch sử phiên bản" sub="Khôi phục tạo một phiên bản mới, không xóa lịch sử." onClose={() => setPanel(null)}>
        <div className="versionList">{versions.map((v) => (
          <article className="versionItem" key={v.id}>
            <div><b>Phiên bản {v.versionNumber}{v.current ? " · hiện tại" : ""}</b><span>{fmt(v.createdAt)}</span></div>
            <p>{v.summary}</p><small>{v.kind}{v.createdBy ? ` · ${v.createdBy}` : ""}</small>
            {v.restorable && can("PROJECT_EDIT") ? <button className="smallButton" disabled={busy !== null} onClick={() => void restore(v)}>{busy === "restore" ? "Đang khôi phục…" : "Khôi phục"}</button> : null}
          </article>))}</div>
      </Drawer> : null}

      {panel === "content" ? <ContentDrawer schema={schema} busy={busy === "edit"} onClose={() => setPanel(null)} onApply={applyOps}/> : null}
      {panel === "settings" ? <SettingsDrawer project={project} busy={busy === "settings"} onClose={() => setPanel(null)} onSave={saveSettings}/> : null}
      {panel === "assets" ? <AssetsDrawer workspaceId={workspaceId} projectId={projectId} canEdit={can("PROJECT_EDIT")} onClose={() => setPanel(null)} onError={(e) => guard(e, "Thao tác tệp thất bại.")}/> : null}
      {panel === "members" ? <MembersDrawer workspaceId={workspaceId} projectId={projectId} me={me} onClose={() => setPanel(null)} onError={(e) => guard(e, "Thao tác thành viên thất bại.")}/> : null}
      {panel === "publish" ? <PublishModal workspaceId={workspaceId} projectId={projectId} revision={revision} current={project.siteVisibility} onClose={() => { setPanel(null); void reload().catch(() => undefined); }} onUnauthorized={onUnauthorized}/> : null}
    </div>
  );
}

function Drawer({ title, sub, onClose, children }: { title: string; sub?: string; onClose: () => void; children: ReactNode }) {
  const dialog = useDialog(title, onClose);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="drawer" {...dialog.props}>
        <div className="drawerHeader"><div><h2 id={dialog.titleId}>{title}</h2>{sub ? <p>{sub}</p> : null}</div><button className="button icon" aria-label="Đóng" onClick={onClose}>✕</button></div>
        {children}
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="settingField"><span>{label}</span>{children}</label>;
}

const section = (schema: PageSchema, type: string): Section | undefined => schema.sections.find((s) => s.type === type);
type Item = { id: string; name: string; description: string };

/** Direct edit: same PATCH /schema endpoint as the AI path, so it is validated, versioned and audited identically. */
function ContentDrawer({ schema, busy, onClose, onApply }: {
  schema: PageSchema; busy: boolean; onClose: () => void; onApply: (ops: SchemaOperation[], summary: string) => Promise<boolean>;
}) {
  const hero = section(schema, "Hero");
  const grid = section(schema, "ProductGrid");
  const items = (grid?.props.items as Item[] | undefined) ?? [];
  const [title, setTitle] = useState(String(hero?.props.title ?? ""));
  const [description, setDescription] = useState(String(hero?.props.description ?? ""));
  const [drafts, setDrafts] = useState<Item[]>(items.map((i) => ({ ...i })));
  const [newName, setNewName] = useState("");

  async function save() {
    const ops: SchemaOperation[] = [];
    if (hero && title !== hero.props.title) ops.push({ type: "UPDATE_PROP", sectionId: hero.id, path: "title", value: title });
    if (hero && description !== hero.props.description) ops.push({ type: "UPDATE_PROP", sectionId: hero.id, path: "description", value: description });
    if (grid) {
      drafts.forEach((d) => {
        const o = items.find((i) => i.id === d.id);
        if (!o) return;
        (["name", "description"] as const).forEach((k) => { if (o[k] !== d[k]) ops.push({ type: "UPDATE_PROP", sectionId: grid.id, itemId: d.id, path: k, value: d[k] }); });
      });
      items.filter((o) => !drafts.some((d) => d.id === o.id)).forEach((o) => ops.push({ type: "REMOVE_ITEM", sectionId: grid.id, itemId: o.id }));
      drafts.filter((d) => !items.some((o) => o.id === d.id)).forEach((d) => ops.push({ type: "ADD_ITEM", sectionId: grid.id, item: d }));
    }
    if (!ops.length) { onClose(); return; }
    if (await onApply(ops, "Chỉnh sửa nội dung trực tiếp")) onClose();
  }

  function add() {
    const name = newName.trim();
    if (!name) return;
    const id = `p${Date.now().toString(36)}`;
    setDrafts([...drafts, { id, name, description: "" }]); setNewName("");
  }

  return (
    <Drawer title="Chỉnh sửa nội dung" sub="Thay đổi được kiểm tra và lưu thành một phiên bản." onClose={onClose}>
      {hero ? <section className="settingGroup"><h3>Hero</h3>
        <Field label="Tiêu đề"><input maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)}/></Field>
        <Field label="Mô tả"><textarea className="plainArea" maxLength={600} value={description} onChange={(e) => setDescription(e.target.value)}/></Field></section> : null}
      {grid ? <section className="settingGroup"><h3>Sản phẩm</h3>
        {drafts.map((d, i) => (
          <div className="itemEditor" key={d.id}>
            <input aria-label="Tên sản phẩm" maxLength={120} value={d.name} onChange={(e) => setDrafts(drafts.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}/>
            <input aria-label="Mô tả sản phẩm" maxLength={400} value={d.description} onChange={(e) => setDrafts(drafts.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))}/>
            <button className="smallButton danger" onClick={() => setDrafts(drafts.filter((_, j) => j !== i))}>Xóa</button>
          </div>))}
        <div className="inlineForm"><input placeholder="Tên sản phẩm mới" maxLength={120} value={newName} onChange={(e) => setNewName(e.target.value)}/><button className="button" onClick={add} disabled={!newName.trim()}>Thêm</button></div>
      </section> : null}
      <div className="drawerActions"><button className="button ghost" onClick={onClose}>Hủy</button><button className="button primary" disabled={busy || drafts.some((d) => !d.name.trim())} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</button></div>
    </Drawer>
  );
}

function SettingsDrawer({ project, busy, onClose, onSave }: { project: ApiProject; busy: boolean; onClose: () => void; onSave: (patch: Partial<ApiProject>) => Promise<void> }) {
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
    </Drawer>
  );
}

function AssetsDrawer({ workspaceId, projectId, canEdit, onClose, onError }: { workspaceId: string; projectId: string; canEdit: boolean; onClose: () => void; onError: (e: unknown) => void }) {
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

  return (
    <Drawer title="Tệp của project" sub="PNG, JPEG, WebP, GIF hoặc PDF. Lưu trong MinIO qua URL ký sẵn." onClose={onClose}>
      {canEdit ? <label className="uploadBox">{uploading ? "Đang tải lên…" : "Chọn tệp để tải lên"}
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp,image/gif,application/pdf" disabled={uploading} onChange={(e) => void upload(e.target.files?.[0])}/></label> : null}
      <div className="versionList">{assets === null ? <div role="status">Đang tải…</div> : assets.length === 0 ? <p className="hint">Chưa có tệp nào.</p> : assets.map((a) => (
        <article className="versionItem" key={a.id}>
          <div><b>{a.name}</b><span>{(a.size / 1024).toFixed(1)} KB</span></div>
          {a.contentType.startsWith("image/") && a.downloadUrl ? /* eslint-disable-next-line @next/next/no-img-element */ <img className="assetThumb" src={a.downloadUrl} alt={a.name}/> : null}
          {a.downloadUrl ? <a href={a.downloadUrl} target="_blank" rel="noreferrer noopener">Mở tệp</a> : null}
          {canEdit ? <button className="smallButton danger" onClick={() => { if (window.confirm(`Xóa ${a.name}?`)) api.deleteAsset(workspaceId, projectId, a.id).then(load).catch(onError); }}>Xóa</button> : null}
        </article>))}</div>
    </Drawer>
  );
}

const STATUS_LABEL: Record<string, string> = {
  QUEUED: "Đang chờ", POLICY_CHECK: "Kiểm tra chính sách", SECURITY_CHECK: "Kiểm tra bảo mật", BUILDING: "Đang build",
  DEPLOYING: "Đang triển khai", RUNNING: "Đang chạy", FAILED: "Thất bại", ROLLED_BACK: "Đã hoàn tác"
};

function PublishModal({ workspaceId, projectId, revision, current, onClose, onUnauthorized }: {
  workspaceId: string; projectId: string; revision: number; current: "PRIVATE" | "PUBLIC"; onClose: () => void; onUnauthorized: () => void;
}) {
  const [visibility, setVisibility] = useState<"PRIVATE" | "PUBLIC">(current);
  const [deployment, setDeployment] = useState<Deployment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // One key per dialog: clicking twice or retrying after a network error can never create a second deployment.
  const key = useRef<string>(`ui-${crypto.randomUUID()}`);
  const running = deployment !== null && !TERMINAL.includes(deployment.status);
  const dialog = useDialog("Xuất bản website", running ? null : onClose);        // cannot be dismissed with Escape mid-publish

  useEffect(() => {
    if (!deployment || TERMINAL.includes(deployment.status)) return;
    const t = setTimeout(() => {
      api.getDeployment(workspaceId, projectId, deployment.id).then(setDeployment).catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) onUnauthorized(); else setError(errText(e, "Không đọc được trạng thái triển khai."));
      });
    }, 800);
    return () => clearTimeout(t);
  }, [deployment, workspaceId, projectId, onUnauthorized]);

  async function start() {
    setSubmitting(true); setError(null);
    try { setDeployment(await api.publish(workspaceId, projectId, visibility, revision, key.current)); }
    catch (e) {
      if (e instanceof ApiError && e.status === 401) onUnauthorized();
      else setError(e instanceof ApiError && e.code === "REVISION_CONFLICT" ? "Project đã thay đổi. Đóng hộp thoại, kiểm tra bản mới rồi xuất bản lại." : errText(e, "Xuất bản thất bại."));
    } finally { setSubmitting(false); }
  }

  return (
    <div className="overlay modalOverlay"><div className="modal" {...dialog.props}>
      <h2 id={dialog.titleId}>Xuất bản website</h2>
      <p>Phiên bản hiện tại sẽ được đưa qua kiểm tra chính sách, bảo mật, build và triển khai.</p>
      {!deployment ? (["PRIVATE", "PUBLIC"] as const).map((v) => (
        <button className={`publishChoice ${visibility === v ? "selected" : ""}`} key={v} onClick={() => setVisibility(v)}>
          <b>{v === "PRIVATE" ? "Riêng tư" : "Công khai"}</b><span>{v === "PRIVATE" ? "Chỉ thành viên được cấp quyền." : "Mọi người có thể truy cập."}</span></button>
      )) : (
        <div className="deployBox" role="status" aria-live="polite">
          <b>{STATUS_LABEL[deployment.status] ?? deployment.status}</b>
          <ol>{deployment.events.map((ev, i) => <li key={i}>{STATUS_LABEL[ev.status] ?? ev.status}{ev.message ? ` — ${ev.message}` : ""}</li>)}</ol>
          {deployment.status === "RUNNING" && deployment.url ? <p>{deployment.mock ? "Đây là URL của nhà cung cấp mô phỏng (không phải website thật): " : "Website: "}<code>{deployment.url}</code></p> : null}
          {deployment.status === "FAILED" ? <p className="formError">Không xuất bản được: {deployment.error}</p> : null}
        </div>
      )}
      {error ? <p className="formError" role="alert">{error}</p> : null}
      <div className="modalActions">
        <button className="button ghost" onClick={onClose}>{deployment ? "Đóng" : "Hủy"}</button>
        {!deployment ? <button className="button primary" disabled={submitting} onClick={() => void start()}>{submitting ? "Đang gửi…" : "Xuất bản"}</button> : running ? <button className="button primary" disabled>Đang xử lý…</button> : null}
      </div>
    </div></div>
  );
}

function roleLabel(role: string) {
  return ({ WORKSPACE_ADMIN: "Quản trị workspace", OWNER: "Chủ sở hữu", EDITOR: "Biên tập", PUBLISHER: "Xuất bản", VIEWER: "Chỉ xem" } as Record<string, string>)[role] ?? role;
}

function MemberTable({ title, members, roles, currentUserId, onChange, onRemove, onAdd, busy, error }: {
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
              <td><b>{m.displayName ?? m.username}</b><small>{m.username}{m.email ? ` · ${m.email}` : ""}</small></td>
              <td><select aria-label={`Vai trò của ${m.username}`} value={m.role} disabled={busy || m.userId === currentUserId} onChange={(e) => onChange(m, e.target.value)}>
                {roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}</select></td>
              <td><button className="smallButton danger" disabled={busy} onClick={() => onRemove(m)} aria-label={`Xóa ${m.username}`}>{m.userId === currentUserId ? "Rời" : "Xóa"}</button></td>
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

function MembersDrawer({ workspaceId, projectId, me, onClose, onError }: { workspaceId: string; projectId: string; me: Me; onClose: () => void; onError: (e: unknown) => void }) {
  const wsRole = me.workspaces.find((w) => w.id === workspaceId)?.role;
  const wsAdmin = wsRole === "WORKSPACE_ADMIN" || wsRole === "ADMIN" || me.roles.includes("ADMIN");
  const [project, setProject] = useState<Member[] | null>(null);
  const [workspace, setWorkspace] = useState<Member[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<{ project: string | null; workspace: string | null }>({ project: null, workspace: null });

  const load = useCallback(() => {
    api.listProjectMembers(workspaceId, projectId).then(setProject).catch(onError);
    if (wsAdmin) api.listWorkspaceMembers(workspaceId).then(setWorkspace).catch(onError);
  }, [workspaceId, projectId, wsAdmin, onError]);
  useEffect(() => { load(); }, [load]);

  const who = (v: string) => (v.includes("@") ? { email: v.trim() } : { username: v.trim() });
  async function act(scope: "project" | "workspace", fn: () => Promise<unknown>): Promise<boolean> {
    setBusy(true); setErrors((e) => ({ ...e, [scope]: null }));
    try { await fn(); load(); return true; }
    catch (e) { if (e instanceof ApiError && e.status !== 401) setErrors((x) => ({ ...x, [scope]: errText(e, "Thao tác thất bại.") })); else onError(e); return false; }
    finally { setBusy(false); }
  }

  return (
    <Drawer title="Thành viên và quyền" sub="Quyền được kiểm tra ở máy chủ; thay đổi được ghi vào nhật ký kiểm toán." onClose={onClose}>
      <MemberTable title="Thành viên project" members={project} roles={PROJECT_ROLES} currentUserId={me.id} busy={busy} error={errors.project}
        onChange={(m, r) => void act("project", () => api.changeProjectMember(workspaceId, projectId, m.userId, r))}
        onRemove={(m) => { if (window.confirm(`Xóa ${m.username} khỏi project?`)) void act("project", () => api.removeProjectMember(workspaceId, projectId, m.userId)); }}
        onAdd={(v, r) => act("project", () => api.addProjectMember(workspaceId, projectId, who(v), r))}/>
      {wsAdmin ? <MemberTable title="Thành viên workspace" members={workspace} roles={WORKSPACE_ROLES} currentUserId={me.id} busy={busy} error={errors.workspace}
        onChange={(m, r) => void act("workspace", () => api.changeWorkspaceMember(workspaceId, m.userId, r))}
        onRemove={(m) => { if (window.confirm(`Xóa ${m.username} khỏi workspace? Họ cũng mất quyền ở mọi project.`)) void act("workspace", () => api.removeWorkspaceMember(workspaceId, m.userId)); }}
        onAdd={(v, r) => act("workspace", () => api.addWorkspaceMember(workspaceId, who(v), r))}/> : null}
    </Drawer>
  );
}
