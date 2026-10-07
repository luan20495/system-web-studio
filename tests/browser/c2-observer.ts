// @class: harness — passive observer used ONLY by the C2-runtime fixture page: it reads what C2's runtime wrote (data-xw-state / xw:state) through C5's own vocabulary function. No state machine.
import { describeRuntimeState } from "../../features/studio/builder/core/publicData";
declare global { interface Window { __views: ReturnType<typeof describeRuntimeState>[]; __view: () => ReturnType<typeof describeRuntimeState> } }
window.__views = [];
const root = () => document.querySelector("[data-xw-runtime]");
window.__view = () => describeRuntimeState(root()?.getAttribute("data-xw-state"), root()?.getAttribute("data-xw-detail"));
const r = root();
// C2's runtime is `defer`red, so this script runs first and sees every state change it announces
if (r) { r.addEventListener("xw:state", () => { const v = window.__view(); if (v) window.__views.push(v); }); }
