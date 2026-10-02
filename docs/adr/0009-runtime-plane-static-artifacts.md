# ADR 0009 — Runtime plane: immutable static artifacts in object storage behind a gateway
Status: **accepted and implemented** (2026-10-02). Increment 7.1.

## Context
`MockDeployProvider` serves nothing; the UI correctly says "Demo deployment". The spec asks for a first real target of
"static artifact → object storage → CDN/Nginx → public/private gateway" and *not* one container per simple site.

## Decision
1. **Artifact.** Publishing a `PAGE_SCHEMA` version renders it with the **same fixed renderer** used by the preview
   (`lib/schema-preview.ts`, data-only input — no generated code runs), in a small Node *render worker* (a separate process consuming
   a RabbitMQ queue, so the TypeScript renderer is not re-implemented in Kotlin and cannot drift). Output: `index.html`, the referenced
   project images copied as files (`asset://id` resolved to relative paths), a manifest (`files`, sizes, SHA-256) and the policy/security
   check results. The artifact is content-addressed: `artifacts/<project>/<sha256>/…` in a dedicated MinIO bucket, write-once.
   For `STATIC_APP` the build plane produces the same artifact format (ADR 0010).
2. **Deploy = pointer switch.** A deployment records `artifact_id`; RUNNING means "the site's current pointer is this artifact".
   Rollback = switch the pointer to an earlier artifact (instant, audited). Old artifacts are kept per retention policy.
3. **Gateway.** One Nginx (or Caddy) instance in front of MinIO (read-only service account, bucket not public):
   * hostname `<slug>.<sites-domain>` → current artifact prefix (looked up from a small map file or an internal lookup endpoint,
     regenerated on every pointer switch; no per-site container);
   * security headers on every response: strict CSP (`default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline';
     script-src 'none'` for page-schema sites — they contain no scripts), `X-Content-Type-Options`, `Referrer-Policy`, HSTS;
   * **private sites** (`site_visibility = PRIVATE`): gateway `auth_request` to the API, which checks the company SSO session (OIDC)
     and project/workspace membership; public sites are served without auth;
   * TLS and public exposure through the existing dedicated Cloudflare tunnel (wildcard hostname) — never the shared tunnel.
4. **Domains.** Sites use a registrable domain *different from the Studio's* so a site can never read Studio cookies; previews of
   generated code (later) use yet another domain.
5. **Not in 7.1:** contact-form submissions (the component already renders "not connected"; a form-collection endpoint is a separate
   feature), custom domains (data model ready, issuance later), CDN beyond Cloudflare's, analytics.

## Alternatives considered
* One container per site — rejected (spec; cost; attack surface).
* Public MinIO bucket — rejected (no headers, no private sites, bucket listing risks).
* Rendering in the browser and uploading — rejected (the client is untrusted).
* Re-implementing the renderer in Kotlin — rejected (two renderers drift; the preview must equal the published site).

## Acceptance criteria for 7.1
Published site reachable on its hostname with the exact preview markup; private site requires SSO + membership (tested both ways);
rollback switches content without a rebuild; artifacts immutable and hash-verified; CSP and headers asserted in E2E; a deleted
asset fails the policy check (as today); the UI drops the "Demo deployment" label only when this provider is active.

## Implementation (2026-10-02)
* Sites host: `https://sites.toolsmcp.uk/<slug>/` (path per site, see SOFTWARE_FACTORY_DESIGN §6); locally `http://127.0.0.1:18088/<slug>/`.
* `workers/render/server.ts` — render worker (Node, 127.0.0.1, `X-Render-Token`), compiled with the repo's TypeScript; uses `lib/schema-preview.ts`,
  which now also accepts artifact-relative image paths `assets/<uuid>.<ext>`.
* `publish/StaticSites.kt` — `StaticSiteBuilder` (index.html + referenced images, manifest with SHA-256, artifact id = SHA-256 of the manifest,
  write-once keys in the private `studio-artifacts` bucket via `integration/storage/ArtifactStore.kt`; a rendered `<script>` fails the build).
* `publish/SiteService.kt` — slugs, the `sites` pointer, `StaticSiteDeployProvider` (`DEPLOY_PROVIDER=static`; `mock` stays the default for
  tests/other setups and keeps the "Demo deployment" label); private-site access: single-use 60 s ticket (Redis) issued by
  `POST /api/v1/sites/{slug}/access-ticket` to a member signed in on the Studio, redeemed at `/_access` on the sites host for a host-only
  `site_session` cookie (HttpOnly, SameSite=Lax, Secure in production, 8 h); membership is re-checked on every request.
* `publish/SiteControllers.kt` — serving (`/sites/**`, own stateless security chain, GET/HEAD only, strict CSP `default-src 'none'` without
  scripts, `nosniff`, ETag/304, integrity check of every file against the manifest), rollback, unpublish, site info.
* **Caching decision:** responses are `public, no-cache` (revalidated each time, ETag → 304) or `private, no-store`. An E2E run showed that
  time-based caching let the public copy of a site that had just been switched to private keep being served; correctness first.
* `infra/sites-gateway/default.conf.template` — nginx-unprivileged, read-only container (`sites-gateway` in `compose.yml` and
  `compose.public.yml`), only slug paths, rewritten under `/sites/`, rate-limited; the API is not reachable through it.
* Migration V13 (`artifacts`, `sites`, `deployments.artifact_id`). Tests: `StaticSiteTests` (4), `e2e/factory-flow.mjs` (public site served by
  the gateway with headers, private site sign-in through the Studio, non-member refused, rollback, unpublish).
* Not done in 7.1: contact-form submissions, custom domains, purge-based edge caching, artifact retention/cleanup job.
