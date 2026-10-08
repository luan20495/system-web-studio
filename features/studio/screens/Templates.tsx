"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/http-api";
import type { TemplateDto } from "@/lib/http-types";
import { useLoad } from "../../useLoad";
import { ago, ErrorState, errText, Pill, StateView } from "../../ui";
import { SchemaThumb } from "../../library";
import { S } from "../base";

const REVIEW_LABEL: Record<TemplateDto["reviewStatus"], [string, string]> = { PRIVATE: ["PRIVATE", "Riêng tư"], SUBMITTED: ["QUEUED", "Đang kiểm tra"], REVIEW: ["UNKNOWN", "Chờ duyệt"],
  APPROVED: ["COMPANY", "Công ty"], ARCHIVED: ["ARCHIVED", "Đã lưu trữ"] };

/** Safe-render preview image when the server has one (JS-free screenshot), otherwise the client-side schema thumbnail. */
function TemplateThumb({ t }: { t: TemplateDto }) {
  const [broken, setBroken] = useState(false);
  return t.previewStatus === "READY" && !broken
    ? <img className="thumb previewImg" src={`/api/v1/templates/${t.id}/preview`} alt={`Ảnh xem trước mẫu ${t.name}`} loading="lazy" onError={() => setBroken(true)}/>
    : <SchemaThumb schema={t.schema} title={`Xem trước mẫu ${t.name}`}/>;
}

