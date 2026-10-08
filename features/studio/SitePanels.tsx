"use client";
// Website structure (stage G): pages + SEO, navigation, 404 page, form submissions, custom domains.
import { useCallback, useEffect, useState } from "react";
import { X } from "@xweb/ui";
import { api, ApiError } from "@/lib/http-api";
import type { FormSubmission, NavLink, PageSchema, SchemaOperation, SiteDomain } from "@/lib/http-types";
import { ago, errText, StateView } from "../ui";
import { Drawer, Field } from "./drawers";

const slugify = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "d").toLowerCase()
  .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60);

/** page selector above the outline: home + extra pages */
export function PageBar({ schema, pageId, onPage, onAdd, canEdit }: { schema: PageSchema; pageId: string; onPage: (id: string) => void; onAdd: () => void; canEdit: boolean }) {
  return <div className="pageBar">
    <label className="srOnly" htmlFor="page-select">Trang đang sửa</label>
    <select id="page-select" value={pageId} onChange={(e) => onPage(e.target.value)}>
      <option value="home">Trang chủ (/)</option>
      {(schema.pages ?? []).map((p) => <option key={p.id} value={p.id}>{p.title} (/{p.slug}/)</option>)}
    </select>
    {canEdit ? <button type="button" className="smallButton" onClick={onAdd}>＋ Trang</button> : null}
  </div>;
}

type Apply = (ops: SchemaOperation[], summary: string) => Promise<boolean>;

function PagesSection({ schema, pageId, onPage, canEdit, apply }: { schema: PageSchema; pageId: string; onPage: (id: string) => void; canEdit: boolean; apply: Apply }) {
  const pages = schema.pages ?? [];
  const current = pageId === "home" ? null : pages.find((p) => p.id === pageId) ?? null;
  const seo = current ? current.seo ?? {} : schema.site?.home?.seo ?? {};
  const [title, setTitle] = useState(current?.title ?? schema.site?.home?.title ?? ""); const [slug, setSlug] = useState(current?.slug ?? "");
  const [seoTitle, setSeoTitle] = useState(seo.title ?? ""); const [desc, setDesc] = useState(seo.description ?? ""); const [noindex, setNoindex] = useState(seo.noindex === true);
  const [newTitle, setNewTitle] = useState("");
  useEffect(() => { const s = current ? current.seo ?? {} : schema.site?.home?.seo ?? {};
    setTitle(current?.title ?? schema.site?.home?.title ?? ""); setSlug(current?.slug ?? ""); setSeoTitle(s.title ?? ""); setDesc(s.description ?? ""); setNoindex(s.noindex === true);
  }, [pageId]); // eslint-disable-line react-hooks/exhaustive-deps
  async function save() {
    const props: Record<string, unknown> = { title: title.trim(), seo: { ...(seoTitle.trim() ? { title: seoTitle.trim() } : {}), ...(desc.trim() ? { description: desc.trim() } : {}), noindex } };
    if (current) props.slug = slug.trim();
    await apply([{ type: "UPDATE_PAGE", pageId, props }], `Cập nhật trang ${title.trim() || "chủ"}`);
  }
  async function add() {
    const t = newTitle.trim(); if (!t) return;
    let s = slugify(t) || "trang"; let n = 2; while (pages.some((p) => p.slug === s)) s = `${slugify(t)}-${n++}`;
    const id = `p-${Math.random().toString(36).slice(2, 8)}`;
    if (await apply([{ type: "ADD_PAGE", pageId: id, props: { slug: s, title: t } }], `Thêm trang ${t}`)) { setNewTitle(""); onPage(id); }
  }
  async function remove() {
    if (!current || !confirm(`Xoá trang “${current.title}” cùng các phần của nó? Liên kết điều hướng tới trang này cũng bị xoá.`)) return;
    if (await apply([{ type: "REMOVE_PAGE", pageId: current.id }], `Xoá trang ${current.title}`)) onPage("home");
  }
  return <section className="settingGroup"><h3>Trang</h3>
    <ul className="plainList">{[{ id: "home", title: schema.site?.home?.title || "Trang chủ", slug: "" }, ...pages].map((p) =>
      <li key={p.id} className="row between"><button type="button" className={`linkButton${p.id === pageId ? " active" : ""}`} onClick={() => onPage(p.id)}>{p.title}</button><span className="code">/{p.slug}{p.slug ? "/" : ""}</span></li>)}</ul>
    {canEdit ? <form className="row" onSubmit={(e) => { e.preventDefault(); void add(); }}>
      <input aria-label="Tên trang mới" placeholder="Tên trang mới (ví dụ Giới thiệu)" maxLength={80} value={newTitle} onChange={(e) => setNewTitle(e.target.value)}/>
      <button className="button" disabled={!newTitle.trim() || pages.length >= 20}>Thêm trang</button></form> : null}
    <h4>{current ? `Trang “${current.title}”` : "Trang chủ"}: tiêu đề & SEO</h4>
    <Field label="Tiêu đề trang"><input maxLength={80} value={title} disabled={!canEdit} onChange={(e) => setTitle(e.target.value)}/></Field>
    {current ? <Field label="Đường dẫn (slug)"><input maxLength={60} value={slug} disabled={!canEdit} onChange={(e) => setSlug(slugify(e.target.value))}/></Field> : null}
    <Field label="Tiêu đề SEO"><input maxLength={70} value={seoTitle} disabled={!canEdit} onChange={(e) => setSeoTitle(e.target.value)}/></Field>
    <Field label="Mô tả SEO"><input maxLength={160} value={desc} disabled={!canEdit} onChange={(e) => setDesc(e.target.value)}/></Field>
    <label className="switch"><input type="checkbox" checked={noindex} disabled={!canEdit} onChange={(e) => setNoindex(e.target.checked)}/> Không cho công cụ tìm kiếm lập chỉ mục</label>
    {canEdit ? <div className="drawerActions">{current ? <button className="button ghost" onClick={() => void remove()}>Xoá trang</button> : null}
      <button className="button primary" disabled={!title.trim() && !!current} onClick={() => void save()}>Lưu trang</button></div> : null}
  </section>;
}

