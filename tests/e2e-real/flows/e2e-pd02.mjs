// @class: real-backend — the DOCUMENT side of public data on a real stack (no data source needed): Studio authors a slot, a READ query, `public`, a binding through the real UI; the publish review lists PUBLIC_QUERIES and asks for the
// acknowledgement; the release contract is unchanged; the server freezes the PUBLIC_QUERIES event; the published page serves a runtime config with a same-origin apiBase and makes exactly ONE anonymous request for the public query.
// It claims NOTHING about data: what the visitor receives is E2E-PD01's job (needs a real source the C3 policy accepts).
import { chain } from "../lib/publicdata.mjs";
export const id = "E2E-PD02", title = "Public data V1, document side: slot → READ public query → bind → publish approval → PUBLIC_QUERIES → runtime config → one anonymous request";
export const run = (ctx) => chain(ctx, { withData: false });
