"use client";

import { PortalApp } from "@xweb/auth";
import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorBoundary } from "@xweb/ui";
import { StudioApp } from "@/features/studio/StudioApp";

// the route render is wrapped so a screen exception shows the shared fallback instead of unmounting the portal; navigating to another screen clears it.
// (A boundary around the Builder itself, so a Design-mode crash keeps the project chrome, is added inside features/studio by its owner.)
export default function Entry() {
  return <PortalApp portal="studio" render={(seg) => <ErrorBoundary resetKeys={[seg.join("/")]} homeHref={PORTAL_PREFIX.studio}><StudioApp seg={seg} dedicated/></ErrorBoundary>}/>;
}
