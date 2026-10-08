"use client";

import { Fragment, useMemo, useState } from "react";
import Link from "next/link";
import { api } from "@/lib/http-api";
import type { BlockDto, TemplateDto } from "@/lib/http-types";
import { useA } from "../console/context";
import { useLoad } from "../../useLoad";
import { BlockStatus, blockPage, CheckList, ReviewTimeline, SchemaThumb } from "../../library";
import { ago, Card, ComingSoon, ErrorState, errText, num, Pager, Pill, StateView } from "../../ui";
import { PageHead } from "../PageHead";

// ------------------------------------------------------------------ components
export function ComponentsPage() {
  const [tab, setTab] = useState<"registry" | "blocks">("registry");
  return (<>
    <PageHead title="Component Registry" sub="Component đã duyệt (AI và trình chỉnh sửa chỉ dùng những component này) và khối do nhân viên đóng góp chờ duyệt."/>
    <div className="tabs" role="tablist">
      <button role="tab" aria-selected={tab === "registry"} className={tab === "registry" ? "active" : ""} onClick={() => setTab("registry")}>Registry</button>
      <button role="tab" aria-selected={tab === "blocks"} className={tab === "blocks" ? "active" : ""} onClick={() => setTab("blocks")}>Khối đóng góp</button>
    </div>
    {tab === "registry" ? <RegistryTable/> : <BlocksAdmin/>}
  </>);
}

export function RegistryTable() {
  const { data, error, loading, reload } = useLoad(() => api.admin.components(), []);
  const [open, setOpen] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  return (<>
    <Card>
      <table className="table"><thead><tr><th>Component</th><th>Nhóm</th><th>Phiên bản</th><th>Trạng thái</th><th>Dùng trong</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.map((c) => (<Fragment key={c.id}>
          <tr><td><b>{c.name}</b><small className="code">{c.id}</small><small>{c.description}</small></td><td>{c.category}</td><td>{c.latestVersion}</td><td><Pill value={c.status === "ACTIVE" ? "ACTIVE" : c.status} label={c.status === "ACTIVE" ? "Đã duyệt" : c.status}/></td>
            <td>{num(c.usedInProjects)} ứng dụng<small>{num(c.sections)} mục</small></td><td><button className="btn sm" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>Schema</button></td></tr>
          {open === c.id ? <tr className="detailRow"><td colSpan={6}><pre>{c.propsSchema ? JSON.stringify(JSON.parse(c.propsSchema), null, 2) : "—"}</pre></td></tr> : null}
        </Fragment>))}</tbody></table>
    </Card>
    <Card title="Thêm component gốc mới"><ComingSoon title="Component có renderer mới">Thêm một loại component gốc mới cần viết renderer trong mã nguồn và được review như mọi thay đổi mã. Hệ thống không chạy HTML/JS do người dùng tải lên. Nhân viên đóng góp “khối” (cấu hình sẵn của component đã duyệt) ở tab bên cạnh.</ComingSoon></Card>
  </>);
}

const BLOCK_FILTERS: [string, string][] = [["REVIEW", "Chờ duyệt"], ["APPROVED", "Đã duyệt"], ["PRIVATE", "Riêng tư"], ["DEPRECATED", "Ngừng dùng"], ["ALL", "Tất cả"]];

export function BlocksAdmin() {
  const [status, setStatus] = useState("REVIEW"); const [page, setPage] = useState(0); const [open, setOpen] = useState<string | null>(null);
  const { data, error, loading, reload } = useLoad(() => api.admin.blocks(status, page), [status, page]);
  return <Card>
    <div className="filters">{BLOCK_FILTERS.map(([k, l]) => <button key={k} className={`btn sm ${status === k ? "primary" : ""}`} aria-pressed={status === k}
      onClick={() => { setStatus(k); setPage(0); setOpen(null); }}>{l}{k !== "ALL" && data?.counts[k] ? ` (${num(data.counts[k])})` : ""}</button>)}</div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.page.items.length === 0
      ? <StateView kind="empty" title={status === "REVIEW" ? "Không có khối nào chờ duyệt" : "Không có khối"}/> : <>
      <table className="table"><thead><tr><th>Khối</th><th>Component gốc</th><th>Người đóng góp</th><th>Trạng thái</th><th>Phiên bản</th><th>Cập nhật</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
        <tbody>{data!.page.items.map((b) => <Fragment key={b.id}>
          <tr><td><b>{b.name}</b>{b.description ? <small>{b.description}</small> : null}</td><td className="code">{b.baseComponent}</td><td>{b.owner ?? "—"}</td>
            <td><BlockStatus status={b.status}/></td><td>v{b.latestVersion}{b.approvedVersion ? <small>đang dùng: v{b.approvedVersion}</small> : null}</td><td>{ago(b.updatedAt)}</td>
            <td><button className="btn sm" aria-expanded={open === b.id} onClick={() => setOpen(open === b.id ? null : b.id)}>{open === b.id ? "Đóng" : "Xem xét"}</button></td></tr>
          {open === b.id ? <tr className="detailRow"><td colSpan={7}><BlockReviewPanel id={b.id} onDone={() => { reload(); }}/></td></tr> : null}
        </Fragment>)}</tbody></table>
      <Pager page={page} size={data!.page.size} total={data!.page.total} onPage={setPage}/></>}
  </Card>;
}

