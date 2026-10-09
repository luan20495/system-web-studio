"use client";
import { useEffect, useRef, type ReactNode } from "react";

/**
 * A server refusal inside a dialog (M-034). It sits right above the submit row, but the footer is sticky: on a short screen the message used to render BELOW the fold,
 * under the footer, and the person saw nothing happen. It is an alert (announced), and when it appears or changes it is scrolled into view ABOVE the sticky footer
 * (scroll-margin-bottom leaves room for it). Field errors keep their own ids and aria-describedby; this is for the form-level message.
 */
export function FormError({ children, className = "formError", ...rest }: { children: ReactNode; className?: string; "data-testid"?: string; "data-kind"?: string }) {
  const ref = useRef<HTMLParagraphElement>(null);
  useEffect(() => { ref.current?.scrollIntoView?.({ block: "nearest" }); }, [children]);
  return <p ref={ref} className={className} role="alert" style={{ scrollMarginBottom: 96 }} {...rest}>{children}</p>;
}
