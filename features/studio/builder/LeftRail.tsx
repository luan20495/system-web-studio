"use client";
import { Tabs, tabPanelProps } from "./ui/primitives";
import type { ReactNode } from "react";

export const RAIL = [
  { id: "pages", label: "Trang" }, { id: "components", label: "Thành phần" }, { id: "data", label: "Dữ liệu" }, { id: "forms", label: "Biểu mẫu" },
  { id: "actions", label: "Hành động" }, { id: "workflows", label: "Workflow" }, { id: "theme", label: "Giao diện" }, { id: "ai", label: "AI" },
] as const;
export type RailId = (typeof RAIL)[number]["id"];

export function LeftRail({ value, onChange, children }: { value: RailId; onChange: (id: RailId) => void; children: ReactNode }) {
  return (
    <aside className="bx-left" aria-label="Công cụ dựng ứng dụng">
      <Tabs label="Công cụ" orientation="vertical" idPrefix="rail" items={RAIL.map((r) => ({ id: r.id, label: r.label }))} value={value} onChange={(id) => onChange(id as RailId)}/>
      <div {...tabPanelProps("rail", value)} className="bx-left-panel">{children}</div>
    </aside>
  );
}
