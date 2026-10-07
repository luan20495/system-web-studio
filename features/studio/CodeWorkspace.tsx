"use client";
// Code projects (STATIC_APP, ADR 0008/0012): AI and Code modes over a real Git repository; every change is a commit on its own branch,
// built in the sandbox, previewed from the sites origin (CSP sandbox) and merged only after a green build.
import { canEditProject, canPublish, canShare, resolvePermissions } from "@xweb/permissions";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/http-api";
import { SERVER_KINDS, type AiStatus, type ApiProject, type AuthConfig, type CodeAiHistoryItem, type CodeChange, type CodeCommit, type DiffFile, type TreeFile } from "@/lib/http-types";
import { useSession } from "../session";
import { ago, ErrorState, errText, fmtDate, StateView, tok, usd } from "../ui";
import { Drawer, MembersDrawer } from "./drawers";
import { PublishModal } from "./ReleaseModal";
import { DesignPane, IdeDrawer, PackagesDrawer, RuntimeDrawer } from "./CodePanels";
import { projectBase, S } from "./base";

const STATUS: Record<CodeChange["status"], string> = { BUILDING: "Đang build", READY: "Sẵn sàng", FAILED: "Build lỗi", MERGED: "Đã hợp nhất", DISCARDED: "Đã huỷ" };
const STAGE: Record<string, string> = { CLAIMED: "đã nhận", SOURCE: "lấy mã", SCAN_SOURCE: "quét mã", PREPARE: "chuẩn bị", INSTALL: "cài gói", BUILD: "build",
  PACKAGE: "đóng gói", SCAN_OUTPUT: "quét kết quả", UPLOAD: "tải lên", UPLOADED: "đã tải lên", DONE: "xong" };

/** Line diff (LCS) for small source files. */
function lineDiff(a: string, b: string): { t: " " | "-" | "+"; s: string }[] {
  const x = a.split("\n"), y = b.split("\n");
  if (x.length * y.length > 4_000_000) return [...x.map((s) => ({ t: "-" as const, s })), ...y.map((s) => ({ t: "+" as const, s }))];
  const m = Array.from({ length: x.length + 1 }, () => new Int32Array(y.length + 1));
  for (let i = x.length - 1; i >= 0; i--) for (let j = y.length - 1; j >= 0; j--) m[i][j] = x[i] === y[j] ? m[i + 1][j + 1] + 1 : Math.max(m[i + 1][j], m[i][j + 1]);
  const out: { t: " " | "-" | "+"; s: string }[] = []; let i = 0, j = 0;
  while (i < x.length && j < y.length) { if (x[i] === y[j]) { out.push({ t: " ", s: x[i] }); i++; j++; } else if (m[i + 1][j] >= m[i][j + 1]) out.push({ t: "-", s: x[i++] }); else out.push({ t: "+", s: y[j++] }); }
  while (i < x.length) out.push({ t: "-", s: x[i++] }); while (j < y.length) out.push({ t: "+", s: y[j++] });
  return out;
}