export function BlockReviewPanel({ id, onDone }: { id: string; onDone: () => void }) {
  const { data, error, loading, reload } = useLoad(() => api.admin.block(id), [id]);
  const [comment, setComment] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  if (loading && !data) return <StateView kind="loading"/>;
  if (error) return <ErrorState error={error} retry={reload}/>;
  const b: BlockDto = data!;
  const latest = b.versions.find((v) => v.version === b.latestVersion);
  const page = blockPage(b);
  async function act(fn: () => Promise<unknown>) { setBusy(true); setErr(null); try { await fn(); setComment(""); reload(); onDone(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } finally { setBusy(false); } }
  return <div className="grid2">
    <div>{page ? <SchemaThumb schema={page} title={`Xem trước khối ${b.name}`} tall/> : null}
      <details><summary>Thuộc tính (JSON, v{latest?.version})</summary><pre>{JSON.stringify(latest?.props ?? {}, null, 2)}</pre></details></div>
    <div>
      <h2 className="subHead">Kiểm tra tự động</h2>{latest?.validation ? <CheckList checks={latest.validation}/> : <p className="muted">Chưa chạy (khối chưa được gửi).</p>}
      <h2 className="subHead">Lịch sử</h2><ReviewTimeline reviews={b.reviews}/>
      {b.status === "REVIEW" ? (b.canReview ? <div className="inlineForm">
        <label htmlFor={`c-${b.id}`}>Nhận xét (bắt buộc khi từ chối)</label>
        <textarea id={`c-${b.id}`} rows={2} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)}/>
        <div className="row"><button className="btn primary" disabled={busy} onClick={() => void act(() => api.admin.reviewBlock(b.id, "APPROVE", b.latestVersion, comment.trim() || undefined))}>Phê duyệt v{b.latestVersion}</button>
          <button className="btn danger" disabled={busy || !comment.trim()} onClick={() => void act(() => api.admin.reviewBlock(b.id, "REJECT", b.latestVersion, comment.trim()))}>Từ chối</button></div>
      </div> : <p className="notice">Bạn là người đóng góp khối này nên không thể tự duyệt; cần một quản trị viên khác.</p>) : null}
      <div className="row">
        {b.status !== "DEPRECATED" && b.approvedVersion != null ? <button className="btn sm" disabled={busy} onClick={() => { if (confirm("Ngừng dùng khối này? Khối biến khỏi thư viện; các trang đang dùng không bị thay đổi.")) void act(() => api.admin.deprecateBlock(b.id, comment.trim() || undefined)); }}>Ngừng dùng</button> : null}
        {b.status === "DEPRECATED" ? <button className="btn sm" disabled={busy} onClick={() => void act(() => api.admin.restoreBlock(b.id))}>Khôi phục</button> : null}
      </div>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </div>
  </div>;
}

