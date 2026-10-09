"use client";

import { useCallback, useEffect, useId, useMemo, useState } from "react";
import { ArrowLeft, ErrorBoundary, Settings, Sparkles, Tabs, toast, confirm } from "@xweb/ui";
import { useRouter, useSearchParams } from "next/navigation";
import { api, newIdempotencyKey } from "@/lib/http-api";
import { renderSchemaDocument } from "@/lib/schema-preview";
import type { ApiProject, AssetDto, BlockDto, PageSchema, RegistryComponent, SchemaOperation, VersionSummary } from "@/lib/http-types";
import type { DeviceMode } from "@/lib/types";
import { sectionLabel, sectionSummary } from "@/components/SectionInspector";
import type { AppDefinitionV2, DefinitionOperation } from "@xweb/types";
import { BuilderWorkspace } from "./builder/BuilderWorkspace";
import type { RuntimeCalls } from "./builder/TestPanel";
import type { DataManagementCalls } from "./builder/core/dataManagement";
import { backendFrom, type ProbeState } from "./builder/core/backend";
import { NOT_RENDERED } from "./builder/core/library";
import type { BlockOption } from "./builder/panels/ComponentsPanel";
import { canEditProject, canPublish, canShare, canViewProject, holdsStorageConstant, resolvePermissions } from "@xweb/permissions";
import { useSession } from "../session";
import { ErrorState, errText, fmtDate, StateView } from "../ui";
import { AssetsDrawer, DeviceIcon, Drawer, MembersDrawer, SettingsDrawer, suggestions } from "./drawers";
import { PublishModal } from "./ReleaseModal";
import { GuardedButton } from "./GuardedButton";
import { OverflowMenu } from "./OverflowMenu";
import { AiProgress } from "./AiProgress";
import { SaveBlockDrawer, SaveTemplateSection } from "./libraryPanels";
import { CodeWorkspace } from "./CodeWorkspace";
import { SiteDrawer } from "./SitePanels";
import { insertable } from "../library";
import { projectBase, S } from "./base";
import { nonceOfPage, toMessages } from "./workspace/runFailure";
import { useSaveMachine } from "./workspace/useSaveMachine";
import { useAiConversation } from "./workspace/useAiConversation";

type Mode = "ai" | "design" | "code";
type PanelName = "members" | "versions" | "assets" | "publish" | "settings" | "site";
const MODES: Mode[] = ["ai", "design", "code"];
const PANELS: PanelName[] = ["members", "versions", "assets", "publish", "settings", "site"];