function DiffView({ files }: { files: DiffFile[] }) {
  return <div className="diffView">{files.map((f) => {
    const rows = lineDiff(f.before ?? "", f.after ?? "");
    // show changed lines with 2 lines of context
    const keep = rows.map((r, i) => r.t !== " " || rows.slice(Math.max(0, i - 2), i + 3).some((x) => x.t !== " "));
    return <section key={f.path}><h4>{f.path} {f.before == null ? <em>(mới)</em> : f.after == null ? <em>(xoá)</em> : null}</h4>
      <pre>{rows.map((r, i) => keep[i] ? <div key={i} className={`dl ${r.t === "+" ? "add" : r.t === "-" ? "del" : ""}`}>{r.t} {r.s}</div> : (keep[i - 1] ? <div key={i} className="dl gap">⋯</div> : null))}</pre></section>;
  })}</div>;
}

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
  const [busy, setBusy] = useState<string | null>(null); const [notice, setNotice] = useState<string | null>(null); const [error, setError] = useState<unknown>(null);
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
    api.code.file(ws, pid, path).then((f) => setOriginal((o) => ({ ...o, [path]: { text: f.text, editable: f.editable } }))).catch((e) => setNotice(errText(e, "Không đọc được tệp.")));
  }, [path, tree, original, ws, pid]);
  const change = changes.find((c) => c.id === selected) ?? null;
  useEffect(() => { setDiff(null); if (change && tab === "diff") api.code.diff(ws, pid, change.id).then(setDiff).catch(() => undefined); }, [change?.id, tab, ws, pid]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (panel === "versions") api.code.commits(ws, pid).then(setCommits).catch(() => setCommits([])); }, [panel, ws, pid]);

  const valid = (m: string) => m === "auto" || m === "mock" || (ai?.models ?? []).some((x) => x.id === m);
  const effectiveModel = ai?.configured ? (model && valid(model) ? model : valid(ai.defaultModel) ? ai.defaultModel : "auto") : "mock";
  const dirty = Object.keys(drafts).filter((p) => drafts[p] !== original[p]?.text);
  const current = drafts[path] ?? original[path]?.text ?? "";
  const editable = canEdit && !!original[path]?.editable;
  const files = useMemo(() => (tree ?? []).slice().sort((a, b) => a.path.localeCompare(b.path)), [tree]);

  async function act<T>(label: string, fn: () => Promise<T>, fallback: string): Promise<T | undefined> {
    setBusy(label); setNotice(null);
    try { return await fn(); } catch (e) { setNotice(e instanceof ApiError ? `${e.message}${e.code ? ` (${e.code})` : ""}` : errText(e, fallback)); return undefined; } finally { setBusy(null); }
  }
  async function propose() {
    const c = await act("propose", () => api.code.propose(ws, pid, summary.trim() || `Sửa ${dirty.join(", ")}`, dirty.map((p) => ({ path: p, content: drafts[p] }))), "Không tạo được thay đổi.");
    if (c) { setDrafts({}); setSummary(""); setSelected(c.id); setTab("preview"); void loadChanges(); setNotice("Đã tạo thay đổi trên nhánh riêng; đang build trong sandbox."); }
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
      setNotice(`${r.message}${r.usage?.totalTokens != null ? ` · ${tok(r.usage.totalTokens)} token${r.usage.costUsd != null ? ` · ${usd(r.usage.costUsd)}` : ""}` : ""}`);
    }
  }
  async function merge(c: CodeChange) {
    const m = await act("merge", () => api.code.merge(ws, pid, c.id), "Không hợp nhất được.");
    if (m) { void loadChanges(); setOriginal({}); void loadTree(); api.lookupProject(pid).then(onProject).catch(() => undefined); setNotice("Đã hợp nhất vào main. Có thể xuất bản."); }
  }
  async function approve(c: CodeChange) {
    const comment = window.prompt("Nhận xét khi duyệt (tuỳ chọn):") ?? undefined;
    if (await act("approve", () => api.code.approve(ws, pid, c.id, comment), "Không duyệt được.")) void loadChanges();
  }
  async function discard(c: CodeChange) { if (await act("discard", () => api.code.discard(ws, pid, c.id), "Không huỷ được.")) void loadChanges(); }

  if (error) return <div className="wsError"><ErrorState error={error} retry={() => { setError(null); void loadTree(); }}/></div>;
  return (
    <div className="studio codeStudio">
      <header className="topbar">
        <div className="brand">
          <button className="button icon" aria-label="Danh sách ứng dụng" title="Danh sách ứng dụng" onClick={() => router.push(S("/projects"))}>←</button>
          <div>
            <div className="projectName">{project.name}</div>
            <div className="projectMeta">Ứng dụng web (mã nguồn) · React + Vite · revision {project.revision}{canEdit ? "" : " · chỉ xem"}</div>
          </div>
        </div>
        <nav className="modeTabs" aria-label="Chế độ">
          {(["ai", "design", "code"] as const).map((m) => <button key={m} className={mode === m && !panel ? "active" : ""} aria-pressed={mode === m} onClick={() => go(m)}>{m === "ai" ? "✦ AI" : m === "design" ? "Design" : "Code"}</button>)}
        </nav>
        <div className="topActions">
          <button className="button ghost" onClick={() => go("versions")}>Lịch sử</button>
          <button className="button ghost" onClick={() => go("packages")}>Thư viện</button>
          <button className="button ghost" onClick={() => go("ide")}>IDE</button>
          {isServer ? <button className="button ghost" onClick={() => go("runtime")}>Máy chủ</button> : null}
          {canShareApp ? <button className="button ghost" onClick={() => go("members")}>Chia sẻ</button> : null}
          <button className="button primary" disabled={!canPublishApp} onClick={() => go("publish")}>Xuất bản</button>
        </div>
      </header>
      <main className="codeBody">
        <section className="codeLeft" aria-label={mode === "ai" ? "AI" : mode === "design" ? "Thiết kế" : "Mã nguồn"}>
          {mode === "design" ? <DesignPane ws={ws} pid={pid} canEdit={canEdit} onChange={(c) => { setSelected(c.id); setTab("preview"); void loadChanges(); setNotice("Đã tạo thay đổi giao diện; đang build trong sandbox."); }}/> : mode === "ai" ? (<>
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
                {live ? <span className="hint" role="status">{live.status.startsWith("tool:") ? `AI đang dùng ${live.status.slice(5)}…` : `Đang nhận… ${live.chars} ký tự`}
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
                    onKeyDown={(e) => { if (e.key === "Tab" && editable) { e.preventDefault(); const t = e.currentTarget, s = t.selectionStart; const v = t.value.slice(0, s) + "  " + t.value.slice(t.selectionEnd); setDrafts((d) => ({ ...d, [path]: v })); requestAnimationFrame(() => { t.selectionStart = t.selectionEnd = s + 2; }); } }}/>}
                {canEdit ? <div className="draftBar">
                  <span>{dirty.length ? `Bản nháp: ${dirty.length} tệp` : "Chưa có thay đổi"}</span>
                  <input aria-label="Mô tả thay đổi" placeholder="Mô tả ngắn (tuỳ chọn)" value={summary} maxLength={300} onChange={(e) => setSummary(e.target.value)}/>
                  <button className="button ghost" disabled={!dirty.length || busy !== null} onClick={() => setDrafts({})}>Bỏ nháp</button>
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
              <div className="tabs" role="tablist">{(["preview", "diff", "log"] as const).map((t) => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
                {t === "preview" ? "Xem trước" : t === "diff" ? "Mã thay đổi" : "Build & quét"}</button>)}</div>
              <div className="row">
                {change.status === "READY" && change.reviewRequired && !change.approvedBy && me && change.createdBy !== me.displayName && canPublishApp
                  ? <button className="button ghost" disabled={busy !== null} onClick={() => void approve(change)}>Duyệt</button> : null}
                {canEdit && change.status === "READY" ? <button className="button primary" disabled={busy !== null || (!!change.reviewRequired && !change.approvedBy)}
                  title={change.reviewRequired && !change.approvedBy ? "Cần một thành viên khác duyệt trước" : undefined} onClick={() => void merge(change)}>{busy === "merge" ? "Đang hợp nhất…" : "Hợp nhất vào main"}</button> : null}
                {canEdit && ["BUILDING", "READY", "FAILED"].includes(change.status) ? <button className="button ghost" disabled={busy !== null} onClick={() => void discard(change)}>Huỷ</button> : null}
              </div>
            </div>
            {change.reviewRequired ? <p className="hint">{change.approvedBy ? `Đã duyệt bởi ${change.approvedBy}${change.reviewComment ? ` — “${change.reviewComment}”` : ""}` : "Dự án yêu cầu duyệt: một thành viên có quyền xuất bản (không phải người tạo) cần duyệt trước khi hợp nhất."}</p> : null}
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
          </div> : null}
        </section>
      </main>
      {notice ? <button className="toast" onClick={() => setNotice(null)}>{notice}</button> : null}
      {panel === "members" && me ? <MembersDrawer workspaceId={ws} projectId={pid} me={me} onClose={() => go(mode)} onError={(e) => setNotice(errText(e, "Thao tác thành viên thất bại."))}/> : null}
      {panel === "publish" ? <PublishModal workspaceId={ws} projectId={pid} revision={project.revision} current="PRIVATE" canPublish={canPublishApp} allowed={cfg?.codeAppPublicPublish === false || cfg?.publicPublish === false ? ["PRIVATE"] : ["PRIVATE", "PUBLIC"]} onClose={() => { go(mode); api.lookupProject(pid).then(onProject).catch(() => undefined); }}
        onUnauthorized={() => setNotice("Phiên đăng nhập đã hết hạn.")}/> : null}
      {panel === "versions" ? <Drawer title="Lịch sử (commit trên main)" sub="Lấy trực tiếp từ kho Git của nền tảng." onClose={() => go(mode)}>
        {commits == null ? <StateView kind="loading"/> : <ol className="commitList">{commits.map((c) => <li key={c.sha}><b>{c.message.split("\n")[0]}</b>
          <small className="code">{c.sha.slice(0, 10)} {c.verified ? <span className="pill pill-ok">Đã ký · {c.signer}</span> : <span className="pill pill-muted">Chưa ký</span>}</small>
          <small>Tác giả {c.author} · commit bởi {c.committer} · {fmtDate(c.date)}</small></li>)}</ol>}
        {canEdit ? <section className="settingGroup"><h3>Chính sách hợp nhất</h3>
          <select aria-label="Chính sách hợp nhất" defaultValue="" onChange={(e) => void act("policy", () => api.code.mergePolicy(ws, pid, (e.target.value || null) as "AUTO_MERGE_ALLOWED" | "REVIEW_REQUIRED" | null), "Không đổi được.").then((r) => { if (r) { setNotice(`Chính sách hiện hành: ${r.effective === "REVIEW_REQUIRED" ? "cần duyệt" : "hợp nhất trực tiếp"}`); void loadChanges(); } })}>
            <option value="">Theo workspace</option><option value="AUTO_MERGE_ALLOWED">Hợp nhất trực tiếp sau khi build xanh</option><option value="REVIEW_REQUIRED">Cần người khác duyệt</option></select></section> : null}
      </Drawer> : null}
      {panel === "packages" ? <PackagesDrawer ws={ws} pid={pid} canEdit={canEdit} onClose={() => go(mode)} onChange={(id) => { setSelected(id); go(mode); void loadChanges(); }}/> : null}
      {panel === "ide" ? <IdeDrawer ws={ws} pid={pid} onClose={() => go(mode)}/> : null}
      {panel === "runtime" && isServer ? <RuntimeDrawer ws={ws} pid={pid} canPublish={canPublishApp} canSettings={canEdit} onClose={() => go(mode)}/> : null}
    </div>
  );
}
