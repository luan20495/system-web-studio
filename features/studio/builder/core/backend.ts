/**
 * What the connected backend can do, from ONE probe: GET /api/v1/component-metadata. A 404/501 means the C2 V2 core is not integrated yet:
 * the V2 definition operations (ADD_QUERY, ADD_ACTION, …) would be rejected, so the Data/Action/Workflow editors say NOT_READY instead of
 * sending them. Everything else stays gated by its own static reason (readiness.ts).
 */
import type { ComponentMetadataV2 } from "@xweb/types";
import { available, failed, loading, notReady, readinessFromError, STATIC_NOT_READY, type Readiness } from "./readiness";

export type ProbeState = { status: "loading" } | { status: "ok"; metadata: ComponentMetadataV2[] } | { status: "error"; error: unknown };

export type Backend = {
  metadata: Map<string, ComponentMetadataV2>;
  /** component-metadata endpoint itself */
  metadataReadiness: Readiness;
  /** may the Builder send typed V2 definition operations? */
  definitionOps: Readiness;
};

export function backendFrom(p: ProbeState): Backend {
  if (p.status === "loading") return { metadata: new Map(), metadataReadiness: loading(), definitionOps: loading() };
  if (p.status === "error") {
    const metadataReadiness = readinessFromError(p.error, STATIC_NOT_READY.DEFINITION_OPERATIONS ?? "Máy chủ chưa có component-metadata.");
    return { metadata: new Map(), metadataReadiness, definitionOps: metadataReadiness.state === "NOT_READY" ? notReady(STATIC_NOT_READY.DEFINITION_OPERATIONS ?? "") : metadataReadiness };
  }
  return { metadata: new Map(p.metadata.map((m) => [m.id, m])), metadataReadiness: available(), definitionOps: available() };
}

export { failed };