function TemplateCard({ t, onUse, onChanged, categories, mine }: { t: TemplateDto; onUse?: (t: TemplateDto) => void; onChanged: () => void; categories: Record<string, string>; mine: boolean }) {
  const [editing, setEditing] = useState(false); const [name, setName] = useState(t.name); const [desc, setDesc] = useState(t.description);
  const [category, setCategory] = useState(t.category); const [tags, setTags] = useState(t.tags.join(", "));
  const [err, setErr] = useState<string | null>(null); const [checks, setChecks] = useState<{ check: string; ok: boolean; message: string }[] | null>(null);
  async function act(fn: () => Promise<unknown>) { setErr(null); try { await fn(); setEditing(false); onChanged(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  async function saveEdit() {
    await act(async () => { await api.updateTemplate(t.id, { name: name.trim(), description: desc.trim() });
      await api.templateCatalog(t.id, { category, tags: tags.split(",").map((x) => x.trim()).filter(Boolean) }); });
  }
  async function submit() { setErr(null); try { const r = await api.submitTemplate(t.id); setChecks(r.passed ? null : r.checks); onChanged(); } catch (x) { setErr(errText(x, "Không gửi được.")); } }
  const [tone, label] = REVIEW_LABEL[t.reviewStatus] ?? ["PRIVATE", t.reviewStatus];
  return <article className="libCard">
    <TemplateThumb t={t}/>
    <div className="row between"><h2>{t.name}</h2><Pill value={tone} label={label}/></div>
    {t.description ? <p>{t.description}</p> : null}
    <div className="chips"><span className="chip">{categories[t.category] ?? t.category}</span>{t.tags.map((x) => <span key={x} className="chip">#{x}</span>)}</div>
    <div className="meta">{t.sections} mục · v{t.version} · {t.author ?? "—"} · {t.usageCount} lần dùng · cập nhật {ago(t.updatedAt)}</div>
    {t.reviewStatus === "PRIVATE" && t.reviewComment ? <p className="notice">Quản trị viên: {t.reviewComment}</p> : null}
    {editing ? <form className="inlineForm" onSubmit={(e) => { e.preventDefault(); void saveEdit(); }}>
      <input aria-label="Tên mẫu" value={name} maxLength={120} onChange={(e) => setName(e.target.value)}/>
      <textarea aria-label="Mô tả mẫu" value={desc} maxLength={500} rows={2} onChange={(e) => setDesc(e.target.value)}/>
      <select aria-label="Danh mục" value={category} onChange={(e) => setCategory(e.target.value)}>{Object.entries(categories).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <input aria-label="Thẻ (phân tách bằng dấu phẩy)" placeholder="Thẻ, phân tách bằng dấu phẩy" value={tags} onChange={(e) => setTags(e.target.value)}/>
      <div className="actions"><button className="btn sm primary" disabled={!name.trim()}>Lưu</button><button type="button" className="btn sm" onClick={() => setEditing(false)}>Hủy</button></div>
    </form> : <div className="actions">
      {onUse ? <button className="btn sm primary" onClick={() => onUse(t)}>Dùng mẫu này</button> : null}
      {t.canEdit ? <><button className="btn sm" onClick={() => setEditing(true)}>Sửa</button>
        {t.reviewStatus === "PRIVATE" ? <button className="btn sm" onClick={() => void submit()}>Gửi duyệt cho công ty</button> : null}
        <button className="btn sm ghost" onClick={() => { if (confirm(`Lưu trữ mẫu “${t.name}”? Ứng dụng đã tạo từ mẫu không bị ảnh hưởng.`)) void act(() => api.archiveTemplate(t.id)); }}>Lưu trữ</button></> : null}
      {t.reviewStatus === "REVIEW" && mine ? <button className="btn sm ghost" onClick={() => void act(() => api.withdrawTemplate(t.id))}>Rút lại</button> : null}
    </div>}
    {checks ? <ul className="plainList" aria-label="Kết quả kiểm tra">{checks.filter((c) => !c.ok).map((c) => <li key={c.check} className="formError">{c.check}: {c.message}</li>)}</ul> : null}
    {err ? <p className="formError" role="alert">{err}</p> : null}
  </article>;
}

export function Templates() {
  const router = useRouter();
  const [scope, setScope] = useState<"company" | "mine">("company");
  const [category, setCategory] = useState(""); const [sort, setSort] = useState<"recent" | "popular">("recent");
  const cats = useLoad(() => api.libraryCategories(), []);
  const { data, error, loading, reload } = useLoad(() => api.templates(scope, { category: category || undefined, sort }), [scope, category, sort]);
  const use = (t: TemplateDto) => router.push(S(`/new?template=${t.id}`));
  return (<>
    <div className="pageHead"><div><h1>Templates</h1><p>Mẫu khởi đầu cho website. Một mẫu là cấu trúc trang (Page Schema) từ component đã duyệt, không phải mã nguồn; ảnh không đi kèm mẫu.</p></div></div>
    <div className="tabs" role="tablist">{([["company", "Mẫu của công ty"], ["mine", "Mẫu của tôi"]] as const).map(([k, l]) =>
      <button key={k} role="tab" aria-selected={scope === k} className={scope === k ? "active" : ""} onClick={() => setScope(k)}>{l}</button>)}</div>
    <div className="filters">
      <select aria-label="Danh mục" value={category} onChange={(e) => setCategory(e.target.value)}><option value="">Mọi danh mục</option>
        {Object.entries(cats.data?.templates ?? {}).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <select aria-label="Sắp xếp" value={sort} onChange={(e) => setSort(e.target.value as "recent" | "popular")}><option value="recent">Mới cập nhật</option><option value="popular">Dùng nhiều nhất</option></select>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (
      <div className="compGrid">
        {scope === "company" ? <article className="libCard"><div className="thumb placeholder"><span>Trang mặc định</span></div><h2>Trang mặc định</h2>
          <p>Có sẵn trong hệ thống: thanh điều hướng, Hero, sản phẩm, công nghệ, đánh giá, form liên hệ, chân trang.</p>
          <div className="actions"><Link className="btn sm primary" href={S("/new")}>Dùng mẫu này</Link></div></article> : null}
        {data!.map((t) => <TemplateCard key={t.id} t={t} onUse={use} onChanged={reload} categories={cats.data?.templates ?? {}} mine={scope === "mine"}/>)}
        {data!.length === 0 ? <StateView kind="empty" title={scope === "mine" ? "Bạn chưa lưu mẫu nào" : "Chưa có mẫu công ty"}
          detail={<p>{scope === "mine" ? "Mở một ứng dụng → Cài đặt → “Lưu trang thành mẫu”." : "Tác giả gửi mẫu đi duyệt; quản trị viên duyệt để đưa vào thư viện công ty."}</p>}/> : null}
      </div>)}
  </>);
}
