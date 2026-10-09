"use client";

import Link from "next/link";
import type { AuditRow } from "@/lib/http-types";
import { DisclosureRow } from "@xweb/ui";
import { useA } from "../console/context";
import { parseJsonOr } from "../safeJson";
import { actionLabel, ago, fmtDate, StateView } from "../../ui";

/** audit rows expand with a real button (aria-expanded, Enter / Space, a tab stop); a click on the row still toggles (M-029) */
export function AuditTable({ rows, compact }: { rows: AuditRow[]; compact?: boolean }) {
  const A = useA();
  if (!rows.length) return <StateView kind="empty" title="Chưa có hoạt động"/>;
  return (
    <table className="table">
      <thead><tr><th><span className="srOnly">Chi tiết</span></th><th>Thời gian</th><th>Người thực hiện</th><th>Hành động</th><th>Đối tượng</th>{compact ? null : <><th>IP</th><th>Mã yêu cầu</th></>}</tr></thead>
      <tbody>{rows.map((r) => (
        <DisclosureRow key={r.id} colSpan={compact ? 5 : 7} label={`Chi tiết: ${actionLabel(r.action)}, ${fmtDate(r.createdAt)}`}
          cells={<>
            <td title={fmtDate(r.createdAt)}>{ago(r.createdAt)}</td><td>{r.actor ?? (r.actorId ? r.actorId.slice(0, 8) : <span className="muted">Hệ thống</span>)}</td>
            <td><b>{actionLabel(r.action)}</b><small className="code">{r.action}</small></td>
            <td>{r.projectId ? <Link href={A(`/applications/${r.projectId}`)}>{r.resourceType}</Link> : r.resourceType}</td>
            {compact ? null : <><td>{r.ipAddress ?? "—"}</td><td className="code">{r.requestId ?? "—"}</td></>}
          </>}
          detail={<pre>{JSON.stringify({ resourceId: r.resourceId, old: parseJsonOr(r.oldValue), new: parseJsonOr(r.newValue) }, null, 2)}</pre>}/>
      ))}</tbody>
    </table>
  );
}
