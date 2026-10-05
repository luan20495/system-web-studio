"use client";

import { useEffect, useState, type ReactNode } from "react";
import { isDemoMode, studioApi } from "@/lib/api-client";
import { renderPreviewDocument } from "@/lib/preview-document";
import { useDialog } from "./useDialog";
import type { ChatMessage, DeviceMode, PageContent, Project, StudioSnapshot, Visibility } from "@/lib/types";

const initialContent: PageContent = {
  heroEyebrow: "",
  heroTitle: "",
  heroDescription: "",
  products: [],
  testimonials: [],
  showTestimonials: false,
  showComparison: false
};

export default function StudioShell() {
  const [snapshot, setSnapshot] = useState<StudioSnapshot | null>(null);
  const [content, setContent] = useState<PageContent>(initialContent);
  const [project, setProject] = useState<Project | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [prompt, setPrompt] = useState("");
  const [device, setDevice] = useState<DeviceMode>("desktop");
  const [activePane, setActivePane] = useState<"conversation" | "preview">("conversation");
  const [loading, setLoading] = useState(true);
  const [operation, setOperation] = useState<"prompt" | "settings" | "publish" | null>(null);
  const [saved, setSaved] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishVisibility, setPublishVisibility] = useState<Visibility>("private");
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    studioApi.getStudio()
      .then((data) => {
        setSnapshot(data);
        setProject(data.project);
        setContent(data.content);
        setMessages(data.messages);
        setPublishVisibility(data.project.visibility);
      })
      .catch((error: Error) => setNotice(error.message))
      .finally(() => setLoading(false));
  }, []);

  async function submitPrompt() {
    if (!project || !prompt.trim() || operation) return;
    const value = prompt.trim();
    setMessages((prev) => [...prev, { id: `local_${Date.now()}`, role: "user", content: value }]);
    setPrompt("");
    setOperation("prompt");
    setSaved(false);

    try {
      const result = await studioApi.sendPrompt(project.id, value, content);
      setContent(result.content);
      setMessages((prev) => [...prev, result.message]);
      if (result.version) {
        setSnapshot((prev) => prev ? { ...prev, versions: [result.version!, ...prev.versions] } : prev);
      }
      setSaved(true);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Không thể cập nhật website.");
    } finally {
      setOperation(null);
    }
  }

  async function saveProject(next: Project) {
    setOperation("settings");
    try {
      const updated = await studioApi.updateProject(next);
      setProject(updated);
      setPublishVisibility(updated.visibility);
      setSaved(true);
      setSettingsOpen(false);
      setNotice(isDemoMode ? "Demo: thay đổi chỉ tồn tại trong phiên này, chưa lưu vào backend." : "Đã lưu project settings.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Không thể lưu project.");
    } finally {
      setOperation(null);
    }
  }

  async function publish() {
    if (!project || operation) return;
    setOperation("publish");
    try {
      const result = await studioApi.publish(project.id, publishVisibility);
      setPublishOpen(false);
      if (result.status === "demo") {
        setNotice("Demo: chưa có website nào được deploy hoặc đổi quyền truy cập.");
      } else {
        setProject({ ...project, visibility: result.visibility });
        setNotice(`Publish thành công: ${result.url}`);
      }
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Publish thất bại.");
    } finally {
      setOperation(null);
    }
  }

  const previewDocument = renderPreviewDocument(content);

  if (loading) {
    return <div className="boot" role="status"><div className="spinner"/><div>Đang tải System Web Studio…</div></div>;
  }

  if (!project) {
    return <div className="boot" role="alert"><p>{notice ?? "Không tải được project."}</p><button className="button" onClick={() => window.location.reload()}>Thử tải lại</button></div>;
  }

  return (
    <div className="studio">
      <header className="topbar">
        <div className="brand">
          <div className="brandMark">✦</div>
          <div>
            <div className="projectName">{project.name}</div>
            <div className="projectMeta">{project.id} • {project.branch} • {project.owner}</div>
          </div>
        </div>

        <div className="topActions">
          <span className="savedPill">{isDemoMode ? "Demo" : saved ? "✓ Đã lưu" : "Đang lưu…"}</span>
          <button className="button ghost" onClick={() => setHistoryOpen(true)}>Lịch sử</button>
          <button className="button primary" disabled={operation !== null} onClick={() => setPublishOpen(true)}>Xuất bản</button>
          <button className="button icon" aria-label="Cài đặt" onClick={() => setSettingsOpen(true)}>⚙</button>
          <details className="mobileMore" onClick={(event) => {
            if (event.target instanceof HTMLButtonElement) event.currentTarget.open = false;
          }}>
            <summary aria-label="More actions">•••</summary>
            <div className="mobileMoreMenu">
              <button onClick={() => setHistoryOpen(true)}>Lịch sử</button>
              <button onClick={() => setSettingsOpen(true)}>Cài đặt</button>
            </div>
          </details>
        </div>
      </header>

      <nav className="mobilePaneTabs" aria-label="Studio view">
        <button className={activePane === "conversation" ? "active" : ""} aria-pressed={activePane === "conversation"} onClick={() => setActivePane("conversation")}>Hội thoại</button>
        <button className={activePane === "preview" ? "active" : ""} aria-pressed={activePane === "preview"} onClick={() => setActivePane("preview")}>Xem trước</button>
      </nav>

      <main className={`workspace pane-${activePane}`}>
        <section className="promptPane">
          <div className="conversation">
            <div className="intro">
              <h1>Bạn muốn thay đổi điều gì?</h1>
                <p>{isDemoMode ? "Tiếp tục chỉnh sửa trong bản demo. Các thay đổi chưa được lưu vào backend." : "Mô tả thay đổi cho website của bạn."}</p>
            </div>

            {messages.map((message) => (
              <div className={`message ${message.role}`} key={message.id}>
                <div className="bubble">
                  <div>{message.content}</div>
                  {message.meta?.length ? (
                    <div className="chips">{message.meta.map((item) => <span className="chip" key={item}>{item}</span>)}</div>
                  ) : null}
                </div>
              </div>
            ))}

            {operation ? <div className="message assistant"><div className="bubble typing" role="status">{operation === "prompt" ? "Đang cập nhật bản xem trước…" : operation === "settings" ? "Đang lưu cài đặt…" : "Đang xuất bản…"}</div></div> : null}
          </div>

          <div className="composer">
            <div className="composerBox">
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    void submitPrompt();
                  }
                }}
                placeholder="Ví dụ: Thêm bảng so sánh 3 sản phẩm…"
              />
              <div className="composerFooter">
                <span>{isDemoMode ? "Demo · approved blocks only" : "Schema changes are versioned"}</span>
                <button className="sendButton" disabled={operation !== null || !prompt.trim()} onClick={() => void submitPrompt()}>
                  {operation === "prompt" ? "Đang xử lý…" : "Gửi ↑"}
                </button>
              </div>
            </div>
          </div>
        </section>

        <section className="previewPane">
          <div className="previewToolbar">
            <div className="toolbarGroup">
              <span className="toolbarLabel">Xem trước</span>
              <div className="segmented">
                {(["desktop", "tablet", "mobile"] as DeviceMode[]).map((mode) => (
                  <button key={mode} className={device === mode ? "active" : ""} onClick={() => setDevice(mode)}>
                    {mode === "desktop" ? "Máy tính" : mode === "tablet" ? "Máy tính bảng" : "Điện thoại"}
                  </button>
                ))}
              </div>
            </div>

            <div className="toolbarGroup">
              <span className="environmentBadge">{isDemoMode ? "Demo · local state" : "Backend"}</span>
              <button className="smallButton" onClick={() => setSettingsOpen(true)}>Cài đặt project</button>
            </div>
          </div>

          <div className={`canvasViewport viewport-${device}`}>
            <iframe className="previewFrame" title="Website preview" sandbox="" srcDoc={previewDocument}/>
          </div>
        </section>
      </main>

      {notice ? <button className="toast" onClick={() => setNotice(null)}>{notice}</button> : null}

      {settingsOpen ? <SettingsDrawer project={project} busy={operation === "settings"} onClose={() => setSettingsOpen(false)} onSave={saveProject}/> : null}
      {historyOpen ? <HistoryDrawer versions={snapshot?.versions ?? []} onClose={() => setHistoryOpen(false)}/> : null}
      {publishOpen ? (
        <PublishModal
          busy={operation === "publish"}
          visibility={publishVisibility}
          onVisibility={setPublishVisibility}
          onClose={() => setPublishOpen(false)}
          onPublish={publish}
        />
      ) : null}
    </div>
  );
}

