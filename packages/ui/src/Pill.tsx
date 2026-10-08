"use client";
/**
 * Pill: a status chip. The TEXT always says the status (colour is never the only carrier); the tone only helps scanning.
 * `value` is a status key looked up in PILL_TONE; `tone` overrides it. Callers used to pick a colour by borrowing a status key ("UNKNOWN" for grey,
 * "PUBLIC" for blue): warnings, risks and paid models came out grey (M-032). Semantic keys now exist (HIGH_RISK, PAID, AWAITING_REVIEW, CRITICAL, WARNING, INFO, PASS, SKIPPED ...)
 * and `tone` is the explicit escape hatch: <Pill value="x" tone="warn" label="Trả phí"/>.
 */
export type PillTone = "ok" | "warn" | "bad" | "info" | "muted";
const TONE: Record<string, PillTone> = {
  PENDING: "warn",
  HEALTHY: "ok", OK: "ok", ERROR: "bad", BAD_OUTPUT: "warn", RUNNING: "ok", ACTIVE: "ok", UPDATED: "ok", READY: "ok", true: "ok", PUBLIC: "info",
  DEGRADED: "warn", QUEUED: "warn", POLICY_CHECK: "warn", SECURITY_CHECK: "warn", BUILDING: "warn", DEPLOYING: "warn", NO_CHANGE: "muted", PRIVATE: "muted",
  UNAVAILABLE: "bad", FAILED: "bad", DISABLED: "bad", false: "bad", UNSUPPORTED: "warn", NOT_CONFIGURED: "muted", UNKNOWN: "muted", NOT_IMPLEMENTED: "muted", COMING_SOON: "muted",
  // semantic keys (M-032): what the chip MEANS, not which colour the caller wanted
  HIGH_RISK: "bad", MEDIUM_RISK: "warn", LOW_RISK: "muted", PAID: "warn", FREE: "ok", AWAITING_REVIEW: "warn", SUBMITTED: "warn", IN_REVIEW: "warn", PENDING_DELETE: "warn",
  CRITICAL: "bad", WARNING: "warn", INFO: "info", PASS: "ok", SKIPPED: "muted", HIGH: "bad", MEDIUM: "warn", LOW: "muted",
  SUSPENDED: "warn", DELETED: "bad", REVIEW: "warn", APPROVED: "ok", DEPRECATED: "bad", DRAFT: "muted", REJECTED: "bad", SUPERSEDED: "muted", COMPANY: "info", ARCHIVED: "muted"
};
export const PILL_TONE: Readonly<Record<string, PillTone>> = TONE;
export const pillTone = (value: string): PillTone => TONE[value] ?? "muted";
export function Pill({ value, label, tone }: { value: string; label?: string; tone?: PillTone }) { return <span className={`pill pill-${tone ?? pillTone(value)}`}>{label ?? value}</span>; }
