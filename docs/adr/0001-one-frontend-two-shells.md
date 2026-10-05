# ADR 0001 — One frontend, two shells, client-side routing
Status: accepted (2026-10-02)

**Context.** The product now has two user areas (Admin Console, Builder Studio) plus auth screens, and needs deep links
(`/studio/projects/{id}/design`). The same Next.js source must still produce the static mock build (GitHub Pages) without a server.

**Decision.** One Next.js app with a single optional catch-all route `app/[[...slug]]/page.tsx`. In http mode every path renders it
and `components/app/AppEntry.tsx` routes on `usePathname()` to `/login`, `/auth/*`, `/admin/*` (`features/admin`) or `/studio/*`
(`features/studio`). `generateStaticParams` returns only the root, so the static export still builds (mock mode renders the demo).
API, OAuth2 and OIDC-callback rewrites are `beforeFiles` so the catch-all never shadows `/login/oauth2/code/oidc`.

**Consequences.** One design system, one API client, one session provider. Route guards on the client only choose a screen; the
backend authorises every call. Code is organised by feature folders (`features/auth|admin|studio`) without a risky repo move.
