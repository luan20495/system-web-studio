"use client";

import { Suspense, lazy } from "react";
import { PortalApp } from "@xweb/auth";
import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorBoundary, StateView } from "@xweb/ui";

// M-053 step 1: the console is a separate chunk. The login page and the auth gate (PortalApp) do not download it; it is fetched when a signed-in person reaches a console route.
const StudioApp = lazy(() => import("@/features/studio/StudioApp").then((m) => ({ default: m.StudioApp })));

// the route render is wrapped so a screen exception (or a failed chunk load) shows the shared fallback instead of unmounting the portal; navigating to another screen clears it.
// (A boundary around the Builder itself, so a Design-mode crash keeps the project chrome, is added inside features/studio by its owner.)
export default function Entry() {
  return <PortalApp portal="studio" render={(seg) => <ErrorBoundary resetKeys={[seg.join("/")]} homeHref={PORTAL_PREFIX.studio}><Suspense fallback={<StateView level={1} kind="loading"/>}><StudioApp seg={seg} dedicated/></Suspense></ErrorBoundary>}/>;
}
