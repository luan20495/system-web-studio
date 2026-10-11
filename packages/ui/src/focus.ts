/**
 * Focus helpers shared by the overlays (Modal, NavDrawer). Pure DOM: no React.
 * A control only counts as a Tab stop when a person could actually see and use it: not disabled, not `tabindex="-1"`, not hidden input,
 * not inside a visually-hidden (`.srOnly`) or `aria-hidden` / `inert` subtree. (The visually hidden native <select> that `Picker` mirrors its value into
 * is `tabindex=-1` + `.srOnly`: it used to be the initial focus of a dialog and one invisible Tab stop per cycle.)
 */
export const TABBABLE_SELECTOR = "a[href],button,input,select,textarea,summary,[tabindex]";

export function isTabbable(el: Element): el is HTMLElement {
  if (!(el instanceof HTMLElement)) return false;
  if (!el.matches(TABBABLE_SELECTOR)) return false;
  if (el.getAttribute("tabindex") === "-1") return false;
  if (el.matches(":disabled")) return false;
  if (el instanceof HTMLInputElement && el.type === "hidden") return false;
  if (el.closest(".srOnly,[aria-hidden='true'],[inert],[hidden]")) return false;
  return el.getClientRects().length > 0;
}

/** every Tab stop under `root`, in DOM order */
export function tabbables(root: ParentNode): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(TABBABLE_SELECTOR)).filter(isTabbable);
}

/** Wrap Tab / Shift+Tab inside `root`. Returns true when the key was handled (the caller must not do anything else with it). */
export function trapTab(e: KeyboardEvent, root: HTMLElement): boolean {
  if (e.key !== "Tab") return false;
  const items = tabbables(root);
  if (!items.length) { e.preventDefault(); return true; }
  const first = items[0], last = items[items.length - 1], at = document.activeElement as HTMLElement | null;
  if (e.shiftKey && (at === first || !at || !root.contains(at))) { e.preventDefault(); last.focus(); return true; }
  if (!e.shiftKey && (at === last || !at || !root.contains(at))) { e.preventDefault(); first.focus(); return true; }
  return false;
}

// ---- the element that OPENED a dialog (where focus returns on close) ---------------------------------------------------------------------------------------
// Two facts make "remember document.activeElement" not enough:
//  1. Safari / WebKit does not focus a button (or link, checkbox...) when it is CLICKED, so at the moment a dialog opens `document.activeElement` is <body>.
//  2. In the Studio a dialog is a ROUTE (`/projects/x/share`, `/versions`...): opening or closing it changes the path, and the app router gives every path its own page, so the whole workspace is
//     REMOUNTED (FQ-A11Y-02: after Escape focus was <body>, 6/6 builder dialogs, on the real stack). The opener element the dialog captured is then a DETACHED node.
// So the last control pressed (pointer, or Enter / Space) is remembered by what it IS (tag, test id, id, label, text; plus the menu button that offered it), not only as a node. A dialog returns focus
// to the node when it is still in the page; otherwise it waits (a few seconds at most) for the equivalent control to be rendered again and focuses that, unless the person has already done something else.
type Descriptor = { tag: string; testid: string | null; id: string | null; label: string | null; text: string; owner: Descriptor | null };
let lastTrigger: HTMLElement | null = null; let lastTriggerAt = 0;
const TRIGGER = "button,a[href],summary,[role=button],[role=menuitem],[role=combobox],[tabindex]";
const norm = (t: string | null | undefined) => (t ?? "").replace(/\s+/g, " ").trim().slice(0, 48);
function describe(el: HTMLElement, withOwner = true): Descriptor {
  const owner = withOwner ? document.querySelector<HTMLElement>('[aria-haspopup][aria-expanded="true"]') : null;   // the menu button that offered a menu item: where focus goes when the item itself is gone
  return { tag: el.tagName.toLowerCase(), testid: el.getAttribute("data-testid"), id: el.id || null, label: el.getAttribute("aria-label"), text: norm(el.textContent), owner: owner && owner !== el ? describe(owner, false) : null };
}
function visible(el: HTMLElement) { return el.getClientRects().length > 0 && !el.closest("[role=dialog],[aria-hidden='true'],[inert],[hidden]"); }
function resolve(d: Descriptor): HTMLElement | null {
  const same = (el: HTMLElement) => visible(el) && (d.testid ? el.getAttribute("data-testid") === d.testid : d.id ? el.id === d.id : d.label ? el.getAttribute("aria-label") === d.label : d.text !== "" && norm(el.textContent) === d.text);
  const hit = Array.from(document.querySelectorAll<HTMLElement>(d.tag)).find(same);
  return hit ?? (d.owner ? resolve(d.owner) : null);
}
if (typeof document !== "undefined") {
  const remember = (e: Event) => { const t = (e.target as Element | null)?.closest?.(TRIGGER); lastTrigger = t instanceof HTMLElement ? t : null; lastTriggerAt = Date.now(); if (lastTrigger) triggerDescriptors.set(lastTrigger, describe(lastTrigger)); cancelPending(); };
  document.addEventListener("pointerdown", remember, true);
  document.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") remember(e); }, true);
}
const triggerDescriptors = new WeakMap<HTMLElement, Descriptor>();
/** the control a dialog should return focus to: the focused element, else the control last pressed (WebKit click; or the same control re-rendered after a route change), else null */
export function dialogOpener(): HTMLElement | null {
  if (typeof document === "undefined") return null;
  const a = document.activeElement;
  if (a instanceof HTMLElement && a !== document.body) return a;
  if (!lastTrigger || Date.now() - lastTriggerAt > 10_000) return null;   // only a press that just happened opened this dialog; a stale one would send focus to an unrelated control
  if (lastTrigger.isConnected) return Date.now() - lastTriggerAt < 1500 ? lastTrigger : null;
  const d = triggerDescriptors.get(lastTrigger); return d ? resolve(d) : null;
}
let pending: { cancel: () => void } | null = null;
function cancelPending() { pending?.cancel(); pending = null; }
/** Give focus back to `opener` when a dialog closes. A node that is still in the page gets it now; a detached one (the page was re-rendered by the route change that closed the dialog) is looked up again
 *  by what it was, for at most 4 s, and the wait is dropped the moment the person presses or types something else. */
export function restoreOpener(opener: HTMLElement | null | undefined) {
  if (typeof document === "undefined" || !opener) return;
  if (opener.isConnected) { opener.focus?.(); return; }
  const d = triggerDescriptors.get(opener) ?? describe(opener, false);
  cancelPending();
  let stop = false; const t0 = Date.now();
  const tick = () => {
    if (stop) return;
    const el = resolve(d);
    if (el) { el.focus(); pending = null; return; }
    if (Date.now() - t0 > 4000) { pending = null; return; }
    requestAnimationFrame(tick);
  };
  pending = { cancel: () => { stop = true; } };
  requestAnimationFrame(tick);
}
