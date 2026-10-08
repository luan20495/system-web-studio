"use client";
/** The real wiring of the organization and employee screens (session, live adapters). The presentational parts are OrganizationScreens.tsx / EmployeesScreens.tsx (also used by the browser harness). */
import { useEffect, useMemo, useState } from "react";
import { useSession } from "../session";
import { PageHead } from "./PageHead";
import { adminScope } from "./adminModel";
import { organizationPlan } from "./organizationModel";
import { liveOrganization } from "./organizationAdapter";
import { liveProvisioning } from "./provisioningAdapter";
import { provisioningPlan } from "./provisioningModel";
import { OrganizationView } from "./OrganizationScreens";
import { EmployeesView } from "./EmployeesScreens";
import type { Option } from "./ProvisioningScreens";

/** the tenant of the page: the session's (exactly one) or one of the caller's OWN tenants; never typed */
function useTenant() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const plan = useMemo(() => organizationPlan(scope, liveOrganization.state), [scope]);
  const [chosen, setChosen] = useState<string>("");
  const tenant = plan.fixedTenant ?? plan.tenantChoice.find((t) => t.id === chosen) ?? plan.tenantChoice[0] ?? { id: "", name: "" };
  return { me, scope, plan, tenant, setChosen };
}

export function OrganizationPage() {
  const { plan, tenant, setChosen } = useTenant();
  useEffect(() => { document.title = "Cơ cấu tổ chức"; }, []);
  return (<>
    <PageHead title="Cơ cấu tổ chức" sub="Dựng cơ cấu của công ty bằng các đơn vị và loại đơn vị do bạn tự định nghĩa."/>
    {plan.tenantChoice.length > 1 ? <label className="field xp-tenantSwitch"><span>Công ty</span><select data-testid="org-tenant-switch" value={tenant.id} onChange={(e) => setChosen(e.target.value)}>{plan.tenantChoice.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label> : null}
    <OrganizationView api={liveOrganization} plan={plan} tenant={tenant}/>
  </>);
}

export function EmployeesPage() {
  const { me, scope, plan, tenant, setChosen } = useTenant();
  const provPlan = useMemo(() => provisioningPlan(scope, "admin", liveProvisioning.state), [scope]);
  const ownOf = useMemo(() => (tenantId: string): Option[] => (me?.workspaces ?? []).filter((w) => w.tenantId === tenantId).map((w) => ({ id: w.id, name: w.name })), [me]);
  useEffect(() => { document.title = "Nhân viên"; }, []);
  return (<>
    <PageHead title="Nhân viên" sub="Danh bạ nhân viên của công ty: tìm kiếm, lọc theo đơn vị, thêm nhân viên và xem chi tiết."/>
    <EmployeesView api={liveOrganization} plan={plan} tenant={tenant} onTenant={setChosen} canToggleStatus={scope.platform}
      prov={{ api: liveProvisioning, plan: provPlan, workspacesOf: ownOf, tenants: plan.tenantChoice.length ? plan.tenantChoice : plan.fixedTenant ? [plan.fixedTenant] : [] }}/>
  </>);
}
