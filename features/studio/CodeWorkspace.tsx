"use client";
// Code projects (STATIC_APP, ADR 0008/0012): AI and Code modes over a real Git repository; every change is a commit on its own branch,
// built in the sandbox, previewed from the sites origin (CSP sandbox) and merged only after a green build.
import { canEditProject, canPublish, canShare, resolvePermissions } from "@xweb/permissions";
import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { ArrowLeft, confirm, ReasonButton, Sparkles, TabPanel, Tabs, toast } from "@xweb/ui";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/http-api";
import { SERVER_KINDS, type AiStatus, type ApiProject, type AuthConfig, type CodeAiHistoryItem, type CodeChange, type CodeCommit, type DiffFile, type TreeFile } from "@/lib/http-types";
import { useSession } from "../session";
import { ago, ErrorState, errText, fmtDate, StateView, tok, usd } from "../ui";
import { Drawer, MembersDrawer } from "./drawers";
import { PublishModal } from "./ReleaseModal";
import { ReviewDialog } from "./ReviewDialog";
import { OverflowMenu } from "./OverflowMenu";
import { describeStatus } from "./aiProgressModel";
import { diffFileRows } from "./codeDiff";
import { DesignPane, IdeDrawer, PackagesDrawer, RuntimeDrawer } from "./CodePanels";
import { projectBase, S } from "./base";

const STATUS: Record<CodeChange["status"], string> = { BUILDING: "Đang build", READY: "Sẵn sàng", FAILED: "Build lỗi", MERGED: "Đã hợp nhất", DISCARDED: "Đã huỷ" };
const STAGE: Record<string, string> = { CLAIMED: "đã nhận", SOURCE: "lấy mã", SCAN_SOURCE: "quét mã", PREPARE: "chuẩn bị", INSTALL: "cài gói", BUILD: "build",
  PACKAGE: "đóng gói", SCAN_OUTPUT: "quét kết quả", UPLOAD: "tải lên", UPLOADED: "đã tải lên", DONE: "xong" };

// M-082: memo + per-file cache (codeDiff.ts): the LCS runs once per diff response, not on every keystroke elsewhere in the workspace
const DiffView = memo(function DiffView({ files }: { files: DiffFile[] }) {
  return <div className="diffView">{files.map((f) => {
    const rows = diffFileRows(f);
    return <section key={f.path}><h4>{f.path} {f.before == null ? <em>(mới)</em> : f.after == null ? <em>(xoá)</em> : null}</h4>
      <pre>{rows.map((r) => r.gap ? <div key={r.index} className="dl gap">⋯</div> : <div key={r.index} className={`dl ${r.row.t === "+" ? "add" : r.row.t === "-" ? "del" : ""}`}>{r.row.t} {r.row.s}</div>)}</pre></section>;
  })}</div>;
});

