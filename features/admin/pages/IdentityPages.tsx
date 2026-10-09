"use client";

import { ScopePicker } from "./AiGovernancePage";
import { Fragment, useState } from "react";
import { api } from "@/lib/http-api";
import type { Connector, Department } from "@/lib/http-types";
import { LoadGate, confirm, prompt } from "@xweb/ui";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, Kpi, Pill, StateView } from "../../ui";
import { PageHead } from "../PageHead";
import { useAdminAction } from "../useAdminAction";

export function DepartmentsPage() {
  const { data, error, reload } = useLoad(() => api.admin.departments(), []);
  const [name, setName] = useState(""); const [parent, setParent] = useState("");
  const [who, setWho] = useState({ type: "USER", id: "" }); const [target, setTarget] = useState("");
  const { act, busy, msg, err } = useAdminAction("Không thực hiện được.", reload);
  const deps = (data ?? []).filter((d) => d.kind === "DEPARTMENT");
  const teamsOf = (id: string) => (data ?? []).filter((d) => d.parentId === id);
  function row(d: Department) {
    return <li key={d.id} className="deptRow"><b>{d.name}</b> <small>{d.kind === "TEAM" ? "nhóm" : "phòng ban"} · {d.users} người · {d.workspaces} workspace</small>
      <button className="btn sm ghost" onClick={async () => { const n = (await prompt({ title: `Đổi tên “${d.name}”`, label: "Tên mới", defaultValue: d.name, required: true, maxLength: 120, confirmLabel: "Đổi tên" }))?.trim(); if (n) void act(() => api.admin.renameDepartment(d.id, { name: n })); }}>Đổi tên</button>
      <button className="btn sm ghost" onClick={async () => { if (await confirm({ title: `Xóa “${d.name}”?`, message: "Mục này sẽ bị xóa khỏi cơ cấu phòng ban và nhóm.", confirmLabel: "Xóa", danger: true })) void act(() => api.admin.deleteDepartment(d.id)); }}>Xóa</button></li>;
  }
  return (<>
    <PageHead title="Phòng ban & nhóm" sub="Nhóm tổ chức để báo cáo chi phí và sử dụng. Không cấp quyền: quyền truy cập vẫn theo thành viên workspace/ứng dụng."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Thêm">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (name.trim()) void act(() => api.admin.createDepartment({ name: name.trim(), kind: parent ? "TEAM" : "DEPARTMENT", parentId: parent || undefined }).then(() => setName(""))); }}>
        <input aria-label="Tên" placeholder="Tên phòng ban hoặc nhóm" maxLength={120} value={name} onChange={(e) => setName(e.target.value)}/>
        <select aria-label="Thuộc phòng ban" value={parent} onChange={(e) => setParent(e.target.value)}><option value="">— phòng ban cấp cao nhất —</option>{deps.map((d) => <option key={d.id} value={d.id}>Nhóm trong: {d.name}</option>)}</select>
        <button className="btn primary" disabled={!name.trim() || busy}>Thêm</button>
      </form>
    </Card>
    <Card title="Cơ cấu">{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : deps.length === 0 ? <StateView kind="empty" title="Chưa có phòng ban"/> :
      <ul className="plainList">{deps.map((d) => <Fragment key={d.id}>{row(d)}{teamsOf(d.id).length ? <ul className="plainList nested">{teamsOf(d.id).map(row)}</ul> : null}</Fragment>)}</ul>}</Card>
    <Card title="Gán người dùng hoặc workspace">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (who.id) void act(() => who.type === "USER" ? api.admin.assignUserDepartment(who.id, target || null) : api.admin.assignWorkspaceDepartment(who.id, target || null), "Đã gán."); }}>
        <ScopePicker types={["USER", "WORKSPACE"]} value={who} onChange={setWho}/>
        <select aria-label="Phòng ban / nhóm" value={target} onChange={(e) => setTarget(e.target.value)}><option value="">— bỏ gán —</option>{(data ?? []).map((d) => <option key={d.id} value={d.id}>{d.kind === "TEAM" ? "  · " : ""}{d.name}</option>)}</select>
        <button className="btn primary" disabled={!who.id}>Gán</button>
      </form>
    </Card>
  </>);
}

