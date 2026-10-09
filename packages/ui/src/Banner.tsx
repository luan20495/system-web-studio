/**
 * XWEB banner templates (C5-S3, docs/BRAND_GUIDELINE.md section 12): one component, three compositions.
 *   intro       product / section introduction: cobalt wash + soft glow (.xp-bg-hero)
 *   docs        documentation / demo: quiet grid pattern on the surface (.xp-bg-pattern)
 *   onboarding  login / first-run: the auth glow over the page background (.xp-bg-auth)
 * Layout: a text column (safe area: 300px minimum, 62ch maximum, never covered) and a DECORATIVE media column (aria-hidden, dropped below 600px).
 * Put meaning in `title` / `children` / `actions`, never in `media`. Light / dark follow the page tokens.
 */
import { useId, type ReactNode } from "react";
import { BrandMark } from "./Brand";

export type BannerProps = {
  variant?: "intro" | "docs" | "onboarding";
  eyebrow?: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
  /** decorative art; defaults to the XWEB mark. Hidden from assistive technology and on phones. */
  media?: ReactNode;
  /** heading level of the title (2 by default: a page keeps its single h1) */
  level?: 1 | 2 | 3;
  className?: string;
};

const BG = { intro: "xp-bg-hero", docs: "xp-bg-pattern", onboarding: "xp-bg-auth" } as const;

export function Banner({ variant = "intro", eyebrow, title, children, actions, media, level = 2, className }: BannerProps) {
  const id = useId(); const H = `h${level}` as "h1" | "h2" | "h3";
  return (
    <section className={`xp-banner xp-banner-${variant} ${BG[variant]}${className ? ` ${className}` : ""}`} aria-labelledby={id} data-variant={variant}>
      <div className="xp-bannerText">
        {eyebrow ? <p className="xp-bannerEyebrow">{eyebrow}</p> : null}
        <H className="xp-bannerTitle" id={id}>{title}</H>
        {children ? <div className="xp-bannerBody">{children}</div> : null}
        {actions ? <div className="xp-bannerActions">{actions}</div> : null}
      </div>
      <div className="xp-bannerMedia" aria-hidden="true">{media ?? <BrandMark size={96}/>}</div>
    </section>
  );
}
