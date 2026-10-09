"use client";
import { useEffect, useRef, useState } from "react";
import type { ActivationLink } from "@/lib/http-types";
import { fmtDate } from "../ui";
import { Modal } from "./Modal";
import { KeyRound, ModalHeader } from "@xweb/ui";

export const ROLE_LABELS: Record<string, string> = {
  WORKSPACE_ADMIN: "Quản trị không gian làm việc", EDITOR: "Biên tập viên", PUBLISHER: "Người xuất bản", VIEWER: "Người xem"
};
export const roleLabel = (r: string) => ROLE_LABELS[r] ?? r;

/**
 * The activation/reset link is shown once: the platform keeps only its hash, and a tenant admin cannot ask for another one (C1 handoff). So this dialog never lets it slip away by accident:
 *  - the first focus is the COPY button (a stray Enter copies instead of discarding);
 *  - Esc and "Xong" ask first, until the link was copied (button, or by hand from the field) or the person says they saved it;
 *  - a refused clipboard (blocked / insecure origin) is said out loud, with what to do instead.
 * Initial focus: `Modal` honours `data-autofocus` (the Copy button).
 */
export function LinkBox({ link, onClose, copiedBefore = false, onCopied }: { link: ActivationLink; onClose: () => void; copiedBefore?: boolean; onCopied?: () => void }) {
  const [copied, setCopied] = useState(copiedBefore); const [copyFailed, setCopyFailed] = useState(false); const [asking, setAsking] = useState(false);
  const copiedRef = useRef(copiedBefore); const askingRef = useRef(false); const closeRef = useRef(onClose); closeRef.current = onClose;
  const copyBtn = useRef<HTMLButtonElement>(null); const backBtn = useRef<HTMLButtonElement>(null); const field = useRef<HTMLInputElement>(null); const wasAsking = useRef(false);
  const url = `${window.location.origin}/auth/activate#${link.token}`;
  const ask = (v: boolean) => { askingRef.current = v; setAsking(v); };
  const markCopied = () => { copiedRef.current = true; setCopied(true); setCopyFailed(false); onCopied?.(); };
  const tryClose = () => { if (copiedRef.current) closeRef.current(); else ask(true); };
  const onEscape = useRef(() => { if (askingRef.current) ask(false); else if (copiedRef.current) closeRef.current(); else ask(true); }).current;
  useEffect(() => { if (asking) backBtn.current?.focus(); else if (wasAsking.current) copyBtn.current?.focus(); wasAsking.current = asking; }, [asking]);
  async function copy() {
    try { await navigator.clipboard.writeText(url); markCopied(); }
    catch { setCopyFailed(true); field.current?.focus(); field.current?.select(); }
  }
  return (
    <Modal label="Liên kết kích hoạt" onClose={onEscape}>
      <div className="modalBody" data-testid="link-box">
        <ModalHeader icon={<KeyRound size={22}/>} title={link.purpose === "RESET" ? "Liên kết đặt lại mật khẩu" : "Liên kết kích hoạt"}/>
        <p>Gửi liên kết này cho <b>{link.displayName}</b> ({link.username}). Người dùng tự đặt mật khẩu của mình khi mở liên kết (tối thiểu 8 ký tự). Liên kết chỉ dùng được một lần, hết hạn lúc {fmtDate(link.expiresAt)} và <b>sẽ không hiển thị lại</b>: hãy sao chép trước khi đóng.</p>
        <input ref={field} readOnly aria-label="Liên kết" value={url} onFocus={(e) => e.currentTarget.select()} onCopy={markCopied}/>
        <span className="srOnly" role="status">{copied ? "Đã sao chép liên kết." : ""}</span>
        {copyFailed ? <p className="formError" role="alert">Không sao chép được tự động (trình duyệt đang chặn). Hãy chọn liên kết trong ô trên, nhấn Ctrl+C (⌘C trên Mac), rồi chọn Xong.</p> : null}
        {asking ? (
          <div className="notice" role="alert" data-testid="link-confirm">
            <b>Bạn chưa sao chép liên kết.</b> Đóng bây giờ thì liên kết biến mất và không xem lại được.
            <div className="row" style={{ marginTop: 8, flexWrap: "wrap" }}>
              <button type="button" className="btn primary" ref={backBtn} onClick={() => ask(false)}>Quay lại sao chép liên kết</button>
              <button type="button" className="btn" onClick={() => closeRef.current()}>Tôi đã lưu liên kết, đóng</button>
            </div>
          </div>
        ) : (
          <div className="xp-footer">
            <button type="button" className="btn" onClick={tryClose}>Xong</button>
            <button type="button" className="btn primary" ref={copyBtn} data-autofocus onClick={() => void copy()}>{copied ? "Đã sao chép" : "Sao chép liên kết"}</button>
          </div>
        )}
      </div>
    </Modal>
  );
}
