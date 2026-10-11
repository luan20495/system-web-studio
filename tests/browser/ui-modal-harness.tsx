// @class: harness — HARNESS, NOT REAL BACKEND. Shared <Modal> (+ <Picker>) in the Admin CSS set: dismissal while busy, nesting, stale onClose, backdrop rule, initial focus.
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { Modal } from "../../packages/ui/src/Modal";
import { Picker } from "../../packages/ui/src/Picker";
import "./admin-css";

declare global { interface Window { __log: unknown[]; __closeA: () => void } }
window.__log = [];

function App() {
  const [a, setA] = useState(false); const [b, setB] = useState(false); const [c, setC] = useState(false); const [d, setD] = useState(false);
  const [epoch, setEpoch] = useState(0); const [r, setR] = useState(false); const [gone, setGone] = useState(false);   // R = a dialog that is a ROUTE: opening / closing it re-renders the opener as a NEW node (the Studio remounts its workspace per path)
  const [busy, setBusy] = useState(false); const [count, setCount] = useState(0); const [kind, setKind] = useState("OPENROUTER");
  window.__closeA = () => setA(false);
  return (
    <div style={{ padding: 24 }}>
      <button id="open-a" className="btn" onClick={() => setA(true)}>Mở A</button>
      <button id="open-c" className="btn" onClick={() => setC(true)}>Mở C</button>
      <button id="open-d" className="btn" onClick={() => setD(true)}>Mở D</button>
      {gone ? null : <button key={epoch} id="open-r" className="btn" onClick={() => { setEpoch((e) => e + 1); setR(true); }}>Mở R</button>}
      <input id="outside" aria-label="ngoài hộp thoại" />
      {r ? <Modal label="Hộp R" onClose={() => { setEpoch((e) => e + 1); setR(false); }}><div className="modalBody"><h2>Hộp R</h2><button id="drop-r" className="btn" onClick={() => { setGone(true); setEpoch((e) => e + 1); setR(false); }}>Đóng và bỏ nút mở</button><button id="close-r" className="btn" onClick={() => { setEpoch((e) => e + 1); setR(false); }}>Đóng R</button></div></Modal> : null}
      {a ? <Modal label="Hộp A" closeOnBackdrop onClose={() => { window.__log.push("closeA"); setA(false); }}>
        <form className="modalBody" onSubmit={(e) => { e.preventDefault(); setBusy(true); setTimeout(() => setBusy(false), 700); }}>
          <h2>Hộp A</h2>
          <Picker label="Loại" value={kind} options={[{ value: "OPENROUTER", label: "OpenRouter" }, { value: "LOCAL", label: "Nội bộ" }]} onChange={setKind}/>
          <label className="field"><span>Tên</span><input id="name-a"/></label>
          <button type="button" id="open-b" className="btn" onClick={() => setB(true)}>Mở B</button>
          <div className="xp-footer"><button type="button" id="cancel-a" className="btn" onClick={() => setA(false)}>Hủy</button><button id="submit-a" className="btn primary" aria-busy={busy || undefined} disabled={busy}>Gửi</button></div>
        </form>
      </Modal> : null}
      {b ? <Modal label="Hộp B" onClose={() => { window.__log.push("closeB"); setB(false); }}><div className="modalBody"><h2>Hộp B</h2><button id="cancel-b" className="btn" onClick={() => setB(false)}>Đóng B</button></div></Modal> : null}
      {c ? <Modal label="Hộp C" onClose={() => { window.__log.push(`closeC:${count}`); setC(false); }}><div className="modalBody"><h2>Hộp C</h2><button id="inc-c" className="btn" onClick={() => setCount((n) => n + 1)}>Tăng {count}</button></div></Modal> : null}
      {d ? <Modal label="Hộp D" closeOnBackdrop onClose={() => { window.__log.push("closeD"); setD(false); }}><div className="modalBody"><h2>Hộp D</h2><input id="in-d" aria-label="trong D" defaultValue="đã gõ một ít chữ"/></div></Modal> : null}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
