// @class: real-backend — THE canonical PAGE_SCHEMA public-data flow, end to end on a real stack: Studio (slot → READ query → public → bind) → publish review (PUBLIC_QUERIES + acknowledgement) → anonymous visitor →
// runtime config → apiBase → same-origin /{slug}/_data → PUBLIC_SITE → LIVE query → DataGateway → real data source → rendered text. No stub, no interception, no seeded rows: the data comes from a REAL data source the
// operator named (E2E_DS_* + E2E_PD_SQL or E2E_PD_OPERATION_KEY + E2E_PD_EXPECT_TEXT). Prerequisites that belong to someone else end BLOCKED with the exact missing piece (never a pass, never a workaround).
import { chain } from "../lib/publicdata.mjs";
export const id = "E2E-PD01", title = "Public data V1, canonical chain: Studio slot → READ public query → bind → publish (PUBLIC_QUERIES, acknowledgement) → anonymous page → config → apiBase → /{slug}/_data → LIVE → rendered";
export const run = (ctx) => chain(ctx, { withData: true });
