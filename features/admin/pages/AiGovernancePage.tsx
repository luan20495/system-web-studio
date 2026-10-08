"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api } from "@/lib/http-api";
import type { AccessRule, AiBudget, EffectiveModel } from "@/lib/http-types";
import { confirm } from "@xweb/ui";
import { useLoad } from "../../useLoad";
import { ago, Card, ErrorState, errText, num, Pill, StateView, usd } from "../../ui";
import { LoadNote } from "../LoadNote";
import { PageHead } from "../PageHead";

// ---------------------------------------------------------------- Stage E: AI governance

const SCOPE_LABEL: Record<string, string> = { ORG: "Toàn tổ chức", WORKSPACE: "Workspace", ROLE: "Vai trò trong workspace", USER: "Người dùng", PROJECT: "Ứng dụng" };

/** Picks a scope id: workspaces/users/applications are searched through the existing admin lists. */
export function ScopePicker({ types, value, onChange }: { types: string[]; value: { type: string; id: string }; onChange: (v: { type: string; id: string }) => void }) {
  const [q, setQ] = useState(""); const [applied, setApplied] = useState(""); const [ws, setWs] = useState(""); const [role, setRole] = useState("EDITOR");
  useEffect(() => { const t = setTimeout(() => setApplied(q), 250); return () => clearTimeout(t); }, [q]);     // one request per pause in typing, not per key
  const kind = value.type === "ROLE" ? "WORKSPACE" : value.type;
  const found = useLoad(async (): Promise<{ id: string; label: string }[]> => (kind === "WORKSPACE" ? (await api.admin.workspaces(0, applied)).items.map((w) => ({ id: w.id, label: w.name }))
    : kind === "USER" ? (await api.admin.users(0, applied)).items.map((u) => ({ id: u.id, label: `${u.displayName ?? u.username} (${u.username})` }))
    : kind === "PROJECT" ? (await api.admin.applications({ page: 0, q: applied })).items.map((a) => ({ id: a.id, label: `${a.name} · ${a.workspaceName}` })) : []), [kind, applied]);
  const opts = found.data ?? [];
  useEffect(() => { if (value.type === "ROLE" && ws) onChange({ type: "ROLE", id: `${ws}:${role}` }); }, [ws, role]); // eslint-disable-line react-hooks/exhaustive-deps
  return <>
    <select aria-label="Phạm vi" value={value.type} onChange={(e) => { setQ(""); setWs(""); onChange({ type: e.target.value, id: "" }); }}>
      {types.map((t) => <option key={t} value={t}>{SCOPE_LABEL[t]}</option>)}</select>
    {value.type !== "ORG" ? <>
      <input aria-label="Tìm phạm vi" placeholder="Tìm…" value={q} onChange={(e) => setQ(e.target.value)}/>
      <select aria-label="Chọn phạm vi" value={value.type === "ROLE" ? ws : value.id} onChange={(e) => value.type === "ROLE" ? setWs(e.target.value) : onChange({ type: value.type, id: e.target.value })}>
        <option value="">— chọn —</option>{opts.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}</select>
      {value.type === "ROLE" ? <select aria-label="Vai trò" value={role} onChange={(e) => setRole(e.target.value)}>
        {["WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER"].map((r) => <option key={r} value={r}>{r}</option>)}</select> : null}
      <LoadNote load={found} what="danh sách để chọn"/>
    </> : null}
  </>;
}

