"use client";
import { LoadGate } from "@xweb/ui";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { api } from "@/lib/http-api";
import type { CostLine, SecurityFinding, AdminAlert } from "@/lib/http-types";
import { useA } from "../console/context";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, errText, fmtDate, Kpi, num, Pill, StateView, usd } from "../../ui";
import { PageHead } from "../PageHead";
import { useAdminAction } from "../useAdminAction";

const ALERT_KIND: Record<string, string> = { AI_BUDGET_SOFT: "Ngân sách AI sắp hết", AI_BUDGET_EXCEEDED: "Vượt ngân sách AI", AI_PROVIDER_FAILURE: "Nhà cung cấp AI từ chối" };
/** Admin alerts: budget thresholds, provider failures. One open alert per condition and period. */
export function AlertsPage() {
  const [all, setAll] = useState(false);
  const { data, error, reload } = useLoad(() => api.admin.alerts(all), [all]);
  const { act, err } = useAdminAction("Không xác nhận được.", reload);
  function ack(a: AdminAlert) { void act(() => api.admin.ackAlert(a.id)); }
  return (<>
    <PageHead title="Cảnh báo" sub="Sinh tự động khi ngân sách chạm ngưỡng hoặc nhà cung cấp AI từ chối khoá / hết tín dụng." actions={<label className="switch"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)}/> Hiện cả đã xử lý</label>}/>
    {err ? <p className="formError" role="alert">{err}</p> : null}
    <Card title={data ? `${num(data.open)} cảnh báo đang mở` : "Cảnh báo"}>{error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : !data.items.length ? <StateView kind="empty" title="Không có cảnh báo"/> :
      <table className="table"><thead><tr><th>Mức</th><th>Loại</th><th>Nội dung</th><th>Thời gian</th><th>Xử lý</th></tr></thead>
        <tbody>{data.items.map((a) => <tr key={a.id} className={`alertRow ${a.severity}`}><td><Pill value={a.severity === "CRITICAL" ? "DISABLED" : a.severity === "WARNING" ? "UNKNOWN" : "ACTIVE"} label={a.severity}/></td>
          <td>{ALERT_KIND[a.kind] ?? a.kind}</td><td>{a.message}</td><td>{ago(a.createdAt)}</td>
          <td>{a.acknowledgedAt ? <small>{a.acknowledgedBy ?? "—"} · {ago(a.acknowledgedAt)}</small> : <button className="btn sm" onClick={() => void ack(a)}>Đã xử lý</button>}</td></tr>)}</tbody></table>}</Card>
  </>);
}

// ---------------------------------------------------------------- Stage H: security findings, hosting cost, departments

const SEV_TONE: Record<string, string> = { CRITICAL: "DISABLED", HIGH: "DISABLED", MEDIUM: "UNKNOWN", LOW: "QUEUED", INFO: "ACTIVE" };
const SOURCE_LABEL: Record<string, string> = { DEPENDENCY: "Phụ thuộc (OSV)", SECRET: "Quét bí mật", PACKAGE: "Danh mục package", CONFIG: "Cấu hình" };
export function SecurityPage() {
  const A = useA();
  const { data, error, reload } = useLoad(() => api.admin.securityFindings(), []);
  const [sev, setSev] = useState("");
  const rows: SecurityFinding[] = (data?.findings ?? []).filter((f) => !sev || f.severity === sev);
  return (<>
    <PageHead title="Phát hiện bảo mật" sub="Từ dữ liệu thật: quét phụ thuộc và bí mật của bản build gần nhất mỗi ứng dụng mã nguồn, rủi ro package đã chấp nhận, cấu hình nguy cơ. Không có điểm bảo mật tổng hợp."/>
    {error ? <ErrorState error={error} retry={reload}/> : !data ? <StateView kind="loading"/> : <>
      <div className="kpiGrid">{["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((k) => <button key={k} className={`kpi kpiButton${sev === k ? " active" : ""}`} onClick={() => setSev(sev === k ? "" : k)} aria-pressed={sev === k}>
        <div className="kpiLabel">{k}</div><div className="kpiValue">{num(data.counts[k] ?? 0)}</div></button>)}</div>
      <Card title={sev ? `Mức ${sev}` : "Tất cả phát hiện"}>{rows.length === 0 ? <StateView kind="empty" title="Không có phát hiện"/> :
        <table className="table"><thead><tr><th>Mức</th><th>Nguồn</th><th>Phát hiện</th><th>Đối tượng</th><th>Thời điểm</th></tr></thead>
          <tbody>{rows.map((f, i) => <tr key={i}><td><Pill value={SEV_TONE[f.severity] ?? "UNKNOWN"} label={f.severity}/></td><td>{SOURCE_LABEL[f.source] ?? f.source}</td>
            <td><b>{f.title}</b><small>{f.detail}</small></td>
            <td>{f.resourceType === "PROJECT" && f.resourceId ? <Link href={A(`/applications/${f.resourceId}`)}>{f.resourceName ?? f.resourceId}</Link> : <span className="code">{f.resourceName ?? f.resourceId ?? "—"}</span>}</td>
            <td>{f.detectedAt ? ago(f.detectedAt) : "hiện tại"}</td></tr>)}</tbody></table>}</Card>
      <p className="hint">{data.note}</p></>}
  </>);
}

