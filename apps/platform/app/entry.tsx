"use client";

import { PortalApp } from "@xweb/auth";
import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorBoundary } from "@xweb/ui";
import { AdminApp } from "@/features/admin/AdminApp";

// the route render is wrapped so a screen exception shows the shared fallback instead of unmounting the portal; navigating to another screen clears it
export default function Entry() {
  return <PortalApp portal="platform" render={(seg) => <ErrorBoundary resetKeys={[seg.join("/")]} homeHref={PORTAL_PREFIX.platform}><AdminApp seg={seg} portal="platform"/></ErrorBoundary>}/>;
}
