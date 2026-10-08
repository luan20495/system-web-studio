"use client";
/**
 * A searchable person picker (WAI-ARIA combobox + listbox) for "choose one existing account": a search box, a short result list with avatar / name / username,
 * and, once chosen, a card with a "Bỏ chọn" button. `people` is whatever the host's search returned (the server filters); nothing is invented here.
 * Keyboard: ↑ ↓ move, Enter chooses, Esc clears the search.
 */
import { useId, useState } from "react";
import { Search, X } from "@xweb/ui";
import { initials, personLabel, type Person } from "./adminModel";

const Avatar = ({ p }: { p: Person }) => <span className="xp-avatar" aria-hidden="true">{initials(p)}</span>;

export function PersonPicker({ id, label, people, q, setQ, value, onChange, loading, placeholder, emptyText }: {
  id: string; label: string; people: Person[]; q: string; setQ: (q: string) => void; value: string; onChange: (id: string) => void; loading?: boolean; placeholder: string; emptyText: string;
}) {
  const list = useId(); const [active, setActive] = useState(0);
  const chosen = value ? people.find((p) => p.id === value) : undefined;
  if (value) return (
    <div className="xp-personChosen" id={id} data-testid="person-chosen">
      {chosen ? <Avatar p={chosen}/> : <span className="xp-avatar" aria-hidden="true">?</span>}
      <span className="xp-personText"><b>{chosen ? (chosen.displayName || chosen.username) : "Người dùng đã chọn"}</b><small>{chosen ? chosen.username : value.slice(0, 8)}</small></span>
      <button type="button" className="btn sm ghost xp-btnIcon" onClick={() => onChange("")} aria-label={`Bỏ chọn ${chosen ? personLabel(chosen, value) : "người dùng"}`}><X size={14} aria-hidden="true"/> Bỏ chọn</button>
    </div>);
  const rows = people.slice(0, 50); const at = Math.min(active, Math.max(0, rows.length - 1));
  function onKey(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") { e.preventDefault(); setActive(Math.min(rows.length - 1, at + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive(Math.max(0, at - 1)); }
    else if (e.key === "Enter" && rows[at]) { e.preventDefault(); onChange(rows[at].id); }
    else if (e.key === "Escape" && q) { e.preventDefault(); e.stopPropagation(); setQ(""); }
  }
  return (
    <div className="xp-people" id={id}>
      <span className="xp-search"><Search size={16} aria-hidden="true"/>
        <input role="combobox" aria-expanded="true" aria-controls={list} aria-autocomplete="list" aria-activedescendant={rows[at] ? `${list}-${at}` : undefined} aria-label={label} placeholder={placeholder} value={q} autoComplete="off"
          onChange={(e) => { setQ(e.target.value); setActive(0); }} onKeyDown={onKey}/>
        {q ? <button type="button" className="xp-clear" aria-label="Xóa tìm kiếm" onClick={() => setQ("")}><X size={14} aria-hidden="true"/></button> : null}
      </span>
      <p className="srOnly" role="status" aria-live="polite" data-testid="people-status">{loading ? "Đang tìm…" : rows.length ? `${rows.length} kết quả. Dùng mũi tên lên xuống để chọn, Enter để chọn người.` : emptyText}</p>
      <ul id={list} role="listbox" tabIndex={-1} aria-label="Kết quả tìm kiếm" className="xp-peopleList" aria-busy={loading || undefined}>
        {rows.length === 0 ? <li className="xp-peopleEmpty" role="presentation">{loading ? "Đang tìm…" : emptyText}</li> : rows.map((p, i) => (
          <li key={p.id} id={`${list}-${i}`} role="option" aria-selected={i === at} data-value={p.id} className={`xp-person${i === at ? " active" : ""}`} onMouseEnter={() => setActive(i)} onClick={() => onChange(p.id)}>
            <Avatar p={p}/><span className="xp-personText"><b>{p.displayName || p.username}</b><small>{p.username}</small></span><span className="xp-pick" aria-hidden="true">Chọn</span>
          </li>))}
      </ul>
    </div>
  );
}
