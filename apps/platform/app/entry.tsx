"use client";

import { Suspense, lazy } from "react";
import { PortalApp } from "@xweb/auth";
import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorBoundary, StateView } from "@xweb/ui";

// M-053 step 1: the console is a separate chunk. The login page and the auth gate (PortalApp) do not download it; it is fetched when a signed-in person reaches a console route.
const AdminApp = lazy(() => import("@/features/admin/AdminApp").then((m) => ({ default: m.AdminApp })));

// the route render is wrapped so a screen exception (or a failed chunk load) shows the shared fallback instead of unmounting the portal; navigating to another screen clears it
export default function Entry() {
  return <PortalApp portal="platform" render={(seg) => <ErrorBoundary resetKeys={[seg.join("/")]} homeHref={PORTAL_PREFIX.platform}><Suspense fallback={<StateView kind="loading"/>}><AdminApp seg={seg} portal="platform"/></Suspense></ErrorBoundary>}/>;
}