function SettingsDrawer({ project, busy, onClose, onSave }: {
  project: Project;
  busy: boolean;
  onClose: () => void;
  onSave: (project: Project) => Promise<void>;
}) {
  const [draft, setDraft] = useState(project);
  const dialog = useDialog("Cài đặt project", onClose);

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="drawer" {...dialog.props}>
        <div className="drawerHeader"><div><h2 id={dialog.titleId}>Cài đặt project</h2><p>Thông tin kỹ thuật và quyền truy cập.</p></div><button className="button icon" aria-label="Đóng" onClick={onClose}>✕</button></div>

        <Setting title="Chung">
          <Field label="Tên project"><input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })}/></Field>
          <Field label="Framework"><input value={draft.framework} onChange={(e) => setDraft({ ...draft, framework: e.target.value })}/></Field>
        </Setting>

        <Setting title="Truy cập">
          <Field label="Chế độ"><select value={draft.visibility} onChange={(e) => setDraft({ ...draft, visibility: e.target.value as Visibility })}><option value="private">Riêng tư</option><option value="public">Công khai</option></select></Field>
          <Field label="Xác thực"><select value={draft.authMode} onChange={(e) => setDraft({ ...draft, authMode: e.target.value as Project["authMode"] })}><option value="sso">SSO</option><option value="password">Tài khoản / Mật khẩu</option><option value="public">Công khai</option></select></Field>
        </Setting>

        <Setting title="Tên miền">
          <Field label="Tên miền xem trước"><input value={draft.domain} onChange={(e) => setDraft({ ...draft, domain: e.target.value })}/></Field>
          <Field label="Tên miền riêng"><input value={draft.customDomain ?? ""} placeholder="example.com" onChange={(e) => setDraft({ ...draft, customDomain: e.target.value })}/></Field>
        </Setting>

        <Setting title="Triển khai">
          <Field label="Chế độ"><select value={draft.deploymentMode} onChange={(e) => setDraft({ ...draft, deploymentMode: e.target.value as Project["deploymentMode"] })}><option value="auto">Tự động</option><option value="static">Tĩnh</option><option value="dynamic">Động</option></select></Field>
          <Field label="Nền tảng"><select value={draft.deploymentTarget} onChange={(e) => setDraft({ ...draft, deploymentTarget: e.target.value as Project["deploymentTarget"] })}><option value="self-host">Self-host</option><option value="aws">AWS</option><option value="azure">Azure</option><option value="gcp">GCP</option></select></Field>
        </Setting>

        <div className="drawerActions"><button className="button ghost" onClick={onClose}>Hủy</button><button className="button primary" disabled={busy} onClick={() => void onSave(draft)}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</button></div>
      </div>
    </div>
  );
}

