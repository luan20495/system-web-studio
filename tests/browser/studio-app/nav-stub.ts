// @class: harness — test-only host for the REAL <StudioApp>; NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Virtual next/navigation: the path lives in module state (`?start=` gives the first one), window.location is never touched. Every push/replace is logged in window.__nav.log.
// HARNESS stub of next/navigation: a virtual router (path + query kept in module state, never touching window.location).
import { useSyncExternalStore } from "react";
type L = () => void;
const listeners = new Set<L>();
const init = new URLSearchParams(window.location.search).get("start") ?? "/studio";
let cur = init;
(window as unknown as { __nav: { log: string[]; path: string } }).__nav = { log: [] as string[], get path() { return cur; } };
const sub = (l: L) => { listeners.add(l); return () => { listeners.delete(l); }; };
const get = () => cur;
const go = (to: string, kind: string) => { (window as unknown as { __nav: { log: string[]; path: string } }).__nav.log.push(`${kind} ${to}`); cur = to; listeners.forEach((l) => l()); };
export const router = { push: (to: string) => go(to, "push"), replace: (to: string) => go(to, "replace"), back: () => (window as unknown as { __nav: { log: string[]; path: string } }).__nav.log.push("back"), forward() {}, refresh() {}, prefetch() {} };
export const useRouter = () => router;
export const usePathname = () => useSyncExternalStore(sub, get, get).split("?")[0];
export const useSearchParams = () => { const p = useSyncExternalStore(sub, get, get); return new URLSearchParams(p.includes("?") ? p.slice(p.indexOf("?") + 1) : ""); };
