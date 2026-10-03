"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { ApiProject, BlockDto, TemplateDto } from "@/lib/http-types";
import { sectionLabel } from "@/components/SectionInspector";
import { useSession } from "../session";
import { rememberPortal } from "../routing";
import { useLoad } from "../useLoad";
import { actionLabel, ago, Card, ErrorState, errText, NavLink, num, Pager, Pill, StateView, usd } from "../ui";
import { ProjectWorkspace } from "./ProjectWorkspace";
import { BlockStatus, BlockThumb, blockPage, CheckList, ReviewTimeline, SchemaThumb } from "../library";

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
    case "site-access": return <SiteAccess/>;
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
        <div className="row">{mine ? <Pill value="ACTIVE" label="Của tôi"/> : <Pill value="PRIVATE" label="Được chia sẻ"/>}<Pill value="PRIVATE" label="Website"/>
          {p.status === "ARCHIVED" ? <Pill value="ARCHIVED" label="Đã lưu trữ"/> : null}</div></div>
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
  // creating makes you the project owner, which needs workspace membership; "ADMIN" = system admin viewing a workspace without membership
  const canCreate = role === "WORKSPACE_ADMIN" || role === "EDITOR";
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
      ) : <p className="notice">{role === "ADMIN" ? "Bạn đang xem workspace này với quyền quản trị hệ thống nhưng không phải thành viên, nên không tạo ứng dụng ở đây được. Chọn workspace của bạn hoặc nhờ quản trị workspace thêm bạn." : "Vai trò của bạn trong workspace này không cho phép tạo ứng dụng mới."}</p>}
    </section>
    <div className="kpiGrid">
      <div className="kpi"><div className="kpiLabel">Lượt AI hôm nay</div>
        <div className="kpiValue">{u ? (u.aiConfigured ? `${num(u.aiRequestsUsed)} / ${num(u.aiRequestsLimit)}` : "Mô phỏng") : "…"}</div>
        <div className="kpiHint">{u ? (u.aiConfigured ? (u.aiWindowResetsInSeconds ? `Làm mới sau ${Math.ceil(u.aiWindowResetsInSeconds / 3600)} giờ` : "Chưa dùng lượt nào") : "Chưa cấu hình AI thật, không tính lượt") : ""}</div></div>
      <div className="kpi"><div className="kpiLabel">Token AI 24 giờ qua</div>
        <div className="kpiValue">{u ? (u.aiConfigured || u.tokensLast24h ? `${num(u.tokensLast24h)}${u.tokensLimitPerDay ? ` / ${num(u.tokensLimitPerDay)}` : ""}` : "—") : "…"}</div>
        <div className="kpiHint">{u ? (u.usageLast30Days?.calls ? `30 ngày: ${num(u.usageLast30Days.totalTokens)} token · ${usd(u.usageLast30Days.costUsd)} (số liệu nhà cung cấp)` : "Chưa gọi model thật nào; bộ mô phỏng không tính token") : ""}</div></div>
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
function useCanCreate() {
  const { me } = useSession(); const { workspaceId } = useStudio();
  const role = me!.workspaces.find((w) => w.id === workspaceId)?.role;
  return role === "WORKSPACE_ADMIN" || role === "EDITOR";
}