export function CodeWorkspace({ project, view, onProject }: { project: ApiProject; view?: string; onProject: (p: ApiProject) => void }) {
  const router = useRouter(); const { me } = useSession();
  const ws = project.workspaceId, pid = project.id, base = projectBase(pid);
  const mode: "ai" | "code" | "design" = view === "code" ? "code" : view === "design" ? "design" : "ai";
  const panel = ["members", "publish", "versions", "packages", "ide", "runtime"].includes(view ?? "") ? view : null;
  const isServer = SERVER_KINDS.includes(project.appKind ?? "SOURCE_WEB_APP");
  const go = (to: string) => router.push(`${base}/${to}`);
  // UX only: the list is what the server resolved for this project; helpers in @xweb/permissions (canonical.ts)
  const perms = resolvePermissions(project.permissions);
  const canEdit = canEditProject(perms), canPublishApp = canPublish(perms), canShareApp = canShare(perms);
  const [tree, setTree] = useState<TreeFile[] | null>(null);
  const [path, setPath] = useState("src/App.tsx");
  const [original, setOriginal] = useState<Record<string, { text: string | null; editable: boolean }>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [summary, setSummary] = useState("");
  const [changes, setChanges] = useState<CodeChange[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [tab, setTab] = useState<"preview" | "diff" | "log">("preview");
  const [diff, setDiff] = useState<DiffFile[] | null>(null);
  const [history, setHistory] = useState<CodeAiHistoryItem[]>([]);
  const [prompt, setPrompt] = useState(""); const [ai, setAi] = useState<AiStatus | null>(null);
  const [model, setModel] = useState<string>(() => { try { return localStorage.getItem("studio-ai-model") ?? ""; } catch { return ""; } });
  const [busy, setBusy] = useState<string | null>(null); const [error, setError] = useState<unknown>(null);
  const [pane, setPane] = useState<"work" | "changes">("work"); const paneId = useId(); const detailTabs = useId();
  const [tabIndent, setTabIndent] = useState(false); const escapeTab = useRef(false);   // M-015: Tab indents only when this is switched on
  const [commits, setCommits] = useState<CodeCommit[] | null>(null);
  const [live, setLive] = useState<{ id: string | null; chars: number; status: string } | null>(null);
  const [cfg, setCfg] = useState<AuthConfig | null>(null);
  useEffect(() => { api.authConfig().then(setCfg).catch(() => undefined); }, []);

  const loadTree = useCallback(() => api.code.tree(ws, pid).then(setTree).catch(setError), [ws, pid]);
  const loadChanges = useCallback(() => api.code.changes(ws, pid).then((c) => { setChanges(c); setSelected((s) => s ?? c.find((x) => x.status !== "DISCARDED")?.id ?? null); }).catch(() => undefined), [ws, pid]);
  const loadHistory = useCallback(() => api.code.aiHistory(ws, pid).then(setHistory).catch(() => undefined), [ws, pid]);
  useEffect(() => { void loadTree(); void loadChanges(); void loadHistory(); api.aiStatus(ws).then(setAi).catch(() => undefined); }, [loadTree, loadChanges, loadHistory]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { try { localStorage.setItem("studio-ai-model", model); } catch { /* ignore */ } }, [model]);
  // while something builds, refresh every 2.5 s
  useEffect(() => { if (!changes.some((c) => c.status === "BUILDING")) return; const t = setInterval(() => void loadChanges(), 2500); return () => clearInterval(t); }, [changes, loadChanges]);
  useEffect(() => {
    if (original[path] || !tree?.some((f) => f.path === path)) return;
    api.code.file(ws, pid, path).then((f) => setOriginal((o) => ({ ...o, [path]: { text: f.text, editable: f.editable } }))).catch((e) => toast.error(errText(e, "Không đọc được tệp.")));
  }, [path, tree, original, ws, pid]);
  const change = changes.find((c) => c.id === selected) ?? null;
  useEffect(() => { setDiff(null); if (change && tab === "diff") api.code.diff(ws, pid, change.id).then(setDiff).catch(() => undefined); }, [change?.id, tab, ws, pid]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (panel === "versions") api.code.commits(ws, pid).then(setCommits).catch(() => setCommits([])); }, [panel, ws, pid]);

  const valid = (m: string) => m === "auto" || m === "mock" || (ai?.models ?? []).some((x) => x.id === m);
  const effectiveModel = ai?.configured ? (model && valid(model) ? model : valid(ai.defaultModel) ? ai.defaultModel : "auto") : "mock";
  const dirty = Object.keys(drafts).filter((p) => drafts[p] !== original[p]?.text);
  // M-045: unsaved drafts are not lost silently: leaving the page (reload, closing the tab) asks first while there is a draft, and 'Bỏ nháp' asks before it clears them
  useEffect(() => {
    if (!dirty.length) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", h); return () => window.removeEventListener("beforeunload", h);
  }, [dirty.length]);
  async function discardDrafts() {
    if (!(await confirm({ title: `Bỏ ${dirty.length} bản nháp chưa gửi?`, message: `Nội dung đã sửa trong ${dirty.length === 1 ? "tệp" : `${dirty.length} tệp`} (${dirty.slice(0, 3).join(", ")}${dirty.length > 3 ? "…" : ""}) sẽ mất và không khôi phục được.`, confirmLabel: "Bỏ nháp", cancelLabel: "Giữ lại", danger: true }))) return;
    setDrafts({});
  }
  const current = drafts[path] ?? original[path]?.text ?? "";
  const editable = canEdit && !!original[path]?.editable;
  const files = useMemo(() => (tree ?? []).slice().sort((a, b) => a.path.localeCompare(b.path)), [tree]);

  async function act<T>(label: string, fn: () => Promise<T>, fallback: string): Promise<T | undefined> {
    setBusy(label);
    try { return await fn(); } catch (e) { toast.error(errText(e, fallback)); return undefined; } finally { setBusy(null); }
  }
  async function propose() {
    const c = await act("propose", () => api.code.propose(ws, pid, summary.trim() || `Sửa ${dirty.join(", ")}`, dirty.map((p) => ({ path: p, content: drafts[p] }))), "Không tạo được thay đổi.");
    if (c) { setDrafts({}); setSummary(""); setSelected(c.id); setTab("preview"); void loadChanges(); toast.success("Đã tạo thay đổi trên nhánh riêng; đang build trong sandbox."); }
  }
  async function sendPrompt() {
    const text = prompt.trim(); if (!text) return;
    const real = ai?.configured === true && effectiveModel !== "mock";
    const r = await act("ai", () => real
      ? api.code.aiStream(ws, pid, text, effectiveModel, {
          onStart: (id) => setLive({ id, chars: 0, status: "" }),
          onDelta: (t) => setLive((l) => l ? { ...l, chars: l.chars + t.length } : l),
          onStatus: (st) => setLive((l) => l ? { ...l, status: st } : l) }).finally(() => setLive(null))
      : api.code.ai(ws, pid, text, ai?.configured ? effectiveModel : undefined), "AI không xử lý được.");
    if (r) {
      setPrompt(""); void loadHistory();
      if (r.change) { setSelected(r.change.id); setTab("preview"); void loadChanges(); }
      toast.info(`${r.message}${r.usage?.totalTokens != null ? ` · ${tok(r.usage.totalTokens)} token${r.usage.costUsd != null ? ` · ${usd(r.usage.costUsd)}` : ""}` : ""}`);
    }
  }
  async function merge(c: CodeChange) {
    const m = await act("merge", () => api.code.merge(ws, pid, c.id), "Không hợp nhất được.");
    if (m) { void loadChanges(); setOriginal({}); void loadTree(); api.lookupProject(pid).then(onProject).catch(() => undefined); toast.success("Đã hợp nhất vào main. Có thể xuất bản."); }
  }
  /** M-001: the change under review. Nothing is sent until the person presses "Duyệt" in the dialog; Cancel / Esc only close it. */
  const [reviewing, setReviewing] = useState<CodeChange | null>(null);
  async function approve(c: CodeChange, comment?: string) {
    if (await act("approve", () => api.code.approve(ws, pid, c.id, comment), "Không duyệt được.")) { setReviewing(null); void loadChanges(); }
  }
  async function discard(c: CodeChange) {
    if (!(await confirm({ title: "Bỏ thay đổi này?", message: `“${c.summary}” bị bỏ và không hợp nhất vào main. Không thể lấy lại.`, confirmLabel: "Bỏ thay đổi", danger: true }))) return;
    if (await act("discard", () => api.code.discard(ws, pid, c.id), "Không bỏ được thay đổi.")) void loadChanges();
  }

  if (error) return <div className="wsError"><ErrorState error={error} retry={() => { setError(null); void loadTree(); }}/></div>;
  return (
    <div className="studio codeStudio">
      <header className="topbar">
        <div className="brand">
          <button className="button icon" aria-label="Danh sách ứng dụng" title="Danh sách ứng dụng" onClick={() => router.push(S("/projects"))}><ArrowLeft size={16} aria-hidden="true"/></button>
          <div>
            <div className="projectName" role="heading" aria-level={1} title={project.name}>{project.name}</div>
            <div className="projectMeta">Ứng dụng web (mã nguồn) · React + Vite · revision {project.revision}{canEdit ? "" : " · chỉ xem"}</div>
          </div>
        </div>
        <nav className="modeTabs" aria-label="Chế độ">
          {(["ai", "design", "code"] as const).map((m) => <button key={m} className={mode === m && !panel ? "active" : ""} aria-pressed={mode === m} onClick={() => go(m)}>{m === "ai" ? <><Sparkles size={14} aria-hidden="true"/> AI</> : m === "design" ? "Design" : "Code"}</button>)}
        </nav>
        <div className="topActions">
          <button className="button ghost" onClick={() => go("versions")}>Lịch sử</button>
          <button className="button ghost" onClick={() => go("packages")}>Thư viện</button>
          <button className="button ghost" onClick={() => go("ide")}>IDE</button>
          {isServer ? <button className="button ghost" onClick={() => go("runtime")}>Máy chủ</button> : null}
          {canShareApp ? <button className="button ghost" onClick={() => go("members")}>Chia sẻ</button> : null}
          <OverflowMenu items={[
            { key: "versions", label: "Lịch sử", onSelect: () => go("versions") }, { key: "packages", label: "Thư viện", onSelect: () => go("packages") }, { key: "ide", label: "IDE", onSelect: () => go("ide") },
            ...(isServer ? [{ key: "runtime", label: "Máy chủ", onSelect: () => go("runtime") }] : []), ...(canShareApp ? [{ key: "members", label: "Chia sẻ", onSelect: () => go("members") }] : [])]}/>
          <button className="button primary" disabled={!canPublishApp} onClick={() => go("publish")}>Xuất bản</button>
        </div>
      </header>
      <main className="codeBody" data-pane={pane}>
        {/* phone (<= 767 px): ONE pane at a time (M-104); both stay mounted so the conversation, the draft and the preview keep their state */}
        <div className="wsPaneSwitch"><Tabs label="Khu vực làm việc" idBase={paneId} value={pane} onChange={setPane} panels={false}
          tabs={[{ value: "work", label: mode === "ai" ? "Trò chuyện" : mode === "design" ? "Thiết kế" : "Mã nguồn" }, { value: "changes", label: "Thay đổi" }]}/></div>
        <section className="codeLeft" aria-label={mode === "ai" ? "AI" : mode === "design" ? "Thiết kế" : "Mã nguồn"}>
          {mode === "design" ? <DesignPane ws={ws} pid={pid} canEdit={canEdit} onChange={(c) => { setSelected(c.id); setTab("preview"); void loadChanges(); toast.success("Đã tạo thay đổi giao diện; đang build trong sandbox."); }}/> : mode === "ai" ? (<>
            <div className="chatScroll">
              {history.length === 0 ? <div className="hint chatEmpty">Mô tả thay đổi bạn muốn. AI chỉ sửa src/, public/ và index.html; mỗi thay đổi được build trong sandbox và cần bạn hợp nhất.
                {!ai?.configured ? <> AI hiện chưa được quản trị viên bật. <b>Chế độ thử nghiệm</b> hiểu vài yêu cầu như “đổi tiêu đề thành “…”” hoặc “đổi màu nền vàng”.</> : null}</div> : null}
              {[...history].reverse().map((h) => <div key={h.promptId} className="codeMsg">
                <div className="message user"><div className="bubble">{h.text}</div></div>
                <div className="message assistant"><div className="bubble">{h.message}
                  <div className="chips"><span className="chip">{h.model === "mock" ? "Chế độ thử nghiệm" : h.model ?? "—"}</span>
                    {h.changeId ? <button className="chip linkChip" onClick={() => { setSelected(h.changeId); setTab("preview"); }}>{STATUS[(changes.find((c) => c.id === h.changeId)?.status ?? h.changeStatus ?? "BUILDING") as CodeChange["status"]]} · xem</button> : <span className="chip">không đổi</span>}</div></div></div>
              </div>)}
            </div>
            {canEdit ? <div className="composer">
              <textarea aria-label="Mô tả thay đổi mã" value={prompt} maxLength={2000} placeholder="Ví dụ: Thêm danh sách 3 tính năng dưới tiêu đề…" onChange={(e) => setPrompt(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void sendPrompt(); }}/>
              <div className="composerFooter">
                {ai?.configured ? <select aria-label="Model AI" value={effectiveModel} onChange={(e) => setModel(e.target.value)}>
                  <option value="auto">Tự động</option>
                  {(ai.providers ?? []).map((g) => <optgroup key={g.id} label={`${g.name}${g.paid ? " · tính phí" : ""}`}>{g.models.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</optgroup>)}
                  <option value="mock">Chế độ thử nghiệm (không dùng AI thật)</option></select> : <span className="hint">AI hiện chưa được quản trị viên bật (Chế độ thử nghiệm).</span>}
                {live ? <span className="hint" role="status">{describeStatus(live.status) ?? (live.chars > 0 ? `Đang nhận… ${live.chars} ký tự` : "Đã gửi, đang chờ model trả lời…")}
                  {live.id ? <button type="button" className="smallButton" onClick={() => { void api.cancelStream(live.id!).catch(() => undefined); }}>Huỷ</button> : null}</span> : null}
                <button className="sendButton" disabled={busy !== null || !prompt.trim()} onClick={() => void sendPrompt()}>{busy === "ai" ? "Đang tạo…" : "Gửi ↑"}</button>
              </div>
            </div> : <p className="hint">Bạn chỉ có quyền xem.</p>}
          </>) : (<>
            <div className="codeFiles">
              <ul className="fileTree" aria-label="Tệp trong main">{files.map((f) =>
                <li key={f.path}><button className={`${f.path === path ? "active" : ""} ${dirty.includes(f.path) ? "dirty" : ""}`} onClick={() => setPath(f.path)}>{f.path}{dirty.includes(f.path) ? " •" : ""}</button></li>)}</ul>
              <div className="editorPane">
                <div className="editorHead"><span className="code">{path}</span>{original[path] && !original[path].editable ? <span className="hint">Chỉ đọc: cấu hình và thư viện do khung mẫu quản lý</span> : null}</div>
                {original[path]?.text == null && original[path] ? <p className="hint">Tệp nhị phân hoặc quá lớn để hiển thị.</p> :
                  <textarea className="codeEditor" aria-label={`Nội dung ${path}`} spellCheck={false} readOnly={!editable} value={current}
                    onChange={(e) => setDrafts((d) => ({ ...d, [path]: e.target.value }))}
                    aria-describedby="code-tab-hint"
                    onKeyDown={(e) => {
                      // M-015 (WCAG 2.1.2): Tab moves focus like everywhere else. It indents ONLY when the person turned that option on, and even then Esc then Tab leaves; Shift+Tab always leaves.
                      if (e.key === "Escape") { escapeTab.current = true; return; }
                      if (e.key !== "Tab") { escapeTab.current = false; return; }
                      if (e.shiftKey || !tabIndent || !editable || escapeTab.current) { escapeTab.current = false; return; }
                      e.preventDefault(); const t = e.currentTarget, s = t.selectionStart; const v = t.value.slice(0, s) + "  " + t.value.slice(t.selectionEnd);
                      setDrafts((d) => ({ ...d, [path]: v })); requestAnimationFrame(() => { t.selectionStart = t.selectionEnd = s + 2; });
                    }}/>}
                <div className="hint" id="code-tab-hint">
                  <label><input type="checkbox" checked={tabIndent} onChange={(e) => setTabIndent(e.target.checked)}/> Dùng phím Tab để thụt lề</label>
                  {" "}{tabIndent ? "Nhấn Esc rồi Tab để thoát khỏi ô soạn thảo; Shift+Tab luôn thoát." : "Phím Tab chuyển sang điều khiển kế tiếp. Bật tuỳ chọn này nếu muốn Tab thụt lề hai khoảng trắng."}
                </div>
                {canEdit ? <div className="draftBar">
                  <span>{dirty.length ? `Bản nháp: ${dirty.length} tệp` : "Chưa có thay đổi"}</span>
                  <input aria-label="Mô tả thay đổi" placeholder="Mô tả ngắn (tuỳ chọn)" value={summary} maxLength={300} onChange={(e) => setSummary(e.target.value)}/>
                  <button className="button ghost" disabled={!dirty.length || busy !== null} onClick={() => void discardDrafts()}>Bỏ nháp</button>
                  <button className="button primary" disabled={!dirty.length || busy !== null} onClick={() => void propose()}>{busy === "propose" ? "Đang gửi…" : "Tạo thay đổi & build"}</button>
                </div> : null}
              </div>
            </div>
          </>)}
        </section>

        <section className="codeRight" aria-label="Thay đổi">
          <div className="changeList">
            <h2>Thay đổi</h2>
            {changes.length === 0 ? <p className="hint">Chưa có thay đổi nào. Mỗi thay đổi là một commit trên nhánh riêng, được build trong sandbox trước khi hợp nhất vào main.</p> :
              <ul>{changes.filter((c) => c.status !== "DISCARDED").slice(0, 12).map((c) => <li key={c.id}>
                <button className={`changeItem ${c.id === selected ? "active" : ""}`} onClick={() => setSelected(c.id)}>
                  <span className={`state s-${c.status.toLowerCase()}`}>{STATUS[c.status]}{c.status === "BUILDING" && c.build?.stage ? ` · ${STAGE[c.build.stage] ?? c.build.stage}` : ""}</span>
                  <b>{c.summary}</b><small>{c.kind === "AI" ? "AI" : c.createdBy} · {ago(c.createdAt)} · {c.files.length} tệp · {c.headSha.slice(0, 7)}</small></button></li>)}</ul>}
          </div>
          {change ? <div className="changeDetail">
            <div className="row between">
              <Tabs label="Chi tiết thay đổi" idBase={detailTabs} value={tab} onChange={setTab} tabs={[{ value: "preview", label: "Xem trước" }, { value: "diff", label: "Mã thay đổi" }, { value: "log", label: "Build & quét" }]}/>
              <div className="row">
                {change.status === "READY" && change.reviewRequired && !change.approvedBy && me && change.createdBy !== me.displayName && canPublishApp
                  ? <button className="button ghost" disabled={busy !== null} onClick={() => setReviewing(change)}>Duyệt</button> : null}
                {canEdit && change.status === "READY" ? <ReasonButton className="button primary" busy={busy === "merge"} unavailable={busy !== null || (!!change.reviewRequired && !change.approvedBy)}
                  reason={change.reviewRequired && !change.approvedBy ? "Cần một thành viên khác duyệt trước khi hợp nhất." : undefined} onClick={() => void merge(change)}>{busy === "merge" ? "Đang hợp nhất…" : "Hợp nhất vào main"}</ReasonButton> : null}
                {canEdit && ["BUILDING", "READY", "FAILED"].includes(change.status) ? <button className="button ghost" disabled={busy !== null} onClick={() => void discard(change)}>Bỏ thay đổi</button> : null}
              </div>
            </div>
            {change.reviewRequired ? <p className="hint">{change.approvedBy ? `Đã duyệt bởi ${change.approvedBy}${change.reviewComment ? ` — “${change.reviewComment}”` : ""}` : "Dự án yêu cầu duyệt: một thành viên có quyền xuất bản (không phải người tạo) cần duyệt trước khi hợp nhất."}</p> : null}
            <TabPanel idBase={detailTabs} value={tab}>
            {tab === "preview" ? (change.previewUrl
              ? <><iframe className="appPreview" title="Bản xem trước ứng dụng" sandbox="allow-scripts" src={change.previewUrl}/>
                <p className="hint">Chạy cách ly (không cookie/lưu trữ), liên kết hết hạn {change.previewExpiresAt ? fmtDate(change.previewExpiresAt) : ""}. <a href={change.previewUrl} target="_blank" rel="noopener noreferrer">Mở trong tab mới</a></p></>
              : change.status === "BUILDING" ? <StateView kind="loading" title="Đang build trong sandbox…" detail={<p>{change.build?.stage ? `Bước: ${STAGE[change.build.stage] ?? change.build.stage}` : "Chờ runner nhận việc"}</p>}/>
              : change.status === "FAILED" ? <StateView kind="error" title="Build không thành công" detail={<p>{change.error}</p>}/>
              : <p className="hint">Không có bản xem trước (đã hợp nhất hoặc hết hạn).</p>) : null}
            {tab === "diff" ? (diff ? <DiffView files={diff}/> : <StateView kind="loading"/>) : null}
            {tab === "log" ? <div className="buildInfo">
              {change.build ? <>
                <p><b>{change.build.status}</b> · bắt đầu {change.build.startedAt ? fmtDate(change.build.startedAt) : "—"}{change.build.finishedAt ? ` · xong ${fmtDate(change.build.finishedAt)}` : ""}</p>
                {change.build.scans ? <ul className="plainList">
                  <li>Quét bí mật (mã nguồn): {change.build.scans.sourceSecrets ? `${change.build.scans.sourceSecrets.findings.length} phát hiện` : "—"}</li>
                  <li>Lỗ hổng thư viện (OSV): {change.build.scans.dependencies ? `${change.build.scans.dependencies.findings.length} cảnh báo / ${change.build.scans.dependencies.packages} gói` : "—"}</li>
                  <li>Quét bí mật (kết quả build): {change.build.scans.outputSecrets ? `${change.build.scans.outputSecrets.findings.length} phát hiện` : "—"}</li>
                  <li>SBOM: {change.build.scans.sbom ? `${change.build.scans.sbom.length} gói` : "—"}</li></ul> : null}
                {change.build.error ? <p className="formError">{change.build.error}</p> : null}
                <pre className="buildLog">{change.build.log ?? "(chưa có log)"}</pre></> : <p className="hint">Chưa có thông tin build.</p>}
            </div> : null}
            </TabPanel>
          </div> : null}
        </section>
      </main>
      {reviewing ? <ReviewDialog summary={reviewing.summary} busy={busy === "approve"} onApprove={(comment) => void approve(reviewing, comment)} onClose={() => setReviewing(null)}/> : null}
      {panel === "members" && me ? <MembersDrawer workspaceId={ws} projectId={pid} me={me} onClose={() => go(mode)} onError={(e) => toast.error(errText(e, "Thao tác thành viên thất bại."))}/> : null}
      {panel === "publish" ? <PublishModal workspaceId={ws} projectId={pid} revision={project.revision} current="PRIVATE" canPublish={canPublishApp} allowed={cfg?.codeAppPublicPublish === false || cfg?.publicPublish === false ? ["PRIVATE"] : ["PRIVATE", "PUBLIC"]} onClose={() => { go(mode); api.lookupProject(pid).then(onProject).catch(() => undefined); }}
        onUnauthorized={() => toast.warning("Phiên đăng nhập đã hết hạn.")}/> : null}
      {panel === "versions" ? <Drawer title="Lịch sử (commit trên main)" sub="Lấy trực tiếp từ kho Git của nền tảng." onClose={() => go(mode)}>
        {commits == null ? <StateView kind="loading"/> : <ol className="commitList">{commits.map((c) => <li key={c.sha}><b>{c.message.split("\n")[0]}</b>
          <small className="code">{c.sha.slice(0, 10)} {c.verified ? <span className="pill pill-ok">Đã ký · {c.signer}</span> : <span className="pill pill-muted">Chưa ký</span>}</small>
          <small>Tác giả {c.author} · commit bởi {c.committer} · {fmtDate(c.date)}</small></li>)}</ol>}
        {canEdit ? <section className="settingGroup"><h3>Chính sách hợp nhất</h3>
          <select aria-label="Chính sách hợp nhất" defaultValue="" onChange={(e) => void act("policy", () => api.code.mergePolicy(ws, pid, (e.target.value || null) as "AUTO_MERGE_ALLOWED" | "REVIEW_REQUIRED" | null), "Không đổi được.").then((r) => { if (r) { toast.info(`Chính sách hiện hành: ${r.effective === "REVIEW_REQUIRED" ? "cần duyệt" : "hợp nhất trực tiếp"}`); void loadChanges(); } })}>
            <option value="">Theo workspace</option><option value="AUTO_MERGE_ALLOWED">Hợp nhất trực tiếp sau khi build xanh</option><option value="REVIEW_REQUIRED">Cần người khác duyệt</option></select></section> : null}
      </Drawer> : null}
      {panel === "packages" ? <PackagesDrawer ws={ws} pid={pid} canEdit={canEdit} onClose={() => go(mode)} onChange={(id) => { setSelected(id); go(mode); void loadChanges(); }}/> : null}
      {panel === "ide" ? <IdeDrawer ws={ws} pid={pid} onClose={() => go(mode)}/> : null}
      {panel === "runtime" && isServer ? <RuntimeDrawer ws={ws} pid={pid} canPublish={canPublishApp} canSettings={canEdit} onClose={() => go(mode)}/> : null}
    </div>
  );
}
