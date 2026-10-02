"use client";
// Editor-side entry points of Phase 5: save the page as a template, save a section as a block.
import { useState } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { BlockDto, Section, TemplateDto } from "@/lib/http-types";
import { useLoad } from "../useLoad";
import { errText } from "../ui";
import { Drawer, Field } from "./drawers";

/** Settings drawer section. The server copies the project's SAVED page; images are not carried into templates. */
export function SaveTemplateSection({ workspaceId, projectId, projectName }: { workspaceId: string; projectId: string; projectName: string }) {
  const mine = useLoad(() => api.templates("mine"), []);
  const [target, setTarget] = useState(""); const [name, setName] = useState(projectName); const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null);
  const editable = (mine.data ?? []).filter((t: TemplateDto) => t.canEdit);
  async function save() {
    setBusy(true); setErr(null); setMsg(null);
    try {
      const r = await api.saveTemplate(workspaceId, projectId, { name: name.trim(), description: description.trim(), ...(target ? { templateId: target } : {}) });
      setMsg(`Đã lưu mẫu “${r.template.name}” (v${r.template.version}, riêng tư).${r.removedImages ? ` ${r.removedImages} ảnh không được đưa vào mẫu.` : ""} Xem ở Studio → Templates.`);
      mine.reload();
    } catch (x) { setErr(errText(x, "Không lưu được mẫu.")); } finally { setBusy(false); }
  }
  return (
    <section className="settingGroup" aria-labelledby="save-template-title"><h3 id="save-template-title">Lưu trang thành mẫu</h3>
      <p className="hint">Lưu bản đã lưu của trang làm mẫu khởi đầu cho website mới. Mẫu mới chỉ bạn thấy; quản trị viên có thể chia sẻ cho toàn công ty. Ảnh không đi kèm mẫu.</p>
      <Field label="Lưu vào"><select value={target} onChange={(e) => { setTarget(e.target.value); const t = editable.find((x) => x.id === e.target.value); if (t) { setName(t.name); setDescription(t.description); } }}>
        <option value="">Mẫu mới</option>{editable.map((t) => <option key={t.id} value={t.id}>Phiên bản mới của: {t.name} (v{t.version})</option>)}</select></Field>
      <Field label="Tên mẫu"><input maxLength={120} value={name} onChange={(e) => setName(e.target.value)}/></Field>
      <Field label="Mô tả mẫu"><input maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)}/></Field>
      <div className="drawerActions"><button className="button primary" disabled={busy || !name.trim()} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu thành mẫu"}</button></div>
      {msg ? <p className="hint" role="status">{msg}</p> : null}
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </section>
  );
}

/** Save the selected section (as stored on the server, not the unsaved inspector draft) as a private block or a new draft version. */
export function SaveBlockDrawer({ workspaceId, projectId, section, title, onClose, onSaved }: {
  workspaceId: string; projectId: string; section: Section; title: string; onClose: () => void; onSaved: (message: string) => void;
}) {
  const mine = useLoad(() => api.blocks("mine"), []);
  const candidates = (mine.data ?? []).filter((b: BlockDto) => b.baseComponent === section.type && b.canEdit);
  const [target, setTarget] = useState(""); const [name, setName] = useState(title); const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  async function save() {
    setBusy(true); setErr(null);
    try {
      const r = await api.saveBlock(workspaceId, projectId, { sectionId: section.id, name: name.trim(), description: description.trim(), ...(target ? { packageId: target } : {}) });
      onSaved(`Đã lưu khối “${r.block.name}” (phiên bản ${r.block.latestVersion}, riêng tư).${r.removedImages ? ` ${r.removedImages} ảnh không được đưa vào khối.` : ""} Gửi duyệt ở Studio → Components.`);
    } catch (x) {
      setErr(x instanceof ApiError && x.code === "SECTION_NOT_FOUND" ? "Mục này chưa được lưu trên máy chủ. Lưu thay đổi trước." : errText(x, "Không lưu được khối."));
    } finally { setBusy(false); }
  }
  return (
    <Drawer title="Lưu thành khối" sub={`Dựa trên component ${section.type}. Dùng nội dung ĐÃ LƯU của mục này.`} onClose={onClose}>
      <section className="settingGroup">
        <p className="hint">Khối là cấu hình sẵn của một component đã duyệt (không chứa mã). Khối mới chỉ bạn dùng được; gửi duyệt để đưa vào thư viện công ty.</p>
        <Field label="Lưu vào"><select value={target} onChange={(e) => { setTarget(e.target.value); const b = candidates.find((x) => x.id === e.target.value); if (b) { setName(b.name); setDescription(b.description); } }}>
          <option value="">Khối mới</option>{candidates.map((b) => <option key={b.id} value={b.id}>Phiên bản mới của: {b.name}</option>)}</select></Field>
        <Field label="Tên khối"><input maxLength={80} value={name} onChange={(e) => setName(e.target.value)} autoFocus/></Field>
        <Field label="Mô tả"><input maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)}/></Field>
      </section>
      {err ? <p className="formError" role="alert">{err}</p> : null}
      <div className="drawerActions"><button className="button ghost" onClick={onClose}>Hủy</button><button className="button primary" disabled={busy || !name.trim()} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu khối"}</button></div>
    </Drawer>
  );
}
