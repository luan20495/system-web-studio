"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { api } from "@/lib/http-api";
import type { ApiProject } from "@/lib/http-types";
import { sectionLabel } from "@/components/SectionInspector";
import { useSession } from "../session";
import { rememberPortal } from "../routing";
import { useLoad } from "../useLoad";
import { actionLabel, ago, Card, ComingSoon, ErrorState, errText, NavLink, num, Pager, Pill, StateView } from "../ui";
import { ProjectWorkspace } from "./ProjectWorkspace";

type StudioCtx = { workspaceId: string; setWorkspaceId: (id: string) => void };
const Ctx = createContext<StudioCtx | null>(null);
const useStudio = () => useContext(Ctx)!;

export function StudioApp({ seg }: { seg: string[] }) {
  const { me } = useSession();
  const [workspaceId, setWs] = useState(() => {
    try { const saved = localStorage.getItem("studio-ws"); if (saved && me!.workspaces.some((w) => w.id === saved)) return saved; } catch { /* ignore */ }
    return me!.workspaces[0].id;
  });
  const setWorkspaceId = (id: string) => { setWs(id); try { localStorage.setItem("studio-ws", id); } catch { /* ignore */ } };
  // the project workspace is a full-screen editor; everything else lives in the Studio shell
  if (seg[0] === "projects" && seg[1]) return <ProjectWorkspace projectId={seg[1]} view={seg[2]}/>;
  return (
    <Ctx.Provider value={{ workspaceId, setWorkspaceId }}>
      <div className="shell studio-shell">
        <StudioSidebar active={seg[0] ?? ""}/>
        <div className="shellMain"><StudioHeader/><main className="page" id="main" tabIndex={0}>{route(seg)}</main></div>
      </div>
    </Ctx.Provider>
  );
}

function route(seg: string[]): ReactNode {
  switch (seg[0] ?? "") {
    case "": return <Home/>;
    case "projects": return <Projects/>;
    case "new": return <NewApp/>;
    case "templates": return <Templates/>;
    case "components": return <Components/>;
    case "activity": return <Activity/>;
    default: return <StateView kind="notfound"/>;
  }
}

function StudioSidebar({ active }: { active: string }) {
  const nav: [string, string, string][] = [["", "Trang chủ", "⌂"], ["projects", "Ứng dụng", "▤"], ["templates", "Templates", "▧"], ["components", "Components", "◇"], ["activity", "Hoạt động", "≡"]];
  return (
    <aside className="sidebar" aria-label="Điều hướng Studio">
      <div className="sideBrand"><span className="logoMark" aria-hidden="true">◆</span><div><b>Company Builder Studio</b><small>AI Software Factory</small></div></div>
      <Link className="btn primary block" href="/studio/new">+ Tạo ứng dụng</Link>
      <nav>{nav.map(([k, l, i]) => <NavLink key={k} href={`/studio${k ? `/${k}` : ""}`} active={active === k} icon={i}>{l}</NavLink>)}</nav>
    </aside>
  );
}

function StudioHeader() {
  const { me, logout } = useSession(); const { workspaceId, setWorkspaceId } = useStudio(); const router = useRouter();
  const [q, setQ] = useState("");
  return (
    <header className="topHeader">
      <form className="search" role="search" onSubmit={(e) => { e.preventDefault(); router.push(`/studio/projects?q=${encodeURIComponent(q)}`); }}>
        <input aria-label="Tìm ứng dụng" placeholder="Tìm ứng dụng…" value={q} onChange={(e) => setQ(e.target.value)}/>
      </form>
      <div className="row">
        {me!.workspaces.length > 1 ? <select aria-label="Workspace" value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)}>{me!.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</select>
          : <span className="muted">{me!.workspaces[0].name}</span>}
        {me!.systemAdmin ? <Link className="btn sm" href="/admin" onClick={() => rememberPortal("admin")}>Admin Console</Link> : null}
        <span className="avatar" title={me!.displayName} aria-label={`Tài khoản: ${me!.displayName}`}>{me!.displayName.slice(0, 2).toUpperCase()}</span>
        <button className="btn sm ghost" onClick={() => void logout()}>Đăng xuất</button>
      </div>
    </header>
  );
}