function Setting({ title, children }: { title: string; children: ReactNode }) {
  return <section className="settingGroup"><h3>{title}</h3>{children}</section>;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="settingField"><span>{label}</span>{children}</label>;
}

function HistoryDrawer({ versions, onClose }: { versions: StudioSnapshot["versions"]; onClose: () => void }) {
  const dialog = useDialog("Lịch sử phiên bản", onClose);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="drawer" {...dialog.props}>
        <div className="drawerHeader"><div><h2 id={dialog.titleId}>Lịch sử phiên bản</h2><p>{isDemoMode ? "Bản ghi demo; chưa có Git hoặc chức năng khôi phục." : "Các phiên bản schema của project."}</p></div><button className="button icon" aria-label="Đóng" onClick={onClose}>✕</button></div>
        <div className="versionList">{versions.map((version) => <article className="versionItem" key={version.id}><div><b>{version.label}</b><span>{new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(version.createdAt))}</span></div><p>{version.summary}</p>{version.sourceRevision ? <code>{version.sourceRevision}</code> : <small>Chưa có bản xuất mã nguồn</small>}</article>)}</div>
      </div>
    </div>
  );
}

function PublishModal({ visibility, busy, onVisibility, onClose, onPublish }: {
  visibility: Visibility;
  busy: boolean;
  onVisibility: (value: Visibility) => void;
  onClose: () => void;
  onPublish: () => Promise<void>;
}) {
  const dialog = useDialog("Xuất bản website", busy ? null : onClose);
  return (
    <div className="overlay modalOverlay">
      <div className="modal" {...dialog.props}>
        <h2 id={dialog.titleId}>Xuất bản website</h2>
        <p>{isDemoMode ? "Bản demo không triển khai website và không thay đổi quyền truy cập." : "Backend sẽ kiểm tra quyền và xuất bản phiên bản project đã chọn."}</p>
        {(["private", "public"] as Visibility[]).map((value) => (
          <button className={`publishChoice ${visibility === value ? "selected" : ""}`} key={value} onClick={() => onVisibility(value)}>
            <b>{value === "private" ? "Riêng tư" : "Công khai"}</b>
            <span>{value === "private" ? "Chỉ thành viên được cấp quyền." : "Mọi người có thể truy cập."}</span>
          </button>
        ))}
        <div className="modalActions"><button className="button ghost" onClick={onClose}>Hủy</button><button className="button primary" disabled={busy} onClick={() => void onPublish()}>{busy ? "Đang xuất bản…" : "Xuất bản"}</button></div>
      </div>
    </div>
  );
}
