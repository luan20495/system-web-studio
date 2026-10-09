"use client";
import { useMemo } from "react";
import Link from "next/link";
import { useSession } from "../../session";
import { adminScope } from "../adminModel";
import { useA } from "../console/context";
import { relatedPeople, type PeopleKey } from "./peopleSections";

/** M-065: one line under the heading of each people screen that names the sibling screens and what each is for, so the four routes read as one area, not four look-alikes */
export function PeopleLinks({ current }: { current: PeopleKey }) {
  const { me } = useSession(); const A = useA();
  const others = useMemo(() => relatedPeople(current, adminScope(me)), [current, me]);
  if (!others.length) return null;
  return (
    <nav className="hint" aria-label="Các mục quản lý người liên quan" data-testid="people-links">
      Mục liên quan: {others.map((o, i) => <span key={o.key}>{i ? " · " : ""}<Link href={A(`/${o.key}`)}>{o.label}</Link> ({o.job})</span>)}
    </nav>
  );
}
