"use client";

import Link from "next/link";
import type { ApiProject } from "@/lib/http-types";
import { ago, Pill } from "../../ui";
import { S } from "../base";

export function ProjectCard({ p, mine }: { p: ApiProject; mine: boolean }) {
  return (
    <Link className="projectCard" href={S(`/projects/${p.id}`)}>
      <div className="projectThumb" aria-hidden="true"><span/></div>
      <div className="projectInfo"><b>{p.name}</b><small>{p.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"} · cập nhật {ago(p.updatedAt)}</small>
        <div className="row">{mine ? <Pill value="ACTIVE" label="Của tôi"/> : <Pill value="PRIVATE" label="Được chia sẻ"/>}<Pill value="PRIVATE" label="Website"/>
          {p.status === "ARCHIVED" ? <Pill value="ARCHIVED" label="Đã lưu trữ"/> : null}</div></div>
    </Link>
  );
}
