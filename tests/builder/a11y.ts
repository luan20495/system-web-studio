/** Tiny static accessibility checks over rendered HTML (no DOM library in the repo): every button and form control must have an accessible name. */
const strip = (s: string) => s.replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").trim();
const attr = (tag: string, name: string) => new RegExp(`\\s${name}="([^"]*)"`).exec(tag)?.[1];

export function unnamedButtons(html: string): string[] {
  const bad: string[] = [];
  for (const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)) {
    const name = attr(m[1], "aria-label") || attr(m[1], "title") || strip(m[2]);
    if (!name) bad.push(m[0].slice(0, 120));
  }
  return bad;
}

/** icon-only = visible text is one or two symbol characters. Those must carry aria-label. */
export function iconOnlyWithoutLabel(html: string): string[] {
  const bad: string[] = [];
  for (const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)) {
    const text = strip(m[2]);
    if (text.length > 0 && text.length <= 2 && !/[A-Za-zÀ-ỹ0-9]/.test(text) && !attr(m[1], "aria-label")) bad.push(m[0].slice(0, 120));
  }
  return bad;
}

export function unlabelledControls(html: string): string[] {
  const forIds = new Set(Array.from(html.matchAll(/<label\b[^>]*\sfor="([^"]*)"/g), (m) => m[1]));
  const wrapped: [number, number][] = Array.from(html.matchAll(/<label\b[^>]*>[\s\S]*?<\/label>/g), (m) => [m.index!, m.index! + m[0].length]);
  const bad: string[] = [];
  for (const m of html.matchAll(/<(input|select|textarea)\b([^>]*)>/g)) {
    const a = m[2];
    if (/type="(hidden|submit|button)"/.test(a)) continue;
    const id = attr(a, "id");
    const inLabel = wrapped.some(([s, e]) => m.index! >= s && m.index! < e);
    if (!(attr(a, "aria-label") || attr(a, "aria-labelledby") || (id && forIds.has(id)) || inLabel)) bad.push(m[0].slice(0, 120));
  }
  return bad;
}

export const a11yProblems = (html: string) => [...unnamedButtons(html), ...iconOnlyWithoutLabel(html), ...unlabelledControls(html)];
