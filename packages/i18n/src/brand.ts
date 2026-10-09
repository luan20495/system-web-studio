/**
 * Product and portal names: ONE source (M-063). The UI used four names for the same product (AI Software Factory, Company Builder Studio, Admin Console, Builder Studio);
 * `PORTAL_LABEL` in @xweb/permissions, the three `apps/* /layout.tsx` titles, the sidebar brands and the login picker each said something different.
 *
 * DECISIONS FOR THE PRODUCT OWNER (docs/parallel/c5/audit/S3-glossary.md section 1): the product name "Xweb" and the three portal names below are the proposal that matches what
 * the repository and PORTAL_LABEL already used. `legacyNames` are the names that must disappear from user-visible text; a unit test ratchets their count down.
 */
export const BRAND = {
  product: "Xweb",
  portal: { platform: "Xweb Platform", admin: "Quản trị công ty", studio: "Xweb Studio" },
  /** the browser-tab / <title> form: the portal name, then the product when the portal name does not already carry it */
  title: { platform: "Xweb Platform", admin: "Xweb · Quản trị công ty", studio: "Xweb Studio" },
  /** the words under the XWEB logo in each portal's sidebar (BrandLockup, @xweb/ui): one logo, the portal named in words (docs/BRAND_GUIDELINE.md section 13) */
  context: { platform: "Platform", admin: "Quản trị công ty", studio: "Studio" },
  legacyNames: ["AI Software Factory", "Company Builder Studio", "Admin Console", "Builder Studio"],
} as const;

export type BrandPortal = keyof typeof BRAND.portal;
