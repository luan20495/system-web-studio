"use client";
/**
 * Button: the ONE button of the product (M-068). It renders the vocabulary documented in factory.css ("BUTTON VOCABULARY"):
 *
 *   <Button variant="primary" onClick={save}>Lưu</Button>
 *   <Button variant="danger" size="sm" onClick={remove}>Xóa ứng dụng</Button>          outlined red; add `filled` for the confirm button of a destructive dialog
 *   <Button icon aria-label="Đóng"><X size={16} aria-hidden="true"/></Button>        icon-only needs an accessible name
 *   <Button busy>Đang lưu…</Button>                                                       aria-busy, ignores clicks, stays focusable
 *
 * `type` defaults to "button" (a form submit is `type="submit"` on purpose). A button that is unavailable for a REASON is a ReasonButton (visible reason, aria-disabled).
 * Plain `disabled` is for "not available and nothing to explain". Native attributes pass through.
 */
import type { ButtonHTMLAttributes, MouseEvent } from "react";

export type ButtonVariant = "secondary" | "primary" | "danger" | "ghost";
export type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className"> & {
  variant?: ButtonVariant; size?: "md" | "sm"; icon?: boolean; block?: boolean; busy?: boolean;
  /** with variant="danger": the filled (red background) form, for the confirm button of a destructive dialog */
  filled?: boolean; className?: string;
};

/** the class string of a button, shared with anything that must look like one (an <a> styled as a button) */
export function buttonClass({ variant = "secondary", size = "md", icon = false, block = false, filled = false, className }: Pick<ButtonProps, "variant" | "size" | "icon" | "block" | "filled" | "className">): string {
  return ["btn", variant === "primary" ? "primary" : variant === "danger" ? (filled ? "primary danger" : "danger") : variant === "ghost" ? "ghost" : "", size === "sm" ? "sm" : "", icon ? "icon" : "", block ? "block" : "", className ?? ""].filter(Boolean).join(" ");
}

export function Button({ variant, size, icon, block, filled, busy = false, className, type = "button", onClick, ...rest }: ButtonProps) {
  const guard = (e: MouseEvent<HTMLButtonElement>) => { if (busy) { e.preventDefault(); return; } onClick?.(e); };
  return <button {...rest} type={type} className={buttonClass({ variant, size, icon, block, filled, className })} aria-busy={busy || undefined} onClick={guard}/>;
}
