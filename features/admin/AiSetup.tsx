"use client";
import { useA } from "./console/context";
import Link from "next/link";
import { useState, type FormEvent, type ReactNode } from "react";
import { api } from "@/lib/http-api";
import type { AiLimitDefaults, AiOverride, AiProbe, AiProviderInfo, AiProviderKind } from "@/lib/http-types";
import { LoadNote } from "./LoadNote";
import { useSingleFlight } from "./useAdminAction";
import { useLoad } from "../useLoad";
import { Modal } from "./Modal";
import { Card, ErrorState, errText, num, Pill, StateView, usd } from "../ui";
import { Activity, CircleAlert, CircleCheck, CircleSlash, Cpu, KeyRound, ModalHeader, Pencil, Picker, Plus, Power, ProviderLogo, Save, Server, Settings2, ShieldCheck, Switch, Trash2, Zap, ChevronDown, ChevronUp, Download, type PickerOption, confirm, LoadGate } from "@xweb/ui";

// ------------------------------------------------------------------ wording
export const KIND_LABELS: Record<AiProviderKind, string> = {
  OPENROUTER: "OpenRouter", OPENAI: "OpenAI", ANTHROPIC: "Anthropic", GEMINI: "Google Gemini", OPENAI_COMPATIBLE: "Tương thích OpenAI", LOCAL: "AI nội bộ (Local)"
};
const KIND_HINT: Record<AiProviderKind, string> = {
  OPENROUTER: "Dùng được các mô hình miễn phí cho chế độ Tự động.", OPENAI: "Mô hình OpenAI (GPT…), tính phí theo hợp đồng.", ANTHROPIC: "Mô hình Claude của Anthropic, tính phí theo hợp đồng.",
  GEMINI: "Mô hình Gemini của Google, tính phí theo hợp đồng.", OPENAI_COMPATIBLE: "Dịch vụ khác có giao diện giống OpenAI (cần nhập địa chỉ dịch vụ).", LOCAL: "Máy chủ AI chạy trong mạng công ty (ví dụ Ollama)."
};
const NEEDS_ADDRESS = (k: AiProviderKind) => k === "OPENAI_COMPATIBLE" || k === "LOCAL";
const KEY_OPTIONAL = (k: AiProviderKind) => k === "OPENAI_COMPATIBLE" || k === "LOCAL";

/** 0 is never shown as a bare "0": it means "Không giới hạn" for usage limits and "Chưa cấp ngân sách" for paid budgets. */
export const countLimit = (n: number | null | undefined) => (n == null || n === 0 ? "Không giới hạn" : num(n));
export const budgetLimit = (n: number | null | undefined) => (n == null || n === 0 ? "Chưa cấp ngân sách" : usd(n));
const SOURCE_LABEL = { DEFAULT: "Mặc định của công ty", WORKSPACE: "Theo không gian làm việc", USER: "Riêng cho người này" } as const;

