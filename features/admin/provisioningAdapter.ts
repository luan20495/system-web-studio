import { api } from "@/lib/http-api";
import { createProvisioningApi, type ProvisioningApi } from "./provisioning";

/** The production adapter bound to the real client. Routes and who may call them: provisioning.ts `CAPABILITIES` (C1 contract @ 2356d64). */
export const liveProvisioning: ProvisioningApi = createProvisioningApi({
  createTenantUser: (t, b) => api.admin.createTenantUser(t, b),
  createTenantWorkspace: (t, name) => api.admin.createTenantWorkspace(t, name),
  setTenantMember: (t, u, role) => api.admin.setTenantMember(t, u, role),
  changeWorkspaceMember: (w, u, role) => api.changeWorkspaceMember(w, u, role),
  addWorkspaceMember: (w, who, role) => api.addWorkspaceMember(w, who, role),
  tenantMemberCandidates: (t, q) => api.admin.tenantMemberCandidates(t, q),
  activationLink: (u) => api.admin.activationLink(u),
  setUserStatus: (u, enabled) => api.admin.setUserStatus(u, enabled),
});
