// @class: real-backend — thin calls over C3's Management API (docs/parallel/c3/MANAGEMENT_API.md, commit e606465 — NOT yet integrated into integration/v2, NOT verified against a running backend by C5).
// Used ONLY for API setup, for reading server state back after a browser step, and for cleanup. What the user sees is asserted in the browser.
// Anything created is recorded in fx.created.* so cleanup() can unbind/delete it even when a flow fails half-way.
import { randomSecret } from "./api.mjs";

export function management(fx, who, workspaceKey = "A") {
  const s = fx.sessions[who], w = fx.workspaces[workspaceKey], base = `/workspaces/${w}`;
  const ds = (id) => `${base}/data-sources/${encodeURIComponent(id)}`;
  return {
    session: s, workspaceId: w,
    connectors: () => s.get(`${base}/data-sources/connectors`),
    list: () => s.get(`${base}/data-sources`),
    get: (id) => s.get(ds(id)),
    async create(body) { const r = await s.post(`${base}/data-sources`, body); if (r.status === 201 && r.body?.id) fx.created.dataSources.push({ workspaceId: w, id: r.body.id, owner: who }); return r; },
    patch: (id, body) => s.patch(ds(id), body),
    remove: (id) => s.del(ds(id)),
    credential: (id) => s.get(`${ds(id)}/credential`),
    putCredential: (id, credential) => s.put(`${ds(id)}/credential`, { credential }),
    removeCredential: (id) => s.del(`${ds(id)}/credential`),
    test: (id) => s.post(`${ds(id)}/test`, undefined, { timeoutMs: 45_000 }),
    bindings: (projectId) => s.get(`${base}/projects/${projectId}/data-bindings`),
    async bind(projectId, mode, slotId, dataSourceId) {
      const r = await s.put(`${base}/projects/${projectId}/data-bindings/${mode}/${encodeURIComponent(slotId)}`, { dataSourceId });
      if (r.status === 200) fx.created.bindings.push({ workspaceId: w, projectId, mode, slotId, owner: who });
      return r;
    },
    unbind: (projectId, mode, slotId) => s.del(`${base}/projects/${projectId}/data-bindings/${mode}/${encodeURIComponent(slotId)}`),
  };
}

/** TEST_FAILURE_CODES of a failed connection test (MANAGEMENT_API.md §4) — a local copy: the suite is plain node and cannot import the TypeScript contract */
export const TEST_FAILURE_CODES = ["AUTH_REJECTED", "CONNECT_FAILED", "HOST_UNRESOLVED", "ADDRESS_BLOCKED", "TLS_FAILED", "TIMEOUT", "ROLE_TOO_PRIVILEGED", "INVALID_CREDENTIAL", "NOT_IMPLEMENTED", "INTERNAL"];

/** values for the required config keys of a connector descriptor, pointing at a host that cannot exist (RFC 2606 `.invalid`/`.example`): the connection test must FAIL for real.
 *  Returns {config} or {unknownKeys} when the catalogue asks for a key this suite cannot fill. */
export function bogusConfigFor(descriptor, runId) {
  const known = { host: "db.e2e-invalid.example", port: 5432, database: "e2e", baseUrl: "https://api.e2e-invalid.example", url: "https://api.e2e-invalid.example", schema: "public", sslmode: "require" };
  const config = {}, unknownKeys = [];
  for (const k of descriptor.configKeys ?? []) { if (!k.required) continue; if (k.name in known) config[k.name] = known[k.name]; else unknownKeys.push(k.name); }
  return unknownKeys.length ? { unknownKeys } : { config };
}
/** random per-run credential values for every credential key of the descriptor (kept in memory, never printed) */
export const randomCredentialFor = (descriptor) => Object.fromEntries((descriptor.credentialKeys ?? []).map((k) => [k, randomSecret()]));

/** 404 WITHOUT a domain code = the management controllers are not mounted (app.data-platform.enabled=false, or the build predates C3's commit) */
export const notMounted = (r) => r.status === 404 && !r.body?.code;
