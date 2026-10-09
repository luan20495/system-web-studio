"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useSession } from "../session";
import { rememberPortal } from "../routing";
import { canAccessPortal } from "@xweb/permissions";
import { MenuButton, SkipLink, useMain, useNavDrawer, AppWindow, Boxes, Diamond, History, House, LayoutTemplate, Plus, PortalSwitcher } from "@xweb/ui";
import { NavLink, StateView } from "../ui";
import { ProjectWorkspace } from "./ProjectWorkspace";
import { consoleHref, S } from "./base";
import { Ctx, useStudio } from "./studioContext";
import { Home } from "./screens/Home";
import { Projects } from "./screens/Projects";
import { NewApp } from "./screens/NewApp";
import { Templates } from "./screens/Templates";
import { Components } from "./screens/Components";
import { Activity } from "./screens/Activity";
import { SiteAccess } from "./screens/SiteAccess";
import { claimStudioStorage, clearStudioStorage } from "./studioStorage";

/** `dedicated` = rendered by the Studio web app (app.xweb.vn); links to the other consoles then go through the portal switcher. */
export function StudioApp({ seg, dedicated = false }: { seg: string[]; dedicated?: boolean }) {
  const nav = useNavDrawer();
  const main = useMain(seg.join("/"));      // M-025: id="main" for the skip link, a Tab stop only while the region scrolls, focus on the page heading after a route change
  const TITLES: Record<string, string> = { "": "Trang chủ", projects: "Ứng dụng", new: "Tạo ứng dụng", templates: "Templates", components: "Components", activity: "Hoạt động", "site-access": "Mở trang riêng tư" };
  const section = seg[0] ?? "";
  useEffect(() => { if (!(section === "projects" && seg[1])) document.title = `${TITLES[section] ?? "Không tìm thấy trang"} · Xweb Studio`; }, [section, seg[1]]); // eslint-disable-line react-hooks/exhaustive-deps
  const { me } = useSession();
  const [workspaceId, setWs] = useState(() => {
    claimStudioStorage(me!.id);   // M-091: what another user left in this browser is dropped before it is read
    try { const saved = localStorage.getItem("studio-ws"); if (saved && me!.workspaces.some((w) => w.id === saved)) return saved; } catch { /* ignore */ }
    return me!.workspaces[0].id;
  });
  const setWorkspaceId = (id: string) => { setWs(id); try { localStorage.setItem("studio-ws", id); } catch { /* ignore */ } };
  // the project workspace is a full-screen editor; everything else lives in the Studio shell
  if (seg[0] === "projects" && seg[1]) return <ProjectWorkspace projectId={seg[1]} view={seg[2]}/>;
  return (
    <Ctx.Provider value={{ workspaceId, setWorkspaceId, dedicated }}>
      <div className="shell studio-shell" data-nav={nav.open ? "open" : "closed"}>
        <SkipLink/>
        <StudioSidebar active={seg[0] ?? ""}/>
        <div className="sideBackdrop" onClick={nav.close} aria-hidden="true"/>
        <div className="shellMain"><StudioHeader nav={nav}/><main className="page" {...main}>{route(seg)}</main></div>
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
    default: return <StateView level={1} kind="notfound"/>;
  }
}

function StudioSidebar({ active }: { active: string }) {
  const nav: [string, string, ReactNode][] = [["", "Trang chủ", <House size={18}/>], ["projects", "Ứng dụng", <AppWindow size={18}/>], ["templates", "Templates", <LayoutTemplate size={18}/>], ["components", "Components", <Boxes size={18}/>], ["activity", "Hoạt động", <History size={18}/>]];
  return (
    <aside className="sidebar dark" id="studio-sidebar" aria-label="Điều hướng Studio">
      <div className="sideBrand"><span className="logoMark" aria-hidden="true"><Diamond size={16} fill="currentColor"/></span><div><b>Company Builder Studio</b><small>AI Software Factory</small></div></div>
      <Link className="btn primary block xp-btnIcon" href={S("/new")}><Plus size={16} aria-hidden="true"/> Tạo ứng dụng</Link>
      <nav>{nav.map(([k, l, i]) => <NavLink key={k} href={S(k ? `/${k}` : "")} active={active === k} icon={i}>{l}</NavLink>)}</nav>
    </aside>
  );
}

function StudioHeader({ nav }: { nav: ReturnType<typeof useNavDrawer> }) {
  const { me, logout } = useSession(); const { workspaceId, setWorkspaceId, dedicated } = useStudio(); const router = useRouter();
  const [q, setQ] = useState("");
  return (
    <header className="topHeader studioTop">
      <MenuButton open={nav.open} onClick={nav.toggle} buttonRef={nav.button} controls="studio-sidebar"/>
      <form className="search" role="search" onSubmit={(e) => { e.preventDefault(); router.push(S(`/projects?q=${encodeURIComponent(q)}`)); }}>
        <input aria-label="Tìm ứng dụng" placeholder="Tìm ứng dụng…" value={q} onChange={(e) => setQ(e.target.value)}/>
      </form>
      <div className="row">
        {me!.workspaces.length > 1 ? <select aria-label="Workspace" value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)}>{me!.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</select>
          : <span className="muted">{me!.workspaces[0].name}</span>}
        {dedicated ? <PortalSwitcher me={me} current="studio"/> : canAccessPortal(me, "admin") ? <Link className="btn sm" href={consoleHref()} onClick={() => rememberPortal("admin")}>Admin Console</Link> : null}
        <span className="avatar" title={me!.displayName} aria-label={`Tài khoản: ${me!.displayName}`}>{me!.displayName.slice(0, 2).toUpperCase()}</span>
        <button className="btn sm ghost" onClick={() => { clearStudioStorage(); void logout(); }}>Đăng xuất</button>
      </div>
    </header>
  );
}
