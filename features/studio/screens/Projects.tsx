"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/http-api";
import { useSession } from "../../session";
import { useLoad } from "../../useLoad";
import { ErrorState, Pager, StateView } from "../../ui";
import { S } from "../base";
import { useStudio } from "../studioContext";
import { ProjectCard } from "./ProjectCard";

// ------------------------------------------------------------------ projects
export function Projects() {
  const params = useSearchParams(); const { me } = useSession(); const { workspaceId } = useStudio();
  const [scope, setScope] = useState<"all" | "owned" | "shared">("all"); const [page, setPage] = useState(0);
  const [q, setQ] = useState(params.get("q") ?? ""); const [query, setQuery] = useState(params.get("q") ?? "");
  useEffect(() => { const v = params.get("q") ?? ""; setQ(v); setQuery(v); setPage(0); }, [params]);
  const { data, error, loading, reload } = useLoad(() => api.projectsPage(workspaceId, page, 12, query, scope), [workspaceId, page, query, scope]);
  return (<>
    <div className="pageHead"><div><h1>Ứng dụng</h1><p>Ứng dụng trong workspace hiện tại mà bạn có quyền xem.</p></div><Link className="btn primary" href={S("/new")}>+ Tạo ứng dụng</Link></div>
    <div className="row between wrap">
      <div className="tabs" role="tablist">{([["all", "Tất cả"], ["owned", "Của tôi"], ["shared", "Được chia sẻ với tôi"]] as const).map(([k, l]) => <button key={k} role="tab" aria-selected={scope === k} className={scope === k ? "active" : ""} onClick={() => { setScope(k); setPage(0); }}>{l}</button>)}</div>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}><input aria-label="Tìm theo tên" placeholder="Tìm theo tên" value={q} onChange={(e) => setQ(e.target.value)}/><button className="btn">Tìm</button></form>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0
      ? <StateView kind="empty" title={query ? "Không có ứng dụng phù hợp" : "Chưa có ứng dụng"} action={<Link className="btn primary" href={S("/new")}>Tạo ứng dụng</Link>}/>
      : (<><div className="projectGrid">{data!.items.map((p) => <ProjectCard key={p.id} p={p} mine={p.ownerUserId === me!.id}/>)}</div><Pager page={page} size={12} total={data!.total} onPage={setPage}/></>)}
  </>);
}
