// @class: harness — HARNESS, NOT REAL BACKEND. The shared imperative Toast / confirm() / prompt() in the Admin CSS set (no provider anywhere), with short toast durations so the spec can watch auto-dismiss; a Modal button proves the toast renders above overlays.
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { Modal } from "../../packages/ui/src/Modal";
import { toast } from "../../packages/ui/src/Toast";
import { confirm, prompt } from "../../packages/ui/src/dialogs";
import "./admin-css";

const w = window as unknown as { __log: string[] };
w.__log = [];
toast.configure({ success: 700, info: 700, warning: 900 });

function App() {
  const [m, setM] = useState(false);
  return (
    <div style={{ padding: 24 }}>
      <button id="ok" onClick={() => toast.success("Đã lưu cài đặt.")}>ok</button>
      <button id="err" onClick={() => toast.error("Không lưu được thay đổi.", { title: "Lỗi lưu" })}>err</button>
      <button id="five" onClick={() => { for (let i = 1; i <= 5; i++) toast.success(`Thông báo số ${i}`); }}>five</button>
      <button id="same" onClick={() => toast.info("Đang cập nhật…", { id: "same" })}>same</button>
      <button id="modal" onClick={() => setM(true)}>modal</button>
      <button id="confirm" onClick={async () => { w.__log.push(`confirm:${await confirm({ title: "Xóa mục này?", message: "Không thể hoàn tác.", confirmLabel: "Xóa", danger: true })}`); }}>confirm</button>
      <button id="confirm2" onClick={async () => { w.__log.push(`confirm2:${await confirm("Lưu thay đổi?")}`); }}>confirm2</button>
      <button id="prompt" onClick={async () => { w.__log.push(`prompt:${JSON.stringify(await prompt({ title: "Từ chối mẫu", label: "Lý do (gửi cho tác giả)", multiline: true, required: true }))}`); }}>prompt</button>
      {m ? <Modal label="Hộp thoại" onClose={() => setM(false)}><div className="modalBody"><h2>Hộp thoại</h2><button id="toast-in-modal" className="btn" onClick={() => toast.error("Lỗi trong hộp thoại")}>báo lỗi</button><button id="close-m" className="btn" onClick={() => setM(false)}>Đóng</button></div></Modal> : null}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
