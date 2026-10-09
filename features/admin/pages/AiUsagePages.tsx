"use client";
import { useAdminAction } from "../useAdminAction";
import { LoadGate } from "@xweb/ui";

import { useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import { api } from "@/lib/http-api";
import type { AiProviderInfo, AiUsageReport, UsageBucket, UsageTotals } from "@/lib/http-types";
import { useA } from "../console/context";
import { LoadNote } from "../LoadNote";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, errText, fmtDate, Kpi, num, Pager, Pill, StateView, tok, usd } from "../../ui";

// ------------------------------------------------------------------ AI control
const OUTCOME_LABEL: Record<string, string> = { OK: "Thành công", BAD_OUTPUT: "Trả lời không dùng được", ERROR: "Lỗi (không có trả lời)" };

/** How complete a provider-reported total is: calls with no reported usage are counted, never estimated. */
export function coverage(t: UsageTotals) {
  if (!t.calls) return "Chưa có lượt gọi mô hình nào";
  return `${num(t.calls - t.callsWithoutUsage)}/${num(t.calls)} lượt gọi có số token do nhà cung cấp báo`;
}

export function UsageTable({ rows, keyLabel }: { rows: UsageBucket[]; keyLabel: string }) {
  if (!rows.length) return <StateView kind="empty" title="Chưa có dữ liệu" detail="Chưa có lượt gọi mô hình thật nào trong khoảng thời gian này."/>;
  return <table className="table"><thead><tr><th>{keyLabel}</th><th>Lượt gọi</th><th>Lỗi</th><th>Token vào / ra</th><th>Tổng token</th><th>Chi phí</th></tr></thead>
    <tbody>{rows.map((r) => <tr key={r.key}><td>{r.label ? <><b>{r.label}</b>{r.label !== r.key ? <small className="code">{r.key}</small> : null}</> : <span className="code">{r.key}</span>}</td>
      <td>{num(r.totals.calls)}</td><td>{num(r.totals.failedCalls)}</td><td>{num(r.totals.promptTokens)} / {num(r.totals.completionTokens)}</td>
      <td><b>{num(r.totals.totalTokens)}</b>{r.totals.callsWithoutUsage ? <small>{num(r.totals.callsWithoutUsage)} lượt không có số liệu</small> : null}</td>
      <td>{usd(r.totals.costUsd)}</td></tr>)}</tbody></table>;
}

