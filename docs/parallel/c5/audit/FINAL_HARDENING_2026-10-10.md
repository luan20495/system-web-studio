# C5 final hardening pass (2026-10-10, base integration/v2 @ fad4a7b)
Evidence classes as before; nothing here relabels HARNESS as REAL_BACKEND. Details of what was found / fixed / run: `C5_NEXT_SESSION_HANDOFF.md` §9. Visual proof: `~/.xweb-evidence/c5-final-2026-10-10/` (outside git).

| Area | Result | Class |
|---|---|---|
| Builder top bar, failed save | P1 FIXED: "Thử lại" was covered by the mode tabs at 1366-1440 px | REAL_BACKEND (S2/S6/S9) + HARNESS (`TOPBAR-SAVE-ERR`) |
| Data panel focus after the guided form | P2 FIXED (race on `requestAnimationFrame`) | HARNESS (`data-binding` 5/5) |
| Rollback flows P03-P06 | flow updated to the in-app confirmation | REAL_BACKEND PASS |
| Responsive 360-1920, axe | axe critical + serious 0; overflow 0; small targets 0; no missing focus ring | HARNESS (1422 + 36 visits) |
| Browsers | CHROMIUM full harness PASS; WEBKIT PASS (admin 183/184: clipboard permission, engine limit; studio-wave2 needs `WEBKIT_PLAIN_TAB=1`; `sanity` is Chromium-only: CDP); FIREFOX BLOCKED_TOOLING; Safari NOT_TESTED | HARNESS |
| Business flows | see handoff §9 (onboarding PL01, tenant AD01-03, org ORG01 31/31, permission USER01 / SUPER01 / ADMIN01, build, publish, visitor, rollback / unpublish) | REAL_BACKEND |
| Data-backed / action / workflow in the real stack | BLOCKED by C2 / C3 / C0 / C4 (E2E-06 / 07 / 08 / 09 / 14), PD01 needs a real source | not frontend |

## Addendum: C6 P2 closure (FQ-UI-01, FQ-A11Y-02)
| Defect | Result | Class |
|---|---|---|
| FQ-UI-01 `/admin/employees` clipped with long names | FIXED (CSS: grid tracks and selects may shrink) | HARNESS `org-hardening` FQ-UI-01 @9 widths (fails 5/9 without the fix), REAL_BACKEND `E2E-UI01` 9/9 |
| FQ-A11Y-02 builder dialogs lose focus after Escape | FIXED in the shared focus primitive (`restoreOpener`) | HARNESS `shared-ui` (fails 4/4 without the fix), REAL_BACKEND `E2E-A11Y01` 15/15 (16 checks failed 15 before, measured at the previous head) |
Details: `C5_NEXT_SESSION_HANDOFF.md` §11.