function NewApp() {
  const router = useRouter(); const { workspaceId } = useStudio();
  const params = useSearchParams();
  const company = useLoad(() => api.templates("company"), []); const mine = useLoad(() => api.templates("mine"), []);
  const [templateId, setTemplateId] = useState<string>(params.get("template") ?? "");
  const [name, setName] = useState(""); const [busy, setBusy] = useState(false); const [err, setErr] = useState<string | null>(null);
  const [kind, setKind] = useState<"website" | "code">(params.get("type") === "code" ? "code" : "website");
  const authConfig = useLoad(() => api.authConfig(), []);
  const codeOn = authConfig.data?.codeProjects === true;
  const types: [string, string, string, boolean][] = [["website", "Website", "Trang giới thiệu/landing một trang từ component đã duyệt.", true],
    ["code", "Ứng dụng web (mã nguồn)", codeOn ? "Ứng dụng React chạy trên trình duyệt: sửa bằng AI hoặc trực tiếp mã nguồn, mỗi thay đổi được build trong sandbox."
      : "Chưa bật trên máy chủ này (cần kho Git và máy build).", codeOn],
    ["dashboard", "Dashboard", "Biểu đồ và số liệu từ hệ thống nội bộ (cần kết nối dữ liệu).", false], ["internal", "Internal Tool", "Công cụ nội bộ có biểu mẫu và bảng dữ liệu (cần máy chủ).", false]];
  async function create(e: FormEvent) {
    e.preventDefault(); if (!name.trim()) return; setBusy(true); setErr(null);
    try {
      const p = kind === "code" ? await api.createProject(workspaceId, name.trim(), undefined, undefined, "STATIC_APP")
        : await api.createProject(workspaceId, name.trim(), undefined, templateId || undefined);
      router.push(`/studio/projects/${p.id}/ai`);
    }
    catch (x) { setErr(errText(x, "Không tạo được ứng dụng.")); setBusy(false); }
  }
  const options: [string, string, string][] = [["", "Trang mặc định", "Có sẵn trong hệ thống: thanh điều hướng, Hero, sản phẩm, đánh giá, liên hệ."],
    ...(company.data ?? []).map((t): [string, string, string] => [t.id, t.name, `Mẫu công ty · ${t.sections} mục · v${t.version}`]),
    ...(mine.data ?? []).map((t): [string, string, string] => [t.id, t.name, `Mẫu của tôi · ${t.sections} mục · v${t.version}`])];
  return (<>
    <div className="pageHead"><div><h1>Tạo ứng dụng</h1><p>Chọn loại ứng dụng. Hiện chỉ Website hoạt động; các loại khác cần kiến trúc sinh mã (chưa triển khai).</p></div></div>
    <div className="typeGrid" role="radiogroup" aria-label="Loại ứng dụng">{types.map(([k, t, d, on]) => (
      <div key={k} role="radio" tabIndex={on ? 0 : -1} aria-checked={kind === k} aria-disabled={!on} className={`typeCard${kind === k ? " selected" : ""}${on ? "" : " disabled"}`}
        onClick={() => { if (on) setKind(k as "website" | "code"); }} onKeyDown={(e) => { if (on && (e.key === " " || e.key === "Enter")) { e.preventDefault(); setKind(k as "website" | "code"); } }}>
        <div className="row between"><b>{t}</b>{on ? <Pill value="ACTIVE" label="Sẵn sàng"/> : <Pill value="COMING_SOON" label={k === "code" ? "Chưa bật" : "Sắp có"}/>}</div><p>{d}</p>
      </div>))}</div>
    <Card title={kind === "code" ? "Ứng dụng web mới (mã nguồn)" : "Website mới"}>
      <form onSubmit={(e) => void create(e)}>
        {kind === "website" ? <fieldset className="pickList" aria-label="Bắt đầu từ mẫu"><legend className="hint">Bắt đầu từ</legend>
          {options.map(([id, label, sub]) => <label key={id || "default"}><input type="radio" name="template" value={id} checked={templateId === id} onChange={() => setTemplateId(id)}/>
            <span><b>{label}</b><small>{sub}</small></span></label>)}
        </fieldset> : <p className="hint">Bắt đầu từ khung React + Vite + TypeScript đã duyệt. Mã nguồn nằm trong kho Git của nền tảng; thư viện chỉ gồm các gói đã duyệt (React). Ứng dụng chạy cách ly trong trình duyệt (không có cookie/localStorage) và chỉ xuất bản công khai ở phiên bản này.</p>}
        <div className="filters">
          <input aria-label="Tên ứng dụng" placeholder="Tên ứng dụng" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} autoFocus/>
          <button className="btn primary" disabled={busy || !name.trim()}>{busy ? "Đang tạo…" : kind === "code" ? "Tạo ứng dụng" : "Tạo website"}</button>
        </div>
      </form>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </Card>
  </>);
}

const REVIEW_LABEL: Record<TemplateDto["reviewStatus"], [string, string]> = { PRIVATE: ["PRIVATE", "Riêng tư"], SUBMITTED: ["QUEUED", "Đang kiểm tra"], REVIEW: ["UNKNOWN", "Chờ duyệt"],
  APPROVED: ["COMPANY", "Công ty"], ARCHIVED: ["ARCHIVED", "Đã lưu trữ"] };

/** Safe-render preview image when the server has one (JS-free screenshot), otherwise the client-side schema thumbnail. */
function TemplateThumb({ t }: { t: TemplateDto }) {
  const [broken, setBroken] = useState(false);
  return t.previewStatus === "READY" && !broken
    ? <img className="thumb previewImg" src={`/api/v1/templates/${t.id}/preview`} alt={`Ảnh xem trước mẫu ${t.name}`} loading="lazy" onError={() => setBroken(true)}/>
    : <SchemaThumb schema={t.schema} title={`Xem trước mẫu ${t.name}`}/>;
}

