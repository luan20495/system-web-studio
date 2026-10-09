"use client";
/**
 * M-065: the one "which company" control of the people screens (Công ty của tôi, Cơ cấu tổ chức, Nhân viên). Before, each screen carried its own copy of this select.
 * Shown only when the person administers more than one company; the list is the caller's OWN tenants from `/auth/me` (never typed). Local to the consoles; a candidate for @xweb/ui.
 */
export function TenantSwitch({ tenants, value, onChange, testId }: { tenants: readonly { id: string; name: string }[]; value: string; onChange: (id: string) => void; testId?: string }) {
  if (tenants.length < 2) return null;
  return (
    <label className="field xp-tenantSwitch"><span>Công ty</span>
      <select data-testid={testId} value={value} onChange={(e) => onChange(e.target.value)}>{tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select>
    </label>
  );
}
