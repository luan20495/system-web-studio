// @class: harness — test-only host for the REAL <StudioApp>; NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// next/link replacement: an <a> that goes through the virtual router.
import { router } from "./nav-stub";
export default function Link({ href, onClick, children, ...rest }: any) {
  return <a href={href} {...rest} onClick={(e) => { onClick?.(e); if (e.defaultPrevented || e.metaKey || e.ctrlKey || rest.target === "_blank") return; e.preventDefault(); router.push(String(href)); }}>{children}</a>;
}
