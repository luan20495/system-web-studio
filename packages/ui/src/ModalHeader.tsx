import type { ReactNode } from "react";
/** Dialog header: an icon tile (optional: a title-only dialog passes none), the title (the dialog's accessible name comes from the Modal's label) and a subtitle that says what the dialog does. */
export function ModalHeader({ icon, title, subtitle }: { icon?: ReactNode; title: string; subtitle?: string }) {
  return (
    <div className="xp-modalHead">
      {icon ? <span className="xp-headIcon" aria-hidden="true">{icon}</span> : null}
      <div><h2>{title}</h2>{subtitle ? <p>{subtitle}</p> : null}</div>
    </div>
  );
}
