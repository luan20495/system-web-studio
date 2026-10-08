// @class: tooling
// Shared measurements of the UI audit tools (scripts/ui-audit.mjs, ui-state-matrix.mjs, ui-keyboard.mjs): what a reviewer would notice on ONE rendered screen. HARNESS or REAL stack: the code is the same.
// MEASURE runs inside the page (self-contained, serialised by Playwright). focusProbe / axe / VIEWPORTS run in Node. Nothing here changes the page except pressing Tab (focusProbe).
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
export const AXE = require.resolve("axe-core/axe.min.js");

/** the responsive matrix of the final gate (wide to narrow) */
export const VIEWPORTS = [1920, 1440, 1280, 1024, 768, 600, 430, 390, 360];
export const viewportOf = (w) => ({ width: w, height: w >= 1000 ? 900 : 800 });

export const MEASURE = () => {
  const text = document.body.innerText; const isIconish = (c) => /[←-⇿⌀-⏿■-➿⬀-⯿]/.test(c);
  const glyphs = [...new Set([...text].filter(isIconish))].join("");
  const replacement = text.includes("�"); const mojibake = /â€|Ã[\u0080-¿]|Â[ -¿]|áº|á»/.test(text);
  const entities = /&(amp|lt|gt|nbsp|hellip|mdash|#\d+);/.test(text);
  const vw = window.innerWidth; const overflowX = document.documentElement.scrollWidth - vw;
  const vis = (e) => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none"; };
  const leafs = [...document.body.querySelectorAll("*")].filter((e) => vis(e) && ![...e.childNodes].some((n) => n.nodeType === 1) && e.textContent.trim() && !e.closest("svg,iframe,[aria-hidden=true],.srOnly"));
  const inScrollX = (e) => { for (let p = e.parentElement; p && p !== document.body; p = p.parentElement) { const cs = getComputedStyle(p); if (/(auto|scroll)/.test(cs.overflowX) && p.scrollWidth > p.clientWidth + 1) return true; } return false; };   /* text inside a horizontally scrollable region (a wide table) is reachable by scrolling it: not a spill */
  const spill = leafs.filter((e) => e.getBoundingClientRect().right > vw + 1 && !inScrollX(e)).slice(0, 4).map((e) => e.textContent.trim().slice(0, 40));
  const clipped = leafs.filter((e) => { const cs = getComputedStyle(e); return (cs.overflow === "hidden" || cs.overflowX === "hidden") && cs.textOverflow !== "ellipsis" && e.scrollWidth > e.clientWidth + 2; }).slice(0, 4).map((e) => e.textContent.trim().slice(0, 40));
  const tiny = leafs.filter((e) => parseFloat(getComputedStyle(e).fontSize) < 11).length;
  const imgs = [...document.images].filter((i) => i.complete && i.naturalWidth === 0).map((i) => i.src.slice(-40));
  const name = (e) => (e.getAttribute("aria-label") || e.getAttribute("aria-labelledby") || e.getAttribute("title") || e.textContent || [...(e.labels ?? [])].map((l) => l.textContent).join(" ") || "").trim();
  const noName = [...document.querySelectorAll("button,[role=button],a[href]")].filter((e) => vis(e) && !name(e) && !e.querySelector("img[alt]:not([alt=''])")).slice(0, 4).map((e) => e.outerHTML.slice(0, 80));
  const noLabel = [...document.querySelectorAll("input:not([type=hidden]),select,textarea")].filter((e) => vis(e) && !e.getAttribute("aria-label") && !e.getAttribute("aria-labelledby") && !e.closest("label") && !(e.id && document.querySelector(`label[for="${CSS.escape(e.id)}"]`))).slice(0, 4).map((e) => e.outerHTML.slice(0, 80));
  const norm = (t) => t.toLowerCase().replace(/\s+/g, " ").trim();
  const textOf = (n) => (n.nodeType === 3 ? n.textContent : n.nodeType === 1 && n.getAttribute("aria-hidden") !== "true" ? [...n.childNodes].map(textOf).join(" ") : ""); const visibleText = (e) => norm(textOf(e)).replace(/[^\p{L}\p{N}]+/gu, " ").trim(); /* element boundaries count as a space, punctuation is ignored, as in the accessible-name comparison */
  const nameMismatch = [...document.querySelectorAll("button,[role=button],a[href],[role=tab],[role=menuitem],[role=radio],[role=checkbox],[role=combobox]")].filter((e) => vis(e) && e.getAttribute("aria-label")).filter((e) => { const t = visibleText(e); return t && /[\p{L}\p{N}]/u.test(t) && !norm(e.getAttribute("aria-label")).replace(/[^\p{L}\p{N}]+/gu, " ").trim().includes(t); }).slice(0, 5).map((e) => `${visibleText(e).slice(0, 30)} ≠ ${e.getAttribute("aria-label").slice(0, 40)}`);
  const small = [...document.querySelectorAll("button,[role=button],[role=tab],a.btn,input[type=checkbox],input[type=radio],select")].filter((e) => { if (!vis(e) || e.classList.contains("srOnly")) return false; /* .srOnly = visually hidden mirror (1 px clip), not a pointer target */ const r = e.getBoundingClientRect(); return r.width < 24 || r.height < 24; }).slice(0, 6).map((e) => `${e.tagName.toLowerCase()}.${(e.className || "").toString().slice(0, 24)} ${Math.round(e.getBoundingClientRect().width)}x${Math.round(e.getBoundingClientRect().height)} ${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 24)}`);
  const native = [...document.querySelectorAll("select:not(.srOnly)")].filter(vis).length;
  const h1 = document.querySelectorAll("h1").length; const main = document.querySelectorAll("main").length;

  // ---- final-gate checks (S4 wave 2): controls that cannot be reached or hit, and extra size rules
  const desc = (e) => `${e.tagName.toLowerCase()}${e.id ? "#" + e.id : ""}${typeof e.className === "string" && e.className ? "." + e.className.trim().split(/\s+/).slice(0, 2).join(".") : ""}[${(name(e) || "").slice(0, 24)}]`;
  const isSkip = (e) => /skip/i.test(typeof e.className === "string" ? e.className : "") || /^bỏ qua/i.test((e.textContent || "").trim());
  const modal = document.querySelector("[role=dialog][aria-modal=true],.adminModal,.modal");   // an open modal legitimately covers the page behind it: only its own controls are judged
  const ctl = [...document.querySelectorAll("button,a[href],input:not([type=hidden]),select,textarea,summary,[role=button],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=switch],[role=link],[tabindex]:not([tabindex='-1'])")]
    .filter((e) => vis(e) && !e.disabled && !e.closest("[aria-hidden=true],[inert]") && !e.classList.contains("srOnly") && !isSkip(e) && (!modal || modal.contains(e)));
  const clipState = (e) => {                       // "ok" | "scrolled" (reachable by scrolling its container) | "clipped" (hidden by overflow:hidden) | "offscreen"
    const r = e.getBoundingClientRect(); let state = "ok";
    for (let p = e.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      const cs = getComputedStyle(p); if (cs.overflowX === "visible" && cs.overflowY === "visible") continue;
      const pr = p.getBoundingClientRect(); const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      if (cx < pr.left || cx > pr.right || cy < pr.top || cy > pr.bottom) { if (/(auto|scroll)/.test(cs.overflowX + cs.overflowY)) return "scrolled"; else return "clipped"; }   // inside a scroller: reachable by scrolling it (outer clips then apply to the scrolled view, not checked)
    }
    if (state === "ok" && (r.right <= 0 || r.left >= vw)) return "offscreen";
    return state;
  };
  const clippedCtl = ctl.filter((e) => ["clipped", "offscreen"].includes(clipState(e))).slice(0, 6).map((e) => `${desc(e)} ${clipState(e)}`);
  const covered = ctl.filter((e) => {
    if (clipState(e) !== "ok") return false; const r = e.getBoundingClientRect(); const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (cx < 0 || cx >= vw || cy < 0 || cy >= innerHeight) return false;
    const t = document.elementFromPoint(cx, cy); if (!t || e.contains(t) || t.contains(e)) return false;
    if (e.labels && [...e.labels].some((l) => l.contains(t))) return false; if (e.closest("label")?.contains(t)) return false; return true;
  }).slice(0, 6).map((e) => { const r = e.getBoundingClientRect(); const t = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return `${desc(e)} covered by ${t ? desc(t) : "?"}`; });
  const inline = (e) => getComputedStyle(e).display === "inline";
  const smallMore = [...document.querySelectorAll("input:not([type=hidden]):not([type=checkbox]):not([type=radio]),textarea,a[href]")].filter((e) => { if (!vis(e) || e.classList.contains("srOnly") || isSkip(e) || (e.tagName === "A" && inline(e))) return false; const r = e.getBoundingClientRect(); return r.width < 24 || r.height < 24; }).slice(0, 6).map((e) => `${desc(e)} ${Math.round(e.getBoundingClientRect().width)}x${Math.round(e.getBoundingClientRect().height)}`);
  const controls = ctl.length;
  return { controls, clippedCtl, covered, smallMore, glyphs, replacement, mojibake, entities, overflowX, spill, clipped, tiny, imgs, noName, noLabel, nameMismatch, small, native, h1, main, title: document.title, textLen: text.trim().length, h: document.documentElement.scrollHeight };
};
export const AXE_TAGS = (process.env.AUDIT_AXE_TAGS ?? "wcag2a,wcag2aa,wcag21a,wcag21aa,wcag22aa,best-practice").split(",");
export async function axe(page) {
  try { await page.addScriptTag({ path: AXE }); return await page.evaluate(async (tags) => (await window.axe.run(document, { runOnly: tags, resultTypes: ["violations"] })).violations.map((v) => ({ id: v.id, impact: v.impact, n: v.nodes.length, sel: v.nodes[0]?.target?.join(" ").slice(0, 70), nodes: v.nodes.slice(0, 6).map((x) => `${x.target.join(" ").slice(0, 80)} :: ${(x.html ?? "").replace(/\s+/g, " ").slice(0, 110)}`) })), AXE_TAGS); } catch (e) { return [{ id: "axe-failed", impact: "n/a", n: 0, sel: String(e).slice(0, 60), nodes: [] }]; }
}


/**
 * Keyboard probe: from the top of the page press Tab `n` times (default 10) and record for every stop whether it has a VISIBLE focus indicator (outline / ring / box-shadow), whether a sticky or fixed
 * element covers it (WCAG 2.4.11 Focus Not Obscured: fully covered = fail, partly = warn) and whether it is inside the viewport. Focus that falls to <body> while controls exist is reported.
 */
export async function focusProbe(page, n = 10) {
  await page.evaluate(() => { document.activeElement?.blur?.(); window.scrollTo(0, 0); }).catch(() => undefined);
  const stops = [];
  for (let i = 0; i < n; i++) {
    await page.keyboard.press("Tab").catch(() => undefined); await page.waitForTimeout(70);
    const s = await page.evaluate(() => {
      const el = document.activeElement; if (!el || el === document.body || el === document.documentElement) return { body: true };
      const name = (el.getAttribute("aria-label") || el.textContent || el.getAttribute("placeholder") || el.getAttribute("title") || el.tagName).trim().replace(/\s+/g, " ").slice(0, 28);
      const d = `${el.tagName.toLowerCase()}[${name}]`; if (el.tagName === "IFRAME") return { frame: true, d };
      const cs = getComputedStyle(el); const drawn = (e) => { const c = getComputedStyle(e); return (c.outlineStyle !== "none" && parseFloat(c.outlineWidth) > 0 && !/rgba\(0, 0, 0, 0\)/.test(c.outlineColor)) || (!!c.boxShadow && c.boxShadow !== "none"); };
      let ring = drawn(el); for (let a = el.parentElement, i = 0; !ring && a && i < 3; a = a.parentElement, i++) if (a.matches(":focus-within") && drawn(a)) ring = true;   /* a wrapper may carry the indicator */
      const fv = el.matches(":focus-visible");
      const r = el.getBoundingClientRect(); const vw = innerWidth, vh = innerHeight;
      const pts = [[0.5, 0.5], [0.15, 0.2], [0.85, 0.2], [0.15, 0.8], [0.85, 0.8]].map(([fx, fy]) => [Math.min(vw - 1, Math.max(0, r.left + r.width * fx)), Math.min(vh - 1, Math.max(0, r.top + r.height * fy))]);
      let blocked = 0; let by = "";
      for (const [x, y] of pts) { const t = document.elementFromPoint(x, y); if (t && !el.contains(t) && !t.contains(el)) { for (let p = t; p && p !== document.body; p = p.parentElement) { const pos = getComputedStyle(p).position; if (pos === "fixed" || pos === "sticky") { blocked++; by = `${p.tagName.toLowerCase()}${typeof p.className === "string" && p.className ? "." + p.className.trim().split(/\s+/)[0] : ""}`; break; } } } }
      const inView = r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw;
      return { d, fv, ring: !!ring, blocked, of: pts.length, by, inView };
    }).catch(() => ({ body: true }));
    stops.push(s);
  }
  const real = stops.filter((s) => !s.body);
  return {
    stops: real.length,
    noIndicator: real.filter((s) => !s.frame && !s.ring).map((s) => s.d),
    obscuredFully: real.filter((s) => !s.frame && s.blocked >= s.of).map((s) => `${s.d} by ${s.by}`),
    obscuredPartly: real.filter((s) => !s.frame && s.blocked > 0 && s.blocked < s.of).map((s) => `${s.d} by ${s.by}`),
    offscreen: real.filter((s) => !s.frame && !s.inView).map((s) => s.d),
    lostToBody: stops.filter((s) => s.body).length,
  };
}