function NavigationSection({ schema, canEdit, apply }: { schema: PageSchema; canEdit: boolean; apply: Apply }) {
  const [links, setLinks] = useState<NavLink[]>(schema.site?.navigation ?? []);
  useEffect(() => { setLinks(schema.site?.navigation ?? []); }, [schema.site?.navigation]);
  const kind = (l: NavLink) => (l.pageId ? "page" : l.url ? "url" : "anchor");
  function set(i: number, patch: Partial<NavLink>) { setLinks((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l))); }
  function setKind(i: number, k: string) { setLinks((ls) => ls.map((l, j) => (j === i ? { id: l.id, label: l.label, ...(k === "page" ? { pageId: "home" } : k === "url" ? { url: "https://" } : { anchor: "#contact" }) } : l))); }
  const pages = [{ id: "home", title: "Trang chủ" }, ...(schema.pages ?? [])];
  return <section className="settingGroup"><h3>Điều hướng</h3>
    <p className="hint">Hiện trên thanh điều hướng của mọi trang. Liên kết tới trang của website, tới một phần (#id) hoặc tới tên miền ngoài đã được quản trị viên duyệt (chỉ https).</p>
    <ul className="plainList">{links.map((l, i) => <li key={l.id} className="navRow">
      <input aria-label="Nhãn" value={l.label} maxLength={40} disabled={!canEdit} onChange={(e) => set(i, { label: e.target.value })}/>
      <select aria-label="Loại liên kết" value={kind(l)} disabled={!canEdit} onChange={(e) => setKind(i, e.target.value)}><option value="page">Trang</option><option value="anchor">Phần trên trang</option><option value="url">Liên kết ngoài</option></select>
      {kind(l) === "page" ? <select aria-label="Trang đích" value={l.pageId} disabled={!canEdit} onChange={(e) => set(i, { pageId: e.target.value })}>{pages.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}</select>
        : <input aria-label={kind(l) === "url" ? "URL" : "Neo"} value={kind(l) === "url" ? l.url ?? "" : l.anchor ?? ""} maxLength={500} disabled={!canEdit}
          onChange={(e) => set(i, kind(l) === "url" ? { url: e.target.value } : { anchor: e.target.value })}/>}
      {canEdit ? <button type="button" className="smallButton" aria-label={`Xoá liên kết ${l.label}`} onClick={() => setLinks((ls) => ls.filter((_, j) => j !== i))}><X size={14} aria-hidden="true"/></button> : null}
    </li>)}</ul>
    {canEdit ? <div className="drawerActions">
      <button className="button ghost" disabled={links.length >= 12} onClick={() => setLinks((ls) => [...ls, { id: `n-${Math.random().toString(36).slice(2, 7)}`, label: "Liên kết", pageId: "home" }])}>＋ Liên kết</button>
      <button className="button primary" disabled={links.some((l) => !l.label.trim())} onClick={() => void apply([{ type: "SET_NAVIGATION", value: links.map((l) => ({ ...l, label: l.label.trim() })) }], "Cập nhật điều hướng")}>Lưu điều hướng</button>
    </div> : null}
  </section>;
}