function ProjectCard({ p, mine }: { p: ApiProject; mine: boolean }) {
  return (
    <Link className="projectCard" href={`/studio/projects/${p.id}`}>
      <div className="projectThumb" aria-hidden="true"><span/></div>
      <div className="projectInfo"><b>{p.name}</b><small>{p.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"} · cập nhật {ago(p.updatedAt)}</small>
        <div className="row">{mine ? <Pill value="ACTIVE" label="Của tôi"/> : <Pill value="PRIVATE" label="Được chia sẻ"/>}<Pill value="PRIVATE" label="Website"/></div></div>
    </Link>
  );
}

// ------------------------------------------------------------------ home
function Home() {
  const router = useRouter(); const { me } = useSession(); const { workspaceId } = useStudio();
  const recent = useLoad(() => api.projectsPage(workspaceId, 0, 6), [workspaceId]);
  const usage = useLoad(() => api.myUsage(), []);
  const comps = useLoad(() => api.components(), []);
  const role = me!.workspaces.find((w) => w.id === workspaceId)?.role;
  const canCreate = role === "WORKSPACE_ADMIN" || role === "EDITOR" || role === "ADMIN";
  const [idea, setIdea] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  async function start(e: FormEvent) {
    e.preventDefault(); const text = idea.trim(); if (!text) return;
    setBusy(true); setErr(null);
    try {
      const p = await api.createProject(workspaceId, text.length > 60 ? `${text.slice(0, 57)}…` : text);
      router.push(`/studio/projects/${p.id}/ai?prompt=${encodeURIComponent(text)}`);
    } catch (x) { setErr(errText(x, "Không tạo được ứng dụng.")); setBusy(false); }
  }
  const u = usage.data;
  return (<>
    <section className="homeHero">
      <h1>Bạn muốn xây dựng gì?</h1>
      <p>Mô tả ý tưởng; Studio tạo website từ component đã duyệt của công ty, rồi bạn chỉnh bằng AI hoặc trực quan.</p>
      {canCreate ? (
        <form className="bigPrompt" onSubmit={(e) => void start(e)}>
          <textarea aria-label="Mô tả ứng dụng muốn tạo" placeholder="Ví dụ: Website giới thiệu máy lọc nước, có bảng so sánh 3 sản phẩm…" value={idea} onChange={(e) => setIdea(e.target.value)} maxLength={2000}
            onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void start(e); }}/>
          <div className="row between"><small className="muted">Hiện hỗ trợ loại ứng dụng: Website (một trang). Ctrl/⌘ + Enter để tạo.</small><button className="btn primary" disabled={busy || !idea.trim()}>{busy ? "Đang tạo…" : "Tạo bằng AI"}</button></div>
          {err ? <p className="formError" role="alert">{err}</p> : null}
        </form>
      ) : <p className="notice">Vai trò của bạn trong workspace này không cho phép tạo ứng dụng mới.</p>}
    </section>
    <div className="kpiGrid">
      <div className="kpi"><div className="kpiLabel">Lượt AI hôm nay</div>
        <div className="kpiValue">{u ? (u.aiConfigured ? `${num(u.aiRequestsUsed)} / ${num(u.aiRequestsLimit)}` : "Mô phỏng") : "…"}</div>
        <div className="kpiHint">{u ? (u.aiConfigured ? (u.aiWindowResetsInSeconds ? `Làm mới sau ${Math.ceil(u.aiWindowResetsInSeconds / 3600)} giờ` : "Chưa dùng lượt nào") : "Chưa cấu hình AI thật, không tính lượt") : ""}</div></div>
      <div className="kpi"><div className="kpiLabel">Prompt hôm nay</div><div className="kpiValue">{u ? num(u.promptsToday) : "…"}</div><div className="kpiHint">{u ? `Tối đa ${u.promptsPerMinute}/phút` : ""}</div></div>
      <div className="kpi"><div className="kpiLabel">Ứng dụng trong workspace</div><div className="kpiValue">{recent.data ? num(recent.data.total) : "…"}</div></div>
      <div className="kpi"><div className="kpiLabel">Component của công ty</div><div className="kpiValue">{comps.data ? comps.data.length : "…"}</div></div>
    </div>
    <Card title="Ứng dụng gần đây" actions={<Link className="btn sm" href="/studio/projects">Xem tất cả</Link>}>
      {recent.error ? <ErrorState error={recent.error} retry={recent.reload}/> : !recent.data ? <StateView kind="loading"/> : recent.data.items.length === 0
        ? <StateView kind="empty" title="Chưa có ứng dụng nào" detail={<p>Bắt đầu bằng ô mô tả phía trên hoặc <Link href="/studio/new">tạo ứng dụng</Link>.</p>}/>
        : <div className="projectGrid">{recent.data.items.map((p) => <ProjectCard key={p.id} p={p} mine={p.ownerUserId === me!.id}/>)}</div>}
    </Card>
    <Card title="Component dùng chung" actions={<Link className="btn sm" href="/studio/components">Thư viện</Link>}>
      {comps.data ? <div className="chipRow">{comps.data.filter((c) => c.status === "ACTIVE").map((c) => <span key={c.id} className="tag">{sectionLabel(c.id, c.name)} <small>{num(c.usedInProjects ?? 0)} ứng dụng</small></span>)}</div> : <StateView kind="loading"/>}
    </Card>
  </>);
}

