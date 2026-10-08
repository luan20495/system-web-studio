"use client";
import { useState } from "react";
import type { ActivationLink } from "@/lib/http-types";
import { fmtDate } from "../ui";
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
        <div className="xp-footer">
          <button className="btn" onClick={onClose}>Xong</button>
          <button className="btn primary" onClick={() => void copy()}>{copied ? "Đã sao chép" : "Sao chép liên kết"}</button>
        </div>
      </div>
    </Modal>
  );
}
