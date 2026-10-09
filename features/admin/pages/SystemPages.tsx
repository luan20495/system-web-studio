"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/http-api";
import type { PackageView, RepoRow, SettingView, HealthItem } from "@/lib/http-types";
import { confirm, prompt, LoadGate } from "@xweb/ui";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, fmtDate, Kpi, num, Pill, StateView } from "../../ui";
import { PageHead } from "../PageHead";
import { useAdminAction } from "../useAdminAction";

// ------------------------------------------------------------------ health
const HEALTH_LABEL: Record<HealthItem["status"], string> = { HEALTHY: "Khỏe", DEGRADED: "Suy giảm", UNAVAILABLE: "Không khả dụng", UNKNOWN: "Không rõ", NOT_CONFIGURED: "Chưa cấu hình" };
export function HealthPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.health(), []);
  if (!data) return <LoadGate load={{ data, error, loading, reload }} level={1} label="tình trạng hệ thống">{() => null}</LoadGate>;
  const h = data!;
  return (<>
    <PageHead title="Sức khỏe hệ thống" sub={`Kiểm tra trực tiếp lúc ${fmtDate(h.checkedAt)}`} actions={<button className="btn" onClick={reload} disabled={loading}>{loading ? "Đang kiểm tra…" : "Kiểm tra lại"}</button>}/>
    {h.items.length === 0 ? <StateView kind="empty" title="Chưa có thành phần nào được kiểm tra" detail={<p>Máy chủ chưa báo thành phần nào. Bấm “Kiểm tra lại” để thử lần nữa.</p>}/> : null}
    <div className="healthGrid">{h.items.map((i) => <div key={i.name} className={`healthCard h-${i.status}`}><div className="row between"><b>{i.name}</b><Pill value={i.status} label={HEALTH_LABEL[i.status]}/></div><small>{i.detail ?? ""}</small>{i.latencyMs !== null ? <small className="muted">{i.latencyMs} ms</small> : null}</div>)}</div>
    <div className="kpiGrid">
      <Kpi label="Uptime API" value={`${Math.floor(h.uptimeSeconds / 3600)} giờ ${Math.floor((h.uptimeSeconds % 3600) / 60)} phút`}/><Kpi label="Phiên bản schema DB" value={`V${h.schemaVersion ?? "?"}`}/>
      <Kpi label="Hàng đợi xuất bản" value={h.publishQueueDepth ?? "—"} hint={`Dead-letter: ${h.deadLetterDepth ?? "—"}`}/><Kpi label="Runtime" value={`Java ${h.javaVersion}`} hint={h.profiles.join(", ") || "default"}/>
    </div>
  </>);
}

// ------------------------------------------------------------------ settings (read-only)
const SETTING_GROUP: Record<string, string> = { authentication: "Xác thực", limits: "Giới hạn", ai: "AI", retention: "Lưu trữ & dọn dẹp", deployment: "Triển khai", network: "Mạng" };
const mib = (b?: number | null) => (b == null ? "—" : b < 1048576 ? `${(b / 1024).toFixed(0)} KiB` : `${(b / 1048576).toFixed(1)} MiB`);
const secs = (ms: number) => (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + " s";

export function UsageRows({ rows, label }: { rows: { key: string; label: string | null; builds: number; succeeded: number; failed: number; cpuMs: number; durationMs: number; artifactBytes: number }[]; label: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có build"/>;
  return <table className="table"><thead><tr><th>{label}</th><th>Build</th><th>Thành công</th><th>Lỗi</th><th>CPU</th><th>Thời gian</th><th>Kết quả</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ?? <span className="code">{r.key.slice(0, 8)}</span>}</td><td>{num(r.builds)}</td><td>{num(r.succeeded)}</td><td>{num(r.failed)}</td>
      <td>{secs(r.cpuMs)}</td><td>{secs(r.durationMs)}</td><td>{mib(r.artifactBytes)}</td></tr>)}</tbody></table>;
}

