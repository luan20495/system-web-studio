// @class: harness — test-only stand-in for `next/navigation` and `next/link` (esbuild alias in build-harness.mjs); history-based router. NOT part of any shipped bundle.
import * as React from "react";

const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());
export function navigate(to: string, replace = false) {
  const u = new URL(to, location.href);
  (replace ? history.replaceState : history.pushState).call(history, null, "", u.pathname + u.search + u.hash);
  emit();
}
if (typeof window !== "undefined") window.addEventListener("popstate", emit);
const snap = () => location.pathname + "\u0000" + location.search;
const sub = (cb: () => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };
export function usePathname() { const s = React.useSyncExternalStore(sub, snap, snap); return s.split("\u0000")[0]; }
export function useSearchParams() { const s = React.useSyncExternalStore(sub, snap, snap); return React.useMemo(() => new URLSearchParams(s.split("\u0000")[1]), [s]); }
export function useRouter() { return React.useMemo(() => ({ push: (t: string) => navigate(t), replace: (t: string) => navigate(t, true), back: () => history.back(), refresh() {}, prefetch() {} }), []); }

type LinkProps = React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string; replace?: boolean; prefetch?: boolean };
const Link = React.forwardRef<HTMLAnchorElement, LinkProps>(function Link({ href, replace, prefetch: _prefetch, onClick, ...rest }, ref) {
  return <a ref={ref} href={href} {...rest} onClick={(e) => { onClick?.(e); if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || /^https?:/.test(href)) return; e.preventDefault(); navigate(href, replace); }} />;
});
export default Link;
