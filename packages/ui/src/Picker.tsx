"use client";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Check, ChevronDown } from "./icons";

export type PickerOption<V extends string> = { value: V; label: string; hint?: string; icon?: ReactNode; disabled?: boolean };

/**
 * A dropdown picker with a logo / hint per option (WAI-ARIA listbox, keyboard: ↑ ↓ Home End Enter Space Esc, type-ahead by first letter).
 * A visually hidden NATIVE <select> mirrors the value: forms, autofill, screen-reader shortcuts and existing scripts that do `getByLabel(label).selectOption(...)` keep working.
 */
export function Picker<V extends string>({ label, buttonLabel, value, options, onChange, disabled, describedBy }: { /** id of the element that explains / reports an error for this field */ describedBy?: string; label: string; /** accessible name of the visible button (default: label); the hidden native select keeps `label` */ buttonLabel?: string; value: V; options: PickerOption<V>[]; onChange: (v: V) => void; disabled?: boolean }) {
  const id = useId(); const [open, setOpen] = useState(false); const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null); const btn = useRef<HTMLButtonElement>(null);
  const cur = options.find((o) => o.value === value) ?? options[0];
  const enabled = options.map((o, i) => (o.disabled ? -1 : i)).filter((i) => i >= 0);
  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", away); return () => document.removeEventListener("mousedown", away);
  }, [open]);
  const openAt = () => { setActive(Math.max(0, options.findIndex((o) => o.value === value))); setOpen(true); };
  const choose = (i: number) => { const o = options[i]; if (!o || o.disabled) return; onChange(o.value); setOpen(false); btn.current?.focus(); };
  const move = (d: number) => { const at = enabled.indexOf(active); setActive(enabled[Math.min(enabled.length - 1, Math.max(0, at + d))] ?? active); };
  function onKey(e: React.KeyboardEvent) {
    if (disabled) return;
    if (!open) { if (["ArrowDown", "ArrowUp", "Enter", " "].includes(e.key)) { e.preventDefault(); openAt(); } return; }
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); setOpen(false); btn.current?.focus(); }
    else if (e.key === "ArrowDown") { e.preventDefault(); move(1); } else if (e.key === "ArrowUp") { e.preventDefault(); move(-1); }
    else if (e.key === "Home") { e.preventDefault(); setActive(enabled[0]); } else if (e.key === "End") { e.preventDefault(); setActive(enabled[enabled.length - 1]); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); choose(active); }
    else if (e.key === "Tab") setOpen(false);
    else if (e.key.length === 1) { const i = options.findIndex((o, k) => !o.disabled && o.label.toLowerCase().startsWith(e.key.toLowerCase()) && k !== active); if (i >= 0) setActive(i); }
  }
  return (
    <div className="xp-picker" ref={root} onKeyDown={onKey}>
      <label htmlFor={`${id}-native`} className="xp-pickerLabel">{label}</label>
      <select id={`${id}-native`} className="srOnly" value={value} tabIndex={-1} disabled={disabled} onChange={(e) => onChange(e.target.value as V)}>{options.map((o) => <option key={o.value} value={o.value} disabled={o.disabled}>{o.label}</option>)}</select>
      <button ref={btn} type="button" className="xp-pickerBtn" role="combobox" aria-describedby={describedBy} aria-haspopup="listbox" aria-expanded={open} aria-controls={`${id}-list`} aria-label={`${buttonLabel ?? label}: ${cur?.label ?? ""}`} disabled={disabled} onClick={() => (open ? setOpen(false) : openAt())} data-testid="picker-button">
        {cur?.icon}<span className="xp-pickerCur"><b>{cur?.label}</b>{cur?.hint ? <small>{cur.hint}</small> : null}</span><ChevronDown size={16} aria-hidden="true"/>
      </button>
      {open ? (
        <ul id={`${id}-list`} className="xp-pickerList" role="listbox" aria-label={buttonLabel ?? label} aria-activedescendant={`${id}-o${active}`} data-testid="picker-list">
          {options.map((o, i) => (
            <li key={o.value} id={`${id}-o${i}`} role="option" aria-selected={o.value === value} aria-disabled={o.disabled || undefined} className={`xp-opt${i === active ? " active" : ""}${o.disabled ? " disabled" : ""}`}
              onMouseEnter={() => !o.disabled && setActive(i)} onClick={() => choose(i)} data-value={o.value}>
              {o.icon}<span className="xp-pickerCur"><b>{o.label}</b>{o.hint ? <small>{o.hint}</small> : null}</span>{o.value === value ? <Check size={16} aria-hidden="true"/> : null}
            </li>))}
        </ul>) : null}
    </div>
  );
}
