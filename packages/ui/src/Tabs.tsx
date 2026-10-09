"use client";
/**
 * Tabs (M-028): WAI-ARIA tabs pattern. role=tablist / tab / tabpanel; ONE tab stop (roving tabindex: the selected tab is tabbable, the others are reached with ← → Home End);
 * selection follows focus (automatic activation, the right default for tabs that only switch a panel already in the page).
 *
 *   const id = useId();
 *   <Tabs label="Chi tiết ứng dụng" idBase={id} value={tab} onChange={setTab} tabs={[{ value: "info", label: "Thông tin" }, { value: "members", label: "Thành viên", count: 4 }]}/>
 *   <TabPanel idBase={id} value={tab}>…</TabPanel>
 *
 * `aria-controls` is written only when there IS a panel to point at (`panels`, default true; pass `panels={false}` for tabs that re-render the page body — a filter-like switch —
 * where no single tabpanel element exists). If the choice changes the URL (a route), do not use tabs at all: use plain links with `aria-current="page"`.
 */
import { useRef, type KeyboardEvent, type ReactNode } from "react";

export type TabItem<V extends string> = { value: V; label: ReactNode; disabled?: boolean; /** a number shown after the label (read as "Thành viên, 4") */ count?: number };
export const tabId = (idBase: string, value: string) => `${idBase}-tab-${value}`;
export const panelId = (idBase: string, value: string) => `${idBase}-panel-${value}`;

/** next enabled index for an arrow / Home / End key; wraps around. null = not a navigation key. Pure, so it is unit-tested. */
export function nextTabIndex(key: string, from: number, enabled: boolean[], rtl = false): number | null {
  const n = enabled.length; if (!n || !enabled.some(Boolean)) return null;
  const first = enabled.findIndex(Boolean); const last = enabled.lastIndexOf(true);
  if (key === "Home") return first; if (key === "End") return last;
  const fwd = key === (rtl ? "ArrowLeft" : "ArrowRight"), back = key === (rtl ? "ArrowRight" : "ArrowLeft");
  if (!fwd && !back) return null;
  for (let step = 1; step <= n; step++) { const i = (from + (fwd ? step : -step) + n * 2) % n; if (enabled[i]) return i; }
  return null;
}

export function Tabs<V extends string>({ label, labelledBy, idBase, value, onChange, tabs, panels = true, className }: {
  /** accessible name of the tab list (or `labelledBy`) */ label?: string; labelledBy?: string;
  /** shared prefix of the tab / panel ids (use `useId()`) */ idBase: string; value: V; onChange: (v: V) => void; tabs: TabItem<V>[]; panels?: boolean; className?: string;
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const cur = tabs.findIndex((t) => t.value === value);
  const tabbable = cur >= 0 && !tabs[cur].disabled ? cur : tabs.findIndex((t) => !t.disabled);
  function onKey(e: KeyboardEvent<HTMLDivElement>) {
    const from = tabs.findIndex((t) => tabId(idBase, t.value) === (document.activeElement as HTMLElement | null)?.id);
    const i = nextTabIndex(e.key, from < 0 ? Math.max(0, cur) : from, tabs.map((t) => !t.disabled));
    if (i === null) return;
    e.preventDefault(); refs.current[i]?.focus(); onChange(tabs[i].value);
  }
  return (
    <div className={`tabs xp-tabs${className ? ` ${className}` : ""}`} role="tablist" aria-label={labelledBy ? undefined : label} aria-labelledby={labelledBy} onKeyDown={onKey}>
      {tabs.map((t, i) => {
        const on = t.value === value;
        return (
          <button key={t.value} ref={(el) => { refs.current[i] = el; }} type="button" role="tab" id={tabId(idBase, t.value)} className={on ? "active" : undefined}
            aria-selected={on} aria-controls={panels && on ? panelId(idBase, t.value) : undefined} tabIndex={i === tabbable ? 0 : -1} disabled={t.disabled} onClick={() => onChange(t.value)}>
            {t.label}{t.count !== undefined ? <span className="xp-tabCount"> <span className="srOnly">, </span>{t.count}</span> : null}
          </button>
        );
      })}
    </div>
  );
}

/** the panel of the selected tab. Reachable by Tab when it has no focusable content of its own (tabIndex 0), per the APG. */
export function TabPanel({ idBase, value, children, className, focusable = true }: { idBase: string; value: string; children: ReactNode; className?: string; focusable?: boolean }) {
  return <div role="tabpanel" id={panelId(idBase, value)} aria-labelledby={tabId(idBase, value)} tabIndex={focusable ? 0 : undefined} className={className}>{children}</div>;
}