/** Model access (deny rules below the global enable flag; most restrictive wins) and money budgets. */
export function AiGovernancePage() {
  const rules = useLoad(() => api.admin.accessRules(), []);
  const budgets = useLoad(() => api.admin.budgets(), []);
  const [rule, setRule] = useState({ type: "ORG", id: "" }); const [model, setModel] = useState("paid:*");
  const [b, setB] = useState({ type: "ORG", id: "" }); const [bf, setBf] = useState({ period: "MONTHLY", amount: "", currency: "USD", rate: "", soft: "80", hard: true });
  const [check, setCheck] = useState({ type: "USER", id: "" }); const [checkWs, setCheckWs] = useState({ type: "WORKSPACE", id: "" }); const [eff, setEff] = useState<EffectiveModel[] | null>(null);
  const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  async function addRule(e: FormEvent) {
    e.preventDefault(); setErr(null); setMsg(null);
    try { await api.admin.addAccessRule({ scopeType: rule.type, scopeId: rule.type === "ORG" ? undefined : rule.id, modelId: model.trim() }); setMsg("Đã thêm quy tắc chặn."); rules.reload(); }
    catch (x) { setErr(errText(x, "Không thêm được.")); }
  }
  async function delRule(r: AccessRule) { setErr(null); try { await api.admin.deleteAccessRule(r.id); rules.reload(); } catch (x) { setErr(errText(x, "Không xoá được.")); } }
  async function saveBudget(e: FormEvent) {
    e.preventDefault(); setErr(null); setMsg(null);
    try {
      await api.admin.setBudget({ scopeType: b.type, scopeId: b.type === "ORG" ? undefined : b.id, period: bf.period, amount: Number(bf.amount), currency: bf.currency.toUpperCase(),
        usdPerUnit: bf.currency.toUpperCase() === "USD" ? undefined : Number(bf.rate), softPercent: Number(bf.soft), hard: bf.hard });
      setMsg("Đã lưu ngân sách."); budgets.reload();
    } catch (x) { setErr(errText(x, "Không lưu được.")); }
  }
  async function delBudget(x: AiBudget) { if (!(await confirm({ title: "Xoá ngân sách này?", message: `${SCOPE_LABEL[x.scopeType]} ${x.scopeLabel ?? x.scopeId}: không còn bị giới hạn bởi ngân sách này.`, confirmLabel: "Xoá ngân sách", danger: true }))) return; try { await api.admin.deleteBudget(x.id); budgets.reload(); } catch (e) { setErr(errText(e, "Không xoá được.")); } }
  async function runCheck(e: FormEvent) { e.preventDefault(); setErr(null); try { setEff(await api.admin.effectiveModels(check.id, checkWs.id || undefined)); } catch (x) { setErr(errText(x, "Không kiểm tra được.")); } }
  const money = (v: number, c: string) => `${num(Math.round(v * 10000) / 10000)} ${c}`;
  return (<>
    <PageHead title="Quản trị AI" sub="Quyền dùng model theo tổ chức → workspace → vai trò → người dùng (chặn ở bất kỳ mức nào là chặn), và ngân sách tiền trên chi phí đã biết."/>
    {err ? <p className="formError" role="alert">{err}</p> : null}{msg ? <p className="hint" role="status">{msg}</p> : null}
    <Card title="Quyền dùng model (Model Access)">
      <p className="hint">Model phải được bật ở AI Control trước. Quy tắc ở đây chỉ CHẶN thêm. Mã model: chính xác (ví dụ <code>openai:gpt-…</code>), <code>paid:*</code> (mọi model trả phí) hoặc <code>*</code> (mọi model thật; bộ mô phỏng luôn dùng được).</p>
      <form className="filters wrap" onSubmit={(e) => void addRule(e)}>
        <ScopePicker types={["ORG", "WORKSPACE", "ROLE", "USER"]} value={rule} onChange={setRule}/>
        <input aria-label="Model bị chặn" value={model} onChange={(e) => setModel(e.target.value)} placeholder="paid:* | * | provider:model"/>
        <button className="btn primary" disabled={!model.trim() || (rule.type !== "ORG" && !rule.id)}>Thêm quy tắc chặn</button>
      </form>
      {rules.error ? <ErrorState error={rules.error} retry={rules.reload}/> : !rules.data ? <StateView kind="loading"/> : !rules.data.length ? <StateView kind="empty" title="Chưa có quy tắc chặn nào"/> :
        <table className="table"><thead><tr><th>Phạm vi</th><th>Đối tượng</th><th>Model bị chặn</th><th>Tạo</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{rules.data.map((r) => <tr key={r.id}><td>{SCOPE_LABEL[r.scopeType]}</td><td>{r.scopeLabel ?? r.scopeId}</td><td className="code">{r.modelId}</td><td>{ago(r.createdAt)}</td>
            <td><button className="btn sm ghost" onClick={() => void delRule(r)}>Bỏ chặn</button></td></tr>)}</tbody></table>}
      <h3 className="subHead">Kiểm tra quyền hiệu lực</h3>
      <form className="filters wrap" onSubmit={(e) => void runCheck(e)}>
        <ScopePicker types={["USER"]} value={check} onChange={setCheck}/><ScopePicker types={["WORKSPACE"]} value={checkWs} onChange={setCheckWs}/>
        <button className="btn" disabled={!check.id}>Kiểm tra</button>
      </form>
      {eff ? eff.length === 0 ? <StateView kind="empty" title="Chưa có model nào được cấu hình"/> :
        <table className="table"><thead><tr><th>Model</th><th>Nhà cung cấp</th><th>Trả phí</th><th>Kết quả</th></tr></thead>
          <tbody>{eff.map((m) => <tr key={m.id}><td className="code">{m.id}</td><td>{m.provider}</td><td>{m.paid ? "Có" : "Không"}</td>
            <td><Pill value={m.allowed ? "ACTIVE" : "DISABLED"} label={m.reason}/></td></tr>)}</tbody></table> : null}
    </Card>
    <Card title="Ngân sách AI (tiền)">
      <p className="hint">Chỉ tính chi phí đã biết: chi phí nhà cung cấp báo, hoặc token × bảng giá ở AI Control. Cuộc gọi không rõ chi phí được đếm riêng, không ước đoán.
        Ngân sách cứng chặn model TRẢ PHÍ khi đã tiêu đủ (model miễn phí vẫn dùng được); model trả phí chưa có giá sẽ bị từ chối khi có ngân sách cứng. Tiền tệ khác USD cần tỷ giá do bạn nhập.</p>
      <form className="filters wrap" onSubmit={(e) => void saveBudget(e)}>
        <ScopePicker types={["ORG", "WORKSPACE", "USER", "PROJECT"]} value={b} onChange={setB}/>
        <select aria-label="Chu kỳ" value={bf.period} onChange={(e) => setBf({ ...bf, period: e.target.value })}><option value="DAILY">Ngày</option><option value="MONTHLY">Tháng</option></select>
        <input aria-label="Số tiền" type="number" min="0" step="any" placeholder="Số tiền" value={bf.amount} onChange={(e) => setBf({ ...bf, amount: e.target.value })}/>
        <input aria-label="Tiền tệ" maxLength={3} value={bf.currency} onChange={(e) => setBf({ ...bf, currency: e.target.value })} style={{ width: 70 }}/>
        {bf.currency.toUpperCase() !== "USD" ? <input aria-label="USD cho 1 đơn vị" type="number" step="any" placeholder={`USD / 1 ${bf.currency.toUpperCase()}`} value={bf.rate} onChange={(e) => setBf({ ...bf, rate: e.target.value })}/> : null}
        <label className="row">Cảnh báo ở <input aria-label="Ngưỡng cảnh báo %" type="number" min="1" max="100" value={bf.soft} onChange={(e) => setBf({ ...bf, soft: e.target.value })} style={{ width: 64 }}/>%</label>
        <label className="switch"><input type="checkbox" checked={bf.hard} onChange={(e) => setBf({ ...bf, hard: e.target.checked })}/> Chặn khi vượt</label>
        <button className="btn primary" disabled={!bf.amount || (b.type !== "ORG" && !b.id)}>Lưu ngân sách</button>
      </form>
      {budgets.error ? <ErrorState error={budgets.error} retry={budgets.reload}/> : !budgets.data ? <StateView kind="loading"/> : !budgets.data.length ? <StateView kind="empty" title="Chưa có ngân sách nào"/> :
        <table className="table"><thead><tr><th>Phạm vi</th><th>Chu kỳ</th><th>Ngân sách</th><th>Đã dùng</th><th>Mức dùng</th><th>Không rõ chi phí</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{budgets.data.map((x) => <tr key={x.id}><td>{SCOPE_LABEL[x.scopeType]}<small>{x.scopeLabel ?? x.scopeId}</small></td><td>{x.period === "DAILY" ? "Ngày" : "Tháng"}</td>
            <td>{money(x.amount, x.currency)}<small>{x.hard ? "chặn khi vượt" : "chỉ cảnh báo"} · cảnh báo {x.softPercent}%</small></td>
            <td>{money(x.spent, x.currency)}{x.currency !== "USD" ? <small>{usd(x.spentUsd)}</small> : null}</td>
            <td><div className={`budgetBar ${x.percent >= 100 ? "over" : x.percent >= x.softPercent ? "warn" : ""}`} role="img" aria-label={`${x.percent}%`}><i style={{ width: `${Math.min(100, x.percent)}%` }}/></div><small>{x.percent}%</small></td>
            <td>{num(x.unknownCostCalls)}</td><td><button className="btn sm ghost" onClick={() => void delBudget(x)}>Xoá</button></td></tr>)}</tbody></table>}
    </Card>
  </>);
}
