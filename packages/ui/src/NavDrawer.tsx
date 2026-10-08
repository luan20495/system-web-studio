"use client";
/**
 * Mobile navigation for the three shells. At ≤ 900 px the sidebar is a drawer: the header shows a menu button, the drawer slides in over a backdrop, Escape / the backdrop / choosing a page close it, and focus returns to the button.
 * Wide screens are unchanged (the sidebar is always visible and the button is hidden by CSS).
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { Menu } from "./icons";

export function useNavDrawer() {
  const [open, setOpen] = useState(false); const path = usePathname(); const button = useRef<HTMLButtonElement>(null);
  useEffect(() => { setOpen(false); }, [path]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setOpen(false); button.current?.focus(); } };
    document.addEventListener("keydown", onKey); return () => document.removeEventListener("keydown", onKey);
  }, [open]);
  const toggle = useCallback(() => setOpen((v) => !v), []); const close = useCallback(() => setOpen(false), []);
  return { open, toggle, close, button };
}

export function MenuButton({ open, onClick, buttonRef, controls }: { open: boolean; onClick: () => void; buttonRef?: React.Ref<HTMLButtonElement>; controls: string }) {
  return <button type="button" ref={buttonRef} className="btn sm ghost navToggle xp-btnIcon" aria-label={open ? "Đóng menu điều hướng" : "Mở menu điều hướng"} aria-expanded={open} aria-controls={controls} onClick={onClick}><Menu size={18} aria-hidden="true"/></button>;
}
