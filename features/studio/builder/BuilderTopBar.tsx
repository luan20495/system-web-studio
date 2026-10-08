"use client";
import type { ReactNode } from "react";

type Device = "desktop" | "tablet" | "mobile";
const DEVICES: { id: Device; label: string }[] = [{ id: "desktop", label: "Máy tính" }, { id: "tablet", label: "Máy tính bảng" }, { id: "mobile", label: "Điện thoại" }];

export function BuilderTopBar({ name, meta, save, appMode, onAppMode, device, onDevice, leading, modeTabs, canShare, shareReason, onShare, canPublish, publishReason, publishBusy, issues, onPublish, trailing, onRetrySave }: {
  name: string; meta: string; save: { state: "saved" | "saving" | "error"; at: Date | null }; appMode: "EDIT" | "TEST"; onAppMode: (m: "EDIT" | "TEST") => void;
  device: Device; onDevice: (d: Device) => void; leading?: ReactNode; modeTabs?: ReactNode; canShare: boolean; shareReason: string; onShare: () => void;
  canPublish: boolean; publishReason: string; publishBusy: boolean; issues: { block: number; warn: number }; onPublish: () => void; trailing?: ReactNode;
  /** set only while a failed save is waiting: re-sends the SAME edit (safe: the server checks the revision, so an edit that did land answers 409 and the page reloads) */
  onRetrySave?: () => void;
}) {
  return (
    <header className="topbar bx-top" aria-label="Thanh công cụ Builder">
      <div className="brand">
        {leading}
        <div><div className="projectName">{name}</div><div className="projectMeta">{meta}</div></div>
        <span className={`saveState ${save.state}`} role="status" aria-live="polite">
          {save.state === "saving" ? "Đang lưu…" : save.state === "error" ? "Lưu thất bại" : `Đã lưu${save.at ? ` ${save.at.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}` : ""}`}
        </span>
        {save.state === "error" && onRetrySave ? <button type="button" className="smallButton" data-testid="retry-save" onClick={onRetrySave}>Thử lại</button> : null}
      </div>
      <div className="bx-top-center">
        {modeTabs}
        <div className="segmented" role="group" aria-label="Chế độ ứng dụng">
          <button type="button" className={appMode === "EDIT" ? "active" : ""} aria-pressed={appMode === "EDIT"} onClick={() => onAppMode("EDIT")}>Chỉnh sửa</button>
          <button type="button" className={appMode === "TEST" ? "active" : ""} aria-pressed={appMode === "TEST"} onClick={() => onAppMode("TEST")}>Dùng thử</button>
        </div>
        <div className="segmented" role="group" aria-label="Kích thước màn hình xem trước">
          {DEVICES.map((d) => <button type="button" key={d.id} className={device === d.id ? "active" : ""} aria-pressed={device === d.id} onClick={() => onDevice(d.id)}>{d.label}</button>)}
        </div>
      </div>
      <div className="topActions">
        {trailing}
        <button type="button" className="button ghost" disabled={!canShare} title={canShare ? "Quản lý thành viên của ứng dụng" : shareReason} onClick={onShare}>Chia sẻ</button>
        <button type="button" className="button primary" disabled={!canPublish || publishBusy} title={canPublish ? "Kiểm tra rồi xuất bản phiên bản hiện tại" : publishReason} onClick={onPublish}>
          Xuất bản{issues.block ? <span className="bx-badge bad" aria-label={`${issues.block} lỗi chặn xuất bản`}>{issues.block}</span> : issues.warn ? <span className="bx-badge warn" aria-label={`${issues.warn} cảnh báo`}>{issues.warn}</span> : null}
        </button>
      </div>
    </header>
  );
}
