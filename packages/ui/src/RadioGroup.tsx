"use client";
/**
 * RadioGroup (M-030 / M-028): a choice among 2-6 options shown as cards (publish audience "Riêng tư / Công khai", scope pickers, ...).
 * NATIVE radios inside labels: the browser gives the group ONE tab stop, arrow keys, the `checked` state and form semantics for free; a screen reader reads
 * "Riêng tư, radio button, 1 of 2, checked" + the group's name. The old class-only buttons (`.publishChoice.selected`) had none of that.
 *
 *   <RadioGroup legend="Ai xem được ứng dụng?" value={v} onChange={setV} options={[{ value: "PRIVATE", label: "Riêng tư", hint: "Chỉ người được mời" }, ...]}/>
 */
import { useId, type ReactNode } from "react";

export type RadioOption<V extends string> = { value: V; label: string; hint?: ReactNode; icon?: ReactNode; disabled?: boolean };

export function RadioGroup<V extends string>({ legend, hideLegend = false, value, onChange, options, name, describedBy, className, disabled }: {
  /** the question the options answer; it is the group's accessible name */ legend: string; hideLegend?: boolean;
  value: V; onChange: (v: V) => void; options: RadioOption<V>[]; name?: string; describedBy?: string; className?: string; disabled?: boolean;
}) {
  const auto = useId(); const group = name ?? auto;
  return (
    <fieldset className={`xp-radioGroup${className ? ` ${className}` : ""}`} aria-describedby={describedBy} disabled={disabled}>
      <legend className={hideLegend ? "srOnly" : "xp-radioLegend"}>{legend}</legend>
      <div className="xp-radioList">
        {options.map((o) => (
          <label key={o.value} className={`xp-radioCard${o.value === value ? " selected" : ""}${o.disabled ? " disabled" : ""}`}>
            <input type="radio" name={group} value={o.value} checked={o.value === value} disabled={o.disabled} onChange={() => onChange(o.value)}/>
            <span className="xp-radioBody">
              {o.icon ? <span className="xp-radioIcon" aria-hidden="true">{o.icon}</span> : null}
              <span className="xp-radioText"><b>{o.label}</b>{o.hint ? <small>{o.hint}</small> : null}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