function Head({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return <div className="pageHead"><div><h1>{title}</h1>{sub ? <p>{sub}</p> : null}</div>{actions}</div>;
}

// ------------------------------------------------------------------ container with tabs
const TABS: [string, string][] = [["providers", "Nhà cung cấp"], ["models", "Mô hình"], ["limits", "Hạn mức"], ["usage", "Sử dụng"]];
export function AiAdmin({ tab, usage, pricing }: { tab?: string; usage: ReactNode; pricing: ReactNode }) {
  const A = useA();
  const active = TABS.some(([k]) => k === tab) ? tab! : "providers";
  return (<>
    <Head title="AI" sub="Cấu hình AI cho cả công ty: nhà cung cấp, mô hình được dùng và hạn mức. Nhân viên không bao giờ thấy khóa kết nối."/>
    <div className="tabs" role="tablist">{TABS.map(([k, l]) => <Link key={k} role="tab" aria-selected={active === k} className={active === k ? "active" : ""} href={A(`/ai/${k}`)}>{l}</Link>)}</div>
    {active === "providers" ? <ProvidersTab/> : active === "models" ? <ModelsTab pricing={pricing}/> : active === "limits" ? <LimitsTab/> : usage}
  </>);
}

// ------------------------------------------------------------------ providers
function ProvidersTab() {
  const { data, error, loading, reload } = useLoad(() => api.admin.aiProviders(), []);
  const [dialog, setDialog] = useState<{ edit?: AiProviderInfo } | null>(null);
  const [picker, setPicker] = useState<AiProviderInfo | null>(null);
  const [probes, setProbes] = useState<Record<string, AiProbe | "running">>({}); const [msg, setMsg] = useState<string | null>(null);
  async function probe(id: string) {
    setProbes((p) => ({ ...p, [id]: "running" }));
    try { const r = await api.admin.aiProbe(id); setProbes((p) => ({ ...p, [id]: r })); } catch (e) { setProbes((p) => ({ ...p, [id]: { id, ok: false, latencyMs: 0, detail: errText(e, "Chưa kiểm tra được.") } })); }
  }
  const once = useSingleFlight();
  async function remove(p: AiProviderInfo) {
    if (!(await confirm({ title: `Xóa nhà cung cấp “${p.name}”?`, message: "Khóa kết nối đã lưu sẽ bị xóa và các mô hình của nhà cung cấp này ngừng hoạt động.", confirmLabel: "Xóa nhà cung cấp", danger: true }))) return;
    void once(async () => { try { await api.admin.deleteAiProvider(p.id); setMsg(`Đã xóa “${p.name}”.`); reload(); } catch (e) { setMsg(errText(e, "Chưa xóa được.")); } });
  }
  async function toggle(p: AiProviderInfo) {
    if (p.enabled && !(await confirm({ title: `Tắt nhà cung cấp “${p.name}”?`, message: "Mọi mô hình của nhà cung cấp này ngừng dùng được cho cả công ty cho tới khi bạn bật lại.", confirmLabel: "Tắt nhà cung cấp", danger: true }))) return;
    void once(async () => { try { await api.admin.updateAiProvider(p.id, { enabled: !p.enabled }); reload(); } catch (e) { setMsg(errText(e, "Chưa đổi được trạng thái.")); } });
  }
  return (<>
    <Card title="Nhà cung cấp AI" actions={<button className="btn primary xp-btnIcon" onClick={() => setDialog({})}><Plus size={16} aria-hidden="true"/>Thêm nhà cung cấp</button>}>
      <p className="hint">Chưa cấu hình AI thật thì hệ thống dùng chế độ thử nghiệm (mô phỏng, không gửi dữ liệu ra ngoài). Khóa kết nối được mã hóa khi lưu và không thể xem lại; chỉ có thể thay khóa mới.</p>
      {msg ? <p className="notice" role="status">{msg}</p> : null}
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : !data!.length ? (
        <StateView kind="empty" title="Chưa cấu hình AI thật." detail={<p>Thêm nhà cung cấp AI đầu tiên để nhân viên dùng được AI thật.</p>} action={<button className="btn primary" onClick={() => setDialog({})}>+ Thêm nhà cung cấp</button>}/>
      ) : <div className="providerList">{data!.map((p) => {
        const pr = probes[p.id]; const kind = (p.kind || "LOCAL") as AiProviderKind;
        const on = p.models.filter((m) => m.enabled).length;
        return <section key={p.id} className="providerItem" aria-label={p.name} data-testid={`provider:${p.id}`}>
          <div className="xp-provRow">
            <ProviderLogo kind={kind} size={44}/>
            <div className="xp-provMain">
              <div className="xp-provName"><b>{p.name}</b> <small>{KIND_LABELS[kind] ?? kind}</small>
                {p.managedBySystem ? <Pill value="COMPANY" label="Được quản lý bởi hệ thống"/> : p.enabled ? <Pill value="ACTIVE" label="Đang bật"/> : <Pill value="DISABLED" label="Đang tắt"/>}
                {p.configured ? <Pill value="HEALTHY" label="Đã cấu hình"/> : <Pill value="NOT_CONFIGURED" label={p.models.length === 0 ? "Chưa chọn mô hình" : "Chưa cấu hình"}/>}</div>
              <div className="xp-provMeta">
                <span data-testid="model-count"><Cpu size={13} aria-hidden="true"/>{p.models.length ? `${p.models.length} mô hình${p.models.some((m) => m.enabled) ? ` · ${on} đang được phép dùng` : " · chưa mô hình nào được phép dùng (vào tab Mô hình để bật)"}` : "Chưa có mô hình nào"}</span>
                <span><KeyRound size={13} aria-hidden="true"/>Khóa kết nối: {p.keySet ? "Đã cấu hình" : KEY_OPTIONAL(kind) ? "Không dùng" : "Chưa cấu hình"}</span>
                <span>{p.paid ? "Tính phí" : "Miễn phí"}</span>
                {pr && pr !== "running" ? <span data-testid="probe-result" data-ok={pr.ok} style={{ color: pr.ok ? "var(--f-ok)" : "var(--f-bad)" }}>{pr.ok ? <CircleCheck size={13} aria-hidden="true"/> : <CircleAlert size={13} aria-hidden="true"/>}{pr.ok ? ` ${pr.detail}` : ` ${pr.detail}`}</span> : null}
              </div>
            </div>
            <div className="xp-provActions">
              <button className="btn sm xp-btnIcon" disabled={pr === "running"} onClick={() => void probe(p.id)}><Zap size={14} aria-hidden="true"/>{pr === "running" ? "Đang kiểm tra…" : "Kiểm tra kết nối"}</button>
              {!p.managedBySystem ? <>
                {kind !== "OPENROUTER" ? <button className="btn sm xp-btnIcon" onClick={() => setPicker(p)}><Download size={14} aria-hidden="true"/>Tải danh sách mô hình</button> : null}
                <button className="btn sm xp-btnIcon" onClick={() => setDialog({ edit: p })}><Pencil size={14} aria-hidden="true"/>Sửa</button>
                <button className="btn sm xp-btnIcon" onClick={() => void toggle(p)}>{p.enabled ? <CircleSlash size={14} aria-hidden="true"/> : <Power size={14} aria-hidden="true"/>}{p.enabled ? "Tắt" : "Bật"}</button>
                <button className="btn sm danger xp-btnIcon" onClick={() => void remove(p)}><Trash2 size={14} aria-hidden="true"/>Xóa</button>
              </> : null}
            </div>
          </div>
        </section>;
      })}</div>}
    </Card>
    {dialog ? <ProviderDialog edit={dialog.edit} onClose={() => setDialog(null)} onSaved={(p) => { setDialog(null); reload(); void probe(p.id); }}/> : null}
    {picker ? <ModelPicker provider={picker} onClose={() => setPicker(null)} onSaved={() => { setPicker(null); reload(); }}/> : null}
  </>);
}