// ------------------------------------------------------------------ projects
function Projects() {
  const params = useSearchParams(); const { me } = useSession(); const { workspaceId } = useStudio();
  const [scope, setScope] = useState<"all" | "owned" | "shared">("all"); const [page, setPage] = useState(0);
  const [q, setQ] = useState(params.get("q") ?? ""); const [query, setQuery] = useState(params.get("q") ?? "");
  useEffect(() => { const v = params.get("q") ?? ""; setQ(v); setQuery(v); setPage(0); }, [params]);
  const { data, error, loading, reload } = useLoad(() => api.projectsPage(workspaceId, page, 12, query, scope), [workspaceId, page, query, scope]);
  return (<>
    <div className="pageHead"><div><h1>Ứng dụng</h1><p>Ứng dụng trong workspace hiện tại mà bạn có quyền xem.</p></div><Link className="btn primary" href="/studio/new">+ Tạo ứng dụng</Link></div>
    <div className="row between wrap">
      <div className="tabs" role="tablist">{([["all", "Tất cả"], ["owned", "Của tôi"], ["shared", "Được chia sẻ với tôi"]] as const).map(([k, l]) => <button key={k} role="tab" aria-selected={scope === k} className={scope === k ? "active" : ""} onClick={() => { setScope(k); setPage(0); }}>{l}</button>)}</div>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q); }}><input aria-label="Tìm theo tên" placeholder="Tìm theo tên" value={q} onChange={(e) => setQ(e.target.value)}/><button className="btn">Tìm</button></form>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.items.length === 0
      ? <StateView kind="empty" title={query ? "Không có ứng dụng phù hợp" : "Chưa có ứng dụng"} action={<Link className="btn primary" href="/studio/new">Tạo ứng dụng</Link>}/>
      : (<><div className="projectGrid">{data!.items.map((p) => <ProjectCard key={p.id} p={p} mine={p.ownerUserId === me!.id}/>)}</div><Pager page={page} size={12} total={data!.total} onPage={setPage}/></>)}
  </>);
}

// ------------------------------------------------------------------ create
function NewApp() {
  const router = useRouter(); const { workspaceId } = useStudio();
  const [name, setName] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const types: [string, string, string, boolean][] = [["website", "Website", "Trang giới thiệu/landing một trang từ component đã duyệt.", true],
    ["dashboard", "Dashboard", "Biểu đồ và số liệu từ hệ thống nội bộ.", false], ["internal", "Internal Tool", "Công cụ nội bộ có biểu mẫu và bảng dữ liệu.", false],
    ["workflow", "Workflow", "Quy trình phê duyệt nhiều bước.", false]];
  async function create(e: FormEvent) {
    e.preventDefault(); if (!name.trim()) return; setBusy(true); setErr(null);
    try { const p = await api.createProject(workspaceId, name.trim()); router.push(`/studio/projects/${p.id}/ai`); } catch (x) { setErr(errText(x, "Không tạo được ứng dụng.")); setBusy(false); }
  }
  return (<>
    <div className="pageHead"><div><h1>Tạo ứng dụng</h1><p>Chọn loại ứng dụng. Hiện chỉ Website hoạt động; các loại khác cần kiến trúc sinh mã (chưa triển khai).</p></div></div>
    <div className="typeGrid" role="radiogroup" aria-label="Loại ứng dụng">{types.map(([k, t, d, on]) => (
      <div key={k} role="radio" aria-checked={k === "website"} aria-disabled={!on} className={`typeCard${k === "website" ? " selected" : ""}${on ? "" : " disabled"}`}>
        <div className="row between"><b>{t}</b>{on ? <Pill value="ACTIVE" label="Sẵn sàng"/> : <Pill value="COMING_SOON" label="Sắp có"/>}</div><p>{d}</p>
      </div>))}</div>
    <Card title="Website mới">
      <form className="filters" onSubmit={(e) => void create(e)}>
        <input aria-label="Tên ứng dụng" placeholder="Tên ứng dụng" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} autoFocus/>
        <button className="btn primary" disabled={busy || !name.trim()}>{busy ? "Đang tạo…" : "Tạo website"}</button>
      </form>
      <p className="hint">Bắt đầu từ trang mẫu mặc định (Hero, sản phẩm, đánh giá, liên hệ). Thư viện template riêng của công ty chưa có.</p>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
  </>);
}

