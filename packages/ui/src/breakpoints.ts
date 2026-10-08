/**
 * The responsive breakpoints of the portals (M-087). CSS media queries cannot read custom properties, so the same numbers are written in the stylesheets;
 * tests/builder/design-tokens.test.ts keeps the stylesheets and this file in step and fails when a NEW max-width threshold is introduced.
 * Use these from TypeScript (`matchMedia(MQ.tablet)`) instead of a literal.
 */
export const BREAKPOINT = { tablet: 900, phone: 760, narrow: 600 } as const;
export const MQ = { tablet: `(max-width: ${BREAKPOINT.tablet}px)`, phone: `(max-width: ${BREAKPOINT.phone}px)`, narrow: `(max-width: ${BREAKPOINT.narrow}px)` } as const;