function ProviderDialog({ edit, onClose, onSaved }: { edit?: AiProviderInfo; onClose: () => void; onSaved: (p: AiProviderInfo) => void }) {
  const [kind, setKind] = useState<AiProviderKind>((edit?.kind || "OPENROUTER") as AiProviderKind);
  const [name, setName] = useState(edit?.name ?? ""); const [baseUrl, setBaseUrl] = useState(edit?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState(""); const [models, setModels] = useState((edit?.savedModels ?? []).join("\n")); const [defaultModel, setDefaultModel] = useState(edit?.defaultModel ?? "");
  const [paid, setPaid] = useState(edit?.paid ?? true); const [enabled, setEnabled] = useState(edit?.enabled ?? true); const [advanced, setAdvanced] = useState(false);
  const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  const list = models.split(/[\n,]/).map((m) => m.trim()).filter(Boolean);
  function pickKind(k: AiProviderKind) { setKind(k); setPaid(k !== "LOCAL" && k !== "OPENROUTER"); if (!name) setName(KIND_LABELS[k]); }
  const once = useSingleFlight();
  function submit(e: FormEvent) {
    e.preventDefault(); void once(async () => {
    setBusy(true); setError(null);
    try {
      const body = { name: name.trim(), baseUrl: NEEDS_ADDRESS(kind) ? baseUrl.trim() : undefined, apiKey: apiKey.trim() || undefined,
        models: kind === "OPENROUTER" ? undefined : list, defaultModel: kind === "OPENROUTER" ? undefined : defaultModel, paid, enabled };
      const saved = edit ? await api.admin.updateAiProvider(edit.id, body) : await api.admin.addAiProvider({ ...body, kind });
      onSaved(saved);
    } catch (err) { setError(errText(err, "Chưa lưu được nhà cung cấp.")); } finally { setBusy(false); }
    });
  }
  const kinds = (Object.keys(KIND_LABELS) as AiProviderKind[]).map((k): PickerOption<AiProviderKind> => ({ value: k, label: KIND_LABELS[k], hint: KIND_HINT[k], icon: <ProviderLogo kind={k} size={32}/> }));
  return (
    <Modal label={edit ? "Sửa nhà cung cấp" : "Thêm nhà cung cấp"} onClose={onClose}>
      <form className="modalBody" onSubmit={(e) => void submit(e)} autoComplete="off" data-testid="provider-dialog">
        <ModalHeader icon={<Server size={22}/>} title={edit ? "Sửa nhà cung cấp" : "Thêm nhà cung cấp"} subtitle={edit ? `Cập nhật kết nối tới ${edit.name}. Khóa đã lưu không bao giờ hiển thị lại.` : "Kết nối một nhà cung cấp AI cho cả công ty."}/>

        <section className="xp-section" aria-label="Nhà cung cấp">
          <h3><Server size={14} aria-hidden="true"/> Nhà cung cấp</h3>
          {!edit ? <Picker label="Loại" buttonLabel="Nhà cung cấp" value={kind} options={kinds} onChange={pickKind}/> : <div className="row"><ProviderLogo kind={kind} size={40}/><div><b>{KIND_LABELS[kind]}</b><small className="hint">{KIND_HINT[kind]}</small></div></div>}
          <label className="field"><span>Tên</span><input value={name} onChange={(e) => setName(e.target.value)} required maxLength={80}/></label>
          {NEEDS_ADDRESS(kind) ? <label className="field"><span>Địa chỉ dịch vụ</span><input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} required placeholder="https://ai.example.com/v1"/></label> : null}
        </section>

        <section className="xp-section" aria-label="API key">
          <h3><KeyRound size={14} aria-hidden="true"/> API key</h3>
          <label className="field"><span>Khóa kết nối{KEY_OPTIONAL(kind) ? " (không bắt buộc)" : ""}</span>
            <span className="xp-keyInput"><KeyRound size={16} aria-hidden="true"/><input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} autoComplete="new-password" required={!edit && !KEY_OPTIONAL(kind)} placeholder={edit?.keySet ? "••••••••" : ""}/></span>
            <small>{edit?.keySet ? "Đã cấu hình. Để trống nếu không muốn thay đổi khóa hiện tại." : "Dán khóa do nhà cung cấp cấp cho công ty."}</small></label>
          <p className="xp-note" role="note"><ShieldCheck size={16} aria-hidden="true"/><span>Bảo mật: khóa được mã hóa khi lưu, chỉ nằm trên máy chủ và không thể xem lại; chỉ có thể thay khóa mới.</span></p>
        </section>

        {kind !== "OPENROUTER" ? <section className="xp-section" aria-label="Mô hình">
          <h3><Cpu size={14} aria-hidden="true"/> Mô hình</h3>
          <label className="field"><span>Danh sách mô hình (mỗi dòng một mô hình)</span><textarea rows={3} value={models} onChange={(e) => setModels(e.target.value)} placeholder="Để trống rồi dùng “Tải danh sách mô hình” sau khi lưu"/></label>
          <label className="field"><span>Mô hình mặc định của nhà cung cấp này</span>
            <select value={defaultModel} onChange={(e) => setDefaultModel(e.target.value)}><option value="">Không chọn</option>{list.map((m) => <option key={m} value={m}>{m}</option>)}</select></label>
        </section> : <p className="hint">Hệ thống tự lấy danh sách mô hình miễn phí của OpenRouter.</p>}

        <section className="xp-section" aria-label="Trạng thái">
          <h3><Activity size={14} aria-hidden="true"/> Trạng thái</h3>
          <Switch icon={<Power size={16}/>} checked={enabled} onChange={setEnabled} label="Bật nhà cung cấp này" hint={enabled ? "Nhân viên có thể dùng các mô hình được cho phép." : "Đang tắt: không mô hình nào của nhà cung cấp này được dùng."}/>
        </section>

        <div className="xp-adv">
          <button type="button" className="xp-advBtn" aria-expanded={advanced} onClick={() => setAdvanced(!advanced)}><Settings2 size={16} aria-hidden="true"/> {advanced ? "Ẩn Nâng cao" : "Nâng cao"}<span style={{ marginLeft: "auto" }}>{advanced ? <ChevronUp size={16} aria-hidden="true"/> : <ChevronDown size={16} aria-hidden="true"/>}</span></button>
          {advanced ? <div className="xp-advBody"><Switch checked={paid} onChange={setPaid} disabled={kind === "OPENROUTER"} label="Có tính phí" hint="Mô hình trả phí mặc định TẮT và cần ngân sách."/></div> : null}
        </div>

        {error ? <p className="formError" role="alert">{error}</p> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary xp-btnIcon" disabled={busy || !name.trim()} aria-busy={busy || undefined}><Save size={16} aria-hidden="true"/>{busy ? "Đang lưu…" : "Lưu"}</button></div>
      </form>
    </Modal>
  );
}

