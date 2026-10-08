"use client";
import Link from "next/link";
import type { ReactNode } from "react";
import { PORTAL_LABEL, type PortalId } from "@xweb/permissions";
import { Card, ComingSoon, StateView } from "../../ui";
import type { AdminScope } from "../adminModel";
import { otherConsoleHref, type AdminPortal } from "../base";
import { PageHead } from "../PageHead";
import { ScopedHome } from "../TenantScreens";
import { useAdminConsole } from "./context";
import { resolveSection } from "./sectionPolicy";
import { SECTIONS, type Section } from "./sections";

/** The screen for the path below the console prefix (`seg` = ["users", "u1"]) for this person: the table (sections.tsx) decides, this only renders the answer. */
export function route(portal: AdminPortal, seg: string[], scope: AdminScope): ReactNode {
  const key = seg[0] ?? "";
  const r = resolveSection(SECTIONS, portal, key, scope);
  switch (r.kind) {
    case "coming": return <ComingSection section={r.section}/>;
    case "page": return r.section.render?.(seg) ?? <NotFound/>;
    case "needs-scope": return <NeedsScope what={r.what}/>;
    case "needs-platform": return <NeedsPlatform/>;
    case "scoped-home": return <ScopedHome/>;
    case "elsewhere": return <ElsewhereNote section={key}/>;
    case "notfound": return <NotFound/>;
  }
}

/** an address no screen answers: a real 404 with the way back (not "it is in the other console") */
function NotFound() {
  const { A } = useAdminConsole();
  return <StateView level={1} kind="notfound" action={<Link className="btn" href={A("")}>Về trang tổng quan</Link>}/>;
}
function NeedsPlatform() {
  return <StateView level={1} kind="forbidden" title="Mục này chỉ dành cho quản trị hệ thống" detail={<p>Tài khoản của bạn quản trị công ty / workspace, không phải toàn hệ thống. Các mục bạn dùng được nằm ở thanh bên trái.</p>}/>;
}
function NeedsScope({ what }: { what: string }) {
  return <StateView level={1} kind="forbidden" title={`Bạn chưa quản trị ${what} nào`} detail={<p>Máy chủ không liệt kê quyền tương ứng cho tài khoản này.</p>}/>;
}

function ComingSection({ section }: { section: Section }) {
  const c = section.coming!;
  return (<>
    <PageHead title={section.label} sub={c.why}/>
    <Card title="Chưa sẵn sàng"><ComingSoon title={section.label}>{c.needs} Màn hình này sẽ hiển thị dữ liệu thật ngay khi phần máy chủ có mặt; hiện tại không có dữ liệu giả.</ComingSoon></Card>
  </>);
}

/** A section that exists, but in the other console. */
function ElsewhereNote({ section }: { section: string }) {
  const { portal } = useAdminConsole();
  const other: PortalId = portal === "platform" ? "admin" : "platform";
  return <StateView level={1} kind="notfound" title="Mục này nằm ở trang khác" detail={<p>Mục này thuộc {PORTAL_LABEL[other]}.</p>} action={<a className="btn" href={otherConsoleHref(other, `/${section}`)}>Mở {PORTAL_LABEL[other]}</a>}/>;
}