export function DailyBars({ daily }: { daily: AiUsageReport["daily"] }) {
  const max = Math.max(1, ...daily.map((d) => d.totalTokens));
  const day = (iso: string) => { const d = new Date(`${iso}T00:00:00`); return Number.isNaN(d.getTime()) ? iso : new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit" }).format(d); };   // an empty series has no first day: an Invalid Date must not throw
  return <figure className="barsFig">
    <div className="bars" role="img" aria-label={`Token theo ngày từ ${day(daily[0]?.day ?? "")} đến hôm nay, cao nhất ${num(max)} token`}>
      {daily.map((d) => <div key={d.day} className="bar" title={`${day(d.day)}: ${num(d.totalTokens)} token, ${num(d.calls)} lượt gọi${d.failedCalls ? `, ${num(d.failedCalls)} lỗi` : ""}`}>
        <span style={{ height: `${Math.round((d.totalTokens / max) * 100)}%` }}/></div>)}
    </div>
    <figcaption className="barsAxis"><span>{daily.length ? day(daily[0].day) : ""}</span><span>Token mỗi ngày · cao nhất {num(max === 1 && !daily.some((d) => d.totalTokens) ? 0 : max)}</span><span>Hôm nay</span></figcaption>
  </figure>;
}

export function AiCallsLog({ models }: { models: string[] }) {
  const A = useA();
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState(""); const [model, setModel] = useState("");
  const params = useMemo(() => ({ page, outcome: outcome || undefined, model: model || undefined }), [page, outcome, model]);
  const { data, error, loading, reload } = useLoad(() => api.admin.aiCalls(params), [params]);
  return <Card title="Nhật ký lượt gọi mô hình">
    <div className="filters">
      <select aria-label="Kết quả" value={outcome} onChange={(e) => { setPage(0); setOutcome(e.target.value); }}><option value="">Mọi kết quả</option>{Object.entries(OUTCOME_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <select aria-label="Mô hình" value={model} onChange={(e) => { setPage(0); setModel(e.target.value); }}><option value="">Mọi mô hình</option>{models.map((m) => <option key={m} value={m}>{m}</option>)}</select>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.total ? <StateView kind="empty" title="Chưa có lượt gọi" detail="Bộ mô phỏng không gọi mô hình nên không có dòng nào ở đây."/> : <>
      <table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Ứng dụng</th><th>Model</th><th>Kết quả</th><th>Token vào / ra</th><th>Chi phí</th><th>Thời gian chạy</th></tr></thead>
        <tbody>{data!.items.map((c) => <tr key={c.id}><td>{ago(c.createdAt)}<small>{fmtDate(c.createdAt)}</small></td>
          <td><Link href={A(`/users/${c.userId}`)}>{c.user ?? c.userId.slice(0, 8)}</Link></td>
          <td><Link href={A(`/applications/${c.projectId}`)}>{c.project ?? "—"}</Link><small>{c.workspace}</small></td>
          <td className="code">{c.model}</td><td><Pill value={c.outcome} label={OUTCOME_LABEL[c.outcome]}/>{c.httpStatus && c.httpStatus !== 200 ? <small>HTTP {c.httpStatus}</small> : null}</td>
          <td>{c.totalTokens == null ? <span className="muted">không báo</span> : <>{tok(c.promptTokens)} / {tok(c.completionTokens)}</>}</td>
          <td>{usd(c.costUsd)}{c.costSource ? <small>{c.costSource === "CATALOG" ? "theo bảng giá" : "nhà cung cấp báo"}</small> : null}</td><td>{(c.latencyMs / 1000).toFixed(1)} s{c.requestId ? <small className="code">{c.requestId.slice(0, 8)}</small> : null}</td></tr>)}</tbody></table>
      <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>}
  </Card>;
}

/** Configured providers and per-model switches. "Configured" = key + model list present; reachability only after a live check. */
/** Overview: this month's provider-reported AI usage (same source as AI Control). */
export function AiMonthCard() {
  const A = useA();
  const { data, error, reload } = useLoad(() => api.admin.aiUsage(new Date().getDate()), []);
  const t = data?.totals;
  return <Card title="AI tháng này" actions={<Link className="btn sm" href={A("/ai")}>AI Control</Link>}>
    {error ? <ErrorState error={error} retry={reload}/> : !t ? <StateView kind="loading"/> : <div className="kpiGrid">
      <Kpi label="Lượt gọi mô hình" value={num(t.calls)} hint={`${num(t.failedCalls)} lỗi`}/>
      <Kpi label="Token" value={num(t.totalTokens)} hint={t.callsWithoutUsage ? `${num(t.callsWithoutUsage)} lượt không có số liệu` : "số liệu nhà cung cấp"}/>
      <Kpi label="Chi phí" value={usd(t.costUsd)} hint={t.calls ? `${num(t.costReportedCalls)}/${num(t.calls)} lượt có chi phí` : "Chưa có lượt gọi"}/>
    </div>}
    <p className="hint">Ngân sách tiền (theo tổ chức, workspace, người dùng, ứng dụng) và ngưỡng cảnh báo đặt ở mục “Quyền & ngân sách AI” của Quản trị công ty.</p>
  </Card>;
}

/** Explicit prices, never built in. Rows are immutable: a change is a new row from now on, so past costs stay reproducible. */
export function PricingCard() {
  const providers = useLoad(() => api.admin.aiProviders(), []);
  const { data, error, loading, reload } = useLoad(() => api.admin.aiPricing(), []);
  const [f, setF] = useState({ modelId: "", input: "", output: "", note: "" });
  const { act, busy, msg: ok, err } = useAdminAction("Không thêm được giá.", reload);
  const models = (providers.data ?? []).flatMap((p) => p.models.map((m) => m.id));
  // prices are immutable rows: one click = one row (single-flight), the form is cleared only after the server accepted it
  function add(e: FormEvent) {
    e.preventDefault();
    const row = { modelId: f.modelId, inputUsdPerMTok: Number(f.input), outputUsdPerMTok: Number(f.output), note: f.note.trim() || undefined };
    void act(() => api.admin.aiAddPrice(row), `Đã thêm giá cho ${f.modelId}, áp dụng từ bây giờ.`).then((r) => { if (r.status === "ok") setF({ modelId: "", input: "", output: "", note: "" }); });
  }
  return <Card title="Bảng giá mô hình">
    <p className="hint">Dùng để tính chi phí khi nhà cung cấp không báo (OpenAI, Anthropic, Gemini, mô hình nội bộ). Hệ thống không có sẵn giá nào. Giá không sửa được: thay đổi = thêm dòng mới áp dụng từ thời điểm thêm; chi phí đã ghi không bị tính lại.</p>
    <LoadNote load={providers} what="danh sách mô hình"/>
    <form className="filters wrap" onSubmit={(e) => void add(e)}>
      <select aria-label="Mô hình" value={f.modelId} onChange={(e) => setF({ ...f, modelId: e.target.value })} required><option value="">Chọn mô hình</option>{models.map((m) => <option key={m} value={m}>{m}</option>)}</select>
      <input aria-label="Giá token vào (USD / 1 triệu)" type="number" min="0" max="10000" step="0.000001" placeholder="Vào USD/1M" value={f.input} onChange={(e) => setF({ ...f, input: e.target.value })} required/>
      <input aria-label="Giá token ra (USD / 1 triệu)" type="number" min="0" max="10000" step="0.000001" placeholder="Ra USD/1M" value={f.output} onChange={(e) => setF({ ...f, output: e.target.value })} required/>
      <input aria-label="Ghi chú (nguồn giá)" placeholder="Ghi chú, ví dụ: theo hợp đồng 2026" maxLength={200} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })}/>
      <button className="btn primary" disabled={!f.modelId || busy}>Thêm giá</button>
    </form>
    {ok ? <p className="hint" role="status">{ok}</p> : null}{err ? <p className="formError" role="alert">{err}</p> : null}
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.length ? <StateView kind="empty" title="Chưa có giá nào" detail="Chi phí của mô hình trả phí sẽ hiển thị “không rõ” cho tới khi có giá."/> :
      <table className="table"><thead><tr><th>Model</th><th>Vào / Ra (USD / 1M token)</th><th>Áp dụng từ</th><th>Ghi chú</th><th>Người thêm</th></tr></thead>
        <tbody>{data!.map((r) => <tr key={r.id}><td className="code">{r.modelId}</td><td>{r.inputUsdPerMTok} / {r.outputUsdPerMTok}</td><td>{fmtDate(r.effectiveFrom)}</td><td>{r.note || "—"}</td><td>{r.createdBy ?? "—"}</td></tr>)}</tbody></table>}
  </Card>;
}

