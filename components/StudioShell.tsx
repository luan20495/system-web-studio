"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { studioApi } from "@/lib/api-client";
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
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
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

  const reuse = snapshot?.registryReuse ?? 0;

  async function submitPrompt() {
    if (!project || !prompt.trim() || busy) return;
    const value = prompt.trim();
    setMessages((prev) => [...prev, { id: `local_${Date.now()}`, role: "user", content: value }]);
    setPrompt("");
    setBusy(true);
    setSaved(false);

    try {
      const result = await studioApi.sendPrompt(project.id, value, content);
      setContent(result.content);
      setMessages((prev) => [...prev, result.message]);
      setSnapshot((prev) => prev ? {
        ...prev,
        registryReuse: result.registryReuse,
        versions: [result.version, ...prev.versions]
      } : prev);
      setSaved(true);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Không thể cập nhật website.");
    } finally {
      setBusy(false);
    }
  }

  async function saveProject(next: Project) {
    setBusy(true);
    try {
      const updated = await studioApi.updateProject(next);
      setProject(updated);
      setPublishVisibility(updated.visibility);
      setSaved(true);
      setSettingsOpen(false);
      setNotice("Đã lưu project settings.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Không thể lưu project.");
    } finally {
      setBusy(false);
    }
  }

  async function publish() {
    if (!project) return;
    setBusy(true);
    try {
      const result = await studioApi.publish(project.id, publishVisibility);
      setProject({ ...project, visibility: result.visibility });
      setPublishOpen(false);
      setNotice(`Publish thành công: ${result.url}`);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Publish thất bại.");
    } finally {
      setBusy(false);
    }
  }

  const canvasClass = useMemo(() => `canvas ${device}`, [device]);

  if (loading) {
    return <div className="boot"><div className="spinner"/><div>Loading System Web Studio…</div></div>;
  }

  if (!project) {
    return <div className="boot">Không tải được project.</div>;
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
          <span className="savedPill">{saved ? "✓ Saved" : "Saving…"}</span>
          <button className="button ghost" onClick={() => setHistoryOpen(true)}>History</button>
          <button className="button primary" onClick={() => setPublishOpen(true)}>Publish</button>
          <button className="button icon" aria-label="Settings" onClick={() => setSettingsOpen(true)}>⚙</button>
        </div>
      </header>

      <main className="workspace">
        <section className="promptPane">
          <div className="conversation">
            <div className="intro">
              <h1>What do you want to build?</h1>
              <p>AI ưu tiên component có sẵn. Dữ liệu hiện đang chạy mock nhưng UI đã đi qua API layer.</p>
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

            {busy ? <div className="message assistant"><div className="bubble typing">AI is working…</div></div> : null}
          </div>

          <div className="composer">
            <div className="composerBox">
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    void submitPrompt();
                  }
                }}
                placeholder="Ví dụ: Thêm bảng so sánh 3 sản phẩm trước phần đánh giá…"
              />
              <div className="composerFooter">
                <span>Registry reuse: {reuse}%</span>
                <button className="sendButton" disabled={busy || !prompt.trim()} onClick={() => void submitPrompt()}>
                  {busy ? "Working…" : "Send ↑"}
                </button>
              </div>
            </div>
          </div>
        </section>

        <section className="previewPane">
          <div className="previewToolbar">
            <div className="toolbarGroup">
              <span className="toolbarLabel">Preview</span>
              <div className="segmented">
                {(["desktop", "tablet", "mobile"] as DeviceMode[]).map((mode) => (
                  <button key={mode} className={device === mode ? "active" : ""} onClick={() => setDevice(mode)}>
                    {mode[0].toUpperCase() + mode.slice(1)}
                  </button>
                ))}
              </div>
            </div>

            <div className="toolbarGroup">
              <span className="environmentBadge">Mock API</span>
              <button className="smallButton" onClick={() => setSettingsOpen(true)}>Project settings</button>
            </div>
          </div>

          <div className="canvasViewport">
            <div className={canvasClass}>
              <nav className="siteNav">
                <div className="siteLogo">KAROFI</div>
                <div className="siteMenu"><span>Sản phẩm</span><span>Công nghệ</span><span>Đánh giá</span><span>Liên hệ</span></div>
              </nav>

              <section className="hero">
                <div className="heroCopy">
                  <div className="siteEyebrow">{content.heroEyebrow}</div>
                  <h2>{content.heroTitle}</h2>
                  <p>{content.heroDescription}</p>
                  <div className="ctaRow"><button>Khám phá sản phẩm</button><button className="secondary">Nhận tư vấn</button></div>
                </div>
                <div className="productVisual"><div className="machine"/></div>
              </section>

              <section className="siteSection">
                <div className="sectionEyebrow">Sản phẩm nổi bật</div>
                <h3>Chọn giải pháp phù hợp với gia đình bạn</h3>
                <div className="productGrid">
                  {content.products.map((product) => (
                    <article className="productCard" key={product.id}>
                      <div className="productImage"/>
                      <div className="productBody"><b>{product.name}</b><span>{product.description}</span></div>
                    </article>
                  ))}
                </div>
              </section>

              {content.showComparison ? (
                <section className="siteSection alt">
                  <div className="sectionEyebrow">So sánh nhanh</div>
                  <h3>Chọn model phù hợp với nhu cầu</h3>
                  <div className="productGrid">
                    {content.products.slice(0, 3).map((product) => (
                      <article className="compareCard" key={product.id}><b>{product.name}</b><span>{product.description}</span></article>
                    ))}
                  </div>
                </section>
              ) : null}

              {content.showTestimonials ? (
                <section className="siteSection alt">
                  <div className="sectionEyebrow">Khách hàng</div>
                  <h3>Trải nghiệm thực tế</h3>
                  <div className="testimonialGrid">
                    {content.testimonials.map((item) => (
                      <article className="quoteCard" key={item.id}>
                        <div className="stars">{"★".repeat(item.rating)}</div>
                        <p>“{item.quote}”</p>
                        <b>{item.author} • {item.location}</b>
                      </article>
                    ))}
                  </div>
                </section>
              ) : null}

              <section className="siteSection">
                <div className="contactLayout">
                  <div><div className="sectionEyebrow">Tư vấn sản phẩm</div><h3>Để lại thông tin, chúng tôi sẽ liên hệ.</h3><p>Nhận tư vấn theo nhu cầu sử dụng, không gian và ngân sách.</p></div>
                  <div className="formGrid">
                    <div className="fakeInput">Họ và tên</div><div className="fakeInput">Số điện thoại</div>
                    <div className="fakeInput">Tỉnh / Thành phố</div><div className="fakeInput">Sản phẩm quan tâm</div>
                    <div className="fakeInput wide">Nội dung cần tư vấn</div>
                  </div>
                </div>
              </section>

              <footer className="siteFooter"><div><b>KAROFI</b><br/><span>Pure water • better living</span></div><div>Generated with System Web Studio</div></footer>
            </div>
          </div>
        </section>
      </main>

      {notice ? <button className="toast" onClick={() => setNotice(null)}>{notice}</button> : null}

      {settingsOpen ? <SettingsDrawer project={project} busy={busy} onClose={() => setSettingsOpen(false)} onSave={saveProject}/> : null}
      {historyOpen ? <HistoryDrawer versions={snapshot?.versions ?? []} onClose={() => setHistoryOpen(false)}/> : null}
      {publishOpen ? (
        <PublishModal
          busy={busy}
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

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="drawer">
        <div className="drawerHeader"><div><h2>Project Settings</h2><p>Technical details stay out of the main workspace.</p></div><button className="button icon" onClick={onClose}>✕</button></div>

        <Setting title="General">
          <Field label="Project name"><input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })}/></Field>
          <Field label="Framework"><input value={draft.framework} onChange={(e) => setDraft({ ...draft, framework: e.target.value })}/></Field>
        </Setting>

        <Setting title="Access">
          <Field label="Visibility"><select value={draft.visibility} onChange={(e) => setDraft({ ...draft, visibility: e.target.value as Visibility })}><option value="private">Private</option><option value="public">Public</option></select></Field>
          <Field label="Authentication"><select value={draft.authMode} onChange={(e) => setDraft({ ...draft, authMode: e.target.value as Project["authMode"] })}><option value="sso">SSO</option><option value="password">User / Password</option><option value="public">Public</option></select></Field>
        </Setting>

        <Setting title="Domain">
          <Field label="Preview domain"><input value={draft.domain} onChange={(e) => setDraft({ ...draft, domain: e.target.value })}/></Field>
          <Field label="Custom domain"><input value={draft.customDomain ?? ""} placeholder="example.com" onChange={(e) => setDraft({ ...draft, customDomain: e.target.value })}/></Field>
        </Setting>

        <Setting title="Deployment">
          <Field label="Mode"><select value={draft.deploymentMode} onChange={(e) => setDraft({ ...draft, deploymentMode: e.target.value as Project["deploymentMode"] })}><option value="auto">Auto</option><option value="static">Static</option><option value="dynamic">Dynamic</option></select></Field>
          <Field label="Target"><select value={draft.deploymentTarget} onChange={(e) => setDraft({ ...draft, deploymentTarget: e.target.value as Project["deploymentTarget"] })}><option value="self-host">Self-host</option><option value="aws">AWS</option><option value="azure">Azure</option><option value="gcp">GCP</option></select></Field>
        </Setting>

        <div className="drawerActions"><button className="button ghost" onClick={onClose}>Cancel</button><button className="button primary" disabled={busy} onClick={() => void onSave(draft)}>{busy ? "Saving…" : "Save changes"}</button></div>
      </aside>
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
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="drawer">
        <div className="drawerHeader"><div><h2>Version History</h2><p>Ready to map to real Git commits later.</p></div><button className="button icon" onClick={onClose}>✕</button></div>
        <div className="versionList">{versions.map((version) => <article className="versionItem" key={version.id}><div><b>{version.label}</b><span>{version.createdAt}</span></div><p>{version.summary}</p><code>{version.commitSha}</code></article>)}</div>
      </aside>
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
  return (
    <div className="overlay modalOverlay">
      <div className="modal">
        <h2>Publish website</h2>
        <p>Frontend flow is production-shaped: the future backend only needs to implement the publish endpoint.</p>
        {(["private", "public"] as Visibility[]).map((value) => (
          <button className={`publishChoice ${visibility === value ? "selected" : ""}`} key={value} onClick={() => onVisibility(value)}>
            <b>{value === "private" ? "Private" : "Public"}</b>
            <span>{value === "private" ? "Authenticated users only." : "Accessible from the Internet."}</span>
          </button>
        ))}
        <div className="modalActions"><button className="button ghost" onClick={onClose}>Cancel</button><button className="button primary" disabled={busy} onClick={() => void onPublish()}>{busy ? "Publishing…" : "Publish"}</button></div>
      </div>
    </div>
  );
}
