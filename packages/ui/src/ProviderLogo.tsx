import { siAnthropic, siGooglegemini, siOpenrouter } from "simple-icons";
import { Plug, Server, Sparkles, type LucideIcon } from "./icons";

/**
 * Logo of an AI provider KIND. Brand marks come from the Simple Icons package (CC0; the trademarks stay with their owners and are used here only to identify the provider, as a single-colour mark in a neutral tile,
 * never in a way that suggests endorsement). OpenAI has no entry: Simple Icons removed it at the owner's request, so it gets a generic Lucide icon and its NAME — we ship no copy of a mark we are not licensed to.
 * Local / OpenAI-compatible are generic by nature. Decorative: the name is always next to it.
 */
type Brand = { path: string; title: string };
const BRAND: Record<string, Brand> = { OPENROUTER: siOpenrouter, ANTHROPIC: siAnthropic, GEMINI: siGooglegemini };
const GENERIC: Record<string, LucideIcon> = { OPENAI: Sparkles, OPENAI_COMPATIBLE: Plug, LOCAL: Server };

export function ProviderLogo({ kind, size = 36, className = "" }: { kind: string; size?: number; className?: string }) {
  const brand = BRAND[kind]; const Icon = GENERIC[kind] ?? Server;
  return (
    <span className={`xp-logo ${className}`} style={{ width: size, height: size }} aria-hidden="true" data-kind={kind} data-brand={brand ? "brand" : "generic"}>
      {brand
        ? <svg viewBox="0 0 24 24" width={Math.round(size * 0.56)} height={Math.round(size * 0.56)} fill="currentColor" role="presentation" focusable="false"><path d={brand.path}/></svg>
        : <Icon size={Math.round(size * 0.52)} strokeWidth={1.75}/>}
    </span>
  );
}
export const hasBrandLogo = (kind: string): boolean => kind in BRAND;
