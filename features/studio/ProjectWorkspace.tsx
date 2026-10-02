"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { api, ApiError } from "@/lib/http-api";
import { renderSchemaDocument } from "@/lib/schema-preview";
import type { AiStatus, ApiProject, AssetDto, BlockDto, PageSchema, PromptHistoryItem, RegistryComponent, SchemaOperation, Section, VersionSummary } from "@/lib/http-types";
import type { DeviceMode } from "@/lib/types";
import { SectionInspector, sectionLabel, sectionSummary } from "@/components/SectionInspector";
import { useSession } from "../session";
import { ErrorState, errText, fmtDate, StateView, tok, usd } from "../ui";
import { AssetsDrawer, DeviceIcon, Drawer, MembersDrawer, PublishModal, SettingsDrawer, suggestions } from "./drawers";
import { SaveBlockDrawer, SaveTemplateSection } from "./libraryPanels";
import { insertable } from "../library";

type Mode = "ai" | "design" | "code";
type PanelName = "members" | "versions" | "assets" | "publish" | "settings";
const MODES: Mode[] = ["ai", "design", "code"];
const PANELS: PanelName[] = ["members", "versions", "assets", "publish", "settings"];
/** Provider-reported usage of one prompt. The simulator makes no model call, so it has no tokens to show. */
function usageChip(calls: number, tokens: number | null, cost: number | null): string {
  if (!calls) return "không tính token";
  return [tokens == null ? "token: nhà cung cấp không báo" : `${tok(tokens)} token`, ...(cost == null ? [] : [usd(cost)]), ...(calls > 1 ? [`${calls} lượt gọi model`] : [])].join(" · ");
}

type Msg = { id: string; role: "user" | "assistant"; content: string; meta?: string[]; detail?: string };
const NOT_RENDERED = new Set(["LandingTemplate", "ProductCard"]);    // in the registry but the preview has no renderer for them yet
const DEFAULT_TEXT: Record<string, string> = { heading: "Tiêu đề mục mới", title: "Tiêu đề mới", brand: "Thương hiệu", text: "© Công ty", body: "Nội dung mới" };

function defaultProps(c: RegistryComponent): Record<string, unknown> {
  const schema = c.versions.find((v) => v.version === c.latestVersion)?.propsSchema ?? {};
  const out: Record<string, unknown> = {};
  for (const r of schema.required ?? []) {
    const d = schema.properties?.[r] ?? {};
    out[r] = d.type === "array" ? [] : d.type === "boolean" ? true : d.type === "number" ? 0 : DEFAULT_TEXT[r] ?? "Nội dung mới";
  }
  if (schema.properties?.visible) out.visible = true;
  return out;
}
const nonceOfPage = () => (typeof document === "undefined" ? undefined : (document.querySelector("script[nonce]") as HTMLScriptElement | null)?.nonce || undefined);

