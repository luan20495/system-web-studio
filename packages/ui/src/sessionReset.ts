// Side-effect module (listed in package.json "sideEffects"): the keyed useLoad cache must never outlive the identity it was filled for.
// On login, logout and a 401 inside a session, the api-client announces `sessionChanged()`; the cache is cleared here.
import { onSessionChange } from "../../api-client/src/core";
import { clearLoadCache } from "./useLoad";

onSessionChange(() => clearLoadCache());