const COST_ITEM: Record<string, string> = { STORAGE_GIB_MONTH: "Lưu trữ (GiB · tháng)", BUILD_CPU_HOUR: "CPU build (giờ)", BUILD_MINUTE: "Thời gian build (phút)", EGRESS_GIB: "Băng thông ra (GiB, chưa đo)" };
const gib = (b: number | null | undefined) => { const n = Number(b); return Number.isFinite(n) ? `${(n / 1073741824).toFixed(n >= 1073741824 ? 1 : 3)} GiB` : "—"; };
const hours = (ms: number | null | undefined) => (Number.isFinite(Number(ms)) ? `${(Number(ms) / 3600000).toFixed(2)} h` : "—");
const money = (v: number | null) => (v == null ? "chưa có giá" : usd(v));
export function CostTable({ rows, first }: { rows: CostLine[]; first: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có số liệu"/>;
  return <table className="table"><thead><tr><th>{first}</th><th>Lưu trữ</th><th>CPU build</th><th>Thời gian build</th><th>AI</th><th>Tổng đã biết</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ?? r.key}{r.complete ? null : <small>chưa đủ giá → tổng chưa đầy đủ</small>}</td>
      <td>{gib(r.storageBytes)}<small>{money(r.storageUsd)}</small></td><td>{hours(r.buildCpuMs)}<small>{money(r.cpuUsd)}</small></td>
      <td>{(r.buildMs / 60000).toFixed(1)} phút<small>{money(r.buildUsd)}</small></td><td>{usd(r.aiUsd)}{r.aiUnknownCalls ? <small>{r.aiUnknownCalls} lượt chưa rõ chi phí</small> : null}</td>
      <td><b>{usd(r.totalKnownUsd)}</b></td></tr>)}</tbody></table>;
}
export function CostsPage() {
  const [days, setDays] = useState(30);
  const { data, error, reload } = useLoad(() => api.admin.costs(days), [days]);
  const [f, setF] = useState({ item: "STORAGE_GIB_MONTH", price: "", currency: "USD", rate: "", note: "" });
  const { act, busy, err } = useAdminAction("Không lưu được.", reload);
  // prices are immutable rows: one click = one row (single-flight)
  function add(e: FormEvent) {
    e.preventDefault();
    const row = { item: f.item, unitPrice: Number(f.price), currency: f.currency.toUpperCase(), usdPerUnit: f.currency.toUpperCase() === "USD" ? undefined : Number(f.rate), note: f.note || undefined };
    void act(() => api.admin.addCostPrice(row)).then((r) => { if (r.status === "ok") setF((x) => ({ ...x, price: "", note: "" })); });
  }
  return (<>
    <PageHead title="Chi phí hosting" sub="Số đo thật × đơn giá do quản trị viên nhập. Thiếu đơn giá thì hiện “chưa có giá”, không ước đoán. Băng thông ra chưa được đo."
      actions={<select aria-label="Khoảng thời gian" value={days} onChange={(e) => setDays(Number(e.target.value))}><option value={7}>7 ngày</option><option value={30}>30 ngày</option><option value={90}>90 ngày</option></select>}/>
    <Card title="Đơn giá">
      <form className="filters wrap" onSubmit={(e) => void add(e)}>
        <select aria-label="Hạng mục" value={f.item} onChange={(e) => setF({ ...f, item: e.target.value })}>{Object.entries(COST_ITEM).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        <input aria-label="Đơn giá" type="number" min="0" step="any" placeholder="Đơn giá" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })}/>
        <input aria-label="Tiền tệ" maxLength={3} value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })} style={{ width: 70 }}/>
        {f.currency.toUpperCase() !== "USD" ? <input aria-label="USD cho 1 đơn vị tiền" type="number" step="any" placeholder="USD / 1 đơn vị" value={f.rate} onChange={(e) => setF({ ...f, rate: e.target.value })}/> : null}
        <input aria-label="Ghi chú" placeholder="Nguồn giá (hợp đồng, bảng giá…)" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
        <button className="btn primary" disabled={!f.price || busy}>Thêm đơn giá</button>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      {data ? <ul className="plainList">{data.prices.map((p) => <li key={p.id}>{COST_ITEM[p.item] ?? p.item}: {p.unitPrice} {p.currency}{p.currency !== "USD" ? ` (×${p.usdPerUnit} USD)` : ""} · từ {fmtDate(p.effectiveFrom)}{p.note ? ` · ${p.note}` : ""}</li>)}
        {data.missingPrices.map((m) => <li key={m} className="hint">{COST_ITEM[m]}: chưa có đơn giá</li>)}</ul> : null}
    </Card>
    <LoadGate load={{ data, error, loading: !data && !error, reload }} label="chi phí" isEmpty={(d) => !d.total || typeof d.total !== "object"} empty={{ title: "Chưa có số liệu chi phí", detail: <p>Số liệu xuất hiện khi có build, lưu trữ hoặc lượt AI trong khoảng thời gian này.</p> }}>{(data) => <>
      <div className="kpiGrid"><Kpi label={`Tổng đã biết (${data.days} ngày)`} value={usd(data.total.totalKnownUsd)} hint={data.total.complete ? "đủ đơn giá" : "chưa đầy đủ: thiếu đơn giá hoặc AI chưa rõ chi phí"}/>
        <Kpi label="Lưu trữ hiện tại" value={gib(data.total.storageBytes)}/><Kpi label="CPU build" value={hours(data.total.buildCpuMs)}/><Kpi label="AI" value={usd(data.total.aiUsd)}/></div>
      <Card title="Theo phòng ban"><CostTable rows={data.byDepartment} first="Phòng ban"/></Card>
      <Card title="Theo workspace"><CostTable rows={data.byWorkspace} first="Workspace"/></Card>
      <Card title="Theo ứng dụng"><CostTable rows={data.byApplication} first="Ứng dụng"/></Card>
      <p className="hint">{data.egress}</p></>}</LoadGate>
  </>);
}