function Templates() {
  return (<>
    <div className="pageHead"><div><h1>Templates</h1><p>Mẫu khởi đầu cho ứng dụng.</p></div></div>
    <div className="grid2">
      <Card title="Trang mẫu mặc định"><p>Website một trang: thanh điều hướng, Hero, danh sách sản phẩm, công nghệ, đánh giá, form liên hệ, chân trang. Mọi website mới hiện bắt đầu từ mẫu này.</p><Link className="btn primary" href="/studio/new">Dùng mẫu này</Link></Card>
      <Card title="Company Templates & My Templates"><ComingSoon title="Thư viện template">Lưu một trang thành template, chia sẻ cho công ty và duyệt template sẽ có ở Phase 5 (template là Page Schema JSON, không phải mã nguồn).</ComingSoon></Card>
    </div>
  </>);
}

function Components() {
  const { data, error, loading, reload } = useLoad(() => api.components(), []);
  const [open, setOpen] = useState<string | null>(null);
  return (<>
    <div className="pageHead"><div><h1>Company Components</h1><p>Component đã được duyệt. AI và trình chỉnh sửa chỉ dùng những component này; số liệu là số ứng dụng đang dùng trong toàn công ty.</p></div></div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (
      <div className="compGrid">{data!.map((c) => {
        const props = Object.keys(c.versions.find((v) => v.version === c.latestVersion)?.propsSchema.properties ?? {});
        return (
          <article key={c.id} className="compCard">
            <div className="row between"><b>{sectionLabel(c.id, c.name)}</b><Pill value={c.status === "ACTIVE" ? "ACTIVE" : c.status} label={c.status === "ACTIVE" ? "Đã duyệt" : c.status}/></div>
            <small className="code">{c.id} · v{c.latestVersion} · {c.category}</small>
            <p>{c.description}</p>
            <div className="row between"><small>{num(c.usedInProjects ?? 0)} ứng dụng đang dùng</small><button className="btn sm" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>{open === c.id ? "Ẩn" : "Thuộc tính"}</button></div>
            {open === c.id ? <ul className="plainList">{props.map((p) => <li key={p} className="code">{p}</li>)}</ul> : null}
          </article>);
      })}</div>
    )}
    <Card title="Đóng góp component"><ComingSoon title="Gửi component để duyệt">Quy trình đóng góp (riêng tư → gửi duyệt → kiểm tra → phê duyệt) chưa triển khai. Không có ảnh chụp hay đánh giá vì hệ thống chưa lưu.</ComingSoon></Card>
  </>);
}

function Activity() {
  const { data, error, loading, reload } = useLoad(() => api.myActivity(50), []);
  return (<>
    <div className="pageHead"><div><h1>Hoạt động của tôi</h1><p>Lấy từ nhật ký kiểm toán của hệ thống.</p></div></div>
    <Card>{error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.length === 0 ? <StateView kind="empty" title="Chưa có hoạt động"/> : (
      <ul className="activityList">{data!.map((a) => <li key={a.id}><span className="muted">{ago(a.createdAt)}</span><b>{actionLabel(a.action)}</b>{a.projectId ? <Link href={`/studio/projects/${a.projectId}`}>mở ứng dụng</Link> : null}</li>)}</ul>
    )}</Card>
  </>);
}