function NotFoundSection({ schema, canEdit, apply }: { schema: PageSchema; canEdit: boolean; apply: Apply }) {
  const [title, setTitle] = useState(schema.site?.notFound?.title ?? ""); const [message, setMessage] = useState(schema.site?.notFound?.message ?? "");
  return <section className="settingGroup"><h3>Trang 404</h3>
    <Field label="Tiêu đề"><input maxLength={80} placeholder="Không tìm thấy trang" value={title} disabled={!canEdit} onChange={(e) => setTitle(e.target.value)}/></Field>
    <Field label="Lời nhắn"><input maxLength={300} placeholder="Trang bạn tìm không tồn tại…" value={message} disabled={!canEdit} onChange={(e) => setMessage(e.target.value)}/></Field>
    {canEdit ? <div className="drawerActions"><button className="button" onClick={() => void apply([{ type: "UPDATE_SITE", props: { notFound: { ...(title.trim() ? { title: title.trim() } : {}), ...(message.trim() ? { message: message.trim() } : {}) } } }], "Cập nhật trang 404")}>Lưu</button></div> : null}
  </section>;
}

function FormsSection({ ws, pid }: { ws: string; pid: string }) {
  const [data, setData] = useState<{ items: FormSubmission[]; total: number } | null>(null); const [err, setErr] = useState<string | null>(null);
  const load = useCallback(() => api.formSubmissions(ws, pid).then(setData).catch((e) => setErr(e instanceof ApiError && e.status === 403 ? "Chỉ người chỉnh sửa ứng dụng mới xem được dữ liệu form." : errText(e, "Không tải được."))), [ws, pid]);
  useEffect(() => { void load(); }, [load]);
  async function del(s: FormSubmission) { if (!confirm("Xoá tin gửi này?")) return; try { await api.deleteFormSubmission(ws, pid, s.id); void load(); } catch (e) { setErr(errText(e, "Không xoá được.")); } }
  return <section className="settingGroup"><h3>Form gửi về</h3>
    <p className="hint">Tin gửi từ form liên hệ trên website đã xuất bản (công khai). Dữ liệu cá nhân: chỉ người chỉnh sửa xem được, tự xoá sau thời hạn lưu giữ của công ty.</p>
    {err ? <p className="formError" role="alert">{err}</p> : !data ? <StateView kind="loading"/> : data.items.length === 0 ? <p className="hint">Chưa có tin gửi nào.</p> : <>
      <p><a className="button ghost" href={api.formExportUrl(ws, pid)} download>Tải CSV ({data.total})</a></p>
      <ul className="plainList">{data.items.map((s) => <li key={s.id} className="submission">
        <div className="row between"><b>{s.data.name}</b><small>{ago(s.createdAt)}</small></div>
        <div className="code">{s.data.email}{s.data.phone ? ` · ${s.data.phone}` : ""}</div><p>{s.data.message}</p>
        <button type="button" className="smallButton" onClick={() => void del(s)}>Xoá</button></li>)}</ul></>}
  </section>;
}

const DOMAIN_STATUS: Record<SiteDomain["status"], string> = { PENDING: "Chờ xác minh", VERIFIED: "Đã xác minh", FAILED: "Chưa xác minh được" };
const TLS_STATUS: Record<SiteDomain["tlsStatus"], string> = { UNKNOWN: "Chưa kiểm tra", PENDING: "Chưa có HTTPS", ACTIVE: "HTTPS hoạt động", ERROR: "Chứng chỉ lỗi" };

