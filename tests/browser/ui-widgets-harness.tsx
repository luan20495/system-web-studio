// @class: harness — HARNESS, NOT REAL BACKEND. The shared widgets (Tabs, RadioGroup, DisclosureRow, ReasonButton, Pill, Picker, LoadGate / ErrorState) in the Admin CSS set, on a light page and on a dark `.studio` page.
import { createRoot } from "react-dom/client";
import { useId, useState } from "react";
import { Tabs, TabPanel } from "../../packages/ui/src/Tabs";
import { RadioGroup } from "../../packages/ui/src/RadioGroup";
import { DisclosureRow } from "../../packages/ui/src/DisclosureRow";
import { ReasonButton } from "../../packages/ui/src/ReasonButton";
import { Pill } from "../../packages/ui/src/Pill";
import { Button } from "../../packages/ui/src/Button";
import { Switch } from "../../packages/ui/src/Switch";
import { Picker } from "../../packages/ui/src/Picker";
import { LoadGate } from "../../packages/ui/src/LoadGate";
import { ErrorState } from "../../packages/ui/src/States";
import { ApiError } from "../../packages/api-client/src/core";
import "./admin-css";

declare global { interface Window { __log: unknown[] } }
window.__log = [];
const dark = new URLSearchParams(location.search).has("dark");

function Gate() {
  const [mode, setMode] = useState<"loading" | "error" | "notfound" | "empty" | "ready">("error");
  const [tries, setTries] = useState(0);
  const load = { data: mode === "empty" ? [] as string[] : mode === "ready" ? ["An", "Bình"] : null, error: mode === "error" ? new ApiError(500, "INTERNAL_ERROR", "NullPointerException", "req-77") : mode === "notfound" ? new ApiError(404, "PROJECT_NOT_FOUND", "x") : null, loading: mode === "loading", reload: () => { setTries((n) => n + 1); window.__log.push("retry"); } };
  return (
    <section aria-label="Cổng tải">
      {(["loading", "error", "notfound", "empty", "ready"] as const).map((m) => <button key={m} id={`g-${m}`} type="button" onClick={() => setMode(m)}>{m}</button>)}<span id="tries">{tries}</span>
      <LoadGate load={load} label="danh sách" isEmpty={(d) => d.length === 0} empty={{ title: "Chưa có mục nào" }}>{(d) => <ul id="ready-list">{d.map((x) => <li key={x}>{x}</li>)}</ul>}</LoadGate>
    </section>
  );
}

