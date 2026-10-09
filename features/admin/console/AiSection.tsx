"use client";
import { AiAdmin } from "../AiSetup";
import { AiPage, PricingCard } from "../pages/AiUsagePages";

/** The Platform "AI" section as ONE lazy chunk (sections.tsx): AI setup + usage + pricing are fetched together on the first visit. */
export function AiSection({ tab }: { tab?: string }) {
  return <AiAdmin tab={tab} usage={<AiPage/>} pricing={<PricingCard/>}/>;
}
