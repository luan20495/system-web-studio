"use client";
/**
 * A toolbar button that can be unavailable AND says why when someone tries it (M-031, Studio top bars).
 *
 * The shared `ReasonButton` puts the reason as text next to the button; a top bar has no room for that, and its reasons were `title=` only (invisible on touch, never shown for a
 * disabled button to a keyboard user). Here an unavailable action is `aria-disabled="true"` (still focusable, announced as "unavailable"); pressing it (click, Enter, Space, tap)
 * raises the reason as a status toast (`toast.info`, role=status) instead of doing nothing. `disabled` stays a plain native disabled for a transient busy state, where the page already
 * says what is going on (the save indicator).
 */
import { useId, type ButtonHTMLAttributes, type MouseEvent } from "react";
import { toast } from "@xweb/ui";

export function GuardedButton({ unavailable = false, reason, onClick, children, type = "button", ...rest }: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "title"> & {
  unavailable?: boolean; reason: string;
}) {
  const id = useId();
  const guard = (e: MouseEvent<HTMLButtonElement>) => { if (unavailable) { e.preventDefault(); toast.info(reason, { id }); return; } onClick?.(e); };
  return <button {...rest} type={type} aria-disabled={unavailable || undefined} onClick={guard}>{children}</button>;
}
