"use client";
/**
 * Review (approve) dialog of a code change. Replaces `window.prompt`, whose Cancel (null) used to be read as "no comment" and APPROVED the change.
 * Rules: nothing is sent unless the person presses "Duyệt"; Escape, "Hủy" and the close path never send a request; one request per press (busy lock).
 * LOCAL to features/studio (the shared dialog hook lives in @xweb/ui and is only imported here); S3 may unify it with a shared ConfirmDialog later.
 * There is deliberately no "Từ chối" button: the server has no reject endpoint for a code change (only approve and discard), so none is invented here.
 */
import { useId, useState } from "react";
import { useDialog } from "@/components/useDialog";

export function ReviewDialog({ summary, busy, onApprove, onClose }: { summary: string; busy: boolean; onApprove: (comment?: string) => void; onClose: () => void }) {
  const dialog = useDialog("Duyệt thay đổi", busy ? null : onClose);   // cannot be dismissed with Escape while the request is in flight
  const [comment, setComment] = useState("");
  const field = useId();
  return (
    <div className="overlay modalOverlay">
      <div className="modal" {...dialog.props} data-testid="review-dialog" aria-busy={busy}>
        <h2 id={dialog.titleId}>Duyệt thay đổi này?</h2>
        <p><b>{summary}</b></p>
        <p className="hint">Sau khi duyệt, thay đổi có thể được hợp nhất vào main. “Hủy” hoặc phím Esc đóng hộp thoại và không gửi gì.</p>
        <label htmlFor={field} className="hint">Nhận xét (tuỳ chọn)</label>
        <textarea id={field} value={comment} maxLength={500} rows={3} disabled={busy} onChange={(e) => setComment(e.target.value)} style={{ width: "100%", marginTop: 6 }}/>
        <div className="modalActions">
          <button type="button" className="button ghost" disabled={busy} onClick={onClose}>Hủy</button>
          <button type="button" className="button primary" disabled={busy} onClick={() => { if (!busy) onApprove(comment.trim() || undefined); }}>{busy ? "Đang duyệt…" : "Duyệt"}</button>
        </div>
      </div>
    </div>
  );
}
