# ADR 0002 — Portal choice is navigation intent, never authorization
Status: accepted (2026-10-02) · still in force under [ADR 0022](0022-frontend-monorepo-three-deployments.md): the three portals do not change that choosing a portal is intent, not authorization

The login screen asks "Admin Console" or "Builder Studio". The choice is kept in `sessionStorage` (`factory-portal`) and used only by
`resolvePostLogin()` (`features/routing.ts`) to pick a destination. It is never sent to the server. Admin access requires the live
`users.system_admin AND enabled` value, checked on every `/api/v1/admin/**` request by `AdminGuard` (not the login-time session
snapshot). Studio access requires a workspace membership. Tested: employee choosing Admin → `/auth/no-access`; forged portal value →
still no console; direct `/admin` → screen denied and API 403; removing `system_admin` in the DB takes effect on the next request.
No AI_ADMIN/BUILDER roles were invented: the existing role model is used.