function ModelPicker({ provider, onClose, onSaved }: { provider: AiProviderInfo; onClose: () => void; onSaved: () => void }) {
  const { data, error, loading } = useLoad(() => api.admin.discoverAiModels(provider.id), [provider.id]);
  const [chosen, setChosen] = useState<Set<string>>(new Set(provider.savedModels)); const [manual, setManual] = useState("");
  const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const all = Array.from(new Set([...(data?.models ?? []), ...provider.savedModels]));
  function flip(m: string) { setChosen((c) => { const n = new Set(c); if (n.has(m)) n.delete(m); else n.add(m); return n; }); }
  const once = useSingleFlight();
  function save() { void once(async () => {
    setBusy(true); setErr(null);
    const extra = manual.split(/[\n,]/).map((m) => m.trim()).filter(Boolean);
    try { await api.admin.updateAiProvider(provider.id, { models: Array.from(new Set([...chosen, ...extra])) }); onSaved(); } catch (e) { setErr(errText(e, "Chưa lưu được danh sách mô hình.")); } finally { setBusy(false); }
  }); }
  return (
    <Modal label="Chọn mô hình" onClose={onClose}>
      <div className="modalBody">
        <h2>Mô hình của {provider.name}</h2>
        {error ? <ErrorState error={error}/> : loading ? <StateView kind="loading"/> : <p className="hint">{data!.detail}</p>}
        <div className="checkList">{all.map((m) => <label key={m} className="check"><input type="checkbox" checked={chosen.has(m)} onChange={() => flip(m)}/> {m}</label>)}</div>
        <label className="field"><span>Thêm mã mô hình thủ công</span><input value={manual} onChange={(e) => setManual(e.target.value)} placeholder="ví dụ: gpt-4o-mini"/></label>
        <p className="hint">Chọn mô hình ở đây chỉ đưa vào danh sách; bạn vẫn phải bật từng mô hình ở tab Mô hình trước khi nhân viên dùng được.</p>
        {err ? <p className="formError" role="alert">{err}</p> : null}
        <div className="row"><button className="btn primary" disabled={busy} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu danh sách"}</button><button className="btn" onClick={onClose}>Đóng</button></div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ models
function ModelsTab({ pricing }: { pricing: ReactNode }) {
  const A = useA();
  const { data, error, loading, reload } = useLoad(() => api.admin.aiProviders(), []);
  const limits = useLoad(() => api.admin.aiLimits(), []);
  const [msg, setMsg] = useState<string | null>(null); const [priceFor, setPriceFor] = useState<string | null>(null);
  const [price, setPrice] = useState({ input: "", output: "" });
  const defaultModel = limits.data?.defaults.defaultModel ?? "auto";
  // optimistic: the switch moves at once and is put back if the server refuses
  const [over, setOver] = useState<Record<string, boolean>>({});
  async function toggle(id: string, enabled: boolean) {
    setMsg(null); setOver((o) => ({ ...o, [id]: enabled }));
    try { await api.admin.aiModelPolicy(id, enabled); reload(); } catch (e) { setMsg(errText(e, "Chưa đổi được.")); setOver((o) => { const n = { ...o }; delete n[id]; return n; }); }
  }
  async function makeDefault(id: string) { setMsg(null); try { await api.admin.setAiDefaults({ defaultModel: id }); limits.reload(); } catch (e) { setMsg(errText(e, "Chưa đặt được mô hình mặc định.")); } }
  const once = useSingleFlight();
  function savePrice(e: FormEvent, id: string) {
    e.preventDefault(); void once(async () => {
    setMsg(null);
    try { await api.admin.aiAddPrice({ modelId: id, inputUsdPerMTok: Number(price.input), outputUsdPerMTok: Number(price.output) }); setPriceFor(null); setPrice({ input: "", output: "" }); reload(); setMsg("Đã lưu giá."); }
    catch (x) { setMsg(errText(x, "Chưa lưu được giá.")); }
    });
  }
  const rows = (data ?? []).flatMap((p) => p.models.map((m) => ({ p, m })));
  return (<>
    <Card title="Mô hình AI" actions={<span>Mặc định hiện tại: <b>{defaultModel === "auto" ? "Tự động" : defaultModel}</b> {defaultModel !== "auto" ? <button className="btn sm" onClick={() => void makeDefault("auto")}>Đặt về Tự động</button> : null}</span>}>
      <p className="hint">“Tự động” dùng các mô hình miễn phí của OpenRouter khi đã cấu hình, nếu không thì dùng chế độ thử nghiệm. Mô hình trả phí mặc định TẮT; muốn dùng cần bật, nhập giá và cấp ngân sách ở tab Hạn mức. Nhân viên chỉ thấy mô hình đang bật.</p>
      {msg ? <p className="notice" role="status">{msg}</p> : null}
      <LoadNote load={limits} what="mô hình mặc định hiện tại"/>
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : rows.length === 0 ? (
        <StateView kind="empty" title="Chưa có mô hình nào" detail={<p>Thêm nhà cung cấp rồi chọn mô hình ở tab Nhà cung cấp.</p>} action={<Link className="btn primary" href={A("/ai/providers")}>Đến Nhà cung cấp</Link>}/>
      ) : <table className="table"><thead><tr><th>Mô hình</th><th>Nhà cung cấp</th><th>Loại</th><th>Giá (USD / 1 triệu token)</th><th>Được phép dùng</th><th>Mặc định</th></tr></thead>
        <tbody>{rows.map(({ p, m }) => <tr key={m.id}>
          <td><b>{m.name}</b></td><td>{p.name}</td>
          <td>{m.paid ? <Pill value="UNKNOWN" label="Trả phí"/> : <Pill value="ACTIVE" label="Miễn phí"/>}</td>
          <td>{!m.paid ? <span className="muted">—</span> : priceFor === m.id ? (
            <form className="filters" onSubmit={(e) => void savePrice(e, m.id)}><input aria-label="Giá vào" type="number" min="0" step="0.000001" placeholder="Vào" value={price.input} onChange={(e) => setPrice({ ...price, input: e.target.value })} required/>
              <input aria-label="Giá ra" type="number" min="0" step="0.000001" placeholder="Ra" value={price.output} onChange={(e) => setPrice({ ...price, output: e.target.value })} required/>
              <button className="btn sm primary">Lưu</button><button type="button" className="btn sm" onClick={() => setPriceFor(null)}>Hủy</button></form>
          ) : <>{m.price ? `vào ${m.price.inputUsdPerMTok} · ra ${m.price.outputUsdPerMTok}` : <span className="muted">Chưa có giá</span>} <button className="btn sm" onClick={() => setPriceFor(m.id)}>Cấu hình giá</button></>}</td>
          <td><label className="switch"><input type="checkbox" checked={over[m.id] ?? m.enabled} onChange={(e) => void toggle(m.id, e.target.checked)} aria-label={`Cho phép ${m.name}`}/> {(over[m.id] ?? m.enabled) ? "Bật" : "Tắt"}</label></td>
          <td>{defaultModel === m.id ? <Pill value="ACTIVE" label="Mặc định"/> : <button className="btn sm" disabled={!(over[m.id] ?? m.enabled)} title={(over[m.id] ?? m.enabled) ? undefined : "Bật mô hình trước"} onClick={() => void makeDefault(m.id)}>Đặt làm mặc định</button>}</td>
        </tr>)}</tbody></table>}
    </Card>
    {pricing}
  </>);
}

// ------------------------------------------------------------------ limits
function NumberField({ label, value, onChange, hint, step = "1" }: { label: string; value: string; onChange: (v: string) => void; hint: (n: number) => string; step?: string }) {
  const n = Number(value);
  return <label className="field"><span>{label}</span><input type="number" min="0" step={step} value={value} onChange={(e) => onChange(e.target.value)} required/>
    <small>{Number.isFinite(n) ? hint(n) : ""}</small></label>;
}

function LimitsTab() {
  const { data, error, loading, reload, setData } = useLoad(() => api.admin.aiLimits(), []);
  const models = useLoad(() => api.admin.aiProviders(), []);
  const [form, setForm] = useState<Record<string, string> | null>(null); const [msg, setMsg] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const once = useSingleFlight();
  if (!data) return <LoadGate load={{ data, error, loading, reload }} level={2} label="hạn mức AI">{() => null}</LoadGate>;
  const d = data!.defaults;
  const f = form ?? { defaultModel: d.defaultModel, requestsPerUserDay: String(d.requestsPerUserDay), tokensPerUserDay: String(d.tokensPerUserDay), tokensPerWorkspaceMonth: String(d.tokensPerWorkspaceMonth),
    paidBudgetPerUserMonth: String(d.paidBudgetPerUserMonth), paidBudgetPerWorkspaceMonth: String(d.paidBudgetPerWorkspaceMonth) };
  const set = (k: string, v: string) => setForm({ ...f, [k]: v });
  const enabledModels = (models.data ?? []).flatMap((p) => p.models.filter((m) => m.enabled).map((m) => ({ id: m.id, label: `${m.name} (${p.name})` })));
  function save(e: FormEvent) {
    e.preventDefault(); void once(async () => {
    setBusy(true); setMsg(null);
    const body: Partial<AiLimitDefaults> = { defaultModel: f.defaultModel, requestsPerUserDay: Number(f.requestsPerUserDay), tokensPerUserDay: Number(f.tokensPerUserDay), tokensPerWorkspaceMonth: Number(f.tokensPerWorkspaceMonth),
      paidBudgetPerUserMonth: Number(f.paidBudgetPerUserMonth), paidBudgetPerWorkspaceMonth: Number(f.paidBudgetPerWorkspaceMonth) };
    try { setData(await api.admin.setAiDefaults(body)); setForm(null); setMsg("Đã lưu hạn mức mặc định."); } catch (x) { setMsg(errText(x, "Chưa lưu được hạn mức.")); } finally { setBusy(false); }
    });
  }
  return (<>
    <Card title="Hạn mức mặc định">
      <p className="hint">Áp dụng cho mọi người trừ khi bạn đặt hạn mức riêng. Mặc định an toàn: mô hình “Tự động”, mô hình trả phí tắt và chưa có ngân sách trả phí.</p>
      <form className="grid2" onSubmit={(e) => void save(e)}>
        <label className="field"><span>Mô hình mặc định</span><select value={f.defaultModel} onChange={(e) => set("defaultModel", e.target.value)}>
          <option value="auto">Tự động</option>{enabledModels.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}</select>
          <small>Dùng khi nhân viên không chọn mô hình.</small><LoadNote load={models} what="danh sách mô hình"/></label>
        <NumberField label="Lượt AI / người / ngày" value={f.requestsPerUserDay} onChange={(v) => set("requestsPerUserDay", v)} hint={(n) => n === 0 ? "Không giới hạn" : `${num(n)} lượt mỗi người mỗi ngày`}/>
        <NumberField label="Lượng AI (token) / người / ngày" value={f.tokensPerUserDay} onChange={(v) => set("tokensPerUserDay", v)} hint={(n) => n === 0 ? "Không giới hạn" : `${num(n)} token`}/>
        <NumberField label="Lượng AI (token) / không gian làm việc / tháng" value={f.tokensPerWorkspaceMonth} onChange={(v) => set("tokensPerWorkspaceMonth", v)} hint={(n) => n === 0 ? "Không giới hạn" : `${num(n)} token`}/>
        <NumberField label="Ngân sách AI trả phí / người / tháng (USD)" step="0.01" value={f.paidBudgetPerUserMonth} onChange={(v) => set("paidBudgetPerUserMonth", v)} hint={(n) => n === 0 ? "Chưa cấp ngân sách" : usd(n)}/>
        <NumberField label="Ngân sách AI trả phí / không gian làm việc / tháng (USD)" step="0.01" value={f.paidBudgetPerWorkspaceMonth} onChange={(v) => set("paidBudgetPerWorkspaceMonth", v)} hint={(n) => n === 0 ? "Chưa cấp ngân sách" : usd(n)}/>
        <div className="row"><button className="btn primary" disabled={busy}>{busy ? "Đang lưu…" : "Lưu hạn mức mặc định"}</button>{msg ? <span role="status">{msg}</span> : null}</div>
      </form>
    </Card>
    <Card title="Hạn mức riêng" actions={<button className="btn primary" onClick={() => setAdding(true)}>+ Thiết lập hạn mức riêng</button>}>
      {data!.overrides.length === 0 ? <StateView kind="empty" title="Chưa có hạn mức riêng" detail={<p>Mọi người đang dùng hạn mức mặc định ở trên.</p>}/> :
        <table className="table"><thead><tr><th>Áp dụng cho</th><th>Lượt/ngày</th><th>Token/ngày</th><th>Token/tháng</th><th>Ngân sách trả phí/tháng</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{data!.overrides.map((o) => <OverrideRow key={o.id} o={o} onDone={(v) => setData(v)}/>)}</tbody></table>}
    </Card>
    {adding ? <OverrideDialog onClose={() => setAdding(false)} onSaved={() => { setAdding(false); reload(); }}/> : null}
  </>);
}

const SCOPE_LABEL = { USER: "Người dùng", WORKSPACE: "Không gian làm việc", PROJECT: "Ứng dụng" } as const;
function OverrideRow({ o, onDone }: { o: AiOverride; onDone: (v: import("@/lib/http-types").AiLimitsView) => void }) {
  const [err, setErr] = useState<string | null>(null);
  async function del() { if (!(await confirm({ title: "Xóa hạn mức riêng này?", message: `${o.scopeLabel ?? o.scopeId} sẽ quay về hạn mức mặc định.`, confirmLabel: "Xóa hạn mức", danger: true }))) return; try { onDone(await api.admin.deleteAiOverride(o.id)); } catch (e) { setErr(errText(e, "Chưa xóa được.")); } }
  return <tr><td><b>{o.scopeLabel ?? o.scopeId}</b><small>{SCOPE_LABEL[o.scopeType]}</small></td>
    <td>{o.requestsPerDay == null ? <span className="muted">Theo mặc định</span> : countLimit(o.requestsPerDay)}</td>
    <td>{o.tokensPerDay == null ? <span className="muted">Theo mặc định</span> : countLimit(o.tokensPerDay)}</td>
    <td>{o.tokensPerMonth == null ? <span className="muted">Theo mặc định</span> : countLimit(o.tokensPerMonth)}</td>
    <td>{o.paidBudgetMonth == null ? <span className="muted">Theo mặc định</span> : budgetLimit(o.paidBudgetMonth)}</td>
    <td><button className="btn sm danger" onClick={() => void del()}>Xóa</button>{err ? <small className="formError">{err}</small> : null}</td></tr>;
}

/** Create/replace an exception. With `fixed`, the target is given (the user detail page); otherwise the admin searches for it. */
export function OverrideDialog({ fixed, current, onClose, onSaved }: { fixed?: { scopeType: "USER" | "WORKSPACE" | "PROJECT"; scopeId: string; label: string }; current?: AiOverride | null; onClose: () => void; onSaved: () => void }) {
  const [scopeType, setScopeType] = useState<"USER" | "WORKSPACE" | "PROJECT">(fixed?.scopeType ?? "USER");
  const [q, setQ] = useState(""); const [target, setTarget] = useState<{ id: string; label: string } | null>(fixed ? { id: fixed.scopeId, label: fixed.label } : null);
  const found = useLoad(async () => {
    if (fixed || q.trim().length < 2) return [] as { id: string; label: string }[];
    if (scopeType === "USER") return (await api.admin.users(0, q.trim())).items.map((u) => ({ id: u.id, label: `${u.displayName ?? u.username} (${u.username})` }));
    if (scopeType === "WORKSPACE") return (await api.admin.workspaces(0, q.trim())).items.map((w) => ({ id: w.id, label: w.name }));
    return (await api.admin.applications({ page: 0, q: q.trim() })).items.map((a) => ({ id: a.id, label: `${a.name} — ${a.workspaceName}` }));
  }, [q, scopeType, fixed]);
  const [v, setV] = useState({ rpd: current?.requestsPerDay?.toString() ?? "", tpd: current?.tokensPerDay?.toString() ?? "", tpm: current?.tokensPerMonth?.toString() ?? "", budget: current?.paidBudgetMonth?.toString() ?? "" });
  const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  const fields = { USER: ["rpd", "tpd", "budget"], WORKSPACE: ["rpd", "tpd", "tpm", "budget"], PROJECT: ["rpd", "tpm"] }[scopeType];
  const num0 = (s: string) => (s.trim() === "" ? null : Number(s));
  const once = useSingleFlight();
  function submit(e: FormEvent) {
    e.preventDefault(); if (!target) { setError("Hãy chọn đối tượng áp dụng."); return; }
    void once(async () => {
    setBusy(true); setError(null);
    try {
      await api.admin.setAiOverride({ scopeType, scopeId: target.id, requestsPerDay: fields.includes("rpd") ? num0(v.rpd) : null, tokensPerDay: fields.includes("tpd") ? num0(v.tpd) : null,
        tokensPerMonth: fields.includes("tpm") ? num0(v.tpm) : null, paidBudgetMonth: fields.includes("budget") ? num0(v.budget) : null });
      onSaved();
    } catch (err) { setError(errText(err, "Chưa lưu được hạn mức riêng.")); } finally { setBusy(false); }
    });
  }
  const L = { rpd: "Lượt AI / ngày", tpd: "Lượng AI (token) / ngày", tpm: "Lượng AI (token) / tháng", budget: "Ngân sách AI trả phí / tháng (USD)" } as const;
  const H = { rpd: "0 = Không giới hạn", tpd: "0 = Không giới hạn", tpm: "0 = Không giới hạn", budget: "0 = Chưa cấp ngân sách" } as const;
  return (
    <Modal label="Thiết lập hạn mức riêng" onClose={onClose}>
      <form className="modalBody" onSubmit={(e) => void submit(e)}>
        <h2>Thiết lập hạn mức riêng{fixed ? ` cho ${fixed.label}` : ""}</h2>
        {!fixed ? <>
          <label className="field"><span>Áp dụng cho</span><select value={scopeType} onChange={(e) => { setScopeType(e.target.value as typeof scopeType); setTarget(null); setQ(""); }}>
            {(Object.keys(SCOPE_LABEL) as (keyof typeof SCOPE_LABEL)[]).map((k) => <option key={k} value={k}>{SCOPE_LABEL[k]}</option>)}</select></label>
          {target ? <p>Đã chọn: <b>{target.label}</b> <button type="button" className="btn sm" onClick={() => setTarget(null)}>Đổi</button></p> : <>
            <label className="field"><span>Tìm {SCOPE_LABEL[scopeType].toLowerCase()}</span><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Nhập ít nhất 2 ký tự"/></label>
            <ul className="pickList">{(found.data ?? []).map((r) => <li key={r.id}><button type="button" className="btn sm" onClick={() => setTarget(r)}>{r.label}</button></li>)}</ul></>}
        </> : null}
        {fields.map((k) => <label key={k} className="field"><span>{L[k as keyof typeof L]}</span><input type="number" min="0" step={k === "budget" ? "0.01" : "1"} value={v[k as keyof typeof v]} onChange={(e) => setV({ ...v, [k]: e.target.value })} placeholder="Theo mặc định"/>
          <small>{v[k as keyof typeof v].trim() === "" ? "Để trống = theo mặc định của công ty" : Number(v[k as keyof typeof v]) === 0 ? H[k as keyof typeof H].replace("0 = ", "") : ""}</small></label>)}
        {error ? <p className="formError" role="alert">{error}</p> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" disabled={busy} aria-busy={busy || undefined}>{busy ? "Đang lưu…" : "Lưu"}</button></div>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ user detail
export function UserAiCard({ userId, name }: { userId: string; name: string }) {
  const { data, error, loading, reload } = useLoad(() => api.admin.aiUserView(userId), [userId]);
  const [edit, setEdit] = useState(false); const [msg, setMsg] = useState<string | null>(null);
  if (loading && !data) return <Card title="AI của người này"><StateView kind="loading"/></Card>;
  if (error) return <Card title="AI của người này"><ErrorState error={error} retry={reload}/></Card>;
  const v = data!; const L = v.limits;
  const line = (l: { value: number; source: keyof typeof SOURCE_LABEL }, fmt: (n: number) => string) => <>{fmt(l.value)} <small className="muted">· {SOURCE_LABEL[l.source]}</small></>;
  async function clear() {
    if (!v.override || !(await confirm({ title: "Xóa hạn mức riêng của người này?", message: "Họ sẽ quay về hạn mức mặc định.", confirmLabel: "Xóa hạn mức", danger: true }))) return;
    try { await api.admin.deleteAiOverride(v.override.id); setMsg("Đã xóa hạn mức riêng."); reload(); } catch (e) { setMsg(errText(e, "Chưa xóa được.")); }
  }
  return (
    <Card title="AI của người này" actions={<div className="row"><button className="btn primary" onClick={() => setEdit(true)}>Thiết lập hạn mức riêng</button>{v.override ? <button className="btn" onClick={() => void clear()}>Xóa hạn mức riêng</button> : null}</div>}>
      {msg ? <p className="notice" role="status">{msg}</p> : null}
      <table className="table"><tbody>
        <tr><th scope="row">AI được phép sử dụng</th><td>{v.allowedModels.length ? v.allowedModels.map((m) => `${m.name}${m.paid ? " (trả phí)" : ""}`).join(", ") : "Chưa có mô hình nào được bật — AI đang ở chế độ thử nghiệm."}</td></tr>
        <tr><th scope="row">Mô hình hiệu lực</th><td>{v.effectiveDefaultModel}</td></tr>
        <tr><th scope="row">Lượt đã dùng hôm nay</th><td>{num(v.requestsToday)} / {line(L.requestsPerDay, countLimit)}</td></tr>
        <tr><th scope="row">Lượng AI (token) đã dùng</th><td>Hôm nay {num(v.tokensToday)} / {line(L.tokensPerDay, countLimit)} · Tháng này {num(v.tokensThisMonth)}</td></tr>
        <tr><th scope="row">Ngân sách trả phí đã dùng (tháng)</th><td>{usd(v.paidSpentThisMonthUsd)} / {line(L.paidBudgetUserMonth, budgetLimit)}</td></tr>
        <tr><th scope="row">Hạn mức theo không gian làm việc</th><td>Token/tháng: {line(L.tokensPerMonthWorkspace, countLimit)} · Ngân sách trả phí/tháng: {line(L.paidBudgetWorkspaceMonth, budgetLimit)}</td></tr>
      </tbody></table>
      {edit ? <OverrideDialog fixed={{ scopeType: "USER", scopeId: userId, label: name }} current={v.override} onClose={() => setEdit(false)} onSaved={() => { setEdit(false); setMsg("Đã lưu hạn mức riêng."); reload(); }}/> : null}
    </Card>
  );
}
