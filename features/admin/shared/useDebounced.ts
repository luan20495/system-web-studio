"use client";
import { useEffect, useState } from "react";

/**
 * M-097: the value after the person stopped typing for `ms` (one request per pause, not one per key). Local to the consoles; a candidate for @xweb/ui (S3 owns packages/ui).
 * An empty value is applied at once (clearing a search should not wait).
 */
export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    if (value === "" || value === v) { setV(value); return; }
    const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, ms]);
  return v;
}

/** keys of the shared `useLoad` cache for lists more than one console screen shows at once (M-097); platform-wide data, cleared on sign-out with the rest of the cache */
export const AI_PROVIDERS_KEY = "admin:ai:providers";
export const AI_LIMITS_KEY = "admin:ai:limits";
