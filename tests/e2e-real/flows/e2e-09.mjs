// @class: real-backend — BLOCKED skeleton: a real data side effect cannot be produced by any production connector.
// SEPARATION once unblocked: [fixture] source+slot+binding+mutation seeded · [api] replay with the SAME key · [ui] submit in the published app · [backend] row read back · [cleanup] delete the row and unbind.
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-09", title = "Mutation/action success with a real side effect and correct UI";
export const blocker = { owner: "C3", ref: "H-C2-02 + H-C0-07 + B-C5-06", reason: "A LIVE data mutation needs a declared slot (no op: C2 H-C2-02), a LIVE binding to a WRITABLE source the gateway may reach (the writable PostgreSQL connector and mutation definitions exist on integration/v2, but a local stack cannot reach a source: public address required, H-C0-07) and a published app (no data host, B-C5-06). TEST mode never writes (WOULD_RUN) — covered by E2E-S1. The client rule is tested at unit level: an ambiguous mutation is IDEMPOTENCY_OUTCOME_UNKNOWN, never retried, never shown as success." };
export async function run() { throw new Blocked(blocker.owner, blocker.reason, blocker.ref); }
// Intended steps once unblocked: publish the app; as a user with DATA_MUTATE open the published app, submit the form bound to a CREATE_RECORD action with a fresh Idempotency key,
// assert (a) 200 envelope status OK, (b) the row exists in the source (read back through a READ query), (c) the UI shows success only after (a), (d) replaying the SAME key does not write twice.
