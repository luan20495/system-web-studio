"use client";
import Link from "next/link";
import { portalHref, rememberPortal, type PortalId } from "@xweb/permissions";
import { MenuButton, Diamond, PortalSwitcher, type useNavDrawer } from "@xweb/ui";
import { useSession } from "../../session";
import { NavLink } from "../../ui";
import type { AdminScope } from "../adminModel";
import type { AdminPortal } from "../base";
import { useAdminConsole } from "./context";
import type { Section } from "./sections";

export const CONSOLE_NAME: Record<AdminPortal, string> = { all: "Admin Console", platform: "Xweb Platform", admin: "Quản trị công ty" };

export function AdminSidebar({ active, scope, items }: { active: string; scope: AdminScope; items: readonly Section[] }) {
  const { me } = useSession(); const { portal, A } = useAdminConsole();
  return (
    <aside className="sidebar dark" id="admin-sidebar" aria-label="Điều hướng quản trị">
      <div className="sideBrand"><span className="logoMark" aria-hidden="true"><Diamond size={16} fill="currentColor"/></span><div><b>AI Software Factory</b><small>{CONSOLE_NAME[portal]}</small></div></div>
      <nav>{items.map((s) => <NavLink key={s.key} href={A(s.key ? `/${s.key}` : "")} active={active === s.key} icon={s.icon}>{s.label}{s.listed === "coming" ? <small className="xp-navSoon"> Sắp có</small> : null}</NavLink>)}</nav>
      <div className="sideFoot"><div className="avatar" aria-hidden="true">{(me?.displayName ?? "?").slice(0, 2).toUpperCase()}</div><div><b>{me?.displayName}</b><small>{portal === "platform" ? "Quản trị nền tảng" : scope.platform ? "Quản trị hệ thống" : scope.tenants.length ? "Quản trị công ty" : "Quản trị workspace"}</small></div></div>
    </aside>
  );
}

export function AdminHeader({ nav }: { nav: ReturnType<typeof useNavDrawer> }) {
  const { me, logout } = useSession(); const { portal } = useAdminConsole();
  return (
    <header className="topHeader">
      <div className="row"><MenuButton open={nav.open} onClick={nav.toggle} buttonRef={nav.button} controls="admin-sidebar"/><div className="crumb">{CONSOLE_NAME[portal]}</div></div>
      <div className="row">
        {portal === "all"
          ? (me && me.workspaces.length > 0 ? <Link className="btn sm" href={portalHref("studio")} onClick={() => rememberPortal("builder")}>Mở Builder Studio</Link> : null)
          : <PortalSwitcher me={me} current={portal as PortalId}/>}
        <button className="btn sm ghost" onClick={() => void logout()}>Đăng xuất</button>
      </div>
    </header>
  );
}