/** Build quotas and storage: measured usage (runner cgroup CPU, wall clock, artifact bytes), refusals, retention preview, repository lifecycle. */
export function BuildsPage() {
  const rep = useLoad(() => api.admin.builds(), []);
  const preview = useLoad(() => api.admin.retentionPreview(), []);
  const repos = useLoad(() => api.admin.repositories(), []);
  const { act, busy, msg, err } = useAdminAction("Không thực hiện được.", () => { preview.reload(); rep.reload(); repos.reload(); });
  async function runCleanup() { if (!(await confirm({ title: "Chạy dọn dẹp ngay?", message: `Sẽ xóa ${num(preview.data?.retention?.artifactsDeleted ?? 0)} artifact (${mib(preview.data?.retention?.artifactBytesFreed)}) và dữ liệu hết hạn lưu giữ. Việc này không hòan tác được.`, confirmLabel: "Chạy dọn dẹp", danger: true }))) return; void act(() => api.admin.retentionRun(), (r) => `Đã dọn: ${r.retention?.artifactsDeleted ?? 0} artifact (${mib(r.retention?.artifactBytesFreed)}), ${r.retention?.previewsExpired ?? 0} bản xem trước hết hạn.`); }
  async function hardDelete(r: RepoRow) { if (!(await confirm({ title: `Xóa vĩnh viễn kho mã “${r.name}”?`, message: "Không thể hòan tác.", confirmLabel: "Xóa vĩnh viễn", danger: true }))) return; void act(() => api.admin.deleteRepository(r.projectId)); }
  return (<>
    <PageHead title="Build & lưu trữ" sub="Số liệu đo thật từ runner (CPU của container, thời gian, kích thước kết quả). Giới hạn chỉnh trong Cài đặt → Build / Lưu trữ / Lưu giữ."/>
    <LoadGate load={rep} label="số liệu build">{(d) => <>
      <div className="kpiGrid">
        <Kpi label="Build 30 ngày" value={num(d.totals.builds)} hint={`${num(d.totals.succeeded)} thành công · ${num(d.totals.failed)} lỗi`}/>
        <Kpi label="CPU đã dùng" value={secs(d.totals.cpuMs)} hint={`thời gian chạy ${secs(d.totals.durationMs)}`}/>
        <Kpi label="Đang chạy / chờ" value={`${num(d.running)} / ${num(d.queued)}`}/>
        <Kpi label="Lưu trữ" value={mib(d.storage.artifactsBytes)} hint={`${num(d.storage.artifactsCount)} artifact · repo ${mib(d.storage.repositoriesBytes)} · tệp ${mib(d.storage.assetsBytes)}`}/>
      </div>
      <div className="grid2"><Card title="Theo workspace"><UsageRows rows={d.byWorkspace} label="Workspace"/></Card><Card title="Theo người dùng"><UsageRows rows={d.byUser} label="Người dùng"/></Card></div>
      <Card title="Theo ứng dụng"><UsageRows rows={d.byProject} label="Ứng dụng"/></Card>
      <Card title="Build bị từ chối">{d.rejections.length ? <table className="table"><thead><tr><th>Thời gian</th><th>Người</th><th>Ứng dụng</th><th>Lý do</th><th>Chi tiết</th></tr></thead>
        <tbody>{d.rejections.map((r, i) => <tr key={i}><td>{ago(r.createdAt)}</td><td>{r.user ?? "—"}</td><td>{r.project ?? "—"}</td><td className="code">{r.reason}</td><td>{r.detail}</td></tr>)}</tbody></table> : <StateView kind="empty" title="Không có build nào bị từ chối"/>}</Card>
    </>}</LoadGate>
    <Card title="Dọn dẹp (lưu giữ)" actions={<button className="btn sm primary" disabled={busy} onClick={() => void runCleanup()}>{busy ? "Đang dọn…" : "Chạy dọn dẹp ngay"}</button>}>
      <p className="hint">Luôn giữ: bản đang phục vụ, N bản xuất bản gần nhất để quay lại, bản xem trước còn hạn, build đang chạy. Job tự chạy mỗi giờ; mỗi lần xóa đều ghi audit.</p>
      <LoadGate load={preview} compact label="bản xem trước dọn dẹp">{(p) => <ul className="plainList"><li>Sẽ xóa {num(p.retention?.artifactsDeleted ?? 0)} artifact ({mib(p.retention?.artifactBytesFreed)})</li><li>{num(p.retention?.previewsExpired ?? 0)} bản xem trước đã hết hạn</li>
        <li>{num(p.retention?.failedBuildLogsCleared ?? 0)} log build lỗi cũ</li><li>{num(p.retention?.repositoriesPendingDelete ?? 0)} kho mã hết hạn lưu trữ</li></ul>}</LoadGate>
      {msg ? <p className="hint" role="status">{msg}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
    <Card title="Kho mã nguồn"><LoadGate load={repos} compact label="kho mã" isEmpty={(r) => r.length === 0} empty={{ title: "Chưa có kho mã" }}>{(rows) =>
      <table className="table"><thead><tr><th>Kho</th><th>Ứng dụng</th><th>Trạng thái</th><th>Kích thước</th><th>Lưu trữ đến</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{rows.map((r) => <tr key={r.projectId}><td className="code">{r.name}</td><td>{r.project ?? "—"}</td>
          <td><Pill value={r.state} label={{ ACTIVE: "Đang dùng", ARCHIVED: "Đã lưu trữ", PENDING_DELETE: "Chờ xóa", DELETED: "Đã xóa" }[r.state]}/></td>
          <td>{mib(r.sizeBytes)}</td><td>{r.deleteAfter ? fmtDate(r.deleteAfter) : "—"}</td>
          <td>{r.state === "PENDING_DELETE" ? <button className="btn sm danger" onClick={() => void hardDelete(r)}>Xóa vĩnh viễn</button> : null}</td></tr>)}</tbody></table>}</LoadGate></Card>
  </>);
}

const PKG_STATUS: Record<string, [string, string]> = { PENDING: ["AWAITING_REVIEW", "Chờ duyệt"], RESOLVING: ["QUEUED", "Đang kiểm tra"], ALLOWED: ["APPROVED", "Cho phép"], DENIED: ["REJECTED", "Từ chối"] };
/** Approved npm package catalog (ADR 0013): approve → closure resolved in the sandbox + OSV scan; HIGH/CRITICAL denied unless the risk is accepted. */
export function PackagesPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.packages(), []);
  const [f, setF] = useState({ name: "", range: "", pin: "", note: "" });
  const { act, busy, msg, err } = useAdminAction("Không thực hiện được.", reload);
  useEffect(() => { if (!data?.some((p) => p.status === "RESOLVING")) return; const t = setInterval(reload, 3000); return () => clearInterval(t); }, [data, reload]);
  function approve(name: string, range?: string, pin?: string, note?: string) {
    void act(() => api.admin.approvePackage({ name, versionRange: range || undefined, pinnedVersion: pin || undefined, note: note || undefined }), `Đang kiểm tra ${name} (giải phụ thuộc trong sandbox + quét OSV).`)
      .then((r) => { if (r.status === "ok") setF({ name: "", range: "", pin: "", note: "" }); });
  }
  async function decide(p: PackageView, status: "ALLOWED" | "DENIED") {
    const risky = (p.findings ?? []).some((x) => x.severity === "HIGH" || x.severity === "CRITICAL");
    let accept = false, note: string | undefined;
    if (status === "DENIED" && !(await confirm({ title: `Từ chối package “${p.name}”?`, message: "Package không còn nằm trong danh mục được phép: ứng dụng mã nguồn không thêm được nó.", confirmLabel: "Từ chối package", danger: true }))) return;
    if (status === "ALLOWED" && risky) { note = (await prompt({ title: `Cho phép “${p.name}” dù có lỗ hổng nghiêm trọng?`, message: "Package có lỗ hổng mức cao hoặc nghiêm trọng. Việc chấp nhận rủi ro được ghi lại cùng lý do.", label: "Lý do chấp nhận rủi ro (bắt buộc)", multiline: true, required: true, maxLength: 500, confirmLabel: "Chấp nhận rủi ro" })) ?? ""; if (!note.trim()) return; accept = true; }
    void act(() => api.admin.decidePackage(p.name, status, accept, note));
  }
  return (<>
    <PageHead title="Packages" sub="Chỉ package trong danh mục mới vào được mirror và lockfile của ứng dụng mã nguồn. Duyệt = giải cây phụ thuộc (không chạy mã package) + quét lỗ hổng OSV."/>
    <Card title="Duyệt package">
      <form className="filters wrap" onSubmit={(e) => { e.preventDefault(); if (f.name.trim()) void approve(f.name.trim(), f.range.trim(), f.pin.trim(), f.note.trim()); }}>
        <input aria-label="Tên package" placeholder="Tên (ví dụ date-fns)" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })}/>
        <input aria-label="Khoảng phiên bản" placeholder="Khoảng phiên bản (^3.0.0)" value={f.range} onChange={(e) => setF({ ...f, range: e.target.value })}/>
        <input aria-label="Ghim phiên bản" placeholder="Hoặc ghim (3.6.0)" value={f.pin} onChange={(e) => setF({ ...f, pin: e.target.value })}/>
        <input aria-label="Ghi chú" placeholder="Ghi chú" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
        <button className="btn primary" disabled={!f.name.trim() || busy}>Kiểm tra & duyệt</button>
      </form>
      {msg ? <p className="hint" role="status">{msg}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
    <Card title="Danh mục">{error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.length ? <StateView kind="empty" title="Chưa có package nào ngoài khung mẫu"/> :
      <table className="table"><thead><tr><th>Package</th><th>Phiên bản</th><th>Trạng thái</th><th>Phụ thuộc</th><th>Lỗ hổng</th><th>Người yêu cầu / quyết định</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.map((p) => { const sev = (p.findings ?? []).reduce<Record<string, number>>((a, x) => { a[x.severity] = (a[x.severity] ?? 0) + 1; return a; }, {});
          return <tr key={p.name}><td className="code">{p.name}{p.note ? <small>{p.note}</small> : null}</td><td>{p.pinnedVersion ?? p.versionRange}</td>
            <td><Pill value={PKG_STATUS[p.status][0]} label={PKG_STATUS[p.status][1]}/>{p.riskAccepted ? <small>rủi ro đã chấp nhận</small> : null}</td>
            <td>{p.dependencies ?? "—"}</td><td>{p.findings == null ? "—" : Object.keys(sev).length ? Object.entries(sev).map(([k, v]) => `${v} ${k}`).join(", ") : "0"}</td>
            <td>{p.requestedBy ?? "—"}<small>{p.decidedBy ? `${p.decidedBy} · ${p.decidedAt ? ago(p.decidedAt) : ""}` : ""}</small></td>
            <td><div className="row">{p.status === "PENDING" ? <button className="btn sm primary" onClick={() => void approve(p.name)}>Kiểm tra & duyệt</button> : null}
              {p.status === "DENIED" && p.dependencies != null ? <button className="btn sm" onClick={() => void decide(p, "ALLOWED")}>Cho phép</button> : null}
              {p.status === "ALLOWED" ? <button className="btn sm ghost" onClick={() => void decide(p, "DENIED")}>Từ chối</button> : null}</div></td></tr>; })}</tbody></table>}</Card>
  </>);
}

