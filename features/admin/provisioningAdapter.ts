import { api } from "@/lib/http-api";
import { createProvisioningApi, type ProvisioningApi } from "./provisioning";

/** The production adapter bound to the real client. Routes: provisioning.ts `CAPABILITIES`. When C1 delivers a contract, add its method here and flip its capability to READY. */
export const liveProvisioning: ProvisioningApi = createProvisioningApi({
  createUser: (b) => api.admin.createUser(b),
  setTenantMember: (t, u, role) => api.admin.setTenantMember(t, u, role),
  changeWorkspaceMember: (w, u, role) => api.changeWorkspaceMember(w, u, role),
  addWorkspaceMember: (w, who, role) => api.addWorkspaceMember(w, who, role),
  tenantMemberCandidates: (t, q) => api.admin.tenantMemberCandidates(t, q),
  activationLink: (u) => api.admin.activationLink(u),
  setUserStatus: (u, enabled) => api.admin.setUserStatus(u, enabled),
});
