// @class: real-backend — what to expect when a workspace VIEWER opens Studio, once C1 has DECIDED (H-C1-03). C5 never decides the policy: the operator passes it as E2E_VIEWER_POLICY.
//   unset      → undecided: the flows collect their evidence and end BLOCKED(C1) when the viewer is refused
//   app-view   → decided "a viewer reaches Studio read-only": being refused is a FAILED check
//   no-studio  → decided "viewers are not Studio users": being refused is the expected, PASSING behaviour
/** @returns "blocked" | "refused-ok" | "refused-fail" | "continue" — what the caller does next */
export function viewerGate(cfg, gated, check, url = "") {
  const policy = cfg.viewerPolicy;
  if (policy === "no-studio") { check.ok("policy no-studio: the viewer is refused at the Studio gate (/auth/no-access)", gated, url); return gated ? "refused-ok" : "continue"; }
  if (policy === "app-view") { check.ok("policy app-view: the viewer reaches Studio (not /auth/no-access)", !gated, url); return gated ? "refused-fail" : "continue"; }
  return gated ? "blocked" : "continue";
}
export const VIEWER_BLOCKER = "a workspace VIEWER holds permissions [] (GET /auth/me), so the Studio portal gate sends the viewer to /auth/no-access although the project lists them as a member; the read-only Builder cannot be observed. Decision needed from C1 (H-C1-03): should a project member without workspace permissions reach Studio read-only? Re-run with E2E_VIEWER_POLICY=app-view or no-studio once decided.";
