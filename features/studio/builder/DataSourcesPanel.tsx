"use client";
/**
 * Data sources of the workspace + the TEST / LIVE binding of each slot of this application (C3 Management API, MANAGEMENT_API.md @ e606465).
 * Not verified against a running backend. Rules: no secret is ever shown or kept (password inputs are write-only and cleared right after the request),
 * every write has a pending lock (no double submit), an ambiguous write asks for a reload before anything is repeated, and nothing is simulated:
 * with no host (`calls` absent) or a server that answers "not mounted" the panel says "Chưa sẵn sàng".
 */
import { useCallback, useEffect, useRef, useState } from "react";
import type { AppDefinitionV2, BindingMode, ConnectorDescriptor, CredentialMetadata, DataBinding, DataSourceView } from "@xweb/types";
import {
  BINDING_LABEL, bindingFor, checkSourceForm, compactMap, credentialView, explainManagementError, managementReadinessFromError, needsReload, slotsOf, testResultView, unboundLive,
  type DataManagementCalls, type ManagementMessage, type SourceForm, type TestView,
} from "./core/dataManagement";
import { notReady, type Readiness } from "./core/readiness";
import { Dialog, Field, StateBox } from "./ui/primitives";

type Load = { state: "loading" } | { state: "ready" } | { state: "error"; message: ManagementMessage } | { state: "gate"; gate: Readiness };
const emptyForm = (): SourceForm => ({ name: "", type: "", config: {}, credential: {} });