/**
 * Which AI providers are in use, from the providers the company configured (the same list as the Providers tab). The legacy `/admin/ai` answer (`provider: openrouter | mock`, `configured` = an environment key)
 * only knows ONE provider and would say "OpenRouter / no key" while OpenAI or Anthropic is working, so it is not shown as the status (backend handoff: M-059).
 */
function ProviderKpi({ providers }: { providers: { data: AiProviderInfo[] | null; error: unknown } }) {
  if (providers.error) return <Kpi label="Nhà cung cấp AI" value="—" hint="Chưa tải được danh sách nhà cung cấp"/>;
  if (!providers.data) return <Kpi label="Nhà cung cấp AI" value="…"/>;
  const on = providers.data.filter((p) => p.enabled && p.configured);
  return <Kpi label="Nhà cung cấp AI" value={on.length ? `${num(on.length)} đang bật` : "Chế độ thử nghiệm"}
    hint={on.length ? `${on.slice(0, 3).map((p) => p.name).join(", ")}${on.length > 3 ? ` và ${on.length - 3} nhà cung cấp khác` : ""}` : "Chưa có nhà cung cấp AI thật nào được cấu hình và bật: hệ thống dùng chế độ thử nghiệm (mô phỏng)."}/>;
}

export function AiPage() {
  const providers = useLoad(() => api.admin.aiProviders(), []);
  const { data, error, loading, reload } = useLoad(() => api.admin.ai(), []);
  const [days, setDays] = useState(30);
  const usage = useLoad(() => api.admin.aiUsage(days), [days]);
  if (!data) return <LoadGate load={{ data, error, loading, reload }} level={2} label="số liệu AI">{() => null}</LoadGate>;
  const a = data!;
  const u = usage.data;
  const t = u?.totals;
  return (<>
    <div className="kpiGrid">
      <ProviderKpi providers={providers}/>
      <Kpi label="Lượt AI hôm nay" value={num(a.requestsToday)} hint={`${num(a.externalToday)} qua AI thật · ${num(a.requestsMonth)} trong tháng`}/>
      <Kpi label="Giới hạn" value={`${a.dailyLimitPerUser} lượt/ngày/người`}
        hint={u ? [u.limits.dailyTokensPerUser ? `${num(u.limits.dailyTokensPerUser)} token/ngày/người` : "Không giới hạn token/người",
          u.limits.monthlyTokensPerWorkspace ? `${num(u.limits.monthlyTokensPerWorkspace)} token/tháng/workspace` : "không giới hạn token/workspace"].join(" · ") : `${a.promptsPerMinute} prompt/phút`}/>
    </div>

    <Card title="Mức sử dụng mô hình" actions={<div className="seg" role="group" aria-label="Khoảng thời gian">{[1, 7, 30, 90].map((d) =>
      <button key={d} className={`btn sm ${d === days ? "primary" : ""}`} aria-pressed={d === days} onClick={() => setDays(d)}>{d === 1 ? "Hôm nay" : `${d} ngày`}</button>)}</div>}>
      {usage.error ? <ErrorState error={usage.error} retry={usage.reload}/> : !u || !t ? <StateView kind="loading"/> : <>
        <div className="kpiGrid">
          <Kpi label="Lượt gọi mô hình" value={num(t.calls)} hint={t.calls ? `${num(t.failedCalls)} lỗi / không dùng được (gồm lượt thử lại)` : "Chưa có"}/>
          <Kpi label="Tổng token" value={num(t.totalTokens)} hint={`${num(t.promptTokens)} vào · ${num(t.completionTokens)} ra`}/>
          <Kpi label="Chi phí (nhà cung cấp báo)" value={usd(t.costUsd)} hint={t.calls ? `${num(t.costReportedCalls)}/${num(t.calls)} lượt có báo chi phí` : "Model miễn phí báo $0"}/>
          <Kpi label="Thời gian trả lời TB" value={t.avgLatencyMs == null ? "—" : `${(t.avgLatencyMs / 1000).toFixed(1)} s`}/>
        </div>
        <p className="hint">{coverage(t)}. Lượt lỗi (HTTP 429, hết thời gian chờ) không có số token và không được tính là 0 ước lượng. Bộ mô phỏng không gọi mô hình nên không xuất hiện ở đây.</p>
        <DailyBars daily={u.daily}/>
      </>}
    </Card>
    {u ? <>
      <Card title="Theo mô hình"><UsageTable rows={u.byModel} keyLabel="Mô hình"/></Card>
      <div className="grid2">
        <Card title="Theo người dùng (top 20)"><UsageTable rows={u.byUser} keyLabel="Người dùng"/></Card>
        <Card title="Theo workspace (top 20)"><UsageTable rows={u.byWorkspace} keyLabel="Workspace"/></Card>
      </div>
      <AiCallsLog models={u.byModel.map((m) => m.key)}/>
    </> : null}

    <Card title="Prompt gần đây"><table className="table"><thead><tr><th>Thời gian</th><th>Người dùng</th><th>Prompt</th><th>Model</th><th>Kết quả</th></tr></thead><tbody>{a.recent.map((p) => <tr key={p.id}><td>{ago(p.createdAt)}</td><td>{p.user ?? "—"}</td><td>{p.text}</td><td className="code">{p.model ?? p.provider}</td><td>{p.outcome ? <Pill value={p.outcome}/> : "—"}</td></tr>)}</tbody></table></Card>
  </>);
}