export function ProjectWorkspace({ projectId, view }: { projectId: string; view?: string }) {
  const router = useRouter(); const params = useSearchParams(); const { me } = useSession();
  const base = projectBase(projectId);
  const lastModeKey = `ws-mode-${projectId}`;
  const mode: Mode = MODES.includes(view as Mode) ? (view as Mode) : ((() => { try { const m = sessionStorage.getItem(lastModeKey); return MODES.includes(m as Mode) ? (m as Mode) : "ai"; } catch { return "ai"; } })());
  const panel: PanelName | null = PANELS.includes(view as PanelName) ? (view as PanelName) : null;
  useEffect(() => { try { sessionStorage.setItem(lastModeKey, mode); } catch { /* ignore */ } }, [mode, lastModeKey]);
  const go = (to: string) => router.push(`${base}/${to}`);

  const [project, setProject] = useState<ApiProject | null>(null);
  const [schema, setSchema] = useState<PageSchema | null>(null);
  const [revision, setRevision] = useState(0);
  const [versions, setVersions] = useState<VersionSummary[]>([]);
  const [registry, setRegistry] = useState<RegistryComponent[]>([]);
  const [blocks, setBlocks] = useState<{ company: BlockDto[]; mine: BlockDto[] }>({ company: [], mine: [] });
  const [savingBlock, setSavingBlock] = useState(false);
  const [assets, setAssets] = useState<AssetDto[]>([]);
  /** page of a multi-page site being edited in Design mode ("home" = the root page) */
  const [pageId, setPageId] = useState("home");
  const [publicPublish, setPublicPublish] = useState<boolean | undefined>(undefined);
  useEffect(() => { api.authConfig().then((c) => setPublicPublish(c.publicPublish)).catch(() => undefined); }, []);
  /** one probe tells the Builder whether the V2 core (component-metadata, typed definition ops) is integrated on this server */
  const [probe, setProbe] = useState<ProbeState>({ status: "loading" });
  useEffect(() => { api.componentMetadata().then((metadata) => setProbe({ status: "ok", metadata })).catch((error) => setProbe({ status: "error", error })); }, []);
  const backend = useMemo(() => backendFrom(probe), [probe]);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [device, setDevice] = useState<DeviceMode>("desktop");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pane, setPane] = useState<"chat" | "preview">("chat"); const paneId = useId();
  const { busy, save, setSave, failedEdit, setFailedEdit, saveFailureRef, lastNoticeRef, onConflict, run, flight } = useSaveMachine();
  const ws = project?.workspaceId ?? "";
  // UX only (the server re-checks every call). The input is the permission list the server resolved for THIS project; no role name is read (permissions.ts / canonical.ts).
  const perms = useMemo(() => resolvePermissions(project?.permissions), [project?.permissions]);
  const mayEdit = canEditProject(perms), mayPublish = canPublish(perms), mayShare = canShare(perms), mayDelete = holdsStorageConstant(project?.permissions, "PROJECT_DELETE");
  const readOnly = !mayEdit;
  // page-level gate: a project whose resolved permissions lack APP_VIEW is not shown (APP_VIEW without APP_EDIT is NOT a reason to leave: it opens read-only)
  useEffect(() => { if (project?.name) document.title = `${project.name} · Xweb Studio`; }, [project?.name]);
  useEffect(() => { if (project && !canViewProject(perms)) router.replace("/auth/no-access?portal=studio&reason=app-view"); }, [project, perms, router]);

  const label = (type: string) => sectionLabel(type, registry.find((c) => c.id === type)?.name);
  const refreshVersions = async () => setVersions(await api.listVersions(ws, projectId).catch(() => versions));
  const chat = useAiConversation({ ws, projectId, project, schema, revision, readOnly, busy, mode, run, setSchema, setRevision, refreshVersions, label,
    handOver: { prompt: params.get("prompt"), clear: () => router.replace(`${base}/ai`) } });
  const { ai, setAi, messages, setMessages, prompt, setPrompt, live, setModel, effectiveModel, promptRef, submitPrompt, cancel, convRef, behind, onConversationScroll, jumpToNewest } = chat;

  const loadAssets = useCallback((w: string) => api.listAssets(w, projectId).then(setAssets).catch(() => undefined), [projectId]);
  const loadBlocks = useCallback(() => {
    Promise.all([api.blocks("company"), api.blocks("mine")]).then(([company, mine]) => setBlocks({ company, mine })).catch(() => undefined);
  }, []);
  const reload = useCallback(async () => {
    const p = await api.lookupProject(projectId);
    // code projects have no page schema: CodeWorkspace loads its own data
    if (p.appType === "STATIC_APP") { setProject(p); return; }
    const [s, v, h] = await Promise.all([api.getSchema(p.workspaceId, projectId), api.listVersions(p.workspaceId, projectId), api.listPrompts(p.workspaceId, projectId)]);
    setProject(p); setSchema(s.schema); setRevision(s.revision); setVersions(v); setMessages(toMessages(h));
    setSave((x) => (x.at ? x : { state: "saved", at: new Date(p.updatedAt) }));
    void loadAssets(p.workspaceId);
  }, [projectId, loadAssets]); // eslint-disable-line react-hooks/exhaustive-deps
  onConflict.current = reload;
  useEffect(() => { reload().catch(setLoadError); api.components().then(setRegistry).catch(() => undefined); loadBlocks(); api.aiStatus(ws).then(setAi).catch(() => undefined); }, [reload]); // eslint-disable-line react-hooks/exhaustive-deps
  // signed download URLs expire after 10 minutes: refresh the asset map before that
  useEffect(() => { if (!ws) return; const t = setInterval(() => void loadAssets(ws), 8 * 60_000); return () => clearInterval(t); }, [ws, loadAssets]);

  async function applyOps(ops: (SchemaOperation | DefinitionOperation)[], summary: string, blockId?: string): Promise<boolean> {
    if (flight.current !== null) return false;           // another write / AI request is in flight (M-020): this one is dropped, not sent twice and not reported as a failure
    const r = await run("edit", () => api.patchSchema(ws, projectId, revision, ops, summary, blockId), "Không lưu được thay đổi.");
    if (!r) { setFailedEdit(saveFailureRef.current === "retryable" ? { ops, summary, blockId } : null); return false; }
    setFailedEdit(null); setSchema(r.schema); setRevision(r.revision); void refreshVersions(); return true;
  }
  const retrySave = () => { if (failedEdit) void applyOps(failedEdit.ops, failedEdit.summary, failedEdit.blockId); };

  async function restore(v: VersionSummary) {
    if (!(await confirm({ title: `Khôi phục phiên bản ${v.versionNumber}?`, message: "Nội dung của phiên bản này trở thành một phiên bản MỚI. Lịch sử cũ giữ nguyên, nên có thể quay lại bản hiện tại sau đó.", confirmLabel: "Khôi phục phiên bản này" }))) return;
    const r = await run("restore", () => api.restoreVersion(ws, projectId, v.id, revision), "Không khôi phục được phiên bản.");
    if (!r) return;
    setSchema(r.schema); setRevision(r.revision); void refreshVersions(); toast.success(`Đã khôi phục phiên bản ${v.versionNumber} thành phiên bản ${r.version.versionNumber}.`);
  }
  async function archive() {
    if (!project || !(await confirm({ title: `Lưu trữ “${project.name}”?`, message: "Ứng dụng chỉ còn xem được và website bị gỡ khỏi mạng. Dữ liệu và phiên bản được giữ; có thể khôi phục sau.", confirmLabel: "Lưu trữ ứng dụng", danger: true }))) return;
    const r = await run("settings", () => api.archiveProject(ws, projectId), "Không lưu trữ được.");
    if (r) { go(mode); void reload(); }
  }
  async function saveSettings(patch: Partial<ApiProject>) {
    const p = await run("settings", () => api.updateProject(ws, projectId, revision, patch), "Không lưu được cài đặt.");
    if (p) { setProject(p); setRevision(p.revision); go(mode); toast.success("Đã lưu cài đặt."); }
  }
  const pageSections = useMemo(() => (!schema ? [] : pageId === "home" ? schema.sections : schema.pages?.find((x) => x.id === pageId)?.sections ?? schema.sections), [schema, pageId]);
  useEffect(() => { if (schema && pageId !== "home" && !schema.pages?.some((x) => x.id === pageId)) setPageId("home"); }, [schema, pageId]);
  const onPage = pageId === "home" ? {} : { pageId };

  /** A block inserts an ordinary section of its approved base component with the block's props (validated like any edit). */
  async function addBlock(b: BlockDto) {
    if (!schema || !b.current) return;
    const id = `${b.baseComponent.toLowerCase()}-${Math.random().toString(36).slice(2, 7)}`;
    const footer = pageSections.find((s) => s.type === "Footer");
    const props = JSON.parse(JSON.stringify(b.current.props)) as Record<string, unknown>;
    const ok = await applyOps([{ type: "ADD_SECTION", ...onPage, sectionType: b.baseComponent, sectionId: id, props, ...(footer && b.baseComponent !== "Footer" ? { beforeSectionId: footer.id } : {}) }], `Thêm khối ${b.name}`, b.id);
    if (ok) setSelectedId(id);
  }
  const assetUrls = useMemo(() => Object.fromEntries(assets.filter((a) => a.downloadUrl).map((a) => [a.id, a.downloadUrl!])), [assets]);
  const previewDocument = useMemo(() => (schema ? renderSchemaDocument(schema, {
    selectedId: null, interactive: false, nonce: nonceOfPage(), assets: assetUrls, pageId
  }) : ""), [schema, assetUrls, pageId]);

  /** Test mode talks to the real backend: TEST = the saved draft, no side effects, key per click (runtime-api.md). Rebuilt only when the project changes. */
  const runtime = useMemo<RuntimeCalls | undefined>(() => !ws || !projectId ? undefined : ({
    runQuery: (q) => api.appRuntime.runQuery(ws, projectId, q, { mode: "TEST" }),
    runAction: (a, key) => api.appRuntime.executeAction(ws, projectId, a, { mode: "TEST", idempotencyKey: key }),
    startWorkflow: (w, key) => api.appRuntime.startWorkflow(ws, projectId, w, { mode: "TEST", idempotencyKey: key }),
    getRun: (r) => api.appRuntime.workflowRun(ws, projectId, r),
    cancelRun: (r) => api.appRuntime.cancelWorkflowRun(ws, projectId, r),
    newKey: () => newIdempotencyKey("test"),
  }), [ws, projectId]);

  /** C3 Management API (MANAGEMENT_API.md): tenant is derived by the server from the workspace in the path; nothing but contract fields is sent. */
  const dataManagement = useMemo<DataManagementCalls | undefined>(() => !ws || !projectId ? undefined : ({
    connectors: async () => (await api.dataManagement.connectors(ws)).items,
    list: async () => (await api.dataManagement.list(ws)).items,
    create: (b) => api.dataManagement.create(ws, b),
    update: (id, b) => api.dataManagement.update(ws, id, b),
    remove: (id) => api.dataManagement.remove(ws, id),
    credential: (id) => api.dataManagement.credential(ws, id),
    setCredential: (id, c) => api.dataManagement.setCredential(ws, id, c),
    removeCredential: (id) => api.dataManagement.removeCredential(ws, id),
    test: (id) => api.dataManagement.testConnection(ws, id),
    listBindings: async () => (await api.dataManagement.listBindings(ws, projectId)).items,
    bind: (m, slot, id) => api.dataManagement.bind(ws, projectId, m, slot, id),
    unbind: (m, slot) => api.dataManagement.unbind(ws, projectId, m, slot),
  }), [ws, projectId]);

  if (loadError) return <div className="wsError"><ErrorState error={loadError} retry={() => { setLoadError(null); reload().catch(setLoadError); }}/><p><a className="btn xp-btnIcon" href={S("/projects")}><ArrowLeft size={14} aria-hidden="true"/> Danh sách ứng dụng</a></p></div>;
  if (project?.appType === "STATIC_APP") return <CodeWorkspace project={project} view={view} onProject={setProject}/>;
  if (!project || !schema) return <div className="wsError"><StateView kind="loading" title="Đang mở ứng dụng…"/></div>;
  // company blocks, then my own drafts (an approved block of mine is already in the company list)
  const blockOptions = [...blocks.company.map((b) => ({ b, who: "Công ty" })), ...blocks.mine.filter((b) => b.approvedVersion == null || b.status !== "APPROVED").map((b) => ({ b, who: "Của tôi" }))]
    .filter(({ b }) => insertable(b, registry, NOT_RENDERED));
  const selected = pageSections.find((s) => s.id === selectedId) ?? null;
  const latest = versions[0]?.versionNumber;

  const renderPreview = (o: { selectedId: string | null; interactive: boolean; pageId: string }) => renderSchemaDocument(schema, { selectedId: o.selectedId, interactive: o.interactive, nonce: nonceOfPage(), assets: assetUrls, pageId: o.pageId });
  const modeTabs = (
    <nav className="modeTabs" aria-label="Chế độ">
      {MODES.map((m) => <button key={m} className={mode === m ? "active" : ""} aria-pressed={mode === m} onClick={() => go(m)}>{m === "ai" ? <><Sparkles size={14} aria-hidden="true"/> AI</> : m === "design" ? "Design" : <>Code<small className="xp-navSoon"> Sắp có</small></>}</button>)}
    </nav>
  );

  return (
    <div className={`studio workspace3${mode === "design" ? " bx-root" : ""}`}>
      {mode === "design" ? <ErrorBoundary variant="inline" resetKeys={[projectId]} homeHref={S("/projects")} onReset={() => void reload().catch(() => undefined)}><BuilderWorkspace
        project={project} doc={schema as AppDefinitionV2} revision={revision} registry={registry} assets={assets} backend={backend} pageId={pageId} onPage={(id) => { setPageId(id); setSelectedId(null); }}
        selectedId={selectedId} onSelect={setSelectedId} device={device} onDevice={setDevice} busy={busy !== null} save={save} readOnly={readOnly} latest={latest}
        runtime={runtime} dataManagement={dataManagement} onRetrySave={failedEdit ? retrySave : undefined} lastFailure={() => lastNoticeRef.current} blocks={blockOptions.map(({ b, who }): BlockOption => ({ id: b.id, name: b.name, who, baseLabel: label(b.baseComponent) }))}
        applyOps={applyOps} addBlock={(id) => { const o = blockOptions.find(({ b }) => b.id === id); if (o) void addBlock(o.b); }}
        renderPreview={renderPreview} labelOf={label} summaryOf={sectionSummary}
        leading={<button className="button icon" aria-label="Danh sách ứng dụng" title="Danh sách ứng dụng" onClick={() => router.push(S("/projects"))}><ArrowLeft size={16} aria-hidden="true"/></button>}
        modeTabs={modeTabs}
        trailing={<>
          <button className="button ghost" onClick={() => go("site")}>Website</button>
          <button className="button ghost" onClick={() => go("versions")}>Phiên bản</button>
          <button className="button ghost" onClick={() => go("assets")}>Tệp</button>
          <GuardedButton className="button icon" aria-label="Cài đặt project" unavailable={!mayEdit} reason="Bạn không có quyền đổi cài đặt ứng dụng." onClick={() => go("settings")}><Settings size={16} aria-hidden="true"/></GuardedButton></>}
        goAi={() => go("ai")} openSite={() => go("site")} openMembers={() => go("members")} openPublish={() => go("publish")} saveBlock={() => setSavingBlock(true)}/></ErrorBoundary> : (
      <header className="topbar">
        <div className="brand">
          <button className="button icon" aria-label="Danh sách ứng dụng" title="Danh sách ứng dụng" onClick={() => router.push(S("/projects"))}><ArrowLeft size={16} aria-hidden="true"/></button>
          <div>
            <div className="projectName" role="heading" aria-level={1} title={project.name}>{project.name}</div>
            <div className="projectMeta">{latest ? `Phiên bản ${latest}` : "Chưa có phiên bản"} · revision {revision} · {project.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"}{readOnly ? " · chỉ xem" : ""}</div>
          </div>
          <span className={`saveState ${save.state}`} role="status" aria-live="polite">
            {save.state === "saving" ? "Đang lưu…" : save.state === "error" ? "Lưu thất bại" : `Đã lưu${save.at ? ` ${save.at.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}` : ""}`}
          </span>
        </div>
        {modeTabs}
        <div className="topActions">
          <button className="button ghost" onClick={() => go("site")}>Website</button>
          <button className="button ghost" onClick={() => go("versions")}>Phiên bản</button>
          <button className="button ghost" onClick={() => go("assets")}>Tệp</button>
          {mayShare ? <button className="button ghost" onClick={() => go("members")}>Chia sẻ</button> : null}
          <GuardedButton className="button icon" aria-label="Cài đặt project" unavailable={!mayEdit} reason="Bạn không có quyền đổi cài đặt ứng dụng." onClick={() => go("settings")}><Settings size={16} aria-hidden="true"/></GuardedButton>
          <OverflowMenu items={[
            { key: "site", label: "Website", onSelect: () => go("site") }, { key: "versions", label: "Phiên bản", onSelect: () => go("versions") }, { key: "assets", label: "Tệp", onSelect: () => go("assets") },
            ...(mayShare ? [{ key: "members", label: "Chia sẻ", onSelect: () => go("members") }] : []),
            { key: "settings", label: "Cài đặt project", onSelect: () => go("settings"), unavailable: !mayEdit, reason: "Bạn không có quyền đổi cài đặt." }]}/>
          <GuardedButton className="button primary" unavailable={!mayPublish} reason="Bạn không có quyền xuất bản (cần quyền APP_PUBLISH)." disabled={busy !== null} onClick={() => go("publish")}>Xuất bản</GuardedButton>
        </div>
      </header>)}

      {project.status === "ARCHIVED" ? <div className="archivedBanner" role="status">Ứng dụng đã được lưu trữ: chỉ xem, website đang ngoại tuyến.
        <button className="smallButton" onClick={() => void run("settings", () => api.restoreProject(ws, projectId), "Không khôi phục được (cần quyền chủ sở hữu hoặc quản trị).").then((r) => { if (r) void reload(); })}>Khôi phục</button></div> : null}
      {mode === "code" ? (
        <main className="codeMode">
          <div className="codeCard">
            <span className="pill pill-muted">Chưa triển khai</span>
            <h1>Code Mode cần kiến trúc sinh mã nguồn</h1>
            <p>Ứng dụng này được lưu dưới dạng <b>Page Schema (JSON)</b> và chỉ dùng component trong Company Component Registry. Hệ thống <b>chưa tạo repository mã nguồn</b>, nên không có file, terminal hay Git để hiển thị.</p>
            <p>Sinh mã nguồn, Git, sandbox build và runtime là một giai đoạn sản phẩm riêng, sẽ được thiết kế và duyệt trước khi triển khai.</p>
            <div className="row"><button className="btn primary" onClick={() => go("design")}>Chỉnh trực quan (Design)</button><button className="btn" onClick={() => go("ai")}>Chỉnh bằng AI</button></div>
          </div>
        </main>
      ) : mode === "ai" ? (
        <main className={`wsBody mode-${mode}`} data-pane={pane}>
          {/* phone (<= 767 px): ONE pane at a time (M-104). Both stay mounted, so the conversation, the draft prompt and the preview keep their state; CSS shows one. */}
          <div className="wsPaneSwitch"><Tabs label="Khu vực làm việc" idBase={paneId} value={pane} onChange={setPane} panels={false}
            tabs={[{ value: "chat", label: "Trò chuyện" }, { value: "preview", label: "Xem trước" }]}/></div>
          {mode === "ai" ? (
            <section className="promptPane">
              <div className="conversation" ref={convRef} onScroll={onConversationScroll}>
                {messages.length === 0 ? (
                  <div className="intro"><h2>Bạn muốn ứng dụng thay đổi thế nào?</h2><p>Mô tả bằng lời; thay đổi được kiểm tra theo registry rồi lưu thành phiên bản có thể khôi phục.</p>
                    {!readOnly ? <div className="starterList" aria-label="Gợi ý để bắt đầu">{suggestions(ai?.configured === true).map((t) => (
                      <button type="button" key={t} className="starter" disabled={busy !== null} onClick={() => { setPrompt(t); promptRef.current?.focus(); }}><Sparkles size={14} aria-hidden="true"/>{t}</button>))}</div> : null}</div>
                ) : null}
                <div role="log" aria-live="polite" aria-relevant="additions" aria-label="Cuộc trò chuyện với AI">{messages.map((m) => (
                  <div className={`message ${m.role}`} key={m.id}><div className="bubble"><div>{m.content}</div>
                    {m.detail ? <div className="msgDetail">{m.detail}</div> : null}
                    {m.meta?.length ? <div className="chips">{m.meta.map((x) => <span className="chip" key={x}>{x}</span>)}</div> : null}</div></div>
                ))}</div>
                {busy === "prompt" && live ? <AiProgress live={live} model={effectiveModel}
                    onCancel={cancel}/>
                  : busy === "prompt" ? <div className="message assistant"><div className="bubble typing" role="status"><span className="dots" aria-hidden="true"><i/><i/><i/></span> Đang phân tích yêu cầu{ai?.configured && effectiveModel !== "mock" ? " với AI…" : "…"}</div></div> : null}
                {behind ? <button type="button" className="smallButton" style={{ position: "sticky", bottom: 8, marginLeft: "auto", display: "block" }} onClick={jumpToNewest}>Tin mới ↓</button> : null}
              </div>
              <div className="composer">
                {!readOnly && messages.length > 0 ? <div className="suggestions" aria-label="Gợi ý">{suggestions(ai?.configured === true).map((t) => (
                  <button type="button" key={t} className="suggestion" disabled={busy !== null} onClick={() => { setPrompt(t); promptRef.current?.focus(); }}>+ {t}</button>))}</div> : null}
                <div className="composerBox">
                  <textarea ref={promptRef} value={prompt} disabled={readOnly} onChange={(e) => setPrompt(e.target.value)} maxLength={2000} aria-label="Mô tả thay đổi"
                    onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void submitPrompt(); } }}
                    placeholder={readOnly ? "Bạn chỉ có quyền xem ứng dụng này." : "Mô tả thay đổi bạn muốn tạo…"}/>
                  <div className="composerFooter">
                    {ai?.configured ? (
                      <label className="aiPicker"><span className="srOnly">Model AI</span>
                        <select aria-label="Model AI" value={effectiveModel} onChange={(e) => setModel(e.target.value)} disabled={busy !== null}>
                          <option value="auto">Tự động</option>
                          {(ai.providers ?? []).map((g) => <optgroup key={g.id} label={`${g.name}${g.paid ? " · tính phí" : ""}`}>
                            {g.models.map((m) => <option key={m.id} value={m.id}>{m.name}{m.name !== m.id ? ` — ${m.id}` : ""}</option>)}</optgroup>)}
                          <option value="mock">Chế độ thử nghiệm (không dùng AI thật)</option>
                        </select></label>
                    ) : <span title={ai?.dataNotice}>AI hiện chưa được quản trị viên bật (Chế độ thử nghiệm).</span>}
                    <button className="sendButton" disabled={busy !== null || !prompt.trim() || readOnly} onClick={() => void submitPrompt()}>{busy === "prompt" ? "Đang xử lý…" : "Gửi ↑"}</button>
                  </div>
                  {ai?.configured && effectiveModel !== "mock" ? <p className="aiNotice">{(ai.providers ?? []).find((g) => g.models.some((m) => m.id === effectiveModel))?.dataNotice ?? ai.dataNotice} {ai.dailyLimitPerUser > 0 ? `Giới hạn ${ai.dailyLimitPerUser} lượt AI mỗi ngày cho mỗi người.` : "Không giới hạn số lượt AI mỗi ngày."}</p> : null}
                </div>
              </div>
            </section>
          ) : null}

          <section className="previewPane">
            <div className="previewToolbar">
              <div className="toolbarGroup"><span className="toolbarLabel">Xem trước</span>
                <div className="segmented">{(["desktop", "tablet", "mobile"] as DeviceMode[]).map((d) => (
                  <button key={d} className={device === d ? "active" : ""} aria-pressed={device === d} onClick={() => setDevice(d)}><DeviceIcon kind={d}/>{d === "desktop" ? "Máy tính" : d === "tablet" ? "Máy tính bảng" : "Điện thoại"}</button>))}</div></div>
              <div className="toolbarGroup"/>
            </div>
            <div className={`canvasViewport viewport-${device}`}>
              {/* AI mode: no scripts at all (the Builder canvas has its own sandboxed iframe) */}
              <iframe className="previewFrame" title="Bản xem trước website" sandbox="" srcDoc={previewDocument}/>
            </div>
          </section>

        </main>
      ) : null}


      {panel === "versions" ? <Drawer title="Lịch sử phiên bản" sub="Khôi phục tạo một phiên bản mới; phiên bản cũ không bao giờ bị sửa." onClose={() => go(mode)}>
        <div className="versionList">{versions.map((v) => (
          <article className="versionItem" key={v.id}>
            <div><b>Phiên bản {v.versionNumber}{v.current ? " · hiện tại" : ""}</b><span>{fmtDate(v.createdAt)}</span></div>
            <p>{v.summary}</p><small>{v.kind}{v.createdBy ? ` · ${v.createdBy}` : ""}</small>
            {v.restorable && mayEdit ? <button className="smallButton" disabled={busy !== null} onClick={() => void restore(v)}>{busy === "restore" ? "Đang khôi phục…" : "Khôi phục"}</button> : null}
          </article>))}</div>
      </Drawer> : null}
      {panel === "site" ? <SiteDrawer schema={schema} ws={ws} pid={projectId} pageId={pageId} onPage={(id) => { setPageId(id); setSelectedId(null); }} canEdit={!readOnly}
        canPublish={mayPublish} apply={applyOps} onClose={() => go(mode)}/> : null}
      {panel === "settings" ? <SettingsDrawer project={project} busy={busy === "settings"} onClose={() => go(mode)} onSave={saveSettings}
        extra={<>{!readOnly ? <SaveTemplateSection workspaceId={ws} projectId={projectId} projectName={project.name}/> : null}
          {mayDelete && project.status !== "ARCHIVED" ? <section className="settingGroup"><h3>Lưu trữ ứng dụng</h3>
            <p className="hint">Ứng dụng chỉ còn xem được, website bị gỡ khỏi mạng. Dữ liệu và phiên bản được giữ; có thể khôi phục.</p>
            <button className="button ghost" onClick={() => void archive()}>Lưu trữ</button>
          </section> : null}</>}/> : null}
      {savingBlock && selected ? <SaveBlockDrawer workspaceId={ws} projectId={projectId} section={selected} title={label(selected.type)}
        onClose={() => setSavingBlock(false)} onSaved={(m) => { setSavingBlock(false); toast.success(m); loadBlocks(); }}/> : null}
      {panel === "assets" ? <AssetsDrawer workspaceId={ws} projectId={projectId} canEdit={mayEdit} onClose={() => { void loadAssets(ws); go(mode); }} onError={(e) => toast.error(errText(e, "Thao tác tệp thất bại."))}/> : null}
      {panel === "members" && me ? <MembersDrawer workspaceId={ws} projectId={projectId} me={me} onClose={() => go(mode)} onError={(e) => toast.error(errText(e, "Thao tác thành viên thất bại."))}/> : null}
      {panel === "publish" ? <PublishModal workspaceId={ws} projectId={projectId} revision={revision} current={project.siteVisibility} versionNumber={latest} canPublish={mayPublish} draft={schema as AppDefinitionV2}
        allowed={publicPublish === false ? ["PRIVATE"] : ["PRIVATE", "PUBLIC"]}
        onClose={() => { go(mode); void reload().catch(() => undefined); }} onUnauthorized={() => undefined}/> : null}
    </div>
  );
}
