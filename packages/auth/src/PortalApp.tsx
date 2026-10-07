"use client";

import { Suspense, useEffect, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { canAccessPortal, PORTAL_PREFIX, resolvePortalPostLogin, segments, type PortalId } from "@xweb/permissions";
import { StateView } from "@xweb/ui";
import { SessionProvider, useSession } from "./session";
import { ActivatePage, LoginPage, NoAccess, NoWorkspace, SessionExpired, SigningIn } from "./AuthPages";

/**
 * Entry of one dedicated web app (platform, admin or studio): its own login and auth pages, the shared session, and a permission gate.
 * `render` receives the path segments below the portal prefix ("/studio/projects/x" -> ["projects","x"]).
 * Gates only choose a screen; every API call is authorised again by the server.
 */
export function PortalApp({ portal, render }: { portal: PortalId; render: (seg: string[]) => ReactNode }) {
  return <Suspense fallback={<Splash/>}><SessionProvider><PortalRouter portal={portal} render={render}/></SessionProvider></Suspense>;
}

function Splash() { return <div className="splash"><StateView kind="loading" title="Đang tải…"/></div>; }
function Redirect({ to }: { to: string }) {
  const router = useRouter();
  useEffect(() => { router.replace(to); }, [router, to]);
  return <Splash/>;
}

function PortalRouter({ portal, render }: { portal: PortalId; render: (seg: string[]) => ReactNode }) {
  const pathname = usePathname() ?? "/";
  const seg = segments(pathname);
  const { me, loading, disabled } = useSession();
  const prefix = PORTAL_PREFIX[portal].slice(1);

  if (seg[0] === "login" || (seg[0] === prefix && seg[1] === "login")) return <LoginPage fixedPortal={portal}/>;   // `/<portal>/login` is the URL people type: it is the login page, not a console section
  if (seg[0] === "auth") {
    if (seg[1] === "activate") return <ActivatePage/>;
    if (seg[1] === "signing-in") return <SigningIn fixedPortal={portal}/>;
    if (seg[1] === "no-access") return <NoAccess/>;
    if (seg[1] === "no-workspace") return <NoWorkspace/>;
    if (seg[1] === "session-expired") return <SessionExpired/>;
    return <Redirect to="/login"/>;
  }
  if (loading) return <Splash/>;
  if (seg.length === 0) return <Redirect to={resolvePortalPostLogin({ me, disabled, portal })}/>;
  if (disabled) return <Redirect to="/auth/no-access?reason=disabled"/>;
  if (!me) return <Redirect to={`/login?next=${encodeURIComponent(pathname + (typeof window !== "undefined" ? window.location.search : ""))}`}/>;
  if (seg[0] !== prefix) return <div className="splash"><StateView kind="notfound" title="Không có trang này" action={<a className="btn" href={PORTAL_PREFIX[portal]}>Về trang chính</a>}/></div>;
  if (!canAccessPortal(me, portal)) return <Redirect to={`/auth/no-access?portal=${portal}`}/>;
  return <>{render(seg.slice(1))}</>;
}