/** SSO / SAML / SCIM status and SCIM group → workspace role mappings (never system admin). */
export function IdentityPage() {
  const cfg = useLoad(() => api.authConfig(), []);
  const scim = useLoad(() => api.adminScim(), []);
  const [m, setM] = useState({ groupId: "", ws: { type: "WORKSPACE", id: "" }, role: "VIEWER" });
  const { act, busy, err } = useAdminAction("Không thực hiện được.", scim.reload);
  const s = scim.data;
  return (<>
    <PageHead title="Định danh" sub="Đăng nhập một lần (OIDC), SAML qua nhà cung cấp OIDC (identity brokering), cấp tài khoản tự động (SCIM 2.0). MFA do nhà cung cấp danh tính quản lý."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}
    <div className="kpiGrid">
      <Kpi label="OIDC (SSO)" value={<LoadGate load={cfg} compact label="trạng thái đăng nhập một lần">{(c) => (c.oidc ? "Bật" : "Tắt")}</LoadGate>} hint="OIDC_ENABLED · đăng xuất cũng kết thúc phiên ở IdP"/>
      <Kpi label="SAML" value={<LoadGate load={cfg} compact label="trạng thái SAML">{(c) => (c.saml ? "Bật (qua IdP broker)" : "Tắt")}</LoadGate>} hint="SAML_ENABLED + SAML_IDP_HINT"/>
      <Kpi label="SCIM 2.0" value={<LoadGate load={scim} compact label="trạng thái SCIM">{(s) => (s.enabled ? "Bật" : "Tắt")}</LoadGate>} hint={s ? `${s.users} tài khoản do SCIM cấp · SCIM_ENABLED + SCIM_TOKEN` : ""}/>
      <Kpi label="MFA" value="Do IdP quản lý" hint="MFA managed by Identity Provider"/>
    </div>
    <Card title="Nhóm SCIM → quyền workspace">
      <p className="hint">Nhóm từ IdP chỉ tạo quyền khi được ánh xạ ở đây; không có ánh xạ nào tới quản trị hệ thống. Thành viên do SCIM thêm sẽ được SCIM gỡ; thành viên thêm tay không bị đụng tới.</p>
      <LoadGate load={scim} label="nhóm SCIM">{(s) => <>
        <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (m.groupId && m.ws.id) void act(() => api.addScimMapping(m.groupId, m.ws.id, m.role)); }}>
          <select aria-label="Nhóm SCIM" value={m.groupId} onChange={(e) => setM({ ...m, groupId: e.target.value })}><option value="">— nhóm —</option>{s.groups.map((g) => <option key={g.id} value={g.id}>{g.displayName} ({g.members})</option>)}</select>
          <ScopePicker types={["WORKSPACE"]} value={m.ws} onChange={(v) => setM({ ...m, ws: v })}/>
          <select aria-label="Vai trò" value={m.role} onChange={(e) => setM({ ...m, role: e.target.value })}>{["VIEWER", "PUBLISHER", "EDITOR", "WORKSPACE_ADMIN"].map((r) => <option key={r} value={r}>{r}</option>)}</select>
          <button className="btn primary" disabled={!m.groupId || !m.ws.id || busy}>Ánh xạ</button>
        </form>
        {s.mappings.length === 0 ? <StateView kind="empty" title="Chưa có ánh xạ"/> : <table className="table"><thead><tr><th>Nhóm</th><th>Workspace</th><th>Vai trò</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{s.mappings.map((x) => <tr key={x.id}><td>{x.group}</td><td>{x.workspace}</td><td className="code">{x.role}</td><td><button className="btn sm ghost" onClick={() => void act(() => api.deleteScimMapping(x.id))}>Gỡ</button></td></tr>)}</tbody></table>}
      </>}</LoadGate>
    </Card>
  </>);
}