export function ProjectWorkspace({ projectId, view }: { projectId: string; view?: string }) {
  const router = useRouter(); const params = useSearchParams(); const { me } = useSession();
  const base = `/studio/projects/${projectId}`;
  const lastModeKey = `ws-mode-${projectId}`;
  const mode: Mode = MODES.includes(view as Mode) ? (view as Mode) : ((() => { try { const m = sessionStorage.getItem(lastModeKey); return MODES.includes(m as Mode) ? (m as Mode) : "ai"; } catch { return "ai"; } })());
  const panel: PanelName | null = PANELS.includes(view as PanelName) ? (view as PanelName) : null;
  useEffect(() => { try { sessionStorage.setItem(lastModeKey, mode); } catch { /* ignore */ } }, [mode, lastModeKey]);
  const go = (to: string) => router.push(`${base}/${to}`);

  const [project, setProject] = useState<ApiProject | null>(null);
  const [schema, setSchema] = useState<PageSchema | null>(null);
  const [revision, setRevision] = useState(0);
  const [versions, setVersions] = useState<VersionSummary[]>([]);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [registry, setRegistry] = useState<RegistryComponent[]>([]);
  const [blocks, setBlocks] = useState<{ company: BlockDto[]; mine: BlockDto[] }>({ company: [], mine: [] });
  const [savingBlock, setSavingBlock] = useState(false);
  const [assets, setAssets] = useState<AssetDto[]>([]);
  const [ai, setAi] = useState<AiStatus | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [save, setSave] = useState<{ state: "saved" | "saving" | "error"; at: Date | null }>({ state: "saved", at: null });
  const [prompt, setPrompt] = useState("");
  const [device, setDevice] = useState<DeviceMode>("desktop");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [model, setModel] = useState<string>(() => { try { return localStorage.getItem("studio-ai-model") ?? "auto"; } catch { return "auto"; } });
  const promptRef = useRef<HTMLTextAreaElement>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const ws = project?.workspaceId ?? "";
  const can = (p: string) => project?.permissions.includes(p) ?? false;
  const readOnly = !can("PROJECT_EDIT");

  const toMessages = (items: PromptHistoryItem[]): Msg[] => [...items].reverse().flatMap((p) => [
    { id: `${p.id}-u`, role: "user" as const, content: p.text },
    { id: `${p.id}-a`, role: "assistant" as const, content: p.assistantMessage,
      meta: [p.outcome, ...(p.model ? [p.model === "mock" ? "mô phỏng" : p.model] : []), usageChip(p.aiCalls ?? 0, p.totalTokens ?? null, p.costUsd ?? null)] }
  ]);
  const loadAssets = useCallback((w: string) => api.listAssets(w, projectId).then(setAssets).catch(() => undefined), [projectId]);
  const loadBlocks = useCallback(() => {
    Promise.all([api.blocks("company"), api.blocks("mine")]).then(([company, mine]) => setBlocks({ company, mine })).catch(() => undefined);
  }, []);
  const reload = useCallback(async () => {
    const p = await api.lookupProject(projectId);
    const [s, v, h] = await Promise.all([api.getSchema(p.workspaceId, projectId), api.listVersions(p.workspaceId, projectId), api.listPrompts(p.workspaceId, projectId)]);
    setProject(p); setSchema(s.schema); setRevision(s.revision); setVersions(v); setMessages(toMessages(h));
    setSave((x) => (x.at ? x : { state: "saved", at: new Date(p.updatedAt) }));
    void loadAssets(p.workspaceId);
  }, [projectId, loadAssets]);
  useEffect(() => { reload().catch(setLoadError); api.components().then(setRegistry).catch(() => undefined); loadBlocks(); api.aiStatus().then(setAi).catch(() => undefined); }, [reload]);
  // signed download URLs expire after 10 minutes: refresh the asset map before that
  useEffect(() => { if (!ws) return; const t = setInterval(() => void loadAssets(ws), 8 * 60_000); return () => clearInterval(t); }, [ws, loadAssets]);
  useEffect(() => { try { localStorage.setItem("studio-ai-model", model); } catch { /* ignore */ } }, [model]);
  const effectiveModel = ai?.configured ? (model === "auto" || model === "mock" || ai.models.some((m) => m.id === model) ? model : "auto") : "mock";

  async function run<T>(label: string, fn: () => Promise<T>, fallback: string): Promise<T | undefined> {
    setBusy(label); setSave((x) => ({ ...x, state: "saving" }));
    try { const out = await fn(); setSave({ state: "saved", at: new Date() }); return out; }
    catch (e) {
      setSave((x) => ({ ...x, state: "error" }));
      if (e instanceof ApiError && e.code === "REVISION_CONFLICT") { setNotice("Project vừa được thay đổi ở nơi khác. Đã tải lại bản mới nhất, hãy thử lại."); await reload().catch(() => undefined); }
      else if (e instanceof ApiError && e.code === "AI_TOKEN_LIMIT") {
        const d = e.details as { scope?: string; used?: number; limit?: number } | undefined;
        setNotice(`${d?.scope === "workspace" ? "Workspace đã dùng hết ngân sách token AI của tháng" : "Bạn đã dùng hết hạn mức token AI trong 24 giờ"}${d?.limit ? ` (${tok(d.used ?? 0)} / ${tok(d.limit)} token)` : ""}. Có thể chọn “Mô phỏng” để tiếp tục chỉnh sửa.`);
      }
      else if (!(e instanceof ApiError && e.status === 401)) setNotice(errText(e, fallback));
      return undefined;
    } finally { setBusy(null); }
  }
  const refreshVersions = async () => setVersions(await api.listVersions(ws, projectId).catch(() => versions));

  const label = (type: string) => sectionLabel(type, registry.find((c) => c.id === type)?.name);
  async function submitPrompt(text = prompt.trim()) {
    if (!text || busy || readOnly || !project) return;
    setMessages((m) => [...m, { id: `local-${Date.now()}`, role: "user", content: text }]); setPrompt("");
    const r = await run("prompt", () => api.sendPrompt(ws, projectId, text, revision, ai?.configured ? effectiveModel : undefined), "Không thể cập nhật website.");
    if (!r) return;
    setSchema(r.pageSchema); setRevision(r.revision);
    const changed = Array.from(new Set(r.schemaPatch.map((op) => op.sectionType ?? r.pageSchema.sections.find((s) => s.id === op.sectionId)?.type ?? schema?.sections.find((s) => s.id === op.sectionId)?.type).filter(Boolean) as string[]));
    const used = Array.from(new Set(r.pageSchema.sections.map((s) => s.type)));
    const meta = [r.outcome, r.model && r.model !== "mock" ? r.model : "mô phỏng", ...(r.version ? [`Phiên bản ${r.version.versionNumber}`] : []),
      usageChip(r.usage?.attempts ?? 0, r.usage?.totalTokens ?? null, r.usage?.costUsd ?? null)];
    const detail = r.outcome === "UPDATED" ? `Đã đổi: ${changed.map(label).join(", ") || "—"} · Component đang dùng: ${used.map(label).join(", ")}` : undefined;
    setMessages((m) => [...m, { id: r.promptId, role: "assistant", content: r.message.content, meta, detail }]);
    if (r.version) void refreshVersions();
  }
  // a prompt handed over from Studio Home is sent once, then removed from the URL
  const handedOver = useRef(false);
  useEffect(() => {
    const p = params.get("prompt");
    if (p && project && schema && !handedOver.current) { handedOver.current = true; router.replace(`${base}/ai`); void submitPrompt(p); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params, project, schema]);

  async function applyOps(ops: SchemaOperation[], summary: string): Promise<boolean> {
    const r = await run("edit", () => api.patchSchema(ws, projectId, revision, ops, summary), "Không lưu được thay đổi.");
    if (!r) return false;
    setSchema(r.schema); setRevision(r.revision); void refreshVersions(); return true;
  }
  async function restore(v: VersionSummary) {
    if (!window.confirm(`Khôi phục phiên bản ${v.versionNumber}? Một phiên bản mới sẽ được tạo; lịch sử cũ giữ nguyên.`)) return;
    const r = await run("restore", () => api.restoreVersion(ws, projectId, v.id, revision), "Không khôi phục được phiên bản.");
    if (!r) return;
    setSchema(r.schema); setRevision(r.revision); void refreshVersions(); setNotice(`Đã khôi phục phiên bản ${v.versionNumber} thành phiên bản ${r.version.versionNumber}.`);
  }
  async function saveSettings(patch: Partial<ApiProject>) {
    const p = await run("settings", () => api.updateProject(ws, projectId, revision, patch), "Không lưu được cài đặt.");
    if (p) { setProject(p); setRevision(p.revision); go(mode); setNotice("Đã lưu cài đặt."); }
  }
  /** A block inserts an ordinary section of its approved base component with the block's props (validated like any edit). */
  async function addBlock(b: BlockDto) {
    if (!schema || !b.current) return;
    const id = `${b.baseComponent.toLowerCase()}-${Math.random().toString(36).slice(2, 7)}`;
    const footer = schema.sections.find((s) => s.type === "Footer");
    const props = JSON.parse(JSON.stringify(b.current.props)) as Record<string, unknown>;
    const ok = await applyOps([{ type: "ADD_SECTION", sectionType: b.baseComponent, sectionId: id, props, ...(footer && b.baseComponent !== "Footer" ? { beforeSectionId: footer.id } : {}) }], `Thêm khối ${b.name}`);
    if (ok) setSelectedId(id);
  }
  async function addSection(c: RegistryComponent) {
    if (!schema) return;
    const id = `${c.id.toLowerCase()}-${Math.random().toString(36).slice(2, 7)}`;
    const footer = schema.sections.find((s) => s.type === "Footer");
    const ok = await applyOps([{ type: "ADD_SECTION", sectionType: c.id, sectionId: id, props: defaultProps(c), ...(footer && c.id !== "Footer" ? { beforeSectionId: footer.id } : {}) }], `Thêm ${label(c.id)}`);
    if (ok) setSelectedId(id);
  }

  // preview click-to-select (design mode): only messages from our own iframe window, only ids that exist in the schema
  useEffect(() => {
    if (mode !== "design") return;
    const onMessage = (e: MessageEvent) => {
      if (e.source !== frameRef.current?.contentWindow) return;
      const d = e.data as { type?: unknown; sectionId?: unknown };
      if (d?.type === "studio:select" && typeof d.sectionId === "string" && schema?.sections.some((s) => s.id === d.sectionId)) setSelectedId(d.sectionId);
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [mode, schema]);

  const assetUrls = useMemo(() => Object.fromEntries(assets.filter((a) => a.downloadUrl).map((a) => [a.id, a.downloadUrl!])), [assets]);
  const previewDocument = useMemo(() => (schema ? renderSchemaDocument(schema, {
    selectedId: mode === "design" ? selectedId : null, interactive: mode === "design" && !readOnly, nonce: nonceOfPage(), assets: assetUrls
  }) : ""), [schema, selectedId, mode, readOnly, assetUrls]);

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }));
  function onDragEnd(e: DragEndEvent) {
    if (!schema || !e.over || e.active.id === e.over.id) return;
    const from = schema.sections.findIndex((s) => s.id === e.active.id), to = schema.sections.findIndex((s) => s.id === e.over!.id);
    if (from < 0 || to < 0) return;
    void applyOps([{ type: "MOVE_SECTION", sectionId: String(e.active.id), index: to }], `Di chuyển ${label(schema.sections[from].type)}`);
  }

  if (loadError) return <div className="wsError"><ErrorState error={loadError} retry={() => { setLoadError(null); reload().catch(setLoadError); }}/><p><a className="btn" href="/studio/projects">← Danh sách ứng dụng</a></p></div>;
  if (!project || !schema) return <div className="wsError"><StateView kind="loading" title="Đang mở ứng dụng…"/></div>;
  // company blocks, then my own drafts (an approved block of mine is already in the company list)
  const blockOptions = [...blocks.company.map((b) => ({ b, who: "Công ty" })), ...blocks.mine.filter((b) => b.approvedVersion == null || b.status !== "APPROVED").map((b) => ({ b, who: "Của tôi" }))]
    .filter(({ b }) => insertable(b, registry, NOT_RENDERED));
  const selected = schema.sections.find((s) => s.id === selectedId) ?? null;
  const latest = versions[0]?.versionNumber;

  return (
    <div className="studio workspace3">
      <header className="topbar">
        <div className="brand">
          <button className="button icon" aria-label="Danh sách ứng dụng" title="Danh sách ứng dụng" onClick={() => router.push("/studio/projects")}>←</button>
          <div>
            <div className="projectName">{project.name}</div>
            <div className="projectMeta">{latest ? `Phiên bản ${latest}` : "Chưa có phiên bản"} · revision {revision} · {project.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"}{readOnly ? " · chỉ xem" : ""}</div>
          </div>
          <span className={`saveState ${save.state}`} role="status" aria-live="polite">
            {save.state === "saving" ? "Đang lưu…" : save.state === "error" ? "Lưu thất bại" : `✓ Đã lưu${save.at ? ` ${save.at.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}` : ""}`}
          </span>
        </div>
        <nav className="modeTabs" aria-label="Chế độ">
          {MODES.map((m) => <button key={m} className={mode === m ? "active" : ""} aria-pressed={mode === m} onClick={() => go(m)}>{m === "ai" ? "✦ AI" : m === "design" ? "Design" : "Code"}</button>)}
        </nav>
        <div className="topActions">
          <button className="button ghost" onClick={() => go("versions")}>Phiên bản</button>
          <button className="button ghost" onClick={() => go("assets")}>Tệp</button>
          {can("PROJECT_MEMBERS") ? <button className="button ghost" onClick={() => go("members")}>Chia sẻ</button> : null}
          <button className="button icon" aria-label="Cài đặt project" title={can("PROJECT_SETTINGS") ? "Cài đặt project" : "Bạn không có quyền đổi cài đặt"} disabled={!can("PROJECT_SETTINGS")} onClick={() => go("settings")}>⚙</button>
          <button className="button primary" disabled={!can("PROJECT_PUBLISH") || busy !== null} title={can("PROJECT_PUBLISH") ? "Xuất bản phiên bản hiện tại" : "Bạn không có quyền xuất bản"} onClick={() => go("publish")}>Xuất bản</button>
        </div>
      </header>

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
      ) : (
        <main className={`wsBody mode-${mode}`}>
          {mode === "ai" ? (
            <section className="promptPane">
              <div className="conversation">
                {messages.length === 0 ? (
                  <div className="intro"><h1>Bạn muốn ứng dụng thay đổi thế nào?</h1><p>Mô tả bằng lời; thay đổi được kiểm tra theo registry rồi lưu thành phiên bản có thể khôi phục.</p>
                    {!readOnly ? <div className="starterList" aria-label="Gợi ý để bắt đầu">{suggestions(ai?.configured === true).map((t) => (
                      <button type="button" key={t} className="starter" disabled={busy !== null} onClick={() => { setPrompt(t); promptRef.current?.focus(); }}><span aria-hidden="true">✦</span>{t}</button>))}</div> : null}</div>
                ) : null}
                {messages.map((m) => (
                  <div className={`message ${m.role}`} key={m.id}><div className="bubble"><div>{m.content}</div>
                    {m.detail ? <div className="msgDetail">{m.detail}</div> : null}
                    {m.meta?.length ? <div className="chips">{m.meta.map((x) => <span className="chip" key={x}>{x}</span>)}</div> : null}</div></div>
                ))}
                {busy === "prompt" ? <div className="message assistant"><div className="bubble typing" role="status"><span className="dots" aria-hidden="true"><i/><i/><i/></span> Đang phân tích yêu cầu{ai?.configured && effectiveModel !== "mock" ? " với AI…" : "…"}</div></div> : null}
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
                          <option value="auto">AI · tự động (các model miễn phí)</option>
                          {ai.models.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.id}</option>)}
                          <option value="mock">Mô phỏng (không gọi AI)</option>
                        </select></label>
                    ) : <span title={ai?.dataNotice}>AI: mô phỏng (chưa có key OpenRouter)</span>}
                    <button className="sendButton" disabled={busy !== null || !prompt.trim() || readOnly} onClick={() => void submitPrompt()}>{busy === "prompt" ? "Đang xử lý…" : "Gửi ↑"}</button>
                  </div>
                  {ai?.configured && effectiveModel !== "mock" ? <p className="aiNotice">{ai.dataNotice} Giới hạn {ai.dailyLimitPerUser} lượt AI/ngày/người dùng.</p> : null}
                </div>
              </div>
            </section>
          ) : (
            <section className="designLeft" aria-label="Cấu trúc trang và thư viện component">
              <div className="paneSection">
                <h2>Cấu trúc trang</h2>
                <p className="hint">{readOnly ? "Bạn chỉ có quyền xem." : "Kéo để đổi thứ tự (hoặc dùng phím: chọn tay cầm, Space, mũi tên). Nhấp để chọn."}</p>
                <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
                  <SortableContext items={schema.sections.map((s) => s.id)} strategy={verticalListSortingStrategy}>
                    <ol className="outline">{schema.sections.map((s) => (
                      <SortableRow key={s.id} section={s} title={label(s.type)} active={s.id === selectedId} disabled={readOnly || busy !== null} onSelect={() => setSelectedId(s.id === selectedId ? null : s.id)}/>
                    ))}</ol>
                  </SortableContext>
                </DndContext>
              </div>
              {!readOnly ? (
                <div className="paneSection">
                  <h2>Thư viện component</h2>
                  <p className="hint">Component đã được duyệt của công ty. Nhấp để thêm vào trang.</p>
                  <ul className="libList">{registry.filter((c) => c.status === "ACTIVE").map((c) => (
                    <li key={c.id}><button type="button" disabled={busy !== null || NOT_RENDERED.has(c.id)} onClick={() => void addSection(c)} title={NOT_RENDERED.has(c.id) ? "Chưa có renderer cho component này" : `Thêm ${label(c.id)}`}>
                      <b>+ {label(c.id)}</b><span>{c.category}{NOT_RENDERED.has(c.id) ? " · chưa hỗ trợ xem trước" : ""}</span></button></li>))}</ul>
                  <h2>Khối dựng sẵn</h2>
                  <p className="hint">Cấu hình sẵn của component đã duyệt. “Công ty” đã được phê duyệt; “Của tôi” là khối riêng của bạn.</p>
                  {blockOptions.length === 0 ? <p className="hint">Chưa có khối nào. Chọn một mục rồi bấm “Lưu thành khối”.</p> : (
                    <ul className="libList">{blockOptions.map(({ b, who }) => (
                      <li key={`${who}-${b.id}`}><button type="button" disabled={busy !== null} onClick={() => void addBlock(b)} title={`Thêm khối ${b.name}`}>
                        <b>+ {b.name}</b><span>{who} · {label(b.baseComponent)}</span></button></li>))}</ul>)}
                </div>
              ) : null}
            </section>
          )}

          <section className="previewPane">
            <div className="previewToolbar">
              <div className="toolbarGroup"><span className="toolbarLabel">Xem trước</span>
                <div className="segmented">{(["desktop", "tablet", "mobile"] as DeviceMode[]).map((d) => (
                  <button key={d} className={device === d ? "active" : ""} aria-pressed={device === d} onClick={() => setDevice(d)}><DeviceIcon kind={d}/>{d === "desktop" ? "Máy tính" : d === "tablet" ? "Máy tính bảng" : "Điện thoại"}</button>))}</div></div>
              <div className="toolbarGroup">{mode === "design" && !readOnly ? <span className="toolbarLabel">Nhấp vào một mục trong bản xem trước để chỉnh</span> : null}</div>
            </div>
            <div className={`canvasViewport viewport-${device}`}>
              {/* AI mode: no scripts at all. Design mode: our own click-to-select script only; still no same-origin, forms, popups or top navigation. */}
              <iframe ref={frameRef} className="previewFrame" title="Bản xem trước website" sandbox={mode === "design" && !readOnly ? "allow-scripts" : ""} srcDoc={previewDocument}/>
            </div>
          </section>

          {mode === "design" ? (
            <aside className="inspectorPane" aria-label="Thuộc tính">
              {selected && !readOnly ? <div className="inspectorTools"><button type="button" className="btn sm" onClick={() => setSavingBlock(true)}>Lưu thành khối…</button></div> : null}
              {selected ? (
                <SectionInspector key={`${selected.id}:${JSON.stringify(selected.props)}`} section={selected} component={registry.find((c) => c.id === selected.type)}
                  index={schema.sections.indexOf(selected)} count={schema.sections.length} readOnly={readOnly} busy={busy === "edit"} assets={assets}
                  onClose={() => setSelectedId(null)}
                  onApply={async (ops, summary) => { const ok = await applyOps(ops, summary); if (ok && ops.some((o) => o.type === "REMOVE_SECTION")) setSelectedId(null); return ok; }}/>
              ) : <StateView kind="empty" title="Chưa chọn mục nào" detail={<p>Chọn một mục trong “Cấu trúc trang” hoặc nhấp vào bản xem trước.</p>}/>}
            </aside>
          ) : null}
        </main>
      )}

      {notice ? <button className="toast" onClick={() => setNotice(null)}>{notice}</button> : null}

      {panel === "versions" ? <Drawer title="Lịch sử phiên bản" sub="Khôi phục tạo một phiên bản mới; phiên bản cũ không bao giờ bị sửa." onClose={() => go(mode)}>
        <div className="versionList">{versions.map((v) => (
          <article className="versionItem" key={v.id}>
            <div><b>Phiên bản {v.versionNumber}{v.current ? " · hiện tại" : ""}</b><span>{fmtDate(v.createdAt)}</span></div>
            <p>{v.summary}</p><small>{v.kind}{v.createdBy ? ` · ${v.createdBy}` : ""}</small>
            {v.restorable && can("PROJECT_EDIT") ? <button className="smallButton" disabled={busy !== null} onClick={() => void restore(v)}>{busy === "restore" ? "Đang khôi phục…" : "Khôi phục"}</button> : null}
          </article>))}</div>
      </Drawer> : null}
      {panel === "settings" ? <SettingsDrawer project={project} busy={busy === "settings"} onClose={() => go(mode)} onSave={saveSettings}
        extra={!readOnly ? <SaveTemplateSection workspaceId={ws} projectId={projectId} projectName={project.name}/> : undefined}/> : null}
      {savingBlock && selected ? <SaveBlockDrawer workspaceId={ws} projectId={projectId} section={selected} title={label(selected.type)}
        onClose={() => setSavingBlock(false)} onSaved={(m) => { setSavingBlock(false); setNotice(m); loadBlocks(); }}/> : null}
      {panel === "assets" ? <AssetsDrawer workspaceId={ws} projectId={projectId} canEdit={can("PROJECT_EDIT")} onClose={() => { void loadAssets(ws); go(mode); }} onError={(e) => setNotice(errText(e, "Thao tác tệp thất bại."))}/> : null}
      {panel === "members" && me ? <MembersDrawer workspaceId={ws} projectId={projectId} me={me} onClose={() => go(mode)} onError={(e) => setNotice(errText(e, "Thao tác thành viên thất bại."))}/> : null}
      {panel === "publish" ? <PublishModal workspaceId={ws} projectId={projectId} revision={revision} current={project.siteVisibility} versionNumber={latest}
        onClose={() => { go(mode); void reload().catch(() => undefined); }} onUnauthorized={() => undefined}/> : null}
    </div>
  );
}

function SortableRow({ section, title, active, disabled, onSelect }: { section: Section; title: string; active: boolean; disabled: boolean; onSelect: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: section.id, disabled });
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.6 : 1 }} className="sortRow">
      {!disabled ? <button type="button" className="dragHandle" aria-label={`Kéo để di chuyển ${title}`} {...attributes} {...listeners}>⋮⋮</button> : null}
      <button type="button" className={active ? "active" : ""} aria-pressed={active} onClick={onSelect}><b>{title}</b><span>{sectionSummary(section) || section.id}</span></button>
    </li>
  );
}
