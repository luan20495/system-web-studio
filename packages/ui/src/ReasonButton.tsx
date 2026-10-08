"use client";
/**
 * ReasonButton (M-031): a button that can be unavailable AND says why, where everyone can see it.
 * A `disabled` button with `title="Bật mô hình trước"` hides the reason from touch users (no hover), from keyboard users (a disabled button is not focusable, so
 * it never gets its tooltip) and from most screen readers. Here an unavailable action is `aria-disabled="true"` (still focusable and announced as "unavailable"),
 * its click does nothing, and the reason is VISIBLE text tied to the button with aria-describedby. Never title-only.
 *
 *   <ReasonButton className="btn sm" unavailable={!enabled} reason="Bật mô hình trước." onClick={makeDefault}>Đặt làm mặc định</ReasonButton>
 *
 * `unavailable` without a `reason` is a programming error in the caller and falls back to a native `disabled` button (nothing to say, nothing hidden).
 * While `busy` the button is aria-busy and ignores clicks, but is not "unavailable" (no reason is shown).
 */
import { useId, type ButtonHTMLAttributes, type MouseEvent } from "react";

export type ReasonButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "disabled" | "title"> & {
  unavailable?: boolean; reason?: string; busy?: boolean;
  /** where the reason goes: under the button (default) or after it on the same line */ reasonPlacement?: "below" | "inline";
};

export function ReasonButton({ unavailable = false, reason, busy = false, reasonPlacement = "below", onClick, className, children, type = "button", ...rest }: ReasonButtonProps) {
  const id = useId(); const explained = unavailable && !!reason;
  const guard = (e: MouseEvent<HTMLButtonElement>) => { if (unavailable || busy) { e.preventDefault(); return; } onClick?.(e); };
  return (
    <span className={`xp-reasonWrap ${reasonPlacement}`}>
      <button {...rest} type={type} className={`${className ?? ""}${explained ? " xp-reasonBtn" : ""}`.trim() || undefined}
        disabled={unavailable && !reason ? true : undefined} aria-disabled={explained ? true : undefined} aria-busy={busy || undefined}
        aria-describedby={[explained ? id : null, rest["aria-describedby"]].filter(Boolean).join(" ") || undefined} onClick={guard}>{children}</button>
      {explained ? <small id={id} className="xp-reason">{reason}</small> : null}
    </span>
  );
}
