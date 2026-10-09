"use client";

/**
 * The Platform and Admin consoles (one shell, two portals; `apps/platform` and `apps/admin` pass their own). The pages live in `pages/*`, the sections (nav, ownership, access, routing) in ONE table: `console/sections.tsx`.
 * The console is carried by `AdminConsoleContext`, not by module variables.
 */
import { useEffect, useMemo } from "react";
import { SkipLink, useMain, useNavDrawer } from "@xweb/ui";
import { useSession } from "../session";
import { adminScope } from "./adminModel";
import type { AdminPortal } from "./base";
import { AdminHeader, AdminSidebar, CONSOLE_NAME } from "./console/chrome";
import { AdminConsoleContext, makeAdminConsole } from "./console/context";
import { route } from "./console/routes";
import { navSections, owns } from "./console/sectionPolicy";
import { SECTIONS } from "./console/sections";

export function AdminApp({ seg, portal = "all" }: { seg: string[]; portal?: AdminPortal }) {
  const section = seg[0] ?? "";
  const active = section === "workspaces" ? "users" : section;
  const { me } = useSession();
  const scope = adminScope(me); const nav = useNavDrawer();
  // skip link target: focus goes to the page's h1 and the region scrolls to the top on a route change; a Tab stop only while it scrolls
  const main = useMain(seg.join("/"));
  const consoleValue = useMemo(() => makeAdminConsole(portal, (key) => owns(SECTIONS, portal, key)), [portal]);
  const items = navSections(SECTIONS, portal, scope);
  // one title per screen ("Nhân viên · Quản trị công ty"), so browser tabs, history and screen readers can tell the pages apart
  const sectionLabel = items.find((s) => s.key === active)?.label;
  useEffect(() => { document.title = `${sectionLabel ?? "Quản trị"} · ${CONSOLE_NAME[portal]}`; }, [sectionLabel, portal]);
  return (
    <AdminConsoleContext.Provider value={consoleValue}>
      <div className="shell admin" data-nav={nav.open ? "open" : "closed"}>
        <SkipLink/>
        <AdminSidebar active={active} scope={scope} items={items}/>
        <div className="sideBackdrop" onClick={nav.close} aria-hidden="true"/>
        <div className="shellMain">
          <AdminHeader nav={nav}/>
          <main className="page" {...main}>{route(portal, seg, scope)}</main>
        </div>
      </div>
    </AdminConsoleContext.Provider>
  );
}
