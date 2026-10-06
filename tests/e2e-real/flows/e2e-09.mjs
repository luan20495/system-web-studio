// @class: real-backend — BLOCKED skeleton: a real data side effect cannot be produced by any production connector.
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-09", title = "Mutation/action success with a real side effect and correct UI";
export const blocker = { owner: "C3", ref: "B-C0-W-04 + B-C0-W-03 + B-C0-W-01", reason: "The production connectors (postgres, rest) are read-only, so a LIVE data mutation answers MUTATION_UNSUPPORTED (B-C0-W-04); no HTTP route registers a source (B-C0-W-03); LIVE mutating actions answer 503 RUNTIME_STORES_VOLATILE until V29 or allow-volatile-stores (B-C0-W-01). TEST mode never writes (WOULD_RUN) — that part is covered by E2E-S1." };
export async function run() { throw new Blocked(blocker.owner, blocker.reason, blocker.ref); }
// Intended steps once unblocked: publish the app; as a user with DATA_MUTATE open the published app, submit the form bound to a CREATE_RECORD action with a fresh Idempotency key,
// assert (a) 200 envelope status OK, (b) the row exists in the source (read back through a READ query), (c) the UI shows success only after (a), (d) replaying the SAME key does not write twice.
