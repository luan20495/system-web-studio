/** Approved design tokens. Visual editing (Design mode) only offers these values. */
export const spacing = { none: "0", xs: "4px", sm: "8px", md: "16px", lg: "24px", xl: "40px" } as const;
export type Space = keyof typeof spacing;
export const tones = ["neutral", "primary", "success", "warning", "danger"] as const;
export type Tone = (typeof tones)[number];
export const sp = (s?: Space) => (s ? spacing[s] : undefined);
