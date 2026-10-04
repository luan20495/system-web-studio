"use client";
// Code-project panels: Design mode (safe AST edits), approved packages, IDE clone access.
import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { CloneAccess, CodeChange, DependencyRequest, DesignNode, RuntimeStatus } from "@/lib/http-types";
import { ago, errText, StateView } from "../ui";
import { Drawer } from "./drawers";

const REQ_STATUS: Record<DependencyRequest["status"], string> = { LOCKING: "Đang tạo lockfile", COMMITTED: "Đã tạo thay đổi", REJECTED: "Bị từ chối", FAILED: "Lỗi" };
const PROP_LABEL: Record<string, string> = { gap: "Khoảng cách", padding: "Lề trong", tone: "Tông màu", variant: "Kiểu", size: "Cỡ", level: "Cấp tiêu đề", title: "Tiêu đề",
  label: "Nhãn", placeholder: "Gợi ý nhập", brand: "Tên thương hiệu", hint: "Gợi ý", direction: "Hướng", align: "Căn", alt: "Mô tả ảnh", "aria-label": "Nhãn trợ năng" };

/** Design mode V1 for code apps: element tree of src/App.tsx; text, token props and visibility edited through the AST service. */
export function DesignPane({ ws, pid, canEdit, onChange }: { ws: string; pid: string; canEdit: boolean; onChange: (c: CodeChange) => void }) {
  const [path, setPath] = useState("src/App.tsx");
  const [nodes, setNodes] = useState<DesignNode[] | null>(null); const [ok, setOk] = useState(true);
  const [sel, setSel] = useState<string | null>(null);
  const [text, setText] = useState(""); const [props, setProps] = useState<Record<string, string | null>>({}); const [hidden, setHidden] = useState(false);
  const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const load = useCallback(() => { setNodes(null); api.code.design(ws, pid, path).then((t) => { setOk(t.ok); setNodes(t.nodes); }).catch((e) => setErr(errText(e, "Không đọc được tệp."))); }, [ws, pid, path]);
  useEffect(() => { load(); }, [load]);
  const node = nodes?.find((n) => n.id === sel) ?? null;
  useEffect(() => { if (!node) return; setText(node.text ?? ""); setHidden(node.hidden); setProps({}); setErr(null); }, [node?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  async function apply() {
    if (!node) return;
    const changed = Object.fromEntries(Object.entries(props).filter(([k, v]) => (node.props.find((p) => p.name === k)?.value ?? null) !== v));
    const body = { path, nodeId: node.id, ...(node.textEditable && text.trim() !== (node.text ?? "") ? { text } : {}), ...(node.hiddenEditable && hidden !== node.hidden ? { hidden } : {}),
      ...(Object.keys(changed).length ? { props: changed } : {}), summary: `Chỉnh ${node.tag} (${path}:${node.line})` };
    if (Object.keys(body).length <= 3) { setErr("Chưa có thay đổi nào."); return; }
    setBusy(true); setErr(null);
    try { const c = await api.code.designEdit(ws, pid, body); onChange(c); setSel(null); load(); }
    catch (e) { setErr(e instanceof ApiError ? e.message : errText(e, "Không áp dụng được.")); } finally { setBusy(false); }
  }
  return <div className="designCode">
    <div className="editorHead"><label className="row">Tệp <input aria-label="Tệp thiết kế" value={path} onChange={(e) => setPath(e.target.value)} onBlur={load}/></label>
      <span className="hint">Chỉ sửa được văn bản, thuộc tính theo design token và ẩn/hiện. Phần còn lại: chế độ Code.</span></div>
    {!nodes ? <StateView kind="loading"/> : !ok ? <StateView kind="error" title="Tệp có lỗi cú pháp" detail={<p>Sửa trong chế độ Code trước.</p>}/> :
      <div className="designSplit">
        <ul className="designTree" aria-label="Cây thành phần">{nodes.map((n) => <li key={n.id}>
          <button className={`${n.id === sel ? "active" : ""} ${n.codeOnly ? "codeOnly" : ""}`} style={{ paddingLeft: 8 + n.depth * 14 }} onClick={() => setSel(n.id)}
            title={n.codeOnly ?? undefined}>&lt;{n.tag}&gt;{n.text ? <small> {n.text.slice(0, 30)}</small> : null}{n.hidden ? <small> (ẩn)</small> : null}{n.codeOnly ? <small> · chỉ Code</small> : null}</button></li>)}</ul>
        <div className="designProps">
          {!node ? <p className="hint">Chọn một thành phần.</p> : node.codeOnly ? <p className="notice">{node.codeOnly}</p> : <form onSubmit={(e) => { e.preventDefault(); void apply(); }} className="inlineForm">
            <b>&lt;{node.tag}&gt; <small className="hint">dòng {node.line}{node.company ? " · @company/ui" : ""}</small></b>
            {node.textEditable ? <label>Văn bản<input value={text} maxLength={500} onChange={(e) => setText(e.target.value)} disabled={!canEdit}/></label> : null}
            {node.props.map((p) => <label key={p.name}>{PROP_LABEL[p.name] ?? p.name}
              {p.kind === "expression" ? <small className="hint"> — biểu thức, sửa trong Code</small> :
                p.allowed ? <select value={props[p.name] !== undefined ? props[p.name] ?? "" : p.value ?? ""} disabled={!canEdit} onChange={(e) => setProps((x) => ({ ...x, [p.name]: e.target.value || null }))}>
                  <option value="">(mặc định)</option>{p.allowed.map((v) => <option key={v} value={v}>{v}</option>)}</select>
                : <input value={props[p.name] !== undefined ? props[p.name] ?? "" : p.value ?? ""} maxLength={200} disabled={!canEdit} onChange={(e) => setProps((x) => ({ ...x, [p.name]: e.target.value || null }))}/>}
            </label>)}
            {node.hiddenEditable ? <label className="switch"><input type="checkbox" checked={hidden} disabled={!canEdit} onChange={(e) => setHidden(e.target.checked)}/> Ẩn thành phần này</label> : null}
            {canEdit ? <button className="button primary" disabled={busy}>{busy ? "Đang tạo…" : "Tạo thay đổi & build"}</button> : null}
            {err ? <p className="formError" role="alert">{err}</p> : null}
          </form>}
        </div>
      </div>}
  </div>;
}

/** Approved packages: request one (catalog only); the platform writes package.json/lockfile in the sandbox and opens a change. */
export function PackagesDrawer({ ws, pid, canEdit, onClose, onChange }: { ws: string; pid: string; canEdit: boolean; onClose: () => void; onChange: (id: string) => void }) {
  const [data, setData] = useState<{ requests: DependencyRequest[]; approved: { name: string; spec: string }[] } | null>(null);
  const [name, setName] = useState(""); const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  const load = useCallback(() => api.code.dependencies(ws, pid).then(setData).catch((e) => setErr(errText(e, "Không tải được."))), [ws, pid]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (!data?.requests.some((r) => r.status === "LOCKING")) return; const t = setInterval(() => void load(), 2500); return () => clearInterval(t); }, [data, load]);
  async function request(n: string) {
    setErr(null); setMsg(null);
    try { await api.code.requestDependency(ws, pid, n.trim()); setMsg(`Đã yêu cầu ${n}. Lockfile được tạo trong sandbox rồi thành một thay đổi cần build.`); setName(""); void load(); }
    catch (e) { setErr(e instanceof ApiError ? e.message : errText(e, "Không gửi được.")); }
  }
  return <Drawer title="Thư viện (package)" sub="Chỉ dùng được package công ty đã duyệt. Không chạy lệnh cài đặt tuỳ ý; phiên bản do danh mục quyết định." onClose={onClose}>
    {!data ? <StateView kind="loading"/> : <>
      <section className="settingGroup"><h3>Đã duyệt</h3>
        {data.approved.length === 0 ? <p className="hint">Chưa có package nào ngoài React và @company/*.</p> :
          <ul className="plainList">{data.approved.map((a) => <li key={a.name} className="row between"><span className="code">{a.name} <small>{a.spec}</small></span>
            {canEdit ? <button className="button ghost" onClick={() => void request(a.name)}>Thêm</button> : null}</li>)}</ul>}
        {canEdit ? <form className="row" onSubmit={(e) => { e.preventDefault(); if (name.trim()) void request(name); }}>
          <input aria-label="Tên package" placeholder="Tên package khác (gửi quản trị viên duyệt)" value={name} onChange={(e) => setName(e.target.value)}/>
          <button className="button primary" disabled={!name.trim()}>Yêu cầu</button></form> : null}
        {msg ? <p className="hint" role="status">{msg}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
      </section>
      <section className="settingGroup"><h3>Yêu cầu gần đây</h3>
        {data.requests.length === 0 ? <p className="hint">Chưa có.</p> : <ul className="plainList">{data.requests.map((r) => <li key={r.id}>
          <b className="code">{r.packageName}</b> <small>{r.spec}</small> · {REQ_STATUS[r.status]} · {ago(r.createdAt)}
          {r.changeId ? <> · <button className="linkButton" onClick={() => onChange(r.changeId!)}>xem thay đổi</button></> : null}
          {r.error ? <small className="formError">{r.error}</small> : null}</li>)}</ul>}
      </section></>}
  </Drawer>;
}

/** Read-only clone access for VS Code / Cursor / git CLI. The token is shown once. */
export function IdeDrawer({ ws, pid, onClose }: { ws: string; pid: string; onClose: () => void }) {
  const [a, setA] = useState<CloneAccess | null>(null); const [err, setErr] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  async function issue() { setBusy(true); setErr(null); try { setA(await api.code.cloneAccess(ws, pid)); } catch (e) { setErr(e instanceof ApiError ? e.message : errText(e, "Không tạo được.")); } finally { setBusy(false); } }
  async function revoke() { setErr(null); try { await api.code.revokeCloneAccess(); setA(null); } catch (e) { setErr(errText(e, "Không thu hồi được.")); } }
  const withCred = a ? a.cloneUrl.replace("://", `://${encodeURIComponent(a.username)}:${a.token}@`) : "";
  return <Drawer title="Mở bằng IDE" sub="Truy cập CHỈ ĐỌC kho mã của ứng dụng. Thay đổi vẫn đi qua Studio (build trong sandbox → hợp nhất)." onClose={onClose}>
    <section className="settingGroup">
      {!a ? <><p className="hint">Tạo token chỉ đọc cho riêng bạn. Token hiện một lần; tạo lại sẽ vô hiệu token cũ.</p>
        <button className="button primary" disabled={busy} onClick={() => void issue()}>{busy ? "Đang tạo…" : "Tạo token clone"}</button></> : <>
        <p><b>Clone URL</b><br/><code className="breakAll">{a.cloneUrl}</code></p>
        <p><b>Tài khoản</b> <code>{a.username}</code> · <b>Token</b> <code className="breakAll">{a.token}</code></p>
        <h4>git CLI</h4><pre className="buildLog">git clone {withCred}</pre>
        <h4>VS Code / Cursor</h4><p className="hint">Command Palette → “Git: Clone” → dán URL trên (đã kèm token), hoặc clone bằng git CLI rồi “Open Folder”. Để chạy thử: <code>npm ci && npm run build</code> (cần quyền tới mirror package của công ty).</p>
        <p className="hint">{a.note}</p>
        <button className="button ghost" onClick={() => void revoke()}>Thu hồi token</button></>}
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </section>
  </Drawer>;
}

const SD_LABEL: Record<string, string> = { PENDING: "Chờ khởi động", STARTING: "Đang khởi động", RUNNING: "Đang chạy", FAILED: "Lỗi", SUPERSEDED: "Đã thay thế", STOPPED: "Đã dừng" };
/** Server runtime of a server app (ADR 0017): deployments (blue/green, rollback without rebuild), write-only secrets, recent logs. */
export function RuntimeDrawer({ ws, pid, canPublish, canSettings, onClose }: { ws: string; pid: string; canPublish: boolean; canSettings: boolean; onClose: () => void }) {
  const [rt, setRt] = useState<RuntimeStatus | null>(null); const [err, setErr] = useState<string | null>(null);
  const [name, setName] = useState(""); const [value, setValue] = useState("");
  const load = useCallback(() => api.runtime(ws, pid).then(setRt).catch((e) => setErr(errText(e, "Không tải được."))), [ws, pid]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (!rt || !(rt.desiredDeploymentId || rt.deployments.some((d) => d.status === "PENDING" || d.status === "STARTING"))) return; const t = setInterval(() => void load(), 3000); return () => clearInterval(t); }, [rt, load]);
  async function act(fn: () => Promise<RuntimeStatus>) { setErr(null); try { setRt(await fn()); } catch (e) { setErr(e instanceof ApiError ? e.message : errText(e, "Không thực hiện được.")); } }
  return <Drawer title="Máy chủ của ứng dụng" sub="Mỗi lần xuất bản: build trong sandbox → container mới được kiểm tra sức khỏe → chuyển lưu lượng (bản cũ phục vụ tới khi bản mới khỏe)." onClose={onClose} wide>
    {!rt ? (err ? <p className="formError" role="alert">{err}</p> : <StateView kind="loading"/>) : <>
      <p className="hint">{rt.notice}{rt.database ? <> Cơ sở dữ liệu riêng: <code>{rt.database}</code> (mật khẩu không bao giờ hiển thị).</> : null}</p>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      <section className="settingGroup"><h3>Các lần triển khai</h3>
        {rt.deployments.length === 0 ? <p className="hint">Chưa triển khai. Bấm “Xuất bản” để build và chạy máy chủ.</p> :
          <ul className="plainList">{rt.deployments.map((d) => <li key={d.id} className="row between">
            <span><b>v{d.version}</b> {SD_LABEL[d.status] ?? d.status}{d.current ? " · đang phục vụ" : ""}{d.rollbackOf ? " · khôi phục" : ""} <small>{ago(d.createdAt)} · {d.routes} đường dẫn{d.commitSha ? ` · ${d.commitSha.slice(0, 8)}` : ""}</small>
              {d.error ? <small className="formError">{d.error}</small> : null}</span>
            {canPublish && !d.current && (d.status === "SUPERSEDED" || d.status === "STOPPED") ? <button className="smallButton" onClick={() => void act(() => api.runtimeRollback(ws, pid, d.id))}>Khôi phục bản này</button> : null}
          </li>)}</ul>}
        {canPublish && rt.currentDeploymentId ? <button className="button ghost" onClick={() => { if (confirm("Dừng máy chủ của ứng dụng? API sẽ ngừng trả lời tới khi xuất bản lại.")) void act(() => api.runtimeStop(ws, pid)); }}>Dừng máy chủ</button> : null}
      </section>
      <section className="settingGroup"><h3>Bí mật (biến môi trường)</h3>
        <p className="hint">Giá trị được mã hóa, chỉ ghi: không bao giờ hiển thị lại, không vào kho mã và không gửi cho AI. Áp dụng ở lần triển khai tiếp theo.</p>
        {rt.secrets.length ? <ul className="plainList">{rt.secrets.map((s) => <li key={s.name} className="row between"><span className="code">{s.name}</span><small>{s.updatedBy ?? "—"} · {ago(s.updatedAt)}</small>
          {canSettings ? <button className="smallButton" onClick={() => void act(() => api.deleteSecret(ws, pid, s.name))}>Xoá</button> : null}</li>)}</ul> : <p className="hint">Chưa có bí mật.</p>}
        {canSettings ? <form className="row" onSubmit={(e) => { e.preventDefault(); void act(() => api.setSecret(ws, pid, name.trim(), value)).then(() => { setName(""); setValue(""); }); }}>
          <input aria-label="Tên biến" placeholder="TEN_BIEN" value={name} onChange={(e) => setName(e.target.value.toUpperCase())} maxLength={64}/>
          <input aria-label="Giá trị" type="password" autoComplete="off" placeholder="Giá trị" value={value} onChange={(e) => setValue(e.target.value)} maxLength={4000}/>
          <button className="button" disabled={!/^[A-Z][A-Z0-9_]{1,63}$/.test(name.trim()) || !value}>Lưu</button></form> : null}
      </section>
      <section className="settingGroup"><h3>Connector được cấp</h3>
        {rt.connectors.length ? <p>{rt.connectors.map((c) => <code key={c} className="tag">{c}</code>)}</p> : <p className="hint">Chưa có. Quản trị viên cấp connector đã duyệt cho ứng dụng (Admin → Connector).</p>}
      </section>
      <section className="settingGroup"><h3>Nhật ký gần đây</h3>
        {rt.logs ? <><small className="hint">{rt.logsAt ? ago(rt.logsAt) : ""} · đã che thông tin nhạy cảm</small><pre className="buildLog">{rt.logs}</pre></> : <p className="hint">Chưa có nhật ký.</p>}
      </section></>}
  </Drawer>;
}
