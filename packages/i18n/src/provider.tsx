"use client";
/**
 * I18nProvider (M-071 phase 0): the place where the locale of the UI is decided for the client tree. With only `vi` it changes nothing visible; it gives the
 * later phases (cookie / Accept-Language negotiation, catalogues) the seam, and gives `useLocale()` / `useFormat()` to components that want locale-aware values.
 * A component outside a provider gets the default locale: the provider is NOT required for the existing helpers (`fmtDate`, `num`, ... keep working unchanged).
 */
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { DEFAULT_LOCALE, dirOf, langOf, type Locale } from "./locale";
import { getFormatters, setActiveLocale, type Formatters } from "./format";

export type I18nValue = { locale: Locale; lang: string; dir: "ltr" | "rtl"; fmt: Formatters };
const make = (locale: Locale): I18nValue => ({ locale, lang: langOf(locale), dir: dirOf(locale), fmt: getFormatters(locale) });
const DEFAULT_VALUE = make(DEFAULT_LOCALE);
const Ctx = createContext<I18nValue>(DEFAULT_VALUE);

export function I18nProvider({ locale = DEFAULT_LOCALE, children }: { locale?: Locale; children: ReactNode }) {
  const value = useMemo(() => make(locale), [locale]);
  setActiveLocale(locale);                                  // idempotent; keeps the free formatter functions in step with the provider
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
export const useI18n = (): I18nValue => useContext(Ctx);
export const useLocale = (): Locale => useContext(Ctx).locale;
export const useFormat = (): Formatters => useContext(Ctx).fmt;
