/**
 * Locale constants (M-071 phase 0). Only `vi` exists; the point of this file is that NOTHING ELSE hard-codes "vi", "vi-VN" or `dir`:
 * the three apps' `<html lang dir>`, the formatters and the provider all read it. Adding a locale later is one entry here plus its messages.
 *
 * `intl.currency` is "en-US" ON PURPOSE: AI costs are USD and are shown as `$1,234.5` today; changing the grouping next to the vi-VN token counts would be a visible
 * change (S3-glossary / R2-i18n-theme A.7). It is a separate field so that decision is one line when the product owner takes it.
 */
export const SUPPORTED_LOCALES = ["vi"] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];
export const DEFAULT_LOCALE: Locale = "vi";

export type LocaleMeta = {
  /** the value of `<html lang>` */ lang: string;
  /** reading direction: the value of `<html dir>` and the base of the logical CSS properties */ dir: "ltr" | "rtl";
  /** the BCP-47 tags handed to Intl, per kind of value */ intl: { date: string; number: string; currency: string; collator: string };
};

export const LOCALE_META: Readonly<Record<Locale, LocaleMeta>> = {
  vi: { lang: "vi", dir: "ltr", intl: { date: "vi-VN", number: "vi-VN", currency: "en-US", collator: "vi" } }
};

export const isLocale = (v: unknown): v is Locale => typeof v === "string" && (SUPPORTED_LOCALES as readonly string[]).includes(v);
export const metaOf = (l: Locale = DEFAULT_LOCALE): LocaleMeta => LOCALE_META[l];
export const langOf = (l: Locale = DEFAULT_LOCALE): string => LOCALE_META[l].lang;
export const dirOf = (l: Locale = DEFAULT_LOCALE): "ltr" | "rtl" => LOCALE_META[l].dir;

/** the attributes of `<html>` for a locale: `<html {...htmlAttrs()}>` */
export const htmlAttrs = (l: Locale = DEFAULT_LOCALE): { lang: string; dir: "ltr" | "rtl" } => ({ lang: langOf(l), dir: dirOf(l) });
