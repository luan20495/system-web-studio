"use client";
/**
 * Imperative replacements for `window.confirm` / `window.prompt` (no provider, no state in the caller), built on the shared `Modal` and the overlay stack:
 *
 *   if (await confirm({ title: "Xóa nguồn “billing-db”?", message: "Không thể hoàn tác.", confirmLabel: "Xóa nguồn", danger: true })) …
 *   const reason = await prompt({ title: "Từ chối mẫu", label: "Lý do (gửi cho tác giả)", multiline: true, required: true });   // string, or null on Cancel / Esc
 *
 * Each call mounts its own React root on `document.body` (so it works from any handler, outside any tree) and unmounts it when settled.
 * Behaviour: Esc / "Hủy" → `false` / `null`; a destructive confirmation (`danger`) focuses "Hủy" first, any other starts on the confirm button / the field;
 * focus returns to the element that opened it; `required` / `validate` show an inline, associated error instead of closing; the backdrop never settles a dialog
 * (a stray click must not answer a question). On the server (no `document`) they settle immediately with `false` / `null`.
 */
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { Modal } from "./Modal";

export type ConfirmOptions = { title: string; message?: ReactNode; confirmLabel?: string; cancelLabel?: string; /** destructive: red confirm button, focus starts on Cancel */ danger?: boolean };
export type PromptOptions = {
  title: string; label: string; message?: ReactNode; defaultValue?: string; placeholder?: string; multiline?: boolean; required?: boolean; maxLength?: number;
  confirmLabel?: string; cancelLabel?: string; /** return an error text to keep the dialog open, or null when valid */ validate?: (value: string) => string | null;
};

function mountDialog<T>(render: (done: (v: T) => void) => ReactNode, fallback: T): Promise<T> {
  if (typeof document === "undefined") return Promise.resolve(fallback);
  return new Promise<T>((resolve) => {
    const el = document.createElement("div"); el.setAttribute("data-xp-dialog-host", ""); document.body.appendChild(el);
    const root = createRoot(el); let settled = false;
    const done = (v: T) => { if (settled) return; settled = true; resolve(v); setTimeout(() => { root.unmount(); el.remove(); }, 0); };
    root.render(render(done));
  });
}

function ConfirmView({ o, done }: { o: ConfirmOptions; done: (v: boolean) => void }) {
  const id = useId();
  return (
    <Modal label={o.title} onClose={() => done(false)}>
      <form className="modalBody" onSubmit={(e) => { e.preventDefault(); done(true); }} aria-describedby={o.message ? `${id}-m` : undefined}>
        <h2>{o.title}</h2>
        {o.message ? <div id={`${id}-m`} className="xp-dialogMsg">{o.message}</div> : null}
        <div className="xp-footer">
          <button type="button" className="btn" data-autofocus={o.danger ? "" : undefined} onClick={() => done(false)}>{o.cancelLabel ?? "Hủy"}</button>
          <button type="submit" className={`btn primary${o.danger ? " danger" : ""}`} data-autofocus={o.danger ? undefined : ""}>{o.confirmLabel ?? "Xác nhận"}</button>
        </div>
      </form>
    </Modal>
  );
}

function PromptView({ o, done }: { o: PromptOptions; done: (v: string | null) => void }) {
  const id = useId(); const [value, setValue] = useState(o.defaultValue ?? ""); const [error, setError] = useState<string | null>(null);
  function submit(e: FormEvent) {
    e.preventDefault();
    const problem = o.required && !value.trim() ? "Hãy nhập nội dung." : o.validate?.(value) ?? null;
    if (problem) { setError(problem); return; }
    done(value);
  }
  const common = { id: `${id}-f`, "data-autofocus": "", value, maxLength: o.maxLength, placeholder: o.placeholder, "aria-invalid": error ? true : undefined, "aria-describedby": error ? `${id}-e` : o.message ? `${id}-m` : undefined, onChange: (e: { target: { value: string } }) => { setValue(e.target.value); if (error) setError(null); } };
  return (
    <Modal label={o.title} onClose={() => done(null)}>
      <form className="modalBody" onSubmit={submit} noValidate>
        <h2>{o.title}</h2>
        {o.message ? <div id={`${id}-m`} className="xp-dialogMsg">{o.message}</div> : null}
        <label className="field" htmlFor={`${id}-f`}><span>{o.label}</span>{o.multiline ? <textarea rows={4} {...common}/> : <input {...common}/>}</label>
        {error ? <p className="formError" role="alert" id={`${id}-e`}>{error}</p> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={() => done(null)}>{o.cancelLabel ?? "Hủy"}</button><button type="submit" className="btn primary">{o.confirmLabel ?? "Lưu"}</button></div>
      </form>
    </Modal>
  );
}

/** Promise<boolean>: true = confirmed; false = Cancel / Esc */
export function confirm(o: string | ConfirmOptions): Promise<boolean> {
  const opts: ConfirmOptions = typeof o === "string" ? { title: o } : o;
  return mountDialog<boolean>((done) => <ConfirmView o={opts} done={done}/>, false);
}
/** Promise<string | null>: the text, or null on Cancel / Esc */
export function prompt(o: PromptOptions): Promise<string | null> {
  return mountDialog<string | null>((done) => <PromptView o={o} done={done}/>, null);
}
