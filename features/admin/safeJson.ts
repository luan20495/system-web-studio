/**
 * Text the server stores as JSON but does not guarantee to be JSON (audit old / new values, a component's props schema): a screen shows what it got, it never throws on it.
 * Pure; unit-tested (tests/builder/safejson.test.ts).
 */

/** the parsed value, or the text itself when it is not JSON; a null / empty value is returned as it is */
export function parseJsonOr(raw: string | null | undefined): unknown {
  if (!raw) return raw;
  try { return JSON.parse(raw); } catch { return raw; }
}

/** pretty-printed JSON, or the raw text when it is not JSON; `empty` for a null / empty value */
export function prettyJson(raw: string | null | undefined, empty = "—"): string {
  if (!raw) return empty;
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; }
}
