/**
 * Locale-aware formatters (M-071 phase 0, M-103). For `vi` the OUTPUT IS IDENTICAL to the helpers that lived in packages/ui/src/ui.tsx (a unit test keeps the old
 * implementations as an oracle). What changes: every Intl object is created ONCE per locale and reused (the old `fmtDate` built a new `Intl.DateTimeFormat`
 * for every table cell: S2-044), and the locale comes from `locale.ts`, not from literals scattered over the files.
 */
import { DEFAULT_LOCALE, LOCALE_META, type Locale } from "./locale";

const AGO_WORDS: Record<Locale, { now: string; minutes: (n: number) => string; hours: (n: number) => string; days: (n: number) => string }> = {
  vi: { now: "vừa xong", minutes: (n) => `${n} phút trước`, hours: (n) => `${n} giờ trước`, days: (n) => `${n} ngày trước` }
};

export type Formatters = {
  /** "9 thg 10, 2026, 14:05" (medium date + short time); "—" for null / undefined / empty */
  fmtDate: (iso?: string | null) => string;
  /** relative time up to 30 days, then the date; "—" for null */
  ago: (iso?: string | null, now?: number) => string;
  /** integer / decimal with the locale's grouping; null counts as 0 */
  num: (n?: number | null) => string;
  /** provider-reported USD: null is "—" (not reported), 0 is "$0", tiny values keep 2 significant digits */
  usd: (n?: number | null) => string;
  /** token count: null is "—" (not reported) */
  tok: (n?: number | null) => string;
};

export function createFormatters(locale: Locale = DEFAULT_LOCALE): Formatters {
  const m = LOCALE_META[locale].intl; const words = AGO_WORDS[locale];
  const date = new Intl.DateTimeFormat(m.date, { dateStyle: "medium", timeStyle: "short" });
  const number = new Intl.NumberFormat(m.number);
  const money = new Intl.NumberFormat(m.currency, { maximumFractionDigits: 4 });
  const fmtDate: Formatters["fmtDate"] = (iso) => (iso ? date.format(new Date(iso)) : "—");
  return {
    fmtDate,
    ago(iso, now = Date.now()) {
      if (!iso) return "—";
      const s = Math.round((now - new Date(iso).getTime()) / 1000);
      if (s < 60) return words.now; if (s < 3600) return words.minutes(Math.floor(s / 60)); if (s < 86400) return words.hours(Math.floor(s / 3600));
      if (s < 86400 * 30) return words.days(Math.floor(s / 86400)); return fmtDate(iso);
    },
    num: (n) => number.format(n ?? 0),
    usd: (n) => (n == null ? "—" : n === 0 ? "$0" : `$${n < 0.01 ? n.toPrecision(2) : money.format(n)}`),
    tok: (n) => (n == null ? "—" : number.format(n))
  };
}

const cache = new Map<Locale, Formatters>();
/** the shared formatters of a locale (created once) */
export function getFormatters(locale: Locale = DEFAULT_LOCALE): Formatters {
  let f = cache.get(locale); if (!f) { f = createFormatters(locale); cache.set(locale, f); } return f;
}

/** the locale the free functions below use; set by <I18nProvider> (a screen that is not inside a provider keeps the default) */
let active: Locale = DEFAULT_LOCALE;
export const setActiveLocale = (l: Locale) => { active = l; };
export const activeLocale = (): Locale => active;
