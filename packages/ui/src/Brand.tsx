/**
 * XWEB logo family as React (C5-S3). The CANONICAL artwork is the SVG files in packages/ui/brand/ (xweb-mark.svg, xweb-mark-small.svg, xweb-logo-*.svg);
 * the path data below is the same geometry and tests/builder/brand.test.ts fails when the two drift. Colours come from the semantic tokens
 * (--color-brand-tile / --color-brand-tile-accent / --color-brand-ink, ui.css `.xp-brand*`), so one component is right on a light page, on the dark
 * Studio / sidebar and in forced-colors mode. Rendered inline: no request, no layout shift, no network asset.
 * Usage rules (clear space, minimum size, misuse): docs/BRAND_GUIDELINE.md.
 */
import { useId } from "react";
import { BRAND, type BrandPortal } from "../../i18n/src/brand";

/** the mark at >= 25 px: two chevrons meeting at a gap ("modules joining"), stroke 3 on a 32 grid */
export const MARK_PATHS = { left: "M9 9.5 14 16l-5 6.5", right: "M23 9.5 18 16l5 6.5", stroke: 3, radius: 8 } as const;
/** the small mark (16 / 24 px, favicon): heavier stroke and a wider gap so the two chevrons do not merge on a 16 px pixel grid */
export const MARK_SMALL_PATHS = { left: "M8.5 9 13 16l-4.5 7", right: "M23.5 9 19 16l4.5 7", stroke: 4, radius: 7 } as const;
/** the wordmark X W E B, monoline stroke 3, 20 units high, drawn at x = 46 next to the 32-unit mark */
export const WORDMARK_PATHS = ["M1.5 1.5 7 10l-5.5 8.5M16.5 1.5 11 10l5.5 8.5", "M23 1.5l4 17 5-12.5 5 12.5 4-17", "M60 1.5H48.5v17H60M48.5 10H58", "M66.5 1.5v17h9.25a4.25 4.25 0 0 0 0-8.5H66.5m0-8.5h8.25a4.25 4.25 0 0 1 0 8.5"] as const;

type MarkProps = { size?: number; mono?: boolean; className?: string; title?: string };

function Glyph({ small, mono, maskId }: { small: boolean; mono: boolean; maskId: string }) {
  const g = small ? MARK_SMALL_PATHS : MARK_PATHS;
  if (mono) return (
    <>
      <defs><mask id={maskId}><rect width="32" height="32" rx={g.radius} fill="#fff"/><g fill="none" stroke="#000" strokeWidth={g.stroke} strokeLinecap="round" strokeLinejoin="round"><path d={g.left}/><path d={g.right}/></g></mask></defs>
      <rect width="32" height="32" rx={g.radius} fill="currentColor" mask={`url(#${maskId})`}/>
    </>
  );
  return (
    <>
      <rect className="xp-brandTile" width="32" height="32" rx={g.radius}/>
      <g fill="none" strokeWidth={g.stroke} strokeLinecap="round" strokeLinejoin="round"><path className="xp-brandGlyph" d={g.left}/><path className="xp-brandGlyphAccent" d={g.right}/></g>
    </>
  );
}

/** B / F / G: the square mark. Decorative by default (aria-hidden); pass `title` when the mark is the only thing that names the product. */
export function BrandMark({ size = 32, mono = false, className, title }: MarkProps) {
  const id = useId().replace(/:/g, "");
  return (
    <svg className={`xp-brandMark${className ? ` ${className}` : ""}`} viewBox="0 0 32 32" width={size} height={size} focusable="false" {...(title ? { role: "img", "aria-label": title } : { "aria-hidden": true })}>
      <Glyph small={size <= 24} mono={mono} maskId={`xwm${id}`}/>
    </svg>
  );
}

/** A / C / D / E: the horizontal logo (mark + XWEB wordmark). One image with the product name as its accessible name. Light / dark follow the page tokens. */
export function BrandLogo({ height = 24, mono = false, className, title = BRAND.product }: { height?: number } & Omit<MarkProps, "size">) {
  const id = useId().replace(/:/g, "");
  return (
    <svg className={`xp-brandLogo${className ? ` ${className}` : ""}`} viewBox="0 0 136 32" height={height} width={(height * 136) / 32} role="img" aria-label={title} focusable="false">
      <Glyph small={height <= 24} mono={mono} maskId={`xwl${id}`}/>
      <g className={mono ? undefined : "xp-brandInk"} transform="translate(46 6)" fill="none" stroke={mono ? "currentColor" : undefined} strokeWidth="3" strokeLinejoin="round">{WORDMARK_PATHS.map((d) => <path key={d} d={d}/>)}</g>
    </svg>
  );
}

/** the sidebar / header lockup: the logo plus the portal context in words (Platform, Quản trị công ty, Studio). Same logo everywhere; only the words differ. */
export function BrandLockup({ portal, height = 22 }: { portal: BrandPortal; height?: number }) {
  return (
    <div className="xp-brandLockup" data-portal={portal}>
      <BrandLogo height={height}/>
      <small className="xp-brandContext">{BRAND.context[portal]}</small>
    </div>
  );
}
