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
