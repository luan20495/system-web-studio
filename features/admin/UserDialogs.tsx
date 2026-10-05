"use client";
import { useState, type FormEvent } from "react";
import { api } from "@/lib/http-api";
import type { ActivationLink } from "@/lib/http-types";
import { useLoad } from "../useLoad";
import { errText, fmtDate } from "../ui";
import { Modal } from "./Modal";

export const ROLE_LABELS: Record<string, string> = {
  WORKSPACE_ADMIN: "Quản trị không gian làm việc", EDITOR: "Biên tập viên", PUBLISHER: "Người xuất bản", VIEWER: "Người xem"
};
export const roleLabel = (r: string) => ROLE_LABELS[r] ?? r;

/** The activation/reset link is shown once: the platform keeps only its hash. */
export function LinkBox({ link, onClose }: { link: ActivationLink; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const url = `${window.location.origin}/auth/activate#${link.token}`;
  async function copy() { try { await navigator.clipboard.writeText(url); setCopied(true); } catch { setCopied(false); } }
  return (
    <Modal label="Liên kết kích hoạt" onClose={onClose}>
      <div className="modalBody">
        <h2>{link.purpose === "RESET" ? "Liên kết đặt lại mật khẩu" : "Liên kết kích hoạt"}</h2>
        <p>Gửi liên kết này cho <b>{link.displayName}</b> ({link.username}). Người dùng tự đặt mật khẩu của mình. Liên kết chỉ dùng được một lần, hết hạn lúc {fmtDate(link.expiresAt)} và sẽ không hiển thị lại.</p>
        <input readOnly aria-label="Liên kết" value={url} onFocus={(e) => e.currentTarget.select()}/>
        <div className="row">
          <button className="btn primary" onClick={() => void copy()}>{copied ? "Đã sao chép" : "Sao chép liên kết"}</button>
          <button className="btn" onClick={onClose}>Xong</button>
        </div>
      </div>
    </Modal>
  );
}

export function CreateUserDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const ws = useLoad(() => api.admin.workspaces(0), []);
  const [username, setUsername] = useState(""); const [displayName, setDisplayName] = useState(""); const [email, setEmail] = useState("");
  const [workspaceId, setWorkspaceId] = useState(""); const [role, setRole] = useState("EDITOR");
  const [newWs, setNewWs] = useState(""); const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  const [link, setLink] = useState<ActivationLink | null>(null);
  const items = ws.data?.items ?? [];
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      let target = workspaceId || items[0]?.id;
      if (!target || target === "__new") target = (await api.admin.createWorkspace(newWs.trim())).id;
      setLink(await api.admin.createUser({ username: username.trim().toLowerCase(), displayName: displayName.trim(), email: email.trim() || undefined, workspaceId: target, role }));
      onCreated();
    } catch (err) { setError(errText(err, "Chưa tạo được người dùng.")); } finally { setBusy(false); }
  }
  if (link) return <LinkBox link={link} onClose={onClose}/>;
  const selected = workspaceId || items[0]?.id || "__new";
  return (
    <Modal label="Thêm người dùng" onClose={onClose}>
      <form className="modalBody" onSubmit={(e) => void submit(e)}>
        <h2>Thêm người dùng</h2>
        <label className="field"><span>Tên đăng nhập</span><input value={username} onChange={(e) => setUsername(e.target.value)} required minLength={3} maxLength={40} autoComplete="off"/></label>
        <label className="field"><span>Tên hiển thị</span><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={160}/></label>
        <label className="field"><span>Email (không bắt buộc)</span><input type="email" value={email} onChange={(e) => setEmail(e.target.value)}/></label>
        <label className="field"><span>Không gian làm việc</span>
          <select disabled={ws.loading && !ws.data} value={selected} onChange={(e) => setWorkspaceId(e.target.value)}>
            {ws.loading && !ws.data ? <option>Đang tải…</option> : null}{items.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}<option value="__new">+ Tạo không gian làm việc mới</option>
          </select></label>
        {selected === "__new" ? <label className="field"><span>Tên không gian làm việc mới</span><input value={newWs} onChange={(e) => setNewWs(e.target.value)} required/></label> : null}
        <label className="field"><span>Vai trò</span>
          <select value={role} onChange={(e) => setRole(e.target.value)}>{Object.entries(ROLE_LABELS).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <p className="hint">Sau khi tạo, bạn nhận một liên kết kích hoạt để gửi cho người dùng. Họ tự đặt mật khẩu.</p>
        {error ? <p className="formError" role="alert">{error}</p> : null}
        <div className="row"><button className="btn primary" disabled={busy}>{busy ? "Đang tạo…" : "Tạo người dùng"}</button><button type="button" className="btn" onClick={onClose}>Hủy</button></div>
      </form>
    </Modal>
  );
}
