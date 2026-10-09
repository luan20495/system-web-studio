"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/http-api";
import { sectionLabel } from "@/components/SectionInspector";
import { LoadGate, useAction } from "@xweb/ui";
import { useSession } from "../../session";
import { useLoad } from "../../useLoad";
import { Card, ErrorState, errText, num, StateView, usd } from "../../ui";
import { S } from "../base";
import { useStudio } from "../studioContext";
import { ProjectCard } from "./ProjectCard";

// ------------------------------------------------------------------ home
export function Home() {
  const router = useRouter(); const { me } = useSession(); const { workspaceId } = useStudio();
  const recent = useLoad(() => api.projectsPage(workspaceId, 0, 6), [workspaceId]);
  const usage = useLoad(() => api.myUsage(), []);
  const comps = useLoad(() => api.components(), []);
  // Creating a project has NO canonical permission code (PROJECT_CREATE is a server-internal storage constant that /auth/me does not expose), so the UI cannot know in advance and
  // must not guess from a role name: the form is offered and the server decides (403 → a plain message). Handoff H-C1-05 asks for a resolved capability.
  const [idea, setIdea] = useState(""); const [err, setErr] = useState<string | null>(null); const [leaving, setLeaving] = useState(false);
  // M-020: Ctrl+Enter, Enter + click and a double click all arrive before React re-renders `busy`; the action decides from a ref and sends ONE createProject
  const create = useAction((_ctx, text: string) => api.createProject(workspaceId, text.length > 60 ? `${text.slice(0, 57)}…` : text));
  const busy = create.busy || leaving;
  async function start(e: FormEvent) {
    e.preventDefault(); const text = idea.trim(); if (!text) return;
    setErr(null);
    const r = await create.run(text);
    if (r.status === "ok") { setLeaving(true); router.push(S(`/projects/${r.value.id}/ai?prompt=${encodeURIComponent(text)}`)); }
    else if (r.status === "error") setErr(r.error instanceof ApiError && r.error.status === 403 ? "Bạn không có quyền tạo ứng dụng trong workspace này (máy chủ từ chối)." : errText(r.error, "Không tạo được ứng dụng."));
  }
  const u = usage.data;
  return (<>
    <section className="homeHero">
      <h1>Bạn muốn xây dựng gì?</h1>
      <p>Mô tả ý tưởng; Studio tạo website từ component đã duyệt của công ty, rồi bạn chỉnh bằng AI hoặc trực quan.</p>
        <form className="bigPrompt" onSubmit={(e) => void start(e)}>
          <textarea aria-label="Mô tả ứng dụng muốn tạo" placeholder="Ví dụ: Website giới thiệu máy lọc nước, có bảng so sánh 3 sản phẩm…" value={idea} onChange={(e) => setIdea(e.target.value)} maxLength={2000}
            onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void start(e); }}/>
          <div className="row between"><small className="muted">Hiện hỗ trợ loại ứng dụng: Website (một trang). Ctrl/⌘ + Enter để tạo.</small><button className="btn primary" disabled={busy || !idea.trim()}>{busy ? "Đang tạo…" : "Tạo bằng AI"}</button></div>
          {err ? <p className="formError" role="alert">{err}</p> : null}
        </form>
    </section>
    <div className="kpiGrid">
      {usage.error && !u
        ? <div className="kpi"><div className="kpiLabel">Mức dùng AI của bạn</div><div className="kpiValue"><ErrorState error={usage.error} retry={usage.reload} compact title="Chưa tải được mức dùng AI"/></div></div>
        : (<>
          <div className="kpi"><div className="kpiLabel">Lượt AI hôm nay</div>
            <div className="kpiValue">{u ? (u.aiConfigured ? (u.aiRequestsLimit > 0 ? `${num(u.aiRequestsUsed)} / ${num(u.aiRequestsLimit)}` : `${num(u.aiRequestsUsed)} (không giới hạn)`) : "Chế độ thử nghiệm") : "…"}</div>
            <div className="kpiHint">{u ? (u.aiConfigured ? (u.aiWindowResetsInSeconds ? `Làm mới sau ${Math.ceil(u.aiWindowResetsInSeconds / 3600)} giờ` : "Chưa dùng lượt nào") : "AI hiện chưa được quản trị viên bật") : ""}</div></div>
          <div className="kpi"><div className="kpiLabel">Token AI 24 giờ qua</div>
            <div className="kpiValue">{u ? (u.aiConfigured || u.tokensLast24h ? `${num(u.tokensLast24h)}${u.tokensLimitPerDay ? ` / ${num(u.tokensLimitPerDay)}` : ""}` : "—") : "…"}</div>
            <div className="kpiHint">{u ? (u.usageLast30Days?.calls ? `30 ngày: ${num(u.usageLast30Days.totalTokens)} token · ${usd(u.usageLast30Days.costUsd)} (số liệu nhà cung cấp)` : "Chưa gọi model thật nào; Chế độ thử nghiệm không tính token") : ""}</div></div>
          <div className="kpi"><div className="kpiLabel">Prompt hôm nay</div><div className="kpiValue">{u ? num(u.promptsToday) : "…"}</div><div className="kpiHint">{u ? `Tối đa ${u.promptsPerMinute}/phút` : ""}</div></div>
        </>)}
      <div className="kpi"><div className="kpiLabel">Ứng dụng trong workspace</div>
        <div className="kpiValue"><LoadGate load={recent} compact label="số ứng dụng" errorTitle="Chưa tải được số ứng dụng">{(d) => <>{num(d.total)}</>}</LoadGate></div></div>
      <div className="kpi"><div className="kpiLabel">Component của công ty</div>
        <div className="kpiValue"><LoadGate load={comps} compact label="số thành phần" errorTitle="Chưa tải được số thành phần">{(d) => <>{d.length}</>}</LoadGate></div></div>
    </div>
    <Card title="Ứng dụng gần đây" actions={<Link className="btn sm" href={S("/projects")}>Xem tất cả</Link>}>
      {recent.error ? <ErrorState error={recent.error} retry={recent.reload}/> : !recent.data ? <StateView kind="loading"/> : recent.data.items.length === 0
        ? <StateView kind="empty" title="Chưa có ứng dụng nào" detail={<p>Bắt đầu bằng ô mô tả phía trên hoặc <Link href={S("/new")}>tạo ứng dụng</Link>.</p>}/>
        : <div className="projectGrid">{recent.data.items.map((p) => <ProjectCard key={p.id} p={p} mine={p.ownerUserId === me!.id}/>)}</div>}
    </Card>
    <Card title="Component dùng chung" actions={<Link className="btn sm" href={S("/components")}>Thư viện</Link>}>
      <LoadGate load={comps} compact label="thành phần dùng chung" errorTitle="Chưa tải được danh sách thành phần">
        {(d) => <div className="chipRow">{d.filter((c) => c.status === "ACTIVE").map((c) => <span key={c.id} className="tag">{sectionLabel(c.id, c.name)} <small>{num(c.usedInProjects ?? 0)} ứng dụng</small></span>)}</div>}
      </LoadGate>
    </Card>
  </>);
}
