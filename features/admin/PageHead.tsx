import type { ReactNode } from "react";
export function PageHead({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return <div className="pageHead"><div><h1>{title}</h1>{sub ? <p>{sub}</p> : null}</div>{actions}</div>;
}