function TemplateCard({ t, onUse, onChanged, categories, mine }: { t: TemplateDto; onUse?: (t: TemplateDto) => void; onChanged: () => void; categories: Record<string, string>; mine: boolean }) {
  const [editing, setEditing] = useState(false); const [name, setName] = useState(t.name); const [desc, setDesc] = useState(t.description);
  const [category, setCategory] = useState(t.category); const [tags, setTags] = useState(t.tags.join(", "));
  const [err, setErr] = useState<string | null>(null); const [checks, setChecks] = useState<{ check: string; ok: boolean; message: string }[] | null>(null);
  async function act(fn: () => Promise<unknown>) { setErr(null); try { await fn(); setEditing(false); onChanged(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  async function saveEdit() {
    await act(async () => { await api.updateTemplate(t.id, { name: name.trim(), description: desc.trim() });
      await api.templateCatalog(t.id, { category, tags: tags.split(",").map((x) => x.trim()).filter(Boolean) }); });
  }
  async function submit() { setErr(null); try { const r = await api.submitTemplate(t.id); setChecks(r.passed ? null : r.checks); onChanged(); } catch (x) { setErr(errText(x, "Không gửi được.")); } }
  const [tone, label] = REVIEW_LABEL[t.reviewStatus] ?? ["PRIVATE", t.reviewStatus];
  return <article className="libCard">
    <TemplateThumb t={t}/>
    <div className="row between"><h2>{t.name}</h2><Pill value={tone} label={label}/></div>
    {t.description ? <p>{t.description}</p> : null}
    <div className="chips"><span className="chip">{categories[t.category] ?? t.category}</span>{t.tags.map((x) => <span key={x} className="chip">#{x}</span>)}</div>
    <div className="meta">{t.sections} mục · v{t.version} · {t.author ?? "—"} · {t.usageCount} lần dùng · cập nhật {ago(t.updatedAt)}</div>
    {t.reviewStatus === "PRIVATE" && t.reviewComment ? <p className="notice">Quản trị viên: {t.reviewComment}</p> : null}
    {editing ? <form className="inlineForm" onSubmit={(e) => { e.preventDefault(); void saveEdit(); }}>
      <input aria-label="Tên mẫu" value={name} maxLength={120} onChange={(e) => setName(e.target.value)}/>
      <textarea aria-label="Mô tả mẫu" value={desc} maxLength={500} rows={2} onChange={(e) => setDesc(e.target.value)}/>
      <select aria-label="Danh mục" value={category} onChange={(e) => setCategory(e.target.value)}>{Object.entries(categories).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <input aria-label="Thẻ (phân tách bằng dấu phẩy)" placeholder="Thẻ, phân tách bằng dấu phẩy" value={tags} onChange={(e) => setTags(e.target.value)}/>
      <div className="actions"><button className="btn sm primary" disabled={!name.trim()}>Lưu</button><button type="button" className="btn sm" onClick={() => setEditing(false)}>Hủy</button></div>
    </form> : <div className="actions">
      {onUse ? <button className="btn sm primary" onClick={() => onUse(t)}>Dùng mẫu này</button> : null}
      {t.canEdit ? <><button className="btn sm" onClick={() => setEditing(true)}>Sửa</button>
        {t.reviewStatus === "PRIVATE" ? <button className="btn sm" onClick={() => void submit()}>Gửi duyệt cho công ty</button> : null}
        <button className="btn sm ghost" onClick={() => { if (confirm(`Lưu trữ mẫu “${t.name}”? Ứng dụng đã tạo từ mẫu không bị ảnh hưởng.`)) void act(() => api.archiveTemplate(t.id)); }}>Lưu trữ</button></> : null}
      {t.reviewStatus === "REVIEW" && mine ? <button className="btn sm ghost" onClick={() => void act(() => api.withdrawTemplate(t.id))}>Rút lại</button> : null}
    </div>}
    {checks ? <ul className="plainList" aria-label="Kết quả kiểm tra">{checks.filter((c) => !c.ok).map((c) => <li key={c.check} className="formError">{c.check}: {c.message}</li>)}</ul> : null}
    {err ? <p className="formError" role="alert">{err}</p> : null}
  </article>;
}

function Templates() {
  const router = useRouter(); const canCreate = useCanCreate();
  const [scope, setScope] = useState<"company" | "mine">("company");
  const [category, setCategory] = useState(""); const [sort, setSort] = useState<"recent" | "popular">("recent");
  const cats = useLoad(() => api.libraryCategories(), []);
  const { data, error, loading, reload } = useLoad(() => api.templates(scope, { category: category || undefined, sort }), [scope, category, sort]);
  const use = canCreate ? (t: TemplateDto) => router.push(`/studio/new?template=${t.id}`) : undefined;
  return (<>
    <div className="pageHead"><div><h1>Templates</h1><p>Mẫu khởi đầu cho website. Một mẫu là cấu trúc trang (Page Schema) từ component đã duyệt, không phải mã nguồn; ảnh không đi kèm mẫu.</p></div></div>
    <div className="tabs" role="tablist">{([["company", "Mẫu của công ty"], ["mine", "Mẫu của tôi"]] as const).map(([k, l]) =>
      <button key={k} role="tab" aria-selected={scope === k} className={scope === k ? "active" : ""} onClick={() => setScope(k)}>{l}</button>)}</div>
    <div className="filters">
      <select aria-label="Danh mục" value={category} onChange={(e) => setCategory(e.target.value)}><option value="">Mọi danh mục</option>
        {Object.entries(cats.data?.templates ?? {}).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      <select aria-label="Sắp xếp" value={sort} onChange={(e) => setSort(e.target.value as "recent" | "popular")}><option value="recent">Mới cập nhật</option><option value="popular">Dùng nhiều nhất</option></select>
    </div>
    {error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : (
      <div className="compGrid">
        {scope === "company" ? <article className="libCard"><div className="thumb placeholder"><span>Trang mặc định</span></div><h2>Trang mặc định</h2>
          <p>Có sẵn trong hệ thống: thanh điều hướng, Hero, sản phẩm, công nghệ, đánh giá, form liên hệ, chân trang.</p>
          {canCreate ? <div className="actions"><Link className="btn sm primary" href="/studio/new">Dùng mẫu này</Link></div> : null}</article> : null}
        {data!.map((t) => <TemplateCard key={t.id} t={t} onUse={use} onChanged={reload} categories={cats.data?.templates ?? {}} mine={scope === "mine"}/>)}
        {data!.length === 0 ? <StateView kind="empty" title={scope === "mine" ? "Bạn chưa lưu mẫu nào" : "Chưa có mẫu công ty"}
          detail={<p>{scope === "mine" ? "Mở một ứng dụng → Cài đặt → “Lưu trang thành mẫu”." : "Tác giả gửi mẫu đi duyệt; quản trị viên duyệt để đưa vào thư viện công ty."}</p>}/> : null}
      </div>)}
  </>);
}

function BlockCard({ b, mine, onChanged }: { b: BlockDto; mine: boolean; onChanged: () => void }) {
  const [open, setOpen] = useState(false); const [err, setErr] = useState<string | null>(null); const [info, setInfo] = useState<string | null>(null);
  const page = blockPage(b);
  async function act(fn: () => Promise<unknown>, ok?: string) { setErr(null); setInfo(null); try { await fn(); if (ok) setInfo(ok); onChanged(); } catch (x) { setErr(errText(x, "Không thực hiện được.")); } }
  const latest = b.versions.find((v) => v.version === b.latestVersion);
  const lastDecision = [...b.reviews].reverse().find((r) => r.decision === "REJECT" || r.decision === "APPROVE" || r.decision === "VALIDATION_FAILED");
  return <article className="libCard">
    {page ? <BlockThumb b={b}/> : null}
    <div className="row between"><h2>{b.name}</h2>{mine ? <BlockStatus status={b.status}/> : <Pill value="APPROVED" label="Đã duyệt"/>}</div>
    {b.description ? <p>{b.description}</p> : null}
    <div className="meta">{b.usageCount ? `${b.usageCount} lần dùng · ` : ""}Dựa trên <span className="code">{b.baseComponent}</span> · {mine ? `phiên bản ${b.latestVersion}${b.approvedVersion ? ` · đang dùng trong công ty: v${b.approvedVersion}` : ""}` : `v${b.approvedVersion} · ${b.owner ?? "—"}`}</div>
    {mine && lastDecision && b.status === "PRIVATE" && lastDecision.decision !== "APPROVE" ? <p className="notice">{lastDecision.decision === "REJECT" ? `Bị từ chối: ${lastDecision.comment}` : "Kiểm tra tự động không đạt — xem chi tiết."}</p> : null}
    {mine ? <div className="actions">
      {b.status === "PRIVATE" ? <button className="btn sm primary" onClick={() => void act(async () => { const r = await api.submitBlock(b.id); if (!r.passed) { setOpen(true); throw new Error("Kiểm tra tự động không đạt; xem danh sách bên dưới."); } }, "Đã gửi duyệt.")}>Gửi duyệt</button> : null}
      {b.status === "REVIEW" ? <button className="btn sm" onClick={() => void act(() => api.withdrawBlock(b.id), "Đã rút lại.")}>Rút lại</button> : null}
      {b.approvedVersion == null && b.status !== "REVIEW" ? <button className="btn sm ghost" onClick={() => { if (confirm(`Xóa khối “${b.name}”?`)) void act(() => api.deleteBlock(b.id)); }}>Xóa</button> : null}
      <button className="btn sm ghost" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? "Ẩn chi tiết" : "Chi tiết"}</button>
    </div> : null}
    {open && mine ? <div>
      {latest?.validation ? <><h3 className="subHead">Kiểm tra tự động (v{latest.version})</h3><CheckList checks={latest.validation}/></> : <p className="muted">Chưa kiểm tra: bấm “Gửi duyệt”.</p>}
      <h3 className="subHead">Lịch sử</h3><ReviewTimeline reviews={b.reviews}/>
    </div> : null}
    {info ? <p className="hint" role="status">{info}</p> : null}
    {err ? <p className="formError" role="alert">{err}</p> : null}
  </article>;
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
    <BlocksSection/>
  </>);
}

function BlocksSection() {
  const company = useLoad(() => api.blocks("company"), []); const mine = useLoad(() => api.blocks("mine"), []);
  return (<>
    <Card title="Khối dựng sẵn của công ty">
      <p className="hint">Khối là một cấu hình sẵn (nội dung, bố cục) của một component đã duyệt, được nhân viên đóng góp và quản trị viên phê duyệt. Khối không chứa mã: khi chèn vào trang, nó là một mục bình thường của component gốc. Chưa đo số lần sử dụng khối.</p>
      {company.error ? <ErrorState error={company.error} retry={company.reload}/> : !company.data ? <StateView kind="loading"/> : company.data.length === 0
        ? <StateView kind="empty" title="Chưa có khối nào được duyệt"/> : <div className="compGrid">{company.data.map((b) => <BlockCard key={b.id} b={b} mine={false} onChanged={company.reload}/>)}</div>}
    </Card>
    <Card title="Khối của tôi">
      <p className="hint">Tạo khối: mở một ứng dụng ở chế độ Design, chọn một mục rồi bấm “Lưu thành khối”. Khối riêng tư chỉ bạn dùng được; gửi duyệt để kiểm tra tự động rồi chờ quản trị viên (người khác bạn) phê duyệt.</p>
      {mine.error ? <ErrorState error={mine.error} retry={mine.reload}/> : !mine.data ? <StateView kind="loading"/> : mine.data.length === 0
        ? <StateView kind="empty" title="Bạn chưa có khối nào"/> : <div className="compGrid">{mine.data.map((b) => <BlockCard key={b.id} b={b} mine onChanged={() => { mine.reload(); company.reload(); }}/>)}</div>}
    </Card>
  </>);
}

/**
 * A private published site sends visitors here (ADR 0009). Signed in on the Studio, a member gets a single-use ticket that the sites
 * host exchanges for its own session; the Studio cookie never leaves this origin. Only the server-built redirect is followed.
 */
function SiteAccess() {
  const params = useSearchParams();
  const site = params.get("site") ?? ""; const path = params.get("path") ?? "/";
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(site)) { setErr("Liên kết không hợp lệ."); return; }
    api.siteAccessTicket(site, path).then((r) => { window.location.assign(r.redirect); })
      .catch((x: unknown) => setErr(x instanceof ApiError && x.status === 404 ? "Bạn không có quyền xem trang riêng tư này, hoặc trang không còn tồn tại." : errText(x, "Không mở được trang.")));
  }, [site, path]);
  return err ? <StateView kind="forbidden" title="Không mở được trang" detail={<p>{err}</p>} action={<Link className="btn" href="/studio">Về Studio</Link>}/>
    : <StateView kind="loading" title="Đang mở trang riêng tư…"/>;
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
