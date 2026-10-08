"use client";

import { Fragment, useState } from "react";
import Link from "next/link";
import type { AuditRow } from "@/lib/http-types";
import { useA } from "../console/context";
import { actionLabel, ago, fmtDate, StateView } from "../../ui";

export function AuditTable({ rows, compact }: { rows: AuditRow[]; compact?: boolean }) {
  const A = useA();
  const [open, setOpen] = useState<string | null>(null);
  if (!rows.length) return <StateView kind="empty" title="Chưa có hoạt động"/>;
  return (
    <table className="table">
      <thead><tr><th>Thời gian</th><th>Người thực hiện</th><th>Hành động</th><th>Đối tượng</th>{compact ? null : <><th>IP</th><th>Request ID</th></>}</tr></thead>
      <tbody>{rows.map((r) => (<Fragment key={r.id}>
        <tr className="clickRow" onClick={() => setOpen(open === r.id ? null : r.id)}>
          <td title={fmtDate(r.createdAt)}>{ago(r.createdAt)}</td><td>{r.actor ?? (r.actorId ? r.actorId.slice(0, 8) : <span className="muted">Hệ thống</span>)}</td>
          <td><b>{actionLabel(r.action)}</b><small className="code">{r.action}</small></td>
          <td>{r.projectId ? <Link href={A(`/applications/${r.projectId}`)} onClick={(e) => e.stopPropagation()}>{r.resourceType}</Link> : r.resourceType}</td>
          {compact ? null : <><td>{r.ipAddress ?? "—"}</td><td className="code">{r.requestId ?? "—"}</td></>}
        </tr>
        {open === r.id ? <tr className="detailRow"><td colSpan={compact ? 4 : 6}><pre>{JSON.stringify({ resourceId: r.resourceId, old: r.oldValue && JSON.parse(r.oldValue), new: r.newValue && JSON.parse(r.newValue) }, null, 2)}</pre></td></tr> : null}
      </Fragment>))}</tbody>
    </table>
  );
}
