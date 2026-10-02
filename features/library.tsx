"use client";
// Shared pieces for templates and contributed blocks (Studio and Admin Console).
import { useMemo } from "react";
import type { BlockDto, BlockReview, CheckResult, PageSchema, RegistryComponent } from "@/lib/http-types";
import { renderSchemaDocument } from "@/lib/schema-preview";
import { fmtDate, Pill } from "./ui";

/** Static, script-less render of a page schema (sandbox=""), scaled down. Templates and blocks carry no images (cleared on save). */
export function SchemaThumb({ schema, title, tall }: { schema: PageSchema; title: string; tall?: boolean }) {
  const doc = useMemo(() => renderSchemaDocument(schema, null), [schema]);
  return <div className={`thumb${tall ? " tall" : ""}`}><iframe title={title} sandbox="" srcDoc={doc} loading="lazy" tabIndex={-1} aria-hidden="true"/></div>;
}

export const blockPage = (b: BlockDto): PageSchema | null => (b.current ? {
  page: "block-preview", sections: [{ id: "block-preview", type: b.baseComponent, componentVersion: b.current.baseComponentVersion, props: b.current.props }]
} : null);

export const BLOCK_STATUS: Record<string, string> = {
  PRIVATE: "Riêng tư", SUBMITTED: "Đã gửi", VALIDATING: "Đang kiểm tra", REVIEW: "Chờ duyệt", APPROVED: "Đã duyệt", DEPRECATED: "Ngừng dùng",
  DRAFT: "Bản nháp", REJECTED: "Bị từ chối", SUPERSEDED: "Đã thay thế"
};
export const BlockStatus = ({ status }: { status: string }) => <Pill value={status} label={BLOCK_STATUS[status] ?? status}/>;

const CHECK_LABEL: Record<string, string> = {
  registry: "Component gốc đã duyệt", props: "Thuộc tính hợp lệ", "no-files": "Không gắn tệp của dự án", size: "Kích thước", "text-safety": "Không có mã nhúng", name: "Tên không trùng", version: "Phiên bản"
};
export function CheckList({ checks }: { checks: CheckResult[] }) {
  return <ul className="checkList" aria-label="Kết quả kiểm tra tự động">{checks.map((c) =>
    <li key={c.check} className={c.ok ? "ok" : "bad"}><span aria-hidden="true">{c.ok ? "✓" : "✕"}</span><b>{CHECK_LABEL[c.check] ?? c.check}</b><small>{c.message}</small></li>)}</ul>;
}

const DECISION_LABEL: Record<string, string> = {
  SUBMIT: "Gửi duyệt", VALIDATION_PASSED: "Kiểm tra tự động: đạt", VALIDATION_FAILED: "Kiểm tra tự động: không đạt", WITHDRAW: "Rút lại",
  APPROVE: "Phê duyệt", REJECT: "Từ chối", DEPRECATE: "Ngừng dùng", RESTORE: "Khôi phục"
};
export function ReviewTimeline({ reviews }: { reviews: BlockReview[] }) {
  if (!reviews.length) return <p className="muted">Chưa có hoạt động duyệt.</p>;
  return <ol className="timeline">{reviews.map((r) => <li key={r.id}><b>{DECISION_LABEL[r.decision] ?? r.decision}</b> <small>v{r.version} · {r.actor ?? "hệ thống"} · {fmtDate(r.createdAt)}</small>
    {r.comment ? <p>{r.comment}</p> : null}</li>)}</ol>;
}

/** Blocks can only be inserted when their base component is still approved and has a preview renderer. */
export function insertable(b: BlockDto, registry: RegistryComponent[], notRendered: Set<string>) {
  return !!b.current && b.status !== "DEPRECATED" && registry.some((c) => c.id === b.baseComponent && c.status === "ACTIVE") && !notRendered.has(b.baseComponent);
}
