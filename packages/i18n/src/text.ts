/**
 * Helpers of the text library. The rule behind every label map in this package: a person never sees a code. A value the map does not know falls back to a NEUTRAL word
 * and is reported once through `onUnmappedLabel` (the app can log it); it is never printed raw.
 */
export const NEUTRAL_LABEL = "Khác";

let unmapped: ((info: { map: string; value: string }) => void) | null = null;
const seen = new Set<string>();
/** wire a logger for values that have no label (the default is silent); each (map, value) pair is reported once */
export const onUnmappedLabel = (fn: ((info: { map: string; value: string }) => void) | null) => { unmapped = fn; seen.clear(); };

/** the label of `value`; unknown, empty or non-string values give `fallback` (default "Khác") and are reported */
export function labelOf<K extends string>(map: Readonly<Record<K, string>>, value: string | null | undefined, fallback: string = NEUTRAL_LABEL, mapName = "labels"): string {
  if (value === null || value === undefined || value === "") return fallback;
  const hit = (map as Readonly<Record<string, string | undefined>>)[value];
  if (hit !== undefined && Object.prototype.hasOwnProperty.call(map, value)) return hit;
  const key = `${mapName}:${value}`;
  if (!seen.has(key)) { seen.add(key); try { unmapped?.({ map: mapName, value }); } catch { /* reporting must never break the screen */ } }
  return fallback;
}

/** the keys of a label map, typed: for `<select>` options built from the same table that labels them */
export const labelKeys = <K extends string>(map: Readonly<Record<K, string>>): K[] => Object.keys(map) as K[];
