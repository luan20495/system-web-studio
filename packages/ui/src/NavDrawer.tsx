"use client";
/**
 * Mobile navigation for the three shells. At ≤ 900 px the sidebar is a drawer: the header shows a menu button, the drawer slides in over a backdrop, Escape / the backdrop / choosing a page close it, and focus returns to the button.
 * Wide screens are unchanged (the sidebar is always visible and the button is hidden by CSS).
 *
 * Focus (M-013): a CLOSED drawer is `visibility:hidden` (factory.css) so none of its links is a Tab stop or in the accessibility tree.
 * While it is OPEN, focus moves to its first link, Tab / Shift+Tab wrap inside it, the rest of the shell (header + page) is `inert`, and closing returns focus to the menu button.
 * The drawer is found through the menu button's `aria-controls`; nothing else has to be wired by the screen.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { Menu } from "./icons";
import { tabbables, trapTab } from "./focus";

const NARROW = "(max-width: 900px)";

export function useNavDrawer() {
  const [open, setOpen] = useState(false); const path = usePathname(); const button = useRef<HTMLButtonElement>(null);
  useEffect(() => { setOpen(false); }, [path]);
  // growing past the drawer breakpoint with the drawer open would leave the page inert next to a static sidebar
  useEffect(() => {
    if (!open || typeof window === "undefined" || !window.matchMedia) return;
    const mq = window.matchMedia(NARROW); const on = () => { if (!mq.matches) setOpen(false); };
    mq.addEventListener("change", on); return () => mq.removeEventListener("change", on);
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const btn = button.current;
    const sidebar = btn ? document.getElementById(btn.getAttribute("aria-controls") ?? "") : null;
    const inerted: Element[] = [];
    if (sidebar?.parentElement) for (const c of Array.from(sidebar.parentElement.children)) {
      if (c !== sidebar && !c.classList.contains("sideBackdrop") && !c.hasAttribute("inert")) { c.setAttribute("inert", ""); inerted.push(c); }
    }
    if (sidebar) (tabbables(sidebar)[0] ?? sidebar).focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { setOpen(false); return; }
      if (sidebar) trapTab(e, sidebar);
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      inerted.forEach((c) => c.removeAttribute("inert"));
      const a = document.activeElement;
      if (!a || a === document.body || sidebar?.contains(a)) button.current?.focus();
    };
  }, [open]);
  const toggle = useCallback(() => setOpen((v) => !v), []); const close = useCallback(() => setOpen(false), []);
  return { open, toggle, close, button };
}

export function MenuButton({ open, onClick, buttonRef, controls }: { open: boolean; onClick: () => void; buttonRef?: React.Ref<HTMLButtonElement>; controls: string }) {
  return <button type="button" ref={buttonRef} className="btn sm ghost navToggle xp-btnIcon" aria-label={open ? "Đóng menu điều hướng" : "Mở menu điều hướng"} aria-expanded={open} aria-controls={controls} onClick={onClick}><Menu size={18} aria-hidden="true"/></button>;
}