export function DataSourcesPanel({ doc, calls, canManage, manageReason }: { doc: AppDefinitionV2; calls?: DataManagementCalls; canManage: boolean; manageReason: string }) {
  const [load, setLoad] = useState<Load>({ state: "loading" });
  const [connectors, setConnectors] = useState<ConnectorDescriptor[]>([]);
  const [sources, setSources] = useState<DataSourceView[]>([]);
  const [bindings, setBindings] = useState<DataBinding[]>([]);
  const [creds, setCreds] = useState<Record<string, CredentialMetadata>>({});
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  const [notice, setNotice] = useState<Record<string, ManagementMessage | string>>({});
  const [tests, setTests] = useState<Record<string, TestView>>({});
  const [form, setForm] = useState<SourceForm>(emptyForm());
  const [formErrors, setFormErrors] = useState<{ field: string; message: string }[]>([]);
  const [credInput, setCredInput] = useState<Record<string, Record<string, string>>>({});
  const [pick, setPick] = useState<Record<string, string>>({});
  const [removing, setRemoving] = useState<DataSourceView | null>(null);
  const alive = useRef(true);
  const inflight = useRef<Set<string>>(new Set());          // synchronous double-submit guard (state updates are async)
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const lockOn = (k: string) => { inflight.current.add(k); setPending(new Set(inflight.current)); };
  const lockOff = (k: string) => { inflight.current.delete(k); if (alive.current) setPending(new Set(inflight.current)); };
  const say = (k: string, m: ManagementMessage | string | null) => { if (!alive.current) return; setNotice((n) => { const c = { ...n }; if (m === null) delete c[k]; else c[k] = m; return c; }); };

  const reload = useCallback(async () => {
    if (!calls) return;
    setLoad({ state: "loading" });
    try {
      const [cs, ds, bs] = await Promise.all([calls.connectors(), calls.list(), calls.listBindings()]);
      const meta: Record<string, CredentialMetadata> = {};
      await Promise.all(ds.filter((d) => d.hasCredential).map(async (d) => { try { meta[d.id] = await calls.credential(d.id); } catch { /* the badge falls back to hasCredential */ } }));
      if (!alive.current) return;
      setConnectors(cs); setSources(ds); setBindings(bs); setCreds(meta); setLoad({ state: "ready" });
    } catch (e) {
      if (!alive.current) return;
      const gate = managementReadinessFromError(e);
      setLoad(gate ? { state: "gate", gate } : { state: "error", message: explainManagementError(e) });
    }
  }, [calls]);
  useEffect(() => { void reload(); }, [reload]);

  if (!calls) return <StateBox state={notReady("Chưa kết nối máy chủ để quản lý nguồn dữ liệu. Không có nguồn nào được giả lập ở trình duyệt.")}/>;
  if (load.state === "gate") return <StateBox state={load.gate}/>;
  if (load.state === "loading") return <StateBox state={{ state: "LOADING" }}/>;
  if (load.state === "error") {
    return (<div className="bx-state bx-state-error" role="alert" data-testid="ds-error"><b>{load.message.title}</b><p>{load.message.detail}</p>
      <button type="button" className="smallButton" data-testid="ds-reload" onClick={() => void reload()}>Thử lại</button></div>);
  }

  const descriptor = connectors.find((c) => c.type === form.type);
  const writes = !canManage ? manageReason : null;
  const busy = (k: string) => pending.has(k);

  /** one funnel for every write: lock → call → reload on ambiguity → unlock. Secrets never reach `say`. */
  async function run<T>(key: string, fn: () => Promise<T>, ok?: (r: T) => void, explain: (e: unknown) => ManagementMessage = (e) => explainManagementError(e, { write: true })): Promise<boolean> {
    if (inflight.current.has(key)) return false;
    lockOn(key); say(key, null);
    try { const r = await fn(); ok?.(r); return true; }
    catch (e) {
      const m = explain(e); say(key, m);
      if (needsReload(m)) { const c = calls!; try { const [ds, bs] = await Promise.all([c.list(), c.listBindings()]); if (alive.current) { setSources(ds); setBindings(bs); } } catch { /* the notice already tells the user to reload */ } }
      return false;
    } finally { lockOff(key); }
  }

  async function create() {
    const errs = checkSourceForm(form, descriptor, { requireCredential: !!descriptor?.credentialKeys.length });
    setFormErrors(errs); if (errs.length) return;
    const body = { name: form.name, type: form.type, config: compactMap(form.config, true), ...(Object.keys(compactMap(form.credential, false)).length ? { credential: compactMap(form.credential, false) } : {}) };
    setForm((f) => ({ ...f, credential: {} }));                         // secrets leave the page state as soon as they are sent
    await run("create", () => calls!.create(body), (d) => { setSources((s) => [...s, d]); setForm(emptyForm()); say("create", `Đã tạo nguồn “${d.name}”. Hãy nhập khóa kết nối nếu chưa có, rồi kiểm tra kết nối.`); });
  }
  async function replaceCredential(d: DataSourceView, desc?: ConnectorDescriptor) {
    const typed = credInput[d.id] ?? {};
    const errs = checkSourceForm({ name: d.name, type: d.type, config: d.config, credential: typed }, desc, { requireCredential: true }).filter((e) => e.field === "credential");
    if (errs.length) { say(`cred:${d.id}`, { kind: "invalid", title: "Khóa kết nối chưa đủ", detail: errs[0].message, retrySafe: true }); return; }
    const body = compactMap(typed, false);
    setCredInput((c) => ({ ...c, [d.id]: {} }));
    await run(`cred:${d.id}`, () => calls!.setCredential(d.id, body), (m) => { setCreds((c) => ({ ...c, [d.id]: m })); setSources((s) => s.map((x) => (x.id === d.id ? { ...x, hasCredential: m.configured } : x))); say(`cred:${d.id}`, "Đã cập nhật khóa kết nối. Giá trị được giấu."); });
  }
  async function dropCredential(d: DataSourceView) {
    await run(`cred:${d.id}`, () => calls!.removeCredential(d.id), () => { setCreds((c) => { const n = { ...c }; delete n[d.id]; return n; }); setSources((s) => s.map((x) => (x.id === d.id ? { ...x, hasCredential: false } : x))); say(`cred:${d.id}`, "Đã xóa khóa kết nối."); });
  }
  async function testIt(d: DataSourceView) {
    setTests((t) => { const n = { ...t }; delete n[d.id]; return n; });
    await run(`test:${d.id}`, () => calls!.test(d.id), (r) => setTests((t) => ({ ...t, [d.id]: testResultView(r) })));
  }
  async function toggle(d: DataSourceView) {
    await run(`ds:${d.id}`, () => calls!.update(d.id, { status: d.status === "ACTIVE" ? "DISABLED" : "ACTIVE" }), (u) => setSources((s) => s.map((x) => (x.id === u.id ? u : x))));
  }
  async function doRemove() {
    if (!removing) return;
    const d = removing;
    const ok = await run(`del:${d.id}`, () => calls!.remove(d.id), () => { setSources((s) => s.filter((x) => x.id !== d.id)); setBindings((b) => b.filter((x) => x.dataSourceId !== d.id)); },
      (e) => { const m = explainManagementError(e, { write: true }); return m.kind === "conflict" ? { ...m, detail: "Nguồn đang được liên kết với một ứng dụng. Hãy bỏ liên kết ở mục “Khe dữ liệu” rồi xóa lại." } : m; });
    if (ok) setRemoving(null);
  }
  async function bind(mode: BindingMode, slot: string) {
    const target = pick[`${mode}:${slot}`]; if (!target) { say(`bind:${mode}:${slot}`, { kind: "invalid", title: "Chưa chọn nguồn", detail: "Chọn một nguồn dữ liệu rồi liên kết.", retrySafe: true }); return; }
    await run(`bind:${mode}:${slot}`, () => calls!.bind(mode, slot, target), (b) => setBindings((bs) => [...bs.filter((x) => !(x.mode === b.mode && x.slotId === b.slotId)), b]));
  }
  async function unbind(mode: BindingMode, slot: string) {
    await run(`bind:${mode}:${slot}`, () => calls!.unbind(mode, slot), () => setBindings((bs) => bs.filter((x) => !(x.mode === mode && x.slotId === slot))));
  }

  const slots = slotsOf(doc);
  const noteText = (k: string) => { const n = notice[k]; if (!n) return null; return typeof n === "string" ? <p className="hint" role="status" data-testid={`note:${k}`}>{n}</p> : <p role="alert" className="hint" data-testid={`note:${k}`}><b>{n.title}.</b> {n.detail}</p>; };

  return (
    <div className="bx-ds" data-testid="ds-panel">
      {writes ? <p className="hint" data-testid="ds-readonly">{writes}</p> : null}
      <h3 className="bx-h3">Nguồn dữ liệu của không gian làm việc</h3>
      {sources.length ? (
        <ul className="bx-list" data-testid="ds-list">
          {sources.map((d) => {
            const desc = connectors.find((c) => c.type === d.type); const cv = credentialView(creds[d.id], d.hasCredential); const t = tests[d.id];
            const typed = credInput[d.id] ?? {};
            return (
              <li key={d.id} data-testid={`ds:${d.id}`} className="bx-ds-item">
                <div><b>{d.name}</b> <small>{desc?.displayName ?? d.type} · {d.status === "ACTIVE" ? "Đang bật" : "Đang tắt"}</small></div>
                <p className="hint" data-testid={`cred-state:${d.id}`}>{cv.text}</p>
                <div className="bx-row">
                  <button type="button" className="smallButton" data-testid={`ds-test:${d.id}`} disabled={!!writes || busy(`test:${d.id}`)} aria-busy={busy(`test:${d.id}`)} title={writes ?? undefined} onClick={() => void testIt(d)}>{busy(`test:${d.id}`) ? "Đang kiểm tra kết nối…" : "Kiểm tra kết nối"}</button>
                  <button type="button" className="smallButton" data-testid={`ds-toggle:${d.id}`} disabled={!!writes || busy(`ds:${d.id}`)} title={writes ?? undefined} onClick={() => void toggle(d)}>{d.status === "ACTIVE" ? "Tắt" : "Bật"}</button>
                  <button type="button" className="smallButton danger" data-testid={`ds-delete:${d.id}`} disabled={!!writes || busy(`del:${d.id}`)} title={writes ?? undefined} onClick={() => setRemoving(d)}>Xóa</button>
                </div>
                {t ? <div className={`bx-outcome tone-${t.state === "OK" ? "ok" : t.state === "WARN" ? "warn" : "bad"}`} role={t.state === "FAILED" ? "alert" : "status"} data-test-state={t.state} data-testid={`ds-test-result:${d.id}`}>
                  <b>{t.title}</b><p>{t.detail}</p>{t.code ? <p className="hint">Mã: {t.code}</p> : null}
                  {t.warnings.length ? <ul>{t.warnings.map((w, i) => <li key={i} data-testid={`ds-warning:${d.id}`}>{w}</li>)}</ul> : null}</div> : null}
                {noteText(`test:${d.id}`)}{noteText(`ds:${d.id}`)}{removing?.id === d.id ? null : noteText(`del:${d.id}`)}
                <fieldset className="bx-group" disabled={!!writes}><legend>Khóa kết nối (chỉ ghi, không xem lại)</legend>
                  {(desc?.credentialKeys ?? []).map((k) => (
                    <Field key={k} label={k}>{(id) => <input id={id} type="password" autoComplete="new-password" data-testid={`cred-input:${d.id}:${k}`} value={typed[k] ?? ""} onChange={(e) => setCredInput((c) => ({ ...c, [d.id]: { ...(c[d.id] ?? {}), [k]: e.target.value } }))}/>}</Field>
                  ))}
                  <div className="bx-row">
                    <button type="button" className="smallButton" data-testid={`cred-save:${d.id}`} disabled={!!writes || busy(`cred:${d.id}`) || !desc?.credentialKeys.length} onClick={() => void replaceCredential(d, desc)}>{cv.configured ? "Thay khóa kết nối" : "Lưu khóa kết nối"}</button>
                    {cv.configured ? <button type="button" className="smallButton danger" data-testid={`cred-remove:${d.id}`} disabled={!!writes || busy(`cred:${d.id}`)} onClick={() => void dropCredential(d)}>Xóa khóa kết nối</button> : null}
                  </div>
                  {noteText(`cred:${d.id}`)}
                </fieldset>
              </li>
            );
          })}
        </ul>
      ) : <p className="hint" data-testid="ds-empty">Chưa có nguồn dữ liệu nào trong không gian làm việc này.</p>}

      <form className="bx-form" data-testid="ds-create-form" onSubmit={(e) => { e.preventDefault(); void create(); }}>
        <h3 className="bx-h3">Thêm nguồn dữ liệu</h3>
        <Field label="Tên nguồn">{(id) => <input id={id} disabled={!!writes} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}/>}</Field>
        <Field label="Loại">{(id) => <select id={id} disabled={!!writes} value={form.type} onChange={(e) => setForm({ ...emptyForm(), name: form.name, type: e.target.value })}>
          <option value="">— chọn loại —</option>{connectors.map((c) => <option key={c.type} value={c.type} disabled={c.status !== "AVAILABLE"}>{c.displayName}{c.status !== "AVAILABLE" ? " (sắp có)" : ""}</option>)}</select>}</Field>
        {descriptor ? <>
          {descriptor.notes ? <p className="hint">{descriptor.notes}</p> : null}
          {descriptor.configKeys.map((k) => <Field key={k.name} label={`${k.name}${k.required ? " *" : ""}`} hint={k.description}>{(id) => <input id={id} disabled={!!writes} value={form.config[k.name] ?? ""} onChange={(e) => setForm({ ...form, config: { ...form.config, [k.name]: e.target.value } })}/>}</Field>)}
          {descriptor.credentialKeys.map((k) => <Field key={k} label={`${k} (khóa kết nối)`}>{(id) => <input id={id} type="password" autoComplete="new-password" disabled={!!writes} data-testid={`new-cred:${k}`} value={form.credential[k] ?? ""} onChange={(e) => setForm({ ...form, credential: { ...form.credential, [k]: e.target.value } })}/>}</Field>)}
        </> : null}
        {formErrors.length ? <ul role="alert" className="hint" data-testid="ds-form-errors">{formErrors.map((e, i) => <li key={i}>{e.message}</li>)}</ul> : null}
        {noteText("create")}
        <button type="submit" className="button primary" data-testid="ds-create" disabled={!!writes || busy("create")} aria-busy={busy("create")} title={writes ?? undefined}>{busy("create") ? "Đang tạo…" : "Tạo nguồn"}</button>
      </form>

      <h3 className="bx-h3">Liên kết khe dữ liệu của ứng dụng</h3>
      {slots.length ? <>
        <ul className="bx-list" data-testid="slot-list">
          {slots.map((s) => (
            <li key={s.id} data-testid={`slot:${s.id}`}>
              <b>{s.name}</b> <small>khe “{s.id}”</small>
              {(["TEST", "LIVE"] as const).map((mode) => {
                const cur = bindingFor(bindings, mode, s.id); const k = `bind:${mode}:${s.id}`; const curSrc = sources.find((d) => d.id === cur?.dataSourceId);
                return (
                  <div className="bx-row" key={mode} data-testid={`binding:${mode}:${s.id}`}>
                    <span>{BINDING_LABEL[mode]}: {cur ? <b data-testid={`bound:${mode}:${s.id}`}>{curSrc?.name ?? cur.dataSourceId}</b> : <i>chưa liên kết</i>}</span>
                    <select aria-label={`Nguồn cho ${s.id} (${mode})`} disabled={!!writes || busy(k)} value={pick[`${mode}:${s.id}`] ?? ""} onChange={(e) => setPick({ ...pick, [`${mode}:${s.id}`]: e.target.value })}>
                      <option value="">— chọn nguồn —</option>{sources.filter((d) => d.status === "ACTIVE").map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
                    <button type="button" className="smallButton" data-testid={`bind:${mode}:${s.id}`} disabled={!!writes || busy(k)} aria-busy={busy(k)} title={writes ?? undefined} onClick={() => void bind(mode, s.id)}>{busy(k) ? "Đang lưu…" : cur ? "Đổi liên kết" : "Liên kết"}</button>
                    {cur ? <button type="button" className="smallButton" data-testid={`unbind:${mode}:${s.id}`} disabled={!!writes || busy(k)} onClick={() => void unbind(mode, s.id)}>Bỏ liên kết</button> : null}
                    {noteText(k)}
                  </div>
                );
              })}
            </li>
          ))}
        </ul>
        {unboundLive(doc, bindings).length ? <p className="hint" data-testid="live-unbound">Chưa liên kết LIVE cho: {unboundLive(doc, bindings).join(", ")}. Ứng dụng đã xuất bản sẽ trả lỗi “nguồn chưa được liên kết” khi gọi dữ liệu thật.</p> : null}
      </> : <p className="hint" data-testid="slot-empty">Tài liệu ứng dụng chưa khai báo khe dữ liệu (dataSources[]) và chưa có thao tác nào để thêm khe từ Studio, nên chưa liên kết được. Nhờ C2 bổ sung (xem handoff).</p>}

      {removing ? (
        <Dialog title={`Xóa nguồn “${removing.name}”?`} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="button ghost" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="button primary" data-testid="ds-delete-confirm" disabled={busy(`del:${removing.id}`)} onClick={() => void doRemove()}>Xóa nguồn</button></>}>
          <p>Nguồn và khóa kết nối của nó sẽ bị xóa khỏi không gian làm việc. Nếu nguồn đang được liên kết với một ứng dụng, máy chủ sẽ từ chối: hãy bỏ liên kết trước.</p>
          {noteText(`del:${removing.id}`)}
        </Dialog>
      ) : null}
    </div>
  );
}
