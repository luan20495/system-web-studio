"use client";

import { Suspense, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import StudioShell from "@/components/StudioShell";
import { isDemoMode } from "@/lib/api-client";
import { SessionProvider, useSession } from "@/features/session";
import { hasWorkspace, isAdmin, rememberedPortal, resolvePostLogin, segments } from "@/features/routing";
import { ActivatePage, LoginPage, NoAccess, NoWorkspace, SessionExpired, SigningIn } from "@/features/auth/AuthPages";
import { AdminApp } from "@/features/admin/AdminApp";
import { StudioApp } from "@/features/studio/StudioApp";
import { StateView } from "@/features/ui";

export default function AppEntry() {
  if (isDemoMode) return <StudioShell/>;          // static demo: no backend, no routing
  return <Suspense fallback={<Splash/>}><SessionProvider><Router/></SessionProvider></Suspense>;
}

function Splash() { return <div className="splash"><StateView level={1} kind="loading" title="Đang tải…"/></div>; }

function Redirect({ to }: { to: string }) {
  const router = useRouter();
  useEffect(() => { router.replace(to); }, [router, to]);
  return <Splash/>;
}

function Router() {
  const pathname = usePathname() ?? "/";
  const seg = segments(pathname);
  const { me, loading, disabled } = useSession();

  if (seg[0] === "login") return <LoginPage/>;
  if (seg[0] === "auth") {
    if (seg[1] === "activate") return <ActivatePage/>;
    if (seg[1] === "signing-in") return <SigningIn/>;
    if (seg[1] === "no-access") return <NoAccess/>;
    if (seg[1] === "no-workspace") return <NoWorkspace/>;
    if (seg[1] === "session-expired") return <SessionExpired/>;
    return <Redirect to="/login"/>;
  }
  if (loading) return <Splash/>;
  if (seg.length === 0) return <Redirect to={resolvePostLogin({ me, disabled, portal: rememberedPortal() })}/>;
  if (disabled) return <Redirect to="/auth/no-access?reason=disabled"/>;
  if (!me) return <Redirect to={`/login?next=${encodeURIComponent(pathname + (typeof window !== "undefined" ? window.location.search : ""))}`}/>;
  // Client-side gates only choose a screen; every admin/studio API call is authorised again by the server.
  if (seg[0] === "admin") return isAdmin(me) ? <AdminApp seg={seg.slice(1)}/> : <Redirect to="/auth/no-access"/>;
  if (seg[0] === "studio") return hasWorkspace(me) ? <StudioApp seg={seg.slice(1)}/> : <Redirect to="/auth/no-workspace"/>;
  return <div className="splash"><StateView level={1} kind="notfound" title="Không có trang này" action={<a className="btn" href="/">Về trang chính</a>}/></div>;
}