/** Approved HTTPS connectors for server apps: the credential is stored encrypted and added by the platform; apps never see it. */
export function ConnectorsPage() {
  const { data, error, reload } = useLoad(() => api.admin.connectors(), []);
  const [f, setF] = useState({ key: "", name: "", description: "", baseUrl: "https://", authHeader: "", authValue: "", ops: "GET /items" });
  const [grant, setGrant] = useState<{ key: string; app: { type: string; id: string } } | null>(null);
  const { act, busy, msg, err } = useAdminAction("Không lưu được.", reload);
  function edit(c: Connector) { setF({ key: c.key, name: c.name, description: c.description, baseUrl: c.baseUrl, authHeader: c.authHeader ?? "", authValue: "", ops: c.operations.map((o) => `${o.method} ${o.path}`).join("\n") }); }
  // one "METHOD /path" per line; a path with a space inside is flagged (it used to be glued together silently: "GET /a b" became "/ab")
  const operations = f.ops.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const [m, ...p] = l.split(/\s+/); return { method: (m ?? "").toUpperCase(), path: p.join(""), bad: p.length !== 1 }; });
  const badOps = operations.some((o) => o.bad);
  return (<>
    <PageHead title="Connector" sub="API HTTPS đã duyệt mà ứng dụng có máy chủ được gọi qua cổng runtime. Thông tin xác thực mã hóa, chỉ ghi; ứng dụng và AI không bao giờ thấy."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Thêm / sửa connector">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); void act(() => api.admin.saveConnector({ key: f.key.trim(), name: f.name.trim(), description: f.description.trim() || undefined, baseUrl: f.baseUrl.trim(),
        authHeader: f.authHeader.trim() || undefined, authValue: f.authValue || undefined, operations: operations.map(({ method, path }) => ({ method, path })) }), "Đã lưu connector.").then((r) => { if (r.status === "ok") setF((x) => ({ ...x, authValue: "" })); }); }}>
        <input aria-label="Mã" placeholder="ma-connector" value={f.key} onChange={(e) => setF({ ...f, key: e.target.value.toLowerCase() })}/>
        <input aria-label="Tên" placeholder="Tên" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })}/>
        <input aria-label="Base URL" placeholder="https://api.example.com/v1" value={f.baseUrl} onChange={(e) => setF({ ...f, baseUrl: e.target.value })}/>
        <input aria-label="Header xác thực" placeholder="Authorization" value={f.authHeader} onChange={(e) => setF({ ...f, authHeader: e.target.value })}/>
        <input aria-label="Giá trị xác thực" type="password" autoComplete="off" placeholder="Giá trị (để trống = giữ nguyên)" value={f.authValue} onChange={(e) => setF({ ...f, authValue: e.target.value })}/>
        <input aria-label="Mô tả" placeholder="Mô tả (AI đọc được)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })}/>
        <textarea aria-label="Thao tác cho phép" rows={3} placeholder={"GET /contacts\nPOST /contacts"} value={f.ops} onChange={(e) => setF({ ...f, ops: e.target.value })}/>
        {badOps ? <p className="formError" role="alert">Mỗi dòng gồm phương thức và một đường dẫn không có khoảng trắng, ví dụ “GET /contacts”.</p> : null}
        <button className="btn primary" disabled={!f.key || !f.name || !f.baseUrl || badOps || busy}>Lưu</button>
      </form>
    </Card>
    <Card title="Danh mục">{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : data.length === 0 ? <StateView kind="empty" title="Chưa có connector"/> :
      <table className="table"><thead><tr><th>Connector</th><th>Base URL</th><th>Thao tác</th><th>Xác thực</th><th>Ứng dụng</th><th>Trạng thái</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data.map((c) => <tr key={c.key}><td><b>{c.name}</b><small className="code">{c.key}</small></td><td className="code">{c.baseUrl}</td>
          <td>{c.operations.map((o) => <small key={o.method + o.path} className="code">{o.method} {o.path}</small>)}</td><td>{c.hasSecret ? `${c.authHeader ?? "—"}: ••••` : "không"}</td><td>{c.grants}</td>
          <td><Pill value={c.status === "APPROVED" ? "ACTIVE" : "DISABLED"} label={c.status === "APPROVED" ? "Đã duyệt" : "Tắt"}/></td>
          <td><div className="row"><button className="btn sm" onClick={() => edit(c)}>Sửa</button>
            <button className="btn sm ghost" onClick={async () => { if (c.status === "APPROVED" && !(await confirm({ title: `Tắt connector “${c.name}”?`, message: "Ứng dụng có máy chủ đang dùng connector này sẽ không gọi được API này cho tới khi bật lại.", confirmLabel: "Tắt connector", danger: true }))) return; void act(() => api.admin.connectorStatus(c.key, c.status === "APPROVED" ? "DISABLED" : "APPROVED")); }}>{c.status === "APPROVED" ? "Tắt" : "Bật"}</button>
            <button className="btn sm ghost" onClick={() => setGrant({ key: c.key, app: { type: "PROJECT", id: "" } })}>Cấp cho ứng dụng</button></div></td></tr>)}</tbody></table>}</Card>
    {grant ? <Card title={`Cấp “${grant.key}” cho ứng dụng có máy chủ`}>
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (grant.app.id) void act(() => api.admin.grantConnector(grant.key, grant.app.id), "Đã cấp.").then((r) => { if (r.status === "ok") setGrant(null); }); }}>
        <ScopePicker types={["PROJECT"]} value={grant.app} onChange={(v) => setGrant({ ...grant, app: v })}/><button className="btn primary" disabled={!grant.app.id}>Cấp</button>
        <button type="button" className="btn ghost" onClick={() => setGrant(null)}>Đóng</button></form></Card> : null}
  </>);
}

