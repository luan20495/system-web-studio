"use client";
/** The real wiring of the create-account screens (session, API, live adapter). The presentational parts are in ProvisioningScreens.tsx (also used by the browser harness). */
import { useEffect, useMemo } from "react";
import { api } from "@/lib/http-api";
import { useSession } from "../session";
import { useLoad } from "../useLoad";
import { ErrorState, StateView } from "../ui";
import { Modal } from "./Modal";
import { CreateAccountDialog, PeopleView, type Option } from "./ProvisioningScreens";
import { liveProvisioning } from "./provisioningAdapter";
import { adminScope } from "./adminModel";
import { provisioningPlan } from "./provisioningModel";
import { PageHead } from "./PageHead";

/** Platform / SYSTEM_ADMIN: the dialog with the real tenant and workspace lists */
export function PlatformCreateAccount({ onClose, onCreated }: { onClose: () => void; onCreated?: () => void }) {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const plan = useMemo(() => provisioningPlan(scope, "platform", liveProvisioning.state), [scope]);
  const tenants = useLoad(async () => (scope.platform ? (await api.admin.tenants()).filter((t) => t.status !== "DELETED").map((t): Option => ({ id: t.id, name: t.name })) : []), [scope.platform]);
  // workspaces cannot be listed by tenant yet (H-C1-16): the SYSTEM_ADMIN sees the first page of all of them, the server answers WORKSPACE_NOT_FOUND for another tenant's; workspaces created here are added by the dialog
  const ws = useLoad(async () => (await api.admin.workspaces(0)).items.map((w): Option => ({ id: w.id, name: w.name })), []);
  if (tenants.error || ws.error) return <Modal label="Tạo tài khoản" onClose={onClose}><div className="modalBody"><ErrorState error={tenants.error ?? ws.error} retry={() => { tenants.reload(); ws.reload(); }}/><button className="btn" onClick={onClose}>Đóng</button></div></Modal>;
  if ((tenants.loading && !tenants.data) || (ws.loading && !ws.data)) return <Modal label="Tạo tài khoản" onClose={onClose}><div className="modalBody"><StateView kind="loading"/></div></Modal>;
  return <CreateAccountDialog api={liveProvisioning} plan={plan} tenants={tenants.data ?? []} workspacesOf={() => ws.data ?? []} onClose={onClose} onCreated={() => onCreated?.()}/>;
}

/** Admin portal → Người dùng, for tenant admins and workspace admins (a SYSTEM_ADMIN uses the Platform / the system screens) */
export function PeoplePage() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const plan = useMemo(() => provisioningPlan(scope, "admin", liveProvisioning.state), [scope]);
  // the caller's own workspaces of that tenant (`/auth/me` rows carry the tenant); a tenant admin who belongs to none creates one in the dialog
  const ownOf = useMemo(() => (tenantId: string): Option[] => (me?.workspaces ?? []).filter((w) => w.tenantId === tenantId).map((w) => ({ id: w.id, name: w.name })), [me]);
  const admin = useMemo(() => scope.workspaces.map((w): Option => ({ id: w.id, name: w.name })), [scope]);
  useEffect(() => { document.title = "Người dùng"; }, []);
  return (<>
    <PageHead title="Người dùng" sub="Tạo tài khoản trong công ty của bạn và thêm người vào workspace."/>
    <PeopleView api={liveProvisioning} plan={plan} tenants={scope.tenants.map((t): Option => ({ id: t.id, name: t.name }))} workspacesOf={ownOf} memberWorkspaces={admin}/>
  </>);
}
