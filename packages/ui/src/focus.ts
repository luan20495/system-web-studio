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
// Safari / WebKit does not focus a button (or link, checkbox...) when it is CLICKED, so at the moment a dialog opens `document.activeElement` is <body> and closing the dialog could not
// give focus back to the control the person pressed (WCAG 2.4.3). The last pointer target is remembered (capture phase, no behaviour change) and used only when nothing real has the focus.
let lastPointerTrigger: HTMLElement | null = null; let lastPointerAt = 0;
if (typeof document !== "undefined") {
  document.addEventListener("pointerdown", (e) => { const t = (e.target as Element | null)?.closest?.("button,a[href],summary,[role=button],[role=combobox],[tabindex]"); lastPointerTrigger = t instanceof HTMLElement ? t : null; lastPointerAt = Date.now(); }, true);
}
/** the control a dialog should return focus to: the focused element, else (WebKit click) the control last pressed, else null */
export function dialogOpener(): HTMLElement | null {
  if (typeof document === "undefined") return null;
  const a = document.activeElement;
  if (a instanceof HTMLElement && a !== document.body) return a;
  return lastPointerTrigger?.isConnected && Date.now() - lastPointerAt < 1500 ? lastPointerTrigger : null;   // only a press that just happened opened this dialog; a stale one would send focus to an unrelated control
}
