"use client";
/**
 * The labelled form field (M-067): ~48 screens hand-write `<label className="field"><span>Nhãn</span><input/></label>` and then wire hint / error ids by hand (or not at all).
 * This renders exactly that markup (so adopting it is pixel-identical), plus an optional hint inside the label and an error line after it, both tied to the control.
 *
 *   <Field label="Tên công ty"><input value={name} onChange={…}/></Field>
 *   <Field label="Mã công ty" hint="Chữ thường, số và dấu gạch" error={problems.slug}>
 *     {(a) => <input {...a} value={slug} onChange={…}/>}            // a = { id, "aria-describedby", "aria-invalid" }
 *   </Field>
 *
 * The plain-children form relies on the wrapping <label> for the name; the render-function form also gets an id plus aria-describedby / aria-invalid for the hint and the error.
 */
import { useId, type ReactNode } from "react";

export type FieldControlProps = { id: string; "aria-describedby"?: string; "aria-invalid"?: true };

export function Field({ label, hint, error, className, children }: {
  label: ReactNode;
  /** a short help line shown under the control, read with it */
  hint?: ReactNode;
  /** the validation message; shown after the field as an alert and marks the control invalid */
  error?: ReactNode;
  className?: string;
  children: ReactNode | ((control: FieldControlProps) => ReactNode);
}) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined; const errId = error ? `${id}-err` : undefined;
  const describedBy = [hintId, errId].filter(Boolean).join(" ") || undefined;
  const control = typeof children === "function" ? children({ id, "aria-describedby": describedBy, "aria-invalid": error ? true : undefined }) : children;
  return (
    <>
      <label className={className ? `field ${className}` : "field"} htmlFor={typeof children === "function" ? id : undefined}>
        <span>{label}</span>{control}{hint ? <small id={hintId}>{hint}</small> : null}
      </label>
      {error ? <p className="formError" role="alert" id={errId}>{error}</p> : null}
    </>
  );
}
