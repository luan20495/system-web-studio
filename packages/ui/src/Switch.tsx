"use client";
import { useId, type ReactNode } from "react";

/** An on/off setting. A real `role="switch"` button (Space / Enter toggle it), labelled by its text, `aria-checked` = the state. Replaces a lone checkbox for settings that apply at once. */
export function Switch({ checked, onChange, label, hint, disabled, icon }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; hint?: ReactNode; disabled?: boolean; icon?: ReactNode }) {
  const id = useId();
  return (
    <div className={`xp-switchRow${disabled ? " disabled" : ""}`}>
      {icon ? <span className="xp-rowIcon" aria-hidden="true">{icon}</span> : null}
      <div className="xp-switchText"><label htmlFor={id}>{label}</label>{hint ? <small>{hint}</small> : null}</div>
      <button id={id} type="button" role="switch" aria-checked={checked} className={`xp-switch${checked ? " on" : ""}`} disabled={disabled} onClick={() => onChange(!checked)}><span className="xp-knob"/></button>
    </div>
  );
}
