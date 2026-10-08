"use client";
/** Renders a unit-type icon from the closed allow-list (organizationModel.UNIT_ICONS). An unknown id falls back to the folder icon: the id is never used as a URL or as markup. */
import type { ReactNode } from "react";
import { Briefcase, Building2, Code, Cpu, Folder, GitBranch, Headset, Landmark, Layers, MapPin, Megaphone, Package, Scale, Smartphone, Users, Wallet, type LucideIcon } from "@xweb/ui";
import { safeIcon } from "./organizationModel";

const ICONS: Record<string, LucideIcon> = {
  building: Building2, landmark: Landmark, "map-pin": MapPin, briefcase: Briefcase, layers: Layers, users: Users, "git-branch": GitBranch, folder: Folder,
  cpu: Cpu, code: Code, smartphone: Smartphone, package: Package, megaphone: Megaphone, wallet: Wallet, headset: Headset, scale: Scale,
};
export function UnitIcon({ id, size = 18 }: { id: string | null | undefined; size?: number }): ReactNode {
  const I = ICONS[safeIcon(id)] ?? Folder; return <I size={size} aria-hidden="true"/>;
}
