"use client";

import type { Me } from "@xweb/types";
import { accessiblePortals, portalHref, PORTAL_LABEL, type PortalId } from "@xweb/permissions";

/**
 * Links to the other web apps this person may open. Permission-driven: a portal the person cannot use is never rendered, so an ordinary
 * user never sees a link to Platform or Admin. (The server still authorises every request.)
 */
export function PortalSwitcher({ me, current, className = "btn sm", onNavigate }: { me: Me | null; current: PortalId; className?: string; onNavigate?: (p: PortalId) => void }) {
  const others = accessiblePortals(me).filter((p) => p !== current);
  if (others.length === 0) return null;
  return <>{others.map((p) => <a key={p} className={className} href={portalHref(p)} onClick={() => onNavigate?.(p)}>Mở {PORTAL_LABEL[p]}</a>)}</>;
}