function DomainsSection({ ws, pid, canPublish }: { ws: string; pid: string; canPublish: boolean }) {
  const [list, setList] = useState<SiteDomain[] | null>(null); const [host, setHost] = useState(""); const [err, setErr] = useState<string | null>(null); const [busy, setBusy] = useState<string | null>(null);
  const load = useCallback(() => api.domains(ws, pid).then(setList).catch((e) => setErr(errText(e, "Không tải được."))), [ws, pid]);
  useEffect(() => { void load(); }, [load]);
  async function act(id: string, fn: () => Promise<unknown>) { setBusy(id); setErr(null); try { await fn(); await load(); } catch (e) { setErr(errText(e, "Không thực hiện được.")); } finally { setBusy(null); } }
  return <section className="settingGroup"><h3>Tên miền riêng</h3>
    <p className="hint">Chỉ cho website công khai. Bạn chứng minh quyền sở hữu bằng một bản ghi DNS TXT; hệ thống không bao giờ hỏi mật khẩu DNS. HTTPS do lớp CDN/tunnel phía trước cung cấp; trạng thái là kết quả kiểm tra thật.</p>
    {canPublish ? <form className="row" onSubmit={(e) => { e.preventDefault(); if (host.trim()) void act("add", async () => { await api.addDomain(ws, pid, host.trim()); setHost(""); }); }}>
      <input aria-label="Tên miền" placeholder="www.ten-mien-cua-ban.vn" value={host} onChange={(e) => setHost(e.target.value)}/><button className="button" disabled={!host.trim() || busy !== null}>Thêm</button></form> : null}
    {err ? <p className="formError" role="alert">{err}</p> : null}
    {!list ? <StateView kind="loading"/> : list.length === 0 ? <p className="hint">Chưa có tên miền riêng.</p> : <ul className="plainList">{list.map((d) => <li key={d.id} className="domainRow">
      <div className="row between"><b className="code">{d.hostname}</b><span>{DOMAIN_STATUS[d.status]} · {TLS_STATUS[d.tlsStatus]}</span></div>
      {d.status !== "VERIFIED" ? <div className="hint">Tạo bản ghi DNS: <code>TXT</code> <code className="breakAll">{d.txtName}</code> = <code className="breakAll">{d.txtValue}</code>, và <code>CNAME</code> <code>{d.hostname}</code> → <code>{d.cnameTarget}</code></div> : null}
      {d.lastError ? <small className="formError">{d.lastError}</small> : null}
      {canPublish ? <div className="row">
        {d.status !== "VERIFIED" ? <button type="button" className="smallButton" disabled={busy !== null} onClick={() => void act(d.id, () => api.verifyDomain(ws, pid, d.id))}>Kiểm tra DNS</button>
          : <button type="button" className="smallButton" disabled={busy !== null} onClick={() => void act(d.id, () => api.checkDomainTls(ws, pid, d.id))}>Kiểm tra HTTPS</button>}
        <button type="button" className="smallButton" disabled={busy !== null} onClick={() => { if (confirm(`Gỡ tên miền ${d.hostname}?`)) void act(d.id, () => api.removeDomain(ws, pid, d.id)); }}>Gỡ</button>
      </div> : null}</li>)}</ul>}
  </section>;
}

export function SiteDrawer({ schema, ws, pid, pageId, onPage, canEdit, canPublish, apply, onClose }: {
  schema: PageSchema; ws: string; pid: string; pageId: string; onPage: (id: string) => void; canEdit: boolean; canPublish: boolean; apply: Apply; onClose: () => void;
}) {
  return <Drawer title="Website" sub="Các trang, điều hướng, trang 404, form gửi về và tên miền. Mỗi lần xuất bản là một bản chụp nguyên vẹn của tất cả các trang." onClose={onClose} wide>
    <PagesSection schema={schema} pageId={pageId} onPage={onPage} canEdit={canEdit} apply={apply}/>
    <NavigationSection schema={schema} canEdit={canEdit} apply={apply}/>
    <NotFoundSection schema={schema} canEdit={canEdit} apply={apply}/>
    {canEdit ? <FormsSection ws={ws} pid={pid}/> : null}
    <DomainsSection ws={ws} pid={pid} canPublish={canPublish}/>
  </Drawer>;
}