// ------------------------------------------------------------------ templates
export function TemplatesAdmin() {
  const A = useA();
  const [page, setPage] = useState(0); const [visibility, setVisibility] = useState(""); const [status, setStatus] = useState("ACTIVE"); const [q, setQ] = useState(""); const [applied, setApplied] = useState("");
  const [open, setOpen] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null);
  const params = useMemo(() => ({ page, visibility: visibility || undefined, status: status || undefined, q: applied || undefined }), [page, visibility, status, applied]);
  const { data, error, loading, reload } = useLoad(() => api.admin.templates(params), [params]);
  async function act(fn: () => Promise<TemplateDto>) { setErr(null); try { await fn(); reload(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  return (<>
    <PageHead title="Templates" sub="Mẫu là cấu trúc trang (Page Schema) do nhân viên lưu từ ứng dụng. Tác giả gửi duyệt → kiểm tra tự động (component, nội dung, render an toàn) → quản trị viên khác tác giả duyệt hoặc từ chối."/>
    <Card>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(q.trim()); }}>
        <input aria-label="Tìm mẫu" placeholder="Tìm theo tên" value={q} onChange={(e) => setQ(e.target.value)}/>
        <select aria-label="Phạm vi" value={visibility} onChange={(e) => { setPage(0); setVisibility(e.target.value); }}><option value="">Mọi phạm vi</option><option value="COMPANY">Công ty</option><option value="PRIVATE">Riêng tư</option></select>
        <select aria-label="Trạng thái" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}><option value="">Mọi trạng thái</option><option value="REVIEW">Chờ duyệt</option>
          <option value="ACTIVE">Đang dùng</option><option value="APPROVED">Đã duyệt</option><option value="PRIVATE">Riêng tư</option><option value="ARCHIVED">Đã lưu trữ</option></select>
        <button className="btn primary">Lọc</button>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0 ? <StateView kind="empty" title="Không có mẫu"/> : <>
        <table className="table"><thead><tr><th>Mẫu</th><th>Tác giả</th><th>Duyệt</th><th>Trạng thái</th><th>Mục · lượt dùng</th><th>Cập nhật</th><th><span className="srOnly">Thao tác</span></th></tr></thead>
          <tbody>{data!.items.map((t) => <Fragment key={t.id}>
            <tr><td><b>{t.name}</b><small>v{t.version}{t.description ? ` · ${t.description}` : ""}</small></td><td>{t.author ?? "—"}</td>
              <td><Pill value={{ PRIVATE: "PRIVATE", SUBMITTED: "QUEUED", REVIEW: "UNKNOWN", APPROVED: "COMPANY", ARCHIVED: "ARCHIVED" }[t.reviewStatus]}
                label={{ PRIVATE: "Riêng tư", SUBMITTED: "Đang kiểm tra", REVIEW: "Chờ duyệt", APPROVED: "Đã duyệt (công ty)", ARCHIVED: "Đã lưu trữ" }[t.reviewStatus]}/></td>
              <td>{t.status === "ACTIVE" ? <Pill value="ACTIVE" label="Đang dùng"/> : <Pill value="ARCHIVED" label="Đã lưu trữ"/>}</td><td>{t.sections} · {t.usageCount}</td><td>{ago(t.updatedAt)}</td>
              <td><div className="row">
                <button className="btn sm" aria-expanded={open === t.id} onClick={() => setOpen(open === t.id ? null : t.id)}>Xem</button>
                {t.canReview ? <><button className="btn sm primary" onClick={() => void act(() => api.admin.reviewTemplate(t.id, "APPROVE"))}>Duyệt</button>
                  <button className="btn sm" onClick={() => { const c = window.prompt("Lý do từ chối (gửi cho tác giả):")?.trim(); if (c) void act(() => api.admin.reviewTemplate(t.id, "REJECT", c)); }}>Từ chối</button></> : null}
                {t.status === "ACTIVE" && t.reviewStatus !== "REVIEW" ? (t.visibility === "PRIVATE"
                  ? <button className="btn sm primary" onClick={() => void act(() => api.admin.templateVisibility(t.id, "COMPANY"))}>Chia sẻ toàn công ty</button>
                  : <button className="btn sm" onClick={() => void act(() => api.admin.templateVisibility(t.id, "PRIVATE"))}>Thu hồi về riêng tư</button>) : null}
                {t.status === "ACTIVE" ? <button className="btn sm ghost" onClick={() => { if (confirm(`Lưu trữ mẫu “${t.name}”?`)) void act(() => api.admin.templateStatus(t.id, "ARCHIVED")); }}>Lưu trữ</button>
                  : <button className="btn sm" onClick={() => void act(() => api.admin.templateStatus(t.id, "ACTIVE"))}>Khôi phục</button>}
              </div></td></tr>
            {open === t.id ? <tr className="detailRow"><td colSpan={7}><div className="grid2">{t.previewStatus === "READY"
                ? <img className="previewImg" src={`/api/v1/templates/${t.id}/preview`} alt={`Ảnh xem trước mẫu ${t.name}`}/> : <SchemaThumb schema={t.schema} title={`Xem trước mẫu ${t.name}`} tall/>}
              <div><p>Component: {t.componentTypes.map((c) => <span key={c} className="tag code">{c}</span>)}</p>
                <p>Danh mục: {t.category}{t.tags.length ? ` · ${t.tags.map((x) => `#${x}`).join(" ")}` : ""} · ảnh xem trước: {t.previewStatus}
                  {" "}<button className="btn sm ghost" onClick={() => void act(async () => { await api.admin.templatePreview(t.id); return t; })}>Tạo lại ảnh</button></p>
                {t.reviewComment ? <p>Nhận xét duyệt: {t.reviewComment}{t.reviewedBy ? ` — ${t.reviewedBy}` : ""}</p> : null}{t.sourceProjectId ? <p><Link href={A(`/applications/${t.sourceProjectId}`)}>Ứng dụng nguồn</Link></p> : null}</div></div></td></tr> : null}
          </Fragment>)}</tbody></table>
        <Pager page={page} size={data!.size} total={data!.total} onPage={setPage}/></>}
    </Card>
  </>);
}
