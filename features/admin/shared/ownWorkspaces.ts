"use client";
import { useMemo } from "react";
import { useSession } from "../../session";
import type { Option } from "../ProvisioningScreens";

/** M-065: the caller's own workspaces of one company (`/auth/me` rows carry the tenant), shared by the two create-account entry points (Người dùng, Nhân viên) */
export function useOwnWorkspacesOf(): (tenantId: string) => Option[] {
  const { me } = useSession();
  return useMemo(() => (tenantId: string): Option[] => (me?.workspaces ?? []).filter((w) => w.tenantId === tenantId).map((w) => ({ id: w.id, name: w.name })), [me]);
}
