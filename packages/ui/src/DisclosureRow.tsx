"use client";
/**
 * DisclosureRow (M-029): a table row that expands to show detail (audit trail, alert, deployment). The old rows expanded on a mouse click of the <tr> only:
 * no role, no `aria-expanded`, no keyboard. Here the first cell holds a real <button aria-expanded aria-controls> (Enter / Space work, it is a tab stop,
 * a screen reader says "Xem chi tiết …, collapsed"); clicking the row anywhere else still toggles as a mouse convenience (clicks on links / buttons inside it do not).
 *
 *   <thead><tr><th><span className="srOnly">Chi tiết</span></th><th>Thời gian</th>…</tr></thead>
 *   <tbody><DisclosureRow label={`Chi tiết sự kiện ${a.action}`} cells={<><td>…</td><td>…</td></>} colSpan={5} detail={<AuditDetail a={a}/>}/></tbody>
 *
 * The detail row is rendered only while open; `aria-controls` points at it only then (a reference to a missing id is an error).
 * Controlled (`open` + `onToggle`) or uncontrolled (`defaultOpen`).
 */
import { useId, useState, type ReactNode, type MouseEvent } from "react";
import { ChevronDown, ChevronRight } from "./icons";

export function DisclosureRow({ label, cells, colSpan, detail, open: openProp, defaultOpen = false, onToggle, className }: {
  /** what the button does, said for THIS row: "Chi tiết sự kiện Đăng nhập lúc 10:42" (it is the button's accessible name) */ label: string;
  /** the data cells of the summary row (the toggle cell is added in front) */ cells: ReactNode;
  /** columns of the table INCLUDING the toggle column, for the detail row */ colSpan: number; detail: ReactNode;
  open?: boolean; defaultOpen?: boolean; onToggle?: (open: boolean) => void; className?: string;
}) {
  const id = useId(); const [own, setOwn] = useState(defaultOpen);
  const open = openProp ?? own;
  const toggle = () => { const next = !open; if (openProp === undefined) setOwn(next); onToggle?.(next); };
  const rowClick = (e: MouseEvent) => { if ((e.target as HTMLElement).closest("a,button,input,select,textarea,label,summary,[role=button]")) return; toggle(); };
  const Icon = open ? ChevronDown : ChevronRight;
  return (
    <>
      <tr className={`xp-disc${open ? " open" : ""}${className ? ` ${className}` : ""}`} onClick={rowClick}>
        <td className="xp-discToggle"><button type="button" className="xp-discBtn" aria-expanded={open} aria-controls={open ? id : undefined} onClick={toggle}><Icon size={16} aria-hidden="true"/><span className="srOnly">{label}</span></button></td>
        {cells}
      </tr>
      {open ? <tr id={id} className="xp-discDetail"><td colSpan={colSpan}>{detail}</td></tr> : null}
    </>
  );
}
