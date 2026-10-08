"use client";

import { useState } from "react";
import { api } from "@/lib/http-api";
import type { BlockDto } from "@/lib/http-types";
import { sectionLabel } from "@/components/SectionInspector";
import { useLoad } from "../../useLoad";
import { Card, ErrorState, errText, num, Pill, StateView } from "../../ui";
import { BlockStatus, BlockThumb, blockPage, CheckList, ReviewTimeline } from "../../library";

function BlockCard({ b, mine, onChanged }: { b: BlockDto; mine: boolean; onChanged: () => void }) {
  const [open, setOpen] = useState(false); const [err, setErr] = useState<string | null>(null); const [info, setInfo] = useState<string | null>(null);
  const page = blockPage(b);
  async function act(fn: () => Promise<unknown>, ok?: string) { setErr(null); setInfo(null); try { await fn(); if (ok) setInfo(ok); onChanged(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  const latest = b.versions.find((v) => v.version === b.latestVersion);
  const lastDecision = [...b.reviews].reverse().find((r) => r.decision === "REJECT" || r.decision === "APPROVE" || r.decision === "VALIDATION_FAILED");
  return <article className="libCard">
    {page ? <BlockThumb b={b}/> : null}
    <div className="row between"><h2>{b.name}</h2>{mine ? <BlockStatus status={b.status}/> : <Pill value="APPROVED" label="Đã duyệt"/>}</div>
    {b.description ? <p>{b.description}</p> : null}
    <div className="meta">{b.usageCount ? `${b.usageCount} lần dùng · ` : ""}Dựa trên <span className="code">{b.baseComponent}</span> · {mine ? `phiên bản ${b.latestVersion}${b.approvedVersion ? ` · đang dùng trong công ty: v${b.approvedVersion}` : ""}` : `v${b.approvedVersion} · ${b.owner ?? "—"}`}</div>
    {mine && lastDecision && b.status === "PRIVATE" && lastDecision.decision !== "APPROVE" ? <p className="notice">{lastDecision.decision === "REJECT" ? `Bị từ chối: ${lastDecision.comment}` : "Kiểm tra tự động không đạt — xem chi tiết."}</p> : null}
    {mine ? <div className="actions">
      {b.status === "PRIVATE" ? <button className="btn sm primary" onClick={() => void act(async () => { const r = await api.submitBlock(b.id); if (!r.passed) { setOpen(true); throw new Error("Kiểm tra tự động không đạt; xem danh sách bên dưới."); } }, "Đã gửi duyệt.")}>Gửi duyệt</button> : null}
      {b.status === "REVIEW" ? <button className="btn sm" onClick={() => void act(() => api.withdrawBlock(b.id), "Đã rút lại.")}>Rút lại</button> : null}
      {b.approvedVersion == null && b.status !== "REVIEW" ? <button className="btn sm ghost" onClick={() => { if (confirm(`Xóa khối “${b.name}”?`)) void act(() => api.deleteBlock(b.id)); }}>Xóa</button> : null}
      <button className="btn sm ghost" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? "Ẩn chi tiết" : "Chi tiết"}</button>
    </div> : null}
    {open && mine ? <div>
      {latest?.validation ? <><h3 className="subHead">Kiểm tra tự động (v{latest.version})</h3><CheckList checks={latest.validation}/></> : <p className="muted">Chưa kiểm tra: bấm “Gửi duyệt”.</p>}
      <h3 className="subHead">Lịch sử</h3><ReviewTimeline reviews={b.reviews}/>
    </div> : null}
    {info ? <p className="hint" role="status">{info}</p> : null}
    {err ? <p className="formError" role="alert">{err}</p> : null}
  </article>;
}
export function Components() {
  const { data, error, loading, reload } = useLoad(() => api.components(), []);
  const [open, setOpen] = useState<string | null>(null);
  return (<>
    <div className="pageHead"><div><h1>Company Components</h1><p>Component đã được duyệt. AI và trình chỉnh sửa chỉ dùng những component này; số liệu là số ứng dụng đang dùng trong toàn công ty.</p></div></div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (
      <div className="compGrid">{data!.map((c) => {
        const props = Object.keys(c.versions.find((v) => v.version === c.latestVersion)?.propsSchema.properties ?? {});
        return (
          <article key={c.id} className="compCard">
            <div className="row between"><b>{sectionLabel(c.id, c.name)}</b><Pill value={c.status === "ACTIVE" ? "ACTIVE" : c.status} label={c.status === "ACTIVE" ? "Đã duyệt" : c.status}/></div>
            <small className="code">{c.id} · v{c.latestVersion} · {c.category}</small>
            <p>{c.description}</p>
            <div className="row between"><small>{num(c.usedInProjects ?? 0)} ứng dụng đang dùng</small><button className="btn sm" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>{open === c.id ? "Ẩn" : "Thuộc tính"}</button></div>
            {open === c.id ? <ul className="plainList">{props.map((p) => <li key={p} className="code">{p}</li>)}</ul> : null}
          </article>);
      })}</div>
    )}
    <BlocksSection/>
  </>);
}

function BlocksSection() {
  const company = useLoad(() => api.blocks("company"), []); const mine = useLoad(() => api.blocks("mine"), []);
  return (<>
    <Card title="Khối dựng sẵn của công ty">
      <p className="hint">Khối là một cấu hình sẵn (nội dung, bố cục) của một component đã duyệt, được nhân viên đóng góp và quản trị viên phê duyệt. Khối không chứa mã: khi chèn vào trang, nó là một mục bình thường của component gốc. Chưa đo số lần sử dụng khối.</p>
      {company.error ? <ErrorState error={company.error} retry={company.reload}/> : !company.data ? <StateView kind="loading"/> : company.data.length === 0
        ? <StateView kind="empty" title="Chưa có khối nào được duyệt"/> : <div className="compGrid">{company.data.map((b) => <BlockCard key={b.id} b={b} mine={false} onChanged={company.reload}/>)}</div>}
    </Card>
    <Card title="Khối của tôi">
      <p className="hint">Tạo khối: mở một ứng dụng ở chế độ Design, chọn một mục rồi bấm “Lưu thành khối”. Khối riêng tư chỉ bạn dùng được; gửi duyệt để kiểm tra tự động rồi chờ quản trị viên (người khác bạn) phê duyệt.</p>
      {mine.error ? <ErrorState error={mine.error} retry={mine.reload}/> : !mine.data ? <StateView kind="loading"/> : mine.data.length === 0
        ? <StateView kind="empty" title="Bạn chưa có khối nào"/> : <div className="compGrid">{mine.data.map((b) => <BlockCard key={b.id} b={b} mine onChanged={() => { mine.reload(); company.reload(); }}/>)}</div>}
    </Card>
  </>);
}