function App() {
  const base = useId();
  const [tab, setTab] = useState("info"); const [aud, setAud] = useState("PRIVATE"); const [kind, setKind] = useState("A");
  return (
    <div className={dark ? "studio" : "shell"} style={{ padding: 24, display: "block", height: "auto", gridTemplateRows: "none" }}>
      <h1>Bộ thành phần</h1>
      <input id="before" aria-label="trước tab" /><Tabs label="Chi tiết" idBase={base} value={tab} onChange={(v) => { window.__log.push(`tab:${v}`); setTab(v); }} tabs={[{ value: "info", label: "Thông tin" }, { value: "members", label: "Thành viên", count: 4 }, { value: "off", label: "Đã khóa", disabled: true }, { value: "logs", label: "Nhật ký" }]}/>
      <TabPanel idBase={base} value={tab}><p id="panel-text">Nội dung {tab}</p></TabPanel>
      <input id="after" aria-label="sau tab" />
      <hr/>
      <RadioGroup legend="Ai xem được ứng dụng?" value={aud} onChange={(v) => { window.__log.push(`aud:${v}`); setAud(v); }} options={[{ value: "PRIVATE", label: "Riêng tư", hint: "Chỉ người được mời" }, { value: "PUBLIC", label: "Công khai", hint: "Ai có liên kết" }, { value: "COMPANY", label: "Cả công ty", hint: "Mọi người trong công ty" }]}/>
      <input id="after-radio" aria-label="sau nhóm radio" />
      <hr/>
      {dark ? null : <table className="table"><thead><tr><th><span className="srOnly">Chi tiết</span></th><th>Thời gian</th><th>Sự kiện</th><th>Liên kết</th></tr></thead>
        <tbody>
          <DisclosureRow label="Chi tiết sự kiện Đăng nhập" colSpan={4} cells={<><td>10:42</td><td>Đăng nhập, <a href="#z" id="td-link">xem phiên</a></td><td><a href="#x" id="row-link">Mở</a></td></>} detail={<p id="detail-1">Địa chỉ IP 10.0.0.1</p>}/>
          <DisclosureRow label="Chi tiết sự kiện Xuất bản" colSpan={4} cells={<><td>10:50</td><td>Xuất bản</td><td/></>} detail={<p id="detail-2">Phiên bản 3</p>}/>
        </tbody></table>}
      <hr/>
      <p><ReasonButton id="rb-off" className="btn" unavailable reason="Bật mô hình trước." onClick={() => window.__log.push("rb-off")}>Đặt làm mặc định</ReasonButton>
        {" "}<ReasonButton id="rb-on" className="btn" onClick={() => window.__log.push("rb-on")}>Lưu</ReasonButton>
        {" "}<ReasonButton id="rb-busy" className="btn" busy onClick={() => window.__log.push("rb-busy")}>Đang lưu</ReasonButton></p>
      <p id="pills"><Pill value="HIGH_RISK" label="Rủi ro cao"/> <Pill value="PAID" label="Trả phí"/> <Pill value="AWAITING_REVIEW" label="Chờ duyệt"/> <Pill value="ACTIVE" label="Hoạt động"/> <Pill value="x" tone="info" label="Thông tin"/> <Pill value="UNKNOWN" label="Chưa rõ"/></p>
      <Picker label="Loại" value={kind} options={[{ value: "A", label: "Loại A" }, { value: "B", label: "Loại B" }, { value: "C", label: "Loại C" }]} onChange={setKind}/>
      <hr/>
      <section aria-label="Điều khiển" id="controls">
        <p>Xem <a href="#y" id="p-link">hướng dẫn</a> trước khi dùng.</p>
        <p><label><input type="checkbox" id="c-check"/> Nhớ tôi</label> <label><input type="radio" name="r" id="c-radio"/> Chọn</label> <input id="c-text" aria-label="Ô nhập"/> <select id="c-select" aria-label="Chọn"><option>A</option></select> <textarea id="c-area" aria-label="Ghi chú" style={{ minHeight: 40 }}/></p>
        <p><button className="btn" id="c-btn">Hủy</button> <button className="btn danger" id="c-danger">Xóa</button> <button className="btn primary" id="c-primary">Lưu</button></p>
        <div id="switches"><Switch label="Tắt" checked={false} onChange={() => {}}/><Switch label="Bật" checked onChange={() => {}}/></div>
        <div className="providerItem" style={{ width: "100%" }}><div className="xp-provRow" id="prow"><div className="xp-provMain"><div className="xp-provName"><b>OpenRouter</b><Pill value="ACTIVE" label="Đang bật"/></div><div className="xp-provMeta"><span>https://openrouter.ai/api/v1</span></div></div>
          <div className="xp-provActions" id="pact"><button className="btn sm">Kiểm tra</button><button className="btn sm">Sửa</button><button className="btn sm danger">Xóa</button><button className="btn sm">Tắt</button></div></div></div>
      </section>
      <section aria-label="Nút" id="btn-pairs">
        {([["button", "default", "secondary", undefined, false], ["button primary", "primary", "primary", undefined, false], ["button ghost", "ghost", "ghost", undefined, false], ["smallButton", "small", "secondary", "sm", false],
          ["smallButton danger", "small danger", "danger", "sm", false], ["button", "disabled", "secondary", undefined, true]] as const).map(([legacy, label, variant, size, off], i) => (
          <p key={i}><button type="button" className={legacy} data-pair={i} data-kind="legacy" disabled={off}>{label}</button> <Button variant={variant} size={size} data-pair={i} data-kind="new" disabled={off}>{label}</Button></p>))}
        <p><button type="button" className="button icon" data-pair="6" data-kind="legacy" aria-label="Đóng">×</button> <Button icon data-pair="6" data-kind="new" aria-label="Đóng">×</Button> <Button busy id="busy-btn">Đang lưu</Button></p>
      </section>
      <Gate/>
      <div id="notfound-page"><ErrorState level={1} error={new ApiError(404, "PROJECT_NOT_FOUND", "x")} retry={() => window.__log.push("nf-retry")}/></div>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
