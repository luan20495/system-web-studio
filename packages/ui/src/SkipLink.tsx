"use client";
/**
 * SkipLink (M-025): the first Tab stop of a page; hidden until focused, then a visible pill at the top-left. It jumps over the sidebar / header (19 Tab presses on the Admin
 * portal) to <main id="main">. The link handles the click itself (focus + scroll) instead of leaving it to a `#main` hash change, because the App Router would turn the
 * hash into a navigation and the browser's focus-to-fragment is unreliable on an element that is only programmatically focusable.
 *
 *   <SkipLink/>               first child of the shell, before the sidebar
 */
import type { MouseEvent, ReactNode } from "react";
import { focusProgrammatically } from "./useMain";

export function SkipLink({ target = "main", children = "Bỏ qua điều hướng, tới nội dung chính" }: { target?: string; children?: ReactNode }) {
  function go(e: MouseEvent<HTMLAnchorElement>) {
    const el = document.getElementById(target); if (!el) return;
    e.preventDefault(); focusProgrammatically(el); el.scrollIntoView?.({ block: "start" });
  }
  return <a className="xp-skip" href={`#${target}`} onClick={go}>{children}</a>;
}
