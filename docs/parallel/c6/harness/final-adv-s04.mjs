// supplementary re-run of ADV-S04 (activation-link replay) when the shared activation limiter allows one call; no login, no fixture
import { S, recorder, st } from "./final-lib.mjs";
import { loadState } from "./final-iam-fx.mjs";
const { rec, save } = recorder("final-adv-s04"); const state = loadState();
const a = new S(); a.csrf = (await a.call("GET", "/api/v1/auth/csrf")).json?.token;
const r = await a.call("POST", "/api/v1/auth/activation/complete", { token: state.tenants.X.taToken, password: "Zz9!" + Math.random().toString(36).slice(2, 10) });
rec("ADV-S04b", "session", "replay of an already used one-time activation token (1 limiter call)", "410 LINK_INVALID", st(r), r.status === 410 ? true : r.status === 429 ? null : false, r.status === 429 ? { note: "BLOCKED: shared 30/600s activation limiter" } : {});
process.exit(save() ? 1 : 0);
