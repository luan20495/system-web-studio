"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { LoadGate } from "@xweb/ui";
import { api } from "@/lib/http-api";
import { SERVER_KINDS, type AppKind } from "@/lib/http-types";
import { useLoad } from "../../useLoad";
import { Card, errText, Pill } from "../../ui";
import { S } from "../base";
import { useStudio } from "../studioContext";

// ------------------------------------------------------------------ create
// (no role-based "can create": see Home; the server answers 403 when creation is not allowed)

export function NewApp() {
  const router = useRouter(); const { workspaceId } = useStudio();
  const params = useSearchParams();
  const company = useLoad(() => api.templates("company"), []); const mine = useLoad(() => api.templates("mine"), []);
  const [templateId, setTemplateId] = useState<string>(params.get("template") ?? "");
  const [name, setName] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const [kind, setKind] = useState<AppKind>(params.get("type") === "code" ? "SOURCE_WEB_APP" : "WEBSITE_STATIC");
  const authConfig = useLoad(() => api.authConfig(), []);
  const codeOn = authConfig.data?.codeProjects === true; const serverOn = authConfig.data?.serverApps === true;
  const offCode = "Chưa bật trên máy chủ này (cần kho Git và máy build).", offServer = "Chưa bật: cần quản trị viên bật ứng dụng có máy chủ và runtime cô lập.";
  const types: [AppKind, string, string, boolean][] = [
    ["WEBSITE_STATIC", "Website", "Website một hoặc nhiều trang từ component đã duyệt, có form liên hệ và tên miền riêng.", true],
    ["SOURCE_WEB_APP", "Ứng dụng web (mã nguồn)", codeOn ? "Ứng dụng React chạy trên trình duyệt: sửa bằng AI hoặc trực tiếp mã nguồn, mỗi thay đổi được build trong sandbox." : offCode, codeOn],
    ["DASHBOARD", "Dashboard", codeOn ? "Bảng số liệu và biểu đồ (React) từ API của một ứng dụng có máy chủ hoặc dữ liệu tĩnh." : offCode, codeOn],
    ["INTERNAL_TOOL", "Công cụ nội bộ", serverOn ? "Biểu mẫu + bảng dữ liệu có máy chủ và cơ sở dữ liệu riêng; người dùng là thành viên ứng dụng." : offServer, serverOn],
    ["WORKFLOW", "Quy trình (workflow)", serverOn ? "Các bước duyệt/xử lý chạy trên máy chủ riêng, lưu lịch sử, gọi connector đã duyệt." : offServer, serverOn],
    ["SERVER_APP", "Ứng dụng có máy chủ", serverOn ? "Giao diện React + API Node.js chạy trong container cô lập (không Internet), CSDL riêng, bí mật chỉ ghi." : offServer, serverOn]];
  async function create(e: FormEvent) {
    e.preventDefault(); if (!name.trim()) return; setBusy(true); setErr(null);
    try {
      const p = kind !== "WEBSITE_STATIC" ? await api.createProject(workspaceId, name.trim(), undefined, undefined, "STATIC_APP", kind)
        : await api.createProject(workspaceId, name.trim(), undefined, templateId || undefined);
      router.push(S(`/projects/${p.id}/ai`));
    }
    catch (x) { setErr(errText(x, "Không tạo được ứng dụng.")); setBusy(false); }
  }
  const defaultOption: [string, string, string] = ["", "Trang mặc định", "Có sẵn trong hệ thống: thanh điều hướng, Hero, sản phẩm, đánh giá, liên hệ."];
  // M-119 / M-122: the template choice is a load of its own (company + mine): it says it is loading, says when it failed (with a retry), says when there is nothing to pick. The default page is always offered.
  const templates = {
    data: company.data && mine.data ? [...company.data.map((t): [string, string, string] => [t.id, t.name, `Mẫu công ty · ${t.sections} mục · v${t.version}`]), ...mine.data.map((t): [string, string, string] => [t.id, t.name, `Mẫu của tôi · ${t.sections} mục · v${t.version}`])] : null,
    error: company.error ?? mine.error, loading: company.loading || mine.loading, reload: () => { company.reload(); mine.reload(); },
  };
  return (<>
    <div className="pageHead"><div><h1>Tạo ứng dụng</h1><p>Chọn loại ứng dụng. Loại nào chưa bật trên máy chủ này sẽ ghi rõ lý do.</p></div></div>
    <LoadGate load={authConfig} compact label="loại ứng dụng đã bật" errorTitle="Chưa tải được loại ứng dụng nào đã bật trên máy chủ">{() => (
    <div className="typeGrid" role="radiogroup" aria-label="Loại ứng dụng">{types.map(([k, t, d, on]) => (
      <div key={k} role="radio" tabIndex={on ? 0 : -1} aria-checked={kind === k} aria-disabled={!on} className={`typeCard${kind === k ? " selected" : ""}${on ? "" : " disabled"}`}
        onClick={() => { if (on) setKind(k); }} onKeyDown={(e) => { if (on && (e.key === " " || e.key === "Enter")) { e.preventDefault(); setKind(k); } }}>
        <div className="row between"><b>{t}</b>{on ? <Pill value="ACTIVE" label="Sẵn sàng"/> : <Pill value="COMING_SOON" label="Chưa bật"/>}</div><p>{d}</p>
      </div>))}</div>
    )}</LoadGate>
    <Card title={kind === "WEBSITE_STATIC" ? "Website mới" : `${types.find((x) => x[0] === kind)?.[1] ?? "Ứng dụng"} mới`}>
      <form onSubmit={(e) => void create(e)}>
        {kind === "WEBSITE_STATIC" ? <fieldset className="pickList" aria-label="Bắt đầu từ mẫu"><legend className="hint">Bắt đầu từ</legend>
          {[defaultOption].map(([id, label, sub]) => <label key="default"><input type="radio" name="template" value={id} checked={templateId === id} onChange={() => setTemplateId(id)}/>
            <span><b>{label}</b><small>{sub}</small></span></label>)}
          <LoadGate load={templates} compact label="mẫu của công ty và của bạn" errorTitle="Chưa tải được danh sách mẫu" isEmpty={(d) => d.length === 0} empty={{ title: "Chưa có mẫu nào", detail: <p>Chưa có mẫu của công ty hoặc của bạn. Website sẽ bắt đầu từ trang mặc định ở trên.</p> }}>
            {(d) => <>{d.map(([id, label, sub]) => <label key={id}><input type="radio" name="template" value={id} checked={templateId === id} onChange={() => setTemplateId(id)}/>
              <span><b>{label}</b><small>{sub}</small></span></label>)}</>}
          </LoadGate>
        </fieldset> : SERVER_KINDS.includes(kind) ? <p className="hint">Bắt đầu từ khung React + Node.js đã duyệt. Máy chủ của ứng dụng chạy trong container cô lập (không root, không Internet), có cơ sở dữ liệu PostgreSQL riêng; bí mật (API key…) chỉ ghi, không bao giờ hiện lại và không gửi cho AI. Chỉ các đường dẫn khai báo trong openapi.json mới gọi được từ bên ngoài.</p>
          : <p className="hint">Bắt đầu từ khung React + Vite + TypeScript đã duyệt. Mã nguồn nằm trong kho Git của nền tảng; thư viện chỉ gồm các gói đã duyệt. Ứng dụng chạy cách ly trong trình duyệt (không có cookie/localStorage).</p>}
        <div className="filters">
          <input aria-label="Tên ứng dụng" placeholder="Tên ứng dụng" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} autoFocus/>
          <button className="btn primary" disabled={busy || !name.trim()}>{busy ? "Đang tạo…" : kind === "WEBSITE_STATIC" ? "Tạo website" : "Tạo ứng dụng"}</button>
        </div>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
  </>);
}