const BK_NAME: Record<string, string> = { postgres: "CSDL nền tảng (PostgreSQL)", appdb: "CSDL của ứng dụng có máy chủ", minio: "Tệp & artifact (MinIO)", forgejo: "Kho mã (Forgejo)", offsite: "Bản sao ngoài máy (S3)" };
/** Backup monitoring: last successful backup per component, the latest restore drill, problems (also raised as alerts). */
export function BackupsPage() {
  const { data, error, reload } = useLoad(() => api.admin.backups(), []);
  const size = (b: number | null) => (b == null ? "—" : b > 1048576 ? `${(b / 1048576).toFixed(1)} MiB` : `${Math.round(b / 1024)} KiB`);
  return (<>
    <PageHead title="Sao lưu" sub="Lịch: hằng ngày (scripts/backup-daemon.sh), diễn tập khôi phục hằng tuần từ chính file sao lưu vào máy chủ tạm. Cảnh báo khi bản sao lưu quá 26 giờ, lỗi hoặc diễn tập không đạt."/>
    {error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : data.length === 0 ? <StateView kind="empty" title="Chưa cấu hình thư mục sao lưu (BACKUP_STATUS_DIRS)"/> :
      data.map((e) => <Card key={e.environment} title={`Môi trường: ${e.environment}`} actions={<Pill value={e.healthy ? "ACTIVE" : "DISABLED"} label={e.healthy ? "Ổn" : "Có vấn đề"}/>}>
        {e.problems.length ? <ul className="plainList">{e.problems.map((p) => <li key={p} className="formError">{p}</li>)}</ul> : null}
        <table className="table"><thead><tr><th>Thành phần</th><th>Lần thành công gần nhất</th><th>Kích thước</th><th>Lần chạy gần nhất</th></tr></thead>
          <tbody>{e.components.map((c) => <tr key={c.name}><td>{BK_NAME[c.name] ?? c.name}</td>
            <td>{c.lastSuccess ? <>{ago(c.lastSuccess)} {c.stale ? <Pill value="WARNING" label="quá hạn"/> : null}</> : c.state === "SKIPPED" ? (c.name === "offsite" ? "CHƯA CẤU HÌNH — sao lưu chỉ nằm trên máy này" : "không triển khai ở đây") : "chưa có"}</td>
            <td>{size(c.sizeBytes)}</td><td>{c.state}{c.error ? <small className="formError">{c.error}</small> : null}</td></tr>)}</tbody></table>
        <h3 className="subHead">Diễn tập khôi phục {e.drillAt ? `· ${ago(e.drillAt)}` : ""}</h3>
        {e.drill.length === 0 ? <p className="hint">Chưa diễn tập.</p> : <ul className="plainList">{e.drill.map((d) => <li key={d.component}><Pill value={d.result === "PASS" || d.result === "SKIPPED" ? d.result : "FAILED"} label={d.result}/> {BK_NAME[d.component] ?? d.component}: {d.detail}</li>)}</ul>}
      </Card>)}
  </>);
}