/** Editable policies (audited; high-risk ones ask for confirmation) above the read-only effective configuration. */
export function SettingsPage() {
  const { data, error, loading, reload } = useLoad(() => api.admin.settings(), []);
  const pol = useLoad(() => api.admin.policies(), []);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const { act, busy, msg: ok, err } = useAdminAction("Không lưu được.", pol.reload);
  async function save(s: SettingView, value: string) {
    if (s.risk === "HIGH" && !(await confirm({ title: `Đổi “${s.label}”?`, message: `Đây là cài đặt rủi ro cao. Giá trị mới: ${value}.`, confirmLabel: "Đổi cài đặt", danger: true }))) return;
    void act(() => api.admin.setPolicy(s.key, value, s.risk === "HIGH"), `Đã lưu: ${s.label}`).then((r) => { if (r.status === "ok") setDraft((d) => { const n = { ...d }; delete n[s.key]; return n; }); });
  }
  function reset(s: SettingView) { void act(() => api.admin.resetPolicy(s.key)); }
  const groups = (pol.data ?? []).reduce<Record<string, SettingView[]>>((acc, s) => { (acc[s.group] ??= []).push(s); return acc; }, {});
  return (<>
    <PageHead title="Cài đặt" sub="Chính sách chỉnh được (ghi audit; mục rủi ro cao cần xác nhận). Giá trị mặc định lấy từ cấu hình máy chủ."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{ok ? <p className="hint" role="status">{ok}</p> : null}
    {pol.error ? <ErrorState error={pol.error} retry={pol.reload}/> : !pol.data ? <StateView kind="loading"/> : pol.data.length === 0 ? <StateView kind="empty" title="Chưa có chính sách nào chỉnh được"/> : <div className="grid2">{Object.entries(groups).map(([g, items]) =>
      <Card key={g} title={g}><table className="table settingsTable"><tbody>{items.map((s) => {
        const v = draft[s.key] ?? s.value;
        return <tr key={s.key}><td><b>{s.label}</b>{s.risk === "HIGH" ? <Pill value="HIGH_RISK" label="Rủi ro cao"/> : null}<small className="code">{s.key}</small>
          <small>{s.overridden ? `Đã đổi bởi ${s.updatedBy ?? "—"} ${s.updatedAt ? ago(s.updatedAt) : ""} · mặc định ${s.defaultValue}` : "Mặc định từ cấu hình"}</small></td>
          <td className="settingCtl">{s.type === "BOOL"
            ? <label className="switch"><input type="checkbox" checked={s.value === "true"} aria-label={s.label} onChange={(e) => void save(s, String(e.target.checked))}/> {s.value === "true" ? "Bật" : "Tắt"}</label>
            : <form className="row" onSubmit={(e) => { e.preventDefault(); void save(s, v); }}>{s.type === "DOMAINS" ? <input aria-label={s.label} type="text" placeholder="example.com, docs.example.org" value={v} onChange={(e) => setDraft((d) => ({ ...d, [s.key]: e.target.value }))}/>
              : <input aria-label={s.label} type="number" min={s.min} max={s.max} value={v} onChange={(e) => setDraft((d) => ({ ...d, [s.key]: e.target.value }))}/>}
              <span className="hint">{s.unit}</span>{draft[s.key] !== undefined && draft[s.key] !== s.value ? <button className="btn sm primary" disabled={busy}>Lưu</button> : null}</form>}
            {s.overridden ? <button className="btn sm ghost" onClick={() => void reset(s)}>Mặc định</button> : null}</td></tr>;
      })}</tbody></table></Card>)}</div>}
    <h2 className="subHead">Cấu hình đang hiệu lực (chỉ xem)</h2>
    {loading && !data ? <StateView kind="loading"/> : error ? <ErrorState error={error} retry={reload}/> : Object.keys(data!).length === 0 ? <StateView kind="empty" title="Chưa có cấu hình hiệu lực để hiển thị"/> :
      <div className="grid2">{Object.entries(data!).map(([group, values]) => (
        <Card key={group} title={SETTING_GROUP[group] ?? group}><dl className="kv">{Object.entries(values).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v === null || v === undefined ? "—" : String(v)}</dd></div>)}</dl></Card>
      ))}</div>}
  </>);
}
